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
from util import (
    _analyzed_at_text, _clamp_int, _day_bounds_ms, _esc_html, _fmt_ts, _fmt_tz_offset,
    _importance_label, _like_escape, _parse_tz_offset, _prefix_upper, _safe_bool,
    _safe_int, _safe_json, _validate_item, logger, pkg_label,
)
from storage import (
    AdvisorArchiveError, _advisor_jsonl_path, _append_advisor_reports, _append_jsonl,
    _connect, _ensure_index, _import_extract_module, _load_advisor_reports,
    _normalize_advisor_item, _probe_main_db_writable, _run_extract_bg,
    _save_advisor_reports, _trim_advisor_jsonl, _write_advisor_jsonl,
    _write_advisor_snapshot, init_db,
)
from routes import (
    admin_extract, admin_extract_status, query_advisor, query_analysis, query_messages,
    receive_advisor, receive_analysis, receive_weixin, register,
)
# 聚合统计与「下一步建议」两条只读接口（都按模块自带 register(app) 挂载）
import stats_api
import suggests

# 让 `python server/app.py` 直跑时，内部 `import app as app_module` 命中同一份模块，
# 避免出现第二份 Flask 应用与第二份运行期状态。
try:
    sys.modules.setdefault("app", sys.modules[__name__])
except KeyError:
    # 以别的名字被动态加载（例如测试里的 spec_from_file_location）时保持原样
    pass


if not logger.handlers:
    logging.basicConfig(
        level=logging.INFO,
        format="[%(asctime)s] %(levelname)s %(name)s %(message)s")

app = Flask(__name__)

# 单次请求体上限：上报接口只收 JSON 数组，8 MiB 足够几十万条消息，
# 但没有上限时一个超大 body 就能把进程内存打满。
app.config["MAX_CONTENT_LENGTH"] = 8 * 1024 * 1024

# 挂载数据面路由（/weixin、/messages、/analysis、/advisor、/admin/extract）
register(app)

# 挂载只读聚合接口（/stats 全库统计、/suggests/next 下一步建议）
stats_api.register(app)
suggests.register(app)


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

# ------------------------------------------------------------------
# 接口：GET /messages 查询消息
# ------------------------------------------------------------------

# ------------------------------------------------------------------
# 接口：分析结果上报与查询
# ------------------------------------------------------------------

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


ADVISOR_MAX_SUGGESTIONS = 200    # 单份报告的 suggestions 条数上限
ADVISOR_MAX_SUMMARY_LEN = 4096   # summary 字符上限
ADVISOR_MAX_DETAIL_LEN = 2048    # suggestion.detail 字符上限
ADVISOR_MAX_TITLE_LEN = 256      # suggestion.title 字符上限
ADVISOR_MAX_MODEL_LEN = 128      # model_used 字符上限
ADVISOR_MAX_CREATED_LEN = 64     # created_at 字符上限
ADVISOR_MAX_PERIOD_DAYS = 3650   # period_days 上限（10 年）
ADVISOR_MAX_CASE_COUNT = 1000000  # case_count 上限


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

# 归类规则的占位标记：必须与 console_ui.py 里的字面量完全一致。
# console_ui.py 只留标记，规则正文在 console_classify.js（单一事实源）。
CONSOLE_CLASSIFY_MARKER = '/*__CONSOLE_CLASSIFY_JS__*/'


def _read_console_classify_js():
    """读取控制台归类规则源文件 server/console_classify.js。

    读不到就返回空串并记一条告警，绝不抛异常打断服务启动：
    前端拿不到 window.ConsoleClassify 时会自动退化成「平铺渲染」，
    控制台照常可用，只是没有来源 App 分区 / 发送者归类这些增强。
    """
    path = os.path.join(BASE_DIR, 'console_classify.js')
    try:
        with open(path, 'r', encoding='utf-8') as fh:
            return fh.read()
    except Exception as exc:
        logger.warning('console_classify.js 读取失败，控制台将退化为平铺渲染：%s', exc)
        return ''


# 规则内联进控制台 HTML：占位标记只在 console_ui.py 里出现一次，
# 启动时替换一次即可（不要每个请求都 replace 一遍 70KB+ 的字符串）。
CONSOLE_CLASSIFY_JS = _read_console_classify_js()
CONSOLE_HTML_READY = CONSOLE_HTML.replace(CONSOLE_CLASSIFY_MARKER, CONSOLE_CLASSIFY_JS)

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
    return Response(CONSOLE_HTML_READY, mimetype="text/html",
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
