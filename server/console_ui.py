# -*- coding: utf-8 -*-
# 控制台前端资源：纯常量模块。
#
# 本文件由 server/app.py 通过 importlib.util.spec_from_file_location 按路径加载
#（与 _import_extract_module 同一套路），因此：
#   * 只能有常量赋值，不得出现 import / 函数定义 / 顶层副作用；
#   * 三个字符串常量内部不得出现连续三个双引号（会截断字符串）。
#
# 导出：
#   CONSOLE_HTML      —— 完整单页 HTML（内联 CSS + JS），控制台外壳
#   CONSOLE_MANIFEST  —— PWA manifest dict
#   CONSOLE_SW        —— service worker 源码（字符串）
CONSOLE_HTML = r"""<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<meta name="apple-mobile-web-app-capable" content="yes">
<meta name="mobile-web-app-capable" content="yes">
<meta name="apple-mobile-web-app-status-bar-style" content="black-translucent">
<meta name="apple-mobile-web-app-title" content="微信分析助手">
<meta name="theme-color" content="#07C160" media="(prefers-color-scheme: light)">
<meta name="theme-color" content="#1f9e5c" media="(prefers-color-scheme: dark)">
<title>微信分析助手</title>
<script>
// 页面所在目录（域根绝对路径）：/console -> /，/mmonitor/console -> /mmonitor/
window.__WX_DIR = (function () {
  var p = location.pathname;
  while (p.length > 1 && p.charAt(p.length - 1) === '/') p = p.slice(0, -1);
  return p.slice(0, p.lastIndexOf('/') + 1);
})();
// manifest / apple-touch-icon 链接用 JS 注入（基于 __WX_DIR 的绝对路径）：
// 静态 href 在带末尾斜杠或前缀挂载时会解析错
(function () {
  var head = document.head;
  function addLink(rel, href) {
    var l = document.createElement('link');
    l.rel = rel;
    l.href = href;
    head.appendChild(l);
  }
  addLink('manifest', window.__WX_DIR + 'console/manifest.json');
  addLink('apple-touch-icon', window.__WX_DIR + 'console/icons/icon-180.png');
})();
</script>
<style>
  /* ===== 设计令牌（DESIGN-4 变量化 / DESIGN-5 字阶 / §6.2 暗色 / §6 对比度 AA）=====
     颜色、字阶、圆角、间距、热区全部走 CSS 变量；暗色只覆盖变量，
     组件样式不复制第二套。浅色下正文与辅助文字对比度均 >= 4.5:1。 */
  :root {
    color-scheme: light dark;
    --bg: #f2f4f5;
    --surface: #ffffff;
    --surface-2: #eef1f3;
    --text: #1f2328;
    --muted: #5b6570;
    --border: #d8dee3;
    --accent: #07c160;
    --accent-text: #0b7a43;
    --accent-strong: #0b7a43;
    --accent-weak: #e4f6ec;
    --on-accent: #ffffff;
    --header-warn: #ffefbf;
    --danger: #c0261a;
    --danger-weak: #fdecea;
    --on-danger: #ffffff;
    --warn: #9a4a00;
    --warn-weak: #fff1e0;
    --info: #1a5fb4;
    --focus: #1a5fb4;
    --badge-db: #0b5fbd;
    --badge-nt: #a34800;
    --badge-grp: #0b7a43;
    --badge-app: #1a5fb4;
    --on-badge: #ffffff;
    --sk-hi: #e9edf0;
    --shadow: 0 1px 2px rgba(16, 24, 40, .08);
    --radius: 12px;
    --radius-sm: 8px;
    --tap: 44px;
    --fs-xs: 12px;
    --fs-sm: 14px;
    --fs-md: 16px;
    --fs-lg: 20px;
    --fs-xl: 24px;
    --fs-2xl: 28px;
    --lh-tight: 1.25;
    --lh: 1.55;
    --sp-1: 4px;
    --sp-2: 8px;
    --sp-3: 12px;
    --sp-4: 16px;
    --header-h: calc(env(safe-area-inset-top) + 64px);
  }
  @media (prefers-color-scheme: dark) {
    :root {
      --bg: #0f1317;
      --surface: #171c22;
      --surface-2: #1e242c;
      --text: #e9edf1;
      --muted: #a9b4c0;
      --border: #2a323b;
      --accent: #2fbf72;
      --accent-text: #5cd992;
      --accent-strong: #1f9e5c;
      --accent-weak: #123324;
      --on-accent: #06130b;
      --header-warn: #05130c;
      --danger: #ff8a80;
      --danger-weak: #2a1512;
      --on-danger: #2b0d08;
      --warn: #ffb865;
      --warn-weak: #2b1d0e;
      --info: #7fb2ff;
      --focus: #7fb2ff;
      --badge-db: #1b4f8f;
      --badge-nt: #7a3c12;
      --badge-grp: #14532f;
      --badge-app: #173d70;
      --on-badge: #eef3f8;
      --sk-hi: #262e37;
      --shadow: 0 1px 2px rgba(0, 0, 0, .45);
    }
  }

  *, *::before, *::after { box-sizing: border-box; -webkit-tap-highlight-color: transparent; }
  html, body { overscroll-behavior-y: contain; }
  body {
    margin: 0;
    font-family: -apple-system, BlinkMacSystemFont, "PingFang SC", "Microsoft YaHei", sans-serif;
    background: var(--bg); color: var(--text);
    font-size: var(--fs-md); line-height: var(--lh);
    -webkit-text-size-adjust: 100%;
  }
  h1, h2, h3 { margin: 0; line-height: var(--lh-tight); }
  button, input { font: inherit; color: inherit; }
  .sr-only {
    position: absolute; width: 1px; height: 1px; padding: 0; margin: -1px;
    overflow: hidden; clip: rect(0 0 0 0); white-space: nowrap; border: 0;
  }
  a:focus, button:focus, input:focus, [tabindex]:focus { outline: 3px solid var(--focus); outline-offset: 2px; }
  :focus:not(:focus-visible) { outline: none; }
  :focus-visible { outline: 3px solid var(--focus); outline-offset: 2px; }

  /* ===== 头部 ===== */
  header {
    position: sticky; top: 0; z-index: 30;
    background: var(--accent-strong); color: var(--on-accent);
    padding: calc(env(safe-area-inset-top) + 10px) var(--sp-3) 8px;
  }
  .h-row { display: flex; align-items: center; gap: var(--sp-2); }
  header h1 { font-size: var(--fs-lg); font-weight: 700; flex: 1; min-width: 0; }
  .icon-btn {
    min-width: var(--tap); min-height: var(--tap);
    display: inline-flex; align-items: center; justify-content: center;
    background: rgba(255, 255, 255, .14); border: none; border-radius: 10px;
    color: var(--on-accent); font-size: var(--fs-lg); line-height: 1; cursor: pointer;
  }
  .icon-btn:active { transform: scale(.94); }
  .sync-line { font-size: var(--fs-xs); color: var(--on-accent); margin-top: 2px; }
  .sync-line.err { color: var(--header-warn); font-weight: 700; }

  /* ===== 下拉刷新提示条 ===== */
  #ptr {
    position: fixed; top: 0; left: 0; right: 0; z-index: 40;
    text-align: center; color: var(--accent-text); font-size: var(--fs-sm);
    padding-top: calc(env(safe-area-inset-top) + 6px);
    transform: translateY(-100%); transition: transform .18s ease;
    pointer-events: none;
  }
  #ptr.show { transform: translateY(0); }
  #ptr .ptr-in {
    display: inline-block; background: var(--surface); color: var(--text);
    border: 1px solid var(--border); border-radius: 14px;
    padding: 6px 14px; box-shadow: 0 2px 8px rgba(0, 0, 0, .18);
  }

  /* ===== 主体布局（DESIGN-11：手机单栏 / >=900px 两栏）===== */
  .shell {
    max-width: 720px; margin: 0 auto;
    padding: var(--sp-3) var(--sp-3) calc(env(safe-area-inset-bottom) + 84px);
  }
  /* 手机端 .side 不生成盒子：概览条与过滤条成为 .shell 的直接子元素，
     过滤条的 sticky 才能在整个 .shell 高度内持续生效 */
  .side { display: contents; }
  main { max-width: 720px; margin: 0 auto; min-width: 0; }

  .card {
    background: var(--surface); border: 1px solid var(--border);
    border-radius: var(--radius); padding: var(--sp-3);
    margin-bottom: var(--sp-3); box-shadow: var(--shadow);
  }

  /* ===== 首屏概览条（DESIGN-1）===== */
  .ov-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: var(--sp-2); }
  .ov-item { background: var(--surface-2); border-radius: var(--radius-sm); padding: var(--sp-2) var(--sp-3); min-height: 56px; }
  .ov-num { font-size: var(--fs-xl); font-weight: 700; color: var(--accent-text); line-height: var(--lh-tight); }
  .ov-num.time { font-size: var(--fs-lg); }
  .ov-label { font-size: var(--fs-xs); color: var(--muted); }
  .ov-sub { font-size: var(--fs-xs); color: var(--muted); }

  /* ===== 常驻全局过滤条（FP1-e：四个 tab 同一口径）===== */
  .filterbar {
    position: sticky; top: var(--header-h); z-index: 20;
    background: var(--bg); padding: var(--sp-2) 0;
    border-bottom: 1px solid var(--border);
  }
  .fb-row { display: flex; align-items: center; gap: var(--sp-2); }
  .chips { display: flex; flex-wrap: wrap; gap: var(--sp-2); }
  .chip-btn {
    display: inline-flex; align-items: center; justify-content: center;
    min-height: var(--tap); min-width: var(--tap); padding: 0 var(--sp-3);
    border-radius: 999px; background: var(--surface);
    border: 1px solid var(--border); color: var(--text);
    font-size: var(--fs-sm); cursor: pointer; user-select: none;
  }
  .chip-btn[aria-pressed="true"] {
    background: var(--accent-strong); border-color: var(--accent-strong);
    color: var(--on-accent); font-weight: 700;
  }
  .search-row { display: flex; align-items: center; gap: var(--sp-2); margin-top: var(--sp-2); }
  .search-row input {
    flex: 1; min-width: 0; min-height: var(--tap);
    font-size: var(--fs-md); padding: 0 var(--sp-3);
    border: 1px solid var(--border); border-radius: var(--radius-sm);
    background: var(--surface); color: var(--text);
  }
  .btn-ghost {
    min-height: var(--tap); min-width: var(--tap); padding: 0 var(--sp-3);
    background: var(--surface-2); border: 1px solid var(--border);
    border-radius: var(--radius-sm); color: var(--text);
    font-size: var(--fs-sm); cursor: pointer;
  }
  .btn-ghost:disabled { opacity: .55; cursor: default; }
  .btn {
    min-height: var(--tap); padding: 0 var(--sp-4);
    border-radius: var(--radius-sm); border: 1px solid transparent;
    font-size: var(--fs-sm); cursor: pointer;
  }
  .btn-primary { background: var(--accent-strong); color: var(--on-accent); font-weight: 600; }
  .btn-secondary { background: var(--surface-2); color: var(--text); border-color: var(--border); }

  /* ===== 会话列表 ===== */
  .sec-head {
    font-size: var(--fs-sm); color: var(--muted); margin: var(--sp-3) var(--sp-1) var(--sp-2);
    font-weight: 700;
  }
  .sec-head.warn { color: var(--warn); }
  .conv-row { display: flex; align-items: center; gap: var(--sp-1); }
  .conv-head {
    flex: 1; min-width: 0; min-height: var(--tap);
    display: flex; align-items: center; gap: var(--sp-2);
    padding: var(--sp-1) 0; cursor: pointer;
  }
  .caret { color: var(--muted); font-size: var(--fs-sm); flex: none; width: 14px; }
  .conv-main { flex: 1; min-width: 0; display: block; }
  .conv-title-row { display: flex; align-items: center; gap: var(--sp-1); min-width: 0; }
  .conv-name {
    font-weight: 700; font-size: var(--fs-md); flex: 1; min-width: 0;
    overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
  }
  .conv-count { color: var(--muted); font-size: var(--fs-xs); white-space: nowrap; flex: none; }
  .conv-preview {
    color: var(--muted); font-size: var(--fs-sm); margin-top: 2px;
    display: flex; gap: var(--sp-2); align-items: baseline;
  }
  .conv-preview .pv-text { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .conv-preview .pv-time { flex: none; font-size: var(--fs-xs); color: var(--muted); }
  .todo-flag { color: var(--warn); font-size: var(--fs-sm); flex: none; }
  .copy-btn {
    min-width: var(--tap); min-height: var(--tap); flex: none;
    display: inline-flex; align-items: center; justify-content: center;
    background: none; border: none; border-radius: var(--radius-sm);
    color: var(--muted); font-size: var(--fs-md); cursor: pointer;
  }
  .msg-list { margin-top: var(--sp-2); border-top: 1px solid var(--border); padding-top: var(--sp-2); }
  .msg { padding: var(--sp-2) 0; border-bottom: 1px solid var(--surface-2); }
  .msg:last-child { border-bottom: none; }
  .m-meta { font-size: var(--fs-xs); color: var(--muted); }
  .m-text { font-size: var(--fs-sm); white-space: pre-wrap; overflow-wrap: anywhere; word-break: normal; }
  .date-head {
    display: flex; align-items: center; gap: var(--sp-2);
    min-height: var(--tap); font-size: var(--fs-sm);
    color: var(--info); font-weight: 700; border-bottom: 1px solid var(--border);
  }
  .date-body { padding-left: var(--sp-2); }
  .badge {
    display: inline-block; border-radius: 4px; padding: 1px 6px;
    font-size: var(--fs-xs); color: var(--on-badge); margin-right: var(--sp-1);
  }
  .b-db { background: var(--badge-db); }
  .b-nt { background: var(--badge-nt); }
  .b-grp { background: var(--badge-grp); }
  .b-app { background: var(--badge-app); }
  .chip {
    display: inline-block; border-radius: 999px; padding: 3px 10px;
    font-size: var(--fs-xs); background: var(--accent-weak); color: var(--accent-text);
    margin-right: var(--sp-1); margin-bottom: var(--sp-1);
  }
  .chip.warn { background: var(--warn-weak); color: var(--warn); }
  .s2 { border-top: 1px dashed var(--border); margin-top: var(--sp-2); padding-top: var(--sp-2); font-size: var(--fs-sm); }
  .s2 .row { margin: var(--sp-1) 0; }
  .s2 .label { color: var(--muted); font-size: var(--fs-xs); }
  .s2 ul { margin: var(--sp-1) 0 var(--sp-1) 18px; padding: 0; }
  .imp-high { color: var(--danger); font-weight: 700; }
  .imp-mid { color: var(--warn); font-weight: 700; }
  .todo-block { margin: var(--sp-2) 0; }
  .stat-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: var(--sp-2); }
  .stat-grid .card { margin-bottom: 0; }
  .stat-num { font-size: var(--fs-xl); font-weight: 700; color: var(--accent-text); }
  .stat-label { font-size: var(--fs-xs); color: var(--muted); }
  .stat-value { font-size: var(--fs-sm); color: var(--text); margin-top: var(--sp-2); }
  .ana-head { display: flex; align-items: center; gap: var(--sp-2); }
  .ana-meta { font-size: var(--fs-xs); color: var(--muted); margin-top: 2px; }
  .note {
    font-size: var(--fs-xs); color: var(--muted); background: var(--surface-2);
    border-radius: var(--radius-sm); padding: var(--sp-2) var(--sp-3); margin-bottom: var(--sp-3);
  }
  .refresh-note { text-align: center; color: var(--muted); font-size: var(--fs-xs); padding: var(--sp-2); }

  /* ===== 空态 / 错误态 / 骨架屏（DESIGN-10）===== */
  .state { text-align: center; padding: var(--sp-4) var(--sp-3); border-radius: var(--radius); }
  .state-empty { background: var(--surface); border: 1px dashed var(--border); }
  .state-error { background: var(--danger-weak); border: 1px solid var(--danger); }
  .state-ico { font-size: var(--fs-2xl); line-height: 1; margin-bottom: var(--sp-2); }
  .state-title { font-size: var(--fs-md); font-weight: 700; margin-bottom: var(--sp-1); }
  .state-desc { font-size: var(--fs-sm); color: var(--muted); line-height: var(--lh); }
  .state-error .state-title { color: var(--danger); }
  .state-next { font-size: var(--fs-sm); color: var(--text); margin-top: var(--sp-2); }
  .state-actions { margin-top: var(--sp-3); display: flex; gap: var(--sp-2); justify-content: center; flex-wrap: wrap; }
  .sk-card {
    background: var(--surface); border: 1px solid var(--border);
    border-radius: var(--radius); padding: var(--sp-3); margin-bottom: var(--sp-3);
  }
  .sk-line {
    height: 12px; border-radius: 6px; margin-bottom: var(--sp-2);
    background: linear-gradient(90deg, var(--surface-2) 25%, var(--sk-hi) 37%, var(--surface-2) 63%);
    background-size: 400% 100%; animation: sk-shine 1.4s ease infinite;
  }
  .sk-line.w30 { width: 30%; }
  .sk-line.w60 { width: 60%; }
  .sk-line.w90 { width: 90%; }
  @keyframes sk-shine {
    0% { background-position: 100% 50%; }
    100% { background-position: 0 50%; }
  }

  /* ===== 鉴权框 ===== */
  .auth-box { max-width: 360px; margin: 32px auto; text-align: center; }
  .auth-box h2 { font-size: var(--fs-lg); margin-bottom: var(--sp-2); }
  .auth-box p { font-size: var(--fs-sm); color: var(--muted); margin: 0 0 var(--sp-3); }
  .auth-box input {
    width: 100%; min-height: var(--tap); font-size: var(--fs-md); padding: 0 var(--sp-3);
    border: 1px solid var(--border); border-radius: var(--radius-sm);
    background: var(--surface); color: var(--text); margin-bottom: var(--sp-2);
  }
  .auth-box button { width: 100%; }
  .auth-msg { color: var(--danger); font-size: var(--fs-sm); min-height: 20px; margin: 0 0 var(--sp-2); }

  /* ===== 底部 tab 栏（角标 + 44px 热区）===== */
  .tabbar {
    position: fixed; left: 0; right: 0; bottom: 0; z-index: 30;
    display: flex; background: var(--surface); border-top: 1px solid var(--border);
    padding-bottom: env(safe-area-inset-bottom);
  }
  .tabbar button {
    flex: 1; background: none; border: none; color: var(--muted);
    display: flex; flex-direction: column; align-items: center; justify-content: center;
    gap: 2px; min-height: 56px; padding: 6px 2px; cursor: pointer; position: relative;
  }
  .tabbar button .t-ico { font-size: var(--fs-lg); line-height: 1; }
  .tabbar button .t-label { font-size: var(--fs-xs); }
  .tabbar button.active { color: var(--accent-text); font-weight: 700; }
  .t-badge {
    position: absolute; top: 2px; left: 50%; margin-left: 4px;
    min-width: 18px; height: 18px; padding: 0 5px; border-radius: 9px;
    background: var(--danger); color: var(--on-danger);
    font-size: var(--fs-xs); line-height: 18px; text-align: center; font-weight: 700;
  }
  .t-badge:empty { display: none; }

  /* ===== toast（DESIGN-12：轻量反馈）===== */
  .toast {
    position: fixed; left: 50%; bottom: calc(env(safe-area-inset-bottom) + 76px);
    transform: translateX(-50%); z-index: 50;
    max-width: 86vw; padding: 10px 16px; border-radius: 999px;
    background: var(--text); color: var(--bg);
    font-size: var(--fs-sm); line-height: 1.4;
    opacity: 0; pointer-events: none; transition: opacity .18s ease;
  }
  .toast.show { opacity: 1; }

  /* ===== 宽屏两栏（DESIGN-11）===== */
  @media (min-width: 900px) {
    .shell {
      max-width: 1180px; display: grid;
      grid-template-columns: 320px minmax(0, 1fr);
      gap: var(--sp-4); align-items: start;
    }
    .side {
      display: block; position: sticky;
      top: calc(env(safe-area-inset-top) + 64px);
      max-height: calc(100vh - 90px); overflow-y: auto;
    }
    .filterbar { position: static; border-bottom: none; padding-bottom: 0; }
    main { max-width: none; }
    .cards { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: var(--sp-3); }
    .cards .card { margin-bottom: 0; }
    .stat-grid { grid-template-columns: repeat(4, minmax(0, 1fr)); }
    .tabbar {
      left: 50%; transform: translateX(-50%);
      width: min(560px, 100%); border-radius: var(--radius) var(--radius) 0 0;
      border: 1px solid var(--border); border-bottom: none;
    }
  }

  /* ===== 尊重系统的减弱动效设置 ===== */
  @media (prefers-reduced-motion: reduce) {
    *, *::before, *::after {
      animation-duration: .001ms !important;
      animation-iteration-count: 1 !important;
      transition-duration: .001ms !important;
      scroll-behavior: auto !important;
    }
    .sk-line { background: var(--surface-2); }
  }
</style>
</head>
<body>
<header>
  <div class="h-row">
    <h1>💬 微信分析助手</h1>
    <button type="button" class="icon-btn" id="btn-refresh" aria-label="立即刷新数据" onclick="load('manual')">⟳</button>
  </div>
  <div class="sync-line" id="sync-line" role="status" aria-live="polite">正在同步…</div>
</header>
<div id="ptr" aria-hidden="true"><span class="ptr-in" id="ptr-text">下拉刷新</span></div>
<div class="shell">
  <aside class="side">
    <section class="card" id="overview" aria-label="今日概览" aria-live="polite">
      <div class="ov-grid">
        <div class="ov-item">
          <div class="ov-num" id="ov-todo">—</div>
          <div class="ov-label">今日待办</div>
          <div class="ov-sub" id="ov-todo-sub"></div>
        </div>
        <div class="ov-item">
          <div class="ov-num" id="ov-analysis">—</div>
          <div class="ov-label">未读分析</div>
          <div class="ov-sub" id="ov-analysis-sub"></div>
        </div>
        <div class="ov-item">
          <div class="ov-num" id="ov-msgs">—</div>
          <div class="ov-label">消息总数</div>
          <div class="ov-sub" id="ov-msgs-sub"></div>
        </div>
        <div class="ov-item">
          <div class="ov-num time" id="ov-sync">—</div>
          <div class="ov-label">最近同步</div>
          <div class="ov-sub" id="ov-sync-sub"></div>
        </div>
      </div>
    </section>
    <section class="filterbar" aria-label="全局过滤与搜索">
      <div class="fb-row">
        <div class="chips" id="app-chips" role="group" aria-label="按来源 App 过滤"></div>
        <button type="button" class="btn-ghost" id="btn-clear-app" aria-label="清除来源 App 过滤" onclick="clearAppFilter()">清除</button>
      </div>
      <div class="search-row">
        <label class="sr-only" for="global-search">全局搜索（会话、待办、分析、顾问）</label>
        <input id="global-search" type="search" autocomplete="off"
               placeholder="搜索会话 / 待办 / 分析 / 顾问"
               aria-label="全局搜索" oninput="onSearchInput(this.value)">
        <button type="button" class="btn-ghost" id="btn-clear-search" aria-label="清除搜索关键词" onclick="clearSearch()">清除</button>
      </div>
    </section>
  </aside>
  <main id="main">
    <div id="data" role="tabpanel" aria-labelledby="tab-conv"></div>
  </main>
</div>
<nav class="tabbar" role="tablist" aria-label="主导航">
  <button type="button" id="tab-conv" class="tab active" role="tab" aria-selected="true"
          aria-controls="data" onclick="switchTab('conv')">
    <span class="t-ico" aria-hidden="true">💬</span><span class="t-label">会话</span>
    <span class="t-badge" id="badge-conv"></span>
  </button>
  <button type="button" id="tab-todo" class="tab" role="tab" aria-selected="false"
          aria-controls="data" onclick="switchTab('todo')">
    <span class="t-ico" aria-hidden="true">⚡</span><span class="t-label">待办</span>
    <span class="t-badge" id="badge-todo"></span>
  </button>
  <button type="button" id="tab-stat" class="tab" role="tab" aria-selected="false"
          aria-controls="data" onclick="switchTab('stat')">
    <span class="t-ico" aria-hidden="true">📊</span><span class="t-label">统计</span>
    <span class="t-badge" id="badge-stat"></span>
  </button>
  <button type="button" id="tab-adv" class="tab" role="tab" aria-selected="false"
          aria-controls="data" onclick="switchTab('adv')">
    <span class="t-ico" aria-hidden="true">🧭</span><span class="t-label">顾问</span>
    <span class="t-badge" id="badge-adv"></span>
  </button>
</nav>
<div id="toast" class="toast" role="status" aria-live="polite"></div>
<div id="live-status" class="sr-only" role="status" aria-live="polite"></div>

<script>
// ---------- 存储包装（隐私模式下 localStorage 不可用时不致命）----------
var LS = (function () {
  var mem = {};
  var ok = true;
  try {
    window.localStorage.setItem('__wx_probe', '1');
    window.localStorage.removeItem('__wx_probe');
  } catch (e) { ok = false; }
  return {
    get: function (k) {
      try {
        if (ok) {
          var v = window.localStorage.getItem(k);
          return v === null ? (k in mem ? mem[k] : null) : v;
        }
      } catch (e) { }
      return (k in mem) ? mem[k] : null;
    },
    set: function (k, v) {
      try { if (ok) { window.localStorage.setItem(k, v); return true; } } catch (e) { }
      mem[k] = v;
      return false;
    }
  };
})();

// ---------- token 管理：query -> localStorage -> X-Token 头 ----------
(function initToken() {
  var q = new URLSearchParams(window.location.search).get('token');
  // 只在本地还没有令牌时才用 URL 里的：
  // 无条件覆盖 -> 收藏夹/历史里的旧 token 链接会把新令牌冲掉
  if (q && !LS.get('wx_token')) {
    LS.set('wx_token', q);
  }
  // 不管用不用 URL 里的令牌，都把 token 参数从地址栏抹掉（防截图/历史记录泄露），
  // 其余查询参数（如 PWA 快捷方式带的 ?tab=）保留
  if (q) {
    var u = new URL(window.location.href);
    u.searchParams.delete('token');
    var rest = u.searchParams.toString();
    window.history.replaceState(null, '', u.pathname + (rest ? '?' + rest : '') + u.hash);
  }
})();
var TOKEN = LS.get('wx_token') || '';

// ---------- 工具 ----------
function $(id) { return document.getElementById(id); }

// esc()：所有插入 innerHTML 的服务端字符串都必须过这里。
// 单引号也转义：属性里用单引号包裹时同样安全。
function esc(s) {
  return String(s == null ? '' : s)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

function pad2(n) { return (n < 10 ? '0' : '') + n; }

function fmtTime(ms) {
  var d = new Date(Number(ms) || 0);
  if (isNaN(d.getTime())) return '';
  return (d.getMonth() + 1) + '-' + pad2(d.getDate()) + ' ' + pad2(d.getHours()) + ':' + pad2(d.getMinutes());
}
function fmtClock(ms) {
  var d = new Date(Number(ms) || 0);
  if (isNaN(d.getTime())) return '—';
  return pad2(d.getHours()) + ':' + pad2(d.getMinutes()) + ':' + pad2(d.getSeconds());
}
function fmtDate(ms) {
  var d = new Date(Number(ms) || 0);
  if (isNaN(d.getTime())) return '';
  return (d.getMonth() + 1) + '月' + d.getDate() + '日';
}
// 分组用的完整日期键（跨年不撞车）
function dateKey(ms) {
  var d = new Date(Number(ms) || 0);
  if (isNaN(d.getTime())) return 'unknown';
  return d.getFullYear() + '-' + pad2(d.getMonth() + 1) + '-' + pad2(d.getDate());
}
// 相对时间：刚刚 / 3 分钟前 / 昨天 / 10月3日
function relTime(ms) {
  var t = Number(ms) || 0;
  if (!t) return '';
  var now = Date.now();
  var d = new Date(t), n = new Date(now);
  var dayMs = 86400000;
  var startOfToday = new Date(n.getFullYear(), n.getMonth(), n.getDate()).getTime();
  if (t >= startOfToday) {
    var diff = Math.floor((now - t) / 60000);
    if (diff < 1) return '刚刚';
    if (diff < 60) return diff + ' 分钟前';
    return Math.floor(diff / 60) + ' 小时前';
  }
  if (t >= startOfToday - dayMs) return '昨天';
  if (d.getFullYear() === n.getFullYear()) return fmtDate(t);
  return d.getFullYear() + '年' + fmtDate(t);
}
function isToday(ms) {
  var t = Number(ms) || 0;
  if (!t) return false;
  var d = new Date(t), n = new Date();
  return d.getFullYear() === n.getFullYear() && d.getMonth() === n.getMonth() && d.getDate() === n.getDate();
}

// ---------- 状态 ----------
var CUR_TAB = 'conv';
var MSGS = [], ANALYSES = [], ADVISORS = [];
// App 过滤：'' = 全部；否则只看该包名（四个 tab 共用同一口径）
var APP_FILTER = '';
// 全局搜索关键词（跨四个 tab）
var QUERY = '';
// 鉴权态：令牌无效后固定显示鉴权框，避免把错误态渲染成普通列表
var AUTH_MODE = false;
// 折叠状态（key 形如 c:<pkg><SEP><会话名> 或 <key>|<日期键>，
// true = 展开，默认折叠；持久化到 localStorage）
var COLLAPSE = {};
try { COLLAPSE = JSON.parse(LS.get('wx_collapse_v1') || '{}') || {}; } catch (e) { COLLAPSE = {}; }
var CONV_NODES = [];   // 折叠节点索引 -> 折叠 key
var CONV_CARDS = [];   // 会话卡片索引 -> {ck, name}（复制会话名用）
var todoByConv = {};
var LAST_SYNC = 0;     // 最近一次数据同步成功时间（毫秒）
var LAST_ERR = '';     // 最近一次同步错误（空 = 正常）
var LAST_ERR_KIND = '';
var SW_ERR = '';       // service worker 注册失败提示
var NEW_CASES = {};    // 上次同步后新增分析的 key 集合（概览条「未读分析」）
var PREV_SYNC = 0;     // 上一次成功同步的时间戳
var LOAD_SEQ = 0;      // 请求序号：响应回来时序号不是最新就丢弃
var LOAD_CTRL = null;  // 当前轮的 AbortController
var LOAD_BUSY = false; // 上一轮是否还在飞（30s 定时器据此跳过）
var TOAST_T = null;
var FILTER_SIG = '';

// 来源 App 口径（与服务端 PKG_LABELS 一致）：老数据没有 pkg，一律按微信
var PKG_WX = 'com.tencent.mm';
var PKG_LABELS = {
  'com.tencent.mm': '微信',
  'com.ss.android.lark': '飞书',
  'com.alibaba.android.rimet': '钉钉'
};
// 会话复合身份（对齐安卓端 ConvKey）：pkg + SEP + 会话名。
// SEP 用 U+0001（构造方式避免源码里出现裸控制字符）
var SEP = String.fromCharCode(1);
function normPkg(p) { return p || PKG_WX; }
function pkgLabel(p) { return PKG_LABELS[normPkg(p)] || normPkg(p); }
function ckOf(o) { return normPkg(o.pkg) + SEP + (o.conversation == null ? '' : o.conversation); }

// 需要行动：优先看 s1.need_action.value（端上上报结构），
// s1 缺失时退回接口顶层归一后的 need_action 布尔值
function needActionOf(a) {
  if (!a) return false;
  if (a.s1 && a.s1.need_action) {
    var na = a.s1.need_action;
    if (na && typeof na === 'object') return !!na.value;
    return !!na;
  }
  return a.need_action === true;
}
// 重要度：优先 s1.importance，缺失时退回接口顶层 importance
function importanceOf(a) {
  if (!a) return '';
  if (a.s1 && a.s1.importance != null && a.s1.importance !== '') return a.s1.importance;
  return a.importance == null ? '' : a.importance;
}
// 分析的唯一标识（用于统计「未读」）
function analysisKey(a) {
  if (!a) return '';
  if (a.case_id) return String(a.case_id);
  return 't' + String(a.received_at || 0);
}

// ---------- 鉴权框 ----------
// 只在第一次创建，之后复用同一节点：401 重试不清空用户正在输入的令牌
function renderAuth(msg) {
  var box = $('auth-box');
  if (!box) {
    $('data').innerHTML =
      '<div class="card auth-box" id="auth-box">' +
      '<h2>🔒 请输入访问令牌</h2>' +
      '<p class="auth-msg" id="auth-msg"></p>' +
      '<label class="sr-only" for="tk">访问令牌</label>' +
      '<input id="tk" type="password" autocomplete="off" placeholder="X-Token">' +
      '<button type="button" class="btn btn-primary" onclick="saveToken()">保存并进入</button>' +
      '<p class="state-next">令牌保存在本机浏览器（localStorage），不会随链接外传。</p>' +
      '</div>';
    var tk = $('tk');
    if (tk) tk.focus();
  }
  var m = $('auth-msg');
  if (m) m.textContent = msg || '';
}
function saveToken() {
  var el = $('tk');
  if (!el) return;
  LS.set('wx_token', el.value);
  window.location.reload();
}

// ---------- API ----------
// 用 __WX_DIR 拼域根绝对路径：页面挂在 /console 还是 /mmonitor/console
// （甚至带末尾斜杠）都能正确指到同级接口，不写死挂载前缀
function mkErr(kind, msg) {
  var e = new Error(msg);
  e.kind = kind;
  return e;
}
async function api(path, opts) {
  opts = opts || {};
  var init = { headers: { 'X-Token': TOKEN }, cache: 'no-store' };
  if (opts.signal) init.signal = opts.signal;
  var r;
  try {
    r = await fetch(window.__WX_DIR + path, init);
  } catch (e) {
    if (e && e.name === 'AbortError') throw mkErr('abort', '已取消');
    throw mkErr('network', '网络错误：无法连接到服务器');
  }
  // 四种失败必须区分：401 / 503 / 其它非 2xx / 网络异常
  if (r.status === 401) throw mkErr('unauthorized', '令牌无效或已过期');
  if (r.status === 503) throw mkErr('not_configured', '服务端未配置访问令牌（WEIXIN_TOKEN），请在服务端设置后重启');
  if (!r.ok) {
    // 不判 r.ok：500 的 HTML 错误页会被 r.json() 解析抛 SyntaxError，
    // 界面上只剩「网络异常」，看不出真实原因
    var detail = '';
    try { detail = (await r.text()).slice(0, 200); } catch (e2) { }
    throw mkErr('http', '接口错误 HTTP ' + r.status + (detail ? '：' + detail : ''));
  }
  return r.json();
}

// ---------- 反馈 ----------
function toast(msg) {
  var el = $('toast');
  if (!el) return;
  el.textContent = msg;
  el.classList.add('show');
  if (TOAST_T) clearTimeout(TOAST_T);
  TOAST_T = setTimeout(function () { el.classList.remove('show'); }, 2000);
}

// 复制：优先 navigator.clipboard，不可用时降级到 execCommand
function copyText(text) {
  if (navigator.clipboard && navigator.clipboard.writeText) {
    return navigator.clipboard.writeText(text);
  }
  return new Promise(function (resolve, reject) {
    try {
      var ta = document.createElement('textarea');
      ta.value = text;
      ta.setAttribute('readonly', '');
      ta.style.position = 'fixed';
      ta.style.top = '0';
      ta.style.opacity = '0';
      document.body.appendChild(ta);
      ta.select();
      var ok = document.execCommand('copy');
      document.body.removeChild(ta);
      if (ok) resolve();
      else reject(new Error('copy failed'));
    } catch (e) { reject(e); }
  });
}
function copyConvName(i) {
  var meta = CONV_CARDS[i];
  if (!meta) return;
  copyText(meta.name).then(function () {
    toast('已复制会话名');
  }, function () {
    toast('复制失败，请长按会话名手动复制');
  });
}

// 顶部同步状态行：时间 + 数据量 / 错误（同时播报给读屏）
function renderSyncLine() {
  var el = $('sync-line');
  var live = $('live-status');
  var txt;
  if (LAST_ERR) {
    txt = '⚠️ 同步失败：' + LAST_ERR;
    if (el) { el.className = 'sync-line err'; el.textContent = txt; }
    if (live) live.textContent = '同步失败：' + LAST_ERR;
    return;
  }
  if (el) el.className = 'sync-line';
  if (LOAD_BUSY && LAST_SYNC) txt = '同步中… · 上次同步 ' + fmtClock(LAST_SYNC);
  else if (!LAST_SYNC) txt = '正在同步…';
  else txt = '已同步 ' + fmtClock(LAST_SYNC) + ' · 消息 ' + MSGS.length + ' · 分析 ' + ANALYSES.length;
  if (SW_ERR) txt += ' · ⚠️ 离线缓存不可用';
  if (el) el.textContent = txt;
  if (live) live.textContent = txt;
}

// ---------- 过滤与搜索（全局口径，四个 tab 共用）----------
function filteredMsgs() {
  if (!APP_FILTER) return MSGS;
  return MSGS.filter(function (m) { return normPkg(m.pkg) === APP_FILTER; });
}
function filteredAnalyses() {
  if (!APP_FILTER) return ANALYSES;
  return ANALYSES.filter(function (a) { return normPkg(a.pkg) === APP_FILTER; });
}
function filteredTodos() {
  return filteredAnalyses().filter(needActionOf);
}
// 页签按数据里实际出现的 App 生成（消息 + 分析两侧合并）
function appTabList() {
  var seen = {};
  MSGS.forEach(function (m) { seen[normPkg(m.pkg)] = true; });
  ANALYSES.forEach(function (a) { seen[normPkg(a.pkg)] = true; });
  var tabs = [['', '全部']];
  Object.keys(seen).forEach(function (p) {
    if (p) tabs.push([p, pkgLabel(p)]);
  });
  tabs.sort(function (a, b) {
    if (a[0] === '') return -1;
    if (b[0] === '') return 1;
    if (a[0] === PKG_WX) return -1;
    if (b[0] === PKG_WX) return 1;
    return a[1] < b[1] ? -1 : 1;
  });
  return tabs;
}
function setAppFilter(p) {
  var np = p || '';
  if (np === APP_FILTER) {
    renderFilterBar(true);
    return;
  }
  APP_FILTER = np;
  renderFilterBar(true);
  renderOverview();
  renderBadges();
  renderData();
  toast(APP_FILTER ? ('已筛选：' + pkgLabel(APP_FILTER)) : '已显示全部来源');
}
function clearAppFilter() {
  if (!APP_FILTER) return;
  APP_FILTER = '';
  renderFilterBar(true);
  renderOverview();
  renderBadges();
  renderData();
  toast('已清除来源过滤');
}
// chips 的键盘可达（Enter / 空格）
function onFilterKey(e) {
  if (e.key !== 'Enter' && e.key !== ' ') return;
  e.preventDefault();
  var el = e.currentTarget || e.target;
  var pkg = (el && el.getAttribute) ? (el.getAttribute('data-pkg') || '') : '';
  setAppFilter(pkg);
}
function renderFilterBar(force) {
  var box = $('app-chips');
  if (!box) return;
  var tabs = appTabList();
  var listSig = tabs.map(function (t) { return t[0]; }).join('|');
  // 只有页签集合变了才重建 DOM：重建会把键盘焦点从 chip 上弄丢
  if (force || listSig !== FILTER_SIG) {
    FILTER_SIG = listSig;
    box.innerHTML = tabs.map(function (t) {
      return '<span class="chip-btn" role="button" tabindex="0" aria-pressed="' +
        (APP_FILTER === t[0] ? 'true' : 'false') +
        '" data-pkg="' + esc(t[0]) +
        '" onclick="setAppFilter(this.dataset.pkg)"' +
        ' onkeydown="onFilterKey(event)">' + esc(t[1]) + '</span>';
    }).join('');
  } else {
    // 只更新选中态
    var kids = box.children;
    for (var i = 0; i < kids.length; i++) {
      var p = kids[i].getAttribute('data-pkg') || '';
      kids[i].setAttribute('aria-pressed', APP_FILTER === p ? 'true' : 'false');
    }
  }
  var clr = $('btn-clear-app');
  if (clr) {
    clr.disabled = !APP_FILTER;
    clr.textContent = APP_FILTER ? ('清除：' + pkgLabel(APP_FILTER)) : '清除';
  }
}
function onSearchInput(v) {
  QUERY = v;
  updateSearchUI();
  renderData();
}
function updateSearchUI() {
  var b = $('btn-clear-search');
  if (b) b.disabled = !QUERY.trim();
}
function clearSearch() {
  QUERY = '';
  var i = $('global-search');
  if (i) i.value = '';
  updateSearchUI();
  renderData();
  toast('已清除搜索');
}

// 分析结果的搜索文本：s1 / s2 / case_id / 会话名 / 待办文本
function analysisSearchText(a) {
  var parts = [];
  if (a.case_id) parts.push(String(a.case_id));
  if (a.conversation) parts.push(String(a.conversation));
  if (a.s1) { try { parts.push(JSON.stringify(a.s1)); } catch (e) { } }
  if (a.s2) { try { parts.push(JSON.stringify(a.s2)); } catch (e) { } }
  if (a.todo_text) parts.push(String(a.todo_text));
  if (a.todo_due) parts.push(String(a.todo_due));
  return parts.join(' ').toLowerCase();
}
// 顾问报告的搜索文本：报告内容
function advisorSearchText(r) {
  var parts = [];
  if (r.summary) parts.push(String(r.summary));
  if (r.model_used) parts.push(String(r.model_used));
  if (r.created_at) parts.push(String(r.created_at));
  if (r.case_count != null) parts.push(String(r.case_count));
  if (r.period_days != null) parts.push(String(r.period_days));
  (r.suggestions || []).forEach(function (s) {
    if (!s) return;
    if (s.title) parts.push(String(s.title));
    if (s.detail) parts.push(String(s.detail));
  });
  return parts.join(' ').toLowerCase();
}

// ---------- 首屏概览条 + tab 角标 ----------
function renderOverview() {
  var todos = filteredTodos();
  var today = todos.filter(function (a) { return isToday(a.received_at || 0); });
  var analyses = filteredAnalyses();
  var msgs = filteredMsgs();
  var unread = 0;
  analyses.forEach(function (a) { if (NEW_CASES[analysisKey(a)]) unread++; });
  var todoEl = $('ov-todo'), anaEl = $('ov-analysis');
  var msgEl = $('ov-msgs'), syncEl = $('ov-sync');
  if (todoEl) todoEl.textContent = String(today.length);
  if (anaEl) anaEl.textContent = String(unread);
  if (msgEl) msgEl.textContent = String(msgs.length);
  if (syncEl) syncEl.textContent = LAST_SYNC ? fmtClock(LAST_SYNC) : '—';
  var tSub = $('ov-todo-sub');
  if (tSub) tSub.textContent = todos.length > today.length ? ('共 ' + todos.length + ' 条待办') : '';
  var aSub = $('ov-analysis-sub');
  if (aSub) aSub.textContent = '上次同步后新增';
  var mSub = $('ov-msgs-sub');
  if (mSub) mSub.textContent = APP_FILTER ? pkgLabel(APP_FILTER) : '全部来源';
  var sSub = $('ov-sync-sub');
  if (sSub) sSub.textContent = LAST_ERR ? '同步失败' : (LOAD_BUSY ? '同步中' : '');
}
function setBadge(id, n) {
  var el = $(id);
  if (!el) return;
  if (!n) { el.textContent = ''; return; }
  el.textContent = n > 99 ? '99+' : String(n);
}
function renderBadges() {
  setBadge('badge-conv', filteredMsgs().length);
  setBadge('badge-todo', filteredTodos().length);
  setBadge('badge-stat', filteredAnalyses().length);
  setBadge('badge-adv', ADVISORS.length);
  var pairs = [
    ['tab-conv', '会话', filteredMsgs().length, '条消息'],
    ['tab-todo', '待办', filteredTodos().length, '条待办'],
    ['tab-stat', '统计', filteredAnalyses().length, '条分析'],
    ['tab-adv', '顾问', ADVISORS.length, '份报告']
  ];
  pairs.forEach(function (p) {
    var b = $(p[0]);
    if (b) b.setAttribute('aria-label', p[1] + '，' + p[2] + ' ' + p[3]);
  });
}

// ---------- 数据加载（并发 + 序号 + AbortController）----------
// 分页拉完消息：服务端的 next_before 是 <毫秒>_<rowid> 复合游标。
// 只取一页 -> 超过一页的历史消息在控制台里永远看不到。
async function fetchAllMessages(signal) {
  var all = [], before = null, guard = 0;
  while (guard++ < 100) {
    var url = 'messages?limit=500';
    if (before) url += '&before=' + encodeURIComponent(before);
    var r = await api(url, { signal: signal });
    var part = r.messages || [];
    all = all.concat(part);
    if (!r.next_before || !part.length) break;
    before = r.next_before;
  }
  return all;
}

async function load(reason) {
  // 没有令牌就别去请求：每 30 秒撞一次 401 只会把用户正在输入的令牌冲掉
  if (!TOKEN) { renderAuth('请输入访问令牌后进入控制台'); return; }
  var seq = ++LOAD_SEQ;
  // 新一轮开始时中止上一轮未完成的请求
  if (LOAD_CTRL) {
    try { LOAD_CTRL.abort(); } catch (e) { }
  }
  var ctrl = new AbortController();
  LOAD_CTRL = ctrl;
  LOAD_BUSY = true;
  renderSyncLine();
  renderOverview();
  try {
    // 三个接口并发：原来串行 await，一个慢接口会拖住整轮
    var res = await Promise.all([
      fetchAllMessages(ctrl.signal),
      api('analysis?limit=1000', { signal: ctrl.signal }),
      // TODO: 服务端本轮可能给 /analysis 增加可选 before/date 参数，
      // 前端暂不依赖（当前 limit=1000 已经够用）。
      api('advisor?limit=100', { signal: ctrl.signal })
    ]);
    // 旧响应后到 -> 丢弃，不能覆盖新数据
    if (seq !== LOAD_SEQ) return;
    MSGS = res[0] || [];
    ANALYSES = (res[1] && res[1].analyses) || [];
    ADVISORS = (res[2] && res[2].reports) || [];
    AUTH_MODE = false;
    // 「未读分析」= 上次同步之后才到达的分析（首次同步时全部算新增）
    NEW_CASES = {};
    ANALYSES.forEach(function (a) {
      if (!PREV_SYNC || (a.received_at || 0) > PREV_SYNC) NEW_CASES[analysisKey(a)] = true;
    });
    todoByConv = {};
    ANALYSES.forEach(function (x) {
      if (needActionOf(x)) {
        // 同一复合会话保留 received_at 最新的一条
        var ck = ckOf(x);
        if (!todoByConv[ck] ||
            (x.received_at || 0) > (todoByConv[ck].received_at || 0)) {
          todoByConv[ck] = x;
        }
      }
    });
    LAST_SYNC = Date.now();
    PREV_SYNC = LAST_SYNC;
    LAST_ERR = '';
    LAST_ERR_KIND = '';
    LOAD_BUSY = false;
    render();
  } catch (e) {
    if (seq !== LOAD_SEQ) return;           // 已被新一轮接管
    if (e && e.kind === 'abort') return;    // 主动中止，不算错误
    LOAD_BUSY = false;
    LAST_ERR = (e && e.message) ? e.message : '未知错误';
    LAST_ERR_KIND = (e && e.kind) ? e.kind : 'network';
    if (LAST_ERR_KIND === 'unauthorized') {
      AUTH_MODE = true;
      renderAuth('令牌无效或已过期，请重新输入令牌');
      renderSyncLine();
      renderOverview();
      renderFilterBar(true);
      renderBadges();
      return;
    }
    renderSyncLine();
    renderOverview();
    renderData();
  }
}
function retryLoad() {
  toast('正在重试');
  load('retry');
}

// ---------- 下拉刷新（触摸实现，PWA / iPhone Safari 通用）----------
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
      load('ptr').finally(function () {
        ptrText.textContent = '已更新';
        setTimeout(function () { ptr.classList.remove('show'); }, 600);
      });
    } else {
      ptr.classList.remove('show');
    }
  }, { passive: true });
})();

// ---------- 渲染 ----------
// 只重绘数据区 #data：过滤条 / 搜索框 / 概览条都在 #data 之外，
// 不会被整块 innerHTML 换掉（焦点、光标、输入内容都不丢）
function renderData() {
  if (AUTH_MODE) { renderAuth('令牌无效或已过期，请重新输入令牌'); return; }
  if (!TOKEN) { renderAuth('请输入访问令牌后进入控制台'); return; }
  var el = $('data');
  if (!el) return;
  var errHtml = '';
  if (LAST_ERR_KIND && LAST_ERR_KIND !== 'unauthorized') {
    errHtml = errorState(LAST_ERR_KIND, LAST_ERR);
  }
  var hasData = MSGS.length || ANALYSES.length || ADVISORS.length;
  // 只有错误、没有旧数据 -> 只显示错误态；错误 + 旧数据 -> 错误态在上、数据在下
  if (errHtml && !hasData) {
    el.innerHTML = errHtml;
    return;
  }
  // 重绘前记住搜索框的焦点与光标（保险：即使将来把搜索框挪进 #data 也不丢）
  var ae = document.activeElement;
  var keep = (ae && ae.id === 'global-search') ? ae : null;
  var selS = keep ? keep.selectionStart : null;
  var selE = keep ? keep.selectionEnd : null;
  el.innerHTML = errHtml + renderTabHtml();
  if (keep && document.activeElement !== keep) {
    keep.focus();
    try { keep.setSelectionRange(selS, selE); } catch (e) { }
  }
}
function render() {
  renderSyncLine();
  renderOverview();
  renderFilterBar(false);
  renderBadges();
  renderData();
}
function renderTabHtml() {
  if (CUR_TAB === 'conv') return renderConvs();
  if (CUR_TAB === 'todo') return renderTodos();
  if (CUR_TAB === 'stat') return renderStats();
  return renderAdvisor();
}
function switchTab(t) {
  CUR_TAB = t;
  ['conv', 'todo', 'stat', 'adv'].forEach(function (k) {
    var b = $('tab-' + k);
    if (!b) return;
    var on = (k === t);
    b.className = 'tab' + (on ? ' active' : '');
    b.setAttribute('aria-selected', on ? 'true' : 'false');
    b.setAttribute('tabindex', on ? '0' : '-1');
  });
  var panel = $('data');
  if (panel) panel.setAttribute('aria-labelledby', 'tab-' + t);
  window.scrollTo(0, 0);
  render();
}

// ---------- 空态 / 错误态 / 骨架屏 ----------
function emptyState(title, desc, next, actions) {
  return '<div class="state state-empty">' +
    '<div class="state-ico" aria-hidden="true">🗂️</div>' +
    '<div class="state-title">' + esc(title) + '</div>' +
    '<div class="state-desc">' + esc(desc) + '</div>' +
    (next ? '<div class="state-next">下一步：' + esc(next) + '</div>' : '') +
    (actions ? '<div class="state-actions">' + actions + '</div>' : '') +
    '</div>';
}
function errorState(kind, msg) {
  var meta = {
    unauthorized: { ico: '🔒', title: '令牌无效', desc: '访问令牌缺失或不匹配，服务端拒绝了这次请求。' },
    not_configured: { ico: '⚙️', title: '服务端未配置令牌', desc: '服务端没有设置 WEIXIN_TOKEN，无法提供任何数据。请在服务端配置环境变量后重启。' },
    http: { ico: '⚠️', title: '接口返回错误', desc: '服务器处理请求时出错了，稍后重试；若持续失败请查看服务端日志。' },
    network: { ico: '📡', title: '网络错误', desc: '连不上服务器，请检查手机网络、服务是否在运行，以及是否处于离线状态。' }
  }[kind] || { ico: '⚠️', title: '出错了', desc: '发生未知错误。' };
  return '<div class="state state-error" role="alert">' +
    '<div class="state-ico" aria-hidden="true">' + meta.ico + '</div>' +
    '<div class="state-title">' + esc(meta.title) + '</div>' +
    '<div class="state-desc">' + esc(meta.desc) + '</div>' +
    (msg ? '<div class="state-next">' + esc(msg) + '</div>' : '') +
    '<div class="state-actions">' +
    '<button type="button" class="btn btn-primary" onclick="retryLoad()">重试</button>' +
    '</div></div>';
}
function skeletonHtml(n) {
  var h = '<div aria-hidden="true">';
  for (var i = 0; i < (n || 4); i++) {
    h += '<div class="sk-card"><div class="sk-line w60"></div>' +
      '<div class="sk-line w90"></div><div class="sk-line w30"></div></div>';
  }
  return h + '</div>';
}
function emptyStateFor(tab) {
  var q = QUERY.trim();
  var acts = '';
  if (q) acts += '<button type="button" class="btn btn-secondary" onclick="clearSearch()">清除搜索</button>';
  if (APP_FILTER) acts += '<button type="button" class="btn btn-secondary" onclick="clearAppFilter()">查看全部 App</button>';
  if (q) {
    return emptyState('没有匹配结果',
      '当前搜索「' + q + '」没有匹配的内容。',
      '换个关键词，或点「清除搜索」回到完整列表。', acts);
  }
  if (APP_FILTER) {
    return emptyState('该来源下暂无数据',
      '当前只看「' + pkgLabel(APP_FILTER) + '」，这个来源下暂时没有可展示的内容。',
      '点「查看全部 App」切回全部来源，或在过滤条上换一个来源。', acts);
  }
  var defaults = {
    conv: ['还没有消息', '服务器上还没有采集到通知消息，控制台因此是空的。',
      '在手机端开启通知监听并同步一次，或确认服务端数据目录里有 messages.db。'],
    todo: ['今天没有待办', '当前没有标记为「需要行动」的分析结果。',
      '保持通知监听开启，新的重要消息会自动出现在这里。'],
    stat: ['还没有统计数据', '拉到消息与分析数据后，这里会显示分布统计。',
      '下拉刷新或点右上角刷新按钮重新拉取数据。'],
    adv: ['还没有顾问报告', '顾问复盘报告由手机端定时生成并上报，生成后会出现在这里。',
      '等端上跑完一轮复盘，或确认端上「顾问」任务已开启。']
  }[tab] || ['暂无数据', '当前没有可展示的内容。', '下拉刷新试试。'];
  return emptyState(defaults[0], defaults[1], defaults[2], acts);
}
function refreshNote() {
  return '<div class="refresh-note">下拉可刷新 · 每 30 秒自动同步</div>';
}

// ---------- 会话 tab：需关注分区 + 会话/日期分层折叠 ----------
function renderConvs() {
  CONV_NODES = [];
  CONV_CARDS = [];
  var q = QUERY.trim().toLowerCase();
  var groups = {}, order = [];
  filteredMsgs().forEach(function (m) {
    var ck = ckOf(m);
    if (!groups[ck]) { groups[ck] = []; order.push(ck); }
    groups[ck].push(m);
  });
  var watch = [], rest = [];
  order.forEach(function (ck) {
    var list = groups[ck];
    var name = list[0].conversation == null ? '' : String(list[0].conversation);
    if (q) {
      // 会话名命中 -> 整组保留；否则只保留发送者/内容命中的消息
      if (name.toLowerCase().indexOf(q) < 0) {
        list = list.filter(function (m) {
          return String(m.sender || '').toLowerCase().indexOf(q) >= 0 ||
                 String(m.text || '').toLowerCase().indexOf(q) >= 0;
        });
        if (!list.length) return;
      }
    }
    var item = { ck: ck, name: name, list: list };
    if (todoByConv[ck]) watch.push(item);
    else rest.push(item);
  });
  var html = '';
  if (watch.length) {
    html += '<div class="sec-head warn">⚡ 需关注（' + watch.length + '）</div>' +
      '<div class="cards">' + watch.map(renderConvCard).join('') + '</div>';
  }
  if (rest.length) {
    html += '<div class="sec-head">全部会话（' + rest.length + '）</div>' +
      '<div class="cards">' + rest.map(renderConvCard).join('') + '</div>';
  }
  if (!html) html = emptyStateFor('conv');
  return html + refreshNote();
}

// 一级折叠组：会话（默认折叠，显示名称 + 条数 + 最新摘要 + 相对时间）；
// 展开后有 need_action 分析块 + 按日期的二级折叠组
function renderConvCard(item) {
  var ck = item.ck, name = item.name, list = item.list;
  var key = 'c:' + ck;
  var nodeIdx = CONV_NODES.length;
  CONV_NODES.push(key);
  var cardIdx = CONV_CARDS.length;
  CONV_CARDS.push({ ck: ck, name: name });
  var open = !!COLLAPSE[key];
  var latest = list[0];
  var todo = todoByConv[ck];
  // App 角标：微信不显示（主场景），其它 App 显示中文名，防止同名会话混淆
  var appBadge = normPkg(latest.pkg) === PKG_WX ? '' :
    '<span class="badge b-app">' + esc(pkgLabel(latest.pkg)) + '</span>';
  var html = '<div class="card"><div class="conv-row">' +
    '<div class="conv-head" role="button" tabindex="0" aria-expanded="' +
    (open ? 'true' : 'false') + '" data-node="' + nodeIdx + '"' +
    ' onclick="toggleNode(' + nodeIdx + ')" onkeydown="onNodeKey(event, ' + nodeIdx + ')">' +
    '<span class="caret" id="caret-' + nodeIdx + '" aria-hidden="true">' + (open ? '▾' : '▸') + '</span>' +
    '<span class="conv-main">' +
    '<span class="conv-title-row">' + appBadge +
    '<span class="conv-name">' + esc(name) + '</span>' +
    (todo ? '<span class="todo-flag" aria-label="有待办">⚡</span>' : '') +
    '</span>' +
    '<span class="conv-preview"><span class="pv-text">' +
    esc(latest.sender) + '：' + esc(latest.text) + '</span>' +
    '<span class="pv-time">' + esc(relTime(latest.timestamp)) + '</span></span>' +
    '</span>' +
    '<span class="conv-count">' + list.length + ' 条</span></div>' +
    '<button type="button" class="copy-btn" aria-label="复制会话名" ' +
    'onclick="copyConvName(' + cardIdx + ')">⧉</button></div>' +
    '<div class="msg-list" id="node-' + nodeIdx + '"' + (open ? '' : ' hidden') + '>';
  if (todo) html += renderAnalysisBlock(todo);
  // 二级折叠组：按日期分组（list 已按时间倒序，日期组同样倒序）
  var byDate = {}, dOrder = [];
  list.forEach(function (m) {
    var d = dateKey(m.timestamp);
    if (!byDate[d]) { byDate[d] = []; dOrder.push(d); }
    byDate[d].push(m);
  });
  dOrder.forEach(function (d) {
    var dIdx = CONV_NODES.length;
    CONV_NODES.push(key + '|' + d);
    var dOpen = !!COLLAPSE[key + '|' + d];
    html += '<div class="date-head" role="button" tabindex="0" aria-expanded="' +
      (dOpen ? 'true' : 'false') + '" data-node="' + dIdx + '"' +
      ' onclick="toggleNode(' + dIdx + ')" onkeydown="onNodeKey(event, ' + dIdx + ')">' +
      '<span class="caret" id="caret-' + dIdx + '" aria-hidden="true">' + (dOpen ? '▾' : '▸') + '</span>' +
      '<span class="conv-name">' + esc(fmtDate(byDate[d][0].timestamp)) + '</span>' +
      '<span class="conv-count">' + byDate[d].length + ' 条</span></div>' +
      '<div class="date-body" id="node-' + dIdx + '"' + (dOpen ? '' : ' hidden') + '>';
    byDate[d].forEach(function (m) {
      var badges = (m.is_group ? '<span class="badge b-grp">群</span>' : '') +
        (normPkg(m.pkg) !== PKG_WX ? '<span class="badge b-app">' +
          esc(pkgLabel(m.pkg)) + '</span>' : '') +
        (m.source === 'android-db' ? '<span class="badge b-db">库</span>' : '');
      html += '<div class="msg"><div class="m-meta">' + badges + esc(m.sender) +
        ' · ' + esc(fmtTime(m.timestamp)) + '</div>' +
        '<div class="m-text">' + esc(m.text) + '</div></div>';
    });
    html += '</div>';
  });
  return html + '</div></div>';
}

// 折叠头键盘可达（Enter / 空格）
function onNodeKey(e, i) {
  if (e.key === 'Enter' || e.key === ' ') {
    e.preventDefault();
    toggleNode(i);
  }
}
// 折叠 / 展开切换：更新状态、持久化、只动对应节点（不重绘整页）
function toggleNode(i) {
  var key = CONV_NODES[i];
  if (key == null) return;
  COLLAPSE[key] = !COLLAPSE[key];
  LS.set('wx_collapse_v1', JSON.stringify(COLLAPSE));
  var open = !!COLLAPSE[key];
  var el = $('node-' + i);
  if (el) el.hidden = !open;
  var caret = $('caret-' + i);
  if (caret) caret.textContent = open ? '▾' : '▸';
  var heads = document.querySelectorAll('[data-node="' + i + '"]');
  for (var k = 0; k < heads.length; k++) {
    heads[k].setAttribute('aria-expanded', open ? 'true' : 'false');
  }
  toast(open ? '已展开' : '已收起');
}

// ---------- 分析结果块：S1 chips + S2 区 ----------
function impLabel(v) {
  // S1 重要度可能是 0-9 数值（端上）或 high/mid/low（旧格式），统一成标签。
  if (v === null || v === undefined || v === '') return '';
  var n = (typeof v === 'number') ? v :
    (/^[0-9]+$/.test(String(v).trim()) ? parseInt(String(v).trim(), 10) : NaN);
  if (!isNaN(n)) return n >= 6 ? 'high' : (n >= 3 ? 'mid' : 'low');
  return String(v).trim().toLowerCase();
}
function impCls(v) {
  var i = impLabel(v);
  if (i === 'high') return 'imp-high';
  if (i === 'mid') return 'imp-mid';
  return '';
}

function renderAnalysisBlock(a) {
  var html = '<div class="todo-block">';
  if (a.s1) {
    var s1 = a.s1, chips = '';
    if (s1.need_action) {
      var prob = Number(s1.need_action.prob);
      chips += '<span class="chip warn">⚡待办 ' +
        (isNaN(prob) ? '—' : Math.round(prob * 100) + '%') + '</span>';
    }
    var impVal = importanceOf(a);
    if (impVal) {
      chips += '<span class="chip' + (impLabel(impVal) === 'high' ? ' warn' : '') +
        '">重要度 ' + esc(impVal) + '</span>';
    }
    if (s1.due_window) chips += '<span class="chip">截止 ' + esc(s1.due_window) + '</span>';
    if (s1.topic) chips += '<span class="chip">' + esc(s1.topic) + '</span>';
    if (chips) html += '<div class="chips">' + chips + '</div>';
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
        html += '<li>' + esc(typeof t === 'string' ? t : (t && t.task ? t.task : JSON.stringify(t))) + '</li>';
      });
      html += '</ul></div>';
    }
    html += '</div>';
  }
  return html + '</div>';
}

// ---------- 待办 tab ----------
function renderTodos() {
  var q = QUERY.trim().toLowerCase();
  var todos = filteredTodos().filter(function (a) {
    if (!q) return true;
    return analysisSearchText(a).indexOf(q) >= 0;
  });
  // 重要度 high 优先，同级按上报时间倒序（最新在前）
  var impRank = { high: 0, mid: 1, low: 2 };
  todos.sort(function (a, b) {
    var ra = impRank[impLabel(importanceOf(a))];
    var rb = impRank[impLabel(importanceOf(b))];
    if (ra == null) ra = 3;
    if (rb == null) rb = 3;
    if (ra !== rb) return ra - rb;
    return (b.received_at || 0) - (a.received_at || 0);
  });
  if (!todos.length) return emptyStateFor('todo') + refreshNote();
  var html = '<div class="cards">';
  todos.forEach(function (a) {
    var appBadge = normPkg(a.pkg) === PKG_WX ? '' :
      '<span class="badge b-app">' + esc(pkgLabel(a.pkg)) + '</span>';
    html += '<div class="card">' +
      '<div class="conv-row"><div class="conv-head">' +
      '<span class="conv-main"><span class="conv-title-row">' + appBadge +
      '<span class="conv-name">' + esc(a.conversation) + '</span>' +
      '<span class="' + impCls(importanceOf(a)) + '">' +
      esc(importanceOf(a)) + '</span></span></span>' +
      '<span class="conv-count">窗口消息 ' + esc(a.message_count == null ? 0 : a.message_count) + ' 条</span>' +
      '</div></div>' +
      renderAnalysisBlock(a) +
      '<div class="ana-meta">分析于 ' + esc(a.analyzed_at || '') +
      ' · case ' + esc(a.case_id || '') + '</div></div>';
  });
  return html + '</div>' + refreshNote();
}

// ---------- 统计 tab ----------
function renderStats() {
  var q = QUERY.trim().toLowerCase();
  var msgs = filteredMsgs();
  var analyses = filteredAnalyses();
  var total = msgs.length;
  var bySrc = {};
  var byApp = {};
  var lastReport = 0;
  msgs.forEach(function (m) {
    var src = m.source || 'unknown';
    bySrc[src] = (bySrc[src] || 0) + 1;
    var p = normPkg(m.pkg);
    byApp[p] = (byApp[p] || 0) + 1;
    if ((m.received_at || 0) > lastReport) lastReport = m.received_at;
  });
  var analyzedConvs = {};
  analyses.forEach(function (a) { analyzedConvs[ckOf(a)] = 1; });
  function cell(num, label) {
    return '<div class="card"><div class="stat-num">' + esc(num) + '</div>' +
      '<div class="stat-label">' + esc(label) + '</div></div>';
  }
  var srcTxt = Object.keys(bySrc).map(function (k) {
    return (k === 'android-db' ? '数据库' : k === 'android-notification' ? '通知' : k) +
      ' ' + bySrc[k];
  }).join(' / ');
  var appTxt = Object.keys(byApp).map(function (k) {
    return pkgLabel(k) + ' ' + byApp[k];
  }).join(' / ');
  var html = '<div class="stat-grid">' +
    cell(total, '消息总数') +
    cell(Object.keys(analyzedConvs).length, '已分析会话数') +
    cell(analyses.length, '分析结果数') +
    cell(lastReport ? fmtTime(lastReport) : '—', '最近上报时间') +
    '</div><div class="card"><div class="stat-label">应用分布</div>' +
    '<div class="stat-value">' + esc(appTxt || '—') + '</div></div>' +
    '<div class="card"><div class="stat-label">来源分布</div>' +
    '<div class="stat-value">' + esc(srcTxt || '—') + '</div></div>';
  // 最近分析结果（供全局搜索按 s1 / s2 / case_id 命中）
  var list = analyses.filter(function (a) {
    if (!q) return true;
    return analysisSearchText(a).indexOf(q) >= 0;
  });
  html += '<div class="sec-head">最近分析结果（' + list.length + '）</div>';
  if (!list.length) {
    html += emptyStateFor('stat');
  } else {
    html += '<div class="cards">';
    list.slice(0, 30).forEach(function (a) {
      var appBadge = normPkg(a.pkg) === PKG_WX ? '' :
        '<span class="badge b-app">' + esc(pkgLabel(a.pkg)) + '</span>';
      var summary = (a.s2 && a.s2.summary) ? a.s2.summary : '';
      html += '<div class="card">' +
        '<div class="ana-head">' + appBadge +
        '<span class="conv-name">' + esc(a.conversation) + '</span>' +
        '<span class="conv-count">' + esc(a.case_id || '') + '</span></div>' +
        '<div class="ana-meta">重要度 ' + esc(importanceOf(a) || '—') +
        ' · 分析于 ' + esc(a.analyzed_at || '') +
        ' · 消息 ' + esc(a.message_count == null ? 0 : a.message_count) + ' 条</div>' +
        (summary ? '<div class="conv-preview"><span class="pv-text">' +
          esc(summary) + '</span></div>' : '') +
        '</div>';
    });
    html += '</div>';
  }
  return html + refreshNote();
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
  var q = QUERY.trim().toLowerCase();
  var reports = ADVISORS.filter(function (r) {
    // 顾问报告本身不带来源 App 字段：有 pkg 时按同一口径过滤，没有就整体保留
    if (APP_FILTER && r.pkg && normPkg(r.pkg) !== APP_FILTER) return false;
    if (!q) return true;
    return advisorSearchText(r).indexOf(q) >= 0;
  });
  if (!reports.length) return emptyStateFor('adv') + refreshNote();
  var html = '';
  if (APP_FILTER) {
    html += '<div class="note">顾问报告不携带来源 App 字段，无法按「' +
      esc(pkgLabel(APP_FILTER)) + '」过滤，下面显示的是全部报告。</div>';
  }
  html += '<div class="cards">';
  reports.forEach(function (r) {
    // 建议按 type 分组展示
    var groups = {}, order = [];
    (r.suggestions || []).forEach(function (s) {
      if (!s) return;
      var t = ADV_TYPE_META[s.type] ? s.type : 'other';
      if (!groups[t]) { groups[t] = []; order.push(t); }
      groups[t].push(s);
    });
    html += '<div class="card">' +
      '<div class="conv-row"><div class="conv-head">' +
      '<span class="conv-main"><span class="conv-title-row">' +
      '<span class="conv-name">🧭 复盘报告</span></span></span>' +
      '<span class="conv-count">' + esc(r.created_at || '') + '</span>' +
      '</div></div>' +
      '<div class="chips">' +
      '<span class="chip">模型 ' + esc(r.model_used || '—') + '</span>' +
      '<span class="chip">覆盖 ' + esc(String(parseInt(r.case_count, 10) || 0)) +
      ' 个 case</span>' +
      (parseInt(r.period_days, 10) > 0
        ? '<span class="chip">近 ' + esc(String(parseInt(r.period_days, 10))) + ' 天</span>'
        : '') +
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
  return html + '</div>' + refreshNote();
}

// ---------- tab 栏方向键切换 ----------
(function bindTabKeys() {
  var bar = document.querySelector('.tabbar');
  if (!bar) return;
  bar.addEventListener('keydown', function (e) {
    if (e.key !== 'ArrowLeft' && e.key !== 'ArrowRight') return;
    var keys = ['conv', 'todo', 'stat', 'adv'];
    var i = keys.indexOf(CUR_TAB);
    if (i < 0) i = 0;
    var n = (e.key === 'ArrowLeft') ? (i - 1 + keys.length) % keys.length : (i + 1) % keys.length;
    e.preventDefault();
    switchTab(keys[n]);
    var b = $('tab-' + keys[n]);
    if (b) b.focus();
  });
})();

// ---------- 启动 ----------
(function boot() {
  // PWA 快捷方式 ?tab=conv|todo|stat|adv
  var t = new URLSearchParams(window.location.search).get('tab');
  if (t && ['conv', 'todo', 'stat', 'adv'].indexOf(t) >= 0) {
    CUR_TAB = t;
    ['conv', 'todo', 'stat', 'adv'].forEach(function (k) {
      var b = $('tab-' + k);
      if (!b) return;
      var on = (k === t);
      b.className = 'tab' + (on ? ' active' : '');
      b.setAttribute('aria-selected', on ? 'true' : 'false');
      b.setAttribute('tabindex', on ? '0' : '-1');
    });
    var panel = $('data');
    if (panel) panel.setAttribute('aria-labelledby', 'tab-' + t);
  }
  updateSearchUI();
  if (!TOKEN) {
    renderAuth('请输入访问令牌后进入控制台');
    renderSyncLine();
    renderOverview();
    renderFilterBar(true);
    return;
  }
  // 首次加载：骨架屏（DESIGN-10）
  $('data').innerHTML = skeletonHtml(4);
  renderSyncLine();
  renderOverview();
  renderFilterBar(true);
  load('init');
})();

// 30 秒自动刷新：上一轮还在飞就跳过，不叠加并发
setInterval(function () {
  if (LOAD_BUSY) return;
  load('auto');
}, 30000);

// 注册 service worker（PWA 可安装 / 离线骨架）
// 同样基于 __WX_DIR：/console -> /sw.js（scope /）；
// /mmonitor/console -> /mmonitor/sw.js（scope /mmonitor/），均可控制本页
if ('serviceWorker' in navigator) {
  navigator.serviceWorker.register(window.__WX_DIR + 'sw.js').catch(function (err) {
    // 静默吞错会让离线能力出问题却没人知道：打日志 + 在同步行提示
    console.error('Service Worker 注册失败', err);
    SW_ERR = 'SW 注册失败';
    renderSyncLine();
  });
}
</script>
</body>
</html>"""

CONSOLE_MANIFEST = {
    "name": "微信分析助手",
    "short_name": "微信分析助手",
    "description": "微信消息监测与分析结果查看端",
    # 相对路径：相对 manifest 自身 URL 解析（/console/manifest.json -> /console/；
    # /mmonitor/console/manifest.json -> /mmonitor/console/），两种挂载方式都正确
    "id": "./",
    "lang": "zh-CN",
    "dir": "ltr",
    "start_url": "./",
    "scope": "./",
    "display": "standalone",
    # 锁竖屏会和 DESIGN-11 的宽屏两栏布局冲突，所以用 any
    "orientation": "any",
    "categories": ["productivity", "utilities"],
    "background_color": "#f5f5f5",
    "theme_color": "#07C160",
    "icons": [
        {"src": "icons/icon-192.png", "sizes": "192x192", "type": "image/png",
         "purpose": "any"},
        {"src": "icons/icon-512.png", "sizes": "512x512", "type": "image/png",
         "purpose": "any"},
        {"src": "icons/icon-512-maskable.png", "sizes": "512x512",
         "type": "image/png", "purpose": "maskable"},
        # SVG data URI 兜底（Android Chrome 可用；iOS apple-touch-icon 只认 PNG，
        # 见 CONSOLE_HTML 注入的 link）
        {"src": "data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' "
                "width='512' height='512'%3E%3Crect width='512' height='512' "
                "rx='100' fill='%2307C160'/%3E%3Ctext x='256' y='330' "
                "font-size='260' text-anchor='middle'%3E%F0%9F%92%AC%3C/text%3E%3C/svg%3E",
         "sizes": "any", "type": "image/svg+xml", "purpose": "any"},
    ],
    # 快捷方式：直接落到对应 tab（页面读 ?tab= 切换视图）
    "shortcuts": [
        {"name": "会话", "short_name": "会话", "url": "./?tab=conv",
         "icons": [{"src": "icons/icon-192.png", "sizes": "192x192",
                    "type": "image/png"}]},
        {"name": "待办", "short_name": "待办", "url": "./?tab=todo",
         "icons": [{"src": "icons/icon-192.png", "sizes": "192x192",
                    "type": "image/png"}]},
        {"name": "统计", "short_name": "统计", "url": "./?tab=stat",
         "icons": [{"src": "icons/icon-192.png", "sizes": "192x192",
                    "type": "image/png"}]},
    ],
}

CONSOLE_SW = r"""var CACHE = 'wx-console-v5';
// 只有控制台骨架（/console 或 /console/，含前缀挂载）才允许进缓存。
// 落地页 / 里内联了最近 100 条消息正文，缓存它等于把明文留在磁盘上，
// 无令牌也能从 Cache Storage 里读出来（P0-1）。
function isConsoleShell(pathname) {
  return /\/console\/?$/.test(pathname);
}
function cacheable(resp) {
  if (!resp || !resp.ok) return false;
  var cc = resp.headers.get('Cache-Control') || '';
  if (cc.indexOf('no-store') >= 0 || cc.indexOf('private') >= 0) return false;
  return true;
}
// 离线兜底页：控制台壳也没缓存时返回它。
// 原来直接 return caches.match(...) ，未命中时得到 undefined，
// respondWith(undefined) 会抛 TypeError，页面直接白屏。
var OFFLINE_HTML = '<!DOCTYPE html><html lang="zh-CN"><head><meta charset="utf-8">' +
  '<meta name="viewport" content="width=device-width,initial-scale=1">' +
  '<title>离线 - 微信分析助手</title>' +
  '<style>body{margin:0;padding:24px;min-height:100vh;display:flex;align-items:center;' +
  'justify-content:center;background:#f2f4f5;color:#1f2328;' +
  'font-family:-apple-system,BlinkMacSystemFont,sans-serif}' +
  '.box{max-width:360px;background:#fff;border:1px solid #d8dee3;border-radius:12px;' +
  'padding:20px;text-align:center}' +
  'h1{font-size:20px;margin:0 0 8px}' +
  'p{font-size:14px;line-height:1.6;color:#5b6570;margin:0 0 16px}' +
  'button{min-height:44px;padding:0 20px;font-size:16px;border:0;border-radius:8px;' +
  'background:#07c160;color:#fff}' +
  '@media (prefers-color-scheme:dark){body{background:#0f1317;color:#e9edf1}' +
  '.box{background:#171c22;border-color:#2a323b}p{color:#a9b4c0}' +
  'button{background:#2fbf72;color:#06130b}}</style>' +
  '</head><body><div class="box"><h1>离线中</h1>' +
  '<p>没有网络连接，且本地还没有缓存控制台页面。恢复网络后重试。</p>' +
  '<button onclick="location.reload()">重试</button></div></body></html>';
function offlineResponse() {
  return new Response(OFFLINE_HTML, {
    status: 200,
    headers: { 'Content-Type': 'text/html; charset=utf-8' }
  });
}
// 控制台壳的候选缓存键：当前路径 -> 去尾斜杠 -> 前缀挂载下的 /console 与 /console/
function shellCandidates(pathname) {
  var out = [];
  if (pathname) out.push(pathname);
  var noSlash = String(pathname || '').replace(/\/$/, '');
  if (noSlash && noSlash !== pathname) out.push(noSlash);
  if (/\/console$/.test(noSlash)) {
    out.push(noSlash + '/');
    out.push(noSlash.replace(/\/console$/, '/console'));
  }
  out.push('/console');
  out.push('/console/');
  return out;
}
// 离线时的回退顺序：精确路径缓存 -> 其它壳缓存 -> 内置离线页（绝不 undefined）
function matchShell(pathname) {
  var cands = shellCandidates(pathname);
  var i = 0;
  function next() {
    if (i >= cands.length) return Promise.resolve(null);
    var c = cands[i++];
    return caches.match(c, { ignoreSearch: true }).then(function (hit) {
      return hit || next();
    });
  }
  return next();
}
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
  // 页面导航：只接管控制台骨架，且改成网络优先（离线时回缓存）。
  // 原来是 cache-first + 无差别拦截导航 → 落地页 HTML 被写进缓存。
  if (e.request.mode === 'navigate') {
    if (!isConsoleShell(url.pathname)) return;  // 交给浏览器默认行为
    e.respondWith(
      fetch(e.request).then(function (resp) {
        if (cacheable(resp)) {
          var copy = resp.clone();
          caches.open(CACHE).then(function (c) { c.put(url.pathname, copy); });
        }
        return resp;
      }).catch(function () {
        return matchShell(url.pathname).then(function (hit) {
          return hit || offlineResponse();
        });
      })
    );
    return;
  }
  // 控制台静态资源：缓存优先，命中后后台回填
  if (url.pathname.indexOf('/console/') >= 0) {
    e.respondWith(
      caches.open(CACHE).then(function (cache) {
        return cache.match(e.request).then(function (hit) {
          var net = fetch(e.request).then(function (resp) {
            if (cacheable(resp)) cache.put(e.request, resp.clone());
            return resp;
          }).catch(function () {
            // hit 也可能为空：绝不把 undefined 交给 respondWith（会抛 TypeError 白屏）
            return hit || new Response('', { status: 503, statusText: 'offline' });
          });
          return hit || net;
        });
      })
    );
    return;
  }
  // 其它请求（manifest / sw.js 等）：网络优先，失败回缓存；再失败给 503，不返回 undefined
  e.respondWith(
    fetch(e.request).catch(function () {
      return caches.match(e.request).then(function (hit) {
        return hit || new Response('', { status: 503, statusText: 'offline' });
      });
    })
  );
});
"""

# 本文件由 server/app.py 通过 importlib 按路径加载，禁止 import / 副作用。
