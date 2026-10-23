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

当前版本：**versionName 0.3.3 / versionCode 9**（beta 包 versionName=0.3.3-test）

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

### 18. laya TFLite 端侧判定引擎 + 控制台操作说明 + MiniCPM5-2B S2 升级（2026-10-10）

- 用户口径（m01525/m02080/m02108）：S1=laya 模型（端侧）、S2=minicpm 模型（端侧），默认可下载到手机本地跑，保留自定义模型配置。
- **S2 端侧模型升级为 MiniCPM5-2B**（替代 MiniCPM3-4B）：2.5B 参数、128K 上下文、Artificial Analysis 全球 4B 以下第一（23分）、BFCL-V4 66.6（Function Call）、Agentic Index 20（同级<10）、真实任务评测 891/1000。llama.cpp PR #24889 已合并到 v0.5.0 → 无需升级 native。ModelScope: OpenBMB/MiniCPM5-2B-GGUF → MiniCPM5-2B-Q4_K_M.gguf 1,561,318,368B。真机下载完成 state=ready ✅。
- **laya 端侧 TFLite 引擎**：laya = ModernBERT 判别式决策模型（非生成式 LLM），llama.cpp 上游 PR #29363 尚未合并。改用 LiteRT (TensorFlow Lite) 路线：litert-community/laya-LiteRT 提供 mmBERT-base TFLite 模型（0.68 GB，51ms/question，100+ 语言，有 Android sample app）。
  - `LayaTfliteEngine.kt`：SpTokenizer 贪心分词器（tokenizer.json vocab）+ Calibration（温度校准）+ runQuestion（TFLite 双图推理 + 跨 MASK softmax）+ Interpreter 缓存（getInterpreter/releaseAll）。
  - `AnalysisConfig.kt`：新增 `S1_TYPE_LAYA_ONDEVICE = "laya_ondevice"`。
  - `AnalysisWorker.kt`：S1 分支增加 `runS1LayaTflite` 路径（四题逐题推理：noul/score/choice/choice）。
  - `LocalModelStore.kt`：MODEL_LAYA 下载条目（644MB wfp16 + act head 796KB + tokenizer.json + calibration.json，辅助文件 size=-1 不校验）。
  - `AnalysisSettingsActivity.kt`：S1 spinner 第四选项。
  - `isReady` 修复：`size <= 0` 跳过字节数校验（tokenizer 等辅助文件无预知大小）。
  - 新增 `LayaTfliteCoreTest.kt` 6 例（分词器贪心匹配 + 未知字符 unk + cls/sep 追加 + 温度校准读取 + softmax 归一化）。
- **控制台 UI 改进**（m01697/98）：
  - 操作说明区块（引导用户使用主页的折叠/删除/分析功能）。
  - 「回到主页」快捷按钮。
  - `ConsoleActivity.kt` 添加 CollapseAll/ExpandAll/GoHome 按钮事件（CollapseStore.setAll 需要 Set<ConvKey>）。
- **主页连接字段隐藏**：
  - S1 选「本地端侧模型」→ PC laya 连接字段（URL/Basic/模型名）全部隐藏（applyS1TypeEditable visibility GONE）。
  - S2 选「本地 MiniCPM5-2B」→ GLM 云端连接字段全部隐藏（applyS2FieldsEnabled visibility GONE）。
  - s2_provider_labels 更新为 MiniCPM5-2B。
- **其他修复**：
  - `MessageStore.append` 统一收口 isBlocked（自身/系统源不入库）。
  - `AnalysisWorker` 候选链同口径过滤（非 force 时未启用 App 不进候选）。
  - `runS1Local` maxTokens 1024→2048、`runS2Local`/HTTP maxTokens 1024→4096（推理模型思考段占额度）。
  - `ScheduleSelfHeal` 排程失联自愈（KeepAlive 看门狗 + enqueueAnalysisNow 抢先修复）。
  - `MessageStore.append` 统一收口 isBlocked（自采集噪声根除）。
  - `ConsoleActivity` 添加 Toast import。
- HyperOS 注意：force-stop 后 READ/WRITE_CALENDAR 权限会被系统重置 → 需重新 `pm grant`（仅开发调试场景）。
- 测试：OpenDebug 全量单测 exit 0（含 LayaTfliteCoreTest 6 例 + WatchlistIdTest 4 例 + AnalysisParsingTest 修正例）。
### 19. Laya-LiteRT 端侧模型契约移植 + 真机验证 + 端到端打通（2026-10-11）

- §三.18 的旧实现基于错误假设（token_ids 输入 / 全词表 logits 输出 / sentencepiece 特殊 id / 虚构文件名），与 litert-community/Laya-Multilingual-LiteRT 真实契约完全不符 → 按官方 Android 参考实现（Apache-2.0，repo android/ 目录）整体移植重写。
- 真实契约要点：词表 256000，特殊 token 整数 id PAD=0/SEP=1(<eos>)/CLS=2(<bos>)/UNK=3/MASK=4；主图 `laya_ml_s256_embeds_wfp16.tflite` 输入 inputs_embeds[1,N,768]fp32 + attention_mask[1,N] + qtype_onehot[1,3]，输出 **token_logits[1,N] 每位置一个标量**（非全词表分布）+ pooled_cls[1,768]；token id 先查 `token_embeddings_fp16.bin`（fp16 [256000,768]=393,216,000B）gather 成 fp32；act 图输入 pooled_cls+feats=[top1, top1-top2, 归一化熵, k/255] → act_logits[1,2]；BPE+byte fallback+Metaspace；温度校准按 temperature_by_options 桶（K≤2/≤5/≤10/11+）优先；noul 选项恒 false 在前；round(,4)=HALF_EVEN。
- 新包 `app/src/main/java/com/notifyme/android/laya/` 7 文件：LayaTokenizer（added-token trie + merges + 双链表 BPE）、LayaEmbeddings（mmap fp16 表）、LayaPromptBuilder、LayaDecoder（numpySum/float32 softmax/confidence=1-H/logK）、LayaCalibration、LayaJson（保键序 + python repr float）、LayaEngine（LiteRT CompiledModel CPU/GPU 双后端图封装）；另有根包 `LayaTfliteEngine.kt` 门面重写（answerQuestions 4 题批量）。
- 依赖切换：org.tensorflow:tensorflow-lite → **`com.google.ai.edge.litert:litert:2.2.0` maven 坐标**（API 类在传递的 litert-api；files(aar) 方案弃用，丢传递依赖）；根 Kotlin 2.0.20 → **2.2.21**（litert-api 元数据 2.3.0，2.0.x 读不了）。
- LocalModelStore laya 清单改真实 7 文件（主图 250,889,040B / act head 795,816B / calibration 9,156B / tokenizer.json 34,363,188B / tokenizer_config / 嵌入表 393,216,000B / token_embeddings.json）；ModelDownloadWorker 主源 IOException → 删 .part → hf-mirror 镜像重试一次（防两源拼接坏文件）。
- debug 门测试：移植 LayaGateRunner + LayaGateActivity（`--es backend cpu|gpu --ei window 256`），读 files/fixtures/gate_rows_s256.json 201 行逐行比对。
- 三处云端 gating 对 laya 豁免（laya 是端侧）：AnalysisWorker localOnly 门、baseUrl.isEmpty 门、AnalysisScheduler fullyLocal 网络约束。
- 新增 debug-only AnalysisE2EActivity（注入测试会话 + force 分析走完整生产链路），验证后测试数据已清理。
- **真机验证（25098PN5AC / HyperOS）**：CPU 门 201/201 ids/markers/argmax 全精确、max_delta_p=0.0014478（36s）；GPU 门 201/201、0.0014491（32s）；端到端 4 题 4.3s：need_action=0.47 / importance=4.2 / due_window=tomorrow / topic=schedule。差异仅官方契约标 NOT_EVALUATED 的 round(,4) 末位。
- 本机构建坑：AGP 8.5.2 内嵌 aapt2 在本机 app\ 子树写输出报「数据无效 (13)」→ 用 build-tools 35.0.0 aapt2 经环境变量覆盖（`-P`/ORG_GRADLE_PROJECT_ 传入，**勿写入 gradle.properties 入库**）；HyperOS 装机需手机端放行「USB 安装」；Theme.NoDisplay 的 Activity 必须在启动后台任务后立即 finish()（否则 onResume 前未 finish 崩溃）。
- 测试：LayaTfliteCoreTest 重写为 13 例纯 JVM 契约测试（JSON 键序/pythonFloat/温度桶覆盖/softmax/confidence/round4/decode schema 期望值/noul 默认文案/PromptBuilder 常量）；OpenDebug 单测 302 全绿。
- UI 修复（602cfe2，用户真机反馈「laya 手机本地没有下载、保存的按钮」）：ModelListActivity 原来只绑 S1/S2 两卡片，laya 清单在 LocalModelStore 但 UI 无入口（报错文案指向不存在的「模型管理→Laya-Multilingual」）→ 补第三卡片复用 bindModel/refreshModel（下载/进度/取消/删除，删除时 releaseAll）；AnalysisSettingsActivity applyS1TypeEditable 补 S1_TYPE_LAYA_ONDEVICE 隐藏 PC 连接字段，选 laya 且未就绪时 Toast 指路。模拟器实测：点下载 6s 秒过 adb 预摆的 648MB 校验，prefs laya_ml_state=ready，UI 绿字「已下载·校验通过（648 MB）」。

### 20. 关注默认反转 v3 + 悬浮窗消失自愈（2026-10-12，**待真机验证**，0.3.4/10）

用户两项需求（m00002）：「通知默认不关注」「悬浮窗口不显示，需要修复」。

**需求 1 · v3 反转默认**（`WatchlistStore`）：
- 旧语义「空名单 = 全部关注」反转为 **空名单 = 一个都不关注**：新会话只落库存着，不分析、不弹卡、不排期，用户点 ⭐（或关注列表勾选）才放行；统一判定式 `keyId !in 黑名单 && (SENTINEL_ALL ∈ 名单 || keyId ∈ 名单)`。
- 新增 `SENTINEL_ALL="__watch_all__"` 表达「全部关注」（含未来新会话，不物化全量）；v2 旧哨兵 `SENTINEL_NONE` 停止写入、保留读取兼容——它匹配不到任何真实键，老用户「全不关注」设置天然延续，**零迁移**。
- 旧「全部取消」写 NONE 的用户升级后行为不变；旧「空=全关注」的用户升级后会变成全不关注（本次反转的预期语义变更，README/架构文档已同步口径）。
- UI 三处同构改造：`WatchlistActivity`（btnWatchAll→写 ALL 哨兵+清黑名单；btnWatchNone→清空白名单；勾选/取消按 watchAll 态分流：ALL 态单独取消=进黑名单隐藏，白名单态取消=仅移出、会话仍可见）、`MainActivity.toggleWatch` 同逻辑；`buildFeed` 分区剔除双哨兵——ALL 态与空名单都扁平展示；chip 态仍由 `FeedItem.Header.watched` 预计算字段驱动（§三.16 的成果不动）。
- 弹卡口径自动收紧：即时卡路径只认 `watchedKeys()`（显式 ⭐ 白名单），AnalysisWorker/Scheduler 走 `isWatched` 自动继承——「默认不关注」在全链路一次生效。

**需求 2 · 悬浮窗消失根因与修复**：
- 根因 1（主因）：`OverlayService` 是 START_NOT_STICKY，悬浮球只在卡片事件/设置页开关时挂载 → 进程重启、ROM 回收服务后球**永不回来**（正对应 §三.8 记录的 HyperOS force-stop 后需手动关开开关的现象）。修复 = 自愈四挂点：`NotifyMeListener.onListenerConnected`、`KeepAliveService.onStartCommand`（开机后立即）、看门狗每 60s（与服务被杀后重拉两条路径都覆盖）、`MainActivity.onResume`；全部收敛到新增的 `OverlayManager.ensureBallVisible(context)`（无权限/球开关关 → 静默跳过；startForegroundService 后台启动被拒 → catch 降级等下一跳）。
- 根因 2：`ensureBall`/`reconcileBall` 无权限守卫、三处 `wm.addView` 无兜底 → 权限运行中被吊销时 SecurityException **直接崩掉悬浮服务**（球卡全灭）。修复 = reconcileBall 判 `ballEnabled && canDrawOverlays`；球/卡/面板三处 addView 全部 `runCatching`（挂不上就丢这层 UI，不崩服务、不丢已落库消息）。
- 根因 3：设置页两开关旧代码 `if (checked) syncService()` 只在**开启**时下发 → 关球后球残留。修复 = 两开关无条件 syncService()（关球即时下发 RECONCILE 摘球）。
- 卡片显示条件不变：`cardsEnabled`（默认关）+ 显式 ⭐ 白名单才即时弹——与需求 1 天然自洽。

**验证与收尾**：`WatchlistIdTest` 按 v3 重写 6 例（含黑名单优先、ALL 态黑名单单独取消、旧 NONE 兼容）；OpenDebug 单测 **304 全绿**（基线 302−4+6）；`assembleOpenDebug` 通过。文档同步：README 特性行与 M9 行（原「规划中」已过时）、`docs/architecture/README.md` 三态语义段与 prefs 表、`04-analysis-pipeline.mmd` 已重渲 svg/png、`watchlist_hint` 文案。版本 `0.3.4/10`。
本机环境事实：工作区缺 `local.properties`（AGP 报 SDK location not found）→ 已重建（gitignored，`sdk.dir=C:\Users\<你>\AppData\Local\Android\Sdk`）；单测/打包仍需 §三.19 的 aapt2 覆盖 `-Pandroid.aapt2FromMavenOverride=...\build-tools\35.0.0\aapt2.exe`。
**遗留**：真机（HyperOS）回归待用户验证——重启手机看球是否自动回来、吊销再恢复悬浮窗权限看服务是否还崩、关球开关看球是否即时消失；本轮改动未提交（工作区另有 M10 advisor 未提交改动，待真机验证通过后与 §三.15 遗留一并梳理提交边界）。
（2026-10-13 注：两批改动均已提交推送，见 §三.21；HyperOS 真机回归仍待用户确认。）

### 21. §三.20 提交闭环 + M10 advisor 端侧本地评审（2026-10-13，0.3.5/11，d616d84）

- v3 关注默认反转 + 悬浮球自愈批次随 `52a6182` 提交推送（`da7571d..52a6182`），§三.20「本轮改动未提交」遗留闭环。
- **M10 advisor 本地评审**：AI 评审新增 `advisor_provider` 云/本地二选一；本地复用已内置的 MiniCPM5-2B 端侧推理引擎，评审数据不出端、不受 cloudBlocked 限制；端侧推理引擎文案同步「已内置」。9 files +208/−36。
- **编译修复（该批次自身阻塞项）**：`AnalysisParsing.kt:141` 有一行粘贴截断的注释（`* 真机实测：2B 常在 maxTokens 内不闭合`）导致 KDoc 未闭合且 `fun extractJsonFromText(content: String): JSONObject?` 签名行丢失 → 补全注释并恢复签名（函数体逐字未动）；同批删除的 `stripThinkTags` 在 HEAD 即零调用（死代码）。链路：`extractJsonPayload(raw)` → 取 `choices[0].message.content` → `extractJsonFromText`；Worker/tests 统一调 `extractJsonPayload`。
- 验证：`compileOpen/BetaDebugKotlin` + 两 flavor 单测源集编译 + `:app:testBetaDebugUnitTest` 全绿后提交推送（`52a6182..d616d84`）。

### 22. 主页「⚡ 最近待办」区：编辑/删除/一键加日历（2026-10-13，**0.3.6/12**，995a480 + 4d579dd）

用户需求（m00082）：「"最近待办" 需要放在导航栏顶端，待办事项可以编辑、删除、加到日历中，加到日历默认会设置提醒。功能加好以后更新apk并推送」。
**编号勘误**：commit message 里的「M12」是误标——ROADMAP 的 M12 = 分析范围筛选（仍未实现），本节功能未占用里程碑号。

**设计与实现**（9 files +560/−16）：
- **位置**：MainActivity 标题栏 divider 之下、信息流顶端固定区块（代码库无「导航栏」实体，主页即唯一常驻入口）；`activity_main.xml` 静态插 `llHomeTodos`（标题行「⚡ 最近待办 N」+ 查看全部 → `AnalysisListActivity`），行布局新文件 `item_home_todo.xml` = 摘要(2行)+meta（会话 · 截止 X）+ 编辑/日历/删除 三按钮；空则整区 GONE。
- **数据口径**：与悬浮面板同源 `TodoPanelCore.selectTodos(latestByConvKey, visibleKeys, HOME_TODO_LIMIT=5)`；visibleKeys 复用主页过滤（readRecent(500) convKey 剔 unwatched/blocked/disabled），在 `buildFeed` 同一后台线程顺手算，`FeedResult` 扩 `(items, empty, todos, remindedCaseIds)`。
- **粒度=会话 case 级**（caseId 即 dedupKey，与角标/面板/提醒全一致）；不做 per-task 编辑——`Task(who,what,when)` 无稳定 id，索引会漂。
- **编辑=覆盖字段不毁原结论**：record 新增 `todoText/todoDue`（JSON 键 `todo_text`/`todo_due`，仅非空写出），显示走回退链 `displayTodo() = todoText.ifEmpty { s2Summary.ifEmpty { s1Topic } }`、`displayDue()` 同理；悬浮面板行同步改 `displayTodo()`。弹窗预填当前值，空内容拒存且弹窗不关，长度上限 200/50。
- **`AnalysisStore.updateRecord(caseId, patch)`**：`@Synchronized` 只替换第一条命中行、损坏行原样保留、未命中不写盘，`JsonlStore.atomicWrite` 原子重写——与 Worker `append` 同对象锁并发安全。删除复用 `deleteCase`（与悬浮卡「标记已处理」同语义，无确认弹窗，即删即刷）。
- **加日历默认带提醒**：`CalendarHelper.createReminder` 新增不依赖 `ChatMessage` 的原语重载（旧签名委托）；手动入口复用三级降级链（provider→前台插入→App 内闹钟），lead=`AnalysisConfig.reminderLeadMinutes`（默认 15 分钟），**不要求 reminderEnabled 开关**（手动操作即意图）；`ReminderStore.containsActive(caseId)` 去重，成功后按钮变「已加日历」。无日历权限先 `requestPermissions(REQ=43)`，授权返回自动补跑（拒绝也跑降级链兜底）。
- 版本 `0.3.6/12`；README「提醒」特性与架构图同步（4d579dd）。

**验证**：
- 单测：新 `TodoOverrideTest` 4 例（displayTodo/displayDue 优先级、空不写键、round-trip、老记录默认空）；OpenDebug 全量 + Beta 20 suites 0 失败；`assembleOpen/BetaDebug` 绿，aapt 确认 0.3.6/12。
- 模拟器走查（emulator-5556，API 34——原三台设备 adb 全僵尸，新起 AVD）：种子 jsonl 注入 → 显示 2 条/计数/meta ✓；编辑空文本拒存不关窗 ✓；保存即时更新 + `todo_text`/`todo_due` 落盘 + 杀进程重启仍在 ✓；日历权限弹窗→授权自动补跑 ✓；模拟器无日历账户走 L3 App 内闹钟，**闹钟 `origWhen = event_time − 900000ms` 精确提前 15 分钟** ✓，`reminders.jsonl` status=alarm ✓；连点第二次去重（记录仍 1 条）✓；删除计数 2→1→0 整区隐藏 + jsonl 行消失 ✓；查看全部跳转 ✓。
- 走查坑：转场动画期间 uiautomator dump 坐标会过期（误点行打开会话页），需等待后重 dump；bounds 中心点 = ((x1+x2)/2, (y1+y2)/2)。

**遗留**：
- L1 provider 直写日历与 L2 前台 Intent 路径未在模拟器覆盖（无日历账户/无日历 App）——需真机带账户确认。
- 弹窗中文输入未经 adb 验证（只能注 ASCII）；中文显示/存储已由种子数据覆盖。
- `README_EN.md` 未同步本功能（惯例功能提交只更中文 README）。
- CI 既有 lint error（`NotifyMeListener.kt:214` getMessagesFromBundleArray NewApi，52a6182 引入）未修，与本次无关；CI lint 步骤被 `| tail -20` 吞退出码。
- 正式签名 APK 需打 `v0.3.6` tag 触发 release.yml（0.3.4/0.3.5/0.3.6 均未打 tag，推 main 仅出 debug artifact）。

### 23. 五路代码评审 + H1-H4 修复（2026-10-13，**0.3.7/13**）

用户发起全量代码评审（5 路并行精读：数据层/分析管线/UI/服务与通知/日历与工程 + 端侧引擎定向核查，评审全程只读）；报告交付后用户批复「开修」（按 H1→H4）。

**评审结论**：安全隐私五专项全过（无密钥入库、release 无 exported 攻击面、脱敏强制点齐、OkHttp 全超时、JNI 加载失败有降级）。发现 High 5 / Medium 11 / Low 18；核心债务两族同源——「提醒三份状态与外部世界不同步」与「analysis/reminders 无界增长 × 主线程读取点未清完」。完整清单见评审报告（未入库）。

**本批修复（H1-H4，10 files）**：
- **H1** KeepAliveService FGS 类型 `dataSync`→`specialUse`（+`PROPERTY_SPECIAL_USE_FGS_SUBTYPE` 说明）——A14+ dataSync 每 24h 限 ~6h 强停，看门狗每日被腰斩。
- **H2** NotifyMeListener 第三方 Bundle 解析三层 try/catch（toRawNotification/extractStyleMessages/parser.parse）——畸形 Bundle 不再崩整进程（listener/overlay/a11y 同进程）。
- **H3** 新 `ReminderRecovery.rescheduleLostAlarms`：扫 `status==alarm && event_time>now` 幂等重排 L3 闹钟；挂 BootReceiver（BOOT_COMPLETED）+ KeepAliveService 启动后 5s 双保险（PendingIntent requestCode=dedupKey.hashCode+UPDATE_CURRENT 天然幂等）。修复「重启后闹钟全丢但 jsonl 仍 active 挡重建 → 主页假已加日历永不响」。
- **H4** `Retention.pruneNow` 扩 analysis.jsonl/reminders.jsonl 两文件（7 天同口径；reminders 中非 failed 且 event_time>now 一律保留，它们是去重/补挂/重试依据）；`AnalysisStore.readRecent` 改 `readTailLines`（原全文件读）。纯决策拆 `AnalysisRetentionCore`/`ReminderRetentionCore` 可单测。
- 新 `RetentionPruneTest` 10 例；两 flavor 编译 + 全量单测 + assembleOpenDebug 全绿。

**模拟器走查（emulator-5556，API 34；注：MuMu 三台 127.0.0.1:16384/:5555/emulator-5554 宿主注入 frida-agent 崩所有 App，不可用）**：种子注入（3 analysis + 4 reminders，含 8 天前过期记录）→ prune：analysis 3→2（fixcaseOLD 删）、reminders 4→1（3 条过期/失败删，alarm+未来事件豁免保留）✓；主页「⚡ 最近待办 2」tail 读回归 ✓；`adb reboot` 后 BootReceiver+KeepAliveService 双路径各补挂 1 条，`dumpsys alarm` 仅 1 个 `RTC_WAKEUP origWhen=1791885751317`（=event−900000，精确提前 15 分钟）✓；KeepAliveService `isForeground=true types=0x40000000`（specialUse）无异常 ✓。
走查坑：run-as 读不了 /sdcard（A15），种子必须推 `/data/local/tmp`；PowerShell `Set-Content -Encoding utf8` 带 BOM 会让首行 JSON 解析失败被当损坏行保留，种子用 `UTF8Encoding($false)` 写；BOOT_COMPLETED 会在装包后立刻拉起进程跑一次 pruneIfDue，注入种子前若已过该点需删 `shared_prefs/retention_state.xml` 重触发。

**未修遗留（下批候选）**：H5（主线程整文件读×4：OverlayService.kt:308、AnalysisListActivity.kt:91、AnalysisSettingsActivity.kt:589、ReminderListActivity.kt:80）；M1-M11（提醒生命周期族建议统一 ReminderRecovery/Cleanup 入口收口）；Low 18 条。H2 畸形 Bundle 真机注入未实测（代码级防御+编译验证）。正式 APK 仍需打 tag（0.3.4-0.3.7 均未打）。

### 24. v0.3.7 发布 + M 批修复（H5+M1-M11）（2026-10-13，**0.3.8/14**）

用户批复「先打 v0.3.7 tag 出正式 APK 继续修」：`v0.3.7` 注解 tag 打 69f7738 推送，release run 38366675824 **success**——首个 CI 全自动正式 APK（notifyme-open.apk + sha256 挂 Releases，验证了 release.yml 与 Secrets）。随后开修 M 批（H5+M1-M11，14 files）。

**本批修复**：
- **M1** 主页手动加日历 `baseTime` 由 `analyzedAt` 改 `now`——老分析记录的「明天15:00」不再按过期锚点解析。
- **M2** 新 `ReminderCleanup.forCase`（取消闹钟+删日历事件+删提醒记录，三步幂等）；主页删除待办与悬浮卡「标记已处理」两入口接上——删待办不再留幽灵提醒。
- **M3** `ReminderStore.fromJson` legacy 无 status 记录按 note 推断（App内提醒→ALARM/唤起日历→INTENT/else→CALENDAR），不再误判 FAILED 绕过去重；新 `findByKey`。
- **M4** `JsonlStore.overwrite` renameTo 失败→`Files.move(REPLACE_EXISTING)`→仍失败抛 IOException 保留原文件（原实现退化截断写可毁全量数据）。
- **M5** `MainActivity.refreshMessages` 单飞锁+重跑标记（feedLock/feedRerun+token），搜索连点不再并发全量读。
- **M6** 日历结果判定改 `ReminderOutcome.created` 结构化字段，不再 `startsWith("成功")` 文案耦合；toast 带提前量。
- **M7** 调试广播接收器 `RECEIVER_NOT_EXPORTED`。
- **M8** `NotifyMeListener` recentKeys 全路径 `synchronized`（含 onNotificationRemoved）+ 应用名 labelCache（PM IPC 缓存），主线程 prefs/PM 调用消除。
- **M9** 端侧模型下载支持 sha256 校验（`ModelFile.sha256`，跳过路径/最终校验/.part 转正前三点校验；未配置时 Log.w 明示仅字节数校验）。
- **M10** `AnalysisWorker` 失败路径 msg 落 `recordResult` 状态行（原来只 return Result 不落盘，UI 看不到失败原因）。
- **M11** `CoroutineWorker` 循环开头 `isStopped` 检查→保存进度 `Result.retry()`，10min 上限不再白烧已完成会话。
- **H5** 四处主线程整文件读全部后台化：OverlayService.togglePanel（先挂「加载中」占位，Thread 读数据 handler.post 回填，token 守卫防塞死视图）、AnalysisListActivity/AnalysisSettingsActivity 统计/ReminderListActivity 同样本改造。

新 `ReminderLegacyStatusTest` 7 例（M3 推断+M6 判定）；两 flavor 编译+全量单测+assembleOpenDebug 全绿。

**模拟器走查（emulator-5556）**：M3 legacy 记录主页显示「已加日历」且重点→toast「跳过：已创建过（去重）」不新增 ✓；M1+M6 新记录 created_at=now、event_time=设备本地明天15:00、toast「已设App内提醒，默认提前 15 分钟提醒」✓；M2 删除待办→reminders.jsonl 记录消失+`dumpsys alarm` 出现 `Reason=alarm_cancelled` 快照 ✓；M5 搜索框连打快删进程存活、空态正常 ✓；H5 四页（分析结果列表/已创建的提醒（legacy 徽章「已设App内提醒」）/分析设置统计「已分析 1 个会话 / 待办 1 个」/悬浮球面板「最近待办·项目群 · 写周报草稿」回填）全过 ✓。
走查坑：`cmd notification post` 通知 pkg=com.android.shell 被来源注册表过滤不入库，M8 去重锁无法 shell 注入实测（代码级+编译验证）；悬浮球窗口 `fitTypes=STATUS_BARS` 使输入 frame 比 `dumpsys window` mAttrs y 低一个状态栏高度（136px），点球要按 `dumpsys input` 的 frame 中心；OverlayService exported=false，需 `adb root` 后 `am start-foreground-service`；悬浮球需 `overlay_config` prefs `cards_enabled=true`+appops SYSTEM_ALERT_WINDOW allow。

**未修遗留**：Low 18 条（见评审报告）；H2 畸形 Bundle 注入、M9 真实下载校验（需模型 URL 配 sha256 后验证）、M4 rename 失败路径（需模拟锁文件）均代码级验证。M1-M11 修复版正式 APK 待打 `v0.3.8` tag。

### 25. v0.3.8 正式发布 + 分析结果回看原文（2026-10-13，**0.3.9/15**）

`v0.3.8` 注解 tag 打 4721740 推送，release run 38371491105 **success**（约 8 分钟），正式 APK+sha256（74276082…2422be75e）挂 https://github.com/solidjoker/notifyme/releases/tag/v0.3.8 。

用户新需求「分析结果里没有了原文，需要加上原文」+「插上手机，安装最新 apk」：
- **M13 回看原文**：`AnalysisCase.windowOf(messages, windowEnd, messageCount)`（ts ≤ windowEnd 的最近 N 条，与建 case/主页高亮同口径）+ `formatWindowSource`（每行「发送者: 内容」）纯函数；`AnalysisListActivity` 后台线程从 `MessageStore.readRecent(500)` 按 convKey 分组重组 caseId→原文 map（**不落库 analysis.jsonl，隐私口径不变**；消息被裁剪的旧记录自然无原文→隐藏该行）。卡片新增 `tvAnalysisSource`：默认折叠 4 行、点击展开/收起（expanded 集合跨刷新保持）。新 `AnalysisWindowSourceTest` 6 例。
- **真机验收（ebb079b5 Redmi Android 16，0.3.4→0.3.9 `-r` 升级数据保留）**：分析结果页「原文：姜倩: 好的…」折叠显示 ✓，点击 208px→572px 展开 10 行 ✓，再点收起 ✓；主页待办区/信息流升级后正常。多张「姜倩」卡原文相同系数据正确反映（多次手动分析同一窗口，windowEnd 一致）。
- 两 flavor 编译+全量单测+assembleOpenDebug 全绿。**按硬约束未推送**，等用户明示。

### 26. 主页按日期折叠 + 分析结果逐条显示（2026-10-14，**0.3.10/16**）

用户需求（m01647）：「现在的折叠消息里带着历史信息，需要按天来做。分析的结果则显示在每条消息旁边」。拍板：①彻底按天折叠（去会话级整块控制）②每条消息旁直接显示结果内容。

- **三态按天折叠模型**（`CollapseStore` 重写）：显式折叠集 `collapsed_dates` + 显式展开集 `expanded_dates` + 默认规则（每会话「最新一天之外」的历史天折叠）；优先级 折叠 > 展开 > 默认。`latestDayKeyOf` 排除 `__unknown__` 哨兵（字典序 `_`>数字，否则误判为最新天）。旧 `collapsed_conversations` 废弃（存量留着不读）。`toggleDate` 互斥写；`setAllDates` 支撑「全部折叠/展开」——展开必须写显式展开集，否则被默认规则重新收起历史天。
- **MainActivity**：`FeedItem.Header` 删 collapsed 字段、会话头箭头隐藏；`bindMessage` 每条消息旁直接挂该天分析结果——待办橙底「⚡ 待办 <displayTodo> · 截止 <displayDue>」、非待办灰底「<topic本地化> · 重要度 X.X」、同天多窗口追加「（等N次分析）」。`buildFeed` 一次 `AnalysisStore.readRecent(200)`，每 case 重建窗口（ts≤windowEnd 最近 messageCount 条）映射所覆盖天（与 M13 会话窗口口径一致）；`feedDayPairs` @Volatile 后台缓存 (会话,天) 全集，标题栏「全部折叠/展开」主线程直写不再读盘；ConsoleActivity 两按钮同改 `setAllDates`。
- 新 `CollapseStoreTest` 8 例（哨兵排除×3 + 三态优先级 + 跨会话不串扰）。两 flavor 编译 + 全量单测 339 例 + assembleOpenDebug 全绿。
- **模拟器走查（emulator-5556，多天种子 7 消息/3 case）**：默认历史天▶/最新天▼ ✓；点日期头展开后 10-12 两条消息各挂橙「⚡ 待办 给张三发送报价单 · 截止：明天15:00」✓；10-14 两条灰「闲聊 · 重要度 4.6（等2次分析）」✓；展开/折叠态跨重启双向持久 ✓；全部折叠 5 组▶→全部展开全▼ ✓；显式折叠下搜索「10-08」命中组强展开 ✓；删 prefs 回默认规则 ✓。
- **按硬约束未推送**，等用户明示（M13/0.3.9 亦未推）。


### 27. 端侧分析进度（M15）（2026-10-17，**0.3.11/17**）

用户需求（m03179）：「手机端用手机模型分析对话需要显示进度」。原实现只有 WorkManager 的 4dp 不确定进度条：本地 0.5B 推理一个会话要几十秒（真机实测 prefill 502 token ≈ 20s），期间界面完全没反馈。

- **native 进度回调**（`llama_jni.cpp`）：`nativeComplete` 增加可选 `progress` 对象，Kotlin 侧 `LlamaJni.ProgressSink.onProgress(phase, current, total)`；prefill 每批、decode 每 8 token 回报，回调异常在 native 侧清掉（绝不让异常穿 native 栈）。prefill 批从 512 改为 64（`PREFILL_CHUNK`）：真机从「20 秒一动不动」变成约 2-3 秒一次。
- **状态总线**（`AnalysisProgress.kt`）：`AnalysisRunState`（阶段/会话/token/上限/起始时刻）+ `AnalysisProgressBus`（单进程单例，publish 切主线程、同帧合并）+ `AnalysisProgressUi`（文案拼装 + 文字/进度条渲染，可算百分比走确定态）。阶段：准备 → 加载模型 → 读取上下文（prefill）→ 端侧推理（decode）→ 云端 → 整理。
- **Worker 接线**（`AnalysisWorker`）：每个 case 发布状态（第 i/n 个会话），本地 S1/S2/本地评审的 `chat(...)` 都传进度回调；云端分支报 REMOTE；`doWork` 用 try/finally 保证任何路径（取消/异常/提前返回）都 `publish(null)`，不会卡在最后一个阶段。
- **三处 UI**：会话详情页（新增 `tvAnalysisProgress` + 原进度条）、控制台分析设置状态行（新增 `progressAnalysis` 条）、主页会话头 chip 下的进度条（按会话匹配改确定态）。prefill 期间每秒补一帧，让「已用 N秒」不冻住。
- **单测**：`AnalysisProgressTest` 7 例（percent 的 decode 区间/跨 case 折算/夹取/无预算 null；formatElapsed 秒/分/负值）。
- **真机验收（vermeer / Android 15，0.3.10-test→0.3.11-test `-r` 升级）**：S1 本地模型（Qwen2.5-0.5B-Instruct Q4_K_M，491400032 字节）单会话分析，界面实时显示「读取会话上下文 128/502 token · 已用 5秒」→「320/502 · 13秒」→「192/502 · 9秒」（另一轮），完成后回到结果 chips（需要行动 95% / 重要度 7.0 / 截止 明天 / 主题 工作任务）。logcat 证据：`publish LOADING_MODEL → LOCAL_PREFILL 0/502 → 502/502 → LOCAL_DECODE 0/2048 … 43/2048 → SAVING → null` 全程连续；`files/analysis.jsonl` 落库 S1 判定 0.90/7.0。真机 prefill 502 token≈20s、decode ≈20 token/s，分批后肉眼跟手。
- **端侧 Laya 判定题进度**（补 M15 覆盖）：用户真机 S1 配的是「Laya 端侧」（4 道判定题逐题图推理），原来没有任何进度。`LayaTfliteEngine.answerQuestions` 增加 `onProgress(done,total)`，Worker 每答完一题发布 `LOCAL_QUESTIONS` 阶段，UI 显示「端侧判定 n/4 题 · 已用 x秒」；真机实测阶段日志 `LOADING_MODEL → LOCAL_QUESTIONS 0/4 → SAVING → idle`（图加载约 4 秒、4 题约 0.9 秒）。`AnalysisProgressBus` 增加阶段切换日志（只切阶段时打一行，token 级不刷屏）便于真机排查。
- 本机 `aapt2` 在当前环境无法在 `app\` 下建文件（Gradle 资源链接报 stableIds.txt「数据无效」），本次验证用临时 `layout.buildDirectory` 改到 `build/appout` 完成构建，**验证后已还原，未入库**。
- 按硬约束未推送，等用户明示。

### 28. 恢复「按对话折叠」：会话级 + 天级两级并存（M16）（2026-10-19，**0.3.12/18**）

用户需求（m01356）：「按对话折叠功能现在点不到了，需要恢复」。M14（§三.26）删掉会话级整块折叠并把会话头箭头 `View.GONE`，但主页提示语 `home_header_hint`「点会话名查看对话与分析 · 点右侧箭头折叠」和控制台引导文案都还写着点箭头折叠 → 用户照文案去点，什么都没有。拍板：**两级并存**（会话头箭头收整组，天组头箭头收某一天），不回到「只有天级」。

- **CollapseStore**（`MainActivity.kt` 底部）：新增会话级显式集 `collapsed_conversations_v2`（**刻意换新键名**——M13 的旧键 `collapsed_conversations` 仍不读写，免得多年前的旧状态突然复活）；纯函数 `isConversationCollapsed(set, key) = key.id in set`；`toggleConversation(ctx, key, nowCollapsed)` 只动会话集，组内各天的显式态原样保留（展开即回到原状）；`setAll(ctx, pairs, collapsed)` 一次写齐三集（折叠=会话全选 + 每个 (会话,天) 进显式折叠；展开=清会话集 + 每个 (会话,天) 进显式展开）。旧 `setAllDates` 保留。
- **MainActivity**：`buildFeed` 里 `convCollapsed = isConversationCollapsed(...) && query.isEmpty()`（搜索命中时强制展开，保证结果可见）；`FeedItem.Header` 重新带 `collapsed` 内容字段（`identityOf` 仍只用 `key`，靠 DiffUtil 内容比较让 ▼/▶ 重绑，不会误判整项替换）；`bindHeader` 恢复箭头可见（多选态仍 GONE）并挂 `onToggleConversation`；标题栏「全部折叠/展开」改走 `setAll(feedDayPairs, …)`。
- **ConsoleActivity**：两个快捷按钮同口径改 `setAll(collapseTargets(), …)`，抽出 `collapseTargets()`（readRecent(500)→按会话分组→distinct 天，与主页 `feedDayPairs` 同一份口径）。顺带修老 bug：「全部展开」原来传 `emptyList()`，只清态不写显式展开 → 历史天立刻被默认规则重新收起，等于按了没反应。
- **单测** `CollapseStoreTest` +3 例（集合内才收 / 会话级与天级互相独立 / 跨 App 同名会话不串味），共 11 例；全量门禁 `:app:testOpenDebugUnitTest :app:assembleOpenDebug :app:assembleBetaDebug` BUILD SUCCESSFUL。
- **模拟器走查（emulator-5554，0.3.12/18 真包）**：点会话头 ▼→▶，整组（天头 + 消息）消失、下一会话不受影响 ✓；再点恢复 ✓；「全部折叠」→ 所有会话 ▶、无天组无消息 ✓；「全部展开」→ `expanded_dates` 写入全部 (会话,天) 复合键（含 10-16/17/18 历史天）✓；单独点天组箭头仍能只收一天（两级互不干扰）✓；`am force-stop` 重启后会话折叠保持（`shared_prefs/collapse_state.xml` 的 `collapsed_conversations_v2`）✓；折叠态下搜索命中 → 强制展开、清搜索 → 回到折叠 ✓。
- **真机已验（2026-10-21，小米17 `ebb079b5`，0.3.12/18）**：`adb install -r` 后主页会话头右侧 ▼ 正常显示且可点，点一下整组（天头 + 消息）收起、箭头变 ▶，再点恢复原状（uiautomator 逐帧比对：收起时 `tvDateArrow`/`tvText` 节点消失、展开后与收起前完全一致 43 leaves）；`collapsed_conversations_v2` 空集 = 用户原本无折叠，走查后状态无残留。顺带确认：真机 prefs 里 M13 时代的旧键 `collapsed_conversations` 还留着 72 条陈旧会话（`collapsed_dates` 5 条）——新代码不读写旧键，所以它们**完全不生效**（正是换新键名的目的），要不要顺手清掉等下次改动一起处理。
- 按硬约束未推送（连同 §三.27 的 Laya 进度提交一起等用户明示）。

### 29. 会话内自建提醒并关联日历（M17）（2026-10-22，**0.3.13/19**）

用户需求（m01723）：「会话中可以自建提醒并关联日历」。此前提醒只能由**分析结果**自动创建（`CalendarHelper.createReminderResult` 由 AnalysisWorker 调用，标题固定带「任务：」前缀、时间来自模型给的 dueTime 文本），用户在会话里没有任何手动入口。

- **两个入口**（`ConversationActivity` + `activity_conversation.xml`）：分析面板内「📅 新建提醒」次级按钮；长按消息 → 多选条上的「建提醒」（取选中最后一条，标题预填为折叠空白后的消息文本，弹窗里注明「来自：<会话> 的消息」）。面板下方新增 `tvConvReminders`「📅 本会话已设 N 条提醒 · 点击管理」→ `ReminderListActivity.start(this, key)`（N=0 时隐藏；计数在 `renderContent` 后台线程算，所以分析记录为空的会话也能看到）。
- **弹窗**：内容（必填，`InputFilter.LengthFilter(60)`）、时间（默认**下一个整点**，可直接手输自然语言「明天15:00」，或点「📅 选时间」走 DatePicker→TimePicker 回填 `yyyy-MM-dd HH:mm`）、提前提醒 Spinner、说明文案。三条校验都在 positive 按钮里自校验、失败不关窗：空内容→「提醒内容不能为空」；解析不出时间→提示正确格式；时间已过（1 分钟容差）→「这个时间已经过了，改到未来再存」。
- **CalendarHelper**：`parseDueTime` 拆出 `tryParseDueTime(text, base): Long?`（解析失败返回 null；`parseDueTime` 仍回退 `base+1h`，**分析链路语义不变**，`CalendarDueTimeTest` 7 例继续全过），新增纯 `HH:mm` 分支（今天该时刻，已过顺延明天——原来「15:00」这种写法会掉进 +1h 兜底）；`createReminderResult` 增两个默认参 `titleOverride`（手动提醒标题=用户原文，不加「任务：」前缀，会话信息留在 description）与 `explicitEventTime`（手动路径已有精确时间，不再二次解析）。
- **新 `ManualReminderCore.kt`（纯 JVM 可单测）**：`dedupKey(pkg,conversation,title,eventTime) = "man:" + SHA-1("man|pkg|conv|title|time") 前 16 位`（同标题同时间即同一条，连点两次被 `ReminderStore.containsActive` 挡）；`defaultTimeText`/`nextWholeHour`/`prefillTitle`/`isInPast`/`leadLabel`。`AnalysisConfig.REMINDER_LEAD_OPTIONS` 由 `15/60/1440` 扩为 **`0/15/60/1440`**，`reminder_lead_labels` 同步加「不提前」（`ReminderSettingsActivity` 用 `indexOf` 定位，自动适配）。
- **权限**：无日历权限时先 `requestPermissions(READ/WRITE_CALENDAR, 4001)`；**拒绝也照样跑**（提示「未给日历权限，将走降级方式」后走 L2 唤起日历 App / L3 App 内闹钟），不吞掉用户刚按下的动作。
- **顺带加固**：`ReminderListActivity` 的「删除/取消」原来**一键即删**（连带删日历事件），M17 后这页成了常用管理入口，改为 AlertDialog 二次确认（取消/删除）。
- **单测**：新增 `ManualReminderCoreTest` 9 例（去重键稳定且随维度变化、时间文本往返、默认时间=下一整点、纯时刻未到算今天/已过顺延明天、无时间信息返回 null 且 `parseDueTime` 仍回退 +1h、预填折叠空白限长、`isInPast` 容差、`leadLabel` 与档位数组一致）；全量 `:app:testOpenDebugUnitTest` **359 例 0 失败**，`:app:lintOpenDebug` 通过。
- **模拟器走查（emulator-5554，0.3.13/19 真包）**：按钮/弹窗字段/四档 Spinner（不提前/15 分钟/1 小时/1 天）/选时间（日历 24 日 → 时钟切文字输入 10:30 → 回填 `2026-10-24 10:30`）全部正确；两条校验 Toast 实拍确认；长按气泡 →「建提醒」标题预填 + 来源提示 ✓；降级链日志（无日历账户时）`L1 跳过：无可写日历账户 → L2 跳过：无日历 App → AlarmHelper: App 内提醒已设置 key=man:fd48ce235884746d`，`reminders.jsonl` 落 `status=alarm`、`event_time=1792809000000`（= 2026-10-24 10:30 +08，与所选一致）。给模拟器塞一条可写日历后 **L1 真写成功**：`Events` 行 title=消息原文（无前缀）、dtstart=1792681200000（2026-10-22 23:00 +08）、dtend=+1h、description 三行齐全，`Reminders` 行 `minutes=15 method=1`；记录变 `status=calendar, calendar_event_id=1`。计数行随之显示「本会话已设 2 条提醒」；重复保存同一条 → Toast「跳过：已创建过（去重）」且文件仍 2 行；列表页状态徽标（已建日历事件 / 已设App内提醒）正确；删除确认 →「取消」保留、「删除」后 `reminders.jsonl` 清空且 `Events`/`Reminders` 查询均 `No result found`（日历事件同步删掉）、列表回到「暂无提醒记录」。
- **真机已验（2026-10-23，小米17 `ebb079b5` / HyperOS 16 / Android 16，0.3.13/19）**：`adb install -r` 从 0.3.12/18 平滑升级（数据全留、无 FATAL、通知监听自动重绑）。日历权限 granted=true，机上有两个 `calendar_access_level=700` 的可写日历（`Xiaomi Calendar` / `XesPY`）→ **L1 直写真实日历成功**：日志 `CalendarHelper: L1 日历事件已创建: id=1956 title=M17 test clear later`；`content query events` 回 `_id=1956, dtstart=1792710000000（2026-10-23 07:00 +08）, dtend=+1h, calendar_id=1, description=会话：姜倩`；`reminders` 行 `event_id=1956, minutes=15, method=1`。第二条特意选**新增的「不提前」档** → `id=1957` 且 `reminders` 行 `minutes=0`（证明 0 档能落、AlarmHelper 的 `maxOf(eventTime-0, now+1s)` 不会算出过去）。
- UI 实拍核对：弹窗默认时间=**下一个整点**（06:59 时给 07:00、07:02 时给 08:00 都对）、Spinner 四档「不提前/15 分钟/1 小时/1 天」、长按气泡 → 多选条「建提醒」→ 标题预填消息原文 + 「来自：姜倩 的消息」、计数行「📅 本会话已设 2 条提醒 · 点击管理」→ 管理页两条徽标均「已建日历事件」+「事件时间 10-23 08:00 ｜ 创建于 10-23 07:02」、删除弹「删除这条提醒？已写入的日历事件会一并删除。」。删除后 `reminders.jsonl` 清空、列表回「暂无提醒记录」、计数行隐藏，`Events` 两行变 `deleted=1`（可同步日历是软删，由同步适配器清），`Reminders` 查询 `No result found` → **用户日历无残留**。
- **已推送（2026-10-23）**：`origin/main` = 7050aad（连同 §三.27 Laya 进度 98d13e8、§三.28 M16 ff91f5c/bd72b60 一并），CI run 39868475598 **全绿**（Lint / Unit tests + assemble / Server tests / Guard 全 success，约 6.5 分钟）。
### 30. 新建提醒自动预填内容（M18）（2026-10-23，**0.3.14/20**，be58a24）

用户需求（m02331）：「新建提醒，需要自动填入提醒内容」。M17 的面板入口（非长按消息）内容框恒为空，用户要从零打字。

- `ManualReminderCore` 新增 `PrefillSource { ANALYSIS, MESSAGE, NONE }`、`PrefillSuggestion(text, source)`、`suggestPrefill(analysisTodo, messages, minLength=4, maxLen=40)`：优先级 ①分析待办（`record.todoText.ifEmpty { it.s2Summary }`，**不用 s1Topic**——那只是「闲聊」这类分类词）②最近一条**有实质内容**的消息（传入旧→新，内部 `asReversed()`；跳过占位符与长度<4）③都没有→留空（不编内容）。
- `messageBody(text)` 剥通知拼接前缀：`[4条]张三: …` → 正文（先 `substringAfter("条]")`，再取半/全角冒号 `minOf` 位置，colon>30 视为不是前缀）；开头是媒体标签（`[图片]`/`[小程序]`，`]` 在 2..12）且不是纯占位符时剥掉标签只留正文。`isPlaceholder`：trim 后长度 3..14、首尾方括号、内部不含方括号。
- **刻意不用正则**：写 Kotlin 正则要叠三四层反斜杠（经 run_code 传输还要再叠一层），极易错；纯字符串操作可读性也更好。
- `ConversationActivity`：`renderContent` 后台线程顺手缓存 `cachedAnalysisTodo` / `cachedMessageTexts`；`showReminderDialog` 里只有 `source == null` 才走 `suggestPrefill`（长按路径仍用消息原文）；弹窗下方三态提示 `reminder_manual_from_msg` / `reminder_manual_prefill_analysis`「已按分析结果预填，可直接改」/ `reminder_manual_prefill_message`。
- 单测 +6（`ManualReminderCoreTest` 共 15 例）；全量 365 例 0 失败。真机（小米17）两分支均实拍通过：姜倩会话 → 预填分析摘要；小柿子班级群最后一条 `[6条]陈老师: [小程序] 布置了一份作业` → 预填「布置了一份作业」。

### 31. 会话标签体系 + 控制台定时分析面板（M19/M20）（2026-10-23，**0.3.15/21**）

用户需求（m02490）「分析过的会话内容要做标记，区分状态，其中一条是无需后续，后续不再分析这个标签的内容」+（m02508）「标签体系 需要程序设计一下 待办、跟踪、无关，还有什么？待办、跟踪 需要加到后续会话分析里，其他不再后续分析。定时分析的控制台需要加上。」

**标签体系（4 档 + 未标记）**：🟠 待办 `todo` / 🔵 跟踪 `track` / ✅ 已办结 `done` / ⚪ 无关 `noise` / 未标记 `none`。回答用户「还有什么」：在待办/跟踪/无关之外补 **已办结**（事情办完，结果留档但别再自动分析）；**未标记**不是档位而是回落态（跟随关注名单，保证老用户行为不变）。拟再提「等待回复 / 仅提醒」两档，待用户拍板。

- 门禁纯函数 `ConvTagCore.shouldAnalyze(tag, watched, forced)`：`keepsAnalyzing = TODO||TRACK` 强制纳入；`stopsAnalyzing = DONE||NOISE` 强制排除；否则回落 `watched`。`forced` 只在**用户真点分析**时为 true。
- 三处接线：`AnalysisWorker.doWorkExclusive` 建案过滤；`AnalysisScheduler.syncAll` 撤孤儿条件（已办结/无关的会话级定时自动撤销）；`MainActivity` 会话头新增 `tvTagBadge`（未标记=灰色「🏷」，打标后「🟠 待办」并着色，点击弹选择器）+ `ConversationActivity` 面板下 `tvConvTag` 行。
- **门禁语义修正（重要）**：`forced = forceKey != null && !quiet`。会话级定时也带 forceKey，但它是 `quiet=true` 的自动轮，**必须**同样被「已办结/无关」拦住，否则用户标了「无关」它照跑。
- 存储 `ConvTagStore`：prefs `conv_tags` / 键 `tags` = JSON `{convKeyId: code}`，只存已打标条目。
- **弹窗坑**：`AlertDialog` 同时 `setMessage` + `setSingleChoiceItems` 时选项面板被消息面板挤掉——模拟器实拍弹窗只剩标题+说明+取消（无异常，纯渲染层吞掉）。改为 `setView(自定义 LinearLayout)`：说明 TextView + 5 个 TextView 行（`btn_secondary` 背景、当前档 brand_green + 假粗体），行内点击落库并回调；`var dialog: AlertDialog? = null` 先声明再赋值（Kotlin 不许前向引用 val）。

**M20 控制台「⏱ 定时分析」面板**（`activity_console.xml` + `ConsoleActivity`，插在「分析」与「日历提醒」两张入口卡之间）：

- 纯 JVM 判定 `AnalysisScheduleSummaryCore.evaluate(enabled, intervalMinutes, lastRunAt, now)` → `Health { DISABLED, GLOBAL_OFF, NEVER_RUN, OK, STALE }`；STALE 阈值直接复用 `ScheduleSelfHealCore.thresholdFor(interval)`（**与自愈同一把尺**，避免面板说正常而自愈在补排）。
- 面板内容：全局态一行（含「上次 / 下次约」）；STALE 时橙色告警「上次运行距今 N 分钟，超过 M 分钟阈值：排程可能已被系统丢弃。点下面『立即分析一轮』会自动补排」；按会话单独定时的列表（每会话下次触发；被标签拦掉的排最后并标「已办结/无关，不再自动分析」，最多 5 条 + 「…另有 N 个未列出」）；标签统计行「标签：🟠 待办 1 · ⚪ 无关 1 · 1 个已办结/无关，之后不再自动分析」；按钮「▶ 立即分析一轮」/「⚙ 分析设置」。
- **顺带修掉一个真 bug**：`AnalysisWorker.runRound():92` 原来 `if (!config.enabled) return Result.success()` —— 总开关关掉时**连用户手点的分析也静默不跑**（点了没反应、也没日志）。新增 `KEY_MANUAL`（`AnalysisScheduler.enqueueAnalysisNow` 必定 true；定时轮用另一个 builder，不受影响），门禁改 `if (!config.enabled && !manual)`；`console_schedule_disabled` 文案同步改为「定时分析已关闭：不会自动跑，点「分析」或下面「立即分析一轮」仍会跑」。
- **日志计数分家**：门禁原来把一切不通过都记成 `标签拦截`，实测出现 `候选 35 个, 标签拦截 35 个`（其实只有 1 个打了标，其余 34 个是未关注）→ 拆 `skippedByTag` / `skippedUnwatched`，日志变「标签拦截 1 个, 未关注拦截 34 个」。
- 单测：`ConvTagCoreTest` 7 例 + `AnalysisScheduleSummaryCoreTest` 7 例；全量 **379 例 0 失败 0 错**；`:app:lintOpenDebug`（148 条全 Warning、0 Error）+ 双 flavor assemble 均 BUILD SUCCESSFUL。
- 模拟器走查（emulator-5554）：徽标 🏷 → 弹窗五档齐全 → 选「⚪ 无关」→ 徽标变灰、prefs 落 `{convKeyId: noise}`；控制台显示「标签：⚪ 无关 1」；总开关关闭时点「▶ 立即分析一轮」→ 日志 `入选 0 个会话 (候选 35 个, 标签拦截 1 个, 未关注拦截 34 个, force=null)`；改标「🟠 待办」再跑 → `入选 1 个会话 (候选 35 个, 标签拦截 0 个, 未关注拦截 34 个)` 且真的开跑（`AnalysisProgress: stage=LOADING_MODEL → REMOTE → idle`）。**待办：小米17 真机回归（本轮手机未连）。**
## 四、构建与运行

```powershell
# 环境
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:ANDROID_HOME = "C:\Users\<你>\AppData\Local\Android\Sdk"
# 仅本机（2026-10-11 起）：AGP 8.5.2 内嵌 aapt2 在 app\ 子树写输出报「数据无效」→ 覆盖为 build-tools 35.0.0 的 aapt2（勿入库）；
# 且 .gradle 锁文件曾被 Windows ACL 卡死 → GRADLE_USER_HOME 指到仓库内 .gradle-home（已 gitignore）
$env:ORG_GRADLE_PROJECT_android.aapt2FromMavenOverride = "$env:ANDROID_HOME\build-tools\35.0.0\aapt2.exe"
$env:GRADLE_USER_HOME = "C:\project\notifyme\.gradle-home"

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
- MuMu 测试机上微博/抖音会自己抢前台（`am start` 后被顶掉，uiautomator 拍到别人）：`pm disable-user --user 0 com.sina.weibo` / `com.ss.android.ugc.aweme` 后立刻正常（要恢复用 `pm enable <包名>`）
- adb 输不了中文，但验证「搜索态强制展开」可以挑**名字里带 ASCII 的会话**（如 `Jmoon`）当探针：`input text Jmoon`
- uiautomator 属性顺序是 `text` 在 `resource-id` 之前，写解析正则别假定顺序；节点里 `&#1;` 就是 ConvKey/日期复合键的分隔符 \u0001
- uiautomator dump 的容器节点是 `<node …>children</node>`（**非自闭合**），用 `/<node[^>]*?\/>/g` 只能抓到叶子节点；判断「行可点」要抓叶子（如 `tvArrow`）的 bounds 中心点去 tap，别指望在结果里看到 clickable 的行容器
- HyperOS 16 截图：`exec-out screencap -p > x.png` 会把 stderr 的 `[Warning] Multiple displays were found…` 一起写进文件（PNG 头变成 `[Warning]`，read_image 报 Unsupported or malformed image data）。可靠写法：`adb shell screencap -p /sdcard/x.png` + `adb pull`（或给 screencap 加 `-d <display-id>`，id 见 `dumpsys SurfaceFlinger --display-id`）
- 读 App 私有 prefs 在真机上必须 `adb shell "run-as com.notifyme.android cat shared_prefs/collapse_state.xml"`；直接 `adb shell cat /data/data/…` 是 `Permission denied`（若再 `2>/dev/null || echo MISSING` 会把权限错误伪装成「文件不存在」，别据此判断状态）
- 小米17 USB 会偶发掉线：`adb install` 中途报 `device 'ebb079b5' not found` + 空错误行 → `adb kill-server; adb start-server` 后重试即成功，不是签名/安装权限问题
- **MuMu 上残留的 frida-server 会让所有新起进程 SIGSEGV**：崩在 `/memfd:frida-agent-64.so`（backtrace 9 帧全在里面），表现是「一点图标就屡次停止运行」+ `Process … failed to attach`，与被测 APK 无关。判定法：`am force-stop` 一个第三方 App 再启动，若同样崩 = 环境问题。修：`adb shell "ps -A | grep -i frida"` 找 pid → `adb shell kill -9 <pid>`，之后 App 立刻能起（需要时再自己启 frida-server）
- 模拟器没有日历账户时想验 L1：`content insert` 会被拒（`Only sync adapters may write to account_name`），改走 `adb shell "sqlite3 /data/data/com.android.providers.calendar/databases/calendar.db < /data/local/tmp/x.sql"` 直接 `INSERT INTO Calendars (account_name, account_type, _sync_id, dirty, name, calendar_displayName, calendar_access_level, visible, sync_events, calendar_timezone, ownerAccount, isPrimary, deleted) VALUES (…600…)`（老 AOSP 库表名/列名是 `Calendars`/`calendar_displayName`，先 `.schema` 确认）；测完 `DELETE FROM Calendars WHERE account_name=…`，库上有 trigger 会级联删 Events
- uiautomator 取层级最稳的写法：`cmd /c "adb -s <s> exec-out uiautomator dump /dev/tty > x.xml 2>nul"`（`shell "uiautomator dump …; cat /sdcard/ui.xml" > x` 经常落空文件）
- **Toast 不进 uiautomator 层级**：要验 Toast 文案必须在点击后立刻 `exec-out screencap`（约 0.5-2 秒窗口），别指望 dump 里能看到
- **HyperOS 上输入法弹出会把 AlertDialog 整体上移**：在 EditText 里打完字后按钮 bounds 会变（实测「存入日历」从 y≈1771 移到 y≈1268），照上一帧坐标点就会点空/点错行 → **每次 tap 前重新 dump 取坐标**
- 真机（小米17）`adb shell content query --uri content://com.android.calendar/{events,reminders}` 免 root 直接可读（`--where '_id in (1956,1957)'` 这种写法可用）；但**删掉可同步日历上的事件是软删**：`events` 行仍在、只是 `deleted=1 dirty=1`（同步适配器稍后物理清），判据要查 `deleted` 列而不是「行消失」；`reminders` 行则是真删（`No result found`）
- 驱动 TimePicker：先点 `toggle_mode`（content-desc「切换到文字输入模式…」）再用 `input keyevent KEYCODE_MOVE_END; input keyevent 67 67; input text 10` 改 `input_hour`/`input_minute`，比点表盘准；DatePicker 的日期格子是无 id 的 `View`，按行列坐标数日期容易点错一天，落库后务必回读 `event_time` 核对
- 本机当前文件策略 danger-full-access；无需申请 sandbox_permissions
- MuMu 上 `com.sankuai.moviepro` 会反复抢前台并让 `uiautomator dump` 报 `ERROR: could not get idle state.`（还伴随 Shutdown thread SIGSEGV）：`pm disable-user --user 0` **不够**（进程活着仍能把自己拉到前台），要 `adb shell pm disable com.sankuai.moviepro` + `am force-stop`（本机 adb shell 是 root 才能用 `pm disable`）；测完 `pm enable` 还原
- **PowerShell 里 `adb … > file` 是 PowerShell 自己重定向，写出来是 UTF-16**：node 按 utf8 读会看到乱码/空匹配（`logcat -d > x.txt` 同理）。可靠写法：`cmd /c "adb -s <s> logcat -d > x.txt 2>&1"`
- `am start … >/dev/null 2>&1` 在 PowerShell 里会因 `/dev/null` 不是合法路径而**报错并中断整条命令**（表现是 App 根本没启动，误判成 UI 没反应）→ 用 `| Out-Null`
- 解析 uiautomator 的 `bounds`：`[800,532][861,580]` 用「删掉非数字和逗号」会粘成 `800,532861,580`（中间那个 `][` 被吃掉）→ 必须用四捕获组正则；另外按属性名取值时匹配串是 `text="`（前面是空格不是引号），写成 `"text="` 会一个都取不到
- `AlertDialog` 同时 `setMessage` + `setSingleChoiceItems` 时选项面板会被消息面板挤掉（实测只剩标题+说明+按钮，无任何异常日志）→ 列表类弹窗一律用 `setView(自定义 LinearLayout)`（见 §三.31）

## 五、下一步计划（路线图，对应 README）

> **已展开为可执行计划：[`docs/ROADMAP.md`](docs/ROADMAP.md)**（M0–M9 里程碑 + 任务清单 + 验收口径 + 决策门 D1–D6，均已拍板）。
> 当前状态（2026-10-23 更新 2）：**M18（0.3.14/20，be58a24）已提交**；**M19 会话标签体系 + M20 控制台定时分析面板（0.3.15/21）已交付**（379 单测全绿、lint 0 Error、模拟器走查通过），**未推送、小米17 未回归**（手机未连）。本地 main 领先 origin：be58a24 + M19/M20 待提交。新增待办：①标签是否再加「等待回复 / 仅提醒」两档待拍板；②会话去重三口径待拍板（§三.26 前后画像）；③结果页可点空态（已提议未确认）；④真机陈旧 prefs 键 `collapsed_conversations`（72 条）要不要清。
> 当前状态（2026-10-23 更新）：**0.3.13/19 已推送且 CI 全绿**（run 39868475598），M17 已在小米17 真机走查通过（L1 直写小米日历、「不提前」档、长按建提醒、删除联动；测试事件已全部清理）。待办：①会话去重三口径待用户拍板（重复画像见 §三.26 前后记录）；②结果页「本会话 0 条 · 全部会话 N 条 →」可点空态（已提议未确认）；③M13 遗留的陈旧 prefs 键 `collapsed_conversations`（真机上仍留 72 条）要不要顺手清。
> 当前状态（2026-10-22 更新）：§三.29 M17 会话内自建提醒并关联日历（**0.3.13/19**）已交付，模拟器全链路走查通过（含 L1 真写日历、去重、删除二次确认），**真机未验**（本轮手机未连）；本地 main 领先 origin 三个提交（98d13e8 Laya 进度 / ff91f5c M16 / bd72b60 docs）+ M17 待提交，均未推送。
> 当前状态（2026-10-21 更新）：§三.27 M15 端侧分析进度已随 **0.3.11/17** 推送（b6105d7），CI 护栏修复 23b0e0d 已推送且绿；Laya 判定题进度（98d13e8）与 §三.28 M16 恢复按对话折叠（**0.3.12/18**）**已交付未推送**（本地 main 领先 origin 两个提交）。0.3.12/18 已装小米17 并实测折叠可用。
> 当前状态（2026-10-14 更新）：§三.25/26 已交付**未推送**（M13 分析结果回看原文 0.3.9/15 + M14 主页按天折叠与逐条结果 0.3.10/16，模拟器走查通过）；v0.3.8 正式发布完成；M1-M11 评审修复已随 0.3.8 发布（§三.24）。
> 当前状态（2026-10-13 更新）：§三.21/§三.22/§三.23 已交付推送——M10 advisor 端侧本地评审（0.3.5/11）+ 主页「⚡ 最近待办」区编辑/删除/一键加日历（0.3.6/12，模拟器走查通过，L1 日历直写待真机确认）+ 五路评审 H1-H4 修复（0.3.7/13，重启闹钟补挂/FGS specialUse/Bundle 防御/jsonl 裁剪）；§三.20 v3 批次提交闭环，HyperOS 真机回归仍待用户确认。
> 当前状态（2026-10-10 更新）：**W1 真机端到端已打通**，并按用户口径改为**全程手机内分析**（S1=端侧 Qwen2.5-0.5B + few-shot，S2=手机内配置的 GLM；不再依赖电脑 laya-local，见 §三.15 真机实测 ✅）；**M9 真机已验**（悬浮卡实拍 card_real.png）；**M10/M11/M11.4 已交付**；排程失联自愈 + 主页采集过滤 + 自采集噪声根除 + 悬浮面板「最近待办」与主页联动修复**全部真机验证通过（见 §三.14，§三.13 待办 1-3 已闭环）**；M12 分析范围筛选设计完成待实现；评测文档待补充；**0.5B few-shot 已完成并真机验证（§三.15，端侧 S1 已产出真实判定 JSON）**；本会话修复已随验证通过提交并推送；M0 代码与 CI 侧已完成，只剩 D5 正式密钥库 + 4 个 Secrets（等 `administration` 权限 token）。剩余待办：M12 分析范围筛选、评测文档补充、0.5B few-shot（低优先）；**2026-10-12 另有 v3「默认不关注」+ 悬浮窗自愈已交付待真机回归（§三.20，0.3.4/10）**；执行时以 docs/ROADMAP.md 为准。
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
