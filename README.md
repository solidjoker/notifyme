# notifyme · 微信通知本地智能分析助手

简体中文 | [English](README_EN.md)

[![build](https://github.com/solidjoker/notifyme/actions/workflows/android.yml/badge.svg)](https://github.com/solidjoker/notifyme/actions/workflows/android.yml)
[![release](https://img.shields.io/github/v/release/solidjoker/notifyme?label=release)](https://github.com/solidjoker/notifyme/releases/latest)
[![license](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

> **TodayToTomorrow for little mermaid**

一个本地优先的微信通知捕获、会话管理与 AI 分析工具。
通知原文留在你自己的设备上，分析用你自己指定的模型，
需要行动的消息自动生成日历 / 闹钟提醒。

- 🤖 **Android**：现有 APK，独立运行，完全离线可用
- 🍎 **iPhone**：PWA 查看端（需配套服务端）；原生 iOS 查看端 + 提醒推送规划中
- 🖥️ **服务端（可选）**：跨设备查看、历史回填、公网访问

---

## 功能特性

**捕获与会话管理**

- 通知监听 + 无障碍直读双通道捕获通知，支持微信 / 飞书 / 钉钉等多应用（来源可在控制台勾选）
- 首页按「会话 → 日期」两级分组、折叠浏览；不同 App 的同名会话分别展示
- 关注列表（⭐）：**新会话默认不关注**（只收录、不分析、不弹卡），点 ⭐ 精确指定或「全部关注」一键放开全部；PWA 控制台可按应用筛选
- 多种删除方式：左滑会话/日期头、长按多选批量删除
- PWA 控制台按「来源 App → 会话 → 日期 / 发送者（可一键切换）→ 消息」四级归类，待办按重要度分区、顾问建议按类型分区；排队 Tab 支持按设备筛选；归类规则集中在 `server/console_classify.js`，有独立单测覆盖
- 控制台重做为两栏布局 + **五个 Tab（会话 / 待办 / 排队 / 统计 / 顾问）**，顶部概览条汇总近 24 小时消息、待处理、排队任务、同步失败与最近同步；支持明暗主题与响应式（手机单列 / 平板两栏 / 宽屏三栏）

**AI 分析**

- 两阶段分析：一级判定是否需要行动，二级拆解任务
- ⚡ 角标提示「待行动」结果，阅读后可一键清除标记
- 支持自定义提示词（每个会话可独立配置）
- 支持下载本地模型、端侧 Agent 推理
- OpenAI 兼容接口：云端模型或本地 Agent 均可接入

**提醒**

- 三级降级提醒链：系统日历事件 → 日历插入 Intent → 本地闹钟
- 📅 角标显示会话关联的提醒，点击进入提醒管理页
- 首页顶端「⚡ 最近待办」区：实时汇总待行动的分析结果，可直接**编辑**内容与截止时间、**删除**、**一键加入日历**（默认自动设置提前 15 分钟提醒，重复点击按会话去重）

**同步与回填（需服务端）**

- 消息可上报到自托管 Flask 服务端（局域网或经隧道公网）
- WorkManager 周期任务，休眠 / 重启后仍可靠调度
- 可选的安卓 / PC 微信数据库历史回填脚本

---

## 架构概览

```
微信通知 ──► NotificationListenerService ─┐
微信界面 ──► 无障碍直读 Service ──────────┤
                                          ▼
                              MessageStore (files/messages.jsonl)
                                          │
                    ┌─────────────────────┼─────────────────────┐
                    ▼                     ▼                     ▼
              首页分组/检索/待办     AI 分析 Worker          同步 Worker
        （会话→日期两级）     （云端 API / 本地模型）     （自托管服务器）
                                      │
                                      ▼
                        AnalysisStore + ⚡待行动标记
                                      │
                                      ▼
              CalendarHelper：日历事件 → 日历 Intent → AlarmHelper 闹钟
```

更完整的架构文档见 [docs/architecture/README.md](docs/architecture/README.md)，
含 9 张 C4 风格图（系统上下文、安卓分层、采集通道、分析流水线、
提醒链、功能全景、服务端、构建部署、同步时序）。

---

## 快速开始

### Android（现有 APK）

1. 从 [Releases](https://github.com/solidjoker/notifyme/releases/latest) 下载最新 `notifyme-open.apk`
   （同页附 `.sha256` 校验文件），或按 [docs/BUILD.md](docs/BUILD.md) 自行构建
2. 安装后按引导授予：
   - **通知使用权**（设置 → 通知访问权限）— 必需
   - （可选）**无障碍服务**：启用微信界面直读
   - （可选）**日历权限**：用于写入待办事件
3. 在 App「分析设置」中填入你自己的 OpenAI 兼容接口地址、密钥与模型名

发布版（open flavor）不内置任何服务器地址与密钥，未配置即完全离线。

### iPhone（PWA 查看端）

iPhone 目前通过 PWA 查看已同步的消息与分析结果，需配套服务端：

1. 在电脑或服务器上部署服务端（见下方「服务端部署」）
2. 用 Safari 打开 `http://<你的服务器地址>/console`
3. 「分享 → 添加到主屏幕」即可像原生 App 一样使用

> iOS 系统限制：任何第三方 App 都无法像 Android 那样后台读取其他应用的通知，
> iPhone 端只能查看已同步数据，不能本地捕获。
> 规划中的原生 iOS 客户端定位为「查看端 + 提醒推送」（把待行动结果推送到 iPhone），
> 而非本地捕获。详见 [docs/ROADMAP.md](docs/ROADMAP.md) 的 M5。

### 服务端部署（可选）

```powershell
cd server
python -m venv .venv
.venv\Scripts\activate
pip install -r requirements.txt

# 鉴权令牌（必填）：服务端只读环境变量 WEIXIN_TOKEN
$env:WEIXIN_TOKEN = "你的长随机令牌"
python app.py
```

Windows 一键启动：双击 `server\启动服务.bat`，它会先 `call` 同目录的
`secrets.local.bat`（格式 `set WEIXIN_TOKEN=你的长随机令牌`），再拉起 `app.py` 并自动
`adb reverse`。`app.py` 本身**不读** `secrets.local.bat`——直接
`python app.py` 时必须自己设好环境变量。

#### 监听地址（由是否鉴权自动决定）

| 运行方式 | 监听地址 | 外部可达性 |
|---|---|---|
| 设了 `WEIXIN_TOKEN` | `0.0.0.0:8000` | 局域网可访问；App 服务器地址填 `http://<电脑IP>:8000/weixin` |
| 只设 `WEIXIN_ALLOW_NO_AUTH=1` | `127.0.0.1:8000` | 仅本机；App 需先 `adb reverse tcp:8000 tcp:8000` |
| 两者都没设 | 不启动 | 打印错误并退出（`sys.exit(1)`），不会带着空鉴权上线 |
| 额外设 `WEIXIN_ALLOW_INSECURE_BIND=1` | `0.0.0.0:8000` | 显式要求无令牌对外网暴露 |

端口用 `PORT` 环境变量改（默认 8000）。

#### 鉴权令牌怎么给

服务端配了 `WEIXIN_TOKEN` 后，所有数据接口都要带令牌，二选一：

- 请求头 `X-Token: <令牌>`（App 上报、PWA 控制台用这个）
- URL 参数 `?token=<令牌>`（兼容 iPhone Safari 等不能带自定义头的浏览器；服务端会记一条 deprecation 日志）

App 端的令牌存在本机 SharedPreferences（首次可用 beta flavor 的默认值预填），上报时自动带上
`X-Token` 头。令牌不对返回 **401**；服务端根本没配令牌且未放行时返回 **503**；
同一 IP 60 秒内失败 10 次后返回 **429** 并附 `Retry-After`。

#### 接口速查

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/health` | 健康检查，不需要令牌 |
| POST | `/weixin` | 上报消息数组（逐条校验，坏条目单独拒不影响好条目） |
| GET | `/messages` | 查消息：`pkg` / `conversation` / `date` / `tz` / `limit` / `before` |
| POST | `/analysis` | 上报分析结果 |
| GET | `/analysis` | 查分析结果：`todo_only=1` 只看需要行动的 |
| POST | `/advisor` | 上报 AI 顾问复盘报告（`report_id` 是去重键） |
| GET | `/advisor` | 查顾问报告，最新在前；`limit` 默认 20、上限 100 |
| POST | `/queue` | 上报手机端排队任务快照（整份覆盖，坏条目单独拒） |
| GET | `/queue` | 查排队任务：`device_id` / `state` / `limit`≤500，返回 `{ok, tasks, summary, devices}`（`devices` = 设备台数与各设备活跃任务数） |
| GET | `/console` | PWA 控制台（iPhone 用 Safari 打开这个地址） |

#### 游标翻页（`next_before`）

`/messages` 与 `/analysis` 用同一套游标：

1. 首次请求不带 `before`，取第一页；
2. 响应里的 `next_before` 形如 `<毫秒时间戳>_<rowid>`，**只有真的有下一页时才给**，末页为 `null`；
3. 下一页把 `next_before` 原样回传给 `before` 即可（按时间倒序，不重不漏）。

#### 字段表

**POST `/weixin`（JSON 数组，每条一个消息）**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `sender` | string | 是 | 发送者名，最长 512 字符 |
| `text` | string | 是 | 正文，最长 64 KiB |
| `timestamp` | number | 是 | 毫秒时间戳，范围 0 ~ 2100-01-01 |
| `conversation` | string | 是 | 会话名，最长 512 字符 |
| `is_group` | bool | 是 | 是否群聊 |
| `source` | string | 否 | 来源标记，缺省 `android-notification` |
| `pkg` | string | 否 | 来源 App 包名，缺省按微信 `com.tencent.mm` 回填 |

响应：全部有效回 200 `{"ok":true,"received":n,"duplicated":m,"invalid":0}`；
有条目被拒回 207，并带 `rejected`（被拒条目在数组里的下标）。

**GET `/messages` 每条消息**：`sender` / `text` / `timestamp` /
`conversation` / `is_group` / `received_at` / `source` / `pkg`。

**GET `/analysis` 每条结果**：`case_id` / `conversation` / `window_end` /
`message_count` / `s1` / `s2` / `escalated` / `need_action`(bool) /
`importance` / `analyzed_at` / `protocol` / `received_at` /
`forks` / `filtered` / `pkg` / `redacted` / `redaction_rules` /
`redaction_hits` / `todo_text` / `todo_due`。

> `importance` 已归一：端上传的是 0–9 数值（≥6 → `high`，≥3 → `mid`，其余 `low`），
> 老数据里的字符串原样保留。注意标签是 `mid` 而不是 `medium`。

**POST `/advisor`（JSON 数组，每条一份报告）**：`report_id`（去重键，必填）/
`created_at` / `period_days` / `case_count` / `summary` /
`suggestions`（元素为 `{type,title,detail}`，`type` 取
`prompt`/`watchlist`/`threshold`/`other`） / `model_used`。
响应 200 返回 `inserted`/`duplicated`/`invalid`；有条目被拒回 207，
带 `rejected` 与 `rejected_ids`（客户端据此把被拒的报告留到下一轮重报，不会当成功）。

#### 服务端数据文件（都在 `server/data/`，已写进 .gitignore）

| 文件 | 内容 |
|---|---|
| `messages.jsonl` | 消息原文存档（与安卓端同格式），超过 64 MiB 轮转一份 `.jsonl.1` |
| `messages.db` | SQLite 查询库（控制台检索、分页、时区过滤都走它）；另有 `queue_tasks` 表存手机端排队任务快照（保留 7 天） |
| `advisor_reports.jsonl` | 顾问报告主存储，最多 1000 条，超出裁剪最旧的 |
| `advisor_reports.json` | 顾问报告的 JSON 快照（兼容读取用） |

安卓端自己落盘的文件清单见 [PRIVACY.md](PRIVACY.md)。

---

## 从源码构建

需要 JDK 17 + Android SDK（compileSdk 34，minSdk 26）。
完整构建说明（环境配置、flavor 说明、密钥注入、adb 安装）见 [docs/BUILD.md](docs/BUILD.md)。

快速构建发布版：

```powershell
$env:JAVA_HOME = "你的 JDK 17 路径"

.\gradlew.bat assembleOpenDebug       # 产物：app/build/outputs/apk/open/debug/notifyme-open.apk
.\gradlew.bat testOpenDebugUnitTest   # 单元测试（app/src/test 下全量发现）
.\gradlew.bat lintOpenDebug           # Android Lint
```

开 PR 前跑一遍本地全量自检（服务端用例 + 语法检查 + 隐私守卫 + 版本校验 + Android 单测/双 flavor/Lint）：

```powershell
powershell -ExecutionPolicy Bypass -File docs\scripts\local_check.ps1
```

---

## 隐私

- 消息默认只存设备应用私有目录，配置前完全离线
- 三档隐私模式（关闭 / 出设备脱敏 / 仅端侧分析），手机号等 8 类信息端侧脱敏，发送前可预览对照
- 仅在你主动配置并点击时，数据才会发往你指定的接口
- 支持按会话 / 按日期 / 批量删除；卸载即彻底清除

完整说明见 [PRIVACY.md](PRIVACY.md)。

## 法律与合规

仅可用于你自己的设备与你有权处理的数据。捕获与分析他人消息前请确保
已取得必要同意，并遵守当地法律与微信相关条款。

---

## 下一步计划

按里程碑推进，完整排期、工作量与风险见 [docs/ROADMAP.md](docs/ROADMAP.md)。

1. **跨应用通知管理** ✅ 已完成（M2，支持微信 / 飞书 / 钉钉…；悬浮通知 M9 已上线：悬浮球 + 悬浮卡）
2. **隐私信息保护** ✅ 已完成（M3，三档脱敏 + 预览）
3. **本地 Agent 与大模型优化**：端侧模型能力、提示词与分析质量（下一步 M4）
4. **iOS 原生查看端 + 提醒推送**：在 iPhone 上看分析结果并收到提醒（不含本地捕获）
5. **前端 UI 优化**：交互细节、可读性与多端适配
6. **通知之外的信息融入**：把自己发出的消息等通知流之外的数据纳入方案
7. **打通智能硬件**：与可穿戴 / 家居等设备的通知联动
8. **其他**：欢迎在 Issues 中共同讨论

## 参与贡献

欢迎 Issue 与 PR。提交即表示你同意项目以 MIT 许可证发布你的贡献。

## 许可证

[MIT License](LICENSE) © 2026 solidjoker。第三方组件许可见 [THIRD-PARTY.md](THIRD-PARTY.md)。
