"""控制台前端资源（server/console_ui.py）与控制台路由的回归测试。

设计原则：
- console_ui.py 必须是「纯常量模块」——不许 import、不许有副作用，
  否则 WSGI 里按路径加载会出问题，也没法独立静态检查；
- 下面的结构性断言不依赖具体文案，只卡住真正的坏味道
  （引入外部资源、SW 缓存数据接口、控制台被鉴权挡住、图标缺失等）。
"""
import ast
import json
import os

import pytest

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
UI_PATH = os.path.join(ROOT, "server", "console_ui.py")


def _load_ui():
    if not os.path.exists(UI_PATH):
        pytest.skip("server/console_ui.py 尚未落地")
    import importlib.util

    spec = importlib.util.spec_from_file_location("notifyme_console_ui_t", UI_PATH)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def _ui_source():
    if not os.path.exists(UI_PATH):
        pytest.skip("server/console_ui.py 尚未落地")
    with open(UI_PATH, "r", encoding="utf-8") as fh:
        return fh.read()


def test_console_ui_module_has_no_imports():
    """纯常量模块：任何 import 都是红旗（会引入依赖/副作用，破坏按路径加载）。"""
    tree = ast.parse(_ui_source())
    bad = [n for n in tree.body if isinstance(n, (ast.Import, ast.ImportFrom))]
    assert not bad, "console_ui.py 不允许有 import: %s" % [type(n).__name__ for n in bad]


def test_console_ui_module_has_no_side_effects():
    """模块级只允许赋值/常量定义，不允许调用函数（import 即执行）。"""
    tree = ast.parse(_ui_source())
    allowed = (ast.Assign, ast.AnnAssign, ast.Expr)
    offenders = []
    for node in tree.body:
        if isinstance(node, ast.Expr):
            # 只允许 docstring
            if isinstance(node.value, ast.Constant):
                continue
            offenders.append(ast.dump(node)[:120])
            continue
        if not isinstance(node, allowed):
            offenders.append(ast.dump(node)[:120])
    assert not offenders, "console_ui.py 模块级存在非赋值语句: %s" % offenders


def test_console_ui_exposes_expected_constants():
    mod = _load_ui()
    assert isinstance(mod.CONSOLE_HTML, str) and len(mod.CONSOLE_HTML) > 500
    assert "<!DOCTYPE html>" in mod.CONSOLE_HTML
    assert "</html>" in mod.CONSOLE_HTML
    assert isinstance(mod.CONSOLE_MANIFEST, dict)
    assert isinstance(mod.CONSOLE_SW, str) and len(mod.CONSOLE_SW) > 100


def test_console_manifest_is_json_serializable():
    mod = _load_ui()
    text = json.dumps(mod.CONSOLE_MANIFEST)
    assert "icons" in mod.CONSOLE_MANIFEST
    assert mod.CONSOLE_MANIFEST["icons"], "manifest 必须带图标，否则 PWA 不可安装"
    assert "start_url" in mod.CONSOLE_MANIFEST


def test_console_html_has_no_third_party_resources():
    """控制台不得引用任何外部 CDN/字体/脚本（本地优先 + 隐私）。"""
    html = _load_ui().CONSOLE_HTML
    for needle in ("src=\"http://", "src='http://", "src=\"https://", "src='https://",
                   "href=\"http://", "href='http://", "href=\"https://", "href='https://"):
        assert needle not in html, "控制台引用了外部资源: %s" % needle


def test_console_sw_cache_name_is_stable():
    """缓存名变更会让旧 SW 残留缓存，测试与文档都依赖这个名字。"""
    sw = _load_ui().CONSOLE_SW
    assert "wx-console-v5" in sw


def test_console_sw_does_not_cache_data_api():
    """SW 不得缓存数据接口（/messages /analysis /advisor）或落地页正文。"""
    sw = _load_ui().CONSOLE_SW
    # 缓存白名单只允许 /console 外壳
    assert "/console" in sw
    # 不允许把数据接口整段预缓存
    for api in ("/messages", "/analysis", "/advisor"):
        assert ("addAll([%s" % api) not in sw
        assert ("cache.add(%s" % api) not in sw


def test_console_route_is_served_without_token():
    """控制台外壳不含用户数据，必须免鉴权（否则 PWA 主屏启动就是一屏 401）。"""
    if not os.path.exists(UI_PATH):
        pytest.skip("server/console_ui.py 尚未落地")
    import importlib.util

    spec = importlib.util.spec_from_file_location(
        "notifyme_app_t", os.path.join(ROOT, "server", "app.py"))
    app_mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(app_mod)

    app_mod.TOKEN = ""
    app_mod.ALLOW_NO_AUTH = ""
    app_mod._db_ready = True
    client = app_mod.app.test_client()

    resp = client.get("/console")
    assert resp.status_code == 200
    assert b"<!DOCTYPE html>" in resp.data

    resp = client.get("/console/manifest.json")
    assert resp.status_code == 200
    assert resp.get_json()["icons"]

    resp = client.get("/sw.js")
    assert resp.status_code == 200
    assert b"wx-console-v5" in resp.data


def test_console_assets_are_not_cached_with_user_data():
    """外壳响应头必须禁止共享缓存（避免把带 token 的页面缓存给其他人）。"""
    if not os.path.exists(UI_PATH):
        pytest.skip("server/console_ui.py 尚未落地")
    import importlib.util

    spec = importlib.util.spec_from_file_location(
        "notifyme_app_t2", os.path.join(ROOT, "server", "app.py"))
    app_mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(app_mod)

    app_mod.TOKEN = ""
    app_mod.ALLOW_NO_AUTH = ""
    app_mod._db_ready = True
    client = app_mod.app.test_client()

    resp = client.get("/console")
    cache_control = resp.headers.get("Cache-Control", "")
    assert "no-cache" in cache_control or "no-store" in cache_control

# ---------------------------------------------------------------------------
# Top13 #2：SW 缓存策略（网络优先 + 只缓存控制台壳）
# ---------------------------------------------------------------------------

def test_console_sw_navigation_is_network_first_for_shell_only():
    """Top13 #2：导航请求网络优先，且只接管控制台壳（落地页不落缓存）。"""
    sw = _load_ui().CONSOLE_SW
    assert "e.request.mode === 'navigate'" in sw
    nav = sw[sw.index("e.request.mode === 'navigate'"):]
    # 网络优先：先 fetch，失败才回缓存
    assert nav.index("fetch(e.request)") < nav.index("matchShell(url.pathname)"), (
        "控制台壳导航必须是网络优先（离线才回缓存）")
    # 非控制台壳不接管导航 -> 落地页 HTML 不会被写进 Cache Storage
    assert "if (!isConsoleShell(url.pathname)) return;" in nav
    # 缓存写入发生在壳判断之后
    assert nav.index("isConsoleShell(url.pathname)") < nav.index("c.put(url.pathname, copy)")


def test_console_sw_only_caches_console_shell():
    """Top13 #2：缓存白名单只允许 /console 外壳，不含落地页与数据接口。"""
    sw = _load_ui().CONSOLE_SW
    assert "/console" in sw
    for api in ("/messages", "/analysis", "/advisor", "/weixin", "/admin"):
        assert api in sw, "SW 应显式放行数据接口 %s" % api
    puts = [line for line in sw.splitlines() if ".put(" in line]
    assert puts, "SW 应当有缓存写入逻辑"
    for line in puts:
        # 只允许两种缓存键：控制台壳路径（url.pathname）与 /console/ 静态资源（e.request）
        assert "url.pathname" in line or "e.request" in line, \
            "缓存键只能是控制台壳路径或 /console/ 静态资源: %s" % line.strip()
    assert not any(line.strip().startswith("cache.put('/'") for line in puts), \
        "落地页 / 不得进缓存"


def test_console_sw_respects_no_store_cache_control():
    """Top13 #2：带 no-store / private 的响应不得进缓存。"""
    sw = _load_ui().CONSOLE_SW
    assert "no-store" in sw
    assert "private" in sw


# ---------------------------------------------------------------------------
# Top13 #6 / #8：顾问渲染转义 + api() 判 r.ok
# ---------------------------------------------------------------------------

def test_advisor_render_escapes_server_data():
    """Top13 #6：顾问报告的服务端字段进 innerHTML 前必须过 esc()（存储型 XSS）。"""
    html = _load_ui().CONSOLE_HTML
    start = html.index("function renderAdvisor()")
    # 只取函数体（内层的 function (r) { 不在行首，不能用 "function " 定位结束）
    end = html.find("\n// ----------", start + 40)
    block = html[start:end if end > 0 else len(html)]
    for field in ("r.created_at", "r.model_used", "r.summary", "s.title", "s.detail"):
        assert field in block, "顾问渲染缺少字段 %s" % field
        raw = "' + %s + '" % field
        assert raw not in block, "%s 未转义就拼进 HTML" % field
    assert "esc(r.created_at || '')" in block
    assert "esc(r.model_used || '—')" in block
    assert "esc(r.summary)" in block
    assert "esc(s.title || '')" in block
    assert "esc(s.detail)" in block


def test_console_api_rejects_non_2xx():
    """Top13 #8：api() 必须判 r.ok（否则 500 的 HTML 错误页会让 json() 抛错）。"""
    html = _load_ui().CONSOLE_HTML
    assert "if (!r.ok)" in html
    api_start = html.index("async function api(")
    api_block = html[api_start:api_start + 1600]
    assert "if (!r.ok)" in api_block


def test_console_refresh_does_not_rebuild_auth_box():
    """Top13 #8：30s 自动刷新不应把令牌输入框整块重建（会吃掉用户输入）。"""
    html = _load_ui().CONSOLE_HTML
    if "setInterval" in html:
        idx = html.index("setInterval")
        window = html[idx:idx + 400]
        assert "renderAuth(" not in window, "定时刷新里不应重绘鉴权框"

