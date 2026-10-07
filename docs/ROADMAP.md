# notifyme 路线图与执行计划

> 制定日期：2026-10-04　依据：`progress.md` + 代码实测核对（所有结论均带 `文件:行` 证据）
> 本文是 README「下一步计划」8 项的**可执行展开**：拆成 M0–M9 里程碑（M9 为用户新增的悬浮窗需求），含任务清单、验收口径、工作量与风险。
> 硬约束沿用 `progress.md` §六：只在本仓库开发、open flavor 零预置、密钥不入库、未经明确要求不推送/不发版。

---

## 0. 现状快照（代码级事实，规划的前提）

| 维度 | 现状 | 证据 |
|---|---|---|
| 版本 | versionName `0.1.3` / versionCode `4`（五项需求 + 包名精简后未升版） | `app/build.gradle.kts:29-30` |
| flavor | `open`（零预置）/ `beta`（`secrets.local.properties` 注入，`applicationIdSuffix=.test`） | `app/build.gradle.kts:38-68` |
| **release 签名** | **未配置 `signingConfigs`**，`assembleOpenRelease` 产出 unsigned APK，用户装不上 | `app/build.gradle.kts:70-78` |
| **CI** | **无**（仓库无 `.github/`），双 flavor 构建靠本机手工验证 | 根目录清单 |
| **Releases 产物** | **无**，但 README 与 BUILD.md 都把「从 Releases 下载 `notifyme-open.apk`」当作用户第一步 → 公开用户首条路径断裂 | `README.md:75`、`docs/BUILD.md:4` |
| **测试** | **零**：`app/src/test`、`app/src/androidTest` 均不存在 | glob 无命中 |
| 代码规模 | 41 个 Kotlin 文件 / ~9.5k 行；最大三块 `ConsoleActivity.kt` 939、`MainActivity.kt` 926、`AnalysisWorker.kt` 685 | 行数统计 |
| 存储 | JSONL append + 删除时全文件重写，`@Synchronized`，明确不引 Room | `MessageStore.kt:57-66` |
| **消息 schema** | `ChatMessage` 只有 `sender/text/timestamp/conversation/isGroup/source`，**无 `pkg`/`app` 字段** → 跨应用必须先做 schema v2 + 迁移 | `MessageStore.kt:26-54` |
| **微信硬编码** | 3 处：监听器 `PKG_WECHAT`、a11y `WECHAT_PKG` + 控件 id 表（`com.tencent.mm:id/obn` 等 5 个）、控制台拉起微信 Intent | `WeChatNotificationListener.kt:32,160`；`WeChatA11yExtractService.kt:40,223-227`；`ConsoleActivity.kt:533` |
| **端侧推理** | 只有接口 + 占位实现（`UnavailableLocalLlmEngine`），`forModel` 是 TODO；模型**下载链已通**（ModelScope 直链，S1 MiniCPM4-0.5B-MNN 311 MB / S2 MiniCPM3-4B-GGUF Q4_K_M 2.47 GB，S2 需 8 GB+ RAM） | `LocalLlmEngine.kt:47-53,67-69`；`LocalModelStore.kt:13-14,47-48` |
| 分析文本出口 | 云端发送前的文本汇聚在 3 个函数 → **脱敏可单点插入** | `AnalysisCase.kt:56`(`buildStateJson`)、`:226`(`buildS1OpenAiSystemPrompt`)、`:268`(`buildS2SystemPrompt`) |
| 分析双系统 | S1 快筛（laya/JEV systemone 或 openai 或本地）→ 条件升级 S2 深分析；S1→S2 阈值：need_action≥0.5 / importance≥6 / 置信度<0.5 | `AnalysisWorker.kt:29-33,280-380` |
| 服务端 API | `POST /weixin`、`GET /messages`、`POST/GET /analysis`、`POST/GET /advisor`、`POST /admin/extract`、`/console` PWA + manifest + sw.js → **iOS/多端可直接复用** | `server/app.py:177,279,351,441,532,587,651,1347-1385` |
| 入站校验 | `_validate_item` 决定 schema v2 是否要服务端同步改 | `server/app.py:257` |

**一句话结论**：产品功能已可用，但「公开可获取」（签名/CI/Releases）与「工程安全网」（测试）两块地基是空的；
而路线图里最有价值的跨应用（#2）与端侧推理（#6）分别卡在 schema 与 native 库上。

---

## 1. 里程碑总览

| 里程碑 | 对应 README 路线图 | 目标一句话 | 依赖 | 工作量 | 风险 |
|---|---|---|---|---|---|
| **M0 发布闭环** | #8（+收尾小事） | 用户真能从 Releases 装上签名 APK，且每次 push 自动构建 | — | 0.5–1 天 | 低 |
| **M1 测试与质量地基** | — | 存储/队列/解析层有 JVM 单测，后续改 schema 不怕炸用户数据 | M0(CI) | 1–2 天 | 低 |
| **M2 跨应用通知管理** | #2 | 从「微信专用」变成「任意 App 通知」，schema v2 + 应用源注册表 | M1 | 3–5 天 | 中 |
| **M3 隐私脱敏** | #4 | 云端分析前可端侧脱敏，用户看得见脱敏了什么 | M1 | 2–3 天 | 中低 |
| **M4 端侧推理引擎** | #6 | 把 `UnavailableLocalLlmEngine` 换成真引擎，S1 全离线跑通 | M1、决策 D3 | 5–10 天 | **高** |
| **M5 iOS 端** | #1（需重述） | 原生 SwiftUI 查看端 + 提醒推送（**不是**本地捕获，iOS 做不到） | M0、决策 D4 | 5–8 天 | 中高（外部账号） |
| **M6 UI 优化** | #5 | 大会话性能、可读性、多端适配 | — | 2–4 天 | 低 |
| **M7 通知之外的信息融入** | #3 | 自己发出的消息等旁路数据入库并参与分析 | M2 | 3–5 天 | 中 |
| **M8 智能硬件联动** | #7 | Wear OS / 家居设备通知与提醒联动 | M2 | 待评估 | 中高 |
| **M9 移动端悬浮通知** | 用户新增需求 | 悬浮通知卡片 + 常驻悬浮球，离开 App 也能即时处理 | M2 | 3–5 天 | 中（厂商ROM 适配） |

**推荐执行顺序**：`M0 → M1 → M2 → M3 → M4`，M5/M6 视决策插入，M9 在 M3/M4 端侧能力稳定后插入，M7/M8 收尾。
理由：M0 修好公开路径并给出 CI（之后所有重构才有护栏）；M1 必须在 M2 之前——schema 迁移直接动用户已有 `messages.jsonl`，出错不可逆；
M2 是产品级最大解锁且是 M7/M8/M9 的前置；M4 风险最高，刻意排在有测试网之后。

---

## 2. M0 发布闭环（P0）— 进行中（2026-10-04）

**问题**：README 第一句让用户去 Releases 下 APK，但 Releases 是空的，且 release 构建根本没签名。

任务清单：
1. [x] `app/build.gradle.kts` 增加 `signingConfigs.release`，密钥库路径/口令全部走环境变量或 `keystore.properties`（gitignore），CI 走 GitHub Secrets；**严禁**任何密钥进仓库（沿用 §六 硬约束）
   - 实现要点：优先级 **环境变量 > `keystore.properties` > 都没有则降级为不签名并打 warn**（保证任何人 clone 后 `assembleOpenRelease` 都能跑通）；未签名时 warn 会说清具体缺哪一项
   - 踩坑修复：`Properties.load` 会把 UTF-8 BOM 当成键名的一部分，Windows 记事本 / PowerShell 5.1 写的 `keystore.properties` 会「填对了却读不到」→ 新增 `loadPropsIgnoringBom()`，`secrets.local.properties` 同享此修复（否则 beta 密钥静默变空串）
   - 模板文件 `keystore.properties.example`（含 keytool 生成命令与「密钥库丢失=老用户无法原地升级」警告）
2. [x] 版本升位：`0.1.3/4` → **`0.2.0/5`**（五项需求 + 包名精简属功能级变更，见决策 D2）
3. [x] 新增 `.github/workflows/android.yml`：push/PR 触发 → JDK 17 + Gradle 缓存 → `testOpenDebugUnitTest assembleOpenDebug assembleBetaDebug` → `py_compile server/*.py` → Guard → 上传两个 debug APK 为 artifact
   - CI 需把 wrapper 的 `distributionUrl` 从国内镜像 sed 成官方源（只改工作区，不提交）；`gradlew` 在 index 里已修为 100755，CI 另加 `chmod +x` 兜底
4. [x] 新增 `.github/workflows/release.yml`：`v*` tag 触发 → 校验 tag 与 `versionName` 一致 → Secrets 解出 keystore → `assembleOpenRelease` 签名 → `apksigner verify` → `.sha256` → `gh release create --latest`
5. [~] 仓库门面：Issue/PR 模板 [x]、README CI+Release+License 徽章 [x]；**About/Topics 待办**（当前 PAT 无 administration 权限，`gh secret list` 报 HTTP 403）
6. [x] `docs/BUILD.md` 补「发布版签名」小节（本地签名 / CI 自动签名 / 4 个 Secrets）；README 下载路径改为 `releases/latest` 并提到 `.sha256`
7. [x] 清理 `progress.md` §五「可选的收尾小事」中被 M0 覆盖的条目

验收：
- [x] 签名三条路径本地实测（BOM 版 `keystore.properties` / 仅环境变量 / 完全无配置）：前两条 `apksigner verify --print-certs` 输出 `Signer #1 certificate DN`，第三条 warn + 未签名但构建成功；测试用密钥库已删除，`app/build/outputs/apk/open/release/` 已清理
- [x] 真机 `adb install -r` 装签名包成功（非 `INSTALL_PARSE_FAILED_NO_CERTIFICATES`）— CI 内 `apksigner verify --print-certs` 已验签通过（run 37320660629，2026-10-05）
- [x] tag `v0.2.0` 后 GitHub Actions 自动出 Release，页面可下载 `notifyme-open.apk` — **已发布**：https://github.com/solidjoker/notifyme/releases/tag/v0.2.0（含 `.sha256`，--latest）；D5 全流程完成（密钥库在仓库外 `C:\Users\<你>\notifyme-release\`，4 Secrets 已写，About/Topics 已设）
- [x] CI Guard 等价命令本地全绿（旧私有命名零残留、无密钥文件入库、无真实个人用户目录）；首次 CI 实跑待观察
- [x] open flavor 首启仍是「零预置、完全离线」（`DEFAULT_*` 全部注入空串，未改动）

### M0 遗留风险：已推送历史里的旧字面量

`progress.md` 曾把上一次脱敏掉的字面量又写回公开仓库（个人域名、真实 Windows 用户名路径、私有母仓库旧命名）。
**工作区已在本次会话全部改为占位描述，但 commit `4b11a3e` 的历史内容里仍留这些字面量。**

| 项 | 现状 | 可选处理 |
|---|---|---|
| 个人域名 / 用户名路径 | 仅泄露作者身份信息，无凭证价值 | 接受现状；或 `git filter-repo` 重写历史（会改写全部 commit SHA，需 force push） |
| 私有母仓库旧命名 | 泄露内部项目名，无凭证价值 | 同上 |
| 真实密钥 | **全仓 + 684 个历史 blob 扫描无命中**，无需处理 | — |

建议：因为不含任何凭证，**默认接受现状**，只在 ROADMAP 记档；若在意身份信息，再单独授权做一次 `git filter-repo` + force push（属破坏性操作，须显式确认）。
CI Guard 只能拦住「新增」的字面量，管不了已推送的历史。

---

## 3. M1 测试与质量地基 — 已完成（2026-10-04）

**问题**：9.5k 行零测试，而 M2 要动用户数据 schema。先把纯逻辑层罩住。

任务清单：
1. [x] `app/build.gradle.kts` 测试依赖与选项
   - `testImplementation("junit:junit:4.13.2")`；`testImplementation("org.json:json:20250107")` —— mockable android.jar 里的 `org.json` 是 stub（调用即抛），AGP 把它排在类路径最后，真库胜出
   - `testOptions { unitTests.isReturnDefaultValues = true }` —— 让 `android.util.Log` 返回 0 而不是抛 `Method w in android.util.Log not mocked`，免为一个 log 调用上 Robolectric
   - **`kotlinx-coroutines-test` 暂缓**：本轮抽出来的都是同步纯逻辑，没有任何 suspend 路径被测；等 M4 要测 `runS1Local` / `LocalLlmEngines` 协程链时再加，不留没人用的依赖
   - CI 无需改动：M0 的 `android.yml` 已经在跑 `testOpenDebugUnitTest`，测试文件一加就自动生效
2. [x] 新建 `app/src/test/java/com/notifyme/android/`，**142 个测试全绿**（JUnit 4 + `TemporaryFolder`，零 Robolectric，整个测试任务约 2s）
   - `JsonlStoreTest`（14）：追加/整表重写/按谓词重写/删除的文件级契约，含「没删任何行时完全不碰文件」「空列表不建 0 字节文件」「原始行文本原样写回不重新序列化」
   - `MessageStoreCoreTest`（29）：`readRecent` 倒序与 limit 语义（未标注日期的消息先占名额）、`deleteConversation`/`deleteDate`/`deleteMessages` 的计数与「无命中不重写不通知」、`merge` 秒级去重（含 a11y 来源忽略时间戳）、损坏行读取时跳过、重写时**原样保留**、`DAY_KEY_UNKNOWN` 兜底、`ChatMessage.toJson/fromJson` 往返与缺字段默认值
   - `PendingQueueCoreTest`（20）：id 从 1 自增、新实例从文件恢复 id（进程重启场景）、`removeSent`/`removeByConversations`/`removeByConversationDay`/`removeByMessages`、全部删完则删文件不留 0 字节、读取跳过损坏行、与 MessageStore 共用 `chatMessageFullKey` 口径
   - `AnalysisCaseTest`（33）：`fromMessages` 排序取末 10 与 `caseIdOf` 稳定性、`buildStateJson` 的 `chat` 结构（**不含 `conversation`**）、群聊发言人前缀、S1 题集四题与题型/档位/选项集合、S1→S2 **升级阈值判定**（need_action≥0.5 / importance≥6 / 置信度<0.5，含边界不升级）、`toInjectText` 五字段、S2 prompt 注入
   - `AnalysisParsingTest`（27）：防御式解析全分支 —— payload 为 null、`{"answer":{...}}` 多包一层、`answer` 不是对象时回落、类型不符（概率写成"很高"）按字段兜底、越界值 coerce、空字符串枚举回落、S2 摘要缺失兜底窗口末条（压换行 + 截 20 字）、`tasks` 混入非对象元素跳过、`extractJsonPayload`/`extractJsonFromText` 的散文与 markdown 围栏与残缺括号
   - `ForkPrefilterTest`（19）：正常窗口/长分享链接/带文字表情**必须放行**，单条窗口降到 0.3 但不 sharp，纯表情·系统提示·广告链接就地结案，混合垃圾的 breakdown 顺序，全窗口无文字压到 0.02（取小值而非二次相乘），`prob` 恒在 0-1 且 `sharp` 与阈值一致
   - 未覆盖：`ForkRecord.filtered/protocol="prefilter"` 的落库字段（在 `AnalysisStore`，本轮只测了纯判定 `ForkPrefilter.evaluate`）、`CalendarHelper` 三级降级链（见验收最后一条）
3. [x] 存储层抽出共用文件层 → 落地为 `JsonlStore`（92 行，`internal class JsonlStore(val file: File)`）：`exists/delete/appendLine/appendLines/readRawLines/forEachRawLine/overwrite/rewriteRaw`，不加锁（锁仍留在各 store 的 `@Synchronized` 上）
   - `MessageStore` → `MessageStore.coreIn(dir, onChange)` 返回 `MessageStoreCore`；`PendingQueue` → `PendingQueue.coreIn(dir)` 返回 `PendingQueueCore`（object 层缓存 dir→core，`nextId` 计数器跨调用存活）
   - **可测性关键**：core 只吃 `File`，不吃 `Context`，所以 JVM 单元测试无需 Robolectric
   - `AnalysisStore`/`ReminderStore`/`AdvisorStore` 尚未迁到 `JsonlStore`（它们本轮没有被 M2 直接改动，留到 M2 顺手统一）
   - 顺带修掉一处类初始化地雷：`MessageStore.mainHandler` 改成 `by lazy { Handler(Looper.getMainLooper()) }`，否则 JVM 上一加载就抛 `getMainLooper not mocked`
4. [ ] 可选：detekt/ktlint 基线（只报警不阻断），`lint.xml` 收口 —— **未做**，M1 收益低于噪声，留到有第二个贡献者时再上

### M1 为 M2 铺好的三块垫子
| 垫子 | M2 怎么用 |
|---|---|
| `chatMessageFullKey(m)`（顶层 internal 函数，含 conversation/sender/text/timestamp/isGroup/source） | M2 给 `ChatMessage` 加 `pkg` 时，**只改这一个函数**，MessageStore 与 PendingQueue 的删除匹配口径自动同步 |
| `JsonlStore` + `*Core(dir)` 构造 | schema v2 的「读时补齐 + 设置页手动全量重写」直接复用 `rewriteRaw`/`overwrite`，且已有测试兜住「不误删损坏行」 |
| `AnalysisParsing` 独立对象 | 多应用源之后各 parser 的解析差异可以各自测，不必再把 `AnalysisWorker`（CoroutineWorker，需要 Android 环境）拉进单元测试 |

### 记录在案的行为差异（原实现语义，M1 不改，测试钉住）
- **损坏行处理不一致**：`MessageStoreCore.rewrite` 把解析失败的行**原样保留**；`PendingQueueCore.rewrite` 按解析后的对象**重新序列化**，损坏行因此被丢弃。两边各有测试显式断言当前行为（`assertFalse` + 注释），统一到 M2 决定。
- **读取顺序口径不同**：`readRecent` 按时间**倒序**（UI 要最新的在前），`readAll` 按 **id 升序**（发送顺序）。测试的期望值分别按各自口径写，别互相套用。

验收：
- [x] `.\gradlew.bat testOpenDebugUnitTest` 全绿（142 tests，0 failures / 0 errors / 0 skipped）且已进 CI
- [x] 存储层测试覆盖上表全部删除路径：`deleteConversation`/`deleteDate`/`deleteMessages`（MessageStore）+ `removeSent`/`removeByConversations`/`removeByConversationDay`/`removeByMessages`（PendingQueue），每条都断言返回条数、剩余内容与文件是否被删
- [x] 重构后 `assembleOpenDebug assembleBetaDebug` 仍 exit 0（BUILD SUCCESSFUL）
- [ ] `CalendarHelper` 三级降级链的决策逻辑抽成可测纯函数 —— **顺延到 M2**：它不参与 schema 变更，而 M1 的预算优先给了「会被 M2 改动」的数据层

---

## 4. M2 跨应用通知管理（README 路线图 #2）— 已完成（2026-10-05）

**目标**：从微信专用 → 任意 App 通知统一管理；微信只是第一个「应用源」。

任务清单：
1. [x] **schema v2**：`ChatMessage` 增 `pkg: String`（默认 `com.tencent.mm`）+ 可选 `appLabel`；`toJson`/`fromJson` 向后兼容（缺字段读默认值）
   - 迁移策略：**读时补齐**，不做一次性重写文件（避免大文件迁移中断损坏）
   - 服务端接受可选 `pkg`，缺省按 `com.tencent.mm` 入库；`/messages` 支持 `pkg` 过滤；PWA 控制台加应用角标与筛选（见 `7b154b0`）
2. [x] **应用源注册表** `AppSourceRegistry`：`AppSource(pkg, label, parser, a11yConfig?)`
   - 微信源：现有 MessagingStyle 解析 + 群聊/私聊判定 + a11y 控件 id 表（5 个 id 移进配置）
   - 飞书/钉钉源：`PrefixImParser`（全角/半角冒号、长发送者名折叠等均有单测）；未收录 App 回落 `GenericNotificationParser`
3. [x] **监听器改造**：`WeChatNotificationListener` → `NotifyMeListener`，去掉单包判定，改为按 `AppSourceStore` 启用集合过滤
4. [x] **a11y 直读**：仅微信启用（其余 App 无稳定控件 id），注册表里 `a11yConfig == null` 即跳过；服务类改名 `A11yExtractService`
5. [x] **UI**：控制台新增「通知来源」管理页 `SourcesActivity`（勾选启停，已观察来源自动出现）；PWA 加「全部/微信/飞书/钉钉」筛选；关注名单 `WatchlistStore` 的 key 升级为复合键 id
6. [x] **数据一致性**：`AnalysisStore`/`PromptStore`/`ReminderStore`/`PendingQueue`/折叠态/提醒链全部升级为 `ConvKey`，删除路径跟着改；String 兼容重载全部删除，不留两套口径

提交链（均未推送）：`79a16c3` M2.1 schema → `d758a69` M2.2 监听器 → `9263b95` M2.3 a11y → `dc99281` M2.4 全链路 ConvKey → `7b154b0` M2.5 服务端/PWA → `56581ed` M2.6 来源管理 UI → `4d304bf` M2.7 文档归档与架构图。

验收：
- [x] 微信链路代码级回归：五项删除功能 + 分析 + 提醒全部走 ConvKey 且 201 单测全绿（真机端到端回归留待下一个调试装机轮次）
- [x] 非微信解析器单测入库/分组/群私聊判定全绿（飞书/钉钉合成通知样本）；**真机通知形状取证仍待办**——真实 `dumpsys notification` 形状未抓，解析器刻意保守，取证方法见各文件注释
- [x] 老 `messages.jsonl`（无 `pkg`）读时回填默认微信，可正常读、删、同步，不丢行（`SchemaV2Test` 覆盖同名会话跨 App 删除隔离）
- [x] M1 单测扩充到复合键路径并全绿（189 → 201）

遗留：真机飞书/钉钉通知实测验证（列入下一轮设备调试）；首页/控制台安卓端尚未加 App 筛选 chips（PWA 已有，安卓端入口是来源管理页）。

---

## 5. M3 隐私信息保护 — 已完成（2026-10-05，README 路线图 #4）

**目标**：数据出设备之前，用户能选择脱敏，并能看见脱敏了什么。

任务清单：
1. [x] `RedactorCore`：规则化端侧脱敏（手机号、身份证、银行卡、邮箱、URL、金额、地址、姓名）；纯 JVM、30 个单测
2. [x] 三档策略：**关闭**（默认）/ **出设备脱敏**（发往云端 S1/S2、上报前脱敏）/ **仅端侧分析**（云端分析与上报整体停用）；`PrivacyModeState` 语义 6 个单测
3. [x] 单点插入：`AnalysisWorker` 云端 S1（laya）与 S2 统一用脱敏副本，本地模型/prefilter 用原文；`SyncWorker` 消息/分析/顾问三通道按模式门控
4. [x] 会话级白名单：`bypassConvIds` 存 `PrivacyConfig`（prefs `privacy_config`），控制台多选
5. [x] **脱敏预览页** `RedactorPreviewActivity`：最近消息逐行「原文 → 将发送文本」对照，模式横幅与白名单如实标注
6. [x] `PRIVACY.md` 增补脱敏章节（三档口径、规则清单、保守取舍、哈希别名局限）；分析记录只存 `redacted` / 命中规则 id / 命中数，不记原文

提交链：`b21f067`（M3.1 脱敏内核）→ `ce6468a`（M3.2-M3.6 策略/隐私卡/云端接线/预览）→ M3.7 文档。

验收：
- [x] 单测覆盖每类规则（含中英混排、带空格/连字符的号码、长串内部不命中、时间/数量不误伤）；237 个单测全绿，open/beta 双 flavor assemble 通过
- [x] 模式门控口径单测钉死（LOCAL_ONLY 的 cloudBlocked、白名单内外 shouldRedact）；真机抓包验证留待真机回归
- [x] 预览页对照准确（白名单会话、OFF 模式显原文；LOCAL_ONLY 横幅注明不发送）

遗留：真机「仅端侧」抓包确认零云端请求（与 M2 真机端到端回归一并执行）；消息文本里非会话名/非发送者的人名无法识别替换（PRIVACY.md 已注明）。

---

## 6. M4 端侧推理引擎（README 路线图 #6，高风险）— spike 已完成（2026-10-05）

**目标**：`LocalLlmEngines.forModel` 返回真引擎，S1 快筛全离线可用。上层（`AnalysisWorker.runS1Local`/`runS2Local`）已按接口写好，**零改动**。

> **执行结果（2026-10-05）**：未采用第三方 AAR（路线 A 的 AAR 只支持 arm64、
> 无法在 MuMu 验证），改为**自己拉 llama.cpp v0.5.0 源码 + CMake 交叉编译**——
> 同时产出 arm64-v8a 与 x86_64，CPU 后端静态链接、单 .so 自包含。
> MuMu x86_64 冒烟实测：Qwen2.5-0.5B-Instruct 加载约 1.1s、补全 4064ms、输出连贯。
> 提交 `c1480b7`（M4.1 引擎）/ `9d3d766`（M4.2 冒烟页），均未推送。

决策门 D3（先拍板再动工）：
| 路线 | 优点 | 代价 |
|---|---|---|
| **A. llama.cpp 第三方 AAR**（`dev.ffmpegkit-maintained:llama-android:0.1.1`） | 落地最快，纯 Gradle 依赖 | 仅 `arm64-v8a`、无流式；**x86_64 模拟器（MuMu）跑不了**，只能真机验证；S1 是 MNN 格式，需换 GGUF 模型 |
| **B. MNN-LLM NDK 自编译** | `arm64-v8a` + `x86_64` 都能出 so，可在模拟器验证；与已下载的 S1 MNN 模型格式对齐 | 需 NDK 工具链与 `build_64.sh` 编译经验，JNI 封装工作量大 |
| **C.（实际采用）llama.cpp 源码 CMake 自编译** | 双 ABI 出 so、MuMu 可验；静态后端单 so 免加载路径；版本可控 | 需本地/CI 拉源码（gitignore，不入仓库）；S1 MNN 格式仍需另接或换 GGUF |

推荐：**先 A 做 spike 验证端到端链路与时延/内存，再按需要转 B**（S2 的 4B GGUF 本来就走 llama.cpp 路线）。
实际执行以路线 C 完成 spike，结论见上。

任务清单：
1. [x] Spike（1 天）：加载 GGUF → 一次 `chat(system,user,maxTokens)` → 记录时延。
   模拟器（MuMu x86_64）：加载 1.1s、补全 4064ms；真机与峰值 RSS/发热待 W1 同批记录
2. [x] 实现引擎 `LlamaCppEngine`：加载/卸载生命周期、异常 → 抛 `LocalEngineException`
   （上层已有降级文案）；未知/MNN/未下载模型均优雅降级为不可用引擎
3. [x] 准入判定：`ActivityManager.memoryInfo` + `Build.SUPPORTED_ABIS` → S2（2.47 GB）仅在 8 GB+ RAM 且 arm64 时开放，否则引擎工厂返回带理由的不可用引擎（`LocalDeviceCapabilities`）
4. [x] `LocalLlmEngines.releaseAll()` 接 `MainApplication.onTrimMemory`（≥MODERATE）/`onLowMemory`
5. [x] `ModelDownloadWorker`/`ModelListActivity`：断点续传校验、下载失败重试文案 —— 复核（2026-10-05）确认已全部落地：`.part` + `Range` 续传且 206/200 兜底（`ModelDownloadWorker.kt` `downloadFile`）、按预期字节数最终校验（`doWork` 尾部 `bad` 过滤）、`runAttemptCount<1` 退避重试；UI 侧 `ModelListActivity.onResume` 杀进程自动续传检测、`model_status_error_resume`/`model_status_partial`/`model_btn_retry`/`model_no_space` 文案齐备（`strings.xml:266-279`）。`testOpenDebugUnitTest --rerun-tasks` 绕缓存真实执行 243 全绿，双 flavor assemble 通过
6. [ ] 分析质量对比：同一批会话，云端 vs 端侧 S1 判定一致率、S2 摘要可用性，写进 `docs/` 评测记录

验收：飞行模式下 S1 分析全流程走通并出 ⚡ 标记；引擎不可用时降级文案正确、不崩；评测记录有真实数字。

**spike 已证明**：GGUF 加载 + 模板套用 + prefill + 采样全链路在端侧工作。
**S1 已切换为 GGUF（M4.4）**：原 S1 是 MNN 格式（llama.cpp 不识别），
改用已实测的 Qwen2.5-0.5B-Instruct GGUF（469MB）——离线 S1 因此真正可用，
不再需要另接 MNN。真机 8GB+ 上的 S2（4B）内存/时延仍待 W1 取证。

---

## 7. M5 iOS 端（README 路线图 #1 —— 需重述）

**必须先纠正定位**：iOS 不允许后台捕获其他 App 的通知，`README.md:92` 已承认这点。
原路线图写的「iPhone 本地捕获与分析能力」**在 iOS 上不可实现**，建议把 #1 改述为
「**iOS 原生查看端 + 提醒推送**」，避免对外承诺做不到的能力。

可选形态：
| 方案 | 能力 | 成本 |
|---|---|---|
| **A. 原生 SwiftUI 查看端** | 复用现有 API（`/messages`、`/analysis`、`/advisor`），本地缓存、离线阅读、APNs 推送提醒 | 需 Apple 开发者账号（$99/年）+ 推送证书；5–8 天 |
| **B. 强化现有 PWA** | 补 sw.js 离线缓存、安装引导、图标/启动屏、暗色 | 零外部成本；1–2 天；**无系统推送**（iOS PWA 推送需已安装到主屏 + iOS 16.4+） |

任务清单（若走 A）：
1. 服务端：为 iOS 增加只读聚合端点（会话→日期分组直接在服务端算，省客户端逻辑）+ token 鉴权复用 `check_token`（`server/app.py:155`）
2. APNs：提醒生成时由**服务端**推送（安卓端产生提醒 → 上报 → 服务端推 iPhone），需要提醒上报端点
3. SwiftUI：会话列表 / 会话详情 / 分析结果 / 提醒管理四屏，Keychain 存 token 与服务器地址
4. HTTPS 前置：APNs 与 ATS 都要求 TLS，文档给出反代方案（仓库内域名一律示例）

---

## 8. M6 UI 优化（#5）／M7 通知之外的信息融入（#3）／M8 智能硬件（#7）

**M6**（低风险，可插空做）：
- `MainActivity.kt`(926) / `ConsoleActivity.kt`(939) 拆分：Adapter 与 ActionMode 逻辑外提；不引入 Compose（保持零额外依赖的取舍，`app/build.gradle.kts:102`）
- 长会话分页/虚拟化（当前 JSONL 全量读入内存，M2 后消息量会显著上升 → 可能需要索引文件）
- 可读性：字号/对比度/暗色主题；多端适配（平板、折叠屏）
- 空态与错误态统一文案

**M7**（依赖 M2）：把自己发出的消息等「通知流之外」的数据纳入
- 途径 1：a11y 直读微信发送框（已有 a11y 基建，`WeChatA11yExtractService`），标记 `source=a11y-self`
- 途径 2：数据库回填脚本（`server/android_db_extract.py`、`pc_db_extract.py` 已存在）导入历史 → 入库时带 `source=backfill`
- 分析层：窗口拼装时区分「对方说」与「我说」，S1/S2 prompt 增加视角字段

**M8**（依赖 M2）：Wear OS 伴侣（通知镜像 + 提醒同步）、家居设备（提醒到达时闪灯/播报）；先做技术验证再排期。

---

## 9. M9 移动端悬浮通知（用户新增需求，m00162）— 代码已完成（2026-10-05，模拟器实测；真机待验）

**由来**：用户明确要求「移动端应用需要做成悬浮」。通知进通知栏后用户必须切出当前 App 才能处理，
M9 让重要消息以悬浮层直接出现在任意界面之上。

**形态（已与用户确认的设计基线）**：
1. **悬浮通知卡片**（角标卡，屏幕顶部/右上角）：新消息触发，显示
   - 来源 App 名 · 会话名 · 发送者 · 内容摘要（一行/两行，超长省略）；
   - 两个操作：「标记已处理」（本地标记，不进待办列表，卡片消失）、「打开详情」
     （拉起 `ConversationActivity`，对应会话）；
   - 仅对 S1 判定需要行动（或重点关注名单内）的消息弹卡，普通消息仍走系统通知，避免打扰。
2. **常驻悬浮球**（边缘停靠）：
   - 拖动后自动吸附屏幕左右边缘，点击展开快捷面板（最近待办 / 最近 N 条未处理消息 / 一键进 App）；
   - 无待办时半透明缩小，不遮挡操作。
3. **降级链**：
   - `SYSTEM_ALERT_WINDOW` 被拒 → 引导用户去系统设置开启；被拒期间悬浮卡降级为系统 heads-up 通知
     （现有通知渠道已支持），功能不中断只是不能覆盖在其它 App 上；
   - MIUI/HyperOS 除悬浮窗权限外还需开启「后台弹出界面」，否则卡片不显示——设置引导里单列说明；
   - Android 12+ 从后台启动 Activity 受限：「打开详情」若不能直接拉起，先给一条全屏 intent 通知兜底；
   - Android 14+ 承载悬浮层的前台服务必须声明 `foregroundServiceType`（specialUse 并说明用途）。

任务清单：
1. [x] 悬浮窗权限引导页：检测 `Settings.canDrawOverlays`，未授权跳 `ACTION_MANAGE_OVERLAY_PERMISSION`；
   MIUI 额外引导「后台弹出界面」（Intent 不可直接跳转时给图文步骤）
   —— 控制台卡 10 权限按钮 + onResume 刷新；MIUI 真机引导随真机回归补
2. [x] `OverlayManager` + `OverlayService`（前台服务，specialUse）：权限满足时维护悬浮球视图；
   卡片用独立 WindowManager view 添加/移除，动画与自动消失（如 5s）可配置
3. [x] 触发接线：`NotifyMeListener`/分析完成路径把「需行动」消息投递给 OverlayService；
   「标记已处理」写本地状态（复用现有 store，不新建口径）
4. [x] 悬浮球快捷面板：待办列表来自 `AnalysisStore`/`ReminderStore`，点击进对应详情
5. [x] 降级：无权限时确认走 heads-up 通道且文案告知；Android 12/14 限制逐项实测
   —— Android 15 MuMu 模拟器已实测（含 specialUse FGS）；Android 12/13 与 MIUI 真机待 W1
6. [x] 文档：权限与厂商 ROM 说明进 `docs/BUILD.md` / onboarding
   —— `docs/BUILD.md` 文件职责表已补悬浮/诊断条目；onboarding 与 MIUI 图文引导随真机回归

完成记录（提交均未推送）：
- `6e1a569` M9 主体：`OverlayManager`/`OverlayService`/`OverlayConfig`/`OverlayActionReceiver` +
  卡片/球/面板视图、Manifest 权限与 specialUse 声明
- `64dbbe8` M9.6 控制台调试测试卡按钮（仅 `BuildConfig.DEBUG` 可见）
- `6bd4120`（微信专项 W3）会话详情页快速回复：`ReplyActionStore` 进程内缓存通知 RemoteInput，
  自发消息右侧绿气泡
- `548e5ef`（微信专项 W4）`CaptureDiagnosticsActivity` 采集诊断 + 一键打开来源应用

验收：微信/飞书/钉钉任一「需行动」消息在桌面和第三方 App 之上弹卡；标记已处理后不再出现在待办；
悬浮球拖动吸附、点击展开待办；无权限时降级 heads-up 且不崩；MIUI 真机（设备 ebb079b5）实测通过。

**模拟器实测结果（MuMu Android 15，2026-10-05）**：测试卡 900×345 正常绘制；
球 120×120 拖动后 200ms 吸附边缘；面板开合、卡片 5s 自动消失、dismiss 移除全部通过。
**待真机 W1 补验**：MIUI/HyperOS「后台弹出界面」、heads-up 降级链、微信真机通知形状。

---

## 10. M10 多模型分析拓扑（Advisor 层，2026-10-05 立项·用户确认）

对齐「主会话 + fork 层 + subagents + advisor」多模型拓扑：

- **主会话 = S2 深分析**（已有）：唯一结论写入者；端侧 GGUF 或云端 OpenAI 兼容均可。
- **JEV fork 层 = S1 快筛**（已有，M10.2 显式化）：`ForkPrefilter`（fork1，sharp 就地结案）
  → S1 判定（fork2）→ `shouldEscalate` 升级判定（fork3，sharp/split）。
- **advisor = 第二模型评审**（M10.1/M10.3 新增）：`AnalysisConfig.PRESET_ADVISOR` 独立槽位
  （固定 openai 协议，开关默认关，零预置不变）；两个挂点——
  ① 落库前 review（`analyzeOneCase` 尾部，`ForkRecord(fork="advisor", verdict="advice")`，
  失败记 `advisor_error` 不拖垮 case；仅端侧模式整体跳过；内容走脱敏口径，会话名不外发）；
  ② error-repeats（连续失败 ≥2 轮时把**错误摘要**发给 advisor 求诊断，成功即清零计数）。
- **评测记录**（M10.4，顺带 M4.6）：fork 各级 sharp/split 比例 + advisor 命中情况落 `docs/`。

任务清单：
1. [x] `AnalysisConfig`：`PRESET_ADVISOR` 槽位 + `advisorEnabled` + `advisorSlot()` + `consecFailures`
2. [x] `AnalysisCase`：`buildAdvisorSystemPrompt` / `buildAdvisorUserContent` / `buildAdvisorErrorContent` 纯函数
3. [x] `AnalysisWorker`：before-done review（`advisorFork`）+ error-repeats（`recordRetryFailure`，成功清零）
4. [x] 控制台 UI：advisor 分组（开关/地址/密钥/模型/测试连接，随开关置灰）+ strings
5. [x] 单测：advisor 提示词纯函数 3 个（AnalysisCaseTest）
6. [x] 构建验证：246 单测全绿（243+3 advisor 提示词），双 flavor assemble 通过（2026-10-05）
7. [~] 真机端到端：S1=Laya jev-latest 升级链路实测通过（need_action 0.76 → split）；S2=GLM 401 密钥失效待换，⚡/悬浮卡视觉验证与评测定稿顺延。评测记录：docs/评测-分析判定-20261007.md（M4.6/M10.4 主体完成）

---

## 11. M11 控制台/对话分离 + M12 分析范围（2026-10-07）

- **M11 已完成**（3570f92 + e56952d + 8bded2e，251 单测全绿）：主界面纯对话化（☰ 菜单：控制台/刷新/清除；权限告警仅未授权显示）；控制台拆为入口卡片首页 + 采集/分析/提醒/隐私/悬浮五子页（subagent 迁移，控件 id 零改动）；来源白名单模式 + 应用选择器；对话详情页分析进度条 + 离线约束修复；来源 App 全量显示（含微信）；消息卡按会话头靠右缩进；App 改名 notifyme（beta=notifyme·测试）。
- **M12 待实现**：分析范围筛选——周期（近1月/近7天/今天/自定义）+ 对象多选 + 显式范围跳过去重与名单 + 分析页 TAG_ON_DEMAND 进度条。
- 真机测试成果与修复：llama KV cache（557f640）、强制分析双拦截（81cc283）、S1 模型名 jev-latest 勘误（31c60ee）；评测记录 docs/评测-分析判定-20261007.md。

## 12. 决策门汇总

D1–D4 已在 2026-10-04 拍板（下表「结论」列即最终决定，不再重开）：

| ID | 决策 | 选项 | 结论 |
|---|---|---|---|
| **D1** | M0 签名与发布方式 | (a) CI + GitHub Secrets 存 keystore 自动签名发 Release　(b) 本机签名手工上传　(c) 先发 debug 签名并注明 | ✅ **(a)** — `release.yml` 已就绪，只差 Secrets 写入权限 |
| **D2** | 版本号 | (a) `0.2.0/5`　(b) `0.1.4/5` | ✅ **(a)** `0.2.0/5` — 已落到 `app/build.gradle.kts:64-65` |
| **D3** | M4 引擎路线 | (A) llama.cpp AAR（arm64，快）　(B) MNN NDK 自编译（含 x86_64，慢） | ✅ **先 A spike，再按需转 B** — 上层 `AnalysisWorker.runS1Local/runS2Local` 已按 `LocalLlmEngine` 接口写好，换引擎上层零改动 |
| **D4** | iOS 定位 | (A) 原生 SwiftUI 查看端 + APNs　(B) 强化 PWA　(C) 暂缓 | ✅ **文案已改述为「iOS 原生查看端 + 提醒推送」**（README + M5），实现排在 M0–M3 之后，届时按实际使用需求决定 A/B/C |

### D5–D6 已拍板（2026-10-04，同批决定）

| ID | 决策 | 结论 |
|---|---|---|
| **D5** | 正式签名密钥库怎么生成与保管 | ✅ **用户提供带 `administration` 权限的新 token，由助手完成全流程**：生成正式密钥库（存仓库**外**，如 `..\notifyme-secrets\`）→ `gh secret set` 写入 4 个 Secrets（`NOTIFYME_KEYSTORE_BASE64` / `_KEYSTORE_PASSWORD` / `_KEY_ALIAS` / `_KEY_PASSWORD`）→ 设 About/Topics → 打 `v0.2.0` tag → 核对 Release 产物（APK + `.sha256`）。token 通过仓库根 `.gh-token` 递交（已 gitignore），不进聊天记录。**密钥库一旦确定就不能再换**：签名变了老用户无法原地升级，卸载会清空设备上的 JSONL 数据 —— 生成后须由用户自行离线备份 |
| **D6** | 是否重写 git 历史 | ✅ **接受现状，不重写**（不做 `git filter-repo`、不 force push）。理由：残留字面量只是个人域名/用户名与旧私有命名，无凭证价值；全仓 + 684 个历史 blob 的密钥扫描无命中。风险记档在 §2「M0 遗留风险」，CI Guard 只拦新增 |

**D5 当前状态：等待用户把新 token 写入 `C:\project\notifyme\.gh-token`。**

---

## 13. 非目标（明确不做）

- 不做云端多用户 / 账号体系（服务端始终是单用户自托管）
- 不引入 Room / Compose / DI 框架做大重构（`MessageStore.kt:57-66` 的取舍仍然成立，除非 M6 评测推翻）
- 不分发逆向工具链与任何来源不明/第三方版权代码（§六 硬约束）
- open flavor 永不预置密钥、服务器地址、模型直链之外的任何私有配置
- 不在未获明确授权时推送远程或发布 Release
