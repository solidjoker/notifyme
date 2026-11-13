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
<!-- theme-color 跟随当前主题：脚本按主题改写 content（颜色取自 CSS 变量，不在 JS 里写死） -->
<meta name="theme-color" id="meta-theme-color" content="#1a1a1a">
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
<style>:root {
  /* ===== aura 设计令牌：暖灰米白 + 墨黑 + 古铜金（单一字面颜色来源） ===== */
  --theme-color: #1a1a1a;
  --bg: #fafaf7;
  --surface: #ffffff;
  --surface-2: #f2f2ee;
  --surface-3: #e8e8e2;
  --text: #202020;
  --muted: #65655f;
  --line: #e4e4df;
  --line-strong: #c9c9c1;
  --accent: #1a1a1a;
  --accent-strong: #1a1a1a;
  --accent-hover: #333333;
  --accent-text: #1a1a1a;
  --on-accent: #fffdf7;
  --gold: #c9a227;
  --gold-weak: #faf5e2;
  --warn: #8a5200;
  --warn-weak: #fdf3e0;
  --danger: #b3261e;
  --danger-weak: #fdecea;
  --on-danger: #ffffff;
  --info: #0b5cab;
  --info-weak: #e9f1fb;
  --ok: #286447;
  --ok-weak: #e7f4ec;
  --badge-db: #1a56c4;
  --badge-nt: #0f766e;
  --badge-grp: #7c3aed;
  --badge-app: #9a3412;
  --on-badge: #ffffff;
  --focus: #c9a227;
  --shadow: 0 1px 2px rgba(22, 22, 20, 0.04);
  --shadow-lift: 0 8px 24px rgba(22, 22, 20, 0.07);
  --overlay: rgba(22, 22, 20, 0.28);
  --skeleton: #eeeeea;
  --skeleton-hi: #f7f7f2;
  --hair: rgba(22, 22, 20, 0.08);
  --scrim: rgba(250, 250, 247, 0.72);
  --hair-strong: rgba(22, 22, 20, 0.14);

  /* ===== 非颜色令牌：间距 / 圆角 / 字号 / 命中区 / 动效 ===== */
  --tap: 44px;
  --radius-sm: 8px;
  --radius: 12px;
  --radius-lg: 16px;
  --radius-pill: 9999px;
  --space-1: 4px;
  --space-2: 8px;
  --space-3: 12px;
  --space-4: 16px;
  --space-5: 24px;
  --space-6: 32px;
  --space-7: 48px;
  --font-xs: 12px;
  --font-sm: 13px;
  --font-md: clamp(14px, .15vw + 13.5px, 15px);
  --font-lg: clamp(16px, .2vw + 15px, 18px);
  --font-xl: clamp(20px, .45vw + 18px, 23px);
  --font-2xl: clamp(26px, 1.6vw + 22px, 40px);
  --maxw: 1240px;
  --dur: 200ms;
  --ease: cubic-bezier(0.2, 0.7, 0.3, 1);
  --z-top: 20;
  --z-tab: 30;
}

[data-theme="dark"] {
  --theme-color: #181819;
  --bg: #181819;
  --surface: #222223;
  --surface-2: #2b2b2c;
  --surface-3: #343435;
  --text: #f4f4f2;
  --muted: #b6b6b0;
  --line: #3c3c3e;
  --line-strong: #52525a;
  --accent: #ededeb;
  --accent-strong: #ededeb;
  --accent-hover: #ffffff;
  --accent-text: #ededeb;
  --on-accent: #161616;
  --gold: #d8b64a;
  --gold-weak: #322b12;
  --warn: #fbbf24;
  --warn-weak: #3a2f12;
  --danger: #ff7b72;
  --danger-weak: #3d1d1c;
  --on-danger: #2b0a08;
  --info: #79c0ff;
  --info-weak: #10243d;
  --ok: #a3d5b8;
  --ok-weak: #10261a;
  --badge-db: #1a56c4;
  --badge-nt: #0f766e;
  --badge-grp: #7c3aed;
  --badge-app: #9a3412;
  --on-badge: #ffffff;
  --focus: #d8b64a;
  --shadow: 0 1px 2px rgba(0, 0, 0, 0.5);
  --shadow-lift: 0 10px 28px rgba(0, 0, 0, 0.55);
  --overlay: rgba(0, 0, 0, 0.6);
  --skeleton: #2b2b2c;
  --skeleton-hi: #363637;
  --hair: rgba(255, 255, 255, 0.09);
  --scrim: rgba(24, 24, 25, 0.72);
  --hair-strong: rgba(255, 255, 255, 0.18);
}

* { box-sizing: border-box; }

html, body {
  margin: 0;
  padding: 0;
  background: var(--bg);
  color: var(--text);
}

body {
  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", "PingFang SC",
    "Hiragino Sans GB", "Microsoft YaHei", sans-serif;
  font-size: clamp(14px, 0.35vw + 13px, 16px);
  line-height: 1.68;
  letter-spacing: -0.003em;
  min-height: 100vh;
  display: flex;
  flex-direction: column;
  -webkit-font-smoothing: antialiased;
}

.sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  margin: -1px;
  overflow: hidden;
  clip: rect(0, 0, 0, 0);
  white-space: nowrap;
  border: 0;
}

/* ============================ 顶栏（半透明模糊页头） ============================ */
.topbar {
  position: sticky;
  top: 0;
  z-index: var(--z-top);
  background: var(--scrim);
  backdrop-filter: blur(16px) saturate(1.4);
  -webkit-backdrop-filter: blur(16px) saturate(1.4);
  border-bottom: 1px solid var(--hair);
  padding: var(--space-3) var(--space-4);
}
.topbar-inner {
  max-width: var(--maxw);
  margin: 0 auto;
  display: flex;
  align-items: center;
  gap: var(--space-3);
  flex-wrap: wrap;
}
.brand {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  font-weight: 700;
  font-size: 22px;
  letter-spacing: -0.04em;
}
.brand-mark {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 26px;
  height: 26px;
  align-self: center;
  border-radius: var(--radius-sm);
  background: var(--accent-strong);
  color: var(--on-accent);
  font-size: 14px;
  font-weight: 600;
}
.brand-sub {
  color: var(--muted);
  font-size: var(--font-xs);
  font-weight: 400;
  letter-spacing: 0.12em;
  text-transform: uppercase;
}
.topbar-actions {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: var(--space-2);
}
.sync-pill {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-1) var(--space-3);
  border-radius: var(--radius-pill);
  background: var(--surface-2);
  border: 1px solid var(--line);
  color: var(--muted);
  font-size: var(--font-xs);
  max-width: 46vw;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.sync-pill.err {
  background: var(--danger-weak);
  border-color: var(--danger);
  color: var(--danger);
}
.sync-pill::before {
  content: '';
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--ok);
  flex: none;
}
.sync-pill.err::before { background: var(--danger); }
.sync-pill.busy::before { background: var(--warn); }

/* ============================ 布局 ============================ */
.shell {
  flex: 1;
  width: 100%;
  max-width: var(--maxw);
  margin: 0 auto;
  padding: var(--space-5) var(--space-4);
  display: grid;
  grid-template-columns: 1fr;
  gap: var(--space-4);
  align-items: start;
}
.side {
  display: grid;
  gap: var(--space-3);
  min-width: 0;
}
main#main {
  min-width: 0;
}

/* ============================ 卡片（发丝描边 + 悬浮） ============================ */
.card {
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--radius);
  padding: var(--space-3) var(--space-4);
  box-shadow: var(--shadow);
  transition: transform var(--dur) var(--ease), border-color var(--dur) var(--ease),
    box-shadow var(--dur) var(--ease);
}
.card + .card { margin-top: var(--space-3); }
.card:hover { border-color: var(--line-strong); }

/* ============================ 概览指标（发丝卡） ============================ */
.ov-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
  gap: var(--space-2);
}
.ov-item {
  min-height: 92px;
  display: flex;
  flex-direction: column;
  justify-content: center;
  gap: var(--space-1);
  padding: var(--space-3);
  border-radius: var(--radius-sm);
  background: var(--surface);
  border: 1px solid var(--line);
  border-top: 2px solid var(--accent-strong);
}
.ov-item.warn { border-top-color: var(--warn); }
.ov-item.danger { border-top-color: var(--danger); }
.ov-item.ok { border-top-color: var(--ok); }
.ov-num {
  font-size: clamp(20px, 1.4vw + 16px, 28px);
  font-weight: 700;
  line-height: 1.15;
  letter-spacing: -0.02em;
  font-variant-numeric: tabular-nums;
}
.ov-label {
  font-size: var(--font-sm);
  font-weight: 600;
}
.ov-sub {
  font-size: var(--font-xs);
  color: var(--muted);
}

/* ============================ 过滤条 ============================ */
.filterbar {
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--radius);
  padding: var(--space-3);
  display: grid;
  gap: var(--space-2);
  box-shadow: var(--shadow);
}
.fb-row {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  flex-wrap: wrap;
}
.chips {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2);
  min-width: 0;
}
.chip-btn {
  min-height: var(--tap);
  padding: var(--space-2) var(--space-3);
  border-radius: var(--radius-pill);
  border: 1px solid var(--line);
  background: var(--surface);
  color: var(--muted);
  font-size: var(--font-sm);
  cursor: pointer;
  transition: background var(--dur) var(--ease), border-color var(--dur) var(--ease),
    color var(--dur) var(--ease);
}
.chip-btn:hover { border-color: var(--line-strong); color: var(--text); }
.chip-btn[aria-pressed="true"] {
  background: var(--accent-strong);
  border-color: var(--accent-strong);
  color: var(--on-accent);
}
.search-row {
  display: flex;
  gap: var(--space-2);
  align-items: center;
}
.search-row input {
  flex: 1;
  min-height: var(--tap);
  padding: var(--space-2) var(--space-3);
  border-radius: var(--radius-sm);
  border: 1px solid var(--line);
  background: var(--surface);
  color: var(--text);
  font-size: var(--font-md);
  font-family: inherit;
}
.search-row input::placeholder { color: var(--muted); }

/* ============================ 按钮（黑色胶囊主按钮） ============================ */
.btn {
  min-height: var(--tap);
  padding: var(--space-2) var(--space-4);
  border-radius: var(--radius-pill);
  border: 1px solid var(--line);
  background: var(--surface);
  color: var(--text);
  font-size: var(--font-sm);
  font-family: inherit;
  cursor: pointer;
  transition: background var(--dur) var(--ease), border-color var(--dur) var(--ease),
    transform var(--dur) var(--ease);
}
.btn:hover { background: var(--surface-2); border-color: var(--line-strong); }
.btn:active { transform: scale(0.97); }
.btn-primary {
  background: var(--accent-strong);
  border-color: var(--accent-strong);
  color: var(--on-accent);
  font-weight: 500;
}
.btn-primary:hover { background: var(--accent-hover); border-color: var(--accent-hover); }
.btn-secondary {
  background: var(--surface);
  border-color: var(--line);
  color: var(--accent-text);
}
.btn-secondary:hover { background: var(--surface-2); }
.btn-ghost {
  min-height: var(--tap);
  padding: var(--space-2) var(--space-3);
  border-radius: var(--radius-pill);
  border: 1px solid transparent;
  background: transparent;
  color: var(--accent-text);
  font-size: var(--font-sm);
  font-family: inherit;
  cursor: pointer;
}
.btn-ghost:hover { background: var(--surface-2); }
.icon-btn {
  min-width: var(--tap);
  min-height: var(--tap);
  border-radius: var(--radius-sm);
  border: 1px solid var(--line);
  background: var(--surface);
  color: var(--text);
  font-size: var(--font-lg);
  line-height: 1;
  cursor: pointer;
}
.icon-btn:hover { background: var(--surface-2); }
.theme-select {
  min-height: var(--tap);
  padding: var(--space-2) var(--space-3);
  border-radius: var(--radius-pill);
  border: 1px solid var(--line);
  background: var(--surface);
  color: var(--text);
  font-size: var(--font-sm);
  font-family: inherit;
}

/* ============================ 分区头（细发丝 + 小型大写） ============================ */
.sec-head {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  margin: var(--space-4) 0 var(--space-2);
  padding: var(--space-2) var(--space-3);
  border-radius: var(--radius-sm);
  background: var(--surface);
  border: 1px solid var(--line);
  border-left: 2px solid var(--line-strong);
  color: var(--text);
  font-size: var(--font-sm);
  font-weight: 600;
  letter-spacing: -0.01em;
}
.sec-head.sub {
  background: transparent;
  border-style: none none none solid;
  border-left-width: 2px;
  border-left-color: var(--accent);
  color: var(--muted);
  font-weight: 500;
  padding-left: var(--space-2);
}
.sec-head.warn {
  border-left-color: var(--warn);
  background: var(--warn-weak);
  color: var(--warn);
}
.sec-head.danger {
  border-left-color: var(--danger);
  background: var(--danger-weak);
  color: var(--danger);
}
.sec-head .sec-count {
  margin-left: auto;
  color: var(--muted);
  font-weight: 400;
  font-size: var(--font-xs);
}

/* ============================ 结果区 ============================ */
#data { min-width: 0; }
#err-box:empty { display: none; }
#pane {
  display: grid;
  grid-template-columns: 1fr;
  gap: var(--space-3);
  align-content: start;
}

/* ============================ 会话卡 ============================ */
.conv-row { min-width: 0; }
.conv-head {
  display: flex;
  width: 100%;
  min-height: var(--tap);
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-2);
  border: 0;
  border-radius: var(--radius-sm);
  background: transparent;
  color: inherit;
  font-family: inherit;
  font-size: var(--font-md);
  text-align: left;
  cursor: pointer;
}
.conv-head:hover { background: var(--surface-2); }
.caret {
  flex: none;
  width: 1.2em;
  color: var(--muted);
  font-size: var(--font-sm);
}
.conv-main { min-width: 0; flex: 1; }
.conv-title-row {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  flex-wrap: wrap;
}
.conv-name {
  font-weight: 600;
  letter-spacing: -0.01em;
  word-break: break-all;
}
.todo-flag { color: var(--warn); }
.conv-preview {
  display: flex;
  gap: var(--space-2);
  color: var(--muted);
  font-size: var(--font-sm);
  min-width: 0;
}
.pv-text {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  flex: 1;
  min-width: 0;
}
.pv-time { flex: none; font-variant-numeric: tabular-nums; }
.conv-count {
  flex: none;
  color: var(--muted);
  font-size: var(--font-xs);
  font-variant-numeric: tabular-nums;
}

/* ============================ 日期 / 发送者分组 ============================ */
.date-head {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  width: 100%;
  min-height: var(--tap);
  margin: var(--space-2) 0 var(--space-1);
  padding: var(--space-1) var(--space-2);
  border: 0;
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--muted);
  font-family: inherit;
  font-size: var(--font-xs);
  font-weight: 600;
  letter-spacing: 0.06em;
  text-transform: uppercase;
  text-align: left;
  cursor: pointer;
}
.date-head:hover { background: var(--surface-2); }
.sender-head {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  width: 100%;
  min-height: 36px;
  margin-top: var(--space-2);
  padding: var(--space-1) var(--space-2);
  border: 0;
  border-left: 2px solid var(--line-strong);
  border-radius: 0 var(--radius-sm) var(--radius-sm) 0;
  background: transparent;
  color: var(--muted);
  font-family: inherit;
  font-size: var(--font-xs);
  text-align: left;
  cursor: pointer;
}
.sender-head:hover { background: var(--surface-2); }
.sender-name { font-weight: 600; color: var(--text); }
.sender-body { display: grid; gap: var(--space-2); margin-left: var(--space-2); }

/* ============================ 消息 ============================ */
.msg {
  padding: var(--space-2) 0;
  border-bottom: 1px dashed var(--line);
}
.msg:last-child { border-bottom: 0; }
.m-meta {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  flex-wrap: wrap;
  color: var(--muted);
  font-size: var(--font-xs);
}
.m-text {
  margin-top: var(--space-1);
  white-space: pre-wrap;
  word-break: break-word;
}

/* ============================ 角标 / 芯片 ============================ */
.badge {
  display: inline-flex;
  align-items: center;
  padding: 2px var(--space-2);
  border-radius: var(--radius-pill);
  font-size: var(--font-xs);
  font-weight: 600;
  color: var(--on-badge);
  background: var(--badge-app);
}
.badge.b-db { background: var(--badge-db); }
.badge.b-nt { background: var(--badge-nt); }
.badge.b-grp { background: var(--badge-grp); }
.badge.b-app { background: var(--badge-app); }
.chip {
  display: inline-flex;
  align-items: center;
  padding: 2px var(--space-2);
  border-radius: var(--radius-pill);
  background: var(--surface-2);
  border: 1px solid var(--line);
  color: var(--text);
  font-size: var(--font-xs);
}
.chip.warn {
  background: var(--warn-weak);
  border-color: var(--warn);
  color: var(--warn);
}
.chip.danger {
  background: var(--danger-weak);
  border-color: var(--danger);
  color: var(--danger);
}
.chip.ok {
  background: var(--ok-weak);
  border-color: var(--ok);
  color: var(--ok);
}
.chip.info {
  background: var(--info-weak);
  border-color: var(--info);
  color: var(--info);
}
.chips-row { display: flex; flex-wrap: wrap; gap: var(--space-1); }

/* ============================ 分析 / 待办 ============================ */
.todo-block { margin-top: var(--space-2); }
.s2 {
  margin-top: var(--space-2);
  padding: var(--space-2) var(--space-3);
  border-radius: var(--radius-sm);
  background: var(--surface-2);
  display: grid;
  gap: var(--space-1);
}
.s2 .label {
  color: var(--muted);
  font-size: var(--font-xs);
  font-weight: 600;
}
.s2 ul { margin: var(--space-1) 0 0; padding-left: 1.2em; }
.ana-meta {
  margin-top: var(--space-2);
  color: var(--muted);
  font-size: var(--font-xs);
}
.imp-high { color: var(--danger); font-weight: 600; }
.imp-mid { color: var(--warn); font-weight: 600; }

/* ============================ 排队任务 ============================ */
.queue-sum {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(96px, 1fr));
  gap: var(--space-2);
  margin-bottom: var(--space-3);
}
.queue-stat {
  padding: var(--space-2) var(--space-3);
  border-radius: var(--radius-sm);
  background: var(--surface);
  border: 1px solid var(--line);
  border-top: 2px solid var(--line-strong);
  text-align: center;
}
.queue-stat .qs-num {
  font-size: clamp(18px, 1vw + 15px, 24px);
  font-weight: 700;
  font-variant-numeric: tabular-nums;
}
.queue-stat .qs-label {
  font-size: var(--font-xs);
  color: var(--muted);
}
.queue-stat.qs-running { border-top-color: var(--info); }
.queue-stat.qs-queued { border-top-color: var(--accent); }
.queue-stat.qs-failed { border-top-color: var(--danger); }
.queue-stat.qs-done { border-top-color: var(--ok); }
.queue-stat.qs-stale { border-top-color: var(--warn); }

.q-card {
  border-left: 2px solid var(--line-strong);
}
.q-card.q-running { border-left-color: var(--info); }
.q-card.q-queued { border-left-color: var(--accent); }
.q-card.q-failed { border-left-color: var(--danger); }
.q-card.q-done { border-left-color: var(--ok); }
.q-card.q-stale { border-left-color: var(--warn); opacity: 0.85; }
.q-head {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  flex-wrap: wrap;
}
.q-title {
  font-weight: 600;
  letter-spacing: -0.01em;
  word-break: break-all;
}
.q-detail {
  margin-top: var(--space-1);
  color: var(--muted);
  font-size: var(--font-sm);
  word-break: break-word;
}
.q-meta {
  margin-top: var(--space-2);
  display: flex;
  gap: var(--space-3);
  flex-wrap: wrap;
  color: var(--muted);
  font-size: var(--font-xs);
  font-variant-numeric: tabular-nums;
}
.q-note {
  margin-top: var(--space-3);
  padding: var(--space-3);
  border-radius: var(--radius-sm);
  background: var(--info-weak);
  color: var(--info);
  font-size: var(--font-xs);
}

/* ============================ 统计 ============================ */
.skip-note {
  border-left: 3px solid var(--warn);
  color: var(--text);
  font-size: var(--font-sm);
}
.suggest-card {
  border-left: 3px solid var(--accent-strong);
}
.suggest-card .state-actions {
  margin-top: var(--space-2);
  display: flex;
  gap: var(--space-2);
}
.stat-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(140px, 1fr));
  gap: var(--space-2);
}
.stat-num {
  font-size: clamp(18px, 1vw + 15px, 26px);
  font-weight: 700;
  font-variant-numeric: tabular-nums;
  letter-spacing: -0.02em;
}
.stat-label { color: var(--muted); font-size: var(--font-xs); }

/* ============================ 顾问 ============================ */
.adv-card + .adv-card { margin-top: var(--space-3); }
.adv-head {
  display: flex;
  align-items: baseline;
  gap: var(--space-2);
  flex-wrap: wrap;
}
.adv-title { font-weight: 600; letter-spacing: -0.01em; }
.adv-meta { color: var(--muted); font-size: var(--font-xs); }
.adv-sum { margin-top: var(--space-2); white-space: pre-wrap; }
.sug {
  margin-top: var(--space-2);
  padding: var(--space-2) var(--space-3);
  border-radius: var(--radius-sm);
  background: var(--surface-2);
  border-left: 2px solid var(--gold);
}
.sug-title { font-weight: 600; }
.sug-detail { color: var(--muted); font-size: var(--font-sm); }

/* ============================ 状态 / 骨架 ============================ */
.state {
  text-align: center;
  padding: var(--space-6) var(--space-4);
  border-radius: var(--radius);
  background: var(--surface);
  border: 1px dashed var(--line-strong);
}
.state-ico { font-size: 30px; }
.state-title { margin-top: var(--space-2); font-weight: 600; font-size: var(--font-lg); }
.state-desc { color: var(--muted); margin-top: var(--space-1); }
.state-next { color: var(--muted); font-size: var(--font-sm); margin-top: var(--space-2); }
.state-actions {
  margin-top: var(--space-3);
  display: flex;
  gap: var(--space-2);
  justify-content: center;
  flex-wrap: wrap;
}
.state-error {
  border-style: solid;
  border-color: var(--danger);
  background: var(--danger-weak);
}
.state-error .state-title { color: var(--danger); }
.state-error .state-desc { color: var(--danger); }

.sk-card {
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--radius);
  padding: var(--space-3) var(--space-4);
  margin-bottom: var(--space-3);
}
.sk-line {
  height: 12px;
  border-radius: 6px;
  background: var(--skeleton);
  margin: var(--space-2) 0;
  position: relative;
  overflow: hidden;
}
.sk-line.w30 { width: 30%; }
.sk-line.w60 { width: 60%; }
.sk-line.w90 { width: 90%; }
.sk-shine {
  position: absolute;
  inset: 0;
  background: linear-gradient(90deg, transparent, var(--skeleton-hi), transparent);
  animation: sk 1.4s infinite;
}
@keyframes sk {
  0% { transform: translateX(-100%); }
  100% { transform: translateX(100%); }
}

.refresh-note {
  margin-top: var(--space-3);
  text-align: center;
  color: var(--muted);
  font-size: var(--font-xs);
}

/* ============================ 分页 ============================ */
.more-wrap {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-3) 0;
}
.more-state { color: var(--muted); font-size: var(--font-xs); }
.more-btn { min-width: 160px; }

/* ============================ 标签栏（分段式 Tab） ============================ */
.tabbar {
  position: sticky;
  bottom: 0;
  z-index: var(--z-tab);
  display: flex;
  gap: var(--space-1);
  padding: var(--space-2) var(--space-3);
  background: var(--scrim);
  backdrop-filter: blur(16px) saturate(1.4);
  -webkit-backdrop-filter: blur(16px) saturate(1.4);
  border-top: 1px solid var(--hair);
  overflow-x: auto;
}
.tabbar button {
  flex: 1;
  min-height: var(--tap);
  min-width: 68px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 2px;
  padding: var(--space-1);
  border: 0;
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--muted);
  font-family: inherit;
  font-size: var(--font-xs);
  cursor: pointer;
  transition: background var(--dur) var(--ease), color var(--dur) var(--ease);
}
.tabbar button:hover { background: var(--surface-2); color: var(--text); }
.tabbar button.active {
  background: var(--accent-strong);
  color: var(--on-accent);
  box-shadow: var(--shadow);
}
.btn-primary { border-radius: var(--radius-pill); font-weight: 500; letter-spacing: -0.01em; }
.chip-btn { border-radius: var(--radius-pill); }
.ov-num { letter-spacing: -0.04em; }
.t-ico { font-size: 15px; line-height: 1; }
.t-label { line-height: 1.2; }
.t-badge {
  min-width: 18px;
  padding: 0 5px;
  border-radius: var(--radius-pill);
  background: var(--danger);
  color: var(--on-danger);
  font-size: 10px;
  line-height: 16px;
  text-align: center;
}
.t-badge:empty { display: none; }
.tabbar button.active .t-badge {
  background: var(--on-accent);
  color: var(--accent-strong);
}

/* ============================ 提示条 ============================ */
#ptr {
  max-height: 0;
  overflow: hidden;
  text-align: center;
  color: var(--muted);
  font-size: var(--font-xs);
  transition: max-height var(--dur) var(--ease);
}
#ptr.show { max-height: 32px; }
.ptr-in { display: inline-block; padding: var(--space-1) 0; }

.toast {
  position: fixed;
  left: 50%;
  bottom: 88px;
  transform: translateX(-50%) translateY(12px);
  max-width: 86vw;
  padding: var(--space-2) var(--space-4);
  border-radius: var(--radius-pill);
  background: var(--text);
  color: var(--bg);
  font-size: var(--font-sm);
  opacity: 0;
  pointer-events: none;
  transition: opacity var(--dur) var(--ease), transform var(--dur) var(--ease);
  z-index: 40;
}
.toast.show { opacity: 1; transform: translateX(-50%) translateY(0); }

/* ============================ 焦点可见（古铜金描边） ============================ */
:focus-visible {
  outline: 3px solid var(--focus);
  outline-offset: 3px;
  border-radius: var(--radius-sm);
}

/* ============================ 补充组件 ============================ */
.auth-box { display: grid; gap: var(--space-2); padding: var(--space-5); border: 1px solid var(--line); border-radius: var(--radius); background: var(--surface); box-shadow: var(--shadow); }
.auth-msg { margin: 0; color: var(--muted); font-size: var(--font-sm); }
.ana-head { display: flex; align-items: center; flex-wrap: wrap; gap: var(--space-2); margin-bottom: var(--space-2); }
.msg-list { display: grid; gap: var(--space-2); }
.date-body { display: grid; gap: var(--space-2); }
.copy-btn { min-width: var(--tap); min-height: var(--tap); display: inline-flex; align-items: center; justify-content: center; padding: 0 var(--space-2); border: 1px solid var(--line); border-radius: var(--radius-pill); background: var(--surface); color: var(--muted); cursor: pointer; }
.copy-btn:hover { background: var(--surface-2); color: var(--text); }
.stat-value { font-size: var(--font-xl); font-weight: 700; color: var(--text); }
.state-empty { border-style: dashed; }
.row { display: flex; align-items: baseline; flex-wrap: wrap; gap: var(--space-2); }
.note { color: var(--muted); font-size: var(--font-sm); }

/* ============================ 入场动效（可降级） ============================ */
@keyframes aura-rise {
  from { opacity: 0; transform: translateY(8px); }
  to { opacity: 1; transform: none; }
}

/* ============================ 响应式 ============================ */
@media (max-width: 600px) {
  .shell { padding: var(--space-3); }
  #pane { grid-template-columns: 1fr; }
  .ov-grid { grid-template-columns: 1fr; }
  .topbar-inner { gap: var(--space-2); }
  .brand-sub { display: none; }
  .sync-pill { max-width: 100%; order: 3; width: 100%; }
  /* 底部常驻标签栏会盖住最后一行，留出安全距离 */
  main { padding-bottom: calc(var(--tap) + var(--space-5) + var(--space-2) + env(safe-area-inset-bottom, 0px)); }
}

@media (min-width: 900px) {
  .shell {
    grid-template-columns: 320px minmax(0, 1fr);
    align-items: start;
  }
  .side { position: sticky; top: 84px; }
  main { max-width: none; margin: 0; width: 100%; }
  #pane { grid-template-columns: repeat(auto-fill, minmax(340px, 1fr)); }
  .topbar {
    position: static;
    order: -2;
  }
  .tabbar {
    position: sticky;
    top: 0;
    bottom: auto;
    border-top: 0;
    border-bottom: 1px solid var(--hair);
    order: -1;
    max-width: var(--maxw);
    margin: 0 auto;
    width: 100%;
    background: var(--scrim);
  }
}

@media (min-width: 1280px) {
  .shell { grid-template-columns: 360px minmax(0, 1fr); }
  #pane { grid-template-columns: repeat(auto-fill, minmax(380px, 1fr)); }
}

@media (prefers-reduced-motion: reduce) {
  * { animation: none !important; transition: none !important; }
}.card { transition: transform var(--dur) var(--ease), border-color var(--dur) var(--ease), box-shadow var(--dur) var(--ease); }
.card:hover { transform: translateY(-4px); border-color: var(--line-strong); box-shadow: var(--shadow-lift); }

/* aura 入场动效：首屏区块依序浮现 */
.appear { animation: aura-rise .4s var(--ease) both; }
@media (min-width: 901px) {
  .shell { grid-template-columns: 320px minmax(0, 1fr); transition: grid-template-columns .3s var(--ease); }
  .shell.fold { grid-template-columns: 64px minmax(0, 1fr); }
  .shell.fold .side .ov-label, .shell.fold .side .ov-sub { display: none; }
}
</style>
<script>
// ---------- 主题三态：auto（跟随系统）/ light / dark ----------
// 颜色只写在 CSS 的 :root 与 [data-theme="dark"] 两个变量块里；
// 这里只负责把「用户偏好」解析成「实际生效主题」，写到 <html data-theme>，
// 再把 CSS 变量 --theme-color 同步给 theme-color meta（不写死颜色）。
(function () {
  var KEY = 'wx_theme_v1';
  var root = document.documentElement;
  var mem = 'auto';
  function readPref() {
    try {
      var v = window.localStorage.getItem(KEY);
      if (v === 'auto' || v === 'light' || v === 'dark') { mem = v; return v; }
    } catch (e) { }
    return mem;
  }
  function writePref(p) {
    mem = p;
    try { window.localStorage.setItem(KEY, p); } catch (e) { }
  }
  function systemTheme() {
    try {
      return (window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches) ? 'dark' : 'light';
    } catch (e) { return 'light'; }
  }
  function resolve(p) { return (p === 'auto') ? systemTheme() : p; }
  function paint() {
    var p = readPref();
    var r = resolve(p);
    root.setAttribute('data-theme', r);
    root.setAttribute('data-theme-pref', p);
    var meta = document.getElementById('meta-theme-color');
    if (meta) {
      var c = '';
      try { c = window.getComputedStyle(root).getPropertyValue('--theme-color'); } catch (e) { }
      c = String(c || '').trim();
      if (c) meta.setAttribute('content', c);
    }
    return r;
  }
  window.__WX_THEME = {
    key: KEY,
    pref: readPref,
    resolve: resolve,
    paint: paint,
    set: function (p) {
      if (p !== 'auto' && p !== 'light' && p !== 'dark') p = 'auto';
      writePref(p);
      paint();
    }
  };
  // 首次访问：localStorage 里没有偏好 -> auto -> 读 prefers-color-scheme 兜底
  paint();
  try {
    var mq = window.matchMedia('(prefers-color-scheme: dark)');
    var onMq = function () { if (readPref() === 'auto') paint(); };
    if (mq.addEventListener) mq.addEventListener('change', onMq);
    else if (mq.addListener) mq.addListener(onMq);
  } catch (e) { }
})();
</script>
</head>
<body>
<header class="topbar">
  <div class="topbar-inner">
    <div class="brand">
      <span class="brand-mark" aria-hidden="true">N</span>
      <span>notifyme</span>
      <span class="brand-sub">本地优先 · 通知原文留在自己设备上</span>
    </div>
    <div class="topbar-actions">
      <span class="sync-pill" id="sync-line" role="status" aria-live="polite">正在同步…</span>
      <label class="sr-only" for="theme-select">主题外观</label>
      <select id="theme-select" class="theme-select" aria-label="主题外观">
        <option value="auto">跟随系统</option>
        <option value="light">浅色</option>
        <option value="dark">深色</option>
      </select>
      <button type="button" class="icon-btn" id="btn-refresh" aria-label="立即刷新数据" onclick="load('manual')">⟳</button>
    </div>
  </div>
</header>
<div id="ptr" aria-hidden="true"><span class="ptr-in" id="ptr-text">下拉刷新</span></div>
<div class="shell">
  <aside class="side">
    <section class="card" id="overview" aria-label="今日概览" aria-live="polite" aria-busy="true">
      <div class="ov-grid">
        <div class="ov-item">
          <div class="ov-num" id="ov-24h">—</div>
          <div class="ov-label">近 24 小时消息</div>
          <div class="ov-sub" id="ov-24h-sub">按已加载数据统计</div>
        </div>
        <div class="ov-item warn">
          <div class="ov-num" id="ov-todo">—</div>
          <div class="ov-label">待处理</div>
          <div class="ov-sub" id="ov-todo-sub">需要行动的分析</div>
        </div>
        <div class="ov-item">
          <div class="ov-num" id="ov-queue">—</div>
          <div class="ov-label">排队任务</div>
          <div class="ov-sub" id="ov-queue-sub">端上排队 / 运行中的任务</div>
        </div>
        <div class="ov-item danger">
          <div class="ov-num" id="ov-fail">—</div>
          <div class="ov-label">同步失败</div>
          <div class="ov-sub" id="ov-fail-sub">加载失败累计次数</div>
        </div>
        <div class="ov-item ok">
          <div class="ov-num" id="ov-sync">—</div>
          <div class="ov-label">最近同步</div>
          <div class="ov-sub" id="ov-sync-sub">每 30 秒自动同步</div>
        </div>
      </div>
    </section>
    <section class="filterbar" aria-label="全局过滤与搜索">
      <div class="fb-row">
        <div class="chips" id="app-chips" role="group" aria-label="按来源 App 过滤"></div>
        <button type="button" class="btn-ghost" id="btn-clear-app" aria-label="清除来源 App 过滤" onclick="clearAppFilter()">清除</button>
      </div>
      <div class="search-row">
        <label class="sr-only" for="global-search">全局搜索（会话、待办、排队、分析、顾问）</label>
        <input id="global-search" type="search" autocomplete="off"
               placeholder="搜索会话 / 待办 / 排队 / 分析 / 顾问"
               aria-label="全局搜索" oninput="onSearchInput(this.value)">
        <button type="button" class="btn-ghost" id="btn-clear-search" aria-label="清除搜索关键词" onclick="clearSearch()">清除</button>
      </div>
    </section>
  </aside>
  <main id="main">
    <div id="data" role="tabpanel" aria-labelledby="tab-conv" aria-busy="true">
      <div id="err-box"></div>
      <div id="pane" aria-live="polite" aria-relevant="additions text"></div>
    </div>
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
  <button type="button" id="tab-queue" class="tab" role="tab" aria-selected="false"
          aria-controls="data" onclick="switchTab('queue')">
    <span class="t-ico" aria-hidden="true">🧾</span><span class="t-label">排队</span>
    <span class="t-badge" id="badge-queue"></span>
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

<!-- 控制台归类规则（单一事实源：server/console_classify.js）。
     本文件只放占位标记，规则正文由 server/app.py 在启动时内联进来；
     页面取不到 window.ConsoleClassify 时会自动降级成平铺渲染。 -->
<script>/*__CONSOLE_CLASSIFY_JS__*/</script>

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

// ---------- 归类规则（单一事实源：server/console_classify.js）----------
// 服务端启动时把 console_classify.js 内联到本页（占位标记在 console_ui.py 里）。
// 取不到时（老缓存页面 / 源文件缺失）保持旧版平铺渲染，不抛异常、不产生坏 DOM。
var CC = window.ConsoleClassify || null;

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
// 顾问报告的 created_at 既可能是毫秒（端上上报），也可能是老存档里的
// "2026-11-10 08:00:00" 文本 —— 两种都要能显示成人话，认不出就原样显示。
// 修前直接 esc(r.created_at) 会把 13 位毫秒原样打在卡片右上角。
function stampText(v) {
  if (v == null || v === "") return "";
  var n = Number(v);
  if (!isNaN(n) && n > 0) {
    if (n < 1e11) n = n * 1000;  // 秒级时间戳按秒解释
    var out = relTime(n);
    if (out) return out;
  }
  return String(v);
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
// 顾问存档读不出来时的说明（GET /advisor 回 200 + ok:false）：只影响顾问 tab，
// 不能让顾问 tab 显示成「还没有顾问报告」——那是事实错误（文件坏了 ≠ 没报告）。
var ADVISORS_ERR = '';
// App 过滤：'' = 全部；否则只看该包名（四个 tab 共用同一口径）
var APP_FILTER = '';
// 全局搜索关键词（跨四个 tab）
var QUERY = '';
// 鉴权态：令牌无效后固定显示鉴权框，避免把错误态渲染成普通列表
var AUTH_MODE = false;
// 折叠状态（key 形如 c:<pkg><SEP><会话名>、<会话key>|<日期键>、
// <会话key>|<日期键>|<发送者key>；true = 展开，默认折叠；持久化到 localStorage）
var COLLAPSE = {};
try { COLLAPSE = JSON.parse(LS.get('wx_collapse_v1') || '{}') || {}; } catch (e) { COLLAPSE = {}; }
var CONV_CARDS = {};   // 会话 key -> 会话名（复制会话名用；key 含 SEP，编码后进 data-*）
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
// 分页：服务端 next_before 是 <毫秒>_<rowid> 复合游标，
// 前端按游标一页一页往前翻，不做「一次拉全量 + 前端切片」。
var PAGE_LIMIT = 200;
var MSG_NEXT = '';     // 消息下一页游标（空 = 没有更多）
var ANA_NEXT = '';     // 分析下一页游标（空 = 没有更多）
var MSG_PAGES = 0;     // 已加载消息页数
var ANA_PAGES = 0;     // 已加载分析页数
var MORE_BUSY = false; // 「加载更多」进行中
var FAIL_COUNT = 0;    // 累计加载失败次数（接口不提供失败统计，只能前端计数）
var QUEUE = [];        // 端上排队任务（GET /queue）
var QUEUE_SUMMARY = null;  // 排队汇总：running/queued/failed/done/stale/total
var QUEUE_ERR = '';    // 排队接口不可用时的说明（只提示，不影响其它数据）
var QUEUE_STATE = '';  // 排队 tab 的状态筛选（空 = 全部）
var QUEUE_DEVICE = '';   // 排队 tab 的设备筛选（空 = 全部设备）
var QUEUE_DEVICES = [];  // 设备清单（GET /queue 响应里的 devices 字段）
// 服务端聚合真值（GET /stats）：概览与统计优先用它，避免「按已加载的数据算」随翻页漂移。
// 接口不可用时保持 null，调用方退回本地估算并标注。
var STATS = null;
var STATS_ERR = '';
// 下一步建议（GET /suggests/next）：表驱动规则给出的最多 3 条可执行动作。
var SUGGESTS = [];
// 分页接口跳过的脏数据条数（messages / analysis 各自累计），非 0 时给一行提示。
var SKIP_NOTICE = { msg: 0, ana: 0 };
// 会话卡内的二级归类维度：date（按日期，默认）/ sender（按发送者）；持久化到 localStorage
var CONV_GROUP_DIM = 'date';
try { var _gd = LS.get('wx_group_v1'); if (_gd === 'sender' || _gd === 'date') CONV_GROUP_DIM = _gd; } catch (e) { }
var HAS_DATA = false;  // 是否至少成功加载过一次（决定骨架占位还是真实数字）
var THEME_KEY = 'wx_theme_v1';

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
// 口径统一交给 console_classify.js（规则只写一份）；
// 规则脚本缺失时用下面的兜底实现，行为与旧版一致。
function normPkg(p) { return CC ? CC.normPkg(p) : (p || PKG_WX); }
function pkgLabel(p) {
  return CC ? CC.pkgLabel(p, PKG_LABELS) : (PKG_LABELS[normPkg(p)] || normPkg(p));
}
function ckOf(o) {
  return CC ? CC.ckOf(o) :
    (normPkg(o.pkg) + SEP + (o.conversation == null ? '' : o.conversation));
}

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
    // 鉴权框只占结果区 #pane，不再整块替换 #data：
    // 换掉 #data 会连带干掉 #err-box / #pane 两个固定节点
    var pane = $('pane') || $('data');
    if (!pane) return;
    clearPaneSections();
    pane.innerHTML =
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
// 复制会话名：按会话 key（复合游标同一套 key）取名字，不再依赖数组下标
function copyConvNameByKey(ck) {
  var name = CONV_CARDS[ck];
  if (name == null) return;
  copyText(name).then(function () {
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
    if (el) { el.className = 'sync-pill err'; el.textContent = txt; }
    if (live) live.textContent = '同步失败：' + LAST_ERR;
    return;
  }
  if (el) el.className = 'sync-pill' + (LOAD_BUSY ? ' busy' : '');
  if (LOAD_BUSY && LAST_SYNC) txt = '同步中… · 上次同步 ' + fmtClock(LAST_SYNC);
  else if (!LAST_SYNC) txt = '正在同步…';
  else {
    // 有全库真值时显示真值，否则退回「已加载」口径并标注
    var mTotal = statNum(['messages', 'total']);
    var aTotal = statNum(['analyses', 'total']);
    if (mTotal == null || APP_FILTER) {
      txt = '已同步 ' + fmtClock(LAST_SYNC) + ' · 已加载消息 ' + MSGS.length + ' · 分析 ' + ANALYSES.length;
    } else {
      txt = '已同步 ' + fmtClock(LAST_SYNC) + ' · 消息 ' + mTotal + ' · 分析 ' + aTotal;
    }
  }
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
  refreshSuggests();
  toast(APP_FILTER ? ('已筛选：' + pkgLabel(APP_FILTER)) : '已显示全部来源');
}
function clearAppFilter() {
  if (!APP_FILTER) return;
  APP_FILTER = '';
  renderFilterBar(true);
  renderOverview();
  renderBadges();
  renderData();
  refreshSuggests();
  toast('已清除来源过滤');
}
function renderFilterBar(force) {
  var box = $('app-chips');
  if (!box) return;
  var tabs = appTabList();
  var listSig = tabs.map(function (t) { return t[0]; }).join('|');
  // 只有页签集合变了才重建 DOM：重建会把键盘焦点从 chip 上弄丢
  if (force || listSig !== FILTER_SIG) {
    FILTER_SIG = listSig;
    // 原生 button：自带焦点环、Enter/空格触发、aria-pressed 语义，
    // 不再需要 span[role=button] + 内联 onclick/onkeydown
    box.innerHTML = tabs.map(function (t) {
      return '<button type="button" class="chip-btn" aria-pressed="' +
        (APP_FILTER === t[0] ? 'true' : 'false') +
        '" data-pkg="' + esc(t[0]) + '">' + esc(t[1]) + '</button>';
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
  // 脱敏证据也能被搜到：命中规则名与条数决定要不要重点看这条
  if (a.redaction_rules) {
    try { parts.push(JSON.stringify(a.redaction_rules)); } catch (e) { }
    parts.push(String(a.redaction_rules));
  }
  if (a.redaction_hits != null) parts.push(String(a.redaction_hits));
  if (a.redacted) parts.push('已脱敏');
  return parts.join(' ').toLowerCase();
}
// 脱敏徽标：redacted 为真时说明这条分析结果里有内容被替换成占位符
function redactBadge(a) {
  if (!a || !a.redacted) return '';
  var hits = Number(a.redaction_hits || 0);
  return '<span class="badge b-grp" title="上报前已脱敏">🛡 已脱敏 ' +
    (hits > 0 ? hits + ' 处' : '') + '</span>';
}
// 脱敏明细：命中了几条规则、共几处；没命中就不占版面
function redactDetail(a) {
  if (!a || !a.redacted) return '';
  var rules = [];
  var raw = a.redaction_rules;
  if (raw) {
    if (Object.prototype.toString.call(raw) === '[object Array]') {
      for (var i = 0; i < raw.length; i++) {
        var r0 = raw[i];
        var nm = (r0 && typeof r0 === 'object') ? (r0.name || r0.rule || r0.id || '') : String(r0);
        if (nm) rules.push(String(nm));
      }
    } else {
      rules.push(String(raw));
    }
  }
  var hits = Number(a.redaction_hits || 0);
  var txt = '上报前已脱敏';
  if (hits > 0) txt += ' · 命中 ' + hits + ' 处';
  if (rules.length) txt += ' · 规则：' + rules.join('、');
  return '<div class="ana-meta">🛡 ' + esc(txt) + '</div>';
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
// 近 24 小时条数：只统计「已加载」的消息，用于 /stats 不可用时的估算口径。
// 首次加载完成前用骨架占位，避免直接显示 0 造成误解。
function countLast24h(list) {
  var from = Date.now() - 86400000;
  var n = 0;
  for (var i = 0; i < list.length; i++) {
    var t = Number(list[i].timestamp || 0);
    if (t && t >= from) n++;
  }
  return n;
}
// 写概览数字；sk 为真时写骨架占位（而不是假数字）
function setOv(id, val, sk) {
  var el = $(id);
  if (!el) return;
  if (sk) {
    el.innerHTML = '<span class="sk-line w60" aria-hidden="true"></span>';
    return;
  }
  el.textContent = val;
}
// 取聚合真值的某个数字：拿不到返回 null，调用方据此退回估算
function statNum(path) {
  if (!STATS) return null;
  var cur = STATS;
  for (var i = 0; i < path.length; i++) {
    if (cur == null) return null;
    cur = cur[path[i]];
  }
  return (typeof cur === 'number' && isFinite(cur)) ? cur : null;
}
// 概览条：优先服务端全库真值，拿不到才用「已加载」数据估算并标注
function renderOverview() {
  var msgs = filteredMsgs();
  var todos = filteredTodos();
  var sk = !HAS_DATA;
  // /stats 是整库口径（pkg 只回显不参与过滤），所以来源过滤时一律走本地估算，避免混口径
  var useSrv = !!(STATS && !APP_FILTER && !QUERY.trim());
  var est = !useSrv;
  var n24 = useSrv ? (statNum(['messages', 'last_24h']) || 0) : countLast24h(msgs);
  var nTodo = useSrv ? (statNum(['analyses', 'todo']) || 0) : todos.length;
  var nQueue = useSrv ? (statNum(['queue', 'active']) || 0) : queueActiveCount();
  setOv('ov-24h', String(n24), sk);
  setOv('ov-todo', String(nTodo), sk);
  setOv('ov-queue', String(nQueue), sk);
  setOv('ov-fail', String(FAIL_COUNT), sk);
  setOv('ov-sync', LAST_SYNC ? fmtClock(LAST_SYNC) : '—', sk);
  var s24 = $('ov-24h-sub');
  if (s24) {
    s24.textContent = sk ? '加载中…' :
      (est
        ? ('估算 · 仅已加载 ' + msgs.length + ' 条' + (MSG_NEXT ? '，还有更多' : ''))
        : ('全库真值 · 已加载 ' + msgs.length + ' 条' + (MSG_NEXT ? '，还有更多' : '')));
  }
  var sTodo = $('ov-todo-sub');
  if (sTodo) {
    sTodo.textContent = sk ? '加载中…' :
      (est ? ('估算 · 当前筛选下 ' + todos.length + ' 条')
        : ('全库真值 · 共 ' + (statNum(['analyses', 'convs']) || 0) + ' 个会话'));
  }
  var sQueue = $('ov-queue-sub');
  if (sQueue) {
    // 设备数必须是「还有在跑任务」的台数：设备停报后它的过期行要等 7 天保留期
    // 才清，这段时间它还挂在 devices 清单里但 active 恒为 0 —— 拿清单长度当
    // 设备数，同一行就会写出「当前没有排队任务 · 2 台设备」（自相矛盾）。
    var devN = useSrv ? (statNum(['queue', 'devices_active']) || 0) : liveDeviceCount();
    var actN = nQueue || 0;
    sQueue.textContent = sk ? '加载中…' :
      (actN
        ? (actN + ' 个任务排队或运行中' + (devN ? (' · ' + devN + ' 台设备') : ''))
        : (devN ? ('当前没有排队任务 · ' + devN + ' 台设备') : '当前没有排队任务'));
    if (est && !sk) sQueue.textContent += '（估算）';
  }
  var sFail = $('ov-fail-sub');
  if (sFail) {
    sFail.textContent = sk ? '加载中…' :
      (FAIL_COUNT ? ('最近错误：' + LAST_ERR) : '暂无失败记录');
  }
  var sSync = $('ov-sync-sub');
  if (sSync) {
    sSync.textContent = sk ? '加载中…' :
      (LOAD_BUSY ? '同步中' : (LAST_ERR ? '同步失败' : '每 30 秒自动同步'));
  }
  var ov = $('overview');
  if (ov) ov.setAttribute('aria-busy', LOAD_BUSY ? 'true' : 'false');
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
  setBadge('badge-queue', queueActiveCount());
  var pairs = [
    ['tab-conv', '会话', filteredMsgs().length, '条消息'],
    ['tab-todo', '待办', filteredTodos().length, '条待办'],
    ['tab-queue', '排队', queueActiveCount(), '个任务'],
    ['tab-stat', '统计', filteredAnalyses().length, '条分析'],
    ['tab-adv', '顾问', ADVISORS.length, '份报告']
  ];
  pairs.forEach(function (p) {
    var b = $(p[0]);
    if (b) b.setAttribute('aria-label', p[1] + '，' + p[2] + ' ' + p[3]);
  });
}

// ---------- 数据加载（并发 + 序号 + AbortController + 游标分页）----------
// 服务端的 next_before 是 <毫秒>_<rowid> 复合游标，且只在「真的还有下一页」
// 时才返回；前端按游标一页一页往前翻，不再一次性把全部消息拉下来再切片。
async function fetchMsgPage(before, signal) {
  var url = 'messages?limit=' + PAGE_LIMIT;
  if (before) url += '&before=' + encodeURIComponent(before);
  var r = await api(url, { signal: signal });
  return { items: r.messages || [], next: r.next_before || '', skipped: Number(r.skipped || 0) };
}
async function fetchAnaPage(before, signal) {
  var url = 'analysis?limit=' + PAGE_LIMIT;
  if (before) url += '&before=' + encodeURIComponent(before);
  var r = await api(url, { signal: signal });
  return { items: r.analyses || [], next: r.next_before || '', skipped: Number(r.skipped || 0) };
}
// 脏数据提示条：跳过的坏行不进列表，但必须告诉用户「不是没有更多，是被跳过」
function skipNoticeHtml() {
  var n = (SKIP_NOTICE.msg || 0) + (SKIP_NOTICE.ana || 0);
  if (!n) return '';
  var parts = [];
  if (SKIP_NOTICE.msg) parts.push('消息 ' + SKIP_NOTICE.msg + ' 条');
  if (SKIP_NOTICE.ana) parts.push('分析 ' + SKIP_NOTICE.ana + ' 条');
  return '<div class="card skip-note" role="status">⚠️ 有 ' + esc(parts.join(' / ')) +
    ' 因格式损坏被跳过（服务端 skipped 计数），列表条数少于实际库存属正常。</div>';
}
// 排队任务（端上 WorkManager 队列 + 待同步消息队列）
async function fetchQueue(signal) {
  // 取服务端上限（QUEUE_MAX_LIMIT = 500）：排队任务没有游标翻页，
  // 取一页就是全部可见范围，别在 200 就把更早的任务截掉。
  var r = await api('queue?limit=500', { signal: signal });
  return { items: r.tasks || [], summary: r.summary || null, devices: r.devices || [] };
}
// 全库聚合真值：消息 / 分析 / 排队三段，与前端已加载多少条无关
async function fetchStats(signal) {
  return await api('stats', { signal: signal });
}
// 下一步建议：服务端表驱动规则，最多 3 条
// 带来源筛选时必须把包名发给服务端：待办条数与「未分析会话」数都是在 SQL 里按
// pkg 算的全库数字，前端对已加载那一页做过滤得到的数对不上。
async function fetchSuggests(signal) {
  var url = 'suggests/next';
  if (APP_FILTER) url += '?pkg=' + encodeURIComponent(APP_FILTER);
  var r = await api(url, { signal: signal });
  return { items: r.suggests || [], pkg: r.pkg || '' };
}
// 换来源筛选后重取建议：证据串里的数字是服务端按 pkg 算的，不重取就还是上一份。
async function refreshSuggests() {
  if (!TOKEN) return;
  try {
    var r = await fetchSuggests();
    SUGGESTS = r.items || [];
  } catch (e) {
    // 建议是兜底通道：失败就清空，别拿上一份别的来源的数字冒充
    SUGGESTS = [];
  }
  renderData();
}
// 由当前 ANALYSES 重建派生索引：同一复合会话保留 received_at 最新的一条待办
function rebuildDerived() {
  todoByConv = {};
  ANALYSES.forEach(function (x) {
    if (!needActionOf(x)) return;
    var ck = ckOf(x);
    if (!todoByConv[ck] ||
        (x.received_at || 0) > (todoByConv[ck].received_at || 0)) {
      todoByConv[ck] = x;
    }
  });
}

// 「加载更多」：按 next_before 游标取下一页并追加；游标为空就是「没有更多」
async function loadMore(kind) {
  if (MORE_BUSY) return;
  var isAna = (kind === 'ana');
  var next = isAna ? ANA_NEXT : MSG_NEXT;
  if (!next) { render(); return; }
  MORE_BUSY = true;
  render();
  try {
    var sig = LOAD_CTRL ? LOAD_CTRL.signal : undefined;
    var r = isAna ? await fetchAnaPage(next, sig) : await fetchMsgPage(next, sig);
    if (isAna) {
      ANALYSES = ANALYSES.concat(r.items);
      ANA_NEXT = r.next;
      ANA_PAGES++;
      SKIP_NOTICE.ana = (SKIP_NOTICE.ana || 0) + (r.skipped || 0);
    } else {
      MSGS = MSGS.concat(r.items);
      MSG_NEXT = r.next;
      MSG_PAGES++;
      SKIP_NOTICE.msg = (SKIP_NOTICE.msg || 0) + (r.skipped || 0);
    }
    rebuildDerived();
  } catch (e) {
    if (!e || e.kind !== 'abort') {
      FAIL_COUNT++;
      toast('加载更多失败：' + ((e && e.message) ? e.message : '未知错误'));
      renderSyncLine();
      renderOverview();
    }
  } finally {
    MORE_BUSY = false;
    render();
  }
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
  var dataEl = $('data');
  if (dataEl) dataEl.setAttribute('aria-busy', 'true');
  renderSyncLine();
  renderOverview();
  try {
    // 三个接口并发：只取第一页，其余靠「加载更多」按 next_before 游标翻
    var res = await Promise.all([
      fetchMsgPage('', ctrl.signal),
      fetchAnaPage('', ctrl.signal),
      api('advisor?limit=100', { signal: ctrl.signal }),
      // 排队任务单独兜底：接口缺失或报错只影响「排队」tab，不能让整个控制台失败
      fetchQueue(ctrl.signal).catch(function (e) {
        if (e && e.kind === 'abort') throw e;
        return { items: [], summary: null, devices: [], err: (e && e.message) ? e.message : '排队任务接口不可用' };
      }),
      // 聚合真值与建议同样单独兜底：拿不到就退回本地估算，不影响主数据流
      fetchStats(ctrl.signal).catch(function (e) {
        if (e && e.kind === 'abort') throw e;
        return { err: (e && e.message) ? e.message : '统计接口不可用' };
      }),
      fetchSuggests(ctrl.signal).catch(function (e) {
        if (e && e.kind === 'abort') throw e;
        return { items: [], err: (e && e.message) ? e.message : '建议接口不可用' };
      })
    ]);
    // 旧响应后到 -> 丢弃，不能覆盖新数据
    if (seq !== LOAD_SEQ) return;
    MSGS = res[0].items || [];
    MSG_NEXT = res[0].next || '';
    MSG_PAGES = MSGS.length ? 1 : 0;
    ANALYSES = res[1].items || [];
    ANA_NEXT = res[1].next || '';
    ANA_PAGES = ANALYSES.length ? 1 : 0;
    SKIP_NOTICE = { msg: res[0].skipped || 0, ana: res[1].skipped || 0 };
    if (SKIP_NOTICE.msg || SKIP_NOTICE.ana) {
      toast('有 ' + ((SKIP_NOTICE.msg || 0) + (SKIP_NOTICE.ana || 0)) + ' 条脏数据被跳过');
    }
    ADVISORS = (res[2] && res[2].reports) || [];
    ADVISORS_ERR = (res[2] && res[2].ok === false)
      ? ((res[2].detail || res[2].error) || '顾问存档不可读') : '';
    QUEUE = (res[3] && res[3].items) || [];
    QUEUE_SUMMARY = (res[3] && res[3].summary) || null;
    QUEUE_ERR = (res[3] && res[3].err) || '';
    QUEUE_DEVICES = (res[3] && res[3].devices) || [];
    // 聚合真值：只有拿到 ok 字段才算成功，否则视为不可用并记原因
    if (res[4] && res[4].ok) { STATS = res[4]; STATS_ERR = ''; }
    else { STATS = null; STATS_ERR = (res[4] && res[4].err) || '统计接口不可用'; }
    SUGGESTS = (res[5] && res[5].items) || [];
    AUTH_MODE = false;
    HAS_DATA = true;
    // 「未读分析」= 上次同步之后才到达的分析（首次同步时全部算新增）
    NEW_CASES = {};
    ANALYSES.forEach(function (a) {
      if (!PREV_SYNC || (a.received_at || 0) > PREV_SYNC) NEW_CASES[analysisKey(a)] = true;
    });
    rebuildDerived();
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
    FAIL_COUNT++;
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
  } finally {
    if (seq === LOAD_SEQ) {
      LOAD_BUSY = false;
      var d = $('data');
      if (d) d.setAttribute('aria-busy', 'false');
    }
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

// ---------- 渲染（分区打补丁：只改真正变了的那一块）----------
// #data 内部固定两块：#err-box（错误提示）与 #pane（结果区）。
// 结果区按 section 打补丁：内容没变的 section 根本不碰 DOM，
// 变了的也只替换该 section 内部 —— 单张卡片变化不会带动整页重排。
var PANE_SECTIONS = {};

// section key 要能安全地放进 data-* 属性：统一 percent 编码
function encKey(k) {
  return encodeURIComponent(String(k == null ? '' : k));
}
function decKey(k) {
  var s = String(k == null ? '' : k);
  try { return decodeURIComponent(s); } catch (e) { return s; }
}
function clearPaneSections() {
  Object.keys(PANE_SECTIONS).forEach(function (k) {
    var n = PANE_SECTIONS[k];
    if (n && n.parentNode) n.parentNode.removeChild(n);
    delete PANE_SECTIONS[k];
  });
}
// 局部更新：内容没变的 section 一律不碰 DOM
function patchSections(list) {
  var pane = $('pane');
  if (!pane) return;
  var seen = {};
  var ordered = [];
  // 新区块先攒进 DocumentFragment，最后一次性挂到 DOM 上，避免逐个插入触发多次重排
  var frag = document.createDocumentFragment();
  (list || []).forEach(function (sec) {
    if (!sec) return;
    seen[sec.key] = true;
    var el = PANE_SECTIONS[sec.key];
    if (!el) {
      el = document.createElement('div');
      el.className = sec.full ? 'pane-sec pane-full' : 'pane-sec';
      el.setAttribute('data-sec', sec.key);
    el.classList.add('appear');
      el.__html = sec.html;
      el.innerHTML = sec.html;
      PANE_SECTIONS[sec.key] = el;
      frag.appendChild(el);
    } else if (el.__html !== sec.html) {
      // 只有内容真的变了才替换这一块
      el.__html = sec.html;
      el.innerHTML = sec.html;
    }
    ordered.push(el);
  });
  // 摘掉已经不存在的 section
  Object.keys(PANE_SECTIONS).forEach(function (k) {
    if (seen[k]) return;
    var n = PANE_SECTIONS[k];
    if (n && n.parentNode) n.parentNode.removeChild(n);
    delete PANE_SECTIONS[k];
  });
  // 清掉骨架屏等临时节点（没有 data-sec 的都是一次性产物）
  Array.prototype.slice.call(pane.children).forEach(function (n) {
    if (!n.getAttribute || !n.getAttribute('data-sec')) pane.removeChild(n);
  });
  if (frag.childNodes.length) pane.appendChild(frag);
  // 按目标顺序摆放：只移动位置不对的节点
  var ref = null;
  ordered.forEach(function (el) {
    if (ref) {
      if (ref.nextSibling !== el) pane.insertBefore(el, ref.nextSibling);
    } else if (pane.firstChild !== el) {
      pane.insertBefore(el, pane.firstChild);
    }
    ref = el;
  });
}
function renderData() {
  if (AUTH_MODE) { renderAuth('令牌无效或已过期，请重新输入令牌'); return; }
  if (!TOKEN) { renderAuth('请输入访问令牌后进入控制台'); return; }
  var errBox = $('err-box');
  var pane = $('pane');
  if (!errBox || !pane) return;
  var errHtml = '';
  if (LAST_ERR_KIND && LAST_ERR_KIND !== 'unauthorized') {
    errHtml = errorState(LAST_ERR_KIND, LAST_ERR);
  }
  // 错误提示块单独比内容：没变就不动 DOM
  if (errBox.__html !== errHtml) {
    errBox.__html = errHtml;
    errBox.innerHTML = errHtml;
  }
  var hasData = MSGS.length || ANALYSES.length || ADVISORS.length;
  // 只有错误、没有旧数据 -> 只显示错误态；错误 + 旧数据 -> 错误态在上、数据在下
  if (errHtml && !hasData) {
    clearPaneSections();
    if (pane.__cleared !== true) {
      pane.__cleared = true;
      pane.innerHTML = '';
    }
    return;
  }
  pane.__cleared = false;
  patchSections(renderTabSections());
}
function render() {
  renderSyncLine();
  renderOverview();
  renderFilterBar(false);
  renderBadges();
  renderData();
}
// 下一步建议：服务端表驱动规则给的 0–3 条动作，按 jump 提供跳转按钮
function suggestHtml(s) {
  if (!s) return '';
  var jumpMap = { queue: 'queue', todo: 'todo', conv: 'conv', adv: 'adv' };
  var to = jumpMap[s.jump] || '';
  var labelMap = { queue: '去看排队', todo: '去处理待办', conv: '去看会话', adv: '去看顾问' };
  var act = to ? ('<button type="button" class="btn btn-secondary" data-action="jump"' +
    ' data-jump="' + esc(to) + '">' + esc(labelMap[to] || '去看看') + '</button>') : '';
  return '<div class="card suggest-card">' +
    '<div class="conv-head"><span class="conv-main">' +
    '<span class="conv-title-row"><span class="conv-name">' + esc(s.title || '建议') + '</span>' +
    '<span class="badge b-app">优先级 ' + esc(s.priority == null ? '—' : s.priority) + '</span>' +
    '</span></span></div>' +
    (s.why ? '<div class="ana-meta">为什么：' + esc(s.why) + '</div>' : '') +
    (s.how ? '<div class="ana-meta">怎么做：' + esc(s.how) + '</div>' : '') +
    (s.evidence ? '<div class="conv-preview"><span class="pv-text">证据：' + esc(s.evidence) +
      '</span></div>' : '') +
    (act ? '<div class="state-actions">' + act + '</div>' : '') +
    '</div>';
}
function suggestSections() {
  if (!SUGGESTS || !SUGGESTS.length) return [];
  // 标题带来源口径：待办 / 未分析会话这两族数字是服务端按 pkg 算的，不标出来
  // 用户会以为「已筛选：飞书」下面的数字还是全库的。
  var scope = APP_FILTER ? (' · 仅 ' + pkgLabel(APP_FILTER)) : '';
  var out = [{
    key: 'suggest-head',
    html: '<div class="sec-head">下一步建议（' + SUGGESTS.length + '）' +
      esc(scope) + '</div>',
    full: true
  }];
  SUGGESTS.forEach(function (s, i) {
    out.push({ key: 'suggest:' + i + ':' + encKey(String(s.type || '')), html: suggestHtml(s) });
  });
  return out;
}
// 脏数据提示（skipped 累计），放在建议之前
function skipSections() {
  var h = skipNoticeHtml();
  return h ? [{ key: 'skip-note', html: h, full: true }] : [];
}
// 当前 tab 的 section 列表（每个 tab 渲染函数返回 [{key, html, full}]）
function renderTabSections() {
  if (CUR_TAB === 'conv') return skipSections().concat(suggestSections(), renderConvs());
  if (CUR_TAB === 'todo') return skipSections().concat(suggestSections(), renderTodos());
  if (CUR_TAB === 'queue') return skipSections().concat(suggestSections(), renderQueue());
  if (CUR_TAB === 'stat') return skipSections().concat(suggestSections(), renderStats());
  return skipSections().concat(suggestSections(), renderAdvisor());
}
// 同步 tab 状态：roving tabindex（选中 tab 可 Tab 进入，其余 -1）+ aria-selected
function syncTabs(t) {
  ['conv', 'todo', 'queue', 'stat', 'adv'].forEach(function (k) {
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
function switchTab(t) {
  CUR_TAB = t;
  syncTabs(t);
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
    network: { ico: '📡', title: '网络错误', desc: '连不上服务器，请检查手机网络、服务是否在运行，以及是否处于离线状态。' },
    // 顾问存档损坏是「数据在、但读不出来」：既要报错，也要说清不是「没有报告」。
    advisor_archive: { ico: '🧯', title: '顾问存档损坏', desc: '服务端解析顾问报告存档失败：为防历史报告被整份覆盖，写入已暂停，读取只能返回空列表。文件仍在服务器上，这不是「没有报告」。' }
  }[kind] || { ico: '⚠️', title: '出错了', desc: '发生未知错误。' };
  return '<div class="state state-error" role="alert">' +
    '<div class="state-ico" aria-hidden="true">' + meta.ico + '</div>' +
    '<div class="state-title">' + esc(meta.title) + '</div>' +
    '<div class="state-desc">' + esc(meta.desc) + '</div>' +
    (msg ? '<div class="state-next">' + esc(msg) + '</div>' : '') +
    '<div class="state-actions">' +
    '<button type="button" class="btn btn-primary" data-action="retry">重试</button>' +
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
  if (q) acts += '<button type="button" class="btn btn-secondary" data-action="clear-search">清除搜索</button>';
  if (APP_FILTER) acts += '<button type="button" class="btn btn-secondary" data-action="clear-app">查看全部 App</button>';
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
    queue: ['当前没有排队任务', '手机端没有排队或运行中的分析任务，也没有待同步的消息。',
      '在手机端触发一次分析或等下一轮同步，队列状态会自动出现在这里。'],
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

// ---------- 分页：按 next_before 游标翻页 + 「没有更多」状态 ----------
// 服务端只在「真的还有下一页」时才给 next_before：游标为空即到底。
function moreHtml(kind) {
  var isAna = (kind === 'ana');
  var next = isAna ? ANA_NEXT : MSG_NEXT;
  var pages = isAna ? ANA_PAGES : MSG_PAGES;
  if (!next) {
    return '<div class="more-wrap"><div class="more-state">已到底部 · 没有更多了</div></div>';
  }
  return '<div class="more-wrap">' +
    '<button type="button" class="btn btn-secondary more-btn" data-action="more"' +
    ' data-kind="' + (isAna ? 'ana' : 'msg') + '"' + (MORE_BUSY ? ' disabled' : '') + '>' +
    (MORE_BUSY ? '加载中…' : '加载更多') + '</button>' +
    '<div class="more-state">已加载 ' + pages + ' 页，还有更多</div></div>';
}

// ---------- 事件委托：卡片动作统一由容器处理 ----------
// 卡片是 innerHTML 生成的、没有稳定引用，用 data-action + 委托代替内联 onclick。
function handleAction(action, btn) {
  var key = decKey(btn.getAttribute('data-key') || '');
  if (action === 'toggle') { toggleNodeByKey(key); return; }
  if (action === 'copy') { copyConvNameByKey(key); return; }
  if (action === 'more') { loadMore(btn.getAttribute('data-kind') || 'msg'); return; }
  if (action === 'retry') { retryLoad(); return; }
  if (action === 'clear-search') { clearSearch(); return; }
  if (action === 'clear-app') { clearAppFilter(); return; }
  if (action === 'queue-filter') { setQueueState(btn.getAttribute('data-state') || ''); return; }
  if (action === 'queue-device') { setQueueDevice(btn.getAttribute('data-device') || ''); return; }
  if (action === 'conv-dim') { setConvGroupDim(btn.getAttribute('data-dim') || 'date'); return; }
  if (action === 'jump') { switchTab(btn.getAttribute('data-jump') || 'conv'); return; }
}
function bindDelegation() {
  var data = $('data');
  if (data) {
    data.addEventListener('click', function (e) {
      var t = e.target;
      if (!t || !t.closest) return;
      var btn = t.closest('[data-action]');
      if (!btn || !data.contains(btn)) return;
      handleAction(btn.getAttribute('data-action'), btn);
    });
  }
  var chips = $('app-chips');
  if (chips) {
    chips.addEventListener('click', function (e) {
      var t = e.target;
      if (!t || !t.closest) return;
      var btn = t.closest('[data-pkg]');
      if (!btn || !chips.contains(btn)) return;
      setAppFilter(btn.getAttribute('data-pkg') || '');
    });
  }
}

// ---------- 主题控件：跟随系统 / 浅色 / 深色 ----------
function themeLabel(v) {
  return v === 'light' ? '浅色' : (v === 'dark' ? '深色' : '跟随系统');
}
function initThemeControl() {
  var sel = $('theme-select');
  if (!sel) return;
  var tw = window.__WX_THEME;
  if (!tw) return;
  if (tw.pref) sel.value = tw.pref();
  sel.addEventListener('change', function () {
    var v = sel.value;
    if (tw.set) tw.set(v);
    toast('主题：' + themeLabel(v));
  });
}

// ---------- 归类辅助：规则统一走 console_classify.js ----------
// 下面几个函数在规则脚本缺失（CC 为 null）时统一返回 null，
// 调用方据此退回改造前的平铺渲染：不抛异常、不产生坏 DOM。
// 按来源 App 分组会话
function groupConvsByPkg(items) {
  if (!CC || !CC.groupByPkg) return null;
  try {
    return CC.groupByPkg(items || [], PKG_LABELS, function (it) {
      return it && it.pkg;
    });
  } catch (e) { return null; }
}
// 按发送者分组消息（归一化规则在规则层：去控制字符、去「名字:」前缀）
function groupMsgsBySender(msgs) {
  if (!CC || !CC.groupBySender) return null;
  try { return CC.groupBySender(msgs || []); } catch (e) { return null; }
}
// 按重要度分区待办
function groupTodosByImportance(todos) {
  if (!CC || !CC.groupTodosByImportance) return null;
  try {
    // 重要度先过 impLabel 归一（端上可能上报 0-9 数值），再交给规则层分区
    return CC.groupTodosByImportance(todos || [], function (a) {
      return impLabel(importanceOf(a));
    });
  } catch (e) { return null; }
}

// ---------- 会话 tab：需关注分区 + 会话/日期分层折叠 ----------
// 返回 section 列表：每张会话卡是独立 section，
// 只有内容真的变了的那张卡才会被替换，折叠/展开其它卡片不受影响。
function renderConvs() {
  CONV_CARDS = {};
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
    var item = { ck: ck, name: name, list: list, pkg: normPkg(list[0].pkg) };
    if (todoByConv[ck]) watch.push(item);
    else rest.push(item);
  });
  var out = [];
  // 会话卡内的二级归类维度切换（按日期 / 按发送者）
  out.push({ key: 'conv-dim', html: convDimBar(), full: true });
  if (!watch.length && !rest.length) {
    out.push({ key: 'conv-empty', html: emptyStateFor('conv'), full: true });
    out.push({ key: 'conv-note', html: refreshNote(), full: true });
    return out;
  }
  if (watch.length) {
    out.push({
      key: 'conv-watch-head',
      html: '<div class="sec-head warn">⚡ 需关注（' + watch.length + '）</div>',
      full: true
    });
    watch.forEach(function (item) {
      CONV_CARDS[item.ck] = item.name;
      out.push({ key: 'card:' + encKey(item.ck), html: renderConvCard(item) });
    });
  }
  if (rest.length) {
    out.push({
      key: 'conv-rest-head',
      html: '<div class="sec-head">全部会话（' + rest.length + '）</div>',
      full: true
    });
    // 「全部会话」再按来源 App 二次分区：
    // 选中了某个 App 时不分区（只剩一个来源，分区没意义），直接平铺。
    var restGroups = APP_FILTER ? null : groupConvsByPkg(rest);
    if (restGroups) {
      restGroups.forEach(function (g) {
        out.push({
          key: 'conv-pkg:' + encKey(g.pkg),
          html: '<div class="sec-head sub">' + esc(g.label) + ' · ' + g.count + ' 个会话</div>',
          full: true
        });
        g.items.forEach(function (item) {
          CONV_CARDS[item.ck] = item.name;
          out.push({ key: 'card:' + encKey(item.ck), html: renderConvCard(item) });
        });
      });
    } else {
      rest.forEach(function (item) {
        CONV_CARDS[item.ck] = item.name;
        out.push({ key: 'card:' + encKey(item.ck), html: renderConvCard(item) });
      });
    }
  }
  // 分页：按 next_before 游标翻页；游标为空就是「没有更多」
  out.push({ key: 'conv-more', html: moreHtml('msg'), full: true });
  out.push({ key: 'conv-note', html: refreshNote(), full: true });
  return out;
}

// ---------- 会话卡内的二级归类维度（按日期 / 按发送者） ----------
// 维度选择持久化到 localStorage（wx_group_v1），刷新后保持
function setConvGroupDim(dim) {
  CONV_GROUP_DIM = (dim === 'sender') ? 'sender' : 'date';
  try { LS.set('wx_group_v1', CONV_GROUP_DIM); } catch (e) { }
  renderData();
}
function convDimBar() {
  var dims = [['date', '按日期'], ['sender', '按发送者']];
  var html = '<div class="chips" role="group" aria-label="会话内二级归类维度">';
  dims.forEach(function (d) {
    html += '<button type="button" class="chip-btn" aria-pressed="' +
      (CONV_GROUP_DIM === d[0] ? 'true' : 'false') +
      '" data-action="conv-dim" data-dim="' + d[0] + '">' + esc(d[1]) + '</button>';
  });
  html += '</div>';
  html += '<p class="q-note">会话展开后先按「' +
    (CONV_GROUP_DIM === 'sender' ? '发送者' : '日期') + '」分组，再按次级维度细分。</p>';
  return html;
}
// 当天零点（本地时区）：规则层按这个值分日期组，
// 保证同一天的消息落进同一组（传原始毫秒会一条一个组）
function dayStartMs(ms) {
  var d = new Date(Number(ms) || 0);
  if (isNaN(d.getTime())) return 0;
  d.setHours(0, 0, 0, 0);
  return d.getTime();
}
// 二级分组统一走规则层；规则层缺失时返回 null，由调用方降级成平铺
function groupMsgsByDim(msgs, dim) {
  if (!CC || !CC.groupMessages) return null;
  var list = msgs || [];
  var getTs = function (m) { return dayStartMs(m && m.timestamp); };
  var getSender = function (m) { return (m && m.sender != null) ? String(m.sender) : ''; };
  var groups = null;
  try {
    groups = CC.groupMessages(list, dim, getTs, getSender);
  } catch (e) {
    try { groups = CC.groupMessages(list, dim, getTs); } catch (e2) { groups = null; }
  }
  if (!groups || !groups.length) return null;
  for (var i = 0; i < groups.length; i++) {
    var g = groups[i];
    if (!g || g.key == null || !g.items) return null;
  }
  return groups;
}
// 一级/二级折叠组共用渲染：g = {key, dim, name, ts, count, items, children}
function convGroupHtml(baseKey, g) {
  var gkey = baseKey + '|' + g.key;
  var egk = encKey(gkey);
  var gOpen = !!COLLAPSE[gkey];
  var isSender = (g.dim === 'sender');
  var headCls = isSender ? 'sender-head' : 'date-head';
  var bodyCls = isSender ? 'sender-body' : 'date-body';
  var nameCls = isSender ? 'sender-name' : 'conv-name';
  var label = isSender ? esc(g.name || '未知发送者') :
    (g.ts ? esc(fmtDate(g.ts)) : esc(g.name || ''));
  var cnt = (g.count == null) ? (g.items || []).length : g.count;
  var html = '<button type="button" class="' + headCls + '" aria-expanded="' +
    (gOpen ? 'true' : 'false') + '" data-action="toggle" data-key="' + egk + '"' +
    ' data-head="' + egk + '" aria-controls="panel-' + egk + '"' +
    ' aria-label="' + esc((isSender ? '发送者 ' : '日期 ') + (g.name || label) + '，' + cnt + ' 条消息') + '">' +
    '<span class="caret" data-caret="' + egk + '" aria-hidden="true">' + (gOpen ? '▾' : '▸') + '</span>' +
    '<span class="' + nameCls + '">' + label + '</span>' +
    '<span class="conv-count">' + cnt + ' 条</span></button>' +
    '<div class="' + bodyCls + '" id="panel-' + egk + '" data-panel="' + egk + '"' +
    (gOpen ? '' : ' hidden') + '>';
  var kids = g.children || [];
  if (kids.length) {
    kids.forEach(function (c) { html += convGroupHtml(gkey, c); });
  } else {
    (g.items || []).forEach(function (m) { html += msgHtml(m); });
  }
  html += '</div>';
  return html;
}

// 单条消息的 HTML（会话卡内复用）
function msgHtml(m) {
  var badges = (m.is_group ? '<span class="badge b-grp">群</span>' : '') +
    (normPkg(m.pkg) !== PKG_WX ? '<span class="badge b-app">' +
      esc(pkgLabel(m.pkg)) + '</span>' : '') +
    (m.source === 'android-db' ? '<span class="badge b-db">库</span>' : '');
  return '<div class="msg"><div class="m-meta">' + badges + esc(m.sender) +
    ' · ' + esc(fmtTime(m.timestamp)) + '</div>' +
    '<div class="m-text">' + esc(m.text) + '</div></div>';
}

// 一级折叠组：会话（默认折叠，显示名称 + 条数 + 最新摘要 + 相对时间）；
// 展开后有 need_action 分析块 + 按日期的二级折叠组
function renderConvCard(item) {
  var ck = item.ck, name = item.name, list = item.list;
  var key = 'c:' + ck;
  var ek = encKey(key);
  var open = !!COLLAPSE[key];
  var latest = list[0];
  var todo = todoByConv[ck];
  // App 角标：微信不显示（主场景），其它 App 显示中文名，防止同名会话混淆
  var appBadge = normPkg(latest.pkg) === PKG_WX ? '' :
    '<span class="badge b-app">' + esc(pkgLabel(latest.pkg)) + '</span>';
  // 折叠头用原生 button：自带焦点环、Enter/空格触发、aria-expanded 语义，
  // 点击由 #data 上的事件委托统一处理（不再用内联 onclick）
  var html = '<div class="card"><div class="conv-row">' +
    '<button type="button" class="conv-head" aria-expanded="' +
    (open ? 'true' : 'false') + '" data-action="toggle" data-key="' + ek + '"' +
    ' data-head="' + ek + '" aria-controls="panel-' + ek + '"' +
    ' aria-label="' + esc('会话 ' + (name || '未命名') + '，' + list.length + ' 条消息') + '">' +
    '<span class="caret" data-caret="' + ek + '" aria-hidden="true">' + (open ? '▾' : '▸') + '</span>' +
    '<span class="conv-main">' +
    '<span class="conv-title-row">' + appBadge +
    '<span class="conv-name">' + esc(name) + '</span>' +
    (todo ? '<span class="todo-flag" aria-label="有待办">⚡</span>' : '') +
    '</span>' +
    '<span class="conv-preview"><span class="pv-text">' +
    esc(latest.sender) + '：' + esc(latest.text) + '</span>' +
    '<span class="pv-time">' + esc(relTime(latest.timestamp)) + '</span></span>' +
    '</span>' +
    '<span class="conv-count">' + list.length + ' 条</span></button>' +
    '<button type="button" class="copy-btn" aria-label="复制会话名" data-action="copy"' +
    ' data-key="' + encKey(ck) + '">⧉</button></div>' +
    '<div class="msg-list" id="panel-' + ek + '" data-panel="' + ek + '"' +
    (open ? '' : ' hidden') + '>';
  if (todo) html += renderAnalysisBlock(todo);
  // 二级折叠组：维度可切换（默认按日期，可切成按发送者）。
  // 分组与排序统一走规则层（console_classify.js 的 groupMessages），
  // 规则层缺失时降级成平铺消息（不抛异常、不产生坏 DOM）。
  var dim = (CONV_GROUP_DIM === 'sender') ? 'sender' : 'date';
  var dimGroups = groupMsgsByDim(list, dim);
  if (dimGroups) {
    dimGroups.forEach(function (g) {
      html += convGroupHtml(key, g);
    });
  } else {
    list.forEach(function (m) { html += msgHtml(m); });
  }
  return html + '</div></div>';
}

// 折叠 / 展开切换：按 key（不再依赖数组下标），只动对应节点（不重绘整页）
function toggleNodeByKey(nodeKey) {
  if (nodeKey == null) return;
  COLLAPSE[nodeKey] = !COLLAPSE[nodeKey];
  LS.set('wx_collapse_v1', JSON.stringify(COLLAPSE));
  var open = !!COLLAPSE[nodeKey];
  applyNodeState(encKey(nodeKey), open);
  toast(open ? '已展开' : '已收起');
}
// 只更新这个折叠组涉及的三个节点：面板显隐 / 箭头 / 折叠头 aria-expanded
function applyNodeState(ek, open) {
  var panel = document.querySelector('[data-panel="' + ek + '"]');
  if (panel) panel.hidden = !open;
  var caret = document.querySelector('[data-caret="' + ek + '"]');
  if (caret) caret.textContent = open ? '▾' : '▸';
  var head = document.querySelector('[data-head="' + ek + '"]');
  if (head) head.setAttribute('aria-expanded', open ? 'true' : 'false');
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
    chips += redactBadge(a);
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
  return html + redactDetail(a) + '</div>';
}

// 单条待办的 section：每条一个 section，key 稳定，改一条不会带动其它条重绘
function todoSection(a) {
  var appBadge = normPkg(a.pkg) === PKG_WX ? '' :
    '<span class="badge b-app">' + esc(pkgLabel(a.pkg)) + '</span>';
  var html = '<div class="card">' +
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
  return {
    key: 'todo:' + encKey(analysisKey(a)) + ':' + encKey(String(a.received_at || 0)),
    html: html
  };
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
  var out = [];
  if (!todos.length) {
    out.push({ key: 'todo-empty', html: emptyStateFor('todo'), full: true });
    out.push({ key: 'todo-note', html: refreshNote(), full: true });
    return out;
  }
  // 按重要度分区（高 → 中 → 低 → 未标）：CC 不可用时返回 null，退回旧版平铺
  var todoGroups = groupTodosByImportance(todos);
  if (todoGroups) {
    todoGroups.forEach(function (g) {
      // 分区头单独一个 section：key 稳定唯一（重要度 key 固定）
      out.push({
        key: 'todo-sec:' + encKey(g.key || 'none'),
        html: '<div class="sec-head">' + esc(g.label) + '（' + g.count + '）</div>',
        full: true
      });
      g.items.forEach(function (a) { out.push(todoSection(a)); });
    });
  } else {
    todos.forEach(function (a) { out.push(todoSection(a)); });
  }
  // 分析结果同样按 next_before 游标翻页
  out.push({ key: 'todo-more', html: moreHtml('ana'), full: true });
  out.push({ key: 'todo-note', html: refreshNote(), full: true });
  return out;
}

// ---------- 统计 tab ----------
function renderStats() {
  var q = QUERY.trim().toLowerCase();
  var msgs = filteredMsgs();
  var analyses = filteredAnalyses();
  // 无搜索/无来源过滤时用 /stats 的整库真值，否则退回「已加载」口径
  var useSrv = !!(STATS && !APP_FILTER && !q);
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
  if (useSrv) {
    bySrc = STATS.messages.by_source || {};
    byApp = STATS.messages.by_pkg || {};
  }
  var total = useSrv ? (STATS.messages.total || 0) : msgs.length;
  var convTotal = useSrv ? (STATS.messages.convs || 0) : Object.keys(analyzedConvs).length;
  var anaTotal = useSrv ? (STATS.analyses.total || 0) : analyses.length;
  var lastMs = useSrv ? (STATS.messages.last_received_at || 0) : lastReport;
  function cell(num, label) {
    return '<div class="card"><div class="stat-num">' + esc(num) + '</div>' +
      '<div class="stat-label">' + esc(label) + '</div></div>';
  }
  // /stats 的 by_pkg / by_source 是 [{pkg|source, count}]，前端本地算的是 {键: 次数}；两种形状都要能吃
  function pairList(obj, keyName) {
    if (Object.prototype.toString.call(obj) === '[object Array]') {
      return obj.map(function (x) {
        return [String(x && x[keyName] != null ? x[keyName] : ''), Number(x && x.count || 0)];
      });
    }
    return Object.keys(obj || {}).map(function (k) { return [k, Number(obj[k] || 0)]; });
  }
  var srcPairs = pairList(bySrc, 'source');
  var appPairs = pairList(byApp, 'pkg');
  var srcTxt = srcPairs.map(function (p) {
    return (p[0] === 'android-db' ? '数据库' : p[0] === 'android-notification' ? '通知' : p[0]) +
      ' ' + p[1];
  }).join(' / ');
  var appTxt = appPairs.map(function (p) {
    return pkgLabel(p[0]) + ' ' + p[1];
  }).join(' / ');
  var out = [];
  out.push({
    key: 'stat-grid',
    html: '<div class="stat-grid">' +
      cell(total, useSrv ? '消息总数（全库）' : '消息总数（已加载）') +
      cell(useSrv ? convTotal : Object.keys(analyzedConvs).length,
        useSrv ? '总会话数（全库）' : '已分析会话数') +
      cell(anaTotal, useSrv ? '分析结果数（全库）' : '分析结果数') +
      cell(lastMs ? fmtTime(lastMs) : '—', '最近上报时间') +
      '</div>',
    full: true
  });
  if (useSrv) {
    out.push({
      key: 'stat-redact',
      html: '<div class="card"><div class="stat-label">脱敏情况</div>' +
        '<div class="stat-value">' + (STATS.analyses.redacted_cases || 0) + ' 条已脱敏 / ' +
        (STATS.analyses.redaction_hits || 0) + ' 处命中</div>' +
        '<div class="ov-sub">上报前已把命中规则的内容替换为占位符，原文不出设备</div></div>'
    });
    var kindTxt = pairList(STATS.queue.by_kind || [], 'kind').map(function (p) {
      return p[0] + ' ' + p[1];
    }).join(' / ');
    out.push({
      key: 'stat-queue',
      html: '<div class="card"><div class="stat-label">排队任务</div>' +
        '<div class="stat-value">活跃 ' + (STATS.queue.active || 0) + ' · 失败 ' +
        (STATS.queue.failed || 0) + ' · 陈旧 ' + (STATS.queue.stale || 0) + '</div>' +
        '<div class="ov-sub">' + esc(kindTxt || '暂无任务') + ' · ' +
        esc(deviceTallyText(STATS.queue)) + '</div></div>'
    });
  }
  out.push({
    key: 'stat-app',
    html: '<div class="card"><div class="stat-label">应用分布</div>' +
      '<div class="stat-value">' + esc(appTxt || '—') + '</div></div>'
  });
  out.push({
    key: 'stat-src',
    html: '<div class="card"><div class="stat-label">来源分布</div>' +
      '<div class="stat-value">' + esc(srcTxt || '—') + '</div></div>'
  });
  // 最近分析结果（供全局搜索按 s1 / s2 / case_id 命中）
  var list = analyses.filter(function (a) {
    if (!q) return true;
    return analysisSearchText(a).indexOf(q) >= 0;
  });
  out.push({
    key: 'stat-list-head',
    html: '<div class="sec-head">最近分析结果（' + list.length + '）</div>',
    full: true
  });
  if (!list.length) {
    out.push({ key: 'stat-empty', html: emptyStateFor('stat'), full: true });
  } else {
    list.slice(0, 30).forEach(function (a) {
      var appBadge = normPkg(a.pkg) === PKG_WX ? '' :
        '<span class="badge b-app">' + esc(pkgLabel(a.pkg)) + '</span>';
      var summary = (a.s2 && a.s2.summary) ? a.s2.summary : '';
      var html = '<div class="card">' +
        '<div class="ana-head">' + appBadge +
        '<span class="conv-name">' + esc(a.conversation) + '</span>' +
        '<span class="conv-count">' + esc(a.case_id || '') + '</span>' +
        redactBadge(a) + '</div>' +
        '<div class="ana-meta">重要度 ' + esc(importanceOf(a) || '—') +
        ' · 分析于 ' + esc(a.analyzed_at || '') +
        ' · 消息 ' + esc(a.message_count == null ? 0 : a.message_count) + ' 条</div>' +
        (summary ? '<div class="conv-preview"><span class="pv-text">' +
          esc(summary) + '</span></div>' : '') +
        redactDetail(a) +
        '</div>';
      out.push({
        key: 'stat-item:' + encKey(analysisKey(a)) + ':' + encKey(String(a.received_at || 0)),
        html: html
      });
    });
  }
  out.push({ key: 'stat-note', html: refreshNote(), full: true });
  return out;
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
  var out = [];
  // 存档损坏：必须先报错再谈空态。读接口回的是 200 + ok:false（不是 5xx），
  // 只取 reports 会把「文件坏了」渲染成「还没有报告」，把用户引去等端上跑复盘。
  if (ADVISORS_ERR) {
    out.push({
      key: 'adv-error',
      html: errorState('advisor_archive', ADVISORS_ERR +
        ' 修好之前新的顾问上报会一直失败（POST /advisor 回 5xx），历史报告仍在服务器上。'),
      full: true
    });
    out.push({ key: 'adv-note', html: refreshNote(), full: true });
    return out;
  }
  if (!reports.length) {
    out.push({ key: 'adv-empty', html: emptyStateFor('adv'), full: true });
    out.push({ key: 'adv-note', html: refreshNote(), full: true });
    return out;
  }
  if (APP_FILTER) {
    out.push({
      key: 'adv-note-filter',
      html: '<div class="note">顾问报告不携带来源 App 字段，无法按「' +
        esc(pkgLabel(APP_FILTER)) + '」过滤，下面显示的是全部报告。</div>',
      full: true
    });
  }
  reports.forEach(function (r, i) {
    var html = '';
    // 建议按 type 分组：类型优先序固定（watchlist → threshold → prompt → other，
    // 未知类型排最后），组内按 title 升序。规则来自 console_classify.js；
    // 取不到规则脚本时退回旧口径（顺序 = 数据出现顺序），行为与改造前一致。
    var groups = null;
    if (CC && CC.groupSuggestions) {
      try { groups = CC.groupSuggestions(r.suggestions || []); } catch (e) { groups = null; }
    }
    if (!groups) {
      // 降级路径：本地旧分组（未知类型落到 other，顺序 = 数据出现顺序）
      groups = [];
      var localGroups = {}, localOrder = [];
      (r.suggestions || []).forEach(function (s) {
        if (!s) return;
        var t = ADV_TYPE_META[s.type] ? s.type : 'other';
        if (!localGroups[t]) { localGroups[t] = []; localOrder.push(t); }
        localGroups[t].push(s);
      });
      localOrder.forEach(function (t) {
        groups.push({ type: t, items: localGroups[t] });
      });
    }
    html += '<div class="card">' +
      '<div class="conv-row"><div class="conv-head">' +
      '<span class="conv-main"><span class="conv-title-row">' +
      '<span class="conv-name">🧭 复盘报告</span></span></span>' +
      '<span class="conv-count">' + esc(stampText(r.created_at)) + '</span>' +
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
    groups.forEach(function (g) {
      // 未知类型统一落到 other 的图标/中文名
      var meta = ADV_TYPE_META[g.type] || ADV_TYPE_META.other;
      html += '<div class="s2"><div class="row"><span class="label">' +
        meta.ico + ' ' + meta.label + '</span><ul>';
      (g.items || []).forEach(function (s) {
        if (!s) return;
        html += '<li><b>' + esc(s.title || '') + '</b>' +
          (s.detail ? ' — ' + esc(s.detail) : '') + '</li>';
      });
      html += '</ul></div></div>';
    });
    html += '</div>';
    out.push({
      key: 'adv:' + i + ':' + encKey(String(r.created_at || '')) + ':' +
        encKey(String(r.case_count == null ? '' : r.case_count)),
      html: html
    });
  });
  out.push({ key: 'adv-note', html: refreshNote(), full: true });
  return out;
}


// ---------- 排队任务 tab（端上 WorkManager 队列 + 待同步消息队列）----------
var QUEUE_STATE_META = {
  running: { label: '运行中', cls: 'q-running' },
  queued: { label: '排队中', cls: 'q-queued' },
  failed: { label: '失败', cls: 'q-failed' },
  done: { label: '已完成', cls: 'q-done' }
};
var QUEUE_KIND_META = {
  analysis: '分析',
  sync: '同步',
  model: '模型',
  other: '其它'
};
function queueStateLabel(st) {
  var m = QUEUE_STATE_META[st || ''];
  return m ? m.label : '排队中';
}
function queueKindLabel(k) {
  return QUEUE_KIND_META[k || ''] || '其它';
}
// 「还在跑的设备」台数：devices 清单里 active > 0 的那些。
// 清单本身回答「库里出现过哪几台设备」，设备停报（换机 / 卸载 / 关掉上报）后
// 它的过期行要等 7 天保留期才清，这段时间 active 恒为 0 —— 拿清单长度当设备数
// 会在概览条写出「当前没有排队任务 · 2 台设备」。服务端 App 走 /stats 的
// queue.devices_active，这里只给离线 / 估算场景兜底。
function liveDeviceCount() {
  var n = 0;
  for (var i = 0; i < QUEUE_DEVICES.length; i++) {
    if (Number((QUEUE_DEVICES[i] && QUEUE_DEVICES[i].active) || 0) > 0) n++;
  }
  return n;
}
// 设备台数文案：总台数与「在跑」台数分开报，两者不等时才补后半句，
// 免得用户看到「设备 2 台」却找不到任何在跑的任务。
function deviceTallyText(q) {
  if (!q) return '设备 —';
  var all = Number(q.devices || 0);
  if (!all) return '设备 0 台';
  // 老服务端没有 devices_active 时不能凭空报「在跑 0 台」，按「不知道就当全在跑」
  // 保守处理：只报总台数，别补后半句。
  var live = Number(q.devices_active);
  if (!isFinite(live)) live = all;
  return '设备 ' + all + ' 台' + (live < all ? ('（在跑 ' + live + ' 台）') : '');
}
// 活跃任务数（排队中 + 运行中）：概览条与 tab 角标都用它。
// 优先用 GET /queue 的 summary（服务端全库口径，与 /stats 的 queue 段同源），
// 拿不到才退回「数已加载的列表」—— 否则一页 200 条时角标永远停在 200。
function queueActiveCount() {
  var sm = QUEUE_SUMMARY;
  if (sm && typeof sm.queued === 'number' && typeof sm.running === 'number') {
    return Number(sm.queued || 0) + Number(sm.running || 0);
  }
  var n = 0;
  for (var i = 0; i < QUEUE.length; i++) {
    var st = QUEUE[i].state || '';
    if (st === 'running' || st === 'queued') n++;
  }
  return n;
}
// 单个状态的条数（全库口径）。状态 chip 的计数与上方汇总必须同源：
// 直接数 QUEUE 数组数的是「最近 500 条这一页」，600 条排队中 + 20 条已完成时
// 会把 chip 的「已完成」显示成 0，而汇总卡（走 summary）显示 20 —— 同屏两个数打架。
// summary 拿不到（排队接口挂了）才退回数已加载的列表，至少不比空白差。
function queueStateTotal(st) {
  var sm = QUEUE_SUMMARY;
  if (sm && typeof sm[st] === 'number') return Number(sm[st] || 0);
  if (st === 'total') return QUEUE.length;
  var n = 0;
  for (var i = 0; i < QUEUE.length; i++) {
    if ((QUEUE[i].state || '') === st) n++;
  }
  return n;
}
function setQueueState(st) {
  QUEUE_STATE = st || '';
  renderData();
}
// 设备筛选（多台设备时才出现）
function setQueueDevice(d) {
  QUEUE_DEVICE = d || '';
  renderData();
}
function filteredQueues() {
  var q = QUERY.trim().toLowerCase();
  var out = QUEUE.slice();
  if (QUEUE_STATE) {
    out = out.filter(function (t) { return (t.state || '') === QUEUE_STATE; });
  }
  if (QUEUE_DEVICE) {
    out = out.filter(function (t) { return (t.device_id || '') === QUEUE_DEVICE; });
  }
  if (q) {
    out = out.filter(function (t) {
      var hay = [t.title || '', t.detail || '', t.device_id || '', t.task_id || '',
        queueKindLabel(t.kind), queueStateLabel(t.state)].join(' ').toLowerCase();
      return hay.indexOf(q) >= 0;
    });
  }
  return out;
}
function queueSummaryCard() {
  var sm = QUEUE_SUMMARY || {};
  var cells = [
    ['qs-running', Number(sm.running || 0), '运行中'],
    ['qs-queued', Number(sm.queued || 0), '排队中'],
    ['qs-failed', Number(sm.failed || 0), '失败'],
    ['qs-done', Number(sm.done || 0), '已完成'],
    ['qs-stale', Number(sm.stale || 0), '已过期']
  ];
  var html = '<div class="queue-sum">';
  cells.forEach(function (c) {
    html += '<div class="queue-stat ' + c[0] + '"><div class="qs-num">' + esc(c[1]) +
      '</div><div class="qs-label">' + esc(c[2]) + '</div></div>';
  });
  html += '</div>';
  if (QUEUE_ERR) {
    html += '<div class="q-note">排队任务接口暂不可用：' + esc(QUEUE_ERR) + '。其它数据不受影响。</div>';
  } else {
    html += '<div class="q-note">数据来自手机端同步上报：分析任务的排队 / 运行 / 失败状态，以及待同步消息队列。超过 30 分钟未更新的条目算「已过期」。' +
      '上方汇总为全库口径（与「统计」Tab 一致），下方列表最多显示最近 500 条。' +
      '设备 chip 上的「活跃」只算未过期的条目，「已过期」是那台设备挂着但已经不在跑的任务数。' +
      '概览条上的设备数只算「还有在跑任务」的台数：停报设备的过期行要等 7 天保留期才清，' +
      '这期间它会挂在设备清单里但不算在跑。' +
      '状态 chip 上的数字同样是全库口径（过期行只计入「已过期」），不是这一页的条数。' +
      '7 天前的行会在读取或上报时顺带清掉。</div>';
  }
  return html;
}
function queueFilterBar() {
  var states = ['running', 'queued', 'failed', 'done'];
  var html = '<div class="chips" role="group" aria-label="按排队状态筛选">';
  html += '<button type="button" class="chip-btn" aria-pressed="' + (QUEUE_STATE ? 'false' : 'true') +
    '" data-action="queue-filter" data-state="">全部</button>';
  states.forEach(function (st) {
    var meta = QUEUE_STATE_META[st];
    var cnt = queueStateTotal(st);
    html += '<button type="button" class="chip-btn" aria-pressed="' + (QUEUE_STATE === st ? 'true' : 'false') +
      '" data-action="queue-filter" data-state="' + st + '">' + esc(meta.label) + ' ' + esc(cnt) + '</button>';
  });
  html += '</div>';
  // 设备筛选：只有一台设备时不给切换入口（筛了等于没筛），多台才显示
  if (QUEUE_DEVICES.length > 1) {
    html += '<div class="chips" role="group" aria-label="按设备筛选">';
    html += '<button type="button" class="chip-btn" aria-pressed="' + (QUEUE_DEVICE ? 'false' : 'true') +
      '" data-action="queue-device" data-device="">全部设备</button>';
    QUEUE_DEVICES.forEach(function (d) {
      var did = (d && d.device_id != null) ? String(d.device_id) : '';
      var ac = Number((d && d.active) || 0);
      var sl = Number((d && d.stale) || 0);
      // active 是「未过期的排队中 + 运行中」，与上方汇总结算口径一致；
      // stale 单列一句，替用户解释「这台设备挂着任务但已经不在跑了」。
      var tail = ' ' + esc(ac) + ' 活跃' + (sl > 0 ? ' · ' + esc(sl) + ' 已过期' : '');
      html += '<button type="button" class="chip-btn" aria-pressed="' + (QUEUE_DEVICE === did ? 'true' : 'false') +
        '" data-action="queue-device" data-device="' + esc(did) + '">' + esc(did || '未知设备') +
        tail + '</button>';
    });
    html += '</div>';
  }
  return html;
}
function queueCard(t) {
  var st = t.state || 'queued';
  var meta = QUEUE_STATE_META[st];
  var cls = meta ? meta.cls : 'q-queued';
  if (t.stale) cls += ' q-stale';
  var html = '<div class="card q-card ' + cls + '">';
  html += '<div class="q-head"><span class="chip">' + esc(queueKindLabel(t.kind)) + '</span>' +
    '<span class="q-title">' + esc(t.title || '未命名任务') + '</span>' +
    (t.stale ? '<span class="chip">已过期</span>' : '') + '</div>';
  if (t.detail) html += '<div class="q-detail">' + esc(t.detail) + '</div>';
  html += '<div class="q-meta"><span>' + esc(queueStateLabel(st)) + '</span>' +
    '<span>设备 ' + esc(t.device_id || '—') + '</span>' +
    '<span>排队于 ' + esc(t.queued_at ? fmtTime(t.queued_at) : '—') + '</span>' +
    '<span>更新于 ' + esc(t.updated_at ? relTime(t.updated_at) : '—') + '</span></div>';
  html += '</div>';
  return {
    key: 'queue-item:' + encKey((t.device_id || '') + '/' + (t.task_id || '')),
    html: html,
    full: true
  };
}
// 空态有两种成因，不能混成一句「当前没有排队任务」：
// 任务超一页时选了「已完成」、而这一页全是排队中 —— 列表为空，chip 却说全库有 20 条。
// 这种时候要给「当前页没有命中 + 全库有多少」的解释，否则用户以为任务丢了。
function queueEmptyHtml() {
  // 搜索 / 设备筛选是「这一页里挑」，命中为 0 时不能拿全库数安慰用户，交回通用空态。
  if (QUERY.trim() || QUEUE_DEVICE) return emptyStateFor('queue');
  var want = QUEUE_STATE ? queueStateTotal(QUEUE_STATE) : queueStateTotal('total');
  if (want > 0) {
    var name = QUEUE_STATE ? ('「' + queueStateLabel(QUEUE_STATE) + '」') : '';
    return emptyState('当前页没有' + (name || '') + '任务',
      '全库共有 ' + want + ' 条' + (name || '') + '任务，它们不在这最近 500 条里。',
      '上方汇总与状态 chip 的计数是全库口径，下方列表最多显示最近 500 条；换一个状态筛选，或等更早的任务被清理后再看。');
  }
  return emptyStateFor('queue');
}
function renderQueue() {
  var tasks = filteredQueues();
  var out = [];
  out.push({ key: 'queue-sum', html: queueSummaryCard(), full: true });
  out.push({ key: 'queue-filter', html: queueFilterBar(), full: true });
  if (!tasks.length) {
    out.push({ key: 'queue-empty', html: queueEmptyHtml(), full: true });
    out.push({ key: 'queue-note', html: refreshNote(), full: true });
    return out;
  }
  var groups = null;
  if (!CC || !CC.groupQueueTasks) {
    groups = null;
  } else {
    groups = CC.groupQueueTasks(tasks);
  }
  if (groups && groups.length) {
    groups.forEach(function (g) {
      out.push({
        key: 'queue-sec:' + encKey(g.key),
        html: '<div class="sec-head">' + esc(g.label) + '<span class="sec-count">' + esc(g.count) + ' 个</span></div>',
        full: true
      });
      g.items.forEach(function (t) { out.push(queueCard(t)); });
    });
  } else {
    tasks.forEach(function (t) { out.push(queueCard(t)); });
  }
  out.push({ key: 'queue-note', html: refreshNote(), full: true });
  return out;
}

// ---------- tab 栏方向键切换 ----------
(function bindTabKeys() {
  var bar = document.querySelector('.tabbar');
  if (!bar) return;
  bar.addEventListener('keydown', function (e) {
    if (e.key !== 'ArrowLeft' && e.key !== 'ArrowRight') return;
    var keys = ['conv', 'todo', 'queue', 'stat', 'adv'];
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
  if (t && ['conv', 'todo', 'queue', 'stat', 'adv'].indexOf(t) >= 0) CUR_TAB = t;
  syncTabs(CUR_TAB);
  updateSearchUI();
  initThemeControl();
  bindDelegation();
  if (!TOKEN) {
    renderAuth('请输入访问令牌后进入控制台');
    renderSyncLine();
    renderOverview();
    renderFilterBar(true);
    return;
  }
  // 首次加载：骨架屏（DESIGN-10）—— 放在 #pane 里，不重建 #data 的固定结构
  var pane = $('pane');
  if (pane) pane.innerHTML = skeletonHtml(4);
  var dataEl = $('data');
  if (dataEl) dataEl.setAttribute('aria-busy', 'true');
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
    "background_color": "#f4f6f8",
    "theme_color": "#1d4ed8",
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
                "rx='100' fill='%231d4ed8'/%3E%3Ctext x='256' y='330' "
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
  '<style>:root{--bg:#f2f4f5;--surface:#ffffff;--text:#1f2328;--muted:#5b6570;' +
  '--border:#d8dee3;--accent:#1d4ed8;--on-accent:#ffffff}' +
  'body{margin:0;padding:24px;min-height:100vh;display:flex;align-items:center;' +
  'justify-content:center;background:var(--bg);color:var(--text);' +
  'font-family:-apple-system,BlinkMacSystemFont,sans-serif}' +
  '.box{max-width:360px;background:var(--surface);border:1px solid var(--border);' +
  'border-radius:12px;padding:20px;text-align:center}' +
  'h1{font-size:20px;margin:0 0 8px}' +
  'p{font-size:14px;line-height:1.6;color:var(--muted);margin:0 0 16px}' +
  'button{min-height:44px;padding:0 20px;font-size:16px;border:0;border-radius:8px;' +
  'background:var(--accent);color:var(--on-accent)}' +
  '@media (prefers-color-scheme:dark){:root{--bg:#0f1317;--surface:#171c22;' +
  '--text:#e9edf1;--muted:#a9b4c0;--border:#2a323b;--accent:#60a5fa;' +
  '--on-accent:#06130b}}</style>' +
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
      url.pathname.indexOf('/admin') >= 0 ||
      url.pathname.indexOf('/queue') >= 0 ||
      url.pathname.indexOf('/stats') >= 0 ||
      url.pathname.indexOf('/suggests') >= 0) {
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
