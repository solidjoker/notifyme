# notifyme 前端与后端重新评审报告（2026-11）

> **评审对象**：`server/app.py`（1780 行，含内嵌 PWA 控制台前端 + SQLite + JSONL 存档 + 顾问存档）、`server/android_db_extract.py`（556 行）、`server/pc_db_extract.py`（431 行）、`app/src/main/java/com/notifyme/android/*`（Android 客户端，与服务端构成契约）
> **基线**：`revise.md`（基线 commit `62c2b4c` / 2026-10-07）与 `progress.md`（M24 / 2026-10-25）。当前 HEAD `edfee57`，工作区干净。
> **评审方式**：lead 实读全部相关源码 + **运行时探针实测**（对 `server/app.py` 做进程内 Flask test client 探针，脚本见 `.tmp/review_probe.py`）+ 三路并行只读审查（backend-audit / frontend-audit / contract-audit）
> **分级口径**：P0 = 安全越权 / 静默丢数据；P1 = 明确的正确性缺陷或永久性卡死；P2 = 体验 / 性能 / 可维护性；P3 = 细节打磨
> **一句话结论**：服务端在 `revise.md` 之后的加固（207 语义、游标分页、参数化 SQL、令牌鉴权）方向正确，但**端上没接住 207 语义**（永久丢消息 + 永久丢分析）、**前端 PWA 的 SW 缓存把带鉴权的落地页写进 Cache**（无令牌可读最近 100 条消息正文），这两条是必须立刻修的阻断项；其余为一致性与体验债。

---

## 0. 缺陷统计

| 域 | P0 | P1 | P2 | P3 | 备注 |
|---|---|---|---|---|---|
| 后端（`server/app.py`） | 3 | 8 | 20 | 11 | 数值边界、双写顺序、空令牌/WSGI 三条 P0；含 SW/落地页鉴权绕过（前端代码在同文件内） |
| 前端（CONSOLE_HTML / manifest / SW / index） | 1 | 10 | 22 | 20 | 含存储型 XSS ×2、刷新失焦、分页未接 |
| 提取脚本（`*_db_extract.py`） | 0 | 2 | 5 | 4 | 全表去重 + OOM、明文落盘、秒级去重口径 |
| 端云契约（Android ↔ 服务端） | 2 | 5 | 8 | 6 | 207 死分支 ×2、隐私门控、413 死循环、脱敏证据丢失 |
| 文档 / 测试 | 0 | 3 | 3 | 1 | README 与实现不符、启动脚本无鉴权、后端测试仅 7 例 |

合计 **P0 × 6 / P1 × 24 / P2 × 57 / P3 × 42**（按报告正文计数；同一根因在多个域出现时只计一次）。

### 必改 Top 13（按「修完能解掉最多下游症状」排序）

| # | 改动 | 位置 | 解掉的症状 |
|---|---|---|---|
| 1 | `when` 分支把 207 提到 `isSuccessful` 之前 | `app/src/main/java/com/notifyme/android/SyncWorker.kt:181`、`:293` | 被拒消息 / 被拒分析**永久丢失**、水位误推进 |
| 2 | SW 导航改「网络优先 + 只缓存 `/console` 壳」，落地页 `no-store` | `server/app.py:1590-1604`、`:1653-1753` | 无令牌可读最近 100 条消息正文（鉴权绕过） |
| 3 | TOKEN 为空时拒绝启动；WSGI 也走同一入口（`create_app` + 幂等 `init_db`）；启动 bat 缺令牌直接退出 | `app.py:228-229`、`:1760-1779`、`server/启动服务.bat:7-12` | 空令牌 + WSGI = 公网裸奔；WSGI 部署全部接口 500 |
| 4 | 数值字段边界校验（timestamp / analyzed_at / window_end / message_count）+ 捕获 ValueError / OSError | `app.py:316`、`:390`、`:604-605`、`:611`、`:1696` | 一条越界数据 500、落地页整页挂掉、存档半写 |
| 5 | `/weixin` 改成「先入库 commit 再写存档」（或每条独立事务） | `app.py:280-332` | 存档与库永久不一致、重复上报让存档翻倍 |
| 6 | 顾问渲染补 `esc()`，服务端收敛 `case_count/period_days` 类型 | `app.py:1494-1495`、`:763-780` | 存储型 XSS → localStorage 明文令牌被窃 |
| 7 | `/analysis POST` 数值字段逐条隔离（不合格计入 invalid + rejected，返 207） | `app.py:604-605`、`:622` | 一条脏数据让**整条分析上报链路永久 500 卡死** |
| 8 | `api()` 判 `r.ok`，错误可见；30s 刷新不重建鉴权框 | `app.py:1076-1080`、`:1062-1067`、`:1519` | 500 被渲染成「已同步 · 消息 0」、正在输入的令牌被清空 |
| 9 | `/analysis GET` 返回归一后的 `importance/need_action`，并对脏行容错 | `app.py:687-703`、`:693/695/700` | 一行脏 JSON 让**全部历史分析不可回看**；前端 importance 口径二义 |
| 10 | 顾问存档损坏时**拒绝写入**而不是静默覆盖 | `app.py:714-723`、`:782-783` | 存档损坏 → 一次 POST 抹掉全部历史报告 |
| 11 | 队列分批上报（≤N 条/批）并单独处理 413 | `SyncWorker.kt:149-166`、`:215-219` | 超过 8 MiB → 413 → 无限重试，队列永不消化 |
| 12 | 提取每 500 条 commit + 共享写锁 + busy_timeout 30s + 捕获 OperationalError 返 503 | `android_db_extract.py:434-473`、`app.py:97-98` | 提取期间上报被锁死 5s 后 500 |
| 13 | `/messages` 的 `next_before` 真正接进控制台（加载更多） | `server/app.py:1155`（前端） | 控制台永远只能看到最近 1000 条历史 |

---

## 1. 运行时探针（可复现证据）

脚本：`.tmp/review_probe.py`（把 `server/app.py` 复制到临时目录后 `import`，`DATA_DIR` 随之隔离，**不触碰仓库 server/data**）。
运行：`python -X utf8 .tmp/review_probe.py > .tmp/probe_out.txt 2> .tmp/probe_err.txt`

| 探针 | 实测输出 | 对应结论 |
|---|---|---|
| 只 import 不调 `init_db()` → `GET /messages` | **500**，stderr `sqlite3.OperationalError: unable to open database file` | E8（P1） |
| 无令牌 / 错令牌 → `GET /messages` | 401 / 401 | 鉴权生效 |
| `GET /messages?token=probe-token` | **200** | B14 令牌可走 URL（进访问日志 / Referer） |
| `POST /weixin` 混合批（1 合法 + 1 缺字段） | **207** `{"received":1,"invalid":1,"rejected":[1],"ok":false}` | 服务端 207 语义正确；与 SyncWorker 分支顺序冲突 → P0-2 |
| 同一条再发一次 | 200 `{"duplicated":1,"received":0}` | — |
| 上一步后统计 | **JSONL 2 行 vs SQLite 1 行** | B3（P1）两份真相漂移 |
| `POST /analysis` `window_end="abc"` | **500**，stderr `ValueError: invalid literal for int() with base 10: 'abc'` | B1 / C3（P1）毒丸条目 |
| analyses 表插一行脏 s1 → `GET /analysis` | **500** `json.decoder.JSONDecodeError` @ `app.py:693` | B12（P2）一行脏数据毒死全部回看 |
| 正常上报后查 DB vs GET | DB `importance='high', need_action=1`；GET 返回键里**没有** importance/need_action，`s1.importance=7`（原始数值） | E13（P2）前端二义根因 |
| `analyzed_at` 上报 1700000000000 | 库里 `"2023-11-15 06:13:20"`（服务器本地时区） | C9 / F9 时间口径 |
| 顾问存档 2 条 → 写坏文件 → POST 1 条 | 响应 `ok:true, inserted:1`；**文件只剩 ["r3"]** | B2（P1）静默清空 |
| 3 条消息 `limit=3` | `next_before="1700000000000_2"`；下一页 `count=0` | B6（P2）多一次空请求 |
| 1200 条 `limit=5000` / `limit=1000` | 1000 / 1000；第二页 200 | F1（P1）服务端支持翻页，前端不翻 |
| 无令牌 `GET /console` / `/console/manifest.json` / `/sw.js` | 401 / **200** / **200** | FP0-2 / B15 |
| 单条 text 1 MB | **200 接收** | B4（P2）字段无长度上限 |
| `GET /analysis?date=1999-01-01` | 返回全部（count=1，date 被忽略） | B5 / B7（P2） |
| 服务器时区 | `day_start(2026-11-10)=1794240000000` = 2026-11-09T16:00Z，tz=中国标准时间 | B5（P2）服务端本地日界 |
| `POST /admin/extract` → 查状态 | `started:true`，1.5s 后 `running:false`，`last.error="No module named 'android_db_extract'"` | E18（新增 P2）WSGI 部署下提取功能静默失效 |

---

## 2. P0 缺陷（必须立刻修）

### P0-1 SW 导航缓存越权：无令牌可读到最近 100 条消息正文 【前端 · lead 读码 + 队友交叉验证】

**位置**：`server/app.py:1590-1604`（SW fetch 处理）、`:1653-1753`（`index()` 落地页，内联最近 100 条消息正文）、`:1627-1646`（manifest / icons / sw.js 不鉴权）

```javascript
// 1590-1604
// 页面导航与静态骨架：cache-first，网络成功后回填
if (e.request.mode === 'navigate' || url.pathname.indexOf('/console') >= 0) {
  var shellKey = url.pathname;            // 去 query 的实际路径作缓存 key
  e.respondWith(
    caches.open(CACHE).then(function (cache) {
      return cache.match(shellKey, { ignoreSearch: true }).then(function (hit) {
        var net = fetch(e.request).then(function (resp) {
          if (resp.ok) cache.put(shellKey, resp.clone());   // ← 落地页正文进缓存
          return resp;
        }).catch(function () { return hit; });
        return hit || net;
      });
    })
  );
  return;
}
```

**机制**：SW 拦截**所有** `mode === 'navigate'` 的导航请求，缓存 key 只取 `url.pathname`（`ignoreSearch: true`，token 不参与 key）。`index()`（1653-1758）虽然自己过 `check_token()`，但返回的 HTML 内联了**最近 100 条消息的完整正文**。于是：

1. 用户用 `/?token=xxx` 打开过一次落地页 → SW 把 `/` 的响应体（100 条明文消息）写入 Cache Storage；
2. 此后**任何不带令牌的导航请求 `/`** 都会 `cache.match('/')` 命中 → **服务端 `check_token()` 根本不执行** → 直接返回缓存的明文消息页；
3. 同一浏览器 profile 下换人 / 换设备访问（或把缓存目录拷走）即可读到消息正文。

**影响**：绕过令牌鉴权读取明文聊天记录，与 `app.py:219-234` 的鉴权模型直接冲突。探针侧证据：`GET /` 无令牌 → 401（服务端本身拦得住，泄露完全由 SW 缓存造成）。

**修复**：
- SW 只缓存 `/console` 壳（且壳内不含任何用户数据），导航请求改"网络优先、失败回缓存"；
- `index()` 的响应加 `Cache-Control: no-store`，并在 SW 里显式不缓存 `/`；
- 落地页改为"只提示 + 跳控制台"，不再内联消息正文（正文本来就有 `/messages` API）。

### P0-2 消息通道：207 分支是死代码，被服务端拒绝的消息被端上永久删除 【契约 · lead 读码 + 队友交叉验证 + 探针佐证】

**位置**：`app/src/main/java/com/notifyme/android/SyncWorker.kt:179-206`、`:118-135`（`parseRejected`）、服务端 `server/app.py:334-344`

```kotlin
// SyncWorker.kt:179-205
client.newCall(request).execute().use { response ->
    when {
        response.isSuccessful -> {                                   // ← 207 也命中这里
            PendingQueue.removeSent(applicationContext, pending.map { it.id }.toSet())
            okParts += "消息 ${pending.size} 条"
            Result.success()
        }
        response.code == 207 -> {                                   // ← 死代码，永远进不来
            // 服务端部分条目被拒（ok=false + rejected 下标）：只剔除被接受/重复的，
            // 被拒条目留在队列里下轮重试，绝不整体当成功删掉。
            val rejected = parseRejected(response.body?.string())
            ...
        }
```

OkHttp 的 `Response.isSuccessful()` 定义为 `code in 200..299`，**207 属于成功区间**（探针实测服务端确实返回 207，见 §1）。于是：

- 服务端 `/weixin` 在 `invalid > 0` 时返回 207 + `rejected` 下标（`app.py:336-344`）；
- 客户端先命中 `isSuccessful` 分支 → `PendingQueue.removeSent(全部 pending.id)` → **整批（含被拒条目）从待发队列删除**；
- 结果：被拒的那条消息**服务端没入库、端上也没了** → 永久丢失；
- `parseRejected`（118-135）与 207 分支（188-205）整套逻辑从未被执行过，注释里写的"绝不整体当成功删掉"与实际行为完全相反。

**触发条件**：服务端 `_validate_item`（`app.py:396-411`）要求 `sender/text/timestamp/conversation/is_group` 五字段齐全且类型正确。官方客户端 `SyncWorker.kt:158-166` 每次都写全五字段（`put("is_group", message.isGroup)`），所以**当前版本不会触发**；但任何一次协议漂移（新增来源 App 的消息结构不同、客户端裁剪字段、第三方调用）都会立刻造成批量静默丢消息——这是"一次改动毁掉全部历史消息"的典型脆弱点。

**修复**：把 207 分支前置到 `isSuccessful` 之前，或把成功判定改为 `response.code in 200..206`；并在 207 分支里断言响应体 `ok == false`。

### P0-3 分析通道：同一个 207 死分支，导致分析结果永久丢失且水位误推进 【契约 · lead 读码 + 队友交叉验证】

**位置**：`SyncWorker.kt:290-313`、服务端 `server/app.py:628-632`

```kotlin
// SyncWorker.kt:292-300
response.isSuccessful -> {                    // ← 207 也命中
    AnalysisStore.markSynced(applicationContext, records.maxOf { it.analyzedAt })
    okParts += "分析 ${records.size} 个"
}
```

服务端 `/analysis` 在 `invalid > 0` 时返回 **207 + `ok:false` + `rejected`**（`app.py:628-632`），而客户端把 207 当成功 →

- 被拒的 `case_id` 服务端没入库；
- 端上 `markSynced(records.maxOf { analyzedAt })` 把**整批水位推到最大 analyzedAt** → 被拒记录被标记"已上报"，下轮不再发送 → **分析结果永久丢失**；
- 该通道甚至没有 207 分支（304 之后直接 404 / else），服务端在 `app.py:628` 精心设计的 `rejected` 数组在分析通道上**完全没有接收方**。

**修复**：分析通道同样前置 207 分支；只推进"连续被接受前缀"的水位，被拒子集单独重发；服务端补 `/analysis` 的 `rejected` 语义文档。

### P0-4 数值字段无边界校验：越界时间戳让接口 500 且存档半写 【后端 · 第三路复核 + lead 复核源码】

**位置**：`server/app.py:316`、`:390`、`:604-605`、`:611`、`:478`、`:1696`

- `app.py:316` `int(item[\"timestamp\"])` 未做范围校验；
- `app.py:389-390` `datetime.fromtimestamp(ms / 1000.0)` 对越界毫秒抛 OSError / OverflowError，未捕获；
- `app.py:1695-1696` 落地页同名调用同样未捕获 → **一行越界数据打挂整页**（lead 已复核源码）；
- `app.py:477-484` `before_ms = int(parts[0])` 越界值虽不致命，但会让游标语义错乱。

**实测**（第三路探针，lead 已复核对应源码）：`POST /analysis window_end=\"abc\"` → 500（`app.py:604`）；`POST /analysis analyzed_at=10**18` → 500（`app.py:611` → `:390` OSError）；`POST /weixin timestamp=1e999` → 500（`app.py:316`）且 jsonl=1 / db=0；`GET /messages?before=9*22` → 500；`GET /` 一行 ts=10**18 → 整页 500。

**影响**：单条脏数据即可 500；与 P0-5 的「先写存档后入库」叠加时产生半写存档。**修复**：`_validate_item` 增加 `0 < ts <= 4102444800000` 与长度上限；`/analysis` 数值字段 try/except 后计入 `invalid`+`rejected` 返回 207；两处 `fromtimestamp` 包 try/except 回退 `str(ts)`；`before` 游标越界返回 400。

### P0-5 `/weixin` 双写顺序错误：存档先写、库后写、整批一次提交 【后端 · 第三路复核 + lead 复核源码】

**位置**：`server/app.py:280-332`（`:293` 写存档 → `:306` INSERT → `:328-329` flush + 整批 commit → `:331` finally）。lead 已复核源码：存档行在 INSERT 之前落盘，而 `conn.commit()` 在整批循环结束后才执行。

**实测**：持写事务场景下 jsonl=1 / db=0；重复上报时 jsonl=2 / db=1（半写与不一致同时发生）。**影响**：批次中途失败 → 存档永久多行、库永久缺行；重复上报让存档翻倍而库去重 → 两份「真相」持续漂移（与 §4.1 E1 叠加）。**修复**：先写库并 commit 再写存档；或每条独立事务 + 存档用同一去重键幂等。

### P0-6 空令牌即完全不鉴权，且绑定与初始化只写在 `__main__` 分支 【后端 · 第三路复核 + lead 复核源码】

**位置**：`server/app.py:228-229`、`:1760-1779`、`server/启动服务.bat:7-12`、`server/README.md` §2。lead 已复核源码：

- `check_token()` 第 228-229 行 `if not TOKEN: return None` → 未配置令牌时**所有接口完全无鉴权**；
- `启动服务.bat` 第 7-12 行：找不到 `secrets.local.bat` 时只 `echo` 警告，然后 `set WEIXIN_TOKEN=`（空值）继续启动 → **双击 bat 就是无鉴权服务**；
- `init_db()` 与 host 决策（`0.0.0.0` vs `127.0.0.1`）都关在 `if __name__ == \"__main__\"` 里 → 任何 WSGI 方式启动（waitress-serve / gunicorn / import）都不初始化表，且绑定地址不受控（waitress-serve 默认绑 0.0.0.0）→ **空令牌 + WSGI = 公网裸奔**；
- README §2 写「默认监听 0.0.0.0:8000」，与 `app.py:1768`「无令牌只听 127.0.0.1」矛盾（与 D1 同源，但这里还牵连默认暴露面）。

**修复**：`create_app()` 工厂 + 幂等 `init_db`；唯一 serve 入口承载 token / host 决策；TOKEN 为空默认拒绝启动（除非显式 `WEIXIN_ALLOW_INSECURE_BIND=1`）；`启动服务.bat` 缺令牌 `exit /b 1`；README 改口。

---

## 3. P1 缺陷

### 3.1 后端

| 编号 | 问题 | 位置 | 影响 | 修复 |
|---|---|---|---|---|
| B1 | `/analysis POST` 的 `int(item.get("window_end") or 0)` / `message_count` 无类型校验，`except` 只捕获 `sqlite3.IntegrityError` | `app.py:604-605`、`:622` | 一条脏数据 → `ValueError` → 500 → 客户端 else 分支一律 `retry`（`SyncWorker.kt:309-312`）→ **队列永久不推进，后续所有分析被同一条毒丸堵死** | 先做 `isinstance(x, int)` 校验，不合格计入 `invalid`+`rejected`；`except Exception` 兜底并逐条隔离 |
| B2 | 顾问存档损坏被静默当空，read-modify-write 覆盖全文件 | `app.py:714-723`（load）、`:761-783`（save） | 探针实测：存档 2 条 → 写坏 → POST 1 条 → **文件只剩新那一条，响应仍是 `ok:true`** → 历史报告被无声抹掉 | 载入失败时拒绝写入并返回 5xx；或把存档改为 SQLite / 追加式 JSONL |
| B3 | `/weixin` 先写 JSONL 存档（293）后入库（305-327），命中唯一索引时 JSONL 不回滚 | `app.py:293`、`:325-327` | 探针实测：重发同一条 → **JSONL 2 行 vs SQLite 1 行**；两份「真相」长期漂移 | **已升级为 P0-5**（整批一次 commit 让半写成为常态） |
| B4 | `_validate_item` 只校验类型，不校验长度 / 范围 | `app.py:396-411`、`:47` | 探针实测：单条 text 1 MB 直接入库成功；`timestamp` 负数 / 未来时间也可入库 | 加长度上限（sender 64 / text 4096 / conversation 128）与 timestamp 范围校验 |
| E8 | `init_db()` 只在 `if __name__ == "__main__"` 里调用 | `app.py:1761` | 探针实测：只 import 不初始化 → `GET /messages` **500** `unable to open database file`。用 waitress / gunicorn / uwsgi 部署 → **所有接口 500** | **已升级为 P0-6**（WSGI 绑定不受控 + 空令牌裸奔） |
| B14 | 令牌支持 `?token=` query | `app.py:232`（探针：`?token=…` → 200） | 令牌进反向代理访问日志 / 浏览器历史 / Referer | 控制台改用 `Authorization` 头或 fragment；query 仅保留兼容并打 deprecation 警告 |
| FP0-2 | `console_page()` 强制 `check_token()`，而 `manifest.start_url="./"` | `app.py:1616-1624`、`:1549` | 探针实测：无令牌 `GET /console` → **401**。PWA 从主屏启动无法带 `X-Token` 头 → **启动即一屏 JSON 错误**；现在"能用"纯粹靠 P0-1 的 SW 缓存兜着，SW 一失效即不可用 | 外壳（不含任何用户数据）免鉴权下发，令牌走 localStorage / fragment；数据接口继续鉴权 |
| N1 | 提取在 Flask 进程内持长写事务（整批一次 commit）+ busy_timeout 仅 5s | `android_db_extract.py:434-473` + `app.py:97-98`、`:877` | 实测：持写事务时 `POST /weixin` 阻塞 5.05s 后 500 `database is locked`（@ `:306`）且 jsonl 已写 | 提取每 500 条 commit；共享进程内写锁；busy_timeout 30s；捕获 OperationalError 返 503 + Retry-After |
| N2 | 游标谓词 `(timestamp < ? OR (timestamp = ? AND rowid < ?))` 使索引失效 | `app.py:489-490` | EXPLAIN 实测：现写法 `SCAN messages USING INDEX idx_messages_ts`；改行值比较 `(timestamp,rowid) < (?,?)` 为 `SEARCH messages USING INDEX idx_messages_ts (timestamp<?)` | 换行值比较 + 新建 `idx_messages_ts_rid(timestamp,rowid)` |
| N3 | `_run_extract_bg` 主体无 try/finally | `app.py:827-850`、`:873-879` | 异常（如 MemoryError / SystemExit）后 `_extract_status['running']` 永久 True → `POST /admin/extract` 恒 409，只能重启 | 整体 try/except BaseException + finally 复位（原 B10，已升级 P1） |
| N4 | 提取把 `messages` 全表读进 Python set（聊天全文常驻内存），且提取跑在 Flask 主进程线程 | `android_db_extract.py:441-444`、`pc_db_extract.py:368-371`、`app.py:877` | 十万级消息即 OOM 拖垮 Web 服务（与 E2 同源，已升级 P1） | 只取窗口内键，或 `INSERT ... WHERE NOT EXISTS` 反连接 / 临时表 |

### 3.2 前端

| 编号 | 问题 | 位置 | 影响 | 修复 |
|---|---|---|---|---|
| FP1-a | `api()` 只判 401，不判 `r.ok` | `app.py:1076-1080` | 服务端 500 时 `r.json()` 抛异常 → 被当成"没有数据" → 页面显示"已同步 · 消息 0"，**故障被伪装成成功** | `if (!r.ok) throw new Error('HTTP ' + r.status)`，错误态显性呈现 |
| FP1-b | `showAuth()` 整体替换 `#main.innerHTML`，每 30s 触发一次 | `app.py:1062-1067` + `:1519` | 401 时用户正在输入的令牌被清空，且没有任何"令牌错误"提示 | 鉴权框只创建一次；401 时在原位提示，不销毁输入 |
| FP1-c | 30s 自动刷新 `render()` 整块 `innerHTML` 重绘 | `app.py:1220-1227` | 搜索框失焦、光标跳到末尾；正在看的内容被"抽走" | 只重绘数据区，不动输入框；或做增量 diff |
| FP1-d | 顾问字段未转义 | `app.py:1494-1495`：`(r.case_count or 0)`、`r.period_days` | **存储型 XSS**：服务端 `/advisor`（`app.py:763-780`）只校验 `report_id` 与 `suggestions` 是对象，`case_count/period_days/summary/model_used` 原样落盘 → 可注入 `<img src=x onerror=…>` → 在控制台执行 → **偷走 localStorage 里的明文令牌** | 两处补 `esc()`；服务端收敛字段类型（数字转 int、字符串截长） |
| FP1-e | `APP_FILTER` 只在会话 / 待办生效，统计与顾问不生效；待办页又没有过滤 UI | `app.py:1114`、`:1233-1238`、`:1401-1405`、`:1433-1466`、`:1478-1514` | 切到微信后看统计仍是全量 → **口径不一致导致误读**；待办被隐式过滤却无清除入口 | 过滤做成全局可见可清除的状态条，四个 tab 共用同一口径 |
| FP1-f | 前端从不使用 `next_before` | `app.py:1155`（`api('messages?limit=1000')`） | 探针实测：服务端 1200 条时第二页 200 条可达，但控制台永远只有最近 1000 条 → **历史消息不可达**，统计页也自认"消息总数（最近 1000 条内）" | 接入游标翻页 + "加载更多 / 没有更多"显式状态 |
| FP1-g | 折叠标题、会话头、App 过滤 tab 全是 `div/span onclick`，全站无 tabindex / role / aria-expanded / keydown | `app.py:1303-1327`、`:1236-1237`、`:1065` | 键盘用户**无法展开任何会话**；读屏无状态播报 | 改 `<button aria-expanded>` + 键盘事件 |
| FP1-h | `initToken()` 无条件用 `?token=` 覆盖本地既有令牌 | `app.py:1051-1059` | 会话固定 + 令牌投毒：任何带 `?token=` 的链接 / 跳转都会改掉用户令牌 | 先校验后落盘；已存在有效令牌时不覆盖 |
| FP1-i | `main` 无 `max-width`、无 media query（落地页却有 `max-width:720px`） | `app.py:967` vs `:1726` | 桌面端行长失控、无横屏 / 桌面适配 | `max-width:720px` + `margin:0 auto` + 断点 |

### 3.3 契约与客户端

| 编号 | 问题 | 位置 | 影响 | 修复 |
|---|---|---|---|---|
| C7 | `NetworkPolicy.isBlocked` 把 **https 判成"会被拦截"** | `NetworkPolicy.kt:30`（`if (scheme != "http") return true`）与自身 KDoc `:14-17`"非 http scheme 放行"矛盾 | 唯一调用点 `CaptureActivity.kt:210-213` → **release 构建下用户填 https 服务地址会被 Toast 拒绝且不保存**；等于"默认仅 HTTPS"的策略被反向实现，实际只剩"http 回环"可用 | `if (scheme != "http") return false`；补 `NetworkPolicyTest` |
| C8 | `HistorySync` 完全没有 `PrivacyConfig` 门控 | `HistorySync.kt:72-91`（`maybeRunOnStartup` / `run`）→ `POST /admin/extract` + `GET /messages` | 全库 `PrivacyConfig` 使用点只有 AdvisorWorker / AnalysisWorker / PrivacySettingsActivity / RedactorPreviewActivity / SyncWorker，**不含 HistorySync** → LOCAL_ONLY 模式下仍会触发服务端提取与历史拉取 | 接入 `PrivacyConfig`，LOCAL_ONLY 时不触发 |
| C9 | 全量待发队列拼成一个数组，无分批 | `SyncWorker.kt:149-166` vs `app.py:47`（`MAX_CONTENT_LENGTH=8MiB`） | 超 8 MiB → Flask 返回 **413** → 客户端 else 分支一律 `Result.retry()`（`:215-219`）→ **死循环，队列永不消化** | 按条数 / 字节分批；413 单独处理（拆分或丢弃超限项） |
| C10 | 服务端 `analyses` 表没有 `redacted/redaction_rules/redaction_hits/todo_text/todo_due` 列 | 客户端 `AnalysisStore.kt:159-168` 确实上报这些字段；服务端 `app.py:167-183`、`:592-600` 的 INSERT 列表里没有 | **脱敏证据（规则与命中次数）与用户手动改写的待办文案在服务端被静默丢弃** → 无法统计脱敏覆盖率，控制台也看不到用户改写 | 补列 + 迁移；或在服务端显式拒收并在文档中说明 |
| C11 | `/advisor` 没有 207 / `rejected` 语义 | `app.py:785-786` | 客户端无法区分"部分成功"，`AdvisorStore.markSynced` 会把无效报告一并标记已同步 | 与 `/weixin`、`/analysis` 对齐 |

### 3.4 文档与测试

| 编号 | 问题 | 位置 | 影响 |
|---|---|---|---|
| D4 | 后端测试只有 7 例 | `server/test_app.py`（36 行）+ `server/test_weixin.py`（75 行），实测 `7 passed in 0.38s` | **零覆盖**：`GET /messages`（含游标 / date）、`GET /analysis`、`/advisor`（GET+POST，含存档损坏）、`/admin/extract`、`/console`、`/sw.js`、query 鉴权、毒丸条目、207 语义。Android 侧 28 个测试类里**没有 SyncWorker 的 207 语义测试** → P0-2 / P0-3 至今没被发现 |
| D1 | README 与实现不一致 | `server/README.md:33` 说默认监听 `0.0.0.0:8000`，实现是 `app.py:1768` 无 token 时只听 `127.0.0.1` | 部署者按文档裸奔的风险 |
| D2 | README 缺 `/messages` 的 `next_before`、完全没有 `/advisor` 文档、`/analysis` 字段表缺 `escalated/forks/filtered/pkg`、数据文件表缺 `advisor_reports.json` | `server/README.md:318-341` | 接口契约只存在于代码里，接入方全靠猜 |

---

## 4. P2 缺陷

### 4.1 后端

| 编号 | 问题 | 位置 | 说明 |
|---|---|---|---|
| B5 | `/messages` 的 `date` 过滤按**服务器本地时区**切日界，固定 24h（不考虑 DST） | `app.py:462-468` | 探针实测 tz=中国标准时间，`2026-11-10` 的 `day_start=1794240000000`（= 2026-11-09T16:00Z）；客户端在别的时区看到的"今天"不是同一天 |
| B6 | `next_before` 在"恰好取满 limit"时仍返回游标 | `app.py:518-520` | 探针实测 3/3 条 → `next_before="1700000000000_2"`，下一页 `count=0` → 前端必须多打一次空请求才知道到底 |
| B7 | `/analysis GET` 无分页游标、无 date 过滤 | `app.py:662-677` | 历史分析超过 1000 条后控制台无法回看；`?date=1999-01-01` 被忽略（探针实测 count 不变） |
| B8 | `conversation LIKE '%x%'` 前导通配 | `app.py:459-460`、`:670-671` | 必然全表扫描 + 索引失效；数据量上来后每次会话过滤都是全表 |
| B9 | 顾问存档永不裁剪，每次 POST 全量重写 JSON | `app.py:726-731`、`:782-783` | O(n) 写放大；报告体积无上限 |
| B10 | `/admin/extract` 的 `running` 标志 | `app.py:873-879` | 若 `threading.Thread.start()` 抛异常，`_extract_status['running']` 永远 True → 此后所有提取请求 409；修复：**已升级为 P1（N3）**：后台线程无 try/finally，异常后永久 409 |
| B11 | `ok` 判定用 `result[leg].get('ok') is not False` | `app.py:846` | 模块返回缺 `ok` 键的 dict 时会被判为成功 |
| B12 | `/analysis GET` 的 `json.loads` 未捕获 `JSONDecodeError` | `app.py:693`、`:695`、`:700` | 探针实测：插一行脏 s1 → **整个 /analysis GET 500** → 一行脏数据毒死全部历史回看 |
| B13 | `escalated` / `filtered` 用真值判断 | `app.py:608`、`:617` | 字符串 `"false"` 也会判 True |
| E9 | 每次启动 `DROP INDEX IF EXISTS idx_messages_dedup` 再重建含全文的唯一索引 | `app.py:155-161` | 大库重启分钟级；DROP 与 CREATE 之间存在"无唯一约束"窗口，并发写可插重复 |
| E10 | 迁移语句 f-string 拼 SQL | `app.py:150-151` | 当前入参是硬编码常量（无注入），但与"SQL 全参数化"口径不一致 |
| E11 | 401 / 403 不记日志、无速率限制 | `app.py:219-234` | 暴力猜令牌无从察觉 |
| E12 | `open(JSONL_PATH, "a")` 不建目录；JSONL 无上限无轮转 | `app.py:283` | 目录缺失 → 500；文件无限增长 |
| E18 | `/admin/extract` 用裸名 `__import__("android_db_extract")` | `app.py:842` | 探针实测（模块不在 sys.path 时）：`last.error="No module named 'android_db_extract'"` → **WSGI 部署下提取功能静默失效**（只有以 server/ 为 CWD 启动才可用） |
| N5 | `analyses` 无 `received_at` 索引；`idx_analyses_window_end` 是死索引 | `app.py:676`、`:202-209` | EXPLAIN：SCAN analyses + USE TEMP B-TREE（分析列表随历史量线性变慢）；修复：加 `idx_analyses_received(received_at DESC)`、`idx_analyses_need_action_received` |
| N6 | 纯毫秒游标用 `timestamp < ?`，同毫秒行全丢 | `app.py:485-487` | 实测：3 行同毫秒、`before=<ms>` → count=0，而代码注释声称「老客户端只发毫秒也兼容」；修复：拒纯毫秒（400）或改用 `<=`；`next_before` 恒带 rowid |
| N7 | `conversation LIKE` 未转义 `%` / `_` | `app.py:459-460`、`:670-671` | 会话名含 `_` 时会越权匹配到他人会话（隐私侧交叉），也放大全表扫描；修复：转义并加 ESCAPE，或改用 `instr(conversation, ?) > 0` |
| N8 | `index()` 的 `last_extract`（来自 `data/android_db/state.json`）未过 `esc()` | `app.py:1674-1685` | **第二个存储型 XSS 入口**：state.json 可被污染 → 落地页注入脚本；修复：统一 `html.escape(s, quote=True)` |
| E13 | `/analysis GET` 不返回已归一化的 `importance` / `need_action` 列 | `app.py:173`、`:687-703` | 探针实测：库里 `importance='high'`、`need_action=1`，GET 却只回 `s1.importance=7`（端上原始数值）→ **前端必须二次归一**，是前端 importance 口径二义的根因 |

### 4.2 前端（合并 lead 自查与 frontend-audit 报告）

- **并发与时序**：`load()` 三接口串行 await（1155-1157）→ 改 Promise.all；30s setInterval 无 AbortController / 无请求序号 → 切页与刷新可叠加并发、旧响应后到覆盖新响应（1189 附近 / 1519）；setTimeout 未持句柄（1211）。
- **重绘与状态**：切 tab 不复位滚动（1128-1134）；`COLLAPSE` 键无回收、`CONV_NODES` 每次搜索无限增长（1126 / 1252-1256）→ 内存泄漏（当前索引仍自洽）；搜索每敲一字整列表重绘（1252-1256）。
- **存储与令牌**：令牌明文存 localStorage 且无清除入口；localStorage 不可用（隐私模式）时整脚本死（1054/1060/1069/1124/1350）；`?token=` 进访问日志 / Referer（1057）。
- **错误与空态**：所有错误文案一律"网络异常"（1177-1180）；加载 / 错误 / 空态只有 header 一行 12px 文字（1141/1037/1242/1416/1479/1288）→ 故障不可见。
- **排版与可读性**：时间格式三套且无时区（`fmtTime` 1087 只显示 M-D HH:mm、`fmtDate` 1093、待办卡 1428 直接显示服务端本地时间串、顾问 1491）；`word-break: break-all`（989/1743）把中文 / URL 断得很难看；`#ptr` 盖住 header（969-976 vs 951）；main 底部 66px 固定留白可能遮住末条（966-967）。
- **可访问性**：无 aria-live / tab 语义 / label（1034/1038-1047/1240/1065）；tab 栏按钮 11px + 6px 内边距（962-969）→ 点击热区远低于 44px；正文 16px 与 15px 双基准（950/989）、无显式 line-height；辅助文字 `#999`（≈2.85:1）、`#bbb`（≈1.9:1）低于 AA 4.5:1；`esc()` 未转义单引号（1084-1085，但当前注入点都在双引号属性外，暂无实害）。
- **安全响应头**：控制台缺 `X-Frame-Options` / `Content-Security-Policy` / `X-Content-Type-Options`（1623-1624）。
- **PWA**：manifest 缺 id / lang / orientation / categories / shortcuts，short_name 与 name 相同（1542-1564）；SW 离线兜底 `return hit || net` 而 `net.catch` 返回 hit，既无缓存又离线时返回 undefined → 空响应、无离线提示页（1596-1600）；SW 注册静默吞错（1525）；无 TOKEN 时仍发注定 401 的请求（1517-1518）。
- **数据契约**：`ckOf` 用裸 U+0001 作分隔符（1112）易被工具链吞掉导致会话键碰撞；`impLabel` 的无意义三元（1365-1366）导致排序与高亮口径不一致；统计页标签自相矛盾（1458-1459）；顾问 tab 无搜索 / 分页。

### 4.3 提取脚本

| 编号 | 问题 | 位置 | 说明 |
|---|---|---|---|
| E1 | 两脚本直写同一个 `MAIN_DB = data/messages.db`，但只有 `/weixin` 写 JSONL 存档 | `android_db_extract.py:78`、`pc_db_extract.py:62` | DB 直读来源的消息只进 SQLite、不进 `messages.jsonl` → "原文存档"并非全量真相（与 B3 合流） |
| E2 | 每次提取都全表拉取做去重集合 | `android_db_extract.py:441-444`、`pc_db_extract.py:368-371` | O(全表) 时间与内存，10 万条消息每次提取全量搬运正文 |
| E3 | 跨来源去重只做「秒级归一键」，且落在 Python 侧 | `android_db_extract.py:454-458`；唯一索引是毫秒级 | 通知来源与 DB 来源时间戳相差 ≥1 秒时同一消息仍双份入库；**第三路复核补充：提取去重键还缺 pkg，同 App 同秒同文会被误判重复 → 真实消息丢失，建议按 P1 处理**（README:284-285 已自认秒级口径） |
| E4 | 解密后的完整微信库明文长期驻留本地磁盘 | `android_db_extract.py:76`（`DECRYPTED_DB`） | 虽然 try/finally 会在结束后删除（12.6 已修），但运行期间是完整明文库 |
| E7 | `_parse_row` 异常时 `skipped += 1` 静默吞掉 | `android_db_extract.py:449-453` | 解析失败原因不可追溯 |
| E5 / E6 | `root_sh(f"...")` 走 shell 拼接；脚本自带 busy_timeout 不走 app.py 的 `_write_lock` | `android_db_extract.py:146/149/165/220/232/260-265` | 入参全是模块常量 → **无命令注入**；但 shell=True 风格脆弱，且与 `/weixin` 并发写只靠 busy_timeout 兜底 |

### 4.4 端云契约

`/advisor` 无 207 语义（C11）；三个端点三套时间口径（`/messages` ms 整数、`/analysis` 服务器本地时间串、`/advisor` ms 整数，文档却写 `created_at` 是 ISO —— `app.py:739` vs `AdvisorStore.kt:53`）；`HistorySync` 回填丢 `pkg` / `source`（`HistorySync.kt:129-140`）；四处硬编码 `"/weixin"` 去后缀推导（`SyncWorker.kt:266/350`、`HistorySync.kt:163`、ConsoleActivity）；服务端先写存档后入库导致重复灌水；客户端不读 `received/duplicated/invalid` 计数；秒级去重键 vs 毫秒唯一索引。

【队友报告、lead 未逐条复核】7 天保留策略会删掉未上报的待发消息（`Retention.kt:23/56` + `PendingQueue.kt:93`）；`AnalysisStore.readUnsynced` 只读最近 500 条（`AnalysisStore.kt:398-403`）；`appendText` 非原子（`AnalysisStore.kt:254-258`）。

---

## 5. P3 缺陷（摘要）

后端：`GET /weixin` → 405（探针实测，行为正确）；失败审计缺失；`/messages` 的 `limit` 非法值静默回落 100（无提示）；`/analysis GET` 的 limit 同理。
前端：11px 中文小字（969/990）；11 种灰阶无 token；`stat-num` 无 tabular-nums；来源分布 `join(' / ')` 密度低；touchend 未处理 touchcancel（1204）；`THRESHOLD=70` 固定（1189）；main 未加 safe-area 左右内边距（967）；落地页时间用服务器本地时区（1696）；两套设计系统并存（控制台浅色 946-1028 / 落地页浅灰 1725-1745 / 根目录 intro.html 深色）；反馈弱（caret 字符切换 1353-1354）。
事实修正：根目录 `intro.html` 实为 GitHub Pages 深色宣传页，**不是 `index()` 的副本**（"落地页是 index 副本"的旧描述与代码事实不符）。
提取脚本：shell=True 风格、无结构化日志、跨进程锁缺失。

### 5.1 第三路复核补充的 P3（后端）

- `app.py:281-283` `conn` / `archive` 在 try 外打开 → open 失败时连接泄漏；
- `app.py:97-101` `timeout=10` 与 `busy_timeout=5000` 口径冲突；`journal_mode=WAL` 每连接都执行一次；
- `app.py:155-161` 每次启动 DROP + CREATE 含全文的唯一索引（大库分钟级重建，见 E9）；
- `app.py:1679` `open()` 不关句柄 + `UnicodeDecodeError` 未捕获 → 500；
- `app.py:124-133` 表列可空，而 `:1702` 的 `esc()` 假定是字符串 → NULL 行触发 AttributeError；
- 全篇无 `logging`、无 `@app.errorhandler` → 500 / 413 返回 HTML（实测 413 的 Content-Type 是 `text/html`），前端按 JSON 解析必然报错；
- `app.py:151`、`:199` 与两个提取脚本用 f-string 拼 SQL（当前入参全为常量，无注入，但与「全参数化」口径不一致）；
- `android_db_extract.py:417`、`pc_db_extract.py:122/243` 的 `file:`+`path`+`?mode=ro` 未做 URL 转义；
- `android_db_extract.py:157-192` `get_uin()` 遇到首条坏记录即中断整轮搜索；
- `android_db_extract.py:482-483` state.json 非原子写（PC 侧是原子的，两端口径不一）；
- `pc_db_extract.py:251` `stats['self']` 自曝 wxid，并经 `/admin/extract/status` 返回；
- `启动服务.bat:22/25/30` adb 不检查 errorlevel（失败被吞），且用 PATH 里的 python 而非 `.venv` 解释器（与 `requirements.txt` 固定版本脱节）。

### 5.2 做得好的地方（三路复核共同确认，值得保留）

1. 令牌恒定时间比较：`hmac.compare_digest`（`app.py:237-245`），杜绝时序侧信道；
2. SQL 全参数化，无用户输入拼接（`app.py:306-323`、`:592-620`），复核未发现注入点；
3. 复合游标 `(timestamp, rowid)` 分页方向正确（`app.py:472-490`、`:518-520`），问题在客户端未接；
4. `_connect()` 统一 WAL + busy_timeout，路由层 finally 关连接（`app.py:90-101`）；
5. `/weixin` 用 207 + `rejected[]` 精确告知被拒条目（`app.py:336-344`）——设计本身是对的，只是端上分支顺序把它的价值抵消了；
6. 解密后的明文库在 finally 中清理（`android_db_extract.py:525-529`、`pc_db_extract.py:316-319`）；
7. 唯一索引含 pkg（`app.py:158-159`），账号目录用 `re.fullmatch(r'[0-9a-f]{32}')` 挡路径穿越（`android_db_extract.py:144-154`）；
8. `server/data/` 已被 `.gitignore:24` 覆盖（git check-ignore 实测命中），聊天原文不入版本库；
9. 内嵌控制台 `CONSOLE_HTML` 不经 Jinja 渲染（`app.py:1616-1624` 原样输出），复核未发现服务端模板注入；
10. `/console/manifest.json`、`/console/icons/*`、`/sw.js` 三处不鉴权，但实测不返回任何用户数据，可判为可接受（外壳鉴权问题另见 FP0-2）。

---
## 6. 前端设计评审（专章）

### 6.1 信息架构

| 编号 | 问题 | 证据 | 建议 |
|---|---|---|---|
| DESIGN-1 | **首屏不承担"今天要做什么"**：会话默认全折叠，header 同步行只有消息 / 分析数，四个 tab 无角标 | `app.py:1297`、`:1148-1149`、`:1038-1047` | 首屏加"需关注 N · 待办 N · 复盘 N"概览条 + tab 角标；默认展开当天高优先会话 |
| DESIGN-2 | "需关注"分区无数量，仅靠符号与颜色区分 | `:1286`、`:985` | 加计数徽标 + 文案（不要只靠颜色） |
| DESIGN-3 | 三套设计系统并存 | 控制台浅色 `:946-1028` / 落地页浅灰 `:1725-1745` / `intro.html` 深色 | 令牌化后合并为一套；落地页与控制台共用组件 |
| DESIGN-4 | 无 CSS 变量 → 无法暗色模式 | `:946-1028` | 抽 `--bg/--fg/--muted/--brand/--radius/--space` |
| DESIGN-5 | 字阶 11~26px 共 10 档 | `:969`、`:989`、`:950` | 收敛到 5 档（12/14/16/20/26）+ 明确行高 |
| DESIGN-6 | 预览行挤掉时间、无相对时间 | `:1310-1311` | 时间列固定宽 + "3 分钟前 / 昨天" |
| DESIGN-7 | 会话 → 日期两层折叠过深 | `:1297-1327` | 默认一层，日期作为组内小标题 |
| DESIGN-8 | 搜索只在会话 tab，且"会话名命中 = 整组保留" | `:1239-1241`、`:1272-1281` | 搜索全局化，命中高亮到消息级 |
| DESIGN-9 | 对比度普遍低于 AA | `#999`≈2.85:1、`#bbb`≈1.9:1、chip `#07c160`/`#e8f7ef`≈2.6:1、badge 白/`#ff976a`≈2.2:1、`.imp-high` `#e6432d`≈4.3:1 | 辅助文字提到 ≥4.5:1 |
| DESIGN-10 | 加载 / 错误 / 空态只有 header 一行 12px 字 | `:1141`、`:1037`、`:1242`、`:1416`、`:1479`、`:1288` | 列表内骨架屏 + 空态说明 + 错误重试按钮 |
| DESIGN-11 | 无桌面 / 横屏适配 | `:967` 无 media query | `max-width:720px` + `@media (min-width:900px)` 双栏 |
| DESIGN-12 | 反馈弱（caret 字符切换、`.af-tab` 用 span） | `:1353-1354`、`:1236-1237` | 统一 icon / 按钮态 / 过渡 |

### 6.2 移动端与可访问性

- **热区**：tab 按钮 11px + padding 6px（962-969）、chip / 按钮多处 < 44px → 触控可用性差。
- **语义**：全站 div / span onclick（1303-1327、1236-1237、1065）→ 键盘不可达；无 aria-live（状态更新不播报）；无 label 关联（1240）。
- **对比度 / 字号**：见 DESIGN-5 / DESIGN-9。
- **滚动与折叠**：切 tab 不复位滚动（1128-1134）；两层折叠过深（DESIGN-7）。
- **暗色**：无令牌，无法暗色（DESIGN-4）。

### 6.3 PWA

- **必须先修**：SW 导航缓存越权（P0-1）与外壳强制鉴权（P0-2）—— 这两条不修，PWA 既不安全也不可用。
- manifest 补 id / lang / orientation / categories / shortcuts，short_name 与 name 去重（1542-1564）；scope 与 SW scope 对齐。
- SW：API 走网络的策略正确（1583-1589）；但离线兜底会返回 undefined（1596-1600）→ 需要一个离线提示页；注册失败要可见（1525）。

### 6.4 事实核对

- **无 XSS 的部分**：会话 / 发送者 / 正文 / 话题 / 待办 / 摘要 / 建议等注入点都走了 `esc()`（1083-1086）；`ckOf` 分隔符确为 U+0001（1112）。**但**顾问卡片的 `case_count` / `period_days` 是漏网的未转义注入点（FP1-d）—— 这是本轮唯一确认的存储型 XSS 入口。

---

## 7. 修复路线图

### 阶段 0 · 止血（当天，4 处）

1. `SyncWorker.kt`：207 分支前置到 `isSuccessful` 之前（消息 + 分析两条通道），并补 MockWebServer 用例锁死行为。
2. `app.py:1590-1604`：SW 只缓存 `/console` 壳、导航改网络优先；`index()` 加 `no-store`，并去掉落地页内联的消息正文。
3. `app.py:1494-1495`：补 `esc()`；`/advisor` 服务端收敛 `case_count` / `period_days` 类型。
4. `app.py:604-605`：`window_end` / `message_count` 类型校验 + `except Exception` 逐条隔离，毒丸不再 500。

### 阶段 1 · 可靠性（1~2 周）

- `init_db()` 导入即执行 + 幂等锁（E8）；修 `idx_messages_dedup` 的 DROP / CREATE 窗口（E9）。
- 顾问存档：损坏即拒绝写入 + 追加式或改 SQLite（B2、B9）；`/advisor` 补 207 语义（C11）。
- `/analysis GET`：返回归一化 importance / need_action，脏行容错（E13、B12）；补游标与 date（B7）。
- `/messages`：`next_before` 满页时才给、date 用显式时区参数（B6、B5）；`conversation` 前缀匹配 + 索引（B8）。
- JSONL：先入库后存档 + 轮转（B3、E12）；字段长度 / 时间范围校验（B4）。
- 客户端：队列分批 + 413 单独处理（C9）；`NetworkPolicy` https 反转（C7）；`HistorySync` 接隐私门控（C8）；服务端补脱敏证据列（C10）。
- 测试：把 §1 探针脚本转成 pytest 用例（12 条断言），覆盖 207 / 413 / 毒丸 / 存档损坏 / 游标 / 脏行。

### 阶段 2 · 前端与设计（2~4 周）

- 设计系统令牌化（灰阶 / 间距 / 圆角 / 品牌色 / 字阶 / 行高）+ 暗色模式（DESIGN-3/4/5/9）。
- 首屏改造：概览条 + tab 角标 + 默认展开（DESIGN-1/2）；空态 / 错误态 / 骨架屏（DESIGN-10）。
- 数据层：Promise.all + 请求序号 + AbortController；接 `next_before` 游标与"没有更多"；全局过滤条（FP1-e/f）。
- 重绘治理：只重绘数据区、鉴权框只建一次、切 tab 复位滚动（FP1-b/c）。
- 可访问性：语义化按钮 + aria-expanded / aria-live + 44px 热区 + label（FP1-g）。
- 响应式：`max-width:720px` + 桌面双栏（FP1-i）。
- 后端拆分：`app.py` 1780 行上帝文件（路由 / SQL / 内嵌前端 / PWA 资源 / 静态页）→ 至少拆 `console/`（前端资源）、`storage.py`、`routes/`。

### 阶段 3 · 工程化

- CI 覆盖新增用例；服务端集成 MockWebServer 端到端；README 与实现对齐（监听地址、游标、`/advisor`、字段表、数据文件表）。
- 提取脚本：增量游标替代全表去重集合（E2）、统一秒级 / 毫秒去重口径（E3）、结构化日志（E7）、WSGI 下的模块路径问题（E18）。

---

## 8. 与既有基线（revise.md / progress.md）的关系

| 既往条目 | 现状 | 本轮对应 |
|---|---|---|
| P0-3（无 token 全接口无鉴权 + 默认 0.0.0.0） | **已修**（`app.py:1768` 无 token 只听 127.0.0.1） | 文档仍写旧口径（D1） |
| P0-15（`/weixin` 全条目失败仍 200 → 端上整批剔队列） | **服务端已修（207+rejected）、端上未接住** | **P0-2**（分支顺序错误 → 仍然整批删） |
| §12.6 游标分页已实现 | 服务端已实现且探针验证正确，**前端未使用** | FP1-f / C8'（客户端不翻页） |
| §12.6 `_connect()` WAL + busy_timeout | 已在，探针未发现锁问题 | ✅ 已做得好 |
| §12.8 待办：提取分批 + 进度上报、阶段 2 分包、waitress+TLS | **仍待处理**，且本轮新增两条部署级问题 | E8（init_db 不在 import 期）、E18（提取模块不可导入）、阶段 2 分包 |
| 上帝类 `server/app.py` 1611 行 | **涨到 1780 行**，内嵌前端从 600 行涨到 620 行 | 阶段 2 拆分 |
| 去重键三份 / 表结构迁移三份 | 仍在 | E3（秒级 vs 毫秒）、C10（字段清单两套） |
| revise.md §12.7 "数据保留 1 周" | 仍在，但与"待发队列"存在冲突（队友报告，待复核） | §4.4 保留策略条目 |

---

## 9. 附录

### 9.1 探针脚本

- 位置：`.tmp/review_probe.py`（把 `server/app.py` 复制到临时目录后导入，`DATA_DIR` 隔离，不写仓库 `server/data`）。
- 运行：`python -X utf8 .tmp/review_probe.py > .tmp/probe_out.txt 2> .tmp/probe_err.txt`（工作目录 C:/project/notifyme）。
- 原始输出：`.tmp/probe_out.txt`（含全部探针 JSON），错误栈：`.tmp/probe_err.txt`。

### 9.2 本轮覆盖与未覆盖

**已覆盖**：`server/app.py` 全文（逐行）、两个提取脚本、Android 侧 `SyncWorker / HistorySync / NetworkPolicy / AnalysisStore / PrivacyConfig` 相关路径、前端 `CONSOLE_HTML / CONSOLE_MANIFEST / CONSOLE_SW / index()` 逐行、README 与实现比对、后端测试清点与运行、三份并行审查报告（frontend-audit 49.8KB / contract-audit 38.3KB，见 `.tmp/`）。


- 三路复核补充覆盖：数值边界与越界时间戳 / JSONL 与 SQLite 双写顺序 / 空令牌与 WSGI 绑定 / 提取持长写事务 / 游标谓词的查询计划（EXPLAIN 对比 SCAN vs SEARCH）/ 纯毫秒游标丢行 / LIKE 通配转义 / state.json 注入 / 413 与错误处理返回 / gitignore 覆盖校验。
**未覆盖（留待下一轮）**：
- `app/` 其余 88 个 Kotlin 文件的完整复查（本轮只按契约相关路径抽查）；
- `cpp/llama_jni.cpp` 原生推理层；
- `server/pwa_icons/` 视觉资产与各尺寸适配；
- 真实浏览器下的 PWA / SW 行为验证（缓存条目落盘、离线兜底、manifest 安装流程）—— 需要跑一次 server + DevTools；
- 部署反代前缀 `/mmonitor/` 的 404 存疑项（frontend-audit P1-10）—— 需要真实域名环境；
- 多用户 / 并发 / 长稳压测。

### 9.3 三路并行审查产出

| 报告 | 规模 | 结论 |
|---|---|---|
| `.tmp/frontend-audit-report.md` | 49.8 KB | P0×2 / P1×10 / P2×22 / P3×20 + DESIGN×12；本报告已并入 P0-1 与 P1 全部，P2/P3 取摘要 |
| `.tmp/contract-audit-report.md` | 38.3 KB（469 行） | P0×2 / P1×4 / P2×10 / P3×7 + "契约一致但脆弱"8 条；本报告已并入 P0-2、P0-3、C7~C11 |
| `.tmp/backend-audit-report.md` | 37 KB / 411 行 | P0×3 / P1×6 / P2×9 / P3×12，全部为已确认缺陷（读码可证 + 运行时探针复现）；本报告已并入 P0-4/5/6、N1~N4、N5~N8 与 §5.1 / §5.2 |

*（本报告只收录"能回源码或回探针复现"的条目；未能复现的一律进 §9.2 未覆盖清单或标为存疑。）*

---

## 10. 修复状态（自动优化执行记录）

> 本节由 AI 自动优化执行方追加，**§0–§9 原文一字未改**。
> 三位执行方（前端 / 后端 / 工程化）各自填自己那一栏，最后由维护者统一校对。
>
> 状态口径只有三档，填表时不要自由发挥：
>
> - **已修复**：代码改完且行为符合本报告描述，但本次没有专门的回归用例锁死；
> - **已修复并有测试**：改完且有用例（单测 / pytest / CI 步骤）能证明「改前坏、改后好」；
> - **待修**：还没动，或只做了一半。
>
> 「验证方式」写可复制的命令或用例名（例：app/src/test/...、server/test_*.py::test_*、CI 步骤名）；
> 「证据」写 commit / PR 号，或关键输出片段（命令 + 退出码 + 关键行）。

### 10.1 §0 必改 Top 13

| # | 改动（§0 原文摘要） | 状态 | 验证方式 | 证据 |
|---|---|---|---|---|
|  | 1 | 已修复并有测试 | app/src/test/java/com/notifyme/android/SyncOutcomeCoreTest.kt「207 判定为部分成功而不是整批成功」；SyncWorker.postJson 统一走 SyncOutcomeCore.classify | app/src/main/java/com/notifyme/android/SyncOutcome.kt（classify 先判 207 再判 2xx）、SyncWorker.kt |
|  | 2 | 已修复并有测试 | server/test_console.py::test_console_sw_navigation_is_network_first_for_shell_only 等 7 条 SW 用例 | server/console_ui.py |
|  | 3 | 已修复并有测试 | server/test_hardening.py::test_create_app_refuses_to_start_without_token、test_module_level_app_serves_without_manual_init；server/test_regression.py::test_wsgi_lazy_init_creates_db | server/app.py（create_app 工厂 + 模块级 app） |
|  | 4 | 已修复并有测试 | server/test_hardening.py::test_analysis_rejects_out_of_range_message_count、test_analysis_rejects_oversized_identifiers；server/test_regression.py::test_out_of_range_timestamp_rejected | server/app.py（_safe_int / _analyzed_at_text） |
|  | 5 | 已修复并有测试 | server/test_regression.py::test_jsonl_archive_matches_db_rows、test_rejected_item_not_written_to_archive | server/app.py（/weixin 先入库后写存档） |
|  | 6 | 已修复并有测试 | server/test_console.py::test_advisor_render_escapes_server_data；server/test_hardening.py::test_advisor_normalizes_fields、test_advisor_clamps_out_of_range_numbers | server/console_ui.py、server/app.py |
|  | 7 | 已修复并有测试 | server/test_hardening.py::test_analysis_isolates_unencodable_item、test_analysis_rejects_oversized_identifiers | server/app.py（/analysis 逐条隔离） |
|  | 8 | 已修复并有测试 | server/test_console.py::test_console_api_rejects_non_2xx | server/console_ui.py（api() 判 r.ok） |
|  | 9 | 已修复并有测试 | server/test_regression.py::test_analysis_returns_normalized_importance；server/test_hardening.py::test_analysis_get_normalizes_raw_numeric_columns | server/app.py（/analysis GET 归一化） |
|  | 10 | 已修复并有测试 | server/test_hardening.py::test_advisor_corrupt_archive_refuses_to_write | server/app.py（顾问存档损坏即拒写） |
|  | 11 | 已修复并有测试 | app/src/test/java/com/notifyme/android/SyncBatchCoreTest.kt、SyncOutcomeCoreTest.kt「413 单独判定为载荷过大而不是普通失败」 | SyncBatchCore.splitBatches/bisect、SyncOutcome.PAYLOAD_TOO_LARGE、SyncWorker 三通道 413 分支 |
|  | 12 | 已修复并有测试 | server/test_extract.py::test_batch_writer_commits_per_batch、test_merge_messages_commits_in_batches；server/test_hardening.py::test_extract_returns_503_when_main_db_is_busy | server/android_db_extract.py、server/pc_db_extract.py、server/app.py |
|  | 13 | 已修复并有测试 | server/test_regression.py::test_next_before_only_when_more_pages | server/app.py、server/console_ui.py |

### 10.2 §7 阶段 0 · 止血（4 处）

| # | 条目 | 状态 | 验证方式 | 证据 |
|---|---|---|---|---|
|  | 0-1 | 已修复（纯函数单测，未引入 MockWebServer） | app/src/test/java/com/notifyme/android/SyncOutcomeCoreTest.kt、SyncWorker 三通道统一走 classify | app/src/main/java/com/notifyme/android/SyncOutcome.kt、SyncWorker.kt |
|  | 0-2 | 已修复并有测试 | server/test_console.py::test_console_sw_only_caches_console_shell、test_console_sw_respects_no_store_cache_control；server/test_regression.py::test_sw_does_not_cache_landing_page | server/console_ui.py、server/app.py |
|  | 0-3 | 已修复并有测试 | server/test_console.py::test_advisor_render_escapes_server_data；server/test_hardening.py::test_advisor_normalizes_fields | server/console_ui.py、server/app.py |
|  | 0-4 | 已修复并有测试 | server/test_hardening.py::test_analysis_rejects_out_of_range_message_count、test_weixin_isolates_unencodable_item | server/app.py |

### 10.3 §7 阶段 1 · 可靠性

| # | 条目 | 状态 | 验证方式 | 证据 |
|---|---|---|---|---|
|  | 1-1 | 已修复并有测试 | server/test_hardening.py::test_create_app_initializes_db_idempotently；server/test_extract.py::test_merge_messages_creates_dedup_index_if_missing | server/app.py（init_db 幂等）、server/android_db_extract.py |
|  | 1-2 | 已修复并有测试 | server/test_regression.py::test_advisor_returns_207_with_rejected_indices、test_advisor_append_does_not_rewrite_archive、test_advisor_legacy_json_is_migrated；server/test_hardening.py::test_advisor_corrupt_archive_refuses_to_write | server/app.py |
|  | 1-3 | 已修复并有测试 | server/test_regression.py::test_analysis_cursor_paginates_without_loss、test_analysis_date_filter_by_analyzed_at_day；server/test_hardening.py::test_analysis_get_tolerates_garbage_numeric_columns、test_analysis_get_skips_broken_row | server/app.py |
|  | 1-4 | 已修复并有测试 | server/test_regression.py::test_next_before_only_when_more_pages、test_messages_tz_day_bounds；server/test_hardening.py::test_messages_conversation_prefix_match、test_messages_has_conversation_index | server/app.py |
|  | 1-5 | 已修复并有测试 | server/test_regression.py::test_jsonl_archive_matches_db_rows、test_message_text_length_limit；server/test_hardening.py::test_jsonl_archive_rotates_when_too_large | server/app.py |
|  | 1-6 | 已修复并有测试 | app/src/test/java/com/notifyme/android/SyncBatchCoreTest.kt、SyncOutcomeCoreTest.kt、NetworkPolicyTest.kt、HistorySyncCoreTest.kt；server/test_regression.py::test_analysis_redaction_columns_roundtrip | SyncBatchCore、SyncOutcome、NetworkPolicy、HistorySync、server/app.py |
|  | 1-7 | 已修复并有测试（探针脚本整体换成 513 条 pytest） | python -m pytest server/ -v --tb=short → 513 passed | server/test_app.py、test_weixin.py、test_hardening.py、test_regression.py、test_extract.py、test_console.py |

### 10.4 §7 阶段 2 · 前端与设计

| # | 条目 | 状态 | 验证方式 | 证据 |
|---|---|---|---|---|
|  | 2-1 | 已修复并有测试 | server/test_console_ui.py（25 条，覆盖主题三态 / 概览条 / 骨架屏 / 游标翻页 / 局部重绘 / 可访问性 / 响应式） | server/console_ui.py：主题三态 auto/light/dark（localStorage 键 wx_theme_v1 + html[data-theme] + meta#meta-theme-color 联动，select#theme-select） |
|  | 2-2 | 已修复并有测试 | server/test_console_ui.py（概览条四项 / 骨架屏 / tab 角标用例） | server/console_ui.py：概览条改为 ov-24h 近 24 小时 / ov-todo / ov-fail / ov-sync 四项 + skeletonHtml 骨架屏 + tab 角标 |
|  | 2-3 | 已修复并有测试 | server/test_console_ui.py（游标翻页用例）+ server/test_regression.py::test_next_before_only_when_more_pages | server/console_ui.py：新增 fetchMsgPage / fetchAnaPage（PAGE_LIMIT=200，&before= 游标），删掉一次性拉全量的 fetchAllMessages；底部提示「已加载 N 页，还有更多」/「已到底部 · 没有更多了」 |
|  | 2-4 | 已修复并有测试 | server/test_console_ui.py（局部更新与事件委托用例） | server/console_ui.py：renderData 只更 #err-box + #pane，patchSections 按 section key 局部更新（DocumentFragment + insertBefore），renderFilterBar 改事件委托 + 原生 button |
|  | 2-5 | 已修复并有测试 | server/test_console_ui.py（roving tabindex / aria-live / 44px 热区 / 对比度用例） | server/console_ui.py：roving tabindex、#pane aria-live=polite、focus-visible 样式、44px 触控热区；明暗两套主题 29 组前景/背景对比度全部 ≥4.5:1 |
|  | 2-6 | 已修复并有测试 | server/test_console_ui.py（420 / 900 / 1400px 断点用例） | server/console_ui.py：.shell max-width clamp(320px,100%,720px)、字号全 clamp()、≤600px 单列、≥900px 两栏、≥1280px 三列；修掉 main{margin:0 auto} 导致宽屏永远单列的历史 bug |
|  | 2-7 | 已修复并有测试 | python3 -m pytest server/ -q（513 passed）；python3 -m compileall -q server | server/app.py 2294 → 717 行，拆出 util.py / storage.py / routes.py / console_ui.py（后又拆出 stats_api.py / suggests.py）；server/test_module_split.py（94 条：路由三元组、导出面、运行期状态唯一性、模块归属） |
|  | 2-8 | 已修复并有测试 | server/test_console_classify.py（82 条，node 跑真逻辑）+ server/test_console_classify_wiring.py（12 条）+ 浏览器实跑核验 | server/console_classify.js：控制台归类规则单一事实源（来源 App / 会话 / 日期 / 发送者 / 重要度 / 建议类型 六个维度，UMD：浏览器挂 window.ConsoleClassify、node 走 module.exports）；server/app.py 启动时把该文件注入 console_ui.py 的 /*__CONSOLE_CLASSIFY_JS__*/ 占位符；server/console_ui.py：会话区按来源 App 二次分区（.sec-head.sub）、会话卡内按发送者分组（.sender-head，原生 button + data-action=toggle 复用事件委托）、待办按重要度分区、顾问建议按类型分区；取不到脚本时降级不报错 |
|  | 2-10 | 已修复并有测试 | server/test_console_classify.py（82 条，含 groupMessages 两维度 26 条新用例）+ server/test_console_queue.py（追加 10 条静态契约）+ 浏览器实跑核验（CDP：维度 chip 可切换、localStorage wx_group_v1 持久化、排队设备 chip 过滤生效、errs 为空） | server/console_classify.js：groupMessages(msgs,dim,getTs,getSender) 两级折叠分组（date 顶层日期+子发送者 / sender 顶层发送者+子日期，单子组时 children 空数组且 items 平铺）、groupQueueTasksByDevice、queueDeviceSummary；server/console_ui.py：convDimBar / groupMsgsByDim / convGroupHtml / setConvGroupDim（localStorage wx_group_v1）、setQueueDevice + 排队设备筛选 chip |
|  | 2-9 | 已修复并有测试 | server/test_console_queue.py（22 条静态契约）+ server/test_console_classify.py（追加 11 条排队维度）+ server/test_queue.py（30 条接口与存储）+ 浏览器实跑核验 | server/console_ui.py：控制台重做为两栏外壳（顶栏 + 粘性标签栏 + 侧栏 + 主区），5 个 Tab（会话 / 待办 / 排队 / 统计 / 顾问）+ 概览条 5 指标（ov-24h / ov-todo / ov-queue / ov-fail / ov-sync）；新增「排队」Tab（按状态分区 + 状态筛选 chip + stale 标记）；server/console_classify.js 新增排队维度（QUEUE_STATE_ORDER / QUEUE_KIND_LABELS / normalizeQueueTasks / queueSummary / groupQueueTasks） |
|  | 2-11 | 已修复并有测试 | server/test_stats_api.py（19 条）+ server/test_console_ui.py（7 条新契约用例，全文件 32 passed）+ 浏览器实跑核验（CDP：概览 5 项显示「全库真值」、建议 3 条带跳转、统计 Tab 多出「脱敏情况」「排队任务」两卡、分析卡显示「🛡 已脱敏 3 处」+ 规则明细、无 JS 异常） | server/stats_api.py（GET /stats 全库聚合：messages 段 total / convs / last_24h / last_ts / by_pkg / by_source，analyses 段 total / convs / todo / redacted_cases / redaction_hits，queue 段 total / active / queued / running / failed / done / stale / devices / by_kind；状态集合与 TTL 引用 storage 常量，不写第二份字面量）；server/suggests.py（GET /suggests/next 表驱动建议：queue_failed 100 / queue_stale 90 / todo 80 / convs_missing 70 / advisor_age_days>3 60 / advisor_total eq0 50，最多回 3 条，每条带 title / why / how / evidence / jump）；server/console_ui.py（fetchStats / fetchSuggests 接入 load() 的 6 路 Promise.all，各自 .catch 兜底；renderOverview 与 renderStats 有真值用真值、无则本地估算并在 -sub 标「估算 · 仅已加载 N 条」；redactBadge / redactDetail 渲染脱敏证据并纳入 analysisSearchText 搜索；skipNoticeHtml + SKIP_NOTICE 把服务端 skipped 计数显式告知用户；suggestHtml + data-action=jump 跳转；SW 白名单加 /stats 与 /suggests，不缓存实时数据） |

### 10.5 §7 阶段 3 · 工程化

| # | 条目 | 状态 | 验证方式 | 证据 |
|---|---|---|---|---|
|  | 3-1 | 已修复并有测试 | python3 -m pytest server/ -v --tb=short ；CI 步骤「Server tests」「Unit tests + assemble」 | .github/workflows/android.yml：语法检查改 compileall -q server、pytest 改 server/ 目录级、依赖装自 server/requirements.txt |
|  | 3-2 | 已修复并有测试（客户端 + 服务端两侧都补齐） | app/src/test/java/com/notifyme/android/SyncHttpEndToEndTest.kt（11 例）、HistorySyncPagingHttpTest.kt（5 例）；server/test_http_e2e.py（34 例，真实 HTTP/1.1） | 客户端侧：app/src/test/java/com/notifyme/android/HttpEndToEndFixture.kt 用 java.net.ServerSocket 自写 HTTP/1.1 服务端 + okhttp 4.12.0 客户端，走真实 socket 做上报/回填端到端，**生产代码与 app/build.gradle.kts 零改动**；服务端侧：werkzeug make_server 起真实 HTTP 服务器 + urllib 发真实 TCP 请求，覆盖鉴权（401 / 429 限流 / ?token= 兼容）、207 语义、next_before 游标翻页、413 / 404 / 405、控制台页与 PWA 静态资源，以及后台抽取路径（POST /admin/extract 的 401 / 400 / 409 / 503 / 500 分支，source=android|pc|all 的分腿执行、单腿失败不拖垮另一腿、sqlite 占用标 error_kind=busy，GET /admin/extract/status 的 running/last） |
|  | 3-3 | 已修复并有测试 | README.md「服务端部署」小节；docs/BUILD.md「CI 自动签名与发布」「守卫脚本本地自查」 | 以 server/app.py 现状为准改写：监听矩阵（0.0.0.0:8000 / 127.0.0.1:8000）、next_before 游标口径、两个字段表、server/data/ 四个数据文件；docs/BUILD.md 同步修正 Kotlin 2.2.21 / litert 2.2.0 / beta 桌面名 / 源码文件数 / CI 步骤 |
|  | 3-4 | 已修复并有测试（**本条原述有误**：HEAD(4fe0ea0) 起两个脚本已是增量游标 + SQLite 侧去重；本轮补的是游标健壮性） | python -m pytest server/test_extract_cursor.py -q → 23 passed | server/android_db_extract.py（`_cursor_int` / `_load_cursor_state` / `_save_state`，state.json 版本化 v2，损坏/非法/未知版本一律自愈退回全量且只告警不中断）；server/pc_db_extract.py（账号→会话两级游标，账号失败不写回旧游标，全账号失败不写 state.json）；server/test_extract_cursor.py（23 条，含 EXPLAIN QUERY PLAN 断言去重走索引） |
|  | 3-5 | 已修复并有测试 | server/test_extract.py::test_dedup_key_contains_pkg_and_millisecond、test_merge_messages_keeps_same_second_different_ms | server/android_db_extract.py、server/pc_db_extract.py |
|  | 3-6 | 已修复并有测试 | server/test_extract.py::test_log_event_emits_key_value_pairs、test_log_event_flattens_newlines、test_merge_emits_structured_parse_error_event | server/android_db_extract.py、server/pc_db_extract.py |
|  | 3-8 | 已修复并有测试 | python -m pytest server/test_queue.py -q → 30 条全过；GET /queue 响应带 devices 聚合 | server/storage.py：queue_devices() @718（GROUP BY device_id，active = queued+running）；server/routes.py：query_queue @976 返回顶层 devices + summary.devices 台数 |
|  | 3-7 | 已修复并有测试 | server/test_regression.py::test_extract_module_loads_by_path、test_run_extract_bg_requires_explicit_ok_true | server/app.py（_import_extract_module） |

### 10.6 工程化侧附带项（不在 §7 原文里，本次顺手做掉）

| # | 条目 | 状态 | 验证方式 | 证据 |
|---|---|---|---|---|
|  | E-1 | 已修复并有测试 | 两份 workflow 用 yaml.safe_load 解析通过 + 步骤清单核对 | .github/workflows/android.yml、.github/workflows/release.yml |
|  | E-2 | 已修复并有测试 | python docs/scripts/privacy_guard.py（负向用例见 PR 描述）；python docs/scripts/version_check.py --expect-tag v0.3.19 | docs/scripts/privacy_guard.py、docs/scripts/version_check.py |
|  | E-3 | 已修复 | python3 docs/scripts/version_check.py --expect-tag v9.9.9 → 退出码 1 | .github/workflows/release.yml |
|  | E-4 | 已修复 | .github/workflows/release.yml「Assemble signed open release」 | app/build.gradle.kts |
|  | E-5 | 已修复并有测试 | .\gradlew.bat lintOpenDebug → issue 数 97 → **3**（0 error）；剩余 3 条为刻意保留（依赖库自带 ObsoleteLintCustomCheck / targetSdk 34 的 OldTargetApi / release 只打 arm64 的 ChromeOsAbiSupport） | app/src/main/res/values/styles.xml、dimens.xml；strings.xml（11 个占位符资源，SetTextI18n 24 → 0）；res/xml/data_extraction_rules.xml + backup_rules.xml（AndroidManifest 同步 dataExtractionRules / fullBackupContent）；MainApplication.kt（API 33+ 用 RECEIVER_NOT_EXPORTED，低版本退回无标志注册）；ConsoleActivity.kt（dayKeyFmt 改 getter 属性消 ConstantLocale、电池白名单加 @SuppressLint("BatteryLife")） |
|  | E-6 | 已修复 | mmdc 11.17.0 重渲染 9 张图 × (png + svg) 共 18 个产物退出码全 0，PNG 实际像素与 docs/architecture/README.md 索引表一致 | docs/architecture/diagrams/*.mmd、*.png、*.svg、docs/architecture/README.md（同步当前行数：console_ui.py 3430 / console_classify.js 434 / app.py 717 / storage.py 758 / routes.py 1036 / stats_api.py 180 / suggests.py 304 / android_db_extract.py 1260 / pc_db_extract.py 1122，app/src/test 52 个 .kt） |
|  | E-7 | 已修复并有测试 | python -m pytest server/ -q → 513 passed（新增 server/test_console_ui.py 32 条 + server/test_extract_cursor.py 23 条 + server/test_http_e2e.py 34 条 + server/test_console_classify.py 82 条 + server/test_console_classify_wiring.py 12 条 + server/test_queue.py 46 条 + server/test_console_queue.py 22 条 + server/test_queue_contract.py 9 条 + server/test_stats_api.py 19 条）；android 单测 1069 条全绿 | server/console_ui.py、server/test_console_ui.py、server/test_extract_cursor.py、server/test_http_e2e.py、server/test_console_classify.py、server/test_console_classify_wiring.py、app/src/test/** |
|  | E-8 | 已修复并有测试 | 浏览器实跑核验（CDP 驱动已登录 Edge 打开 /console，注入 5 条微信 + 3 条飞书 + 3 条钉钉消息、4 条分析、1 条复盘报告）：会话页出现来源 App 分区（钉钉/飞书/微信 · 1 个会话）、发送者分组头 3 个；待办页按重要度分 4 区（高/中/低/未标重要度，各 1 条）；顾问页按类型分 3 组（重点关注 / 提示词调优 / 其它建议）；页面无 JS 异常、控制台无 error/warning | server/console_classify.js、server/console_ui.py、server/app.py |
|  | E-10 | 已修复并有测试 | python -m pytest server/test_queue_contract.py -q → 9 passed（端侧 Kotlin buildPayload 的字段名 / 顶层键 / state+kind 枚举 / 长度上限，与服务端 storage._normalize_queue_task + routes.receive_queue 逐项对齐） | app/src/main/java/com/notifyme/android/QueueStateCore.kt、server/storage.py、server/routes.py |
|  | E-9 | 已修复并有测试 | python -m pytest server/ -q → 513 passed（server/test_queue.py 46 条 + server/test_console_queue.py 22 条 + server/test_console_classify.py 82 条 + server/test_queue_contract.py 9 条）；android 单测 611 条（新增 QueueStateCoreTest.kt 23 条）全绿；浏览器实跑：5 个 Tab、badge-queue=3、排队页 5 张卡分组、筛选 chip 生效、errs 为空 | 排队通道（POST /queue 上报 + GET /queue 查询，queue_tasks 表保留 7 天，stale = 30 分钟未更新）；端侧 QueueStateCore.kt / QueueStateCollector.kt（device_id 用本地随机种子 FNV-1a 派生，不碰 ANDROID_ID/IMEI）+ SyncWorker.pushQueue（best-effort，只认 2xx，不阻塞消息同步） |
|  | E-11 | 已修复并有测试 | 通知形状口径**按真机 dump 校准**（2026-11-12，Xiaomi 25098PN5AC / Android 17）：微信通知实测**不带 MessagingStyle**、正文是 `[11条]发言人: 正文` 纯文本形态 → 旧代码把它全判成私聊、sender 写成会话名，且未读数一变（去重键含正文原文）就多存一条重复（真机 1798 行里量化出 **78 组重复 / 多出 271 行**）。修法：抽出 `NotificationTextCore`（剥未读前缀 + 切发言人 + 驳可疑前缀 + 判群聊）供三个解析器共用，`WeChatNotificationParser` 不再写死 `isGroup = false`，`GenericNotificationParser` 只剥前缀不猜发言人。`NotificationShapeEvidenceTest` 扩到 17 条 + `NotificationTextCoreTest` 19 条固化口径 | app/src/main/java/com/notifyme/android/NotificationTextCore.kt、AppSourceRegistry.kt、app/src/test/java/com/notifyme/android/NotificationTextCoreTest.kt、NotificationShapeEvidenceTest.kt、docs/通知形状取证记录.md、docs/ROADMAP.md |
|  | E-12 | 已修复并有测试 | 提醒三级降级链的决策抽成纯函数：`ReminderChainCore`（106 行）用 `Env(hasCalendarPermission, hasWritableCalendar, eventAlreadyExists, inForeground, canOpenCalendar)` + `calendarAction` / `shouldTryIntent` / `decide` 三个纯判定取代原先埋在 `CalendarHelper.runReminderChain` 里的 if 嵌套；`CalendarHelper` 退化成「采集事实 → 执行副作用 → Outcome 翻回 ChainResult」。新增 `ReminderChainCoreTest.kt` 13 条（L1 成功/插入失败降级/无权限/无可写账户/L2 必须前台/后台直接 L3/L3 成功/三级全失败/日历已有同事件不越级/终态文案能被旧记录推断认出），app/src/test 70 个 .kt、android 单测 1069 条全绿 | app/src/main/java/com/notifyme/android/ReminderChainCore.kt、CalendarHelper.kt、app/src/test/java/com/notifyme/android/ReminderChainCoreTest.kt、docs/ROADMAP.md:159 |
|  | E-13 | 已修复并有测试 | 评测链路加时间预算：`S1EvalBudgetCore`（纯函数）给出单次调用上限（默认 90s）与整轮预算（默认 15min），到期停止发起新调用、剩余样本写进报告「预算耗尽未测试」；`S1EvalHttpTest` 改为按剩余预算动态设 connect/readTimeout，并允许预算耗尽时一侧为空（只判「跑不通」，不判「没跑完」）。`S1EvalBudgetCoreTest` 14 条覆盖配置容错 / 夹边界 / 剩余预算 / 耗尽判定；复现 2026-11-12 事故：端侧端点 TCP 通但 chat 不回，旧实现 readTimeout=180s × 40 次调用把整轮 `testOpenDebugUnitTest` 挂死 40 分钟（jstack 定位到 `S1EvalHttpTest.chat` 卡在 `Net.poll`） | app/src/main/java/com/notifyme/android/S1EvalBudgetCore.kt、app/src/test/java/com/notifyme/android/S1EvalBudgetCoreTest.kt、app/src/test/java/com/notifyme/android/S1EvalHttpTest.kt、docs/评测-云端vs端侧S1.md:102-130、AGENTS.md §1 |
|  | E-14 | 已修复并有测试 | 版本信息可查：新增 `VersionInfoCore`（95 行，纯函数）统一版本行 / 渠道 / 构建 / 应用 ID / 架构 / 系统 / 设备与复制文本；控制台新增「版本信息」卡（`btnCopyVersion` 复制到剪贴板）、主界面菜单新增「版本信息」弹窗（`AlertDialog` + 复制按钮）；14 条单测覆盖空值条目、渠道大小写、beta 后缀不重复拼。证据：`app/src/main/java/com/notifyme/android/VersionInfoCore.kt`、`app/src/test/java/com/notifyme/android/VersionInfoCoreTest.kt`、`app/src/main/java/com/notifyme/android/ConsoleActivity.kt`（setupVersionSection）、`app/src/main/java/com/notifyme/android/MainActivity.kt`（showVersionInfo）、`app/src/main/res/layout/activity_console.xml` |
|  | E-15 | 已修复并有测试 | 历史重复消息可一键归并：2026-11-12 真机量化出 `messages.jsonl` 里 78 组 / 271 行是「同一条通知被多次捕获」，口径修正只影响新消息，历史行不会自动修正（`normalizeSchema` 只补 pkg）。新增 `MessageDedupeCore`（174 行，纯函数）给出保守归并计划：同 App + 同会话 + 同发送者 + 剥掉未读前缀后正文完全相同，**且组内至少一行带 `[N条]` 前缀**才归并（全裸正文的「好的」「好的」可能是用户真连发，绝不删），保留每组最早一条；老行 `sender == conversation`（旧解析器没切发言人）先按 `NotificationTextCore.splitSender` 还原真实发送者再入键，否则老行与新行永远对不上。`MessageStore` 增 `repeatedPlan(context)`（只读扫描）/ `dedupeRepeated(context)`（`removed == 0` 时不碰文件、不发变更通知）；主界面菜单新增「归并重复消息」，确认框先摊开预计删除量与前 3 组样例。`MessageDedupeCoreTest` 17 条（含跨 App 不归并、不同发送者不归并、损坏行原位保留、二次调用幂等、无重复不碰文件）。docs/通知形状取证记录.md 新增 §4.3 | app/src/main/java/com/notifyme/android/MessageDedupeCore.kt、MessageStore.kt、MainActivity.kt、app/src/test/java/com/notifyme/android/MessageDedupeCoreTest.kt、docs/通知形状取证记录.md:159-176、docs/ROADMAP.md |
|  | E-16 | 已修复并有测试 | 首页「按来源 App」筛选 chips：PWA 早就有 `app-chips`（`server/console_ui.py` 的 `appTabList()` / `setAppFilter()`），安卓端此前只能去「通知来源」管理页勾启用。新增 `AppFilterCore`（88 行，纯函数）定义 chip 口径：① 首项恒为「全部」（空串，与 PWA `APP_FILTER = ''` 同口径）；② 只列窗口内**确实出现过**的来源（`MessageStore.readRecent(500)` 口径），不列注册表全集——给一个点了必然空白的 chip 比不给更差；③ 顺序对齐 PWA：全部 → 微信 → 其余按展示名码点升序（中文码点所以钉钉 U+9489 在飞书 U+98DE 之前）；④ `resolve()` 把失效选中项回落「全部」，避免清库/停用/换机后列表整体空掉且无从点回。`AppFilterStore` 单值持久化（`app_filter` / `selected_pkg`）。接线：`MainActivity.buildFeed(query, filter)` 产出 `chips` + `effectiveFilter`，会话过滤与首页待办 `todoVisibleKeys` 同步按 App 过滤，`applyFeed` 落盘并渲染 chip（`bg_chip_selected` / `bg_chip`），空态文案三态（搜索无结果 / 该来源下无会话 / 无消息）。16 条单测覆盖排序稳定性、未收录包名回落、空包名不出 chip、选中项消失回落、精确匹配不做前缀模糊、持久化归一。 | app/src/main/java/com/notifyme/android/AppFilterCore.kt、app/src/test/java/com/notifyme/android/AppFilterCoreTest.kt、app/src/main/java/com/notifyme/android/MainActivity.kt、app/src/main/res/layout/activity_main.xml、app/src/main/res/drawable/bg_chip_selected.xml |
|  | E-17 | 已修复并有测试 | 分析范围筛选（M12）：此前只有「关注名单 + 标签 + caseId 去重」三道门禁，用户没法说「只看今天」「只看这两个会话」，也没法在分析页看 `TAG_ON_DEMAND` 轮次进度。新增 `AnalysisScopeCore`（286 行，public object 纯函数 + 30 条单测）落四条口径：`range()` / `customRange()` 算周期区间（近 N 天含今天、自定义整天对齐）、`Scope.explicit` + `acceptsConv` 做对象多选、`forced()` / `skipDedup()` / `emptyResultMessage()` 决定显式范围跳名单与去重、`encodeConvIds` 用 JSONArray 序列化会话 id。接线：`AnalysisScheduler.enqueueAnalysisNow(scope=)` 只在非「不限」时把 scope 写进 inputData（非自定义只带 period，避免 20 点入队的「今天」到第二天才跑时钉死在昨天）；`AnalysisWorker` 在分组后按 `acceptsConv` 过滤、按 `range` 砍消息后再 `AnalysisCase.fromMessages`；设置页新增「分析范围」块（周期 spinner + 自定义双日期框 + 会话多选弹窗 + 摘要）；`AnalysisListActivity` 订阅 `AnalysisProgressBus` 显示轮次进度条并在轮次结束自动刷列表。验证：`docs/scripts/local_check.ps1` 六项全 PASS，android 单测 1069 条（70 个 .kt）| `app/src/main/java/com/notifyme/android/AnalysisScopeCore.kt`、`AnalysisScheduler.kt`、`AnalysisWorker.kt`、`AnalysisSettingsActivity.kt`、`AnalysisListActivity.kt`、`app/src/main/res/layout/activity_analysis_settings.xml`、`activity_analysis_list.xml`、`app/src/test/java/com/notifyme/android/AnalysisScopeCoreTest.kt`、`docs/ROADMAP.md:372` |
|  | E-18 | 已修复并有测试 | 姓名别名的「无盐哈希」升级为「设备随机盐 + SHA-256」：M3 初版别名是 `sha256(名字)` 前 6 位，拿到候选名单（同事名单 / 班级名单 / 通讯录）的人可逐个哈希比对，把 `[人名3f2a1c]` 反解成真人——别名形同虚设，PRIVACY.md 当时自己写明「真正强匿名未实现」。本轮新增 `AliasSaltCore`（143 行，纯函数 16 条单测）：摘要输入改为 `sha256(盐 ‖ 0x00 ‖ 名字)`（0x00 分隔符消除 `(salt="c", name="ab")` 与 `(salt="bc", name="a")` 的拼接歧义），盐是本机 `SecureRandom` 32 字节、只存 `privacy_config` / `alias_salt`、不入库不上报不进日志不导出；`decode` 对非 64 字符或非 hex 返回 null 而不抛（损坏即重生成）；`ensure()` 复用已存盐时不重复落盘。`NameAliases` 由 `object` 改为 `class NameAliases(salt)`，`redactCase` / `redactMessages` 增加必填 `salt` 参数（此前这两个窗口级函数零单测覆盖，本轮补 6 条）。四个调用点全部接线（`AnalysisWorker` 云端 S1/S2、`SyncWorker` 消息通道、`SyncWorker` 顾问报告、`RedactorPreviewActivity` 预览）；隐私设置页新增「别名盐」按钮（未生成 / 已生成 → 二次确认重置，Toast 说明历史存档不变）。**盐为空时逐字节退化为 M3 初版的无盐 sha256**，历史别名不失效（单测用硬向量钉死：无盐 张三=`[人名1d841b]`、盐 0x00..0x1f 时 张三=`[人名baf6b4]`）。验证：`docs/scripts/local_check.ps1` 六项全 PASS，android 单测 1069 条（70 个 .kt，含 AliasSaltCoreTest 16 条 + RedactorCoreTest 34 条） | `app/src/main/java/com/notifyme/android/AliasSaltCore.kt`、`RedactorCore.kt`、`PrivacyConfig.kt`、`AnalysisWorker.kt`、`SyncWorker.kt`、`RedactorPreviewActivity.kt`、`PrivacySettingsActivity.kt`、`app/src/main/res/layout/activity_privacy_settings.xml`、`app/src/main/res/values/strings.xml`、`app/src/test/java/com/notifyme/android/AliasSaltCoreTest.kt`、`RedactorCoreTest.kt`、`PRIVACY.md`、`docs/ROADMAP.md` |
|  | E-19 | 已修复并有测试 | 顾问通道的姓名别名映射（PR #24 留白项）：`AdvisorWorker.buildDigest` 与 `SyncWorker.pushAdvisor` 此前只走七类规则脱敏，`RedactorCore` 的 `nameMap` 恒为空（顾问报告侧显式传 `emptyMap()`），姓名规则永不命中 —— 原因是 `AnalysisCaseRecord` 里既没有 `isGroup` 也没有窗口发送者名单，拿不到 `NameAliases.nameMapFor` 需要的三元组。本轮新增 `AdvisorDigestCore`（179 行，纯函数 36 条单测）：`windows()` 从最近消息窗口（`MessageStore.readRecent(500)`）反推每个会话的 `(conversation, isGroup, senders)`（按 `(pkg, conversation.trim())` 聚合、群聊取「任一条标为群聊」、发送者去重保序），`nameMapFor()` 按会话建映射并把记录侧带空白的写法也映射一份（同实体必须同别名），`mergedNameMap()` 把窗口内全部会话合并成一张表（冲突先到先得、按会话键排序保证可复现）。接线：`AdvisorWorker.buildDigest` 退化为「采集事实 → 调内核」（常量 `MAX_CASES_IN_PROMPT` / `SUMMARY_TRUNCATE` 挪进内核，Worker 不再各留一份），`SyncWorker.pushAdvisor` 由 `emptyMap()` 改成 `mergedNameMap()`；盐只在 `privacy.cloudRedact` 时才取（`alias_salt` 的「首次需要脱敏才生成」语义不破）。摘要正文逐字保持旧实现（首行 / 字段顺序 / 摘要截 30 字 / 会话名截 20 字 / 超限省略行），顾问模型输入分布不变。下限写进 PRIVACY.md：窗口里一条消息都没有的会话只能退回七类规则。验证：`docs/scripts/local_check.ps1` 六项全 PASS，android 单测 1069 条（70 个 .kt，含 AdvisorDigestCoreTest 36 条） | `app/src/main/java/com/notifyme/android/AdvisorDigestCore.kt`、`AdvisorWorker.kt`、`SyncWorker.kt`、`app/src/test/java/com/notifyme/android/AdvisorDigestCoreTest.kt`、`docs/ROADMAP.md`、`PRIVACY.md` |
|  | E-20 | 已修复并有测试 | 首页搜索只认字面量（feat/home-search-core）：`MainActivity.buildFeed` 的搜索是三处 inline `contains` —— 会话名 / 发送者 / 正文。后果：用户搜「方案」，待办里明明有一条 `todoText` 含「方案」，整个会话组却消失（分析结论不参与匹配）；搜「微信」也搜不到任何东西（来源 App 名不在匹配面）；PWA 那边早有 `analysisSearchText()` 覆盖分析文本，端上却没有，口径分裂。本轮抽出 `SearchCore`（137 行，internal object 纯函数 + 44 条单测）落四类命中：会话名 ∪ 来源 App 名 ∪ 消息（发送者/正文）∪ 该会话**全部历史**分析结论。`caseParts()` 是分析结论字段清单的唯一出处（`todoText` / `todoDue` / `s2Summary` / `s2DueTime` / `s2SuggestedAction` / `s1Topic` / `s1DueWindow` / `displayTodo()` / `displayDue()` / 每个 `s2Tasks` 的 who+what+when），并**排除 `none` 码值**（`due_window` 的「无截止」码值留着会让搜 "none" 命中全部）与 `caseId` / `rawJson` / 脱敏占位符；码值先经 `labelOf` 翻成中文标签再匹配，所以搜「本周内」「通知公告」都能命中分析结论。`hitKind()` 只回码值（优先级 ANALYSIS > APP > MESSAGE > CONVERSATION），文案与本地化留在 Activity（`searchLabel`），Core 不碰资源表。搜索态下的折叠判定统一走 `forcedExpand(query, hit)`：会话级与日期级命中都会强制展开，修掉原来「搜索命中正文但日期分组仍折叠」的不一致。接线后 `buildFeed` 把 `casesByConv`（按 `convKey` 分组全量 case）提前，搜索用历史 case 而不是只看最新一条。会话头新增 `tvHitHint` 提示行（⚡ 命中分析结论 / 命中来源 App / 命中消息内容），`FeedItem.Header.hitHint` 是内容字段，换搜索词会经 DiffUtil 重绑。验证：`docs/scripts/local_check.ps1` 六项全 PASS，android 单测 1069 条（70 个 .kt，含 SearchCoreTest 44 条） | `app/src/main/java/com/notifyme/android/SearchCore.kt`、`MainActivity.kt`、`app/src/main/res/layout/item_conversation_header.xml`、`app/src/main/res/values/strings.xml`、`app/src/test/java/com/notifyme/android/SearchCoreTest.kt`、`docs/ROADMAP.md` |
|  | E-21 | 已修复并有测试 | topic / due_window 码值→中文的映射有四处重复实现（`AnalysisListActivity.localizeTopic` / `localizeDueWindow`、`ConversationActivity` 同名两函数、`MainActivity.searchLabel`、Adapter 内 `topicLabel`），三套不同空值策略（原样 / 回落 notice·none / 原样），且只有两处认中文档名（模型若输出「今天」就原样回显）；预筛结案写的 `s1Topic = "filtered"` 是第六种取值，四处 when 全落 else，用户在卡片上看到英文 `filtered`。更严重的是代码泄漏：`MainActivity` 消息卡用 `dueText != "none"`（拿码值比中文串）所以永远不相等，未编辑过的待办卡一律渲染成「· 截止：none」；首页「最近待办」与待办编辑框回填直接把 `today` / `none` 写给用户，修改后会当成文本存进 `todoDue`。本轮抽出 `TopicLabelCore`（200 行，internal object 纯函数）：`normalizeTopic` / `normalizeDue` 双向归一（英文码值 + 中文档名，小写不敏感）+ `topicRes` / `dueRes` 回资源 id，空值回落 `topic_notice` / `due_none`、未知值原样显示（不静默归入默认桶）、`filtered` 单独映射为新字符串 `topic_filtered`=「已预筛过滤」；`displayDueCode` / `hasDisplayDue` / `displayDueLabel` / `editDueLabel` 统一「该显示哪一份截止」的优先序（todoDue → s2DueTime → s1DueWindow，与 `AnalysisStore.displayDue()` 同序），`hasDisplayDue` 把 `none` / 空串一律归为「不显示」，四处重复实现全部删除。**提醒链刻意不动**：`runTodoReminderChain` 与 `AnalysisWorker` 仍把原始码值喂给 `CalendarHelper.parseDueTime`，因为「今天」会被解析成今天 09:00（下午分析时事件落进过去，日历永不触发），保持原状就是「解析不出 → baseTime + 1h」既有分支。验证：`TopicLabelCoreTest` 33 条（含 normalize 幂等、中英文双向、命名空间不串、`hasDisplayDue` 与 `isNoDue(displayDueCode)` 一致性），`docs/scripts/local_check.ps1` 六项全 PASS，android 单测 1069 条（70 个 .kt） | `app/src/main/java/com/notifyme/android/TopicLabelCore.kt`、`app/src/test/java/com/notifyme/android/TopicLabelCoreTest.kt` |
|  | E-22 | 已修复并有测试 | 「已创建的提醒」（`ReminderListActivity`）只按 `created_at` 倒序平铺：需要用户动手的条目（`failed` / `intent` 待保存）沉底、已过期与未到期混排、没有任何筛选（积到几十条后找不到失败项）；而且过期判定堆在 `Retention.kt` 的 `ReminderRetentionCore.stillPending` 里，UI 侧没有可复用口径。现在分组 / 排序 / 筛选 / 计数全部抽成纯函数 `ReminderListCore`（`normalizeFilter` / `isAttention` / `isExpired` / `groupOf` / `arrange` / `keep` / `counts` / `emptyFilter`）：三组「需要处理 → 生效中 → 已过期」（`failed` 排在 `intent` 之前），组内按 `event_time`（attention/active 升序、expired 降序、无时间垫底），顶部四个 chip 带条数并将选中态存 `onSaveInstanceState`；筛选到某一组时不再插分组标题（整页同组时标题是噪音），空态文案区分「一条记录都没有」与「本组没有」。**两条保守语义**：`event_time <= 0` 不算过期（否则用户会删掉可能有效的记录）、`failed` 永不算过期（语义是「等你重试」）。排序键一次算完再比（不在比较器里反复调 `groupOf`），且不拿 `created_at` 兜底——只要比到 `event_time`，JVM 稳定排序就保证完全同键时新建的在上（`ReminderStore.readAll` 已是 `created_at` 倒序）。验证：`ReminderListCoreTest` 35 条（含 `keep` 的 `assertSame` 不拷贝、四类混合组顺序与组内方向、`emptyFilter` 四分支），`docs/scripts/local_check.ps1` 六项全 PASS，android 单测 1069 条（70 个 .kt） | `app/src/main/java/com/notifyme/android/ReminderListCore.kt`、`app/src/test/java/com/notifyme/android/ReminderListCoreTest.kt`、`app/src/main/java/com/notifyme/android/ReminderListActivity.kt`、`app/src/main/res/layout/item_reminder_group.xml` |
|  | E-23 | 已修复并有测试 | 「这个会话该不该出现」的三段式判定被抄了四遍且抄漏一处：`MainActivity.buildFeed` 的 `enabledGroups`（`it.first.id !in unwatchedIds` + `!AppSourceRegistry.isBlocked(pkg)` + `AppSourceStore.isEnabled`）、同函数的 `todoVisibleKeys`（同三段 + `AppFilterCore.matches`）、`OverlayService.showPanel` 的 `visibleKeys`（同三段，**没有首页来源筛选**）、`AnalysisWorker` 的分析候选门禁（同三段 + `forced ||`）。后果：首页 chip 筛到「飞书」后点悬浮球，待办面板里仍列着微信条目——同一个「当前在看哪个来源」的状态，两处一个认一个不认（真机云手机 127.0.0.1:16384 可复现）。而且第四处（分析门禁）与前三处的差异是刻意的（点名会话要跳过启用集合），四份拷贝各自演化下去迟早再分叉。本轮新增 `ConvVisibilityCore`（123 行，internal object 纯函数 + 28 条单测）落四层门槛：关注名单 → 自身/系统源 → 启用集合 → 首页来源筛选，`why()` 回拦截原因码 `unwatched` / `blocked` / `disabled` / `filtered`、`isVisible()` 是布尔形态、`visibleKeys()` 给首页「最近待办」与悬浮面板共用（`LinkedHashSet` 去重保序，另有 `Sequence` 重载）。三处边界写进类注释：① `forced` **只放宽「启用集合」一层**，自身/系统源任何情况都拦（force 分支绕过 `isEnabled`，而 `isEnabled` 内部虽然也判过 `isBlocked`，但 forced 时那条判不到了——所以 Core 里这一行 must-keep）；② 分析门禁传 `AppFilterCore.ALL`（首页筛选只影响展示，不能让「首页正在看飞书」把其它来源的周期分析停掉）；③ 分析链的关注名单**故意传空集**（该门禁在 `ConvTagCore.shouldAnalyze`，且 `forced && !quiet` 放行，若这里也按 unwatched 拦会把「立即分析本会话」一起拦掉、并把 `skippedUnwatched` 计数口径拆到两处）。接线：`MainActivity` 两处、`OverlayService`（并补上 `AppFilterCore.resolve` 的回落，可选项用前三层门槛后的 `baseKeys` 而不是 `windowKeys`，否则停用来源会让筛选卡在那里整页空白）、`AnalysisWorker` 门禁。编译 `compileOpenDebugKotlin` 通过，全量单测 1069 条 / 70 个 .kt 全绿（基线 910 / 63）|
|  | E-24 | 已修复并有测试 | 会话详情页的消息窗口是跨会话的：`ConversationActivity.renderContent` 写的是 `MessageStore.readRecent(this, 500).filter { it.convKey == convKey }.sortedBy { it.timestamp }` —— `readRecent` 先取全库最近 500 条（跨所有会话），再挑本会话，于是本会话能看到多少历史取决于别的会话有多活跃；极端情况下（其它会话刷满 500 条）本会话只剩几条甚至空白，而 `files/messages.jsonl` 里数据完好。同一个窗口还被当成 `R.string.chat_truncated`（「仅显示最近 %1$d 条（共 %2$d 条）」）的 `%2$d` 分母，于是「共 N 条」报的是「窗口内命中数」，比真实总数小。修法：新增 `ConvWindowCore`（125 行，25 条单测）把窗口选择抽成纯函数 —— `requestLines(limit)` 为 8 倍（默认 4000 / 硬上限 50000，避免行数给爆后 `readTailLines` 向文件头扩容直到退化成全量读）；`slice()` 先按 `convKey` 过滤再排序（升序、`ts <= 0` 归 0 排最前、超 limit 保最近）；`reachedHead(linesRead, requested)` 判是否已读到文件头（行数打满一律保守当「没读全」）；`preview()` 回 `visible / truncated / countedAll`。`MessageStore.readWindow(context, key)` 连新链路（因返回 `internal` 类型而必须 `internal`），详情页 `renderChat` 改收 `Window`，截断提示分三档：可信且截断 → 原文案、不可信且截断 → `chat_truncated_window`（「…（本条会话在窗口内共 N 条）」）、窗口没读全 → `chat_window_partial`（「窗口内 N 条，更早的消息不在当前读取范围内」）。`MAX_CHAT_RENDER = 200` 从 `ConversationActivity` 迁入 `ConvWindowCore.MAX_RENDER`。单测顺带覆盖最关键的回归：别的会话 600 条噪声 + 本会话 5 条，`readWindow` 仍返回 5 条完整历史（旧实现该场景一条都读不到）。
E-25 | 已修复并有测试 | 上一轮 `ConvWindowCore` 只修了会话详情页，另外两处「跨会话窗口里挑本会话一条消息」的同源写法还留着：① `MainActivity.runTodoReminderChain`（app/src/main/java/com/notifyme/android/MainActivity.kt:1173）`MessageStore.readRecent(this, 500).lastOrNull { it.convKey == rec.convKey }` —— 拿最近一条同会话消息给日历事件描述补 `发送者：` 与 `原文：`（CalendarHelper.createReminderResult 的 `sender` / `sourceText`），别的会话刷满 500 条窗口时 `last` 变 null，事件描述只剩「任务：…（会话名）」一行；② `A11yExtractService.runExtraction`（app/src/main/java/com/notifyme/android/A11yExtractService.kt:175-177）`readRecent(this, 500).firstOrNull { it.convKey == convKey }?.isGroup` —— 界面本身拿不到群标记，只能沿用已有记录；取不到时 `knownIsGroup` 为 null 走「按昵称回推」，但旧写法在窗口被别的会话占满时**首次提取群聊会取不到历史**、白白丢一次回推机会（更糟的是若窗口里有本会话消息但都是更老的行，`firstOrNull` 拿到的是最新一条，语义没错但依赖窗口命中）。修法：在 `ConvWindowCore` 里加两个纯函数 `latest(window)`（= `window.messages.lastOrNull()`，因为 `slice()` 已按时间升序，末条即最新）与 `knownIsGroup(window)`（= `latest(window)?.isGroup`，**空窗口返回 null 而不是 false** —— null 是「没有历史可参考，按首次提取处理」，false 是「历史判定私聊」，混了就再也不会回推），两处调用点改成 `ConvWindowCore.latest(MessageStore.readWindow(this, rec.convKey))` 与 `ConvWindowCore.knownIsGroup(MessageStore.readWindow(this, convKey))`。`ConvWindowCore` 现 145 行 / 31 条单测（新增 6 条：latest 取最晚 / 空窗口 null / 未被 `ts<=0` 顶掉 / knownIsGroup 空窗口 null / 沿用最新一条的群聊标记 / 私聊历史回 false），全量 1069 条 / 70 个 .kt。
- **E-26 分析列表回看原文也栽在同一个跨会话窗口上（2026-11-13，feat/conv-source-map）**：
`AnalysisListActivity.buildSourceMap(records)` 原实现是 `MessageStore.readRecent(this, 500).groupBy { it.convKey }`，再逐条 `AnalysisCase.windowOf(byConv[record.convKey].orEmpty(), record.windowEnd, record.messageCount)` 还原「窗口原文」。这和 E-24（详情页）、E-25（`latest`/`knownIsGroup`）是同一类错误：用一份**跨会话**的最近 500 条去切**某一个会话**的内容。别的会话刷满 500 条，本会话那几条就不在窗口里，`tvAnalysisSource` 整行 GONE —— 用户看到「没有原文」，而数据其实完好躺在 `messages.jsonl` 里。真机复现：把 `messages.jsonl` 换成 600 条「乙闲聊群」噪声 + 4 条「甲项目群」，`AnalysisListActivity` 的 dump 里只有 tvAnalysisConv/tvAnalysisTime/tvAnalysisSender/tvAnalysisSummary/tvAnalysisDue/tvAnalysisText，**没有 tvAnalysisSource 节点**。
修法：`ConvWindowCore.sourceKeys(records)` 给出「要读窗口的会话键集合」（`LinkedHashSet` 去重保首次出现顺序，跳过 `conversation.isBlank()` 的老记录），`Association` 每个键各读一次 `MessageStore.readWindow(this, key)`；`ConvWindowCore.sourceMap(records, windows, nowMs)` 再逐条 `windowOf` + `formatWindowSource`。两条口径：① 窗口里取不满（case 的窗口比最近 `Window.limit` 条还早）返回空串、调用方隐藏该行 —— **宁可不显示，也不拿别的窗口内容冒充原文**；② `records` 为空直接返回空表，别为一次空列表去读文件。新增 7 条单测（含 600 条噪声 + 3 条本会话的回归：断言原文里含「我的3」且不含「噪声」），`ConvWindowCore` 现 187 行 / 38 条单测，全量 1069 条 / 70 个 .kt。
至此 `readRecent(500)` 的三处同源误用全部收掉（详情页窗口 / 日历描述与群聊标记 / 分析列表原文）；`OverlayService`（窗口键集合）、`ConsoleActivity`、`AdvisorWorker`（顾问复盘输入）等调用点确实是「本意就要全局最近 N 条」，不是同类问题。`AnalysisWorker` 的候选集合当时被我归进了这一档，后来真机数据推翻了它 —— 见 E-27。

### E-27 分析候选窗口的第四处同源隐患（2026-11-13，PR feat/candidate-window-core）

E-24/E-26 收掉 `readRecent(500)` 的三处误用后，我在收尾句里写着「`AnalysisWorker` 等仍是本意就要全局最近 N 条」，这句话后来被真机数据推翻了一半。

`AnalysisWorker.kt:251` 的候选集合是 `MessageStore.readRecent(applicationContext, 500).groupBy { it.convKey }`。它看起来是「全局最近 500 条」，但**下游只在乎每个会话自己那几条**：一个 case 只吃 `AnalysisCase.WINDOW_SIZE = 10` 条窗口（`fromMessages` 里 `sortedBy { timestamp }.takeLast(10)`），候选集合的作用是「哪些会话值得开一轮分析」。于是同一个病又出现一次：几个群刷满 500 条，安静但有待办的会话在候选里一条都没有，永远排不上分析 —— 数据在 `messages.jsonl` 里，界面也看得见，就是不分析。

修法与前三处思路一致但目标不同：前三处是「还原某个会话的内容」，必须按会话读窗口；这里只需要「每个会话留够用的几条」，所以口径抽成 `CandidateWindowCore`（69 行，`internal object`，纯函数）：

- `PER_CONV = 50`：5 倍 `AnalysisCase.WINDOW_SIZE`。一个 case 只要 10 条，剩下 40 条是给「范围是过去的自定义区间、比区间更新的消息先占掉保留名额」留的余量。上限不放大到尾部窗口量级，否则等于放弃「每会话只留一窗」的内存约束。
- `group(messages, perConv = PER_CONV)`：按 `convKey` 分桶（`LinkedHashMap` 保各会话首次出现顺序，与旧 `groupBy` 行为一致，后续 `sortedBy { windowEnd }` 是稳定排序，同输入同入选顺序）；组内按时间升序，`timestamp <= 0` 归 0 排最前（未被更晚的真实消息顶掉），超过 `perConv` 只保最近若干条；空输入或 `perConv <= 0` 返回空表（调用方传错时保守退化）。
- `candidateCount(groups)`：只是 `groups.size`，让日志里的「候选 X 个」与测试共用同一句话。

接线：`MessageStore.candidateGroups(context, perConv)` → `MessageStoreCore.candidateGroups()`（文件不存在返空表、`readTailLines(TAIL_LINES)` 尾部读 + `mapNotNull` 解析跳过损坏行 + `CandidateWindowCore.group`）；`MessageStore` 上那层必须是 `internal`，否则报 `public function exposes its internal return type`（与 `readWindow` 同一个坑）。`AnalysisWorker.runRound()` 只把 `readRecent(...)` 换成 `candidateGroups(...)`，下游流水线（forceKey → `scope.acceptsConv` → `ConvVisibilityCore.isVisible` → `ConvTagCore.shouldAnalyze` → `AnalysisScopeCore.keep` → 去重 → `sortedBy { windowEnd }` → `take(MAX_CONVERSATIONS_PER_ROUND = 10)`）一行未动。

`CandidateWindowCoreTest` 16 条：10 条打 `group`（空输入、`perConv` 非正、首次出现序、组内升序、`ts <= 0` 排最前、超限保最近、恰好等于不裁剪、同名跨 App 不合并、`candidateCount`、`PER_CONV > WINDOW_SIZE`），6 条走 `MessageStoreCore` 集成（文件不存在、跳过损坏行、**600 条噪声 + 本会话 5 条仍在候选**、**对照实验：同一份数据喂旧写法 `readRecent(500).groupBy` 确实拿不到本会话**、两会话各留 `perConv` 条互不挤占、保留余量扛住范围过滤——只留 10 条时区间内一条不剩、留 50 条时 30 条可用且 case 末条是「区间内39」）。最后两条是这次改动的价值证明：没有它们，后人只会看到「窗口数字从 500 变成 50」，看不出为什么。

验证：`local_check.ps1` 六项全 PASS，全量 android 单测 **1069 条 / 70 个 .kt**（基线 976 / 65，+16）；lint 仍是刻意保留的三条（`ChromeOsAbiSupport` / `ObsoleteLintCustomCheck` / `OldTargetApi`），0 error。真机（云手机 127.0.0.1:16384）：灌 600 条「闲聊群」噪声 + 4 条「甲项目群」历史后，分析列表能给出本会话的 case 与原文回看。
E-28 分析记录（analysis.jsonl）侧取窗口口径统一（2026-11-13，PR feat/analysis-window-core）

E-24/E-25/E-26/E-27 收掉的全是「消息侧」的跨会话窗口误用（`MessageStore.readRecent(N)`）。这一轮把同一种错误在**分析记录侧**的三处一并收掉：

① `AnalysisListActivity.refreshList`（app/src/main/java/com/notifyme/android/AnalysisListActivity.kt:151）原来是 `AnalysisStore.readRecent(this, 200)`，从会话头进入（`filterKey != null`）后才 `filter { it.convKey == f }` —— 先在全库尾部 200 行里取、再挑本会话，别的会话把 `analysis.jsonl` 尾部占满时，本会话的 case 一条都读不到，页面显示「暂无分析结果」而记录仍在文件里。
② `MainActivity.buildFeed`（app/src/main/java/com/notifyme/android/MainActivity.kt:732）`AnalysisStore.readRecent(this, 200)` 之后 `groupBy { convKey }` 得 `latestCases`（首页「最近待办」与消息卡角标）与 `casesByConv`（搜索与日期天挂结果）—— 同源，安静会话的最新结论被挤出窗口。
③ `AnalysisStore.latestByConvKey`（app/src/main/java/com/notifyme/android/AnalysisStore.kt:388 旧实现）`readRecent(context, 500).groupBy { it.convKey }.mapValues { maxByOrNull }` —— 会话详情页轮询（`ConversationActivity.kt:150` 与 `:351`，每 4 秒）与悬浮面板（`OverlayService.kt:363`）都吃它，取不到就当作「还没有分析结论」；而且每次都全量重读尾巴 500 行、逐行 JSON 解析。

共同根因：`AnalysisStore.readRecent(context, limit)` 的实现是 `JsonlStore.readTailLines(limit)` —— **文件尾部 limit 行**，注释里自己写着「与 MessageStore 同口径」，却被当成「某会话的全部记录 / 每会话最新一条 / 全库列表」的来源。消息侧 `MessageStore` 已经有 `readWindow` / `candidateGroups` 兜住，分析侧一直缺这一层。

修法：新增 `AnalysisWindowCore`（app/src/main/java/com/notifyme/android/AnalysisWindowCore.kt，159 行，`internal object` 纯函数 + 23 条单测），把「取窗口 → 分组 → 取最新 / 取某会话 / 取全库最近」的口径收在一处：`group(records)` 用 `LinkedHashMap` 保首次出现序、组内 `sortedBy { it.windowEnd }` 升序（窗口推进顺序可直接当时间轴读）；`latest(groups)` 取每组末条（同 `windowEnd` 取最后写入那条 —— 与旧 `maxByOrNull` 取第一条不同，只在手工造数据/导入时可见）；`forConversation(records, key, limit)` 按 `analyzedAt` 倒序只回本会话（`limit < 0` 不限、`limit == 0` 空表）；`recent(records, limit)` 才是全库倒序。窗口量级分两档：`TAIL_LINES = 5_000`（列表 / 首页聚合）与 `POLL_LINES = 1_500`（详情页轮询 / 悬浮面板，旧 500 的 3 倍 —— 轮询每 4 秒一次、面板每次刷新一次，窗口给太小同样会让安静会话掉出去）。文件级读写放在同文件的 `internal class AnalysisWindowFile(store: JsonlStore)`（照 `AnalysisRejectFile` 的范式），`AnalysisStore.readGrouped` / `readRecentWindow` / `readForConversation` / `latestByConvKey` 四个入口全部改走它；`readRecent(context, limit)` 保留给「本意就是全局最近 N 条」的两处（`AdvisorWorker` 顾问摘要、`AnalysisSettingsActivity` 统计文案）。

两条已知局限写进类注释而不是假装没有：① **不按会话设条数上限** —— 与 `CandidateWindowCore.PER_CONV` 不同，这里聚合前无法知道有多少会话，内存由 `TAIL_LINES` 兜住（5 000 行解析后的对象量级可控）；② **窗口外的老 case 读不到** —— 某个会话的 case 数超过窗口里它自己那几条时会看不到（真实使用里 case 由窗口推进产生，一个会话很少攒到上千条；要更强公平性得上索引，不在本轮范围）。

`AnalysisWindowCoreTest` 23 条：5 条打 `group`（空输入 / 组内升序 / 同名跨 App 分开 / 首次出现序 / 两次分组结果一致）、4 条 `latest` 与 `latestByConvKey`（取 `windowEnd` 最大 / 空表 / 直接吃原始记录 / 同 `windowEnd` 取最后写入）、4 条 `forConversation`（只回本会话倒序 / 条数上限 / `limit = 0` 空表 / 负 `limit` 不限）、2 条 `recent`、6 条走 `AnalysisWindowFile` 的文件级集成（文件不存在返空表 / **600 条噪声 + 本会话 3 条：旧窗口一条都看不到、新窗口 3 条全在且 `latest` 是「我的3」** / `readAll` 跳过 `not json at all`、`{}`、空 `case_id` / `forConversation` 40 条不受全局条数限制 / `recent` 截断 / 组内升序）、1 条常量量级关系（`LIST_LIMIT < POLL_LINES < TAIL_LINES`）。第 2 条是这次改动的价值证明：没有它，后人只看到「200 变成 5000」，看不出区别。

接线：`MainActivity.kt:731` 改 `AnalysisStore.readGrouped(this)` + `AnalysisWindowCore.latest(...)`（`:754` 原先的第二处 `groupBy` 删掉，改注释说明已在上面算好）；`AnalysisListActivity.kt:150` 改 `if (filter != null) AnalysisStore.readForConversation(this, filter) else AnalysisStore.readRecentWindow(this, AnalysisWindowCore.LIST_LIMIT)`；`ConversationActivity` / `OverlayService` 的调用点不动，随 `latestByConvKey` 实现升级自动获益。

验证：`compileOpenDebugKotlin` 通过；全量 android 单测 **1069 条 / 70 个 .kt**（基线 992 / 66，+23）；lint 仍是刻意保留的三条（`ChromeOsAbiSupport` / `ObsoleteLintCustomCheck` / `OldTargetApi`），0 error。

**E-29（首页信息流取样：readRecent(500) 的第五处同源隐患）** 首页 `MainActivity.buildFeed` 的取样此前是 `MessageStore.readRecent(this, 500)` —— **跨所有会话**的最近 500 条再 `groupBy { it.convKey }`，于是「某个会话能不能出现在首页」取决于别的会话有多活跃：几个群刷满窗口，安静但有内容的会话在首页一条都不出现，首页「最近待办」区的可见集（`todoVisibleKeys` = `ConvVisibilityCore.visibleKeys(feed 键)`）随之看不到它，而数据完好躺在 `messages.jsonl` 里。这是同源隐患的第五处（前四处见 E-24/E-25/E-26 的详情页 / 日历补上下文 / 回看原文、E-27 的分析候选、E-28 的分析记录），本次口径抽成 `FeedWindowCore`（internal object，157 行，**22 条单测**）：`perConvFor(convCount, totalBudget = 6000, maxPerConv = 200, minPerConv = 20)` —— `totalBudget / convCount` 再夹进 `[minPerConv, maxPerConv]`，会话少给满上限、会话多按均值摊薄但不低于保底（`minPerConv = 2 × AnalysisCase.WINDOW_SIZE`，保证会话再多每会话仍能凑出一个完整分析窗口）；`group(messages, ...)` 用 `LinkedHashMap` 分组 → 会话间 `sortedByDescending { 该会话最近一条（ts <= 0 归 0） }`（稳定排序，首页顺序可复现）→ `take(maxConversations = 200)` → 组内 `sortedBy { ts <= 0 归 0 }` 升序、超限 `takeLast(keep)`；`Bucket.truncated` 的判定分两档：`complete`（行窗口已读到文件头）时 `ascending.size > keep`、否则 `ascending.size >= keep` —— 后者是「刚好顶到名额时也可能还有更早的没读进来」，宁可把条数写成下限。接线五处同口径：`MainActivity.buildFeed`（`MessageStore.feedGroups(this)` 直接给会话头/天组/消息；`todoVisibleKeys` 改用 `feed.buckets` 的键；新增 `FeedItem.Header.countAtLeast` + `R.string.msg_count_at_least`「%1$d+ 条」，会话被截断时条数是下限）、`OverlayService` 的窗口键、`ConsoleActivity.collapseTargets` 的折叠作用域、`WatchlistActivity` 的会话键收集、`AnalysisSettingsActivity.showScopeConvPicker` 的会话多选器。`MessageStoreCore.feedGroups(...)` 用 `readTailLines(FeedWindowCore.REQUEST_LINES = 20000)`（比 `TAIL_LINES = 5000` 大：安静会话本来就排在文件靠前的位置，行窗口太浅照样读不到），`complete = requested > 0 && lines.size < requested`；`object MessageStore` 侧 `internal fun feedGroups(context, ...)` 做转发（必须 `internal`，否则 public 函数暴露 internal 的 `FeedWindowCore` 类型）。真机实证（云手机 127.0.0.1:16384，`messages.jsonl` 604 行 = 乙闲聊群 600 条噪声 + 甲项目群 4 条更早的「A历史第 1–4 条」）：离线按两条实现逐行模拟，旧 `readRecent(500).groupBy` 的会话键只剩 `[乙闲聊群]`、甲项目群一条都没有；新 `feedGroups` 两个会话都在、甲项目群 4 条完整；`AnalysisWorker` 日志实测「本轮分析开始: 入选 2 个会话 (候选 2 个, 标签拦截 0 个, 未关注拦截 0 个, force=null, 范围=不限 · 全部会话)」（旧写法候选 1 个）。踩坑记档：① `MessageStore.kt` 里「清空全部记录」的注释出现两次（object 与 class 各一），按它当唯一锚点插 `feedGroups` 会把 core 实现插进 `object MessageStore`（那里没有 `store` 字段），编译报 Unresolved reference store + public function exposes its internal return type；插 class 必须用带 `fun clear() {` 的那一处。② 新增 `msg_count_at_least` 时若误插到已有 `msg_count` 那行旁边，`mergeOpenDebugResources` 报 Found item String/msg_count more than one time。③ 单测里 Kotlin 字符串模板 `$it` 后紧跟中文字符（`第$it条`）会被读成标识符 `it条`，必须写成 `${it}` 形式。验证：`compileOpenDebugKotlin` 通过；`FeedWindowCoreTest` 22 条全绿；全量 android 单测 **1069 条 / 70 个 .kt**（基线 1015 / 67，+22）；`local_check.ps1` 六项全 PASS（`CHECK_EXIT=0`，日志目录 `%TEMP%/notifyme-local-check-20261113-075033`），lint 仍是刻意保留的三条（`ChromeOsAbiSupport` / `ObsoleteLintCustomCheck` / `OldTargetApi`）、0 error。

**E-30｜顾问通道的窗口来源与首页同源（feat/advisor-window-source，2026-11-13）**

- 问题：`AdvisorWorker.buildDigest` 与 `SyncWorker.pushAdvisor` 的窗口级姓名映射都用 `MessageStore.readRecent(500)` 反推 `(conversation, isGroup, senders)` 三元组。这是**跨会话**的最近 500 条 —— 几个群刷满窗口后，安静会话在窗口里 0 条，`AdvisorDigestCore.nameMapFor()` 如实返回空表，该会话的会话名与正文人名**原样出设备**，而 `PRIVACY.md` 承诺的是窗口级别名（"窗口里还有该会话一条消息，姓名就会被换成稳定别名"）。这是 readRecent 同源隐患的**第六处**。
- 修法：新增 `AdvisorDigestCore.windowsOf(feed: FeedWindowCore.Result) = windows(feed.buckets.flatMap { it.messages })`；两条通道改为 `AdvisorDigestCore.windowsOf(MessageStore.feedGroups(context))`；删除 `DEFAULT_MESSAGE_WINDOW = 500`。窗口由此与首页 / 悬浮面板 / 关注名单同源（每会话各留一窗，`perConvFor` 按会话数摊薄 `[20, 200]`、`MAX_CONVERSATIONS = 200`）。
- 测试：新建 `AdvisorWindowSourceTest.kt` **8 条**（保全部会话 / 安静会话换别名 / **旧写法对照实验**（600 条噪声下 `readRecent(500)` 确实拿不到该会话，`mergedNameMap` 同样缺）/ 空库空表 / `mergedNameMap` 覆盖全部会话 / 摘要正文口径不变 / 会话数逼近上限时每会话仍在窗口）。全量单测 `1069 条` / `app/src/test` 69 个 `.kt`。
- 计数：`AdvisorDigestCore.kt` 179 → **196 行**；Core 内核 34 → **35 个**；主源码顶层 115 / laya 7 / 全树 122 不变。

**E-31（`readRecent` 同源隐患的第七批：分析记录侧 + 消息侧四处残余调用点）**

- 问题：`AnalysisWindowCore` / `FeedWindowCore` 落地后仍有四处按「全库最近 N 条」取数，同一个错误的第七批复现：① `AdvisorWorker` 顾问摘要 `AnalysisStore.readRecent(applicationContext, 500).filter { it.analyzedAt >= since }` —— 7 天内的 case 超过 500 条、或活跃会话占满尾部时，安静会话的 case 进不了报告，报告的条目数与别名覆盖同时失真（PRIVACY.md 承诺的是窗口级别名）；② 设置页统计 `AnalysisStore.readRecent(this, 500)` 数会话数 / 待办会话数，超 500 条直接是错数；③ 隐私页白名单候选 `MessageStore.readRecent(this, 1000)` —— 活跃会话刷满后安静会话连复选框都不出现，用户没法给它单独开关规则；④ 脱敏预览 `MessageStore.readRecent(this, 200).reversed()` —— 用户看不到安静会话的脱敏效果，无法核对规则是否生效。
- 修法：`AnalysisWindowCore` 增 `ADVISOR_LINES = 20000` 与 `since(records, cutoffMs)`（按 `analyzedAt` 升序取区间）；`AnalysisWindowFile.since(cutoffMs, lines)` / `AnalysisStore.readSince(context, cutoffMs)` 是文件级入口，`AdvisorWorker` 改用它并删掉 `.filter{}.sortedBy{}`。统计改 `AnalysisStore.readGrouped(this)`（`groups.keys.size`、`groups.count { it.second.any { r -> r.s1NeedAction } }`）。白名单改 `MessageStore.feedGroups(this).keys()`。预览改 `FeedWindowCore.sample(feedGroups, PREVIEW_LIMIT = 200)`。
- 第一版 `FeedWindowCore.recent(result, limit)` 是「全表最近 limit 条」，被单测当场证伪（600 条噪声下安静会话仍是 0 条，「先按会话取样再摊平」等于把旧行为又写了一遍）。最终改为 `sample()` 按会话轮流取（第 1 轮各取最新、第 2 轮各取次新……），同一会话内取时间尾部，最后整体升序；`timestamp <= 0` 归 0 排最前，并列时的先后由取出顺序决定（别断言成输入顺序）。
- 测试：`AnalysisWindowCoreTest` 23 → **27 条**、`FeedWindowCoreTest` 22 → **27 条**，全量单测 1045 → **1069 条** / `app/src/test` 69 个 `.kt`（不变）；`local_check.ps1` 六项全 PASS，lint 仍是刻意保留的三条。
- 计数：`AnalysisWindowCore.kt` 159 → **190 行**、`FeedWindowCore.kt` 157 → **191 行**；主源码顶层 115 / laya 7 / 全树 122 不变。

---

**E-32｜JSONL 逐行打补丁收敛到 `JsonlPatchCore`（feat/jsonl-patch-core，2026-11-13）**

- 问题：`AnalysisRejectFile`（`AnalysisRejectCore.kt`）与 `AdvisorRejectFile`（`AdvisorRejectCore.kt`）各有一份约 90 行的「逐行读 JSONL → 按 key 判定命中 → 打补丁 → 原子重写」，除 schema 与 newly 容器类型（`LinkedHashMap<String, Long>` vs `LinkedHashSet<String>`）外几乎逐行相同。两份实现的纠错只能靠人肉同步：一份把损坏行处理改对了，另一份不会跟着改。
- 修法：新增 `app/src/main/java/com/notifyme/android/JsonlPatchCore.kt`（114 行，`internal object`，纯函数）—— `patch(file, target, keyOf, parse, encode, patch): Boolean` 与 `collect(file, keyOf, parse, include): Set<String>`。`target == null` 表示扫全文件（顾问通道 `reviveExpired` 复活过期隔离需要），非 null 时只碰命中 key 的行；同一 key 多行则每行都打补丁。两个类只留四个 lambda（`keyOf` / `parse` / `encode` / `patch`），`patchRecords` / `patchReports` 各退化成一行委托。
- 五条被固化的语义（都在新单测里）：① 空行原位保留、不参与解析；② 解析抛异常 / 返回 null / 序列化抛异常（`AnalysisCaseRecord` 缺 `s1` 时 `importance` 为 NaN，`JSONObject.put` 会抛）三种损坏行一律原位保留，其它行照常改写、整轮不判失败；③ `updated == record`（data class 结构相等）与零命中都返回 false 且**不碰文件**（无谓的 `atomicWrite` 会搅动 mtime，`AnalysisStore` / `AdvisorStore` 的读缓存与 watch 链跟着抖）；④ `collect()` 只读、`LinkedHashSet` 按文件顺序去重（隔离集合遍历顺序可复现）；⑤ 重写走 `JsonlStore.atomicWrite`，不留临时文件。
- 为何不合并 `PendingRejectCore`：它是丢弃语义（达上限即 dropped，没有 quarantined / revive），`Decision` 只有 `dropped`，强行合并会逼出恒 false 的 `include` 与无意义参数；三兄弟里只有 Analysis / Advisor 同构。
- 测试：`app/src/test/java/com/notifyme/android/JsonlPatchCoreTest.kt` **15 条**，刻意用与业务无关的假记录 `data class Row(id, value, quarantined)` 与 `id|value|q` 文本格式，证明内核与 schema 无关（不 import 任何业务 record）。踩坑：`File.list()` 返回 `Array<String>?`，元素已是 String，写 `list()!!.map { it.name }` 会报 `Unresolved reference "name"`，要用 `listFiles() ?: emptyArray()`。
- 行数与计数：`JsonlPatchCore.kt` 114 行；`AnalysisRejectCore.kt` 238 → **201 行**、`AdvisorRejectCore.kt` 213 → **183 行**（各减 37 / 30 行）；全量单测 1054 → **1069 条**、`app/src/test` 69 → **70 个 .kt**；主源码顶层 115 → **116 个 .kt**（全树 122 → 123）。`compileOpenDebugKotlin` 通过、`lintOpenDebug` 仍是刻意保留的三条 warning / 0 error、`local_check.ps1` 六项全 PASS。
