# notifyme · 微信通知本地智能分析助手

> **TodayToTomorrow for little mermaid**

一个本地优先的微信通知捕获、会话管理与 AI 分析工具。
通知原文留在你自己的设备上，分析用你自己指定的模型，
需要行动的消息自动生成日历 / 闹钟提醒。

- 🤖 **Android**：现有 APK，独立运行，完全离线可用
- 🍎 **iPhone**：PWA 查看端（需配套服务端）；原生 iOS 客户端规划中
- 🖥️ **服务端（可选）**：跨设备查看、历史回填、公网访问

---

## 功能特性

**捕获与会话管理**

- 通知监听服务 + 无障碍直读双通道捕获微信通知
- 首页按「会话 → 日期」两级分组、折叠浏览
- 关注列表（⭐）：空列表代表关注全部，也可精确指定
- 多种删除方式：左滑会话/日期头、长按多选批量删除

**AI 分析**

- 两阶段分析：一级判定是否需要行动，二级拆解任务
- ⚡ 角标提示「待行动」结果，阅读后可一键清除标记
- 支持自定义提示词（每个会话可独立配置）
- 支持下载本地模型、端侧 Agent 推理
- OpenAI 兼容接口：云端模型或本地 Agent 均可接入

**提醒**

- 三级降级提醒链：系统日历事件 → 日历插入 Intent → 本地闹钟
- 📅 角标显示会话关联的提醒，点击进入提醒管理页

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

## 快速开始

### Android（现有 APK）

1. 从 [Releases](https://github.com/solidjoker/notifyme/releases) 下载 `notifyme-open.apk`，或按 [docs/BUILD.md](docs/BUILD.md) 自行构建
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

> iOS 系统限制：无法像 Android 那样后台捕获通知，iPhone 端仅查看已同步数据。
> 原生 iOS 客户端（含本地捕获能力）在规划中。

### 服务端部署（可选）

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

## 从源码构建

需要 JDK 17 + Android SDK（compileSdk 34，minSdk 26）。
完整构建说明（环境配置、flavor 说明、密钥注入、adb 安装）见 [docs/BUILD.md](docs/BUILD.md)。

快速构建发布版：

```powershell
$env:JAVA_HOME = "你的 JDK 17 路径"
.\gradlew.bat assembleOpenDebug
# 产物：app/build/outputs/apk/open/debug/notifyme-open.apk
```

---

## 隐私

- 消息默认只存设备应用私有目录，配置前完全离线
- 仅在你主动配置并点击时，数据才会发往你指定的接口
- 支持按会话 / 按日期 / 批量删除；卸载即彻底清除

完整说明见 [PRIVACY.md](PRIVACY.md)。

## 法律与合规

仅可用于你自己的设备与你有权处理的数据。捕获与分析他人消息前请确保
已取得必要同意，并遵守当地法律与微信相关条款。

---

## 下一步计划

1. **原生 iOS 客户端**：iPhone 本地捕获与分析能力（当前仅 PWA 查看端）
2. **跨应用通知管理**：从微信扩展到手机上所有 App 的通知，以及 PC 通知的统一管理
3. **通知之外的信息融入**：把自己发出的消息等通知流之外的数据纳入方案
4. **隐私信息保护**：端侧处理、敏感信息识别与脱敏的进一步强化
5. **前端 UI 优化**：交互细节、可读性与多端适配
6. **本地 Agent 与大模型优化**：端侧模型能力、提示词与分析质量
7. **打通智能硬件**：与可穿戴 / 家居等设备的通知联动
8. **其他**：欢迎在 Issues 中共同讨论

## 参与贡献

欢迎 Issue 与 PR。提交即表示你同意项目以 MIT 许可证发布你的贡献。

## 许可证

[MIT License](LICENSE) © 2026 solidjoker。第三方组件许可见 [THIRD-PARTY.md](THIRD-PARTY.md)。
