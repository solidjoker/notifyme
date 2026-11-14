/* 控制台前端「归类」纯函数核心（单一事实源）。
 *
 * 为什么单独成文件：
 * - 浏览器端：内联进 /console 页面（console_ui.py 里放一个 JS 占位标记，
 *   由 server/app.py 读取本文件内容注入，避免两端各写一份规则）；
 * - 测试端：能被 node 直接 require，跑真逻辑单测
 *   （server/test_console_classify.py 驱动 server/test_console_classify_runner.js）。
 *
 * 约束：纯函数、无 DOM 依赖、无网络、ES5 语法（浏览器与 node 双端都能跑）。
 * 归类维度：来源 App / 会话 / 日期 / 发送者 / 重要度 / 建议类型 / 排队任务。
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

  // 分布数组归一：[["<键>", 次数], ...]
  // 服务端 /stats 的 by_pkg / by_source 是按原始列 GROUP BY 出来的，NULL 与
  // 空串各自成组；前端先按标签渲染就会得到「微信 1 / 微信 1 / 微博 1 / 微信 1」
  // （实测：库里 4 行里有 1 行 pkg NULL、1 行空串）。归一必须在展示前做。
  // 归并会改变次数，所以顺序必须重新按次数降序排：沿用首次出现序会出
  // 「微博 2 / 微信 3」这种升序错位（微信的三个分组合并后才是最大头）。
  // 同次数按键名码点升序，保证同一份数据永远得到同一串。
  function mergePairsBy(pairs, normalize) {
    var map = Object.create(null), order = [];  // 键名来自外部数据，别用 {}
    (pairs || []).forEach(function (p) {
      var raw = (p && p[0] != null) ? p[0] : '';
      var k = normalize(raw);
      if (!(k in map)) { map[k] = 0; order.push(k); }
      map[k] += Number((p && p[1]) || 0);
    });
    // 次数为 0 的分组不渲染（服务端不会给出 count=0，这里只防脏输入）
    return order.map(function (k) { return [k, map[k]]; })
      .filter(function (p) { return p[1] > 0; })
      .sort(function (a, b) {
        // 次数降序、同次数键名升序：与 /stats 的 ORDER BY COUNT(*) DESC, key ASC 对齐
        if (a[1] !== b[1]) return b[1] - a[1];
        return a[0] < b[0] ? -1 : (a[0] > b[0] ? 1 : 0);
      });
  }

  // 来源 App 分布：老数据没有 pkg（NULL / 空串）一律按微信算，与 normPkg 同口径
  function mergePkgPairs(pairs) {
    return mergePairsBy(pairs, normPkg);
  }

  // 来源通道分布：空值归成 'unknown'，与前端本地估算档的 'm.source || \'unknown\'' 一致
  function mergeSourcePairs(pairs) {
    return mergePairsBy(pairs, function (s) { return s ? String(s) : 'unknown'; });
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


  // ---------- 排队任务维度（端上 WorkManager 队列 + 待同步消息队列）----------
  // 展示优先序：先看正在动的（运行中），再看排队的，最后才是失败与已完成
  var QUEUE_STATE_ORDER = ['running', 'queued', 'failed', 'done'];
  var QUEUE_STATE_LABELS = {
    running: '运行中',
    queued: '排队中',
    failed: '失败',
    done: '已完成'
  };
  var QUEUE_KIND_LABELS = {
    analysis: '分析',
    sync: '同步',
    model: '模型',
    other: '其它'
  };

  // 归一化一条排队任务：字段越界就截断、非法枚举退回默认值、空 id 直接丢弃
  function normalizeQueueTasks(raw) {
    var list = [];
    var seen = Object.create(null);  // 键名来自外部数据，不能用普通对象
    var arr = raw || [];
    for (var i = 0; i < arr.length; i++) {
      var t = arr[i] || {};
      var id = t.task_id == null ? '' : String(t.task_id).trim();
      if (!id || id.length > 128) continue;
      var dev = t.device_id == null ? '' : String(t.device_id);
      var kind = QUEUE_KIND_LABELS[t.kind] ? String(t.kind) : 'other';
      var state = QUEUE_STATE_LABELS[t.state] ? String(t.state) : 'queued';
      var title = t.title == null ? '' : String(t.title).trim();
      if (!title) title = '未命名任务';
      if (title.length > 256) title = title.slice(0, 256);
      var detail = t.detail == null ? '' : String(t.detail);
      if (detail.length > 512) detail = detail.slice(0, 512);
      var queuedAt = Number(t.queued_at) || 0;
      if (queuedAt < 0) queuedAt = 0;
      var updatedAt = Number(t.updated_at) || queuedAt;
      var key = dev + '/' + id;
      var item = {
        device_id: dev,
        task_id: id,
        kind: kind,
        title: title,
        state: state,
        detail: detail,
        queued_at: queuedAt,
        updated_at: updatedAt,
        stale: !!t.stale
      };
      if (seen[key] !== undefined) {
        list[seen[key]] = item;   // 同一条目后到的覆盖先到的
      } else {
        seen[key] = list.length;
        list.push(item);
      }
    }
    list.sort(function (a, b) {
      var ra = QUEUE_STATE_ORDER.indexOf(a.state);
      var rb = QUEUE_STATE_ORDER.indexOf(b.state);
      if (ra !== rb) { return ra - rb; }
      if (a.queued_at !== b.queued_at) { return a.queued_at - b.queued_at; }
      if (a.task_id === b.task_id) { return 0; }
      return a.task_id < b.task_id ? -1 : 1;
    });
    return list;
  }

  // 汇总：四个状态只统计未过期的条目，过期单独计数，total = 条目总数
  function queueSummary(tasks) {
    var out = { running: 0, queued: 0, failed: 0, done: 0, stale: 0, total: 0 };
    var arr = tasks || [];
    for (var i = 0; i < arr.length; i++) {
      var t = arr[i] || {};
      out.total += 1;
      if (t.stale) { out.stale += 1; continue; }
      var st = String(t.state || '');
      if (out[st] !== undefined) { out[st] += 1; }
    }
    return out;
  }

  // 按状态分区：固定顺序（运行中 → 排队中 → 失败 → 已完成）
  function groupQueueTasks(tasks) {
    var list = normalizeQueueTasks(tasks);
    var out = [];
    QUEUE_STATE_ORDER.forEach(function (st) {
      var items = list.filter(function (t) { return t.state === st; });
      if (!items.length) { return; }
      out.push({ key: st, label: QUEUE_STATE_LABELS[st], count: items.length, items: items });
    });
    return out;
  }

  // ---------- 会话卡两级折叠分组（按日期 / 按发送者）----------

  // 组排序：date 组按 ts 倒序；sender 组按 count 倒序、同 count 按 name 升序。
  // 末位再按 key 兜底，保证同一份输入永远得到同一个顺序（确定性 + 稳定）。
  function sortMessageGroups(groups, dim) {
    groups.sort(function (a, b) {
      if (dim === 'date') {
        if (a.ts !== b.ts) { return b.ts - a.ts; }
      } else {
        if (a.count !== b.count) { return b.count - a.count; }
        if (a.name !== b.name) { return a.name < b.name ? -1 : 1; }
      }
      if (a.key !== b.key) { return a.key < b.key ? -1 : 1; }
      return 0;
    });
    return groups;
  }

  // 两级折叠分组：顶层按 dim 分组（date=日期组 / sender=发送者组），
  // 组内再按另一维度分一级子组；子组只有一个时不拆（items 平铺，不多一层废话）。
  //
  // getTs(m) 必须给：它返回的毫秒值就是「日期」的分组标识（调用方决定粒度，
  // 例如传当天零点对齐的时间戳，这一天的消息就落进同一个日期组）。
  // getSender(m) 可选，默认取 m.sender；名字归一一律复用 senderKey。
  function groupMessages(msgs, dim, getTs, getSender) {
    var list = msgs || [];
    var mode = (dim === 'sender') ? 'sender' : 'date';   // 其它维度一律按日期
    var tsOf = function (m) {
      var v = getTs ? getTs(m) : (m && m.timestamp);
      var n = Number(v);
      return isNaN(n) ? 0 : n;
    };
    var senderOf = function (m) {
      var raw = getSender ? getSender(m) : (m && m.sender);
      return senderKey(raw);
    };

    // 键名来自外部数据（发送者名可能正好叫 __proto__），一律用无原型字典
    var map = Object.create(null), order = [];
    for (var i = 0; i < list.length; i++) {
      var m = list[i];
      var ts = tsOf(m);
      var topKey = (mode === 'sender') ? senderOf(m) : String(ts);
      var g = map[topKey];
      if (!g) {
        g = {
          key: (mode === 'sender' ? 's|' : 'd|') + topKey,
          dim: mode,
          name: (mode === 'sender') ? topKey : String(ts),
          ts: ts,
          count: 0,
          items: [],
          children: [],
          subs: Object.create(null),
          subOrder: []
        };
        map[topKey] = g;
        order.push(topKey);
      }
      g.count += 1;
      g.items.push(m);
      if (ts > g.ts) { g.ts = ts; }

      // 子组：date 维度下按发送者再分，sender 维度下按 ts 再分
      var subKey = (mode === 'date') ? senderOf(m) : String(ts);
      var sub = g.subs[subKey];
      if (!sub) {
        sub = {
          key: (mode === 'date' ? 's|' : 'd|') + subKey,
          dim: (mode === 'date') ? 'sender' : 'date',
          name: (mode === 'date') ? subKey : String(ts),
          ts: ts,
          count: 0,
          items: []
        };
        g.subs[subKey] = sub;
        g.subOrder.push(subKey);
      }
      sub.count += 1;
      sub.items.push(m);
      if (ts > sub.ts) { sub.ts = ts; }
    }

    var out = [];
    order.forEach(function (k) {
      var g = map[k];
      var subs = [];
      g.subOrder.forEach(function (sk) { subs.push(g.subs[sk]); });
      // 只有一个子组时不展开二级（items 本来就是平铺的全部消息）
      if (subs.length > 1) {
        sortMessageGroups(subs, subs[0].dim);
        g.children = subs;
      }
      delete g.subs;
      delete g.subOrder;
      out.push(g);
    });
    sortMessageGroups(out, mode);
    return out;
  }

  // 按设备分组排队任务：active（排队中 + 运行中）倒序 -> count 倒序 -> device_id 升序
  function groupQueueTasksByDevice(tasks) {
    var list = normalizeQueueTasks(tasks);
    var map = Object.create(null), order = [];
    for (var i = 0; i < list.length; i++) {
      var t = list[i];
      var dev = t.device_id == null ? '' : String(t.device_id);
      var g = map[dev];
      if (!g) {
        g = { key: dev, name: dev, count: 0, active: 0, items: [] };
        map[dev] = g;
        order.push(dev);
      }
      g.count += 1;
      g.items.push(t);
      if (t.state === 'queued' || t.state === 'running') { g.active += 1; }
    }
    var out = order.map(function (k) { return map[k]; });
    out.sort(function (a, b) {
      if (a.active !== b.active) { return b.active - a.active; }
      if (a.count !== b.count) { return b.count - a.count; }
      if (a.name !== b.name) { return a.name < b.name ? -1 : 1; }
      return 0;
    });
    return out;
  }

  // 设备维度汇总：设备数 / 在跑任务数（排队中 + 运行中）/ 任务总数
  function queueDeviceSummary(tasks) {
    var groups = groupQueueTasksByDevice(tasks);
    var active = 0, total = 0;
    for (var i = 0; i < groups.length; i++) {
      active += groups[i].active;
      total += groups[i].count;
    }
    return { devices: groups.length, active: active, total: total };
  }

  return {
    VERSION: VERSION,
    QUEUE_STATE_ORDER: QUEUE_STATE_ORDER,
    QUEUE_STATE_LABELS: QUEUE_STATE_LABELS,
    QUEUE_KIND_LABELS: QUEUE_KIND_LABELS,
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
    mergePkgPairs: mergePkgPairs,
    mergeSourcePairs: mergeSourcePairs,
    importanceRank: importanceRank,
    sortTodos: sortTodos,
    groupTodosByImportance: groupTodosByImportance,
    groupSuggestions: groupSuggestions,
    normalizeQueueTasks: normalizeQueueTasks,
    queueSummary: queueSummary,
    groupQueueTasks: groupQueueTasks,
    groupMessages: groupMessages,
    groupQueueTasksByDevice: groupQueueTasksByDevice,
    queueDeviceSummary: queueDeviceSummary
  };
});
