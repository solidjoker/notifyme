# 微信消息接收服务端

配合 `weixin_android` 安卓端使用：安卓端监听微信通知，定时把消息以 JSON 数组的形式 POST 到本服务；本服务负责存档（jsonl）、入库（SQLite）、去重，并提供查询接口和一个极简网页查看页。

## 快速开始

以下命令均假设在项目根目录 `C:\project\qiyeweixin` 下执行，使用母项目的 venv。

### 0. 一键启动（MuMu 模拟器场景，推荐）

双击运行本目录下的 `启动服务.bat`，它会依次完成三件事：

1. 设置访问令牌 `WEIXIN_TOKEN`（真实值在本目录的 `secrets.local.bat`，该文件已 gitignore 不入库；改令牌就编辑它，**改了之后必须同步修改安卓 App 里的「访问令牌」并重新点「保存并启用定时上报」**，否则 App 上报会被 401 拒绝）；
2. `adb connect 127.0.0.1:16384` 连接 MuMu 模拟器，并执行 `adb reverse tcp:8000 tcp:8000`——这样模拟器里的 App 访问 `http://127.0.0.1:8000` 即直达本机服务端，无需关心局域网 IP 和防火墙；
3. 前台启动 Flask 服务端，在窗口里按 `Ctrl+C` 即可停止。

> 注意：`adb reverse` 配置存在 adbd 里，**模拟器重启后失效**，所以每次重启模拟器后都要重新运行一次本 bat（或至少手动执行 reverse 那一步）。

### 1. 安装依赖

```bash
.venv\Scripts\pip.exe install -r weixin_android\server\requirements.txt
```

（如默认源拉取失败，可换镜像：`-i https://pypi.tuna.tsinghua.edu.cn/simple`）

### 2. 启动服务

```bash
.venv\Scripts\python.exe weixin_android\server\app.py
```

默认监听 `0.0.0.0:8000`。自定义端口（Windows cmd）：

```cmd
set PORT=9000 && .venv\Scripts\python.exe weixin_android\server\app.py
```

### 3. 设置访问令牌（强烈建议）

Windows cmd：

```cmd
set WEIXIN_TOKEN=你的复杂令牌 && .venv\Scripts\python.exe weixin_android\server\app.py
```

Git Bash：

```bash
WEIXIN_TOKEN=你的复杂令牌 .venv/Scripts/python.exe weixin_android/server/app.py
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

> **鉴权说明**：所有接口（除 `/console/manifest.json` 和 `/sw.js`）在设置了 `WEIXIN_TOKEN` 后都需要鉴权。支持两种传法：请求头 `X-Token: <令牌>`（API 调用推荐），或 URL query `?token=<令牌>`（浏览器访问用，如 iPhone Safari）。token 即密码，不要分享给他人。

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

**返回**（200）：

```json
{"ok": true, "received": 1, "duplicated": 0, "invalid": 0}
```

- `received`：本次新入库条数
- `duplicated`：重复跳过条数（同一 `conversation + sender + timestamp + text` 已在库中，通常是手机端重试）
- `invalid`：缺字段或类型错误被跳过的条数

鉴权失败返回 401：`{"ok": false, "error": "unauthorized"}`；body 不是 JSON 数组返回 400。

### GET /messages —— 查询消息

**参数**（均为 query string，可选）：

| 参数 | 说明 |
| --- | --- |
| `conversation` | 会话名，模糊匹配 |
| `date` | `YYYY-MM-DD`，按消息 `timestamp` 过滤当天 |
| `limit` | 返回条数，默认 100，上限 1000 |

示例：

```bash
curl "http://127.0.0.1:8000/messages?conversation=家庭&date=2025-12-24&limit=50" -H "X-Token: 你的令牌"
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

结果按 `timestamp` 倒序。`received_at` 为服务端接收时间（毫秒），`source` 标记来源（`android-notification` 通知上报 / `android-db` 数据库直读）。

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

返回（200）：`{"ok": true, "received": 1, "duplicated": 0, "invalid": 0}`，`duplicated` 为 case_id 已存在被跳过的条数。数据存 SQLite `analyses` 表。

### GET /analysis —— 查询分析结果（需 token）

| 参数 | 说明 |
| --- | --- |
| `conversation` | 会话名，模糊匹配 |
| `todo_only` | `1`/`true` 只看 `need_action=true` 的 |
| `limit` | 默认 100，上限 1000 |

返回 `{"ok": true, "count": n, "analyses": [...]}`，`s1`/`s2` 已解析回对象，按 `received_at` 倒序。

### POST /admin/extract —— 触发安卓数据库提取（需 token）

后台线程异步执行一次「直读安卓微信数据库」提取合并（见下章）。

| 参数 | 说明 |
| --- | --- |
| `full` | `full=1` 全量重扫；默认增量（基于 `state.json` 的上次位置） |

返回 `{"ok": true, "started": true}`；已有任务在跑时返回 409。

### GET /admin/extract/status —— 提取状态（需 token）

返回 `{"ok": true, "running": false, "last": {...}}`，`last` 含最近一次的 `scanned / inserted / duplicated / skipped / elapsed_sec / finished_at`，失败时含 `error`。

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
C:\project\qiyeweixin\.venv\Scripts\python.exe android_db_extract.py          # 增量
C:\project\qiyeweixin\.venv\Scripts\python.exe android_db_extract.py --full   # 全量重扫
```

HTTP 触发（服务运行中）：

```bash
curl -X POST http://127.0.0.1:8000/admin/extract -H "X-Token: 你的令牌"
curl http://127.0.0.1:8000/admin/extract/status -H "X-Token: 你的令牌"
```

**定时提取建议**：可用 Windows 任务计划程序每小时跑一次增量 CLI，命令示例：

```cmd
schtasks /create /tn "WeixinAndroidExtract" /tr "\"C:\project\qiyeweixin\.venv\Scripts\python.exe\" C:\project\qiyeweixin\weixin_android\server\android_db_extract.py" /sc hourly
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
2. 点 Safari 底部分享按钮 → 「添加到主屏幕」。
3. 之后从主屏幕图标「微信助手」打开就是全屏独立 App 形态（standalone，无 Safari 地址栏），不再需要输入 token。

### 功能

- **会话** Tab：按会话分组展示消息，组头显示消息数 + 最新预览 + ⚡待办角标；点卡片展开消息列表，有待办的会话内嵌分析结果（S1 chips：待办概率/重要度/截止窗口/主题 + S2 区：摘要/截止时间/建议动作/任务）。
- **待办** Tab：所有 `need_action=true` 的分析结果，高重要度红色标注。
- **统计** Tab：消息总数、来源分布（通知/数据库）、已分析会话数、最近上报时间。
- 每 30 秒自动刷新；全部 HTML/CSS/JS 内联无 CDN，service worker 把页面骨架 cache-first 缓存，离线/弱网也能打开骨架。

### PWA 技术结构

| 路由 | 说明 |
| --- | --- |
| `GET /console` | 单页应用（需 token，支持 `?token=` query） |
| `GET /console/manifest.json` | PWA manifest（name 微信分析助手、theme_color #07C160、display standalone、SVG 图标） |
| `GET /sw.js` | service worker，scope `/`；骨架 cache-first，API 请求（/messages、/analysis 等）走网络 |

> 提示：iOS 的 PWA 图标实际用的是 apple-touch-icon（截图兜底），manifest 图标主要给 Android Chrome 用；数据永远不缓存，只缓存页面骨架。

## 数据文件

运行后在 `server/data/` 下生成：

| 文件 | 说明 |
| --- | --- |
| `messages.jsonl` | 原文存档，一行一条 JSON，字段与安卓端上报格式完全一致（含重复上报；用于备份/追溯） |
| `messages.db` | SQLite 数据库，表 `messages`（含 `source` 列区分来源）+ 表 `analyses`（分析结果，case_id 唯一），查询接口读这里 |
| `android_db/EnMicroMsg.db` | 从模拟器拉取的加密库原件（保留便于排查，已 gitignore） |
| `android_db/state.json` | 提取增量游标与最近运行时间 |

备份直接拷贝这些文件即可。

## 安全提示

- 本服务默认定位：**局域网 / 你自己控制的服务器**自用。Flask 内置服务器不适合高并发生产环境。
- **公网部署务必做到两件事**：设置 `WEIXIN_TOKEN`（长随机字符串）+ 启用 HTTPS。
- 公网暴露建议用 caddy / nginx 反向代理（自动 HTTPS），或用 frp 等内网穿透时**不要裸奔**——同样要套 HTTPS 和 token，且把 token 当作密码对待，不要写进代码或提交到仓库。
- `messages.jsonl` / `messages.db` 里是聊天原文，注意文件本身的存放与备份安全。
