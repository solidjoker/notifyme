# -*- coding: utf-8 -*-
# Copyright (c) 2026 solidjoker
# SPDX-License-Identifier: MIT

"""
微信消息接收服务端
====================
配合 weixin_android 安卓端使用：
安卓端监听微信通知，定时把消息以 JSON 数组的形式 POST 到本服务的 /weixin 接口。

上报协议：
    POST serverUrl
    Content-Type: application/json
    可选请求头: X-Token（服务端配置了 WEIXIN_TOKEN 时必需）
    body 为 JSON 数组，每条消息字段：
        sender       str    发送者昵称
        text         str    消息内容
        timestamp    number 消息时间戳（毫秒）
        conversation str    会话名（群名或联系人名）
        is_group     bool   是否群聊

启动：
    python app.py            # 默认监听 0.0.0.0:8000
    set PORT=9000 && python app.py    # 自定义端口（Windows cmd）

安全提示：
    公网部署时务必设置环境变量 WEIXIN_TOKEN 开启鉴权，
    并放在 caddy / nginx 等反向代理后面启用 HTTPS，不要裸奔！
"""

import json
import os
import sqlite3
import threading
import time
from datetime import datetime

from flask import Flask, request, jsonify, Response, send_from_directory

app = Flask(__name__)

# ------------------------------------------------------------------
# 配置
# ------------------------------------------------------------------

# 鉴权令牌：环境变量 WEIXIN_TOKEN 非空时，所有请求必须带匹配的 X-Token 头。
# 未设置则不鉴权（仅限局域网自用；公网部署务必设置！）
TOKEN = os.environ.get("WEIXIN_TOKEN", "").strip()

# 监听端口：从环境变量 PORT 读取，默认 8000
PORT = int(os.environ.get("PORT", "8000"))

# 数据目录与文件路径（相对于本文件所在目录，保证从任何目录启动都能找到）
BASE_DIR = os.path.dirname(os.path.abspath(__file__))
DATA_DIR = os.path.join(BASE_DIR, "data")
JSONL_PATH = os.path.join(DATA_DIR, "messages.jsonl")  # 原文存档（与安卓端格式一致）
DB_PATH = os.path.join(DATA_DIR, "messages.db")        # SQLite 查询库
ADVISOR_PATH = os.path.join(DATA_DIR, "advisor_reports.json")  # 顾问层报告存档（JSON 数组）

# 写入锁：保护 jsonl 文件和 SQLite 的并发写入
_write_lock = threading.Lock()

# 消息必备字段及期望类型（用于逐条校验）
REQUIRED_FIELDS = {
    "sender": str,
    "text": str,
    "timestamp": (int, float),  # 毫秒时间戳，允许 int 或 float
    "conversation": str,
    "is_group": bool,
}


# ------------------------------------------------------------------
# 数据库初始化
# ------------------------------------------------------------------

def init_db():
    """初始化数据目录和 SQLite 表结构（含去重用的唯一索引）。"""
    os.makedirs(DATA_DIR, exist_ok=True)
    conn = sqlite3.connect(DB_PATH)
    try:
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS messages (
                id            INTEGER PRIMARY KEY AUTOINCREMENT,
                sender        TEXT,
                text          TEXT,
                timestamp     INTEGER,   -- 消息时间戳（毫秒）
                conversation  TEXT,
                is_group      INTEGER,   -- SQLite 无布尔型，0/1 存储
                received_at   INTEGER    -- 服务端接收时间（毫秒）
            )
            """
        )
        # 唯一索引：同一条消息（会话+发送者+时间戳+内容）只入库一次，
        # 防止手机端重试导致重复。重复插入会抛 IntegrityError，按“重复”计数。
        conn.execute(
            """
            CREATE UNIQUE INDEX IF NOT EXISTS idx_messages_dedup
            ON messages (conversation, sender, timestamp, text)
            """
        )
        # 迁移：老库补 source 列，区分消息来源
        #   android-notification = 通知监听上报（默认，历史数据）
        #   android-db           = 安卓数据库直读合并（android_db_extract.py）
        # 说明：跨来源去重不依赖此索引（毫秒时间戳不一致），
        # 由 android_db_extract 按秒级归一键在 Python 侧完成；
        # 此索引继续负责通知来源的重试去重和毫秒级完全相同时的兜底。
        cols = [r[1] for r in conn.execute("PRAGMA table_info(messages)")]
        if "source" not in cols:
            conn.execute(
                "ALTER TABLE messages ADD COLUMN source TEXT "
                "NOT NULL DEFAULT 'android-notification'")
        # 分析结果表：App 侧会话级分析（S1 轻量模型 + S2 深度分析）的上报存档
        # case_id 唯一：同一次分析重复上报直接跳过
        # s1 / s2 存 JSON 原文；need_action / importance 单独抽列方便过滤
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS analyses (
                id            INTEGER PRIMARY KEY AUTOINCREMENT,
                case_id       TEXT UNIQUE,   -- 分析用例 ID（App 侧生成，去重键）
                conversation  TEXT,          -- 会话名
                window_end    INTEGER,       -- 分析窗口右端（毫秒）
                message_count INTEGER,       -- 窗口内消息数
                need_action   INTEGER,       -- S1 判定是否需要行动 0/1
                importance    TEXT,          -- S1 重要度（如 high/mid/low）
                escalated     INTEGER,       -- 是否升级到 S2 深度分析 0/1
                s1            TEXT,          -- S1 结果 JSON 原文
                s2            TEXT,          -- S2 结果 JSON 原文（未升级为 NULL）
                analyzed_at   TEXT,          -- App 侧分析完成时间（原样存）
                protocol      TEXT,          -- 协议版本
                received_at   INTEGER        -- 服务端接收时间（毫秒）
            )
            """
        )
        # 迁移：老库补 forks / filtered 列（fork 层改造，App 侧 v0.1.2+ 上报）
        #   forks    = 分叉决策轨迹 JSON 数组 [{fork, prob, verdict, note}]
        #   filtered = 被 fork1 预筛就地结案 0/1（filtered 的 case 不调 laya）
        acols = [r[1] for r in conn.execute("PRAGMA table_info(analyses)")]
        if "forks" not in acols:
            conn.execute("ALTER TABLE analyses ADD COLUMN forks TEXT")
        if "filtered" not in acols:
            conn.execute(
                "ALTER TABLE analyses ADD COLUMN filtered INTEGER "
                "NOT NULL DEFAULT 0")
        conn.commit()
    finally:
        conn.close()


# ------------------------------------------------------------------
# 鉴权
# ------------------------------------------------------------------

def check_token():
    """校验令牌。返回 None 表示通过，否则返回 401 响应。

    支持两种传法（二选一）：
      1. 请求头 X-Token（API 调用、App 上报用）
      2. URL query ?token=xxx（iPhone Safari 等不方便带自定义头的浏览器；
         /console 页面会把 query token 写进 localStorage，之后页面内
         fetch 自动改用 X-Token 头）
    """
    if not TOKEN:
        return None  # 未配置 token，不鉴权
    if request.headers.get("X-Token", "") == TOKEN:
        return None
    if request.args.get("token", "") == TOKEN:
        return None
    return jsonify({"ok": False, "error": "unauthorized"}), 401


# ------------------------------------------------------------------
# 接口：POST /weixin 接收消息
# ------------------------------------------------------------------

@app.route("/weixin", methods=["POST"])
def receive_weixin():
    """
    接收安卓端上报的消息数组。
    - 逐条校验字段，缺字段/类型错误的条目跳过并计数（invalid）
    - 合法条目追加到 data/messages.jsonl（原文存档）并写入 SQLite
    - 已在库中的重复消息跳过（duplicated）
    返回: {"ok": true, "received": n, "duplicated": m, "invalid": k}
    """
    auth_err = check_token()
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

    now_ms = int(time.time() * 1000)  # 服务端接收时间（毫秒）

    with _write_lock:
        conn = sqlite3.connect(DB_PATH)
        try:
            for item in payload:
                # 每条必须是对象且字段齐全、类型正确
                if not isinstance(item, dict) or not _validate_item(item):
                    invalid += 1
                    continue

                # 1) 追加写入 jsonl 原文存档（与安卓端格式一致的原始字段）
                with open(JSONL_PATH, "a", encoding="utf-8") as f:
                    f.write(json.dumps(item, ensure_ascii=False) + "\n")

                # 2) 写入 SQLite；唯一索引冲突说明是重复消息
                # source 尊重客户端标记（a11y-extract / pc-db 等），
                # 缺省才是 android-notification
                source = item.get("source")
                if not isinstance(source, str) or not source.strip():
                    source = "android-notification"
                try:
                    conn.execute(
                        """
                        INSERT INTO messages
                            (sender, text, timestamp, conversation, is_group,
                             received_at, source)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """,
                        (
                            item["sender"],
                            item["text"],
                            int(item["timestamp"]),
                            item["conversation"],
                            1 if item["is_group"] else 0,
                            now_ms,
                            source.strip(),
                        ),
                    )
                    received += 1
                except sqlite3.IntegrityError:
                    # 命中唯一索引：手机端重试造成的重复上报
                    duplicated += 1
            conn.commit()
        finally:
            conn.close()

    return jsonify({
        "ok": True,
        "received": received,
        "duplicated": duplicated,
        "invalid": invalid,
    })


def _validate_item(item):
    """校验单条消息的必备字段及类型。"""
    for field, expected_type in REQUIRED_FIELDS.items():
        if field not in item:
            return False
        value = item[field]
        # bool 是 int 的子类，is_group 必须严格是 bool，timestamp 不能是 bool
        if field == "is_group":
            if not isinstance(value, bool):
                return False
        elif field == "timestamp":
            if isinstance(value, bool) or not isinstance(value, expected_type):
                return False
        elif not isinstance(value, expected_type):
            return False
    return True


# ------------------------------------------------------------------
# 接口：GET /messages 查询消息
# ------------------------------------------------------------------

@app.route("/messages", methods=["GET"])
def query_messages():
    """
    查询消息。
    参数：
        conversation  会话名（模糊匹配）
        date          YYYY-MM-DD，按消息 timestamp 过滤当天
        limit         返回条数，默认 100，上限 1000
    返回：{"ok": true, "count": n, "messages": [...]}
    """
    auth_err = check_token()
    if auth_err:
        return auth_err

    conversation = request.args.get("conversation", "").strip()
    date_str = request.args.get("date", "").strip()
    try:
        limit = min(int(request.args.get("limit", "100")), 1000)
        if limit <= 0:
            limit = 100
    except ValueError:
        limit = 100

    sql = "SELECT sender, text, timestamp, conversation, is_group, received_at, source FROM messages"
    conditions = []
    params = []

    # 会话名模糊过滤
    if conversation:
        conditions.append("conversation LIKE ?")
        params.append(f"%{conversation}%")

    # 按日期过滤（timestamp 落在当天 [00:00, 次日00:00) 区间）
    if date_str:
        try:
            day_start = int(datetime.strptime(date_str, "%Y-%m-%d").timestamp() * 1000)
            day_end = day_start + 24 * 3600 * 1000
            conditions.append("timestamp >= ? AND timestamp < ?")
            params.extend([day_start, day_end])
        except ValueError:
            return jsonify({"ok": False, "error": "date must be YYYY-MM-DD"}), 400

    if conditions:
        sql += " WHERE " + " AND ".join(conditions)
    sql += " ORDER BY timestamp DESC LIMIT ?"
    params.append(limit)

    conn = sqlite3.connect(DB_PATH)
    try:
        rows = conn.execute(sql, params).fetchall()
    finally:
        conn.close()

    messages = [
        {
            "sender": r[0],
            "text": r[1],
            "timestamp": r[2],
            "conversation": r[3],
            "is_group": bool(r[4]),
            "received_at": r[5],
            "source": r[6],
        }
        for r in rows
    ]
    return jsonify({"ok": True, "count": len(messages), "messages": messages})


# ------------------------------------------------------------------
# 接口：分析结果上报与查询
# ------------------------------------------------------------------

@app.route("/analysis", methods=["POST"])
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
    case_id 已存在的跳过（duplicated），缺 case_id/conversation 的跳过（invalid）。
    返回：{"ok": true, "received": n, "duplicated": m, "invalid": k}
    """
    auth_err = check_token()
    if auth_err:
        return auth_err

    payload = request.get_json(silent=True)
    if not isinstance(payload, list):
        return jsonify({"ok": False, "error": "body must be a JSON array"}), 400

    received = duplicated = invalid = 0
    now_ms = int(time.time() * 1000)

    with _write_lock:
        conn = sqlite3.connect(DB_PATH)
        try:
            for item in payload:
                # 最小校验：必须是对象且带 case_id / conversation
                if (not isinstance(item, dict)
                        or not isinstance(item.get("case_id"), str)
                        or not isinstance(item.get("conversation"), str)):
                    invalid += 1
                    continue
                s1 = item.get("s1") if isinstance(item.get("s1"), dict) else None
                s2 = item.get("s2") if isinstance(item.get("s2"), dict) else None
                # 从 s1 抽出过滤用的标记列
                need_action = 0
                importance = None
                if s1:
                    na = s1.get("need_action")
                    if isinstance(na, dict) and na.get("value") is True:
                        need_action = 1
                    if isinstance(s1.get("importance"), str):
                        importance = s1["importance"]
                forks = item.get("forks") if isinstance(
                    item.get("forks"), list) else None
                try:
                    conn.execute(
                        """
                        INSERT INTO analyses
                            (case_id, conversation, window_end, message_count,
                             need_action, importance, escalated, s1, s2,
                             analyzed_at, protocol, received_at, forks, filtered)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                        (
                            item["case_id"],
                            item["conversation"],
                            int(item.get("window_end") or 0),
                            int(item.get("message_count") or 0),
                            need_action,
                            importance,
                            1 if item.get("escalated") else 0,
                            json.dumps(s1, ensure_ascii=False) if s1 else None,
                            json.dumps(s2, ensure_ascii=False) if s2 else None,
                            item.get("analyzed_at") if isinstance(
                                item.get("analyzed_at"), str) else None,
                            item.get("protocol") if isinstance(
                                item.get("protocol"), str) else None,
                            now_ms,
                            json.dumps(forks, ensure_ascii=False)
                                if forks else None,
                            1 if item.get("filtered") else 0,
                        ),
                    )
                    received += 1
                except sqlite3.IntegrityError:
                    duplicated += 1  # case_id 已存在：重复上报
            conn.commit()
        finally:
            conn.close()

    return jsonify({"ok": True, "received": received,
                    "duplicated": duplicated, "invalid": invalid})


@app.route("/analysis", methods=["GET"])
def query_analysis():
    """
    查询分析结果。
    参数：
        conversation  会话名（模糊匹配）
        todo_only     1/true 只看 need_action=true 的
        limit         默认 100，上限 1000
    返回：{"ok": true, "count": n, "analyses": [...]}（s1/s2 已解析回对象）
    """
    auth_err = check_token()
    if auth_err:
        return auth_err

    conversation = request.args.get("conversation", "").strip()
    todo_only = request.args.get("todo_only", "").lower() in ("1", "true", "yes")
    try:
        limit = min(int(request.args.get("limit", "100")), 1000)
        if limit <= 0:
            limit = 100
    except ValueError:
        limit = 100

    sql = ("SELECT case_id, conversation, window_end, message_count, "
           "need_action, importance, escalated, s1, s2, analyzed_at, "
           "protocol, received_at, forks, filtered FROM analyses")
    conditions, params = [], []
    if conversation:
        conditions.append("conversation LIKE ?")
        params.append(f"%{conversation}%")
    if todo_only:
        conditions.append("need_action = 1")
    if conditions:
        sql += " WHERE " + " AND ".join(conditions)
    sql += " ORDER BY received_at DESC LIMIT ?"
    params.append(limit)

    conn = sqlite3.connect(DB_PATH)
    try:
        rows = conn.execute(sql, params).fetchall()
    finally:
        conn.close()

    analyses = []
    for r in rows:
        analyses.append({
            "case_id": r[0],
            "conversation": r[1],
            "window_end": r[2],
            "message_count": r[3],
            # s1/s2 解析回对象，与上报结构一致
            "s1": json.loads(r[7]) if r[7] else None,
            "escalated": bool(r[6]),
            "s2": json.loads(r[8]) if r[8] else None,
            "analyzed_at": r[9],
            "protocol": r[10],
            "received_at": r[11],
            # fork 层轨迹（App v0.1.2+；老数据为 None/False）
            "forks": json.loads(r[12]) if r[12] else None,
            "filtered": bool(r[13]),
        })
    return jsonify({"ok": True, "count": len(analyses), "analyses": analyses})


# ------------------------------------------------------------------
# 接口：顾问层（Fable）复盘报告上报与查询
# ------------------------------------------------------------------
# 顾问报告量小（每天至多几条），不走 SQLite，直接存
# data/advisor_reports.json（JSON 数组，按接收顺序追加），
# report_id 为去重键：重复上报跳过（duplicated）。

def _load_advisor_reports():
    """读取顾问报告存档。文件不存在或损坏时返回空列表。"""
    if not os.path.exists(ADVISOR_PATH):
        return []
    try:
        with open(ADVISOR_PATH, encoding="utf-8") as f:
            data = json.load(f)
        return data if isinstance(data, list) else []
    except (json.JSONDecodeError, OSError):
        return []


def _save_advisor_reports(reports):
    """覆盖写顾问报告存档（先写临时文件再替换，避免写一半损坏）。"""
    tmp = ADVISOR_PATH + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(reports, f, ensure_ascii=False, indent=2)
    os.replace(tmp, ADVISOR_PATH)


@app.route("/advisor", methods=["POST"])
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
    返回：{"ok": true, "inserted": n, "duplicated": m, "invalid": k}
    """
    auth_err = check_token()
    if auth_err:
        return auth_err

    payload = request.get_json(silent=True)
    if not isinstance(payload, list):
        return jsonify({"ok": False, "error": "body must be a JSON array"}), 400

    inserted = duplicated = invalid = 0

    with _write_lock:
        reports = _load_advisor_reports()
        seen = {r.get("report_id") for r in reports if isinstance(r, dict)}
        for item in payload:
            # 最小校验：必须是对象且带 report_id
            if (not isinstance(item, dict)
                    or not isinstance(item.get("report_id"), str)
                    or not item["report_id"]):
                invalid += 1
                continue
            if item["report_id"] in seen:
                duplicated += 1  # report_id 已存在：重复上报
                continue
            # suggestions 只保留合法对象条目，防脏数据
            if not isinstance(item.get("suggestions"), list):
                item["suggestions"] = []
            item["suggestions"] = [
                s for s in item["suggestions"] if isinstance(s, dict)]
            item["received_at"] = int(time.time() * 1000)  # 服务端接收时间（毫秒）
            reports.append(item)
            seen.add(item["report_id"])
            inserted += 1
        if inserted:
            _save_advisor_reports(reports)

    return jsonify({"ok": True, "inserted": inserted,
                    "duplicated": duplicated, "invalid": invalid})


@app.route("/advisor", methods=["GET"])
def query_advisor():
    """
    查询顾问报告（按接收时间倒序，最新在前）。
    参数：limit 默认 20，上限 100
    返回：{"ok": true, "count": n, "reports": [...]}
    """
    auth_err = check_token()
    if auth_err:
        return auth_err

    try:
        limit = min(int(request.args.get("limit", "20")), 100)
        if limit <= 0:
            limit = 20
    except ValueError:
        limit = 20

    reports = _load_advisor_reports()
    reports = [r for r in reports if isinstance(r, dict)]
    reports.sort(key=lambda r: r.get("received_at", 0), reverse=True)
    reports = reports[:limit]
    return jsonify({"ok": True, "count": len(reports), "reports": reports})


# ------------------------------------------------------------------
# 接口：微信数据库直读提取（管理端点，安卓 / PC 两路）
# ------------------------------------------------------------------

# 提取任务状态（内存态；增量游标分别落在 data/android_db/state.json 与
# data/pc_db/state.json）
_extract_status = {
    "running": False,       # 是否有提取任务正在跑
    "last": None,           # 最近一次完成/失败的统计与错误
}
_extract_lock = threading.Lock()


def _run_extract_bg(full, source):
    """后台线程跑提取，结果写进 _extract_status。

    source：android（安卓数据库直读）/ pc（PC 微信 4.x 数据库直读）/
    all（两者都跑）。各路独立执行：一路失败只记该路的 error，
    不拖垮另一路；整体 ok = 至少一路成功。
    """
    result = {"source": source}
    legs = []
    if source in ("android", "all"):
        legs.append("android")
    if source in ("pc", "all"):
        legs.append("pc")
    for leg in legs:
        try:
            mod = __import__(f"{leg}_db_extract")
            result[leg] = mod.run_extract(full=full)
        except Exception as e:  # 单路失败不拖垮服务与其它路
            result[leg] = {"ok": False, "error": str(e)}
    ok = any(isinstance(result[leg], dict) and result[leg].get("ok") is not False
             for leg in legs)
    with _extract_lock:
        _extract_status["last"] = {"ok": ok, **result}
        _extract_status["running"] = False


@app.route("/admin/extract", methods=["POST"])
def admin_extract():
    """
    触发一次微信数据库提取（后台线程异步执行）。
    参数：
        full=1  全量重扫（默认增量）
        source  提取来源：android（默认，现有行为）/ pc（PC 微信 4.x 库）/
                all（两者都跑）
    返回：{"ok": true, "started": true}；已有任务在跑则 409。
    """
    auth_err = check_token()
    if auth_err:
        return auth_err

    full = request.args.get("full", "").lower() in ("1", "true", "yes")
    source = request.args.get("source", "android").lower()
    if source not in ("android", "pc", "all"):
        return jsonify({
            "ok": False,
            "error": "source must be one of: android / pc / all"}), 400
    with _extract_lock:
        if _extract_status["running"]:
            return jsonify({"ok": False, "error": "extract already running"}), 409
        _extract_status["running"] = True
    t = threading.Thread(target=_run_extract_bg, args=(full, source),
                         daemon=True)
    t.start()
    return jsonify({"ok": True, "started": True, "full": full,
                    "source": source})


@app.route("/admin/extract/status", methods=["GET"])
def admin_extract_status():
    """查询提取状态：running + 最近一次的统计（条数/耗时/错误）。"""
    auth_err = check_token()
    if auth_err:
        return auth_err
    with _extract_lock:
        return jsonify({
            "ok": True,
            "running": _extract_status["running"],
            "last": _extract_status["last"],
        })


# ------------------------------------------------------------------
# iPhone 移动端控制台（PWA）
# ------------------------------------------------------------------
# iOS 无法做采集端（没有通知监听 API、不能后台常驻），只能做查看端。
# /console 是移动优先的单页应用：内联全部 HTML/CSS/JS，不引任何 CDN，
# 配合 manifest + service worker 可「添加到主屏幕」像原生 App 一样全屏打开。
#
# token 传递：iPhone Safari 不方便带自定义请求头，首次用
#   https://<域名>/console?token=xxx
# 打开，页面把 token 写进 localStorage，之后所有 fetch 自动带 X-Token 头，
# 书签 / 主屏幕图标都不再需要 query。

CONSOLE_HTML = """<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<meta name="apple-mobile-web-app-capable" content="yes">
<meta name="mobile-web-app-capable" content="yes">
<meta name="apple-mobile-web-app-status-bar-style" content="black-translucent">
<meta name="apple-mobile-web-app-title" content="微信分析助手">
<meta name="theme-color" content="#07C160">
<title>微信分析助手</title>
<script>
// 页面所在目录（域根绝对路径，兼容 /console、/mmonitor/console 及末尾斜杠）
// /console          -> /
// /mmonitor/console -> /mmonitor/
// /mmonitor/console/-> /mmonitor/
window.__WX_DIR = (function () {
  var p = location.pathname;
  while (p.length > 1 && p.charAt(p.length - 1) === '/') p = p.slice(0, -1);
  return p.slice(0, p.lastIndexOf('/') + 1);
})();
// manifest / apple-touch-icon 链接用 JS 注入：静态相对 href 在页面带末尾
// 斜杠时会解析错，域根绝对 href 又会丢掉 /mmonitor/ 前缀
(function () {
  var head = document.head;
  function addLink(rel, href) {
    var l = document.createElement('link');
    l.rel = rel;
    l.href = href;
    head.appendChild(l);
  }
  addLink('manifest', window.__WX_DIR + 'console/manifest.json');
  // iOS 主屏幕图标（只认 PNG，不认 SVG/data URI）
  addLink('apple-touch-icon', window.__WX_DIR + 'console/icons/icon-180.png');
})();
</script>
<style>
  * { box-sizing: border-box; -webkit-tap-highlight-color: transparent; }
  html, body { overscroll-behavior-y: contain; }
  body { margin: 0; font-family: -apple-system, "PingFang SC", "Microsoft YaHei", sans-serif;
         background: #f5f5f5; color: #333; font-size: 16px; }
  header { position: sticky; top: 0; z-index: 10; background: #07C160; color: #fff;
           padding: calc(env(safe-area-inset-top) + 12px) 16px 12px; }
  header h1 { font-size: 19px; margin: 0; }
  /* 同步状态行：最近刷新时间 / 数据量 / 失败提示 */
  .sync-line { font-size: 12px; color: rgba(255,255,255,.85); margin-top: 4px; }
  .sync-line.err { color: #ffe58f; }
  /* 底部 tab 栏（iPhone 拇指可达区），高度含 Home 指示条安全区 */
  .tabbar { position: fixed; left: 0; right: 0; bottom: 0; z-index: 20;
            display: flex; background: #fff;
            border-top: 1px solid #e5e5e5;
            padding-bottom: env(safe-area-inset-bottom); }
  .tabbar button { flex: 1; background: none; border: none; color: #999;
                   font-size: 11px; padding: 6px 0 5px; line-height: 1.2; }
  .tabbar button .t-ico { display: block; font-size: 22px; margin-bottom: 1px; }
  .tabbar button.active { color: #07C160; font-weight: bold; }
  /* 主区底部留白 = tab 栏内容高度 + 安全区，避免最后一屏被挡住 */
  main { padding: 12px 12px calc(env(safe-area-inset-bottom) + 66px); }
  /* 下拉刷新提示条 */
  #ptr { position: fixed; top: 0; left: 0; right: 0; z-index: 30;
         text-align: center; color: #07C160; font-size: 13px;
         padding-top: calc(env(safe-area-inset-top) + 6px);
         transform: translateY(-100%); transition: transform .18s ease;
         pointer-events: none; }
  #ptr.show { transform: translateY(0); }
  #ptr .ptr-in { display: inline-block; background: #fff; border-radius: 14px;
                 padding: 5px 14px; box-shadow: 0 2px 8px rgba(0,0,0,.18); }
  .card { background: #fff; border-radius: 10px; padding: 12px 14px; margin-bottom: 10px;
          box-shadow: 0 1px 2px rgba(0,0,0,.08); }
  .conv-head { display: flex; align-items: center; gap: 8px; }
  .conv-name { font-weight: bold; font-size: 17px; flex: 1; overflow: hidden;
               text-overflow: ellipsis; white-space: nowrap; }
  .conv-count { color: #999; font-size: 13px; }
  .conv-preview { color: #888; font-size: 14px; margin-top: 4px; overflow: hidden;
                  text-overflow: ellipsis; white-space: nowrap; }
  .todo-flag { color: #ff5722; font-size: 15px; }
  .msg-list { margin-top: 8px; border-top: 1px solid #f0f0f0; padding-top: 8px; display: none; }
  .msg { padding: 6px 0; border-bottom: 1px solid #fafafa; }
  .msg .m-meta { font-size: 12px; color: #999; }
  .msg .m-text { font-size: 15px; white-space: pre-wrap; word-break: break-all; }
  .badge { display: inline-block; border-radius: 4px; padding: 0 5px; font-size: 11px;
           color: #fff; margin-right: 4px; vertical-align: 1px; }
  .b-db { background: #1989fa; } .b-nt { background: #ff976a; } .b-grp { background: #07c160; }
  .chips { display: flex; flex-wrap: wrap; gap: 6px; margin: 8px 0; }
  .chip { background: #e8f7ef; color: #07C160; border-radius: 12px;
          padding: 3px 10px; font-size: 12px; }
  .chip.warn { background: #fff3e8; color: #ff5722; }
  .s2 { border-top: 1px dashed #e0e0e0; margin-top: 8px; padding-top: 8px; font-size: 14px; }
  .s2 .row { margin: 4px 0; }
  .s2 .label { color: #999; font-size: 12px; }
  .s2 ul { margin: 4px 0 4px 18px; padding: 0; }
  .imp-high { color: #e6432d; font-weight: bold; }
  .stat-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }
  .stat-num { font-size: 26px; font-weight: bold; color: #07C160; }
  .stat-label { font-size: 13px; color: #999; }
  .empty { text-align: center; color: #999; padding: 48px 0; font-size: 15px; }
  .auth-box { max-width: 320px; margin: 80px auto; text-align: center; }
  .auth-box input { width: 100%; font-size: 16px; padding: 10px; border: 1px solid #ddd;
                    border-radius: 8px; margin-bottom: 10px; }
  .auth-box button { width: 100%; font-size: 16px; padding: 10px; border: none;
                     border-radius: 8px; background: #07C160; color: #fff; }
  .refresh-note { text-align: center; color: #bbb; font-size: 12px; padding: 6px; }
  /* 会话 tab：搜索框 / 分区标题 / 分层折叠 */
  .search-wrap { margin-bottom: 10px; }
  .search-wrap input { width: 100%; font-size: 15px; padding: 9px 12px;
                       border: 1px solid #ddd; border-radius: 8px; background: #fff; }
  .sec-head { font-size: 13px; color: #999; margin: 12px 2px 8px; font-weight: bold; }
  .sec-head.warn { color: #ff5722; }
  .caret { color: #bbb; font-size: 14px; width: 16px; flex: none; }
  .date-head { display: flex; align-items: center; gap: 6px; font-size: 13px;
               color: #576b95; font-weight: bold; padding: 6px 0;
               border-bottom: 1px solid #f0f0f0; }
  .date-body { padding-left: 4px; }
</style>
</head>
<body>
<header>
  <h1>💬 微信分析助手</h1>
  <div class="sync-line" id="sync-line">正在同步…</div>
</header>
<div id="ptr"><span class="ptr-in" id="ptr-text">下拉刷新</span></div>
<main id="main"><div class="empty">加载中…</div></main>
<nav class="tabbar">
  <button id="tab-conv" class="active" onclick="switchTab('conv')">
    <span class="t-ico">💬</span>会话</button>
  <button id="tab-todo" onclick="switchTab('todo')">
    <span class="t-ico">⚡</span>待办</button>
  <button id="tab-stat" onclick="switchTab('stat')">
    <span class="t-ico">📊</span>统计</button>
  <button id="tab-adv" onclick="switchTab('adv')">
    <span class="t-ico">🧭</span>顾问</button>
</nav>

<script>
// ---------- token 管理：query -> localStorage -> X-Token 头 ----------
(function initToken() {
  var q = new URLSearchParams(location.search).get('token');
  if (q) {
    localStorage.setItem('wx_token', q);
    // 抹掉地址栏里的 token，防截图/分享泄露；
    // 保持当前页面路径（站点可能挂在 /mmonitor/ 前缀下），不写死绝对路径
    history.replaceState(null, '', location.pathname);
  }
})();
var TOKEN = localStorage.getItem('wx_token') || '';

function showAuth() {
  document.getElementById('main').innerHTML =
    '<div class="auth-box"><p>请输入访问令牌</p>' +
    '<input id="tk" type="password" placeholder="X-Token">' +
    '<button onclick="saveToken()">保存并进入</button></div>';
}
function saveToken() {
  localStorage.setItem('wx_token', document.getElementById('tk').value);
  location.reload();
}

// ---------- API ----------
// 用 __WX_DIR 拼域根绝对路径：页面挂在 /console 还是 /mmonitor/console
// （甚至带末尾斜杠）都能正确指到同级接口，不写死挂载前缀
async function api(path) {
  var r = await fetch(window.__WX_DIR + path, { headers: { 'X-Token': TOKEN } });
  if (r.status === 401) { showAuth(); throw new Error('unauthorized'); }
  return r.json();
}

// ---------- 工具 ----------
function esc(s) {
  return String(s == null ? '' : s).replace(/&/g, '&amp;').replace(/</g, '&lt;')
    .replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}
function fmtTime(ms) {
  var d = new Date(ms);
  function p(n) { return (n < 10 ? '0' : '') + n; }
  return (d.getMonth() + 1) + '-' + p(d.getDate()) + ' ' + p(d.getHours()) + ':' + p(d.getMinutes());
}
// 日期分组标签（二级折叠用）：如「10月3日」
function fmtDate(ms) {
  var d = new Date(ms);
  return (d.getMonth() + 1) + '月' + d.getDate() + '日';
}

// ---------- 状态 ----------
var CUR_TAB = 'conv';
var MSGS = [], ANALYSES = [], ADVISORS = [];
// 会话名 -> 最新一条 need_action 分析（待办角标 / 会话内分析区用）
var todoByConv = {};
var LAST_SYNC = 0;   // 最近一次数据同步成功时间（毫秒）
var LAST_ERR = '';   // 最近一次同步错误（空 = 正常）
// 会话 tab：搜索关键词 + 折叠状态（key 形如 "c:会话名" / "c:会话名|10月3日"，
// true = 展开，默认折叠；持久化到 localStorage）
var CONV_QUERY = '';
var COLLAPSE = {};
try { COLLAPSE = JSON.parse(localStorage.getItem('wx_collapse_v1') || '{}'); }
catch (e) { COLLAPSE = {}; }
var CONV_NODES = [];  // 本次渲染的节点 index -> 折叠 key（供 onclick 反查）

function switchTab(t) {
  CUR_TAB = t;
  ['conv', 'todo', 'stat', 'adv'].forEach(function (k) {
    document.getElementById('tab-' + k).className = k === t ? 'active' : '';
  });
  render();
}

// 顶部同步状态行：时间 + 数据量 / 错误
function renderSyncLine() {
  var el = document.getElementById('sync-line');
  if (LAST_ERR) {
    el.className = 'sync-line err';
    el.textContent = '⚠️ 同步失败：' + LAST_ERR + '（下拉重试）';
    return;
  }
  el.className = 'sync-line';
  if (!LAST_SYNC) { el.textContent = '正在同步…'; return; }
  function p(n) { return (n < 10 ? '0' : '') + n; }
  var d = new Date(LAST_SYNC);
  el.textContent = '已同步 ' + p(d.getHours()) + ':' + p(d.getMinutes()) + ':' +
    p(d.getSeconds()) + ' · 消息 ' + MSGS.length + ' · 分析 ' + ANALYSES.length;
}

// ---------- 数据加载 ----------
async function load() {
  try {
    var m = await api('messages?limit=1000');
    var a = await api('analysis?limit=1000');
    var adv = await api('advisor?limit=100');
    MSGS = m.messages || [];
    ANALYSES = a.analyses || [];
    ADVISORS = adv.reports || [];
    todoByConv = {};
    ANALYSES.forEach(function (x) {
      if (x.s1 && x.s1.need_action && x.s1.need_action.value) {
        // 同一会话保留 received_at 最新的一条
        if (!todoByConv[x.conversation] ||
            (x.received_at || 0) > (todoByConv[x.conversation].received_at || 0)) {
          todoByConv[x.conversation] = x;
        }
      }
    });
    LAST_SYNC = Date.now();
    LAST_ERR = '';
    render();
  } catch (e) {
    // 401 已由 api() 弹鉴权框；其它错误显示在同步行，下轮自动重试
    if (String(e.message || e) !== 'unauthorized') {
      LAST_ERR = '网络异常';
      renderSyncLine();
    }
  }
}

// ---------- 下拉刷新（iPhone Safari / PWA 通用触摸实现） ----------
(function initPullToRefresh() {
  var startY = 0, pulling = false, triggered = false;
  var ptr = document.getElementById('ptr');
  var ptrText = document.getElementById('ptr-text');
  var THRESHOLD = 70;  // 下拉多少像素触发刷新
  window.addEventListener('touchstart', function (e) {
    if (window.scrollY <= 0) { startY = e.touches[0].clientY; pulling = true; }
    else pulling = false;
    triggered = false;
  }, { passive: true });
  window.addEventListener('touchmove', function (e) {
    if (!pulling) return;
    var dy = e.touches[0].clientY - startY;
    if (dy > 20 && window.scrollY <= 0) {
      ptr.classList.add('show');
      ptrText.textContent = dy > THRESHOLD ? '松开刷新' : '下拉刷新';
      if (dy > THRESHOLD) triggered = true;
    }
  }, { passive: true });
  window.addEventListener('touchend', function () {
    if (!pulling) return;
    pulling = false;
    if (triggered) {
      ptrText.textContent = '刷新中…';
      load().finally(function () {
        ptrText.textContent = '已更新';
        setTimeout(function () { ptr.classList.remove('show'); }, 600);
      });
    } else {
      ptr.classList.remove('show');
    }
  }, { passive: true });
})();

// ---------- 渲染 ----------
function render() {
  renderSyncLine();
  var el = document.getElementById('main');
  if (CUR_TAB === 'conv') el.innerHTML = renderConvs();
  else if (CUR_TAB === 'todo') el.innerHTML = renderTodos();
  else if (CUR_TAB === 'adv') el.innerHTML = renderAdvisor();
  else el.innerHTML = renderStats();
}

// ---------- 会话 tab：搜索 + 需关注分区 + 会话/日期分层折叠 ----------
function renderConvs() {
  CONV_NODES = [];
  // 搜索框放在列表外层：输入时只重绘 #conv-list，输入框焦点不丢
  var searchHtml = '<div class="search-wrap">' +
    '<input id="conv-search" type="search" placeholder="搜索会话 / 发送者 / 消息内容" ' +
    'value="' + esc(CONV_QUERY) + '" oninput="onConvSearch(this.value)"></div>';
  if (!MSGS.length) return searchHtml + '<div class="empty">暂无消息</div>';
  return searchHtml + '<div id="conv-list">' + renderConvList() + '</div>' +
    '<div class="refresh-note">下拉可刷新 · 每 30 秒自动同步</div>';
}

function onConvSearch(v) {
  CONV_QUERY = v;
  var el = document.getElementById('conv-list');
  if (el) el.innerHTML = renderConvList();
}

// 过滤 + 按会话分组 + 分区渲染（「⚡ 需关注」= 有 need_action 分析的会话）
function renderConvList() {
  var q = CONV_QUERY.trim().toLowerCase();
  var groups = {}, order = [];
  MSGS.forEach(function (m) {
    if (!groups[m.conversation]) { groups[m.conversation] = []; order.push(m.conversation); }
    groups[m.conversation].push(m);
  });
  var watchHtml = '', restHtml = '';
  order.forEach(function (name) {
    var list = groups[name];
    if (q) {
      // 会话名命中 -> 整组保留；否则只保留发送者/内容命中的消息
      if (name.toLowerCase().indexOf(q) < 0) {
        list = list.filter(function (m) {
          return (m.sender || '').toLowerCase().indexOf(q) >= 0 ||
                 (m.text || '').toLowerCase().indexOf(q) >= 0;
        });
        if (!list.length) return;
      }
    }
    var card = renderConvCard(name, list);
    if (todoByConv[name]) watchHtml += card; else restHtml += card;
  });
  var html = '';
  if (watchHtml) html += '<div class="sec-head warn">⚡ 需关注</div>' + watchHtml;
  if (restHtml) html += '<div class="sec-head">全部会话</div>' + restHtml;
  if (!html) html = '<div class="empty">无匹配结果</div>';
  return html;
}

// 一级折叠组：会话（默认折叠，显示名称+条数+最新摘要+时间）；
// 展开后有 need_action 分析块 + 按日期的二级折叠组
function renderConvCard(name, list) {
  var key = 'c:' + name;
  var idx = CONV_NODES.length; CONV_NODES.push(key);
  var open = !!COLLAPSE[key];
  var latest = list[0];
  var todo = todoByConv[name];
  var html = '<div class="card">' +
    '<div class="conv-head" onclick="toggleNode(' + idx + ')">' +
    '<span class="caret" id="caret-' + idx + '">' + (open ? '▾' : '▸') + '</span>' +
    '<span class="conv-name">' + esc(name) + '</span>' +
    (todo ? '<span class="todo-flag">⚡</span>' : '') +
    '<span class="conv-count">' + list.length + ' 条</span></div>' +
    '<div class="conv-preview" onclick="toggleNode(' + idx + ')">' +
    esc(latest.sender) + '：' + esc(latest.text) + ' · ' + fmtTime(latest.timestamp) + '</div>' +
    '<div class="msg-list" id="node-' + idx + '" style="display:' +
    (open ? 'block' : 'none') + '">';
  if (todo) html += renderAnalysisBlock(todo);
  // 二级折叠组：按日期分组（list 已按时间倒序，日期组同样倒序）
  var byDate = {}, dOrder = [];
  list.forEach(function (m) {
    var d = fmtDate(m.timestamp);
    if (!byDate[d]) { byDate[d] = []; dOrder.push(d); }
    byDate[d].push(m);
  });
  dOrder.forEach(function (d) {
    var dIdx = CONV_NODES.length; CONV_NODES.push(key + '|' + d);
    var dOpen = !!COLLAPSE[key + '|' + d];
    html += '<div class="date-head" onclick="toggleNode(' + dIdx + ')">' +
      '<span class="caret" id="caret-' + dIdx + '">' + (dOpen ? '▾' : '▸') + '</span>' +
      esc(d) + '<span class="conv-count" style="margin-left:auto;">' +
      byDate[d].length + ' 条</span></div>' +
      '<div class="date-body" id="node-' + dIdx + '" style="display:' +
      (dOpen ? 'block' : 'none') + '">';
    byDate[d].forEach(function (m) {
      var badges = (m.is_group ? '<span class="badge b-grp">群</span>' : '') +
        (m.source === 'android-db' ? '<span class="badge b-db">库</span>' : '');
      html += '<div class="msg"><div class="m-meta">' + badges + esc(m.sender) +
        ' · ' + fmtTime(m.timestamp) + '</div>' +
        '<div class="m-text">' + esc(m.text) + '</div></div>';
    });
    html += '</div>';
  });
  return html + '</div></div>';
}

// 折叠/展开切换：更新状态、持久化、只动对应节点（不重绘整页）
function toggleNode(i) {
  var key = CONV_NODES[i];
  if (key == null) return;
  COLLAPSE[key] = !COLLAPSE[key];
  localStorage.setItem('wx_collapse_v1', JSON.stringify(COLLAPSE));
  var el = document.getElementById('node-' + i);
  if (el) el.style.display = COLLAPSE[key] ? 'block' : 'none';
  var caret = document.getElementById('caret-' + i);
  if (caret) caret.textContent = COLLAPSE[key] ? '▾' : '▸';
}

// 分析结果块：S1 chips + S2 区（对齐 App 会话详情的结构）
function renderAnalysisBlock(a) {
  var html = '<div style="margin:8px 0;">';
  if (a.s1) {
    var s1 = a.s1, chips = '';
    if (s1.need_action) chips += '<span class="chip warn">⚡待办 ' +
      Math.round((s1.need_action.prob || 0) * 100) + '%</span>';
    if (s1.importance) {
      var imp = esc(s1.importance);
      chips += '<span class="chip' + (imp === 'high' ? ' warn' : '') + '">重要度 ' + imp + '</span>';
    }
    if (s1.due_window) chips += '<span class="chip">截止 ' + esc(s1.due_window) + '</span>';
    if (s1.topic) chips += '<span class="chip">' + esc(s1.topic) + '</span>';
    html += '<div class="chips">' + chips + '</div>';
  }
  if (a.s2) {
    var s2 = a.s2;
    html += '<div class="s2">';
    if (s2.summary) html += '<div class="row"><span class="label">摘要</span><br>' + esc(s2.summary) + '</div>';
    if (s2.due_time) html += '<div class="row"><span class="label">截止时间</span> ' + esc(s2.due_time) + '</div>';
    if (s2.suggested_action) html += '<div class="row"><span class="label">建议动作</span><br>' + esc(s2.suggested_action) + '</div>';
    if (s2.tasks && s2.tasks.length) {
      html += '<div class="row"><span class="label">任务</span><ul>';
      s2.tasks.forEach(function (t) {
        html += '<li>' + esc(typeof t === 'string' ? t : (t.task || JSON.stringify(t))) + '</li>';
      });
      html += '</ul></div>';
    }
    html += '</div>';
  }
  return html + '</div>';
}

function renderTodos() {
  var todos = ANALYSES.filter(function (a) {
    return a.s1 && a.s1.need_action && a.s1.need_action.value;
  });
  // 重要度 high 优先，同级按上报时间倒序（最新在前）
  var impRank = { high: 0, mid: 1, low: 2 };
  todos.sort(function (a, b) {
    var ra = a.s1 && impRank[a.s1.importance] != null ? impRank[a.s1.importance] : 3;
    var rb = b.s1 && impRank[b.s1.importance] != null ? impRank[b.s1.importance] : 3;
    if (ra !== rb) return ra - rb;
    return (b.received_at || 0) - (a.received_at || 0);
  });
  if (!todos.length) return '<div class="empty">🎉 暂无待办</div>';
  var html = '';
  todos.forEach(function (a) {
    var impCls = (a.s1 && a.s1.importance === 'high') ? 'imp-high' : '';
    html += '<div class="card">' +
      '<div class="conv-head"><span class="conv-name">' + esc(a.conversation) + '</span>' +
      '<span class="' + impCls + '">' + esc((a.s1 && a.s1.importance) || '') + '</span></div>' +
      renderAnalysisBlock(a) +
      '<div class="conv-preview">窗口消息 ' + (a.message_count || 0) +
      ' 条 · 分析于 ' + esc(a.analyzed_at || '') + '</div></div>';
  });
  return html + '<div class="refresh-note">下拉可刷新 · 每 30 秒自动同步</div>';
}

function renderStats() {
  var total = MSGS.length;
  var bySrc = {};
  var lastReport = 0;
  MSGS.forEach(function (m) {
    bySrc[m.source || 'unknown'] = (bySrc[m.source || 'unknown'] || 0) + 1;
    if ((m.received_at || 0) > lastReport) lastReport = m.received_at;
  });
  var analyzedConvs = {};
  ANALYSES.forEach(function (a) { analyzedConvs[a.conversation] = 1; });
  function cell(num, label) {
    return '<div class="card"><div class="stat-num">' + num + '</div>' +
      '<div class="stat-label">' + label + '</div></div>';
  }
  var srcTxt = Object.keys(bySrc).map(function (k) {
    return (k === 'android-db' ? '数据库' : k === 'android-notification' ? '通知' : k) +
      ' ' + bySrc[k];
  }).join(' / ');
  return '<div class="stat-grid">' +
    cell(total, '消息总数（最近 1000 条内）') +
    cell(Object.keys(analyzedConvs).length, '已分析会话数') +
    cell(ANALYSES.length, '分析结果数') +
    cell(lastReport ? fmtTime(lastReport) : '—', '最近上报时间') +
    '</div><div class="card"><div class="stat-label">来源分布</div>' +
    '<div style="margin-top:6px;">' + esc(srcTxt || '—') + '</div></div>' +
    '<div class="refresh-note">下拉可刷新 · 每 30 秒自动同步</div>';
}

// ---------- 顾问层（Fable）复盘报告 ----------
// 建议类型图标：prompt=提示词调优 watchlist=重点关注 threshold=阈值调整
var ADV_TYPE_META = {
  prompt:    { ico: '✏️', label: '提示词调优' },
  watchlist: { ico: '👀', label: '重点关注' },
  threshold: { ico: '🎚️', label: '阈值调整' },
  other:     { ico: '💡', label: '其它建议' }
};

function renderAdvisor() {
  if (!ADVISORS.length) return '<div class="empty">暂无顾问报告</div>';
  var html = '';
  ADVISORS.forEach(function (r) {
    // 建议按 type 分组展示
    var groups = {}, order = [];
    (r.suggestions || []).forEach(function (s) {
      var t = ADV_TYPE_META[s.type] ? s.type : 'other';
      if (!groups[t]) { groups[t] = []; order.push(t); }
      groups[t].push(s);
    });
    html += '<div class="card">' +
      '<div class="conv-head"><span class="conv-name">🧭 复盘报告</span>' +
      '<span class="conv-count">' + esc(r.created_at || '') + '</span></div>' +
      '<div class="chips">' +
      '<span class="chip">模型 ' + esc(r.model_used || '—') + '</span>' +
      '<span class="chip">覆盖 ' + (r.case_count || 0) + ' 个 case</span>' +
      (r.period_days ? '<span class="chip">近 ' + r.period_days + ' 天</span>' : '') +
      '</div>';
    if (r.summary) {
      html += '<div class="s2"><div class="row"><span class="label">复盘摘要</span><br>' +
        esc(r.summary) + '</div></div>';
    }
    order.forEach(function (t) {
      var meta = ADV_TYPE_META[t];
      html += '<div class="s2"><div class="row"><span class="label">' +
        meta.ico + ' ' + meta.label + '</span><ul>';
      groups[t].forEach(function (s) {
        html += '<li><b>' + esc(s.title || '') + '</b>' +
          (s.detail ? ' — ' + esc(s.detail) : '') + '</li>';
      });
      html += '</ul></div></div>';
    });
    html += '</div>';
  });
  return html + '<div class="refresh-note">下拉可刷新 · 每 30 秒自动同步</div>';
}

// ---------- 启动 ----------
if (!TOKEN) showAuth();
load();
setInterval(load, 30000);  // 30 秒自动刷新

// 注册 service worker（PWA 可安装 / 离线骨架）
// 同样基于 __WX_DIR：/console -> /sw.js（scope /）；
// /mmonitor/console -> /mmonitor/sw.js（scope /mmonitor/），均可控制本页
if ('serviceWorker' in navigator) {
  navigator.serviceWorker.register(window.__WX_DIR + 'sw.js').catch(function () {});
}
</script>
</body>
</html>"""

# manifest 图标：PNG 文件放 pwa_icons/（由 gen_icons.py 生成），经
# /console/icons/<name> 路由提供。manifest 里的 src 用相对路径，相对 manifest
# 自身 URL 解析（/console/manifest.json -> /console/icons/...；
# /mmonitor/console/manifest.json -> /mmonitor/console/icons/...），
# 两种挂载方式都正确。SVG data URI 保留作兜底（Android Chrome 可用，
# iOS apple-touch-icon 只认 PNG，见 CONSOLE_HTML 注入的 link）。
_ICON_SVG = ("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' "
             "width='512' height='512'%3E%3Crect width='512' height='512' "
             "rx='100' fill='%2307C160'/%3E%3Ctext x='256' y='330' "
             "font-size='260' text-anchor='middle'%3E%F0%9F%92%AC%3C/text%3E%3C/svg%3E")

CONSOLE_MANIFEST = {
    "name": "微信分析助手",
    "short_name": "微信分析助手",
    "description": "微信消息监测与分析结果查看端",
    # 相对路径：相对 manifest 自身 URL 解析（/console/manifest.json ->
    # /console/；/mmonitor/console/manifest.json -> /mmonitor/console/），
    # 两种挂载方式都正确
    "start_url": "./",
    "scope": "./",
    "display": "standalone",
    "background_color": "#f5f5f5",
    "theme_color": "#07C160",
    "icons": [
        {"src": "icons/icon-192.png", "sizes": "192x192", "type": "image/png",
         "purpose": "any"},
        {"src": "icons/icon-512.png", "sizes": "512x512", "type": "image/png",
         "purpose": "any"},
        {"src": "icons/icon-512-maskable.png", "sizes": "512x512",
         "type": "image/png", "purpose": "maskable"},
        {"src": _ICON_SVG, "sizes": "any", "type": "image/svg+xml",
         "purpose": "any"},
    ],
}

PWA_ICON_DIR = os.path.join(BASE_DIR, "pwa_icons")

# Service Worker：页面骨架 cache-first（离线/弱网可开），API 请求走网络。
# 骨架缓存 key 用去掉 query 的实际请求路径（兼容 /console 与 /mmonitor/console
# 两种挂载方式），匹配时 ignoreSearch 避开 ?token= 差异。
CONSOLE_SW = """var CACHE = 'wx-console-v4';
self.addEventListener('install', function (e) { self.skipWaiting(); });
self.addEventListener('activate', function (e) {
  e.waitUntil(caches.keys().then(function (keys) {
    return Promise.all(keys.map(function (k) {
      if (k !== CACHE) return caches.delete(k);
    }));
  }).then(function () { return self.clients.claim(); }));
});
self.addEventListener('fetch', function (e) {
  var url = new URL(e.request.url);
  // API 请求一律走网络（数据要新鲜；用 includes 兼容前缀挂载）
  if (url.pathname.indexOf('/messages') >= 0 ||
      url.pathname.indexOf('/analysis') >= 0 ||
      url.pathname.indexOf('/advisor') >= 0 ||
      url.pathname.indexOf('/weixin') >= 0 ||
      url.pathname.indexOf('/admin') >= 0) {
    return;  // 不拦截，默认网络
  }
  // 页面导航与静态骨架：cache-first，网络成功后回填
  if (e.request.mode === 'navigate' || url.pathname.indexOf('/console') >= 0) {
    var shellKey = url.pathname;  // 去 query 的实际路径作缓存 key
    e.respondWith(
      caches.open(CACHE).then(function (cache) {
        return cache.match(shellKey, { ignoreSearch: true }).then(function (hit) {
          var net = fetch(e.request).then(function (resp) {
            if (resp.ok) cache.put(shellKey, resp.clone());
            return resp;
          }).catch(function () { return hit; });
          return hit || net;
        });
      })
    );
    return;
  }
  // manifest / sw.js 等：网络优先，失败回缓存
  e.respondWith(
    fetch(e.request).catch(function () { return caches.match(e.request); })
  );
});
"""


# strict_slashes=False：同时匹配 /console 与 /console/，
# 因为 manifest 的 start_url "./" 会解析成带末尾斜杠的地址
@app.route("/console", methods=["GET"], strict_slashes=False)
def console_page():
    """移动端控制台单页（鉴权支持 ?token= query，见 check_token）。"""
    auth_err = check_token()
    if auth_err:
        return auth_err
    # no-cache：让 SW 每次都能拿到最新骨架（SW 离线时回缓存）
    return Response(CONSOLE_HTML, mimetype="text/html",
                    headers={"Cache-Control": "no-cache"})


@app.route("/console/manifest.json", methods=["GET"])
def console_manifest():
    """PWA manifest。不含任何数据，不鉴权（SW/浏览器拉取时无法带头）。"""
    return jsonify(CONSOLE_MANIFEST)


@app.route("/console/icons/<path:filename>", methods=["GET"])
def console_icon(filename):
    """PWA 图标 PNG。不含数据，不鉴权（iOS 拉 apple-touch-icon 无法带头）。"""
    return send_from_directory(PWA_ICON_DIR, filename,
                               mimetype="image/png",
                               max_age=86400)


@app.route("/sw.js", methods=["GET"])
def console_sw():
    """Service Worker。放根路径是为了 scope='/' 能控制 /console。
    同样不含数据，不鉴权。"""
    return Response(CONSOLE_SW, mimetype="application/javascript",
                    headers={"Cache-Control": "no-cache"})


# ------------------------------------------------------------------
# 接口：GET / 极简消息列表页
# ------------------------------------------------------------------

@app.route("/", methods=["GET"])
def index():
    """内嵌 HTML 页面：按时间倒序展示最近 100 条消息。"""
    auth_err = check_token()
    if auth_err:
        return auth_err

    conn = sqlite3.connect(DB_PATH)
    try:
        rows = conn.execute(
            """
            SELECT sender, text, timestamp, conversation, is_group, source
            FROM messages ORDER BY timestamp DESC LIMIT 100
            """
        ).fetchall()
        # 提取统计：安卓库来源消息总数
        db_count = conn.execute(
            "SELECT COUNT(*) FROM messages WHERE source='android-db'").fetchone()[0]
    finally:
        conn.close()

    # 最近提取时间（来自 android_db_extract 的状态文件）
    state_path = os.path.join(DATA_DIR, "android_db", "state.json")
    last_extract = "从未提取"
    if os.path.exists(state_path):
        try:
            last_extract = json.load(open(state_path, encoding="utf-8")).get(
                "last_run", "未知")
        except (json.JSONDecodeError, OSError):
            pass
    stats_html = (
        f'<div class="stats">安卓库消息 <b>{db_count}</b> 条 ｜ '
        f'最近提取：{last_extract}</div>')

    # 来源徽标映射：通知上报 / 数据库直读
    SOURCE_BADGE = {
        "android-db": '<span class="badge badge-db">数据库</span>',
        "android-notification": '<span class="badge badge-notify">通知</span>',
    }

    # 拼接消息行
    items_html = ""
    for sender, text, ts, conversation, is_group, source in rows:
        time_str = datetime.fromtimestamp(ts / 1000).strftime("%Y-%m-%d %H:%M:%S")
        group_badge = '<span class="badge">群聊</span>' if is_group else ""
        src_badge = SOURCE_BADGE.get(source, "")
        # 简单转义防 XSS
        esc = lambda s: (s.replace("&", "&amp;").replace("<", "&lt;")
                          .replace(">", "&gt;").replace('"', "&quot;"))
        items_html += (
            f'<div class="msg">'
            f'<div class="meta">{group_badge}{src_badge}'
            f'<span class="conv">{esc(conversation)}</span>'
            f'<span class="sender">{esc(sender)}</span>'
            f'<span class="time">{time_str}</span></div>'
            f'<div class="text">{esc(text)}</div>'
            f'</div>'
        )
    if not items_html:
        items_html = '<div class="empty">暂无消息</div>'

    html = f"""<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>微信消息接收服务</title>
<style>
  body {{ font-family: "Microsoft YaHei", sans-serif; max-width: 720px;
         margin: 0 auto; padding: 16px; background: #f5f5f5; color: #333; }}
  h1 {{ font-size: 20px; }}
  .msg {{ background: #fff; border-radius: 8px; padding: 10px 14px;
         margin-bottom: 8px; box-shadow: 0 1px 2px rgba(0,0,0,.08); }}
  .meta {{ font-size: 13px; color: #888; margin-bottom: 4px; }}
  .badge {{ background: #07c160; color: #fff; border-radius: 4px;
           padding: 1px 6px; font-size: 12px; margin-right: 6px; }}
  .badge-db {{ background: #1989fa; }}
  .badge-notify {{ background: #ff976a; }}
  .stats {{ background: #fff; border-radius: 8px; padding: 10px 14px;
           margin-bottom: 12px; font-size: 13px; color: #666;
           box-shadow: 0 1px 2px rgba(0,0,0,.08); }}
  .conv {{ font-weight: bold; color: #576b95; margin-right: 8px; }}
  .sender {{ margin-right: 8px; }}
  .time {{ float: right; }}
  .text {{ font-size: 15px; white-space: pre-wrap; word-break: break-all; }}
  .empty {{ text-align: center; color: #999; padding: 40px 0; }}
</style>
</head>
<body>
<h1>微信消息（最近 100 条）</h1>
{stats_html}
{items_html}
</body>
</html>"""
    return Response(html, mimetype="text/html")


# ------------------------------------------------------------------
# 启动入口
# ------------------------------------------------------------------

if __name__ == "__main__":
    init_db()
    if TOKEN:
        print(f"[信息] 已启用 X-Token 鉴权")
    else:
        print(f"[警告] 未设置 WEIXIN_TOKEN，接口无鉴权！公网部署务必设置。")
    print(f"[信息] 数据目录: {DATA_DIR}")
    print(f"[信息] 服务监听: http://0.0.0.0:{PORT}")
    # 局域网自用场景，直接 Flask 内置服务器即可；生产环境建议 waitress/gunicorn
    app.run(host="0.0.0.0", port=PORT, threaded=True)
