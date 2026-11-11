# -*- coding: utf-8 -*-
"""服务端加固回归（第 2 轮）：钉住 docs/review-frontend-backend-2026-11.md
的服务端条目。

覆盖：Top13 #3/#4/#5/#6/#7/#9/#10/#12、P0-6、B1/B2/B4/B8、B5/B6、
E12/E13/E18、N2/N3。

运行：
    python -m pytest server/test_hardening.py -v --tb=short
"""
import json
import os
import shutil
import sqlite3
import sys
import tempfile
import threading

import pytest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import app as app_module  # noqa: E402

flask_app = app_module.app


@pytest.fixture
def client():
    """隔离的临时数据目录 + 无鉴权模式（WEIXIN_ALLOW_NO_AUTH=1）。"""
    flask_app.config["TESTING"] = True
    tmp = tempfile.mkdtemp(prefix="notifyme_harden_")
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


def _analysis_item(case_id="c1", conv="conv", **kw):
    item = {
        "case_id": case_id,
        "conversation": conv,
        "window_end": 1700000000000,
        "message_count": 3,
        "s1": {"need_action": {"value": True, "prob": 0.9}, "importance": 7},
        "escalated": False,
        "analyzed_at": "2026-11-10 08:00:00",
        "protocol": "v1",
    }
    item.update(kw)
    return item


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
# Top13 #3 / P0-6 / E8：启动入口与鉴权
# --------------------------------------------------------------------------

def test_create_app_refuses_to_start_without_token(client, monkeypatch):
    """Top13 #3：没配 WEIXIN_TOKEN 时 create_app 直接失败，不开无鉴权服务。"""
    monkeypatch.setattr(app_module, "TOKEN", "")
    monkeypatch.setattr(app_module, "ALLOW_NO_AUTH", "")
    with pytest.raises(RuntimeError):
        app_module.create_app()


def test_create_app_allows_explicit_no_auth(client, monkeypatch):
    """Top13 #3：显式 WEIXIN_ALLOW_NO_AUTH=1 才允许无鉴权启动。"""
    monkeypatch.setattr(app_module, "TOKEN", "")
    monkeypatch.setattr(app_module, "ALLOW_NO_AUTH", "1")
    assert app_module.create_app() is flask_app


def test_create_app_initializes_db_idempotently(client, monkeypatch):
    """Top13 #3 / E8：WSGI 入口自己建库，重复调用安全（不再只 import 不 init）。"""
    monkeypatch.setattr(app_module, "_db_ready", False)
    app_module.create_app()
    app_module.create_app()
    conn = sqlite3.connect(app_module.DB_PATH)
    try:
        tables = {r[0] for r in conn.execute(
            "SELECT name FROM sqlite_master WHERE type='table'")}
    finally:
        conn.close()
    assert {"messages", "analyses"} <= tables


def test_module_level_app_serves_without_manual_init(client, monkeypatch):
    """P0-6：即使没跑过 create_app，请求进来时也会幂等建库（不再 500）。"""
    monkeypatch.setattr(app_module, "_db_ready", False)
    resp = client.get("/messages")
    assert resp.status_code == 200


# --------------------------------------------------------------------------
# Top13 #4 / #7 / B1：脏数据逐条隔离
# --------------------------------------------------------------------------

def test_weixin_isolates_unencodable_item(client):
    """Top13 #4/#7：一条毒丸不能把整批拖成 500，好条目照常入库并回 207。"""
    bad = _msg(text="\ud800", conv="c1")
    good = _msg(text="正常", conv="c1", ts=1700000000001)
    resp = client.post("/weixin", json=[bad, good])
    assert resp.status_code == 207
    data = resp.get_json()
    assert data["invalid"] == 1
    assert data["rejected"] == [0]
    assert data["received"] == 1
    assert _db_count("messages") == 1


def test_analysis_isolates_unencodable_item(client):
    """Top13 #7 / B1：/analysis 一条脏数据只隔离自己，其余入库并回 207。"""
    resp = client.post("/analysis", json=[
        _analysis_item(case_id="\ud800"),
        _analysis_item(case_id="ok-1"),
    ])
    assert resp.status_code == 207
    data = resp.get_json()
    assert data["invalid"] == 1
    assert data["rejected"] == [0]
    assert data["rejected_ids"] == ["\ud800"]
    assert data["received"] == 1
    assert _db_count("analyses") == 1


def test_analysis_rejects_out_of_range_message_count(client):
    """Top13 #4：message_count 越界判 invalid，不进库。"""
    resp = client.post("/analysis", json=[_analysis_item(message_count=-5)])
    assert resp.status_code == 207
    assert resp.get_json()["invalid"] == 1
    assert _db_count("analyses") == 0


def test_analysis_rejects_oversized_identifiers(client):
    """B4：case_id / conversation 超长判 invalid，不进库。"""
    resp = client.post("/analysis", json=[_analysis_item(case_id="x" * 600)])
    assert resp.status_code == 207
    assert resp.get_json()["invalid"] == 1
    assert _db_count("analyses") == 0


# --------------------------------------------------------------------------
# Top13 #5 / E12：先入库后存档 + JSONL 轮转
# --------------------------------------------------------------------------

def test_jsonl_archive_only_contains_persisted_rows(client):
    """Top13 #5：存档只包含真正入库的条目（库提交之后才写存档）。"""
    resp = client.post("/weixin", json=[_msg(text="\ud800"), _msg(text="好")])
    assert resp.status_code == 207
    assert _jsonl_lines() == 1
    assert _db_count("messages") == 1


def test_duplicate_report_does_not_duplicate_archive(client):
    """Top13 #5：重复上报不能让 JSONL 存档翻倍。"""
    m = _msg()
    client.post("/weixin", json=[m])
    client.post("/weixin", json=[m])
    assert _jsonl_lines() == 1
    assert _db_count("messages") == 1


def test_jsonl_archive_rotates_when_too_large(client, monkeypatch):
    """E12：JSONL 存档超过上限自动轮转一份，不无界增长。"""
    monkeypatch.setattr(app_module, "JSONL_MAX_BYTES", 64)
    client.post("/weixin", json=[_msg(text="a" * 200)])
    assert not os.path.exists(app_module.JSONL_PATH + ".1")
    client.post("/weixin", json=[_msg(text="b" * 200, ts=1700000000001)])
    assert os.path.exists(app_module.JSONL_PATH + ".1")


# --------------------------------------------------------------------------
# Top13 #6 / #10 / B2 / B4：顾问存档与字段收敛
# --------------------------------------------------------------------------

def test_advisor_corrupt_archive_refuses_to_write(client):
    """Top13 #10 / B2：存档损坏时拒绝写入，原文件原样保留（不覆盖历史）。"""
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
        assert f.read() == before


def test_advisor_get_reports_corruption_without_500(client):
    """Top13 #10：GET /advisor 存档损坏时回 200 + ok:false（控制台不整页崩）。"""
    with open(app_module.ADVISOR_PATH, "w", encoding="utf-8") as f:
        f.write("{ broken")
    resp = client.get("/advisor")
    assert resp.status_code == 200
    body = resp.get_json()
    assert body["ok"] is False
    assert body["reports"] == []
    assert "corrupt" in body["error"]


def test_advisor_normalizes_fields(client):
    """Top13 #6 / B4：case_count / period_days 收敛成 int，长文本截断。"""
    resp = client.post("/advisor", json=[{
        "report_id": "r1",
        "created_at": "2026-11-10 08:00:00",
        "period_days": "7",
        "case_count": "12",
        "summary": "x" * 9000,
        "model_used": "m" * 500,
        "suggestions": [
            {"type": "prompt", "title": "t" * 900, "detail": "d" * 9000},
            "not-a-dict",
        ],
    }])
    assert resp.status_code == 200
    rep = client.get("/advisor").get_json()["reports"][0]
    assert rep["period_days"] == 7
    assert rep["case_count"] == 12
    assert len(rep["summary"]) == app_module.ADVISOR_MAX_SUMMARY_LEN
    assert len(rep["model_used"]) == app_module.ADVISOR_MAX_MODEL_LEN
    assert len(rep["suggestions"]) == 1
    assert len(rep["suggestions"][0]["title"]) == app_module.ADVISOR_MAX_TITLE_LEN
    assert len(rep["suggestions"][0]["detail"]) == app_module.ADVISOR_MAX_DETAIL_LEN


def test_advisor_clamps_out_of_range_numbers(client):
    """Top13 #6：越界数值收敛到上限，不无限膨胀。"""
    client.post("/advisor", json=[{
        "report_id": "r1",
        "case_count": 10 ** 9,
        "period_days": 99999,
    }])
    rep = client.get("/advisor").get_json()["reports"][0]
    assert rep["case_count"] == app_module.ADVISOR_MAX_CASE_COUNT
    assert rep["period_days"] == app_module.ADVISOR_MAX_PERIOD_DAYS


def test_advisor_rejects_oversized_report_id(client):
    """B4：report_id 超长判 invalid（207），不进存档。"""
    resp = client.post("/advisor", json=[{"report_id": "r" * 600}])
    assert resp.status_code == 207
    assert resp.get_json()["invalid"] == 1
    assert client.get("/advisor").get_json()["count"] == 0


# --------------------------------------------------------------------------
# Top13 #9 / E13：GET /analysis 归一与脏行容错
# --------------------------------------------------------------------------

def test_analysis_get_normalizes_raw_numeric_columns(client):
    """Top13 #9 / E13：GET /analysis 返回归一后的 importance / need_action。"""
    conn = sqlite3.connect(app_module.DB_PATH)
    conn.execute(
        "INSERT INTO analyses (case_id, conversation, window_end,"
        " message_count, need_action, importance, received_at)"
        " VALUES (?,?,?,?,?,?,?)",
        ("raw", "conv", 1700000000000, 5, 1, 7, 1700000000000))
    conn.commit()
    conn.close()
    row = client.get("/analysis").get_json()["analyses"][0]
    assert row["importance"] == "high"
    assert row["need_action"] is True


def test_analysis_get_tolerates_garbage_numeric_columns(client):
    """Top13 #4/#9：脏数值列给 null，不让整页 500。"""
    conn = sqlite3.connect(app_module.DB_PATH)
    conn.execute(
        "INSERT INTO analyses (case_id, conversation, window_end,"
        " message_count, need_action, importance, received_at)"
        " VALUES (?,?,?,?,?,?,?)",
        ("dirty", "conv", "abc", "xyz", 0, 3, 1700000000000))
    conn.commit()
    conn.close()
    resp = client.get("/analysis")
    assert resp.status_code == 200
    row = resp.get_json()["analyses"][0]
    assert row["window_end"] is None
    assert row["message_count"] is None
    assert row["importance"] == "mid"


def test_analysis_get_skips_broken_row(client, monkeypatch):
    """Top13 #9：单行构造异常只跳过这一行，整页照常返回并回报 skipped。"""
    conn = sqlite3.connect(app_module.DB_PATH)
    for cid, imp in (("ok1", 7), ("boom", "boom")):
        conn.execute(
            "INSERT INTO analyses (case_id, conversation, window_end,"
            " message_count, need_action, importance, received_at)"
            " VALUES (?,?,?,?,?,?,?)",
            (cid, "conv", 1700000000000, 1, 0, imp, 1700000000000))
    conn.commit()
    conn.close()
    origin = app_module._importance_label

    def _boom(value):
        if value == "boom":
            raise ValueError("broken row")
        return origin(value)

    monkeypatch.setattr(app_module, "_importance_label", _boom)
    resp = client.get("/analysis")
    assert resp.status_code == 200
    body = resp.get_json()
    assert body["count"] == 1
    assert body["skipped"] == 1
    assert body["analyses"][0]["case_id"] == "ok1"


# --------------------------------------------------------------------------
# B8 / N2：会话前缀匹配（走索引）+ contains 兼容
# --------------------------------------------------------------------------

def _seed_conversations(client):
    client.post("/weixin", json=[
        _msg(text="1", ts=1700000000000, conv="项目组"),
        _msg(text="2", ts=1700000000001, conv="项目组A"),
        _msg(text="3", ts=1700000000002, conv="老项目组"),
    ])


def test_messages_conversation_prefix_match(client):
    """B8：默认前缀匹配，老会话名不被误命中。"""
    _seed_conversations(client)
    resp = client.get("/messages?conversation=项目组")
    convs = {m["conversation"] for m in resp.get_json()["messages"]}
    assert convs == {"项目组", "项目组A"}


def test_messages_conversation_contains_mode_keeps_old_behavior(client):
    """B8：conversation_match=contains 保留旧的包含匹配口径。"""
    _seed_conversations(client)
    resp = client.get("/messages?conversation=项目组&conversation_match=contains")
    convs = {m["conversation"] for m in resp.get_json()["messages"]}
    assert convs == {"项目组", "项目组A", "老项目组"}


def test_messages_conversation_filter_uses_indexable_range(client, monkeypatch):
    """B8 / N2：SQL 用范围比较而不是 LIKE '%x%'（前导通配符让索引失效）。"""
    _seed_conversations(client)
    captured = []
    real_connect = app_module._connect

    class _Tracing:
        def __init__(self, conn):
            self._conn = conn

        def execute(self, sql, *args, **kwargs):
            captured.append(sql)
            return self._conn.execute(sql, *args, **kwargs)

        def __getattr__(self, name):
            return getattr(self._conn, name)

    monkeypatch.setattr(app_module, "_connect",
                        lambda: _Tracing(real_connect()))
    client.get("/messages?conversation=项目组")
    sqls = [s for s in captured if "FROM messages" in s]
    assert sqls, "没有抓到 /messages 的查询"
    assert "conversation >= ?" in sqls[0]
    assert "LIKE ?" not in sqls[0]


def test_messages_has_conversation_index(client):
    """B8：会话列有 (conversation, timestamp) 索引，前缀过滤不走全表扫。"""
    conn = sqlite3.connect(app_module.DB_PATH)
    try:
        names = {r[1] for r in conn.execute("PRAGMA index_list(messages)")}
    finally:
        conn.close()
    assert "idx_messages_conv_ts" in names


def test_analysis_conversation_prefix_match_and_index(client):
    """B8：/analysis 同样改前缀匹配，analyses 表补了对应索引。"""
    for cid, conv in (("a", "项目组"), ("b", "项目组A"), ("c", "老项目组")):
        client.post("/analysis", json=[_analysis_item(case_id=cid, conv=conv)])
    resp = client.get("/analysis?conversation=项目组")
    convs = {a["conversation"] for a in resp.get_json()["analyses"]}
    assert convs == {"项目组", "项目组A"}
    conn = sqlite3.connect(app_module.DB_PATH)
    try:
        names = {r[1] for r in conn.execute("PRAGMA index_list(analyses)")}
    finally:
        conn.close()
    assert "idx_analyses_conv_ts" in names


# --------------------------------------------------------------------------
# Top13 #12 / N1 / N3：提取接口的可重试语义
# --------------------------------------------------------------------------

def test_extract_returns_503_when_main_db_is_busy(client, monkeypatch):
    """Top13 #12：主库被占用时提取接口回 503 + Retry-After（可重试）。"""
    monkeypatch.setattr(app_module, "_probe_main_db_writable",
                        lambda *a, **k: ("busy", "database is locked"))
    resp = client.post("/admin/extract")
    assert resp.status_code == 503
    assert resp.headers.get("Retry-After") == "5"
    assert resp.get_json()["ok"] is False


def test_extract_busy_failure_is_classified(client, monkeypatch):
    """Top13 #12 / N3：后台提取遇到主库占用时标 error_kind=busy，running 复位。"""
    def _boom(leg):
        raise sqlite3.OperationalError("database is locked")

    monkeypatch.setattr(app_module, "_import_extract_module", _boom)
    app_module._extract_status["running"] = True
    app_module._run_extract_bg(False, "android")
    last = app_module._extract_status["last"]
    assert last["ok"] is False
    assert last["android"]["error_kind"] == "busy"
    assert app_module._extract_status["running"] is False


# --------------------------------------------------------------------------
# 参数边界与并发
# --------------------------------------------------------------------------

def test_messages_limit_is_clamped(client):
    """B4：limit 非法 / 超上限不 500，按默认值或上限收敛。"""
    for query in ("?limit=abc", "?limit=-3", "?limit=999999"):
        resp = client.get("/messages" + query)
        assert resp.status_code == 200
        assert len(resp.get_json()["messages"]) <= 1000


def test_concurrent_weixin_posts_are_serialized(client):
    """并发上报不能丢条：写锁 + SAVEPOINT 保证每条都落库。"""
    statuses = []

    def _worker(index):
        with flask_app.test_client() as c:
            resp = c.post("/weixin", json=[_msg(text="m%d" % index,
                                                ts=1700000000000 + index,
                                                conv="c")])
            statuses.append(resp.status_code)

    threads = [threading.Thread(target=_worker, args=(i,)) for i in range(8)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    assert statuses == [200] * 8
    assert _db_count("messages") == 8
