# -*- coding: utf-8 -*-
# Copyright (c) 2026 solidjoker
# SPDX-License-Identifier: MIT
"""下一步建议路由：GET /suggests/next（表驱动，不调模型，最多 3 条，每条带依据）。

为什么要它：控制台的「待办」Tab 只列数据，不告诉用户下一步该干什么。这条路由补
「建议」这一层：每条都带 标题 / 为什么 / 怎么做 / 依据 / 跳转指令（jump），
前端拿着 jump 就能把手引到对应的 Tab。

设计约束（刻意如此，别改）：
  1. 纯表驱动 + 本库聚合，不调任何模型 —— 没有不确定性、没有额外依赖、可单测；
  2. 每条建议必须能指到具体数字（依据），否则宁可不给；
  3. 规则表按 priority 降序声明，_pick 不再二次排序（表本身就是优先级）。
"""

import json
import sqlite3
import time

import storage
from flask import jsonify, request

from util import logger


# 建议上限：一次最多给 3 条，多了等于没重点。
SUGGEST_LIMIT = 3

# 顾问报告超过这么多天没更新就提醒看一眼。
ADVISOR_IDLE_DAYS = 3


def _now_ms():
    """当前毫秒时间戳（单独抽出来，测试里好固定）。"""
    return int(time.time() * 1000)


def _advisor_state():
    """顾问存档（JSONL，每行一份报告）的份数、最近一次 created_at、是否损坏。

    读取口径与 storage 的顾问存档一致（同一文件、同一 JSON 行格式），
    这里只关心份数与时间，不把报告正文载进来。

    损坏时返回 (None, 0, True) —— **份数是 None 而不是 0**：存档读不出来
    不等于「一份报告都没有」，把损坏说成 0 份会让 priority 50 那条建议
    （advisor_total eq0）谎报「还没有顾问报告」，把用户引去等端上跑复盘，
    而真因是服务端文件坏了、要人工处理。None 在 _hit 里视为前提不成立，
    改由 advisor_broken 那条规则接手。
    """
    import app as app_module
    # 走 storage 的读取口径：它同时覆盖 JSONL 主存储与旧的 JSON 数组快照，
    # 并且对损坏文件抛 AdvisorArchiveError。这里不能自己再开一个文件读 ——
    # 只认 JSONL 会把「只有快照、还没迁移」的部署误判成 0 份，白给一条建议。
    try:
        reports = app_module._load_advisor_reports()
    except app_module.AdvisorArchiveError as exc:
        # 存档坏了：份数给 None（不是 0），交给 advisor_broken 那条规则报实情。
        logger.warning("suggests: advisor archive unreadable: %s", exc)
        return None, 0, True
    total = len(reports)
    last_ts = 0
    for item in reports:
        # 端上（AdvisorStore.toUploadJson / SyncWorker）上报的键是 created_at
        # （下划线）；驼峰 createdAt 只出现在早期手写夹具与老部署里，保留兼容。
        # 三个都认不出时退回服务端接收时间 received_at（写入路径必带）。
        ts = (app_module._advisor_ts(item.get("created_at"))
              or app_module._advisor_ts(item.get("createdAt"))
              or app_module._advisor_ts(item.get("received_at")))
        if ts > last_ts:
            last_ts = ts
    return total, last_ts, False


def _queue_state():
    """排队任务计数：总数 / 活跃 / 失败 / 过期。

    过期 = updated_at 超过 QUEUE_TTL_MS 没刷新，不代表团上真实还在跑，单独计。
    """
    import app as app_module
    conn = app_module._connect()
    try:
        rows = conn.execute(
            "SELECT state, updated_at FROM queue_tasks").fetchall()
    except sqlite3.OperationalError as exc:
        logger.debug("suggests: queue table unavailable: %s", exc)
        return {"total": 0, "active": 0, "failed": 0, "stale": 0}
    finally:
        conn.close()
    now = _now_ms()
    out = {"total": len(rows), "active": 0, "failed": 0, "stale": 0}
    for row in rows:
        updated_at = app_module._safe_int(row[1], 0, lo=0)
        if (now - updated_at) > storage.QUEUE_TTL_MS:
            out["stale"] += 1
            continue
        state = row[0] or ""
        if state in storage.QUEUE_ACTIVE_STATES:
            out["active"] += 1
        elif state == "failed":
            out["failed"] += 1
    return out


def _todo_counts(pkg=None):
    """待办总数与涉及会话数：analyses 里 need_action = 1 的行。

    按 (pkg, conversation) 分组数一遍，比「取回所有行再在 Python 里数」省内存。
    pkg 非空时只数该来源 App 的待办（控制台选了来源筛选就把包名传进来）。
    """
    import app as app_module
    conn = app_module._connect()
    sql = ("SELECT pkg, conversation, COUNT(*) FROM analyses"
           " WHERE need_action = 1")
    params = ()
    if pkg:
        sql += " AND pkg = ?"
        params = (pkg,)
    try:
        rows = conn.execute(sql + " GROUP BY pkg, conversation", params).fetchall()
    finally:
        conn.close()
    total = 0
    for row in rows:
        total += app_module._safe_int(row[2], 0, lo=0)
    return total, len(rows)


def _convs_without_analysis(pkg=None):
    """消息里出现过、但 analyses 里没有的 (pkg, conversation) 数。

    一条 SQL 搞定：NOT EXISTS 相关子查询，走 idx_messages_conv_ts 与 analyses 的
    同名列比较；不把两边的会话列表拉到 Python 里做差集。
    pkg 非空时只数该来源 App 的会话（与 _todo_counts 同口径）。
    """
    import app as app_module
    conn = app_module._connect()
    where = ["NOT EXISTS ("
             "  SELECT 1 FROM analyses AS a"
             "  WHERE a.pkg IS m.pkg AND a.conversation IS m.conversation"
             ")"]
    params = ()
    if pkg:
        where.append("m.pkg = ?")
        params = (pkg,)
    sql = ("SELECT COUNT(*) FROM ("
           "  SELECT m.pkg AS pkg, m.conversation AS conversation"
           "  FROM messages AS m"
           "  WHERE " + " AND ".join(where) +
           "  GROUP BY m.pkg, m.conversation"
           ")")
    try:
        row = conn.execute(sql, params).fetchone()
    finally:
        conn.close()
    if row is None:
        return 0
    return app_module._safe_int(row[0], 0, lo=0)


def _fill(template, ctx):
    """把 {name} 占位符换成 ctx 里的值；缺的键原样留着（便于发现漏字段）。"""
    out = template
    for key, value in ctx.items():
        out = out.replace("{" + key + "}", str(value))
    return out


def _hit(rule, ctx):
    """规则前提是否成立（None 视为不成立：没有数据就不该给建议）。"""
    actual = ctx.get(rule["field"])
    if actual is None:
        return False
    op = rule["op"]
    bound = rule["bound"]
    if op == "gt":
        return actual > bound
    if op == "ge":
        return actual >= bound
    if op == "eq0":
        return actual == 0
    return False


def _build(rule, ctx):
    """把规则模板渲染成一条建议（字段与 /suggests/next 的返回契约一致）。"""
    return {
        "type": rule["type"],
        "priority": rule["priority"],
        "title": _fill(rule["title"], ctx),
        "why": _fill(rule["why"], ctx),
        "how": _fill(rule["how"], ctx),
        "evidence": _fill(rule["evidence"], ctx),
        "jump": rule.get("jump", ""),
    }


def _pick(ctx):
    """按声明顺序挑建议：前提成立才收，最多 SUGGEST_LIMIT 条。"""
    out = []
    for rule in RULES:
        if not _hit(rule, ctx):
            continue
        out.append(_build(rule, ctx))
        if len(out) >= SUGGEST_LIMIT:
            break
    return out


def query_suggests_next():
    """GET /suggests/next —— 下一步建议（最多 3 条，带依据与跳转指令）。

    参数：pkg（可选，来源 App 过滤，与 /messages、/analysis 同口径）。
          只影响 todo / convs_missing 两族规则的计数 —— 排队按设备、顾问是
          全局报告，与来源无关；生效后的数字就是证据串里看到的数字。
    返回：{"ok": true, "suggests": [...], "count": n, "generated_at": ..., "pkg": ...}
          顾问存档损坏时不会给「还没有顾问报告」，改给「顾问存档损坏」这条。
          每条：{type, priority, title, why, how, evidence, jump}
          jump：conv / todo / queue / adv / ""（空串 = 无需跳转）。
    """
    import app as app_module
    auth_err = app_module.check_token()
    if auth_err:
        return auth_err

    pkg = (request.args.get("pkg") or "").strip() or None
    now = _now_ms()

    queue = _queue_state()
    advisor_total, advisor_last, advisor_broken = _advisor_state()
    if advisor_last:
        advisor_age_days = max(0, (now - advisor_last) // 86400000)
    else:
        advisor_age_days = None

    # pkg 只作用于「与来源 App 相关」的两族规则（待办 / 未分析会话）；
    # 排队按设备、顾问是全局报告，两段与 pkg 无关，保持全库口径。
    todo, todo_convs = _todo_counts(pkg)
    convs_missing = _convs_without_analysis(pkg)

    ctx = {
        "queue_failed": queue["failed"],
        "queue_stale": queue["stale"],
        "queue_total": queue["total"],
        "todo": todo,
        "todo_convs": todo_convs,
        "convs_missing": convs_missing,
        "advisor_total": advisor_total,
        "advisor_broken": 1 if advisor_broken else 0,
        "advisor_age_days": advisor_age_days,
    }

    suggests = _pick(ctx)
    logger.debug("suggests next pkg=%s -> %s", pkg,
                 [item["type"] for item in suggests])
    return jsonify({"ok": True, "suggests": suggests,
                    "count": len(suggests),
                    "generated_at": now, "pkg": pkg})


# 规则表：按 priority 降序声明，_pick 命中即收，最多 SUGGEST_LIMIT 条。
# field/op/bound 决定前提；title/why/how/evidence 里的 {name} 由 ctx 填。
# jump 是前端跳转指令：conv / todo / queue / adv / ""。
RULES = (
    {
        "type": "rerun",
        "priority": 100,
        "field": "queue_failed", "op": "gt", "bound": 0,
        "title": "有 {queue_failed} 个排队任务失败，建议重跑",
        "why": "失败任务不会自动重试，只会留在队列里；超过 7 天保留期会被清掉。",
        "how": "先看失败详情里的 error_kind，能修的直接修，修不了的删掉重报。",
        "evidence": "失败 {queue_failed} 个 / 共 {queue_total} 个（另有 {queue_stale} 个已过期）",
        "jump": "queue",
    },
    {
        "type": "check",
        "priority": 90,
        "field": "queue_stale", "op": "gt", "bound": 0,
        "title": "有 {queue_stale} 个排队任务已过期，建议核对端上还在不在跑",
        "why": "过期 = 端上超过 30 分钟没刷新这条任务的状态，通常是被杀进程或断网。",
        "how": "打开 Android 端确认后台同步活着，再手动触发一次同步。",
        "evidence": "过期 {queue_stale} 个 / 共 {queue_total} 个",
        "jump": "queue",
    },
    # 存档损坏是「事实错误」级别的问题：读接口回空、写接口回 500，
    # 只说「还没有顾问报告」会把用户引去等端上跑复盘（永远等不到）。
    {
        "type": "check",
        "priority": 85,
        "field": "advisor_broken", "op": "gt", "bound": 0,
        "title": "顾问存档损坏，报告读不出来",
        "why": "服务端解析顾问存档（advisor_reports.jsonl）失败：为防历史报告被整份覆盖，" +
               "写入已被拒绝，读接口只能回空列表。文件仍在服务器上，不是「没有报告」。",
        "how": "登录服务器把损坏文件备份后修好或删掉（有备份就从备份恢复）；" +
               "修好前新的顾问上报会一直失败。",
        "evidence": "顾问存档无法解析（advisor archive is corrupted）",
        "jump": "adv",
    },
    {
        "type": "check",
        "priority": 80,
        "field": "todo", "op": "gt", "bound": 0,
        "title": "有 {todo} 条待办还没处理完",
        "why": "待办是分析结果里 need_action=1 的消息，总量还在涨说明有积压。",
        "how": "从待办 Tab 按重要度从高到低清，先清 high 再清 mid。",
        "evidence": "待办 {todo} 条，分布在 {todo_convs} 个会话",
        "jump": "todo",
    },
    {
        "type": "rerun",
        "priority": 70,
        "field": "convs_missing", "op": "gt", "bound": 0,
        "title": "有 {convs_missing} 个会话还没分析过，建议补跑一次分析",
        "why": "消息抓到不等于分析过；没分析过的会话不会出现在待办与统计里。",
        "how": "在 Android 端对该会话点「立即分析」，或等周期任务跑到。",
        "evidence": "{convs_missing} 个会话无分析结果",
        "jump": "conv",
    },
    {
        "type": "check",
        "priority": 60,
        "field": "advisor_age_days", "op": "gt", "bound": ADVISOR_IDLE_DAYS,
        "title": "顾问建议已 {advisor_age_days} 天没更新，建议看一眼",
        "why": "顾问报告是周期生成的，长时间没更新通常是周期任务没跑到或上报被拒。",
        "how": "打开顾问 Tab 看最近一次报告时间，必要时手动触发一次同步。",
        "evidence": "最近一次顾问报告 {advisor_age_days} 天前（共 {advisor_total} 份）",
        "jump": "adv",
    },
    {
        "type": "check",
        "priority": 50,
        "field": "advisor_total", "op": "eq0", "bound": 0,
        "title": "还没有顾问报告",
        "why": "顾问报告由端上按周期生成并上报，一份都没有说明周期任务还没跑过。",
        "how": "在 Android 端手动触发一次同步，等分析跑完后报告会自动上报。",
        "evidence": "顾问存档 {advisor_total} 份",
        "jump": "adv",
    },
)


def register(app):
    """挂载建议路由（endpoint 名 query_suggests_next，前端按 /suggests/next 取）。"""
    app.add_url_rule("/suggests/next", "query_suggests_next", query_suggests_next,
                     methods=["GET"])
