# 架构与功能图集

> 本目录是对 notifyme Android 端（`app/src/main/java/com/notifyme/android/`）**现有代码**的逆向梳理成果，不是设计提案。
> 所有节点、类名、方法名、常量、行号、路径均来自实际源码与配置文件。
>
> - 绘制方式：Mermaid 源文件（`diagrams/*.mmd`）→ `@mermaid-js/mermaid-cli@11.17.0` 光栅化
> - 每张图三份产物：`.mmd`（可编辑源）· `.png`（原生分辨率，14px 中文字体）· `.svg`（无限缩放，浏览器打开）
> - 中文字体：`Microsoft YaHei, PingFang SC, sans-serif`（Windows 由 `msyh.ttc` 提供）
> - 代码基线：**9 张图全部已重画到当前代码基线**（`versionName 0.3.19` / `versionCode 25` · Kotlin `2.2.21` · AGP `8.5.2` · Gradle `8.9` · compileSdk/targetSdk `34` · minSdk `26` · JDK `17`）——Android 端 `app/src/main/java/com/notifyme/android/` **130 个顶层 .kt**（另含 `laya/` 子包 7 个，全树 137 个，`app/src/test` 85 个 / 1369 条），服务端已拆成 **8+1 模块**（`app.py` 627 / `util.py` 384 / `storage.py` 821 / `routes.py` 1030 / `console_ui.py` 3689 / `stats_api.py` 201 / `suggests.py` 301 / `extract_common.py` 474 + `console_classify.js` 602），CI `.github/workflows/android.yml` 共 **14 步**

---

## 图集索引

| # | 图 | 回答的问题 | 尺寸 (px) |
|---|---|---|---|
| 01 | [系统上下文](#01-系统上下文) | 这个系统和谁说话？数据往哪流？ | 3962 × 1724 |
| 02 | [Android 分层架构](#02-android-分层架构) | 130 个顶层 Kotlin 文件各自属于哪一层？ | 15150 × 2070 |
| 03 | [三条采集通道](#03-三条采集通道) | 消息是怎么进来的？为什么需要三条路？ | 3386 × 1048 |
| 04 | [分析决策流水线](#04-分析决策流水线) | 一条消息如何变成一条待办？ | 2510 × 5356 |
| 05 | [三级降级提醒链](#05-三级降级提醒链) | 提醒为什么不会丢？ | 1973 × 3290 |
| 06 | [功能全景脑图](#06-功能全景脑图) | 这个 App 到底有哪些功能？ | 3135 × 1556 |
| 07 | [服务端架构](#07-服务端架构) | 服务端 8+1 模块里各装了什么？路由在哪一行？ | 6960 × 4346 |
| 08 | [构建与部署](#08-构建与部署) | 从源码到手机、从手机到公网的完整链路 | 6850 × 4895 |
| 09 | [同步时序](#09-同步时序) | 冷启动回填与三通道上报的精确行为 | 5769 × 7926 |

> 尺寸为本次重渲染后 `.png` 的**实际像素**（`System.Drawing.Image.Width × Height`），与 `diagrams/*.png` 一一对应。
>
> 重渲命令（mermaid-cli 11.17.0）：`mmdc -i diagrams/X.mmd -o diagrams/X.svg`；`mmdc -i diagrams/X.mmd -o diagrams/X.png -w <SVG viewBox 宽 + 20> -H <SVG viewBox 高 + 60>`（脑图 06 的 viewBox 起点不是 0，单独用 `-w 3200 -H 1600`）。改完 `.mmd` 必须重渲并回写本表尺寸。

---

## 01 系统上下文

四个信任域 + 两个外部服务。核心事实：**Android 端只负责"看见"和"上报"，所有重活（数据库解密直读）都在 PC 宿主机执行**，因为模拟器才有 MuMu root 通道。

![系统上下文图](diagrams/01-system-context.png)

- 📱 **Android 设备 / 真机（HyperOS）** — `NotifyMeListener`（通知监听，零注入，按 AppSourceStore 启用集合过滤）+ `A11yExtractService`（无障碍直读，免 root，目前仅微信）+ App 主体 + 私有目录存储
- 💻 **PC 本机 Windows** — `server/app.py` Flask `0.0.0.0:8000`、`android_db_extract.py`、`pc_db_extract.py`、`messages.db`、frpc 隧道（localPort 8000 → remotePort 23444）
- ☁️ **VPS 公网入口** — nginx `:443` `location /mmonitor/` → frps `:23444`（allowPorts 白名单）
- 🧠 **外部模型** — laya/JEV `POST /v1/systemone`、OpenAI 兼容通道 `POST /chat/completions`、ModelScope CDN（MiniCPM4-0.5B-MNN 311MB / MiniCPM3-4B-GGUF 2.47GB）
- 📱 **iPhone Safari** — PWA 纯查看端。iOS 没有 NotificationListenerService 等价物，不能采集，只能看

七条编号链路（① 系统通知官方回调 → ⑦ 模型文件下载）在图中逐条标注。

---

## 02 Android 分层架构

五层 + Application 入口。**刻意不引 Compose**：全部传统 View + RecyclerView，运行时依赖只有 5 个（core-ktx / recyclerview / work-runtime-ktx / okhttp / **litert-api**——`com.google.ai.edge.litert:litert:2.2.0`，端侧 LiteRT 推理用），把首次构建失败风险降到最低。JSON 用平台内置 `org.json`，不引 Gson/Moshi。

源码规模：`app/src/main/java/com/notifyme/android/` **顶层 130 个 .kt** + `laya/` 子包 7 个（LiteRT 移植）= 全树 137 个；`app/src/test` 85 个 .kt / 1369 条（纯 JVM 单测）。

![Android 分层架构图](diagrams/02-android-layers.png)

| 层 | 内容 |
|---|---|
| ① UI 层 | **18 个 Activity**：Main（首页）· Console（控制台 8 卡）· Conversation（会话详情+分析面板）· AnalysisList · **AnalysisSettings（S1/S2 槽位）** · Watchlist · Sources（通知来源）· ReminderList · **ReminderSettings** · PromptEdit · ModelList · Onboarding · **Capture** · **CaptureDiagnostics** · **EngineSmoke** · **OverlaySettings** · **PrivacySettings** · **RedactorPreview** |
| ② 采集与常驻层 | `NotifyMeListener`（通知监听）· `A11yExtractService`（无障碍直读，仅微信）· `KeepAliveService`（60s 看门狗 requestRebind）· `BootReceiver` · `ReminderReceiver` · **`OverlayService`**（悬浮窗）· **`OverlayActionReceiver`** |
| ③ 后台任务层 | 4 个 Worker：Sync（三通道上报）· Analysis（逐会话窗口 case）· Advisor（近 7 天复盘）· ModelDownload（.part 断点续传）；3 个 Scheduler 全部 `enqueueUniquePeriodicWork` + UPDATE 策略 |
| ④ 领域 / 决策层 | 既有内核：`AnalysisCase` · `ForkPrefilter` · `CalendarHelper` · `AlarmHelper` · `MessageReplier` · `HistorySync` · `LocalLlmEngine` / `LayaTfliteEngine`；**本轮新增 49 个 Core 逻辑内核**（见下表） |
| ⑤ 存储层 | 5 个 JSONL + `files/models/` 目录 + **22 个** SharedPreferences 文件（见下方存储清单） |

**④ 层本轮新增的 49 个 Core 逻辑内核**（纯 Kotlin、无 Android 依赖，因此能进纯 JVM 单测；图中按主题分组，括号内为文件行数）

| 主题 | 类 | 职责 |
|---|---|---|
| 同步与上报 | `SyncOutcome`（192）· `SyncBatchCore`（84）· `SyncWatermarkCore`（57）· `AnalysisSyncCore`（60）· `AdvisorSyncCore`（44）· `HistorySyncCore`（62）· `QueueStateCore`（156） | 上报结果分类（207 先于 2xx 判定）· 分批与二分 · **已结算前缀水位** · 各通道分批 · 历史同步隐私门控与翻页判定 |
| 拒绝与隔离 | `AnalysisRejectCore`（201）· `AdvisorRejectCore`（183）· `PendingRejectCore`（66） | 被拒次数累计 + 超限隔离，顾问通道复用分析通道实现；两份文件级实现（逐行打补丁 + 原子重写）已收敛到 `JsonlPatchCore` |
| JSONL 补丁 | `JsonlPatchCore`（114） | 5 个 JSONL 存储共用的「逐行读 → 按 key 命中 → 打补丁 → 原子重写」内核：`patch(file, target, keyOf, parse, encode, patch)` 的 target 为 null 时扫全文件（顾问通道 `reviveExpired` 用），空行 / 解析失败 / 序列化失败的损坏行一律**原位保留**，`updated == record` 与「零命中」都不重写文件；`collect(...)` 只读、按文件顺序去重。`AnalysisRejectCore` 238→201 行、`AdvisorRejectCore` 213→183 行，schema 适配各留各的 |
| 扫描与调度 | `AnalysisScanCursor`（265）· `AnalysisScheduleSummaryCore`（71） | 持久化字节游标 + 环形重扫 · 调度健康（Health）自愈阈值 |
| 脱敏与标签 | `RedactorCore`（293）· `AliasSaltCore`（143）· `ConvTagCore`（47） | 身份证/手机号/姓名等规则脱敏 · 姓名别名的**设备随机盐**（sha256(盐 ‖ 0x00 ‖ 名字) 取前 6 位，盐存 privacy_config/alias_salt，可重置；盐为空退化为 M3 初版的无盐 sha256）· 会话标签 NONE/TODO/TRACK/DONE/NOISE |
| 顾问通道 | `AdvisorDigestCore`（196） | 顾问复盘摘要 + 报告上报的输入口径：从**每会话各留一窗**（`windowsOf(FeedWindowCore.Result)`，与首页/悬浮面板/关注名单同源）反推每个会话的 `(conversation, isGroup, senders)` 三元组，补上分析记录里没有的窗口发送者名单，让 `NameAliases.nameMapFor` 的姓名别名在两条顾问通道上都生效（窗口查不到退回七类规则）；同时钉死摘要正文（≤50 个 case / 摘要截 30 字 / 会话名截 20 字） |
| 解析与口径 | `NotificationTextCore`（124）· `ConvNormalize`（82） | 未读前缀剥离（`[11条]`）+ 发言人切分（全角/半角冒号）+ 群聊判定（前缀是否等于/包含于会话名）· 会话名规范化（评测与展示共用） |
| 版本与元信息 | `VersionInfoCore`（95） | 版本行必须带 versionCode（覆盖安装时 versionName 可能不变）+ 渠道/构建文案 + 空值条目不渲染 + 复制文本（报障时直接贴进 issue） |
| 评测预算 | `S1EvalBudgetCore`（83） | 单次调用上限（默认 90s）+ 整轮预算（默认 15min），到期不再发起调用并把剩余样本写进报告 |
| 去重与清理 | `MessageDedupeCore`（174） | 历史重复归并：同 App + 同会话 + 同发送者 + 剥掉未读前缀后同正文，且组内至少一行带未读计数才归并；保留每组最早一条；裸正文相同文本、损坏行、空正文一律不动 |
| 筛选与视图 | `AppFilterCore`（88） | 首页「按来源 App」筛选口径：chip 只列窗口内真出现过的来源（不给点了必然空白的）、顺序对齐 PWA（全部 → 微信 → 其余按展示名码点）、选中项消失自动回落「全部」并在 `applyFeed` 落盘同步 |
| 分析范围 | `AnalysisScopeCore`（286） | 分析范围筛选口径：周期（不限/今天/近 7 天/近 1 月/自定义）由纯函数算区间，「近 N 天」含今天、自定义结束日取 23:59:59.999、落盘只存选择不存毫秒；点名会话走 `forced()` 跳名单与标签门禁、`skipDedup()` 只在手动轮跳 caseId 去重；会话 id 用 JSONArray 序列化（会话名含 `|` 与逗号，逗号拼接会被切坏）；空结果文案区分「被范围筛空」与「真的没活」 |
| 搜索与视图 | `SearchCore`（137） | 首页搜索命中口径：会话名 ∪ 来源 App 名 ∪ 消息 ∪ 该会话全部历史分析结论（`caseParts` 是字段清单唯一出处，码值先翻中文标签再匹配、`none` 码值排除）；搜索态下会话级与日期级折叠一律走 `forcedExpand` 强制展开；`hitKind` 只回码值供会话头提示，文案与本地化留在 Activity |
| 文案与本地化 | `TopicLabelCore`（229） | topic / due_window 码值↔中文档名的唯一归一处（四处重复实现已全部删除）：`normalizeTopic` / `normalizeDue` 双向归一 + `topicRes` / `dueRes` 回资源 id，空值回落 notice / none、未知值原样显示；`displayDueCode` / `hasDisplayDue` / `displayDueLabel` / `editDueLabel` 统一「该显示哪一份截止」的优先序（todoDue → s2DueTime → s1DueWindow），修掉首页「截止：none」码值泄漏；`filtered` 单独映射为「已预筛过滤」 |
| 日期与分组 | `DateKeyCore`（136） | 日期分组 key 与日期组头文案的唯一口径（此前四处各写一套，其中三处用 `SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())`）：`dayKeyOf(timestampMs, zone)` 只走 `java.time`（恒公历 ISO），`parts()` 按位取数字 + `LocalDate.of` 回读校验（`2026-02-30` 返 null），`labelOf()` 用年份**数值比较**算 `crossYear`（不再 `dayKey.startsWith(currentYear)`，那个写法在佛历 / 和历 Locale 下先错在 currentYear 本身），返回纯数值 `DayLabel` 供 `strings.xml` 渲染，`latestOf()` 收掉折叠态的字典序取最大值；同为 2026-11-12 15:30，`SimpleDateFormat` 在 th-TH 下给出 2569、在和历下给出 8，而 Core 恒 2026 —— 两处混用会让删天、折叠、跨年标签全部失效 |
| 时间文本 | `DueTimeCore`（222） | 公历时间文本 ↔ 毫秒的唯一口径（`java.time`，恒公历）：`formatEventTime` / `defaultEventTimeText` / `parse`（完整年月日、M月d日、今天明天后天、周X、下午 +12、纯时刻五个分支）/ `dayStart` / `formatDay` / `parseDay`（严格回读，`2026-02-30` 返 null）/ `adjustPickedYear`（系统 DatePicker 年份按 Locale 历法显示，按改动年数平移回公历）/ `nextWholeHour(now, zone)`（按**本地钟点**对齐，不是 epoch 整除 3600000 —— `Asia/Kathmandu`(+05:45) / `Australia/Adelaide`(+09:30) 下旧写法把提醒弹窗默认值填成 `22:45`）。旧的 `SimpleDateFormat(_, Locale.getDefault())` + `Calendar.getInstance()` 两头都吃历法：泰语设备格式化出佛历 2569、和历出 `8-11-12`（`\d{4}` 不命中，日期被静默丢弃） |
| 数字与时间戳文本 | `LocaleTextCore`（161） | 界面与模型输入里的**数字形态**口径（恒 ASCII）：短时间戳 `minutes` / `seconds` / `hourMinute` 走 `DateTimeFormatter.ofPattern(_, Locale.ROOT)`，此前 11 处 `SimpleDateFormat(_, Locale.getDefault())` 在 ar-EG / fa-IR / bn-BD 下会把 `11-12 18:10` 渲染成本地数字；小数 `fixed` / `percent` / `bytes`（十进制，模型下载）/ `binaryBytes`（1024 进制，诊断页）一律 `String.format(Locale.ROOT, ...)`，否则 de-DE 下 `0,80` 会破坏顾问摘要的 `need_action=0.80` 字段结构与报告 Markdown 表格 |；同名文件还提供 `format(template, args)` 与 `requiredArgs`：资源模板的格式槽位用 Locale.ROOT 语义自己代入（数字与小数点恒 ASCII），调用侧走 `Context.templateText(id, ...)`（ContextTemplateText.kt）；此前 63 处 `getString(R.string.x, 数字)` 在 ar-EG 设备上会渲染成阿拉伯-印度数字与阿拉伯小数点，本轮把 LocaleTextCore 契约 ⑤ 的那条豁免撤销；悬空 % / 未知占位 / 缺参一律降级不抛异常
| 提示词时间文本 | `PromptTimeCore`（112） | 提示词与报告里的**绝对时间文本**唯一口径（`java.time` + 显式 `ZoneId`，恒公历）：`chineseStamp` / `todayCn` / `todayEn` / `dueAnchor` / `isoStamp` / `messageLabel`。`DateTimeFormatter` 任何 Locale 都按 ISO 公历出年，而 `SimpleDateFormat` 的历法随**默认 Locale** 走 —— 实测 JDK 17 + `th-TH`：`Calendar.set(2026, 9, 25)` 落成公历 1483、`SimpleDateFormat("yyyy", getDefault())` 给 2569，旧题面因此写出「今天是 1483年10月25日」。`Locale.US` / `Locale.CHINESE` 只是钉语言，安全来自 Locale→calendar 映射表（巧合，已用对照用例固定）。「同年省略年份」改为 `ZonedDateTime.year` 数值比较；`zone` 参数可注入（单测钉 Asia/Shanghai 与 UTC）。五处提示词题面（`currentTimeHeader` / `todayLineCn` / `todayLineEn` / `messageLabel` / due_window 锚点）与 `S1Eval` 报告抬头已收口，22 条单测含佛历 / 和历 / 阿语 / `Locale.US` 对照回归 |
| 文本截断 | `TextClipCore`（65） | 文本截断的唯一口径：**按 Unicode 码点切，不按 UTF-16 单元切**。`String.take(n)` / `substring(0, n)` 数的是 UTF-16 code unit，emoji 与 CJK 扩展 B 占两个 unit，截在中间会留下孤立代理——实测 `s.take(16)` 的末位是高代理 `\uD83D`，转 UTF-8 落成 `?`（替换字符）；`clip(s, 16)` 得 16 个完整码点、UTF-8 合法。受影响的全是**出设备或落盘的文本**：顾问摘要与顾问请求体（模型输入被残字打断）、`QueueStateCore` 上行标题/详情（服务端与控制台照原样存显）、日历事件标题 / 诊断文案 / 通知标题（用户直接看到）。四条契约：`clip` 是唯一入口且未超限返回同一实例；省略号**追加不占预算**（`clipWithEllipsis(s, 20)` 最多 21 码点，与旧 `take(20) + 省略号` 逐字一致）；ASCII 输入输出逐字节不变（历史文案与既有断言都不用改）；已知边界是「只保证不切断代理对，不保证不切断字素簇」（国旗 / ZWJ 家族可能被切在码点边界上，宁可少画一个 emoji 也不产出非法字符）。含 20 条单测（emoji / 扩展 B / ZWJ 家族 / 逐码点扫任何 n 都不产生替换字符 / ASCII 与 take 逐字节一致） |
| 偏好存储 | `PrefsJsonCore`（135） | SharedPreferences 里「存 JSON 字符串」的读写口径：五处站点（`WatchlistStore` 的会话名单、`AppSourceStore` 的白名单/禁用/已观察、`ConvTagStore` 的标签映射、`ConvAnalysisScheduleStore` 的排期表、`AnalysisScopeCore` 的 Worker 会话 id 列表）此前各抄一份 `try { ... } catch (e: Exception) { }`，「损坏按空」只写在注释里、一条单测都没有。五条契约：① **读永不抛异常**且返回独立拷贝；② **只做形状裁剪、不做领域过滤**（`ConvTag.fromCode(v) != NONE` 这类语义判断留在 Store 层）；③ 元素走 `optString`（数字按文本取出，匹配不到任何包名等价于被忽略）；④ **写不做排序**（名单保持写入顺序，需要稳定输出显式 `sort = true`）；⑤ 与 `MessageStore` 的 JSONL 无关（那边逐行流式文件、这边单键整表）。`objects(raw, project)` 让单条坏数据只丢自己不牵连整表；29 条单测里有 3 条是与旧内联实现逐字对照的实验 |
| 模型通道 | `ChatRequestCore`（61） | OpenAI 兼容 `chat/completions` 的**请求体构造口径**：`AnalysisWorker`（S1/S2 分析）、`AdvisorWorker`（顾问复盘）、`S1EvalRunner`（S1 评测）三处此前各抄一份同构的 ~20 行，URL 归一与 `max_tokens` 逐字相同，但注释里的实测值已经分叉（1024 / 1500 / 4096）、异常类型也不一致（前两处抛 `HttpException`、评测处抛 `IllegalStateException`）。三条契约：① **端点归一只有一个方向**（`trim` → 去尾斜杠 → 已带 `/chat/completions` 原样返回，否则追加），**不猜 provider 前缀**（不补 `/v1`，用户填到 `/v4` 就补在 `/v4` 后面）；② **`max_tokens` 只有一个默认值** `MAX_TOKENS = 4096`（推理模型的思考段计入额度，1024 被吃满后 `content` 是空串），要更小必须显式传参；③ **请求体键集合是契约**（`model` / `messages` / `response_format` / `max_tokens` / `stream`，`messages` 恒 `system` → `user` 两条），`payload()` 返回已序列化字符串。刻意不接管 HTTP、`Authorization` 与异常分类 —— 那是各通道的重试语义 |
| 状态着色 | `StatusTintCore`（31） | 状态行文字的着色判定：`AnalysisSettingsActivity` 与 `CaptureActivity` 各有一份**逐字相同**的 `private fun tintStatus(view, result)`（11 行、连注释都一样，共 9 个调用点），改措辞判定必须两处同改，漏一处就是同一句「失败：…」在一页红、在另一页灰。四条契约：① **错误优先于成功**（`失败`/`鉴权` 先判，`成功`/`完成` 后判 —— `成功：轮询 3 次 + 鉴权失败(401)` 这类半成功文本取红更符合「有事要处理」）；② **判定面只有四个词**（UI 层已核实 `未成功`/`未完成` 在真实文案里 0 命中，不做正则、不加否定词表）；③ `跳过`/`已中断`/空串一律灰，其中 `已中断：完成 N 个会话…` 含「完成」故判绿是**索引前的既有行为，刻意保持不变**；④ **只回资源 id**（`getColor`/`setTextColor` 留在 Activity —— 纯 JVM 单测没有 `Context`）。不接管「待办数 0 才灰、有值才橙」这类语义化着色 |
| 同步地址 | `SyncEndpointCore`（73） | 服务端 URL 与鉴权头的唯一口径（此前五处各拼一份、`X-Token` 头抄三遍且 `isNotBlank`/`isNotEmpty` 不一致）：`base(serverUrl)` = trim → 去尾斜杠 → 去尾部 `/weixin`（**顺序是契约**，`http://h/weixin/` 必须先去掉斜杠才剥得掉）；`url` / `adminUrl` 只做拼接，不猜 `/v1`、不补 `/chat/completions`（那是 `ChatRequestCore`）；`token(raw)` 用 `isNotBlank` 转 `null`，`authorized(builder, raw)` 只在非空时加 `X-Token` 并**返回同一 builder**（链式、且不重复成双）；`SyncConfig` 的 setter 会 trim 落盘，纯空白令牌不可达 —— 这是把两套判定合成一套的依据 |
| 意图与权限 | `ConvExtrasCore`（43）· `PermissionGateCore`（78） | Intent extras → 会话键与运行时权限判定的唯一口径。`ConvExtrasCore`：六处 `getStringExtra(EXTRA_CONVERSATION/EXTRA_PKG)` 的 `takeIf { isNotBlank() } ?: 微信` 收口 —— 包名空白回落微信且**不 trim**（包名无空白语义）；会话名空白 = **无会话**返 `null`，不要构造空串键（`ConvKey("", pkg)` 永远匹配不到任何消息，详情页会静默空窗）。`PermissionGateCore`：POST_NOTIFICATIONS 必须**同时**看 `Build.VERSION.SDK_INT` 与授权结果（API < 33 该权限恒授予，只看结果会弹无效请求）；`allGranted(IntArray)` 对空结果返 false（别把「没申请」当「已授予」）；权限名用 `PERM_POST_NOTIFICATIONS` 字面量常量 —— 直接写 `Manifest.permission.POST_NOTIFICATIONS` 会触发 lint `InlinedApi`（编译期内联的 API 33 符号），基线从 3 条漂到 4 条 |
| 评测口径 | `S2Usability`（349） | S2 深分析输出的**可用性判据**（六项：`delivered` 已交付 / `no_placeholder` 非占位照抄 / `summary` 摘要非空且非原文兜底且 ≤20 字 / `due` 可解析且与 S1 同桶且不早于窗口末条 60s / `action` 建议动作 ≤20 字 / `tasks` 分派条目完整），全过才算「可用」；`bucketOf` 五桶复用 `TopicLabelCore.DUE_*`，`fallbackSummary` 与 `parseS2Output` 共用同一份原文兜底口径；纯函数，34 条单测 |
| 诊断统计 | `AllTimeStatsCore`（124） | 采集诊断页 W4「各来源累计统计」的取数与聚合口径：此前走 `MessageStore.readRecent(200_000)` —— 而 `readRecent` 的窗口写死 `TAIL_LINES = 5000` 行，历史一超就**静默给出偏小的数字**，截断提示的判据还是拿「文件行数」比「解析出的消息数」（损坏行会让人误以为没扫到）。现在 `MessageStoreCore.scanAll(limit)` 的窗口由调用方指定（`SCAN_LINES = 50_000` 行，边读边聚合、不为统计多驻留 `ChatMessage`），`Accumulator.add/finish` 单遍算累计条数 / 会话数（`ConvKey` 复合键去重）/ 自发消息数 / 每 App 的条数·会话数·群聊会话数·最近时间（`ts <= 0` 忽略），`capped(totalLines, scannedLines)` 只比**扫描行数**，损坏行不再误报截断 |
| 提醒列表 | `ReminderListCore`（175） | 提醒列表的分组与排序：三组「需要处理 → 生效中 → 已过期」，组内按事件时间（attention/active 升序、expired 降序、无时间垫底）；chip 筛选只做一次分组判定、不动原则如下 —— 两条保守语义：`event_time <= 0` 不算过期（否则用户会删掉可能有效的记录）、`failed` 永不算过期（语义是「等你重试」）|
| 会话窗口 | `ConvWindowCore`（187） | 会话详情页的消息窗口口径：此前是 `readRecent(500).filter { convKey }`——跨会话窗口，别的会话刷屏就把本会话历史挤掉，且同一窗口被当成「共 N 条」的分母。`requestLines(limit)` 按 8 倍取请求行数（默认 4000 / 硬上限 50000，避免退化成全量读）；`slice()` 先按 `convKey` 过滤再按时间升序、超 limit 保最近；`reachedHead()` 判是否已读到文件头；`preview()` 回可见条数 / 是否截断 / 条数是否可信三态，截断提示文案因此分三档。`MAX_RENDER = 200` 从 Activity 迁入 Core；`latest(window)` / `knownIsGroup(window)` 给「取本会话一条消息补上下文」的两处调用方用（日历事件描述的发送者 / 原文、a11y 提取的群聊标记）——旧写法都是 `readRecent(500)` 跨会话窗口，别的会话刷屏就取不到，而空窗口必须回 **null**（首次提取）不能回 false（写死私聊） |
| 候选窗口 | `CandidateWindowCore`（69） | 分析候选的取样口径：此前是 `readRecent(500).groupBy { convKey }`——跨会话最近 500 条，几个群刷屏就把安静但有内容的会话整个挤出候选（数据在 messages.jsonl 里却永远不被分析）。改为按会话各留 `PER_CONV = 50` 条（5 倍 `AnalysisCase.WINDOW_SIZE`，给自定义过去区间留余量），组内按时间升序、`ts <= 0` 归 0 排最前、超限只保最近；`candidateGroups()` 在 `MessageStoreCore` 里只读尾部 `TAIL_LINES`，不做全量扫描 |
| 分析窗口 | `AnalysisWindowCore`（190） | 分析记录（`analysis.jsonl`）的取窗口口径：此前三处各自 `readRecent(N)` —— 文件尾部 N 行被当成「某会话的全部记录 / 每会话最新一条 / 全库列表」，别的会话占满尾部时安静会话的 case 一条都读不到（分析列表从会话头进入显示「暂无分析结果」、首页 `latestCases` 丢掉它、详情页轮询与悬浮面板取不到最新结论）。`group()` 先放大窗口再按 `convKey` 分组、组内按 `windowEnd` 升序；`latest()` / `latestByConvKey()` 回每会话最新一条；`forConversation()` 按 `analyzedAt` 倒序只回本会话；`recent()` 才是全库倒序。窗口量级分三档：`TAIL_LINES = 5000`（列表 / 首页）、`POLL_LINES = 1500`（详情页轮询 / 悬浮面板）与 `ADVISOR_LINES = 20000`（顾问复盘的近 7 天：旧 `readRecent(500)` 只够活跃会话，安静会话的 case 进不了报告）。`since(records, cutoff)` 按 `analyzedAt` 升序取区间内记录，`AnalysisStore.readSince(context, cutoff)` 是它的文件级入口；文件级读写放同文件的 `internal class AnalysisWindowFile`，`readRecent(context, limit)` 只留给「本意全局最近 N 条」的调用点 |
| 首页窗口 | `FeedWindowCore`（191） | 首页信息流的取样口径：此前是 `readRecent(500)` —— 跨所有会话的最近 500 条，几个群刷满窗口后安静但有内容的会话在首页一条都不出现，首页「最近待办」的可见集（`todoVisibleKeys`）随之看不到它（同源隐患第五处）。改成按会话各留一窗：`perConvFor(convCount)` 按会话数把 `TOTAL_BUDGET = 6000` 摊薄，单会话夹在 `[MIN_PER_CONV = 20, PER_CONV = 200]` 之间（20 = 2 倍 `AnalysisCase.WINDOW_SIZE`，保证每会话至少凑一个分析窗口）；`group()` 会话间按最近一条倒序（稳定排序 → 可复现）、组内升序、`ts <= 0` 归 0 排最前、超限保最近；`Bucket.truncated` 在「已读到文件头」时用 `size > keep`、否则 `size >= keep`，首页据此把条数写成 `N+ 条`（下限而非确数）。`sample(result, limit)` 另有按会话轮流取样：逐条列表（脱敏预览）要的是每个会话都有代表，recent 式的全表最近 N 条在活跃会话刷屏时仍会把安静会话挤掉（这条是先被测试探测出失败才改对的）。接线：首页信息流、悬浮面板窗口键、控制台折叠作用域、关注名单会话键、分析范围会话多选器都走 `MessageStore.feedGroups()`，脱敏预览走 `sample()` |
| 可见性 | `ConvVisibilityCore`（123） | 「这个会话在主页/悬浮面板/分析门禁里可不可见」的唯一口径：四层门槛按序判定（关注名单 → 自身/系统源 → 启用集合 → 首页来源筛选），`why()` 回拦截原因码 `unwatched`/`blocked`/`disabled`/`filtered`、`isVisible()` 是布尔形态、`visibleKeys()` 供首页「最近待办」与悬浮面板共用；`forced` 只放宽「启用集合」一层，自身/系统源任何情况都拦；首页筛选只影响展示，分析门禁传 `ALL`。顺带修掉悬浮面板没接首页筛选（首页筛飞书时面板仍列微信待办）的缺口 |
| 交互与提醒 | `ManualReminderCore`（128）· `ReminderChainCore`（106）· `TodoPanelCore`（27） | 手动提醒**先解析成功再落库** · 三级降级决策（L1 日历 → L2 唤起 → L3 闹钟，13 条单测） · 待办面板（DEFAULT_LIMIT=8）与首页联动 |

**本地存储全清单**

```
files/messages.jsonl            MessageStore.kt:93       采集到的消息
files/pending.jsonl             PendingQueue.kt:63       待上报队列（自增 id 精确剔除）
files/analysis.jsonl            AnalysisStore.kt:270     case 分析记录 + forks 决策轨迹
files/reminders.jsonl           ReminderStore.kt:98      提醒记录
files/advisor_reports.jsonl     AdvisorStore.kt:125     顾问复盘报告
files/models/{modelId}/         LocalModelStore.kt:31    端侧模型文件 + .part 续传

prefs analysis_config           AnalysisConfig.kt:27     分析预设/槽位/协议/Basic 账号
prefs analysis_sync             AnalysisStore.kt:273     分析上报水位（已结算前缀）
prefs analysis_scope            AnalysisScopeCore.kt     分析范围（周期 + 自定义起止 + 点名会话集合）
prefs sync_config               SyncConfig.kt:21         服务器地址/令牌/周期
prefs privacy_config            PrivacyConfig.kt:55      隐私模式 OFF / CLOUD_REDACT / LOCAL_ONLY
prefs prompt_config             PromptStore.kt:21        会话级自定义提示词
prefs watchlist                 WatchlistStore.kt:30     重点关注名单（v3 默认空=不关注；SENTINEL_ALL=全部关注）
prefs history_sync              HistorySync.kt:40        自动提取开关/冷却/状态
prefs a11y_extract              A11yExtractStore.kt:19   无障碍提取运行态
prefs advisor_store             AdvisorStore.kt:128      顾问上报水位
prefs app_sources               AppSourceStore.kt:36     通知来源启用集合
prefs conv_analysis_schedule    ConvAnalysisScheduleStore.kt:22  会话级分析排期
prefs conv_tags                 ConvTagStore.kt:19       会话标签
prefs keepalive_state           KeepAliveService.kt:42   保活状态
prefs local_model_store         LocalModelStore.kt:31    模型清单与下载进度
prefs collapse_state            MainActivity.kt:1503     首页折叠状态
prefs app_state                 OnboardingActivity.kt:32 向导完成标记
prefs overlay_config            OverlayConfig.kt:32      悬浮层配置
prefs pending_queue             PendingQueue.kt:66       待上报队列计数
prefs retention_state           Retention.kt:25           保留期清理状态
prefs app_filter                AppFilterCore.kt        首页「按来源 App」筛选选中项
prefs queue_state               QueueStateCollector.kt  排队上报的 device_seed（FNV-1a 派生 device_id）
```

全部落在 app 私有目录，**卸载即删**。

---

## 03 三条采集通道

三条通道不是冗余，是三种能力边界互补。横向读图。

![三条采集通道图](diagrams/03-capture-pipelines.png)

| 通道 | 机制 | 覆盖范围 | 代价 / 限制 |
|---|---|---|---|
| ① 通知监听（主链路） | 官方 `NotificationListenerService`，按 `AppSourceStore` 启用集合过滤（默认全收，黑名单/被停用包跳过），丢弃 ongoing/聚合摘要，`EXTRA_MESSAGES` 逐条解析发言人，LRU 去重 | 实时增量，M2 起覆盖微信/飞书/钉钉等多 App | 免打扰群与前台会话**不产生通知**→ 完全不可见；拿不到服务启动前的历史；`EXTRA_TEXT` 可能只是"3 条新消息"摘要 |
| ② 无障碍直读 | `AccessibilityService`，控制台点「开始提取」后自动跳回微信，逐屏向上翻页去重合并，连续 `NO_NEW_LIMIT` 屏无新增判定到顶 | 本机会话历史，**免 root** | 时间戳只能按采集顺序每秒递减**估算**（记录标 `source=a11y-extract`）；微信改版会导致 UI 碎裂，靠 resource-id 候选表 + 最大面积文本节点启发式 + 整屏文本行兜底三级降级 |
| ③ 服务端数据库直读 | `android_db_extract.py`（**813 行**，非空 709 行）/ `pc_db_extract.py`（**673 行**，非空 595 行），共用内核 `extract_common.py`（554 行），在 PC 上解密微信本地库 | **完整历史**，含自己发出的消息 | 需要 MuMu root 通道（仅 MuMu 12）；微信版本相关（已验证 8.0.78）；只有文本，媒体是占位符 |

**反向写出路径（不在本图中，因为它是唯一的"写"方向）**：`MessageReplier` 用微信通知自带的「直接回复」`Notification.Action` + `RemoteInput`，把文本塞进 results Bundle 后发送该 action 的 `PendingIntent`——等价于用户在通知栏手打发送。**全部系统公开 API，无注入/hook/协议**。边界：仅用户手动触发的单条回复，禁止自动群发；执行的是微信自己的 PendingIntent，本 App 无法伪造会话。回复失败的一种典型原因是通知已被划掉（PendingIntent 失效）。

**通道 ③ 的隐私门控与翻页**（`HistorySyncCore`）：`OFF` 全放行；`CLOUD_REDACT` **不触发** `POST /admin/extract`（服务端直读会把**原文**写进云端，端上没法脱敏），但仍可从服务端拉回**已脱敏**的历史；`LOCAL_ONLY` 整个云端回填停用（`allowFetch` 直接返回 false）。回填按 `next_before` 复合游标（`毫秒_rowid`）翻页，`MAX_PAGES=20` 封顶，游标不变 / 本页为空即停。

---

## 04 分析决策流水线

纵向长图，从上往下读。核心设计：**分析单位是"会话窗口"而不是"单条消息"**，一个 case = 某关注会话最近 ≤10 条消息，`caseId = sha1(conversation|windowEnd)[:16]`——窗口没变就不重复分析。

![分析决策流水线图](diagrams/04-analysis-pipeline.png)

三个 fork 逐级过滤，越往下越贵：

- **fork 1 · prefilter**（`ForkPrefilter.kt`，零网络零模型）— 初始 `prob=1.0`，乘性惩罚：窗口 <2 条 ×0.3；垃圾占比 ≥80% ×0.05、≥50% ×0.3；全窗口无文字 → `min(prob, 0.02)`。垃圾 = 系统提示语 / 纯表情标签 / 广告链接。`prob < SHARP_THRESHOLD (0.15)` → 就地结案 `filtered=true`，**不调 laya、不调 S2**。约束：只减不增
- **fork 2 · s1**（System 1 轻量判定）— **Laya 决策模型**（走 Laya 服务自身的接口 `POST /v1/systemone`，不是「laya 协议的其他模型」），题集 `noul / choice / score` 一次批量 fan-out：`need_action`(noul) · `importance`(score 0-9) · `due_window`(choice) · `topic`(choice)。或走端侧 `runS1Local()` MiniCPM4-0.5B-MNN。防御式解析，字段缺失不崩
- **fork 3 · escalate**（S1→S2 自动升级，**JEV 体系没有、本 App 新增**）— `need_action prob ≥ 0.5` 或 `importance ≥ 6` 或 `confidence < 0.5`，任一命中即升级。S1 结论注入 S2 prompt，`POST {url}/chat/completions`（jev / glm / custom）或端侧 MiniCPM3-4B-GGUF。**S2 失败不拖垮 case**：保留 S1 结论，`escalated=false`

每步决策都写进 `forks` 数组，logcat tag = `AgentTree`，可 grep 复盘单条消息为什么被判成待办/非待办。

落库后两条出口：`needAction=true` 且提醒开关开启 → `CalendarHelper.createReminder(dedupKey=caseId)`；`SyncWorker` 通道 2 → `POST {server}/analysis`。

**本轮新增的分析侧内核**（详见 [02](#02-android-分层架构) 的 ④ 层清单）：`AnalysisScanCursor`（265 行）把「扫到哪」持久化成字节游标，配合 `FULL_PASS_INTERVAL_MS` 做环形重扫，避免每次全量扫描；`AnalysisScheduleSummaryCore`（71 行）汇总调度健康（`Health`：DISABLED / GLOBAL_OFF 等），阈值复用 `ScheduleSelfHealCore.thresholdFor`；`RedactorCore`（250 行）在落库时打 `redacted` 标记并按 `RedactorRules`（ID_CARD / PHONE / NAME 等）替换为类型占位符；`ConvTagCore`（47 行）给出会话标签 `NONE / TODO / TRACK / DONE / NOISE`；`TodoPanelCore`（27 行）控制待办面板条数（DEFAULT_LIMIT=8）与首页可见性；`AnalysisRejectCore`（201 行）负责被拒次数累计与超限隔离，文件级的逐行打补丁与原子重写走 `JsonlPatchCore`（114 行）（详见 [09](#09-同步时序)）。

旁支 **AdvisorWorker（Fable 层）**：每 7 天或手动触发，通读近 7 天 case（≤50 个，摘要截 30 字），调 System 2 服务商产出 `{summary, suggestions:[{type,title,detail}]}`，`type ∈ prompt/watchlist/threshold/other`。纯增量功能，失败不影响主链路。

---

## 05 三级降级提醒链

设计目标：**提醒必须可达**。设备上有什么就用什么，一路降级到 App 自己弹通知。

![三级降级提醒链图](diagrams/05-reminder-chain.png)

| 级别 | 触发条件 | 动作 | 落地状态 |
|---|---|---|---|
| Level 1 | `findWritableCalendar()` 找到 `CALENDAR_ACCESS_LEVEL` 可写的日历账户 | provider 直接 `insertEvent()` **并**写 `CalendarContract.Reminders` 行（提前量 + `METHOD_ALERT`），让日历自己到点弹提醒 | `STATUS_CALENDAR` 🟢 已建日历事件 |
| Level 2 | 无可写账户，但系统里有日历 App 能响应 `ACTION_INSERT` | `buildCalendarInsertIntent()` 预填标题/时间/描述，唤起系统日历由用户手动保存 | `STATUS_INTENT` 🔵 已唤起日历待保存（列表页可「再次唤起」重放 intent） |
| Level 3 | 连日历 App 都没有（本模拟器即此情况） | `AlarmHelper.schedule()` → `setAndAllowWhileIdle`。触发时间 = 事件时间 − 提前量；已过点则 1 秒后补提醒 | `STATUS_ALARM` 🟠 已设 App 内提醒（通知点击 → ConversationActivity） |
| 兜底 | AlarmManager 也不可用 | 记录失败原因 | `STATUS_FAILED` 🔴 失败（列表页可「重试」重跑三级链） |

**双重防重**

1. `ReminderStore` 先查 `dedupKey`（= caseId 指纹）——同一窗口永不重复创建，成功/失败都只建一次
2. Level 1 再用 `eventExists()` 按 `title + dtstart` 查 `CalendarContract.Events`——防"本地 reminders.jsonl 被删但日历里事件还在"

**两个刻意的取舍**

- `setAndAllowWhileIdle` 而**不是** `setExactAndAllowWhileIdle` → 不申请 `SCHEDULE_EXACT_ALARM`，接受 Doze 下数分钟漂移
- `PendingIntent.requestCode = dedupKey.hashCode()`，intent 不含变化 data + `FLAG_UPDATE_CURRENT` → 同一 dedupKey 重复设置自然覆盖旧闹钟，天然去重

`parseDueTime()` 支持 `yyyy-MM-dd HH:mm` / `M月d日H点` / `HH:mm` / `今天|明天|后天 HH:mm` / `周X HH:mm` / `下午|晚上` +12h；解析失败回退"分析时间 +1 小时"；事件默认占 1 小时。

**手动提醒**（不经过分析链路）：用户在会话页手填提醒时间，走 `ManualReminderCore`（128 行）。`EVENT_TIME_PATTERN = yyyy-MM-dd HH:mm`，**先解析成功再落库**——解析失败就不写 `reminders.jsonl`，提示格式错误，不产生「僵尸记录」；解析成功才写记录并进入同一条三级降级链。

**三级链的决策现在是纯函数**：`ReminderChainCore`（106 行，`internal object`）把「该走哪一级」抽成 `calendarAction(env)` / `shouldTryIntent(env)` / `decide(env, …)` 三个纯判定，输入是 `Env(hasCalendarPermission, hasWritableCalendar, eventAlreadyExists, inForeground, canOpenCalendar)` 五个布尔。`CalendarHelper.runReminderChain` 退化成「采集环境事实 → 执行副作用 → 把 `Outcome` 翻回 `ChainResult`」，因此这条链第一次有了 13 条纯 JVM 单测（`ReminderChainCoreTest.kt`）覆盖：L1 插入成功 / 插入失败降级 / 无权限降级 / 无可写账户降级 / L2 必须前台 / 后台直接落 L3 / L3 成功 / 三级全失败 / 日历已有同事件跳过且不越级 / 五条终态文案仍能被 `ReminderRecord.fromJson` 的旧记录推断认出来。

---

## 06 功能全景脑图

七大功能域、**177 个叶子节点**（另有 48 个中间分组），是全部功能的一次性清单。本次随代码基线重画，新增了隐私模式与脱敏、调度自愈与会话标签、手动提醒、413 单独处置、已结算前缀水位等分支。

![功能全景脑图](diagrams/06-feature-map.png)

`采集能力` · `分析与智能` · `提醒与触达` · `上报与同步` · `界面与配置` · `服务端与查看端` · `安全与边界`

**控制台（ConsoleActivity）八张卡片**，对应 `activity_console.xml`：

| # | 卡片 | 布局行 | 主要控件 |
|---|---|---|---|
| 1 | 💬 微信对接状态 | L65 | `btnConsoleGrant`(L84) · `btnReopenOnboarding`(L95) |
| 2 | ⭐ 重点关注会话 | L120 | `btnOpenWatchlist`(L134) |
| 3 | 📤 定时上报到服务器 | L157 | `sync_push_switch`(L167) · `btnSaveSync`(L226) · `btnSyncNow`(L237) |
| 4 | 📥 聊天记录提取 | L270 | `btnExtractNow`(L284) · `extract_auto_switch`(L299) |
| 5 | 📱 本机无障碍直读（无需 root） | L332 | `btn_a11y_permission`(L352) · `btn_a11y_start`(L369) · `btn_a11y_stop`(L381) |
| 6 | 🧠 后端分析（S1 + S2） | L407 | `analysis_enabled_switch`(L417) · `s2_enabled_switch`(L540) · `btnTestConnection`(L653) · `btnAnalysisNow`(L666) · `btnOpenModels`(L688) |
| 7 | 🧭 顾问复盘 | L727 | `advisor_auto_switch`(L737) · `btnAdvisorNow`(L742) |
| 8 | 📅 日历提醒 | L782 | `reminder_enabled_switch`(L792) · `btnOpenReminders`(L826) |

**重点关注名单的三态语义**（`WatchlistStore`，v3 起**默认不关注**）：空名单 = 一个都不关注——新会话只收录、不分析、不弹卡，用户点 ⭐ 逐个放行；非空且不含哨兵 = 只关注列出的会话；含 `SENTINEL_ALL` 占位串（不可能与真实会话名相同）= 关注全部，此态下单独取消某会话是把它加入显式不关注黑名单（主列表同时隐藏）。v2 用 `SENTINEL_NONE` 表达"一个都不关注"，v3 不再写入它；老用户名单里的残留值因不匹配任何真实键，天然判为不关注，无需迁移。判定口径全端统一：`keyId !in 黑名单 && (SENTINEL_ALL ∈ 名单 || keyId ∈ 名单)`（纯函数 `isWatchedId`，首页 chip、关注列表勾选框、AnalysisWorker 跳过判定共用）。

**会话级自定义提示词**（`PromptStore` / `PromptEditActivity`）：按会话名存，空白 = 用全局默认。注入三处——S1 systemone 的 `state.background`（服务端 4xx 拒绝时降级为不带 background 重试）、S1 openai 槽位 system prompt 的「背景信息」段、S2 prompt。编辑页有 3 个内置模板，点击整体替换编辑器内容。占位符 `{我}` / `{重要的人}` / `{关系}` 由用户手改——**刻意不做变量表单**，把提示词全文的控制权留给用户。

---

## 07 服务端架构

服务端已从早期单文件拆成 **8+1 个模块**（8 个 Python 模块 + 1 个 JS 规则模块），依赖仍然只有 `flask==3.1.3`（另有 waitress / pycryptodome / Pillow）。下面表格的行号是**当前源码的真实行号**，与图 07 标注一致。

| 模块 | 行数 | 装什么 | 关键行 |
|---|---|---|---|
| `server/app.py` | 742 | Flask 壳：鉴权、错误处理、`/console` 与落地页、`create_app`、排队过期行清理节流 | `check_token` @300 · `MAX_CONTENT_LENGTH`=8 MiB @89 · `_handle_too_large` @169 · `_ensure_db_ready` @130 · `_maybe_cleanup_queue` @112 · `console_page` @528 · `create_app` @696 |
| `server/util.py` | 432 | 纯函数工具层（校验、转义、时区换算、格式化、游标解析、顾问时间归一） | `_advisor_ts` @88 · `_validate_item` @400 · `_safe_int` @50 · `_esc_html` @363 · `_fmt_ts` @313 · `_parse_cursor` @195 · `_utc_ms` @236 · `_local_offset_min` @247 · `_local_ms` @270 · `_day_bounds_ms` @283 |
| `server/storage.py` | 885 | SQLite / JSONL 存档 / 顾问存档 / 抽库后台 / 排队任务表（含全库聚合 `queue_summary`、保留期清理 `cleanup_queue_tasks`、在跑设备计数 `count_live_devices`） | `init_db` @58 · `_append_jsonl` @197 · `_advisor_jsonl_path` @216 · `_run_extract_bg` @437 · `_probe_main_db_writable` @479 · `upsert_queue_tasks` @616 · `cleanup_queue_tasks` @662 · `query_queue_tasks` @692 · `queue_summary` @753 · `queue_devices` @812 · `count_live_devices` @864 |
| `server/routes.py` | 1092 | 数据面路由，`register(app)` 挂载 10 条；`/messages` 与 `/analysis` 都返回 `skipped`（被 `typeof` 守卫挡掉的脏行数，让前端能解释「列表条数 < 库存」） | `register` @1080 · `add_url_rule` 映射 @1082-1091 · `receive_queue` @956 · `query_queue` @1029 · `query_messages` @169 · `query_analysis` @568 |
| `server/console_ui.py` | 3780 | 控制台前端常量（HTML/CSS/JS 全内联，5 个 Tab，aura 设计语言）；**顾问存档损坏时渲染 `ADVISORS_ERR` 错误态而不是「还没有顾问报告」**；概览条设备数只算在跑台数（`liveDeviceCount` / `deviceTallyText`）；**概览条待办的会话数读 `todo_convs` 不是 `convs`**；**顾问 Tab 角标读存档总数 `ADVISOR_TOTAL` 并标注「只显示最近 N 份」**；**统计卡的类型分布标注「不含 N 条已过期」**；**统计卡的两条分布先按标签归并再渲染**（`distPairs` / `mergePairsBy`，库里 `pkg` 为 NULL / 空串时不再出现三个「微信」）；**顾问 Tab 的来源过滤说明按 `advisorPkgScope` 四态选措辞**（全不携带 / 混合 / 真过滤 / 零命中），不再无条件写「无法过滤」；**顾问列表的「截断」说明只认已加载条数**（`advisorListNote` / `advisorListState`，筛选/搜索造成的缩短单独交代，不再拿可见条数比存档总数）；**来源筛选下的四个列表空态分开三种成因**（`appScopeCounts` / `appEmptyScope` —— 全库确实没有 / 全库有但不在已加载页 / 拿不到全库分布，不再一律写「该来源下暂无数据」）；**统计 Tab「最近分析结果」只画前 30 条时必须说明另有几条没画、并给展开开关**（`statsListNote` / `statsListMore` / `statsListState`，标题与切片同源读 `listState`） | `CONSOLE_HTML` @13 · `CONSOLE_MANIFEST` @3494 · `CONSOLE_SW` @3540 · `skipNoticeHtml` @1920 · `loadMore` @1976 · `refreshSuggests` @1951 · `renderOverview` @1816 · `CONSOLE_MANIFEST` @3596 · `CONSOLE_SW` @3642 · `renderStats` @2942 · `statsListNote` @2922 · `statsListMore` @2931 · `setStatsListOpen` @2938 · `STATS_LIST_CAP` @1431 · `advisorScopeNote` @3140 · `advisorListNote` @3174 · `renderAdvisor` @3189 · `liveDeviceCount` @3328 · `deviceTallyText` @3337 · `queueStateTotal` @3366 · `queueEmptyHtml` @3487 · `distPairs` @2987 · `pairList` @2973 · `appScopeCounts` @2375 · `emptyStateFor` @2387 |
| `server/stats_api.py` | 228 | 全库聚合 `/stats`（消息 / 分析 / 排队三段）；**会话数按 `(pkg, conversation)` 计**（`CONV_KEY_SQL`，与前端 `ckOf()` 同口径）；排队段同时给 `devices`（台数）与 `devices_active`（其中在跑的台数）；**`by_kind` 只数未过期行**（与四个状态同口径，`sum(by_kind) == total - stale`） | `query_stats` @193 · `_messages_block` @59 · `_analysis_block` @107 · `_queue_block` @142 · `register` @226 · `CONV_KEY_SQL` @44 · `INT_ONLY_TS` @52 · `MAX_TS` @56 |
| `server/suggests.py` | 344 | 下一步建议 `/suggests/next`（规则优先级队列；`pkg` 只作用于「待办 / 未分析会话」两族规则，排队按设备、顾问是全局报告）；`_advisor_state()` 返三元组，损坏时份数是 `None` 不是 0，产出 priority 85 的「顾问存档损坏」 | `query_suggests_next` @209 · `_advisor_state` @38 · `_queue_state` @74 · `_todo_counts` @104 · `_convs_without_analysis` @128 · `_pick` @197 · `register` @341 |
| `server/extract_common.py` | 554 | 两个抽取脚本的共享内核：结构化日志 / 原文 JSONL 存档与轮转 / 跨进程写锁 / 五列去重 / 分批提交 / 主库结构兜底 | `activate` @83 · `_setting` @97 · `_log_event` @119 · `_archive_path` @139 · `_JsonlArchiver` @182 · `_FileLock` @251 · `_Dedup` @359 · `_BatchWriter` @419 · `_ensure_main_schema` @516 · `_cursor_int` @532 |
| `server/console_classify.js` | 642 | 归类规则单一事实源（UMD：浏览器 `window.ConsoleClassify` / node `module.exports`，无 DOM）；**排队条目的长度上限按 Unicode 码点算**（`codePointLength` / `clipCodePoints`，`title` 256 / `detail` 512 / `task_id` 128，与端上 `QueueStateCore` + 服务端 `storage._normalize_queue_task` 三处同口径）；分布归并 `mergePairsBy` 归并后按次数降序重排；**顾问 Tab 的来源过滤判定 `advisorPkgScope`**（返回 `mode` 四态 + `total/tagged/untagged/shown/hidden`，UI 只挑措辞）；**顾问列表的截断/筛选判定 `advisorListState`**（`{loaded,total,shown,truncated,filtered}` 两个正交标志，`total` 缺失回落已加载条数）；**来源筛选空态的判定 `appEmptyScope`**（`mode` 四态 `loaded/none/elsewhere/unknown`，键过 `normPkg` 归并与统计卡同口径，`db` 与 `shown` 无大小关系只信 `db===0`）；**统计 Tab 列表的截断判定 `statsListState`**（`{loaded,shown,cap,hidden,capped,expanded}`，`capped` 与 `expanded` 正交，上限由 UI 传入、脏值回落 `DEFAULT_STATS_LIST_CAP`=30） | `normPkg` @37 · `pkgLabel` @41 · `groupByPkg` @89 · `mergePairsBy` @110 · `mergePkgPairs` @129 · `mergeSourcePairs` @134 · `codePointLength` @242 · `clipCodePoints` @255 · `normalizeQueueTasks` @275 · `queueSummary` @329 · `queueDeviceSummary` @483 · `advisorPkgScope` @504 · `advisorListState` @529 · `_countOf` @539 · `pkgCount` @551 · `appEmptyScope` @578 · `DEFAULT_STATS_LIST_CAP` @595 · `statsListState` @597 · 导出块 @605-641 |
> 行数为当前源码的**文件总行数**（wc -l 口径）；若只计非空行，依次为 app.py 627 / util.py 384 / storage.py 821 / routes.py 1030 / console_ui.py 3689 / stats_api.py 201 / suggests.py 301 / console_classify.js 602 / extract_common.py **474** / android_db_extract.py **709** / pc_db_extract.py **595**。

![服务端架构图](diagrams/07-server-architecture.png)

**路由全表**（含行号）

| 方法 | 路径 | 处理函数 | 说明 |
|---|---|---|---|
| POST | `/weixin` | `receive_weixin` @routes.py:20 | 接收消息数组，返回 `{ok, received, duplicated, invalid}` |
| GET | `/messages` | `query_messages` @routes.py:169 | `conversation` 模糊 / `date` / `limit`≤1000，timestamp DESC；返回体含 `skipped`（当前过滤范围内被 `typeof(timestamp)` 守卫挡掉的脏行数，不含游标段） |
| POST | `/analysis` | `receive_analysis` @routes.py:342 | `case_id` 唯一键去重 |
| GET | `/analysis` | `query_analysis` @routes.py:568 | `todo_only=1` 只看待办，s1/s2 反解析为对象 |
| POST | `/advisor` | `receive_advisor` @routes.py:756 | `report_id` 去重 |
| GET | `/advisor` | `query_advisor` @routes.py:845 | `limit` 默认 20 / 上限 100；返回 `{ok, count, total, has_more, reports}` —— `total` 是**存档总数**（上限 `ADVISOR_MAX_REPORTS`=1000），`has_more` 表示本次被截断；只回 `count` 时前端无法区分「只有 100 份」与「有 400 份、只给最新 100 份」 |
| POST | `/admin/extract` | `admin_extract` @routes.py:889 | `full=1` + `source ∈ android\|pc\|all`，409 = 已在跑 |
| GET | `/admin/extract/status` | `admin_extract_status` @routes.py:942 | `{ok, running, last}` |
| POST | `/queue` | `receive_queue` @routes.py:956 | 手机端上报排队任务快照（整份覆盖，非法条目逐条拒收） |
| GET | `/queue` | `query_queue` @routes.py:1029 | `device_id` / `state` / `limit`≤500，返回 `{ok, tasks, summary, devices}`（`devices` 是按 `device_id` 聚合的设备清单：`total`（现存条数）/ `active`（**已过 TTL 口径**，只算未过期的排队中+运行中）/ `stale`（过期或 `updated_at` 认不出）；`summary` 是**全库口径**，只受 `device_id` / `state` 过滤、不受 `limit` 截断，与 `/stats` 的 `queue` 段同口径；`summary.devices_active` 只数其中 `active > 0` 的台数 —— 设备停报后过期行要等 7 天保留期才清，拿 `devices` 当「几台在跑」会写出「0 个任务 · 2 台设备」） |
| GET | `/stats` | `query_stats` @stats_api.py:193 | 全库真值：消息段（含 `by_pkg` / `by_source` 对象数组）+ 分析段（含 `redacted_cases` / `redaction_hits`）+ 排队段；**分析段不接受 `pkg` 过滤**；消息段的 `by_pkg` / `by_source` 也跟随 `pkg` 过滤（同一段内分布之和恒等于 `total`）；`convs` / `todo_convs` 按 `(pkg, conversation)` 计（与 `/messages` 行及控制台会话卡同口径）；排队段的 `devices_active` 是在跑台数，概览条只读它；`by_kind` 只数未过期行（同一张统计卡上的「活跃 / 陈旧」同口径） |
| GET | `/suggests/next` | `query_suggests_next` @suggests.py:209 | 最多 3 条下一步建议，按 `priority` 降序；每条含 `type` / `title` / `why` / `how` / `evidence` / `jump`；`pkg` 是**真过滤**（只影响 `todo` / `convs_missing` 两族的计数，`evidence` 里的数字按该来源 App 算） |
| GET | `/console` | `console_page` @app.py:528 | PWA 单页，HTML/CSS/JS 全在 `console_ui.py` 的 `CONSOLE_HTML` @console_ui.py:13 |
| GET | `/console/manifest.json` | @app.py:542 | 免鉴权 |
| GET | `/console/icons/<path>` | @app.py:548 | |
| GET | `/sw.js` | `console_sw` @app.py:556 | 免鉴权；骨架 cache-first，`/stats` 与 `/suggests` 也永远走网络 |
| GET | `/` | `index` @app.py:568 | 极简网页，最近 100 条倒序 |

**鉴权**：`check_token()` @app.py:300 接受 Header `X-Token` **或** Query `?token=`。设置 `WEIXIN_TOKEN` 后除 `/console/manifest.json` 与 `/sw.js` 外全部需要鉴权。

**数据库 schema**（`init_db` @storage.py:58）

```sql
CREATE TABLE messages (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  sender TEXT, text TEXT, timestamp INTEGER,
  conversation TEXT, is_group INTEGER, received_at INTEGER
);
CREATE UNIQUE INDEX idx_messages_dedup ON messages (conversation, sender, timestamp, text);
-- 迁移追加：source TEXT NOT NULL DEFAULT 'android-notification'
--          取值 android-notification / android-db / pc-db

CREATE TABLE analyses (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  case_id TEXT UNIQUE, conversation TEXT, window_end INTEGER,
  message_count INTEGER, need_action INTEGER, importance TEXT,
  escalated INTEGER, s1 TEXT, s2 TEXT, analyzed_at TEXT,
  protocol TEXT, received_at INTEGER
);
-- 迁移追加：forks TEXT（JSON 数组 [{fork,prob,verdict,note}]）
--          filtered INTEGER NOT NULL DEFAULT 0

CREATE TABLE queue_tasks (
  device_id TEXT NOT NULL, task_id TEXT NOT NULL,
  kind TEXT NOT NULL, title TEXT NOT NULL DEFAULT '',
  state TEXT NOT NULL, detail TEXT NOT NULL DEFAULT '',
  queued_at INTEGER NOT NULL DEFAULT 0, updated_at INTEGER NOT NULL,
  PRIMARY KEY (device_id, task_id)
);
CREATE INDEX idx_queue_device ON queue_tasks (device_id);
```

**提取编排**：`_extract_status = {"running": False, "last": None}` 内存态 + `_extract_lock`；`_run_extract_bg(full, source)` **@storage.py:437** 起 daemon Thread，逐腿 `mod = __import__(leg + "_db_extract")` → `mod.run_extract(full=full)`。**单腿失败不拖垮另一腿**，整体 ok = 至少一腿不是 `ok is False`。

**部分接受 207**（`routes.py:151-160`）：一批里有条目校验失败时，响应码是 **207**（不是 200），body 为 `{ok:false, rejected:[下标], error:"some items were rejected"}`，**其余条目照常入库**。端上必须按 `rejected` 清单精确剔除（`SyncOutcomeCore.parseRejected`），不能整批当成功删掉——那正是消息永久丢失的根因。

**安卓库解密参数对比**

| | PC 微信 4.x | 安卓微信 |
|---|---|---|
| 库文件 | `Msg*.db`（`message/message_0.db`） | `EnMicroMsg.db` |
| 加密 | SQLCipher 4 | WCDB2 / SQLCipher 页加密 |
| 密钥来源 | 内存搜索 / 进程注入 | `MD5(IMEI + str(uin))[:7].lower()` 离线推导 |
| pageSize | 4096 | 1024 |
| KDF | PBKDF2-HMAC-SHA512，256000 轮 | PBKDF2-HMAC-SHA1，4000 轮 |
| HMAC | 开 | 关，reserve=16（仅 IV） |
| 取库方式 | 本地文件直读 | MuMuManager root shell `cp` + `adb pull` |

**安卓提取七步**（全部运行时探测，无硬编码账号信息）：`MuMuManager.exe sh -v 0 -c` 拿 root shell（adb shell 无 su）→ 定位 `MicroMsg/` 下 32 位 hex 账户目录（= `md5("mm"+uin)`）→ 从 MMKV `files/mmkv/system_config_prefs` 解析 `default_uin`（protobuf varint）→ IMEI 候选链 `service call iphonesubinfo` → `WLOGIN_DEVICE_INFO.xml` → 兜底 `1234567890ABCDEF` → root `cp` 到 `/data/local/tmp` + `chmod 644` + `adb pull` → 纯 Python pycryptodome 逐页 AES-CBC 解密，用第 1 页 page-size 字段验证密钥命中 → 读 `message`/`rcontact`/`chatroom` 三表合并入库。

**跨源去重**：两个来源的毫秒时间戳不一致，故归一化到 `(conversation, sender, 秒级timestamp, text)`；已存在行与批内重复都跳过。通知来源的重试去重仍由原毫秒级唯一索引兜住。增量游标在 `data/android_db/state.json`（上次 max msgId）。

**PWA 查看端**：`?token=` 写入 localStorage 后从地址栏抹除，后续请求自动带 `X-Token`。五个 Tab——会话（按会话分组，头部计数 + 最新预览 + ⚡待办角标，点卡片展开，待办会话内联 S1 chips 与 S2 区）/ 待办（全部 `need_action=true`，高重要度标红）/ **排队（手机端 WorkManager 任务快照，按状态分区，可按状态筛选；stale = 30 分钟未更新）**/ 统计（消息总数、来源分布、已分析会话数、最后上报时间）/ 顾问（AI 复盘建议，按建议类型分区）。顶部概览条 5 个指标（近 24 小时消息 / 待处理 / 排队任务 / 同步失败 / 最近同步）。30 秒自动刷新，全内联无 CDN，**数据永不缓存，只缓存页面骨架**。

---

## 08 构建与部署

![构建与部署图](diagrams/08-build-deploy.png)

**构建**：AGP 8.5.2 + **Kotlin 2.2.21**，compileSdk 34 / minSdk 26 / targetSdk 34，Java 17，NDK 27.2.12479018 + CMake 3.31.6。仓库内置 Gradle Wrapper（8.9），直接 `gradlew.bat assembleOpenDebug` / `assembleBetaDebug` 即可（详见 [docs/BUILD.md](../BUILD.md)）。

> Kotlin **必须 ≥2.1**：`litert-api 2.2.0` 的元数据是 2.3.0，2.0.x 编译器直接报 incompatible version。

**两个 flavor**（`flavorDimensions += "channel"`）

| | `open`（对外发布） | `beta`（内部测试，产品名 test） |
|---|---|---|
| applicationId | `com.notifyme.android` | + `.test` 后缀 |
| versionName | `0.3.19` | `0.3.19-test` |
| 桌面名 | 微信分析助手 | 微信分析助手·测试 |
| `DEFAULT_*` buildConfigField | **全部空串**，APK 内不含任何密钥 | 从 `secrets.local.properties` 经 `secret(key)` 读取，`bcString(value)` 转义反斜杠与引号 |
| 产物 | `notifyme-open.apk` | `notifyme-test.apk` |

beta 注入：`DEFAULT_ANALYSIS_PROTOCOL="openai"`、`GLM_BASE_URL`/`GLM_API_KEY`/`GLM_MODEL`、`JEV_URL`/`JEV_API_KEY`、`MONITOR_URL`/`MONITOR_TOKEN`。首次启动 seed 进配置（仅填充，可改）。

> AGP 禁止 flavor 名以 `test` 开头，故内部名 `beta`、产品名 `test`。桌面名加后缀是因为用户曾把 open 包误认成 test 包，报"没有预置模型、没有历史回填"——那是 open 的设计行为。

**ABI 与产物形态**（`app/build.gradle.kts`）

| buildType | ABI | 混淆 / 资源压缩 | 备注 |
|---|---|---|---|
| `debug`（open + beta） | `arm64-v8a` + `x86_64` | 无 | 真机 + 模拟器双 ABI |
| `release`（open） | **只 `arm64-v8a`** | `isMinifyEnabled=true`（R8 混淆 + `proguard-android-optimize.txt` + `proguard-rules.pro`）· `isShrinkResources=true`（资源压缩） | 少带一套 x86_64 原生库，约省 20MB |

要在 x86_64 模拟器上装发布包，临时加回：`.\gradlew.bat assembleOpenRelease -PreleaseAbis=arm64-v8a,x86_64`（release ABI 清单由 `-PreleaseAbis` 覆盖，默认值只有 `arm64-v8a`）。

**CI：`.github/workflows/android.yml` · 14 步**

`runs-on: ubuntu-24.04`（钉住不用 `ubuntu-latest`：后者将于 2026-10-19 迁到 Ubuntu 26，预装的 JDK / SDK 组合会变，而 CI 是这个仓库唯一的自动化护栏）、`timeout-minutes: 60`、`permissions: contents: read`、`concurrency` 组按 `build-<工作流>-<ref>`，**只在 PR 上** `cancel-in-progress`（main 上的构建不取消——半截提交被跳过比多跑一次更麻烦）。

1. `actions/checkout@v7`
2. `Set up JDK 17`（temurin）
3. `gradle/actions/setup-gradle@v5`（缓存依赖；PR 上 `cache-read-only`）
4. `Prepare wrapper for CI`（`sed` 把 `distributionUrl` 换成官方源）
5. `Ensure Android SDK platform 34, NDK and CMake`（NDK 27.2.12479018 / CMake 3.31.6）
6. `Fetch llama.cpp v0.5.0`（M4 native source，不入库）
7. `Lint (Android Lint)` —— `lintOpenDebug`，**退出码直接判定**（不再 `2>&1 | tail -20` 把退出码吞掉，也不加 `--offline`）
8. `Unit tests + assemble (open & beta)` —— `testOpenDebugUnitTest assembleOpenDebug assembleBetaDebug`
9. `Set up Python (pip 缓存)` —— 3.12，缓存 `server/requirements.txt`
10. `Python syntax check (server, 目录级)` —— `python3 -m compileall -q server`
11. `Server tests (pytest, 目录级发现)` —— `python3 -m pytest server/ -v --tb=short`
12. `Guard - 隐私与仓库卫生` —— `python3 docs/scripts/privacy_guard.py --forbid-local-secrets`
13. `Version check` —— `python3 docs/scripts/version_check.py`
14. `Upload debug APKs` —— `upload-artifact@v7`，两个 debug APK，保留 14 天

**发布：`.github/workflows/release.yml`** —— 推送 `v<version>` 标签触发，`timeout-minutes: 30`、`concurrency` 不取消旧跑。链路：`version_check.py --expect-tag` 校验 tag 与 `versionName` 一致 → 解码 `NOTIFYME_KEYSTORE_BASE64` → `assembleOpenRelease -PrequireReleaseSigning=true`（缺签名直接 `GradleException`）→ `apksigner verify` → 生成 `.sha256` → `gh release create`。需要的 4 个仓库 Secrets：`NOTIFYME_KEYSTORE_BASE64` / `NOTIFYME_KEYSTORE_PASSWORD` / `NOTIFYME_KEY_ALIAS` / `NOTIFYME_KEY_PASSWORD`。**CI 本身不签名。**

**设备安装后的四道授权**（都是系统级手动开关，不能运行时申请）

1. 通知监听 `BIND_NOTIFICATION_LISTENER_SERVICE` → `Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS`；验证 `adb shell settings get secure enabled_notification_listeners`
2. 无障碍服务 `BIND_ACCESSIBILITY_SERVICE` → 系统设置 → 无障碍
3. 电池白名单 `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` → 首页 `btnBatteryWhitelist`
4. 开机自启 `BootReceiver` → `KeepAliveService`。Android 12+ 后台起 FGS 会抛 `ForegroundServiceStartNotAllowedException`，**这是预期失败**，捕获记日志即可；兜底是"来微信通知时系统自动 bind 监听器 + 60s 看门狗 `requestRebind`"

**服务端启动**：`server\启动服务.bat` 做三件事——① `set WEIXIN_TOKEN`（真值在 gitignored `secrets.local.bat`；改令牌必须同步改 App 的「访问令牌」并重按「保存并启用定时上报」，否则 401）② `adb connect 127.0.0.1:16384` + `adb reverse tcp:8000 tcp:8000`（模拟器内 `http://127.0.0.1:8000` 直达宿主，免局域网 IP 与防火墙）③ 前台跑 Flask，Ctrl+C 停。**`adb reverse` 存活于 adbd，模拟器重启即失效，每次重跑 bat。**

**两条访问链路**

```
链路 A（局域网/模拟器）
  App → http://127.0.0.1:8000/weixin → adb reverse → Flask 0.0.0.0:8000

链路 B（公网 HTTPS）
  App / iPhone PWA
    → https://your-domain.example/mmonitor/weixin
    → VPS nginx :443   /etc/nginx/sites-enabled/mmonitor  location /mmonitor/
    → VPS 127.0.0.1:23444   frps  /etc/frps/frps.toml  allowPorts 必须含 23444
    → 宿主机 frpc   frpc/frpc.toml  localPort 8000 → remotePort 23444
    → Flask 127.0.0.1:8000
```

**三处配置必须保持同步**（frpc.toml / frps.toml allowPorts / nginx location）。排障顺序：Flask 存活（`127.0.0.1:8000/messages` 带 token → 200）→ frpc 隧道在跑 → nginx。frpc 热重载：`curl -u admin:... http://127.0.0.1:7400/api/reload`（GET）。

**安全红线**：默认定位局域网/自控服务器；Flask 内置服务器不用于高并发生产；公网必须 `WEIXIN_TOKEN`（长随机）+ HTTPS；可 caddy/nginx 反代或 frp，绝不裸奔；令牌当密码，永不提交；`messages.jsonl` / `messages.db` 含聊天原文，注意存储与备份安全。`android:usesCleartextTraffic="true"` 仅为局域网调试。

---

## 09 同步时序

两个阶段：冷启动历史回填 + 定时三通道上报。逐条对齐 `HistorySync.kt` 与 `SyncWorker.kt` 源码。

![同步时序图](diagrams/09-sync-sequence.png)

**阶段一 · 冷启动回填**（`MainApplication.onCreate:23` 调 `HistorySync.maybeRunOnStartup`）

四道闸门，任一不满足即静默跳过：`auto_enabled=false`（默认 true）→ 距 `last_run_at` 不足 6 小时（`COOLDOWN_MS`，冷却期内**不写状态**，避免覆盖上次有效结果）→ `serverUrl` 为空 → 通过则**先占位写 `last_run_at`** 防多入口并发两轮，再起 `thread(name="history-sync", isDaemon=true)`。

`POST /admin/extract` → 每 2s 轮询 `/admin/extract/status`，最多 90s（`POLL_TIMEOUT_MS`）→ `GET /messages?limit=1000` → `MessageStore.merge` 秒级去重回填。**409（服务端正在跑）视为正常，接管轮询**；`last.ok=false`（无 root / 微信未装 / 解密失败）原样透传 `last.error`。endpoint 推导规则与 `ConsoleActivity.extractEndpoint` 同款：`serverUrl` 去末尾 `/` 再去末尾 `/weixin` 再拼路径。

**阶段二 · 三通道上报**（`SyncScheduler` unique `"wechat_sync"`，`NetworkType.CONNECTED`，周期 15/30/60/120 分钟，WorkManager 下限 15 分钟）

**隐私门控先于任何通道**（`PrivacyConfig`，三档 `OFF` / `CLOUD_REDACT` / `LOCAL_ONLY`）：`serverUrl` 为空 → `Result.success()` 不发任何请求；`pushEnabled=false` → 记「已跳过：推送开关已关闭（消息继续累积）」，`pending.jsonl` 与分析水位都保持不动，重开后下轮一并补报；`LOCAL_ONLY` → 三通道整体不发送，记「已跳过：仅端侧模式，云端上报整体停用（数据继续留在本机）」；`CLOUD_REDACT` → 各通道在发送一刻替换，分析通道只放行**脱敏态产出**的记录。

**三条必须记住的口径**

1. **上报水位＝「已结算前缀」**（`SyncWatermarkCore.prefixWatermark`）：水位只推进到「本轮被接受 ∪ 本轮新被隔离」的**连续前缀**。中间存在「被拒未隔离 / 未脱敏 / 本轮结论不明（207 清单读不出来）」的**洞**时，水位**停在洞前，绝不越过**——越过的记录 `analyzedAt <= 水位`，扫描会当已同步跳过，**永远补报不了**。全部结算时行为与旧口径一致（推进到最大 `analyzedAt`）。
2. **413 单独处置**：服务端单条请求体上限 8 MiB，超限的那一条是「毒丸」，重试无用。端上先把批次二分重试（`SyncBatchCore.bisect`），拆到单条仍 413 才单独隔离该条并继续下一批，**不触发整轮 retry**（否则一条毒丸会永久卡住它后面的整条队列）。消息通道把该条记为被拒（`PendingQueue.markRejected`，累计到上限自然清出队列），分析通道直接 `AnalysisStore.quarantineNow` 隔离。
3. **隐私门控**：被拒 / 未脱敏的条目**不进水位**。`CLOUD_REDACT` 下未脱敏的记录在**扫描层**被跳过（`AnalysisStore.readPending(requireRedacted=true)`），且**不钉住游标**——否则队首那条未脱敏记录会把游标永久钉住，它后面新追加的记录永远读不到、永远不上报。隐私模式一变还会重置水位与扫描偏移（`AnalysisStore.syncPrivacyMode`）。

| 通道 | 目标 | 成功动作 | 失败处置 |
|---|---|---|---|
| ① 消息 | `POST serverUrl` | `PendingQueue.removeSent(本次发送的 id 集合)`——按自增 id 精确剔除，上报期间新入队的保留 | 2xx→success · **401→`Result.failure()` 终止**（配置错误，重试无用）· 其它非 2xx / IOException→`Result.retry()` 退避 |
| ② 分析 | `POST base/analysis` | `AnalysisStore.markSynced(records.maxOf { analyzedAt })`——推进到**本次记录的最大 analyzedAt 而非当前时间**，避免误标上报期间新落库的记录 | 404（旧服务端无端点）→ 静默跳过 · 其它失败 → 只记文案，**水位不动、不触发 Worker 重试**，下轮自然补报 |
| ③ 顾问 | `POST base/advisor` | `AdvisorStore.markSynced(reports.maxOf { createdAt })`，服务端按 `report_id` 去重 | 404 → 仅 `Log.i` 不记 extraParts · 其它 → 水位不动 |

**关键不变量：只有通道 ① 能决定 Worker 的 success/retry/failure。** 通道 ②③ 的任何结果都不改变通道 ① 已写下的处置。三通道结果文案合并进 `lastSyncResult`，如「成功：消息 5 条 + 分析 2 个 + 顾问报告 1 份」；**三通道都无数据时也要落一条「成功：无新数据」**——否则用户点「立即同步」后控制台状态行停留在上一轮，像没点上。

上报 body 的命名转换：消息通道逐条 `remove("isGroup")` + `put("is_group")`（驼峰转蛇形，对齐 PC 端 Python 习惯）；分析通道 `remove("raw_json")`（本地防御存档不上报），`escalated=false` 时显式 `put("s2", JSONObject.NULL)` 对齐接口字段。

**超时**：`SyncWorker` 共享单例 client，connect/read 各 15s（Worker 频次低）；`HistorySync` 独立 client，connect 10s / read 30s（提取轮询更耗时）。

---

## 端到端数据流（一句话版）

```
微信通知 ──① NotificationListenerService──► MessageStore(messages.jsonl)
                                                    │
微信界面 ──② AccessibilityService 逐屏翻页──────────┤
                                                    ├──► PendingQueue(pending.jsonl)
微信本地库 ─③ PC 端 SQLCipher 解密 ──► messages.db ──┘            │
                     ▲                                          │
                     └── POST /admin/extract ◄── HistorySync     │ SyncWorker 通道①
                                                                 ▼
                              AnalysisWorker ◄── WatchlistStore + PromptStore
                                    │
                    fork1 ForkPrefilter(本地, 阈值0.15) ──► filtered=true 就地结案
                                    │ split
                    fork2 S1 laya /v1/systemone 或端侧 MiniCPM4-0.5B
                                    │ need_action / importance / due_window / topic
                    fork3 escalate(≥0.5 | ≥6 | conf<0.5) ──► S2 /chat/completions
                                    │
                              AnalysisStore(analysis.jsonl, forks 轨迹)
                                    │
                    ┌───────────────┼────────────────────┐
                    ▼               ▼                    ▼ SyncWorker 通道②
        CalendarHelper 三级降级   AnalysisListActivity   POST /analysis
        reminders.jsonl           ConversationActivity   → analyses 表(case_id UNIQUE)
                    │                                        │
        AlarmManager/日历/Intent                             ▼
                    │                              GET /analysis?todo_only=1
                    ▼                                        │
        ReminderReceiver 高优先级通知                          ▼
        → 点击回 ConversationActivity              iPhone PWA /mmonitor/console
```

---

## 重新渲染

```powershell
# 前置：npm i -g @mermaid-js/mermaid-cli（本机已装 11.17.0）
$d = "docs/architecture/diagrams"
$mmdc = "$env:APPDATA\npm\mmdc.cmd"

# 每张图先 .png 再 .svg
# -w 12000：视口远大于自然宽度，PNG 落到原生分辨率（既不拉伸也不压缩）
Get-ChildItem $d -Filter *.mmd | ForEach-Object {
  & $mmdc -i $_.FullName -o ($_.FullName -replace '\.mmd$','.png') -b white --scale 1 -w 12000
  & $mmdc -i $_.FullName -o ($_.FullName -replace '\.mmd$','.svg') -b white
}

# 读回真实像素尺寸，回填上方索引表的「尺寸 (px)」列
Add-Type -AssemblyName System.Drawing
Get-ChildItem $d -Filter *.png | ForEach-Object {
  $i = [System.Drawing.Image]::FromFile($_.FullName)
  "$($_.BaseName)  $($i.Width)x$($i.Height)"
  $i.Dispose()
}
```

**踩过的三个坑**

1. `classDef call ...` 会解析失败——`call` 是 Mermaid 保留字（CALLBACKNAME）。本图集改用 `llmcall`
2. sequenceDiagram 的消息文本里**不能出现 ASCII 分号 `;`**（被当成语句分隔符）。改用中文全角 `，` 或 `；`
3. 不加 `-w` 时 mmdc 会把图压进默认视口（约 800 CSS px 宽），文字完全不可读。这里统一传 `-w 12000`：Mermaid 给 SVG 设了 `max-width: <viewBox 宽>`，视口比自然宽度大时 PNG 落在自然分辨率上。也可以先渲染 SVG 读 `viewBox` 宽，再用 `-w <宽+24>` 精确光栅化，两种做法得到的像素尺寸一致。

**如果 mmdc 起浏览器失败**（Windows 上偶发），加 `--puppeteerConfigFile` 指向一个临时配置，渲染完删掉即可：

```json
{ "args": ["--no-sandbox"] }
```

---

## 文档分工

- 仓库根 [README.md](../../README.md)：面向用户的产品介绍、平台说明（Android APK / iPhone PWA）、快速开始与隐私法律。
- [docs/BUILD.md](../BUILD.md)：面向开发者的构建指南（环境、flavor、密钥注入、adb 安装）。
- 本目录：面向维护者的代码级架构逆向梳理，9 张图覆盖系统上下文到同步时序，所有类名/方法名/行号均来自实际源码。

> 历史备注：本架构文档早期版本曾记录「根 README 落后于代码」的差异清单。根 README 已于本次精简重写后与代码对齐（含分析链路、提醒体系、无障碍直读、端侧模型、历史回填、双 flavor 构建等），该差异清单已失效并移除。
