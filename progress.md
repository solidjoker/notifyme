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
  已脱敏：个人域名 →`your-domain.example`（含用 mmdc 重新渲染全部架构图）、
  个人用户目录 → `C:\Users\<你>` 占位、`启动服务.bat` 改为 PATH + 环境变量（ADB/DEVICE_ADDR/LOCAL_PORT）可覆盖、
  外部工具链引用改为环境变量 `WX_TOOLCHAIN_DIR` 指定（工具链不随仓库分发）
  > 本节原先复述了被脱敏掉的域名与用户名字面量，等于二次泄露，已改为占位描述；
  > 但**已推送的 git 历史里仍有旧字面量**，是否重写历史见 ROADMAP「M0 遗留风险」。
- 版权：MIT LICENSE（© 2026 solidjoker）；41 个 Kotlin + 4 个 Python 源文件加版权头
  （`Copyright (c) 2026 solidjoker` / `SPDX-License-Identifier: MIT`）
- 文档：重写 `README.md`（功能/架构/文件说明/构建/路线图 7 项/口号）、
  新增 `PRIVACY.md`、`THIRD-PARTY.md`、`secrets.local.properties.example`、`.gitignore`
- 仓库不含 APK / 构建产物 / server 数据与日志（均 gitignore）
- 已发布：https://github.com/solidjoker/notifyme（main，单条干净历史）

### 3. 包名精简重命名 + 文档重组（上次会话）

围绕「以 Android 包 + iPhone 包为基础、服务器为可选项」精简公开仓库，并彻底移除
会泄露私有母仓库的旧命名（旧包名 / 旧项目名 / 旧产物名；本文不再复述其字面量，
该组正则已固化进 CI 守卫，见 `.github/workflows/android.yml` 的 Guard 步骤）：

- **代码重命名**（已构建验证，`assembleOpenDebug` + `assembleBetaDebug` 均 exit 0）：
  - 包目录 → `com/notifyme/android/`（41 个 .kt 文件 `package` 行同步改）
  - `app/build.gradle.kts`：`namespace` + `applicationId` → `com.notifyme.android`
  - `settings.gradle.kts`：`rootProject.name` → `notifyme`
  - `AndroidManifest.xml` 自定义 action、`activity_main.xml` 自定义 View 全限定名、
    `ReminderReceiver.ACTION_REMIND` 常量、若干注释/日志 → 全部 `com.notifyme.android` / notifyme
  - APK 产物名 → `notifyme-{open,test}.apk`
  - 校验：`git grep -InE '<旧命名正则>' -- .` 无残留
- **文档重组**：
  - 根 `README.md` 重写：平台说明（Android 现有 APK / iPhone PWA 查看端 / 原生 iOS 规划中）、
    服务器明确标为「可选」、快速开始面向用户（装 APK + 授权 + 配分析接口），
    构建/flavor/密钥/adb 细节迁出到新文件 `docs/BUILD.md`
  - 新增 `docs/BUILD.md`：环境要求、双 flavor 表、`secrets.local.properties` 注入键表、构建/安装/签名、目录结构
  - `server/README.md`：把指向私有母仓库的绝对路径
    全部改为本仓库相对路径（在 `server/` 下用本机 `.venv` 跑 `app.py` / `android_db_extract.py`）
  - `docs/architecture/README.md` + `diagrams/07,08.mmd`：同步改名，并用 mmdc 重新渲染 07/08 的 .svg/.png
  - 移除 `docs/architecture/README.md` 中已过时的「与根 README 的差异」清单（根 README 重写后已对齐）
- **注意**：显示名（`app_name` 微信分析助手 等）未改，仅改包名/产物名；显示名属独立产品决策。
- 版本号仍为 0.1.3 / versionCode 4（本次为命名精简，未升版本；如需发布再按语义升）。

### 4. 路线图展开为执行计划（本次会话·规划）

对照代码核对现状后，把 README 的 8 项路线图拆成 M0–M8 可执行里程碑，落在 `docs/ROADMAP.md`。
核对出的**四条硬事实**（都带 `文件:行` 证据，是排序的依据）：

1. **公开用户首条路径是断的**：`README.md:75` 与 `docs/BUILD.md:4` 都让用户「从 Releases 下载 `notifyme-open.apk`」，
   但仓库无 Releases 产物、无 CI（无 `.github/`）、且 `app/build.gradle.kts:70-78` **没有 `signingConfigs`** —— 
   `assembleOpenRelease` 出来的是 unsigned APK，用户根本装不上 → 列为 **M0（P0）**
2. **零测试**：`app/src/test`、`app/src/androidTest` 均不存在，而下一步跨应用要动用户已有 `messages.jsonl` 的 schema，
   出错不可逆 → **M1 测试地基必须排在 M2 之前**
3. **跨应用（路线图 #2）卡在 schema**：`ChatMessage`（`MessageStore.kt:26-54`）没有 `pkg`/`app` 字段；
   微信在 3 处硬编码（`WeChatNotificationListener.kt:32,160`、`WeChatA11yExtractService.kt:40,223-227`、`ConsoleActivity.kt:533`）
4. **端侧推理（#6）是「90% 脚手架 + 0% 引擎」**：`LocalLlmEngine.kt:47-53` 只有 `UnavailableLocalLlmEngine` 占位，
   `forModel` 是 TODO（`:67-69`）；但模型下载链已通（`LocalModelStore.kt:47-48` ModelScope 直链，S1 311 MB / S2 2.47 GB 需 8 GB+ RAM），
   上层 `AnalysisWorker.runS1Local/runS2Local` 已按接口写好，换真引擎上层零改动 → 列为高风险 M4，需先过决策门 D3

**另一处需要对外纠正的定位**：路线图 #1 写「iPhone 本地捕获与分析能力」，但 iOS 不允许后台捕获其他 App 通知
（`README.md:92` 自己已承认）。建议改述为「iOS 原生查看端 + 提醒推送」，服务端 API 已齐备可直接复用
（`server/app.py:177,279,351,441,532,587,651,1347-1385`）。见 ROADMAP M5 与决策门 D4。

**推荐执行顺序**：`M0 发布闭环 → M1 测试地基 → M2 跨应用 → M3 隐私脱敏 → M4 端侧推理`，M5/M6 视决策插入，M7/M8 收尾。
四个待拍板决策：D1 签名与发布方式、D2 版本号（建议 `0.2.0/5`）、D3 引擎路线（建议先 llama.cpp AAR spike）、D4 iOS 定位。

该节产出时仅新增 `docs/ROADMAP.md`；随后的 M0 执行见下一节。

### 5. M0 发布闭环（本次会话·执行中）

四个决策门已拍板：**D1=(a) CI + Secrets 自动签名发 Release**、**D2=(a) 版本升 `0.2.0/5`**、
**D3=先 llama.cpp AAR spike 再按需转 MNN**、**D4=iOS 改述为「原生查看端 + 提醒推送」**（不含本地捕获）。

已改动的文件：

- `app/build.gradle.kts`
  - `versionCode 4→5`、`versionName "0.1.3"→"0.2.0"`（`:64-65`）
  - 新增发布签名：优先级 **环境变量 `NOTIFYME_KEYSTORE_{FILE,PASSWORD,KEY_ALIAS,KEY_PASSWORD}` > 仓库根 `keystore.properties` > 都没有则降级为不签名**；
    `hasReleaseSigning=false` 时只打 `logger.warn` 并说清缺哪一项，**构建不失败**（保证 clone 即可构建）
  - **踩坑修复**：`java.util.Properties.load` 会把 UTF-8 BOM 当成键名的一部分（键变成 `\uFEFFstoreFile`）。
    Windows 记事本 / PowerShell 5.1 的 `Set-Content` 默认写 BOM → `keystore.properties`「填对了却读不到」，
    静默退回未签名；`secrets.local.properties` 同理会让 beta 的 `DEFAULT_*` 静默变空串。
    已抽出 `loadPropsIgnoringBom(file)`（`readText().removePrefix("\uFEFF")` + `StringReader`）供两处共用
  - 三条路径本地实测通过（临时密钥库，测完已删）：BOM 版 props → `apksigner verify --print-certs` 出 `Signer #1 certificate DN`；
    仅环境变量（= CI 路径）→ 同样签名成功；无配置 → warn + 未签名 + `BUILD SUCCESSFUL`。
    `aapt dump badging` 确认产物为 `com.notifyme.android versionCode='5' versionName='0.2.0'`
- `.github/workflows/android.yml`（新增）：push `main` / PR / 手动触发 → temurin 17 + `gradle/actions/setup-gradle@v4`
  → 把 wrapper 的 `distributionUrl` 从腾讯镜像 sed 成官方源（只改工作区）+ `chmod +x gradlew`
  → 缺 `platforms/android-34` 时用 `sdkmanager` 装 `platforms;android-34` + `build-tools;34.0.0`
  → `testOpenDebugUnitTest assembleOpenDebug assembleBetaDebug` → `py_compile server/*.py` → Guard → 上传两个 debug APK（保留 14 天）
- `.github/workflows/release.yml`（新增）：push `v*` tag → 校验 tag 与 `versionName` 一致 → `NOTIFYME_KEYSTORE_BASE64` 解出 jks
  → `assembleOpenRelease`（env 注入口令）→ `apksigner verify --print-certs` → `sha256sum` 生成 `.sha256` → `gh release create --latest`
- **Guard 步骤**（`android.yml`）：`secrets.local.properties` / `keystore.properties` / `secrets.local.bat` 出现即 fail；
  `git grep -InE '<私有旧命名>' -- . ':(exclude).github'` 命中即 fail（**必须排除 `.github`**，否则 workflow 自身的正则字面量自匹配）；
  `git grep -In 'C:\\Users\\' | grep -v 'C:\\Users\\<'` 有输出即 fail（真实个人目录，占位符除外）。三条本地等价命令已验证通过
- `keystore.properties.example`（新增）：含 `keytool -genkeypair` 生成命令、四个键、以及
  「密钥库丢失 = 老用户无法原地升级、卸载会清空 JSONL」的警告；`.gitignore:11` 加 `keystore.properties`
- `README.md`：加 CI / Release / License 三枚徽章；下载指向 `releases/latest` 并提到 `.sha256`；
  iOS 段按 D4 改述为「原生查看端 + 提醒推送」并写明 iOS 不可能后台捕获；「下一步计划」重排为 ROADMAP 顺序并加链接
- `docs/BUILD.md`：「发布版签名（可选）」占位段替换为真实机制（配置优先级表、本地签名步骤、CI 自动签名与 4 个 Secrets）；目录结构补 `.github/`、`docs/ROADMAP.md`、两个 example 文件
- `docs/ROADMAP.md`：M0 勾选实际进度、决策门 D1–D4 标记已决、新增 **D5（密钥库生成与 Secrets 写入）/ D6（是否重写历史）**
  与「M0 遗留风险」小节
- `.github/ISSUE_TEMPLATE/{bug_report.yml,feature_request.yml,config.yml}` + `.github/pull_request_template.md`（新增）：
  表单强制脱敏承诺（不许贴真实聊天内容与密钥）、按捕获/A11y/分析/提醒/同步分环节、PR 自查含 open flavor 零预置与 JSONL 迁移说明
- **公开仓库二次泄露修复**：`progress.md` 曾把上次脱敏掉的字面量写回公开仓库（个人域名、真实 Windows 用户名路径、私有母仓库旧命名），
  `docs/ROADMAP.md:70` 验收项里也复述了旧命名正则。已全部改为占位/不复述。
  **但 commit `4b11a3e` 的历史内容里仍留这些字面量**（不含任何凭证，全仓 + 684 blob 密钥扫描无命中）→ 是否 `git filter-repo` 重写历史见 ROADMAP D6
- `gradlew` 在 git index 里的文件模式修为 `100755`（原 `100644`，Linux/macOS clone 后 `./gradlew` 不可执行）

**被权限卡住的两件事**（当前 gh PAT 是 fine-grained，无 administration 权限）：

- `gh secret list` → `HTTP 403: Resource not accessible by personal access token` ⇒ 无法写入 4 个 Actions Secrets，`release.yml` 暂时跑不起来
- `gh repo edit --description --add-topic` → 同样 403 ⇒ About / Topics 仍为空

两者都需要用户在 GitHub 网页上手工完成，或换一个带 administration 权限的 token。Secrets 清单与 base64 生成命令写在 `release.yml` 顶部注释里。

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

> **已展开为可执行计划：[`docs/ROADMAP.md`](docs/ROADMAP.md)**（M0–M8 里程碑 + 任务清单 + 验收口径 + 决策门 D1–D4）。
> 下面 8 项是 README 的对外表述，保留原样；执行时以 ROADMAP 为准。

1. **iOS 原生查看端 + 提醒推送**：在 iPhone 上看分析结果并收到提醒（iOS 不允许后台捕获其他 App 通知，故不含本地捕获；当前仅 PWA 查看端）
2. **跨应用通知管理**：从微信扩展到手机所有 App 通知 + PC 通知的统一管理
3. **通知之外的信息融入**：自己发出的消息等通知流外数据的接入方案
4. **隐私信息保护**：端侧处理、敏感信息识别/脱敏强化
5. **前端 UI 优化**：交互、可读性、多端适配
6. **本地 Agent 与大模型优化**：端侧模型能力、提示词与分析质量
7. **打通智能硬件**：可穿戴 / 家居等设备通知联动
8. **其他**：Issues 共建

### 可选的收尾小事（下次可做）

> 以下 5 项已全部并入 **ROADMAP M0 发布闭环**（其中版本号一项对应决策门 D2），当前状态：

- [ ] GitHub 仓库 About/Topics（仓库已是 Public）— **被 PAT 权限卡住**，见 §三.5；About 文案已拟好
- [x] 正式版 APK 挂 Releases 的自动化已就绪（`release.yml` + 徽章 + `releases/latest` 链接）— **首次发布阻塞在 Secrets**（决策门 D5）
- [ ] 可选：英文版 README
- [ ] 删除废弃的公开化暂存目录（本地另一目录，已不再使用）
- [x] 版本号已按语义升位 `0.1.3/4` → `0.2.0/5`

## 六、硬约束（务必遵守）

- 仅在 `C:\project\notifyme` 开发并提交；私有母仓库（本地另一目录）不改动
- 密钥/真实服务器地址只放本地 `secrets.local.*`，绝不入库；open flavor 保持零预置
- 不提交用户真实数据；测试数据用后即清
- 未经用户明确要求不推送/不发布新版本（普通文档提交除外，需向用户说明）
- 逆向工具链（含来源不明/第三方版权代码）不进入公开仓库
