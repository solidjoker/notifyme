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

  // ---------- 展示文案：码值 → 中文（唯一一份） ----------
  // 为什么必须集中：同一份 kind 在排队 Tab 的卡片里渲染成「分析」，统计 Tab 的
  // 排队卡却把 GROUP BY 出来的原码直接拼成「analysis 3 / sync 2」—— 同一屏两种
  // 语言。来源（source）更糟：前端只翻 android-db / android-notification 两档，
  // a11y-extract / pc-db / eval / unknown 全是英文码，用户看到不知道是什么。
  // 契约：
  //   sourceLabel 认不出就把原码原样回显（不吞信息），空值 / null 归 unknown 的中文；
  //   queueKindLabel / queueStateLabel 认不出返 null —— 让 UI 自己决定兜底措辞，
  //   规则层不替界面决定「未知该写其它还是未知」。
  var SOURCE_LABELS = {
    'android-notification': '通知',
    'android-db': '从数据库导入',
    'pc-db': '从电脑数据库导入',
    'a11y-extract': '无障碍直读',
    eval: '评测数据',
    unknown: '未知来源'
  };

  function sourceLabel(source) {
    var k = (source == null) ? '' : String(source).trim();
    if (!k) { return SOURCE_LABELS.unknown; }
    return Object.prototype.hasOwnProperty.call(SOURCE_LABELS, k) ? SOURCE_LABELS[k] : k;
  }

  // 消息上的来源徽标：只有「不是默认通知通道」的来源才挂，文案与样式都留在这里。
  // 为什么必须在规则层：旧写法在 UI 里硬写 `m.source === 'android-db'`，于是
  // a11y-extract（无障碍直读，时间戳还是估算值）的消息没有任何标记 —— 用户无法
  // 分辨「这条是通知抓的」还是「这条是从别处读出来的」。CSS 的 --badge-nt /
  // .badge.b-nt 早就定义了，一直没人用。
  var SOURCE_BADGES = {
    'android-db': { text: '库', cls: 'b-db' },
    'pc-db': { text: '库', cls: 'b-db' },
    'a11y-extract': { text: '读', cls: 'b-nt' }
  };

  function sourceBadge(source) {
    var k = (source == null) ? '' : String(source).trim();
    return Object.prototype.hasOwnProperty.call(SOURCE_BADGES, k) ? SOURCE_BADGES[k] : null;
  }

  function _labelOf(table, key) {
    var k = (key == null) ? '' : String(key);
    return Object.prototype.hasOwnProperty.call(table, k) ? table[k] : null;
  }

  function queueKindLabel(kind) {
    return _labelOf(QUEUE_KIND_LABELS, kind);
  }

  function queueStateLabel(state) {
    return _labelOf(QUEUE_STATE_LABELS, state);
  }

  // 分布文案拼接：pairs 是 [[键, 次数], ...]（pairList / distPairs 的输出）。
  // 0 / 负数 / 非数字的条目一律丢弃（与 mergePairsBy 的「丢零次数」同口径），
  // labelOf 回 null 时写「未知」—— 不许把 null 拼成字符串给用户看。
  // 标签函数可以按名字传：控制台的内联脚本与 node 单测都走 JSON 边界，函数没法过
  // JSON.stringify。`pairText(pairs, 'source')` 里的 'source' 在这里解析成 sourceLabel，
  // 认不出的名字一律当「不翻译」（拼原码），不抛。
  var PAIR_LABELERS = {
    source: sourceLabel,
    kind: queueKindLabel,
    state: queueStateLabel
  };

  function _labelerOf(spec) {
    if (typeof spec === 'function') { return spec; }
    var k = (spec == null) ? '' : String(spec);
    return Object.prototype.hasOwnProperty.call(PAIR_LABELERS, k) ? PAIR_LABELERS[k] : null;
  }

  function pairText(pairs, labeler) {
    if (!pairs || !pairs.length) { return ''; }
    var fn = _labelerOf(labeler);
    var parts = [];
    for (var i = 0; i < pairs.length; i++) {
      var pair = pairs[i] || [];
      var n = Number(pair[1]);
      if (!(isFinite(n) && n > 0)) { continue; }
      var key = (pair[0] == null) ? '' : String(pair[0]);
      var label = fn ? fn(key) : key;
      if (label == null || label === '') { label = '未知'; }
      parts.push(label + ' ' + Math.floor(n));
    }
    return parts.join(' / ');
  }

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

  // 统计 tab 的「最近分析结果」只画前若干条，标题里的数字却是全部已加载条数。
  // 翻过几页之后，「最近分析结果（200）」下面只有 30 张卡，中间 170 条凭空消失，
  // 页面里也没有任何一句解释 —— 与顾问列表的截断是同一类问题（说了总数、只画了
  // 一部分）。显示上限本身是刻意的（一屏 200 张卡既卡又没人读），所以不改上限，
  // 改成「说清楚少了几条 + 给一个能真把它们画出来的开关」：
  //   capped=true    已加载条数超过上限（与展开与否无关，用来决定是否给开关）
  //   expanded=true  用户点了「显示全部」，此时 hidden=0、shown=loaded
  // 上限由 UI 传入（规则层不猜前端布局），脏值 / 0 / 负数回落到 DEFAULT_STATS_LIST_CAP。
  var DEFAULT_STATS_LIST_CAP = 30;

  function statsListState(loaded, cap, expanded) {
    var L = _countOf(loaded);
    var C = _countOf(cap) || DEFAULT_STATS_LIST_CAP;
    var open = !!expanded;
    var S = open ? L : (L < C ? L : C);            // 展开后画全部，否则画前 C 条
    return { loaded: L, shown: S, cap: C, hidden: L - S, capped: L > C, expanded: open };
  }

  // Tab 角标的数字口径：能说「全库」的时候必须说全库，只有拿不到真值 / 用户正在
  // 客户端筛选时，才退回「已加载页内的命中数」—— 那时后者是唯一真实的数。
  // 旧写法一律数已加载数组：待办 Tab 把「全库 40 条待办」写成「15」，而同一屏的
  // 概览条（读 /stats 的 analyses.todo）写着 40，两个数字当场打架。
  // loaded=已加载页内条数；total=服务端全库总数（拿不到给 null）；filtered=是否有
  // 来源筛选 / 搜索（客户端过滤只作用于已加载页，此时全库数不能代表列表内容）。
  function badgeState(loaded, total, filtered) {
    var L = _countOf(loaded);
    // 全库数只接受「认得出的非负整数」：null / 负数 / NaN / 非数字一律当「拿不到」，
    // 不能折成 0 —— 那会把「接口没给数」渲染成「全库一条都没有」。
    var T = null;
    if (total != null) {
      var x = Number(total);
      if (isFinite(x) && x >= 0) { T = Math.floor(x); }
    }
    if (filtered || T === null) { return { value: L, scope: 'loaded' }; }
    return { value: T, scope: 'library' };
  }

  // 顶部同步状态行报的是「同步了多少」，两个计数各自有口径：/stats 给得出全库真值
  // 就说全库，拿不到（老服务端 / analyses 段缺失 / 接口失败）或用户正在按来源筛选
  // 时，退回「已加载页」的数并显式标注。
  // 旧写法只看 messages.total 就决定整行口径：① 同一句里混写「已加载消息 5 · 分析 40」；
  // ② analyses.total 缺失时直接拼出「分析 null」（字符串 null 渲染到状态行）；
  // ③ 两侧各自能拿到全库数、只有一侧退化为已加载时，用户无法判断哪个数是哪一档。
  // 所以两个计数分别定档（复用 badgeState 的「拿不到不许折 0」语义），并回报
  // mixed —— 两侧口径不同时界面必须把两侧都标出来。
  function syncCounters(msgLoaded, anaLoaded, msgTotal, anaTotal, filtered) {
    var m = badgeState(msgLoaded, msgTotal, filtered);
    var a = badgeState(anaLoaded, anaTotal, filtered);
    return { msg: m, ana: a, mixed: m.scope !== a.scope };
  }
  // 排队 tab 的空列表有三种成因，设备筛选时最容易误判：
  // 「这台设备这一页里没有」与「这台设备早就没任务了」是两件事，而排队列表只取
  // 最近 500 条（跨设备共享一个窗口，按 updated_at 倒序）—— 一台安静设备的历史
  // 任务会整个掉出窗口，此时设备 chip 还写着「3 活跃」，空态却说「手机端没有排队任务」。
  // 设备（以及每个状态）的全库条数只在 devices 清单与 summary 里（storage.queue_devices
  // / queue_summary，都没有 limit），所以判定必须读它们，绝不能数已加载的 QUEUE。
  // scope: "device" 按设备筛 / "state" 只按状态 / "all" 什么都没筛
  // mode : "loaded"（还有可见条目，调用方不该渲染空态）/ "none"（全库确实没有）
  //        / "elsewhere"（全库有 db 条，但不在已加载的这几百条里）/ "unknown"（拿不到全库口径）
  function _deviceEntry(devices, deviceId) {
    if (Object.prototype.toString.call(devices) !== "[object Array]") return null;
    var want = String(deviceId == null ? "" : deviceId);
    for (var i = 0; i < devices.length; i++) {
      var d = devices[i];
      if (!d || typeof d !== "object") continue;
      if (String(d.device_id == null ? "" : d.device_id) === want) return d;
    }
    return null;
  }

  // 入参 design：device / state 是当前的筛选值（空串 = 不筛），loaded / shown 是
  // 「已加载条数」与「同一套过滤代码筛出的条数」，devices / summary 是 GET /queue
  // 的全库聚合。summary 缺失说明排队接口这次没回来 —— 那是「不知道」，不是「没有」。
  // stateKnown=false 表示服务端没给「这台设备的某个状态」的计数，UI 不能把设备总数
  // 说成「该状态的条数」（设备 × 状态没有任何已知口径，不许现算）。
  function queueEmptyScope(device, state, loaded, shown, devices, summary) {
    var dev = String(device == null ? "" : device).trim();
    var st = String(state == null ? "" : state).trim();
    var L = _countOf(loaded), S = _countOf(shown);
    if (S > L) S = L;
    var scope = dev ? "device" : (st ? "state" : "all");
    var out = { scope: scope, mode: "unknown", db: null, device: dev, state: st,
      loaded: L, shown: S, stateKnown: false };
    if (S > 0) { out.mode = "loaded"; return out; }
    if (!summary || typeof summary !== "object") return out;
    if (scope === "device") {
      if (Object.prototype.toString.call(devices) !== "[object Array]") return out;
      var d = _deviceEntry(devices, dev);
      if (!d) {
        // 设备清单是按 device_id 分组的全库聚合：清单里有别的设备、偏偏没有这台，
        // 说明它的行已经被清掉了（停报超 7 天，或端上清空了队列）。
        out.mode = "none";
        out.db = 0;
        return out;
      }
      out.db = _countOf(d.total);
      out.stateKnown = !st;
      out.mode = out.db > 0 ? "elsewhere" : "none";
      return out;
    }
    var key = st || "total";
    var db = summary[key];
    if (typeof db !== "number") return out;
    out.db = _countOf(db);
    out.stateKnown = true;
    out.mode = out.db > 0 ? "elsewhere" : "none";
    return out;
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
    appEmptyScope: appEmptyScope,
    DEFAULT_STATS_LIST_CAP: DEFAULT_STATS_LIST_CAP,
    statsListState: statsListState,
    badgeState: badgeState,
    syncCounters: syncCounters,
    SOURCE_LABELS: SOURCE_LABELS,
    sourceLabel: sourceLabel,
    queueKindLabel: queueKindLabel,
    queueStateLabel: queueStateLabel,
    pairText: pairText,
    PAIR_LABELERS: PAIR_LABELERS,
    SOURCE_BADGES: SOURCE_BADGES,
    sourceBadge: sourceBadge,
    queueEmptyScope: queueEmptyScope
  };
});
