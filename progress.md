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
**后续约定（D5）**：用户会提供一个带 `administration` 权限的新 token，写进仓库根 `.gh-token`（已加入 `.gitignore`，避免 token 进聊天记录或提交），
由助手完成全流程：生成正式密钥库（存仓库外）→ 写 4 个 Secrets → 设 About/Topics → 打 `v0.2.0` tag → 核对 Release 产物。
**D6 已定**：接受现有 git 历史，**不重写**（不做 `git filter-repo`、不 force push）——旧字面量只是个人域名/用户名与旧私有命名，无凭证价值，风险已记档在 ROADMAP。

### 6. M1 测试与质量地基（本次会话·已完成）

9.5k 行代码此前零测试，而 M2 要动用户数据 schema —— 先把会被改动的数据层与纯逻辑层罩住。

**结果：142 个单元测试全绿**（`testOpenDebugUnitTest`，0 failures / 0 errors / 0 skipped，约 2s），`assembleOpenDebug assembleBetaDebug` 仍 `BUILD SUCCESSFUL`。

改动的文件：

- `app/src/main/java/com/notifyme/android/JsonlStore.kt`（新增，92 行）：`internal class JsonlStore(val file: File)`，
  把「JSONL 文件怎么读怎么写」从各个 store 里抽出来 —— `exists/delete/appendLine/appendLines/readRawLines/forEachRawLine/overwrite/rewriteRaw`。
  不加锁（锁仍留在各 store 的 `@Synchronized` 上）；`appendLines` 空列表不碰文件、自动建父目录；`overwrite` 空列表 = 删文件（绝不留 0 字节）；
  `rewriteRaw` 在「一行都没删掉」时完全不写文件（避免无谓的 mtime 抖动与写放大）
- `app/src/main/java/com/notifyme/android/MessageStore.kt`：抽出 `MessageStoreCore(store, onChange)`，由 `MessageStore.coreIn(dir, onChange)` 构造 —— **core 只吃 `File` 不吃 `Context`，所以 JVM 单测无需 Robolectric**。
  顺带把 `mainHandler` 改成 `by lazy { Handler(Looper.getMainLooper()) }`（否则类一加载就抛 `getMainLooper not mocked`）；
  新增顶层 `internal fun chatMessageFullKey(m)` —— M2 给 `ChatMessage` 加 `pkg` 时只改这一处，两个 store 的删除匹配口径自动同步
- `app/src/main/java/com/notifyme/android/PendingQueue.kt`：同样抽出 `PendingQueueCore(store)` + `coreIn(dir)`；object 层缓存 dir→core，
  保证 `nextId` 计数器跨调用存活（原来每次调用都从文件重算）
- `app/src/main/java/com/notifyme/android/AnalysisParsing.kt`（新增）：把 S1/S2 的防御式解析与 JSON 抽取从 `AnalysisWorker`（CoroutineWorker，单测拉不起来）里搬出来，
  `AnalysisWorker.kt` 由 744 行降到 667 行；`TAG` 仍是 `"AnalysisWorker"`，logcat 过滤习惯不变。
  唯一的行为加固：S2 摘要兜底从 `messages.last().text` 改成 `lastOrNull()?.text.orEmpty()`（空窗口不再 `NoSuchElementException`）
- `app/build.gradle.kts`：`testImplementation(junit:4.13.2)` + `testImplementation(org.json:json:20250107)`
  （mockable android.jar 的 `org.json` 是 stub，AGP 把它排在类路径最后，真库胜出）+ `testOptions { unitTests.isReturnDefaultValues = true }`
  （让 `android.util.Log` 返回 0 而不是抛 `not mocked`）。**`kotlinx-coroutines-test` 暂缓**：本轮没有 suspend 路径被测，等 M4 要测本地引擎协程链时再加
- `app/src/test/java/com/notifyme/android/` 新增 6 个测试类：`JsonlStoreTest`(14) / `MessageStoreCoreTest`(29) / `PendingQueueCoreTest`(20) /
  `AnalysisCaseTest`(33) / `AnalysisParsingTest`(27) / `ForkPrefilterTest`(19)。JUnit 4 + `TemporaryFolder`，零 Robolectric。
  覆盖重点是**删除路径**（M2 会把会话键升级成 `(pkg, conversation)`，删错数据是最贵的 bug）：
  每条删除都断言返回条数、剩余内容、文件是否被删、以及「无命中时不重写文件也不发通知」
- `.gitignore`：加 `.gh-token`（token 递交文件，绝不入库）

**跑测试时暴露的 6 个失败全部是测试自身写错，没有一个是生产 bug**（说明重构语义保住了）：

1. `{"a":1} 和 {"b":2}` 期望解析失败返回 null，实际拿到第一个对象 —— **org.json 的 `JSONObject(String)` 解析到第一个完整对象就停、忽略尾部残留**，改断言为「只取第一个完整对象」
2. 删除通知计数把 `append` 的通知也算进去了 → 断言前先把计数器清零
3. 造数据时「别的天」与「这天」的时间戳只差 60 秒，`dayKeyOf` 按本地日历格式化后是同一天，两条都被删 → 改成相差一天，并加 `assertNotEquals(dayKeyOf(keep), dayKeyOf(drop))` 兜住前提
4. `PendingQueue.readAll()` 按 **id 升序**、`MessageStore.readRecent()` 按 **时间倒序**，同样数据的期望顺序不同 → 分别按各自口径写

**记录在案的行为差异**（原实现语义，M1 不动，测试显式钉住，统一到 M2 决定）：

- 损坏行：`MessageStoreCore.rewrite` **原样保留**解析失败的行；`PendingQueueCore.rewrite` 按解析后对象**重新序列化**，损坏行会被丢弃
- 未覆盖：`ForkRecord.filtered/protocol` 落库字段、`CalendarHelper` 三级降级链（不参与 schema 变更，顺延到 M2）；detekt/ktlint 基线未上

### 7. M2 跨应用通知管理（本次会话·已完成，2026-10-05）

从「微信专用」改为「多应用通知统一管理」，全程身份模型升级为 `ConvKey(pkg, conversation)`。

- 提交链（均未推送）：
  - `79a16c3` M2.1 schema v2：`ChatMessage` 加 `pkg`（默认微信）+ `appLabel`，读时回填；微信/飞书/钉钉三源解析器
  - `d758a69` M2.2 监听器：`WeChatNotificationListener` → `NotifyMeListener`，按 `AppSourceStore` 启用集合过滤
  - `9263b95` M2.3 a11y：`WeChatA11yExtractService` → `A11yExtractService`，仅注册表带 a11yConfig 的源直读（目前仅微信）
  - `dc99281` M2.4 全链路 ConvKey：MainActivity/ConversationActivity/各 store/提醒链全部复合键化，删除 String 兼容重载
  - `7b154b0` M2.5 服务端/PWA：pkg 入库回填、`/messages` 与 `/analysis` 支持 pkg 过滤、去重索引加 pkg 维度、控制台 App 角标与筛选
  - `56581ed` M2.6 来源管理 UI：`SourcesActivity` 勾选启停，控制台入口
  - `4d304bf` M2.7 文档收尾：M2 归档、M9 写入、架构图 01–03 重渲染
- 新增/重写文件：`AppSourceRegistry.kt`（包名→parser/可选 a11yConfig 路由）、`AppSourceStore.kt`（启用态+已观察来源）、
  `ConvKey`（id=`pkg|conversation`）、飞书/钉钉走 `PrefixImParser`、未收录 App 走 `GenericNotificationParser`、
  `PromptStore` 改 ConvKey 键、`SourcesActivity` + 两个布局
- 折叠态日期 key 改为 `ConvKey.id + U+0001 + dayKey`（避开会话名里的 `|`）
- 测试：189 → **201**，全绿；`SchemaV2Test` 覆盖同名会话跨 App 删除隔离，
  `NotificationParserTest` 覆盖飞书/钉钉合成样本；双 flavor assemble 通过
- 遗留：飞书/钉钉真实通知形状未在真机取证（解析器刻意保守）；真机端到端回归留下一轮设备调试；
  用户新增悬浮需求已写成 **M9**（悬浮卡片 + 常驻悬浮球 + SYSTEM_ALERT_WINDOW 降级链）

### 8. M3 隐私脱敏（本次会话·已完成，2026-10-05）

数据出设备前可选脱敏，三档策略 + 全云端链路接线，本地身份与去重语义不变。

- 提交（未推送）：
  - `b21f067` M3.1 脱敏内核 `RedactorCore`：手机号/身份证/银行卡/邮箱/URL/金额/地址/姓名 8 类规则，
    类型占位符（`[手机号]` 等）保留结构，纯 JVM 27 个单测
  - `ce6468a` M3.2-M3.6：三档策略 + 控制台隐私卡 + AnalysisWorker/SyncWorker 接线 + 脱敏预览页
- 三档：**关闭**（默认，升级不改变行为）/ **出设备脱敏**（云端 S1/S2 与上报在发送一刻替换）/
  **仅端侧分析**（云端分析与三条上报通道整体停用）
- 姓名用稳定哈希别名：`[人名xxxxxx]`（SHA-256 前 6 位），窗口内同一人始终同一别名；
  非加密强匿名，取舍写进 `PRIVACY.md`
- 接线口径：云端用 `redactCase` 副本（正文/发送者/会话名都替换），本地模型/prefilter 用原文；
  caseId/pkg/windowEnd 不变；会话级提示词 background 也走脱敏；
  SyncWorker 分析通道只放行 `redacted` 记录，未脱敏旧记录隔离本机不补报
- 新增文件：`PrivacyConfig.kt`（prefs `privacy_config`）、`RedactorPreviewActivity.kt` + 两个预览布局；
  `AnalysisCaseRecord` 加 `redacted/redactionRules/redactionHits`（只存 id 与计数，不存原文）
- 测试：201 → **237**（RedactorCoreTest 30 + PrivacyModeStateTest 6），双 flavor assemble 通过
- 遗留：真机「仅端侧」抓包验证零云端请求（与 M2 真机回归一并）；
  银行卡 13–19 位数字刻意保守（快递单号也遮盖）；非发送者名单内人名无法识别

### 9. M9 悬浮通知 + 微信专项 W2–W4（本次会话，2026-10-05）

按用户「微信是现在需要重点攻克的一个」「移动端应用需要做成悬浮」两条指示，
把微信工作拆成 W1 真机回归 / W2 悬浮 / W3 微信特有能力 / W4 体验打磨四线并行推进。

- 提交（均未推送）：
  - `6e1a569` M9 主体（14 文件，+903）：`OverlayManager` 权限/降级路由、
    `OverlayService`（specialUse FGS，卡片栈 + 边缘吸附球 + 面板）、
    `OverlayConfig`（默认关卡、球开、5s 自消）、`OverlayActionReceiver`
  - `64dbbe8` M9 控制台调试测试卡按钮（仅 debug）
  - `6bd4120` W3：自发消息 me/other 视角贯穿 + 会话详情页快速回复
  - `548e5ef` W4：采集诊断页 + 标题栏一键打开来源应用
- W3 要点：
  - `ChatMessage.isSelf` / `SENDER_SELF="我"`，`A11yExtractService` 三处右侧气泡判定统一常量
  - `AnalysisCase.buildStateJson` 与 `latest_from` 由硬编码 other 改为 me/other；
    S1/S2 prompt 明确「我:」开头是用户自发消息、不产生待办，修掉自发消息误判任务
  - `ReplyActionStore`：进程内 LRU50 缓存每会话存活通知的 RemoteInput action，
    会话详情页底部回复栏按 action 存活显隐，过期有「去微信里回复」兜底文案
- W4 要点：
  - `CaptureStats` 本次启动通知漏斗（posted→blocked/disabled/ongoing/empty/
    duplicate/parseFailed→stored），监听器每个丢弃点计数
  - `MessageStore.storageStats` / `StorageStats`：原始/有效/损坏行数 + 文件字节
  - `CaptureDiagnosticsActivity` 四段只读诊断（权限服务、本次漏斗、本地存储、
    各 App 累计 + 排查提示），控制台卡 2 入口
- 测试：237 → **243** 全绿；双 flavor assemble 通过
- MuMu 模拟器（Android 15）实测：悬浮卡 900×345 正常绘制、球拖动吸附、
  面板开合、5s 自消、dismiss 移除、诊断页四段渲染均通过，无崩溃
- W1 真机端到端回归**仍阻塞**：MuMu 微信停在登录页（账号待用户手动登录，密码不经助手），
  真机 ebb079b5 未连接。待登录/连机后跑 捕获→分析→脱敏→悬浮 全链路，
  并补 MIUI「后台弹出界面」、heads-up 降级、飞书/钉钉真机通知形状取证

### 10. M4 端侧推理引擎 spike（本次会话，2026-10-05）

决策门 D3 的落地执行。第三方 llama.cpp AAR 只支持 arm64、无法在 MuMu 验证，
改为本地/CI 拉 llama.cpp v0.5.0 源码 + CMake 交叉编译，双 ABI 出 so。

- 提交（c1480b7、9d3d766 未推送）：
  - `c1480b7` M4.1：app/src/main/cpp（CMake 静态链接 CPU 后端 + llama_jni.cpp
    非流式 handle 桥：backendInit/create/complete/destroy）；
    `LlamaJni`/`LlamaCppEngine`；`LocalLlmEngines` 工厂接 GGUF S2 并优雅降级；
    Gradle ndkVersion/abiFilters/externalNativeBuild；CI 装 NDK/CMake+拉源码
  - `9d3d766` M4.2：`EngineSmokeActivity` 调试冒烟页（release finish 兜底）
- 关键设计：CPU 后端静态链接（`BUILD_SHARED_LIBS=OFF`、`GGML_BACKEND_DL=OFF`），
  单 libnotifyme-llama.so 自包含、免后端加载路径；每会话线程 max(2,cores-2)；
  每次补全新建 common_sampler（temp 0.3），采样历史不串
- strip 后 so 仅 arm64 8.1MB / x86_64 8.9MB
- MuMu x86_64 实测（Qwen2.5-0.5B-Instruct q4_k_m）：
  模型加载约 1.1s，完整补全 **4064ms**，输出连贯中文，原生链路验证通过
- 243 单测全绿；双 flavor assemble 通过
- 遗留：S2（4B/2.47GB）真机 8GB+ 内存/时延待真机 W1 取证。
  S1 已切换为 GGUF（M4.4）：原 MiniCPM4 S1 是 MNN 格式 llama.cpp 不识别，
  改用已实测的 Qwen2.5-0.5B-Instruct GGUF（469MB），离线 S1 真正可用。
  另已补（M4.3）：`LocalDeviceCapabilities` S2 准入（8GB+ RAM 且 arm64，
  引擎工厂与下载按钮双拦截）；`MainApplication.onTrimMemory`（≥MODERATE）/
  `onLowMemory` 释放引擎

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

> **已展开为可执行计划：[`docs/ROADMAP.md`](docs/ROADMAP.md)**（M0–M9 里程碑 + 任务清单 + 验收口径 + 决策门 D1–D6，均已拍板）。
> 当前状态：**M4 spike 已完成并在 MuMu 实测通过**（c1480b7/9d3d766，243 单测全绿，见 §三.10；S1 MNN 格式待换 GGUF）；**M9 代码已完成**（§三.9，模拟器实测；真机待验）；**W1 真机回归阻塞**（等用户登录微信或连真机 ebb079b5）；**M0 代码与 CI 侧已完成**，只剩 D5 正式密钥库 + 4 个 Secrets（等 `administration` 权限 token）；下一步按微信优先先做 W1 真机端到端（含 S2 4B 真机取证），随后补 M4 S1 GGUF 与准入判定。
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
