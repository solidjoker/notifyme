# -*- coding: utf-8 -*-
# Copyright (c) 2026 solidjoker
# SPDX-License-Identifier: MIT

"""
消息接收服务端（M2 起支持多应用：微信 / 飞书 / 钉钉）
======================================================
配合 notifyme Android 端使用：
安卓端监听通知栏，定时把消息以 JSON 数组的形式 POST 到本服务的 /weixin 接口
（接口名沿用历史路径，已不限于微信）。

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
        pkg          str    可选，来源 App 包名；缺省按微信处理

启动：
    python app.py            # 默认监听 127.0.0.1:8000
    set PORT=9000 && python app.py    # 自定义端口（Windows cmd）
    未设 WEIXIN_TOKEN 时：只听本机 + 所有数据接口返回 503（拒绝无鉴权运行）

安全提示：
    公网部署时务必设置环境变量 WEIXIN_TOKEN 开启鉴权，
    并放在 caddy / nginx 等反向代理后面启用 HTTPS，不要裸奔！
    确实要在无令牌下本机自测时，显式设置 WEIXIN_ALLOW_NO_AUTH=1。
"""

import hmac
import importlib.util
import json
import logging
import os
import re
import sqlite3
import sys
import threading
import time
from datetime import datetime, timedelta, timezone

from flask import Flask, request, jsonify, Response, send_from_directory

logger = logging.getLogger("notifyme.server")
if not logger.handlers:
    logging.basicConfig(
        level=logging.INFO,
        format="[%(asctime)s] %(levelname)s %(name)s %(message)s")

app = Flask(__name__)

# 单次请求体上限：上报接口只收 JSON 数组，8 MiB 足够几十万条消息，
# 但没有上限时一个超大 body 就能把进程内存打满。
app.config["MAX_CONTENT_LENGTH"] = 8 * 1024 * 1024

# WSGI 部署（waitress / gunicorn / uwsgi）不会走 __main__，
# 原来 init_db() 只写在 __main__ 里 → WSGI 启动后数据库根本没建，
# 第一个请求直接 500 "unable to open database file"。
# 改成：第一次请求前惰性初始化（幂等），任何启动方式都覆盖到。
_db_ready = False
_db_lock = threading.Lock()


@app.before_request
def _ensure_db_ready():
    """首次请求前建表建索引；已初始化则直接放行（开销可忽略）。"""
    global _db_ready
    if _db_ready:
        return None
    with _db_lock:
        if not _db_ready:
            init_db()
            _db_ready = True
    return None


@app.after_request
def _security_headers(resp):
    # token 允许走 query（浏览器场景），Referer 带出去等于把 token 泄给第三方
    resp.headers.setdefault("Referrer-Policy", "no-referrer")
    resp.headers.setdefault("X-Content-Type-Options", "nosniff")
    # 控制台 / 落地页被 iframe 套一层就能做点击劫持（点一下就是替攻击者
    # 的页面发消息）。本服务没有任何需要被嵌套的页面，一律 DENY。
    # 注意：不要加 CSP —— 控制台是内联 JS，上了 CSP 页面直接白屏。
    resp.headers.setdefault("X-Frame-Options", "DENY")
    return resp


# ------------------------------------------------------------------
# 统一错误响应
# ------------------------------------------------------------------
# Flask 默认的 404 / 413 / 500 返回的是 HTML 文本，Android 端和控制台 JS
# 都按 JSON 解析 -> 直接抛解析异常，看起来像服务端挂了。
# 这里统一成 {"ok": false, "error": "..."}，并且不回传异常栈
# （栈里可能带本地路径、查询串、令牌片段）。

@app.errorhandler(404)
def _handle_not_found(err):
    """未知路径：JSON 404（不返回 Flask 默认的 HTML 页面）。"""
    return jsonify({"ok": False, "error": "not found"}), 404


@app.errorhandler(413)
def _handle_too_large(err):
    """请求体超过 MAX_CONTENT_LENGTH：JSON 413。"""
    return jsonify({"ok": False, "error": "request body too large"}), 413


@app.errorhandler(500)
def _handle_internal_error(err):
    """未捕获异常：JSON 500 + 服务端日志，绝不把异常栈回给客户端。"""
    logger.error("internal server error: %s", err,
                 exc_info=getattr(err, "original_exception", None))
    return jsonify({"ok": False, "error": "internal server error"}), 500

# ------------------------------------------------------------------
# 配置
# ------------------------------------------------------------------

# 鉴权令牌：环境变量 WEIXIN_TOKEN 非空时，所有请求必须带匹配的 X-Token 头。
# 未设置则不鉴权（仅限局域网自用；公网部署务必设置！）
TOKEN = os.environ.get("WEIXIN_TOKEN", "").strip()

# 未设置 TOKEN 时是否仍允许绑定 0.0.0.0。
# 默认禁止：无鉴权 + 全网监听等于把全部消息（含明文正文）对局域网/公网开放。
# 确实要在家用局域网裸跑时显式设 WEIXIN_ALLOW_INSECURE_BIND=1 承担风险。
ALLOW_INSECURE_BIND = os.environ.get("WEIXIN_ALLOW_INSECURE_BIND", "").strip() == "1"

# 未设置令牌时是否允许「完全无鉴权」运行。
# 默认拒绝：空令牌 = 任何能访问到本服务的人都能读全部消息正文（明文）。
# 只有显式设 WEIXIN_ALLOW_NO_AUTH=1 才放行（仅限本机自用）。
ALLOW_NO_AUTH = os.environ.get("WEIXIN_ALLOW_NO_AUTH", "").strip() == "1"

# 监听端口：从环境变量 PORT 读取，默认 8000
PORT = int(os.environ.get("PORT", "8000"))

# 数据目录与文件路径（相对于本文件所在目录，保证从任何目录启动都能找到）
BASE_DIR = os.path.dirname(os.path.abspath(__file__))
DATA_DIR = os.path.join(BASE_DIR, "data")
JSONL_PATH = os.path.join(DATA_DIR, "messages.jsonl")  # 原文存档（与安卓端格式一致）
DB_PATH = os.path.join(DATA_DIR, "messages.db")        # SQLite 查询库
ADVISOR_PATH = os.path.join(DATA_DIR, "advisor_reports.json")  # 顾问层报告存档（JSON 数组）

# 来源 App：包名 -> 中文名（与安卓端 AppSourceRegistry 口径一致）。
# 老客户端上报不带 pkg，统一按微信回填——不搞一次性迁移，每次写入就地补。
DEFAULT_PKG = "com.tencent.mm"
PKG_LABELS = {
    "com.tencent.mm": "微信",
    "com.ss.android.lark": "飞书",
    "com.alibaba.android.rimet": "钉钉",
}


def pkg_label(pkg):
    """包名转中文名；未知包名回落到包名本身，不瞎猜。"""
    return PKG_LABELS.get(pkg, pkg or DEFAULT_PKG)

# 写入锁：保护 jsonl 文件和 SQLite 的并发写入
_write_lock = threading.Lock()


def _connect():
    """统一的 SQLite 连接：WAL + busy_timeout。

    默认的 rollback journal 下，读写会互相阻塞；Flask 是多线程的
    （threaded=True），控制台查询与 App 上报一并发就会出现
    "database is locked"。WAL 让读不阻塞写，busy_timeout 让偶发争用重试而不是直接报错。
    """
    # timeout 与 busy_timeout 保持一致：sqlite3.connect 的 timeout 只是
    # 首次取锁的默认等待，真正的等待由 busy_timeout 决定，两者不一致会让
    # 等待行为难以推断（评审 P3）。15s 覆盖提取脚本分批提交时的持锁窗口。
    conn = sqlite3.connect(DB_PATH, timeout=15)
    conn.execute("PRAGMA busy_timeout = 15000")
    conn.execute("PRAGMA journal_mode = WAL")
    conn.execute("PRAGMA synchronous = NORMAL")
    return conn

# 消息必备字段及期望类型（用于逐条校验）
REQUIRED_FIELDS = {
    "sender": str,
    "text": str,
    "timestamp": (int, float),  # 毫秒时间戳，允许 int 或 float
    "conversation": str,
    "is_group": bool,
}

# ---- 字段长度与数值边界（防毒丸：一条越界数据能让整页 500 或撑爆内存）----
MAX_TEXT_LEN = 65536           # 正文最长 64 KiB
MAX_NAME_LEN = 512             # 发送者 / 会话名最长
MAX_TS_MS = 4102444800000      # 时间戳上界：2100-01-01（毫秒）
MIN_TS_MS = 0                  # 时间戳下界
MAX_MESSAGE_COUNT = 1000000    # 分析窗口内消息数上界
MAX_ROWID = 9223372036854775807  # SQLite int64 上界（rowid 未知时的游标兜底）
JSONL_MAX_BYTES = 64 * 1024 * 1024   # jsonl 存档超过这个大小就轮转一份


# ------------------------------------------------------------------
# 数据库初始化
# ------------------------------------------------------------------

def _ensure_index(conn, name, ddl):
    """按需建索引：已存在且定义一致就不动它。

    原来每次启动都 DROP + CREATE 唯一索引（含全文 text），
    几万行以上的库重建一次要几十秒，而且建索引全程持写锁
    → 上报请求被 database is locked 挡住 5 秒后 500。
    """
    row = conn.execute(
        "SELECT sql FROM sqlite_master WHERE type='index' AND name=?",
        (name,)).fetchone()
    if row and row[0]:
        want = " ".join(ddl.replace(
            "CREATE UNIQUE INDEX IF NOT EXISTS",
            "CREATE UNIQUE INDEX").split()).lower()
        have = " ".join(row[0].split()).lower()
        if want == have:
            return
        # 定义变了才重建（索引名是本文件里的常量，不来自请求）
        conn.execute("DROP INDEX IF EXISTS " + name)
    conn.execute(ddl)


def init_db():
    """初始化数据目录和 SQLite 表结构（含去重用的唯一索引）。"""
    os.makedirs(DATA_DIR, exist_ok=True)
    conn = _connect()
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
                received_at   INTEGER,   -- 服务端接收时间（毫秒）
                pkg           TEXT       -- 来源 App 包名（M2）
            )
            """
        )
        # 迁移：老库补 source 列，区分消息来源
        #   android-notification = 通知监听上报（默认，历史数据）
        #   android-db           = 安卓数据库直读合并（android_db_extract.py）
        # 说明：跨来源去重不依赖此索引（毫秒时间戳不一致），
        # 由 android_db_extract 在 SQLite 侧按 (pkg, conversation, sender,
        # 毫秒 timestamp, text) 归一键查询完成（不再在 Python 侧全表建集合）；
        # 此索引继续负责通知来源的重试去重和毫秒级完全相同时的兜底。
        cols = [r[1] for r in conn.execute("PRAGMA table_info(messages)")]
        if "source" not in cols:
            conn.execute(
                "ALTER TABLE messages ADD COLUMN source TEXT "
                "NOT NULL DEFAULT 'android-notification'")
        # M2 迁移：老库补 pkg 列并回填微信（历史数据全部来自微信）。
        if "pkg" not in cols:
            conn.execute(
                "ALTER TABLE messages ADD COLUMN pkg TEXT "
                f"NOT NULL DEFAULT '{DEFAULT_PKG}'")
        # 去重唯一索引（M2 版）：列补齐后再（重）建，去重维度含 pkg——
        # 不同 App 的同名会话里同发送者同毫秒同内容不再被误判为重复。
        # 老库的旧索引不含 pkg，先丢弃再建新索引（索引定义无法直接改）。
        _ensure_index(
            conn, "idx_messages_dedup",
            """CREATE UNIQUE INDEX IF NOT EXISTS idx_messages_dedup
            ON messages (pkg, conversation, sender, timestamp, text)""")
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
                received_at   INTEGER,       -- 服务端接收时间（毫秒）
                pkg           TEXT           -- 来源 App 包名（M2）
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
        # M2 迁移：老库补 pkg 列并回填微信（历史分析全部来自微信）。
        if "pkg" not in acols:
            conn.execute(
                "ALTER TABLE analyses ADD COLUMN pkg TEXT "
                f"NOT NULL DEFAULT '{DEFAULT_PKG}'")
        # 迁移：补齐客户端已经在上报、但服务端表里没有的列。
        # 客户端 AnalysisStore 会带脱敏证据（redacted / redaction_rules /
        # redaction_hits）与手动改写（todo_text / todo_due）；
        # 老表没有这些列 → INSERT 直接报 no such column，
        # 整批分析上报被拒（客户端当成失败重试，永远上不来）。
        for _col, _ddl in (("redacted", "INTEGER"),
                           ("redaction_rules", "TEXT"),
                           ("redaction_hits", "TEXT"),
                           ("todo_text", "TEXT"),
                           ("todo_due", "TEXT")):
            if _col not in acols:
                conn.execute(
                    "ALTER TABLE analyses ADD COLUMN " + _col + " " + _ddl)
        # 查询索引：/messages 与控制台都按时间倒序翻页并按会话/来源过滤，
        # 没有索引时每次查询都是全表扫描 + 排序（历史越大越慢）。
        conn.execute(
            "CREATE INDEX IF NOT EXISTS idx_messages_ts ON messages (timestamp)")
        conn.execute(
            "CREATE INDEX IF NOT EXISTS idx_messages_conv_ts "
            "ON messages (conversation, timestamp)")
        conn.execute(
            "CREATE INDEX IF NOT EXISTS idx_analyses_window_end "
            "ON analyses (window_end)")
        # 控制台按接收时间倒序取分析结果，没有索引就是全表扫 + 临时排序
        conn.execute(
            "CREATE INDEX IF NOT EXISTS idx_analyses_received_at "
            "ON analyses (received_at)")
        # 按会话过滤分析结果：conversation 前缀走范围比较才能用上这个索引
        conn.execute(
            "CREATE INDEX IF NOT EXISTS idx_analyses_conv_ts "
            "ON analyses (conversation, received_at)")
        conn.commit()
    finally:
        conn.close()


# ------------------------------------------------------------------
# 鉴权
# ------------------------------------------------------------------

# ------------------------------------------------------------------
# 鉴权失败限流（E11）
# ------------------------------------------------------------------
# 原来 401 没有任何速率限制：拿不到令牌的一方可以无限次试探，
# 顺带把服务端的日志和 CPU 刷满。这里加一个轻量的内存滑动窗口：
# 同一客户端 IP 在 60 秒内失败超过 10 次就回 429 + Retry-After。
# 纯内存 + threading.Lock，不引入任何外部依赖；进程重启即清零。

AUTH_FAIL_LIMIT = 10          # 窗口内允许的鉴权失败次数（超过即 429）
AUTH_FAIL_WINDOW = 60.0       # 滑动窗口长度（秒）
_auth_fail_lock = threading.Lock()
_auth_fail_events = {}        # 客户端 IP -> 失败时间戳列表


def _client_ip():
    """取客户端 IP：反代后面取 X-Forwarded-For 第一跳，否则用 remote_addr。"""
    forwarded = request.headers.get("X-Forwarded-For", "")
    if forwarded:
        first = forwarded.split(",")[0].strip()
        if first:
            return first
    return request.remote_addr or "-"


def _auth_fail_record(ip):
    """记一次鉴权失败，返回 (是否超限, Retry-After 秒数)。线程安全。"""
    now = time.time()
    with _auth_fail_lock:
        hits = [t for t in _auth_fail_events.get(ip, ()) if now - t < AUTH_FAIL_WINDOW]
        hits.append(now)
        _auth_fail_events[ip] = hits
        # 顺手清掉没有近期记录的 IP，避免字典无限增长
        if len(_auth_fail_events) > 256:
            stale = [k for k, v in _auth_fail_events.items()
                     if not any(now - t < AUTH_FAIL_WINDOW for t in v)]
            for k in stale:
                _auth_fail_events.pop(k, None)
        if len(hits) > AUTH_FAIL_LIMIT:
            oldest = hits[0]
            retry = int(AUTH_FAIL_WINDOW - (now - oldest)) + 1
            return True, max(1, retry)
    return False, 0


def _auth_fail_reset(ip):
    """鉴权通过：清掉该 IP 的失败计数。"""
    with _auth_fail_lock:
        _auth_fail_events.pop(ip, None)


def check_token():
    """校验令牌。返回 None 表示通过，否则返回 401 响应。

    支持两种传法（二选一）：
      1. 请求头 X-Token（API 调用、App 上报用）
      2. URL query ?token=xxx（iPhone Safari 等不方便带自定义头的浏览器；
         /console 页面会把 query token 写进 localStorage，之后页面内
         fetch 自动改用 X-Token 头）
    """
    if not TOKEN:
        if ALLOW_NO_AUTH:
            return None
        # 空令牌 = 谁访问到都能读全部消息正文。原来的实现直接 return None，
        # 在 WSGI 部署（绑定地址不受 app.py 控制）下等于把库裸奔到网上。
        return jsonify({
            "ok": False,
            "error": ("server token not configured: set WEIXIN_TOKEN "
                      "(or WEIXIN_ALLOW_NO_AUTH=1 for local-only usage)"),
        }), 503
    ip = _client_ip()
    if _token_match(request.headers.get("X-Token", "")):
        _auth_fail_reset(ip)
        return None
    if _token_match(request.args.get("token", "")):
        # query 传令牌会进反向代理访问日志 / 浏览器历史 / Referer，
        # 只为兼容 iPhone Safari 这类不能带自定义头的浏览器保留，打 deprecation 日志。
        logger.warning("deprecated: token via query string (%s %s)",
                       request.method, request.path)
        _auth_fail_reset(ip)
        return None
    limited, retry_after = _auth_fail_record(ip)
    if limited:
        logger.warning("too many failed auth attempts: %s %s from %s",
                       request.method, request.path, ip)
        return (jsonify({"ok": False,
                         "error": "too many failed auth attempts"}),
                429, {"Retry-After": str(retry_after)})
    logger.warning("unauthorized: %s %s from %s",
                   request.method, request.path, ip)
    return jsonify({"ok": False, "error": "unauthorized"}), 401


def _token_match(candidate):
    """恒定时间比较令牌。

    原来用 == 逐字符比较，比较耗时随相同前缀长度变化，理论上可被用来
    逐字节猜出令牌（时序侧信道）。
    """
    if not candidate:
        return False
    return hmac.compare_digest(candidate.encode("utf-8"), TOKEN.encode("utf-8"))


# ------------------------------------------------------------------
# 接口：GET /health 存活探测
# ------------------------------------------------------------------

@app.route("/health", methods=["GET"])
def health():
    """存活探测接口：只读，固定返回 200 + {"status": "ok"}。

    供反向代理 / 外部监控 / 部署脚本探活用。响应不含任何业务数据，
    故不校验 X-Token——带鉴权会让无状态的存活探针必须持有令牌。
    """
    return jsonify({"status": "ok"}), 200


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
    rejected = []   # 校验失败的原始下标，供客户端精确剔除/重试
    archive_error = ""   # jsonl 存档写入失败的原因（库已提交，不回滚）

    now_ms = int(time.time() * 1000)  # 服务端接收时间（毫秒）

    with _write_lock:
        conn = _connect()
        accepted = []   # 真正入库成功的原始条目（按入库顺序）
        try:
            for idx, item in enumerate(payload):
                # 每条必须是对象且字段齐全、类型正确
                if not isinstance(item, dict) or not _validate_item(item):
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
                    pkg = DEFAULT_PKG
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
                _append_jsonl(accepted)
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


def _importance_label(value):
    """把 S1 重要度统一成 high/mid/low 标签。

    端上实际发的是 0-9 数值（>=6 high / >=3 mid / 其余 low），而旧文档与
    控制台 JS 只认 high/mid/low 字符串；两种都要归一，否则数值重要度会在
    控制台里被当成未知值退化为最低优先级。
    """
    if value is None or isinstance(value, bool):
        return None
    if isinstance(value, (int, float)):
        num = float(value)
    else:
        text = str(value).strip().lower()
        if not text:
            return None
        try:
            num = float(text)
        except ValueError:
            return text
    if num >= 6:
        return "high"
    if num >= 3:
        return "mid"
    return "low"


def _safe_int(value, default=0, lo=None, hi=None):
    """把任意上报值安全转成 int：非法或越界一律回落到 default。

    原来 int(item["timestamp"]) / int(item.get("window_end")) 直接抛
    ValueError / OverflowError → 整个批次 500，而且异常发生在写了一半的
    时候（jsonl 已经写进去、库里没有）。
    """
    if value is None or isinstance(value, bool):
        return default
    try:
        n = int(value)
    except (TypeError, ValueError, OverflowError):
        return default
    if lo is not None and n < lo:
        return default
    if hi is not None and n > hi:
        return default
    return n


def _clamp_int(value, lo=0, hi=None, default=0):
    """把任意上报值收敛到 [lo, hi] 区间内的 int。

    与 _safe_int 的区别：_safe_int 越界时回落到 default（值直接变 0），
    这里改成夹紧到边界 —— 例如 case_count 报了 99999999，存 1000000
    比存 0 更贴近事实（控制台显示的数字仍然有意义）。
    """
    if value is None or isinstance(value, bool):
        return default
    try:
        n = int(value)
    except (TypeError, ValueError, OverflowError):
        return default
    if n < lo:
        return lo
    if hi is not None and n > hi:
        return hi
    return n


def _safe_bool(value, default=False):
    """把任意来源的布尔值归一成 True/False。

    客户端把 false 上报成字符串 "false" / "0" / "" 时，Python 的真值判断
    会把它们全当成 True（非空字符串都是真值）-> 分析结果里 escalated /
    filtered / redacted 被批量写成 1。
    这里统一口径：布尔直接用；字符串按 true/1/yes/on 判真；数字按 != 0 判真；
    认不出来的值一律按 default（默认 False）处理。
    """
    if value is None:
        return default
    if isinstance(value, bool):
        return value
    if isinstance(value, (int, float)):
        return value != 0
    if isinstance(value, str):
        text = value.strip().lower()
        if text in ("true", "1", "yes", "on", "y", "t"):
            return True
        if text in ("false", "0", "no", "off", "n", "f", ""):
            return False
        return default
    return default


def _parse_tz_offset(raw_tz, raw_offset):
    """解析时区参数，返回分钟偏移（东八区 = +480）或 None（未指定）。

    支持 tz=+08:00 / tz=+0800 / tz=+8，以及 tz_offset=480（分钟偏移）。
    非法值返回 ("error", 说明)，调用方据此回 400。

    兼容 URL 里未编码的 '+'：查询串 "?tz=+08:00" 里的 '+' 会被解码成空格
    （服务端收到的是 " 08:00"）。这是手写 curl / 浏览器地址栏最常见的写法，
    按 '+' 处理，免得用户白挨一个 400。
    """
    raw = raw_tz or ""
    tz = raw.strip()
    if tz and raw[:1] == " " and tz[0] not in "+-":
        tz = "+" + tz
    off = (raw_offset or "").strip()
    if tz and off:
        return ("error", "use either tz or tz_offset, not both")
    if off:
        try:
            minutes = int(off)
        except ValueError:
            return ("error", "tz_offset must be an integer number of minutes")
        if not (-14 * 60 <= minutes <= 14 * 60):
            return ("error", "tz_offset out of range")
        return minutes
    if not tz:
        return None
    matched = re.match(r"^([+-])(\d{1,2})(?::?(\d{2}))?$", tz)
    if not matched:
        return ("error", "tz must look like +08:00")
    sign = -1 if matched.group(1) == "-" else 1
    hours = int(matched.group(2))
    minutes = int(matched.group(3) or 0)
    if hours > 14 or minutes > 59:
        return ("error", "tz out of range")
    return sign * (hours * 60 + minutes)


def _fmt_tz_offset(minutes):
    """分钟偏移 -> "+08:00" 形式的字符串；None 原样返回。"""
    if minutes is None:
        return None
    sign = "-" if minutes < 0 else "+"
    minutes = abs(int(minutes))
    return "%s%02d:%02d" % (sign, minutes // 60, minutes % 60)


def _day_bounds_ms(date_str, tz_offset_min):
    """把 YYYY-MM-DD 换算成当天 [00:00, 次日00:00) 的毫秒区间。

    tz_offset_min 为 None 时用服务器本地时区（向后兼容的老行为）；
    否则按该固定偏移时区算（固定偏移没有夏令时，加 24h 精确）。
    """
    day = datetime.strptime(date_str, "%Y-%m-%d")
    if tz_offset_min is None:
        day_start = int(day.timestamp() * 1000)
    else:
        day_start = int(day.replace(tzinfo=timezone.utc).timestamp() * 1000) \
            - tz_offset_min * 60000
    return day_start, day_start + 24 * 3600 * 1000


def _fmt_ts(ms):
    """毫秒时间戳 → 本地时间字符串；越界/非法返回「时间未知」。

    datetime.fromtimestamp 对越界值抛 OSError / OverflowError，
    落地页里一条坏数据就能把整页打挂（实测 10**18 → 500）。
    """
    n = _safe_int(ms, default=None, lo=MIN_TS_MS, hi=MAX_TS_MS)
    if n is None:
        return "时间未知"
    try:
        return datetime.fromtimestamp(n / 1000.0).strftime("%Y-%m-%d %H:%M:%S")
    except (OSError, OverflowError, ValueError):
        return "时间未知"


def _safe_json(raw):
    """解析库里存的 JSON 列；坏数据返回 None，而不是让整个查询 500。"""
    if not raw:
        return None
    try:
        return json.loads(raw)
    except (json.JSONDecodeError, TypeError, ValueError):
        logger.warning("analyses 表存在损坏的 JSON 列，已跳过解析")
        return None


def _like_escape(s):
    """转义 LIKE 查询里的通配符（% 与 _）。

    不转义时，会话名里带 _（单字符通配）会匹配到别人的会话，
    带 % 则等于全表匹配。
    """
    return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")


def _prefix_upper(prefix):
    """前缀匹配的字典序上界：conversation >= p AND conversation < u。

    SQLite 对 TEXT 用 BINARY（UTF-8 字节序）比较，而 UTF-8 字节序与
    Unicode 码点序一致，所以 p + U+10FFFF 就是「以 p 开头」的宽上界：
        "项目组" <= c < "项目组\U0010FFFF"
    用范围比较而不是 LIKE '%x%'，是因为前导通配符会让
    idx_messages_conv_ts / idx_analyses_conv_ts 失效 → 每次查询全表扫。

    这里刻意用宽上界，而不是「最后一个码点 +1」的紧上界：紧上界会把
    「前缀 + 下一个码点」开头的会话一起排除（查 "项目组" 时漏掉 "项目组细"），
    属于误杀。
    """
    if not prefix:
        return None
    return prefix + "\U0010FFFF"


def _esc_html(s):
    """HTML 转义（落地页用；控制台内联 JS 自带 esc）。

    None / 空值一律渲染成空串：messages.conversation / sender 这类列
    允许为 NULL，原来 str(None) 会把字面量 None 显示给用户，
    None.replace() 还会直接抛 AttributeError 让整页 500。
    """
    if s is None:
        return ""
    text = str(s)
    if not text:
        return ""
    return (text.replace("&", "&amp;").replace("<", "&lt;")
            .replace(">", "&gt;").replace('"', "&quot;")
            .replace("'", "&#39;"))


def _append_jsonl(items):
    """把入库成功的原始条目追加到 jsonl 存档。

    - 先建目录（数据目录可能被清过）
    - 超过阈值就轮转一份（messages.jsonl.1），避免单文件无限膨胀
    """
    os.makedirs(DATA_DIR, exist_ok=True)
    try:
        if (os.path.exists(JSONL_PATH)
                and os.path.getsize(JSONL_PATH) > JSONL_MAX_BYTES):
            os.replace(JSONL_PATH, JSONL_PATH + ".1")
    except OSError:
        pass
    with open(JSONL_PATH, "a", encoding="utf-8") as f:
        for item in items:
            f.write(json.dumps(item, ensure_ascii=False) + "\n")
        f.flush()


def _analyzed_at_text(value):
    """analyzed_at 列是 TEXT，但 App 上报的是毫秒整数。

    原实现只接受 str，数值上报会永远写成 NULL（控制台"分析于"始终为空）。
    数值统一转成本地时间字符串，字符串原样保留。
    越界的数值（如 10**18）原来会让 fromtimestamp 抛 OSError → 整批 500，
    现在越界值按「无法解释的时间」处理（返回 None），不拖垮整批。
    """
    if value is None or isinstance(value, bool):
        return None
    if isinstance(value, (int, float)):
        ms = _safe_int(value, default=None, lo=MIN_TS_MS, hi=MAX_TS_MS)
        if ms is None:
            return None
        if ms > 100_000_000_000:  # >10^11 视为毫秒（10^11 ≈ 1973 年）
            return _fmt_ts(ms)
        return str(ms)
    text = str(value).strip()
    return text or None


def _validate_item(item):
    """校验单条消息的必备字段、类型、长度与数值边界。

    越界数据必须在这里被挡下来：正文 1 MB 会把内存撑爆，
    timestamp 越界会让查询与落地页整页 500。
    """
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
            # 时间戳必须落在 [1970, 2100) 毫秒区间内
            if _safe_int(value, default=None,
                         lo=MIN_TS_MS, hi=MAX_TS_MS) is None:
                return False
        elif not isinstance(value, expected_type):
            return False
    # 长度上限：正文 64 KiB，发送者 / 会话名 512
    if len(item["text"]) > MAX_TEXT_LEN:
        return False
    if len(item["sender"]) > MAX_NAME_LEN:
        return False
    if len(item["conversation"]) > MAX_NAME_LEN:
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
    auth_err = check_token()
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
    tz_parsed = _parse_tz_offset(request.args.get("tz", ""),
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
            params.append(f"%{_like_escape(conversation)}%")
        else:
            conditions.append("conversation >= ? AND conversation < ?")
            params.extend([conversation, _prefix_upper(conversation)])

    # 按日期过滤（timestamp 落在当天 [00:00, 次日00:00) 区间）
    if date_str:
        try:
            day_start, day_end = _day_bounds_ms(date_str, tz_offset_min)
        except ValueError:
            return jsonify({"ok": False, "error": "date must be YYYY-MM-DD"}), 400
        conditions.append("timestamp >= ? AND timestamp < ?")
        params.extend([day_start, day_end])

    # 游标分页：按 (timestamp, rowid) 往前翻，避免 LIMIT/OFFSET 深翻页全表扫描。
    # 只按 timestamp 比较会漏掉「同一毫秒但排序靠后」的多条消息，
    # 所以 next_before 带上 rowid，写成 <毫秒>_<rowid>（老客户端只发毫秒也兼容）。
    if before_str:
        parts = before_str.split("_")
        try:
            before_ms = int(parts[0])
            before_rid = int(parts[1]) if len(parts) > 1 else None
        except (ValueError, IndexError):
            return jsonify({
                "ok": False,
                "error": "before must be <ms> or <ms>_<rowid>",
            }), 400
        # 游标必须是合法毫秒时间戳：越界值（如 9*10**22）传给 sqlite
        # 会抛 OverflowError → 整个查询 500
        if not (MIN_TS_MS <= before_ms <= MAX_TS_MS):
            return jsonify({
                "ok": False,
                "error": "before out of range",
            }), 400
        # 行值比较 (timestamp, rowid) < (?, ?)：
        # 原来的 OR 谓词用不上索引（EXPLAIN 显示全表 SCAN），
        # 行值比较能走索引。老客户端只发毫秒时用 int64 上界当 rowid，
        # 顺带修掉「同一毫秒的多行被整批漏掉」的老问题。
        rid = before_rid if before_rid is not None else MAX_ROWID
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

    conn = _connect()
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
        "tz": _fmt_tz_offset(tz_offset_min),
        "tz_offset_minutes": tz_offset_min,
    })


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
        pkg           str   可选，来源 App 包名；缺省按微信处理
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
    rejected = []   # 校验失败的原始下标，供客户端精确剔除/重试
    # 被拒条目的 case_id（能取到时）：客户端优先按 id 匹配，避免依赖
    # 「请求体顺序 == 分批顺序」这个隐式前提（下标口径保留，兼容老客户端）。
    rejected_ids = []
    now_ms = int(time.time() * 1000)

    with _write_lock:
        conn = _connect()
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
                    if (len(item["case_id"]) > MAX_NAME_LEN
                            or len(item["conversation"]) > MAX_NAME_LEN):
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
                        if isinstance(na, dict) and _safe_bool(na.get("value")):
                            need_action = 1
                        importance = _importance_label(s1.get("importance"))
                    forks = item.get("forks") if isinstance(
                        item.get("forks"), list) else None
                    # pkg：与消息表口径一致，老上报缺省按微信回填
                    apkg = item.get("pkg")
                    if not isinstance(apkg, str) or not apkg.strip():
                        apkg = DEFAULT_PKG
                    # 数值字段必须落在合法区间：原来 int("abc") 直接抛 ValueError
                    # → 整批 500，而且异常时前面的条目已经进库
                    _we = item.get("window_end")
                    window_end = 0 if _we is None else _safe_int(
                        _we, default=None, lo=MIN_TS_MS, hi=MAX_TS_MS)
                    _mc = item.get("message_count")
                    message_count = 0 if _mc is None else _safe_int(
                        _mc, default=None, lo=0, hi=MAX_MESSAGE_COUNT)
                    if window_end is None or message_count is None:
                        invalid += 1
                        rejected.append(idx)
                        rejected_ids.append(item["case_id"])
                        continue
                    todo_text = item.get("todo_text")
                    if not isinstance(todo_text, str):
                        todo_text = None
                    elif len(todo_text) > MAX_TEXT_LEN:
                        todo_text = todo_text[:MAX_TEXT_LEN]
                    todo_due = item.get("todo_due")
                    if not isinstance(todo_due, str):
                        todo_due = None
                    elif len(todo_due) > MAX_NAME_LEN:
                        todo_due = todo_due[:MAX_NAME_LEN]
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
                    if any(len(t) > MAX_TEXT_LEN
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
                                1 if _safe_bool(item.get("escalated")) else 0,
                                s1_text,
                                s2_text,
                                _analyzed_at_text(item.get("analyzed_at")),
                                item.get("protocol") if isinstance(
                                    item.get("protocol"), str) else None,
                                now_ms,
                                forks_text,
                                1 if _safe_bool(item.get("filtered")) else 0,
                                apkg.strip(),
                                1 if _safe_bool(item.get("redacted")) else 0,
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


@app.route("/analysis", methods=["GET"])
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
    auth_err = check_token()
    if auth_err:
        return auth_err

    pkg = request.args.get("pkg", "").strip()
    conversation = request.args.get("conversation", "").strip()
    todo_only = _safe_bool(request.args.get("todo_only"))
    date_str = request.args.get("date", "").strip()
    before_str = request.args.get("before", "").strip()
    # 注意：tz 不能提前 strip —— query 里未编码的 '+' 会被解码成空格，
    # _parse_tz_offset 需要看到原始的 " 08:00" 才能还原成 "+08:00"。
    tz_parsed = _parse_tz_offset(request.args.get("tz", ""),
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
            params.append(f"%{_like_escape(conversation)}%")
        else:
            conditions.append("conversation >= ? AND conversation < ?")
            params.extend([conversation, _prefix_upper(conversation)])
    if todo_only:
        conditions.append("need_action = 1")
    # 日期过滤（B7）：analyzed_at 有两种形态 —— 本地时间文本（多数）或
    # 毫秒整数文本（老数据）。两种都覆盖：纯数字按毫秒比区间，
    # 其余按日期前缀做字典序比较（'2026-11-10 08:00:00' 这类定长前缀可比）。
    if date_str:
        try:
            day_start, day_end = _day_bounds_ms(date_str, tz_offset_min)
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
        parts = before_str.split("_")
        try:
            before_ms = int(parts[0])
            before_rid = int(parts[1]) if len(parts) > 1 else None
        except (ValueError, IndexError):
            return jsonify({
                "ok": False,
                "error": "before must be <ms> or <ms>_<rowid>",
            }), 400
        if not (MIN_TS_MS <= before_ms <= MAX_TS_MS):
            return jsonify({"ok": False, "error": "before out of range"}), 400
        rid = before_rid if before_rid is not None else MAX_ROWID
        if not (0 <= rid <= MAX_ROWID):
            return jsonify({"ok": False, "error": "before out of range"}), 400
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

    conn = _connect()
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
                "window_end": _safe_int(r[2], default=None),
                "message_count": _safe_int(r[3], default=None),
                # s1/s2 解析回对象，与上报结构一致；
                # 坏 JSON 不能再让整个查询 500（原来一行脏数据就挂掉整个接口）
                "s1": _safe_json(r[7]),
                "escalated": bool(r[6]),
                "s2": _safe_json(r[8]),
                # 归一后的重要度 / 是否需要行动：
                # 库里 importance 可能是 0-9 数值，也可能是 high/mid/low 文本，
                # 统一归一成 high/mid/low 标签，前端不用再自己判类型。
                "need_action": bool(r[4]),
                "importance": _importance_label(r[5]),
                "analyzed_at": r[9],
                "protocol": r[10],
                "received_at": r[11],
                # fork 层轨迹（App v0.1.2+；老数据为 None/False）
                "forks": _safe_json(r[12]),
                "filtered": bool(r[13]),
                "pkg": r[14],
                # 脱敏证据与手动改写（客户端一直在上报，服务端以前直接丢掉）
                "redacted": bool(r[15]),
                "redaction_rules": _safe_json(r[16]),
                "redaction_hits": _safe_json(r[17]),
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
        "tz": _fmt_tz_offset(tz_offset_min),
        "tz_offset_minutes": tz_offset_min,
    })


# ------------------------------------------------------------------
# 接口：顾问层（Fable）复盘报告上报与查询
# ------------------------------------------------------------------
# 顾问报告量小（每天至多几条），不走 SQLite，直接存
# data/advisor_reports.json（JSON 数组，按接收顺序追加），
# report_id 为去重键：重复上报跳过（duplicated）。

# 顾问报告量小（每天至多几条），不走 SQLite，直接存
# data/advisor_reports.jsonl（JSONL 追加，主存储）
# + data/advisor_reports.json（JSON 数组快照，向后兼容的读法）
# report_id 为去重键：重复上报跳过（duplicated）。
#
# B9：原来每收到一条就把整个 JSON 数组重写一遍（tmp + os.replace），
# 存档越大写放大越厉害，写窗口里崩了还会丢历史。现在主存储改成 JSONL
# 追加（O(1) 追加，不重写老内容），JSON 数组只作为兼容快照保留
# （条数有上限，且只在内容有变化时写）。

ADVISOR_MAX_REPORTS = 1000           # 存档条数上限（超出丢最旧的）
ADVISOR_MAX_BYTES = 8 * 1024 * 1024  # JSONL 字节上限（超出轮转一份）


def _advisor_jsonl_path():
    """主存储路径（跟着 DATA_DIR 走，测试里改 DATA_DIR 就能一起隔离）。"""
    return os.path.join(DATA_DIR, "advisor_reports.jsonl")


class AdvisorArchiveError(RuntimeError):
    """顾问存档损坏。

    存档损坏时**拒绝写入**（不是静默当空列表后整份覆盖），
    历史报告原样留在盘上，等人工修复。
    """


ADVISOR_MAX_SUGGESTIONS = 200    # 单份报告的 suggestions 条数上限
ADVISOR_MAX_SUMMARY_LEN = 4096   # summary 字符上限
ADVISOR_MAX_DETAIL_LEN = 2048    # suggestion.detail 字符上限
ADVISOR_MAX_TITLE_LEN = 256      # suggestion.title 字符上限
ADVISOR_MAX_MODEL_LEN = 128      # model_used 字符上限
ADVISOR_MAX_CREATED_LEN = 64     # created_at 字符上限
ADVISOR_MAX_PERIOD_DAYS = 3650   # period_days 上限（10 年）
ADVISOR_MAX_CASE_COUNT = 1000000  # case_count 上限


def _write_advisor_jsonl(reports):
    """整份重写 JSONL 主存储（迁移 / 裁剪时用）。"""
    path = _advisor_jsonl_path()
    os.makedirs(os.path.dirname(path) or ".", exist_ok=True)
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        for item in reports:
            f.write(json.dumps(item, ensure_ascii=False) + "\n")
        f.flush()
    os.replace(tmp, path)


def _append_advisor_reports(items):
    """追加写入 JSONL 主存储：O(1) 追加，不再整份重写。"""
    if not items:
        return
    path = _advisor_jsonl_path()
    os.makedirs(os.path.dirname(path) or ".", exist_ok=True)
    # 容量上限：文件超阈值就轮转一份（只保留最近一份历史），避免无界增长
    try:
        if (os.path.exists(path)
                and os.path.getsize(path) > ADVISOR_MAX_BYTES):
            os.replace(path, path + ".1")
    except OSError:
        pass
    with open(path, "a", encoding="utf-8") as f:
        for item in items:
            f.write(json.dumps(item, ensure_ascii=False) + "\n")
        f.flush()


def _trim_advisor_jsonl(keep=ADVISOR_MAX_REPORTS):
    """有界裁剪：JSONL 条数超上限时只保留最近 keep 条。"""
    path = _advisor_jsonl_path()
    try:
        with open(path, encoding="utf-8") as f:
            lines = [ln for ln in f if ln.strip()]
    except OSError:
        return
    if len(lines) <= keep:
        return
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        f.writelines(lines[-keep:])
    os.replace(tmp, path)


def _load_advisor_reports():
    """读取顾问报告存档（主存储 JSONL；兼容旧的 JSON 数组并自动迁移）。

    存档损坏时抛 AdvisorArchiveError，**拒绝写入**：
    原来的实现在这里直接返回空列表，下一条上报就会拿「空列表 + 新条目」
    整份覆盖写回去 -> 历史报告被无声抹掉。现在损坏的文件原样留在盘上，
    写入路径直接回 5xx，等人工修复（修好或删掉损坏文件后自动恢复）。
    """
    jsonl_path = _advisor_jsonl_path()
    if os.path.exists(jsonl_path):
        items = []
        try:
            with open(jsonl_path, encoding="utf-8") as f:
                for line in f:
                    line = line.strip()
                    if not line:
                        continue
                    obj = json.loads(line)
                    if isinstance(obj, dict):
                        items.append(obj)
        except (json.JSONDecodeError, OSError, ValueError) as e:
            logger.error("顾问存档损坏，拒绝写入: %s（原因: %s）", jsonl_path, e)
            raise AdvisorArchiveError(
                f"advisor archive is corrupted: {jsonl_path}") from e
        return items
    # 没有 JSONL：读旧格式（JSON 数组）；读通就迁移成 JSONL
    if not os.path.exists(ADVISOR_PATH):
        return []
    try:
        with open(ADVISOR_PATH, encoding="utf-8") as f:
            data = json.load(f)
        if not isinstance(data, list):
            raise ValueError("advisor archive is not a JSON list")
    except (json.JSONDecodeError, OSError, ValueError) as e:
        logger.error("顾问存档损坏，拒绝写入: %s（原因: %s）", ADVISOR_PATH, e)
        raise AdvisorArchiveError(
            f"advisor archive is corrupted: {ADVISOR_PATH}") from e
    records = [r for r in data if isinstance(r, dict)]
    try:
        _write_advisor_jsonl(records)
    except OSError as e:
        logger.error("顾问存档迁移到 JSONL 失败（原因: %s）", e)
    return records


def _write_advisor_snapshot(reports):
    """写向后兼容的 JSON 数组快照（ADVISOR_PATH）。

    只在内容有变化时写；内容没变直接跳过（原来每次上报都无条件整份重写）。
    """
    payload = json.dumps(reports, ensure_ascii=False, indent=2)
    try:
        with open(ADVISOR_PATH, encoding="utf-8") as f:
            if f.read() == payload:
                return
    except OSError:
        pass
    tmp = ADVISOR_PATH + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        f.write(payload)
    os.replace(tmp, ADVISOR_PATH)


def _save_advisor_reports(reports):
    """整份覆盖写顾问存档（JSONL 主存储 + JSON 数组快照）。

    正常上报走 _append_advisor_reports（O(1) 追加）；这个函数留给
    迁移 / 裁剪 / 人工修复的整份重写场景。
    """
    records = [r for r in reports if isinstance(r, dict)]
    _write_advisor_jsonl(records)
    _write_advisor_snapshot(records)


def _normalize_advisor_item(item):
    """把一条顾问报告收敛成安全形状。

    返回 (归一化后的 dict, None) 表示接受；(None, 原因) 表示拒绝。

    原来整条 item 原样落盘：case_count / period_days 可能是字符串、null 或
    浮点，控制台 parseInt 后显示 undefined；summary / suggestions 里的文本
    长度不设上限，一份几 MB 的报告就能把存档撑爆。这里统一成
    「数值字段收敛成 int、字符串字段截断、suggestions 只留 dict 且条数有上限」。
    """
    if not isinstance(item, dict):
        return None, "item is not an object"
    rid = item.get("report_id")
    if not isinstance(rid, str) or not rid.strip():
        return None, "missing report_id"
    rid = rid.strip()
    if len(rid) > MAX_NAME_LEN:
        return None, "report_id too long"
    out = {"report_id": rid}

    def _clip(value, limit):
        if value is None:
            return None
        if not isinstance(value, str):
            value = str(value)
        return value[:limit]

    created_at = _clip(item.get("created_at"), ADVISOR_MAX_CREATED_LEN)
    if created_at is not None:
        out["created_at"] = created_at
    summary = _clip(item.get("summary"), ADVISOR_MAX_SUMMARY_LEN)
    if summary is not None:
        out["summary"] = summary
    model_used = _clip(item.get("model_used"), ADVISOR_MAX_MODEL_LEN)
    if model_used is not None:
        out["model_used"] = model_used
    # 数值字段统一成 int（脏值/越界回落到 0），控制台不用再 parseInt 兜底
    out["period_days"] = _clamp_int(
        item.get("period_days"), lo=0, hi=ADVISOR_MAX_PERIOD_DAYS)
    out["case_count"] = _clamp_int(
        item.get("case_count"), lo=0, hi=ADVISOR_MAX_CASE_COUNT)
    sug = item.get("suggestions")
    norm_sug = []
    if isinstance(sug, list):
        for s in sug[:ADVISOR_MAX_SUGGESTIONS]:
            if not isinstance(s, dict):
                continue
            ns = {}
            for key, limit in (("type", ADVISOR_MAX_TITLE_LEN),
                               ("title", ADVISOR_MAX_TITLE_LEN),
                               ("detail", ADVISOR_MAX_DETAIL_LEN)):
                v = _clip(s.get(key), limit)
                if v is not None:
                    ns[key] = v
            norm_sug.append(ns)
    out["suggestions"] = norm_sug
    # pkg 可选透传：客户端带上就存下来，控制台才能对顾问报告按来源过滤
    # （与 /analysis、/messages 同口径）。缺省或非法则不带该键。
    apkg = item.get("pkg")
    if isinstance(apkg, str) and apkg.strip():
        out["pkg"] = apkg.strip()[:MAX_NAME_LEN]
    return out, None


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
    返回 200：{"ok": True, "inserted": n, "duplicated": m, "invalid": 0}
    返回 207：{"ok": False, "inserted": n, "duplicated": m, "invalid": k,
              "rejected": [下标...], "error": "some items were rejected"}
          （与 /weixin、/analysis 同款语义：被拒的条目不推进客户端的已同步水位）
    """
    auth_err = check_token()
    if auth_err:
        return auth_err

    payload = request.get_json(silent=True)
    if not isinstance(payload, list):
        return jsonify({"ok": False, "error": "body must be a JSON array"}), 400

    inserted = duplicated = invalid = 0
    rejected = []
    # 被拒条目的 report_id（能取到时）：客户端优先按 id 匹配，不依赖下标
    rejected_ids = []

    with _write_lock:
        # 存档损坏时**拒绝写入**：宁可这次上报失败（客户端保留、下轮重报），
        # 也不要拿「空列表 + 新条目」整份覆盖写回去把历史报告抹掉。
        try:
            reports = _load_advisor_reports()
        except AdvisorArchiveError as e:
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
            norm, why = _normalize_advisor_item(item)
            if norm is None:
                invalid += 1
                rejected.append(idx)
                if isinstance(item, dict):
                    _rid = item.get("report_id")
                    if isinstance(_rid, str) and _rid.strip():
                        rejected_ids.append(_rid.strip()[:MAX_NAME_LEN])
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
            _append_advisor_reports(new_items)
            reports.extend(new_items)
            # 有界裁剪：条数超上限时只保留最近的
            if len(reports) > ADVISOR_MAX_REPORTS:
                reports = reports[-ADVISOR_MAX_REPORTS:]
                _trim_advisor_jsonl(ADVISOR_MAX_REPORTS)
            # 兼容快照（JSON 数组）：只在有变化时写
            _write_advisor_snapshot(reports)

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

    try:
        reports = _load_advisor_reports()
    except AdvisorArchiveError as e:
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


def _import_extract_module(leg):
    """按文件路径加载提取模块（android_db_extract / pc_db_extract）。

    原来用 __import__("android_db_extract") 裸名导入：这只有在 app.py 所在
    目录恰好是 sys.path[0] 时才成立。用 WSGI 启动器
    （waitress-serve app:app / gunicorn app:app）时 sys.path 里没有 server/
    → ModuleNotFoundError，提取功能整个报废（实测 POST /admin/extract
    → "No module named android_db_extract"）。
    """
    path = os.path.join(BASE_DIR, f"{leg}_db_extract.py")
    if not os.path.exists(path):
        raise ImportError(f"extract module not found: {path}")
    spec = importlib.util.spec_from_file_location(
        f"notifyme_{leg}_db_extract", path)
    if spec is None or spec.loader is None:
        raise ImportError(f"cannot load extract module: {path}")
    mod = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = mod
    spec.loader.exec_module(mod)
    return mod


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
    try:
        for leg in legs:
            try:
                mod = _import_extract_module(leg)
                result[leg] = mod.run_extract(full=full)
            except Exception as e:  # 单路失败不拖垮服务与其它路
                result[leg] = {"ok": False, "error": str(e)}
                # 主库被占用是可重试的瞬时故障，单独标出来，
                # 控制台/运维能一眼看出「等一会儿再试」而不是「数据坏了」
                _etext = str(e).lower()
                if (isinstance(e, sqlite3.OperationalError)
                        or "locked" in _etext or "busy" in _etext
                        or "占用" in str(e)):
                    result[leg]["error_kind"] = "busy"
        # 成功判定：必须明确是 True 才算成功（B11）。
        # 原来写成 is not False：返回体里没有 ok 键时会被当成成功
        # （两个提取脚本早期版本都没这个键）-> 状态页显示 ok 但其实没跑成。
        ok = any(isinstance(result[leg], dict) and result[leg].get("ok") is True
                 for leg in legs)
        with _extract_lock:
            _extract_status["last"] = {"ok": ok, **result}
    finally:
        # 无论成功失败都要复位 running：
        # 原来只有走到最后一行才复位，中途抛异常 → running 永久为 True
        # → 之后的 /admin/extract 一直返回 409，提取功能彻底卡死。
        with _extract_lock:
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
    # 主库被占用（另一个提取任务正在批量提交 / 有长事务没提交）时
    # 直接回 503 + Retry-After：提取要往同一个库里写，硬启动只会让后台
    # 线程默默跑一半再失败，用户还看到「已启动」。
    state, detail = _probe_main_db_writable()
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



def _import_console_ui_module():
    """
    按文件路径加载 console_ui.py（控制台前端资源：HTML / manifest / SW）。

    为什么用 importlib 按路径加载，而不是普通 import：
    - app.py 常常是被直接执行的脚本（python server/app.py），此时不存在包结构；
    - WSGI 部署（waitress / gunicorn / uwsgi）时工作目录与 sys.path 都不可控。
    两种场景下普通的 import console_ui 都可能 ImportError，按路径加载则都稳。
    与 _import_extract_module 同一套路。
    """
    path = os.path.join(BASE_DIR, "console_ui.py")
    try:
        spec = importlib.util.spec_from_file_location("notifyme_console_ui", path)
        if spec is None or spec.loader is None:
            raise ImportError("cannot load console_ui from %s" % path)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
    except Exception as exc:
        raise RuntimeError("failed to load console_ui.py: %s" % exc)
    for name in ("CONSOLE_HTML", "CONSOLE_MANIFEST", "CONSOLE_SW"):
        if not hasattr(module, name):
            raise RuntimeError("console_ui.py is missing %s" % name)
    return module
# ---- 控制台前端资源（HTML / manifest / service worker）已拆到 console_ui.py ----
# 拆出来的原因：app.py 原本是「后端路由 + 存储层 + 控制台前端」三合一的上帝文件，
# 前端资源占了近 700 行，改一个按钮得在路由代码里翻找。拆出后可独立单测。
# 加载方式与 _import_extract_module 一致：按文件路径 importlib 加载，
# 这样脚本直跑与 WSGI 部署都不依赖 sys.path 或包结构。
_CONSOLE_UI = _import_console_ui_module()
CONSOLE_HTML = _CONSOLE_UI.CONSOLE_HTML
CONSOLE_MANIFEST = _CONSOLE_UI.CONSOLE_MANIFEST
CONSOLE_SW = _CONSOLE_UI.CONSOLE_SW

# manifest 图标：PNG 放 pwa_icons/（由 gen_icons.py 生成），经 /console/icons/<name> 提供。
# manifest 里的 src 用相对路径，相对 manifest 自身 URL 解析
# （/console/manifest.json -> /console/icons/...；
#  /mmonitor/console/manifest.json -> /mmonitor/console/icons/...），
# 两种挂载方式都正确。SVG data URI 保留作兜底（Android Chrome 可用）。
_ICON_SVG = ('data:image/svg+xml,%3Csvg xmlns=' + chr(39) + 'http://www.w3.org/2000/svg' + chr(39) + ' '
             'width=' + chr(39) + '512' + chr(39) + ' height=' + chr(39) + '512' + chr(39) + '%3E%3Crect width=' + chr(39) + '512' + chr(39) + ' height=' + chr(39) + '512' + chr(39) + ' '
             'rx=' + chr(39) + '100' + chr(39) + ' fill=' + chr(39) + '%2307C160' + chr(39) + '/%3E%3Ctext x=' + chr(39) + '256' + chr(39) + ' y=' + chr(39) + '330' + chr(39) + ' '
             'font-size=' + chr(39) + '260' + chr(39) + ' text-anchor=' + chr(39) + 'middle' + chr(39) + '%3E%F0%9F%92%AC%3C/text%3E%3C/svg%3E')

PWA_ICON_DIR = os.path.join(BASE_DIR, 'pwa_icons')

# manifest 若缺 icons（前端资源没带），补齐默认四项，保证 PWA 可安装。
_mc_icons = list(CONSOLE_MANIFEST.get('icons') or [])
_mc_have = set(str(it.get('src', '')) for it in _mc_icons)
if 'icons/icon-192.png' not in _mc_have:
    _mc_icons.insert(0, {'src': 'icons/icon-192.png', 'sizes': '192x192',
                     'type': 'image/png', 'purpose': 'any'})
if 'icons/icon-512.png' not in _mc_have:
    _mc_icons.insert(1, {'src': 'icons/icon-512.png', 'sizes': '512x512',
                     'type': 'image/png', 'purpose': 'any'})
if 'icons/icon-512-maskable.png' not in _mc_have:
    _mc_icons.append({'src': 'icons/icon-512-maskable.png', 'sizes': '512x512',
                     'type': 'image/png', 'purpose': 'maskable'})
if not any(str(it.get('type', '')).startswith('image/svg') for it in _mc_icons):
    _mc_icons.append({'src': _ICON_SVG, 'sizes': 'any',
                     'type': 'image/svg+xml', 'purpose': 'any'})
CONSOLE_MANIFEST['icons'] = _mc_icons


# strict_slashes=False：同时匹配 /console 与 /console/，
# 因为 manifest 的 start_url "./" 会解析成带末尾斜杠的地址
@app.route("/console", methods=["GET"], strict_slashes=False)
def console_page():
    """移动端控制台单页。

    骨架本身**不含任何用户数据**，所以不再强制鉴权：
    原来强制鉴权 → PWA manifest 的 start_url 指向 "./"，
    从桌面主屏启动直接撞上 401，用户看到的是一屏错误而不是控制台。
    真正的数据接口（/messages、/analysis、/advisor）仍然走 X-Token 鉴权。
    """
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

    conn = _connect()
    try:
        rows = conn.execute(
            """
            SELECT sender, text, timestamp, conversation, is_group, source, pkg
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
            with open(state_path, encoding="utf-8") as _sf:
                last_extract = json.load(_sf).get("last_run", "未知")
        except (json.JSONDecodeError, OSError):
            pass
    stats_html = (
        f'<div class="stats">安卓库消息 <b>{db_count}</b> 条 ｜ '
        f'最近提取：{_esc_html(last_extract)}</div>')

    # 来源徽标映射：通知上报 / 数据库直读
    SOURCE_BADGE = {
        "android-db": '<span class="badge badge-db">数据库</span>',
        "android-notification": '<span class="badge badge-notify">通知</span>',
    }

    # 拼接消息行
    items_html = ""
    for sender, text, ts, conversation, is_group, source, pkg in rows:
        # 越界时间戳不再打挂整页（原来一条 10**18 就让整页 500）
        time_str = _fmt_ts(ts)
        group_badge = '<span class="badge">群聊</span>' if is_group else ""
        src_badge = SOURCE_BADGE.get(source, "")
        # App 角标：微信不显示（主场景），其它 App 显示中文名
        app_badge = None
        if app_badge is None and (pkg or DEFAULT_PKG) != DEFAULT_PKG:
            app_badge = (
                f'<span class="badge badge-app">'
                f'{_esc_html(pkg_label(pkg))}</span>')
        # 统一走模块级 _esc_html：NULL 字段渲染成空串
        # （原来的局部 lambda 对 None 会抛 AttributeError，
        #  一条脏数据就能让整页 500；局部 esc 也没转义单引号，
        #  跟前端 esc 口径不一致）
        items_html += (
            f'<div class="msg">'
            f'<div class="meta">{group_badge}{src_badge}{app_badge or ""}'
            f'<span class="conv">{_esc_html(conversation)}</span>'
            f'<span class="sender">{_esc_html(sender)}</span>'
            f'<span class="time">{time_str}</span></div>'
            f'<div class="text">{_esc_html(text)}</div>'
            f'</div>'
        )
    if not items_html:
        items_html = '<div class="empty">暂无消息</div>'

    html = f"""<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>消息接收服务</title>
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
  .badge-app {{ background: #3370ff; }}
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
<h1>消息（最近 100 条）</h1>
{stats_html}
{items_html}
</body>
</html>"""
    # no-store：落地页内联了最近 100 条消息正文，
    # 绝不能被浏览器或 SW 缓存下来（原来只靠 SW 规则兜底，很容易再被缓存住）
    return Response(html, mimetype="text/html",
                    headers={"Cache-Control": "no-store"})


# ------------------------------------------------------------------
# 启动入口
# ------------------------------------------------------------------

def _require_token_configured():
    """令牌没配好就拒绝启动。

    WEIXIN_TOKEN 为空（或只有空白）时 check_token() 会放行一切请求
    -> 消息正文、分析结果、顾问报告全部裸奔。这里让「启动」这一步就失败，
    宁可起不来，也不要默默开一个无鉴权的服务。
    显式设置 WEIXIN_ALLOW_NO_AUTH=1 表示「我知道我在干什么」，放行。
    """
    if TOKEN or ALLOW_NO_AUTH:
        return
    raise RuntimeError(
        "未设置 WEIXIN_TOKEN：拒绝以无鉴权模式启动。"
        "请设置环境变量 WEIXIN_TOKEN 后重启；"
        "若仅想本机自测，可设 WEIXIN_ALLOW_NO_AUTH=1 显式放行。")


def _probe_main_db_writable(timeout=0.5):
    """探测主库现在能不能立刻拿到写锁。

    返回 (state, detail)，state 取值：ok / busy / error。
    提取任务与 /weixin、/analysis 都写同一个主库；被占用时（另一个提取任务
    正在批量提交、或有长事务没提交）直接回 503 + Retry-After，
    比让请求挂着等 30 秒 busy_timeout 再失败强。
    """
    try:
        conn = sqlite3.connect(DB_PATH, timeout=timeout, isolation_level=None)
    except sqlite3.Error as e:
        return "error", str(e)
    try:
        conn.execute("PRAGMA busy_timeout=%d" % int(timeout * 1000))
        conn.execute("BEGIN IMMEDIATE")
        conn.execute("ROLLBACK")
        return "ok", ""
    except sqlite3.OperationalError as e:
        text = str(e).lower()
        if "locked" in text or "busy" in text:
            return "busy", str(e)
        return "error", str(e)
    finally:
        try:
            conn.close()
        except sqlite3.Error:
            pass


def create_app(require_token=True):
    """WSGI 应用工厂（部署统一入口）。

    WSGI 部署（waitress-serve / gunicorn）**不会**执行下面的 __main__ 块，
    原来那两件保命的事都丢了：
      - init_db() 只在 __main__ 里跑 -> WSGI 起来后第一个请求就 500
        （sqlite3.OperationalError: unable to open database file）
      - 「令牌为空就退出」只在 __main__ 里检查 -> WSGI 起来就是无鉴权裸奔
    现在把两件事收进工厂：
      - 幂等 init_db()（重复调用安全，已建好就直接返回）
      - 令牌未配置且未显式允许无鉴权 -> 直接抛 RuntimeError，启动失败

    用法（server 目录下）：
        waitress-serve --call app:create_app        # Windows
        gunicorn "app:create_app()"                  # Linux
    """
    if require_token:
        _require_token_configured()
    init_db()
    return app


if __name__ == "__main__":
    try:
        application = create_app()
    except RuntimeError as e:
        print(f"[错误] {e}")
        sys.exit(1)
    if TOKEN:
        print(f"[信息] 已启用 X-Token 鉴权")
    elif ALLOW_NO_AUTH:
        print("[警告] 未设置 WEIXIN_TOKEN，且显式允许无鉴权运行"
              "（WEIXIN_ALLOW_NO_AUTH=1）：任何人都能读取全部消息正文！")
    print(f"[信息] 数据目录: {DATA_DIR}")
    # 无鉴权时默认只听本机：局域网/公网可直接读到全部消息正文，必须先有令牌。
    host = "0.0.0.0" if (TOKEN or ALLOW_INSECURE_BIND) else "127.0.0.1"
    if host == "0.0.0.0" and not TOKEN:
        print("[警告] 无鉴权且显式允许绑定 0.0.0.0（WEIXIN_ALLOW_INSECURE_BIND=1），"
              "同网段任何人都能读取你的消息！")
    print(f"[信息] 服务监听: http://{host}:{PORT}")
    # 优先用 waitress（生产级 WSGI，支持 Windows）；不可用时降级 Flask 内置服务器
    try:
        from waitress import serve
        serve(application, host=host, port=PORT, threads=4)
    except ImportError:
        print("[提示] waitress 未安装，降级为 Flask 内置服务器（仅限开发/自用）")
        application.run(host=host, port=PORT, threaded=True)
