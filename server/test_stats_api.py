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
import storage as storage_module  # noqa: E402
from app import app as flask_app  # noqa: E402


DAY_MS = 24 * 3600 * 1000


@pytest.fixture()
def api():
    """隔离数据目录的 Flask test client（与 test_app.py 同款做法）。"""
    tmp = tempfile.mkdtemp(prefix="notifyme_stats_")
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
    # 排队过期行清理是进程级节流：不复位会让「插了老行再断言」的用例随执行顺序飘。
    app_module._queue_cleanup_at = 0
    app_module.init_db()
    app_module._auth_fail_events.clear()
    yield flask_app.test_client()
    (app_module.DATA_DIR, app_module.DB_PATH, app_module.JSONL_PATH,
     app_module.ADVISOR_PATH, app_module.TOKEN, app_module.ALLOW_NO_AUTH,
     app_module._db_ready, app_module._queue_cleanup_at) = old
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
    assert data["queue"]["devices_active"] == 0


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
    # dev-a 有 running+queued（活跃 2）、dev-b 只剩 failed+done（活跃 0）：
    # 设备台数是 2，但「还在跑的设备」只有 1 台
    assert queue["devices_active"] == 1
    assert queue["stale"] == 0
    kinds = {item["kind"]: item["count"] for item in queue["by_kind"]}
    assert kinds == {"analysis": 2, "sync": 1, "model": 1}


def test_stats_marks_stale_queue_tasks_and_excludes_from_state(api):
    """updated_at 超过 TTL 的条目算 stale，且不计入活跃 / 失败。

    老行的 updated_at 取「一小时前」而不是 1：读取路径现在会顺带清掉超过
    保留期（7 天）的行，1970 年的行在 before_request 就被删掉了，
    这里要验的是「过期但还在库里」的中间态。
    """
    with app_module._write_lock:
        conn = app_module._connect()
        try:
            conn.execute(
                "INSERT OR REPLACE INTO queue_tasks"
                " (device_id, task_id, kind, title, state, detail, queued_at, updated_at)"
                " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                ("dev-a", "old", "sync", "老任务", "running", "", 0,
                 _now_ms() - 60 * 60 * 1000))
            conn.commit()
        finally:
            conn.close()
    queue = api.get("/stats").get_json()["queue"]
    assert queue["total"] == 1
    assert queue["stale"] == 1
    assert queue["running"] == 0
    assert queue["active"] == 0
    # 这台设备只剩过期行，不算「还在跑的设备」
    assert queue["devices"] == 1
    assert queue["devices_active"] == 0


def test_stats_devices_active_excludes_zombie_device(api):
    """设备停报后过期行还在库里：devices 报 2 台，devices_active 只能是 1 台。

    缺陷现场：概览条读 devices 就会写出「当前没有排队任务 · 2 台设备」——
    0 个任务却有 2 台设备在跑，自相矛盾（真机复现过）。
    """
    _insert_queue([("dev-live", "t1", "sync", "在跑", "running", "")])
    with app_module._write_lock:
        conn = app_module._connect()
        try:
            conn.execute(
                "INSERT OR REPLACE INTO queue_tasks"
                " (device_id, task_id, kind, title, state, detail, queued_at, updated_at)"
                " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                ("dev-zombie", "z1", "sync", "僵尸", "running", "", 0,
                 _now_ms() - storage_module.QUEUE_TTL_MS - 60 * 1000))
            conn.commit()
        finally:
            conn.close()
    queue = api.get("/stats").get_json()["queue"]
    assert queue["devices"] == 2
    assert queue["devices_active"] == 1
    # 过期行只计 stale，不进 running
    assert queue["running"] == 1
    assert queue["stale"] == 1


def test_stats_devices_active_zero_when_every_device_is_zombie(api):
    """全部设备都停报：devices 仍报台数，devices_active 必须是 0。"""
    with app_module._write_lock:
        conn = app_module._connect()
        try:
            conn.execute(
                "INSERT OR REPLACE INTO queue_tasks"
                " (device_id, task_id, kind, title, state, detail, queued_at, updated_at)"
                " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                ("dev-a", "z1", "sync", "僵尸1", "running", "", 0,
                 _now_ms() - storage_module.QUEUE_TTL_MS - 60 * 1000))
            conn.execute(
                "INSERT OR REPLACE INTO queue_tasks"
                " (device_id, task_id, kind, title, state, detail, queued_at, updated_at)"
                " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                ("dev-b", "z2", "sync", "僵尸2", "queued", "", 0,
                 _now_ms() - storage_module.QUEUE_TTL_MS - 60 * 1000))
            conn.commit()
        finally:
            conn.close()
    queue = api.get("/stats").get_json()["queue"]
    assert queue["devices"] == 2
    assert queue["devices_active"] == 0
    assert queue["stale"] == 2
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


def _write_corrupt_advisor():
    """把顾问存档写成坏 JSON：读路径抛 AdvisorArchiveError（一份报告都读不出）。"""
    os.makedirs(app_module.DATA_DIR, exist_ok=True)
    with open(app_module._advisor_jsonl_path(), "w", encoding="utf-8") as fh:
        fh.write("{ broken\n{ also broken\n")


def test_suggests_reports_broken_archive_instead_of_zero_reports(api):
    """存档损坏时不能谎报「还没有顾问报告」。

    修前 _advisor_state 在 AdvisorArchiveError 分支 return 0, 0，priority 50 的
    advisor_total eq0 命中 → 控制台建议卡写「还没有顾问报告 / 顾问存档 0 份」，
    把用户引去等端上跑复盘；真实状态是服务端存档坏了、要人工修。
    现在损坏返回 (None, 0, True)，改成 advisor_broken 那条（priority 85）报实情。
    """
    _write_corrupt_advisor()
    data = api.get("/suggests/next").get_json()
    adv = [item for item in data["suggests"] if item["jump"] == "adv"]
    assert len(adv) == 1, data["suggests"]
    assert adv[0]["priority"] == 85
    assert "损坏" in adv[0]["title"]
    assert "还没有顾问报告" not in [item["title"] for item in data["suggests"]]
    assert "顾问存档 0 份" not in adv[0]["evidence"]


def test_suggests_broken_archive_outranks_todo_but_not_failed_queue(api):
    """priority 85：压过待办（80），但仍排在排队失败（100）与过期（90）之后。"""
    now = _now_ms()
    _write_corrupt_advisor()
    _insert_messages([("a", "hi", now - 1000, "conv1", "微信", "com.tencent.mm")])
    _insert_analyses([("c1", "conv1", 1, "com.tencent.mm", 0, 0)])
    _insert_queue([("dev-a", "t1", "analysis", "A", "failed", "")])
    types = [item["priority"] for item in api.get("/suggests/next").get_json()["suggests"]]
    assert types == sorted(types, reverse=True), types
    assert 85 in types
    assert types[0] == 100


def test_suggests_healthy_archive_never_mentions_corruption(api):
    """存档好着的时候不许出现「损坏」建议（不能把正常状态说成故障）。"""
    now = _now_ms()
    _write_advisor([{"report_id": "r1", "created_at": str(now - 1000)}])
    titles = [item["title"] for item in api.get("/suggests/next").get_json()["suggests"]]
    assert not [t for t in titles if "损坏" in t], titles


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


def test_suggests_pkg_filters_todo_counts(api):
    """E-54：pkg 必须真的参与过滤 —— 待办条数与涉及会话数按来源 App 算。

    修前 pkg 只原样回显，六条规则一条都没用它：控制台选中「飞书」后建议块
    仍然报全库的待办数（与旁边按 pkg 过滤过的列表对不上）。
    """
    _insert_analyses([
        ("c1", "conv1", 1, "com.tencent.mm", 0, 0),
        ("c2", "conv2", 1, "com.tencent.mm", 0, 0),
        ("c3", "conv3", 1, "com.larkoffice.app", 0, 0),
    ])
    full = api.get("/suggests/next").get_json()
    todo = [item for item in full["suggests"] if item["jump"] == "todo"][0]
    assert "3" in todo["evidence"] and "3" in todo["title"]
    one = api.get("/suggests/next?pkg=com.larkoffice.app").get_json()
    assert one["pkg"] == "com.larkoffice.app"
    todo1 = [item for item in one["suggests"] if item["jump"] == "todo"][0]
    assert "1" in todo1["title"], todo1
    assert "1 条" in todo1["evidence"] and "1 个会话" in todo1["evidence"], todo1


def test_suggests_pkg_filters_missing_conversation_counts(api):
    """未分析会话数同样按 pkg 算：别的来源的会话不该算进这一条建议。"""
    now = _now_ms()
    _insert_messages([
        ("a", "hi", now - 1000, "conv1", "微信", "com.tencent.mm"),
        ("b", "yo", now - 1000, "conv2", "飞书", "com.larkoffice.app"),
    ])
    _insert_analyses([("c1", "conv1", 0, "com.tencent.mm", 0, 0)])
    data = api.get("/suggests/next?pkg=com.tencent.mm").get_json()
    types = [item["type"] for item in data["suggests"]]
    assert "rerun" not in types, data["suggests"]
    other = api.get("/suggests/next?pkg=com.larkoffice.app").get_json()
    conv = [item for item in other["suggests"] if item["jump"] == "conv"]
    assert len(conv) == 1, other["suggests"]
    assert "1 个会话" in conv[0]["evidence"], conv[0]


def test_suggests_pkg_does_not_touch_queue_or_advisor_rules(api):
    """排队按设备、顾问是全局报告 —— 两族规则不受 pkg 影响（别把口径抹平）。"""
    _insert_queue([("dev-a", "t1", "analysis", "A", "failed", "")])
    data = api.get("/suggests/next?pkg=com.larkoffice.app").get_json()
    queue = [item for item in data["suggests"] if item["jump"] == "queue"]
    assert len(queue) == 1, data["suggests"]
    assert queue[0]["priority"] == 100
    adv = [item for item in data["suggests"] if item["jump"] == "adv"]
    assert len(adv) == 1, data["suggests"]
    assert adv[0]["priority"] == 50


def test_suggests_pkg_that_has_no_data_silences_own_rules(api):
    """选中的来源一条待办都没有时，不该再报「有 N 条待办」（那是全库的数）。"""
    _insert_analyses([("c1", "conv1", 1, "com.tencent.mm", 0, 0)])
    data = api.get("/suggests/next?pkg=com.larkoffice.app").get_json()
    jumps = [item["jump"] for item in data["suggests"]]
    assert "todo" not in jumps, data["suggests"]
    # 同一条待办数据在「全部」口径下仍然要报出来
    full = api.get("/suggests/next").get_json()
    assert "todo" in [item["jump"] for item in full["suggests"]], full["suggests"]

# ---------------------------------------------------------------- 脏数值行
#
# SQLite 的类型亲和只是「建议」：timestamp / received_at 声明成 INTEGER，
# 写不进整数时会被原样存成 TEXT（直连改库、老库迁移、第三方脚本都能造出来）。
# 裸 int(row[x] or 0) 对 "abc" 完全无效 —— or 只拦假值 —— 整个 /stats 直接 500；
# 而排序里 TEXT 恒大于所有数字，脏行会排第一页最前并被拼进 next_before，
# 客户端拿 "abc_1" 翻页直接 400，永远看不到第二页。


def _exec(sql, params=()):
    """绕过接口直接写库（造脏行用）。"""
    with app_module._write_lock:
        conn = app_module._connect()
        try:
            conn.execute(sql, params)
            conn.commit()
        finally:
            conn.close()


def _insert_text_ts_message(ts, conv="脏会话", pkg="com.tencent.mm"):
    _exec(
        "INSERT INTO messages"
        " (sender, text, timestamp, conversation, is_group, received_at,"
        "  source, pkg)"
        " VALUES (?, ?, ?, ?, 0, ?, ?, ?)",
        ("dirty", "脏行", ts, conv, ts, "android-notification", pkg))


def test_stats_survives_text_timestamp_dirty_row(api):
    """timestamp 被写成文本时 /stats 必须 200，且 MAX 跳过脏行。"""
    now = _now_ms()
    _insert_messages([
        ("a", "hi", now - 1000, "conv1", "微信", "com.tencent.mm"),
        ("b", "yo", now - 500, "conv1", "微信", "com.tencent.mm"),
    ])
    _insert_text_ts_message("abc")
    resp = api.get("/stats")
    assert resp.status_code == 200, resp.get_data(as_text=True)
    messages = resp.get_json()["messages"]
    # 脏行仍算进总数（它是真实存在的一行），但不能参与「最近时间」与近 24h
    assert messages["total"] == 3
    assert messages["last_ts"] == now - 500
    assert messages["last_24h"] == 2


def test_messages_pagination_skips_text_timestamp_row(api):
    """脏行不得排进第一页、更不得毒化 next_before 把第二页打成 400。"""
    now = _now_ms()
    _insert_messages([
        ("a", "hi", now - 1000, "conv1", "微信", "com.tencent.mm"),
        ("b", "yo", now - 500, "conv1", "微信", "com.tencent.mm"),
    ])
    # TEXT 在排序里恒大于所有数字 → 不设 typeof 守卫时它必然排第一
    _insert_text_ts_message("abc")
    first = api.get("/messages?limit=1")
    assert first.status_code == 200, first.get_data(as_text=True)
    body = first.get_json()
    assert body["messages"][0]["text"] == "yo", body["messages"]
    assert body["messages"][0]["timestamp"] == now - 500
    cursor = body["next_before"]
    assert cursor is not None
    # 游标毫秒段必须是数字（修前是 "abc_<rowid>"）
    assert cursor.split("_")[0].isdigit(), cursor
    second = api.get("/messages?limit=1&before=" + cursor)
    assert second.status_code == 200, second.get_data(as_text=True)
    assert second.get_json()["messages"][0]["text"] == "hi"


def test_stats_survives_text_received_at_dirty_row(api):
    """analyses.received_at 同族：/stats 的 last_at 要跳过脏行。"""
    now = _now_ms()
    _insert_analyses([("c1", "conv1", 0, "com.tencent.mm", 0, 0)])
    _exec(
        "INSERT INTO analyses"
        " (case_id, conversation, window_end, message_count, need_action,"
        "  importance, escalated, s1, s2, analyzed_at, protocol, received_at,"
        "  pkg, redacted, redaction_hits, todo_text, todo_due)"
        " VALUES (?, ?, ?, 1, 0, ?, 0, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?)",
        ("dirty-case", "脏会话", now, "high", "{}", "{}", "2026-11-14 10:00:00",
         "v2", "boom", "com.tencent.mm", "{}", "", 0))
    resp = api.get("/stats")
    assert resp.status_code == 200, resp.get_data(as_text=True)
    analyses = resp.get_json()["analyses"]
    assert analyses["total"] == 2
    assert analyses["last_report"] == now


def test_analysis_pagination_skips_text_received_at_row(api):
    """analyses 的脏 received_at 同样不能毒化 /analysis 的游标。"""
    now = _now_ms()
    _insert_analyses([
        ("c1", "conv1", 0, "com.tencent.mm", 0, 0),
        ("c2", "conv2", 0, "com.tencent.mm", 0, 0),
    ])
    _exec(
        "INSERT INTO analyses"
        " (case_id, conversation, window_end, message_count, need_action,"
        "  importance, escalated, s1, s2, analyzed_at, protocol, received_at,"
        "  pkg, redacted, redaction_hits, todo_text, todo_due)"
        " VALUES (?, ?, ?, 1, 0, ?, 0, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?)",
        ("dirty-case", "脏会话", now, "high", "{}", "{}", "2026-11-14 10:00:00",
         "v2", "boom", "com.tencent.mm", "{}", "", 0))
    first = api.get("/analysis?limit=1")
    assert first.status_code == 200, first.get_data(as_text=True)
    body = first.get_json()
    assert body["analyses"][0]["case_id"] != "dirty-case", body["analyses"]
    cursor = body["next_before"]
    assert cursor is not None
    assert cursor.split("_")[0].isdigit(), cursor
    second = api.get("/analysis?limit=1&before=" + cursor)
    assert second.status_code == 200, second.get_data(as_text=True)
    ids = [one["case_id"] for one in second.get_json()["analyses"]]
    assert ids and "dirty-case" not in ids, ids

