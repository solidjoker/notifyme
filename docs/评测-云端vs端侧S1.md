# 评测：云端 vs 端侧 S1 判定（M4 任务 6）

> 记录日期：2026-11-12 ｜ 对应任务：docs/ROADMAP.md M4 任务 6
> 机器生成原始报告：[eval-s1-cloud-vs-local.md](eval-s1-cloud-vs-local.md)（每次跑评测都会覆盖）
> 评测代码：`app/src/test/java/com/notifyme/android/S1EvalHttpTest.kt`（HTTP 侧）、`app/src/debug/java/com/notifyme/android/S1EvalActivity.kt`（真机端侧侧）

## 1. 为什么要做这个评测

分析链路有两级：S1 快筛（判断「这条要不要管」、重要度、时间窗、话题）与 S2 深分析。
S1 有两条实现路径：

- **云端 S1**：走 openai 兼容协议打到远端槽位（`AnalysisWorker.runS1OpenAi`）
- **端侧 S1**：走本地 GGUF 模型（`AnalysisWorker.runS1Local` → `LocalLlmEngines.forModel(MODEL_S1)`）

两条路径用的是**同一份 prompt 构造与同一份解析**（`AnalysisCase.buildS1OpenAiSystemPrompt` / `buildS1LocalSystemPrompt` + `AnalysisParsing.parseS1Result`），
所以「一致率」量的就是模型替换本身带来的差异，而不是代码路径差异。

## 2. 评测口径（与线上完全一致）

| 项 | 口径 | 代码位置 |
|---|---|---|
| 样本窗口 | `AnalysisCase.fromMessages` 的生产滑窗（窗口 10 条、步长 5） | `AnalysisCase.kt` |
| user 提示词 | `kase.analysisUserPrompt(windowEnd)` | `AnalysisCase.kt` |
| 云端 system | `buildS1OpenAiSystemPrompt(background, windowEnd)` | `AnalysisCase.kt:301-318` |
| 端侧 system | `buildS1LocalSystemPrompt(background, windowEnd)` | `AnalysisCase.kt:340-358` |
| 云端解析 | `parseS1Result(extractJsonPayload(raw), "eval-cloud")` | `AnalysisParsing.kt` |
| 端侧解析 | `parseS1Result(extractJsonFromText(raw), "eval-local")` | `AnalysisParsing.kt` |
| need_action | `need_action_prob >= 0.5` | `AnalysisCase.kt:368` |
| 升级 S2 | `prob >= 0.5` 或 `importance >= 6` 或 `confidence < 0.5` | `AnalysisCase.kt:371-374` |
| importance 容差 | 绝对差 <= 1.5 算判对 | `S1Accuracy.kt:166` |
| due / topic | `trim()` + 忽略大小写后相等 | `S1Accuracy.kt:167` |

## 3. 本次实测结果

### 3.1 端点与环境

| 角色 | 端点 | 模型 | 备注 |
|---|---|---|---|
| 云端 S1 | `http://127.0.0.1:8080/v1` | Qwen3.8-Flash-Next-Q4_0-ROCmFP4-STRIX_LEAN（176B 参数，n_ctx 131072） | llama-server，带 api-key
| 端侧 S1（替身） | `http://127.0.0.1:8082/v1` | qwen25-0.5b-q4km（491,400,032 字节） | llama-server，**base 补全模式**（不加 `--jinja` / 不传 `--chat-template-kwargs`）

样本：20 条人工标注的**合成**会话（非真机采集），见 `app/src/test/resources/eval-labels.json`，
覆盖老板派活、朋友闲聊、群 @、自己发言不构成待办、表情刷屏、广告链接、客户投诉风险、改期、家人叮嘱、快递取件码、报销、验证码、面试改期、账单还款、投票、问候、模糊「有空聊」、甲方催确认、项目群。

### 3.2 单侧准确率（相对人工标注）

| 指标 | 云端 S1 | 端侧 S1（替身） | 差值 |
|---|---|---|---|
| need_action 判对 | 75.0% (15/20) | 40.0% (8/20) | -35.0% |
| importance 判对（±1.5） | 60.0% (12/20) | 30.0% (6/20) | -30.0% |
| due_window 判对 | 80.0% (16/20) | 40.0% (8/20) | -40.0% |
| topic 判对 | 70.0% (14/20) | 30.0% (6/20) | -40.0% |
| 四项全对 | 50.0% (10/20) | 10.0% (2/20) | -40.0% |

### 3.3 两侧一致性（不需标注，纯交叉比对）

| 指标 | 数值 |
|---|---|
| 样本窗口数 | 20 |
| 判定完全一致 | 4 (20.0%) |
| need_action 一致 | 11 (55.0%) |
| 升级 S2 一致 | 11 (55.0%) |
| due_window 一致 | 10 (50.0%) |
| topic 一致 | 6 (30.0%) |
| 云端 need_action 命中 | 9 |
| 端侧 need_action 命中 | 0 |
| need_action_prob 平均绝对差 | 0.430 |
| importance 平均绝对差 | 2.95 |
| 端侧 confidence 均值 | 1.000 |

结论：**不可替代**（两侧差异占多数）。

## 4. 限制（看数字前必读）

1. **端侧是替身，不是真机端侧。** 本次端侧端点跑的是 llama-server 的 **base 补全模式**：不加 `--jinja`、不传 `--chat-template-kwargs`，所以模型收不到 chat 模板，也不遵守「只输出一个 JSON 对象」的指令。实测同一条 prompt 返回的是自由文本（例如「好的，我已经记住了老板的最新消息。明天下午十点……请问有什么其他需求或问题吗？」），个别时候自造 `{"result":"明天上午十点"}` 这种 JSON。这直接导致 20 条里 **0 条 need_action 命中**、importance 恒为 0.0、confidence 恒为 1.000。
2. **因此「端侧 40% / 30%」只能当离线下限用**，不代表真机端侧（走 `LocalLlmEngines` + 原生 chat 模板）的真实水平。要拿真机数字，必须用 §5 的 `S1EvalActivity` 在设备/模拟器上采集。
3. 样本是**合成会话**，不是真机通知采集。它的作用是回归比对（同一批样本能复现同一组数字），不是产品级准确率。
4. 20 条样本量小，单项差 1 条就是 5 个百分点，只看趋势不要看小数。
5. 端侧 0.5B 模型与云端 176B 模型**参数量差 300 倍**，这个对比本身就偏极端；如果要评估「端侧能不能替代云端」，更合理的对照是同级小模型（1.5B–4B）在原生模板下的表现。

## 5. 怎么拿到真机端侧数字

真机路径走 `S1Eval` / `S1EvalRunner`：端侧分支用 `LocalLlmEngines.forModel(context, LocalModelStore.MODEL_S1)`，与生产 `runS1Local` 同一条代码路径，prompt 与解析也复用生产实现。

```powershell
# 0) 真机需已下载 S1 模型（端侧引擎就绪），云端槽位在 App 内已配置
# 1) 准备样本（格式见 EvalDataset.kt；可从真机消息库导出后裁剪）
adb -s <serial> push eval.json /sdcard/Android/data/com.notifyme.android/files/eval.json

# 2) 触发采集（debug 源集；windows=每会话窗口数，limit=总量上限）
adb -s <serial> shell am start -n com.notifyme.android/.S1EvalActivity `
    --es dataset /sdcard/Android/data/com.notifyme.android/files/eval.json `
    --es out /sdcard/Android/data/com.notifyme.android/files/s1-agreement.md `
    --ei windows 2 --ei limit 20

# 3) 拉回报告
adb -s <serial> pull /sdcard/Android/data/com.notifyme.android/files/s1-agreement.md docs/
```

采集过程中端侧模型未就绪（未下载 / 引擎异常）会抛 `LocalEngineException`，该窗口按「样本无效」记入未配对表，**不会**被当成不一致。

## 6. 怎么复现本次 HTTP 评测

配置写在 `app/build/eval/eval.properties`（`app/build/` 被 `.gitignore:6` 忽略，**端点与 key 不入库**）：

```properties
CLOUD_URL=http://127.0.0.1:8080/v1
CLOUD_KEY=<8080 的 api-key>
CLOUD_MODEL=<云端模型文件名>
LOCAL_URL=http://127.0.0.1:8082/v1
LOCAL_KEY=
LOCAL_MODEL=qwen25-0.5b-q4km.gguf
OUT=../docs/eval-s1-cloud-vs-local.md
LABEL_PATH=src/test/resources/eval-labels.json
```

跑法（Gradle 必须绕过增量与缓存，否则测试会被判 UP-TO-DATE，评测根本不执行）：

```powershell
Remove-Item -Recurse -Force app\build\test-results\testOpenDebugUnitTest -ErrorAction SilentlyContinue
.\gradlew.bat --no-build-cache testOpenDebugUnitTest --tests 'com.notifyme.android.S1EvalHttpTest' --console=plain
```

报告会覆盖写 `OUT` 指向的文件，并在 stdout 打印一行摘要。

## 7. 相关文件

| 文件 | 作用 |
|---|---|
| `app/src/test/java/com/notifyme/android/S1EvalHttpTest.kt` | HTTP 侧评测入口（可无设备跑，默认跳过除非有配置文件或 `NOTIFYME_EVAL=1`） |
| `app/src/test/java/com/notifyme/android/S1AccuracyTest.kt` | 准确率统计口径单测（10 条） |
| `app/src/test/java/com/notifyme/android/S1AgreementTest.kt` | 一致率判定口径单测 |
| `app/src/main/java/com/notifyme/android/S1Accuracy.kt` | 标注样本解析 + 单侧准确率统计 + Markdown |
| `app/src/main/java/com/notifyme/android/S1Agreement.kt` | 两侧配对与一致率汇总 |
| `app/src/main/java/com/notifyme/android/S1Eval.kt` / `S1EvalRunner.kt` | 真机采集入口与采集器 |
| `app/src/debug/java/com/notifyme/android/S1EvalActivity.kt` | 真机采集触发 Activity（debug 源集） |
| `app/src/test/resources/eval-labels.json` | 20 条人工标注合成样本 |
| `docs/eval-s1-cloud-vs-local.md` | 机器生成的原始报告（逐样本明细 + 原始输出） |
