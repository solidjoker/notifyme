# -*- coding: utf-8 -*-
"""控制台前端「归类」纯函数核心（server/console_classify.js）的真逻辑单测。

为什么不在 Python 里重写一份规则：server/console_classify.js 是控制台归类规则的
单一事实源（浏览器内联 + node 直接 require 两端共用）。Python 复刻版必然与它漂移，
所以这里让 pytest 通过 subprocess 调 node 跑 server/test_console_classify_runner.js：

    pytest -> subprocess(node runner) -> require('./console_classify.js') -> 真实纯函数

断言全部落在 runner 吐出的 JSON 上（成功时 {"ok": true, "out": ...}），
所以测的是浏览器里跑的那份真逻辑，而不是一份镜像实现。

覆盖维度：来源 App 归一化与标签 / 会话复合身份 / 发送者归一化与分组 /
来源 App 分区 / 重要度档位与待办排序分区 / 顾问建议类型分组 / 会话卡两级折叠分组
（groupMessages：按日期 / 按发送者）/ 排队任务按设备分组 / runner 协议本身。

环境：需要 node（或 nodejs）。找不到时默认 skip；环境变量 REQUIRE_NODE=1 时
必须 fail（让 CI 立刻暴露问题，而不是静默跳过）。
"""
import json
import os
import shutil
import subprocess

import pytest

HERE = os.path.dirname(os.path.abspath(__file__))
RUNNER_PATH = os.path.join(HERE, "test_console_classify_runner.js")

# 契约常量：测试侧只留「跨用例都要用」的几个，其余从被测模块读，避免两边漂移
PKG_WX = "com.tencent.mm"
SEP = "\u0001"


@pytest.fixture(scope="session")
def node_exe():
    """定位 node 可执行文件；找不到时按 REQUIRE_NODE 决定 skip 还是 fail。"""
    exe = shutil.which("node") or shutil.which("nodejs")
    if not exe:
        if os.environ.get("REQUIRE_NODE") == "1":
            pytest.fail(
                "环境变量 REQUIRE_NODE=1，但 PATH 里找不到 node/nodejs："
                "控制台归类纯函数单测必须能跑真逻辑（server/console_classify.js）。"
            )
        pytest.skip("node 不可用，跳过前端归类纯函数单测")
    return exe


@pytest.fixture(scope="session")
def call(node_exe):
    """返回调用器：call("fn", *args) -> 该纯函数的真实返回值（JSON 结构）。"""

    def _call(fn, *args):
        payload = {"fn": fn, "args": list(args)}
        proc = subprocess.run(
            [node_exe, RUNNER_PATH],
            input=json.dumps(payload, ensure_ascii=False),
            capture_output=True,
            text=True,
            encoding="utf-8",
            timeout=30,
        )
        assert proc.returncode == 0, (
            f"runner 调用失败：fn={fn} 退出码={proc.returncode} stderr={proc.stderr!r}"
        )
        data = json.loads(proc.stdout)
        assert data.get("ok") is True, f"runner 未返回 ok：fn={fn} stdout={proc.stdout!r}"
        return data.get("out")

    return _call


def _run_raw(node_exe, payload_text):
    """直接跑 runner（不走 ok 协议），返回 CompletedProcess。"""
    return subprocess.run(
        [node_exe, RUNNER_PATH],
        input=payload_text,
        capture_output=True,
        text=True,
        encoding="utf-8",
        timeout=30,
    )


# --------------------------------------------------------------------------
# 常量与协议
# --------------------------------------------------------------------------


def test_version_is_v1(call):
    """VERSION 常量为 "v1"（前端归类规则的版本号）。"""
    version = call("__get__", "VERSION")
    assert version == "v1", f"VERSION 应为 v1，实际 {version!r}"


def test_constants_exposed(call):
    """模块导出的常量齐全：默认包名、会话分隔符、两类优先序、重要度标签。"""
    assert call("__get__", "PKG_WX") == PKG_WX, f"PKG_WX 应为 {PKG_WX}"
    assert call("__get__", "SEP") == SEP, f"SEP 应为 U+0001，实际 {call('__get__', 'SEP')!r}"
    assert call("__get__", "ADVISOR_TYPE_ORDER") == [
        "watchlist",
        "threshold",
        "prompt",
        "other",
    ], "顾问建议类型优先序不对"
    assert call("__get__", "IMPORTANCE_ORDER") == ["high", "mid", "low", ""], (
        "重要度分区顺序不对：%r" % (call("__get__", "IMPORTANCE_ORDER"),)
    )
    labels = call("__get__", "IMPORTANCE_LABELS")
    assert labels == {
        "high": "高重要度",
        "mid": "中重要度",
        "low": "低重要度",
        "": "未标重要度",
    }, f"IMPORTANCE_LABELS 不对：{labels!r}"


def test_runner_rejects_unknown_function(node_exe):
    """runner 收到未知函数名 -> stdout {"error": "unknown_fn"}，退出码 2。"""
    proc = _run_raw(node_exe, json.dumps({"fn": "noSuchFn", "args": []}))
    assert proc.returncode == 2, f"未知函数名应退出码 2，实际 {proc.returncode}"
    data = json.loads(proc.stdout)
    assert data == {"error": "unknown_fn"}, f"未知函数名应返回 unknown_fn，实际 {data!r}"


def test_runner_rejects_bad_json(node_exe):
    """runner 收到非 JSON 输入 -> stdout {"error": "bad_json"}，退出码 2。"""
    proc = _run_raw(node_exe, "not-json")
    assert proc.returncode == 2, f"坏 JSON 应退出码 2，实际 {proc.returncode}"
    data = json.loads(proc.stdout)
    assert data == {"error": "bad_json"}, f"坏 JSON 应返回 bad_json，实际 {data!r}"


# --------------------------------------------------------------------------
# 来源 App 归一化 / 标签 / 会话复合身份
# --------------------------------------------------------------------------


def test_norm_pkg_falls_back_to_wechat(call):
    """normPkg：缺省 / 空值 -> 微信默认包名；有值则原样透传。"""
    assert call("normPkg", None) == PKG_WX, "normPkg(None) 应回落到微信包名"
    assert call("normPkg", "") == PKG_WX, "normPkg('') 应回落到微信包名"
    assert call("normPkg", "com.x") == "com.x", "normPkg 应原样透传 com.x"


def test_pkg_label_hit_and_miss(call):
    """pkgLabel：命中映射用映射值；未命中原样返回包名。"""
    labels = {PKG_WX: "微信", "com.x": "示例"}
    assert call("pkgLabel", PKG_WX, labels) == "微信", "命中映射应返回展示名"
    assert call("pkgLabel", "com.other", labels) == "com.other", "未命中应原样返回包名"
    assert call("pkgLabel", None, labels) == "微信", "缺省包名应按微信取展示名"


def test_ck_of_joins_pkg_and_conversation(call):
    """ckOf：会话复合身份 = 包名 + 分隔符 + 会话名。"""
    got = call("ckOf", {"pkg": "com.x", "conversation": "群A"})
    assert got == "com.x" + SEP + "群A", f"ckOf 拼接过：{got!r}"


def test_ck_of_null_conversation_is_empty_tail(call):
    """ckOf：会话名为 null 时尾部为空串（只留包名 + 分隔符）。"""
    got = call("ckOf", {"pkg": "com.x", "conversation": None})
    assert got == "com.x" + SEP, f"ckOf(null 会话) 应为 包名+分隔符，实际 {got!r}"


# --------------------------------------------------------------------------
# 发送者归一化
# --------------------------------------------------------------------------


def test_sender_key_trims_whitespace(call):
    """senderKey：去首尾空白。"""
    got = call("senderKey", "  李四  ")
    assert got == "李四", f"应去掉首尾空白，实际 {got!r}"


def test_sender_key_drops_zero_width(call):
    """senderKey：去零宽字符（U+200B 等不可见字符）。"""
    got = call("senderKey", "王\u200b五")
    assert got == "王五", f"应去掉零宽字符，实际 {got!r}"


def test_sender_key_drops_ascii_colon_prefix(call):
    """senderKey：去「名字:」半角冒号前缀（来源把发言人写在冒号前）。"""
    got = call("senderKey", "  张三: 你好 ")
    assert got == "张三", f"应去掉「张三:」前缀，实际 {got!r}"


def test_sender_key_drops_fullwidth_colon_prefix(call):
    """senderKey：去「名字：」全角冒号前缀。"""
    got = call("senderKey", "张三：你好")
    assert got == "张三", f"应去掉「张三：」前缀，实际 {got!r}"


def test_sender_key_blank_is_unknown(call):
    """senderKey：全空 -> 「未知发送者」。"""
    assert call("senderKey", "") == "未知发送者", "空串应归为未知发送者"
    assert call("senderKey", "   ") == "未知发送者", "纯空白应归为未知发送者"
    assert call("senderKey", None) == "未知发送者", "null 应归为未知发送者"


# --------------------------------------------------------------------------
# 按发送者分组
# --------------------------------------------------------------------------


def test_group_by_sender_counts_and_messages(call):
    """groupBySender：3 条消息 2 个发送者 -> 2 组，count 与 messages 长度正确。"""
    groups = call(
        "groupBySender",
        [
            {"sender": "张三", "timestamp": 300, "id": "m1"},
            {"sender": "李四", "timestamp": 200, "id": "m2"},
            {"sender": "张三", "timestamp": 100, "id": "m3"},
        ],
    )
    assert len(groups) == 2, f"应分 2 组，实际 {len(groups)} 组：{groups!r}"
    by_key = {g["key"]: g for g in groups}
    assert set(by_key) == {"张三", "李四"}, f"分组键不对：{sorted(by_key)}"
    assert by_key["张三"]["count"] == 2, f"张三 count 应为 2，实际 {by_key['张三']['count']}"
    assert len(by_key["张三"]["messages"]) == 2, "张三 messages 长度应为 2"
    assert by_key["李四"]["count"] == 1, f"李四 count 应为 1，实际 {by_key['李四']['count']}"
    assert len(by_key["李四"]["messages"]) == 1, "李四 messages 长度应为 1"


def test_group_by_sender_order_is_first_appearance(call):
    """groupBySender：分组顺序 = 首次出现顺序（入参时间倒序时最近说话的人在前面）。"""
    groups = call(
        "groupBySender",
        [
            {"sender": "张三", "timestamp": 300},
            {"sender": "李四", "timestamp": 200},
            {"sender": "张三", "timestamp": 100},
        ],
    )
    keys = [g["key"] for g in groups]
    assert keys == ["张三", "李四"], f"分组顺序应按首次出现，实际 {keys}"


def test_group_by_sender_last_ts_is_max(call):
    """groupBySender：lastTs = 组内最大 timestamp。"""
    groups = call(
        "groupBySender",
        [
            {"sender": "张三", "timestamp": 100},
            {"sender": "张三", "timestamp": 900},
            {"sender": "张三", "timestamp": 300},
        ],
    )
    assert len(groups) == 1, f"应只分 1 组，实际 {len(groups)}"
    assert groups[0]["lastTs"] == 900, f"lastTs 应为组内最大 900，实际 {groups[0]['lastTs']}"


def test_group_by_sender_empty_list(call):
    """groupBySender：空数组 -> []。"""
    assert call("groupBySender", []) == [], "空数组应返回 []"


def test_group_by_sender_normalizes_same_sender(call):
    """groupBySender：同一个人名字带不同零宽字符 / 空白 -> 归到同一组。"""
    groups = call(
        "groupBySender",
        [
            {"sender": "张三", "timestamp": 300},
            {"sender": "张\u200b三", "timestamp": 200},
            {"sender": "  张三  ", "timestamp": 100},
        ],
    )
    assert len(groups) == 1, f"归一化后应只分 1 组，实际 {len(groups)} 组：{groups!r}"
    assert groups[0]["key"] == "张三", f"组键应为张三，实际 {groups[0]['key']!r}"
    assert groups[0]["count"] == 3, f"count 应为 3，实际 {groups[0]['count']}"


# --------------------------------------------------------------------------
# 按来源 App 分区
# --------------------------------------------------------------------------


def test_group_by_pkg_partitions_in_input_order(call):
    """groupByPkg：3 个 item 分 2 个包 -> 2 个分区，count / label 正确，items 保序。"""
    labels = {PKG_WX: "微信", "com.x": "示例"}
    groups = call(
        "groupByPkg",
        [
            {"pkg": PKG_WX, "id": 1},
            {"pkg": "com.x", "id": 2},
            {"pkg": PKG_WX, "id": 3},
        ],
        labels,
    )
    assert len(groups) == 2, f"应分 2 个分区，实际 {len(groups)}"
    assert [g["pkg"] for g in groups] == [PKG_WX, "com.x"], (
        "分区应保持传入顺序，实际 %r" % ([g["pkg"] for g in groups],)
    )
    assert groups[0]["label"] == "微信", f"首个分区 label 应为微信，实际 {groups[0]['label']!r}"
    assert groups[0]["count"] == 2, f"微信分区 count 应为 2，实际 {groups[0]['count']}"
    assert [it["id"] for it in groups[0]["items"]] == [1, 3], "微信分区 items 应保序"
    assert groups[1]["label"] == "示例", f"次个分区 label 应为示例，实际 {groups[1]['label']!r}"
    assert groups[1]["count"] == 1, f"示例分区 count 应为 1，实际 {groups[1]['count']}"


def test_group_by_pkg_empty_list(call):
    """groupByPkg：空数组 -> []。"""
    assert call("groupByPkg", [], {}) == [], "空数组应返回 []"


# --------------------------------------------------------------------------
# 重要度档位 / 待办排序与分区
# --------------------------------------------------------------------------


def test_importance_rank(call):
    """importanceRank：high=0 / mid=1 / low=2，其它（含未知值）= 3 同档排最后。

    契约：未知重要度（如 "urgent"）与「未标重要度」同属最后一档（rank=3），
    不能因为值没被识别就排到更后面 —— 否则待办分区里会凭空多出一档。
    """
    assert call("importanceRank", "high") == 0, "high 应为 0"
    assert call("importanceRank", "mid") == 1, "mid 应为 1"
    assert call("importanceRank", "low") == 2, "low 应为 2"
    assert call("importanceRank", "") == 3, f"空重要度应为 3，实际 {call('importanceRank', '')}"
    assert call("importanceRank", None) == 3, "null 应与空串同档"
    assert call("importanceRank", "urgent") == 3, (
        "未知值应与未标重要度同档为 3，实际 %r" % (call("importanceRank", "urgent"),)
    )
    assert call("importanceRank", "urgent") == call("importanceRank", ""), (
        "未知值与空串必须同档"
    )


def test_sort_todos_orders_by_importance_then_received_at(call):
    """sortTodos：高重要度在前，同级按 received_at 倒序（新的在前）。"""
    todos = [
        {"id": "a", "importance": "high", "received_at": 100},
        {"id": "b", "importance": "high", "received_at": 300},
        {"id": "c", "importance": "mid", "received_at": 200},
        {"id": "d", "importance": "low", "received_at": 50},
    ]
    out = call("sortTodos", todos)
    ids = [t["id"] for t in out]
    assert ids == ["b", "a", "c", "d"], f"排序结果不对：{ids}"


def test_sort_todos_returns_new_array(call):
    """sortTodos：返回新数组，不改动入参顺序。"""
    todos = [
        {"id": "a", "importance": "low", "received_at": 100},
        {"id": "b", "importance": "high", "received_at": 300},
    ]
    out = call("sortTodos", todos)
    assert [t["id"] for t in todos] == ["a", "b"], f"入参顺序被改动了：{todos!r}"
    assert [t["id"] for t in out] == ["b", "a"], f"返回数组顺序不对：{out!r}"
    assert out is not todos, "sortTodos 应返回新数组"


def test_group_todos_by_importance_partitions(call):
    """groupTodosByImportance：分区 key 顺序 high -> mid -> low -> ""，label/count 正确。"""
    groups = call(
        "groupTodosByImportance",
        [
            {"id": 1, "importance": "low"},
            {"id": 2, "importance": "high"},
            {"id": 3, "importance": ""},
            {"id": 4, "importance": "mid"},
            {"id": 5, "importance": "high"},
        ],
    )
    assert [g["key"] for g in groups] == ["high", "mid", "low", ""], (
        "分区顺序应为 high->mid->low->空，实际 %r" % ([g["key"] for g in groups],)
    )
    labels = {g["key"]: g["label"] for g in groups}
    assert labels["high"] == "高重要度", f"high 标签不对：{labels['high']!r}"
    assert labels["mid"] == "中重要度", f"mid 标签不对：{labels['mid']!r}"
    assert labels["low"] == "低重要度", f"low 标签不对：{labels['low']!r}"
    assert labels[""] == "未标重要度", f"空档标签不对：{labels['']!r}"
    counts = {g["key"]: g["count"] for g in groups}
    assert counts["high"] == 2, f"high count 应为 2，实际 {counts['high']}"
    assert counts["mid"] == 1, f"mid count 应为 1，实际 {counts['mid']}"
    assert counts["low"] == 1, f"low count 应为 1，实际 {counts['low']}"
    assert counts[""] == 1, f"空档 count 应为 1，实际 {counts['']}"


def test_group_todos_by_importance_unknown_value_goes_to_empty_key(call):
    """groupTodosByImportance：未知重要度（如 "urgent"）归到 key=""（未标重要度）。"""
    groups = call("groupTodosByImportance", [{"id": 1, "importance": "urgent"}])
    assert len(groups) == 1, f"应只分 1 个分区，实际 {len(groups)}"
    assert groups[0]["key"] == "", f"未知重要度应归到空 key，实际 {groups[0]['key']!r}"
    assert groups[0]["label"] == "未标重要度", (
        f"未知重要度分区标签应为未标重要度，实际 {groups[0]['label']!r}"
    )
    assert groups[0]["count"] == 1, f"count 应为 1，实际 {groups[0]['count']}"


# --------------------------------------------------------------------------
# 顾问建议分组
# --------------------------------------------------------------------------


def test_group_suggestions_uses_fixed_type_order(call):
    """groupSuggestions：输入 prompt,watchlist -> 输出 watchlist,prompt（固定优先序）。"""
    groups = call(
        "groupSuggestions",
        [{"type": "prompt", "title": "p"}, {"type": "watchlist", "title": "w"}],
    )
    types = [g["type"] for g in groups]
    assert types == ["watchlist", "prompt"], f"建议类型顺序不对：{types}"


def test_group_suggestions_unknown_type_goes_last(call):
    """groupSuggestions：未知类型排在所有已知类型之后。"""
    groups = call(
        "groupSuggestions",
        [
            {"type": "custom", "title": "c"},
            {"type": "watchlist", "title": "w"},
            {"type": "prompt", "title": "p"},
        ],
    )
    types = [g["type"] for g in groups]
    assert types == ["watchlist", "prompt", "custom"], f"未知类型应排最后，实际 {types}"


def test_group_suggestions_items_sorted_by_title(call):
    """groupSuggestions：同一类型内按 title 升序。"""
    groups = call(
        "groupSuggestions",
        [
            {"type": "prompt", "title": "c"},
            {"type": "prompt", "title": "a"},
            {"type": "prompt", "title": "b"},
        ],
    )
    titles = [it["title"] for g in groups for it in g["items"]]
    assert titles == ["a", "b", "c"], f"组内应按标题升序，实际 {titles}"


def test_group_suggestions_empty_list(call):
    """groupSuggestions：空数组 -> []。"""
    assert call("groupSuggestions", []) == [], "空数组应返回 []"

# --------------------------------------------------------------------------
# 原型键回归：外部数据（群成员名 / 来源包名 / 建议类型）可能正好叫
# "__proto__" 或 "constructor"，分组时绝不能把整页控制台带崩
# --------------------------------------------------------------------------


@pytest.mark.parametrize(
    "name",
    ["__proto__", "constructor", "toString", "hasOwnProperty"],
)
def test_group_by_sender_survives_prototype_keys(call, name):
    """groupBySender：键名撞原型属性时正常分组，不抛异常。"""
    out = call("groupBySender", [{"sender": name, "timestamp": 1}])
    assert len(out) == 1, f"应只分 1 组，实际 {len(out)}"
    assert out[0]["key"] == name, f"分组键应为 {name!r}，实际 {out[0]['key']!r}"
    assert out[0]["count"] == 1, f"count 应为 1，实际 {out[0]['count']}"
    assert len(out[0]["messages"]) == 1, "消息应原样挂在分组里"


@pytest.mark.parametrize(
    "name",
    ["__proto__", "constructor", "toString"],
)
def test_group_by_pkg_survives_prototype_keys(call, name):
    """groupByPkg：来源包名撞原型属性时正常分区，不抛异常。"""
    out = call("groupByPkg", [{"pkg": name}], {}, None)
    assert len(out) == 1, f"应只分 1 区，实际 {len(out)}"
    assert out[0]["pkg"] == name, f"分区 pkg 应为 {name!r}，实际 {out[0]['pkg']!r}"
    assert out[0]["count"] == 1, f"count 应为 1，实际 {out[0]['count']}"


@pytest.mark.parametrize(
    "name",
    ["__proto__", "constructor", "toString"],
)
def test_group_suggestions_survives_prototype_keys(call, name):
    """groupSuggestions：建议类型撞原型属性时正常分组，不抛异常。"""
    out = call("groupSuggestions", [{"type": name, "title": "t"}])
    assert len(out) == 1, f"应只分 1 组，实际 {len(out)}"
    assert out[0]["type"] == name, f"分组类型应为 {name!r}，实际 {out[0]['type']!r}"
    assert len(out[0]["items"]) == 1, "建议应原样挂在分组里"


def test_group_todos_by_importance_survives_prototype_keys(call):
    """groupTodosByImportance：分区键来自固定常量，但计数字典也不能被原型键污染。"""
    out = call(
        "groupTodosByImportance",
        [{"id": 1, "importance": "high"}, {"id": 2, "importance": "urgent"}],
    )
    keys = [g["key"] for g in out]
    assert keys == ["high", ""], f"分区顺序应为 high->空，实际 {keys}"
    ranks = {g["key"]: g["rank"] for g in out}
    assert ranks["high"] == 0, "high rank 应为 0"
    assert ranks[""] == 3, f"未标重要度 rank 应为 3，实际 {ranks['']}"



# --------------------------------------------------------------------------
# 排队任务维度（端上 WorkManager 队列 + 待同步消息队列）
# --------------------------------------------------------------------------


def test_queue_constants_exposed(call):
    """排队维度常量：状态优先序、状态标签、类型标签。"""
    assert call("__get__", "QUEUE_STATE_ORDER") == [
        "running",
        "queued",
        "failed",
        "done",
    ], "排队状态优先序不对（应先运行中、再排队中、然后失败、最后已完成）"
    labels = call("__get__", "QUEUE_STATE_LABELS")
    assert labels == {
        "running": "运行中",
        "queued": "排队中",
        "failed": "失败",
        "done": "已完成",
    }, "QUEUE_STATE_LABELS 不对：%r" % (labels,)
    kinds = call("__get__", "QUEUE_KIND_LABELS")
    assert kinds == {
        "analysis": "分析",
        "sync": "同步",
        "model": "模型",
        "other": "其它",
    }, "QUEUE_KIND_LABELS 不对：%r" % (kinds,)


def _qtask(**kw):
    """构造一条排队任务（字段名与服务端 GET /queue 返回保持一致）。"""
    base = {
        "device_id": "android-1",
        "task_id": "t1",
        "kind": "analysis",
        "title": "分析会话",
        "state": "queued",
        "detail": "",
        "queued_at": 1000,
        "updated_at": 1000,
    }
    base.update(kw)
    return base


def test_normalize_queue_tasks_drops_invalid_entries(call):
    """空 task_id 与超长 task_id 都直接丢弃，不占位置。"""
    out = call("normalizeQueueTasks", [_qtask(task_id=""), _qtask(task_id="x" * 129)])
    assert out == [], "非法 task_id 应被丢弃：%r" % (out,)


def test_normalize_queue_tasks_falls_back_to_defaults(call):
    """非法 kind/state 退回默认值，空标题给默认名。"""
    out = call("normalizeQueueTasks", [_qtask(kind="weird", state="weird", title="   ")])
    assert len(out) == 1, "应保留 1 条"
    assert out[0]["kind"] == "other", "非法 kind 应退回 other"
    assert out[0]["state"] == "queued", "非法 state 应退回 queued"
    assert out[0]["title"] == "未命名任务", "空标题应给默认名"


def test_normalize_queue_tasks_truncates_long_text(call):
    """标题截断到 256、详情截断到 512。"""
    out = call("normalizeQueueTasks", [_qtask(title="标" * 300, detail="详" * 600)])
    assert len(out[0]["title"]) == 256, "标题应截断到 256"
    assert len(out[0]["detail"]) == 512, "详情应截断到 512"

def _has_lone_surrogate(s):
    """是否存在孤立代理（高代理后面不跟低代理，或低代理前面没有高代理）。"""
    for i, ch in enumerate(s):
        cp = ord(ch)
        if 0xD800 <= cp <= 0xDBFF:
            nxt = ord(s[i + 1]) if i + 1 < len(s) else 0
            if not (0xDC00 <= nxt <= 0xDFFF):
                return True
        elif 0xDC00 <= cp <= 0xDFFF:
            prev = ord(s[i - 1]) if i > 0 else 0
            if not (0xD800 <= prev <= 0xDBFF):
                return True
    return False


def test_normalize_queue_tasks_truncates_by_code_point(call):
    """截断按 Unicode 码点数，不是 UTF-16 单元数。

    回归：旧实现用 s.slice(0, 256) 按 unit 切，'a' * 255 + 😀（257 unit / 256 码点）
    会被切成 256 unit，末位留成孤立高代理 0xD83D，浏览器渲染成 U+FFFD —— 端上
    QueueStateCore 走 TextClipCore.clip（码点）、服务端 storage 用 Python len（码点），
    只有控制台按 unit，导致「入库的标题」和「看到的标题」不是同一条。
    """
    emoji = "\U0001F600"
    raw = "a" * 255 + emoji  # 257 unit / 256 码点 => 恰好不超限
    out = call("normalizeQueueTasks", [_qtask(title=raw)])
    title = out[0]["title"]
    assert title == raw, "256 个码点应原样保留，实际 %r" % (title,)
    assert not _has_lone_surrogate(title), "不得留下孤立代理：%r" % (title,)


def test_normalize_queue_tasks_keeps_256_emoji_title(call):
    """256 个 emoji 的标题（512 unit）必须原样保留，不能被砍半。"""
    emoji = "\U0001F600"
    raw = emoji * 256
    assert len(raw) == 256 and len(raw.encode("utf-16-le")) // 2 == 512
    out = call("normalizeQueueTasks", [_qtask(title=raw)])
    title = out[0]["title"]
    assert title == raw, "256 个 emoji 应原样保留，实际长度 %d" % (len(title),)
    assert len(title) == 256, "码点数应仍是 256，实际 %d" % (len(title),)


def test_normalize_queue_tasks_truncates_over_limit_emoji(call):
    """超限的 emoji 标题截到 256 个码点，不产生孤立代理。"""
    emoji = "\U0001F600"
    out = call("normalizeQueueTasks", [_qtask(title=emoji * 300)])
    title = out[0]["title"]
    assert title == emoji * 256, "应截到 256 个码点，实际 %d" % (len(title),)
    assert len(title) == 256, "码点数为 256 时 unit 数是 512，实际 unit %d" % (len(title),)
    assert not _has_lone_surrogate(title), "不得留下孤立代理：%r" % (title,)


def test_normalize_queue_tasks_detail_truncates_by_code_point(call):
    """详情同口径：512 个码点，不是 512 个 unit。"""
    emoji = "\U0001F600"
    out = call("normalizeQueueTasks", [_qtask(detail=emoji * 600)])
    detail = out[0]["detail"]
    assert detail == emoji * 512, "详情应截到 512 个码点，实际 %d" % (len(detail),)
    assert not _has_lone_surrogate(detail), "详情不得留下孤立代理：%r" % (detail,)


def test_normalize_queue_tasks_ascii_truncation_unchanged(call):
    """ASCII 输入与旧实现逐字节相同（300 个 a 截成 256 个 a）。"""
    out = call("normalizeQueueTasks", [_qtask(title="a" * 300, detail="b" * 600)])
    assert out[0]["title"] == "a" * 256, "ASCII 标题应截成 256 个 a"
    assert out[0]["detail"] == "b" * 512, "ASCII 详情应截成 512 个 b"


def test_normalize_queue_tasks_task_id_limit_counts_code_points(call):
    """task_id 的 128 上限也按码点判：128 个 emoji（256 unit）合法，129 个丢弃。

    服务端 storage._normalize_queue_task 用的是 Python len()（码点），对照组在
    test_queue_contract.py；控制台若按 unit 判，同一条报文会「服务端收、控制台丢」。
    """
    emoji = "\U0001F600"
    keep = call("normalizeQueueTasks", [_qtask(task_id=emoji * 128)])
    assert len(keep) == 1, "128 个码点的 task_id 应保留（服务端也收）"
    drop = call("normalizeQueueTasks", [_qtask(task_id=emoji * 129)])
    assert drop == [], "129 个码点的 task_id 应丢弃：%r" % (drop,)


def test_clip_code_points_contract(call):
    """clipCodePoints 契约：不超限原样返回、上限 <=0 返空、超限截在码点边界。"""
    emoji = "\U0001F600"
    assert call("clipCodePoints", "abc", 3) == "abc", "不超限应原样返回"
    assert call("clipCodePoints", emoji * 2, 2) == emoji * 2, "两个 emoji 不超限"
    assert call("clipCodePoints", "abc", 0) == "", "上限 0 应返空串"
    assert call("clipCodePoints", "abc", -1) == "", "负上限应返空串"
    clipped = call("clipCodePoints", "a" + emoji * 3, 2)
    assert clipped == "a" + emoji, "应截到第 2 个码点，实际 %r" % (clipped,)
    assert not _has_lone_surrogate(clipped), "不得留下孤立代理：%r" % (clipped,)


def test_code_point_length_matches_python_len(call):
    """codePointLength 必须与 Python len() 完全一致（Python 字符串就是码点序列）。"""
    emoji = "\U0001F600"
    samples = ["", "a", "abc", emoji, emoji * 3, "a" + emoji + "b", "标" * 5, emoji + "标" + emoji]
    for s in samples:
        got = call("codePointLength", s)
        assert got == len(s), "codePointLength(%r)=%d，Python len=%d" % (s, got, len(s))


def test_normalize_queue_tasks_negative_queued_at_becomes_zero(call):
    """负的 queued_at 归零。"""
    out = call("normalizeQueueTasks", [_qtask(queued_at=-5)])
    assert out[0]["queued_at"] == 0, "负 queued_at 应归零"


def test_normalize_queue_tasks_keeps_latest_for_same_task(call):
    """同 (device_id, task_id) 只留一条，后到的覆盖先到的。"""
    out = call(
        "normalizeQueueTasks",
        [_qtask(state="queued", title="旧标题"), _qtask(state="running", title="新标题")],
    )
    assert len(out) == 1, "同一条任务应只留一条"
    assert out[0]["state"] == "running", "应保留后到的状态"
    assert out[0]["title"] == "新标题", "应保留后到的标题"


def test_normalize_queue_tasks_sort_by_state_then_queued_at(call):
    """排序：状态优先序 -> queued_at 升序 -> task_id。"""
    out = call(
        "normalizeQueueTasks",
        [
            _qtask(task_id="done-1", state="done", queued_at=1),
            _qtask(task_id="q-2", state="queued", queued_at=20),
            _qtask(task_id="r-1", state="running", queued_at=30),
            _qtask(task_id="f-1", state="failed", queued_at=40),
            _qtask(task_id="q-1", state="queued", queued_at=10),
        ],
    )
    assert [t["task_id"] for t in out] == ["r-1", "q-1", "q-2", "f-1", "done-1"], (
        "排序应按 状态优先序 -> queued_at 升序，实际 %r" % ([t["task_id"] for t in out],)
    )


def test_queue_summary_counts_only_fresh_tasks(call):
    """四个状态只统计未过期条目，过期单独计数，total 是条目总数。"""
    tasks = [
        _qtask(task_id="a", state="queued"),
        _qtask(task_id="b", state="running"),
        _qtask(task_id="c", state="failed"),
        _qtask(task_id="d", state="done"),
        _qtask(task_id="e", state="queued", stale=True),
    ]
    s = call("queueSummary", tasks)
    assert s["queued"] == 1, "过期条目不应计入 queued"
    assert s["running"] == 1
    assert s["failed"] == 1
    assert s["done"] == 1
    assert s["stale"] == 1, "过期条目应单独计数"
    assert s["total"] == 5, "total 是条目总数（含过期）"


def test_queue_summary_of_empty_is_all_zero(call):
    """空列表 -> 全 0。"""
    s = call("queueSummary", [])
    assert s == {
        "running": 0,
        "queued": 0,
        "failed": 0,
        "done": 0,
        "stale": 0,
        "total": 0,
    }, "空列表的汇总应全 0，实际 %r" % (s,)


def test_group_queue_tasks_fixed_order(call):
    """分组顺序固定：运行中 -> 排队中 -> 失败 -> 已完成。"""
    tasks = [
        _qtask(task_id="d", state="done"),
        _qtask(task_id="q", state="queued"),
        _qtask(task_id="f", state="failed"),
        _qtask(task_id="r", state="running"),
    ]
    groups = call("groupQueueTasks", tasks)
    assert [g["key"] for g in groups] == ["running", "queued", "failed", "done"], (
        "分组顺序不对：%r" % ([g["key"] for g in groups],)
    )
    assert [g["label"] for g in groups] == ["运行中", "排队中", "失败", "已完成"]
    assert [g["count"] for g in groups] == [1, 1, 1, 1]
    assert groups[0]["items"][0]["task_id"] == "r"


def test_group_queue_tasks_skips_empty_groups(call):
    """没有条目的状态不出现。"""
    groups = call("groupQueueTasks", [_qtask(task_id="q", state="queued")])
    assert [g["key"] for g in groups] == ["queued"], "空分组不应出现"


def test_group_queue_tasks_survives_prototype_keys(call):
    """任务字段来自外部数据，键名撞 Object.prototype 时不能整页挂掉。"""
    groups = call(
        "groupQueueTasks",
        [_qtask(task_id="__proto__", title="原型键"), _qtask(task_id="constructor")],
    )
    total = sum(g["count"] for g in groups)
    assert total == 2, "原型键条目应正常保留，实际 %r" % (total,)


# --------------------------------------------------------------------------
# 会话卡两级折叠分组（groupMessages）
#
# groupMessages 需要 getTs / getSender 两个回调，server/test_console_classify_runner.js
# 的协议只能透传 JSON 值（传不了函数），所以这一节另起一个 node -e 一次性 driver：
# 它从 stdin 读 {module, fn, args, getTsSrc, getSenderSrc}，把 getTsSrc / getSenderSrc
# 用 new Function 编译成真正的函数，再去调 server/console_classify.js 里的被测函数。
# 归类规则仍然全部来自 console_classify.js，driver 只负责「接线」，不在 Python 里复刻规则。
# --------------------------------------------------------------------------

MODULE_PATH = os.path.join(HERE, "console_classify.js")

DRIVER_JS = (
    "var fs=require('fs');"
    "var p=JSON.parse(fs.readFileSync(0,'utf8'));"
    "var api=require(p.module);"
    "function mk(src){return src?new Function('m',src):null;}"
    "var args=(p.args||[]).slice();"
    "if(p.getTsSrc!=null)args.push(mk(p.getTsSrc));"
    "if(p.getSenderSrc!=null)args.push(mk(p.getSenderSrc));"
    "var out=(p.fn==='__exports__')?Object.keys(api).sort():api[p.fn].apply(null,args);"
    "process.stdout.write(Buffer.from(JSON.stringify({ok:true,out:out===undefined?null:out}),'utf8'));"
)

# getTs 返回什么，「日期」就按什么分组（调用方决定粒度，例如传当天零点对齐的时间戳）。
# 所以测试里用「同一天 = 同一个 ts」来表达日期分组。
DAY_MS = 86400000
BASE_DAY = 1767225600000  # 2026-01-01 00:00:00 UTC


def _ts(day=0):
    """第 day 天的零点时间戳（毫秒）。"""
    return BASE_DAY + day * DAY_MS


def _msg(sender, day=0, **kw):
    """构造一条消息（默认字段与控制台会话卡一致：sender + timestamp）。"""
    m = {"sender": sender, "timestamp": _ts(day)}
    m.update(kw)
    return m


@pytest.fixture(scope="session")
def call_msg(node_exe):
    """调用带回调的纯函数：call_msg(fn, args, get_ts_src=None, get_sender_src=None)。"""

    def _call(fn, msgs=None, dim="date", get_ts_src=None, get_sender_src=None):
        payload = {
            "module": MODULE_PATH,
            "fn": fn,
            "args": [msgs, dim],
            "getTsSrc": get_ts_src,
            "getSenderSrc": get_sender_src,
        }
        proc = subprocess.run(
            [node_exe, "-e", DRIVER_JS],
            input=json.dumps(payload, ensure_ascii=False),
            capture_output=True,
            text=True,
            encoding="utf-8",
            timeout=30,
        )
        assert proc.returncode == 0, (
            f"driver 调用失败：fn={fn} 退出码={proc.returncode} stderr={proc.stderr!r}"
        )
        data = json.loads(proc.stdout)
        assert data.get("ok") is True, f"driver 未返回 ok：fn={fn} stdout={proc.stdout!r}"
        return data.get("out")

    return _call


def test_group_messages_date_basic_shape(call_msg):
    """dim='date'：顶层是日期组；key='d|'+ts、name=String(ts)、ts=组内最大时间戳。"""
    out = call_msg(
        "groupMessages",
        [_msg("张三", 0), _msg("李四", 0), _msg("张三", 1)],
        "date",
        get_ts_src="return m.timestamp;",
    )
    assert len(out) == 2, f"应分 2 个日期组，实际 {len(out)}：{out!r}"
    g = out[0]
    assert g["key"] == "d|" + str(_ts(1)), f"日期组 key 不对：{g['key']!r}"
    assert g["dim"] == "date", f"日期组 dim 应为 date，实际 {g['dim']!r}"
    assert g["name"] == str(_ts(1)), f"日期组 name 应是 ts 的字符串：{g['name']!r}"
    assert g["ts"] == _ts(1), f"日期组 ts 应为组内最大时间戳：{g['ts']!r}"
    assert g["count"] == 1, f"count 应为 1，实际 {g['count']}"
    assert len(g["items"]) == 1, "items 应是日期组内的消息"


def test_group_messages_date_multi_sender_same_day_has_children(call_msg):
    """同一天多个人说话 -> 日期组下再分发送者子组（key='s|'+名字）。"""
    out = call_msg(
        "groupMessages",
        [_msg("张三", 0), _msg("李四", 0)],
        "date",
        get_ts_src="return m.timestamp;",
    )
    assert len(out) == 1, f"应只分 1 个日期组，实际 {len(out)}"
    g = out[0]
    assert g["count"] == 2, f"日期组 count 应为 2，实际 {g['count']}"
    assert len(g["items"]) == 2, "日期组 items 应是该日期全部消息"
    assert len(g["children"]) == 2, f"应分 2 个发送者子组，实际 {g['children']!r}"
    keys = sorted(c["key"] for c in g["children"])
    assert keys == ["s|张三", "s|李四"], f"子组 key 不对：{keys}"
    assert all(c["dim"] == "sender" for c in g["children"]), "子组 dim 应为 sender"
    assert sorted(c["name"] for c in g["children"]) == ["张三", "李四"]


def test_group_messages_date_single_sender_children_empty(call_msg):
    """某日期只有一个人 -> children 为空数组，items 直接平铺。"""
    out = call_msg(
        "groupMessages",
        [_msg("张三", 0), _msg("张三", 0)],
        "date",
        get_ts_src="return m.timestamp;",
    )
    assert len(out) == 1
    g = out[0]
    assert g["children"] == [], f"单人不该再分二级，实际 {g['children']!r}"
    assert len(g["items"]) == 2, "items 应平铺该日期全部消息"
    assert g["count"] == 2


def test_group_messages_sender_basic_shape(call_msg):
    """dim='sender'：顶层是发送者组；key='s|'+名字、name=归一名、ts=其最大时间戳。"""
    out = call_msg(
        "groupMessages",
        [_msg("张三", 0), _msg("李四", 0), _msg("张三", 1)],
        "sender",
        get_ts_src="return m.timestamp;",
    )
    assert len(out) == 2, f"应分 2 个发送者组，实际 {len(out)}"
    g = out[0]
    assert g["key"] == "s|张三", f"发送者组 key 不对：{g['key']!r}"
    assert g["dim"] == "sender", f"发送者组 dim 应为 sender，实际 {g['dim']!r}"
    assert g["name"] == "张三", f"发送者组 name 应为归一名：{g['name']!r}"
    assert g["ts"] == _ts(1), f"发送者组 ts 应为其最大时间戳：{g['ts']!r}"
    assert g["count"] == 2, f"count 应为 2，实际 {g['count']}"
    assert len(g["items"]) == 2, "items 应是该发送者的全部消息"


def test_group_messages_sender_single_day_children_empty(call_msg):
    """某人只说过一天 -> children 为空数组，items 平铺。"""
    out = call_msg(
        "groupMessages",
        [_msg("张三", 0), _msg("张三", 0)],
        "sender",
        get_ts_src="return m.timestamp;",
    )
    assert len(out) == 1
    g = out[0]
    assert g["children"] == [], f"只有一天不该再分二级，实际 {g['children']!r}"
    assert len(g["items"]) == 2


def test_group_messages_sender_spanning_days(call_msg):
    """跨天同发送者 -> 发送者组下按日期再分子组（key='d|'+ts，ts 倒序）。"""
    out = call_msg(
        "groupMessages",
        [_msg("张三", 0), _msg("张三", 1), _msg("李四", 1)],
        "sender",
        get_ts_src="return m.timestamp;",
    )
    assert len(out) == 2, f"应分 2 个发送者组，实际 {len(out)}"
    zhang = out[0]
    assert zhang["name"] == "张三", f"count 多的排前面，实际 {zhang['name']!r}"
    assert len(zhang["children"]) == 2, "张三跨两天 -> 2 个日期子组"
    assert [c["key"] for c in zhang["children"]] == ["d|" + str(_ts(1)), "d|" + str(_ts(0))], (
        "日期子组应按 ts 倒序，实际 %r" % ([c["key"] for c in zhang["children"]],)
    )
    assert all(c["dim"] == "date" for c in zhang["children"]), "日期子组 dim 应为 date"
    li = out[1]
    assert li["name"] == "李四"
    assert li["children"] == [], "只有一天不该再分二级"


def test_group_messages_date_single_sender_multiple_days(call_msg):
    """单发送者多日期 -> 日期维度下分多个日期组，每个组都只有一个人（children 空）。"""
    out = call_msg(
        "groupMessages",
        [_msg("张三", 0), _msg("张三", 1)],
        "date",
        get_ts_src="return m.timestamp;",
    )
    assert len(out) == 2, f"应分 2 个日期组，实际 {len(out)}"
    assert [g["key"] for g in out] == ["d|" + str(_ts(1)), "d|" + str(_ts(0))], (
        "日期组应按 ts 倒序，实际 %r" % ([g["key"] for g in out],)
    )
    assert all(g["children"] == [] for g in out), "单人日期组不该有子组"


def test_group_messages_date_sort_by_ts_desc(call_msg):
    """date 维度顶层按 ts 倒序（新的日期在前）。"""
    out = call_msg(
        "groupMessages",
        [_msg("甲", 0), _msg("乙", 2), _msg("丙", 1)],
        "date",
        get_ts_src="return m.timestamp;",
    )
    assert [g["ts"] for g in out] == [_ts(2), _ts(1), _ts(0)], (
        "日期组应按 ts 倒序，实际 %r" % ([g["ts"] for g in out],)
    )


def test_group_messages_sender_sort_by_count_then_name(call_msg):
    """sender 维度顶层按 count 倒序；同 count 按 name 升序。"""
    out = call_msg(
        "groupMessages",
        [
            _msg("B", 0),
            _msg("A", 0),
            _msg("A", 1),
            _msg("C", 0),
        ],
        "sender",
        get_ts_src="return m.timestamp;",
    )
    assert [(g["name"], g["count"]) for g in out] == [("A", 2), ("B", 1), ("C", 1)], (
        "应先按 count 倒序再按 name 升序，实际 %r" % ([(g["name"], g["count"]) for g in out],)
    )


def test_group_messages_children_sort_by_own_dimension(call_msg):
    """子组按自己的维度排序：日期组下的发送者按 count 倒序/name 升序，发送者组下的日期按 ts 倒序。"""
    out = call_msg(
        "groupMessages",
        [_msg("B", 0), _msg("A", 0), _msg("A", 0)],
        "date",
        get_ts_src="return m.timestamp;",
    )
    children = out[0]["children"]
    assert [c["name"] for c in children] == ["A", "B"], (
        "同 count 时子组应按 name 升序，实际 %r" % ([c["name"] for c in children],)
    )

    out2 = call_msg(
        "groupMessages",
        [_msg("A", 0), _msg("A", 1), _msg("A", 2)],
        "sender",
        get_ts_src="return m.timestamp;",
    )
    dates = out2[0]["children"]
    assert [c["ts"] for c in dates] == [_ts(2), _ts(1), _ts(0)], (
        "日期子组应按 ts 倒序，实际 %r" % ([c["ts"] for c in dates],)
    )


def test_group_messages_uses_get_ts(call_msg):
    """getTs 生效：分组按 getTs 返回的值，不看消息自带字段。"""
    msgs = [
        {"sender": "张三", "t": _ts(0), "timestamp": _ts(5)},
        {"sender": "李四", "t": _ts(0), "timestamp": _ts(9)},
    ]
    by_t = call_msg("groupMessages", msgs, "date", get_ts_src="return m.t;")
    assert len(by_t) == 1, f"按 t 分组应只分 1 组，实际 {len(by_t)}"
    assert by_t[0]["key"] == "d|" + str(_ts(0)), f"分组键应基于 t：{by_t[0]['key']!r}"
    assert by_t[0]["count"] == 2

    by_timestamp = call_msg(
        "groupMessages", msgs, "date", get_ts_src="return m.timestamp;"
    )
    assert len(by_timestamp) == 2, "换成 timestamp 就该分 2 组（说明确实用的是 getTs）"


def test_group_messages_uses_get_sender(call_msg):
    """getSender 生效：发送者名来自 getSender，而不是 m.sender。"""
    msgs = [
        {"from": "张三", "timestamp": _ts(0)},
        {"from": "李四", "timestamp": _ts(0)},
    ]
    out = call_msg(
        "groupMessages",
        msgs,
        "date",
        get_ts_src="return m.timestamp;",
        get_sender_src="return m.from;",
    )
    children = out[0]["children"]
    assert sorted(c["name"] for c in children) == ["张三", "李四"], (
        f"应按 from 字段分发送者，实际 %r" % ([c["name"] for c in children],)
    )

    # 不给 getSender 时只能看到 m.sender（这里没有 -> 全部落进「未知发送者」）
    fallback = call_msg(
        "groupMessages", msgs, "sender", get_ts_src="return m.timestamp;"
    )
    assert len(fallback) == 1, "取不到 sender 时应归到一个「未知发送者」"
    assert fallback[0]["name"] == "未知发送者", f"实际 %r" % (fallback[0]["name"],)


def test_group_messages_default_get_sender(call_msg):
    """getSender 可选：不给时默认取 m.sender。"""
    out = call_msg(
        "groupMessages",
        [_msg("张三", 0), _msg("李四", 0)],
        "date",
        get_ts_src="return m.timestamp;",
    )
    names = sorted(c["name"] for c in out[0]["children"])
    assert names == ["张三", "李四"], f"默认应取 m.sender，实际 %r" % (names,)


def test_group_messages_empty_list(call_msg):
    """空数组 -> []。"""
    out = call_msg(
        "groupMessages", [], "date", get_ts_src="return m.timestamp;"
    )
    assert out == [], "空数组应返回 []"


def test_group_messages_unknown_dim_falls_back_to_date(call_msg):
    """dim 其它取值一律当日期处理。"""
    out = call_msg(
        "groupMessages",
        [_msg("张三", 0), _msg("李四", 0)],
        "whatever",
        get_ts_src="return m.timestamp;",
    )
    assert len(out) == 1, "未知维度应按日期分组"
    assert out[0]["dim"] == "date", f"未知维度应落到 date，实际 {out[0]['dim']!r}"


@pytest.mark.parametrize("name", ["__proto__", "constructor", "toString"])
def test_group_messages_survives_prototype_keys(call_msg, name):
    """发送者名撞 Object.prototype 时正常分组，不抛异常。"""
    out = call_msg(
        "groupMessages",
        [{"sender": name, "timestamp": _ts(0)}],
        "sender",
        get_ts_src="return m.timestamp;",
    )
    assert len(out) == 1, f"应只分 1 组，实际 {len(out)}"
    assert out[0]["key"] == "s|" + name, f"分组键应为 s|{name}，实际 {out[0]['key']!r}"
    assert out[0]["name"] == name
    assert out[0]["count"] == 1

    # 同一日期放两个发送者：日期组下要能正常分出子组，且原型键不影响结果
    by_date = call_msg(
        "groupMessages",
        [
            {"sender": name, "timestamp": _ts(0)},
            {"sender": "普通同事", "timestamp": _ts(0)},
        ],
        "date",
        get_ts_src="return m.timestamp;",
    )
    assert len(by_date[0]["children"]) == 2, "日期组下应分出 2 个发送者子组"
    keys = [c["key"] for c in by_date[0]["children"]]
    assert "s|" + name in keys, f"子组键应含 s|{name}，实际 {keys!r}"
    assert by_date[0]["count"] == 2


def test_group_messages_normalizes_sender_names(call_msg):
    """发送者名归一复用 senderKey：冒号前缀 / 零宽字符 / 首尾空白都算同一个人。"""
    msgs = [
        {"sender": "张三: 你好", "timestamp": _ts(0)},
        {"sender": "张\u200b三", "timestamp": _ts(0)},
        {"sender": "  张三  ", "timestamp": _ts(0)},
    ]
    out = call_msg(
        "groupMessages", msgs, "sender", get_ts_src="return m.timestamp;"
    )
    assert len(out) == 1, f"归一后应只分 1 组，实际 {len(out)}：{out!r}"
    assert out[0]["name"] == "张三", f"归一名应为张三，实际 {out[0]['name']!r}"
    assert out[0]["key"] == "s|张三"
    assert out[0]["count"] == 3, f"count 应为 3，实际 {out[0]['count']}"


def test_group_messages_is_deterministic(call_msg):
    """同样的输入永远得到同样的顺序与内容（排序确定且稳定）。"""
    msgs = [
        _msg("B", 0),
        _msg("A", 0),
        _msg("A", 1),
        _msg("C", 2),
    ]
    first = call_msg("groupMessages", msgs, "sender", get_ts_src="return m.timestamp;")
    second = call_msg("groupMessages", msgs, "sender", get_ts_src="return m.timestamp;")
    assert first == second, "同一份输入两次结果应完全一致"


def test_exports_keep_old_api_and_add_new(call_msg):
    """导出面：旧 API 一个不少，新 API 三个都在。"""
    keys = call_msg("__exports__", [])
    old_api = [
        "VERSION",
        "normPkg",
        "pkgLabel",
        "ckOf",
        "senderKey",
        "groupBySender",
        "groupByPkg",
        "importanceRank",
        "sortTodos",
        "groupTodosByImportance",
        "groupSuggestions",
        "QUEUE_STATE_ORDER",
        "QUEUE_STATE_LABELS",
        "QUEUE_KIND_LABELS",
        "normalizeQueueTasks",
        "queueSummary",
        "groupQueueTasks",
    ]
    new_api = ["groupMessages", "groupQueueTasksByDevice", "queueDeviceSummary",
               "mergePkgPairs", "mergeSourcePairs"]
    for name in old_api + new_api:
        assert name in keys, f"导出里应保留 {name}，实际 {keys}"


# --------------------------------------------------------------------------
# 排队任务按设备分组
# --------------------------------------------------------------------------


def test_group_queue_tasks_by_device_groups(call):
    """按设备分组：key/name=设备号，count=任务数，active=排队中+运行中。"""
    tasks = [
        _qtask(device_id="dev-a", task_id="t1", state="running"),
        _qtask(device_id="dev-a", task_id="t2", state="queued"),
        _qtask(device_id="dev-b", task_id="t3", state="done"),
    ]
    groups = call("groupQueueTasksByDevice", tasks)
    assert len(groups) == 2, f"应分 2 个设备组，实际 {len(groups)}"
    a = groups[0]
    assert a["key"] == "dev-a", f"key 应是设备号，实际 {a['key']!r}"
    assert a["name"] == "dev-a", f"name 应是设备号，实际 {a['name']!r}"
    assert a["count"] == 2, f"count 应为 2，实际 {a['count']}"
    assert a["active"] == 2, f"active 应为 2（running+queued），实际 {a['active']}"
    assert len(a["items"]) == 2, "items 应是该设备的归一化任务"
    assert all(t["device_id"] == "dev-a" for t in a["items"]), "items 只含该设备的任务"
    b = groups[1]
    assert b["name"] == "dev-b"
    assert b["count"] == 1
    assert b["active"] == 0, f"已完成的任务不算 active，实际 {b['active']}"


def test_group_queue_tasks_by_device_sorting(call):
    """排序：active 倒序 -> count 倒序 -> device_id 升序。"""
    tasks = [
        _qtask(device_id="dev-b", task_id="t1", state="done"),
        _qtask(device_id="dev-c", task_id="t2", state="queued"),
        _qtask(device_id="dev-a", task_id="t3", state="queued"),
        _qtask(device_id="dev-a", task_id="t4", state="running"),
    ]
    groups = call("groupQueueTasksByDevice", tasks)
    assert [g["name"] for g in groups] == ["dev-a", "dev-c", "dev-b"], (
        "应先按 active 倒序，实际 %r" % ([g["name"] for g in groups],)
    )

    tie = call(
        "groupQueueTasksByDevice",
        [
            _qtask(device_id="dev-z", task_id="t1", state="queued"),
            _qtask(device_id="dev-y", task_id="t2", state="queued"),
        ],
    )
    assert [g["name"] for g in tie] == ["dev-y", "dev-z"], (
        "active 与 count 相同应按设备号升序，实际 %r" % ([g["name"] for g in tie],)
    )


def test_group_queue_tasks_by_device_missing_device_id(call):
    """设备号缺失 -> 归一成空串设备组。"""
    groups = call("groupQueueTasksByDevice", [_qtask(device_id=None, task_id="t1")])
    assert len(groups) == 1, f"应只分 1 组，实际 {len(groups)}"
    assert groups[0]["key"] == "", f"key 应为空串，实际 {groups[0]['key']!r}"
    assert groups[0]["name"] == "", f"name 应为空串，实际 {groups[0]['name']!r}"
    assert groups[0]["count"] == 1


@pytest.mark.parametrize("name", ["__proto__", "constructor", "toString"])
def test_group_queue_tasks_by_device_survives_prototype_keys(call, name):
    """设备号撞 Object.prototype 时正常分组，不抛异常。"""
    groups = call(
        "groupQueueTasksByDevice", [_qtask(device_id=name, task_id="t1", state="queued")]
    )
    assert len(groups) == 1, f"应只分 1 组，实际 {len(groups)}"
    assert groups[0]["key"] == name, f"设备组键应为 {name!r}，实际 {groups[0]['key']!r}"
    assert groups[0]["count"] == 1


def test_group_queue_tasks_by_device_empty(call):
    """空数组 -> []。"""
    assert call("groupQueueTasksByDevice", []) == [], "空数组应返回 []"


def test_queue_device_summary(call):
    """设备维度汇总：设备数 / 在跑任务数 / 任务总数。"""
    tasks = [
        _qtask(device_id="dev-a", task_id="t1", state="running"),
        _qtask(device_id="dev-a", task_id="t2", state="done"),
        _qtask(device_id="dev-b", task_id="t3", state="queued"),
    ]
    s = call("queueDeviceSummary", tasks)
    assert s == {"devices": 2, "active": 2, "total": 3}, "设备汇总不对：%r" % (s,)


def test_queue_device_summary_empty(call):
    """空列表 -> 全 0。"""
    s = call("queueDeviceSummary", [])
    assert s == {"devices": 0, "active": 0, "total": 0}, "空列表的汇总应全 0，实际 %r" % (s,)




# --------------------------------------------------------------------------
# 分布条目归一（by_pkg / by_source 的展示口径）
# --------------------------------------------------------------------------


def test_merge_pkg_pairs_folds_null_and_empty_into_wechat(call):
    """服务端 by_pkg 按原始列分组：NULL 与空串各自成组，展示前必须按微信归并。

    缺陷现场：库里 4 行（微信 / 微博 / NULL / 空串）时，直接按标签渲染会得到
    「微信 1 / 微信 1 / 微博 1 / 微信 1」—— 同一张卡上三个「微信」。
    """
    pairs = [[None, 1], ["", 1], ["com.sina.weibo", 1], [PKG_WX, 1]]
    got = call("mergePkgPairs", pairs)
    assert got == [[PKG_WX, 3], ["com.sina.weibo", 1]], f"归并结果不对：{got!r}"


def test_merge_pkg_pairs_sorts_by_count_desc(call):
    """归并会改次数，顺序必须重排为次数降序（不能沿用首次出现序）。

    反例：服务端返回「微博 2 / 微信 NULL 1 / 微信空串 1 / 微信 1」时，沿用首次
    出现序会渲染成「微博 2 / 微信 3」—— 最大头排在后面，同一张卡自相矛盾。
    """
    pairs = [["com.sina.weibo", 2], [None, 1], ["", 1], [PKG_WX, 1]]
    got = call("mergePkgPairs", pairs)
    assert got == [[PKG_WX, 3], ["com.sina.weibo", 2]], f"次数降序不对：{got!r}"
    # 同次数按键名码点升序，保证同一份数据永远同一串
    assert call("mergePkgPairs", [["b", 2], ["a", 2]]) == [["a", 2], ["b", 2]]
    # 三档混合：微信 5 / 微博 3 / 其它 1
    mixed = [["com.b", 3], [None, 2], ["com.a", 1], ["", 3]]
    assert call("mergePkgPairs", mixed) == [[PKG_WX, 5], ["com.b", 3], ["com.a", 1]]


def test_merge_pkg_pairs_survives_prototype_keys(call):
    """包名撞 Object.prototype（__proto__ / constructor）时正常归并，不抛异常。"""
    pairs = [["__proto__", 1], ["constructor", 2], ["__proto__", 3]]
    got = call("mergePkgPairs", pairs)
    assert got == [["__proto__", 4], ["constructor", 2]], f"原型键归并不对：{got!r}"


def test_merge_source_pairs_folds_blank_into_unknown(call):
    """来源通道空值归成 unknown，与前端本地估算档的 m.source || 'unknown' 一致。"""
    pairs = [["android-notification", 2], [None, 1], ["", 3]]
    got = call("mergeSourcePairs", pairs)
    assert got == [["unknown", 4], ["android-notification", 2]], f"来源归并不对：{got!r}"


def test_merge_pairs_tolerates_bad_input(call):
    """空数组 / 缺列 / 次数不是数字都要能算，不许抛。"""
    assert call("mergePkgPairs", []) == [], "空数组应返回 []"
    assert call("mergePkgPairs", None) == [], "None 应返回 []"
    got = call("mergePkgPairs", [["com.a"], [None, None], ["com.a", "3"]])
    assert got == [["com.a", 3]], f"缺列 / 非数字次数归并不对：{got!r}"



def test_advisor_pkg_scope_all_when_no_filter(call):
    """未选来源时不该渲染任何说明：shown == total、hidden == 0。"""
    reports = [{"id": "r1", "pkg": PKG_WX}, {"id": "r2"}, {"id": "r3", "pkg": "com.sina.weibo"}]
    got = call("advisorPkgScope", reports, "")
    assert got == {"mode": "all", "pkg": "", "total": 3, "shown": 3, "hidden": 0, "untagged": 0}, (
        f"未筛选口径不对：{got!r}"
    )


def test_advisor_pkg_scope_untagged_when_no_report_has_pkg(call):
    """一份都不带 pkg 时才是真的「无法过滤，列表就是全部报告」。"""
    reports = [{"id": "r1"}, {"id": "r2", "pkg": None}, {"id": "r3", "pkg": "   "}]
    got = call("advisorPkgScope", reports, PKG_WX)
    assert got["mode"] == "untagged", f"全部不带 pkg 应判 untagged：{got!r}"
    assert got["total"] == 3 and got["shown"] == 3, f"不带 pkg 的报告必须全部仍显示：{got!r}"
    assert got["hidden"] == 0 and got["untagged"] == 3, f"计数不对：{got!r}"


def test_advisor_pkg_scope_exact_when_every_report_tagged(call):
    """每份都带 pkg 时列表严格等于该来源：说明必须写「已按 X 过滤」。"""
    reports = [{"id": "r1", "pkg": PKG_WX}, {"id": "r2", "pkg": "com.sina.weibo"}]
    got = call("advisorPkgScope", reports, "com.sina.weibo")
    assert got["mode"] == "exact", f"全部带 pkg 应判 exact：{got!r}"
    assert got["total"] == 2 and got["shown"] == 1 and got["hidden"] == 1, f"计数不对：{got!r}"
    assert got["untagged"] == 0, f"exact 档 untagged 应为 0：{got!r}"


def test_advisor_pkg_scope_mixed_keeps_untagged_visible(call):
    """混合档：带 pkg 的按来源筛，不带的仍显示 —— 说明必须交代「另有 K 份」。"""
    reports = [
        {"id": "r1", "pkg": PKG_WX},
        {"id": "r2", "pkg": "com.sina.weibo"},
        {"id": "r3"},
        {"id": "r4", "pkg": "  "},
    ]
    got = call("advisorPkgScope", reports, PKG_WX)
    assert got["mode"] == "mixed", f"混合档判定不对：{got!r}"
    assert got["total"] == 4 and got["shown"] == 3 and got["hidden"] == 1, f"计数不对：{got!r}"
    assert got["untagged"] == 2, f"不带 pkg 的份数不对：{got!r}"


def test_advisor_pkg_scope_hides_everything(call):
    """筛到 0 份也是合法结果：mode 仍是 exact，hidden == total（别退回 untagged）。"""
    reports = [{"id": "r1", "pkg": PKG_WX}, {"id": "r2", "pkg": PKG_WX}]
    got = call("advisorPkgScope", reports, "com.sina.weibo")
    assert got["mode"] == "exact", f"全被筛掉仍属 exact：{got!r}"
    assert got["shown"] == 0 and got["hidden"] == 2, f"全隐藏计数不对：{got!r}"


def test_advisor_pkg_scope_tolerates_bad_input(call):
    """空数组 / None / 非对象条目都要能算，不许抛。"""
    assert call("advisorPkgScope", [], PKG_WX)["mode"] == "untagged", "空列表按 untagged 处理"
    assert call("advisorPkgScope", None, PKG_WX)["total"] == 0, "None 报告列表应按空列表处理"
    got = call("advisorPkgScope", [None, 3, {"id": "r1", "pkg": PKG_WX}], PKG_WX)
    assert got["total"] == 1 and got["shown"] == 1, f"非对象条目应被跳过：{got!r}"

def test_advisor_list_state_keeps_truncation_and_filtering_apart(call):
    """存档 400 / 已加载 100 / 筛到 5：是「筛选」，不是「截断」。

    renderAdvisor 的 reports 是已按来源 + 搜索过滤的可见集，旧写法拿它跟存档总数比，
    会把「筛掉 95 份」说成「只显示最近 5 份（存档共 400 份）」，用户以为丢了 395 份。
    """
    got = call("advisorListState", 100, 400, 5)
    assert got["loaded"] == 100 and got["total"] == 400 and got["shown"] == 5, f"{got!r}"
    assert got["truncated"] is True, f"存档 400 > 已加载 100 必须报截断：{got!r}"
    assert got["filtered"] is True, f"可见 5 < 已加载 100 必须报筛选：{got!r}"


def test_advisor_list_state_not_truncated_when_all_loaded(call):
    """存档 40 份全加载、没筛选：两个标志都不亮，前端不出说明。"""
    got = call("advisorListState", 40, 40, 40)
    assert got["truncated"] is False and got["filtered"] is False, f"{got!r}"
    got = call("advisorListState", 40, 40, 2)
    assert got["truncated"] is False, "存档已全加载，筛掉的不算截断"
    assert got["filtered"] is True, f"可见 2 < 已加载 40 必须报筛选：{got!r}"


def test_advisor_list_state_falls_back_when_total_missing(call):
    """老服务端 / 坏响应没有 total：不许把已加载当成存档总数以外的数。"""
    for bad in (None, 0, -3, "abc", {}):
        got = call("advisorListState", 100, bad, 100)
        assert got["total"] == 100, f"total 缺失应回落已加载条数：{bad!r} -> {got!r}"
        assert got["truncated"] is False, f"total 缺失时不许谎报截断：{bad!r} -> {got!r}"
    got = call("advisorListState", 100, 40, 100)
    assert got["total"] == 100, f"total 小于已加载按已加载算：{got!r}"
    assert got["truncated"] is False, f"{got!r}"


def test_advisor_list_state_clamps_and_tolerates_bad_input(call):
    """脏值不许出负数 / 小数 / NaN，shown 超过 loaded 要夹回去。"""
    got = call("advisorListState", 100, 400, 250)
    assert got["shown"] == 100 and got["filtered"] is False, f"shown 应夹到 loaded：{got!r}"
    got = call("advisorListState", 12.9, 30.2, 12.9)
    assert got["loaded"] == 12 and got["total"] == 30 and got["shown"] == 12, f"{got!r}"
    got = call("advisorListState", -5, -5, -5)
    assert got["loaded"] == 0 and got["total"] == 0 and got["shown"] == 0, f"负数按 0：{got!r}"
    assert got["truncated"] is False and got["filtered"] is False, f"{got!r}"
    got = call("advisorListState", None, None, None)
    assert got["loaded"] == 0 and got["total"] == 0 and got["shown"] == 0, f"{got!r}"

def test_pkg_count_merges_null_and_blank_into_weixin(call):
    """来源分布取某个 App 的全库条数：NULL / 空串必须归到微信，和统计卡同一口径。

    库里 pkg 为 NULL 与空串时各自成一组，normPkg 把两者都归成 com.tencent.mm；
    如果这里按原始键直接比，会得出「微信全库 5 条」而统计卡写「微信 8 条」，
    同一屏两个数同时成立。
    """
    by_pkg = [
        {"pkg": "com.tencent.mm", "count": 5},
        {"pkg": None, "count": 2},
        {"pkg": "", "count": 1},
        {"pkg": "com.sina.weibo", "count": 3},
    ]
    assert call("pkgCount", by_pkg, "com.tencent.mm") == 8
    assert call("pkgCount", by_pkg, "com.sina.weibo") == 3
    # 分布拿到了、全库确实没有：必须回 0，不能回 null（调用方靠它区分「没有」与「不知道」）
    assert call("pkgCount", by_pkg, "com.alibaba.android.rimet") == 0


def test_pkg_count_accepts_map_shape(call):
    """前端本地估算的 byApp 是 {包名: 次数}，同一个函数要吃两种形状。"""
    assert call("pkgCount", {"com.tencent.mm": 2, "": 1}, "com.tencent.mm") == 3
    assert call("pkgCount", {"com.sina.weibo": 4}, "com.tencent.mm") == 0


def test_pkg_count_returns_none_when_unavailable(call):
    """拿不到分布时回 null（不是 0）：0 会让空态断言「全库确实没有」。"""
    for bad in (None, "abc", 42):
        assert call("pkgCount", bad, "com.tencent.mm") is None, f"{bad!r}"


def test_app_empty_scope_separates_missing_from_not_loaded(call):
    """空列表的四种成因必须分开：没有 / 在更早的页 / 不知道 / 不该渲染空态。"""
    by_pkg = [{"pkg": "com.sina.weibo", "count": 3}]
    # 全库确实没有这个来源
    got = call("appEmptyScope", "com.alibaba.android.rimet", 200, 0, by_pkg)
    assert got["mode"] == "none" and got["db"] == 0, f"{got!r}"
    # 全库有 3 条，但一条都不在已加载的 200 条里
    got = call("appEmptyScope", "com.sina.weibo", 200, 0, by_pkg)
    assert got["mode"] == "elsewhere" and got["db"] == 3, f"{got!r}"
    # 拿不到分布：不许替服务端断言「没有」
    got = call("appEmptyScope", "com.sina.weibo", 200, 0, None)
    assert got["mode"] == "unknown" and got["db"] is None, f"{got!r}"
    # 还有可见条目：调用方不该渲染空态
    got = call("appEmptyScope", "com.sina.weibo", 200, 4, by_pkg)
    assert got["mode"] == "loaded" and got["shown"] == 4, f"{got!r}"
    # 没有来源筛选：APP_FILTER 为空时不能落到「这个来源没有」
    got = call("appEmptyScope", "", 0, 0, by_pkg)
    assert got["mode"] == "loaded" and got["pkg"] == "", f"{got!r}"


def test_app_empty_scope_clamps_and_tolerates_bad_input(call):
    """shown 超过 loaded 要夹回；脏值不许出 NaN（NaN 比较恒 false 会把计数静默吞掉）。"""
    got = call("appEmptyScope", "com.sina.weibo", 10, 99, {"com.sina.weibo": 2})
    assert got["loaded"] == 10 and got["shown"] == 10, f"{got!r}"
    assert got["mode"] == "loaded", f"夹紧后仍算有可见条目：{got!r}"
    got = call("appEmptyScope", "com.sina.weibo", "x", -3, {"com.sina.weibo": "2"})
    assert got["loaded"] == 0 and got["shown"] == 0, f"{got!r}"
    assert got["db"] == 2 and got["mode"] == "elsewhere", f"{got!r}"
    got = call("appEmptyScope", "com.tencent.mm", 200, 0, [{"pkg": "com.tencent.mm", "count": 0}])
    assert got["db"] == 0 and got["mode"] == "none", f"计数 0 就是没有：{got!r}"


# --------------------------------------------------------------------------
# 统计 tab「最近分析结果」的显示上限（statsListState）
# 缺陷背景：标题写「最近分析结果（200）」，下面 list.slice(0, 30) 只画 30 张卡，
# 没有任何「另有 170 条未显示」的说明 —— 用户以为数据丢了。上限保留（一屏 200 张
# 卡既卡又没人读），但少了几条必须说清，并且要有一个真能把它们画出来的开关。
# --------------------------------------------------------------------------


def test_stats_list_state_reports_the_rows_it_hides(call):
    """超过上限时必须报出被藏起来的条数，且 capped 只看「已加载 vs 上限」。"""
    got = call("statsListState", 200)
    assert got["loaded"] == 200 and got["shown"] == 30, f"{got!r}"
    assert got["hidden"] == 170 and got["cap"] == 30, f"{got!r}"
    assert got["capped"] is True and got["expanded"] is False, f"{got!r}"
    # 恰好等于上限：全部画得下，不该出现「另有 0 条」这种噪声说明
    got = call("statsListState", 30)
    assert got["hidden"] == 0 and got["capped"] is False, f"{got!r}"
    got = call("statsListState", 12)
    assert got["loaded"] == 12 and got["shown"] == 12 and got["capped"] is False, f"{got!r}"
    got = call("statsListState", 0)
    assert got["loaded"] == 0 and got["shown"] == 0 and got["hidden"] == 0, f"{got!r}"


def test_stats_list_state_expanded_shows_everything(call):
    """展开态：shown = loaded、hidden = 0，但 capped 仍为真（开关要能收起）。"""
    got = call("statsListState", 200, 30, True)
    assert got["expanded"] is True, f"{got!r}"
    assert got["shown"] == 200 and got["hidden"] == 0, f"{got!r}"
    # capped 与展开无关：展开后依然「已加载超过上限」，否则收起按钮会凭空消失
    assert got["capped"] is True and got["cap"] == 30, f"{got!r}"
    got = call("statsListState", 12, 30, True)
    assert got["shown"] == 12 and got["capped"] is False, f"{got!r}"


def test_stats_list_state_defaults_cap_and_tolerates_bad_input(call):
    """上限由 UI 传入，脏值 / 0 / 负数一律回落默认值；条数不许变成 NaN。"""
    assert call("__get__", "DEFAULT_STATS_LIST_CAP") == 30
    for bad in ("x", -5, 0, None):
        got = call("statsListState", 200, bad)
        assert got["cap"] == 30 and got["shown"] == 30, f"cap={bad!r} {got!r}"
    for bad in ("abc", None):
        got = call("statsListState", bad)
        assert got["loaded"] == 0 and got["shown"] == 0 and got["capped"] is False, f"{bad!r} {got!r}"
    # 小数按向下取整：42.9 条已加载没有意义，但不能出现 32.9 这种「另有 N 条」
    got = call("statsListState", 42.9, 10)
    assert got["loaded"] == 42 and got["shown"] == 10 and got["hidden"] == 32, f"{got!r}"


# --------------------------------------------------------------------------
# Tab 角标的数字口径（badgeState）
# 缺陷背景：三个列表角标一律数「已加载页」的条数 —— 待办 Tab 写「15」，而同一屏
# 的概览条（读 /stats 的 analyses.todo）写「40」，用户无从判断哪个是真的。
# 能说全库就说全库；只有拿不到真值、或用户正在客户端筛选（来源 App / 搜索只作用
# 于已加载页）时才退回本页计数。
# --------------------------------------------------------------------------


def test_badge_state_prefers_library_total(call):
    """能拿到全库真值且没有客户端筛选时，角标必须报全库。"""
    got = call("badgeState", 15, 40, False)
    assert got["value"] == 40 and got["scope"] == "library", f"{got!r}"
    # 全库恰好 0 也是「真值」：不能退成本页计数（那会显示本页的 0，看着一样
    # 但 scope 变了，读屏文案会跟着错）
    got = call("badgeState", 0, 0, False)
    assert got["value"] == 0 and got["scope"] == "library", f"{got!r}"


def test_badge_state_falls_back_when_client_side_filtering(call):
    """来源筛选 / 搜索只作用于已加载页，此时全库数不能代表列表内容。"""
    got = call("badgeState", 15, 40, True)
    assert got["value"] == 15 and got["scope"] == "loaded", f"{got!r}"
    # 筛到 0 条也一样：报 40 会让 tab 角标指着「有 40 条待办」，点进去却是空的
    got = call("badgeState", 0, 40, True)
    assert got["value"] == 0 and got["scope"] == "loaded", f"{got!r}"


def test_badge_state_falls_back_when_total_missing(call):
    """拿不到 /stats（老服务端）时退回本页计数，别把「没有数」渲染成 0。"""
    # float("nan") 进不了 JSON（json.dumps 会写 NaN、node JSON.parse 直接拒收），
    # 脏值用字符串与负数覆盖
    for bad in (None, "abc", -3, "3.5.1"):
        got = call("badgeState", 12, bad, False)
        assert got["value"] == 12 and got["scope"] == "loaded", f"{bad!r} {got!r}"


def test_badge_state_clamps_dirty_loaded(call):
    """本页计数是脏值时不许出 NaN / 负数（NaN 参与比较恒 false 会静默吞数）。"""
    got = call("badgeState", "7", "40", False)
    assert got["value"] == 40 and got["scope"] == "library", f"{got!r}"
    got = call("badgeState", -2, None, False)
    assert got["value"] == 0 and got["scope"] == "loaded", f"{got!r}"
    got = call("badgeState", 12.9, 40.7, False)
    assert got["value"] == 40 and got["scope"] == "library", f"{got!r}"
