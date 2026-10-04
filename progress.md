# Progress — notifyme

> 最后更新：2026-10-05
> 正式工作目录：`C:\project\notifyme`
> 远程仓库：`git@github.com:solidjoker/notifyme.git`（public，MIT）
> 口号：**TodayToTomorrow for little mermaid**

---

## 一、项目是什么

安卓本地优先（local-first）的微信通知捕获、会话管理与 AI 分析工具（Kotlin）：
通知原文留在手机本地，分析走用户自配的 OpenAI 兼容接口（云端或本地模型），
需要行动的消息经三级降级链（系统日历事件 → 日历插入 Intent → 本地闹钟）生成提醒。

- 包结构（双 flavor，`app/build.gradle.kts`）：
  - `open`：applicationId `com.notifyme.android`，**所有默认值注入空串，APK 内零密钥零预置**
  - `beta`：applicationId `com.notifyme.android.test`，与主包共存同机，
    默认值由本机 `secrets.local.properties` 注入（该文件已 gitignore）
  - 产物 APK：`notifyme-open.apk` / `notifyme-test.apk`
- 本地存储：应用私有目录 JSONL —— `files/messages.jsonl`、`files/analysis.jsonl`、
  `files/reminders.jsonl`、`files/pending.jsonl`
- 采集通道：`NotificationListenerService`（通知监听）+ 无障碍服务直读微信界面
- 配套（可选）：`server/` Flask 服务端，消息接收/查询 + 网页控制台（PWA）

当前版本：**versionName 0.1.3 / versionCode 4**（五项需求完成后未再升版本号）

## 二、仓库来源与目录关系（重要，避免搞混）

- 母仓库（私有，**保持不动**）：本地另一目录 + 私有远程，含 reverse/wxbot/aibot-bot/web 等全部子项目与完整历史。具体路径与 URL 属私有信息，不写入本公开仓库。
- 公开化时的暂存目录（已废弃，可删）。
- **正式工作目录：`C:\project\notifyme`** —— 仅含 notifyme Android 端内容（App + Flask server + docs），
  单一干净历史，不含逆向工具链。今后只在这里开发。
- 公开版从母仓库 Android 子目录脱敏拷贝而来，包含五项需求的全部最新代码
  （那些改动在母仓库工作区尚未提交，公开版已收录）。

Git 提交身份（本仓库局部配置，未改全局）：
- `solidjoker <20034025+solidjoker@users.noreply.github.com>`

## 三、已完成工作

### 1. 五项需求（已全部开发 + 真机实测通过，2026-10-05）

1. **左滑删除会话**：首页会话头左滑 → 确认 → 删整个会话
   （消息 + 该会话分析记录 + 提醒 + pending 队列一并清理）
2. **左滑按日期删除**：日期头左滑 → 删该会话某天消息（同步清 pending）
3. **聊天室批量删除（两种形态）**：
   - 首页长按会话头 → ActionMode 多选整聊天室 → 批量删除
   - 会话详情页（`ConversationActivity`）长按气泡 → 多选单条消息 → 删除
4. **分析按钮进度条**：点 🔍 后 chip 底部显示 ProgressBar，WorkManager 任务结束自动消失
5. **标记管理**：
   - ⚡ 待行动分析角标，结果页可「✕ 清除标记」（`AnalysisStore.deleteCase`）
   - 📅 提醒角标（会话有提醒即显示），提醒管理页可删除/取消
     （`AlarmHelper.cancelReminder` + `CalendarHelper.deleteEvent` + `ReminderStore.remove`）

实现要点：
- 存储删除方法：`MessageStore` 新增 `deleteConversation/deleteDate/deleteMessages`
  （均 `@Synchronized`，全文件重写，损坏行原样保留）；
  `AnalysisStore` 新增 `deleteCase/deleteByConversation`；
  `PendingQueue` 新增 `removeByConversations/removeByConversationDay/removeByMessages`
- `MainActivity`：`ItemTouchHelper`（`SwipeDeleteCallback`，仅 HEADER/DATE 可滑、选择态禁用、
  滑动铺红底 #E5484D）；ActionMode 批量选择（`selectedConversations`、勾选符 ✓）；
  分析进度用 `WorkManager.getWorkInfosByTagLiveData` + observe（复绑/回收时正确 removeObserver）
- `CalendarHelper`：修复真实 eventId 不入库的旧缺陷（`createReminder` 现写入 `result.eventId`）；
  新增 `deleteEvent`
- `AnalysisScheduler`：请求加 tag `analysis_on_demand` 与 `analysis_conv:<会话>`，`convTag()` 辅助
- 新增 drawable `bg_bubble_selected.xml`
- strings 新增：删除确认类、`batch_selected_n`、`msg_selected_n`、`delete_done`、
  `analysis_clear_mark`、`reminder_action_delete`、`reminder_list_title_conv` 等

实测记录：模拟器 emulator-5554（=127.0.0.1:16384，Android 15，900x1600）；
临时测试数据测完已全部清空，设备恢复空态「暂无捕获到的微信消息」。
日历 L1（真实日历账户写事件）因模拟器无日历账户未实测，仅代码审查；L3 闹钟删除路径已验证。

### 2. 公开化（已完成并发布）

- 隐私审计：扫描全部跟踪文件 + 684 个历史 blob。无真实 API key/私钥入库。
  已脱敏：个人域名 `soliddorisriley.xyz`→`your-domain.example`（含用 mmdc 重新渲染全部架构图）、
  `C:\Users\smith` 等个人路径、`启动服务.bat` 改为 PATH + 环境变量（ADB/DEVICE_ADDR/LOCAL_PORT）可覆盖、
  外部工具链引用改为环境变量 `WX_TOOLCHAIN_DIR` 指定（工具链不随仓库分发）
- 版权：MIT LICENSE（© 2026 solidjoker）；41 个 Kotlin + 4 个 Python 源文件加版权头
  （`Copyright (c) 2026 solidjoker` / `SPDX-License-Identifier: MIT`）
- 文档：重写 `README.md`（功能/架构/文件说明/构建/路线图 7 项/口号）、
  新增 `PRIVACY.md`、`THIRD-PARTY.md`、`secrets.local.properties.example`、`.gitignore`
- 仓库不含 APK / 构建产物 / server 数据与日志（均 gitignore）
- 已发布：https://github.com/solidjoker/notifyme（main，单条干净历史）

### 3. 包名精简重命名 + 文档重组（本次会话）

围绕「以 Android 包 + iPhone 包为基础、服务器为可选项」精简公开仓库，并彻底移除
`qiyeweixin` / `weixin_android` / `weixin-monitor` 等会泄露私有母仓库的旧命名：

- **代码重命名**（已构建验证，`assembleOpenDebug` + `assembleBetaDebug` 均 exit 0）：
  - 包目录 `com/qiyeweixin/weixin_android/` → `com/notifyme/android/`（41 个 .kt 文件 `package` 行同步改）
  - `app/build.gradle.kts`：`namespace` + `applicationId` → `com.notifyme.android`
  - `settings.gradle.kts`：`rootProject.name` `WeixinAndroid` → `notifyme`
  - `AndroidManifest.xml` 自定义 action、`activity_main.xml` 自定义 View 全限定名、
    `ReminderReceiver.ACTION_REMIND` 常量、若干注释/日志 → 全部 `com.notifyme.android` / notifyme
  - APK 产物名 `weixin-monitor-{open,test}.apk` → `notifyme-{open,test}.apk`
  - 校验：`grep -r 'qiyeweixin|weixin_android|WeixinAndroid|weixin-monitor' app/src` 无残留
- **文档重组**：
  - 根 `README.md` 重写：平台说明（Android 现有 APK / iPhone PWA 查看端 / 原生 iOS 规划中）、
    服务器明确标为「可选」、快速开始面向用户（装 APK + 授权 + 配分析接口），
    构建/flavor/密钥/adb 细节迁出到新文件 `docs/BUILD.md`
  - 新增 `docs/BUILD.md`：环境要求、双 flavor 表、`secrets.local.properties` 注入键表、构建/安装/签名、目录结构
  - `server/README.md`：把指向不存在的母仓库路径（`C:\project\qiyeweixin`、`weixin_android\server\...`）
    全部改为本仓库相对路径（在 `server/` 下用本机 `.venv` 跑 `app.py` / `android_db_extract.py`）
  - `docs/architecture/README.md` + `diagrams/07,08.mmd`：同步改名，并用 mmdc 重新渲染 07/08 的 .svg/.png
  - 移除 `docs/architecture/README.md` 中已过时的「与根 README 的差异」清单（根 README 重写后已对齐）
- **注意**：显示名（`app_name` 微信分析助手 等）未改，仅改包名/产物名；显示名属独立产品决策。
- 版本号仍为 0.1.3 / versionCode 4（本次为命名精简，未升版本；如需发布再按语义升）。

## 四、构建与运行

```powershell
# 环境
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:ANDROID_HOME = "C:\Users\<你>\AppData\Local\Android\Sdk"

# 构建（双 flavor 已验证通过）
cd C:\project\notifyme
.\gradlew.bat assembleOpenDebug assembleBetaDebug
# 产物：
#   app\build\outputs\apk\open\debug\notifyme-open.apk
#   app\build\outputs\apk\beta\debug\notifyme-test.apk

# beta 如需预置默认值（自行创建，勿提交）：
copy secrets.local.properties.example secrets.local.properties

# Python 语法自检
python -m py_compile server\app.py server\android_db_extract.py server\pc_db_extract.py server\gen_icons.py

# 安装/启动 beta
adb -s emulator-5554 install -r app\build\outputs\apk\beta\debug\notifyme-test.apk
adb shell monkey -p com.notifyme.android.test -c android.intent.category.LAUNCHER 1
```

ADB/设备要点（踩过的坑）：
- 组件类包名始终是 `com.notifyme.android`（beta 只加 applicationIdSuffix），
  用 `monkey -p <包名> -c android.intent.category.LAUNCHER 1` 启动；不要用会报 Error type 3 的 `-n ...test/.MainActivity`
- 设备自转横屏时：`adb shell settings put system accelerometer_rotation 0` 可立即恢复竖屏
  （`user_rotation` 在加速度模式开时不生效）
- 设备 Sogou IME 无法用 adb 输入中文；uiautomator dump 不含 GONE 节点
- 本机当前文件策略 danger-full-access；无需申请 sandbox_permissions

## 五、下一步计划（路线图，对应 README）

1. **原生 iOS 客户端**：iPhone 本地捕获与分析能力（当前仅 PWA 查看端）
2. **跨应用通知管理**：从微信扩展到手机所有 App 通知 + PC 通知的统一管理
3. **通知之外的信息融入**：自己发出的消息等通知流外数据的接入方案
4. **隐私信息保护**：端侧处理、敏感信息识别/脱敏强化
5. **前端 UI 优化**：交互、可读性、多端适配
6. **本地 Agent 与大模型优化**：端侧模型能力、提示词与分析质量
7. **打通智能硬件**：可穿戴 / 家居等设备通知联动
8. **其他**：Issues 共建

### 可选的收尾小事（下次可做）

- [ ] GitHub 仓库 About/Topics、确认仓库可见性为 Public（如建仓时是 Private）
- [ ] 正式版 APK 挂 Releases（当前仓库不含二进制），README 加下载徽章
- [ ] 可选：英文版 README
- [ ] 删除废弃的公开化暂存目录（本地另一目录，已不再使用）
- [ ] 五项需求已验证但版本号仍为 0.1.3；如需发布新版本，按语义升 versionName/versionCode

## 六、硬约束（务必遵守）

- 仅在 `C:\project\notifyme` 开发并提交；私有母仓库（本地另一目录）不改动
- 密钥/真实服务器地址只放本地 `secrets.local.*`，绝不入库；open flavor 保持零预置
- 不提交用户真实数据；测试数据用后即清
- 未经用户明确要求不推送/不发布新版本（普通文档提交除外，需向用户说明）
- 逆向工具链（含来源不明/第三方版权代码）不进入公开仓库
