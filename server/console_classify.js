/* 控制台前端「归类」纯函数核心（单一事实源）。
 *
 * 为什么单独成文件：
 * - 浏览器端：内联进 /console 页面（console_ui.py 里放一个 JS 占位标记，
 *   由 server/app.py 读取本文件内容注入，避免两端各写一份规则）；
 * - 测试端：能被 node 直接 require，跑真逻辑单测
 *   （server/test_console_classify.py 驱动 server/test_console_classify_runner.js）。
 *
 * 约束：纯函数、无 DOM 依赖、无网络、ES5 语法（浏览器与 node 双端都能跑）。
 * 归类维度：来源 App / 会话 / 日期 / 发送者 / 重要度 / 建议类型。
 */
(function (root, factory) {
  var api = factory();
  if (typeof module === 'object' && module.exports) { module.exports = api; }
  if (root) { root.ConsoleClassify = api; }
})(typeof globalThis !== 'undefined' ? globalThis : (typeof window !== 'undefined' ? window : this), function () {
  'use strict';

  var VERSION = 'v1';

  // 来源 App：老数据没有 pkg 字段，一律按微信算（与前端 PKG_WX 保持一致）
  var PKG_WX = 'com.tencent.mm';
  // 会话复合身份分隔符：U+0001（构造方式避免源码里出现裸控制字符）
  var SEP = String.fromCharCode(1);

  // 建议类型的展示优先序（业务语义：先看要动什么，再看怎么调）
  var ADVISOR_TYPE_ORDER = ['watchlist', 'threshold', 'prompt', 'other'];
  // 重要度分区顺序（空串 = 未标重要度）
  var IMPORTANCE_ORDER = ['high', 'mid', 'low', ''];
  var IMPORTANCE_LABELS = {
    high: '高重要度',
    mid: '中重要度',
    low: '低重要度',
    '': '未标重要度'
  };

  function normPkg(p) {
    return p ? String(p) : PKG_WX;
  }

  function pkgLabel(p, labels) {
    var k = normPkg(p);
    labels = labels || {};
    return labels[k] || k;
  }

  // 会话复合身份：pkg + SEP + 会话名（对齐安卓端 ConvKey）
  function ckOf(o, sep) {
    if (!o) return '';
    return normPkg(o.pkg) + (sep || SEP) + (o.conversation == null ? '' : String(o.conversation));
  }

  // 发送者归一化：
  // - 去控制字符 / 零宽字符（不同来源的不可见字符不一致）
  // - 去首尾空白
  // - 去「名字:」「名字：」这类前缀（部分来源把发言人写在冒号前）
  function senderKey(sender) {
    var s = sender == null ? '' : String(sender);
    s = s.replace(/[\u0000-\u001f\u007f-\u009f\u200b-\u200f\u2028-\u202e\ufeff]/g, '');
    s = s.replace(/^\s+|\s+$/g, '');
    var m = /^([^:：]{1,32})[:：]\s*/.exec(s);
    if (m) { s = m[1]; s = s.replace(/^\s+|\s+$/g, ''); }
    return s || '未知发送者';
  }

  // 按发送者归类：返回 [{key, name, count, lastTs, messages}]
  // 顺序 = 首次出现顺序（入参消息按时间倒序时，就是「最近说话的人在前面」）
  function groupBySender(messages) {
    // 用 Object.create(null) 而不是 {}：键名可能是「constructor」「__proto__」
    // （微信群成员名、来源包名都是外部数据），普通对象会把它们当成原型属性，
    // 导致 map[key] 命中 Object.prototype 并在 push 时抛错，整页控制台挂掉。
    var map = Object.create(null), order = [];
    (messages || []).forEach(function (m) {
      var name = senderKey(m && m.sender);
      if (!map[name]) {
        map[name] = { key: name, name: name, count: 0, lastTs: 0, messages: [] };
        order.push(name);
      }
      var g = map[name];
      g.count += 1;
      g.messages.push(m);
      var ts = Number(m && m.timestamp) || 0;
      if (ts > g.lastTs) { g.lastTs = ts; }
    });
    return order.map(function (k) { return map[k]; });
  }

  // 按来源 App 分区：返回 [{pkg, label, count, items}]，保持传入顺序
  function groupByPkg(items, labels, getPkg) {
    var map = Object.create(null), order = [];  // 同上：键名来自外部数据
    (items || []).forEach(function (it) {
      var pkg = normPkg(getPkg ? getPkg(it) : (it && it.pkg));
      if (!map[pkg]) {
        map[pkg] = { pkg: pkg, label: pkgLabel(pkg, labels), count: 0, items: [] };
        order.push(pkg);
      }
      map[pkg].count += 1;
      map[pkg].items.push(it);
    });
    return order.map(function (k) { return map[k]; });
  }

  // 重要度档位：high=0 mid=1 low=2 其它=3（未标重要度与未知值同档，排最后）
  function importanceRank(v) {
    var i = IMPORTANCE_ORDER.indexOf(String(v == null ? '' : v));
    // 未知值与空串同属「未标重要度」这一档（IMPORTANCE_ORDER 末位）
    return i < 0 ? IMPORTANCE_ORDER.length - 1 : i;
  }

  // 待办排序：高重要度在前，同级按上报时间倒序（新的在前）
  function sortTodos(todos, getImportance) {
    var arr = (todos || []).slice();
    arr.sort(function (a, b) {
      var ra = importanceRank(getImportance ? getImportance(a) : (a && a.importance));
      var rb = importanceRank(getImportance ? getImportance(b) : (b && b.importance));
      if (ra !== rb) { return ra - rb; }
      return (Number(b && b.received_at) || 0) - (Number(a && a.received_at) || 0);
    });
    return arr;
  }

  // 待办按重要度分区：返回 [{key, rank, label, count, items}]
  function groupTodosByImportance(todos, getImportance) {
    var sorted = sortTodos(todos, getImportance);
    var map = Object.create(null), order = [];
    sorted.forEach(function (t) {
      var raw = getImportance ? getImportance(t) : (t && t.importance);
      var v = String(raw == null ? '' : raw);
      var idx = IMPORTANCE_ORDER.indexOf(v);
      var key = idx < 0 ? '' : v;
      if (!map[key]) {
        map[key] = {
          key: key,
          rank: idx < 0 ? IMPORTANCE_ORDER.length - 1 : idx,
          label: IMPORTANCE_LABELS[key],
          count: 0,
          items: []
        };
        order.push(key);
      }
      map[key].count += 1;
      map[key].items.push(t);
    });
    return order.map(function (k) { return map[k]; });
  }

  // 顾问建议按类型分组：先按固定优先序，未知类型排最后，组内按标题排序
  function groupSuggestions(suggestions, order) {
    var ord = order && order.length ? order : ADVISOR_TYPE_ORDER;
    var map = Object.create(null), seen = [];
    (suggestions || []).forEach(function (s) {
      if (!s) { return; }
      var t = String(s.type || 'other');
      if (!map[t]) { map[t] = { type: t, items: [] }; seen.push(t); }
      map[t].items.push(s);
    });
    var out = [];
    ord.forEach(function (t) {
      if (map[t]) { out.push(map[t]); delete map[t]; }
    });
    seen.forEach(function (t) {
      if (map[t]) { out.push(map[t]); delete map[t]; }
    });
    out.forEach(function (g) {
      g.items.sort(function (a, b) {
        var ta = String((a && a.title) || '');
        var tb = String((b && b.title) || '');
        if (ta === tb) { return 0; }
        return ta < tb ? -1 : 1;
      });
    });
    return out;
  }

  return {
    VERSION: VERSION,
    PKG_WX: PKG_WX,
    SEP: SEP,
    ADVISOR_TYPE_ORDER: ADVISOR_TYPE_ORDER,
    IMPORTANCE_ORDER: IMPORTANCE_ORDER,
    IMPORTANCE_LABELS: IMPORTANCE_LABELS,
    normPkg: normPkg,
    pkgLabel: pkgLabel,
    ckOf: ckOf,
    senderKey: senderKey,
    groupBySender: groupBySender,
    groupByPkg: groupByPkg,
    importanceRank: importanceRank,
    sortTodos: sortTodos,
    groupTodosByImportance: groupTodosByImportance,
    groupSuggestions: groupSuggestions
  };
});
