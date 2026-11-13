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
 * 云端 vs 端侧 S1 判定一致率采集器（ROADMAP M4 任务 6 / docs/ROADMAP.md:237）。
 *
 * 只做一件事：对**同一个会话窗口**分别跑云端 S1 与端侧 S1，把两份判定配成
 * [S1Agreement.Pair]。窗口口径、prompt、解析全部复用生产路径
 * （[AnalysisCase.analysisUserPrompt] / [AnalysisCase.buildS1OpenAiSystemPrompt] /
 * [AnalysisCase.buildS1LocalSystemPrompt] / [AnalysisParsing.parseS1Result]），
 * 所以量出来的就是线上真实差异，而不是「另写一套近似的比较逻辑」。
 *
 * 端侧走 [LocalLlmEngines.forModel]（与 runS1Local 同一条路径）：S1 模型未下载 /
 * 引擎未就绪时抛 [LocalEngineException]，调用方按「样本无效」处理，而不是当成不一致。
 *
 * 需要 Context 与网络，故不放进纯 JVM 单测；纯判定部分（[S1Agreement]）与解析部分
 * （[AnalysisParsing]）已各有单测覆盖。
 */
internal object S1EvalRunner {

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    /** connect 15s / read 120s：端侧 0.5B 解码可能较慢，给足时间。 */
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    /** 滑窗步长：每次把窗口起点往前挪 5 条（窗口 10 条，重叠一半）。 */
    private const val WINDOW_STEP = 5

    /** 单个窗口的采集结果：失败也返回（带错误文本），不让一个坏样本中断整批。 */
    data class Outcome(
        val pkg: String,
        val conversation: String,
        val windowEnd: Long,
        val pair: S1Agreement.Pair?,
        val cloudRaw: String,
        val localRaw: String,
        val error: String?
    )

    /** 一批窗口的采集结果。 */
    data class Report(
        val outcomes: List<Outcome>,
        val pairs: List<S1Agreement.Pair>,
        val summary: S1Agreement.Summary,
        val failures: Int
    ) {
        /** 实际比对的窗口数（失败的不计入）。 */
        val compared: Int get() = pairs.size
    }

    /**
     * @param context 任意 Context（用于加载端侧 S1 模型）
     * @param datasetPath 样本 JSON（[EvalDataset] 格式）
     * @param cloudSlot 云端 S1 槽位（openai 协议）
     * @param windowCount 每个会话切几个窗口；<=0 时只取最近一个
     * @param limit 最多采集多少条窗口（0 = 不限）
     * @param background 会话级背景提示词（两侧一致传入，保证唯一变量是 prompt）
     */
    suspend fun collect(
        context: Context,
        datasetPath: File,
        cloudSlot: AnalysisConfig.SlotConfig,
        windowCount: Int = 1,
        limit: Int = 0,
        background: String = "",
        onProgress: ((done: Int, total: Int, conv: String) -> Unit)? = null
    ): Report = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val parsed = EvalDataset.parse(datasetPath.readText())

        // 先算出全部窗口，便于进度显示与 limit 截断
        val planned = ArrayList<Window>()
        for (conv in parsed.convs) {
            planned.addAll(windowsOf(conv, windowCount))
        }
        val targets = if (limit > 0 && planned.size > limit) planned.take(limit) else planned

        val outcomes = ArrayList<Outcome>(targets.size)
        var done = 0
        for (w in targets) {
            done++
            onProgress?.invoke(done, targets.size, w.conv.conversation)
            var cloudRaw = ""
            var localRaw = ""
            var cloudResult: AnalysisCase.Companion.S1Result? = null
            var localResult: AnalysisCase.Companion.S1Result? = null
            val errors = ArrayList<String>(2)

            try {
                cloudRaw = postChatCompletions(
                    cloudSlot,
                    AnalysisCase.buildS1OpenAiSystemPrompt(background, w.case.windowEnd),
                    w.case.analysisUserPrompt(w.case.windowEnd)
                )
                cloudResult = AnalysisParsing.parseS1Result(
                    AnalysisParsing.extractJsonPayload(cloudRaw), "openai"
                )
            } catch (e: Throwable) {
                errors.add("cloud: " + (e.message ?: e.javaClass.simpleName))
            }

            try {
                val engine = LocalLlmEngines.forModel(appContext, LocalModelStore.MODEL_S1)
                localRaw = engine.chat(
                    AnalysisCase.buildS1LocalSystemPrompt(background, w.case.windowEnd),
                    w.case.analysisUserPrompt(w.case.windowEnd),
                    2048,
                    null
                )
                localResult = AnalysisParsing.parseS1Result(
                    AnalysisParsing.extractJsonFromText(localRaw), "local"
                )
            } catch (e: Throwable) {
                errors.add("local: " + (e.message ?: e.javaClass.simpleName))
            }

            val pair = if (cloudResult != null && localResult != null) {
                S1Agreement.Pair(
                    pkg = w.conv.pkg,
                    conversation = w.conv.conversation,
                    windowEnd = w.case.windowEnd,
                    cloud = cloudResult,
                    local = localResult
                )
            } else null
            outcomes.add(
                Outcome(
                    pkg = w.conv.pkg,
                    conversation = w.conv.conversation,
                    windowEnd = w.case.windowEnd,
                    pair = pair,
                    cloudRaw = cloudRaw,
                    localRaw = localRaw,
                    error = if (errors.isEmpty()) null else errors.joinToString(" | ")
                )
            )
        }
        val pairs = outcomes.mapNotNull { it.pair }
        Report(
            outcomes = outcomes,
            pairs = pairs,
            summary = S1Agreement.of(pairs),
            failures = outcomes.count { it.pair == null }
        )
    }

    /** 一个待采集窗口：所属会话 + 组好的 case。 */
    private data class Window(val conv: ConvNormalize.Conv, val case: AnalysisCase)

    /**
     * 切窗口：取最近一个窗口；windowCount > 1 时按 [WINDOW_STEP] 重叠往前滑。
     */
    private fun windowsOf(conv: ConvNormalize.Conv, windowCount: Int): List<Window> {
        val msgs = conv.messages
        if (msgs.isEmpty()) return emptyList()
        val size = AnalysisCase.WINDOW_SIZE
        val out = ArrayList<Window>()

        if (windowCount <= 1) {
            val window = msgs.takeLast(size)
            if (window.isEmpty()) return emptyList()
            return listOf(Window(conv, caseOf(conv, window)))
        }

        var end = msgs.size
        var guard = 0
        while (end > 0 && out.size < windowCount && guard < 64) {
            guard++
            val start = maxOf(0, end - size)
            val window = msgs.subList(start, end)
            if (window.isNotEmpty()) out.add(Window(conv, caseOf(conv, window)))
            if (start == 0) break
            end = start + WINDOW_STEP
        }
        return out
    }

    private fun caseOf(conv: ConvNormalize.Conv, window: List<ConvNormalize.NormMessage>): AnalysisCase {
        val raw = window.map {
            ChatMessage(
                sender = it.sender,
                text = it.text,
                timestamp = it.timestamp,
                conversation = conv.conversation,
                isGroup = it.isGroup,
                source = "eval",
                pkg = conv.pkg
            )
        }
        // 走生产同一条 fromMessages：caseId / windowEnd / 窗口取尾都在里面
        return AnalysisCase.fromMessages(conv.pkg, conv.conversation, raw)!!
    }

    /** POST {url}/chat/completions；与 AnalysisWorker.postChatCompletions 同口径。 */
    private fun postChatCompletions(
        slot: AnalysisConfig.SlotConfig,
        systemPrompt: String,
        userContent: String
    ): String {
        val request = Request.Builder()
            .url(ChatRequestCore.endpoint(slot.url))
            .post(ChatRequestCore.payload(slot.model, systemPrompt, userContent)
                .toRequestBody(JSON_MEDIA_TYPE))
            .apply { if (slot.key.isNotEmpty()) header("Authorization", "Bearer ${slot.key}") }
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("HTTP " + response.code)
            response.body?.string().orEmpty()
        }
    }
}
