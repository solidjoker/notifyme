# -*- coding: utf-8 -*-
# Copyright (c) 2026 solidjoker
# SPDX-License-Identifier: MIT
"""读出口的布尔口径：util._db_bool 的真值表 + 与 SQL `col = 1` 的同源性。

背景：写入口早就用严格真值表 util._safe_bool（管上报侧 JSON 的任意值），
但读出口（从 SQLite 读回 0/1 列）用的是 Python 原生真值 bool(r[i])。
SQLite 的列有类型亲和：INTEGER 亲和列能原样存下 'false' / 'true' / '' / 'abc'
这类非数字文本，而 Python 的真值判断把它们全当成真（bool('false') is True）；
/stats 的聚合却走 SQL `need_action = 1` —— 两边一打架就是
「概览条说 0 条待办、列表里却挂着一条 ⚡待办」。

本文件钉三件事：
1. util._db_bool 的真值表（只有 1 / '1' 算真，数字 2 / 0.5 都不算）；
2. 不变量：_db_bool(v) 必须逐条等于 SQL 的 `col = 1`
   （在真 SQLite 上实测，不抄一遍实现）；
3. 读出口真的走了 _db_bool：脏行在 /messages、/analysis、/ 三个出口上都不再被当真，
   且 /analysis?todo_only=1 的过滤结果与逐行读出口完全一致。
"""
import os
import sqlite3
import sys
import tempfile
import time

import pytest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import app as app_module  # noqa: E402
import util as server_util  # noqa: E402
from app import app as flask_app  # noqa: E402


def _now_ms():
    return int(time.time() * 1000)


def _now_text():
    return time.strftime("%Y-%m-%d %H:%M:%S")


@pytest.fixture()
def api():
    """隔离数据目录的 Flask test client（与 test_stats_api.py 同款做法）。"""
    tmp = tempfile.mkdtemp(prefix="notifyme_bool_")
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
    # 排队过期行清理是进程级节流：不复位会让用例随执行顺序飘。
    app_module._queue_cleanup_at = 0
    app_module.init_db()
    app_module._auth_fail_events.clear()
    yield flask_app.test_client()
    (app_module.DATA_DIR, app_module.DB_PATH, app_module.JSONL_PATH,
     app_module.ADVISOR_PATH, app_module.TOKEN, app_module.ALLOW_NO_AUTH,
     app_module._db_ready, app_module._queue_cleanup_at) = old
    app_module._db_ready = False


def _insert_messages(rows):
    """rows: [(sender, text, timestamp, conversation, is_group)]"""
    with app_module._write_lock:
        conn = app_module._connect()
        try:
            for sender, text, ts, conv, is_group in rows:
                conn.execute(
                    "INSERT INTO messages"
                    " (sender, text, timestamp, conversation, is_group, received_at,"
                    "  source, pkg)"
                    " VALUES (?, ?, ?, ?, ?, ?, 'android-notification',"
                    "  'com.tencent.mm')",
                    (sender, text, ts, conv, is_group, ts))
            conn.commit()
        finally:
            conn.close()


def _insert_analyses(rows):
    """rows: [(case_id, conversation, need_action, escalated, filtered, redacted)]"""
    now = _now_ms()
    with app_module._write_lock:
        conn = app_module._connect()
        try:
            for (case_id, conv, need_action, escalated, filtered, redacted) in rows:
                conn.execute(
                    "INSERT INTO analyses"
                    " (case_id, conversation, window_end, message_count, need_action,"
                    "  importance, escalated, s1, s2, analyzed_at, protocol,"
                    "  received_at, pkg, redacted, redaction_hits, todo_text,"
                    "  todo_due, filtered)"
                    " VALUES (?, ?, ?, 1, ?, 'high', ?, '', '', ?, 'v2', ?, ?, ?, ?,"
                    "  '', 0, ?)",
                    (case_id, conv, now, need_action, escalated, _now_text(), now,
                     "com.tencent.mm", redacted, "[]", filtered))
            conn.commit()
        finally:
            conn.close()


@pytest.mark.parametrize("value,expected", [
    (1, True),
    (True, True),
    ("1", True),
    (" 1 ", True),
    ("01", True),
    ("1.0", True),
    ("+1", True),
    ("1e0", True),
    (0, False),
    (False, False),
    ("0", False),
    (" 0 ", False),
    ("false", False),
    ("False", False),
    ("FALSE", False),
    ("true", False),
    ("yes", False),
    ("on", False),
    ("", False),
    ("abc", False),
    (None, False),
    (0.5, False),
    (2, False),
    (-1, False),
    ("nan", False),
    ("inf", False),
])
def test_db_bool_truth_table(value, expected):
    """真值表：只有 1 / '1'（及其等价数字写法）算真。"""
    assert server_util._db_bool(value) is expected, repr(value)


def test_db_bool_default_only_covers_null():
    """default 只兜 None 与认不出的类型；明确的假值不受 default 影响。"""
    assert server_util._db_bool(None, True) is True
    assert server_util._db_bool(None, False) is False
    assert server_util._db_bool(0, True) is False
    assert server_util._db_bool("0", True) is False
    assert server_util._db_bool("abc", True) is False


def test_db_bool_differs_from_safe_bool_on_text_true():
    """刻意差异：'true' 在写入口（_safe_bool）是真，在读出口（_db_bool）是假。

    因为读出口要对齐的是 SQL 的 `col = 1`，而 SQLite 不认 'true'。
    两套真值表分工不同，别把这条当 bug 改回去。
    """
    assert server_util._safe_bool("true") is True
    assert server_util._db_bool("true") is False


def test_db_bool_matches_sql_equals_one_on_integer_column():
    """不变量：读出口判定必须与 /stats 聚合用的 SQL `col = 1` 完全一致。"""
    samples = [1, 0, "1", "0", "false", "true", "", "abc", 0.5, 2, -1,
               "01", "1.0", "+1", "1e0", None]
    conn = sqlite3.connect(":memory:")
    try:
        conn.execute("CREATE TABLE t (id INTEGER PRIMARY KEY, flag INTEGER)")
        for i, value in enumerate(samples):
            conn.execute("INSERT INTO t (id, flag) VALUES (?, ?)", (i, value))
        rows = conn.execute("SELECT flag, flag = 1 FROM t").fetchall()
    finally:
        conn.close()
    assert len(rows) == len(samples)
    for stored, sql_flag in rows:
        assert server_util._db_bool(stored) is bool(sql_flag), (
            "库里的值 %r：_db_bool=%s，SQL `flag = 1`=%s"
            % (stored, server_util._db_bool(stored), sql_flag))


def test_db_bool_matches_sql_equals_one_on_text_column():
    """TEXT 亲和的老库：'0' / '1' 仍是文本，两边判定仍要一致。"""
    samples = [1, 0, "1", "0", "false", ""]
    conn = sqlite3.connect(":memory:")
    try:
        conn.execute("CREATE TABLE t (id INTEGER PRIMARY KEY, flag TEXT)")
        for i, value in enumerate(samples):
            conn.execute("INSERT INTO t (id, flag) VALUES (?, ?)", (i, value))
        rows = conn.execute("SELECT flag, flag = 1 FROM t").fetchall()
    finally:
        conn.close()
    assert len(rows) == len(samples)
    for stored, sql_flag in rows:
        assert server_util._db_bool(stored) is bool(sql_flag), (
            "库里的值 %r：_db_bool=%s，SQL `flag = 1`=%s"
            % (stored, server_util._db_bool(stored), sql_flag))


def test_messages_readout_is_group_is_not_python_truthy(api):
    """脏行 is_group='false' 在 /messages 上不得被当成群聊。"""
    _insert_messages([
        ("张三", "私聊消息", _now_ms() - 2000, "同事群", "false"),
        ("李四", "群聊消息", _now_ms() - 1000, "同事群", 1),
    ])
    resp = api.get("/messages?limit=10")
    assert resp.status_code == 200
    rows = {m["sender"]: m for m in resp.get_json()["messages"]}
    assert rows["张三"]["is_group"] is False
    assert rows["李四"]["is_group"] is True


def test_analysis_readout_flags_are_not_python_truthy(api):
    """脏行 need_action/escalated/filtered/redacted='false' 全部读成假。"""
    _insert_analyses([
        ("case-dirty", "脏会话", "false", "false", "false", "false"),
        ("case-clean", "干净会话", 1, 0, 0, 1),
    ])
    resp = api.get("/analysis?limit=10")
    assert resp.status_code == 200
    rows = {a["case_id"]: a for a in resp.get_json()["analyses"]}
    dirty = rows["case-dirty"]
    assert dirty["need_action"] is False
    assert dirty["escalated"] is False
    assert dirty["filtered"] is False
    assert dirty["redacted"] is False
    clean = rows["case-clean"]
    assert clean["need_action"] is True
    assert clean["redacted"] is True


def test_analysis_todo_filter_matches_row_level_readout(api):
    """列表过滤（SQL need_action = 1）与逐行读出口必须给出同一个集合。"""
    _insert_analyses([
        ("case-dirty", "脏会话", "false", 0, 0, 0),
        ("case-clean", "干净会话", 1, 0, 0, 0),
    ])
    all_rows = api.get("/analysis?limit=10").get_json()["analyses"]
    todo_rows = api.get("/analysis?limit=10&todo_only=1").get_json()["analyses"]
    assert [a["case_id"] for a in todo_rows] == ["case-clean"]
    assert [a["case_id"] for a in all_rows if a["need_action"]] == [
        a["case_id"] for a in todo_rows]


def test_stats_todo_count_agrees_with_readout(api):
    """概览条（SQL 聚合）与列表读出口不能各说各话。"""
    _insert_analyses([
        ("case-dirty", "脏会话", "false", 0, 0, 0),
        ("case-clean", "干净会话", 1, 0, 0, 0),
    ])
    stats = api.get("/stats").get_json()
    assert stats["analyses"]["todo"] == 1
    rows = api.get("/analysis?limit=10").get_json()["analyses"]
    assert sum(1 for a in rows if a["need_action"]) == stats["analyses"]["todo"]


def test_index_page_group_badge_skips_dirty_row(api):
    """极简页（GET /）的「群聊」徽标同样不能被脏行点亮。"""
    _insert_messages([
        ("张三", "私聊消息", _now_ms() - 2000, "同事群", "false"),
        ("李四", "群聊消息", _now_ms() - 1000, "同事群", 1),
    ])
    resp = api.get("/")
    assert resp.status_code == 200
    html = resp.get_data(as_text=True)
    # 按每条消息的 <div class="msg"> 块拆开看，别靠整页包含判断
    blocks = html.split('<div class="msg">')
    dirty = [b for b in blocks if "张三" in b]
    clean = [b for b in blocks if "李四" in b]
    assert dirty and clean
    assert "群聊" not in dirty[0]  # 脏行不挂群聊徽标
    assert "群聊" in clean[0]      # 干净行照旧挂徽标
