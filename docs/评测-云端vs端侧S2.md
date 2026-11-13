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

真机 S2 走 `LocalLlmEngines.forModel(context, LocalModelStore.MODEL_S2)`，与生产 `runS2Local` 同一条代码路径。两步：

1. 设备上先下好 S2 模型（MiniCPM5-2B，`LocalModelStore.MODEL_S2 = "minicpm5_2b"`，来源 modelscope `OpenBMB/MiniCPM5-2B-GGUF`）。
2. `S2EvalHttpTest` 的 `LOCAL_URL` 换成设备侧暴露的端点，或者按 `S1EvalActivity` 的做法在 debug 源集加一个 S2 采集 Activity（**尚未实现，本轮只留 HTTP 侧**）。

端侧模型未就绪会抛 `LocalEngineException`，该样本按「未交付」记入报告 §四，**不**进任何分母。

## 8. 相关文件

| 文件 | 作用 |
|---|---|
| `app/src/main/java/com/notifyme/android/S2Usability.kt` | 六项判据 + 分项统计 + Markdown（纯函数，349 行） |
| `app/src/test/java/com/notifyme/android/S2UsabilityTest.kt` | 判据口径单测（34 条） |
| `app/src/test/java/com/notifyme/android/S2EvalHttpTest.kt` | HTTP 侧评测入口（无配置文件时自动跳过） |
| `app/src/main/java/com/notifyme/android/AnalysisParsing.kt` | `parseS2Output` / `rejectPlaceholderEcho`；摘要兜底已委托 `S2Usability.fallbackSummary` |
| `app/src/main/java/com/notifyme/android/S1EvalBudgetCore.kt` | 时间预算内核（S1/S2 共用） |
| `app/src/test/resources/eval-labels.json` | 20 条人工标注合成样本（S1/S2 共用） |
| `docs/eval-s2-cloud-vs-local.md` | 机器生成的原始报告（分项 + 逐样本 + 原始输出） |