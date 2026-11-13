# -*- coding: utf-8 -*-
# Copyright (c) 2026 solidjoker
# SPDX-License-Identifier: MIT

"""notifyme 服务端 —— 数据面路由。

通过 register(app) 挂载到 Flask 应用，endpoint 名与 URL 路径与拆分前保持一致。
鉴权、限额与存储入口都在调用时从 app 模块动态读取。
"""

import json
import sqlite3
import threading
import time
from datetime import datetime, timedelta
from flask import jsonify, request

from util import logger

def receive_weixin():
    """
    接收安卓端上报的消息数组。
    - 逐条校验字段，缺字段/类型错误的条目跳过并计数（invalid）
    - 合法条目追加到 data/messages.jsonl（原文存档）并写入 SQLite
    - 已在库中的重复消息跳过（duplicated）
    返回: {"ok": true, "received": n, "duplicated": m, "invalid": k}
    """
    import app as app_module
    auth_err = app_module.check_token()
    if auth_err:
        return auth_err

    # 解析 JSON body，必须是数组
    payload = request.get_json(silent=True)
    if not isinstance(payload, list):
        return jsonify({
            "ok": False,
            "error": "body must be a JSON array"
        }), 400

    received = 0    # 新入库条数
    duplicated = 0  # 重复跳过条数
    invalid = 0     # 校验失败条数
    rejected = []   # 校验失败的原始下标，供客户端精确剔除/重试
    archive_error = ""   # jsonl 存档写入失败的原因（库已提交，不回滚）

    now_ms = int(time.time() * 1000)  # 服务端接收时间（毫秒）

    with app_module._write_lock:
        conn = app_module._connect()
        accepted = []   # 真正入库成功的原始条目（按入库顺序）
        try:
            for idx, item in enumerate(payload):
                # 每条必须是对象且字段齐全、类型正确
                if not isinstance(item, dict) or not app_module._validate_item(item):
                    invalid += 1
                    rejected.append(idx)
                    continue

                # 写入 SQLite；唯一索引冲突说明是重复消息
                # source 尊重客户端标记（a11y-extract / pc-db 等），
                # 缺省才是 android-notification
                source = item.get("source")
                if not isinstance(source, str) or not source.strip():
                    source = "android-notification"
                # pkg：老客户端不带，就地按微信回填（不做一次性批量迁移）
                pkg = item.get("pkg")
                if not isinstance(pkg, str) or not pkg.strip():
                    pkg = app_module.DEFAULT_PKG
                # 每条一个 SAVEPOINT：坏条目只回滚自己，不污染同批好条目，
                # 也不会把整批拖成 500（客户端遇到 500 会永久重试毒丸，堵死队列）。
                conn.execute("SAVEPOINT item_sp")
                try:
                    conn.execute(
                        """
                        INSERT INTO messages
                            (sender, text, timestamp, conversation, is_group,
                             received_at, source, pkg)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                        (
                            item["sender"],
                            item["text"],
                            int(item["timestamp"]),
                            item["conversation"],
                            1 if item["is_group"] else 0,
                            now_ms,
                            source.strip(),
                            pkg.strip(),
                        ),
                    )
                    received += 1
                    accepted.append(item)
                    conn.execute("RELEASE item_sp")
                except sqlite3.IntegrityError:
                    # 命中唯一索引：手机端重试造成的重复上报
                    conn.execute("ROLLBACK TO item_sp")
                    conn.execute("RELEASE item_sp")
                    duplicated += 1
                except sqlite3.OperationalError as e:
                    conn.execute("ROLLBACK TO item_sp")
                    conn.execute("RELEASE item_sp")
                    if "locked" in str(e).lower() or "busy" in str(e).lower():
                        # 主库被别的写入方占用（多半是提取脚本正在批量提交）：
                        # 瞬时可重试故障，回 503 让客户端带退避重试，
                        # 别把它伪装成「条目非法」丢进 rejected。
                        conn.rollback()
                        logger.warning("主库被占用，/weixin 返回 503: %s", e)
                        resp = jsonify({
                            "ok": False,
                            "received": 0,
                            "duplicated": 0,
                            "invalid": 0,
                            "error": "main database is busy, retry later",
                        })
                        resp.status_code = 503
                        resp.headers["Retry-After"] = "5"
                        return resp
                    invalid += 1
                    rejected.append(idx)
                    logger.warning("第 %d 条入库失败（已隔离该条）: %s", idx, e)
                except Exception as e:
                    # 脏数据（超长、类型怪、SQLite 拒绝）隔离成 invalid，
                    # 其余条目照常入库并回 207。
                    conn.execute("ROLLBACK TO item_sp")
                    conn.execute("RELEASE item_sp")
                    invalid += 1
                    rejected.append(idx)
                    logger.warning("第 %d 条入库失败（已隔离该条）: %s", idx, e)
            conn.commit()
        except Exception:
            conn.rollback()
            raise
        finally:
            conn.close()

        # jsonl 原文存档必须在**库提交之后**写：
        # 原来是「先写存档 → 整批最后一次 commit」，一旦中途抛异常，
        # 存档里留下的是库里没有的条目；重复上报也被写进存档，
        # 于是存档条数永久多于库内条数（实测 jsonl 2 行 / 库 1 行）。
        if accepted:
            try:
                app_module._append_jsonl(accepted)
            except OSError as e:
                # 库已提交：这里只丢存档副本，不丢数据，如实回报错误
                logger.error("写入 jsonl 存档失败: %s", e)
                archive_error = str(e)

    # 有校验失败就回 207：客户端据此只剔除被接受/重复的条目。
    # 原来无论 invalid 多少都恒返回 200，客户端会把被拒的消息也当成功删掉（永久丢失）。
    if invalid:
        return jsonify({
            "ok": False,
            "received": received,
            "duplicated": duplicated,
            "invalid": invalid,
            "rejected": rejected,
            "error": "some items were rejected",
            "archive_error": archive_error,
        }), 207
    return jsonify({
        "ok": True,
        "received": received,
        "duplicated": duplicated,
        "invalid": 0,
        "archive_error": archive_error,
    })

def query_messages():
    """
    查询消息。
    参数：
        pkg           来源 App 包名（精确匹配）；缺省不按 App 过滤
        conversation  会话名（模糊匹配）
        date          YYYY-MM-DD，按消息 timestamp 过滤当天
        tz            可选时区（+08:00 / +0800 / +8）：显式给出时按该时区
                      算当天起止；不给则用服务器本地时区（老行为）
        tz_offset     可选时区（分钟偏移，480 = 东八区），与 tz 二选一
        limit         返回条数，默认 100，上限 1000
        before        游标分页：上一页返回的 next_before 原样回传
                      （形如 <毫秒>_<rowid>，也可只给毫秒）
    返回：{"ok": true, "count": n, "messages": [...], "next_before": ms|null}
        next_before 为下一页游标，本页未取满（没有更多）时为 null。
    """
    import app as app_module
    auth_err = app_module.check_token()
    if auth_err:
        return auth_err

    pkg = request.args.get("pkg", "").strip()
    conversation = request.args.get("conversation", "").strip()
    date_str = request.args.get("date", "").strip()
    before_str = request.args.get("before", "").strip()
    # 时区（B5）：显式给出时按该时区算当天起止；不给就用服务器本地时区
    # （保持老行为，Android 端不用改）。
    # 注意：tz 不能提前 strip —— query 里未编码的 '+' 会被解码成空格，
    # _parse_tz_offset 需要看到原始的 " 08:00" 才能还原成 "+08:00"。
    tz_parsed = app_module._parse_tz_offset(request.args.get("tz", ""),
                                 request.args.get("tz_offset", "").strip())
    if isinstance(tz_parsed, tuple):
        return jsonify({"ok": False, "error": tz_parsed[1]}), 400
    tz_offset_min = tz_parsed
    try:
        limit = min(int(request.args.get("limit", "100")), 1000)
        if limit <= 0:
            limit = 100
    except ValueError:
        limit = 100

    sql = ("SELECT sender, text, timestamp, conversation, is_group, "
           "received_at, source, pkg, rowid FROM messages")
    conditions = []
    params = []

    # 来源 App 精确过滤
    if pkg:
        conditions.append("pkg = ?")
        params.append(pkg)

    # 会话名过滤：默认前缀匹配（范围比较，能用上 idx_messages_conv_ts）；
    # conversation_match=contains 退回旧的「包含」模糊匹配（老口径兼容）。
    # 前缀匹配避免 LIKE '%x%' 的前导通配符 → 索引失效 → 全表扫描。
    if conversation:
        if (request.args.get("conversation_match", "") or "").strip().lower() \
                == "contains":
            # 转义 LIKE 通配符：会话名里带 _（单字符通配）会匹配到别人的会话，
            # 带 % 则等于全表匹配。
            conditions.append("conversation LIKE ? ESCAPE '\\'")
            params.append(f"%{app_module._like_escape(conversation)}%")
        else:
            conditions.append("conversation >= ? AND conversation < ?")
            params.extend([conversation, app_module._prefix_upper(conversation)])

    # 按日期过滤（timestamp 落在当天 [00:00, 次日00:00) 区间）
    if date_str:
        try:
            day_start, day_end = app_module._day_bounds_ms(date_str, tz_offset_min)
        except ValueError:
            return jsonify({"ok": False, "error": "date must be YYYY-MM-DD"}), 400
        conditions.append("timestamp >= ? AND timestamp < ?")
        params.extend([day_start, day_end])

    # 游标分页：按 (timestamp, rowid) 往前翻，避免 LIMIT/OFFSET 深翻页全表扫描。
    # 只按 timestamp 比较会漏掉「同一毫秒但排序靠后」的多条消息，
    # 所以 next_before 带上 rowid，写成 <毫秒>_<rowid>（老客户端只发毫秒也兼容）。
    if before_str:
        cursor = app_module._parse_cursor(before_str)
        if cursor[0] == "error":
            return jsonify({"ok": False, "error": cursor[1]}), 400
        before_ms, rid = cursor
        # 行值比较 (timestamp, rowid) < (?, ?)：
        # 原来的 OR 谓词用不上索引（EXPLAIN 显示全表 SCAN），
        # 行值比较能走索引。老客户端只发毫秒时用 int64 上界当 rowid，
        # 顺带修掉「同一毫秒的多行被整批漏掉」的老问题。
        if sqlite3.sqlite_version_info >= (3, 15, 0):
            conditions.append("(timestamp, rowid) < (?, ?)")
            params.extend([before_ms, rid])
        else:
            conditions.append("(timestamp < ? OR (timestamp = ? AND rowid < ?))")
            params.extend([before_ms, before_ms, rid])

    if conditions:
        sql += " WHERE " + " AND ".join(conditions)
    # rowid 兜底：同一毫秒的多条消息在只按 timestamp 排序时顺序不稳定，
    # 会导致翻页丢条目或重复。
    sql += " ORDER BY timestamp DESC, rowid DESC LIMIT ?"
    # 多取一条，用来判断「还有没有下一页」：
    # 原来只看「满页」就发 next_before，前端会多请求一次必然为空的页
    params.append(limit + 1)

    conn = app_module._connect()
    try:
        rows = conn.execute(sql, params).fetchall()
    finally:
        conn.close()

    has_more = len(rows) > limit
    rows = rows[:limit]
    messages = [
        {
            # conversation / sender / text 允许为 NULL：回 null 会让控制台
            # 显示成字面量 null，这里统一成空串。
            "sender": r[0] if r[0] is not None else "",
            "text": r[1] if r[1] is not None else "",
            "timestamp": r[2],
            "conversation": r[3] if r[3] is not None else "",
            "is_group": bool(r[4]),
            "received_at": r[5],
            "source": r[6],
            "pkg": r[7],
        }
        for r in rows
    ]
    next_before = None
    # 只有真的还有下一页时才给游标（原来满页就给 → 多一次空请求）
    if has_more and rows:
        next_before = f"{rows[-1][2]}_{rows[-1][8]}"
    return jsonify({
        "ok": True,
        "count": len(messages),
        "messages": messages,
        "next_before": next_before,
        # 实际用的时区：显式给了 tz / tz_offset 就回显偏移，
        # 没给就是服务器本地时区（null）。
        "tz": app_module._fmt_tz_offset(tz_offset_min),
        "tz_offset_minutes": tz_offset_min,
    })

def receive_analysis():
    """
    接收 App 侧的会话级分析结果（JSON 数组），每条结构：
        case_id       str   分析用例 ID（去重键，必需）
        conversation  str   会话名（必需）
        window_end    int   分析窗口右端（毫秒）
        message_count int   窗口内消息数
        s1            dict  {need_action:{value,prob}, importance, due_window, topic}
        escalated     bool  是否升级到 S2
        s2            dict  {summary, due_time, suggested_action, tasks}（可空）
        analyzed_at   str   App 侧分析完成时间
        protocol      str   协议版本
        pkg           str   可选，来源 App 包名；缺省按微信处理
    case_id 已存在的跳过（duplicated），缺 case_id/conversation 的跳过（invalid）。
    返回：{"ok": true, "received": n, "duplicated": m, "invalid": k}
    """
    import app as app_module
    auth_err = app_module.check_token()
    if auth_err:
        return auth_err

    payload = request.get_json(silent=True)
    if not isinstance(payload, list):
        return jsonify({"ok": False, "error": "body must be a JSON array"}), 400

    received = duplicated = invalid = 0
    rejected = []   # 校验失败的原始下标，供客户端精确剔除/重试
    # 被拒条目的 case_id（能取到时）：客户端优先按 id 匹配，避免依赖
    # 「请求体顺序 == 分批顺序」这个隐式前提（下标口径保留，兼容老客户端）。
    rejected_ids = []
    now_ms = int(time.time() * 1000)

    with app_module._write_lock:
        conn = app_module._connect()
        try:
            for idx, item in enumerate(payload):
                # 每条独立处理：任何一条脏数据都不能把整批拖成 500
                # （客户端遇到 500 会永久重试这条毒丸，整条上报队列堵死）。
                # SAVEPOINT 让坏条目只回滚自己，同批好条目照常入库。
                sp = "analysis_item_sp"
                sp_open = False
                try:
                    # 最小校验：必须是对象且带非空 case_id / conversation
                    if (not isinstance(item, dict)
                            or not isinstance(item.get("case_id"), str)
                            or not item["case_id"].strip()
                            or not isinstance(item.get("conversation"), str)
                            or not item["conversation"].strip()):
                        invalid += 1
                        rejected.append(idx)
                        if isinstance(item, dict):
                            _cid = item.get("case_id")
                            if isinstance(_cid, str) and _cid.strip():
                                rejected_ids.append(_cid.strip())
                        continue
                    # 字段长度上限（与 /weixin 的 _validate_item 同口径）：
                    # 超长标识判 invalid，不进库，也不占存档
                    if (len(item["case_id"]) > app_module.MAX_NAME_LEN
                            or len(item["conversation"]) > app_module.MAX_NAME_LEN):
                        invalid += 1
                        rejected.append(idx)
                        rejected_ids.append(item["case_id"].strip())
                        continue
                    s1 = item.get("s1") if isinstance(item.get("s1"), dict) else None
                    s2 = item.get("s2") if isinstance(item.get("s2"), dict) else None
                    # 从 s1 抽出过滤用的标记列
                    need_action = 0
                    importance = None
                    if s1:
                        na = s1.get("need_action")
                        if isinstance(na, dict) and app_module._safe_bool(na.get("value")):
                            need_action = 1
                        importance = app_module._importance_label(s1.get("importance"))
                    forks = item.get("forks") if isinstance(
                        item.get("forks"), list) else None
                    # pkg：与消息表口径一致，老上报缺省按微信回填
                    apkg = item.get("pkg")
                    if not isinstance(apkg, str) or not apkg.strip():
                        apkg = app_module.DEFAULT_PKG
                    # 数值字段必须落在合法区间：原来 int("abc") 直接抛 ValueError
                    # → 整批 500，而且异常时前面的条目已经进库
                    _we = item.get("window_end")
                    window_end = 0 if _we is None else app_module._safe_int(
                        _we, default=None, lo=app_module.MIN_TS_MS, hi=app_module.MAX_TS_MS)
                    _mc = item.get("message_count")
                    message_count = 0 if _mc is None else app_module._safe_int(
                        _mc, default=None, lo=0, hi=app_module.MAX_MESSAGE_COUNT)
                    if window_end is None or message_count is None:
                        invalid += 1
                        rejected.append(idx)
                        rejected_ids.append(item["case_id"])
                        continue
                    todo_text = item.get("todo_text")
                    if not isinstance(todo_text, str):
                        todo_text = None
                    elif len(todo_text) > app_module.MAX_TEXT_LEN:
                        todo_text = todo_text[:app_module.MAX_TEXT_LEN]
                    todo_due = item.get("todo_due")
                    if not isinstance(todo_due, str):
                        todo_due = None
                    elif len(todo_due) > app_module.MAX_NAME_LEN:
                        todo_due = todo_due[:app_module.MAX_NAME_LEN]
                    # JSON 列先序列化再判长度：不给超长载荷留落库的口子
                    s1_text = json.dumps(s1, ensure_ascii=False) if s1 else None
                    s2_text = json.dumps(s2, ensure_ascii=False) if s2 else None
                    forks_text = (json.dumps(forks, ensure_ascii=False)
                                  if forks is not None else None)
                    rules_val = item.get("redaction_rules")
                    rules_text = (json.dumps(rules_val, ensure_ascii=False)
                                  if rules_val is not None else None)
                    hits_val = item.get("redaction_hits")
                    hits_text = (json.dumps(hits_val, ensure_ascii=False)
                                 if hits_val is not None else None)
                    if any(len(t) > app_module.MAX_TEXT_LEN
                           for t in (s1_text, s2_text, forks_text,
                                     rules_text, hits_text) if t):
                        invalid += 1
                        rejected.append(idx)
                        rejected_ids.append(item["case_id"])
                        continue
                    conn.execute("SAVEPOINT " + sp)
                    sp_open = True
                    inserted = False
                    try:
                        conn.execute(
                            """
                            INSERT INTO analyses
                                (case_id, conversation, window_end, message_count,
                                 need_action, importance, escalated, s1, s2,
                                 analyzed_at, protocol, received_at, forks, filtered,
                                 pkg, redacted, redaction_rules, redaction_hits,
                                 todo_text, todo_due)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                                    ?, ?, ?, ?, ?)
                            """,
                            (
                                item["case_id"],
                                item["conversation"],
                                window_end,
                                message_count,
                                need_action,
                                importance,
                                1 if app_module._safe_bool(item.get("escalated")) else 0,
                                s1_text,
                                s2_text,
                                app_module._analyzed_at_text(item.get("analyzed_at")),
                                item.get("protocol") if isinstance(
                                    item.get("protocol"), str) else None,
                                now_ms,
                                forks_text,
                                1 if app_module._safe_bool(item.get("filtered")) else 0,
                                apkg.strip(),
                                1 if app_module._safe_bool(item.get("redacted")) else 0,
                                rules_text,
                                hits_text,
                                todo_text,
                                todo_due,
                            ),
                        )
                        received += 1
                        inserted = True
                    except sqlite3.IntegrityError:
                        duplicated += 1  # case_id 已存在：重复上报
                    finally:
                        if inserted:
                            conn.execute("RELEASE " + sp)
                        else:
                            conn.execute("ROLLBACK TO " + sp)
                            conn.execute("RELEASE " + sp)
                        sp_open = False
                except sqlite3.OperationalError as e:
                    if sp_open:
                        try:
                            conn.execute("ROLLBACK TO " + sp)
                            conn.execute("RELEASE " + sp)
                        except sqlite3.Error:
                            pass
                        sp_open = False
                    if "locked" in str(e).lower() or "busy" in str(e).lower():
                        # 主库被别的写入方占用：瞬时可重试故障，回 503，
                        # 别把它伪装成「条目非法」丢进 rejected
                        conn.rollback()
                        logger.warning("主库被占用，/analysis 返回 503: %s", e)
                        resp = jsonify({
                            "ok": False,
                            "received": 0,
                            "duplicated": 0,
                            "invalid": 0,
                            "error": "main database is busy, retry later",
                        })
                        resp.status_code = 503
                        resp.headers["Retry-After"] = "5"
                        return resp
                    invalid += 1
                    rejected.append(idx)
                    if isinstance(item, dict) and isinstance(
                            item.get("case_id"), str) and item["case_id"].strip():
                        rejected_ids.append(item["case_id"].strip())
                    logger.warning("第 %d 条分析入库失败（已隔离该条）: %s", idx, e)
                except Exception as e:
                    if sp_open:
                        try:
                            conn.execute("ROLLBACK TO " + sp)
                            conn.execute("RELEASE " + sp)
                        except sqlite3.Error:
                            pass
                        sp_open = False
                    invalid += 1
                    rejected.append(idx)
                    if isinstance(item, dict) and isinstance(
                            item.get("case_id"), str) and item["case_id"].strip():
                        rejected_ids.append(item["case_id"].strip())
                    logger.warning("第 %d 条分析入库失败（已隔离该条）: %s", idx, e)
            conn.commit()
        finally:
            conn.close()

    if invalid:
        return jsonify({"ok": False, "received": received,
                        "duplicated": duplicated, "invalid": invalid,
                        "rejected": rejected,
                        "rejected_ids": rejected_ids,
                        "error": "some items were rejected"}), 207
    return jsonify({"ok": True, "received": received,
                    "duplicated": duplicated, "invalid": 0})

def query_analysis():
    """
    查询分析结果。
    参数：
        pkg           来源 App 包名（精确匹配）
        conversation  会话名（模糊匹配）
        todo_only     1/true 只看 need_action=true 的
        date          YYYY-MM-DD，按 analyzed_at 当天过滤
        tz            可选时区（+08:00 / +0800 / +8），影响 date 的当天边界
        tz_offset     可选时区（分钟偏移，480 = 东八区），与 tz 二选一
        limit         默认 100，上限 1000
        before        游标分页：上一页返回的 next_before 原样回传
                      （形如 <received_at毫秒>_<rowid>，也可只给毫秒）
    返回：{"ok": true, "count": n, "analyses": [...], "next_before": ...}
        next_before 只在真的有下一页时才给（与 /messages 同口径）。
    """
    import app as app_module
    auth_err = app_module.check_token()
    if auth_err:
        return auth_err

    pkg = request.args.get("pkg", "").strip()
    conversation = request.args.get("conversation", "").strip()
    todo_only = app_module._safe_bool(request.args.get("todo_only"))
    date_str = request.args.get("date", "").strip()
    before_str = request.args.get("before", "").strip()
    # 注意：tz 不能提前 strip —— query 里未编码的 '+' 会被解码成空格，
    # _parse_tz_offset 需要看到原始的 " 08:00" 才能还原成 "+08:00"。
    tz_parsed = app_module._parse_tz_offset(request.args.get("tz", ""),
                                 request.args.get("tz_offset", "").strip())
    if isinstance(tz_parsed, tuple):
        return jsonify({"ok": False, "error": tz_parsed[1]}), 400
    tz_offset_min = tz_parsed
    try:
        limit = min(int(request.args.get("limit", "100")), 1000)
        if limit <= 0:
            limit = 100
    except ValueError:
        limit = 100

    sql = ("SELECT case_id, conversation, window_end, message_count, "
           "need_action, importance, escalated, s1, s2, analyzed_at, "
           "protocol, received_at, forks, filtered, pkg, "
           "redacted, redaction_rules, redaction_hits, todo_text, todo_due, "
           "rowid FROM analyses")
    conditions, params = [], []
    if pkg:
        conditions.append("pkg = ?")
        params.append(pkg)
    if conversation:
        # 同 /messages：默认前缀匹配（走 idx_analyses_conv_ts 索引），
        # conversation_match=contains 退回旧的「包含」匹配（老口径兼容）。
        if (request.args.get("conversation_match", "") or "").strip().lower() \
                == "contains":
            conditions.append("conversation LIKE ? ESCAPE '\\'")
            params.append(f"%{app_module._like_escape(conversation)}%")
        else:
            conditions.append("conversation >= ? AND conversation < ?")
            params.extend([conversation, app_module._prefix_upper(conversation)])
    if todo_only:
        conditions.append("need_action = 1")
    # 日期过滤（B7）：analyzed_at 有两种形态 —— 本地时间文本（多数）或
    # 毫秒整数文本（老数据）。两种都覆盖：纯数字按毫秒比区间，
    # 其余按日期前缀做字典序比较（'2026-11-10 08:00:00' 这类定长前缀可比）。
    if date_str:
        try:
            day_start, day_end = app_module._day_bounds_ms(date_str, tz_offset_min)
        except ValueError:
            return jsonify({"ok": False, "error": "date must be YYYY-MM-DD"}), 400
        next_day = (datetime.strptime(date_str, "%Y-%m-%d")
                    + timedelta(days=1)).strftime("%Y-%m-%d")
        conditions.append(
            "((analyzed_at <> '' AND analyzed_at NOT GLOB '*[^0-9]*' "
            "  AND CAST(analyzed_at AS INTEGER) >= ? "
            "  AND CAST(analyzed_at AS INTEGER) < ?) "
            "OR (analyzed_at LIKE ? AND analyzed_at < ?))")
        params.extend([day_start, day_end, date_str + "%", next_day])
    # 游标分页（B7）：与 /messages 同款复合游标 before=<received_at>_<rowid>。
    # 行值比较 (received_at, rowid) < (?, ?) 能走 idx_analyses_received_at
    # （EXPLAIN：SEARCH analyses USING INDEX ... (received_at<?)），
    # 原来那种 OR 谓词则是全表 SCAN。只给毫秒时用 int64 上界当 rowid。
    if before_str:
        cursor = app_module._parse_cursor(before_str)
        if cursor[0] == "error":
            return jsonify({"ok": False, "error": cursor[1]}), 400
        before_ms, rid = cursor
        if sqlite3.sqlite_version_info >= (3, 15, 0):
            conditions.append("(received_at, rowid) < (?, ?)")
            params.extend([before_ms, rid])
        else:
            conditions.append(
                "(received_at < ? OR (received_at = ? AND rowid < ?))")
            params.extend([before_ms, before_ms, rid])
    if conditions:
        sql += " WHERE " + " AND ".join(conditions)
    sql += " ORDER BY received_at DESC, rowid DESC LIMIT ?"
    # 多取一条，用来判断还有没有下一页（与 /messages 同口径）
    params.append(limit + 1)

    conn = app_module._connect()
    try:
        rows = conn.execute(sql, params).fetchall()
    finally:
        conn.close()

    has_more = len(rows) > limit
    rows = rows[:limit]
    analyses = []
    skipped = 0
    for r in rows:
        try:
            analyses.append({
                "case_id": r[0],
                "conversation": r[1] if r[1] is not None else "",
                # 数值列归一：脏行给 null，不把脏字符串直接吐给前端
                "window_end": app_module._safe_int(r[2], default=None),
                "message_count": app_module._safe_int(r[3], default=None),
                # s1/s2 解析回对象，与上报结构一致；
                # 坏 JSON 不能再让整个查询 500（原来一行脏数据就挂掉整个接口）
                "s1": app_module._safe_json(r[7]),
                "escalated": bool(r[6]),
                "s2": app_module._safe_json(r[8]),
                # 归一后的重要度 / 是否需要行动：
                # 库里 importance 可能是 0-9 数值，也可能是 high/mid/low 文本，
                # 统一归一成 high/mid/low 标签，前端不用再自己判类型。
                "need_action": bool(r[4]),
                "importance": app_module._importance_label(r[5]),
                "analyzed_at": r[9],
                "protocol": r[10],
                "received_at": r[11],
                # fork 层轨迹（App v0.1.2+；老数据为 None/False）
                "forks": app_module._safe_json(r[12]),
                "filtered": bool(r[13]),
                "pkg": r[14],
                # 脱敏证据与手动改写（客户端一直在上报，服务端以前直接丢掉）
                "redacted": bool(r[15]),
                "redaction_rules": app_module._safe_json(r[16]),
                "redaction_hits": app_module._safe_json(r[17]),
                "todo_text": r[18],
                "todo_due": r[19],
            })
        except Exception as e:
            # 单行脏数据只跳过这一行，不让整页查询失败
            skipped += 1
            logger.warning("analyses 表存在损坏行，已跳过该行: %s", e)
    next_before = None
    # 只有真的还有下一页时才给游标（原来满页就给 → 多一次空请求）
    if has_more and rows:
        next_before = f"{rows[-1][11]}_{rows[-1][20]}"
    return jsonify({
        "ok": True,
        "count": len(analyses),
        "analyses": analyses,
        "next_before": next_before,
        # 跳过的脏行数：调用方能看出「返回条数 < limit」不是分页漏数据
        "skipped": skipped,
        "tz": app_module._fmt_tz_offset(tz_offset_min),
        "tz_offset_minutes": tz_offset_min,
    })

def receive_advisor():
    """
    接收 App 侧的 AI 顾问复盘报告（JSON 数组），每条结构：
        report_id    str   报告 ID（去重键，必需）
        created_at   str   报告生成时间（ISO 字符串）
        period_days  int   复盘覆盖天数
        case_count   int   覆盖的分析用例数
        summary      str   复盘摘要
        suggestions  list  [{type, title, detail}]
                           type: prompt=提示词调优 / watchlist=重点关注 /
                                 threshold=阈值调整 / other=其它
        model_used   str   生成报告的模型
    report_id 已存在的跳过（duplicated），缺 report_id 的跳过（invalid）。
    返回 200：{"ok": True, "inserted": n, "duplicated": m, "invalid": 0}
    返回 207：{"ok": False, "inserted": n, "duplicated": m, "invalid": k,
              "rejected": [下标...], "error": "some items were rejected"}
          （与 /weixin、/analysis 同款语义：被拒的条目不推进客户端的已同步水位）
    """
    import app as app_module
    auth_err = app_module.check_token()
    if auth_err:
        return auth_err

    payload = request.get_json(silent=True)
    if not isinstance(payload, list):
        return jsonify({"ok": False, "error": "body must be a JSON array"}), 400

    inserted = duplicated = invalid = 0
    rejected = []
    # 被拒条目的 report_id（能取到时）：客户端优先按 id 匹配，不依赖下标
    rejected_ids = []

    with app_module._write_lock:
        # 存档损坏时**拒绝写入**：宁可这次上报失败（客户端保留、下轮重报），
        # 也不要拿「空列表 + 新条目」整份覆盖写回去把历史报告抹掉。
        try:
            reports = app_module._load_advisor_reports()
        except app_module.AdvisorArchiveError as e:
            logger.error("顾问存档损坏，拒绝本次上报: %s", e)
            return jsonify({
                "ok": False,
                "inserted": 0,
                "duplicated": 0,
                "invalid": 0,
                "error": "advisor archive is corrupted, refusing to overwrite it",
                "detail": str(e),
            }), 500
        seen = {r.get("report_id") for r in reports if isinstance(r, dict)}
        new_items = []
        for idx, item in enumerate(payload):
            norm, why = app_module._normalize_advisor_item(item)
            if norm is None:
                invalid += 1
                rejected.append(idx)
                if isinstance(item, dict):
                    _rid = item.get("report_id")
                    if isinstance(_rid, str) and _rid.strip():
                        rejected_ids.append(_rid.strip()[:app_module.MAX_NAME_LEN])
                logger.warning("第 %d 条顾问报告被拒: %s", idx, why)
                continue
            if norm["report_id"] in seen:
                duplicated += 1  # report_id 已存在：重复上报
                continue
            norm["received_at"] = int(time.time() * 1000)  # 服务端接收时间（毫秒）
            new_items.append(norm)
            seen.add(norm["report_id"])
            inserted += 1
        if inserted:
            # 追加写主存储（O(1)），不再整份重写整个数组（B9）
            app_module._append_advisor_reports(new_items)
            reports.extend(new_items)
            # 有界裁剪：条数超上限时只保留最近的
            if len(reports) > app_module.ADVISOR_MAX_REPORTS:
                reports = reports[-app_module.ADVISOR_MAX_REPORTS:]
                app_module._trim_advisor_jsonl(app_module.ADVISOR_MAX_REPORTS)
            # 兼容快照（JSON 数组）：只在有变化时写
            app_module._write_advisor_snapshot(reports)

    # 有条目被拒就回 207：客户端据此把被拒的报告留到下一轮重报，而不是
    # 把它当成已入库（原来恒返回 200，客户端会把被拒报告也标成已同步 → 永久丢失）。
    if invalid:
        return jsonify({"ok": False, "inserted": inserted,
                        "duplicated": duplicated, "invalid": invalid,
                        "rejected": rejected,
                        "rejected_ids": rejected_ids,
                        "error": "some items were rejected"}), 207
    return jsonify({"ok": True, "inserted": inserted,
                    "duplicated": duplicated, "invalid": 0})

def query_advisor():
    """
    查询顾问报告（按接收时间倒序，最新在前）。
    参数：limit 默认 20，上限 100
    返回：{"ok": true, "count": n, "reports": [...]}
    """
    import app as app_module
    auth_err = app_module.check_token()
    if auth_err:
        return auth_err

    try:
        limit = min(int(request.args.get("limit", "20")), 100)
        if limit <= 0:
            limit = 20
    except ValueError:
        limit = 20

    try:
        reports = app_module._load_advisor_reports()
    except app_module.AdvisorArchiveError as e:
        # 这里不返回 5xx：控制台一次并发拉 /messages、/analysis、/advisor 三个接口
        # （Promise.all），任何一个 500 会让整页控制台都读不出数据。
        # 改成 200 + ok:false + error，消息/分析照常显示，顾问页给出错误提示。
        logger.error("顾问存档损坏，暂时无法读取: %s", e)
        return jsonify({
            "ok": False,
            "count": 0,
            "reports": [],
            "error": "advisor archive is corrupted",
            "detail": str(e),
        })
    reports = [r for r in reports if isinstance(r, dict)]
    # 排序键必须是数字：received_at 是写入路径自己赋的毫秒，但 JSONL 由人工修复 /
    # 旧快照迁移 / 第三方脚本写入时可能是字符串或缺失，直接拿原值当键会让
    # int 与 str 比较抛 TypeError → 整页 500（实测）。口径统一走 _advisor_ts。
    reports.sort(
        key=lambda r: (app_module._advisor_ts(r.get("received_at")),
                       app_module._advisor_ts(r.get("created_at"))),
        reverse=True)

    reports = reports[:limit]
    return jsonify({"ok": True, "count": len(reports), "reports": reports})

def admin_extract():
    """
    触发一次微信数据库提取（后台线程异步执行）。
    参数：
        full=1  全量重扫（默认增量）
        source  提取来源：android（默认，现有行为）/ pc（PC 微信 4.x 库）/
                all（两者都跑）
    返回：{"ok": true, "started": true}；已有任务在跑则 409。
    """
    import app as app_module
    auth_err = app_module.check_token()
    if auth_err:
        return auth_err

    full = request.args.get("full", "").lower() in ("1", "true", "yes")
    source = request.args.get("source", "android").lower()
    if source not in ("android", "pc", "all"):
        return jsonify({
            "ok": False,
            "error": "source must be one of: android / pc / all"}), 400
    # 主库被占用（另一个提取任务正在批量提交 / 有长事务没提交）时
    # 直接回 503 + Retry-After：提取要往同一个库里写，硬启动只会让后台
    # 线程默默跑一半再失败，用户还看到「已启动」。
    state, detail = app_module._probe_main_db_writable()
    if state == "busy":
        resp = jsonify({"ok": False,
                        "error": "main database is busy, retry later",
                        "detail": detail})
        resp.status_code = 503
        resp.headers["Retry-After"] = "5"
        return resp
    if state == "error":
        logger.error("主库不可写，提取未启动: %s", detail)
        return jsonify({"ok": False, "error": "main database is not writable",
                        "detail": detail}), 500
    with app_module._extract_lock:
        if app_module._extract_status["running"]:
            return jsonify({"ok": False, "error": "extract already running"}), 409
        app_module._extract_status["running"] = True
    t = threading.Thread(target=app_module._run_extract_bg, args=(full, source),
                         daemon=True)
    t.start()
    return jsonify({"ok": True, "started": True, "full": full,
                    "source": source})

def admin_extract_status():
    """查询提取状态：running + 最近一次的统计（条数/耗时/错误）。"""
    import app as app_module
    auth_err = app_module.check_token()
    if auth_err:
        return auth_err
    with app_module._extract_lock:
        return jsonify({
            "ok": True,
            "running": app_module._extract_status["running"],
            "last": app_module._extract_status["last"],
        })


def receive_queue():
    """
    接收端上报的排队任务状态（排队中 / 运行中 / 失败）。
    请求体：{"device_id": "android-xxxxxxxx", "tasks": [{...}]}
    - 逐条校验，非法条目单独拒收（不影响同批其它条目）
    - 按 (device_id, task_id) 幂等 upsert：同一条任务反复上报只更新那一行
    返回：{"ok": true, "accepted": n, "rejected": [{"index": i, "reason": "..."}]}
    """
    import app as app_module
    import storage as storage_module
    auth_err = app_module.check_token()
    if auth_err:
        return auth_err

    payload = request.get_json(silent=True)
    if not isinstance(payload, dict):
        return jsonify({
            "ok": False,
            "error": "body must be a JSON object"
        }), 400

    device_id = payload.get("device_id")
    if not isinstance(device_id, str) or not device_id.strip():
        return jsonify({
            "ok": False,
            "error": "device_id is required"
        }), 400
    device_id = device_id.strip()
    if len(device_id) > storage_module.QUEUE_DEVICE_ID_MAX_LEN:
        return jsonify({
            "ok": False,
            "error": "device_id too long"
        }), 400

    tasks = payload.get("tasks")
    if not isinstance(tasks, list):
        return jsonify({
            "ok": False,
            "error": "tasks must be a JSON array"
        }), 400

    # device_id 在请求体顶层，任务条目里不重复带：主键是 (device_id, task_id)，
    # 每条都抄一遍设备号只会让报文变大。
    items = []
    for task in tasks:
        if isinstance(task, dict):
            item = dict(task)
            item["device_id"] = device_id
            items.append(item)
        else:
            # 非对象条目保留在原位，交给存储层逐条拒收（rejected 里报原始下标）
            items.append(task)

    now_ms = int(time.time() * 1000)
    try:
        accepted, rejected = storage_module.upsert_queue_tasks(items, now_ms)
    except sqlite3.OperationalError as e:
        # 主库正被提取任务占着：回 503 + Retry-After 让端稍后重试。
        # 不能当成成功 —— 端会以为状态已上报，从而把这条任务从本地队列里丢掉。
        text = str(e).lower()
        if "locked" in text or "busy" in text:
            resp = jsonify({
                "ok": False,
                "accepted": 0,
                "rejected": [],
                "error": "main database is busy, retry later"
            })
            resp.status_code = 503
            resp.headers["Retry-After"] = "5"
            return resp
        raise
    return jsonify({"ok": True, "accepted": accepted, "rejected": rejected})

def query_queue():
    """
    查询排队任务（按更新时间倒序，最新的在前）。
    参数：device_id（可选）、state（可选）、limit（默认 200，上限 500）
    返回：{"ok": true, "tasks": [...], "summary": {...}, "devices": [...]}

    devices 是「库里出现过哪几台设备」的清单（device_id / total / active），
    它统计的是全库，不受 device_id / state / limit 过滤影响 —— 
    过滤只影响 tasks 与 summary 的计数口径。
    summary.devices = len(devices)，即库里一共几台设备。
    """
    import app as app_module
    import storage as storage_module
    auth_err = app_module.check_token()
    if auth_err:
        return auth_err

    device_id = (request.args.get("device_id") or "").strip()
    state = (request.args.get("state") or "").strip()
    try:
        limit = int(request.args.get("limit", storage_module.QUEUE_DEFAULT_LIMIT))
    except (TypeError, ValueError):
        limit = storage_module.QUEUE_DEFAULT_LIMIT
    if limit <= 0:
        limit = storage_module.QUEUE_DEFAULT_LIMIT
    limit = min(limit, storage_module.QUEUE_MAX_LIMIT)

    tasks = storage_module.query_queue_tasks(device_id, state, limit)

    # summary 只统计「没过期」的条目：过期任务不再代表端上的真实队列，
    # 混进 queued/running 会让控制台显示一个其实早就没了的「在跑」任务。
    summary = {"queued": 0, "running": 0, "failed": 0, "done": 0,
               "stale": 0, "total": len(tasks)}
    for task in tasks:
        if task.get("stale"):
            summary["stale"] += 1
            continue
        task_state = task.get("state")
        if task_state in storage_module.QUEUE_STATES:
            summary[task_state] += 1

    # 设备维度清单：回答「有哪几台设备」，统计全库（不过滤 device_id/state），
    # 与 tasks 的过滤口径解耦 —— 前端既能列设备，也能筛某台设备看它的任务。
    devices = storage_module.queue_devices()
    summary["devices"] = len(devices)
    return jsonify({"ok": True, "tasks": tasks, "summary": summary,
                    "devices": devices})

def register(app):
    """把数据面路由挂载到 Flask 应用（endpoint 名与拆分前一致）。"""
    app.add_url_rule("/weixin", "receive_weixin", receive_weixin, methods=["POST"])
    app.add_url_rule("/messages", "query_messages", query_messages, methods=["GET"])
    app.add_url_rule("/analysis", "receive_analysis", receive_analysis, methods=["POST"])
    app.add_url_rule("/analysis", "query_analysis", query_analysis, methods=["GET"])
    app.add_url_rule("/advisor", "receive_advisor", receive_advisor, methods=["POST"])
    app.add_url_rule("/advisor", "query_advisor", query_advisor, methods=["GET"])
    app.add_url_rule("/queue", "receive_queue", receive_queue, methods=["POST"])
    app.add_url_rule("/queue", "query_queue", query_queue, methods=["GET"])
    app.add_url_rule("/admin/extract", "admin_extract", admin_extract, methods=["POST"])
    app.add_url_rule("/admin/extract/status", "admin_extract_status", admin_extract_status, methods=["GET"])

