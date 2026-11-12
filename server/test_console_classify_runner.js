/* node 侧测试 harness：把「函数名 + 参数」变成一次真实调用。
 *
 * 为什么要它：server/console_classify.js 是控制台归类规则的单一事实源
 * （浏览器内联 + node 直接 require 两端共用）。pytest 不方便直接跑浏览器端代码，
 * 所以这里开一个薄壳：stdin 收一段 JSON -> 调真实纯函数 -> stdout 回一段 JSON。
 *
 * 用法：
 *   echo '{"fn":"senderKey","args":[" 张三: 你好 "]}' | node test_console_classify_runner.js
 *
 * 协议（stdin 一段 JSON）：
 *   {"fn": "<函数名>", "args": [...]}
 *       调用 console_classify.js 里的同名纯函数，args 原样透传。
 *   {"fn": "__get__", "args": ["VERSION"]}
 *       读常量（VERSION / PKG_WX / SEP / ADVISOR_TYPE_ORDER / IMPORTANCE_ORDER /
 *       IMPORTANCE_LABELS）。常量不在模块导出里的话，靠这个名字取。
 *
 * stdout（只输出一段 JSON，方便对端 json.loads）：
 *   成功            -> {"ok": true, "out": <返回值>}        退出码 0
 *   未知函数名/常量 -> {"error": "unknown_fn"}               退出码 2
 *   stdin 不是 JSON -> {"error": "bad_json"}                退出码 2
 *
 * 约束：ES5 语法（与 console_classify.js 保持一致）、只用 Node 内置模块。
 */
'use strict';

var fs = require('fs');
var api = require('./console_classify.js');

function has(obj, key) {
  return Object.prototype.hasOwnProperty.call(obj, key);
}

/* 输出一段 JSON 并设置退出码。
 * 用 process.exitCode 而不是 process.exit()：管道上的 stdout 写入是异步的，
 * process.exit() 可能把还没写出去的字节截断，中文 JSON 会被切一半。
 */
function emit(obj, code) {
  // 显式按 UTF-8 写字节：Windows 管道编码不可控，中文断言必须稳。
  process.stdout.write(Buffer.from(JSON.stringify(obj), 'utf8'));
  process.exitCode = code;
}

function main() {
  var raw = '';
  try {
    raw = fs.readFileSync(0, 'utf8');
  } catch (e) {
    emit({ error: 'no_stdin' }, 2);
    return;
  }

  var payload;
  try {
    payload = JSON.parse(raw);
  } catch (e) {
    emit({ error: 'bad_json' }, 2);
    return;
  }
  if (!payload || typeof payload !== 'object') {
    emit({ error: 'bad_json' }, 2);
    return;
  }

  var fn = payload.fn;
  var args = Object.prototype.toString.call(payload.args) === '[object Array]' ? payload.args : [];
  var out;

  if (fn === '__get__') {
    // 常量读取：只认模块自己导出的键
    var name = String(args[0] || '');
    if (!has(api, name)) {
      emit({ error: 'unknown_fn' }, 2);
      return;
    }
    out = api[name];
  } else {
    if (typeof fn !== 'string' || !has(api, fn) || typeof api[fn] !== 'function') {
      emit({ error: 'unknown_fn' }, 2);
      return;
    }
    out = api[fn].apply(null, args);
  }

  emit({ ok: true, out: out === undefined ? null : out }, 0);
}

main();
