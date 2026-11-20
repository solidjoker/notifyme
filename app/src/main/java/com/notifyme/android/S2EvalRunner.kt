// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

    /** S2 的 max_tokens 与生产 runS2OpenAi 同口径（4096；S1 才是 512）。 */
    private const val S2_MAX_TOKENS = 4096

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
        val plan: S1EvalBudgetCore.Plan
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
        onProgress: ((done: Int, total: Int, conv: String) -> Unit)? = null
    ): Report = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val all = S1Accuracy.parse(datasetPath.readText())
        val cases = if (limit > 0 && all.size > limit) all.take(limit) else all

        val outcomes = ArrayList<Outcome>(cases.size)
        val cloudRaw = LinkedHashMap<String, String>()
        val localRaw = LinkedHashMap<String, String>()
        val skipped = ArrayList<String>()
        val startedAt = System.currentTimeMillis()

        for ((idx, case) in cases.withIndex()) {
            onProgress?.invoke(idx + 1, cases.size, case.conversation)

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
                    cloudRaw[case.caseId] = got.raw
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
                    localText = engine.chat(system, user, S2_MAX_TOKENS, null)
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
                } catch (e: Throwable) {
                    // 端侧模型未就绪 / 推理失败：该样本按「未交付」记入报告，不进分母。
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
            plan = plan
        )
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
                ChatRequestCore.payload(slot.model, systemPrompt, userContent)
                    .toRequestBody(JSON_MEDIA_TYPE)
            )
            .apply { if (slot.key.isNotEmpty()) header("Authorization", "Bearer ${slot.key}") }
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("HTTP " + response.code)
            response.body?.string().orEmpty()
        }
    }
}
