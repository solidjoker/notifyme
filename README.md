# 微信通知监听 · 本地智能分析助手

> **TodayToTomorrow for little mermaid**

一个安卓本地优先的微信通知捕获、会话管理与 AI 分析工具：
通知原文留在你自己的手机上，分析用你自己指定的模型，
需要行动的消息自动生成日历 / 闹钟提醒。

- 🟢 发布版零预置：不内置任何服务器地址与密钥，未配置即完全离线
- 🔒 本地 JSONL 存储，应用私有目录，卸载即净
- 🧩 OpenAI 兼容接口：云端模型或本地 Agent 均可接入

---

## 功能特性

**捕获与会话管理**

- 通知监听服务 + 无障碍直读双通道捕获微信通知
- 首页按「会话 → 日期」两级分组、折叠浏览
- 关注列表（⭐）：空列表代表关注全部，也可精确指定
- 删除能力：
  - 左滑会话头：删除整个会话（分析记录与提醒一并清理）
  - 左滑日期头：删除该会话某天的消息
  - 长按会话头：ActionMode 多选，批量删除整个聊天室
  - 会话详情页：长按气泡多选，批量删除单条消息

**AI 分析**

- 会话级快速操作：⭐ 关注 / ✎ 提示词 / 🔍 分析 / 📋 结果
- 分析按钮自带底部进度条，任务结束自动消失
- 两阶段分析：一级判定是否需要行动，二级拆解任务
- ⚡ 角标提示「待行动」结果，阅读后可一键清除标记
- 支持自定义提示词（每个会话可独立配置）
- 支持下载本地模型、端侧 Agent 推理

**提醒**

- 三级降级提醒链：系统日历事件 → 日历插入 Intent → 本地闹钟
- 📅 角标显示会话关联的提醒，点击进入提醒管理页
- 提醒可删除 / 取消（同步取消闹钟与日历事件）

**同步与回填**

- 消息可上报到自托管 Flask 服务端（局域网或经隧道公网）
- WorkManager 周期任务，休眠 / 重启后仍可靠调度
- 可选的安卓 / PC 微信数据库历史回填脚本（标准库实现）

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
              首页分组/检索         AI 分析 Worker          同步 Worker
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

## 文件说明

```
.
├── app/                        # 安卓应用模块
│   ├── build.gradle.kts        # flavor 配置（open/beta）、密钥注入、依赖
│   └── src/main/
│       ├── AndroidManifest.xml # 服务/权限/Activity 声明
│       ├── res/                # 布局、图标、主题、服务配置 XML
│       └── java/com/qiyeweixin/weixin_android/
├── server/                     # 可选配套 Flask 服务端
│   ├── app.py                  # 消息接收/查询 API、网页控制台
│   ├── android_db_extract.py   # 安卓微信数据库历史回填（可选）
│   ├── pc_db_extract.py        # PC 微信数据库历史回填（可选）
│   ├── gen_icons.py            # PWA 图标生成（开发工具）
│   ├── requirements.txt        # Python 依赖（仅 Flask）
│   └── 启动服务.bat             # Windows 一键启动（路径可环境变量覆盖）
├── docs/architecture/          # 架构文档与图源
├── gradle/wrapper/             # Gradle Wrapper
├── PRIVACY.md                  # 隐私说明
├── THIRD-PARTY.md              # 第三方组件与许可证清单
├── LICENSE                     # MIT
└── secrets.local.properties.example
```

**Kotlin 源码按职责分组：**

| 职责 | 文件 |
|---|---|
| 通知采集 | `WeChatNotificationListener`、`WeChatA11yExtractService`、`A11yExtractStore` |
| 本地存储 | `MessageStore`、`AnalysisStore`、`AdvisorStore`、`ReminderStore`、`PromptStore`、`WatchlistStore`、`PendingQueue` |
| 首页与会话 | `MainActivity`、`ConversationActivity`、`FullHeightRecyclerView` |
| AI 分析 | `AnalysisConfig`、`AnalysisWorker`、`AnalysisScheduler`、`AnalysisCase`、`AdvisorWorker`、`AdvisorScheduler`、`LocalLlmEngine`、`ForkPrefilter` |
| 本地模型 | `LocalModelStore`、`ModelDownloadWorker`、`ModelListActivity` |
| 结果与提醒 | `AnalysisListActivity`、`ReminderListActivity`、`ReminderReceiver`、`CalendarHelper`、`AlarmHelper` |
| 关注与提示词 | `WatchlistActivity`、`PromptEditActivity` |
| 服务器同步 | `SyncConfig`、`SyncWorker`、`SyncScheduler`、`HistorySync` |
| 应用骨架 | `MainApplication`、`OnboardingActivity`、`ConsoleActivity`、`BootReceiver`、`KeepAliveService`、`MessageReplier` |

---

## 快速开始

### 环境要求

- JDK 17
- Android SDK，compileSdk 34，minSdk 26（Android 8.0+）
- （可选）Python 3.10+ 用于配套服务端

### 构建两个 flavor

项目有两个 flavor：

- **open**：发布版，`com.qiyeweixin.weixin_android`，所有默认值为空，APK 内零密钥
- **beta**：测试版，`com.qiyeweixin.weixin_android.test`，与主包共存同机，
  默认值由本机 `secrets.local.properties` 注入

```powershell
$env:JAVA_HOME = "你的 JDK 17 路径"

# 发布版（无需任何密钥）
.\gradlew.bat assembleOpenDebug

# 测试版（如需预置默认值）：
copy secrets.local.properties.example secrets.local.properties
# 编辑填入你自己的地址与密钥（该文件已被 gitignore）
.\gradlew.bat assembleBetaDebug
```

产物：`app/build/outputs/apk/<flavor>/debug/weixin-monitor-{open,test}.apk`

### 安装与授权

```powershell
adb install -r app\build\outputs\apk\open\debug\weixin-monitor-open.apk
```

首次进入按引导授予：

1. **通知使用权**（设置 → 通知访问权限）
2. （可选）**无障碍服务**：启用微信界面直读
3. （可选）日历权限，用于写入待办事件

### 配置分析接口

在 App「分析设置」中填入你自己的 OpenAI 兼容接口地址、密钥与模型名，
或在 beta flavor 通过 `secrets.local.properties` 预置。发布版不内置任何地址。

---

## 服务端部署（可选）

```powershell
cd server
python -m venv .venv
.venv\Scripts\activate
pip install -r requirements.txt

# 鉴权令牌（可选但强烈建议）：写入手动创建的 secrets.local.bat
#   set WEIXIN_TOKEN=你的长随机令牌
python app.py
```

局域网内把 App 服务器地址填为 `http://<电脑IP>:8000/weixin`；
如需公网访问，请自行加 HTTPS 反代（仓库文档中的域名均为示例）。
详见 [server/README.md](server/README.md)。

---

## 隐私

- 消息默认只存手机应用私有目录，配置前完全离线
- 仅在你主动配置并点击时，数据才会发往你指定的接口
- 支持按会话 / 按日期 / 批量删除；卸载即彻底清除

完整说明见 [PRIVACY.md](PRIVACY.md)。

## 法律与合规

仅可用于你自己的设备与你有权处理的数据。捕获与分析他人消息前请确保
已取得必要同意，并遵守当地法律与微信相关条款。

---

## 下一步计划

1. **跨应用通知管理**：从微信扩展到手机上所有 App 的通知，以及 PC 通知的统一管理
2. **通知之外的信息融入**：把自己发出的消息等通知流之外的数据纳入方案
3. **隐私信息保护**：端侧处理、敏感信息识别与脱敏的进一步强化
4. **前端 UI 优化**：交互细节、可读性与多端适配
5. **本地 Agent 与大模型优化**：端侧模型能力、提示词与分析质量
6. **打通智能硬件**：与可穿戴 / 家居等设备的通知联动
7. **其他**：欢迎在 Issues 中共同讨论

## 参与贡献

欢迎 Issue 与 PR。提交即表示你同意项目以 MIT 许可证发布你的贡献。

## 许可证

[MIT License](LICENSE) © 2026 solidjoker。第三方组件许可见 [THIRD-PARTY.md](THIRD-PARTY.md)。
