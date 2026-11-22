# 评测：云端 vs 端侧 S2 摘要可用性（M4 任务 6 剩余项）

> 记录日期：2026-11-13 ｜ 对应任务：docs/ROADMAP.md M4 任务 6（S1 部分见 [评测-云端vs端侧S1.md](评测-云端vs端侧S1.md)）
> 机器生成原始报告：[eval-s2-cloud-vs-local.md](eval-s2-cloud-vs-local.md)（每次跑评测都会覆盖）
> 评测代码：`app/src/test/java/com/notifyme/android/S2EvalHttpTest.kt`（HTTP 侧）｜度量口径：`app/src/main/java/com/notifyme/android/S2Usability.kt`

## 1. 为什么要做这个评测

S1 只决定「这条要不要管」，真正给出摘要、截止时间、建议动作与待办分派的是 S2。S2 也有两条实现路径：

- **云端 S2**：`AnalysisWorker.runS2OpenAi` → `postChatCompletions`（openai 兼容协议）
- **端侧 S2**：`AnalysisWorker.runS2Local` → `LocalLlmEngines.forModel(MODEL_S2)`（真机是 MiniCPM5-2B）

两条路径共用同一份 prompt 构造（`AnalysisCase.buildS2SystemPrompt`）与同一份解析（`AnalysisParsing.parseS2Output`）。
所以这里量的不是代码路径差异，而是**换模型以后「输出还能不能用」**。

S1 那轮用的是「判对率」（相对人工标注）；S2 不一样 —— 摘要写得好不好没有唯一答案。
所以这里换一把尺子：**六项可机判的客观判据全过才算「可用」**，措辞好坏不进判据，留给人看原始输出。

## 2. 评测口径（六项判据）

判据实现只有一份，在 `S2Usability.checks(...)`；改判据等于改口径，必须同步改 `S2UsabilityTest`（34 条）。

| 判据（常量） | 通过条件 | 代码位置 |
|---|---|---|
| `delivered` | HTTP 2xx 且能解析出 JSON、`parseS2Output` 不抛 | `S2EvalHttpTest.callOne` |
| `no_placeholder` | `out.placeholderEcho == false`（没有逐字照抄 prompt 里的字段说明） | `AnalysisParsing.S2_SKELETON_*` + `S2Usability` |
| `summary` | 非空、不等于「窗口末条原文兜底」、码点 ≤ 20 | `S2Usability.fallbackSummary` / `MAX_SUMMARY_CHARS` |
| `due` | 空串仅在 S1 的 `due_window == none` 时通过；非空必须 `DueTimeCore.parse(due, windowEnd, zone)` 成功、不早于 windowEnd − 60s、且落在**与 S1 同一个桶** | `S2Usability.bucketOf` / `PAST_TOLERANCE_MS` |
| `action` | `suggested_action` 非空且码点 ≤ 20 | `MAX_ACTION_CHARS` |
| `tasks` | 每条任务的 `who` / `what` 非空；S1 判 `need_action` 时不允许空数组 | `S2Usability.checks` |

两个容易搞错的口径：

1. **S1 输入用人工标注，不用模型结论。** `labelAsS1` 把标注的 `need_action` 转成 prob 1.0 / 0.0、confidence 0.9，其余字段直接取标注值。目的是把「S2 输出可不可用」和「S1 判得准不准」解耦 —— 后者已由 S1 那份评测单独量化。真实链路里 S1 误判会让 S2 跟着歪，那是 S1 准确率的后果，不是 S2 的能力问题。
2. **due 判「同桶」而不是判「等于标注」。** 摘要层允许模型把「明天上午十点」写成「2026-11-14 10:00」；桶对了就算对，具体时刻由 `CalendarHelper` 那条提醒链去解释。

## 3. 本次实测结果

### 3.1 端点与环境

| 角色 | 端点 | 模型 | 备注 |
|---|---|---|---|
| 云端 S2 | `http://127.0.0.1:8080/v1` | Qwen3.8-Flash-Next-Q4_0-ROCmFP4-STRIX_LEAN（176B 参数，n_ctx 131072） | llama-server，带 api-key |
| 端侧 S2（替身） | `http://127.0.0.1:8082/v1` | qwen25-0.5b-q4km（469 MB） | llama-server，`--host 127.0.0.1 -c 4096 -ngl 0 --threads 8` |

样本：与 S1 同一批 20 条人工标注**合成**会话（`app/src/test/resources/eval-labels.json`）。
评测是 `S2EvalHttpTest` 单测，跑法与 S1 完全一致（见 §6）。

### 3.2 综合可用性

| 指标 | 云端 S2 | 端侧 S2（替身） | 差值 |
|---|---|---|---|
| 样本数 | 20 | 20 | - |
| 已交付（HTTP + JSON） | 20/20（100.0%） | 20/20（100.0%） | 0.0% |
| **六项全过（可用）** | **14/20（70.0%）** | **3/20（15.0%）** | **-55.0%** |
| 单次调用中位时延 | 7.0s | 0.8s | - |

结论：**端侧替身可用率只有 15%（3/20）**，云端 70%。端侧快约 9 倍，但快得没有意义。

**数字是区间不是定值**：请求已钉 `temperature=0` + `seed=20261113`（见 §6），但同一个端点连跑三轮仍给出 14/20、15/20、14/20（端侧 2/20、2/20、3/20）—— 云端 176B 走 ROCm 推理，本身就不是逐 bit 确定的。所以这里只报「最新一轮 + 三轮区间」，别把某个百分点当成可复现常量：**云端 70~75%、端侧替身 10~15%**。

### 3.3 分项判据通过率（分母 = 已交付条数）

| 判据 | 云端 S2 | 端侧 S2 | 差值 |
|---|---|---|---|
| 已交付 | 100.0% (20) | 100.0% (20) | 0.0% |
| 非占位照抄 | 100.0% (20) | 100.0% (20) | 0.0% |
| 摘要非空且非原文兜底 | 95.0% (19) | 95.0% (19) | 0.0% |
| due_time 可解析且与 S1 同桶 | 75.0% (15) | **25.0% (5)** | -50.0% |
| 建议动作合规 | 100.0% (20) | 80.0% (16) | -20.0% |
| 分派条目完整 | 100.0% (20) | **55.0% (11)** | -45.0% |

读法：

- **两边都不照抄骨架**（100% / 100%）。端侧 0.5B 虽然小，但没到逐字复读 prompt 说明的程度 —— 它的失败是「内容不成立」而不是「格式跑飞」。摘要与动作两项本轮端侧已追平到 95% / 80%，拖后腿的只剩 due（25%）与 tasks（55%）。
- **端侧崩得最彻底的是 due 与 tasks**。due 25% 的两种机制在探针里都看得到：一是把「当前时间」抄进 `due_time`（见 §5 原始样例），二是不输出 `due_time` 字段 —— 而 S1 判有截止，空串直接判不通过。tasks 55% 则是给空数组或把骨架里的「责任人 / 要做的事」当值填回去。
- **云端唯一反复失分项也是 due**（75%，5 条挂）。挂的样本集中在「产品群 / 妈妈 / 快递 / 行政 / 业主群 / 客户」这类 S1 桶是 `today` 或 `this_week` 的场景 —— 云端倾向把 `this_week` 具体化成某一天，桶就落到 `tomorrow`/`later`。这条对产品有直接含义：**只要用户改了截止时间，就不能拿 S1 的桶去校验 S2**。
- **摘要项两边都是 95% 而不是 100%**：各有 1 条输出的摘要恰好等于「窗口末条原文截 20 字」，被 `summary` 判据拦掉 —— 那是模型偷懒直接回抄原文。
- **非占位照抄与建议动作在端侧「看起来还行」是判据偏宽松的结果**：端侧常输出 `who` / `what` / `when` 三个不在契约里的顶层键、`suggested_action` 写成一句通顺但与任务无关的话，这两项都放过。真正决定可用性的是 due 与 tasks。

### 3.4 逐样本（节选）

完整 20 条见 [eval-s2-cloud-vs-local.md](eval-s2-cloud-vs-local.md) §三。状态串顺序：已交付 · 非占位照抄 · 摘要 · due · 动作 · 分派。

| 会话 | 云端 | 端侧 |
|---|---|---|
| 老板（派活，明天） | ✓✓✓✓✓✓ | ✓✓✓✗✓✗ |
| 产品群（群 @，今天） | ✓✓✓✗✓✓ | ✓✓✓✗✗✗ |
| 张三（私聊，今天） | ✓✓✓✗✓✓ | ✓✓✓✓✗✓ |
| 客户（投诉，今天） | ✓✓✓✗✓✓ | ✓✓✓✗✓✗ |
| 妈妈（家务，本周） | ✓✓✓✗✓✓ | ✓✓✓✓✓✓ |
| 快递（取件，明天） | ✓✓✓✗✓✓ | ✓✓✓✓✓✓ |
| 行政（报备，无截止） | ✓✓✓✓✓✓ | ✓✓✗✗✗✗ |
| 系统通知（无待办） | ✓✓✓✓✓✓ | ✓✓✓✗✓✓ |
| 银行（账单，本周） | ✓✓✓✓✓✓ | ✓✓✓✓✓✓ |
| 业主群（群，本周） | ✓✓✗✓✓✓ | ✓✓✓✗✓✗ |
| 项目群（群，本周） | ✓✓✓✓✓✓ | ✓✓✓✗✗✗ |

端侧这一轮 20 条里只有 **3 条**六项全过（妈妈、快递、银行）。注意它们**都不是「本来就没有待办」的会话** —— 恰恰相反，「无待办」的那几条（系统通知 / 优惠通知）反而挂在 `due` 上：S1 判 `none`、端侧却硬给了一个时间。

也就是说端侧的失效不是「能不能处理空待办」，而是**只要要它给出时间点，它就会把当前时刻或一个瞎猜的时间填进去**；`tasks` 则经常整段丢掉。

## 4. 限制（看数字前必读）

1. **端侧是替身，不是真机端侧。** 真机 S2 走 MiniCPM5-2B（约 1.5 GB，带 chat 模板与结构化约束），本机没有这个 gguf，用 qwen2.5-0.5b 顶替。0.5B 与 2B 差 4 倍参数，**端侧的 10~15% 只能当下限**，不能当成真机结论。
2. **样本是合成会话**，不是真机通知采集。作用是回归比对（同一批样本能复现同一组数字），不是产品级可用率。
3. **20 条样本量小**，单项差 1 条就是 5 个百分点，只看趋势不要看小数。
4. **判据只看客观事实**（S1 桶、窗口末条时间、字段长度、必填非空），不看摘要写得好不好。措辞质量请直接看报告 §五 的原始输出。
5. **占位照抄在评测里不抛异常**：生产链路 `rejectPlaceholderEcho` 会抛 `IllegalStateException` 回退 S1 结论；评测里保留输出并记一条 `no_placeholder` 失败，才能统计「模型跑飞」的比例。这条差异是刻意的。
6. **连跑三轮数字不完全相同，这是端点特性不是判据抖动**：`S2EvalHttpTest.chat()` 从本轮起显式带 `temperature=0` 与 `seed=20261113`，三轮实测仍是 14/15/14 与 2/2/3。云端 176B 走 ROCm、端侧 0.5B 走 llama-server，都不保证逐 bit 复现。读这份报告时把数字当区间（云端 70~75%、端侧替身 10~15%），不要跨轮次比较单个百分点。

## 5. 原始输出样例（两端对比）

同一条「老板派活」窗口（`[11-13 09:02] 老板: 明天上午十点开会，材料你准备一下` / `[11-13 09:05] 我: 好的`）：

```json
// 云端 8080（8.6s）—— 摘要与分派都读得出任务，但 due_time 落成 2025-11-11（早于窗口末条）
{"summary": "老板要求准备明天会议材料", "due_time": "2025-11-11 10:00",
 "suggested_action": "今晚准备会议材料",
 "tasks": [{"who": "我", "what": "准备会议材料", "when": "2025-11-11 10:00前"}]}

// 端侧 8082（0.8s）—— 摘要退化成「会话名 + 最近消息」，due 抄当前时间，tasks 空
{"summary": "老板最近消息", "who": "老板", "what": "最近消息", "when": "2025-11-10 16:01",
 "suggested_action": "给对方发送开会通知", "tasks": []}
```

两端各自的失效点一次看全：

- **云端**：`due_time` 给了 `2025-11-11 10:00`，早于窗口末条 → `due` 判据直接挂；但 `summary` / `suggested_action` / `tasks` 三处都读得出「明天开会、材料你准备」，是能被用户直接用的那种输出。云端 20 条里挂掉的 5 条 due **全部是这一类**：模型把 `today` / `this_week` 具体化成某一天，落错桶。
- **端侧**：① `summary` 退化成「老板最近消息」，没有概括；② `due_time` 不给或抄「当前时间」，桶必然对不上；③ `tasks` 给空数组，而 S1 判 need_action；④ 还多了 `who` / `what` / `when` 三个**不在 prompt 契约里的顶层键**（解析器忽略，但说明它没在看 schema）。
- 端侧 `no_placeholder` 仍 100% 过：它不逐字复读骨架说明，只是把结构忘掉。

> **一处口径缺口**：`no_placeholder` 判的是「与骨架常量**完全相等**」。端侧输出「给用户的建议动作」而骨架常量是「给用户的建议动作，≤20字」时不完全相等 → 判据放过它。这里刻意不收紧：改成 contains 会误伤正常输出里恰好出现的「责任人」这类词。代价是这一项对「截断式照抄」不敏感，是否收紧留作后续决策 —— 改了就要重跑 40 次调用并改 34 条单测。

机器报告 §五 现在给的是**未经截断的完整响应体**（含 `usage` / `timings`），便于复核解析有没有把 JSON 读歪；本轮之前它对样例调了 `esc()`（`take(120)`），落盘只剩半截 JSON，那是报告侧的缺陷，已修。

## 6. 怎么复现本次评测

配置写在 `app/build/eval/eval.properties`（`app/build/` 被 `.gitignore` 忽略，**端点与 key 不入库**）。
S1 与 S2 共用同一份配置文件，S2 额外多一个 `OUT_S2`：

```properties
CLOUD_URL=http://127.0.0.1:8080/v1
CLOUD_KEY=<8080 的 api-key>
CLOUD_MODEL=<云端模型文件名>
LOCAL_URL=http://127.0.0.1:8082/v1
LOCAL_KEY=
LOCAL_MODEL=qwen25-0.5b-q4km.gguf
OUT=../docs/eval-s1-cloud-vs-local.md
OUT_S2=../docs/eval-s2-cloud-vs-local.md    # 必须是纯 ASCII 文件名，见下
LABEL_PATH=src/test/resources/eval-labels.json
LIMIT=20
CALL_TIMEOUT_MS=120000      # 单次调用上限 120s（S2 输出 4096 tokens，比 S1 慢得多）
TOTAL_BUDGET_MS=1500000     # 整轮预算 25min
```

**为什么 `OUT_S2` 必须纯 ASCII**：本轮第一次跑时写的是 `../docs/评测-云端vs端侧S2.md`，报告落盘成了 `è¯æµ-äºç¯vsç¯ä¾§S2.md` —— Windows 下 `java.io.File` 用平台编码往返路径，UTF-8 中文名被 latin-1 化。机器生成报告一律用 ASCII 名，中文解说文档（这份）手写。

**时间预算**：复用 `S1EvalBudgetCore`（单次上限 / 整轮预算 / 耗尽判定，纯函数、14 条单测）。S2 的 `max_tokens=4096`（S1 是 512），单条明显更慢，所以本轮把单次上限从 90s 提到 120s、整轮预算从 15min 提到 25min。预算耗尽就停止发起新调用，剩余样本记进报告「四之二」，**不会**让整轮构建无限等。

跑法（Gradle 必须绕过增量与缓存，否则测试会被判 UP-TO-DATE，评测根本不执行）：

```powershell
Remove-Item -Recurse -Force app\build\test-results\testOpenDebugUnitTest -ErrorAction SilentlyContinue
.\gradlew.bat --no-build-cache testOpenDebugUnitTest --tests 'com.notifyme.android.S2EvalHttpTest' --console=plain
```

本轮实测耗时 **约 2~3 分钟**（20 条 × 2 侧 = 40 次调用，云端中位 7.0s / 端侧 0.8s）。
报告会覆盖写 `OUT_S2` 指向的文件。

## 7. 怎么拿到真机端侧数字

真机 S2 走 `LocalLlmEngines.forModel(context, LocalModelStore.MODEL_S2)`，与生产 `runS2Local` 同一条代码路径。

**采集器已实现**（2026-11-20）：`app/src/main/java/com/notifyme/android/S2EvalRunner.kt`（采集内核）
+ `app/src/debug/java/com/notifyme/android/S2EvalActivity.kt`（debug 入口），
与 S1 的 `S1EvalRunner` / `S1EvalActivity` 同一套分法。

### 7.1 一条命令（主路径）

```powershell
powershell -ExecutionPolicy Bypass -File docs/scripts/run_s2_device_eval.ps1 --serial <设备序列号>
```

脚本把整条链路串起来：**装包 → push 样本 → 起采集 → 等 → pull 报告 → 打印结论摘要**。

| 步骤 | 做什么 | 失败时报什么 |
|---|---|---|
| 1 装包 | `adb install -r -d` 装 debug APK（`--skip-install` 跳过；`-r` 保留数据，端侧模型不会被清掉） | APK 不存在 / 签名不一致（设备上是别的 key） |
| 2 查入口 | `cmd package resolve-activity` 确认 `S2EvalActivity` 在（**只有 debug 包有**） | 「装的包里没有采集入口」 |
| 3 push 样本 | 推到 **App 内部目录**（`/data/local/tmp` → `run-as cp`），**不**走 `/sdcard/Android/data/...` | 体积与本地不一致直接报错 |
| 4 起采集 | `am start ... --es dataset --es out --ei limit --el budget_ms --el timeout_ms` | `am start` 报 Error（Activity 不存在 / 设备锁屏） |
| 5 等报告 | 轮询设备上的报告文件（**先删旧报告**，免得拉到上一轮的结果） | 超时 → 有 `.partial` 就拉半成品，退出码 2 |
| 6 pull + 摘要 | `run-as cat` 读回，打印口径行 / 综合可用性表 / 未交付明细 / 预算耗尽节 | 退出码 3 |

参数：

| 参数 | 默认 | 含义 |
|---|---|---|
| `--serial <S>` | 自动（只有一台在线设备时） | 目标设备序列号（`adb devices` 里那一列） |
| `--apk <路径>` | `app/build/outputs/apk/open/debug/notifyme-open.apk` | 要装的 debug APK |
| `--labels <路径>` | `app/src/test/resources/eval-labels.json` | 标注样本（与 HTTP 侧同一份） |
| `--out <路径>` | `docs/s2-usability-device.md` | 报告拉到本地的位置 |
| `--limit <N>` | `20` | 采前 N 条样本（`0` = 不限） |
| `--budget-ms <N>` | `1500000`（25min） | 整轮预算；`S1EvalBudgetCore` 上限 **60min**，超了静默夹到 60min |
| `--timeout-ms <N>` | `120000`（120s） | 单次调用上限；上限 **10min**，超了静默夹到 10min |
| `--skip-install` | 关 | 不装包（设备上已经有带采集入口的 debug 包时用） |
| `--skip-push` | 关 | 不推样本（设备上已经有 `eval-labels.json` 时用） |
| `--package <包名>` | `com.notifyme.android` | beta flavor 用 `com.notifyme.android.test` |
| `--adb <路径>` | 自动找 SDK 里的 `platform-tools/adb.exe` | adb 可执行文件 |
| `--wait-seconds <N>` | 整轮预算 + 300 | 等报告的最长秒数 |
| `--legacy-sdcard` | 关 | 样本/报告改走 `/sdcard/Android/data/<pkg>/files`（老系统兜底，新系统上 App 可能读不到） |

退出码：`0` 成功并拿到报告 / `1` 用法或环境错误 / `2` 采集没跑完（只落了 `.partial`）/ `3` 设备上没产出报告。
脚本**不含任何 token / key**，也不打印它们：云端槽位在 App 的「分析设置」里配，脚本只管端侧链路。
`--help` 打印完整用法。

### 7.1.1 手敲三步（备选，脚本跑不了时用）

```powershell
# 0) 装 debug 包（采集入口只在 debug 源集里）
adb -s <serial> install -r -d app/build/outputs/apk/open/debug/notifyme-open.apk

# 1) 把标注样本推进 App 的**内部目录**（不是 /sdcard/Android/data/...，见 7.1.2 第 1 条）
adb -s <serial> push app/src/test/resources/eval-labels.json /data/local/tmp/eval-labels.json
adb -s <serial> shell "run-as com.notifyme.android cp /data/local/tmp/eval-labels.json files/eval-labels.json"

# 2) 起采集（budget_ms / timeout_ms 必须用 --el，见 7.1.2 第 3 条）
adb -s <serial> shell am start -n com.notifyme.android/.S2EvalActivity `
    --es dataset /data/user/0/com.notifyme.android/files/eval-labels.json `
    --es out     /data/user/0/com.notifyme.android/files/s2-usability.md `
    --ei limit 6 --el budget_ms 3600000 --el timeout_ms 600000

# 3) 取报告（内部目录要先删旧文件，否则拉到的是上一轮的）
adb -s <serial> shell "run-as com.notifyme.android rm -f files/s2-usability.md"
adb -s <serial> exec-out run-as com.notifyme.android cat files/s2-usability.md > docs/s2-usability-device.md
```

> **路径里的包名按 flavor 变**：open flavor 是 `com.notifyme.android`，
> **beta flavor 带 `.test` 后缀**（`com.notifyme.android.test`）——
> 装 beta 时上面的包名与目录都要跟着改（脚本用 `--package` 传）。

### 7.1.2 五个实测坑（先踩过再说）

1. **样本不能放 `/sdcard/Android/data/<pkg>/files/`**：那个目录是 adb（shell 用户）建的，
   权限 `0770 shell:ext_data_rw`，**App 进程（另一个 uid）读不到** —— Android 11+ 上实测
   `Permission denied`，采集器只会报「样本不存在」，很容易被误判成「采集器坏了」。
   走 App 的**内部目录**（`/data/user/0/<pkg>/files`，`adb push` 到 `/data/local/tmp` 再
   `run-as cp`），报告也从内部目录 `run-as cat` 读回。
2. **样本要 push 在「装好之后」**：卸载会清掉 `/sdcard/Android/data/<pkg>/files/` 下的文件。
   顺序：**装 → push → 起采集**（脚本已保证）。
3. **`budget_ms` / `timeout_ms` 必须用 `--el`（long extra），不能用 `--ei`**：
   `am start --ei` 放的是 Integer，而 `S2EvalActivity` 用 `getLongExtra` 取；
   Android 14+ 的 `Bundle` 不再把 Integer 强转 Long，取不到就**回默认值**。
   真机实测：传 `--ei budget_ms 900000 --ei timeout_ms 600000`，报告头仍写
   「单次上限 120s · 整轮预算 25min」—— 改参数不生效，且**看不出来**。
   （`--ei limit` 是 int extra，`getIntExtra` 取得到，不用改。）
4. **采集期间 App 自己的周期分析会跟采集器抢同一个 llama context**：
   端侧引擎是**进程级单例**（`LocalLlmEngines`），两边同时 prefill 会把 KV cache 的位置写乱，
   `llama_decode` 直接报 `inconsistent sequence positions` → 采集条目全部
   「prefill 解码失败」、0 交付，App 进程还会崩（真机实测，见 7.5）。
   现在两边已用 `AnalysisWorker.ANALYSIS_MUTEX` 串行化，采集 Activity 还会在采集期间
   **暂停周期分析并在结束后恢复**（只影响 debug 包，生产链路零影响）。
   手动跑时仍建议先在 App 设置里关掉周期分析与悬浮窗。
5. **没就绪 ≠ 模型不行**：报告头要分三态读 ——
   **没就绪**（模型没下完 / 设备不达准入）、「**跑了但全挂**」（引擎就绪但推理全失败，
   报告头会写真实原因如「prefill 解码失败」）、「**交付了**」。
   前两种下面那列 0% 都读作「**未测**」，不是「2B 不行」。

### 7.2 前置条件

1. **端侧 S2 模型已下载**（MiniCPM5-2B，`LocalModelStore.MODEL_S2 = "minicpm5_2b"`，
   来源 modelscope `OpenBMB/MiniCPM5-2B-GGUF`，约 1.5 GB）。
2. **云端槽位已配置**（App 的分析设置里配好 URL / key / 模型）。没配就只采端侧一侧，
   云端那列全是「未交付」—— 端侧真机数字照样拿得到。
3. 云端从设备访问本机服务端需要端口转发 —— **端口要用你实际端点的那个**
   （本评测记录的云端端点是 `127.0.0.1:8080`，而 App 自己的服务端默认 8000）：
   ```powershell
   adb reverse tcp:8080 tcp:8080      # 云端评测端点
   adb reverse tcp:8000 tcp:8000      # App 自建服务端（如果你用 App 自己的槽位）
   ```
   App 里填的 URL 要与 `adb reverse` 的那一侧一致，否则 25 分钟白跑。
4. **设备要过端侧准入**：本地推理要求 arm64-v8a 且总内存 ≥ 8 GB
   （`LocalDeviceCapabilities`）。不满足时端侧引擎直接不可用 —— 那一列会全是「未交付」。
   小米 17（25098PN5AC / Android 17 / arm64-v8a / MemTotal 15446992 kB ≈ 14.7 GiB）满足。
5. **要能从 adb 进 App 的内部目录**：`run-as` 只对 **debuggable** 包可用，
   所以设备上必须是 debug 包（采集入口也只在 debug 源集里，两件事是一件事）。
6. **adb 本身要是能跑的**：本机 `C:\mumu_adb\adb.exe` 缺依赖 DLL，直接执行会以
   `STATUS_DLL_NOT_FOUND`（退出码 -1073741515）失败，在 `adb devices` 里表现为
   「设备全没了」—— 很容易被误判成「设备没连」。用 SDK 里的
   `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe`，或 `--adb` 指定。

### 7.3 与 HTTP 侧的口径一致性（两份评测必须能逐项对比）

| 口径 | 唯一出处 |
|---|---|
| S1 输入（人工标注当 S1 结论） | `S2Usability.labelAsS1`（HTTP 侧与真机侧共用） |
| 六项判据 | `S2Usability.checks`（生产与评测同源） |
| 报告排版（逐样本状态串 / 分项表 / 未交付明细 / 预算耗尽节） | `S2EvalReport.render`（HTTP 侧与真机侧共用） |
| 时间预算 | `S1EvalBudgetCore`（S1/S2 共用） |

报告渲染原本只有 HTTP 侧一份（写在 test 源集里），真机侧用不了；抄一份必然漂开，
所以提到 `app/src/main/java/com/notifyme/android/S2EvalReport.kt`，
两边只差「头部说明」与「限制那几条」——那几条本来就该不同（替身版说 0.5B，真机版说 2B）。

### 7.4 未交付与预算

未交付明细**按侧 + 按原因归并**（不是一行一条）：20 条全因同一个原因失败时，
报告写「端侧 · 老板、朋友、…（共 20 条）：模型尚未下载完成：MiniCPM5-2B（GGUF Q4_K_M）」。
云端与端侧**分开列** —— 混在一张列表里会让人以为「两端都挂了」。

端侧模型未就绪会抛 `LocalEngineException`，该样本按「未交付」记入报告 §四，
**不进分项通过率的分母**（那几格的分母是「已交付条数」）。

⚠️ **但 headline「六项全过（可用）」那格的分母是样本数** —— 所以某一侧一条都没交付时，
那一格会显示 `0/20（0.0%）`，**读作「未测」，不是「0% 可用」**。两件事要分清：
「没跑起来」与「跑了但全挂」。

整轮预算由 `--el budget_ms` / `--el timeout_ms` 控制（默认 25min / 120s），耗尽即停，
剩余条数写进报告 §四之二，同样不进分项通过率的分母。

#### 真机上单条要多久（小米 17 实测）

| 阶段 | 实测 | 说明 |
|---|---|---|
| prefill | 499 token ≈ **21 s**（约 24 tok/s） | prompt 长度随样本变（2 条消息 ≈ 500 token，10 条消息 ≈ 700 token） |
| decode | 最多 `n_ctx(4096) − prompt` ≈ 3597 token ≈ **550 s**（约 6.5 tok/s） | **模型不吐 EOG**，一路生成到上下文边界才停（`llama_jni.cpp` 的 `gen_limit` 钳制） |
| **单条合计** | **≈ 9.7 分钟** | 约 1/3 花在 prefill，2/3 花在跑飞的 decode |

**所以默认参数在这个设备上是错的**：

- 默认 `--timeout-ms 120000`（120s）**小于单条实测耗时**。`withTimeout` 取消不了阻塞的
  native 调用，超时是在 JNI 调用**返回之后**才抛 —— 结果照样被丢掉，
  每条都会被记成「端侧推理超时」→ **0 交付**，报告读作「未测」，
  但模型其实跑完了（真机上最容易被误读的一种失败）。
- 默认 `--budget-ms 1500000`（25min）只够跑 **2~3 条**。

**建议值**：

```powershell
--timeout-ms 600000   # 10min（`S1EvalBudgetCore.MAX_CALL_TIMEOUT_MS` 的上限）—— 必须大于单条实测耗时
--budget-ms  3600000  # 60min（`MAX_TOTAL_BUDGET_MS` 的上限）
```

注意 **20 条全量需要 ≈ 3.3 小时**，而整轮预算被 `S1EvalBudgetCore` 硬夹在 60min ——
**一轮跑不满 20 条**。两条路：① 分批跑（4 轮 × 5 条，每轮 `--limit` 取不同的样本偏移，
注意 `takeCases` 取的是**前 N 条**，所以分批要靠改样本文件或加 `--offset`）；
② 调 `S1EvalBudgetCore.MAX_TOTAL_BUDGET_MS`（会影响 S1/S2 HTTP 评测的兜底上限，改前想清楚）。

### 7.5 真机端侧的限制与已知风险

1. **端侧采样没钉**：`LlamaCppEngine` 默认 `temperature = 0.3`、无 seed（与生产同路径），
   实际生成上限 = `n_ctx(4096) − prompt 占用`（≈3597），**不是 4096**。
   端侧数字两次跑会不同，按区间读；云端那一侧钉了 `temperature=0 + seed`。
2. **端侧单次调用没有硬超时**：native 推理是阻塞 JNI 调用，协程取消不了它；
   报告里「单次上限」对端侧这一列是尽力而为。
3. **并发会写坏 KV cache**：采集与 App 自己的周期分析共用同一个 llama context，
   两边同时 prefill → `llama_decode` 报 `inconsistent sequence positions`
   （真机实测：20 条全「prefill 解码失败」、0 交付，App 进程还崩了）。
   现在两边已用 `AnalysisWorker.ANALYSIS_MUTEX` 串行化，采集期间还会暂停周期分析。
4. **内存**：模型 1.5 GB（mmap）+ KV cache ≈ 168 MiB
   （42 层 × 2 个 KV 头 × 128 维 × 2 字节 × 4096 token）+ 计算缓冲 263 MiB
   → 进程 RSS 实测 **2.6~2.8 GB**。12 GB 机器够用，但模型常驻时容易被低内存 killer 盯上 ——
   采集器每跑完一条就落一份 `.partial` 报告，被杀时至少留半成品。
5. **native 侧日志默认看不见**：`llama_log_set(nullptr, nullptr)` 让 llama.cpp 的日志
   走 stderr，Android 上进不了 logcat，native 报错只剩一句「prefill 解码失败」。
   现在 native 侧把 ggml 日志转到了 logcat（tag `notifyme-llama`），
   排查时用 `adb logcat -d -s notifyme-llama`。

### 7.6 真机数字怎么读

报告第一节是三列并排的表：

```
| 指标 | 云端 S2 | 端侧 S2 | 两侧差值 |
| 样本数 | 20 | 20 | - |
| 已交付（HTTP + JSON） | 18/20（90.0%） | 15/20（75.0%） | -15.0% |
| 六项全过（可用） | 12/20（60.0%） | 6/20（30.0%） | -30.0% |
| 单次调用中位时延 | 7.0s | 580.0s | - |
```

- **「端侧 S2」列的「六项全过（可用）」那一格，就是「端侧 2B 的可用率」** ——
  这才是 M4 要回答的问题；「云端 S2」列是**对照**，不是本轮的主角。
  两列的模型不同（真机 MiniCPM5-2B vs 云端槽位里的模型），**不要直接相减看差值**。
- **「已交付」那格是分母为「已交付条数」的分项通过率的分子**；
  「六项全过」那格的分母是**样本数**。
- **端侧一栏是 0 交付 / 0% 时，先看报告头**：
  - 头写「**本轮 0 交付**：模型尚未下载完成 / 设备架构内存不达准入」→ **没跑起来**，读「未测」；
  - 头写「引擎**已就绪**，但**本轮 0 交付**：prefill 解码失败」→ **跑了但全挂**，读「未测」，
    原因看 §四「未交付明细」；
  - 头写「**已交付 N 条**」→ 这 N 条是真实交付，分母才是样本数。
  三种都**不是**「2B 不行」。
- **「四之二、预算耗尽未测试」那节里的样本没进任何分母** —— 预算不够时
  分母会小于样本数，读的时候把两件事分开：「跑了的 N 条里可用率多少」和「样本一共 M 条」。
- 端侧两次跑数字会不同（温度 0.3、无 seed、生成上限被上下文钳制），
  **按区间读**，别拿两次的差当回归。

## 8. 相关文件

| 文件 | 作用 |
|---|---|
| `app/src/main/java/com/notifyme/android/S2Usability.kt` | 六项判据 + 分项统计 + Markdown（纯函数，368 行） |
| `app/src/test/java/com/notifyme/android/S2UsabilityTest.kt` | 判据口径单测（34 条） |
| `app/src/test/java/com/notifyme/android/S2EvalHttpTest.kt` | HTTP 侧评测入口（无配置文件时自动跳过） |
| `app/src/main/java/com/notifyme/android/AnalysisParsing.kt` | `parseS2Output` / `rejectPlaceholderEcho`；摘要兜底已委托 `S2Usability.fallbackSummary` |
| `app/src/main/java/com/notifyme/android/S1EvalBudgetCore.kt` | 时间预算内核（S1/S2 共用） |
| `app/src/test/resources/eval-labels.json` | 20 条人工标注合成样本（S1/S2 共用） |
| `docs/eval-s2-cloud-vs-local.md` | 机器生成的原始报告（分项 + 逐样本 + 原始输出） |
| `app/src/main/java/com/notifyme/android/S2EvalRunner.kt` | 真机端侧采集内核（走 `LocalLlmEngines`，与生产 `runS2Local` 同一条路径） |
| `app/src/main/java/com/notifyme/android/S2EvalReport.kt` | 报告渲染（HTTP 侧与真机侧共用，保证两列数字可逐项对比） |
| `app/src/debug/java/com/notifyme/android/S2EvalActivity.kt` | 真机采集入口（debug 源集，`adb shell am start` 触发；采集期间自动暂停 App 周期分析） |
| `docs/scripts/run_s2_device_eval.ps1` | 真机采集「一条命令」脚本（装包 → push → 起采集 → 等 → pull → 摘要） |
| `app/src/test/java/com/notifyme/android/S2EvalReportTest.kt` | 采集口径单测（12 条：S1 输入同源 / 报告同构 / 未交付不进分母） |