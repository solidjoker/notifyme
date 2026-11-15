# -*- coding: utf-8 -*-
# Copyright (c) 2026 solidjoker
# SPDX-License-Identifier: MIT
"""端云上报通道的字段契约守卫：/weixin（消息）与 /analysis（会话分析）。

排队通道早就有 server/test_queue_contract.py 守着（读 QueueStateCore.kt 与
storage._normalize_queue_task 做静态比对），这两条上报通道一直没人守：

  - 端上多写一个字段 -> 服务端静默丢弃 -> 数据对不上，控制台看不出来；
  - 服务端多要一个字段 -> 端侧没写 -> 整条被拒（逐条拒收）-> 通道堵死；
  - 键名写成驼峰（isGroup）-> 服务端 REQUIRED_FIELDS 判缺字段 -> 整批被拒。

所以这里直接读 Kotlin + Python 源码做静态比对，再用真 HTTP 请求把
「客户端形状的载荷」原样发给服务端，钉住它真的被收下。

三桶口径（每个键必须落在且只落在一桶里）：
  1. 服务端消费：拆列或整块 JSON blob 落库；
  2. 端上写但服务端故意不消费：写进白名单，并写清为什么；
  3. 服务端要但端上没写：不允许（端上少写 = 那条记录被拒）。

改协议时先改这里：白名单与「客户端形状」的载荷必须同步更新，
否则 CI 直接红——这正是本文件存在的意义。
"""
import os
import re
import sys
import tempfile

import pytest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import app as app_module  # noqa: E402
from app import app as flask_app  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
ANDROID = os.path.join(ROOT, "app", "src", "main", "java", "com", "notifyme", "android")
MESSAGE_STORE = os.path.join(ANDROID, "MessageStore.kt")
ANALYSIS_STORE = os.path.join(ANDROID, "AnalysisStore.kt")
SYNC_WORKER = os.path.join(ANDROID, "SyncWorker.kt")
APP_PY = os.path.join(HERE, "app.py")
ROUTES_PY = os.path.join(HERE, "routes.py")

# 端上写、但服务端**故意不消费**的键。必须显式列出：多一个就说明
# 有人在端上偷偷加了字段，服务端会静默丢掉它。
MESSAGE_DROPPED = {
    # 展示快照（App 中文名）：服务端按 pkg 映射标签，不存快照，
    # 否则同一个 App 会出现两个名字（端上改名 + 服务端改名打架）。
    "appLabel",
}
ANALYSIS_DROPPED = {
    # 端侧本地状态：这条记录被服务端拒收过几次 / 首次被拒时间 /
    # 是否被隔离。被服务端拒掉的记录根本进不了服务端，
    # 这三个键是端侧自己的账，服务端不需要也不该存。
    "rejected_count",
    "first_rejected_at",
    "quarantined",
}

PUT_RE = r'put\("([A-Za-z0-9_]+)"'
REMOVE_RE = r'remove\("([A-Za-z0-9_]+)"'
ITEM_KEY_RE = r'item(?:\.get\(\s*|\[\s*)"([A-Za-z0-9_]+)"'
S1_KEY_RE = r's1(?:\.get\(\s*|\[\s*)"([A-Za-z0-9_]+)"'
NA_KEY_RE = r'na(?:\.get\(\s*|\[\s*)"([A-Za-z0-9_]+)"'


def _read(path):
    with open(path, "r", encoding="utf-8") as fh:
        # 统一换行符：Kotlin 与 Python 源码都是 CRLF，
        # 不归一化的话按行定位会切错。
        return fh.read().replace(chr(13) + chr(10), chr(10))


def _lines(path):
    return _read(path).split(chr(10))


def _indent(line):
    return len(line) - len(line.lstrip())


def _find(lines, marker, before=None):
    """找最后（或 before 之前最后）一个包含 marker 的行号。"""
    idxs = [i for i, line in enumerate(lines) if marker in line]
    if before is not None:
        idxs = [i for i in idxs if i < before]
    if not idxs:
        raise AssertionError("源码里找不到: " + marker)
    return idxs[-1]


def _block(lines, start, container_indent):
    """取 start 之后、缩进大于 container_indent 的那一段行。"""
    out = []
    for i in range(start + 1, len(lines)):
        line = lines[i]
        if not line.strip():
            continue
        if _indent(line) <= container_indent:
            break
        out.append(line)
    return out


def _put_paths(lines, start, container_indent):
    """扫描一个 JSONObject().apply { ... } 块，返回每个 put 的对象路径。

    路径长度 1 = 顶层键；长度 2 = 顶层对象的下一层。
    用「缩进 + 容器栈」而不是固定缩进取键：条件块里的 put
    （source / appLabel / redacted / s2 ...）缩进更深，
    但它们仍然是顶层键；嵌套 apply 里的键才属于子对象。
    """
    stack = []  # (缩进, 对象名)；None 表示只是条件块，不是对象
    paths = []
    for i in range(start + 1, len(lines)):
        line = lines[i]
        if not line.strip():
            continue
        cur = _indent(line)
        if cur <= container_indent:
            break
        while stack and cur <= stack[-1][0]:
            stack.pop()
        m = re.search(PUT_RE, line)
        if m:
            labels = [lab for _, lab in stack if lab]
            paths.append(tuple(labels + [m.group(1)]))
        if line.rstrip().endswith("{"):
            stack.append((cur, m.group(1) if m else None))
    return paths


def _fn_body(text, name):
    """取 Python 源码里一个函数体（按下一个 def 切）。"""
    i = text.find("def " + name + "(")
    assert i >= 0, "找不到函数: " + name
    j = text.find(chr(10) + "def ", i + 1)
    return text[i:j if j > 0 else len(text)]


def _insert_columns(text, table):
    """取 INSERT INTO <table> 的列名集合。"""
    m = re.search(r"INSERT INTO " + table + r"\s*\((.*?)\)\s*VALUES", text, re.S)
    assert m, "找不到 INSERT INTO " + table
    flat = m.group(1).replace(chr(10), " ")
    return set(x.strip() for x in flat.split(",") if x.strip())


def _client_record_keys(kt_lines, marker):
    """抠出一个 Kotlin 数据类的 toJson 顶层键 + 子对象键。"""
    ci = _find(kt_lines, marker)
    fi = _find(kt_lines, "fun toJson(): JSONObject = JSONObject().apply {", before=ci)
    body = _block(kt_lines, fi, 4)
    paths = _put_paths(kt_lines, fi, 4)
    top = [p[0] for p in paths if len(p) == 1]
    children = {}
    grand = {}
    for p in paths:
        if len(p) == 2:
            children.setdefault(p[0], set()).add(p[1])
        elif len(p) == 3:
            grand.setdefault(p[0] + "." + p[1], set()).add(p[2])
    return set(top), children, grand


MSG_STORE = _lines(MESSAGE_STORE)
ANA_STORE = _lines(ANALYSIS_STORE)
SYNC = _lines(SYNC_WORKER)
APP_TEXT = _read(APP_PY)
ROUTES_TEXT = _read(ROUTES_PY)

# 端上：ChatMessage.toJson 的顶层键
MSG_JSON_KEYS, _, _ = _client_record_keys(MSG_STORE, 'put("sender"')
# 端上：AnalysisCaseRecord.toJson 的顶层键 + s1 / s2 的子键
ANA_JSON_KEYS, ANA_CHILDREN, ANA_GRAND = _client_record_keys(ANA_STORE, 'put("case_id"')

# 端上：SyncWorker 里两条通道对 toJson 的增删（上报协议是蛇形命名）
_msg_blk = _block(SYNC, _find(SYNC, "val items = pending.mapIndexed"), 8)
_ana_blk = _block(SYNC, _find(SYNC, "val entries = records.map"), 8)
MSG_REMOVED = set(re.findall(REMOVE_RE, chr(10).join(_msg_blk)))
MSG_ADDED = set(re.findall(PUT_RE, chr(10).join(_msg_blk)))
ANA_REMOVED = set(re.findall(REMOVE_RE, chr(10).join(_ana_blk)))
ANA_ADDED = set(re.findall(PUT_RE, chr(10).join(_ana_blk)))

# 端上最终上报的键集合
MSG_CLIENT_KEYS = (MSG_JSON_KEYS - MSG_REMOVED) | MSG_ADDED
ANA_CLIENT_KEYS = (ANA_JSON_KEYS - ANA_REMOVED) | ANA_ADDED

# 服务端：必填字段（app.py 的 REQUIRED_FIELDS）
_req = re.search(r"REQUIRED_FIELDS = \{(.*?)\n\}", APP_TEXT, re.S)
assert _req, "找不到 REQUIRED_FIELDS"
REQUIRED_FIELDS = {}
for _k, _v in re.findall(r'"([A-Za-z0-9_]+)"\s*:\s*([^,\n]+)', _req.group(1)):
    _v = _v.strip()
    if _v.startswith("("):
        _v = "number"
    REQUIRED_FIELDS[_k] = _v

# 服务端：两条通道真正读到的键
MSG_SERVER_READ = set(re.findall(ITEM_KEY_RE, _fn_body(ROUTES_TEXT, "receive_weixin")))
MSG_SERVER_READ |= set(REQUIRED_FIELDS.keys())
ANA_SERVER_READ = set(re.findall(ITEM_KEY_RE, _fn_body(ROUTES_TEXT, "receive_analysis")))
ANA_S1_READ = set(re.findall(S1_KEY_RE, _fn_body(ROUTES_TEXT, "receive_analysis")))
ANA_NA_READ = set(re.findall(NA_KEY_RE, _fn_body(ROUTES_TEXT, "receive_analysis")))

# 服务端落库列（用来钉「appLabel 不入库」「raw_json 不入库」）
MSG_TABLE_COLUMNS = _insert_columns(_fn_body(ROUTES_TEXT, "receive_weixin"), "messages")
ANA_TABLE_COLUMNS = _insert_columns(_fn_body(ROUTES_TEXT, "receive_analysis"), "analyses")

# 端上「总是写」的键（条件写的另算）：缺这些就是端侧少写 = 会被拒
MSG_ALWAYS = {"sender", "text", "timestamp", "conversation", "pkg", "is_group"}
ANA_ALWAYS = {
    "case_id", "conversation", "window_end", "message_count", "analyzed_at",
    "protocol", "pkg", "s1", "escalated", "s2", "forks", "filtered",
}

# 端上「条件写」的键：有值才写，缺了不影响入库
MSG_OPTIONAL = {"source", "appLabel"}
ANA_OPTIONAL = {
    "redacted", "redaction_rules", "redaction_hits", "todo_text", "todo_due",
    "rejected_count", "first_rejected_at", "quarantined",
}


# ---- 消息通道（/weixin）----


def test_message_client_keys_are_pinned():
    """端上最终上报的键集合钉死：多一个就说明有人偷偷加过字段。"""
    assert MSG_CLIENT_KEYS == {
        "sender", "text", "timestamp", "conversation",
        "source", "pkg", "appLabel", "is_group",
    }, "端上消息上报的键集合变了: " + ", ".join(sorted(MSG_CLIENT_KEYS))


def test_message_required_fields_are_written_by_client():
    missing = sorted(set(REQUIRED_FIELDS) - MSG_CLIENT_KEYS)
    assert not missing, "服务端必填但端上没写（那条会被拒）: " + ", ".join(missing)
    assert set(REQUIRED_FIELDS) <= MSG_ALWAYS, (
        "服务端必填的字段必须是端上「总是写」的，否则那条记录会被拒")


def test_message_client_keys_are_known_to_server():
    unknown = sorted(MSG_CLIENT_KEYS - MSG_SERVER_READ - MESSAGE_DROPPED)
    assert not unknown, ("端上写了服务端既不读、也不在白名单里的字段"
                       "（会被静默丢弃）: " + ", ".join(unknown))


def test_message_dropped_keys_are_explicit():
    dropped = sorted(MSG_CLIENT_KEYS - MSG_SERVER_READ)
    assert dropped == sorted(MESSAGE_DROPPED), (
        "服务端不消费的键变了，必须同步白名单并写清原因: " + ", ".join(dropped))


def test_message_channel_uses_snake_case_is_group():
    """上报协议是蛇形命名：本地存的 isGroup 在上报前必须换掉。"""
    assert "is_group" in MSG_CLIENT_KEYS
    assert "isGroup" not in MSG_CLIENT_KEYS
    assert "isGroup" in MSG_REMOVED, "端上不再 remove(isGroup)"
    assert "is_group" in MSG_ADDED, "端上不再 put(is_group)"
    assert REQUIRED_FIELDS.get("is_group") == "bool"


def test_message_required_field_types_match_client_payload():
    assert REQUIRED_FIELDS == {
        "sender": "str",
        "text": "str",
        "timestamp": "number",
        "conversation": "str",
        "is_group": "bool",
    }, "服务端必填字段的类型变了: " + repr(REQUIRED_FIELDS)


def test_app_label_is_not_stored_in_messages_table():
    """appLabel 是展示快照：端上照写，服务端照丢（按 pkg 映射标签）。"""
    assert "appLabel" in MSG_CLIENT_KEYS
    assert "appLabel" not in MSG_TABLE_COLUMNS
    assert "app_label" not in MSG_TABLE_COLUMNS


# ---- 分析通道（/analysis）----


def test_analysis_client_keys_are_pinned():
    assert ANA_CLIENT_KEYS == {
        "case_id", "conversation", "window_end", "message_count",
        "analyzed_at", "protocol", "pkg", "s1", "escalated", "s2",
        "forks", "filtered", "redacted", "redaction_rules", "redaction_hits",
        "todo_text", "todo_due", "rejected_count", "first_rejected_at",
        "quarantined",
    }, "端上分析上报的键集合变了: " + ", ".join(sorted(ANA_CLIENT_KEYS))


def test_analysis_server_consumed_keys_are_written_by_client():
    missing = sorted(ANA_SERVER_READ - ANA_CLIENT_KEYS)
    assert not missing, "服务端要但端上没写（那条会被拒）: " + ", ".join(missing)


def test_analysis_client_keys_are_known_to_server():
    unknown = sorted(ANA_CLIENT_KEYS - ANA_SERVER_READ - ANALYSIS_DROPPED)
    assert not unknown, ("端上写了服务端既不读、也不在白名单里的字段"
                       "（会被静默丢弃）: " + ", ".join(unknown))


def test_analysis_dropped_keys_are_explicit():
    dropped = sorted(ANA_CLIENT_KEYS - ANA_SERVER_READ)
    assert dropped == sorted(ANALYSIS_DROPPED), (
        "服务端不消费的键变了，必须同步白名单并写清原因: " + ", ".join(dropped))


def test_analysis_raw_json_is_never_uploaded():
    """raw_json 是端侧防御存档（含原文），绝不能出设备。"""
    assert "raw_json" in ANA_JSON_KEYS
    assert "raw_json" in ANA_REMOVED
    assert "raw_json" not in ANA_CLIENT_KEYS
    assert "raw_json" not in ANA_TABLE_COLUMNS


def test_analysis_s2_is_always_present():
    """toJson 只在 escalated 时写 s2，上报前必须补 null 对齐接口字段。"""
    assert "s2" in ANA_JSON_KEYS, "toJson 不再写 s2"
    assert "s2" in ANA_ADDED, "上报前不再 put(s2, null)"
    assert "s2" in ANA_CLIENT_KEYS
    assert "s2" in ANA_SERVER_READ
    assert ANA_CHILDREN.get("s2") == {"summary", "due_time", "suggested_action", "tasks"}


def test_analysis_s1_keys_match_server_expectation():
    assert ANA_CHILDREN.get("s1") == {"need_action", "importance",
                                        "due_window", "topic", "confidence"}
    # 服务端只拆 need_action / importance 两列，其余整块存 s1 blob
    assert ANA_S1_READ == {"need_action", "importance"}
    assert "s1" in ANA_TABLE_COLUMNS
    for col in ("due_window", "topic", "confidence", "prob"):
        assert col not in ANA_TABLE_COLUMNS


def test_analysis_need_action_shape_matches_server():
    assert ANA_GRAND.get("s1.need_action") == {"value", "prob"}
    assert ANA_NA_READ == {"value"}, "服务端只按 value 判定 need_action 列"


def test_analysis_case_id_and_conversation_are_required():
    """服务端最小校验：case_id / conversation 必须是非空字符串。"""
    for key in ("case_id", "conversation"):
        assert key in ANA_CLIENT_KEYS
    assert {"case_id", "conversation"} <= ANA_ALWAYS, (
        "服务端必填的字段必须是端上「总是写」的")


def test_analysis_always_written_keys_are_pinned():
    """端上「总是写」的键不能漂移（少写一个 = 那条记录被拒）。"""
    assert MSG_ALWAYS <= MSG_CLIENT_KEYS
    assert ANA_ALWAYS <= ANA_CLIENT_KEYS
    assert MSG_OPTIONAL <= MSG_CLIENT_KEYS
    assert ANA_OPTIONAL <= ANA_CLIENT_KEYS
    assert (MSG_ALWAYS | MSG_OPTIONAL) == MSG_CLIENT_KEYS
    assert (ANA_ALWAYS | ANA_OPTIONAL) == ANA_CLIENT_KEYS


# ---- 运行级：客户端形状的载荷真的能被服务端收下 ----


@pytest.fixture()
def api():
    """隔离数据目录的 Flask test client（与 test_bool_readout.py 同款）。"""
    tmp = tempfile.mkdtemp(prefix="notifyme_contract_")
    old = (app_module.DATA_DIR, app_module.DB_PATH, app_module.JSONL_PATH,
           app_module.ADVISOR_PATH, app_module.TOKEN, app_module.ALLOW_NO_AUTH,
           app_module._db_ready, app_module._queue_cleanup_at)
    app_module.DATA_DIR = tmp
    app_module.DB_PATH = os.path.join(tmp, "messages.db")
    app_module.JSONL_PATH = os.path.join(tmp, "messages.jsonl")
    app_module.ADVISOR_PATH = os.path.join(tmp, "advisor_reports.json")
    app_module.TOKEN = ""
    app_module.ALLOW_NO_AUTH = "1"
    app_module._db_ready = False
    app_module._queue_cleanup_at = 0
    app_module.init_db()
    app_module._auth_fail_events.clear()
    yield flask_app.test_client()
    (app_module.DATA_DIR, app_module.DB_PATH, app_module.JSONL_PATH,
     app_module.ADVISOR_PATH, app_module.TOKEN, app_module.ALLOW_NO_AUTH,
     app_module._db_ready, app_module._queue_cleanup_at) = old
    app_module._db_ready = False


# 客户端形状的载荷（键与端上 toJson + SyncWorker 变换后完全一致）
CLIENT_MESSAGE = {
    "sender": "张三",
    "text": "在吗",
    "timestamp": 1790000000000,
    "conversation": "项目群",
    "source": "android-notification",
    "pkg": "com.tencent.mm",
    "appLabel": "微信",
    "is_group": False,
}

CLIENT_ANALYSIS_ALWAYS = {
    "case_id": "case-1",
    "conversation": "项目群",
    "window_end": 1790000000000,
    "message_count": 10,
    "analyzed_at": 1790000000000,
    "protocol": "v1",
    "pkg": "com.tencent.mm",
    "s1": {
        "need_action": {"value": True, "prob": 0.82},
        "importance": 4.0,
        "due_window": "today",
        "topic": "work",
        "confidence": 0.7,
    },
    "escalated": False,
    "s2": None,
    "forks": [],
    "filtered": False,
}

CLIENT_ANALYSIS_FULL = dict(CLIENT_ANALYSIS_ALWAYS)
CLIENT_ANALYSIS_FULL.update({
    "redacted": True,
    "redaction_rules": ["name"],
    "redaction_hits": 2,
    "todo_text": "催一下",
    "todo_due": "today",
    "rejected_count": 2,
    "first_rejected_at": 1789999000000,
    "quarantined": True,
})


def test_message_payload_in_client_shape_is_accepted(api):
    resp = api.post("/weixin", json=[dict(CLIENT_MESSAGE)])
    body = resp.get_json()
    assert resp.status_code == 200
    assert body["ok"] is True
    assert body["received"] == 1
    assert body["invalid"] == 0


def test_message_payload_with_camel_case_is_group_is_rejected(api):
    """写成 isGroup（本地存储的驼峰）-> 服务端判缺字段 -> 整条被拒。"""
    item = dict(CLIENT_MESSAGE)
    item.pop("is_group")
    item["isGroup"] = False
    resp = api.post("/weixin", json=[item])
    body = resp.get_json()
    assert resp.status_code == 207
    assert body["received"] == 0
    assert body["invalid"] == 1
    assert body["rejected"] == [0]


def test_message_payload_missing_sender_is_rejected(api):
    item = dict(CLIENT_MESSAGE)
    item.pop("sender")
    resp = api.post("/weixin", json=[item])
    body = resp.get_json()
    assert resp.status_code == 207
    assert body["invalid"] == 1


def test_analysis_payload_in_client_shape_is_accepted(api):
    """连端侧本地状态键（被拒计数 / 隔离标记）一起上报，服务端照收。"""
    resp = api.post("/analysis", json=[dict(CLIENT_ANALYSIS_FULL)])
    body = resp.get_json()
    assert resp.status_code == 200
    assert body["ok"] is True
    assert body["received"] == 1
    assert body["invalid"] == 0


def test_analysis_payload_without_dropped_keys_is_accepted(api):
    """端上「总是写」的那一套就够入库：条件键与本地状态键都可缺省。"""
    resp = api.post("/analysis", json=[dict(CLIENT_ANALYSIS_ALWAYS)])
    body = resp.get_json()
    assert resp.status_code == 200
    assert body["received"] == 1
    assert body["invalid"] == 0


def test_analysis_payload_missing_case_id_is_rejected(api):
    item = dict(CLIENT_ANALYSIS_ALWAYS)
    item.pop("case_id")
    resp = api.post("/analysis", json=[item])
    body = resp.get_json()
    assert resp.status_code == 207
    assert body["received"] == 0
    assert body["invalid"] == 1
    assert body["rejected"] == [0]
    # 没有 case_id 时没法按 id 回传，只能靠下标
    assert body.get("rejected_ids") == []


def test_analysis_payload_unknown_extra_key_is_accepted(api):
    """服务端只认自己那几把钥匙，多带的键一律忽略（不拒收）。"""
    item = dict(CLIENT_ANALYSIS_ALWAYS)
    item["client_only_note"] = "端侧本地字段"
    resp = api.post("/analysis", json=[item])
    body = resp.get_json()
    assert resp.status_code == 200
    assert body["received"] == 1


def test_message_payload_with_unknown_extra_key_is_accepted(api):
    item = dict(CLIENT_MESSAGE)
    item["client_only_note"] = "端侧本地字段"
    resp = api.post("/weixin", json=[item])
    body = resp.get_json()
    assert resp.status_code == 200
    assert body["received"] == 1
