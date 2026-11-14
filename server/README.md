# 微信消息接收服务端

配合 notifyme Android 端使用：安卓端监听微信通知，定时把消息以 JSON 数组的形式 POST 到本服务；本服务负责存档（jsonl）、入库（SQLite）、去重，并提供查询接口和一个极简网页查看页。

## 目录结构

服务端已从单文件拆成 6 个模块 + 控制台前端：

| 文件 | 职责 |
|---|---|
| `app.py` | 启动入口与 Flask 壳：鉴权（`check_token`）、错误处理（404/413/500）、`/console` 与落地页、`create_app()` 工厂 |
| `util.py` | 纯函数工具层：数值归一、时间戳格式化、文本转义、来源包名映射 |
| `storage.py` | 存储层：SQLite 连接与建库、JSONL 存档与轮转、顾问存档读写、抽库后台任务 |
| `routes.py` | 数据面路由：`/weixin`、`/messages`、`/analysis`、`/advisor`、`/queue`、`/admin/extract`，由 `register(app)` 挂载 |
| `stats_api.py` | 全库聚合 `/stats`：数值列一律走安全取值（脏值归 0，不让单行脏数据把整页打成 500）；消息段（含 `by_pkg` / `by_source`）、分析段（含脱敏证据）、排队段（`devices` 台数 + `devices_active` 在跑台数 + `by_kind` 类型分布，**分布只数未过期行**，所以 `sum(by_kind) == total - stale`）；**分析段不接受 `pkg` 过滤**；会话数（`convs` / `todo_convs`）按 `(pkg, conversation)` 计，与 `/messages` 行和控制台会话卡同口径；带 `?pkg=` 时消息段的 `by_pkg` / `by_source` 也一并跟着过滤（同一段内分布之和恒等于 `total`） |
| `suggests.py` | 下一步建议 `/suggests/next`：6 条规则按 `priority` 降序取前 3，带 `why` / `how` / `evidence` / `jump`；`?pkg=` 是**真过滤**，只作用于 `todo` / `convs_missing` 两族规则的计数（排队按设备、顾问是全局报告，与来源无关，仍走全库口径） |
| `console_ui.py` | 内嵌 PWA 控制台前端（HTML/CSS/JS，无外部依赖） |
| `extract_common.py` | 两个抽取脚本的共享内核：结构化日志、原文 JSONL 存档与轮转、跨进程写锁、五列去重、分批提交、主库结构兜底；配置由 `activate(module)` 注入宿主模块，`_setting()` 每次调用时读取 |
| `android_db_extract.py` / `pc_db_extract.py` / `gen_icons.py` | 安卓 / PC 本地库解密提取与图标生成脚本 |
| `test_*.py` | pytest 用例（`python -m pytest server/` 全量跑） |

运行期配置（`WEIXIN_TOKEN`、数据目录、各项限额）只在 `app.py` 一处定义，其余模块在函数体内动态读取，因此测试里的 `monkeypatch` 立即生效。

## 快速开始

以下命令均在本目录（`server/`）下执行，使用本目录的 `.venv` 虚拟环境（首次使用请先创建，见根目录 [README.md](../README.md) §服务端部署）。

### 0. 一键启动（MuMu 模拟器场景，推荐）

双击运行本目录下的 `启动服务.bat`，它会依次完成三件事：

1. 设置访问令牌 `WEIXIN_TOKEN`（真实值在本目录的 `secrets.local.bat`，该文件已 gitignore 不入库；**缺这个文件时脚本会直接报错退出**，不再以无鉴权模式启动；改令牌就编辑它，**改了之后必须同步修改安卓 App 里的「访问令牌」并重新点「保存并启用定时上报」**，否则 App 上报会被 401 拒绝）；
2. `adb connect 127.0.0.1:16384` 连接 MuMu 模拟器，并执行 `adb reverse tcp:8000 tcp:8000`——这样模拟器里的 App 访问 `http://127.0.0.1:8000` 即直达本机服务端，无需关心局域网 IP 和防火墙；
3. 前台启动 Flask 服务端，在窗口里按 `Ctrl+C` 即可停止。

> 注意：`adb reverse` 配置存在 adbd 里，**模拟器重启后失效**，所以每次重启模拟器后都要重新运行一次本 bat（或至少手动执行 reverse 那一步）。

### 1. 安装依赖

```bash
.venv\Scripts\pip.exe install -r requirements.txt
```

（如默认源拉取失败，可换镜像：`-i https://pypi.tuna.tsinghua.edu.cn/simple`）

### 2. 启动服务

```bash
.venv\Scripts\python.exe app.py
```

绑定地址随令牌状态变化（与 `app.py` 的实际行为一致）：

| 条件 | 监听地址 | 说明 |
|---|---|---|
| 已设置 `WEIXIN_TOKEN` | `0.0.0.0:8000` | 局域网内可访问，靠 `X-Token` 鉴权 |
| 未设令牌 + `WEIXIN_ALLOW_NO_AUTH=1` | `127.0.0.1:8000` | 显式放行无鉴权，只听本机 |
| 未设令牌且未放行 | 启动即退出（退出码 1） | 数据接口一律返回 503 |

确需无鉴权又对外监听时，可再加 `WEIXIN_ALLOW_INSECURE_BIND=1`（启动时会打警告，同网段任何人都能读到消息正文）。

自定义端口（Windows cmd）：

```cmd
set PORT=9000 && .venv\Scripts\python.exe app.py
```

### 2.1 WSGI 部署（gunicorn / waitress-serve）

`app.py` 导出 `create_app()` 工厂：**WSGI 部署必须走这个入口**，它会做两件事——
幂等初始化数据库（`init_db()` 幂等，可重复调用），以及**令牌为空时直接拒绝启动**。

| 命令 | 说明 |
|---|---|
| `waitress-serve --port=8000 --call app:create_app` | Windows / waitress（推荐） |
| `waitress-serve --port=8000 "app:create_app()"` | 同上，函数式写法 |
| `gunicorn "app:create_app()" -b 0.0.0.0:8000` | Linux / gunicorn |

```bash
# 直接 import app 模块再跑（没有 create_app）——不再支持：
#   只会「import 不 init_db」，首个请求会 500（unable to open database file）
```

要点：

- 空令牌（未设 `WEIXIN_TOKEN` 且未设 `WEIXIN_ALLOW_NO_AUTH=1`）时 `create_app()` 抛 `RuntimeError`，进程启动即失败——不会「空令牌裸奔」；
- `create_app()` 已初始化数据库，模块级的 `app` 变量也在首次请求时惰性兜底初始化（`before_request`），两边都幂等；
- 令牌从环境变量读，WSGI 进程同样需要 `WEIXIN_TOKEN` 环境变量。

### 3. 设置访问令牌（强烈建议）

Windows cmd：

```cmd
set WEIXIN_TOKEN=你的复杂令牌 && .venv\Scripts\python.exe app.py
```

Git Bash：

```bash
WEIXIN_TOKEN=你的复杂令牌 .venv/Scripts/python.exe app.py
```

设置后，安卓端和所有 API 请求都必须带请求头 `X-Token: 你的复杂令牌`，否则返回 401。

## 公网域名入口（可选）

如需让模拟器/手机在局域网外也能上报，可自行用 frp 等隧道 + nginx/caddy 反代把本服务挂到公网域名：

- **对外地址示例**：`https://your-domain.example/mmonitor/`
- **App 服务器地址填**：`https://your-domain.example/mmonitor/weixin`
- **访问令牌**：放在本目录 `secrets.local.bat`（不入库），网页和所有接口请求都需带 `X-Token` 头

链路示例：模拟器/手机 → 公网 HTTPS → VPS nginx（443，路径前缀 `/mmonitor/`）→ VPS 本地 `127.0.0.1:23444` → 本机 frp 隧道 → `127.0.0.1:8000` Flask。

自行部署时需要保持三处配置同步：

1. 本机 `frpc/frpc.toml` 里的 `mmonitor` 隧道块（localPort 8000 → remotePort 23444），改后热加载；
2. VPS `/etc/frps/frps.toml` 的 `allowPorts` 白名单必须包含 `23444`，否则隧道加载报 "port not allowed"；
3. VPS nginx 站点配置里的 `location /mmonitor/` 反代块。

排查顺序：先看 Flask 是否在跑（`http://127.0.0.1:8000/messages` 带 token 应 200），再看隧道是否 running，最后看反代。

## 与 Android 端对接

1. 电脑和手机连**同一个局域网**。
2. 在电脑上运行 `ipconfig`，找到无线网卡/以太网的 `IPv4 地址`，例如 `192.168.1.100`。
3. 在安卓 App 的服务器地址里填：

   ```
   http://192.168.1.100:8000/weixin
   ```

4. 如果服务端设置了 `WEIXIN_TOKEN`，在 App 里同步填写相同的 token（App 会以 `X-Token` 头发送）。

### Windows 防火墙放行

首次启动时 Windows 可能弹出防火墙提示，勾选「专用网络」允许即可。如果没有弹窗或手机连不上，手动放行：

- 控制面板 → Windows Defender 防火墙 → 高级设置 → 入站规则 → 新建规则；
- 规则类型选「端口」→ TCP → 特定本地端口 `8000` → 允许连接 → 勾选「专用」→ 命名保存。

也可以用管理员 PowerShell 一条命令搞定：

```powershell
New-NetFirewallRule -DisplayName "WeixinServer 8000" -Direction Inbound -Protocol TCP -LocalPort 8000 -Action Allow -Profile Private
```

## 接口文档

> **鉴权说明**：所有接口（除 `/console` 外壳、`/console/manifest.json`、`/console/icons/*` 和 `/sw.js`）在设置了 `WEIXIN_TOKEN` 后都需要鉴权。支持两种传法：请求头 `X-Token: <令牌>`（API 调用推荐），或 URL query `?token=<令牌>`（仅兼容 iPhone Safari 这类不能带自定义头的浏览器，会在服务端日志里打 deprecation 警告——令牌进 URL 会落到代理访问日志 / 浏览器历史 / Referer）。token 即密码，不要分享给他人。

### POST /weixin —— 接收消息

**请求头**

| 头 | 说明 |
| --- | --- |
| `Content-Type: application/json` | 必需 |
| `X-Token: <令牌>` | 服务端设置了 `WEIXIN_TOKEN` 时必需 |

**请求体**：JSON 数组，每条消息：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `sender` | string | 发送者昵称 |
| `text` | string | 消息内容 |
| `timestamp` | number | 消息时间戳（毫秒） |
| `conversation` | string | 会话名（群名或联系人名） |
| `is_group` | boolean | 是否群聊 |

示例：

```bash
curl -X POST http://127.0.0.1:8000/weixin \
  -H "Content-Type: application/json" \
  -H "X-Token: 你的令牌" \
  -d "[{\"sender\":\"张三\",\"text\":\"在吗\",\"timestamp\":1735000000000,\"conversation\":\"张三\",\"is_group\":false}]"
```

**返回**（200，全部条目合格）：

```json
{"ok": true, "received": 1, "duplicated": 0, "invalid": 0}
```

- `received`：本次新入库条数
- `duplicated`：重复跳过条数（同一 `pkg + conversation + sender + 毫秒 timestamp + text` 已在库中，通常是手机端重试）
- `invalid`：缺字段或类型错误被跳过的条数

**返回**（207，部分条目被拒）：

```json
{"ok": false, "received": 1, "duplicated": 0, "invalid": 1,
 "rejected": [1], "error": "some items were rejected"}
```

- `rejected`：被拒条目在**请求数组里的下标**（0 起）
- 客户端据此只把「已入库 / 已重复」的条目从待发队列里摘掉，被拒条目留在队列里下轮重报

鉴权失败返回 401：`{"ok": false, "error": "unauthorized"}`；body 不是 JSON 数组返回 400。

### GET /messages —— 查询消息

**参数**（均为 query string，可选）：

| 参数 | 说明 |
| --- | --- |
| `pkg` | 来源 App 包名，精确匹配（如 `com.tencent.mm`）；缺省不按 App 过滤 |
| `conversation` | 会话名，**前缀匹配**（`项目组` 命中 `项目组`、`项目组A`，不命中 `老项目组`）；走 `(conversation, timestamp)` 索引，不做全表扫 |
| `conversation_match` | 传 `contains` 改成旧的包含匹配（`%x%`，`%` 与 `_` 按字面量转义）；默认前缀匹配 |
| `date` | `YYYY-MM-DD`，按消息 `timestamp` 过滤当天；解析失败返回 400 `date must be YYYY-MM-DD`，换算后超出时间戳范围返回 400 `date out of range`（`1970-01-01` 之前 / `9999-12-31` 等边界不再抛 500；空串等于不按日期过滤） |
| `limit` | 返回条数，默认 100，上限 1000 |
| `before` | 游标翻页：把上一页返回的 `next_before` 原样回传（形如 `<毫秒>_<rowid>`，也可只给毫秒）；毫秒与 rowid 任一段非法或越界都返回 400（`before out of range` / `before must be <ms> or <ms>_<rowid>`） |
| `tz` | 可选时区（`+08:00` / `+0800` / `+8`），按该时区解释 `date` 的当天边界；不传则用服务器本地时区（与旧行为一致） |
| `tz_offset` | 可选时区偏移（分钟，如 `480`），与 `tz` 二选一；非法值返回 400 |

示例：

```bash
curl "http://127.0.0.1:8000/messages?conversation=家庭&date=2025-12-24&limit=50" -H "X-Token: 你的令牌"
```

翻页：把响应里的 `next_before` 原样回传即可

```bash
curl "http://127.0.0.1:8000/messages?limit=100&before=1735000000000_42" -H "X-Token: 你的令牌"
```

**返回**（200）：

```json
{
  "ok": true,
  "count": 1,
  "messages": [
    {
      "sender": "张三",
      "text": "在吗",
      "timestamp": 1735000000000,
      "conversation": "张三",
      "is_group": false,
      "received_at": 1735000000123
    }
  ]
}
```

结果按 `timestamp` 倒序（同毫秒按 `rowid` 再排，翻页不丢条目）。`received_at` 为服务端接收时间（毫秒），`source` 标记来源（`android-notification` 通知上报 / `android-db` 数据库直读）。

**数值列的脏值守卫**：`timestamp` / `received_at` 声明成 INTEGER，但 SQLite 的类型亲和只是「建议」——
写不进整数时会被原样存成 TEXT（直连改库、老库迁移、第三方脚本都能造出来）。服务端把 `typeof(timestamp) IN ('integer','real')` 作为**常驻条件**（不是可选过滤）：
不满足的行整条不出现在结果里，正常行的返回格式与上面完全一样。实测该守卫不破坏 `idx_messages_ts`（仍是 SEARCH），
换成 `CAST(timestamp AS INTEGER) >= ?` 才会退化成全表 SCAN。

同理 `next_before` 的毫秒段永远是数字（脏行进不来，也就不会拼出 `abc_1` 这种客户端拿回来自动 400 的游标）。

返回里还有 `next_before`：**还有下一页时**给游标字符串，**没有下一页时为 `null`**（不要按「满页」判断）。

**`skipped`（脏行计数）**：响应里固定带 `skipped` —— 当前过滤范围内被 `typeof(timestamp)` 守卫挡掉的行数。
它不含游标段（游标是页边界，不是过滤条件），所以翻页时这个数不随页码变。
为什么必须有：这些行真实存在于库里，`/stats` 的 `messages.total` 照算；只给列表不给解释，用户看到「库存 3、列表 2」
只会以为分页漏数据。口径是 `count + skipped == 库存`（同一组过滤下），控制台的脏数据横幅就是读它。

**翻页时不要累加**：`skipped` 是「整段过滤范围内」的常量，与游标无关 —— 同一份脏行在每一页都会原样再报一次。控制台 `loadMore` 必须用 `Math.max` 取最大值（旧写法做加法，2 条脏行翻 3 页会显示「6 条被跳过」）。
无过滤条件时也照算（控制台首屏就是 `messages?limit=200`，那正是最需要解释的场景）。

### POST /analysis —— 接收分析结果（需 token）

接收 App 侧的会话级分析结果（JSON 数组），每条：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `case_id` | string | 分析用例 ID（**去重键，必需**） |
| `conversation` | string | 会话名（必需） |
| `window_end` | number | 分析窗口右端（毫秒） |
| `message_count` | number | 窗口内消息数 |
| `s1` | object | `{need_action:{value,prob}, importance, due_window, topic}` |
| `escalated` | boolean | 是否升级到 S2 深度分析 |
| `s2` | object/null | `{summary, due_time, suggested_action, tasks}` |
| `analyzed_at` | string | App 侧分析完成时间 |
| `protocol` | string | 协议版本 |
| `pkg` | string | 来源 App 包名，缺省按微信处理 |
| `forks` | array | fork 层轨迹（可选） |
| `filtered` | boolean | 是否被过滤 |
| `redacted` | boolean | 是否已脱敏 |
| `redaction_rules` | array | 命中的脱敏规则（脱敏证据，可选） |
| `redaction_hits` | array | 脱敏命中记录（可选） |
| `todo_text` | string | 用户手动改写后的待办文案（可选） |
| `todo_due` | string | 待办截止（可选） |

返回（200，全部合格）：`{"ok": true, "received": 1, "duplicated": 0, "invalid": 0}`；
返回（207，部分被拒）：`{"ok": false, "received": 0, "duplicated": 0, "invalid": 1, "rejected": [0], "error": "some items were rejected"}`。
`duplicated` 为 case_id 已存在被跳过的条数，`rejected` 是被拒条目在请求数组里的下标。
207 响应还带 `rejected_ids`（被拒条目的 `case_id` 列表，能取到时）：客户端优先按 id 精确匹配，不依赖「请求体顺序 == 分批顺序」这个隐式前提；`rejected` 下标数组仍然保留，老客户端照常工作。
数值字段（`window_end` / `message_count`）越界或类型不对会被计入 `invalid` 而不是打挂整个接口。
数据存 SQLite `analyses` 表。

### GET /analysis —— 查询分析结果（需 token）

| 参数 | 说明 |
| --- | --- |
| `pkg` | 来源 App 包名，精确匹配 |
| `conversation` | 会话名，**前缀匹配**（同 `/messages`）；传 `conversation_match=contains` 改成包含匹配 |
| `todo_only` | `1`/`true` 只看 `need_action=true` 的 |
| `date` | `YYYY-MM-DD`，按分析完成时间 `analyzed_at` 过滤当天（毫秒与本地文本两种历史格式都兼容）；越界与解析失败的两条 400 口径同 `/messages` |
| `limit` | 默认 100，上限 1000 |
| `before` | 游标翻页：把上一页返回的 `next_before` 原样回传（形如 `<received_at>_<rowid>`）；结构的毫秒段与 rowid 段**都**校验范围（`0 <= rowid <= 2^63-1`），非法或越界返回 400 |
| `tz` / `tz_offset` | 可选时区 / 时区偏移（分钟），作用同 `/messages` |

返回 `{"ok": true, "count": n, "analyses": [...]}`，`s1`/`s2`/`forks` 已解析回对象，按 `received_at DESC, rowid DESC` 倒序（第二排序键保证同毫秒记录不漏不重）。

**数值列的脏值守卫**：`received_at` 同样是 INTEGER 亲和列（SQLite 类型亲和只是「建议」），服务端把 `typeof(received_at) IN ('integer','real')` 作为**常驻条件**，
脏行不出现、也就不会把 `next_before` 拼成 `boom_2` 让第二页直接 400。`window_end` / `message_count` 走安全取值：认不出给 `null`，不把脏字符串吐给前端。
只有**确实还有下一页**时才返回 `next_before`，最后一页返回 `null`。
每条还带归一后的 `need_action`（布尔）与 `importance`（`high`/`medium`/`low`），以及 `redacted` / `redaction_rules` / `redaction_hits` / `todo_text` / `todo_due`。
库里被写脏的数值列（`window_end`/`message_count` 不是数字）返回 `null` 而不是 500；
**`skipped`（脏行计数）**：与 `/messages` 同口径 —— 当前过滤范围内被 `typeof(received_at)` 守卫挡掉的行数，
再加上单行构造失败的行数（后者修前是唯一来源，而守卫在 SQL 侧就把脏行挡掉了，那条分支命不中，`skipped` 恒 0）。
同样不含游标段，`count + skipped == 库存`（`/stats` 的 `analyses.total`），控制台横幅读它。

### POST /advisor —— 接收 AI 顾问复盘报告（需 token）

请求体是 JSON 数组，每条：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `report_id` | string | 报告 ID（**去重键，必需**） |
| `created_at` | string | 报告生成时间 |
| `period_days` | int | 复盘覆盖天数 |
| `case_count` | int | 覆盖的分析用例数 |
| `summary` | string | 复盘摘要 |
| `suggestions` | array | `[{type, title, detail}]` |
| `model_used` | string | 生成报告的模型 |

返回（200，全部合格）：`{"ok": true, "inserted": 1, "duplicated": 0, "invalid": 0}`；
返回（207，部分被拒）：`{"ok": false, "inserted": 1, "duplicated": 0, "invalid": 1, "rejected": [1], "rejected_ids": [], "error": "some items were rejected"}`。
`rejected_ids` 是被拒条目的 `report_id` 列表（缺 `report_id` 的条目取不到 id 时该列表为空）；`rejected` 是下标数组，两者并存。

服务端会对入参做**类型收敛**（防止脏数据把控制台撑爆或被当成 XSS 载荷）：

| 字段 | 收敛规则 |
| --- | --- |
| `report_id` | 必需、非空字符串、长度上限 512；超限/缺失计入 `invalid` |
| `period_days` / `case_count` | 收敛成整数并夹到区间（`period_days` ≤ 3650，`case_count` ≤ 1000000） |
| `summary` / `model_used` / `created_at` | 字符串，超长截断 |
| `suggestions` | 只保留对象元素，非对象丢弃；`title` ≤ 256、`detail` ≤ 2048，最多保留 200 条 |

**存档损坏时拒绝写入**：如果 `server/data/advisor_reports.jsonl`（或旧版 `.json`）损坏，`POST /advisor` 返回
500 `{"ok": false, "inserted": 0, "error": "advisor archive is corrupted, refusing to overwrite it"}`，
**损坏文件原样保留、不被覆盖**（原来的实现会把它当空档，一条上报就把历史整份抹掉）；
`GET /advisor` 此时返回 200 + `{"ok": false, "reports": [], "error": "advisor archive is corrupted"}`（控制台不整页报错）。
修好或删掉损坏文件后自动恢复。

**`/suggests/next` 与 `/console` 的同一口径**：存档损坏时 `_advisor_state()` 返回的份数是 `None`（不是 `0`），
所以`/suggests/next` 不会给「还没有顾问报告」这条误导性建议，改给 priority 85 的
「顾问存档损坏，报告读不出来」（`evidence` = `顾问存档无法解析（advisor archive is corrupted）`）；
控制台顾问 Tab 读 `res[2].ok === false` 置 `ADVISORS_ERR`，渲染 `errorState('advisor_archive')` 错误态并说明
「修好之前新的顾问上报会一直失败、历史报告仍在服务器上」，**不再落进「还没有顾问报告」的空态**。

### GET /advisor —— 查询顾问报告（需 token）

| 参数 | 说明 |
| --- | --- |
| `limit` | 默认 20，**上限 100**（存档上限 `ADVISOR_MAX_REPORTS`=1000 不等于接口一次能给 1000 份） |

返回 `{"ok": true, "count": n, "total": m, "has_more": bool, "reports": [...]}`，按接收时间倒序（最新在前）。数据存 `server/data/advisor_reports.jsonl`（追加写的主存储）+ `server/data/advisor_reports.json`（快照）。

**`total` / `has_more` 为什么必须有**：存档能存 1000 份，而本接口单次最多给 100 份。只回 `count` 时调用方
无法区分「存档只有 100 份」与「存档有 400 份、只拿到最新 100 份」—— 控制台顾问 Tab 会静默只渲染 100 张卡、
角标也停在 100，用户以为更早的 300 份报告丢了。现在 `total` 是存档总条数、`has_more = total > count`，
控制台角标读 `ADVISOR_TOTAL`，列表上方给出「当前只显示最近 N 份（存档共 M 份）」的说明。
存档损坏分支同样回 `total: 0` / `has_more: false`，前端不会拿到 `undefined`。
按 `report_id` 去重：重复上报计入 `duplicated` 不重复写，缺 `report_id` 或字段非法计入 `invalid` 并进 `rejected`（207）。

**顾问报告的 `pkg` 是可选字段，所以「按来源过滤」必须交代真实范围**：端上 `AdvisorStore.toUploadJson` 当前**不写** `pkg`（服务端 schema 允许），
而 `GET /advisor?pkg=` 与控制台的前端过滤都只在确实带字段的行上生效 —— 没有字段的行一律保留。控制台顾问 Tab 的说明由
`console_classify.advisorPkgScope(reports, pkg)` 判定（纯函数、UMD、node 可测），返回
`{mode, total, tagged, untagged, shown, hidden}`：

- `all` —— 没选来源，说明不显示；
- `untagged` —— **一份都不带字段**，这时才可以说「顾问报告不携带来源 App 字段，无法按「X」过滤」；
- `mixed` —— 过滤掉了 `hidden` 份别的来源，另有 `untagged` 份不带字段仍会显示（「这一项会随端上补齐上报字段而减少」）；
- `exact` —— 全都带字段，过滤是真过滤：`shown` 为 0 时单独说「已加载的 M 份报告里没有来自该来源的」。

`console_ui.advisorScopeNote()` 只负责按四态挑措辞，判定不写进 `renderAdvisor`；规则脚本缺失时 `try/catch` 兜 `null`，
沿用旧文案。旧实现只要选了来源就无条件写「无法按「X」过滤」，下面是清一色的该来源报告 —— 说明与事实相反。

**「列表被截断」与「列表被筛选」是两件事，说明必须分开报**：`total > count` 是接口截断，`count > 可见条数` 是筛选/搜索造成的合法缩短。
控制台顾问 Tab 的判定由 `console_classify.advisorListState(loaded, total, shown)` 负责（纯函数、UMD、node 可测），返回
`{loaded, total, shown, truncated, filtered}`：`truncated = total > loaded`、`filtered = shown < loaded`，两个标志正交。
三条保守约定：① `total` 缺失 / 为 0 / 负数 / 非数字 / 小于已加载时一律回落 `total = loaded`（老服务端没有这个键，写「存档共 0 份」比不写更糟）；
② `shown > loaded` 时夹回 `loaded`；③ `loaded == 0` 时两个标志都假。
`console_ui.advisorListNote()` 只按两个标志拼措辞：截断说「当前只显示最近 N 份（存档共 M 份），更早的报告不在此列表里」，
筛选说「这 N 份里有 K 份不匹配当前的筛选或搜索」，两件同时成立就两句都给。规则脚本缺失时退回旧口径 —— **只报截断**。
旧实现拿**过滤后的可见条数**跟存档总数比（`ADVISOR_TOTAL > reports.length`），存档 400 份、本页 100 份、筛到 4 份时写
「当前只显示最近 4 份（存档共 400 份）」，用户以为 395 份报告被截掉了；反过来存档 40 份全加载、筛到 2 份时又在一份没截的情况下谎报「更早的报告不在此列表里」。

**来源筛选下的空态必须分开三种成因**：会话 / 待办 / 统计 / 顾问四个列表都只渲染已经拉下来的那几页（`PAGE_LIMIT = 200`），
按来源筛选是在**已加载页**上做的客户端过滤 —— 过滤后为空时，「全库这个来源一条都没有」「全库有 N 条但都不在这几页里」
「拿不到全库分布」三件事的结论完全不同，不能都说成「该来源下暂无数据」。判定收进 `console_classify.appEmptyScope(pkg, loaded, shown, byPkg)`
（纯函数、UMD、node 可测），返回 `{pkg, loaded, shown, db, mode}`，`mode` 四态：

- `loaded` —— 还有可见条目，调用方不该渲染空态；
- `none` —— 全库计数 `db === 0`，这个来源真的没有；
- `elsewhere` —— 全库有 `db` 条，但都不在已加载的这几页里（更早的页没拉）；
- `unknown` —— `byPkg` 拿不到（首屏 `/stats` 未回 / 老服务端无该键 / 非会话 tab），措辞只能停在「这一页里没有」。

四条保守约定：① `db` 与 `shown` **没有大小关系**（跨页、脏行被 `typeof` 守卫跳过），判定只信 `db === 0`，写成 `db < shown` 会在
「全库 5 条 / 本页 3 条」时误判成「还有更多没加载」；② `pkgCount` 的键一律过 `normPkg` 再比（库里 `pkg` 为 `NULL` 与空串各成一组，
归成 `com.tencent.mm`，与统计卡 `distPairs` 同口径，否则同一屏「微信 5 条」与「微信 8 条」同时成立）；
③ `pkgCount` 返回 `null` 是「拿不到」、`0` 是「拿到了确实是 0」，两者不许混用；
④ 只有会话 Tab 传 `byPkg` —— `/stats` 只有 `messages` 段给按来源分布，`analyses` 段刻意不接受 `pkg`，
拿消息条数当分析条数是造假，待办 / 统计 / 顾问一律落 `unknown`。排队 Tab 直接绕过整个分支：排队条目没有 `pkg` 字段，`APP_FILTER` 对它不生效。

`console_ui.appScopeCounts(tab)` 提供「已加载 / 已筛出」两个数，且与各自列表构造**走同一段过滤代码**（`filteredMsgs` / `filteredTodos` /
`filteredAnalyses` / 与 `renderAdvisor` 同形的 `ADVISORS.filter`）——两个数各写一遍必然在改过滤条件时分叉，分叉的后果是空态开始报错数。
规则脚本缺失时 `try/catch` 兜 `null`，退回旧文案「该来源下暂无数据」；该降级路径有用例钉住字符串。

**列表只画一部分时，标题与切片必须读同一个判定**：统计 Tab 底部的「最近分析结果」既是展示区，也是全局搜索按 `s1` / `s2` / `case_id` 命中的入口，
它一直只画前 30 条（`list.slice(0, 30)`），却把标题写成「最近分析结果（list.length）」—— 翻到第 2 页之后标题报 200、下面只有 30 张卡，中间 170 条既没画也没有任何说明，
用户会以为这些分析结果被丢弃了。判定收进 `console_classify.statsListState(loaded, cap, expanded)`（纯函数、UMD、node 可测），返回
`{loaded, shown, cap, hidden, capped, expanded}`：`shown = expanded ? loaded : min(loaded, cap)`、`hidden = loaded - shown`、`capped = loaded > cap`。
四条约定：① `capped` 与 `expanded` 是**两个正交标志** —— `capped` 只回答「数据有没有超过上限」（决定是否给开关），`expanded` 只回答「用户有没有点开」，
把两者合成一个布尔会让「展开了但刚好没超上限」和「没展开但不超上限」分不开；② 上限由 UI 传入（`STATS_LIST_CAP = 30`），脏值 / 0 / 负数回落 `DEFAULT_STATS_LIST_CAP = 30`，
规则层不写死调用方的展示上限；③ `shown` 由函数自己算，调用方不许传 —— 传了就有第二份口径，切片与文案必然分叉；④ 标题、`stat-list-note`、`stat-list-more` 三处都读同一个 `listState`。
展开状态 `STATS_LIST_OPEN` 只存内存、不落盘：刷新页面回到折叠态是安全选择（一次展开 200 张卡的渲染代价不该被持久化成默认）。
规则脚本缺失时 `try/catch` 兜 `null`，`renderStats` 就地算同样的六个键，退化成「只画前 30 条 + 说明」，绝不再回到「标题说 200、画 30 张」的旧口径。

**角标这类聚合数字默认口径是全库，只有明确的「我在看某一份切片」才降级**：底部 tab 的三个列表角标（会话 / 待办 / 统计）原本一律数 `filteredMsgs().length` / `filteredTodos().length` / `filteredAnalyses().length` ——
这些函数的语义是「客户端筛选**已加载页**」，而角标的语义是「这个入口里有多少东西」，两者只在「一页装得下全部」时才相等。控制台一页 `PAGE_LIMIT = 200`，库里待办超过一页时同一屏会同时出现「待办 15」（tab 角标）与「待处理 40」（概览条，读 `/stats`），
两个数都写着「待办」，用户只能二选一相信。判定收进 `console_classify.badgeState(loaded, total, filtered)`，返回 `{value, scope}`：`scope` 只有 `library` / `loaded` 两态。
三条约定：① **`total` 缺失 / 负数 / `NaN` / 非数字一律回落 `loaded`，不许折成 0** —— 折成 0 会把「接口没给数」渲染成「全库一条都没有」，比原口径更误导（`Number("abc")` 是 `NaN`，而 `NaN >= 0` 恒 false，只判 `>= 0` 挡不住）；
② **筛选态（`APP_FILTER` 或搜索词非空）不报全库**，哪怕全库数就在手上 —— 客户端筛选只作用于已加载页，报全库会让角标在「筛到 0 条」时仍显示大数字；③ **`scope` 要进位读屏文案**（`（本页）`），界面上角标只有一个数字，视障用户拿不到「数字大小暗示口径」这个视觉线索。
排队与顾问两个角标**不套这条规则**：排队本来就是全库口径（`queueActiveCount()` 读 `QUEUE_SUMMARY`，不是数当前页），顾问读存档总数 `ADVISOR_TOTAL`；套上反而会引入「筛选时退回本页」的错误行为（顾问的筛选是服务端真过滤，排队条目不带 pkg）。
规则脚本缺失时 `badgeCount(kind)` 里 `try/catch` 兜底返 `{ value: loaded, scope: 'loaded' }`，退回旧口径但语义自洽（读屏会标「（本页）」）。

**顶部同步状态行的两个计数各带口径，不许整句套一档**：状态行写「已同步 16:32 · 消息 40 · 分析 40」，两个数字分别来自 /stats 的 `messages.total` 与 `analyses.total`。
旧写法只判 `messages.total` 就决定整行口径 —— 后果有三种：① 一侧拿到全库、另一侧拿不到时，整句仍装成全库口径（真机上出现「已加载消息 5 · 分析 40」这种同一句里两种口径的写法，用户无从判断哪个数是哪一档）；
② `analyses.total` 缺失时直接字符串拼接，渲染出「分析 null」；③ 来源筛选时两侧一起退化，但「消息」「分析」两个标签名不变，看不出是已加载口径。
判定收进 `console_classify.syncCounters(msgLoaded, anaLoaded, msgTotal, anaTotal, filtered)`，复用 `badgeState` 的语义逐侧定档，返回 `{msg, ana, mixed}`（`msg` / `ana` 各是 `{value, scope}`，`mixed` 表示两侧口径不同）。
三条约定：① `null`（拿不到）≠ `0`（真的是 0），与角标同一口径；② `filtered` 为真时两侧一起退回已加载，全库数不能代表筛选后的列表；③ 界面只在 `scope === 'loaded'` 的那一侧加「已加载」前缀（`消息 40 · 已加载分析 3`），没退化的一侧不加前缀 —— 两侧都加会让人以为整句都是估算。
规则脚本缺失时 `syncCountText()` 兜底：两侧都能拿到全库数才整句报全库，否则两侧一起退回已加载（宁可少报，也不混口径）。

**来源 / 类型 / 状态的文案映射只在 `console_classify.js`，别在 `console_ui.py` 里再抄一份**：控制台的统计 Tab 曾把 `by_kind` / `by_source` 的原码直接拼进卡片（「analysis 3 / sync 2」「a11y-extract 1」），
而同一屏的排队卡片用另一份 `QUEUE_KIND_META` 渲染「分析 / 同步」—— 同一个枚举在两张卡上两种语言；三条非通知通道（`android-db` / `pc-db` / `a11y-extract`）里只有第一条在消息行有「库」徽标，无障碍直读的消息完全看不出是估时数据。
映射收进规则层：`SOURCE_LABELS`（通知 / 从数据库导入 / 从电脑数据库导入 / 无障碍直读 / 评测数据 / 未知来源）+ `sourceLabel()`、`SOURCE_BADGES` + `sourceBadge()`（`android-db` / `pc-db` → 「库」，`a11y-extract` → 「读」，通知渠道不打标）、`queueKindLabel()` / `queueStateLabel()`（走 `_labelOf`，认不出返 `null` 让调用方决定兜底措辞），
以及 `PAIR_LABELERS` + `pairText(pairs, "source"|"kind")` 供 `[{key,count}]` 形状的分布直接出中文。
三条约定：① **认不出的原码原样回显，空值才折 `unknown`（「未知来源」）** —— 服务端将来加一条采集通道，控制台不会先渲染成空白；② **映射表查键必须走 `Object.prototype.hasOwnProperty.call`**，`source = "constructor"` / `"__proto__"` 这类脏值不许命中原型链；③ **`pairText` 丢掉 `count <= 0` 的项并按传入顺序拼接**，零次数不该占位。
UI 侧只保留降级：`console_ui.sourceBadgeHtml()` 在规则脚本缺失时仍给 `android-db` 打「库」，其余通道打空（宁可不标也不标错）。新增一条采集通道时改 `SOURCE_LABELS` 一处即可，`server/test_console_classify.py` 的 `test_source_label_covers_every_source_the_server_can_write` 会钉住全集。

**排队 Tab 的空态同样必须分开三种成因，设备维度还要再分一层**：设备 chip 上写着「3 台 · 2 活跃」，点开某台设备却看到「手机端没有排队或运行中的分析任务」—— 旧 `queueEmptyHtml()` 只判 `QUERY.trim() || QUEUE_DEVICE` 就退回通用空态，把三种成因混成一句：
① 全库真没有这个状态 / 这台设备的任务；② 全库有 N 条，但列表只覆盖最近 500 条（跨设备共用一个窗口、按 `updated_at` 倒序，安静设备的历史会被活跃设备挤出去）；③ 这次 `/queue` 没返回汇总，压根拿不到全库口径。
判定收进 `console_classify.queueEmptyScope(device, state, loaded, shown, devices, summary)`，返回 `{device, state, scope, loaded, shown, db, stateKnown, mode}`：`scope` 是 `device` / `state` / `all`，`mode` 是 `loaded` / `none` / `elsewhere` / `unknown`（与 `appEmptyScope` 同名同义）。
四条保守约定：① **设备维度读 `devices[].total`（全库分组聚合、无 LIMIT），状态维度读 `summary[state]`**，两者都不能用 `QUEUE.length` 顶替；② **设备查不到 ≠ 没任务** —— `devices` 里没有这台设备说明行已被保留期清理（停报超 7 天），措辞要写「设备『…』已经没有任务了」而不是「这一页没有」；③ **「设备 × 状态」没有已知口径**（`queue_summary(device_id, state)` 只单独接受其一），此时 `stateKnown = false`，UI 只能说「全库共有 N 条任务（各状态合计）」，不许拿设备总数冒充该状态的条数；④ **拿不到 `summary` / `devices` 时回 `unknown`**，措辞写「拿不到全库计数…不敢断言」，绝不替服务端下「已经没有任务」的结论。
UI 侧只挑措辞：`console_ui.queueEmptyHtml()` 在 `scope.scope === 'device'` 下给三档（已清理 / 在更早的页 / 拿不到口径），其余给「全库没有」「这一页没有」「当前页没有 + 全库共 N 条」，并按当前筛选给出「查看全部设备」「查看全部状态」按钮。**搜索仍是「本页里挑」**（`if (QUERY.trim()) return emptyStateFor('queue')`），因为搜索命中的是已加载行、全库条数救不了它。
### POST /queue —— 上报排队任务快照（需 token）

请求体 `{"device_id": "android-1f2a3b4c", "tasks": [...]}`，按 `(device_id, task_id)` 整份覆盖
（同一任务重报只刷新 `updated_at`，不会重复入库）。

返回 `{"ok": true, "accepted": n, "rejected": [{"index": i, "reason": "..."}]}`；
非法条目**逐条拒收**，不影响同批其余条目（这里不走 207，而是 200 + `rejected` 清单）。

存储：表 `queue_tasks` 保留 7 天，`updated_at` 超 30 分钟未更新的条目读时算 `stale`（不回报写）。清理有两处：**上报时顺带清**（`upsert_queue_tasks` 里的 `DELETE`），以及 **读取前顺带清**（`_ensure_db_ready` → `cleanup_queue_tasks`，按 `QUEUE_CLEANUP_INTERVAL_MS` 一小时一次节流）。只留上报那一处是不够的：所有设备停报（换机 / 卸载 / 关掉排队上报）之后过期行永远不会被清，`devices` 会一直挂着早已离线的设备。


**条目长度按 Unicode 码点算，不是按 UTF-16 unit**：`console_classify.normalizeQueueTasks` 用 `codePointLength` / `clipCodePoints`
（手写 `charCodeAt` 判代理对）守 `title` 256 / `detail` 512 / `task_id` 128 三个上限，与端上 `QueueStateCore`（`TextClipCore.clip`）
和服务端 `storage._normalize_queue_task`（Python `len()`）对齐。改之前这里走 `String.prototype.length` + `slice`，
**257 个 unit 的输入（255 个 ASCII + 1 个 emoji）会留下孤立高代理 `0xD83D`**，浏览器把它渲染成 `�`；
反过来，端上合法的「256 个 emoji 标题」（512 unit）在这里会被再砍一半、只剩 128 个 emoji。
三处上限必须同时改：`server/console_classify.js`、`server/storage.py`、`app/.../QueueStateCore.kt`。
### GET /queue —— 查询排队任务（需 token）

| 参数 | 说明 |
| --- | --- |
| `device_id` | 可选，精确匹配 |
| `state` | 可选，`queued` / `running` / `failed` / `done`；非法值返回空列表 |
| `limit` | 默认 200，上限 500 |

返回 `{"ok": true, "tasks": [...], "summary": {...}, "devices": [...]}`。三段口径故意不同：

- `tasks` 是**当前页** —— `device_id` / `state` / `limit` 三个条件都作用在它上面；
- `summary` 是**全库口径** —— 只看 `device_id` / `state`，**不受 `limit` 截断**，与 `GET /stats` 的 `queue` 段同一套过期口径；
  `total` = 全库行数，过期行只计进 `stale`、不再计入四个状态位；
- `devices` 是**全库设备清单** —— 不受任何过滤影响，`summary.devices = len(devices)`；
  `summary.devices_active` 只数其中 `active > 0` 的台数（`storage.count_live_devices`）——「几台设备在用」不能用清单长度：
  每项含 `device_id` / `total` / `active` / `stale`：`total` 是库里现存条数（不做过期剔除），  `active` 只算**未过期**的 `queued` + `running`，`stale` 是过期（或 `updated_at` 认不出）的条数。  设备维度的 `active` 必须与 `summary` 同一套过期口径，否则同一时刻设备 chip 说「2 活跃」  而汇总说 `running 0`。

> 为什么不按当前页统计：任务超一页（默认 200）时按页统计会把总数显示成 200，
> 而同一时刻 `/stats` 与 `devices` 给的是全库真值，控制台「排队」Tab 与「统计」Tab 的数字就对不上。
> 判定走 `storage.queue_summary()`，与 `/stats` 共用同一份过期口径。
>
> 控制台的三个数字必须同源：概览条 / 「排队」Tab 角标走 `queueActiveCount()`（优先读 `summary`），
> 五个状态 chip 走 `queueStateTotal()`，汇总卡五格直接读 `summary` —— 全都是全库口径。
> 过去 chip 是数**当前页**的 `QUEUE` 数组：某设备 600 条排队中 + 20 条已完成时，
> `tasks` 里全是排队中，chip 显示「已完成 0」而汇总卡显示「已完成 20」，同一屏两个数字打架。
> 列表为空但全库有命中时，空态也必须区分「当前页没有」与「全库共有 N 条」（`queueEmptyHtml()`）；
> 但搜索 / 设备筛选是「这一页里挑」，命中 0 就退回通用空态，不能拿全库数安慰用户。
>
> 概览条的「· N 台设备」读的是 `/stats` 的 `queue.devices_active`（不是 `devices`）：
> 设备停报（换机 / 卸载 / 关掉排队上报）后它的过期行要等 7 天保留期才清，
> 这期间它还挂在 `devices` 清单里但 `active` 恒为 0 —— 拿清单长度当设备数，
> 概览条就会写出「当前没有排队任务 · 2 台设备」（0 个任务却有 2 台设备在跑）。
> 统计 Tab 的排队卡用 `deviceTallyText()` 同口径渲染，总台数与「在跑」台数不等时才补后半句；
> 前端离线兜底走 `liveDeviceCount()`（数 `QUEUE_DEVICES` 里 `active > 0`）。
>
> 概览条「待处理」那格的副标题同理读 `analyses.todo_convs`（有待办的会话数），不是 `analyses.convs`（有分析结果的会话数）：
> `/stats` 两个键都给，`/suggests/next` 的证据串也写的是「待办 N 条，分布在 M 个会话」；
> 读 `convs` 时「待办 2 条 · 共 4 个会话」会让用户以为还有 2 条待办没显示出来。

> 同一张统计卡上的类型分布（`queue.by_kind`）也**只数未过期行**，与它左边的「活跃 / 失败 / 陈旧」同口径：
> 过期行只进 `stale`，不进四状态、也不进类型分布。
> 曾经先按 kind 计数再判过期，5 行里 3 条早已停跑时，卡片会同时写
> 「analysis 3 / sync 2」和「活跃 1 · 陈旧 3」—— 同一屏 5 vs 2，用户对不上账。
> 所以 `sum(by_kind 的 count) == total - stale`；有过期行时前端还会在分布后面补一句「（不含 N 条已过期）」。
>
> 统计卡的两条分布（`by_pkg` / `by_source`）在渲染前还要先**按标签归并**：
> 库里 `pkg` 为 `NULL` 或空串时 SQL 会各成一个分组，而前端 `normPkg()` 把它们都归成微信，
> 直接渲染就是「微信 1 / 微信 1 / 微博 1 / 微信 1」。
> 归并在 `console_classify.mergePairsBy(pairs, normalize)`（`mergePkgPairs` / `mergeSourcePairs` 两个薄包装），
> 计数容器用 `Object.create(null)`（防 `__proto__` / `constructor` 原型键）；归并后次数为 0 的分组直接丢弃（`[null, null]` 这类缺列脏数据）。
> **顺序必须重排**：归并本身会改次数，「保首次出现序」会让 `微博 2 / 微信 1+1+1` 渲染成「微博 2 / 微信 3」—— 最大头排最后。
> 所以归并后按次数降序、同次数键名码点升序排（与 `ORDER BY COUNT(*) DESC, key ASC` 对齐）；`console_ui.distPairs` 的兜底分支同口径。
> 服务端不合并：分组之和恒等于 `total`，归并只发生在展示层，且归并后之和仍等于 `total`。

### POST /admin/extract —— 触发安卓数据库提取（需 token）

后台线程异步执行一次「直读安卓微信数据库」提取合并（见下章）。

| 参数 | 说明 |
| --- | --- |
| `full` | `full=1` 全量重扫；默认增量（基于 `state.json` 的上次位置） |

返回 `{"ok": true, "started": true}`；已有任务在跑时返回 409；
主库正被占用（另一个提取进程 / 大量写入）时返回 **503** + `Retry-After: 5`，客户端可稍后重试。

### GET /admin/extract/status —— 提取状态（需 token）

返回 `{"ok": true, "running": false, "last": {...}}`，`last` 含最近一次的 `scanned / inserted / duplicated / skipped / elapsed_sec / finished_at`，失败时含 `error`。

> 注意：提取脚本以前**不返回** `ok` 字段，服务端用 `get('ok') is not False` 判成功，等于恒真——脚本崩了也显示成功。
> 现在改为「必须明确返回 `ok: true` 才算成功」，两个提取脚本已补上该字段。
> 也就是说：**脚本失败现在会如实报失败**（`last.error` 有值、`ok` 为 false），这是行为变更，属于修 bug 而不是回退。

### 错误响应与鉴权限流

| 状态码 | 场景 | 响应体 |
| --- | --- | --- |
| 401 | 令牌缺失或错误 | `{"ok": false, "error": "unauthorized"}` |
| 429 | 同一 IP 在 60 秒内鉴权失败超过 10 次 | `{"ok": false, "error": "too many failed auth attempts"}`，并带 `Retry-After: 60`；鉴权成功一次即清零 |
| 400 | 参数非法（游标越界、时区非法等） | `{"ok": false, "error": "..."}` |
| 404 / 413 / 500 | 路由不存在 / 请求体超过 8 MiB / 服务端异常 | 统一返回 JSON `{"ok": false, "error": "..."}`（500 的异常栈只写服务端日志，不回吐给客户端） |

> 限流按客户端 IP 计数，优先取 `X-Forwarded-For` 的首跳，没有可信反向代理时这个头可以被伪造，
> 正式公网部署建议改成只信 `remote_addr`，或配可信代理白名单。

### GET / —— 网页查看

浏览器打开 `http://127.0.0.1:8000/`（手机浏览器换成电脑局域网 IP），按时间倒序展示最近 100 条消息，群聊带绿色标注，每条带**来源徽标**（橙色「通知」/ 蓝色「数据库」），顶部有提取统计行（安卓库消息总数 + 最近提取时间）。设置了 token 时需在浏览器地址栏访问前先用工具带头发请求，或临时用 `/messages` 接口（网页端同样校验 `X-Token`）。

## 安卓聊天记录数据库提取

除了通知监听，`android_db_extract.py` 还能像 PC 端直读微信数据库那样，把**安卓微信的本地聊天库 EnMicroMsg.db** 解密后合并进监测库，补齐通知抓不到的历史消息。

### 原理

与 PC 端直读 `Msg.db` 的思路对应，只是密钥来源和加密参数不同：

| 环节 | PC 端（参考 `reverse/case-wechat4/wx_read_test.py`） | 安卓端（本工具） |
| --- | --- | --- |
| 数据库 | Msg*.db（SQLCipher 4） | EnMicroMsg.db（WCDB2 / SQLCipher 页加密） |
| 密钥 | 内存搜索 / 进程注入获取 | `MD5(IMEI + str(uin))[:7].lower()` 离线推导 |
| 参数 | pageSize=4096、HMAC-SHA512、256000 迭代 | pageSize=1024、PBKDF2-HMAC-SHA1、4000 迭代、HMAC 关闭、reserve=16（仅 IV） |
| 访问方式 | 本机文件直读 | MuMuManager root shell 复制 + adb pull |

提取流程（全部运行时动态发现，代码不写死任何账号信息）：

1. `MuMuManager.exe sh -v 0 -c "<命令>"` 拿 root shell（adb shell 没有 su）；
2. 在 `MicroMsg/` 下找 32 位十六进制且含 EnMicroMsg.db 的账号目录（即 `md5("mm"+uin)`）；
3. 从 MMKV 配置 `files/mmkv/system_config_prefs` 解析 `default_uin`（新版微信已不写 `system_config.xml`，uin 以 protobuf varint 存在 MMKV 里）；
4. IMEI 候选序列：`service call iphonesubinfo` → `WLOGIN_DEVICE_INFO.xml` → 历史兜底 `1234567890ABCDEF`（模拟器一般走兜底）；
5. root 下 `cp EnMicroMsg.db /data/local/tmp/ && chmod 644`，`adb pull` 到 `data/android_db/`；
6. 纯 Python（pycryptodome）逐页 AES-CBC 解密，用第 1 页页大小字段校验密钥命中；
7. 读 `message` / `rcontact` / `chatroom` 三表，合并进 `messages.db`（`source='android-db'`），清理设备临时文件。

字段映射规则：

- 私聊 `talker` = 对方 wxid → `rcontact` 查备注/昵称做 conversation 和 sender；
- 群聊 `talker` = `群id@chatroom` → 群名做 conversation；群消息 `content` 格式是「`发送者wxid:\n内容`」，拆出 wxid 再映射昵称；
- `isSend=1` 的是自己发的，sender 记「我」；
- `type=1` 文本原文，其它类型记占位符（`[图片]` `[语音]` `[视频]` `[表情]` `[链接/文件]` 等）。

### 用法

CLI（在 `server/` 目录下）：

```bash
.venv\Scripts\python.exe android_db_extract.py          # 增量
.venv\Scripts\python.exe android_db_extract.py --full   # 全量重扫
```

HTTP 触发（服务运行中）：

```bash
curl -X POST http://127.0.0.1:8000/admin/extract -H "X-Token: 你的令牌"
curl http://127.0.0.1:8000/admin/extract/status -H "X-Token: 你的令牌"
```

**定时提取建议**：可用 Windows 任务计划程序每小时跑一次增量 CLI，命令示例：

```cmd
schtasks /create /tn "NotifymeAndroidExtract" /tr "\"<你的仓库路径>\server\.venv\Scripts\python.exe\" <你的仓库路径>\server\android_db_extract.py" /sc hourly
```

（也可在本项目里配一个 Blueprint 定时任务，让 agent 定期调 CLI 或 POST `/admin/extract`。）

### 去重与增量

- 增量游标：`data/android_db/state.json` 记录上次提取的最大 `msgId`，只扫更新的行；
- 跨来源去重：同一条微信消息可能既被通知捕获又在数据库里，合并时按 `(conversation, sender, 秒级时间戳, text)` 归一键去重（秒级是因为两个来源的毫秒时间戳可能不同），库里已有的和本批次内的重复都会跳过；
- 通知来源自身的重试去重仍由原毫秒级唯一索引负责。

### 限制

- **依赖 MuMuManager 的 root 通道**：仅适用于 MuMu 12 模拟器场景；真机需要自备 root 手段并调整命令封装；
- **微信版本相关**：密钥推导公式、MMKV 存储位置、解密参数都可能随微信版本变化（当前在 8.0.78 实测通过）；升级微信后若解密失败，需重新侦察；
- **只收文本原文**：图片/语音/视频等只有占位符，不含附件本体；
- 提取的是主库文件快照，若微信开了 WAL 且未 checkpoint，最近几秒的消息可能下次才见到；
- 路径常量可用环境变量覆盖：`MUMU_MANAGER_PATH` / `MUMU_ADB_PATH` / `MUMU_ADB_SERIAL` / `MUMU_INDEX`。

## iPhone 查看端（PWA）

### 为什么 iPhone 只能做查看端

iOS 的系统限制决定了它做不了采集端：没有类似安卓 NotificationListenerService 的通知监听 API，第三方 App 读不到微信的通知内容；也不允许普通 App 后台常驻轮询；更不可能读取微信的私有数据库（无 root、沙盒隔离）。所以 iPhone 的定位是**纯查看端**——通过浏览器打开服务端提供的控制台页面，看安卓端采集上来的消息和分析结果。

### 使用步骤

1. iPhone Safari 打开（一次性，token 会记住）：

   ```
   https://your-domain.example/mmonitor/console?token=<你的令牌>
   ```

   页面会把 token 写进 localStorage 并自动抹掉地址栏里的 token，之后页面内所有请求自动带 `X-Token` 头。
   > 令牌走 query 只是首次播种的兼容手段，服务端会记一条 deprecation 警告；后续请求一律用 `X-Token` 头。
   > 控制台外壳（`/console` 本身）免鉴权，未登录时页面会显示令牌输入框，数据接口仍然必须鉴权。
2. 点 Safari 底部分享按钮 → 「添加到主屏幕」。
3. 之后从主屏幕图标「微信助手」打开就是全屏独立 App 形态（standalone，无 Safari 地址栏），不再需要输入 token。

### 功能

- **会话** Tab：按会话分组展示消息，组头显示消息数 + 最新预览 + ⚡待办角标；点卡片展开消息列表，有待办的会话内嵌分析结果（S1 chips：待办概率/重要度/截止窗口/主题 + S2 区：摘要/截止时间/建议动作/任务）。
- **待办** Tab：所有 `need_action=true` 的分析结果，高重要度红色标注。
- **统计** Tab：消息总数、来源分布（通知/数据库）、已分析会话数、最近上报时间。
- 每 30 秒自动刷新；全部 HTML/CSS/JS 内联无 CDN。
- service worker 采用**网络优先**：导航请求优先取网络，弱网/离线时回落到缓存的 **控制台外壳**（`/console`）；**任何用户数据（消息正文、分析、顾问报告）都不进缓存**，落地页 `/` 也不缓存，避免无令牌读到明文。

### PWA 技术结构

| 路由 | 说明 |
| --- | --- |
| `GET /console` | 控制台单页应用（**外壳免鉴权**，不含用户数据；数据接口仍需 token） |
| `GET /console/manifest.json` | PWA manifest（name 微信分析助手、theme_color #07C160、display standalone、SVG 图标） |
| `GET /sw.js` | service worker，scope `/`；导航网络优先、只缓存 `/console` 外壳，API 请求（/messages、/analysis 等）与落地页一律走网络不缓存 |

> 提示：iOS 的 PWA 图标实际用的是 apple-touch-icon（截图兜底），manifest 图标主要给 Android Chrome 用；**数据永远不缓存**，只缓存不含用户数据的控制台外壳。

## 数据文件

运行后在 `server/data/` 下生成：

| 文件 | 说明 |
| --- | --- |
| `messages.jsonl` | 原文存档，一行一条 JSON，字段与安卓端上报格式完全一致（含重复上报；用于备份/追溯） |
| `messages.db` | SQLite 数据库，表 `messages`（含 `source` 列区分来源）+ 表 `analyses`（分析结果，case_id 唯一），查询接口读这里 |
| `android_db/EnMicroMsg.db` | 从模拟器拉取的加密库原件（保留便于排查，已 gitignore） |
| `android_db/state.json` | 提取增量游标与最近运行时间 |
| `advisor_reports.jsonl` | AI 顾问复盘报告存档（**主存储**，一行一条，追加写；超过 8 MiB 轮转为 `.jsonl.1`，条数上限 1000） |
| `advisor_reports.json` | 同一份存档的 JSON 快照（仅在内容变化时写），兼容旧格式与既有工具 |

备份直接拷贝这些文件即可。

## 安全提示

- 本服务默认定位：**局域网 / 你自己控制的服务器**自用。Flask 内置服务器不适合高并发生产环境。
- **公网部署务必做到两件事**：设置 `WEIXIN_TOKEN`（长随机字符串）+ 启用 HTTPS。
- 公网暴露建议用 caddy / nginx 反向代理（自动 HTTPS），或用 frp 等内网穿透时**不要裸奔**——同样要套 HTTPS 和 token，且把 token 当作密码对待，不要写进代码或提交到仓库。
- `messages.jsonl` / `messages.db` 里是聊天原文，注意文件本身的存放与备份安全。
