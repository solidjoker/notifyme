# -*- coding: utf-8 -*-
"""控制台「归类」接线回归测试（server/console_ui.py + server/app.py + console_classify.js）。

与 server/test_console_classify.py 分工：
- test_console_classify.py 测「规则本身对不对」（node 跑真逻辑）；
- 本文件测「规则有没有正确接进控制台页面」：
  1. console_ui.py 里占位标记只出现一次（多处就得改多处，漏一处就是半套规则）；
  2. app.py 在启动时把 console_classify.js 内联进 HTML，页面里不再残留标记；
  3. 内联进去的内容与源文件逐字节一致（单一事实源不能走样）；
  4. 源文件读不到时降级为空串、不抛异常（服务照常起，前端退化成平铺渲染）；
  5. 前端取规则用「window.ConsoleClassify || null」优雅降级，且真的用上了规则层
     （会话按来源 App 分区 / 日期组内按发送者再分组 / 待办按重要度分区 / 建议按类型分组）。

这些用例只读文件与模块，不起服务、不访问网络。
"""
import importlib.util
import os
import re

import pytest

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SERVER = os.path.join(ROOT, "server")
UI_PATH = os.path.join(SERVER, "console_ui.py")
APP_PATH = os.path.join(SERVER, "app.py")
JS_PATH = os.path.join(SERVER, "console_classify.js")
MARKER = "/*__CONSOLE_CLASSIFY_JS__*/"


def _read(path):
    with open(path, "r", encoding="utf-8") as fh:
        return fh.read()


def _ui_source():
    if not os.path.exists(UI_PATH):
        pytest.skip("server/console_ui.py 尚未落地")
    return _read(UI_PATH)


def _app_module():
    """按路径加载 server/app.py（与生产一致的 importlib 加载方式）。"""
    spec = importlib.util.spec_from_file_location("notifyme_app_wiring_test", APP_PATH)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


# --------------------------------------------------------------------------
# 占位标记
# --------------------------------------------------------------------------


def test_marker_appears_exactly_once_in_console_ui():
    """console_ui.py 里占位标记只出现一次：多处就得改多处，漏一处就是半套规则。"""
    src = _ui_source()
    assert src.count(MARKER) == 1, (
        "占位标记应只出现 1 次，实际 %d 次" % src.count(MARKER)
    )


def test_marker_is_inside_a_script_tag():
    """标记必须放在 <script> 标签里，否则注入后不是可执行代码。"""
    src = _ui_source()
    i = src.find(MARKER)
    assert i >= 0, "找不到占位标记"
    before = src[:i]
    assert before.rfind("<script") > before.rfind("</script>"), (
        "占位标记不在 script 块内"
    )


# --------------------------------------------------------------------------
# app.py 的注入
# --------------------------------------------------------------------------


def test_app_module_exposes_ready_html():
    """app.py 暴露 CONSOLE_HTML_READY，且与原始 CONSOLE_HTML 不同（说明真的注入过）。"""
    app = _app_module()
    assert hasattr(app, "CONSOLE_HTML_READY"), "app.py 缺少 CONSOLE_HTML_READY"
    assert hasattr(app, "CONSOLE_CLASSIFY_MARKER"), "app.py 缺少 CONSOLE_CLASSIFY_MARKER"
    assert app.CONSOLE_CLASSIFY_MARKER == MARKER, (
        "app.py 的标记常量与 console_ui.py 里的字面量不一致"
    )
    assert app.CONSOLE_HTML_READY != app.CONSOLE_HTML, (
        "CONSOLE_HTML_READY 与 CONSOLE_HTML 相同，说明规则没注进去"
    )


def test_ready_html_has_no_leftover_marker():
    """注入后的 HTML 里不能再有标记残留。"""
    app = _app_module()
    assert MARKER not in app.CONSOLE_HTML_READY, "注入后仍有占位标记残留"


def test_inlined_js_matches_source_file():
    """内联进去的 JS 与 console_classify.js 源文件逐字节一致（单一事实源不能走样）。"""
    app = _app_module()
    js = _read(JS_PATH)
    assert js.strip(), "console_classify.js 是空的"
    assert js in app.CONSOLE_HTML_READY, (
        "注入的 JS 与源文件不一致：源文件 %d 字符，页面里找不到原文" % len(js)
    )


def test_console_page_serves_injected_html():
    """console_page() 返回的是注入后的 HTML（页面能拿到规则）。"""
    app = _app_module()
    with app.app.test_request_context("/console"):
        resp = app.console_page()
    body = resp.get_data(as_text=True)
    assert "ConsoleClassify" in body, "console_page 返回的页面里没有归类规则"
    assert MARKER not in body, "console_page 返回的页面里还有占位标记"


def test_missing_js_degrades_without_raising(tmp_path, monkeypatch):
    """源文件读不到时降级成空串 + 告警，不抛异常打断服务启动。"""
    app = _app_module()
    monkeypatch.setattr(
        app, "BASE_DIR", str(tmp_path)
    )  # 指向一个没有 console_classify.js 的空目录
    assert app._read_console_classify_js() == "", (
        "读不到源文件时应返回空串降级"
    )


# --------------------------------------------------------------------------
# 前端接线
# --------------------------------------------------------------------------


def test_frontend_grabs_rules_with_graceful_fallback():
    """前端用 window.ConsoleClassify || null 取规则，取不到时不报错。"""
    src = _ui_source()
    assert re.search(r"var\s+CC\s*=\s*window\.ConsoleClassify\s*\|\|\s*null", src), (
        "前端取规则的写法应是 var CC = window.ConsoleClassify || null"
    )


def test_frontend_uses_rule_layer_for_all_six_dimensions():
    """六个归类维度都要走规则层，不能只在一处用、其余仍各写一份。"""
    src = _ui_source()
    for api in (
        "groupByPkg",      # 来源 App
        "groupBySender",   # 发送者
        "groupTodosByImportance",  # 重要度
        "groupSuggestions",        # 建议类型
        "normPkg",         # 来源 App 归一
        "ckOf",            # 会话复合身份
    ):
        assert api in src, "前端没有用到规则层的 %s" % api
        assert "CC.%s" % api in src, "前端没有通过规则层调用 %s" % api


def test_frontend_has_sender_group_head():
    """会话卡内的发送者分组头存在（原生 button + 事件委托 + aria）。"""
    src = _ui_source()
    assert "sender-head" in src, "缺少 .sender-head 样式"
    assert "sender-name" in src, "缺少 .sender-name 样式"
    assert "sender-body" in src, "缺少 .sender-body 容器样式"
    assert 'data-action="toggle"' in src, "发送者分组头必须复用事件委托的 toggle"


def test_frontend_has_pkg_section_head():
    """会话按来源 App 二次分区的分区头存在。"""
    src = _ui_source()
    assert "sec-head sub" in src, "缺少 .sec-head.sub 分区头样式"


def test_frontend_keeps_flat_fallback_paths():
    """每个规则调用点都有 null 降级分支（CC 为空时退回改造前的平铺渲染）。"""
    src = _ui_source()
    for fn in (
        "groupConvsByPkg",
        "groupMsgsBySender",
        "groupTodosByImportance",
    ):
        assert "function %s" % fn in src, "缺少前端封装函数 %s" % fn
    # 三个封装都要先判 CC 为空返回 null
    for fn in ("groupConvsByPkg", "groupMsgsBySender", "groupTodosByImportance"):
        m = re.search(r"function %s\([^)]*\)\s*\{(.*?)\n\}" % fn, src, re.S)
        assert m, "找不到 %s 的定义" % fn
        body = m.group(1)
        assert "!CC" in body, "%s 缺少 CC 为空时的降级分支" % fn
