# -*- coding: utf-8 -*-
"""控制台前端（server/console_ui.py）的设计约束回归测试。

对应评审文档 docs/review-frontend-backend-2026-11.md §10.4 的 2-1 ~ 2-6 六项：

- 2-1 暗色与主题：三态切换（跟随系统/浅色/深色）+ localStorage 持久化
  + 首次访问读 prefers-color-scheme 兜底 + 颜色只落在 :root / [data-theme="dark"]
  两个变量块里 + theme-color meta 随主题更新；
- 2-2 首屏与骨架屏：概览条（近 24 小时 / 待处理 / 失败 / 最近同步）、tab 角标、骨架屏；
- 2-3 前端数据层：真正按 next_before 游标翻页，并有「没有更多」状态；
- 2-4 重绘治理：分区打补丁 + 事件委托，不再整页 innerHTML；
- 2-5 可访问性：原生控件 / aria-* / 焦点环 / 44px 热区 / 正文对比度 >= 4.5:1；
- 2-6 响应式：窄屏单列、宽屏多列、clamp()，且不引外部 CSS/JS（离线可用）。

这些用例只读文件内容做结构断言，不起服务、不访问网络。
"""
import ast
import os
import re

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
UI_PATH = os.path.join(ROOT, "server", "console_ui.py")

HEX_RE = re.compile(r"#[0-9a-fA-F]{3,8}(?![0-9a-zA-Z_-])")
COLOR_FUNC_RE = re.compile(r"(?:rgba?|hsla?)\s*\(", re.IGNORECASE)


def _source():
    if not os.path.exists(UI_PATH):
        import pytest

        pytest.skip("server/console_ui.py 尚未落地")
    with open(UI_PATH, "r", encoding="utf-8") as fh:
        return fh.read()


def _ui():
    import importlib.util

    spec = importlib.util.spec_from_file_location("notifyme_console_ui_design_test", UI_PATH)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def _block(text, needle):
    """取 text 中以 needle 开头的第一个 CSS 块（含配对花括号）的内部文本。"""
    i = text.find(needle)
    assert i >= 0, "找不到 CSS 块：%s" % needle
    j = text.find("{", i)
    assert j >= 0, "CSS 块缺少花括号：%s" % needle
    depth = 0
    p = j
    while p < len(text):
        if text[p] == "{":
            depth += 1
        elif text[p] == "}":
            depth -= 1
            if depth == 0:
                break
        p += 1
    return text[j + 1:p]


def _strip_block(text, needle):
    """把 text 里所有以 needle 开头的 CSS 块抠掉，返回剩余文本。"""
    chunks = []
    i = 0
    while True:
        j = text.find(needle, i)
        if j < 0:
            chunks.append(text[i:])
            break
        k = text.find("{", j)
        if k < 0:
            chunks.append(text[i:])
            break
        depth = 0
        p = k
        while p < len(text):
            if text[p] == "{":
                depth += 1
            elif text[p] == "}":
                depth -= 1
                if depth == 0:
                    break
            p += 1
        chunks.append(text[i:j])
        i = p + 1
    return "".join(chunks)


def _css(html):
    m = re.search(r"<style>(.*?)</style>", html, re.S)
    assert m, "控制台 HTML 必须内联样式"
    return m.group(1)


def _vars(css, selector):
    """取某个选择器块里的 --变量表。"""
    body = _block(css, selector)
    out = {}
    for m in re.finditer(r"(--[a-z0-9-]+)\s*:\s*([^;]+);", body):
        out[m.group(1)] = m.group(2).strip()
    return out


def _lum(color):
    h = color.strip().lstrip("#")
    if len(h) == 3:
        h = "".join(c * 2 for c in h)
    parts = [int(h[i:i + 2], 16) / 255.0 for i in (0, 2, 4)]

    def ch(v):
        return v / 12.92 if v <= 0.03928 else ((v + 0.055) / 1.055) ** 2.4

    return 0.2126 * ch(parts[0]) + 0.7152 * ch(parts[1]) + 0.0722 * ch(parts[2])


def _ratio(fg, bg):
    a, b = _lum(fg), _lum(bg)
    hi, lo = max(a, b), min(a, b)
    return (hi + 0.05) / (lo + 0.05)


# ---------------------------------------------------------------- 2-1 主题

def test_console_ui_stays_a_pure_constant_module():
    """纯常量模块：不许 import / def / class / 顶层副作用。"""
    tree = ast.parse(_source())
    bad = []
    for node in tree.body:
        if isinstance(node, (ast.Import, ast.ImportFrom, ast.FunctionDef,
                             ast.AsyncFunctionDef, ast.ClassDef)):
            bad.append(type(node).__name__)
        elif isinstance(node, ast.Expr) and not isinstance(node.value, ast.Constant):
            bad.append("Expr:" + type(node.value).__name__)
        elif not isinstance(node, (ast.Assign, ast.AnnAssign, ast.Expr)):
            bad.append(type(node).__name__)
    assert not bad, "console_ui.py 必须保持纯常量模块，发现：%s" % bad


def test_console_ui_module_imports_and_exposes_three_constants():
    mod = _ui()
    assert isinstance(mod.CONSOLE_HTML, str) and len(mod.CONSOLE_HTML) > 5000
    assert isinstance(mod.CONSOLE_SW, str) and len(mod.CONSOLE_SW) > 100
    assert isinstance(mod.CONSOLE_MANIFEST, dict)


def test_theme_has_three_states_and_a_switcher():
    html = _ui().CONSOLE_HTML
    assert 'id="theme-select"' in html
    for opt in ('value="auto"', 'value="light"', 'value="dark"'):
        assert opt in html, "主题三态缺少 %s" % opt
    assert "跟随系统" in html and "浅色" in html and "深色" in html
    assert "function initThemeControl()" in html
    assert "window.__WX_THEME" in html
    assert "__WX_THEME.set(" in html or ".set(v)" in html


def test_theme_preference_is_persisted_and_falls_back_to_system():
    html = _ui().CONSOLE_HTML
    assert "wx_theme_v1" in html, "主题偏好必须用固定 localStorage 键持久化"
    assert "localStorage.getItem(KEY)" in html
    assert "localStorage.setItem(KEY" in html
    assert "matchMedia('(prefers-color-scheme: dark)')" in html, "首访必须读系统主题兜底"
    assert "addEventListener('change'" in html, "系统主题变化后要能自动跟随"


def test_theme_color_meta_follows_the_active_theme():
    html = _ui().CONSOLE_HTML
    assert 'id="meta-theme-color"' in html
    assert 'name="theme-color"' in html
    assert "getPropertyValue('--theme-color')" in html, "meta 主题色应取自 CSS 变量"
    css = _css(html)
    light = _vars(css, ":root")
    m = re.search(r'<meta[^>]*name="theme-color"[^>]*content="([^"]+)"', html)
    assert m, "缺少 theme-color meta"
    assert m.group(1).lower() == light["--theme-color"].lower(), (
        "meta 初始主题色应与 :root --theme-color 一致")


def test_colors_only_live_in_the_two_variable_blocks():
    """组件样式不得复制第二套颜色：颜色只允许出现在 :root 与 [data-theme="dark"]。"""
    css = _css(_ui().CONSOLE_HTML)
    rest = _strip_block(_strip_block(css, ":root"), '[data-theme="dark"]')
    hexes = HEX_RE.findall(rest)
    assert not hexes, "颜色只允许出现在 :root / [data-theme=dark] 变量块，泄漏：%s" % hexes
    funcs = COLOR_FUNC_RE.findall(rest)
    assert not funcs, "组件样式不得再写死 rgba()/hsl() 颜色"


def test_dark_theme_block_defines_every_color_token():
    css = _css(_ui().CONSOLE_HTML)
    light = _vars(css, ":root")
    dark = _vars(css, '[data-theme="dark"]')
    color_tokens = [k for k, v in light.items() if "#" in v or "rgba(" in v]
    assert len(color_tokens) >= 25, "浅色令牌太少：%s" % color_tokens
    missing = [k for k in color_tokens if k not in dark]
    assert not missing, "深色块缺少令牌：%s" % missing


def test_service_worker_offline_page_also_uses_tokens():
    sw = _ui().CONSOLE_SW
    assert ":root{--bg:" in sw, "离线兜底页也应走 CSS 变量"
    assert "var(--bg)" in sw
    assert "background:#f2f4f5" not in sw, "离线页不得再写死组件颜色"


# ------------------------------------------------------------ 2-2 首屏骨架

def test_overview_bar_exposes_four_metrics():
    html = _ui().CONSOLE_HTML
    assert 'id="overview"' in html
    for oid in ("ov-24h", "ov-todo", "ov-fail", "ov-sync"):
        assert 'id="%s"' % oid in html, "概览条缺少 %s" % oid
    for label in ("近 24 小时消息", "待处理", "同步失败", "最近同步"):
        assert label in html
    assert 'aria-busy="true"' in html


def test_skeleton_screen_replaces_blank_loading_text():
    html = _ui().CONSOLE_HTML
    assert "function skeletonHtml(" in html
    assert "sk-card" in html and "sk-line" in html and "sk-shine" in html
    assert "pane.innerHTML = skeletonHtml(4)" in html, "首屏应先渲染骨架屏"
    assert 'class="sk-line w60" aria-hidden="true"' in html


def test_tab_badges_are_rendered_from_data():
    html = _ui().CONSOLE_HTML
    for bid in ("badge-conv", "badge-todo", "badge-stat", "badge-adv"):
        assert 'id="%s"' % bid in html
    assert "function setBadge(" in html
    assert "function renderBadges(" in html
    assert "aria-label" in html


# ------------------------------------------------------- 2-3 游标分页数据层

def test_frontend_paginates_by_next_before_cursor():
    html = _ui().CONSOLE_HTML
    assert "next_before" in html
    assert "async function fetchMsgPage(" in html
    assert "async function fetchAnaPage(" in html
    assert "encodeURIComponent(before)" in html
    assert "&before=" in html
    assert "MSG_NEXT" in html and "ANA_NEXT" in html
    assert "PAGE_LIMIT" in html


def test_frontend_no_longer_fetches_everything_at_once():
    html = _ui().CONSOLE_HTML
    assert "fetchAllMessages" not in html, "不能再一次性拉全量再前端切片"
    # 消息 / 分析都必须走游标分页，不能一次性拉大页（PAGE_LIMIT 是唯一的页大小）
    assert "messages?limit=500" not in html
    assert "analysis?limit=500" not in html
    assert "limit=PAGE_LIMIT" not in html
    assert "guard < 100" not in html


def test_load_more_button_and_end_of_list_state():
    html = _ui().CONSOLE_HTML
    assert "async function loadMore(" in html
    assert 'data-action="more"' in html
    assert "data-kind=" in html
    assert "没有更多了" in html, "必须给出「没有更多」状态"
    assert "还有更多" in html
    assert "MORE_BUSY" in html


# ----------------------------------------------------------- 2-4 重绘治理

def test_rendering_patches_sections_instead_of_rewriting_the_page():
    html = _ui().CONSOLE_HTML
    assert "function patchSections(" in html
    assert "createDocumentFragment" in html
    assert "insertBefore" in html
    assert "$('data').innerHTML =" not in html, "不能再整页替换 #data"
    assert "el.__html !== sec.html" in html, "内容没变就不该动 DOM"


def test_dynamic_buttons_use_event_delegation():
    html = _ui().CONSOLE_HTML
    assert 'role="button"' not in html, "不要用 span role=button 冒充按钮"
    assert "function bindDelegation()" in html
    assert "function handleAction(" in html
    assert 'data-action="toggle"' in html
    assert 'data-action="copy"' in html
    assert "onFilterKey" not in html


def test_filter_chips_are_native_buttons():
    html = _ui().CONSOLE_HTML
    assert 'class="chip-btn" aria-pressed="' in html
    assert "data-pkg=" in html
    assert "addEventListener('click'" in html


# ------------------------------------------------------------- 2-5 可访问性

def test_aria_attributes_cover_tabs_panels_and_live_regions():
    html = _ui().CONSOLE_HTML
    for needle in ('role="tablist"', 'role="tab"', 'role="tabpanel"',
                   'aria-selected="true"', 'aria-selected="false"',
                   'aria-live="polite"', 'aria-relevant=', 'aria-busy=',
                   'aria-expanded=', 'aria-pressed=', 'aria-controls=',
                   'aria-label='):
        assert needle in html, "缺少 %s" % needle


def test_interactive_targets_are_at_least_44px():
    css = _css(_ui().CONSOLE_HTML)
    assert "--tap: 44px" in css
    targets = [
        (".btn {", "--tap"),
        (".btn-ghost {", "--tap"),
        (".chip-btn {", "--tap"),
        (".icon-btn {", "--tap"),
        (".theme-select {", "--tap"),
        (".search-row input {", "--tap"),
        (".conv-head {", "--tap"),
        (".date-head {", "--tap"),
        (".tabbar button {", "min-height"),
        (".ov-item {", "min-height"),
    ]
    for selector, needle in targets:
        assert selector in css, "缺少样式 %s" % selector
        assert needle in _block(css, selector), "%s 未设置 %s 命中区" % (selector, needle)


def test_focus_ring_is_visible():
    css = _css(_ui().CONSOLE_HTML)
    assert ":focus-visible" in css
    assert "outline: 3px solid var(--focus)" in css
    assert "--focus:" in css


def test_text_contrast_meets_4_5_to_1_in_both_themes():
    css = _css(_ui().CONSOLE_HTML)
    light = _vars(css, ":root")
    dark = _vars(css, '[data-theme="dark"]')
    light_pairs = [
        ("--text", "--bg"), ("--text", "--surface"), ("--text", "--surface-2"),
        ("--muted", "--surface"), ("--muted", "--surface-2"),
        ("--accent-text", "--surface"), ("--accent-text", "--bg"),
        ("--on-accent", "--accent-strong"), ("--on-danger", "--danger"),
        ("--info", "--surface"), ("--warn", "--warn-weak"),
        ("--danger", "--danger-weak"), ("--on-badge", "--badge-db"),
        ("--on-badge", "--badge-nt"), ("--on-badge", "--badge-grp"),
        ("--on-badge", "--badge-app"),
    ]
    for fg, bg in light_pairs:
        ratio = _ratio(light[fg], light[bg])
        assert ratio >= 4.5, "浅色 %s/%s 对比度 %.2f < 4.5" % (fg, bg, ratio)
    dark_pairs = [
        ("--text", "--bg"), ("--text", "--surface"), ("--text", "--surface-2"),
        ("--muted", "--surface"), ("--muted", "--surface-2"),
        ("--accent-text", "--surface"), ("--accent-text", "--surface-2"),
        ("--on-accent", "--accent-strong"), ("--on-danger", "--danger"),
        ("--info", "--surface"), ("--warn", "--warn-weak"),
        ("--danger", "--danger-weak"), ("--on-badge", "--badge-db"),
    ]
    for fg, bg in dark_pairs:
        ratio = _ratio(dark[fg], dark[bg])
        assert ratio >= 4.5, "深色 %s/%s 对比度 %.2f < 4.5" % (fg, bg, ratio)


# --------------------------------------------------------------- 2-6 响应式

def test_responsive_breakpoints_and_fluid_sizing():
    css = _css(_ui().CONSOLE_HTML)
    assert "@media (max-width: 600px)" in css
    assert "@media (min-width: 900px)" in css
    assert "@media (min-width: 1280px)" in css
    assert css.count("clamp(") >= 5, "字号/间距应使用 clamp() 做流体排版"
    narrow = _block(css, "@media (max-width: 600px)")
    assert "#pane" in narrow and "grid-template-columns: 1fr" in narrow, "窄屏必须单列"
    assert ".ov-grid { grid-template-columns: 1fr; }" in narrow
    wide = _block(css, "@media (min-width: 900px)")
    assert "repeat(auto-fill" in wide, "宽屏结果区应自动多列"
    assert "#pane" in wide


def test_console_has_no_third_party_resources():
    mod = _ui()
    for blob in (mod.CONSOLE_HTML, mod.CONSOLE_SW):
        for needle in ('src="http', "src='http", 'href="http', "href='http",
                       "@import", "fonts.googleapis", "cdn.jsdelivr", "unpkg.com"):
            assert needle not in blob, "控制台引用了外部资源：%s" % needle


# ------------------------------------------------- 2-5 / 2-6 运行时细节（浏览器实测回归）

def test_tabs_use_roving_tabindex_from_the_first_paint():
    """首屏就要有 roving tabindex：选中 tab 可 Tab 进入，其余 -1。"""
    html = _ui().CONSOLE_HTML
    assert "function syncTabs(t)" in html
    assert "setAttribute('tabindex', on ? '0' : '-1')" in html
    assert "syncTabs(CUR_TAB);" in html, "启动时必须同步一次 tab 状态"
    assert "syncTabs(t);" in html, "切换 tab 时必须同步 tab 状态"


def test_wide_screen_main_fills_its_grid_column():
    """宽屏 main 不能被 margin:0 auto 收缩成 fit-content，否则结果区永远单列。"""
    css = _css(_ui().CONSOLE_HTML)
    wide = _block(css, "@media (min-width: 900px)")
    assert "main { max-width: none; margin: 0; width: 100%; }" in wide, (
        "宽屏 main 必须撑满第二栏")


# ------------------------------------------- 洞察层：/stats + /suggests + 脱敏证据 + skipped

def test_stats_api_is_wired_into_the_console_data_layer():
    """概览与统计必须优先用服务端聚合真值，不能只吃「已加载的那一页」。"""
    html = _ui().CONSOLE_HTML
    assert "async function fetchStats(" in html
    assert "async function fetchSuggests(" in html
    assert "api('stats', { signal: signal })" in html
    assert "var url = 'suggests/next';" in html
    assert "if (APP_FILTER) url += '?pkg=' + encodeURIComponent(APP_FILTER);" in html
    assert "var r = await api(url, { signal: signal });" in html
    assert "var STATS = null;" in html, "聚合真值要有独立全局，拿不到时回退估算"
    assert "var SUGGESTS = [];" in html
    assert "fetchStats(ctrl.signal).catch" in html, "聚合接口失败不能拖垮主数据流"
    assert "fetchSuggests(ctrl.signal).catch" in html


def test_overview_falls_back_to_local_estimate_with_a_label():
    """拿不到 /stats 时要退回本地估算，并且必须标注「估算」，不能把估算当全库真值。"""
    html = _ui().CONSOLE_HTML
    assert "function statNum(path)" in html
    assert "var useSrv = !!(STATS && !APP_FILTER && !QUERY.trim());" in html
    assert "'估算 · 仅已加载 '" in html
    assert "'全库真值 · 已加载 '" in html
    assert "（估算）" in html, "排队概览估算时也要标注"


def test_overview_todo_sub_uses_todo_convs_not_all_analyzed_convs():
    """概览条「待处理」下面那行会话数必须是「有待办的会话数」。

    /stats 里 analyses 段有两个会话数：convs（有分析结果的会话数）与
    todo_convs（其中有待办的会话数）。概览条上面那格写的是待办条数，下面跟着
    「共 N 个会话」时必须读 todo_convs —— 读 convs 时「待办 2 条 · 共 4 个会话」
    会让用户以为还有 2 条待办没显示出来，而 /suggests/next 同时报的是
    「待办 2 条，分布在 2 个会话」，同一屏两个数字互相打架。
    """
    html = _ui().CONSOLE_HTML
    assert "statNum(['analyses', 'todo_convs'])" in html, \
        "概览条待办的会话数没有读 todo_convs"
    assert "statNum(['analyses', 'convs'])" not in html, \
        "概览条仍拿「有分析结果的会话数」冒充「有待办的会话数」"


def test_stats_tab_shows_redaction_and_queue_truth():
    """统计 Tab 要回答整库口径：脱敏证据、排队任务分布。"""
    html = _ui().CONSOLE_HTML
    assert "key: 'stat-redact'" in html
    assert "key: 'stat-queue'" in html
    assert "redacted_cases" in html and "redaction_hits" in html
    assert "STATS.queue.by_kind" in html
    assert "消息总数（全库）" in html, "有真值时标签必须标明是全库口径"


def test_stats_queue_card_marks_by_kind_scope():
    """by_kind 只数未过期行：有过期行时必须把「不含 N 条已过期」写在旁边。

    否则同一张卡上「analysis 3 / sync 2」与「活跃 1 · 陈旧 3」两个数打架 ——
    用户会以为 5 条都在跑（实测 5 行里 3 条过期）。
    """
    html = _ui().CONSOLE_HTML
    assert "var staleN = Number(STATS.queue.stale || 0);" in html
    assert "（不含 " in html and " 条已过期）" in html
    assert "+ esc(kindScope) +" in html, "分布后面必须接上口径后缀"
    assert "（未过期）" not in html, "口径后缀要说过期条数，不能只说「未过期」"


def test_redaction_evidence_is_rendered_and_searchable():
    """redacted / redaction_rules / redaction_hits 要展示出来，也要能被搜到。"""
    html = _ui().CONSOLE_HTML
    assert "function redactBadge(a)" in html
    assert "function redactDetail(a)" in html
    assert "redactBadge(a)" in html and "redactDetail(a)" in html
    assert "已脱敏" in html
    assert "if (a.redacted) parts.push('已脱敏');" in html
    assert "if (a.redaction_hits != null) parts.push(String(a.redaction_hits));" in html


def test_skipped_rows_are_surfaced_instead_of_silently_dropped():
    """接口返回的 skipped（脏行）必须告诉用户，否则分不清「没有更多」和「被跳过」。"""
    html = _ui().CONSOLE_HTML
    assert "var SKIP_NOTICE = { msg: 0, ana: 0 };" in html
    assert "skipped: Number(r.skipped || 0)" in html
    assert "function skipNoticeHtml()" in html
    assert "function skipSections()" in html
    assert "因格式损坏被跳过" in html
    assert "SKIP_NOTICE.ana = Math.max(SKIP_NOTICE.ana || 0, Number(r.skipped || 0));" in html
    assert "SKIP_NOTICE.msg = Math.max(SKIP_NOTICE.msg || 0, Number(r.skipped || 0));" in html


def test_skip_notice_explains_numeric_column_cause():
    """skipped 的两种成因都要说清：不是只有「坏 JSON」。

    服务端 skipped 的两条来源是「数值列不是数字（typeof 守卫挡掉）」与
    「映射失败」；只写「格式损坏」会让用户以为库里存的是坏 JSON。
    """
    html = _ui().CONSOLE_HTML
    assert "服务端 skipped 计数：时间列不是数字或无法映射" in html
    assert "列表条数少于实际库存属正常" in html


def test_next_step_suggestions_render_with_jump_actions():
    """建议要能一键跳过去处理，且必须走 #data 上的事件委托。"""
    html = _ui().CONSOLE_HTML
    assert "function suggestHtml(s)" in html
    assert "function suggestSections()" in html
    assert 'data-action="jump"' in html
    assert "if (action === 'jump')" in html
    assert "switchTab(btn.getAttribute('data-jump') || 'conv')" in html
    assert "下一步建议（" in html
    assert ".suggest-card {" in _css(html)


def test_service_worker_never_caches_the_insight_endpoints():
    """聚合与建议是实时数据，不能被 SW 缓存策略吞掉。"""
    sw = _ui().CONSOLE_SW
    assert "url.pathname.indexOf('/stats') >= 0" in sw
    assert "url.pathname.indexOf('/suggests') >= 0" in sw


def test_advisor_tab_surfaces_archive_corruption_instead_of_empty_state():
    """顾问存档损坏（GET /advisor 回 200 + ok:false）不能渲染成「还没有顾问报告」。"""
    html = _ui().CONSOLE_HTML
    assert "var ADVISORS_ERR = '';" in html, "损坏原因要有独立全局"
    assert "ADVISORS_ERR = (res[2] && res[2].ok === false)" in html
    assert "res[2].detail || res[2].error" in html, "要优先展示 detail（带具体路径）"
    # 错误分支必须排在空态分支之前，否则先命中 emptyStateFor('adv')
    i_err = html.index("if (ADVISORS_ERR) {")
    i_empty = html.index("if (!reports.length) {")
    assert i_err < i_empty, "损坏分支必须在空态之前"
    assert "errorState('advisor_archive'" in html
    assert "这不是「没有报告」" in html
    assert "advisor_archive: { ico: '🧯', title: '顾问存档损坏'" in html


def test_load_more_does_not_double_count_skipped():
    """skipped 是「整段过滤范围内」的常量，翻页会原样再报一次。

    累加会把 2 条脏行在 3 页里说成 6 条；多页必须取最大值。
    """
    html = _ui().CONSOLE_HTML
    assert "SKIP_NOTICE.ana = Math.max(SKIP_NOTICE.ana || 0, Number(r.skipped || 0));" in html
    assert "SKIP_NOTICE.msg = Math.max(SKIP_NOTICE.msg || 0, Number(r.skipped || 0));" in html
    assert "SKIP_NOTICE.ana = (SKIP_NOTICE.ana || 0) + (r.skipped || 0);" not in html
    assert "SKIP_NOTICE.msg = (SKIP_NOTICE.msg || 0) + (r.skipped || 0);" not in html

def test_advisor_badge_uses_archive_total_and_marks_truncation():
    """GET /advisor 单次最多 100 份、存档上限 1000 份：角标必须报存档总数并标注截断。

    只数已加载的 ADVISORS.length 时，存档有 400 份也只写「100」，顾问 Tab 只渲染
    100 张卡，看起来像更早的 300 份报告丢了；服务端已经把 total 回出来了，前端不能扔。
    """
    html = _ui().CONSOLE_HTML
    assert "var ADVISOR_TOTAL = 0;" in html, "要独立保存存档总数"
    assert "(res[2] && typeof res[2].total === 'number' && res[2].total > 0)" in html
    assert "ADVISOR_TOTAL = (res[2] && typeof res[2].total === 'number' && res[2].total > 0)" in html
    assert "setBadge('badge-adv', ADVISOR_TOTAL || ADVISORS.length);" in html
    assert "setBadge('badge-adv', ADVISORS.length);" not in html, "角标不能只数已加载"
    assert "['tab-adv', '顾问', ADVISOR_TOTAL || ADVISORS.length, '份报告']" in html
    # 截断说明必须出现在报告列表之前（先说明再渲染卡片）
    i_note = html.index("if (ADVISOR_TOTAL > reports.length) {")
    i_cards = html.index("reports.forEach(function (r, i) {")
    assert i_note < i_cards, "截断提示必须在卡片之前"
    assert "更早的报告不在此列表里" in html


def test_stats_dist_merges_pkg_and_source_before_render():
    """应用 / 来源分布要先按展示口径归并再渲染。

    服务端 /stats 的 by_pkg 是按原始列 GROUP BY 的，库里 pkg 是 NULL 与空串时各自
    成一组，直接按标签渲染会写出「微信 1 / 微信 1 / 微博 1 / 微信 1」；本地估算档
    走 normPkg(m.pkg) 天然归一 —— 两条路径必须同一口径（规则层 mergePairsBy）。
    """
    html = _ui().CONSOLE_HTML
    assert "function distPairs(obj, keyName, normalize)" in html, "缺少分布归一 helper"
    assert "CC.mergePairsBy(pairs, normalize)" in html, "分布归一没有走规则层"
    assert "var appPairs = distPairs(byApp, 'pkg', normPkg);" in html, \
        "应用分布没有按 normPkg 归并"
    assert "return s ? String(s) : 'unknown';" in html, "来源空值没有归成 unknown"
    # 旧写法（直接 pairList 后按标签渲染）必须已经消失
    assert "var appPairs = pairList(byApp, 'pkg');" not in html, "应用分布仍在按原始列渲染"
    assert "var srcPairs = pairList(bySrc, 'source');" not in html, "来源分布仍在按原始列渲染"
    # distPairs 的兜底分支（规则脚本缺失时）也必须按次数降序重排：归并会改次数，
    # 沿用首次出现序会渲染成「微博 2 / 微信 3」。规则层自身由 test_console_classify 守着。
    assert html.count("if (a[1] !== b[1]) return b[1] - a[1];") == 1, \
        "distPairs 兜底分支没有按次数降序排（规则层与兜底必须同口径）"
