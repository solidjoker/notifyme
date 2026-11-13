# -*- coding: utf-8 -*-
# Copyright (c) 2026 solidjoker
# SPDX-License-Identifier: MIT
"""全库聚合统计：GET /stats。

为什么要它：控制台的「概览条」与「统计」Tab 原来都用前端已加载的那一页数据算，
翻页前后数字会变（首屏 200 条 vs 翻到第 10 页），用户无法判断「到底还有多少」。
这条接口把口径收回服务端：一次聚合出全库真值，前端只负责显示。

与 /messages、/analysis 的关系：
  - 过滤口径保持一致（pkg 精确匹配）；
  - 本接口不受 before 游标影响 —— 它统计的是全库，不是当前页。
  - 会话数（convs / todo_convs）按 (pkg, conversation) 计，与控制台 ckOf() 同口径 ——
    同名的两个会话来自不同 App 是两个会话，别只数 conversation 列。

返回：{ok, generated_at, tz, tz_offset_minutes,
       messages: {total, convs, last_24h, last_ts, last_received_at, age_days, by_pkg, by_source},
       analyses: {total, convs, todo, todo_convs, redacted_cases, redaction_hits, last_report, age_days},
       queue:    {total, active, queued, running, failed, done, stale, devices, by_kind}}
"""

import time
import sqlite3

from flask import jsonify, request

from util import logger


# 近 24 小时窗口长度（毫秒），与前端概览条同口径。
DAY_MS = 24 * 3600 * 1000

# 会话键的 SQL 口径：会话身份是 (pkg, conversation)，不是 conversation。
# 控制台前端的 ckOf() 也是 pkg + SEP + conversation（console_classify.js），
# 只数 conversation 会把「微信的王五」和「微博的王五」算成同一个会话，
# 而 /messages 的行、控制台的会话卡都是按 (pkg, conversation) 分开的。
# 分隔符用 char(1) 与前端 SEP 同值；conversation 为 NULL 时整体为 NULL，
# COUNT(DISTINCT) 会跳过它 —— 与旧口径一致（脏数据仍不计入会话数）。
CONV_KEY_SQL = "pkg || char(1) || conversation"

def _messages_block(pkg=None):
    """消息侧聚合。pkg=None 表示不按来源 App 过滤（全库口径）。"""
    import app as app_module
    now_ms = int(time.time() * 1000)
    where = " WHERE pkg = ?" if pkg else ""
    params = [pkg] if pkg else []
    conn = app_module._connect()
    try:
        row = conn.execute(
            "SELECT COUNT(*), COUNT(DISTINCT " + CONV_KEY_SQL + "), "
            "MAX(timestamp), MAX(received_at) FROM messages" + where,
            params).fetchone()
        day_row = conn.execute(
            "SELECT COUNT(*) FROM messages" + where +
            (" AND" if where else " WHERE") + " timestamp >= ?",
            params + [now_ms - DAY_MS]).fetchone()
        # 分布也必须跟着 pkg 过滤走：同一段里 total / convs 是过滤后的，
        # 分布却是全库的，客户端渲染出来就是「共 2 条，分布加起来 3 条」。
        by_pkg = conn.execute(
            "SELECT pkg, COUNT(*) FROM messages" + where + " GROUP BY pkg "
            "ORDER BY COUNT(*) DESC, pkg ASC", params).fetchall()
        by_source = conn.execute(
            "SELECT source, COUNT(*) FROM messages" + where + " GROUP BY source "
            "ORDER BY COUNT(*) DESC, source ASC", params).fetchall()
    finally:
        conn.close()
    last_ts = int(row[2] or 0)
    last_at = int(row[3] or 0)
    return {
        "total": int(row[0] or 0),
        "convs": int(row[1] or 0),
        "last_24h": int(day_row[0] or 0),
        "last_ts": last_ts,
        "last_received_at": last_at,
        # 最近一条消息距今多久（天）；没有消息时为 None，前端据此显示「暂无」
        "age_days": max(0, (now_ms - last_ts) // DAY_MS) if last_ts else None,
        "by_pkg": [{"pkg": r[0], "count": int(r[1] or 0)} for r in by_pkg],
        "by_source": [{"source": r[0], "count": int(r[1] or 0)} for r in by_source],
    }


def _analysis_block():
    """分析侧聚合。

    这里不接 pkg 过滤：统计 Tab 要回答的是「整库分析了多少」，
按 App 过滤会把「待办」这种全局计数也一起改掉，反而更难解释。
    """
    import app as app_module
    now_ms = int(time.time() * 1000)
    conn = app_module._connect()
    try:
        row = conn.execute(
            "SELECT COUNT(*), COUNT(DISTINCT " + CONV_KEY_SQL + "), "
            "SUM(CASE WHEN need_action = 1 THEN 1 ELSE 0 END), "
            "MAX(received_at) FROM analyses").fetchone()
        todo = conn.execute(
            "SELECT COUNT(*), COUNT(DISTINCT " + CONV_KEY_SQL + ") FROM analyses "
            "WHERE need_action = 1").fetchone()
        redacted = conn.execute(
            "SELECT COUNT(*), COALESCE(SUM(redaction_hits), 0) FROM analyses "
            "WHERE redacted = 1").fetchone()
    finally:
        conn.close()
    last_at = int(row[3] or 0)
    return {
        "total": int(row[0] or 0),
        "convs": int(row[1] or 0),
        "todo": int(row[2] or 0),
        "todo_convs": int(todo[1] or 0),
        "redacted_cases": int(redacted[0] or 0),
        "redaction_hits": int(redacted[1] or 0),
        "last_report": last_at,
        "age_days": max(0, (now_ms - last_at) // DAY_MS) if last_at else None,
    }


def _queue_block():
    """排队任务聚合。表不存在（老库没跑过 init_db）时按「没有任务」返回。"""
    import app as app_module
    import storage as storage_module
    now_ms = int(time.time() * 1000)
    out = {"total": 0, "active": 0, "queued": 0, "running": 0,
           "failed": 0, "done": 0, "stale": 0, "devices": 0, "by_kind": []}
    conn = app_module._connect()
    try:
        rows = conn.execute(
            "SELECT state, kind, updated_at FROM queue_tasks").fetchall()
    except sqlite3.OperationalError as exc:
        if "no such table" not in str(exc).lower():
            logger.warning("stats: queue table unreadable: %s", exc)
        return out
    finally:
        conn.close()
    out["total"] = len(rows)
    kinds = {}
    for row in rows:
        state = row[0] or ""
        kind = row[1] or ""
        updated_at = int(row[2] or 0)
        kinds[kind] = kinds.get(kind, 0) + 1
        if (now_ms - updated_at) > storage_module.QUEUE_TTL_MS:
            out["stale"] += 1
            continue
        # 状态集合引用 storage 的常量，不在这里再写一份字面量
        if state in storage_module.QUEUE_STATES:
            out[state] += 1
        if state in storage_module.QUEUE_ACTIVE_STATES:
            out["active"] += 1
    # 设备数走 storage.queue_devices()，与 /queue 的 devices 同一口径
    out["devices"] = len(storage_module.queue_devices())
    out["by_kind"] = [{"kind": k, "count": c} for k, c in
                      sorted(kinds.items(), key=lambda kv: (-kv[1], kv[0]))]
    return out


def query_stats():
    """GET /stats —— 全库聚合统计（控制台概览与统计 Tab 的真值来源）。

    参数：
        pkg       来源 App 包名（可选，精确匹配；只影响 messages 段）
        tz        时区 +08:00 / +0800 / +8（仅回显，便于前端与 /messages 对齐）
        tz_offset 时区分钟偏移（与 tz 二选一）

    返回：见模块 docstring。
    """
    import app as app_module
    auth_err = app_module.check_token()
    if auth_err:
        return auth_err

    pkg = (request.args.get("pkg") or "").strip() or None
    tz_offset_min = app_module._parse_tz_offset(request.args.get("tz", ""),
                                     (request.args.get("tz_offset") or "").strip())
    if isinstance(tz_offset_min, tuple):
        return jsonify({"ok": False, "error": tz_offset_min[1]}), 400

    messages = _messages_block(pkg)
    analyses = _analysis_block()
    queue = _queue_block()
    now_ms = int(time.time() * 1000)
    logger.debug("stats pkg=%s -> msgs=%s todo=%s queue=%s",
                 pkg, messages["total"], analyses["todo"], queue["total"])
    return jsonify({"ok": True, "generated_at": now_ms,
                    "tz": app_module._fmt_tz_offset(tz_offset_min),
                    "tz_offset_minutes": tz_offset_min,
                    "messages": messages, "analyses": analyses, "queue": queue})


def register(app):
    """挂载聚合统计路由（endpoint 名 query_stats，与前端 /stats 约定）。"""
    app.add_url_rule("/stats", "query_stats", query_stats, methods=["GET"])
