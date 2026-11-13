# -*- coding: utf-8 -*-
# Copyright (c) 2026 solidjoker
# SPDX-License-Identifier: MIT
"""GET /stats 与 GET /suggests/next 的契约测试。

为什么单独立一个文件：这两条接口只读、不产生写入，但口径最容易被改坏 ——
概览条与统计 Tab 的数字全靠它们，错一个数用户就会看到「翻页前后不一样」。

每条用例自己建临时目录（改 app 模块属性），互不干扰。
"""

import json
import os
import sys
import tempfile
import time

import pytest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import app as app_module  # noqa: E402
from app import app as flask_app  # noqa: E402


DAY_MS = 24 * 3600 * 1000


@pytest.fixture()
def api():
    """隔离数据目录的 Flask test client（与 test_app.py 同款做法）。"""
    tmp = tempfile.mkdtemp(prefix="notifyme_stats_")
    old = (app_module.DATA_DIR, app_module.DB_PATH, app_module.JSONL_PATH,
           app_module.ADVISOR_PATH, app_module.TOKEN, app_module.ALLOW_NO_AUTH,
           app_module._db_ready)
    app_module.DATA_DIR = tmp
    app_module.DB_PATH = os.path.join(tmp, "messages.db")
    app_module.JSONL_PATH = os.path.join(tmp, "messages.jsonl")
    app_module.ADVISOR_PATH = os.path.join(tmp, "advisor_reports.json")
    app_module.TOKEN = ""
    app_module.ALLOW_NO_AUTH = "1"
    app_module._db_ready = False
    app_module.init_db()
    app_module._auth_fail_events.clear()
    yield flask_app.test_client()
    (app_module.DATA_DIR, app_module.DB_PATH, app_module.JSONL_PATH,
     app_module.ADVISOR_PATH, app_module.TOKEN, app_module.ALLOW_NO_AUTH,
     app_module._db_ready) = old
    app_module._db_ready = False


def _now_ms():
    return int(time.time() * 1000)


def _insert_messages(rows):
    """rows: [(sender, text, timestamp, conversation, source, pkg)]"""
    with app_module._write_lock:
        conn = app_module._connect()
        try:
            for sender, text, ts, conv, source, pkg in rows:
                conn.execute(
                    "INSERT INTO messages"
                    " (sender, text, timestamp, conversation, is_group, received_at, source, pkg)"
                    " VALUES (?, ?, ?, ?, 0, ?, ?, ?)",
                    (sender, text, ts, conv, ts, source, pkg))
            conn.commit()
        finally:
            conn.close()


def _insert_analyses(rows):
    """rows: [(case_id, conversation, need_action, pkg, redacted, redaction_hits)]"""
    now = _now_ms()
    with app_module._write_lock:
        conn = app_module._connect()
        try:
            for (case_id, conv, need_action, pkg, redacted, hits) in rows:
                conn.execute(
                    "INSERT INTO analyses"
                    " (case_id, conversation, window_end, message_count, need_action,"
                    "  importance, escalated, s1, s2, analyzed_at, protocol, received_at,"
                    "  pkg, redacted, redaction_hits, todo_text, todo_due)"
                    " VALUES (?, ?, ?, 1, ?, 'high', 0, '', '', ?, 'v2', ?, ?, ?, ?, '', 0)",
                    (case_id, conv, now, need_action, now, now, pkg, redacted, hits))
            conn.commit()
        finally:
            conn.close()


def _insert_queue(rows):
    """rows: [(device_id, task_id, kind, title, state, detail)]"""
    now = _now_ms()
    with app_module._write_lock:
        conn = app_module._connect()
        try:
            for device_id, task_id, kind, title, state, detail in rows:
                conn.execute(
                    "INSERT OR REPLACE INTO queue_tasks"
                    " (device_id, task_id, kind, title, state, detail, queued_at, updated_at)"
                    " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    (device_id, task_id, kind, title, state, detail, now, now))
            conn.commit()
        finally:
            conn.close()


# ---------------------------------------------------------------- 鉴权


def test_stats_requires_token(api):
    """无令牌时 /stats 必须 401（不能把整库统计裸奔）。"""
    app_module.TOKEN = "s3cret"
    resp = api.get("/stats")
    assert resp.status_code == 401
    assert resp.get_json()["ok"] is False


def test_suggests_requires_token(api):
    """/suggests/next 同样要校验令牌。"""
    app_module.TOKEN = "s3cret"
    resp = api.get("/suggests/next")
    assert resp.status_code == 401


def test_stats_accepts_x_token_header(api):
    """带对令牌就放行。"""
    app_module.TOKEN = "s3cret"
    resp = api.get("/stats", headers={"X-Token": "s3cret"})
    assert resp.status_code == 200
    assert resp.get_json()["ok"] is True


# ---------------------------------------------------------------- 空库口径


def test_empty_database_stats_are_zero(api):
    """空库各段必须是 0 / None，不能是 null 或报错。"""
    data = api.get("/stats").get_json()
    assert data["ok"] is True
    assert data["messages"]["total"] == 0
    assert data["messages"]["convs"] == 0
    assert data["messages"]["last_24h"] == 0
    assert data["messages"]["last_ts"] == 0
    assert data["messages"]["age_days"] is None
    assert data["messages"]["by_pkg"] == []
    assert data["analyses"]["total"] == 0
    assert data["analyses"]["todo"] == 0
    assert data["analyses"]["redacted_cases"] == 0
    assert data["analyses"]["last_report"] == 0
    assert data["analyses"]["age_days"] is None
    assert data["queue"]["total"] == 0
    assert data["queue"]["devices"] == 0


def test_empty_database_suggests_still_gives_advisor_hint(api):
    """空库时至少给「还没有顾问报告」这条（不编数字，只有事实）。"""
    data = api.get("/suggests/next").get_json()
    assert data["ok"] is True
    assert data["count"] == len(data["suggests"])
    types = [item["type"] for item in data["suggests"]]
    assert "check" in types
    assert all(item["evidence"] for item in data["suggests"])
    assert all(item["how"] for item in data["suggests"])
    assert all(item["jump"] for item in data["suggests"])


# ---------------------------------------------------------------- 真实数据口径


def test_stats_counts_messages_convs_and_last_24h(api):
    """消息段：总数 / 会话数 / 近 24 小时 / 最近时间，全部按库里的真值算。"""
    now = _now_ms()
    _insert_messages([
        ("a", "hi", now - 1000, "conv1", "微信", "com.tencent.mm"),
        ("b", "yo", now - 2000, "conv1", "微信", "com.tencent.mm"),
        ("c", "old", now - 3 * DAY_MS, "conv2", "钉钉", "com.dingtalk.app"),
    ])
    data = api.get("/stats").get_json()
    msgs = data["messages"]
    assert msgs["total"] == 3
    assert msgs["convs"] == 2
    assert msgs["last_24h"] == 2
    assert msgs["last_ts"] == now - 1000
    assert msgs["last_received_at"] == now - 1000
    assert msgs["age_days"] == 0
    assert msgs["by_pkg"][0] == {"pkg": "com.tencent.mm", "count": 2}
    assert {"pkg": "com.dingtalk.app", "count": 1} in msgs["by_pkg"]
    assert {"source": "微信", "count": 2} in msgs["by_source"]


def test_stats_pkg_filter_only_affects_messages(api):
    """pkg 过滤只作用于消息段（分析段是全局口径，见 stats_api 的 docstring）。"""
    now = _now_ms()
    _insert_messages([
        ("a", "hi", now - 1000, "conv1", "微信", "com.tencent.mm"),
        ("c", "yo", now - 1000, "conv2", "钉钉", "com.dingtalk.app"),
    ])
    _insert_analyses([("c1", "conv1", 1, "com.tencent.mm", 0, 0)])
    data = api.get("/stats?pkg=com.tencent.mm").get_json()
    assert data["messages"]["total"] == 1
    assert data["messages"]["convs"] == 1
    assert data["analyses"]["total"] == 1


def test_stats_pkg_filter_applies_to_distributions(api):
    """by_pkg / by_source 必须跟着 pkg 过滤走，别和同段的 total 混口径。

    同一段里 total / convs 是过滤后的，分布却是全库的 —— 客户端把两个数并排渲染，
    就会出现「共 2 条，应用分布加起来 3 条」这种自相矛盾的卡片。
    """
    now = _now_ms()
    _insert_messages([
        ("a", "hi", now - 1000, "c1", "微信", "com.tencent.mm"),
        ("b", "yo", now - 2000, "c2", "微信", "com.tencent.mm"),
        ("c", "ok", now - 3000, "c3", "微博", "com.sina.weibo"),
    ])
    all_msgs = api.get("/stats").get_json()["messages"]
    assert sum(x["count"] for x in all_msgs["by_pkg"]) == 3
    assert sum(x["count"] for x in all_msgs["by_source"]) == 3

    msgs = api.get("/stats?pkg=com.tencent.mm").get_json()["messages"]
    assert msgs["total"] == 2
    assert msgs["by_pkg"] == [{"pkg": "com.tencent.mm", "count": 2}]
    # 来源分布同样只剩微信的 2 条：分布之和必须等于同段的 total
    assert sum(x["count"] for x in msgs["by_pkg"]) == msgs["total"]
    assert sum(x["count"] for x in msgs["by_source"]) == msgs["total"]

def test_stats_counts_analysis_todo_and_redaction(api):
    """分析段：总数 / 会话数 / 待办 / 脱敏命中。"""
    _insert_analyses([
        ("c1", "conv1", 1, "com.tencent.mm", 0, 0),
        ("c2", "conv1", 0, "com.tencent.mm", 1, 4),
        ("c3", "conv2", 1, "com.dingtalk.app", 1, 2),
    ])
    ana = api.get("/stats").get_json()["analyses"]
    assert ana["total"] == 3
    assert ana["convs"] == 2
    assert ana["todo"] == 2
    assert ana["todo_convs"] == 2
    assert ana["redacted_cases"] == 2
    assert ana["redaction_hits"] == 6
    assert ana["last_report"] > 0


def test_stats_message_convs_counts_pkg_and_conversation(api):
    """会话数按 (pkg, conversation) 计：同名会话来自不同 App 是两个会话。

    控制台的会话卡与 /messages 的行都用 ckOf() = pkg + SEP + conversation 做身份，
    统计只数 conversation 会让「微信的王五」和「微博的王五」合并成一个，
    统计 Tab 的「总会话数」比实际少，和会话 Tab 的卡片数对不上。
    """
    now = _now_ms()
    _insert_messages([
        ("a", "hi", now - 1000, "王五", "微信", "com.tencent.mm"),
        ("b", "yo", now - 2000, "王五", "微博", "com.sina.weibo"),
        ("c", "ok", now - 3000, "王五", "微信", "com.tencent.mm"),
    ])
    msgs = api.get("/stats").get_json()["messages"]
    assert msgs["total"] == 3
    assert msgs["convs"] == 2, "同名会话来自不同 pkg 必须算两个"
    # pkg 过滤后只剩微信的两条，会话数回到 1
    filtered = api.get("/stats?pkg=com.tencent.mm").get_json()["messages"]
    assert filtered["total"] == 2
    assert filtered["convs"] == 1


def test_stats_analysis_convs_counts_pkg_and_conversation(api):
    """分析侧同一口径：convs 与 todo_convs 都按 (pkg, conversation) 分组。"""
    _insert_analyses([
        ("c1", "王五", 1, "com.tencent.mm", 0, 0),
        ("c2", "王五", 1, "com.sina.weibo", 0, 0),
        ("c3", "王五", 0, "com.tencent.mm", 0, 0),
    ])
    ana = api.get("/stats").get_json()["analyses"]
    assert ana["total"] == 3
    assert ana["convs"] == 2, "同名会话来自不同 pkg 必须算两个"
    assert ana["todo"] == 2
    assert ana["todo_convs"] == 2


def test_stats_conv_count_skips_null_conversation(api):
    """conversation 为 NULL 的脏行不计入会话数（与旧口径一致，别静默算成一个）。"""
    now = _now_ms()
    _insert_messages([
        ("a", "hi", now - 1000, "conv1", "微信", "com.tencent.mm"),
    ])
    with app_module._write_lock:
        conn = app_module._connect()
        try:
            conn.execute(
                "INSERT INTO messages"
                " (sender, text, timestamp, conversation, is_group, received_at, source, pkg)"
                " VALUES (?, ?, ?, NULL, 0, ?, ?, ?)",
                ("z", "dirty", now - 500, now - 500, "微信", "com.tencent.mm"))
            conn.commit()
        finally:
            conn.close()
    msgs = api.get("/stats").get_json()["messages"]
    assert msgs["total"] == 2
    assert msgs["convs"] == 1

def test_stats_counts_queue_by_state_and_device(api):
    """排队段：按状态计数 + 设备台数（与 /queue 的 devices 同口径）。"""
    _insert_queue([
        ("dev-a", "t1", "analysis", "分析 A", "running", ""),
        ("dev-a", "t2", "sync", "同步 B", "queued", ""),
        ("dev-b", "t3", "analysis", "分析 C", "failed", "busy"),
        ("dev-b", "t4", "model", "模型 D", "done", ""),
    ])
    queue = api.get("/stats").get_json()["queue"]
    assert queue["total"] == 4
    assert queue["running"] == 1
    assert queue["queued"] == 1
    assert queue["failed"] == 1
    assert queue["done"] == 1
    assert queue["active"] == 2
    assert queue["devices"] == 2
    assert queue["stale"] == 0
    kinds = {item["kind"]: item["count"] for item in queue["by_kind"]}
    assert kinds == {"analysis": 2, "sync": 1, "model": 1}


def test_stats_marks_stale_queue_tasks_and_excludes_from_state(api):
    """updated_at 超过 TTL 的条目算 stale，且不计入活跃 / 失败。"""
    with app_module._write_lock:
        conn = app_module._connect()
        try:
            conn.execute(
                "INSERT OR REPLACE INTO queue_tasks"
                " (device_id, task_id, kind, title, state, detail, queued_at, updated_at)"
                " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                ("dev-a", "old", "sync", "老任务", "running", "", 0, 1))
            conn.commit()
        finally:
            conn.close()
    queue = api.get("/stats").get_json()["queue"]
    assert queue["total"] == 1
    assert queue["stale"] == 1
    assert queue["running"] == 0
    assert queue["active"] == 0


def test_stats_rejects_bad_tz(api):
    """tz 非法要 400（与 /messages 的报错口径一致）。"""
    resp = api.get("/stats?tz=abc")
    assert resp.status_code == 400
    assert resp.get_json()["ok"] is False


def test_stats_echoes_tz(api):
    """tz 合法就回显成 +08:00 形式，方便前端与 /messages 对齐。"""
    data = api.get("/stats?tz=%2B08:00").get_json()
    assert data["tz_offset_minutes"] == 480
    assert data["tz"] == "+08:00"


# ---------------------------------------------------------------- 建议口径


def test_suggests_todo_rule_fires_with_counts(api):
    """有待办就给「处理待办」建议，且依据里带真实条数与会话数。"""
    _insert_analyses([
        ("c1", "conv1", 1, "com.tencent.mm", 0, 0),
        ("c2", "conv2", 1, "com.tencent.mm", 0, 0),
    ])
    data = api.get("/suggests/next").get_json()
    todo = [item for item in data["suggests"] if item["jump"] == "todo"]
    assert len(todo) == 1
    assert "2" in todo[0]["evidence"]
    assert todo[0]["priority"] == 80


def test_suggests_queue_failed_rule_has_highest_priority(api):
    """有失败任务时第一条就是重跑建议（优先级最高）。"""
    _insert_queue([
        ("dev-a", "t1", "analysis", "分析 A", "failed", "busy"),
    ])
    data = api.get("/suggests/next").get_json()
    first = data["suggests"][0]
    assert first["jump"] == "queue"
    assert first["priority"] == 100
    assert "1" in first["evidence"]


def test_suggests_missing_conversations_rule(api):
    """有消息没分析过 -> 给补跑分析建议。"""
    now = _now_ms()
    _insert_messages([
        ("a", "hi", now - 1000, "conv1", "微信", "com.tencent.mm"),
    ])
    data = api.get("/suggests/next").get_json()
    missing = [item for item in data["suggests"] if item["jump"] == "conv"]
    assert len(missing) == 1
    assert missing[0]["type"] == "rerun"


def test_suggests_silent_when_everything_is_fine(api):
    """数据齐、没有失败、没有过期、顾问刚更新 -> 一条建议都不该编。"""
    now = _now_ms()
    _insert_messages([
        ("a", "hi", now - 1000, "conv1", "微信", "com.tencent.mm"),
    ])
    _insert_analyses([("c1", "conv1", 0, "com.tencent.mm", 0, 0)])
    _insert_queue([("dev-a", "t1", "sync", "同步", "done", "")])
    os.makedirs(app_module.DATA_DIR, exist_ok=True)
    with open(app_module._advisor_jsonl_path(), "w", encoding="utf-8") as fh:
        fh.write(json.dumps({"report_id": "r1", "created_at": str(now)}) + "\n")
    data = api.get("/suggests/next").get_json()
    assert data["suggests"] == []
    assert data["count"] == 0


def _write_advisor(rows):
    """直接写顾问 JSONL（端上上报后的落盘形状：created_at 被 _clip 成字符串）。"""
    os.makedirs(app_module.DATA_DIR, exist_ok=True)
    with open(app_module._advisor_jsonl_path(), "w", encoding="utf-8") as fh:
        for row in rows:
            fh.write(json.dumps(row, ensure_ascii=False) + "\n")


def test_suggests_advisor_age_rule_fires_on_snake_case_created_at(api):
    """E-49：端上上报的键是 created_at（下划线），顾问过期规则必须命中。

    修前 _advisor_state 读驼峰 createdAt，生产里永远读不到 → 最近一次顾问
    报告时间恒为 0 → priority 60 的 advisor_age_days 与 priority 50 的
    advisor_total 两条建议从未产出过（空库场景还会被前者「骗」过去）。
    """
    now = _now_ms()
    _insert_messages([("a", "hi", now - 1000, "conv1", "微信", "com.tencent.mm")])
    _insert_analyses([("c1", "conv1", 0, "com.tencent.mm", 0, 0)])
    _insert_queue([("dev-a", "t1", "sync", "同步", "done", "")])
    # created_at 落盘时是字符串（storage._normalize_advisor_item 的 _clip）
    _write_advisor([{"report_id": "old", "created_at": str(now - 5 * DAY_MS)}])
    data = api.get("/suggests/next").get_json()
    adv = [item for item in data["suggests"] if item["jump"] == "adv"]
    assert len(adv) == 1, data["suggests"]
    assert adv[0]["priority"] == 60
    assert "5" in adv[0]["evidence"]


def test_suggests_advisor_total_rule_fires_when_archive_empty(api):
    """没有顾问报告 → advisor_total == 0 的那条建议命中（jump=adv）。"""
    data = api.get("/suggests/next").get_json()
    adv = [item for item in data["suggests"] if item["jump"] == "adv"]
    assert len(adv) == 1, data["suggests"]
    assert adv[0]["priority"] == 50


def test_suggests_returns_at_most_three(api):
    """上限 3 条：数据再差也不能把整页刷满建议。"""
    now = _now_ms()
    _insert_messages([
        ("a", "hi", now - 1000, "conv1", "微信", "com.tencent.mm"),
        ("b", "yo", now - 1000, "conv2", "微信", "com.tencent.mm"),
    ])
    _insert_analyses([("c1", "conv1", 1, "com.tencent.mm", 0, 0)])
    _insert_queue([
        ("dev-a", "t1", "analysis", "A", "failed", ""),
        ("dev-a", "t2", "sync", "B", "queued", ""),
    ])
    data = api.get("/suggests/next").get_json()
    assert data["count"] <= 3
    assert len(data["suggests"]) == data["count"]


def test_suggests_priorities_are_descending(api):
    """返回顺序必须按 priority 降序（前端直接按数组顺序渲染）。"""
    now = _now_ms()
    _insert_messages([
        ("a", "hi", now - 1000, "conv1", "微信", "com.tencent.mm"),
    ])
    _insert_analyses([("c1", "conv1", 1, "com.tencent.mm", 0, 0)])
    _insert_queue([("dev-a", "t1", "analysis", "A", "failed", "")])
    data = api.get("/suggests/next").get_json()
    priorities = [item["priority"] for item in data["suggests"]]
    assert priorities == sorted(priorities, reverse=True)


def test_suggests_echoes_pkg(api):
    """pkg 原样回显（前端据此知道当前是全库还是单 App 口径）。"""
    data = api.get("/suggests/next?pkg=com.tencent.mm").get_json()
    assert data["pkg"] == "com.tencent.mm"
    blank = api.get("/suggests/next").get_json()
    assert blank["pkg"] is None
