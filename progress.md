# Progress — notifyme

> 最后更新：2026-10-10
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

当前版本：**versionName 0.2.0 / versionCode 5**（beta 包 versionName=0.2.0-test）

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

### 11. 复核与收尾（本次会话，2026-10-05）

- **D5/M0 发布闭环已完成（同日追加）**：用户提供 administration token（`.gh-token`）→
  密钥库生成于仓库外 `C:\Users\<你>\notifyme-release\notifyme-release.jks`
  （PKCS12/RSA2048/alias=notifyme，口令只在同目录 keystore-info.txt，绝不入库不入聊天）→
  4 个 Actions Secrets 写入 → About 描述 + 9 个 Topics 设置 → 推 main + tag `v0.2.0` →
  release.yml 全绿，**Release 已发布**：https://github.com/solidjoker/notifyme/releases/tag/v0.2.0
  （签名 `notifyme-open.apk` + `.sha256`，CI 内 apksigner verify 通过）。
  踩坑记录：① release.yml 最初漏了「Fetch llama.cpp v0.5.0」步骤（android.yml 有）→
  configureCMakeRelease 因 third_party 缺源失败，已补齐对齐（d5358f0）；
  ② PKCS12 密钥库 keypass 与 storepass 必须一致（keytool 会忽略独立 keypass），
  NOTIFYME_KEY_PASSWORD 已改为同 storePassword；③ fine-grained PAT 无 `actions:write`，
  `gh run rerun` 403 → 删 tag 重打触发新 run 即可；④ `gh run watch` 阻塞超过工具超时会转后台 job。
- **W1 真机回归进行中（同日追加）**：MuMu 微信已登录；beta 最新包装机 + 通知监听/无障碍/悬浮窗
  三权限就绪（踩坑：adb settings put enabled_notification_listeners 会被系统重写，必须走设置 UI）；
  引导 3 步走完，主界面/前台服务/监听连接正常；悬浮验证 ✅（debug 测试卡截图 overlay_card.png：
  卡片 + ⚡悬浮球同屏渲染正确）。待：真实微信消息验证捕获链、分析接口配置（用户选型中）。
- **M4.5 复核**：`ModelDownloadWorker`/`ModelListActivity` 的断点续传、按字节校验、退避重试、
  杀进程自动续传与全部文案（`strings.xml:266-279`）经代码复核确认早已落地，`docs/ROADMAP.md` M4 任务 5 已勾选并附证据。
  M4.6（云端 vs 端侧质量对比）仍待真机跑批。
- **英文版 README**：新增 `README_EN.md`（全量翻译，含架构图与快速开始；bash 化的命令示例），
  `README.md` 顶部加语言切换链接。
- **构建环境**：本会话沙箱为 workspace-write 且 partial enforcement，Gradle（Java 直写）被
  Windows 沙箱过滤拒绝（`.gradle/**/fileHashes.lock` 打开被拒；cmdlet 写入被虚拟化掩盖了问题），
  两次完全权限申请均超时无人审批。已把用户 `.gradle` 缓存（2.79 GB）robocopy 到工作区
  `.gradle-home/`（已 gitignore）作为 GRADLE_USER_HOME 备用，但项目内 `.gradle` 锁仍被拒，
  同日用户已切完全权限：`testOpenDebugUnitTest --rerun-tasks` 强制绕缓存真实执行，
  **243 单测全绿（0 失败 / 0 错误 / 0 跳过），双 flavor assemble BUILD SUCCESSFUL**。
- **设备状态**：MuMu `emulator-5554` 在线，微信停在密码登录页（账号 yejihaoxiaohao，等用户手动输密码）；
  真机 ebb079b5 仍未连接。W1 真机回归继续阻塞。
- **D5 仍阻塞**：`.gh-token` 不存在，等待用户提供带 administration 权限的 token。

### 12. 真机 W1 测试 + M10/M11 交付（2026-10-07 会话）

真机 ebb079b5（HyperOS, arm64, 16GB）+ 模拟器双端完成以下交付，详见 docs/ROADMAP.md 与 docs/评测-分析判定-20261007.md：

- **M10.7 升级链路真机验证**：S1 已切换为 **Laya 云端模型**（api.typesafe.ai + jev-latest；注意线上只有 jev-latest/jev-preview，旧默认 laya-multilingual 会 400）。liquidjoker「明天下午3点提醒我交方案」实测：need_action=0.76 → escalate split ✅。S2=GLM 报 401（secrets 文件中的 GLM_API_KEY 失效，待用户提供有效密钥后补 ⚡/悬浮卡视觉验证）。S2 失败降级实测正常（保留 S1 结论不崩溃）。
- **评测记录**：docs/评测-分析判定-20261007.md（M4.6/M10.4）——Laya S1 判定质量高（紧急度 8 档/超期催办/时间窗全对）；**端侧 0.5B 回显模板占位符不适合当主判定**（恒 false 漏升级），仅适合离线兜底；arm64 推理 668ms。
- **M11 控制台/对话分离**（3570f92 + e56952d，251 单测）：主界面纯对话化（☰ 菜单：控制台/刷新/清除）；控制台拆为入口卡片首页 + 采集/分析/提醒/隐私/悬浮 五个子页；通知来源白名单模式 + 应用选择器；对话详情页分析进度条；来源 App 全量显示（含微信）；App 改名 **notifyme**（beta=notifyme·测试）。
- **M12 分析范围筛选**（周期 近1月/近7天/今天/自定义 + 对象筛选 + 进度条）：设计完成待实现。
- 真机测试发现并修复：llama KV cache（557f640）、强制分析去重写反+名单未豁免（81cc283）、S1 模型名错误（jev-latest）。
- 白名单测试案例：注入「限时5分钟」「昨天未跟进」两条合成消息实测，Laya 判定 need_action 0.98/importance 7.99/due today 全部符合预期（测试数据已清理）。

> ⚠️ 10-08 勘误：本节两处结论已被推翻——
> ① **S1 的正确形态是 PC 上的 laya-local 服务**（FastAPI，`127.0.0.1:8124`，手机经 `adb reverse tcp:8124` 访问，
>    模型 laya-multilingual）；api.typesafe.ai + jev-latest 是**可选云端**备用（用户概念纠正 m01777）。
> ② **GLM 密钥有效**（slot_glm_key 截断 bug 是取 key 时 Substring(11) 多了 `=`，正确 Substring(12)），
>    S2=glm-5.3-flash 已真机跑通（见 §三.13）。

### 13. 真机持续验证 + 夜间分析停摆根因 + 三项修复（2026-10-08 会话，当前暂停）

**全链路真机验证 ✅**（ebb079b5 + laya-local + GLM）：
- liquidjoker「今天下午1点要完成周报的检查」：prefilter p=1.00 → S1 laya（need_action_prob=0.11，
  importance=4.5，due_window=later，topic=notice）→ escalate p=0.50 → S2 GLM → SUCCESS → 主页 ⚡1；
  此前 §三.12 的班级群案例（S1 0.78 → GLM S2 升级 + 悬浮卡实拍 card_real.png）同样成立。
- 用户在手机上自行完成了采集配置（设备数据事实，勿当测试噪声）：
  `app_sources.xml` **mode=whitelist，白名单=[com.tencent.mm 微信, com.alibaba.android.rimet 钉钉, com.ss.android.lark 飞书]**，
  黑名单勾了 10 个（剪映/招商银行/京东/小宇宙/抖音 dreamina/短信 mms 等）。

**问题与根因（用户报告 m00168）**：
1. 「消息能收到，但点分析没有反应」→ **根因 = MIUI/HyperOS 夜间静默丢弃 WorkManager 的 JobScheduler 注册**：
   进程因前台服务存活、WorkManager 永远干等（analysis.jsonl 卡在前夜 21:21 后再无记录 ≈10 小时；
   重启进程后同一次点击立刻全链跑通；debuggerd/线程转储被 MIUI 屏蔽，dumpsys androidx.work 返回空）。
2. 「不采集指定 App，其会话就不要显示在主页」→ 主页此前显示所有历史会话，与采集开关脱节。
3. 附加发现：重装/更新后 HyperOS 不会自动重绑 NotificationListenerService（settings 有记录≠已绑定），
   需 `cmd notification disallow_listener` + `allow_listener` 强制重绑；已用 `cmd notification post` 做端到端探针验证。
4. a11y 直读把自己进度通知抓成 51 条「采集」噪声入库（source=a11y-extract，pkg=自身）。

**已完成的修复（代码全部落盘，258 单测全绿，APK 已构建）**：
- **ScheduleSelfHeal（新文件 `app/src/main/java/com/notifyme/android/ScheduleSelfHeal.kt`）**：
  `ScheduleSelfHealCore.shouldRepair` 纯决策（阈值=max(3×周期, 30min)、冷却 15min、anchor=max(lastAnalysisTime, 进程出生时间)）
  + 执行侧 `repairIfNeeded` 用 UPDATE 重新 `enqueueUniquePeriodicWork` 向系统重新注册 job。
  接入点：`KeepAliveService` 看门狗 60s 巡检 + `AnalysisScheduler.enqueueAnalysisNow` 开头（用户点按抢先救活）。
  新增 `ScheduleSelfHealCoreTest` 7 用例。
- **主页按采集状态过滤**（`MainActivity.refreshMessages`）：visibleGroups 增加
  `!AppSourceRegistry.isBlocked(pkg) && AppSourceStore.isEnabled(ctx, pkg)`；`tvEmpty` 判空改用 visibleGroups。
- **分析侧同口径**（`AnalysisWorker.doWorkExclusive` 候选链）：isBlocked 恒不分析；非 force 时未启用 App 不进候选。
- **自采集噪声根除**（`MessageStore.append` 统一收口）：自身/系统包永不入库（监听器之外写路径兜底）。
- **可诊断性**：`AnalysisWorker` 每轮开始打日志「本轮分析开始: 入选 N 个会话 (候选 M 个, force=…)」。
- 设备数据清理：messages.jsonl 133→79 条（删自身「采集」噪声 + Shell 测试会话；用户真实微信消息未动）。

**flavor 误装事故（教训）**：`assembleOpenDebug` 产物 `notifyme-open.apk` 的 applicationId 是**干净的
`com.notifyme.android`**（`.test` 后缀属于 **beta** flavor）；把它 install -r 到用户手机上会**新装一个平行包**，
`Success` 但已装的 `com.notifyme.android.test` 纹丝不动（lastUpdateTime 不变即此征兆）。误装的 open 包已卸载。
真机验证一律用 `assembleBetaDebug` → `app\build\outputs\apk\beta\debug\notifyme-test.apk`。

**暂停点（用户 m00447「先暂停」）**：
- beta 新包（含上述全部修复）`app\build\outputs\apk\beta\debug\notifyme-test.apk` 已构建成功；
- 设备 `com.notifyme.android.test` 仍是 10-07 20:20 旧包，**尚未完成新包装机与验证**；
- 期间 HyperOS 拦装机 `INSTALL_FAILED_USER_RESTRICTED`，用户已手动开启「USB 安装」，
  但那次 Success 是 open 包（见上），beta 包尚未确认落机。

**恢复后的待办（按序）**：
1. `adb -s ebb079b5 install -r app\build\outputs\apk\beta\debug\notifyme-test.apk` →
   核对 `lastUpdateTime` 变化 + `pm path`；重绑监听（disallow/allow）+ `adb reverse tcp:8124 tcp:8124` + 确认 PC laya-local 在跑。
2. 真机验证：主页只剩微信/钉钉/飞书会话（即梦/短信/叮咚买菜等应消失——旧包实测还显示着）；
   点 🔍 出现「本轮分析开始」日志；⚡ 角标与悬浮卡回归；观察 60s 看门狗无崩溃。
3. 自我修复路径回归：等 lastAnalysisTime 新鲜时 watchdog 不打日志（不误触发）即可（逻辑由单测覆盖）。
4. M12 分析范围筛选实现（周期 近1月/近7天/今天/自定义 + 多会话选择 + 显式范围跳过去重/名单，设计见 ROADMAP §十一）。
5. 评测文档补充：GLM S2 成功样本、laya-local vs JEV 敏感度对比、M11.4/M11.5 记录。
6. 低优先：端侧 0.5B few-shot prompt 调优。
7. 本会话修复代码**未提交**——真机验证通过后提交+推送（用户惯例）。

### 14. 悬浮面板「最近待办」与主页联动修复 + 三项修复真机验证全部通过（2026-10-09）

- 用户报（m00484）：悬浮球面板「最近待办」与主页数据不联动。根因：旧 `OverlayService.togglePanel` 直接
  `AnalysisStore.readRecent(500).filter{s1NeedAction}.take(8)`，完全不看主页可见性口径——已删/停止采集/不关注会话的
  陈旧待办照常显示，且同会话多条历史记录挤占前 8。
- 修复：新增 `TodoPanelCore.selectTodos(latestByConvKey, homepageVisibleKeys, limit=8)`（纯函数+7 单测）；面板改为与
  MainActivity 同口径：有消息 && 非不关注 && 非黑名单 && App 在采集，同会话只认最新一次结论，按 analyzedAt 倒序。
- 真机验证（ebb079b5，beta 0.2.0-test，lastUpdateTime=2026-10-09 15:00:44）：
  1. 主页采集过滤 ✅（只剩微信白名单会话，短信/叮咚/即梦/采集噪声消失）。
  2. 「立即分析」✅：`AnalysisWorker: 本轮分析开始: 入选 1 个会话 (候选 27 个, force=liquidjoker` → S1 laya 打分
     `confidence=0.17<0.5` → S2 GLM 升级 → 本轮分析完成 → WM-WorkerWrapper SUCCESS（§三.13 待办 2 闭环）。
  3. 面板联动 ✅：数据端 join（analysis.jsonl 76 条 × 可见 26 会话）新口径应仅 1 条「Ashley钰涵」，旧代码会显示
     李志恒/班级群 2 条已不在主页的会话；实机点球→面板单行窗 `[0,1644][1220,2089]`（几何预估 445px 吻合）→点行
     打开会话页=Ashley钰涵（⚡需要行动 73%、最近分析 10-07 12:52，与主页 ⚡1 一致）→面板自动收起 ✅。行点击 E2E 通。
  4. KeepAlive 前台（id=1001）运行、logcat 无 FATAL、看门狗不误报 ✅（§三.13 待办 1/3 闭环）。
- HyperOS 新坑：force-stop 后 OverlayService 不会自动拉起（悬浮球消失）——控制台→悬浮通知→「常驻悬浮球」关→开重开；
  uiautomator 只 dump 焦点窗，overlay 面板不可见，验证要用 `dumpsys input` 的 InputWindow frame；球窗 mAttrs 与实际触摸
  frame 相差 150px（INSET_PARENT_FRAME_BY_IME），点球坐标必须以 input frame 为准（本例中心 (1123,1046)）。
- 测试：OpenDebug 单测全绿（含 TodoPanelCoreTest 7 例），assembleOpenDebug+assembleBetaDebug exit 0。

### 15. 分析改走手机内模型 + 0.5B few-shot 生效 + S2 推理预算修复（2026-10-10）

- 用户改口径（m01048）：「分析 是要通过手机里配置的模型分析，改正」「不是电脑」→ 真机 S1 配置从
  `local`（PC laya-local，经 adb reverse，依赖电脑）切为 **`local_model`（端侧 Qwen2.5-0.5B）**，
  S2 仍走手机里配置的 GLM 云。切换法：改 `shared_prefs/analysis_config.xml` 的 s1_type（/data/local/tmp 中转 cp）。
- 端侧 S1 原缺陷：0.5B 把 system prompt 里的 schema 占位符原样复读（raw=`0.0-1.0` 等）→ 解析不出 JSON →
  旧兜底给 confidence=1.0「高置信无需行动」→ 永不升级，分析名义通、实际恒空转。
- 修复三件（`AnalysisCase.kt` / `AnalysisParsing.kt` / `AnalysisWorker.kt`）：
  1. 新增 `S1_LOCAL_SYSTEM_PROMPT` + `buildS1LocalSystemPrompt()`：中文指令 + **两个 few-shot 样例**
     （工作消息→need 0.9/imp 7.0/today；闲聊→0.05/1.0/none/smalltalk），并明令「不要把占位文字抄进答案」；
     `runS1Local` 换用该 prompt（原与云端共用 schema 指令版）。
  2. `parseS1Result(payload=null)` 置信度 **1.0 → 0.0**：解析失败绝不能定案，必须走升级闸
     （`confidence < 0.5`）交给 S2 重判；同步改 `AnalysisParsingTest`（原断言 1.0/不升级）。
  3. S2 HTTP `max_tokens` 1024 → **4096**（glm-5.3 推理模型的 reasoning_tokens 也占额度，1024 被思考段
     吃满 → content 空串 finish_reason=length，真机复现；与 AdvisorWorker 同口径）。
- 新增 `AnalysisCaseTest` 2 例（few-shot 样例存在性、背景段追加）；OpenDebug 全量单测 exit 0。
- 真机验证（ebb079b5，beta 0.2.0-test，lastUpdateTime=2026-10-10 10:42:58，s1_type=local_model）：
  1. 第一次 chip（姜倩）：端侧加载模型 `model loaded n_ctx=2048 threads=6`，S1 输出
     `topic=smalltalk` 真实枚举值（非占位复读）；confidence=0.0 → 升级闸触发 → S2 GLM → SUCCESS。
  2. 提 max_tokens 后第二次 chip（同会话）：**S1 端侧输出 `need_action_prob=0.9, importance=7.0,
     due_window=today, confidence=0.9` 完整真值 JSON** → 升级 → S2 产出真实摘要
     「姜倩改约周二晚8点，另商议加隔板」+ due_time「周二晚上8点」+ 2 条 tasks → WM SUCCESS。
  3. 全程无 adb reverse、无 PC 参与——分析链路已在手机内闭环（S1 端侧 + S2 手机内配置的 GLM）。
- 遗留观察：0.5B 的 need/conf 数值仍不稳（同会话两次分别 conf 0.0 与 0.9），靠升级闸兜住质量下限；
  进一步调优（few-shot 迭代/更大端侧模型）仍是低优先 ROADMAP 项。
### 16. 主页「关注」chip 点后进 DiffUtil 盲区不刷新 → Header 携带关注态（2026-10-10）

- 用户报（m01251）：「点击 关注，能让对话提前，但标记仍然是『关注』，应该是『已关注』」。
- 根因：FeedAdapter 的 DiffUtil areContentsTheSame 比较 FeedItem.Header 数据类，而 Header 字段里**不含关注态**——点关注后该行只是分组/位置变化、数据完全相同 → DiffUtil 判 move 不判 change → RecyclerView onMove 不重绑 → chip 文案停留旧值。
- 修复（5 处）：
  1. WatchlistStore.kt 新增纯函数 isWatchedId(keyId, unwatched, watched)：keyId !in unwatched && (watched.isEmpty() || keyId in watched)；哨兵场景传**含 SENTINEL_NONE 的原始集**（仅哨兵→恒 false），isWatched 委托它。
  2. MainActivity.kt FeedItem.Header 加 val watched: Boolean（注释写明 move-not-change 根因）。
  3. buildFeed：watchedRaw = getWatched(this)（原始集），去哨兵副本仅用于分区。
  4. Header 构造填 watched = WatchlistStore.isWatchedId(key.id, unwatchedIds, watchedRaw)。
  5. bindHeader chip 改读 header.watched（不再 bind 时现查 prefs）。
- 新增 WatchlistIdTest.kt 4 例（空名单=全关注/黑名单优先/包含判断/仅哨兵=全不关注）；OpenDebug 全量 **262 单测 exit 0**，assembleBetaDebug 成功。
- 真机验证（ebb079b5，beta lastUpdateTime=2026-10-10 11:36:32）双向：米家「⭐ 关注」点击→上移且文案即时变「⭐ 已关注」；姜倩「⭐ 已关注」点击→分区 5→4 即时更新；「⭐ 关注」chip 渲染于其它会话行——move 场景重绑生效。
- **复原教训（HyperOS）**：run-as 写回 shared_prefs 的正确序列 = push /data/local/tmp → force-stop → **立即** cp → monkey；force-stop 后留 sleep 会被 KeepAlive 拉起旧进程用内存 prefs apply 覆盖回磁盘（UI 恒旧值）。测试副作用已全部复原：watched 9 项 / unwatched 3 项，与测试前逐字一致；tmp/ 内含会话名的 dump 已删。
- 注意：UI 点「关注」再点「取消关注」会让中性会话进黑名单（toggleWatch 语义不对称），无法纯 UI 回中性——批量测试后用 prefs 写回复原；WatchlistActivity 补双清未做（低优先）。


### 17. 日历自动创建功能真机验证通过（2026-10-10）

- 触发条件：S1 判定 need_action=true && reminderEnabled=true → CalendarHelper.createReminder（dedupKey=caseId）。
- 真机验证（ebb079b5，HyperOS）：pm grant READ/WRITE_CALENDAR → 小米 Calendar 账户（access_level=700）→ **L1 直接写入系统日历**：
  - 事件 `_id=1860`，title=「任务：会话讨论规则改后时间从晚上改为凌晨…（姜倩）」，dtstart=周二晚 8 点（S2 判定）。
  - CalendarContract.Reminders 行：`event_id=1860, minutes=15, method=1`（15 分钟提前弹窗提醒）。
  - reminders.jsonl 落库：`status=calendar, note=成功：已写入系统日历`。
- 设置页 [ON] 开关与 prefs `reminder_enabled` 一致；force-stop 后权限需重新 pm grant（HyperOS 行为）。
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
> 当前状态（2026-10-10 更新）：**W1 真机端到端已打通**，并按用户口径改为**全程手机内分析**（S1=端侧 Qwen2.5-0.5B + few-shot，S2=手机内配置的 GLM；不再依赖电脑 laya-local，见 §三.15 真机实测 ✅）；**M9 真机已验**（悬浮卡实拍 card_real.png）；**M10/M11/M11.4 已交付**；排程失联自愈 + 主页采集过滤 + 自采集噪声根除 + 悬浮面板「最近待办」与主页联动修复**全部真机验证通过（见 §三.14，§三.13 待办 1-3 已闭环）**；M12 分析范围筛选设计完成待实现；评测文档待补充；**0.5B few-shot 已完成并真机验证（§三.15，端侧 S1 已产出真实判定 JSON）**；本会话修复已随验证通过提交并推送；M0 代码与 CI 侧已完成，只剩 D5 正式密钥库 + 4 个 Secrets（等 `administration` 权限 token）。剩余待办：M12 分析范围筛选、评测文档补充、0.5B few-shot（低优先）；执行时以 docs/ROADMAP.md 为准。
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
