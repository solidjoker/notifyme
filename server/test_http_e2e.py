# -*- coding: utf-8 -*-
# SPDX-License-Identifier: MIT
"""服务端真实 HTTP 端到端用例（评审文档 §7 阶段 3：服务端集成 MockWebServer 端到端）。

Android 侧已经用 java.net.ServerSocket 自写的 HTTP/1.1 夹具补上了端到端用例
（app/src/test/java/com/notifyme/android/HttpEndToEndFixture.kt 等 3 个文件），
服务端这一侧此前只靠 Flask test client 覆盖。

Flask test client 在进程内直接调 WSGI callable，不经过 TCP、不经过 HTTP 解析，
所以下面这些只有真连一次才暴露：

* 状态码与响应体的真实形态（404 / 413 是 JSON，405 目前仍是 Flask 默认 HTML）；
* X-Token 头与 ?token= 两种鉴权传法在真实请求下的行为；
* 鉴权失败限流（429 + Retry-After）这条只在真实请求路径上触发；
* 请求体超限时服务端「先回 413、再断开连接」的真实时序；
* next_before 游标翻页在真实 URL 编码下的可用性。

这里用 werkzeug 的 make_server 起一个真实的 HTTP/1.1 服务器（等价于 Java 侧
MockWebServer 的角色），请求用 urllib 发（真实 TCP socket）。数据目录用临时目录
隔离，不碰仓库里的 server/data。
"""
import json
import os
import shutil
import socket
import sqlite3
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

import pytest
from werkzeug.serving import WSGIRequestHandler, make_server

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import app as app_module  # noqa: E402
from app import app as flask_app  # noqa: E402

TOKEN = "e2e-token"


class _QuietHandler(WSGIRequestHandler):
    """把 dev server 的访问日志静音（否则 pytest 输出被刷屏）。"""

    def log(self, *args, **kwargs):  # noqa: D102 - 覆盖父类签名
        pass


def _request(base, method, path, body=None, token=TOKEN, headers=None, timeout=10):
    """发一个真实 HTTP 请求，返回 (状态码, 响应体文本, 响应头)。"""
    hdrs = dict(headers or {})
    data = None
    if body is not None:
        data = body if isinstance(body, bytes) else json.dumps(body).encode("utf-8")
        hdrs.setdefault("Content-Type", "application/json")
    if token:
        hdrs.setdefault("X-Token", token)
    req = urllib.request.Request(base + path, data=data, method=method, headers=hdrs)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, resp.read().decode("utf-8"), dict(resp.headers)
    except urllib.error.HTTPError as exc:
        return exc.code, exc.read().decode("utf-8"), dict(exc.headers)


def _json(status, text):
    assert status == 200, f"期望 200，实际 {status}：{text[:300]}"
    return json.loads(text)

@pytest.fixture(scope="module")
def live_server():
    """起一个真实的 HTTP 服务器（整个模块共用一个，端口随机）。"""
    original_limit = flask_app.config.get("MAX_CONTENT_LENGTH")
    # 超限请求体的阈值调小：真实阈值是 8MiB（server/app.py MAX_CONTENT_LENGTH），
    # 用 8MiB+ 触发会让客户端在 sendall 阶段就撞上连接重置，测不到状态码。
    flask_app.config["MAX_CONTENT_LENGTH"] = 1024
    server = make_server("127.0.0.1", 0, flask_app, threaded=True,
                        request_handler=_QuietHandler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    deadline = time.time() + 10
    while time.time() < deadline:
        try:
            socket.create_connection(("127.0.0.1", server.server_port), timeout=0.5).close()
            break
        except OSError:
            time.sleep(0.02)
    try:
        yield "http://127.0.0.1:%d" % server.server_port
    finally:
        server.shutdown()
        thread.join(timeout=5)
        flask_app.config["MAX_CONTENT_LENGTH"] = original_limit


def _reset_extract_state():
    """复位提取任务的内存态（running / last），用例之间必须清干净。

    提取是「HTTP 触发 + 后台线程执行」的组合：后台线程把 running 置 True，
    跑完在 finally 里复位。上一条用例的线程若没跑完就退场，残留的
    running=True 会让下一条用例的 POST /admin/extract 直接 409（误报）。
    """
    app_module._extract_status["running"] = False
    app_module._extract_status["last"] = None


@pytest.fixture()
def isolated_store():
    """把服务端数据目录指到临时目录，并在用例之间清掉鉴权失败计数。"""
    tmp = tempfile.mkdtemp(prefix="notifyme_e2e_")
    saved = {
        "DATA_DIR": app_module.DATA_DIR,
        "DB_PATH": app_module.DB_PATH,
        "JSONL_PATH": app_module.JSONL_PATH,
        "ADVISOR_PATH": app_module.ADVISOR_PATH,
        "TOKEN": app_module.TOKEN,
        "ALLOW_NO_AUTH": app_module.ALLOW_NO_AUTH,
    }
    app_module.DATA_DIR = tmp
    app_module.DB_PATH = os.path.join(tmp, "e2e.db")
    app_module.JSONL_PATH = os.path.join(tmp, "messages.jsonl")
    app_module.ADVISOR_PATH = os.path.join(tmp, "advisor_reports.json")
    app_module.TOKEN = TOKEN
    app_module.ALLOW_NO_AUTH = "0"
    app_module._db_ready = False
    with app_module._auth_fail_lock:
        app_module._auth_fail_events.clear()
    _reset_extract_state()
    app_module.init_db()
    try:
        yield tmp
    finally:
        for key, value in saved.items():
            setattr(app_module, key, value)
        app_module._db_ready = False
        with app_module._auth_fail_lock:
            app_module._auth_fail_events.clear()
        _reset_extract_state()
        shutil.rmtree(tmp, ignore_errors=True)


@pytest.fixture()
def api(live_server, isolated_store):
    """组合夹具：真实服务器 + 隔离的数据目录。"""
    return live_server


def _msg(text, ts, conversation="研发群", sender="张三", pkg="com.tencent.mm",
         is_group=True):
    """构造一条合法的微信消息上报条目。"""
    return {
        "sender": sender,
        "text": text,
        "timestamp": ts,
        "conversation": conversation,
        "is_group": is_group,
        "pkg": pkg,
    }


def _analysis(case_id="case-1", conversation="研发群"):
    """构造一条合法的分析结果上报条目。"""
    return {
        "case_id": case_id,
        "conversation": conversation,
        "window_end": 1700000000000,
        "message_count": 3,
        "s1": {
            "need_action": {"value": True, "prob": 0.8},
            "importance": "high",
            "due_window": "today",
            "topic": "联调进度",
        },
        "s2": {
            "summary": "本周联调进度偏慢",
            "due_time": "2026-01-01 10:00",
            "suggested_action": "催一下后端",
            "tasks": ["催一下后端"],
        },
        "escalated": True,
        "analyzed_at": "2026-01-01T10:00:00Z",
        "protocol": "v1",
        "pkg": "com.tencent.mm",
    }


def _advisor(report_id="rep-1"):
    """构造一条合法的顾问复盘报告。"""
    return {
        "report_id": report_id,
        "created_at": "2026-01-01T10:00:00Z",
        "period_days": 7,
        "case_count": 5,
        "summary": "本周复盘：催办类占比偏高",
        "suggestions": [
            {"type": "prompt", "title": "调优提示词", "detail": "降低 need_action 阈值"},
        ],
        "model_used": "local-llama",
    }


# ------------------------------------------------------------------
# 基础链路
# ------------------------------------------------------------------


def test_health_is_public_and_returns_json(api):
    """/health 不校验令牌，返回 200 + JSON。"""
    status, text, headers = _request(api, "GET", "/health", token=None)
    assert status == 200
    assert json.loads(text)["status"] == "ok"
    assert "application/json" in headers.get("Content-Type", "")


def test_response_comes_from_a_real_http_server(api):
    """确认这条链路是真实 TCP + HTTP/1.1，而不是进程内 WSGI 调用。"""
    status, _text, headers = _request(api, "GET", "/health", token=None)
    assert status == 200
    assert "Werkzeug" in headers.get("Server", "")


def test_data_route_requires_token(api):
    """缺令牌访问数据面 → 401 + JSON。"""
    status, text, _ = _request(api, "GET", "/messages", token=None)
    assert status == 401
    body = json.loads(text)
    assert body["ok"] is False
    assert body["error"] == "unauthorized"


def test_wrong_token_is_rejected(api):
    """错令牌 → 401。"""
    status, text, _ = _request(api, "GET", "/messages", token="wrong-token")
    assert status == 401
    assert json.loads(text)["error"] == "unauthorized"


def test_token_via_query_string_is_accepted(api):
    """?token= 兼容路径：iPhone Safari 不能带自定义头。"""
    status, text, _ = _request(api, "GET", "/messages?token=" + TOKEN, token=None)
    assert status == 200
    assert json.loads(text)["ok"] is True


def test_repeated_auth_failures_are_rate_limited(api):
    """连续鉴权失败超过阈值 → 429 + Retry-After（真实请求路径才会触发）。"""
    codes = []
    for _ in range(11):
        status, _text, headers = _request(api, "GET", "/messages", token="bad-token")
        codes.append(status)
        if status == 429:
            assert int(headers.get("Retry-After", "0")) >= 1
            break
    assert codes[:10] == [401] * 10
    assert codes[-1] == 429


# ------------------------------------------------------------------
# /weixin 上报 → /messages 查询
# ------------------------------------------------------------------


def test_weixin_post_then_query_roundtrip(api):
    """上报两条 → 查询能原样取回（字段与正文一致）。"""
    payload = [
        _msg("第一条消息", 1700000000000),
        _msg("第二条消息", 1700000001000),
    ]
    status, text, _ = _request(api, "POST", "/weixin", body=payload)
    assert status == 200
    body = json.loads(text)
    assert body["ok"] is True
    assert body["received"] == 2
    assert body["duplicated"] == 0
    assert body["invalid"] == 0

    status, text, _ = _request(api, "GET", "/messages")
    assert status == 200
    data = json.loads(text)
    assert data["count"] == 2
    texts = sorted(m["text"] for m in data["messages"])
    assert texts == ["第一条消息", "第二条消息"]
    assert all(m["conversation"] == "研发群" for m in data["messages"])


def test_weixin_duplicate_payload_is_idempotent(api):
    """同一条再报一次 → duplicated 计数、不重复入库。"""
    payload = [_msg("重复消息", 1700000000000)]
    _request(api, "POST", "/weixin", body=payload)
    status, text, _ = _request(api, "POST", "/weixin", body=payload)
    assert status == 200
    body = json.loads(text)
    assert body["received"] == 0
    assert body["duplicated"] == 1

    status, text, _ = _request(api, "GET", "/messages")
    assert json.loads(text)["count"] == 1


def test_weixin_rejects_bad_item_with_207(api):
    """一条合法 + 一条缺字段 → 207 且给出被拒下标，合法的那条照常入库。"""
    bad = _msg("坏的", 1700000000000)
    del bad["text"]
    payload = [_msg("好的", 1700000000000), bad]
    status, text, _ = _request(api, "POST", "/weixin", body=payload)
    assert status == 207
    body = json.loads(text)
    assert body["ok"] is False
    assert body["rejected"] == [1]
    assert body["received"] == 1
    assert body["invalid"] == 1

    status, text, _ = _request(api, "GET", "/messages")
    assert json.loads(text)["count"] == 1


def test_weixin_rejects_non_array_body(api):
    """请求体不是数组 → 400 + JSON。"""
    status, text, _ = _request(api, "POST", "/weixin", body={"not": "a list"})
    assert status == 400
    assert json.loads(text)["error"] == "body must be a JSON array"


def test_messages_pagination_cursor(api):
    """next_before 游标翻页：两页拼起来刚好覆盖全部消息，不重不漏。"""
    payload = [
        _msg("m1", 1700000000000),
        _msg("m2", 1700000001000),
        _msg("m3", 1700000002000),
    ]
    _request(api, "POST", "/weixin", body=payload)

    status, text, _ = _request(api, "GET", "/messages?limit=2")
    assert status == 200
    page1 = json.loads(text)
    assert page1["count"] == 2
    assert page1["next_before"], "还有下一页时必须给游标"

    status, text, _ = _request(api, "GET",
                                "/messages?limit=2&before=" + page1["next_before"])
    assert status == 200
    page2 = json.loads(text)
    assert page2["count"] == 1
    assert page2["next_before"] is None, "最后一页不再给游标"

    got = [m["text"] for m in page1["messages"]] + [m["text"] for m in page2["messages"]]
    assert sorted(got) == ["m1", "m2", "m3"]


def test_messages_conversation_prefix_filter(api):
    """会话名默认前缀匹配（走索引的范围比较），不同会话不串味。"""
    payload = [
        _msg("研发消息", 1700000000000, conversation="研发群"),
        _msg("市场消息", 1700000001000, conversation="市场群"),
    ]
    _request(api, "POST", "/weixin", body=payload)

    status, text, _ = _request(api, "GET", "/messages?conversation=" +
                                urllib.parse.quote("研发群"))
    assert status == 200
    data = json.loads(text)
    assert data["count"] == 1
    assert data["messages"][0]["text"] == "研发消息"


def test_messages_contains_match_escapes_underscore(api):
    """conversation_match=contains 时下划线要转义，不能当通配符乱匹配。"""
    payload = [
        _msg("下划线会话", 1700000000000, conversation="a_b"),
        _msg("别的会话", 1700000001000, conversation="axb"),
    ]
    _request(api, "POST", "/weixin", body=payload)

    status, text, _ = _request(api, "GET",
                                "/messages?conversation=a_b&conversation_match=contains")
    assert status == 200
    data = json.loads(text)
    assert data["count"] == 1
    assert data["messages"][0]["conversation"] == "a_b"


# ------------------------------------------------------------------
# /analysis 与 /advisor
# ------------------------------------------------------------------


def test_analysis_post_then_query(api):
    """分析结果上报 → 查询能取回，并带上 S1/S2 归一化后的字段。"""
    status, text, _ = _request(api, "POST", "/analysis", body=[_analysis()])
    assert status == 200
    body = json.loads(text)
    assert body["ok"] is True
    assert body["received"] == 1

    status, text, _ = _request(api, "GET", "/analysis")
    assert status == 200
    data = json.loads(text)
    assert data["count"] == 1
    item = data["analyses"][0]
    assert item["case_id"] == "case-1"
    assert item["conversation"] == "研发群"
    assert item["need_action"] == 1


def test_analysis_rejects_bad_item_with_207(api):
    """缺 case_id → 207 + rejected_ids（客户端按 id 精确剔除）。"""
    bad = _analysis(case_id="")
    status, text, _ = _request(api, "POST", "/analysis", body=[bad])
    assert status == 207
    body = json.loads(text)
    assert body["ok"] is False
    assert body["rejected"] == [0]
    assert body["invalid"] == 1


def test_advisor_post_then_query(api):
    """顾问报告上报 → 查询能取回。"""
    status, text, _ = _request(api, "POST", "/advisor", body=[_advisor()])
    assert status == 200
    body = json.loads(text)
    assert body["ok"] is True
    assert body["inserted"] == 1

    status, text, _ = _request(api, "GET", "/advisor")
    assert status == 200
    data = json.loads(text)
    assert data["count"] == 1
    assert data["reports"][0]["report_id"] == "rep-1"


def test_advisor_duplicate_and_invalid(api):
    """重复 report_id → duplicated；缺 report_id → 207。"""
    _request(api, "POST", "/advisor", body=[_advisor()])
    status, text, _ = _request(api, "POST", "/advisor", body=[_advisor()])
    assert status == 200
    assert json.loads(text)["duplicated"] == 1

    bad = _advisor(report_id="")
    status, text, _ = _request(api, "POST", "/advisor", body=[bad])
    assert status == 207
    body = json.loads(text)
    assert body["rejected"] == [0]
    assert body["invalid"] == 1


# ------------------------------------------------------------------
# 错误语义与控制台静态资源
# ------------------------------------------------------------------


def test_oversized_body_returns_json_413(api):
    """请求体超限 → 413 + JSON（真实链路下服务端先回 413 再断连接）。"""
    big = json.dumps([_msg("x" * 4096, 1700000000000)]).encode("utf-8")
    assert len(big) > 1024
    status, text, _ = _request(api, "POST", "/weixin", body=big)
    assert status == 413
    body = json.loads(text)
    assert body["ok"] is False
    assert body["error"] == "request body too large"


def test_unknown_path_returns_json_404(api):
    """未知路径 → JSON 404（不是 Flask 默认 HTML）。"""
    status, text, _ = _request(api, "GET", "/definitely-not-a-route")
    assert status == 404
    body = json.loads(text)
    assert body["ok"] is False
    assert body["error"] == "not found"


def test_method_not_allowed_on_weixin(api):
    """GET /weixin → 405（该路由只接受 POST）。"""
    status, _text, _ = _request(api, "GET", "/weixin")
    assert status == 405


def test_console_page_is_served_with_new_frontend(api):
    """控制台页能取到，且新前端标记都在（主题三态/概览条/游标翻页/局部重绘）。"""
    status, text, _ = _request(api, "GET", "/console?token=" + TOKEN, token=None)
    assert status == 200
    for marker in ("theme-select", "ov-24h", "fetchMsgPage", "fetchAnaPage",
                  "patchSections", "wx_theme_v1", "aria-live"):
        assert marker in text, f"控制台页缺少标记：{marker}"


def test_console_manifest_and_service_worker(api):
    """PWA 的 manifest / service worker 走真实 HTTP 也能取到。"""
    status, text, _ = _request(api, "GET", "/console/manifest.json?token=" + TOKEN,
                                token=None)
    assert status == 200
    assert json.loads(text)

    status, text, _ = _request(api, "GET", "/sw.js?token=" + TOKEN, token=None)
    assert status == 200
    assert text.strip(), "service worker 不能是空文件"


# ------------------------------------------------------------------
# /admin/extract 后台抽取（真实 HTTP 端到端）
# ------------------------------------------------------------------


class _FakeExtractModule:
    """假的提取模块：run_extract(full=...) 按预设返回结果 / 抛预设异常。

    真实实现是 server/android_db_extract.py 与 server/pc_db_extract.py，
    它们会去读本机微信数据库。端到端用例里换成这个假模块：只验证
    「HTTP 契约 + 后台线程编排」，不碰任何真实数据与真实路径。
    """

    def __init__(self, result=None, error=None, gate=None):
        self.result = {"ok": True, "messages": 0} if result is None else result
        self.error = error
        self.gate = gate
        self.calls = []

    def run_extract(self, full=False):
        """与真实提取脚本同签名：只认 full 关键字参数。"""
        self.calls.append({"full": full})
        if self.gate is not None:
            # 并发用例用：把后台线程卡在这里，由测试决定何时放行
            self.gate.wait(timeout=10)
        if self.error is not None:
            raise self.error
        return self.result


def _fake_extract_importer(modules, calls):
    """构造假的 _import_extract_module：按 leg 返回对应的假模块。

    calls[leg] 记录该 leg 被加载了几次（source=all 时应是 android/pc 各 1）。
    """

    def _import(leg):
        calls[leg] = calls.get(leg, 0) + 1
        mod = modules.get(leg)
        if mod is None:
            raise ImportError("fake extract module not found: %s" % leg)
        return mod

    return _import


def _wait_extract_done(api, timeout=5.0, interval=0.05):
    """轮询 GET /admin/extract/status 直到 running 变 False，返回响应体。"""
    deadline = time.time() + timeout
    body = None
    while time.time() < deadline:
        status, text, _ = _request(api, "GET", "/admin/extract/status")
        assert status == 200, "状态页应返回 200，实际 %s：%s" % (status, text[:200])
        body = json.loads(text)
        if not body["running"]:
            return body
        time.sleep(interval)
    raise AssertionError("提取任务在 %.1f 秒内没有结束：%r" % (timeout, body))


def test_admin_extract_requires_token(api):
    """POST /admin/extract 缺令牌 → 401（真实 HTTP 链路下同样要拦住）。"""
    status, text, _ = _request(api, "POST", "/admin/extract", token=None)
    assert status == 401
    body = json.loads(text)
    assert body["ok"] is False
    assert body["error"] == "unauthorized"


def test_admin_extract_rejects_unknown_source(api):
    """source 只认 android / pc / all，非法值 → 400 且回告可选值。"""
    status, text, _ = _request(api, "POST", "/admin/extract?source=xx")
    assert status == 400
    body = json.loads(text)
    assert body["ok"] is False
    assert body["error"] == "source must be one of: android / pc / all"


def test_admin_extract_starts_background_and_reports_result(api, monkeypatch):
    """正常触发：200 + started，随后状态页给出最近一次的统计。"""
    fake = _FakeExtractModule(result={"ok": True, "messages": 3})
    calls = {}
    monkeypatch.setattr(app_module, "_import_extract_module",
                        _fake_extract_importer({"android": fake}, calls))

    status, text, _ = _request(api, "POST", "/admin/extract?source=android")
    assert status == 200
    body = json.loads(text)
    assert body["ok"] is True
    assert body["started"] is True
    assert body["full"] is False
    assert body["source"] == "android"

    state = _wait_extract_done(api)
    assert state["running"] is False
    last = state["last"]
    assert last["ok"] is True
    assert last["source"] == "android"
    assert last["android"]["messages"] == 3
    assert fake.calls == [{"full": False}]
    assert calls == {"android": 1}


def test_admin_extract_second_request_while_running_returns_409(api, monkeypatch):
    """任务在跑时再点一次 → 409 extract already running（不排队、不并发跑）。"""
    gate = threading.Event()
    fake = _FakeExtractModule(result={"ok": True, "messages": 1}, gate=gate)
    calls = {}
    monkeypatch.setattr(app_module, "_import_extract_module",
                        _fake_extract_importer({"android": fake}, calls))

    status, text, _ = _request(api, "POST", "/admin/extract?source=android")
    assert status == 200
    assert json.loads(text)["started"] is True

    # 后台线程被 gate 卡住，running 仍是 True
    status2, text2, _ = _request(api, "POST", "/admin/extract?source=android")
    assert status2 == 409
    body2 = json.loads(text2)
    assert body2["ok"] is False
    assert body2["error"] == "extract already running"

    gate.set()
    state = _wait_extract_done(api)
    assert state["running"] is False
    assert state["last"]["ok"] is True


def test_admin_extract_returns_503_when_main_db_is_busy(api, monkeypatch):
    """主库被占用 → 503 + Retry-After: 5，提取压根不启动。"""
    monkeypatch.setattr(app_module, "_probe_main_db_writable",
                        lambda timeout=0.5: ("busy", "database is locked"))
    status, text, headers = _request(api, "POST", "/admin/extract")
    assert status == 503
    assert headers.get("Retry-After") == "5"
    body = json.loads(text)
    assert body["ok"] is False
    assert body["error"] == "main database is busy, retry later"
    assert body["detail"] == "database is locked"

    # 没起后台任务：状态页仍然是「没在跑、没结果」
    state = json.loads(_request(api, "GET", "/admin/extract/status")[1])
    assert state["running"] is False
    assert state["last"] is None


def test_admin_extract_returns_500_when_main_db_not_writable(api, monkeypatch):
    """主库彻底不可写（不是忙）→ 500 + detail。"""
    monkeypatch.setattr(app_module, "_probe_main_db_writable",
                        lambda timeout=0.5: ("error", "boom"))
    status, text, _ = _request(api, "POST", "/admin/extract")
    assert status == 500
    body = json.loads(text)
    assert body["ok"] is False
    assert body["error"] == "main database is not writable"
    assert body["detail"] == "boom"


def test_admin_extract_source_pc_runs_only_pc_leg(api, monkeypatch):
    """source=pc：只跑 pc 一路；full=1 会原样透传给提取脚本。"""
    fake = _FakeExtractModule(result={"ok": True, "messages": 5})
    calls = {}
    monkeypatch.setattr(app_module, "_import_extract_module",
                        _fake_extract_importer({"pc": fake}, calls))

    status, text, _ = _request(api, "POST", "/admin/extract?source=pc&full=1")
    assert status == 200
    body = json.loads(text)
    assert body["source"] == "pc"
    assert body["full"] is True

    state = _wait_extract_done(api)
    last = state["last"]
    assert last["ok"] is True
    assert last["source"] == "pc"
    assert last["pc"]["messages"] == 5
    assert "android" not in last
    assert fake.calls == [{"full": True}]
    assert calls == {"pc": 1}


def test_admin_extract_source_all_runs_both_legs(api, monkeypatch):
    """source=all：android 与 pc 各跑一路，last 里两条腿都在。"""
    android_fake = _FakeExtractModule(result={"ok": True, "messages": 2})
    pc_fake = _FakeExtractModule(result={"ok": True, "messages": 4})
    calls = {}
    monkeypatch.setattr(app_module, "_import_extract_module",
                        _fake_extract_importer({"android": android_fake,
                                                "pc": pc_fake}, calls))

    status, text, _ = _request(api, "POST", "/admin/extract?source=all")
    assert status == 200
    assert json.loads(text)["source"] == "all"

    state = _wait_extract_done(api)
    last = state["last"]
    assert last["ok"] is True
    assert last["source"] == "all"
    assert last["android"]["messages"] == 2
    assert last["pc"]["messages"] == 4
    assert calls == {"android": 1, "pc": 1}


def test_admin_extract_one_leg_failure_keeps_other_leg_ok(api, monkeypatch):
    """一路失败不拖垮另一路：整体 ok=True，失败那路带 error。"""
    android_fake = _FakeExtractModule(error=RuntimeError("android leg failed"))
    pc_fake = _FakeExtractModule(result={"ok": True, "messages": 6})
    calls = {}
    monkeypatch.setattr(app_module, "_import_extract_module",
                        _fake_extract_importer({"android": android_fake,
                                                "pc": pc_fake}, calls))

    status, _text, _ = _request(api, "POST", "/admin/extract?source=all")
    assert status == 200

    state = _wait_extract_done(api)
    last = state["last"]
    assert last["ok"] is True
    assert last["android"]["ok"] is False
    assert last["android"]["error"] == "android leg failed"
    assert last["pc"]["ok"] is True
    assert last["pc"]["messages"] == 6


def test_admin_extract_sqlite_operational_error_is_marked_busy(api, monkeypatch):
    """提取脚本报 sqlite3.OperationalError → error_kind=busy（提示稍后重试）。"""
    android_fake = _FakeExtractModule(
        error=sqlite3.OperationalError("database is locked"))
    pc_fake = _FakeExtractModule(result={"ok": True, "messages": 1})
    calls = {}
    monkeypatch.setattr(app_module, "_import_extract_module",
                        _fake_extract_importer({"android": android_fake,
                                                "pc": pc_fake}, calls))

    status, _text, _ = _request(api, "POST", "/admin/extract?source=all")
    assert status == 200

    state = _wait_extract_done(api)
    last = state["last"]
    assert last["android"]["ok"] is False
    assert last["android"]["error_kind"] == "busy"
    assert last["ok"] is True, "pc 那路成功，整体仍算成功"
    assert last["pc"]["ok"] is True


def test_admin_extract_status_requires_token(api):
    """状态页同样要令牌：缺令牌 → 401。"""
    status, text, _ = _request(api, "GET", "/admin/extract/status", token=None)
    assert status == 401
    assert json.loads(text)["error"] == "unauthorized"


def test_admin_extract_status_reports_running_and_last(api):
    """状态页返回 ok / running / last 三个键。"""
    status, text, _ = _request(api, "GET", "/admin/extract/status")
    assert status == 200
    body = json.loads(text)
    assert body["ok"] is True
    assert "running" in body
    assert "last" in body
    assert body["running"] is False
    assert body["last"] is None