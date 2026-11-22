// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 云端 vs **真机端侧** S2 摘要可用性采集器（ROADMAP M4 任务 6 的最后一块）。
 *
 * ## 为什么还要它
 *
 * HTTP 侧评测（`S2EvalHttpTest`）的端侧那一列是用 llama-server 上的
 * qwen2.5-0.5b **替身**顶的 —— 0.5B 与真机的 MiniCPM5-2B 差 4 倍参数，
 * 那 10~15% 的可用率只能读作**下限**（见 `docs/评测-云端vs端侧S2.md` §4 限制 1）。
 * 要给出「端侧 2B 到底行不行」的实测数字，只有一条路：让真机自己跑。
 *
 * ## 与 S1 采集器的关系
 *
 * 结构照抄 [S1EvalRunner]（同一套窗口切法、同一套 prompt 构造、同一套解析），
 * 换的是三处：
 *  1. 模型：S1 用 [LocalModelStore.MODEL_S1]，这里用 [LocalModelStore.MODEL_S2]；
 *  2. 判据：S1 比「判定对不对」，这里用 [S2Usability] 的六项可机判判据；
 *  3. S1 输入：这里用**人工标注**（[S2Usability.labelAsS1]）而不是模型结论，
 *     把「S2 输出可不可用」与「S1 判得准不准」解耦。
 *
 * 端侧走 [LocalLlmEngines.forModel]（与生产 `runS2Local` 同一条路径）：
 * 模型未下载 / 引擎未就绪时抛 [LocalEngineException]，该样本按「未交付」记入报告，
 * **不**进任何分母。
 *
 * ## 为什么放主源码集
 *
 * 采集内核需要 [LocalLlmEngines] / [AnalysisCase] / [AnalysisParsing] / [S2Usability]
 * 这些主源码集的能力；入口 Activity 才是 debug 专属（与 S1 的分法一致）。
 * 纯判定部分由 [S2Usability] 的单测覆盖，本文件只做「跑一遍 + 写报告」。
 */
internal object S2EvalRunner {

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    /** connect 15s / read 180s：真机 2B 解码慢，S2 还要输出 4096 tokens，给足时间。 */
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    /** S2 的 max_tokens 与生产 runS2OpenAi 同口径（用 ChatRequestCore 那一个默认值，别再抄一份）。 */
    private val S2_MAX_TOKENS = ChatRequestCore.MAX_TOKENS

    /**
     * 取前 [limit] 条样本 —— **两侧共用**（HTTP 侧评测与真机采集）。
     *
     * 口径：`limit <= 0` 取全部；否则取前 N 条。
     * 两侧若各写一份，「limit=0」这种边界就会分叉（一边取全部、一边取 1 条），
     * 报告里的样本集就不一样了。
     */
    fun takeCases(all: List<S1Accuracy.Case>, limit: Int): List<S1Accuracy.Case> =
        if (limit > 0 && all.size > limit) all.take(limit) else all

    /** 一条样本两侧各自的采集结果。失败也返回（带错误文本），不让一个坏样本中断整批。 */
    data class Outcome(
        val caseId: String,
        val conversation: String,
        val cloud: S2Usability.Outcome,
        val local: S2Usability.Outcome,
        val cloudRaw: String,
        val localRaw: String
    )

    /** 一批样本的采集结果。 */
    data class Report(
        val cases: List<S1Accuracy.Case>,
        val outcomes: List<Outcome>,
        val cloud: S2Usability.Side,
        val local: S2Usability.Side,
        val rows: List<S2Usability.Row>,
        val cloudRaw: Map<String, String>,
        val localRaw: Map<String, String>,
        val skipped: List<String>,
        val plan: S1EvalBudgetCore.Plan,
        /** 端侧引擎是否就绪（false = 本轮端侧一条都没交付，别把 0% 读成「2B 不行」）。 */
        val localEngineReady: Boolean = false,
        /** 端侧**实际交付**条数（报告头按它写，不按引擎快照 —— 引擎就绪但推理全失败也算 0）。 */
        val localDelivered: Int = 0,
        /** 端侧不可用的原因（模型没下完 / 内存不达准入 / 引擎加载失败…）。 */
        val localUnavailableReason: String = "",
        /** 本轮用的云端槽位（null = 没配，报告里如实写「未配置」）。 */
        val cloudSlot: AnalysisConfig.SlotConfig? = null
    )

    /**
     * @param context 任意 Context（用于加载端侧 S2 模型）
     * @param datasetPath 人工标注样本 JSON（[S1Accuracy] 格式，与 HTTP 侧同一份）
     * @param cloudSlot 云端 S2 槽位（openai 协议）；留空则只采端侧
     * @param limit 最多采集多少条样本（0 = 不限）
     * @param plan 时间预算（单次上限 + 整轮预算，耗尽即停）
     * @param onProgress 进度回调
     */
    suspend fun collect(
        context: Context,
        datasetPath: File,
        cloudSlot: AnalysisConfig.SlotConfig?,
        limit: Int = 0,
        plan: S1EvalBudgetCore.Plan = S1EvalBudgetCore.resolvePlan(null, null),
        onProgress: ((done: Int, total: Int, conv: String) -> Unit)? = null,
        onPartial: ((List<Outcome>) -> Unit)? = null
    ): Report = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val all = S1Accuracy.parse(datasetPath.readText())
        val cases = takeCases(all, limit)

        // 端侧引擎的就绪状态取**一次**：
        // 不可用时 forModel 返回的是快照（不缓存），每条再问一遍会把「模型没下好」
        // 这类原因重复问 N 次。报告头要如实写「已就绪 / 未就绪」——
        // 写死「真机 MiniCPM5-2B」会让人把「设备没就绪」读成「2B 模型不行」。
        val localEngine = LocalLlmEngines.forModel(appContext, LocalModelStore.MODEL_S2)
        val localReady = localEngine.isReady
        val localReason = localEngine.unavailableReason

        val outcomes = ArrayList<Outcome>(cases.size)
        val cloudRaw = LinkedHashMap<String, String>()
        val localRaw = LinkedHashMap<String, String>()
        val skipped = ArrayList<String>()
        val startedAt = System.currentTimeMillis()

        for ((idx, case) in cases.withIndex()) {
            val kase = AnalysisCase.fromMessages(case.pkg, case.conversation, case.messages)
            if (kase == null) {
                val empty = S2Usability.Outcome(
                    caseId = case.caseId,
                    conversation = case.conversation,
                    out = null,
                    error = "窗口为空"
                )
                outcomes.add(
                    Outcome(case.caseId, case.conversation, empty, empty, "", "")
                )
                onProgress?.invoke(idx + 1, cases.size, case.conversation)
                onPartial?.invoke(outcomes.toList())
                continue
            }

            val s1 = S2Usability.labelAsS1(case)
            val system = AnalysisCase.buildS2SystemPrompt(s1, "", kase.windowEnd)
            val user = kase.analysisUserPrompt(kase.windowEnd)

            // ── 云端一侧 ──
            var cloudOut = S2Usability.Outcome(
                caseId = case.caseId,
                conversation = case.conversation,
                out = null,
                error = if (cloudSlot == null) "本轮未配置云端槽位" else null
            )
            var cloudText = ""
            if (cloudSlot != null) {
                val budget = S1EvalBudgetCore.remainingMs(startedAt, System.currentTimeMillis(), plan.totalBudgetMs)
                val timeout = S1EvalBudgetCore.callTimeoutMs(plan, budget)
                if (timeout <= 0L) {
                    skipped.add(case.conversation + " [cloud] 预算耗尽未测试")
                } else {
                    val got = callCloud(cloudSlot, system, user, timeout, kase, case)
                    cloudText = got.raw
                    // 失败时 raw 是空串：不写进 map，否则报告 §五「原始输出样例」会取到空块。
                    if (got.raw.isNotEmpty()) cloudRaw[case.caseId] = got.raw
                    cloudOut = got.outcome
                }
            }

            // ── 真机端侧一侧 ──
            var localOut = S2Usability.Outcome(
                caseId = case.caseId,
                conversation = case.conversation,
                out = null,
                error = null
            )
            var localText = ""
            val budget = S1EvalBudgetCore.remainingMs(startedAt, System.currentTimeMillis(), plan.totalBudgetMs)
            val timeout = S1EvalBudgetCore.callTimeoutMs(plan, budget)
            if (timeout <= 0L) {
                skipped.add(case.conversation + " [local] 预算耗尽未测试")
            } else {
                val t0 = System.currentTimeMillis()
                try {
                    val engine = LocalLlmEngines.forModel(appContext, LocalModelStore.MODEL_S2)
                    // 超时保护：S1EvalBudgetCore 的预算只在**两次调用之间**检查，
                    // 端侧这一支没有它兜着 —— 一条卡死会把整轮拖住。
                    // **注意**：withTimeout 取消不了已在跑的阻塞 native 调用
                    // （LlamaCppEngine 的 chat 是一次不检查取消的 JNI 调用），
                    // 所以这里是「尽量」，不是硬超时 —— 报告 §六 的限制里写明了这一点。
                    //
                    // ★ 必须拿 AnalysisWorker 的分析互斥锁：
                    // 端侧引擎是**进程级单例**（LocalLlmEngines 按 modelId 缓存实例），
                    // 采集与 App 自己的周期分析共用同一个 llama context。两边同时
                    // prefill 会各自写 KV cache 的位置，llama_decode 会报
                    // "inconsistent sequence positions"，整轮采集变成 0 交付。
                    // 同类症状在 progress.md:778 有记录（KV cache 未清 → 第二次起
                    // prefill 解码失败）；那次是另一个成因，但症状同类。
                    //
                    // withTimeout 在 withLock **内层**：超时抛出的时刻晚于 native 调用
                    // 真正结束，所以锁一定是在推理跑完之后才释放 —— 反过来写会在
                    // 推理还在跑时就放锁，并发问题原样回来。
                    localText = AnalysisWorker.ANALYSIS_MUTEX.withLock {
                        withTimeout(timeout) {
                            engine.chat(system, user, S2_MAX_TOKENS, null)
                        }
                    }
                    localRaw[case.caseId] = localText
                    val parsed = AnalysisParsing.parseS2Output(
                        AnalysisParsing.extractJsonFromText(localText), kase, localText
                    )
                    localOut = S2Usability.Outcome(
                        caseId = case.caseId,
                        conversation = case.conversation,
                        out = parsed,
                        elapsedMs = System.currentTimeMillis() - t0
                    )
                } catch (e: TimeoutCancellationException) {
                    // ★ 必须排在 CancellationException 之前：
                    // TimeoutCancellationException 是 CancellationException 的子类，
                    // 被下面那个分支命中就原样抛出 → **整轮采集中止，报告都写不出来**。
                    // 而「真机 2B 单条超 120s」恰恰是常态风险，不能因此报废整轮。
                    localOut = S2Usability.Outcome(
                        caseId = case.caseId,
                        conversation = case.conversation,
                        out = null,
                        elapsedMs = System.currentTimeMillis() - t0,
                        error = "端侧推理超时（${timeout}ms）"
                    )
                } catch (e: CancellationException) {
                    // 真正的协程取消（父作用域被取消）要往上抛：吞掉它会破坏结构化并发。
                    throw e
                } catch (e: Throwable) {
                    // 端侧模型未就绪 / 推理失败 / 超时：该样本按「未交付」记入报告，不进分项通过率的分母。
                    localOut = S2Usability.Outcome(
                        caseId = case.caseId,
                        conversation = case.conversation,
                        out = null,
                        elapsedMs = System.currentTimeMillis() - t0,
                        error = e.message ?: e.javaClass.simpleName
                    )
                }
            }

            outcomes.add(
                Outcome(
                    caseId = case.caseId,
                    conversation = case.conversation,
                    cloud = cloudOut,
                    local = localOut,
                    cloudRaw = cloudText,
                    localRaw = localText
                )
            )
            // 进度在**这条跑完之后**报（带耗时），不是「即将开始」——
            // 真机单条几十秒，只报「开始」看不出哪条卡住。
            onProgress?.invoke(idx + 1, cases.size, case.conversation)
            // 每条结束就把当前结果推出去（调用方据此落 .partial 报告）：
            // 真机跑 25 分钟被系统杀掉时，中间结果不至于全丢。
            onPartial?.invoke(outcomes.toList())

            if (S1EvalBudgetCore.exhausted(startedAt, System.currentTimeMillis(), plan.totalBudgetMs)) {
                for (rest in cases.drop(idx + 1)) {
                    skipped.add(rest.conversation + " 预算耗尽未测试")
                }
                break
            }
        }

        val s1Of: (S1Accuracy.Case) -> AnalysisCase.Companion.S1Result = { S2Usability.labelAsS1(it) }
        val cloudOutcomes = outcomes.associate { it.caseId to it.cloud }
        val localOutcomes = outcomes.associate { it.caseId to it.local }
        Report(
            cases = cases,
            outcomes = outcomes,
            cloud = S2Usability.score("云端", cases, cloudOutcomes, s1Of),
            local = S2Usability.score("端侧", cases, localOutcomes, s1Of),
            rows = S2Usability.rows(cases, cloudOutcomes, localOutcomes, s1Of),
            cloudRaw = cloudRaw,
            localRaw = localRaw,
            skipped = skipped,
            plan = plan,
            localEngineReady = localReady,
            localDelivered = localOutcomes.values.count { it.out != null },
            // 引擎就绪但一条都没交付时，原因要取**真实的失败原因**（如「prefill 解码失败」），
            // 不能拿引擎快照的「未加载」去写报告头 —— 那会把「跑了但全挂」读成「没跑起来」。
            localUnavailableReason = if (localReady) {
                localFailureReason(localOutcomes) ?: localReason
            } else {
                localReason
            },
            cloudSlot = cloudSlot
        )
    }

    /**
     * 端侧未交付的原因归并：取**出现次数最多**的那条（并列时取第一条）。
     * 20 条全挂同一个原因时，报告头与「未交付明细」节写的是同一句话。
     */
    private fun localFailureReason(outcomes: Map<String, S2Usability.Outcome>): String? {
        val reasons = outcomes.values.mapNotNull { it.error?.trim()?.takeIf { r -> r.isNotEmpty() } }
        if (reasons.isEmpty()) return null
        return reasons.groupingBy { it }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .first().key
    }

    /** 云端一次调用：HTTP + 解析，失败也返回 Outcome。 */
    private fun callCloud(
        slot: AnalysisConfig.SlotConfig,
        system: String,
        user: String,
        timeoutMs: Long,
        kase: AnalysisCase,
        case: S1Accuracy.Case
    ): CloudCall {
        val t0 = System.currentTimeMillis()
        return try {
            val raw = postChatCompletions(slot, system, user, timeoutMs)
            val parsed = AnalysisParsing.parseS2Output(
                AnalysisParsing.extractJsonPayload(raw), kase, raw
            )
            CloudCall(
                raw = raw,
                outcome = S2Usability.Outcome(
                    caseId = case.caseId,
                    conversation = case.conversation,
                    out = parsed,
                    elapsedMs = System.currentTimeMillis() - t0
                )
            )
        } catch (e: Throwable) {
            CloudCall(
                raw = "",
                outcome = S2Usability.Outcome(
                    caseId = case.caseId,
                    conversation = case.conversation,
                    out = null,
                    elapsedMs = System.currentTimeMillis() - t0,
                    error = e.message ?: e.javaClass.simpleName
                )
            )
        }
    }

    private data class CloudCall(val raw: String, val outcome: S2Usability.Outcome)

    /** POST {url}/chat/completions；与 AnalysisWorker.postChatCompletions 同口径。 */
    private fun postChatCompletions(
        slot: AnalysisConfig.SlotConfig,
        systemPrompt: String,
        userContent: String,
        timeoutMs: Long
    ): String {
        val request = Request.Builder()
            .url(ChatRequestCore.endpoint(slot.url))
            .post(
                // 请求体走 ChatRequestCore（唯一出处）；采样参数按评测口径钉死 ——
                // 与 HTTP 侧评测（S2EvalHttpTest）用同一对常量，云端两列才可比。
                ChatRequestCore.payload(
                    slot.model,
                    systemPrompt,
                    userContent,
                    S2_MAX_TOKENS,
                    ChatRequestCore.EVAL_TEMPERATURE,
                    ChatRequestCore.EVAL_SEED
                ).toRequestBody(JSON_MEDIA_TYPE)
            )
            .apply { if (slot.key.isNotEmpty()) header("Authorization", "Bearer ${slot.key}") }
            .build()
        // 单次调用上限：评测口径（budget 算出来的剩余时间），比 client 的固定读超时更紧。
        val call = client.newBuilder()
            .readTimeout(timeoutMs.coerceAtLeast(1_000L), TimeUnit.MILLISECONDS)
            .build()
            .newCall(request)
        return call.execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("HTTP " + response.code)
            response.body?.string().orEmpty()
        }
    }
}
