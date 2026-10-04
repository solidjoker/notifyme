# notifyme 路线图与执行计划

> 制定日期：2026-10-04　依据：`progress.md` + 代码实测核对（所有结论均带 `文件:行` 证据）
> 本文是 README「下一步计划」8 项的**可执行展开**：拆成 M0–M8 里程碑，含任务清单、验收口径、工作量与风险。
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

**推荐执行顺序**：`M0 → M1 → M2 → M3 → M4`，M5/M6 视决策插入，M7/M8 收尾。
理由：M0 修好公开路径并给出 CI（之后所有重构才有护栏）；M1 必须在 M2 之前——schema 迁移直接动用户已有 `messages.jsonl`，出错不可逆；
M2 是产品级最大解锁且是 M7/M8 的前置；M4 风险最高，刻意排在有测试网之后。

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
- [ ] 真机 `adb install -r` 装签名包成功（非 `INSTALL_PARSE_FAILED_NO_CERTIFICATES`）— 待正式密钥库就绪后一并验
- [ ] tag `v0.2.0` 后 GitHub Actions 自动出 Release，页面可下载 `notifyme-open.apk` — 阻塞在 4 个 Secrets 无法用当前 PAT 写入
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

## 3. M1 测试与质量地基

**问题**：9.5k 行零测试，而 M2 要动用户数据 schema。先把纯逻辑层罩住。

任务清单：
1. `app/build.gradle.kts` 加 `testImplementation(junit:junit:4.13.2)` + `org.json:json`（JVM 上平台无 org.json）+ `kotlinx-coroutines-test`；接入 CI（M0 已留位）
2. 新建 `app/src/test/java/com/notifyme/android/`，优先覆盖**纯逻辑、无 Android 依赖**的部分：
   - `MessageStore`：append/merge 去重、`deleteConversation`/`deleteDate`/`deleteMessages`、损坏行原样保留、`DAY_KEY_UNKNOWN` 兜底
   - `PendingQueue`：`removeByConversations`/`removeByConversationDay`/`removeByMessages`
   - `ForkPrefilter`：预筛判定分支（就地结案 `filtered=true`、`protocol="prefilter"`）
   - `AnalysisCase`：`parseS1Result`/`parseS2Output` 的防御式解析（字段缺失、`{"answer":{...}}` 多包一层、类型不符）
   - `AnalysisWorker` 的 S1→S2 升级阈值判定（need_action≥0.5 / importance≥6 / 置信度<0.5）
   - `CalendarHelper` 三级降级链的**决策逻辑**（哪级可用走哪级）——把决策从 Android API 调用里抽成可测纯函数
3. 存储层抽出 `FileStore` 共用基类（读行/原子重写/损坏行保留），四个 store（messages/analysis/reminders/pending）复用 → 为 M2 迁移留一个统一入口
4. 可选：detekt/ktlint 基线（只报警不阻断），`lint.xml` 收口

验收：`.\gradlew.bat testOpenDebugUnitTest` 全绿且进 CI；存储层测试覆盖上表全部删除路径；重构后 `assembleOpenDebug assembleBetaDebug` 仍 exit 0。

---

## 4. M2 跨应用通知管理（README 路线图 #2）

**目标**：从微信专用 → 任意 App 通知统一管理；微信只是第一个「应用源」。

任务清单：
1. **schema v2**：`ChatMessage` 增 `pkg: String`（默认 `com.tencent.mm`）+ 可选 `appLabel`；`toJson`/`fromJson` 向后兼容（缺字段读默认值）
   - 迁移策略：**读时补齐**，不做一次性重写文件（避免大文件迁移中断损坏）；提供「设置 → 数据 → 规范化」手动触发全量重写
   - 服务端 `_validate_item`（`server/app.py:257`）接受可选 `pkg`，缺省按 `com.tencent.mm` 入库；`/messages` 支持 `pkg` 过滤；PWA 控制台加应用列与筛选
2. **应用源注册表** `AppSourceRegistry`：`AppSource(pkg, label, parser, a11yConfig?)`
   - `WeChatSource`：现有 MessagingStyle 解析 + 群聊/私聊判定 + a11y 控件 id 表（把 `WeChatA11yExtractService.kt:223-227` 的 5 个 id 移进配置）
   - `GenericSource`：兜底解析 `Notification.extras`（title/text/bigText/subText/MessagingStyle），任何 App 都能入库
3. **监听器改造**：`WeChatNotificationListener` → `NotifyMeListener`，去掉 `PKG_WECHAT` 单包判定（`:32,160`），改为「已启用应用源集合」过滤；保留每源独立的会话名/发送者提取
4. **a11y 直读**：仅微信启用（其余 App 无稳定控件 id），注册表里 `a11yConfig == null` 即跳过；`ConsoleActivity.kt:533` 的拉起 Intent 改为按当前会话 `pkg` 取 launch intent
5. **UI**：设置页新增「通知来源」多选（列出已安装且有通知的 App）；首页加应用筛选 chips；关注列表 `WatchlistStore` 的 key 从 `conversation` 升级为 `(pkg, conversation)`（含兼容读）
6. **数据一致性**：`AnalysisStore`/`ReminderStore`/`PendingQueue` 的会话键同步升级为复合键，删除路径（M1 已测）跟着改

验收：
- 微信链路回归无损（现有五项删除功能 + 分析 + 提醒全部照旧）
- 至少 2 个非微信 App（如短信/Telegram/邮件）通知能入库、分组、进分析
- 老 `messages.jsonl`（无 `pkg`）升级后仍可读、可删、可同步，不丢行
- M1 单测扩充到复合键路径并全绿

---

## 5. M3 隐私信息保护（README 路线图 #4）

**目标**：数据出设备之前，用户能选择脱敏，并能看见脱敏了什么。

任务清单：
1. `Redactor`：规则化端侧脱敏（手机号、身份证、银行卡、邮箱、URL、金额、地址、姓名/昵称映射表）；纯函数、可单测
2. 三档策略：**关闭** / **云端脱敏**（发往 openai、laya/JEV 槽位前脱敏）/ **仅端侧**（未启用本地引擎时禁用云端分析）
3. 单点插入：`AnalysisWorker` 组装 payload 前调用（覆盖 `AnalysisCase.kt:56/226/268` 三个出口）；`SyncWorker` 上报服务端同样受策略约束
4. 会话级白名单：某些会话不脱敏（例如自己的工作群），存 `PromptStore` 同级配置
5. **脱敏预览页**：分析前展示「原文 → 将发送文本」对照，用户可临时改档
6. `PRIVACY.md` 增补脱敏章节与规则清单；分析结果里记录 `redacted: true` 与命中规则数（不记原文）

验收：单测覆盖每类规则（含中英混排、带空格/连字符的号码）；开启「仅端侧」时抓包确认无任何云端请求；预览页对照准确。

---

## 6. M4 端侧推理引擎（README 路线图 #6，高风险）

**目标**：`LocalLlmEngines.forModel` 返回真引擎，S1 快筛全离线可用。上层（`AnalysisWorker.runS1Local`/`runS2Local`）已按接口写好，**零改动**。

决策门 D3（先拍板再动工）：
| 路线 | 优点 | 代价 |
|---|---|---|
| **A. llama.cpp 第三方 AAR**（`dev.ffmpegkit-maintained:llama-android:0.1.1`） | 落地最快，纯 Gradle 依赖 | 仅 `arm64-v8a`、无流式；**x86_64 模拟器（MuMu）跑不了**，只能真机验证；S1 是 MNN 格式，需换 GGUF 模型 |
| **B. MNN-LLM NDK 自编译** | `arm64-v8a` + `x86_64` 都能出 so，可在模拟器验证；与已下载的 S1 MNN 模型格式对齐 | 需 NDK 工具链与 `build_64.sh` 编译经验，JNI 封装工作量大 |

推荐：**先 A 做 spike 验证端到端链路与时延/内存，再按需要转 B**（S2 的 4B GGUF 本来就走 llama.cpp 路线）。

任务清单：
1. Spike（1 天）：加载 S1 模型 → 一次 `chat(system,user,maxTokens)` → 记录首 token/总时延、峰值 RSS、发热；真机（8 GB+）与模拟器分别记录
2. 实现 `LlamaEngine`（或 `MnnLlmEngine`）：加载/卸载生命周期、超时与 OOM → 抛 `LocalEngineException`（上层已有降级文案）
3. 准入判定：`ActivityManager.memoryInfo` + `Build.SUPPORTED_ABIS` → S2（2.47 GB）仅在 8 GB+ RAM 且 arm64 时开放，否则 UI 明确禁用并给理由
4. `LocalLlmEngines.releaseAll()` 接 `onTrimMemory`/`onLowMemory`；模型删除时释放对应 native 句柄
5. `ModelDownloadWorker`/`ModelListActivity` 补：断点续传校验（`LocalModelStore.kt:116` 已按 size 校验）、下载失败重试文案
6. 分析质量对比：同一批会话，云端 vs 端侧 S1 判定一致率、S2 摘要可用性，写进 `docs/` 评测记录

验收：飞行模式下 S1 分析全流程走通并出 ⚡ 标记；引擎不可用时降级文案正确、不崩；评测记录有真实数字。

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

## 9. 决策门汇总

D1–D4 已在 2026-10-04 拍板（下表「结论」列即最终决定，不再重开）：

| ID | 决策 | 选项 | 结论 |
|---|---|---|---|
| **D1** | M0 签名与发布方式 | (a) CI + GitHub Secrets 存 keystore 自动签名发 Release　(b) 本机签名手工上传　(c) 先发 debug 签名并注明 | ✅ **(a)** — `release.yml` 已就绪，只差 Secrets 写入权限 |
| **D2** | 版本号 | (a) `0.2.0/5`　(b) `0.1.4/5` | ✅ **(a)** `0.2.0/5` — 已落到 `app/build.gradle.kts:64-65` |
| **D3** | M4 引擎路线 | (A) llama.cpp AAR（arm64，快）　(B) MNN NDK 自编译（含 x86_64，慢） | ✅ **先 A spike，再按需转 B** — 上层 `AnalysisWorker.runS1Local/runS2Local` 已按 `LocalLlmEngine` 接口写好，换引擎上层零改动 |
| **D4** | iOS 定位 | (A) 原生 SwiftUI 查看端 + APNs　(B) 强化 PWA　(C) 暂缓 | ✅ **文案已改述为「iOS 原生查看端 + 提醒推送」**（README + M5），实现排在 M0–M3 之后，届时按实际使用需求决定 A/B/C |

### M0 尚待用户拍板的两件事

| ID | 待决 | 说明 |
|---|---|---|
| **D5** | 正式签名密钥库怎么生成与保管 | 当前 PAT 无 Actions Secrets 写权限（`gh secret list` → HTTP 403）。要么用户在 GitHub 网页上手工加 4 个 Secrets（`NOTIFYME_KEYSTORE_BASE64` / `_KEYSTORE_PASSWORD` / `_KEY_ALIAS` / `_KEY_PASSWORD`），要么换一个有 administration 权限的 token。**密钥库一旦确定就不能再换**：签名变了老用户无法原地升级，卸载会清空设备上的 JSONL 数据 |
| **D6** | 是否重写 git 历史 | 见 §2「M0 遗留风险」。不含凭证，默认接受现状；重写需 `git filter-repo` + force push，属破坏性操作，须显式授权 |

---

## 10. 非目标（明确不做）

- 不做云端多用户 / 账号体系（服务端始终是单用户自托管）
- 不引入 Room / Compose / DI 框架做大重构（`MessageStore.kt:57-66` 的取舍仍然成立，除非 M6 评测推翻）
- 不分发逆向工具链与任何来源不明/第三方版权代码（§六 硬约束）
- open flavor 永不预置密钥、服务器地址、模型直链之外的任何私有配置
- 不在未获明确授权时推送远程或发布 Release
