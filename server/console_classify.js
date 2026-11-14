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

  // ---------- 文本截断：按码点，不按 UTF-16 单元 ----------
  // 为什么必须按码点：JS 的 String.length / slice 数的是 **UTF-16 code unit**，
  // 而 emoji（U+1F600）与 CJK 扩展 B 起的生僻字各占两个 unit（高代理 + 低代理）。
  // 按 unit 截断会把代理对切成孤半代理，JSON 序列化后是 \ud83d，浏览器渲染成 U+FFFD
  // —— 标题末尾凭空多一个「?」。实测（node 22）："a"*255 + 😀 截到 256 unit 后
  // 末位是 0xd83d（孤立高代理），clipCodePoints 给出的则是完整的 😀。
  //
  // 三层口径必须一致：端上 QueueStateCore 走 TextClipCore.clip（码点）、服务端
  // storage._normalize_queue_task 用 Python len()（也是码点，Python 字符串是码点序列），
  // 只有控制台这一处曾按 unit 数 —— 端上报 256 个 emoji 的标题（512 unit）能入库，
  // 却在展示前被再截成 128 个码点，用户看到的和库里存的不是同一条。
  //
  // 契约：不超过上限时**原样返回同一份字符串**；超限时截到第 maxCodePoints 个码点边界，
  // **不追加省略号**（旧的 slice(0, 256) 也不加，换口径不能顺手改长度语义）；
  // ASCII 输入输出与旧实现逐字节相同。
  function codePointLength(s) {
    var n = 0;
    for (var i = 0; i < s.length; i++) {
      var c = s.charCodeAt(i);
      if (c >= 0xd800 && c <= 0xdbff && i + 1 < s.length) {
        var d = s.charCodeAt(i + 1);
        if (d >= 0xdc00 && d <= 0xdfff) { i++; }
      }
      n++;
    }
    return n;
  }

  function clipCodePoints(s, maxCodePoints) {
    if (!(maxCodePoints > 0)) { return ''; }
    // 每个码点至少占 1 个 unit：unit 数没超过上限，码点数一定也没超
    if (s.length <= maxCodePoints) { return s; }
    var n = 0;
    var i = 0;
    while (i < s.length && n < maxCodePoints) {
      var c = s.charCodeAt(i);
      if (c >= 0xd800 && c <= 0xdbff && i + 1 < s.length) {
        var d = s.charCodeAt(i + 1);
        if (d >= 0xdc00 && d <= 0xdfff) { i++; }
      }
      i++;
      n++;
    }
    return s.slice(0, i);
  }


  // 归一化一条排队任务：字段越界就截断、非法枚举退回默认值、空 id 直接丢弃
  function normalizeQueueTasks(raw) {
    var list = [];
    var seen = Object.create(null);  // 键名来自外部数据，不能用普通对象
    var arr = raw || [];
    for (var i = 0; i < arr.length; i++) {
      var t = arr[i] || {};
      var id = t.task_id == null ? '' : String(t.task_id).trim();
      // 上限同样按码点判：storage._normalize_queue_task 用的是 Python len()（码点），
      // 端上 QueueStateCore 用 id.length（Kotlin String 是 UTF-16，但端上生成的 id 恒 ASCII）。
      // 这里若按 unit 判，同样的报文会在控制台被丢弃、在服务端被接受 —— 三方口径必须一致。
      if (!id || codePointLength(id) > 128) continue;
      var dev = t.device_id == null ? '' : String(t.device_id);
      var kind = QUEUE_KIND_LABELS[t.kind] ? String(t.kind) : 'other';
      var state = QUEUE_STATE_LABELS[t.state] ? String(t.state) : 'queued';
      var title = t.title == null ? '' : String(t.title).trim();
      if (!title) title = '未命名任务';
      // 空标题兜底必须排在截断之前：「未命名任务」本身没超限，顺序反了会白截一刀。
      title = clipCodePoints(title, 256);
      var detail = t.detail == null ? '' : String(t.detail);
      detail = clipCodePoints(detail, 512);
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

  // 顾问报告按来源 App 过滤后的「可见口径」。顾问报告**本身不一定带 pkg**：
  // storage._normalize_advisor_report 只在端上带了这个字段时才落库（老报告没有）。
  // 控制台此前只按「有没有 APP_FILTER」决定说明文案，一律写「无法按…过滤，
  // 下面显示的是全部报告」——而带 pkg 的报告确实被来源筛掉了，说明与事实相反。
  // 三种真实情形必须分开说，所以这里把计数口径收成一份：
  //   mode='all'      未筛选（调用方不该渲染说明）
  //   mode='untagged' 没有任何一份带 pkg —— 真的无法过滤，列表就是全部报告
  //   mode='mixed'    一部分带 pkg（已按来源筛掉别的），不带的仍会显示
  //   mode='exact'    全部带 pkg —— 列表严格等于「该来源的报告」
  // total=存档内报告数 / shown=按来源筛出的份数 / hidden=被筛掉的份数 /
  // untagged=不带 pkg 且仍会显示的份数。空白 pkg 与 null 一律当「不带」。
  function advisorPkgScope(reports, pkg) {
    var list = (reports || []).filter(function (r) { return r && typeof r === 'object'; });
    var total = list.length;
    if (!pkg) {
      return { mode: 'all', pkg: '', total: total, shown: total, hidden: 0, untagged: 0 };
    }
    var want = normPkg(pkg);
    var shown = 0, hidden = 0, untagged = 0, tagged = 0;
    list.forEach(function (r) {
      var raw = r.pkg == null ? '' : String(r.pkg);
      if (!raw.replace(/^\s+|\s+$/g, '')) { untagged += 1; shown += 1; return; }
      tagged += 1;
      if (normPkg(raw) === want) { shown += 1; } else { hidden += 1; }
    });
    var mode = tagged === 0 ? 'untagged' : (untagged > 0 ? 'mixed' : 'exact');
    return { mode: mode, pkg: want, total: total, shown: shown, hidden: hidden, untagged: untagged };
  }

  // 顾问列表的截断判定必须用「已加载条数」（ADVISORS.length），不能用过滤后的
  // 可见条数：来源筛选 / 搜索会合法地把列表变短，那不是截断。用可见条数比较时，
  // 存档 400 份、本页 100 份、筛选后剩 5 份，控制台会写「当前只显示最近 5 份
  // （存档共 400 份），更早的报告不在此列表里」—— 用户以为 395 份被截掉了，
  // 其实它们只是不匹配当前筛选。两个判定正交：
  //   truncated=true 存档比已加载多（页面确实少了一段列表）
  //   filtered=true  已加载的里面还有被筛选挡掉的（与截断无关）
  function advisorListState(loaded, total, shown) {
    var n = function (v) { var x = Number(v); return (isFinite(x) && x > 0) ? Math.floor(x) : 0; };
    var L = n(loaded), T = n(total), S = n(shown);
    if (!T || T < L) T = L;   // total 缺失 / 老服务端 / 脏值 → 当「没有更多」
    if (S > L) S = L;         // 可见数不可能超过已加载
    return { loaded: L, total: T, shown: S, truncated: T > L, filtered: S < L };
  }

  // 次数取值：脏输入（字符串 / 负数 / NaN / 小数）一律向下取整并夹到 >=0，
  // 别把 NaN 带进展示层（NaN 参与比较恒 false，会让「全库有 3 条」静默变成「没有」）。
  function _countOf(v) {
    var x = Number(v);
    return (isFinite(x) && x > 0) ? Math.floor(x) : 0;
  }

  // 从分布里取某个来源的全库条数。入参可能两种形状：
  //   [{pkg, count}]（服务端 /stats 的形状）或 {包名: 次数}（前端本地估算的形状）
  // 键名一律过 normPkg 再比：库里 pkg 为 NULL 与空串时各自成一组，
  // 归一后都算微信 —— 与统计卡 distPairs 的归并口径必须同一个，
  // 否则「微信全库 0 条」和「微信全库 3 条」会在同一屏里同时成立。
  // 返回 null = 拿不到分布（接口缺失 / 形状不认识 / 传入 null），调用方据此退回保守文案；
  // 返回 0 = 分布拿到了，全库确实没有这个来源。
  function pkgCount(byPkg, pkg) {
    if (byPkg == null) return null;
    var isArr = Object.prototype.toString.call(byPkg) === "[object Array]";
    if (!isArr && typeof byPkg !== "object") return null;
    var want = normPkg(pkg), total = 0;
    if (isArr) {
      byPkg.forEach(function (x) {
        if (!x || typeof x !== "object") return;
        if (normPkg(x.pkg) === want) total += _countOf(x.count);
      });
    } else {
      Object.keys(byPkg).forEach(function (k) {
        if (normPkg(k) === want) total += _countOf(byPkg[k]);
      });
    }
    return total;
  }

  // 「列表是空的」在来源筛选下有四种完全不同的成因，共用一句「该来源下暂无数据」
  // 会让用户以为这个 App 从来没来过消息：
  //   mode="loaded"     还有可见条目（调用方不该渲染空态）
  //   mode="none"       全库计数为 0 —— 这个来源确实一条都没有
  //   mode="elsewhere"  全库有 db 条，但都不在已加载的这几页里（更早的页 / 被脏行守卫跳过）
  //   mode="unknown"    拿不到全库分布（离线 / 老服务端 / 统计接口失败），不敢断言
  // loaded=已加载条数、shown=按来源筛出的条数、db=全库该来源条数（null=未知）。
  // shown 被夹到 <= loaded；db 与 shown 没有大小关系（跨页、skip 掉的脏行）——
  // 所以判定只能信「db === 0」，不能拿 db < shown 推任何东西。
  function appEmptyScope(pkg, loaded, shown, byPkg) {
    var L = _countOf(loaded), S = _countOf(shown);
    if (!pkg) return { pkg: "", loaded: L, shown: L, db: null, mode: "loaded" };
    if (S > L) S = L;
    var db = pkgCount(byPkg, pkg);
    var mode = (S > 0) ? "loaded" : (db === null ? "unknown" : (db > 0 ? "elsewhere" : "none"));
    return { pkg: normPkg(pkg), loaded: L, shown: S, db: db, mode: mode };
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
    codePointLength: codePointLength,
    clipCodePoints: clipCodePoints,
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
    queueDeviceSummary: queueDeviceSummary,
    advisorPkgScope: advisorPkgScope,
    advisorListState: advisorListState,
    pkgCount: pkgCount,
    appEmptyScope: appEmptyScope
  };
});
