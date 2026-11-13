# notifyme 路线图与执行计划

> 制定日期：2026-10-04　依据：`progress.md` + 代码实测核对（所有结论均带 `文件:行` 证据）
> 本文是 README「下一步计划」8 项的**可执行展开**：拆成 M0–M9 里程碑（M9 为用户新增的悬浮窗需求），含任务清单、验收口径、工作量与风险。
> 硬约束沿用 `progress.md` §六：只在本仓库开发、open flavor 零预置、密钥不入库、未经明确要求不推送/不发版。

---

## 现状速查（2026-11 对齐）

> §0 是 **2026-10-04 的历史快照**，保留用于对照；下表是当前代码事实，以它为准。

| 维度 | 现状 | 证据 |
|---|---|---|
| 版本 | versionName `0.3.19` / versionCode `25` | `app/build.gradle.kts:89-90` |
| 工具链 | AGP 8.5.2 / Kotlin 2.2.21 / Gradle 8.9（wrapper 自带）/ JDK 17 / compileSdk 34 / minSdk 26 | 根 `build.gradle.kts`、`app/build.gradle.kts` |
| flavor | `open`（零密钥）/ `beta`（`secrets.local.properties` 注入，`applicationIdSuffix=.test`，桌面名「notifyme·测试」） | `app/build.gradle.kts:100-128` |
| release 签名 | 环境变量 > 仓库根 `keystore.properties` > 降级为不签名；CI 用 `-PrequireReleaseSigning=true` 让签名缺失直接失败 | `app/build.gradle.kts:131-169` |
| CI | `.github/workflows/android.yml`：lint + 全量单测 + 双 flavor + 服务端语法检查（目录级）+ pytest（目录级）+ 隐私守卫 + 版本校验，带依赖缓存 / 60 分钟超时 / 并发取消 | `.github/workflows/android.yml` |
| 发布 | `.github/workflows/release.yml`：tag 与 versionName 校验 → 签名构建 → `apksigner verify` → SHA256 → `gh release create` | `.github/workflows/release.yml` |
| 发版现状 | 最近一次公开 Release = `v0.3.8`（2026-10-13）；`versionName` 一路到 `0.3.19` 但 `0.3.9`–`0.3.19` 都没打 tag → **这些版本未发布**。发版只需 `git tag v0.3.19 && git push origin v0.3.19`（tag 必须与 `versionName` 逐字一致）；升不升版是维护者决定项 | `gh release list`、`git tag --list v*`、`docs/BUILD.md` 的「发版现状」 |
| 测试 | 纯 JVM 单测 83 个 `.kt` / 1322 条（`app/src/test`）+ 服务端 `server/test_*.py`（513 条） | `git ls-files "app/src/test/**/*.kt"` |
| 服务端 | 鉴权令牌 `WEIXIN_TOKEN` 必填（未设且未放行则拒启动）；监听 `0.0.0.0:8000` / `127.0.0.1:8000` 按是否鉴权自动决定；`/messages`、`/analysis` 用 `next_before` 游标；`/advisor` 收发顾问报告；`/queue` 收发排队任务快照（控制台第 5 个 Tab） | `server/app.py` |
| 控制台 | PWA 两栏布局 + 5 个 Tab（会话 / 待办 / 排队 / 统计 / 顾问）+ 顶部概览条 5 指标；会话卡内「日期 / 发送者」两级分组可切换（`wx_group_v1` 持久化）；排队 Tab 支持按设备筛选（设备清单来自 `GET /queue` 的 `devices` 聚合）；归类规则集中在 `server/console_classify.js`（UMD，node 单测） | `server/console_ui.py` |
| 仓库卫生 | CI 隐私守卫拒密钥文件 / APK / 用户 JSONL / 私有旧命名 / 真实用户目录；守卫与版本校验脚本在 `docs/scripts/`，本地与 CI 跑同一份实现 | `docs/scripts/privacy_guard.py` |

---

## 0. M0 前基线快照（历史记录，**不是**当前状态）

> 这张表是 2026-10 制定路线时记录的 M0 前基线，作为「为什么要有 M0/M1」的论据保留，**已不再反映当前代码**。
> 当前状态见本文档开头的「现状速查（2026-11 对齐）」表，以及 docs/architecture/README.md 的当前行数与模块清单。
> 端侧推理一行尤其过期：`UnavailableLocalLlmEngine` 早已换成 `LlamaCppEngine`（`forModel` 已实现，S1 可全离线跑通）。

| 维度 | 现状（M0 前基线） | 证据 |
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
| 服务端 API | `POST /weixin`、`GET /messages`、`POST/GET /analysis`、`POST/GET /advisor`、`POST /admin/extract`、`/console` PWA + manifest + sw.js → **iOS/多端可直接复用** | `server/routes.py:20,169,322,548,718,807,844,889`（数据面）、`server/app.py:470,484,490,498`（/console 与 PWA 资源） |
| 入站校验 | `_validate_item` 决定 schema v2 是否要服务端同步改 | `server/util.py:257` |

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
| **M4 端侧推理引擎** ✅ | #6 | 把 `UnavailableLocalLlmEngine` 换成真引擎，S1 全离线跑通（已完成：`LlamaCppEngine` + `LocalLlmEngines.forModel` 落地，评测记录见 `docs/评测-云端vs端侧S1.md`） | M1、决策 D3 | 5–10 天 | **高** |
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
4. [x] 静态检查基线（只报警不阻断），`lint.xml` 收口 —— **已完成**（2026-11-13）
   - **ktlint 侧**：仓库根新增 `.editorconfig`，把 `ktlint_standard` 整体 disabled 后只开 5 条 ——
     `import-ordering` / `no-unused-imports` / `final-newline` / `no-consecutive-blank-lines` / `string-template`。
     没有采用官方全量 standard：按默认规则集跑 `app/src/**/*.kt` 是 **8134 条命中 / 44 条规则**
     （`function-signature` 1722、`indent` 1615、`trailing-comma-on-call-site` 1044、
     `multiline-expression-wrapping` 997 …），这些都是格式化偏好而不是缺陷，一次性重排会把 205 个文件的
     diff 搅成噪声；而且和本仓库既有事实冲突（最长行 259 字符、34 处 >120 字符、`.kt` 行尾 LF/CRLF 混用）。
     收窄到这 5 条后，它们全部能被 `--format` 自动修干净，且每条都对应真实的可读性/合并冲突代价。
   - **一次 `--format` 清账**：70 个文件、+102/−128 行（删 13 个 unused import、重排 34 个文件的 import 块、
     补 29 个末行换行、删 12 处连续空行、9 处多余 `${}`）。修完 `testOpenDebugUnitTest` 仍是 **1157 条全绿**
     —— 纯格式改动没碰语义。基线从此恒为 0，任何新增违规都必然可见。
   - **`docs/scripts/static_check.py`**（217 行）是唯一入口：ktlint 版本 `1.8.0` 与 `sha256`
     `369ad2b789f95a011f807e1fcb690ccef80bd7cd014fd139e73ae82dcc0baeab` 钉死在代码里（与 Maven Central 的 `.sha256` 一致，不符即删），
     jar 缓存到 `~/.notifyme-tools/ktlint/1.8.0/`（`NOTIFYME_KTLINT_JAR` 可直接指定、`NOTIFYME_TOOLS_DIR` 换缓存根）。
     三种模式：默认 `--report` 有命中仍 exit 0（CI 用，只报警不阻断）、`--strict` 有命中 exit 1（本地自检用）、
     `--fix` 先 `--format` 再复查。末行固定输出 `static-check-summary issues=<n> files=<n> rules=<n> enabled=5`。
     若出现 5 条之外的规则（说明 `.editorconfig` 与脚本不同步）会额外告警并强制 exit 1，防止规则悄悄漂移。
   - **`app/lint.xml`**：刻意「什么都不改」—— 不 ignore、不降级。只接受 3 条已知 issue
     （`ObsoleteLintCustomCheck` / `OldTargetApi` / `ChromeOsAbiSupport`），与其 ignore 掉让「第 4 条」
     从报告里消失，不如让它们一直可见，由 `docs/scripts/local_check.ps1` 的 `lint-summary` 断言
     「报告里的 issue id 集合恰好等于这三条」—— 多一条或少于三条都本地失败，等于给 lint 装了回归护栏。
   - **接线**：`docs/scripts/local_check.ps1` 从六步变七步（第 7 步 `static-check --strict`），
     `.github/workflows/android.yml` 加同名只读步骤（`python3 docs/scripts/static_check.py`，不阻断）。
     **为什么不上 Gradle 插件**：`org.jlleitschuh.gradle:ktlint-gradle` 只在 Plugin Portal 有（Maven Central 404），
     且会改 Gradle 依赖图与首次构建时长；独立 CLI jar 版本可控、sha256 可校验、不参与构建图，更符合「只报警」定位。

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
- [x] `CalendarHelper` 三级降级链的决策逻辑抽成可测纯函数 —— **已完成**（2026-11-12）：决策逻辑抽成 `ReminderChainCore`（`internal object`，纯函数、13 条单测），`CalendarHelper.runReminderChain` 退回「采集环境事实 → 执行副作用」

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
   - 三个解析器的抽取口径（剥未读前缀 / 切发言人 / 驳可疑前缀 / 判群聊）统一在 `NotificationTextCore`（纯函数，19 条单测）——真机取证后从「每个 parser 各写一套」收成一套
3. [x] **监听器改造**：`WeChatNotificationListener` → `NotifyMeListener`，去掉单包判定，改为按 `AppSourceStore` 启用集合过滤
4. [x] **a11y 直读**：仅微信启用（其余 App 无稳定控件 id），注册表里 `a11yConfig == null` 即跳过；服务类改名 `A11yExtractService`——**飞书/钉钉的聊天页控件 id 仍未取证，故 `a11yConfig` 留 null（宁可不直读也不猜 id）**，取证命令见 [通知形状取证记录](通知形状取证记录.md) §2.2 / §4
5. [x] **UI**：控制台新增「通知来源」管理页 `SourcesActivity`（勾选启停，已观察来源自动出现）；PWA 加「全部/微信/飞书/钉钉」筛选；关注名单 `WatchlistStore` 的 key 升级为复合键 id
6. [x] **数据一致性**：`AnalysisStore`/`PromptStore`/`ReminderStore`/`PendingQueue`/折叠态/提醒链全部升级为 `ConvKey`，删除路径跟着改；String 兼容重载全部删除，不留两套口径

提交链（均未推送）：`79a16c3` M2.1 schema → `d758a69` M2.2 监听器 → `9263b95` M2.3 a11y → `dc99281` M2.4 全链路 ConvKey → `7b154b0` M2.5 服务端/PWA → `56581ed` M2.6 来源管理 UI → `4d304bf` M2.7 文档归档与架构图。

验收：
- [x] 微信链路代码级回归：五项删除功能 + 分析 + 提醒全部走 ConvKey 且 201 单测全绿（真机端到端回归留待下一个调试装机轮次）
- [x] 非微信解析器单测入库/分组/群私聊判定全绿（飞书/钉钉合成通知样本）；
  **真机通知形状取证：微信 + 钉钉已取到真实 `dumpsys` dump（2026-11-12，Xiaomi 25098PN5AC / Android 17），飞书仍待补**——取证结论见 [通知形状取证记录](通知形状取证记录.md) §3，口径由 `NotificationShapeEvidenceTest` 17 条 + `NotificationTextCoreTest` 19 条固化
- [x] 老 `messages.jsonl`（无 `pkg`）读时回填默认微信，可正常读、删、同步，不丢行（`SchemaV2Test` 覆盖同名会话跨 App 删除隔离）
- [x] M1 单测扩充到复合键路径并全绿（189 → 201）

遗留：飞书真机通知实测验证（微信/钉钉已取证，缺的只是飞书 dump 原文；清单与命令见 [通知形状取证记录](通知形状取证记录.md) §6）；历史重复消息用 App 内「菜单 → 归并重复消息」清理（`MessageDedupeCore`，17 条单测）；首页已加「按来源 App」筛选 chips（`AppFilterCore`，16 条单测，PWA 与控制台安卓端同口径）。

---

## 5. M3 隐私信息保护 — 已完成（2026-10-05，README 路线图 #4）

**目标**：数据出设备之前，用户能选择脱敏，并能看见脱敏了什么。

任务清单：
1. [x] `RedactorCore`：规则化端侧脱敏（手机号、身份证、银行卡、邮箱、URL、金额、地址、姓名）；纯 JVM、30 个单测。**后续补强（M3+，2026-11-13）**：姓名别名的盐从「无盐 sha256(名字)」升级为「设备随机盐 + sha256(盐 ‖ 0x00 ‖ 名字)」，抽成 `AliasSaltCore`（`object AliasSaltCore` 纯函数 16 条单测 + `AliasSaltStore` 落盘），并给 `redactCase` / `redactMessages` 两个窗口级函数补了 6 条端到端单测
2. [x] 三档策略：**关闭**（默认）/ **出设备脱敏**（发往云端 S1/S2、上报前脱敏）/ **仅端侧分析**（云端分析与上报整体停用）；`PrivacyModeState` 语义 6 个单测
3. [x] 单点插入：`AnalysisWorker` 云端 S1（laya）与 S2 统一用脱敏副本，本地模型/prefilter 用原文；`SyncWorker` 消息/分析/顾问三通道按模式门控
4. [x] 会话级白名单：`bypassConvIds` 存 `PrivacyConfig`（prefs `privacy_config`），控制台多选
5. [x] **脱敏预览页** `RedactorPreviewActivity`：最近消息逐行「原文 → 将发送文本」对照，模式横幅与白名单如实标注
6. [x] `PRIVACY.md` 增补脱敏章节（三档口径、规则清单、保守取舍、哈希别名局限）；分析记录只存 `redacted` / 命中规则 id / 命中数，不记原文。**2026-11-13 已把别名局限从「可被候选名单反解」改为「设备随机盐：拿到设备存储的人才能重算」**，并补了设置页「别名盐」重置入口

提交链：`b21f067`（M3.1 脱敏内核）→ `ce6468a`（M3.2-M3.6 策略/隐私卡/云端接线/预览）→ M3.7 文档。

验收：
- [x] 单测覆盖每类规则（含中英混排、带空格/连字符的号码、长串内部不命中、时间/数量不误伤）；237 个单测全绿，open/beta 双 flavor assemble 通过
- [x] 模式门控口径单测钉死（LOCAL_ONLY 的 cloudBlocked、白名单内外 shouldRedact）；真机抓包验证留待真机回归
- [x] 预览页对照准确（白名单会话、OFF 模式显原文；LOCAL_ONLY 横幅注明不发送）

遗留：真机「仅端侧」抓包确认零云端请求（与 M2 真机端到端回归一并执行）；消息文本里非会话名/非发送者的人名无法识别替换（PRIVACY.md 已注明）；顾问通道（`AdvisorWorker.buildDigest`）的会话名只走规则、不走姓名别名映射（报告是聚合文本、没有窗口发送者名单）——**已于 2026-11-13 补强**（见下段「M3 补强 · 顾问通道」）。

**M3 补强（2026-11-13，姓名别名设备随机盐）**：新增 `AliasSaltCore`（143 行，纯函数 16 条单测）+ `AliasSaltStore`（盐存 `privacy_config` / `alias_salt`，32 字节 SecureRandom）；`NameAliases` 由 `object` 改成 `class NameAliases(salt)`，`redactCase` / `redactMessages` 增加必填 `salt` 参数；四个调用点（`AnalysisWorker` / `SyncWorker` 消息通道 / `SyncWorker` 顾问报告 / `RedactorPreviewActivity`）全部接线；隐私设置页新增「别名盐」按钮（未生成 / 已生成 → 确认重置）。盐为空时逐字节退化为 M3 初版的无盐 sha256，历史别名不失效。
**M3 补强（2026-11-13，顾问通道接姓名别名）**：新增 `AdvisorDigestCore`（179 行，纯函数 36 条单测）把顾问通道的输入摘要口径整体搬出 Worker，并补上此前明确留白的别名映射：`AnalysisCaseRecord` 里既没有 `isGroup` 也没有窗口发送者名单，因此 `NameAliases.nameMapFor` 的三元组只能从**最近消息窗口反推**（`windows()` 按 `(pkg, conversation.trim())` 聚合出发送者集合与群聊标记，`nameMapFor()` 再按会话建映射；窗口里查不到的会话退回七类规则，这条下限同步写进 PRIVACY.md）。`AdvisorWorker.buildDigest` 退化为「采集事实 → 调内核」；`SyncWorker.pushAdvisor` 由 `emptyMap()` 改成`mergedNameMap()`（窗口内全部会话合并，冲突先到先得、按会话键排序保证可复现），顾问报告正文里的会话名 / 发送者也能被换成稳定别名。摘要正文（首行、字段顺序、截断长度、超限省略行）逐字保持旧实现，顾问模型的输入分布不变。


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
6. [x] 分析质量对比：同一批会话，云端 vs 端侧 S1 判定一致率、S2 摘要可用性，写进 `docs/` 评测记录 → [docs/评测-云端vs端侧S1.md](评测-云端vs端侧S1.md)（S1 两侧已量化）、[docs/评测-云端vs端侧S2.md](评测-云端vs端侧S2.md)（S2 摘要可用性：六项判据全过 云端 14/20=70.0% vs 端侧替身 3/20=15.0%（三轮区间：云端 70~75%、端侧 10~15%；已钉 temperature=0 + seed 仍非确定）；端侧替身 0.5B 在 due 同桶 25%、分派条目完整 55% 两项崩得最彻底；真机端侧 MiniCPM5-2B 的数字待补）

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
1. 服务端：为 iOS 增加只读聚合端点（会话→日期分组直接在服务端算，省客户端逻辑）+ token 鉴权复用 `check_token`（`server/app.py:268`）
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
- **M12 已完成**（feat/analysis-scope）：分析范围筛选四要素全部落地 —— ① 周期（不限/今天/近 7 天/近 1 月/自定义）：纯函数 `AnalysisScopeCore.range()` 算 `[fromMs, toMs]`，落盘只存选择不存算出来的毫秒，否则「近 7 天」会钉死在保存那天；② 对象多选：设置页「选择会话（可多选）」列出最近 500 条消息里出现过的会话，选中集合走 `Scope.convIds`；③ 显式范围跳过去重与名单：`forced()` / `skipDedup()` 两个纯函数 —— 点名的会话跳过 `AppSourceStore` 启用集合与 `ConvTagCore` 的 DONE/NOISE 拦截，手动轮再跳 `caseId` 去重；周期轮仍走去重，否则窗口没变也会每 15 分钟重算一遍；④ 分析页 `TAG_ON_DEMAND` 进度条：`AnalysisListActivity` 订阅 `AnalysisProgressBus`，复用 `AnalysisProgressUi`，轮次结束自动刷列表。 四条口径都有纯 JVM 单测：`AnalysisScopeCoreTest` 30 条。
- **搜索覆盖分析结论（feat/home-search-core）**：首页搜索原来只拿「会话名 / 发送者 / 正文」三处 `contains`，于是分析结论搜不到 —— 用户在待办里看到过「提交季度方案」，搜「方案」整组消失。现在命中集合扩到**四类**（会话名 ∪ 来源 App 名 ∪ 消息 ∪ 该会话的全部历史分析结论），口径抽成纯函数 `SearchCore`（`hitKind` / `messageHit` / `caseHit` / `conversationHit` / `dayHit` / `forcedExpand` / `normalize`，44 条单测）。配套三件事：① 搜索态折叠一律强制展开（会话级与日期级同口径走 `forcedExpand`），否则「搜得到看不见」；② 会话头新增命中提示行（⚡ 命中分析结论 / 命中来源 App / 命中消息内容，只命中会话名不提示）；③ 码值翻中文标签再参与匹配（`work_task` 可被搜「工作任务」，`this_week` 可被搜「本周内」）。`SearchCore.caseParts` 的字段清单是唯一口径，别在 Activity 里另写一套；「none」作为 `due_window` 码值被排除，否则搜 "none" 命中全部记录。
- **提醒列表分组筛选（feat/reminder-list-core）**：「已创建的提醒」原来只按 `created_at` 倒序平铺，积到几十条后失败/`待保存` 的条目沉底、过期与未到期混排。现在口径抽成纯函数 `ReminderListCore`（`normalizeFilter` / `isAttention` / `isExpired` / `groupOf` / `arrange` / `keep` / `counts` / `emptyFilter`，**36 条单测**）：三组「需要处理 → 生效中 → 已过期」（`failed` 与 `intent` 都归「需要处理」，失败的排在待保存之前），组内按事件时间（attention/active 升序、expired 降序、无时间垫底）；顶部加四个 chip（全部 / 需要处理 / 生效中 / 已过期，带条数），筛选到某一组时不再插分组标题（整页同组时标题是噪音）。两条保守语义写进类注释：`event_time <= 0` 不算过期（否则用户会删掉可能有效的记录）、`failed` 永不算过期（语义是「等你重试」）。空态文案区分「一条记录都没有」与「本组没有」，否则点了 chip 看到空白会以为数据丢了。
- **会话可见性门槛统一（feat/conv-visibility-core）**：「这个会话该不该出现在主页 / 悬浮面板 / 分析候选里」原本是同一条三段式判定被抄了四遍（`MainActivity.buildFeed` 的 `enabledGroups`、同函数的 `todoVisibleKeys`、`OverlayService.showPanel` 的 `visibleKeys`、`AnalysisWorker` 的分析门禁），而且抄漏一处：**悬浮面板没接首页来源筛选**，首页筛到「飞书」时点悬浮球展开面板，里面仍列着微信待办（真机可复现）。现在口径抽成纯函数 `ConvVisibilityCore`（`why` / `isVisible` / `visibleKeys` 列表与序列两个重载，**28 条单测**）：四层门槛按序判定 —— 关注名单（`WatchlistStore.getUnwatched`）→ 自身/系统源（`AppSourceRegistry.isBlocked`）→ 启用集合（`AppSourceStore.isEnabled`）→ 首页来源筛选（`AppFilterCore.matches`），`why()` 回拦截原因码 `unwatched` / `blocked` / `disabled` / `filtered` 供计数与排查，`isVisible()` 是布尔形态。两条边界写进类注释：`forced`（forceKey / 点名会话）**只放宽「启用集合」这一层**——自身/系统源任何情况都拦，否则 a11y 自抓噪声会跟着「立即分析」进模型；分析门禁**传 `AppFilterCore.ALL`**——首页筛选只影响展示，不能让「首页正在看飞书」把其它来源的周期分析悄悄停掉。悬浮面板的可选项也按前三层门槛算出（`AppFilterCore.resolve` 的回落入参），停用来源不会让筛选卡在那里整页空白。
- **会话详情页按会话取窗口（feat/conv-window-core）**：会话详情页原来读的是 `MessageStore.readRecent(500).filter { it.convKey == convKey }` —— 先在全库最近 500 条里取、再挑本会话，所以「本会话能看到多少历史」取决于别的会话有多活跃：别的会话刷满 500 条，本会话的历史就被整段挤出窗口，详情页空白而 `messages.jsonl` 里数据还在；同一个跨会话窗口还被当成 `chat_truncated`（「仅显示最近 %1$d 条（共 %2$d 条）」）的分母，报出来的「共 N 条」其实是「窗口内命中数」，比真实总数小。现在窗口口径抽成纯函数 `ConvWindowCore`（187 行，38 条单测）：`requestLines(limit)` 按 8 倍请求行数（默认 4000，硬上限 50000，避免行数给爆后退化成全量读）、`slice()` 先按 `convKey` 过滤再按时间升序、超过 limit 保留最近若干条、`reachedHead()` 判「是否已读到文件头」、`preview()` 给出三态（可见条数 / 是否截断 / 条数是否可信）。`MessageStore.readWindow(context, key)` 走新链路，详情页改收 `Window`；截断提示按可信度分三档：可信且截断 → 原「仅显示最近 200 条（共 N 条）」、不可信且截断 → 「仅显示最近 200 条（本条会话在窗口内共 N 条）」、窗口没读全 → 「窗口内 N 条，更早的消息不在当前读取范围内」。`MAX_CHAT_RENDER` 常量也从 Activity 迁进 Core（`ConvWindowCore.MAX_RENDER = 200`）。同时把两处「跨会话窗口取本会话一条消息」的同源隐患一起收掉：`ConvWindowCore.latest(window)` 给 `MainActivity.runTodoReminderChain` 补日历事件描述的发送者 / 原文（旧写法 `readRecent(500).lastOrNull { convKey }` 在别的会话刷满窗口时返回 null，事件描述只剩任务行）；`ConvWindowCore.knownIsGroup(window)` 给 `A11yExtractService` 判群聊（旧写法 `readRecent(500).firstOrNull { convKey }` 取不到就写死私聊，首次提取群聊永远走不到「按昵称回推」）；空窗口返回 **null** 而不是 false —— null 是「没有历史可参考」，false 是「历史判定私聊」，语义不能混。第三处同源隐患同期收掉：分析列表回看原文（`AnalysisListActivity.buildSourceMap`）原来也是 `MessageStore.readRecent(500).groupBy { convKey }`，别的会话刷满窗口后本会话那几条不在窗口里，`tvAnalysisSource` 整行 GONE（数据仍在文件里）—— 现在 `ConvWindowCore.sourceKeys(records)` 给出「要读窗口的会话键集合」（去重保序、跳过空会话名的老记录），`ConvWindowCore.sourceMap(records, windows)` 按每会话自己的 `readWindow` 结果还原 case 窗口原文，取不满就返回空串让调用方隐藏该行，不拿别的窗口内容冒充。
- **分析候选窗口按会话取样（feat/candidate-window-core）**：`AnalysisWorker` 选「本轮分析哪些会话」的输入原来是 `MessageStore.readRecent(context, 500).groupBy { it.convKey }` —— 同样是先在全库取最近 500 条（**跨所有会话**）再分组，「某个会话能不能进候选」取决于别的会话有多活跃：几个群刷满窗口，安静但有待办的会话在候选里一条都没有，永远排不上分析（数据仍在 `messages.jsonl` 里）。这是继会话详情页 / 日历补上下文 / 分析列表回看原文之后的**第四处**同源隐患。现在口径抽成纯函数 `CandidateWindowCore`（69 行，16 条单测）：`group()` 按会话分组、每组按时间升序只留最近 `PER_CONV = 50` 条（5 倍 `AnalysisCase.WINDOW_SIZE`，给「范围是过去区间、比区间新的消息先占名额」留余量）、`ts <= 0` 归 0 排最前、超限只保最近、分组顺序保首次出现；`MessageStore.candidateGroups(context)` 走 `MessageStoreCore.candidateGroups()`（只读尾部 `TAIL_LINES`，不做全量扫描，`internal` 避免暴露内部类型）。日志里的「候选 X 个」改由 `candidateCount()` 给出，与测试共用同一句口径。
- **分析记录的取窗口口径统一（feat/analysis-window-core）**：`analysis.jsonl` 侧此前是三处各自 `AnalysisStore.readRecent(N)` —— 那是**文件尾部 N 行**的窗口，却被当成「某会话的全部记录」「每会话最新一条」「全库列表」的来源。后果与消息侧同源：别的会话把尾部占满，安静会话的 case 就一条都读不到（分析列表从会话头进入时显示「暂无分析结果」、首页 `latestCases` 丢掉它、详情页轮询与悬浮面板取不到最新结论，而记录仍在文件里）；且轮询每 4 秒、面板每次刷新都全量重读尾巴 500 行。现在口径抽成 `AnalysisWindowCore`（159 行，23 条单测）：`group()` 先放大窗口再按 `convKey` 分组、组内按 `windowEnd` 升序（窗口推进顺序可直接读）；`latest()` / `latestByConvKey()` 给「每会话最新一条」；`forConversation(key, limit)` 按 `analyzedAt` 倒序只回本会话；`recent(limit)` 才是全库倒序。窗口量级分两档：`TAIL_LINES = 5000`（列表与首页聚合）与 `POLL_LINES = 1500`（详情页轮询 / 悬浮面板，旧 500 的 3 倍）。文件级读写放在同文件的 `internal class AnalysisWindowFile`（照 `AnalysisRejectFile` 范式），`AnalysisStore.readGrouped` / `readRecentWindow` / `readForConversation` / `latestByConvKey` 全部改走它；`readRecent(context, limit)` 保留给「本意就是全局最近 N 条」的调用点（顾问摘要、设置页统计）。两条已知局限写进类注释：不按会话设条数上限（内存由 `TAIL_LINES` 兜住）、窗口外的老 case 读不到（要更强公平性得上索引，不在本轮范围）。
- **首页信息流按会话取样（feat/feed-window-core）**：首页 `MainActivity.buildFeed` 此前是 `MessageStore.readRecent(this, 500)` —— 跨所有会话的最近 500 条再按会话分组，于是「某个会话能不能出现在首页」取决于别的会话有多活跃：几个群刷满窗口，安静但有内容的会话在首页一条都不出现，首页「最近待办」区的可见集（`todoVisibleKeys`）随之看不到它，而数据其实完好躺在 `messages.jsonl` 里。这是同源隐患的**第五处**（前四处：详情页 / 日历补上下文 / 分析列表回看原文 / 分析候选，均已由 `ConvWindowCore` / `CandidateWindowCore` / `AnalysisWindowCore` 收口）。现在口径抽成纯函数 `FeedWindowCore`（`perConvFor` / `group` / `Bucket` / `Result`，**22 条单测**）：按会话各留一窗，名额按会话数自适应 —— `perConvFor(convCount)` 把 `TOTAL_BUDGET = 6000` 摊薄，单会话夹在 `[MIN_PER_CONV = 20, PER_CONV = 200]` 之间（20 = 2 倍 `AnalysisCase.WINDOW_SIZE`，保证会话再多每会话仍能凑出一个完整分析窗口）；会话间按最近一条倒序（`sortedByDescending` 稳定排序 → 首页顺序可复现）、组内按时间升序、`timestamp <= 0` 归 0 排最前、超限保最近。`Bucket.truncated` 的判定：行窗口**读到文件头**时用 `size > keep`（多出来才是真截断），**没读到文件头**时用 `size >= keep`（刚好顶到名额也可能还有更早的没读进来）；首页据此把会话头条数写成 `N+ 条`（`msg_count_at_least`），不再把「窗口内 N 条」说成本会话总共 N 条。接线五处同口径：首页信息流（`feedGroups` 直接给会话头/天组/消息，`todoVisibleKeys` 用 `feed.buckets`）、悬浮面板窗口键、控制台折叠作用域、关注名单会话键、分析范围会话多选器。真机实证（云手机 127.0.0.1:16384：`messages.jsonl` 604 行 = 乙闲聊群 600 条噪声 + 甲项目群 4 条较早）：旧写法 `readRecent(500)` 的候选只剩 1 个会话，新写法 2 个会话都在，`AnalysisWorker` 日志 `入选 2 个会话 (候选 2 个, 标签拦截 0 个, 未关注拦截 0 个, force=null, 范围=不限 · 全部会话)`。
- **顾问通道的窗口来源与首页同源（feat/advisor-window-source）**：顾问复盘摘要（`AdvisorWorker.buildDigest`）与顾问报告上报（`SyncWorker.pushAdvisor`）的窗口级姓名映射此前都用 `MessageStore.readRecent(500)` 反推 `(conversation, isGroup, senders)` 三元组 —— 那是**跨会话**的最近 500 条，几个群刷满窗口后安静会话在窗口里 0 条，`nameMapFor()` 如实返回空表，该会话的会话名与正文人名**原样出设备**，而 `PRIVACY.md` 承诺的是窗口级别名。这是 readRecent 同源隐患的**第六处**（前五处：详情页 / 日历补上下文 / 分析列表回看原文 / 分析候选 / 首页信息流）。
- **分析记录与消息的剩余跨会话窗口收口（feat/analysis-read-since）**：`AnalysisWindowCore` / `FeedWindowCore` 落地后，仍有四处调用点按「全库最近 N 条」取数，都属于同一个同源隐患的残余（`readRecent` 系列第七批）：① `AdvisorWorker` 的顾问摘要用 `AnalysisStore.readRecent(context, 500).filter { analyzedAt >= since }` —— 7 天窗口内的 case 一旦超过 500 条、或活跃会话把尾部占满，安静会话的 case 根本进不了报告（报告条目数、别名覆盖都跟着失真）；② 设置页「统计」用 `AnalysisStore.readRecent(this, 500)` 数会话数与待办会话数，超过 500 条就是错数；③ 隐私页「脱敏白名单」的候选会话只来自 `MessageStore.readRecent(this, 1000)`，活跃会话刷满后安静会话连复选框都不出现；④ 脱敏预览 `MessageStore.readRecent(this, 200).reversed()` 同理，用户看不到安静会话的规则效果、无法核对脱敏是否生效。
  修法：`AnalysisWindowCore` 新增 `ADVISOR_LINES = 20000`（顾问窗口量级，比 `TAIL_LINES = 5000` 更宽，因为要覆盖 7 天全部会话）与 `since(records, cutoffMs)`（按 `analyzedAt` 升序取区间内记录），文件级入口 `AnalysisWindowFile.since(cutoffMs, lines)` 与 `AnalysisStore.readSince(context, cutoffMs)`；`AdvisorWorker` 改 `AnalysisStore.readSince(applicationContext, since)`，删掉 `.filter{}.sortedBy{}`。统计段改 `AnalysisStore.readGrouped(this)` 用 `groups.keys.size` / `groups.count { it.second.any { r -> r.s1NeedAction } }`。白名单候选改 `MessageStore.feedGroups(this).keys()`。脱敏预览改 `FeedWindowCore.sample(MessageStore.feedGroups(this), PREVIEW_LIMIT = 200)`。
  为什么预览不是 `recent(result, limit)`：第一版写的「全表最近 200 条」被单测当场证伪 —— 600 条噪声下安静会话仍被挤出 200 条（`FeedWindowCoreTest > sample 在别的会话刷满行窗口时仍能看到安静会话` 先红后修）。`sample` 改成**按会话轮流取**（第 1 轮各会话各取最新一条，第 2 轮各取次新……），同一会话内按时间取尾部，最后整体按时间升序。
  测试：`AnalysisWindowCoreTest` **23 → 27 条**（`since` 四档：区间过滤 + 升序 / 空输入 / 文件级跨 500 行窗口的安静会话 + 旧写法对照 / 窗口量级不小于 `TAIL_LINES`），`FeedWindowCoreTest` **22 → 27 条**（`sample` 五档：空与非正条数 / 名额够时升序 / 名额不够时轮流取而非切尾 / 未标注日期归 0 排最前 / 600 条噪声下安静会话仍可见 + 旧写法对照）。全量单测 1045 → **1054 条**。
  计数：`AnalysisWindowCore.kt` 159 → **190 行**、`FeedWindowCore.kt` 157 → **191 行**；主源码顶层 115 / laya 7 / 全树 122 不变，`app/src/test` 69 个 `.kt` 不变。

  修法：新增 `AdvisorDigestCore.windowsOf(feed: FeedWindowCore.Result)`（= `windows(feed.buckets.flatMap { it.messages })`），两条通道都改成 `AdvisorDigestCore.windowsOf(MessageStore.feedGroups(context))`，`DEFAULT_MESSAGE_WINDOW = 500` 常量删除 —— 窗口因此与首页 / 悬浮面板 / 关注名单**同源同口径**（每会话各留一窗，`perConvFor` 按会话数摊薄 `[20, 200]`、`MAX_CONVERSATIONS = 200`）。多留几条只会让别名映射更准，且不引入新常量。`app/src/test/java/com/notifyme/android/AdvisorWindowSourceTest.kt` **8 条单测**：`windowsOf` 保全部会话 / 安静会话仍换别名 / **旧写法对照实验**（`readRecent(500)` 在 600 条噪声下确实拿不到该会话，`mergedNameMap` 同样缺）/ 空库空表 / 摘要正文口径不变 / 会话数逼近上限时每会话仍在窗口。

- **JSONL 逐行打补丁的共用内核（feat/jsonl-patch-core）**：`AnalysisRejectFile` 与 `AdvisorRejectFile` 的「逐行读 → 按 key 命中 → 打补丁 → 原子重写」是两份几乎逐行复制的实现（各约 90 行，只差 schema 与 newly 的容器类型），已收敛到 `JsonlPatchCore`（114 行，`internal object`，纯函数 + 15 条单测）。

  口径（五条）：① `target == null` 表示扫全文件（顾问通道 `reviveExpired` 复活过期隔离要用），非 null 时只碰命中 key 的行，同一 key 出现多行则**每行都打补丁**（与旧实现一致）；② **损坏行一律原位保留** —— 解析抛异常、返回 null、以及序列化时抛异常（`AnalysisCaseRecord` 缺 `s1` 时 `importance` 会变 NaN 让 `JSONObject.put` 抛错）三种情况都只保留原行、不影响其它行、也不把整轮判成失败；③ **只有真的改动了才重写文件**：`updated == record`（data class 结构相等）与「零命中」都直接返回 false 不碰文件，避免无谓的原子重写搅动 mtime；④ 空行原位保留、不参与解析也不计入 changed；⑤ `collect(file, keyOf, parse, include)` 只读不写、`LinkedHashSet` 按文件顺序去重（隔离集合的遍历顺序因此可复现）。

  为何不合并 `PendingRejectCore`：它是**丢弃语义**（达上限即 dropped，没有 quarantined / revive 概念），签名也不同（`Decision` 只有 `dropped`），强行合并会逼出一个恒 false 的 `include` 与无意义参数；三兄弟里只有 Analysis / Advisor 是「隔离 + 复活」同构。

  测试：`app/src/test/java/com/notifyme/android/JsonlPatchCoreTest.kt` **15 条**（用与业务无关的假记录 `data class Row(id, value, quarantined)` 与 `id|value|q` 文本格式，证明内核与 schema 无关）：命中改写只改命中行 / 同值不重写 / 零命中不重写 / 文件不存在返 false 且不造空文件 / 损坏行保留而命中行照改 / 空行保留 / 序列化失败保留原行 / 序列化部分失败时其它行仍改写 / target 为 null 扫全文件 / 全不满足不碰文件 / 同 id 多行逐行打补丁 / 解析抛异常按损坏行处理 / collect 只读且按序去重 / collect 文件不存在返空集 / 重写不留临时文件。

  行数与计数：`JsonlPatchCore.kt` 114 行；`AnalysisRejectCore.kt` 238 → **201 行**、`AdvisorRejectCore.kt` 213 → **183 行**（各减 37 / 30 行，只留 schema 适配与业务分支）；全量单测 1054 → **1069 条**、`app/src/test` 69 → **70 个 .kt**；`local_check.ps1` 六项全 PASS，lint 仍是刻意保留的三条 warning / 0 error。
- 真机测试成果与修复：llama KV cache（557f640）、强制分析双拦截（81cc283）、S1 模型名 jev-latest 勘误（31c60ee）；评测记录 docs/评测-分析判定-20261007.md。
- **M11.4 会话级定时分析**（b61ccf4）：已关注会话详情页可设独立分析周期（开关 + 15/30/60/120 分钟，WorkManager 每会话 unique periodic + quiet 静默标记——空结果不覆写全局状态；取消关注后 syncAll 清孤儿任务）。真机验证：liquidjoker 15 分钟排期持久化 + 立即首轮分析。升级链路真机闭环：Laya S1（need_action 0.76-0.98）→ GLM S2 深分析 → ⚡角标 + 悬浮卡弹出（真实数据截图验证）。

- **日期分组 key 与日期文案统一（feat/date-key-core）**：首页的天级分组、日期组头文案、控制台折叠作用域、以及 `MessageStore.dayKeyOf` 共用「yyyy-MM-dd」这个 key，但此前有**四处各写一套**实现：`MessageStore.dayKeyOf`（java.time）、`MainActivity.buildFeed` 里每次现造的 `dayKeyFormat` / `currentYear` / `labelSameYear` / `labelCrossYear` 四个 `SimpleDateFormat`、`ConsoleActivity.dayKeyFmt`、`CollapseStore.latestDayKeyOf`。这不是风格问题：`SimpleDateFormat` 的历法跟着 Locale 走 —— 同一毫秒（2026-11-12 15:30 +08:00）在 `th-TH`（佛历）下格式化成 `2569-11-12`、在 `ja-JP-u-ca-japanese`（和历）下是 `8-11-12`、在 `ar-EG` 下是本地数字（`????-??-??`），而 `java.time` 恒为公历 `2026-11-12`。设备系统 Locale 一旦不是公历，界面侧算出的 key 与 `MessageStore.dayKeyOf` **永远不相等**：左滑删某天删不掉（`deleteDate` 按 key 比对）、`CollapseStore` 的复合键对不上（折叠态全失效）、「最新一天」判定错、`dayKey.startsWith(currentYear)` 里 currentYear 是佛历年所以前缀永不匹配、所有天组都渲染成跨年格式。
  修法：新增纯函数 `DateKeyCore`（136 行，`internal object`，**26 条单测**）：`dayKeyOf(timestampMs, zone)` 只走 `Instant → atZone → toLocalDate`（恒公历 ISO）；`parts(dayKey)` 按位取数字再 `LocalDate.of` 回读校验（`2026-02-30` 返 null，不静默变成 3 月 2 日）；`labelOf(dayKey, nowMs, zone)` 用 `parts.year` 与当下的年份**数值比较**算 `crossYear`（彻底删掉 `startsWith` 前缀判定），返回 `DayLabel(year, month, day, crossYear, unknown)` 纯数值、文案留给 `strings.xml`（新增 `date_label_same_year` = `%1$d月%2$d日`、`date_label_cross_year` = `%1$d年%2$d月%3$d日`）；`latestOf(dayKeys, unknown)` 收掉 `CollapseStore` 的字典序取最大值。四处接线全部改为调用 Core：`MessageStore.dayKeyOf` 一行委托、`MainActivity` 删掉四个 `SimpleDateFormat` 与本地 `DAY_KEY_UNKNOWN` 常量（改用 `DateKeyCore.UNKNOWN`，`MessageStore.DAY_KEY_UNKNOWN` 也改成引用它，字面量只留一份）、`ConsoleActivity.collapseTargets` 改走 `DateKeyCore.dayKeyOf`、`CollapseStore.latestDayKeyOf` 转发 `DateKeyCore.latestOf`（保留旧签名，调用点不改）。
  测试：`app/src/test/java/com/notifyme/android/DateKeyCoreTest.kt` **26 条**，其中两条是**根因本身的回归**：切到 `th-TH` 断言 `SimpleDateFormat` 给出 `2569` 开头而 `DateKeyCore` 给 `2026-11-12`、切到和历断言前者不是 `2026` 开头；另有「佛历下同年分组不判跨年」（旧 `startsWith` 的错法）、`parts` 拒绝 `2026-02-30` / `2026-13-01` / `2026-04-31`、闰日 `2024-02-29` 通过而 `2026-02-29` 不通过、`latestOf` 必须排除 `__unknown__`（`_`=0x5F 大于数字，字典序会赢）、`MessageStore.dayKeyOf` 与 `CollapseStore.latestDayKeyOf` 委托一致性。
  计数：`DateKeyCore.kt` 136 行；主源码顶层 116 → **117**（全树 124）、`app/src/test` 70 → **71 个 .kt**、全量单测 1069 → **1095 条**；`local_check.ps1` 六项全 PASS，lint 仍是刻意保留的三条 warning / 0 error。

- **公历时间文本 ↔ 毫秒统一（feat/due-time-core）**：手动提醒弹窗、日期选择器、自定义分析范围三处的「文本 → 毫秒 → 文本」往返此前都吃**默认 Locale 的历法**，属于同一族 bug（`DateKeyCore` 那轮只修了日期分组 key）。四处旧实现：`ManualReminderCore.eventTimeFormat()`（`SimpleDateFormat(EVENT_TIME_PATTERN, Locale.getDefault())`）、`CalendarHelper.tryParseDueTime`（`Calendar.getInstance()` + `Calendar.set`）、`AnalysisScopeCore.dayStart` / `parseDay`（`Calendar.getInstance(zone)`）、`ConversationActivity.pickDateTime`（`Calendar.getInstance()` 造 + `Calendar.YEAR/MONTH/DAY_OF_MONTH` 读写）。同一毫秒（2026-11-12 15:30 +08:00）在 `th-TH`（佛历）下格式化成 `2569-11-12 18:10`、在 `ja-JP-u-ca-japanese`（和历）下是 `8-11-12 18:10`。后果三条：① 泰语设备上手动提醒弹窗默认填 `2569-11-12 18:00`，用户看到佛历年；② 用「选时间」改日期时 DatePicker 显示的也是佛历年，用户改成 2027 就落成公历 1484 年，保存时判「时间已经过去」或写进 1484 年的日历事件；③ 和历下只给一位年份，`\d{4}` 正则不命中，用户选的日期被整段**静默丢弃**，退回「今天/明天 18:00」。`AnalysisScopeCore.parseDay("2026-11-12")` 在同设备上也会得到公历 1483 年，自定义分析范围整段失效。
  修法：新增纯函数 `DueTimeCore`（213 行，`internal object`，**31 条单测**），只走 `java.time`（ISO 历法），不碰 `Calendar` / `SimpleDateFormat` 的默认历法：`formatter(pattern)` = `DateTimeFormatter.ofPattern(pattern, Locale.ROOT)`；`nextWholeHour` / `formatEventTime` / `defaultEventTimeText`；`parse(text, baseTime, zone)` 五个分支与旧 `tryParseDueTime` 同序（完整年月日 → `M月d日`（已过顺延明年，留 1 分钟容差）→ 今天/明天/后天 → 周X → 纯 `HH:mm`），时间缺省 9:00、下午/晚上/傍晚 +12、越界返 null，非法日期交给 `LocalDate.of` **抛异常返 null**（不静默顺延）；`dayStart` / `formatDay` / `parseDay`（严格回读，`2026-02-30` / `2026-13-01` / `2026-04-31` / 非闰年 2-29 一律 null）；`adjustPickedYear(pickedYear, shownYear, baseYear)` 把系统 DatePicker 回的历法年份按「改了多少年」平移回公历（公历设备平移量恒为 0，佛历 2569 ↔ 2026，和历 8 ↔ 2026，结果越出 `MIN_YEAR=1970..MAX_YEAR=2200` 回落基准年）。接线：`CalendarHelper.tryParseDueTime` 一行委托（`parseDueTime` 的 `+1h` 兜底语义**保持不变**，分析链路靠它）、`ManualReminderCore` 的 `eventTimeFormat()` 换成 `eventTimeText(ms)`、`AnalysisScopeCore` 三个函数保留 `TimeZone` 签名转发（`zone.toZoneId()`，设置页与既有 30 条单测调用点不改）、`ConversationActivity.pickDateTime` 改 `java.time` 造与时区换算（DatePicker 的年份仍由 widget 显示，只按平移量换算）。
  测试：`app/src/test/java/com/notifyme/android/DueTimeCoreTest.kt` **31 条**，其中 4 条是**根因本身的回归**（切 `th-TH` 断言 `SimpleDateFormat` 给出 `2569` 开头、`Calendar.getInstance().get(YEAR)` 是 2569，而 Core 给 `2026-11-12`；切和历断言旧写法只给 `8-` 开头、Core 仍 `2026`），另有五分支逐条对齐、`parse`/`formatDay` 往返一致、佛历下 `dayStart`/`parseDay` 仍是公历零点（对照 `Calendar.getInstance()` 落 1483）、`adjustPickedYear` 公历/佛历/和历/脏值四种、年份边界常量。`ManualReminderCoreTest` 的 `at()` / `fields()` 也一并改成 `java.time`（测试自己也不吃默认历法），并把 `eventTimeFormat().format(Date(ms))` 换成 `eventTimeText(ms)`。
  计数：`DueTimeCore.kt` 213 行；主源码顶层 117 → **118**（全树 125）、`app/src/test` 71 → **72 个 .kt**、全量单测 1095 → **1126 条**；`local_check.ps1` 六项全 PASS，lint 仍是刻意保留的三条 warning / 0 error。

- **数字 / 短时间戳文本口径统一（feat/locale-text-core）**：`DateKeyCore`（日期分组 key）与 `DueTimeCore`（年-月-日 ↔ 毫秒）之后，**同族的第三代**仍在：界面与模型输入里的数字形态照样吃默认 Locale。两类站点：
  ① **短时间戳 `SimpleDateFormat(_, Locale.getDefault())` 共 11 处**（`AdvisorWorker.timeLabel`、`AnalysisListActivity` 时间列、`AnalysisSettingsActivity` 报告时间与状态行、`CaptureActivity` / `CaptureDiagnosticsActivity` 抓包时间、`ConsoleActivity` 的今天/下次时刻、`ConversationActivity` 日期分隔行与最近分析时间、`HistorySync` 日志时间、`MainActivity` 消息卡时间、`ReminderReceiver` 通知文案、`ReminderListActivity` 事件时间与创建时间）—— 在 `ar-EG` / `ar-SA` / `fa-IR` / `bn-BD` / `ne-NP` 下渲染成阿拉伯-印度数字（实测 `١١-١٢ ١٨:١٠`），而 App 界面只有中文（`res/values` 无其他语言目录），中英混排里夹本地数字既不可读也无法复述。
  ② **`"%.2f".format(...)` 走默认 Locale 改小数点**：`AdvisorDigestCore` 的 `need_action=` / `importance=` / `预筛过滤(prob=)`（**这份摘要就是模型输入**，de-DE 下变成 `need_action=0,80`，逗号与行内分隔符混同、字段结构被破坏）、`AnalysisCase.S1.toInjectText()`（S2 题面的 S1 结论注入）、`S1Agreement` 报告 Markdown 表格（`0,80` / `٠٫٨٠` 复制进 docs 直接坏）、`LocalModelStore` / `CaptureDiagnosticsActivity` 的字节数、`AnalysisWorker` 的 fork 日志与 `escalateNote`（落库进 `analysis.jsonl` 的 `forks[].note`）。
  修法：新增纯函数 `LocaleTextCore`（93 行，`internal object`，**16 条单测**）——`minutes` / `seconds` / `hourMinute` 走 `DateTimeFormatter.ofPattern(pattern, Locale.ROOT)`（`zone` 缺省仍 `systemDefault`，`Locale.ROOT` 只管数字形态），`fixed(value, digits)` / `percent` / `bytes`（十进制，模型下载口径）/ `binaryBytes`（1024 进制，诊断页口径）一律 `String.format(Locale.ROOT, ...)`。刻意**不动**的三类：`"%02x".format(it)` hex 摘要（实测恒 `ab`，不受 Locale 影响）、`AnalysisCase` 里已钉 `Locale.US` / `Locale.CHINESE` 的题面时间（钉了就安全）、`getString(R.string.x, 数字)`（资源 `%1$.1f` 走 `Configuration` 的 Locale，属于另一件事，当前 UI 只有中文）。
  测试：`app/src/test/java/com/notifyme/android/LocaleTextCoreTest.kt` **16 条**，两条是**根因本身的回归**（`ar-EG` 下断言旧 `SimpleDateFormat` 输出里一个 ASCII 数字都没有、`de-DE` 下断言 `"%.2f".format(0.8)` 是 `0,80`），其余覆盖 8 种 Locale 的三种时间戳、`fixed` 的 0/负位数退化、`percent` 边界、`bytes` 与 `binaryBytes` 故意不同（同一 1 MiB 分别是 `1.0 MB` / `1.00 MB`）、`zone` 缺省取系统而显式传入才换区、顾问摘要字段里不出现逗号小数点。
  计数：`LocaleTextCore.kt` 93 行；主源码顶层 118 → **119**（全树 126）、`app/src/test` 72 → **73 个 .kt**、全量单测 1126 → **1142 条**；`local_check.ps1` 六项全 PASS，lint 仍是刻意保留的三条 warning / 0 error。

- **资源字符串的数字槽位统一走 `templateText`（feat/format-template）**：上一轮（`LocaleTextCore`）刻意留了一条豁免 —— 契约 ⑤ 写「别把口径推广到 `getString(R.string.x, 数字)`」。真机切 ar-EG（`cmd locale set-app-locales com.notifyme.android --locales ar-EG`）把这个豁免证伪了：`Resources.getString(id, args)` 内部是 `String.format(Configuration.locale)`，实测 `getString(R.string.msg_count, 200)` → `٢٠٠ 条`、`getString(R.string.chip_importance, 2.0)` → `重要度 ٢٫٠`、`getString(R.string.chip_need_action_no, 10)` → `✓ 无需行动 ١٠%`。界面里这些数字是权重 / 概率 / 条数 / 分钟阈值，用户要拿它们跟设置项对照，本地数字直接造成误读。
  修法（**不改资源模板，只换调用方式**）：`LocaleTextCore` 增 `format(template, args)` —— `%(\d+)\$(?:\.(\d+))?([dfs])` 逐槽位代入，`%%` 恒字面 `%`，数字走 `Locale.ROOT`；另加 `requiredArgs(template)` 供契约测试查槽位编号。模板里 `%1$d` / `%1$.1f` 的**精度意图留在资源里**（改成 `%s` 会丢「这里是整数 / 这里保留 1 位小数」的信息，且要同时改 55 条资源和 63 处调用点）。调用侧新增 `Context.templateText(id, vararg args)`（`ContextTemplateText.kt`，29 行）：先 `getString(id)` 取裸模板（不带参不会格式化），再交给 `format`。**63 处 / 13 个文件的数值 `getString` 全部换掉**（MainActivity 22、ConsoleActivity 11、ConversationActivity 10、AnalysisProgress 4、AnalysisList / AnalysisSettings / CaptureDiagnostics / PrivacySettings / ReminderList / AnalysisProgressNotifier / EngineSmoke / ModelList / Onboarding 各 1–3）。
  与 `String.format` 的三处刻意差别：① 固定 `Locale.ROOT`；② `%N$d` 吃任何 `Number`（`String.format` 对 `Double` 抛 `IllegalFormatConversionException`，`%1$.0f` 传 `Int` 同理）；③ 悬空 `%` / 未知占位 / 缺参一律降级（原样保留或空串），宁可在界面上少一个数，也不让一条文案抛异常把整屏吞掉。
  测试：`app/src/test/java/com/notifyme/android/FormatTemplateTest.kt` **16 条** —— ar-EG 下整数槽位恒 ASCII（并对照 `String.format` 确实出本地数字）、de-DE 下 `%1$.1f` 恒 `.`（并对照旧写法出 `2,0`）、精度由模板决定（`%.0f` / `%.2f` / 无精度按 6 位）、`%%` 转义、槽位按 N 取而非按参数顺序、同槽位可复用、无 `%` 模板同一实例原样返回、悬空 `%` 与未知占位原样、缺参 / `null` 参代入空串、`Double`/`Long` 进整数槽不抛、`requiredArgs` 取最大 N；另有 1 条**资源契约测试**直接读 `app/src/main/res/values/strings.xml`，断言扫到的 50+ 条数值模板全部是位置化 `%N$`（写成裸 `%d` 会被 `format` 原样漏出，用户看到字面 `%d`）。
  计数：`LocaleTextCore.kt` 93 → **161 行**、新增 `ContextTemplateText.kt` 29 行；主源码顶层 119 → **120**（全树 127）、`app/src/test` 73 → **74 个 .kt**、全量单测 1142 → **1157 条**；`local_check.ps1` 六项全 PASS，lint 仍是刻意保留的三条 warning（ObsoleteLintCustomCheck / OldTargetApi / ChromeOsAbiSupport）/ 0 error。

- **S2 摘要可用性评测（feat/s2-usability-eval）**：`docs/ROADMAP.md` M4 任务 6 的最后一块 —— S1 那轮（PR #15）量化了「判得准不准」，但真正给摘要 / 截止时间 / 建议动作 / 待办分派的是 S2，端侧能不能顶上来一直没数字。
  为什么要换一把尺子：摘要写得好不好没有唯一答案，抄「判对率」会退化成人工打分。改成**六项可机判的客观判据全过才算「可用」**（`delivered` / `no_placeholder` / `summary` / `due` / `action` / `tasks`），措辞好坏不进判据，留原始输出给人看。判据实现只有一份：`S2Usability.checks(...)`。
  `due` 判「与 S1 同桶」而不是「等于标注」—— 摘要层允许把「明天上午十点」写成 `2026-11-14 10:00`，桶对了算对，具体时刻交给 `CalendarHelper` 的提醒链解释；另加 `PAST_TOLERANCE_MS = 60s` 防「给出过去时间」。`summary` 判「非空、不等于窗口末条原文兜底、码点 ≤ 20」，兜底口径从 `parseS2Output` 里抽出成 `S2Usability.fallbackSummary(kase)`，两处共用一份（各写一份迟早漂开）。
  **S1 输入用人工标注，不用模型结论**：`labelAsS1` 把标注 `need_action` 转 prob 1.0/0.0、confidence 0.9。目的是把「S2 输出可不可用」和「S1 判得准不准」解耦，真实链路里 S1 误判让 S2 跟着歪是 S1 准确率的后果。
  实测（20 条合成样本，云端 Qwen3.8-Flash-Next-176B @ 8080 vs 端侧替身 qwen2.5-0.5b @ 8082，最新一轮）：**六项全过 云端 14/20 = 70.0% vs 端侧 3/20 = 15.0%**，中位时延 7.0s vs 0.8s；分项 due 同桶 75% vs **25%**、分派条目完整 100% vs **55%**、摘要 95% vs 95%、建议动作 100% vs 80%、非占位照抄两边都 100%。连跑三轮（已钉 `temperature=0` + `seed=20261113`）仍是 14/15/14 与 2/2/3，说明不可复现来自 llama-server / ROCm 推理本身而不是请求参数，所以文档只能写「区间 + 单轮数字」（云端 70~75%、端侧替身 10~15%）。端侧本轮全过的 3 条是妈妈 / 快递 / 银行，而「本来没有待办」的系统通知与优惠通知反而挂在 `due` 上（S1 判 none、它硬给了一个时间）；真正的失效模式是**只要要它给时间点就把当前时刻或瞎猜的时间填进去、`tasks` 整段丢掉**。云端的失分则集中在把 `today`/`this_week` 具体化成某一天、桶落错。
  记录：手写解说 [docs/评测-云端vs端侧S2.md](评测-云端vs端侧S2.md)（八节：为什么 / 六项判据口径 / 实测 / 六条限制（含「数字是区间」）/ 原始输出对比 / 复现 / 真机路径 / 文件表）+ 机器生成 [docs/eval-s2-cloud-vs-local.md](eval-s2-cloud-vs-local.md)。两条限制写死在文档里：端侧替身 0.5B 只能当**下限**（真机 S2 是 MiniCPM5-2B，本机没这个 gguf）、20 条样本单项差 1 条就是 5 个百分点。
  **两处新发现的坑**：① 机器生成报告的文件名必须纯 ASCII —— 第一次写 `../docs/评测-云端vs端侧S2.md`，Windows 下 `java.io.File` 用平台编码往返路径，落盘成 `è¯æµ-äºç¯vsç¯ä¾§S2.md`（`S2EvalHttpTest` 的默认 `OUT_S2` 已改成 `eval-s2-cloud-vs-local.md` 并加注释）；② `no_placeholder` 判的是「与骨架常量**完全相等**」，端侧输出「给用户的建议动作」对常量「给用户的建议动作，≤20字」不完全相等 → 判据放过。刻意不收紧（改 contains 会误伤正常输出里恰好出现的「责任人」），代价是对「截断式照抄」不敏感，留作后续决策。
  测试：`app/src/test/java/com/notifyme/android/S2UsabilityTest.kt` **34 条** —— `parseS2Output` 与 Core 兜底同一、六项逐条通过/失败（含 21 字越界与恰好 20 字、空 due 仅在 S1=none 时通过、due 早于 windowEnd、异桶挂且 note 带桶名、needAction 且 tasks 空要挂）、`score` 未交付不进分母、`bucketOf` 五桶与显式 zone（11-13 07:00 +08 在 UTC 下是 11-12 23:00）。`S2EvalHttpTest` 只断言「评测跑通」（每侧至少 1 条解析出且 `size*2 >= cases.size`），不断言答对多少 —— 它是测量工具，不是门禁。
  计数：新增 `S2Usability.kt` 349 行 + `S2UsabilityTest.kt` + `S2EvalHttpTest.kt`；主源码顶层 120 → **121**（全树 128）、`app/src/test` 74 → **76 个 .kt**、全量单测 1157 → **1192 条**。

- **采集诊断页的累计统计口径（feat/alltime-stats-core）**：诊断页 W4「各来源累计统计」那四个数字（累计消息 / 会话总数 / 自发消息 / 每 App 群聊数）此前走 `MessageStore.readRecent(this, ALLTIME_SCAN_LIMIT = 200_000)`，而 `readRecent` 的窗口**写死 `TAIL_LINES = 5_000` 行** —— 传 20 万进去也只覆盖尾部 5 千行，历史一长数字就静默偏小，且页面还自称「累计」；截断提示的判据是 `totalLines > s.total`，拿「文件原始行数」比「解析出的消息数」，损坏行一多就误报、而 20 万上限几乎永达不到。聚合本身是纯函数却住在 Activity 的私有类里，一行测试也没有。
  修法：新增纯函数内核 `AllTimeStatsCore`（124 行，`internal object`，**16 条单测**）—— `SCAN_LINES = 50_000`（比旧 20 万更贴近「真会读到」的量级，5 万行 ≈ 个人设备数年通知量）、`Accumulator` 单遍流式累加（不为统计多驻留数万个 `ChatMessage`）、`summarize(list)` 供测试与一次性聚合、`capped(totalLines, scannedLines)` **只比扫描行数**。口径：会话数按 `ConvKey`（pkg + 会话名）复合键去重、群聊数按「该 App 下 `isGroup` 的**会话**数」而不是消息数、`latest` 忽略 `timestamp <= 0`、来源按包名升序。
  接线：`MessageStoreCore` 新增 `scanAll(limit)`（窗口由调用方指定，逐行解析、损坏行跳过但**计入扫描行数**），`MessageStore.allTimeStats(context)` 是 `internal`（返回 `internal` 类型，public 会报 `public function exposes its internal return type`）；`CaptureDiagnosticsActivity` 删掉 `ALLTIME_SCAN_LIMIT` 与私有 `AllTimeSummary` / `PkgStat`，改渲染 `AllTimeStatsCore.Scan`，截断提示改用 `scan.scannedLines` 作分子。
  测试：`app/src/test/java/com/notifyme/android/AllTimeStatsCoreTest.kt` **16 条** —— summarize 8 条（含跨 App 同名会话是两条会话、群聊按会话计、`latest` 忽略无时间戳、空输入、包名升序）+ `Accumulator` 2 条（逐条喂与整批等价、空态）+ `capped` 3 条（含「损坏行不让覆盖判定误报截断」）+ `scanAll` 4 条。其中一条是**旧行为对照实验**：6000 行文件里本会话 10 条在文件头、其余 5990 行噪声会话 → 新 `scanAll` 得 6000 条 / 2 个会话，旧 `core.readRecent(200_000)` 只给 5000 条 / 1 个会话。
  计数：新增 `AllTimeStatsCore.kt` 124 行 + `AllTimeStatsCoreTest.kt`；主源码顶层 121 → **122**（全树 129）、`app/src/test` 76 → **77 个 .kt**、全量单测 1192 → **1208 条**；`local_check.ps1` 七步全 PASS，lint 仍是刻意保留的三条 warning / 0 error。
- **文本截断按码点切（feat/text-clip-core）**：`String.take(n)` / `substring(0, n)` 数的是 **UTF-16 code unit**，而 emoji 与 CJK 扩展 B 占两个 unit —— 截在中间就留下**孤立代理**，转 UTF-8 落成 `?`（U+FFFD 替换字符）。JDK 17 实测：`"123456789012345😀xy".take(16)` 的末位是高代理 `\uD83D`、UTF-8 末字节 `0x3F`；`clip(s, 16)` 得 16 个完整码点、UTF-8 合法。受影响的全是**出设备或落盘的文本**：① 顾问摘要与顾问请求体（`AdvisorDigestCore` 的会话名 20 / 摘要 30，`AnalysisWorker` 的窗口摘要 300 / 模型回包 400 / 错误信息 200）—— 模型输入被残字打断；② `QueueStateCore` 上行标题 256 / 详情 512 —— 服务端与控制台照原样存显；③ 日历事件标题（12）、诊断与抓包文案（120 / 160）、通知标题（20）、消息卡预览（15）、a11y 文案（60）—— 用户直接看到。
  修法：新增纯函数内核 `TextClipCore`（65 行，`internal object`，**20 条单测**）—— `codePoints(text)`、`clip(text, maxCodePoints, ellipsis = "")`、`clipWithEllipsis(text, maxCodePoints)`、`ELLIPSIS = "…"`。四条契约：① `clip` 是唯一入口，未超限时**返回同一实例**（`assertSame` 守住，避免无谓拷贝）；② 省略号**追加不占预算**（`clipWithEllipsis(s, 20)` 最多 21 码点，与旧 `take(20) + "…"` 逐字一致，`MessageDedupeCore` 的 40 字预览因此完全不变）；③ ASCII 输入输出**逐字节不变**（`take(n)` 与 `clip(s, n)` 在 n=0..28 上全等，历史文案与既有断言都不用改）；④ 已知边界是「只保证不切断代理对，**不保证**不切断字素簇」—— 国旗 / ZWJ 家族可能被切在两个码点之间（末位是 ZWJ），宁可少画一个 emoji 也不产出非法字符，这条写进类注释与单测注释。
  接线：32 处站点 / 15 个文件（`AdvisorDigestCore` 2、`AnalysisWorker` 11、`AdvisorWorker` 1、`AnalysisSettingsActivity` 1、`CaptureActivity` 1、`CalendarHelper` 1、`AnalysisProgressNotifier` 1、`MainActivity` 1、`A11yExtractService` 1、`AnalysisParsing` 1、`ManualReminderCore` 1、`QueueStateCore` 2、`S2Usability` 6、`S1Eval` 1、`MessageDedupeCore` 1）。刻意不套用：`AnalysisCase` 的 `"%02x".format(...).take(16)`（hex 恒 ASCII）、各处列表 `take(n)`（不是文本截断）。
  测试：`app/src/test/java/com/notifyme/android/TextClipCoreTest.kt` **20 条** —— 两条根因回归（`oldTakeLeavesLoneHighSurrogate` 断言 `take(16)` 末位是高位代理且 UTF-8 末字节 `0x3F`；`clipNeverLeavesLoneSurrogate` 对 n=1..18 逐码点扫，UTF-8 往返无损、不含 U+FFFD）、ASCII 逐字节等价、扩展 B（U+20000）、ZWJ 家族切点、`assertSame` 同实例、省略号不占预算、`ELLIPSIS` 单码点且 `codePointAt(0)==0x2026`。
  计数：新增 `TextClipCore.kt` 65 行 + `TextClipCoreTest.kt` 182 行；主源码顶层 122 → **123**（全树 130）、`app/src/test` 77 → **78 个 .kt**、全量单测 1208 → **1228 条**；`local_check.ps1` 七步全 PASS，lint 仍是刻意保留的三条 warning / 0 error。
- **SharedPreferences 的 JSON 读写口径统一（feat/prefs-json-core）**：五处 Store（`WatchlistStore` 会话名单 / `AppSourceStore` 白名单·停用·已观察 / `ConvTagStore` 标签映射 / `ConvAnalysisScheduleStore` 每会话周期 / `AnalysisScopeCore.decodeConvIds`）都把结构化数据塞进一个 JSON 字符串，读的时候各抄一份 `try { ... } catch (e: Exception) { }`，「损坏按空返回」只写在注释里、零单测。现在抽成纯函数 `PrefsJsonCore`（135 行，29 条单测）：读永不抛且返回独立拷贝 / 只做形状裁剪不做领域过滤（语义判断留 Store 层）/ 元素走 `optString` / 写不排序（`sort = true` 才显式排序）/ 与 `MessageStore` 的 JSONL 无关；`objects(raw, project)` 让单条坏数据只丢自己。其中 3 条单测是与旧内联实现逐字对照的实验（同一批脏样本断言结果完全一致）。
- **三个模型通道的 `chat/completions` 请求体与状态行着色统一（feat/chat-request-core）**：`AnalysisWorker` / `AdvisorWorker` / `S1EvalRunner` 三处各有一份 ~20 行同构的请求体构造，URL 归一与 `max_tokens` 逐字相同但注释里的实测值已分叉（1024 / 1500 / 4096）、异常类型也不一致；`AnalysisSettingsActivity` 与 `CaptureActivity` 还各有一份逐字相同的 `tintStatus`（11 行、9 个调用点）。现在抽成两个纯函数内核：`ChatRequestCore`（61 行 / 16 条，端点归一只有 trim→去尾斜杠→补后缀一个方向、不猜 provider 前缀、`max_tokens` 单一默认 4096、请求体键集合是契约、HTTP 与异常分类刻意留在各通道）与 `StatusTintCore`（31 行 / 16 条，失败/鉴权先判再判成功/完成、判定面只有四个词、`已中断…完成` 保持绿是既有行为、只回资源 id）。`AdvisorWorker.parseReport` 的手写 JSON 提取同时改为委托 `AnalysisParsing.extractJsonPayload`。
- **Intent extras → 会话键 与 运行时权限判定统一（feat/conv-extras-permission-core）**：六处「从 Intent 取 pkg / conversation」的 `takeIf { isNotBlank() } ?: 微信` 兜底口径不一致（有的只判 `== null`），会话名为空串时还会构造 `ConvKey("", pkg)` —— 永远匹配不到任何消息，详情页打开即静默空窗。现在抽成 `ConvExtrasCore`（43 行 / 18 条：包名空白回落微信且不 trim、会话名空白返 null）。运行时权限三族同构代码也收口：POST_NOTIFICATIONS 的「sdkInt + 授权结果」判定三处（有一处漏了版本判定），日历读写判定与三处 `requestPermissions` 数组、两处 `grantResults` 判定，统一到 `PermissionGateCore`（78 行 / 15 条，空结果 false、权限数组每次新实例）。压舱细节：权限名必须用 `PERM_POST_NOTIFICATIONS` 字面量，写 `Manifest.permission.POST_NOTIFICATIONS` 会触发 lint `InlinedApi`、基线从 3 条漂到 4 条。

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
