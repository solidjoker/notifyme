# -*- coding: utf-8 -*-
"""回归测试：钉住 docs/review-frontend-backend-2026-11.md 里已修复的缺陷。

每条用例对应报告里的一条编号（P0-x / N-x / B-x / C-x / E-x / FP-x），
防止后续改动把修复打回去。

运行：
    python -m pytest server/test_regression.py -v --tb=short
"""
import json
import os
import shutil
import sqlite3
import sys
import tempfile
from datetime import datetime, timezone

import pytest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import app as app_module  # noqa: E402

flask_app = app_module.app


@pytest.fixture
def client():
    """隔离的临时数据目录 + 无鉴权模式（WEIXIN_ALLOW_NO_AUTH=1）。"""
    flask_app.config["TESTING"] = True
    tmp = tempfile.mkdtemp(prefix="notifyme_regress_")
    app_module.DATA_DIR = tmp
    app_module.DB_PATH = os.path.join(tmp, "test.db")
    app_module.JSONL_PATH = os.path.join(tmp, "messages.jsonl")
    app_module.ADVISOR_PATH = os.path.join(tmp, "advisor.json")
    app_module.TOKEN = ""
    app_module.ALLOW_NO_AUTH = "1"
    app_module._db_ready = False
    app_module.init_db()
    with flask_app.test_client() as c:
        yield c
    shutil.rmtree(tmp, ignore_errors=True)


def _msg(text="hello", ts=1700000000000, conv="test_conv",
         pkg="com.tencent.mm", sender="u"):
    return {"sender": sender, "text": text, "timestamp": ts,
            "conversation": conv, "is_group": False, "pkg": pkg}


def _jsonl_lines():
    if not os.path.exists(app_module.JSONL_PATH):
        return 0
    with open(app_module.JSONL_PATH, encoding="utf-8") as f:
        return sum(1 for line in f if line.strip())


def _db_count(table="messages"):
    conn = sqlite3.connect(app_module.DB_PATH)
    try:
        return conn.execute("SELECT COUNT(*) FROM " + table).fetchone()[0]
    finally:
        conn.close()


# --------------------------------------------------------------------------
# P0-6：令牌与启动
# --------------------------------------------------------------------------

def test_empty_token_rejects_data_apis(client, monkeypatch):
    """P0-6：没配 WEIXIN_TOKEN 时数据接口 503，不再完全不鉴权地放行。"""
    monkeypatch.setattr(app_module, "ALLOW_NO_AUTH", "")
    assert client.get("/messages").status_code == 503
    assert client.post("/weixin", json=[_msg()]).status_code == 503


def test_allow_no_auth_opt_in(client):
    """P0-6：显式 WEIXIN_ALLOW_NO_AUTH=1 才允许无令牌访问。"""
    assert client.get("/messages").status_code == 200


def test_wsgi_lazy_init_creates_db(client, monkeypatch):
    """P0-6：WSGI 启动不走 __main__ → 第一个请求前必须自动建表。"""
    os.remove(app_module.DB_PATH)
    monkeypatch.setattr(app_module, "_db_ready", False)
    resp = client.get("/messages")
    assert resp.status_code == 200
    assert os.path.exists(app_module.DB_PATH)


# --------------------------------------------------------------------------
# P0-4：数值边界
# --------------------------------------------------------------------------

def test_out_of_range_timestamp_rejected(client):
    """P0-4：timestamp 越界（10**18）被挡下，整批返回 207 而不是 500。"""
    resp = client.post("/weixin", json=[_msg(ts=10 ** 18)])
    assert resp.status_code == 207
    assert resp.get_json()["rejected"] == [0]
    assert _db_count() == 0


def test_out_of_range_analyzed_at_not_500(client):
    """P0-4：analyzed_at 越界值不能让整批分析上报 500。"""
    item = {"case_id": "c1", "conversation": "g", "s1": {"importance": 7},
            "analyzed_at": 10 ** 18}
    resp = client.post("/analysis", json=[item])
    assert resp.status_code in (200, 207)
    assert _db_count("analyses") == 1


def test_analysis_invalid_window_end_rejected(client):
    """P0-4：window_end='abc' → 207 且下标精确，不再 ValueError 500。"""
    item = {"case_id": "c1", "conversation": "g", "s1": {"importance": 7},
            "window_end": "abc", "message_count": 3}
    resp = client.post("/analysis", json=[item])
    assert resp.status_code == 207
    assert resp.get_json()["rejected"] == [0]
    assert _db_count("analyses") == 0


def test_analysis_missing_window_end_accepted(client):
    """P0-4 修复的副作用防线：缺字段按 0 处理，保持老行为。"""
    item = {"case_id": "c1", "conversation": "g", "s1": {"importance": 7}}
    resp = client.post("/analysis", json=[item])
    assert resp.status_code == 200


def test_analysis_207_returns_rejected_ids(client):
    """207 语义加固：响应同时回传被拒条目的 case_id。

    客户端原来只能靠「请求体顺序 == 分批顺序」把 rejected 下标映射回 case_id，
    顺序一旦解耦就会静默错位；服务端直接回传 id 后，客户端可按 id 精确匹配。
    """
    good = {"case_id": "ok-1", "conversation": "g", "s1": {"importance": 7},
            "window_end": 1700000000000, "message_count": 3}
    bad = {"case_id": "bad-1", "conversation": "g", "s1": {"importance": 7},
           "window_end": "abc", "message_count": 3}
    resp = client.post("/analysis", json=[good, bad])
    assert resp.status_code == 207
    body = resp.get_json()
    assert body["rejected"] == [1]
    assert body["rejected_ids"] == ["bad-1"]
    assert body["received"] == 1
    assert _db_count("analyses") == 1


def test_analysis_207_rejected_ids_survive_missing_conversation(client):
    """缺 conversation 的条目也要能回传 case_id（老客户端仍按下标工作）。"""
    item = {"case_id": "bad-2", "s1": {"importance": 7},
            "window_end": 1700000000000, "message_count": 1}
    resp = client.post("/analysis", json=[item])
    assert resp.status_code == 207
    body = resp.get_json()
    assert body["rejected"] == [0]
    assert body["rejected_ids"] == ["bad-2"]


def test_advisor_207_returns_rejected_ids(client):
    """207 语义加固：/advisor 响应带 rejected_ids（缺 report_id 时为空列表）。"""
    good = {"report_id": "r1", "created_at": "2026-11-10 00:00:00",
            "period_days": 7, "case_count": 3, "summary": "s",
            "suggestions": [], "model_used": "m"}
    bad = {"summary": "no report_id", "suggestions": []}
    resp = client.post("/advisor", json=[good, bad])
    assert resp.status_code == 207
    body = resp.get_json()
    assert body["rejected"] == [1]
    assert body["rejected_ids"] == []
    assert body["inserted"] == 1


def test_out_of_range_before_returns_400(client):
    """P0-4：越界游标 before=9*10**22 返回 400（原来 sqlite OverflowError 500）。"""
    resp = client.get("/messages?before=90000000000000000000000")
    assert resp.status_code == 400


def test_messages_cursor_rowid_out_of_range_returns_400(client):
    """第 17 轮：复合游标的 rowid 段同样要校验。

    修前 only 毫秒段有范围校验，rowid 直接塞给 sqlite：
    超 int64 抛 OverflowError（整页 500），负数不抛但静默返回空页
    （看起来像「翻到底了」）。现在两段都走 util._parse_cursor → 400。
    """
    for bad in ("1700000000000_99999999999999999999999",
                "1700000000000_9223372036854775808",
                "1700000000000_-1",
                "1700000000000_1_2"):
        resp = client.get("/messages?before=" + bad)
        assert resp.status_code == 400, bad
        assert resp.get_json()["ok"] is False, bad


def test_messages_cursor_rowid_in_range_still_pages(client):
    """第 17 轮：合法 rowid 仍然能翻页（不是把所有复合游标一律拒掉）。"""
    ts = 1700000000000
    for i in range(3):
        client.post("/weixin", json=[_msg(text="m" + str(i), ts=ts)],)
    body = client.get("/messages?limit=2").get_json()
    cursor = body["next_before"]
    assert cursor and "_" in cursor
    page2 = client.get("/messages?limit=2&before=" + cursor)
    assert page2.status_code == 200
    assert len(page2.get_json()["messages"]) == 1


def test_messages_cursor_max_rowid_boundary(client):
    """第 17 轮：int64 上界本身合法（老客户端只发毫秒时就等价于它）。"""
    client.post("/weixin", json=[_msg(text="a", ts=1700000000000)])
    resp = client.get(
        "/messages?before=1700000000000_9223372036854775807")
    assert resp.status_code == 200
    # 行值比较 (timestamp, rowid) < (ms, MAX_ROWID)：同毫秒且 rowid 更小的行仍在页内，
    # 上界只在「同一毫秒的最后一行之后」时才真的排空。
    rows = resp.get_json()["messages"]
    assert [m["text"] for m in rows] == ["a"]


def test_message_text_length_limit(client):
    """B4：正文超过 64 KiB 被拒（1 MB 正文原来直接入库）。"""
    resp = client.post("/weixin", json=[_msg(text="x" * (1024 * 1024))])
    assert resp.status_code == 207
    assert _db_count() == 0


# --------------------------------------------------------------------------
# P0-5：双写一致性
# --------------------------------------------------------------------------

def test_jsonl_archive_matches_db_rows(client):
    """P0-5：存档行数 == 库内新增行数；重复上报不再多写存档。"""
    client.post("/weixin", json=[_msg(text="a"),
                                 _msg(text="b", ts=1700000000001)])
    assert _jsonl_lines() == 2
    assert _db_count() == 2
    resp = client.post("/weixin", json=[_msg(text="a"),
                                        _msg(text="b", ts=1700000000001)])
    assert resp.get_json()["duplicated"] == 2
    assert _jsonl_lines() == 2
    assert _db_count() == 2


def test_rejected_item_not_written_to_archive(client):
    """P0-5：被拒条目不写存档（原来先写存档再入库 → 存档比库多）。"""
    resp = client.post("/weixin", json=[_msg(text="ok"), {"bad": 1}])
    assert resp.status_code == 207
    assert _jsonl_lines() == 1
    assert _db_count() == 1


def test_rejected_index_is_exact(client):
    """P0-2/P0-3：207 的 rejected 是原始下标，客户端据此精确剔除。"""
    resp = client.post("/weixin", json=[{"bad": 1}, _msg(text="ok")])
    assert resp.status_code == 207
    body = resp.get_json()
    assert body["rejected"] == [0]
    assert body["received"] == 1


# --------------------------------------------------------------------------
# N6/B6/N7：游标与查询
# --------------------------------------------------------------------------

def test_cursor_keeps_same_millisecond_rows(client):
    """N6：同一毫秒的多条消息翻页不丢（复合游标带 rowid）。"""
    ts = 1700000000000
    for i in range(3):
        client.post("/weixin", json=[_msg(text="m" + str(i), ts=ts,
                                         sender="u" + str(i))])
    seen = []
    before = None
    for _ in range(5):
        url = "/messages?limit=2"
        if before:
            url += "&before=" + before
        body = client.get(url).get_json()
        seen += [m["text"] for m in body["messages"]]
        before = body.get("next_before")
        if not before:
            break
    assert sorted(seen) == ["m0", "m1", "m2"]


def test_next_before_only_when_more_pages(client):
    """B6：没有更多数据时不再给游标（省掉一次必然为空的请求）。"""
    client.post("/weixin", json=[_msg(text="a", ts=1700000000000)])
    body = client.get("/messages?limit=10").get_json()
    assert body.get("next_before") is None
    client.post("/weixin", json=[_msg(text="b", ts=1700000000001)])
    body = client.get("/messages?limit=1").get_json()
    assert body.get("next_before")


def test_conversation_like_is_escaped(client):
    """N7：会话名里的 _ 是字面量，不当通配符匹配别人的会话。"""
    client.post("/weixin", json=[_msg(text="x", conv="a_b", ts=1700000000000)])
    client.post("/weixin", json=[_msg(text="y", conv="axb", ts=1700000000001)])
    body = client.get("/messages?conversation=a_b").get_json()
    assert [m["text"] for m in body["messages"]] == ["x"]


# --------------------------------------------------------------------------
# C10/B12/E13：analyses 列与查询
# --------------------------------------------------------------------------

def test_analysis_redaction_columns_roundtrip(client):
    """C10：脱敏证据与手动改写能存能取（以前服务端直接丢列）。"""
    item = {"case_id": "c1", "conversation": "g", "s1": {"importance": 7},
            "redacted": True,
            "redaction_rules": [{"from": "a", "to": "b"}],
            "redaction_hits": 2,
            "todo_text": "记得回电话",
            "todo_due": "2026-11-12"}
    assert client.post("/analysis", json=[item]).status_code == 200
    row = client.get("/analysis?limit=10").get_json()["analyses"][0]
    assert row["redacted"] is True
    assert row["todo_text"] == "记得回电话"
    assert row["todo_due"] == "2026-11-12"
    assert row["redaction_hits"] == 2


def test_analysis_broken_json_column_not_500(client):
    """B12：analyses 里一行脏 JSON 不会让整个查询 500。"""
    client.post("/analysis", json=[{"case_id": "c1", "conversation": "g",
                                    "s1": {"importance": 7}}])
    conn = sqlite3.connect(app_module.DB_PATH)
    conn.execute("UPDATE analyses SET s1='not-json'")
    conn.commit()
    conn.close()
    resp = client.get("/analysis?limit=10")
    assert resp.status_code == 200
    assert resp.get_json()["analyses"][0]["s1"] is None


def test_analysis_returns_normalized_importance(client):
    """E13：查询结果带归一后的 importance / need_action 字段。"""
    client.post("/analysis", json=[{"case_id": "c1", "conversation": "g",
                                    "s1": {"importance": 7}}])
    row = client.get("/analysis?limit=10").get_json()["analyses"][0]
    assert "importance" in row
    assert "need_action" in row


# --------------------------------------------------------------------------
# B2：顾问存档
# --------------------------------------------------------------------------

def test_advisor_corrupt_archive_refuses_write(client):
    """B2：存档损坏时拒绝写入（原来一条上报就把历史整份覆盖抹掉）。

    现在的语义：损坏文件原样留在盘上，写入路径回 5xx，
    等人工修好或删掉损坏文件后自动恢复。
    """
    with open(app_module.ADVISOR_PATH, "w", encoding="utf-8") as f:
        f.write("{ this is not json")
    with open(app_module.ADVISOR_PATH, encoding="utf-8") as f:
        before = f.read()
    resp = client.post("/advisor", json=[{"report_id": "r1",
                                          "created_at": "now"}])
    assert resp.status_code == 500
    body = resp.get_json()
    assert body["ok"] is False
    assert body["inserted"] == 0
    assert "corrupt" in body["error"]
    with open(app_module.ADVISOR_PATH, encoding="utf-8") as f:
        assert f.read() == before, "损坏存档必须原样保留，不能被覆盖"


# --------------------------------------------------------------------------
# P0-1 / N8 / FP0-2：落地页、SW、控制台
# --------------------------------------------------------------------------

def test_index_escapes_last_extract(client):
    """N8：state.json 里的 last_extract 被转义（存储型 XSS）。"""
    d = os.path.join(app_module.DATA_DIR, "android_db")
    os.makedirs(d, exist_ok=True)
    with open(os.path.join(d, "state.json"), "w", encoding="utf-8") as f:
        json.dump({"last_run": "<script>alert(1)</script>"}, f)
    resp = client.get("/")
    assert resp.status_code == 200
    body = resp.get_data(as_text=True)
    assert "<script>alert(1)</script>" not in body
    assert "&lt;script&gt;" in body


def test_index_sets_no_store(client):
    """P0-1：落地页内联消息正文 → 必须 no-store，不能被中间缓存。"""
    resp = client.get("/")
    assert resp.headers.get("Cache-Control") == "no-store"


def test_console_page_not_auth_gated(client, monkeypatch):
    """FP0-2：/console 骨架不含数据 → 不鉴权（PWA 主屏启动不再 401）。"""
    monkeypatch.setattr(app_module, "TOKEN", "secret")
    assert client.get("/console").status_code == 200


def test_sw_does_not_cache_landing_page(client):
    """P0-1：SW 只接管控制台骨架，落地页导航不再进缓存。"""
    body = client.get("/sw.js").get_data(as_text=True)
    assert "isConsoleShell" in body
    assert "wx-console-v5" in body
    assert "if (!isConsoleShell(url.pathname)) return;" in body


# --------------------------------------------------------------------------
# E18 / E11 / B14：提取模块与鉴权细节
# --------------------------------------------------------------------------

def test_extract_module_loads_by_path():
    """E18：提取模块按文件路径加载（WSGI 下 sys.path 里没有 server/）。"""
    mod = app_module._import_extract_module("android")
    assert hasattr(mod, "run_extract")


def test_token_via_query_still_works(client, monkeypatch):
    """B14：?token= 仍可用（控制台从地址栏带令牌进来）。"""
    monkeypatch.setattr(app_module, "TOKEN", "secret")
    assert client.get("/messages?token=secret").status_code == 200
    assert client.get("/messages").status_code == 401


def test_security_headers(client):
    """E11：安全响应头（no-referrer / nosniff）。"""
    resp = client.get("/health")
    assert resp.headers.get("Referrer-Policy") == "no-referrer"
    assert resp.headers.get("X-Content-Type-Options") == "nosniff"


# --------------------------------------------------------------------------
# C11：顾问通道的 207 语义
# --------------------------------------------------------------------------

def test_advisor_returns_207_with_rejected_indices(client):
    """C11：/advisor 部分条目被拒时回 207 + rejected 下标。

    原来恒返回 200，客户端把被拒的报告也标成已同步 → 该报告永久丢失。
    """
    resp = client.post("/advisor", json=[
        {"report_id": "r1", "created_at": "2026-11-10 10:00:00", "summary": "ok"},
        {"created_at": "2026-11-10 10:01:00", "summary": "缺 report_id"},
        {"report_id": "r3", "created_at": "2026-11-10 10:02:00", "summary": "ok"},
    ])
    assert resp.status_code == 207
    body = resp.get_json()
    assert body["ok"] is False
    assert body["inserted"] == 2
    assert body["invalid"] == 1
    assert body["rejected"] == [1]


def test_advisor_all_valid_returns_200(client):
    """C11：全部条目合法时仍是 200 + ok=True（老客户端不受影响）。"""
    resp = client.post("/advisor", json=[
        {"report_id": "r1", "created_at": "2026-11-10 10:00:00", "summary": "ok"},
    ])
    assert resp.status_code == 200
    body = resp.get_json()
    assert body["ok"] is True
    assert body["inserted"] == 1
    assert body["invalid"] == 0


# --------------------------------------------------------------------------
# N7（分析通道同款）：会话名里的通配符不能越权匹配
# --------------------------------------------------------------------------

def test_analysis_conversation_like_is_escaped(client):
    """N7：GET /analysis 的 conversation 过滤也要转义 LIKE 通配符。

    会话名 `a_b` 里的 `_` 是 LIKE 单字符通配符，不转义时会连
    `axb` 一起匹配（越权看到别人的会话）。/messages 早就转义了，
    /analysis 原来漏了，同样口径补上。
    """
    for conv in ("a_b", "axb"):
        client.post("/analysis", json=[{
            "case_id": "c-" + conv,
            "conversation": conv,
            "window_end": 1700000000000,
            "message_count": 1,
            "s1": {"importance": "high", "need_action": {"value": True}},
            "analyzed_at": "2026-11-10 10:00:00",
        }])
    resp = client.get("/analysis?conversation=a_b")
    assert resp.status_code == 200
    body = resp.get_json()
    convs = [a["conversation"] for a in body["analyses"]]
    assert convs == ["a_b"]

# --------------------------------------------------------------------------
# B7：GET /analysis 的游标分页 + 日期过滤
# --------------------------------------------------------------------------

def _analysis_item(case_id="c1", conversation="g",
                   analyzed_at="2026-11-10 10:00:00", **kw):
    """构造一条分析上报（结构与 App 端一致）。"""
    item = {"case_id": case_id, "conversation": conversation,
            "window_end": 1700000000000, "message_count": 1,
            "s1": {"importance": 7}, "analyzed_at": analyzed_at}
    item.update(kw)
    return item


def _utc_ms(year, month, day, hour=0, minute=0, second=0):
    """UTC 时刻 -> 毫秒时间戳（用例不依赖服务器本地时区）。"""
    return int(datetime(year, month, day, hour, minute, second,
                        tzinfo=timezone.utc).timestamp() * 1000)


def test_analysis_cursor_paginates_without_loss(client):
    """B7：GET /analysis 复合游标翻页不丢不重（received_at + rowid）。"""
    for i in range(3):
        resp = client.post("/analysis", json=[_analysis_item(case_id="c%d" % i)])
        assert resp.status_code == 200
    seen = []
    before = None
    for _ in range(5):
        url = "/analysis?limit=1"
        if before:
            url += "&before=" + before
        body = client.get(url).get_json()
        seen += [a["case_id"] for a in body["analyses"]]
        before = body.get("next_before")
        if not before:
            break
    assert sorted(seen) == ["c0", "c1", "c2"]
    assert len(seen) == len(set(seen))


def test_analysis_next_before_only_when_more_pages(client):
    """B7：没有下一页时不再给 next_before（与 /messages 同口径）。"""
    client.post("/analysis", json=[_analysis_item(case_id="c1")])
    body = client.get("/analysis?limit=10").get_json()
    assert body.get("next_before") is None
    client.post("/analysis", json=[_analysis_item(case_id="c2")])
    body = client.get("/analysis?limit=1").get_json()
    assert body.get("next_before")
    assert "_" in body["next_before"]


def test_analysis_invalid_before_returns_400(client):
    """B7：非法 / 越界游标回 400，不 500。"""
    resp = client.get("/analysis?before=abc")
    assert resp.status_code == 400
    assert resp.get_json()["ok"] is False
    resp = client.get("/analysis?before=90000000000000000000000")
    assert resp.status_code == 400
    resp = client.get("/analysis?before=1700000000000_xyz")
    assert resp.status_code == 400


def test_analysis_cursor_rowid_out_of_range_returns_400(client):
    """第 17 轮：/analysis 的 rowid 段校验收口到 util._parse_cursor 后仍生效。"""
    for bad in ("1700000000000_99999999999999999999999",
                "1700000000000_9223372036854775808",
                "1700000000000_-1",
                "1700000000000_1_2"):
        resp = client.get("/analysis?before=" + bad)
        assert resp.status_code == 400, bad
        assert resp.get_json()["ok"] is False, bad


def test_analysis_date_filter_by_analyzed_at_day(client):
    """B7：date=YYYY-MM-DD 按 analyzed_at 当天过滤。"""
    client.post("/analysis", json=[
        _analysis_item(case_id="d1", analyzed_at="2026-11-10 10:00:00")])
    client.post("/analysis", json=[
        _analysis_item(case_id="d2", analyzed_at="2026-11-11 10:00:00")])
    body = client.get("/analysis?date=2026-11-10").get_json()
    assert [a["case_id"] for a in body["analyses"]] == ["d1"]
    body = client.get("/analysis?date=2026-11-11").get_json()
    assert [a["case_id"] for a in body["analyses"]] == ["d2"]
    assert client.get("/analysis?date=not-a-date").status_code == 400


def test_analysis_date_filter_respects_tz_for_epoch_columns(client):
    """B7：analyzed_at 是毫秒整数文本（老数据）时，date 的边界按 tz 算。"""
    # UTC 11-10 23:30 == +08:00 11-11 07:30（不在东八区的 11-10 当天）
    ms = _utc_ms(2026, 11, 10, 23, 30)
    conn = sqlite3.connect(app_module.DB_PATH)
    conn.execute(
        "INSERT INTO analyses (case_id, conversation, window_end, "
        "message_count, need_action, importance, escalated, s1, s2, "
        "analyzed_at, protocol, received_at, pkg) "
        "VALUES ('ms1', 'g', ?, 1, 0, 'high', 0, '{}', NULL, ?, 'p', ?, ?)",
        (1700000000000, str(ms), 1700000000000, "com.tencent.mm"))
    conn.commit()
    conn.close()
    body = client.get("/analysis?date=2026-11-10&tz=+08:00").get_json()
    assert body["analyses"] == []
    body = client.get("/analysis?date=2026-11-10&tz=+00:00").get_json()
    assert [a["case_id"] for a in body["analyses"]] == ["ms1"]


def test_analysis_cursor_query_uses_index(client):
    """B7：复合游标走行值比较，能用 idx_analyses_received_at（不是全表 SCAN）。"""
    conn = app_module._connect()
    try:
        plan = conn.execute(
            "EXPLAIN QUERY PLAN SELECT id FROM analyses "
            "WHERE (received_at, rowid) < (?, ?) "
            "ORDER BY received_at DESC, rowid DESC LIMIT ?",
            (1, 2, 3)).fetchall()
    finally:
        conn.close()
    text = " | ".join(str(row[-1]) for row in plan)
    assert "USING INDEX" in text or "USING COVERING INDEX" in text, text
    assert "SCAN analyses" not in text, text


def test_analysis_null_conversation_is_empty_string(client):
    """B7：analyses.conversation 为 NULL 时回空串（控制台别显示 null）。"""
    conn = sqlite3.connect(app_module.DB_PATH)
    conn.execute(
        "INSERT INTO analyses (case_id, conversation, window_end, "
        "message_count, need_action, importance, escalated, s1, s2, "
        "analyzed_at, protocol, received_at, pkg) "
        "VALUES ('n1', NULL, ?, 1, 0, 'high', 0, '{}', NULL, ?, 'p', ?, ?)",
        (1700000000000, "2026-11-10 10:00:00", 1700000000000,
         "com.tencent.mm"))
    conn.commit()
    conn.close()
    row = client.get("/analysis?limit=10").get_json()["analyses"][0]
    assert row["conversation"] == ""



# --------------------------------------------------------------------------
# B5：显式时区（tz / tz_offset）
# --------------------------------------------------------------------------

def test_messages_tz_day_bounds(client):
    """B5：tz=+08:00 时当天边界按东八区算，响应回显实际时区。"""
    inside = _utc_ms(2026, 11, 10, 15, 59)   # +08:00 -> 11-10 23:59
    outside = _utc_ms(2026, 11, 10, 16, 0)    # +08:00 -> 11-11 00:00
    client.post("/weixin", json=[_msg(text="in", ts=inside),
                                 _msg(text="out", ts=outside)])
    body = client.get("/messages?date=2026-11-10&tz=+08:00").get_json()
    assert [m["text"] for m in body["messages"]] == ["in"]
    assert body["tz"] == "+08:00"
    assert body["tz_offset_minutes"] == 480
    # tz_offset（分钟偏移）形式等价
    body = client.get("/messages?date=2026-11-10&tz_offset=480").get_json()
    assert [m["text"] for m in body["messages"]] == ["in"]
    assert body["tz_offset_minutes"] == 480


def test_messages_without_tz_keeps_server_local(client):
    """B5：不带 tz 时保持服务器本地时区行为（老客户端不用改）。"""
    body = client.get("/messages").get_json()
    assert body["tz"] is None
    assert body["tz_offset_minutes"] is None


def test_messages_invalid_tz_returns_400(client):
    """B5：非法时区参数回 400（原来直接当 UTC 或 500）。"""
    resp = client.get("/messages?tz=abc")
    assert resp.status_code == 400
    assert resp.get_json()["ok"] is False
    resp = client.get("/messages?tz=+08:00&tz_offset=480")
    assert resp.status_code == 400
    resp = client.get("/messages?tz=+99:00")
    assert resp.status_code == 400
    resp = client.get("/messages?tz_offset=abc")
    assert resp.status_code == 400


def test_analysis_tz_param_echo_and_validation(client):
    """B5：/analysis 同样支持 tz，非法值回 400。"""
    assert client.get("/analysis").get_json()["tz"] is None
    body = client.get("/analysis?tz=+08:00").get_json()
    assert body["tz"] == "+08:00"
    assert body["tz_offset_minutes"] == 480
    assert client.get("/analysis?tz=abc").status_code == 400


# --------------------------------------------------------------------------
# B13：布尔字段归一（escalated / filtered / redacted / need_action）
# --------------------------------------------------------------------------

def test_safe_bool_normalization_table():
    """B13：_safe_bool 的归一口径（布尔 / 数字 / 字符串 / 认不出来的值）。"""
    f = app_module._safe_bool
    assert f(True) is True
    assert f(False) is False
    assert f(1) is True
    assert f(0) is False
    assert f("true") is True
    assert f("TRUE") is True
    assert f("1") is True
    assert f("yes") is True
    assert f("on") is True
    assert f("false") is False
    assert f("0") is False
    assert f("no") is False
    assert f("off") is False
    assert f("") is False
    assert f(None) is False
    assert f("maybe") is False
    assert f("maybe", default=True) is True


def test_analysis_boolean_strings_are_normalized(client):
    """B13：字符串 'false' / '0' / '' 上报时按假处理（原来全被当真）。"""
    client.post("/analysis", json=[_analysis_item(
        case_id="b1", escalated="false", filtered="0", redacted="",
        s1={"importance": 7, "need_action": {"value": "false"}})])
    row = client.get("/analysis?limit=10").get_json()["analyses"][0]
    assert row["escalated"] is False
    assert row["filtered"] is False
    assert row["redacted"] is False
    assert row["need_action"] is False


def test_analysis_boolean_strings_true_are_normalized(client):
    """B13：字符串 'true' / '1' / 'yes' 上报时按真处理。"""
    client.post("/analysis", json=[_analysis_item(
        case_id="b2", escalated="true", filtered="1", redacted="yes",
        s1={"importance": 7, "need_action": {"value": "1"}})])
    row = client.get("/analysis?limit=10").get_json()["analyses"][0]
    assert row["escalated"] is True
    assert row["filtered"] is True
    assert row["redacted"] is True
    assert row["need_action"] is True


# --------------------------------------------------------------------------
# B11：提取任务的成功判定
# --------------------------------------------------------------------------

def test_run_extract_bg_requires_explicit_ok_true(monkeypatch):
    """B11：返回体里没有 ok 键时算失败（原来 is not False 判成了成功）。"""
    class _Stub(object):
        def __init__(self, payload):
            self.payload = payload

        def run_extract(self, full=False):
            return dict(self.payload)

    monkeypatch.setattr(app_module, "_import_extract_module",
                        lambda leg: _Stub({}))
    app_module._run_extract_bg(False, "android")
    with app_module._extract_lock:
        last = app_module._extract_status["last"]
    assert last["ok"] is False

    monkeypatch.setattr(app_module, "_import_extract_module",
                        lambda leg: _Stub({"ok": True}))
    app_module._run_extract_bg(False, "android")
    with app_module._extract_lock:
        last = app_module._extract_status["last"]
    assert last["ok"] is True


def test_extract_scripts_expose_ok_key():
    """B11：两个提取脚本的返回体都带 ok 键（否则后台判定与脚本口径不一致）。"""
    import inspect
    android = app_module._import_extract_module("android")
    assert '"ok"' in inspect.getsource(android.run_extract)
    assert '"ok"' in inspect.getsource(android.merge_messages)
    pc = app_module._import_extract_module("pc")
    assert '"ok"' in inspect.getsource(pc.run_extract)


# --------------------------------------------------------------------------
# E11：鉴权失败限流
# --------------------------------------------------------------------------

def test_auth_failures_are_rate_limited(client, monkeypatch):
    """E11：连续鉴权失败超阈值回 429 + Retry-After；鉴权成功清零。"""
    monkeypatch.setattr(app_module, "TOKEN", "secret")
    monkeypatch.setattr(app_module, "ALLOW_NO_AUTH", "")
    # 独立的 X-Forwarded-For 桶，不污染其它用例的失败计数
    ip = {"X-Forwarded-For": "203.0.113.7"}
    try:
        for i in range(app_module.AUTH_FAIL_LIMIT):
            headers = dict(ip)
            headers["X-Token"] = "bad"
            resp = client.get("/messages", headers=headers)
            assert resp.status_code == 401, i
        headers = dict(ip)
        headers["X-Token"] = "bad"
        resp = client.get("/messages", headers=headers)
        assert resp.status_code == 429
        assert resp.get_json()["ok"] is False
        assert int(resp.headers.get("Retry-After", "0")) > 0
        # 鉴权成功 -> 该 IP 的失败计数清零
        headers = dict(ip)
        headers["X-Token"] = "secret"
        assert client.get("/messages", headers=headers).status_code == 200
        headers = dict(ip)
        headers["X-Token"] = "bad"
        assert client.get("/messages", headers=headers).status_code == 401
    finally:
        app_module._auth_fail_events.clear()



# --------------------------------------------------------------------------
# B9：顾问存档（JSONL 追加 + 旧格式迁移 + 上限）
# --------------------------------------------------------------------------

def _advisor_jsonl_path():
    return os.path.join(app_module.DATA_DIR, "advisor_reports.jsonl")


def test_advisor_append_does_not_rewrite_archive(client, monkeypatch):
    """B9：正常上报走 JSONL 追加，不再整份重写（写放大 + 崩溃丢历史）。"""
    rewrites = []
    monkeypatch.setattr(app_module, "_write_advisor_jsonl",
                        lambda reports: rewrites.append(len(reports)))
    for i in range(3):
        resp = client.post("/advisor", json=[{"report_id": "r%d" % i,
                                              "created_at": "2026-11-10 10:00:00"}])
        assert resp.status_code == 200
    assert rewrites == [], "追加写路径不应整份重写 JSONL"
    with open(_advisor_jsonl_path(), encoding="utf-8") as f:
        lines = [ln for ln in f if ln.strip()]
    assert len(lines) == 3
    body = client.get("/advisor").get_json()
    assert len(body["reports"]) == 3


def test_advisor_legacy_json_is_migrated(client):
    """B9：旧的 JSON 数组存档首次读到时迁移成 JSONL，历史不丢。"""
    legacy = [{"report_id": "old1", "created_at": "2026-11-09 10:00:00"},
              {"report_id": "old2", "created_at": "2026-11-09 10:01:00"}]
    with open(app_module.ADVISOR_PATH, "w", encoding="utf-8") as f:
        json.dump(legacy, f)
    resp = client.post("/advisor", json=[{"report_id": "new1",
                                          "created_at": "2026-11-10 10:00:00"}])
    assert resp.status_code == 200
    assert resp.get_json()["inserted"] == 1
    with open(_advisor_jsonl_path(), encoding="utf-8") as f:
        lines = [ln for ln in f if ln.strip()]
    assert len(lines) == 3
    body = client.get("/advisor").get_json()
    assert len(body["reports"]) == 3


def test_advisor_archive_is_bounded(client, monkeypatch):
    """B9：存档条数有上限，超出丢最旧的（避免无界增长）。"""
    monkeypatch.setattr(app_module, "ADVISOR_MAX_REPORTS", 2)
    for i in range(3):
        client.post("/advisor", json=[{"report_id": "r%d" % i,
                                      "created_at": "2026-11-10 10:00:00"}])
    with open(_advisor_jsonl_path(), encoding="utf-8") as f:
        lines = [ln for ln in f if ln.strip()]
    assert len(lines) == 2
    body = client.get("/advisor").get_json()
    assert len(body["reports"]) == 2


# --------------------------------------------------------------------------
# P3：错误响应、安全响应头、NULL 列渲染
# --------------------------------------------------------------------------

def test_security_headers_include_x_frame_options(client):
    """P3：补 X-Frame-Options: DENY（页面不允许被 iframe 嵌套）。"""
    resp = client.get("/health")
    assert resp.headers.get("X-Frame-Options") == "DENY"


def test_404_returns_json(client):
    """P3：未知路径回 JSON（原来 Flask 默认 HTML，客户端 JSON 解析直接炸）。"""
    resp = client.get("/no-such-endpoint-xyz")
    assert resp.status_code == 404
    assert resp.headers.get("Content-Type", "").startswith("application/json")
    body = resp.get_json()
    assert body["ok"] is False
    assert body["error"]


def test_413_returns_json(client):
    """P3：请求体超限回 JSON 413（原来回 text/html）。"""
    resp = client.post("/weixin", data=b"x" * (9 * 1024 * 1024),
                       content_type="application/json")
    assert resp.status_code == 413
    body = resp.get_json()
    assert body["ok"] is False
    assert body["error"] == "request body too large"


def test_500_returns_json_without_stack(client, monkeypatch):
    """P3：未捕获异常回 JSON 500，异常栈不泄给客户端。"""
    monkeypatch.setitem(flask_app.config, "TESTING", False)

    def boom():
        raise RuntimeError("secret internal detail")

    monkeypatch.setitem(flask_app.view_functions, "query_messages", boom)
    resp = client.get("/messages")
    assert resp.status_code == 500
    assert resp.get_json() == {"ok": False, "error": "internal server error"}
    text = resp.get_data(as_text=True)
    assert "secret internal detail" not in text
    assert "Traceback" not in text


def test_esc_html_none_is_empty():
    """P3：_esc_html(None) -> 空串（原来渲染成字面量 None）。"""
    assert app_module._esc_html(None) == ""
    assert app_module._esc_html("") == ""
    assert app_module._esc_html("<a>&") == "&lt;a&gt;&amp;"
    assert app_module._esc_html('"') == "&quot;"
    assert app_module._esc_html("'") == "&#39;"


def test_messages_null_columns_are_empty_strings(client):
    """P3：库里 conversation/sender/text 为 NULL 时，接口回空串。"""
    conn = sqlite3.connect(app_module.DB_PATH)
    conn.execute(
        "INSERT INTO messages (sender, text, timestamp, conversation, "
        "is_group, received_at, pkg) VALUES (NULL, NULL, ?, NULL, 0, ?, ?)",
        (1700000000000, 1700000000000, "com.tencent.mm"))
    conn.commit()
    conn.close()
    body = client.get("/messages").get_json()
    assert len(body["messages"]) == 1
    msg = body["messages"][0]
    assert msg["conversation"] == ""
    assert msg["sender"] == ""
    assert msg["text"] == ""


def test_landing_page_renders_null_conversation(client):
    """P3：落地页遇到 NULL 会话名不再 500，渲染成空串而不是字面量 None。"""
    conn = sqlite3.connect(app_module.DB_PATH)
    conn.execute(
        "INSERT INTO messages (sender, text, timestamp, conversation, "
        "is_group, received_at, pkg) VALUES ('u', 'hi', ?, NULL, 0, ?, ?)",
        (1700000000000, 1700000000000, "com.tencent.mm"))
    conn.commit()
    conn.close()
    resp = client.get("/")
    assert resp.status_code == 200
    body = resp.get_data(as_text=True)
    assert "None" not in body
    assert '<span class="conv"></span>' in body


def test_advisor_pkg_is_passthrough(client):
    """顾问报告支持可选 pkg 透传（控制台顾问 tab 才能按来源过滤）。"""
    report = {
        "report_id": "rep-pkg-1",
        "created_at": "2026-11-10 10:00:00",
        "period_days": 7,
        "case_count": 3,
        "summary": "summary",
        "suggestions": [],
        "model_used": "m",
        "pkg": "com.tencent.mm",
    }
    resp = client.post("/advisor", json=[report])
    assert resp.status_code == 200
    body = client.get("/advisor").get_json()
    assert body["count"] == 1
    assert body["reports"][0]["pkg"] == "com.tencent.mm"

    # 非法 pkg（数字）要被丢掉，不能进存档
    bad = dict(report, report_id="rep-pkg-2", pkg=123)
    resp = client.post("/advisor", json=[bad])
    assert resp.status_code == 200
    body = client.get("/advisor").get_json()
    rep = [r for r in body["reports"] if r["report_id"] == "rep-pkg-2"][0]
    assert "pkg" not in rep


def _write_advisor_jsonl_raw(rows):
    """直接往顾问 JSONL 塞行（模拟人工修复 / 第三方脚本 / 老部署）。"""
    os.makedirs(app_module.DATA_DIR, exist_ok=True)
    with open(_advisor_jsonl_path(), "w", encoding="utf-8") as f:
        for row in rows:
            f.write(json.dumps(row, ensure_ascii=False) + "\n")


def test_advisor_sort_survives_string_received_at(client):
    """E-49：JSONL 里 received_at 是字符串时 /advisor 不再 500。

    修前 reports.sort(key=lambda r: r.get("received_at", 0)) 直接拿原值当键，
    数字行与字符串行混在一起就抛 TypeError: '<' not supported between
    instances of 'int' and 'str'，整页 500（控制台的顾问 Tab 随之整块空白）。
    """
    _write_advisor_jsonl_raw([
        {"report_id": "old-int", "received_at": 1700000000000},
        {"report_id": "dirty-str", "received_at": "1700000001000"},
        {"report_id": "missing"},
        {"report_id": "text-clock", "received_at": "2026-11-10 08:00:00"},
    ])
    resp = client.get("/advisor?limit=10")
    assert resp.status_code == 200, resp.get_data(as_text=True)
    body = resp.get_json()
    assert body["ok"] is True
    ids = [r["report_id"] for r in body["reports"]]
    # 有时间的按数值倒序在前，缺时间的垫底（0）：
    # text-clock = 2026-11-10 本地时间（毫秒远大于 1700000001000）→ 最新
    assert ids[0] == "text-clock"
    assert ids[1] == "dirty-str"
    assert ids[-1] == "missing"
    assert set(ids) == {"old-int", "dirty-str", "missing", "text-clock"}


def test_advisor_reports_archive_total_and_truncation(client, monkeypatch):
    """GET /advisor 必须回存档总数与 has_more：单次最多 100 份，存档上限 1000。

    只回 count 时控制台无法区分「只有 100 份」和「有 150 份、只拿到最新 100 份」，
    顾问 Tab 会静默只显示最新 100 份，看起来像更早的报告丢了。
    """
    monkeypatch.setattr(app_module, "ADVISOR_MAX_REPORTS", 1000)
    for i in range(150):
        client.post("/advisor", json=[{"report_id": "r%03d" % i,
                                      "created_at": "2026-11-10 10:00:00"}])
    body = client.get("/advisor?limit=100").get_json()
    assert body["count"] == 100
    assert body["total"] == 150
    assert body["has_more"] is True
    # 分页语义是「最新的在前」：截断丢的是最旧的。
    # 别断言列表末位恰好是 r050 —— 三批写入的 received_at 在快机器上可能落在同一毫秒，
    # 批次要按 created_at 兜底比较，边界那一条会随调度漂移（本机 r050 / CI r051）。
    ids = [r["report_id"] for r in body["reports"]]
    assert len(ids) == 100
    assert len(set(ids)) == 100
    assert ids[0] == "r149"
    assert "r000" not in ids, "最旧的一条必须被截掉"

    full = client.get("/advisor?limit=200").get_json()
    # limit 上限仍是 100（存档上限 1000 不等于接口能一次给 1000 份）
    assert full["count"] == 100
    assert full["total"] == 150
    assert full["has_more"] is True


def test_advisor_within_limit_reports_no_more(client):
    """存档只有 3 份时 has_more 必须是 False（别让控制台常驻截断提示）。"""
    for i in range(3):
        client.post("/advisor", json=[{"report_id": "r%d" % i,
                                      "created_at": "2026-11-10 10:00:00"}])
    body = client.get("/advisor").get_json()
    assert body["count"] == 3
    assert body["total"] == 3
    assert body["has_more"] is False


def test_advisor_corrupted_archive_reports_zero_total(client):
    """存档损坏时 total/has_more 也要给（0/False），前端不能拿到 undefined。"""
    with open(_advisor_jsonl_path(), "w", encoding="utf-8") as f:
        f.write("{broken")
    body = client.get("/advisor").get_json()
    assert body["ok"] is False
    assert body["total"] == 0
    assert body["has_more"] is False


def test_advisor_sort_falls_back_to_created_at(client):
    """received_at 缺失时退到 created_at 排序，不能全打平（等于没排）。"""
    _write_advisor_jsonl_raw([
        {"report_id": "old", "created_at": "1700000000000"},
        {"report_id": "new", "created_at": "1799999999000"},
    ])
    body = client.get("/advisor?limit=10").get_json()
    assert [r["report_id"] for r in body["reports"]] == ["new", "old"]


def test_advisor_ts_parses_every_stored_shape():
    """E-49：_advisor_ts 的口径（毫秒 / 秒 / 数字字符串 / 文本 / 脏值）。

    这是 /advisor 排序与 /suggests/next 顾问规则共用的一把尺子：
    任何形状都得给出可比较的数字，认不出回落 0，绝不抛。
    """
    ts = app_module._advisor_ts
    assert ts(1700000000000) == 1700000000000
    assert ts("1700000001000") == 1700000001000
    assert ts(1700000000) == 1700000000000          # 秒级按秒解释
    assert ts("1700000000.0") == 1700000000000
    assert ts(None) == 0 and ts("") == 0 and ts("   ") == 0
    assert ts(True) == 0 and ts(False) == 0          # bool 不当数字用
    assert ts("not-a-time") == 0
    assert ts(-1) == 0 and ts(10 ** 18) == 0         # 越界值回落，不抛
    # 文本形态按本地时区解析（老存档写的就是本地时间）
    local = datetime(2026, 11, 10, 8, 0, 0)
    assert ts("2026-11-10 08:00:00") == int(local.timestamp() * 1000)
    assert ts("2026-11-10T08:00:00") == int(local.timestamp() * 1000)


def test_console_ui_module_is_integrated(client):
    """控制台前端资源已拆到 console_ui.py 并接进 app.py（可独立单测）。"""
    resp = client.get("/console")
    assert resp.status_code == 200
    html = resp.get_data(as_text=True)
    # 新版控制台的关键节点（拆模块后仍然在位）
    assert "global-search" in html
    assert "prefers-color-scheme" in html
    assert "aria-live" in html
    # SW 缓存名保持 v5，且不缓存数据接口
    sw = client.get("/sw.js").get_data(as_text=True)
    assert "wx-console-v5" in sw
    # 导航只接管控制台壳：落地页 / 不会被写进缓存（P0-1）
    assert "if (!isConsoleShell(url.pathname)) return;" in sw
    # 数据接口显式走网络，不进缓存
    assert "indexOf('/messages') >= 0" in sw
    assert "indexOf('/analysis') >= 0" in sw
