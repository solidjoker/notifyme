// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 后端分析 Worker（case 版）：「逐会话窗口分析」。
 *
 * 流程（对齐 JEV case 体系，见 AnalysisCase 注释）：
 *  1. 开关关闭 / 当前预设槽位 URL 为空 -> 直接 success；
 *  2. 遍历重点关注会话（空名单=全部关注），每个会话取最近 10 条组 case；
 *  3. 按 caseId 去重：窗口没变（无新消息）就跳过；
 *  4. S1 判定：当前预设槽位——laya 协议发 systemone 题集（noul/choice/score），
 *     openai 协议（glm/custom）用对话模型模拟判定题（prompt 要求只输出 JSON 判定）；
 *  5. S1→S2 自动升级：need_action 概率 ≥0.5 / importance ≥6 / 置信度 <0.5 任一命中，
 *     用 openai 槽位（test 包 GLM）对同一窗口做 S2 深分析，
 *     S1 判定结论注入 S2 prompt；openai 槽位不可用时跳过 S2（escalated=false）；
 *  6. 落库（case 级记录）；needAction 且提醒开启 -> 日历事件（dedupKey=caseId）；
 *  7. 单轮最多 10 个会话，会话间隔 500ms；网络/HTTP 失败记结果并 retry。
 *
 * 支持强制分析单个会话（inputData KEY_FORCE_CONVERSATION，会话详情页
 * 「立即分析本会话」用）：跳过 caseId 去重，只分析该会话。
 */
class AnalysisWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "AnalysisWorker"

        /** fork 层微决策日志专用 tag（session log 风格，便于 grep） */
        private const val TAG_FORK = "AgentTree"

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /** inputData 键：强制分析指定会话（跳过窗口去重） */
        const val KEY_FORCE_PKG = "force_pkg"
        const val KEY_FORCE_CONVERSATION = "force_conversation"

        /** 单轮最多分析会话数 */
        private const val MAX_CONVERSATIONS_PER_ROUND = 10

        /** 会话间节流间隔 */
        private const val REQUEST_INTERVAL_MS = 500L

        /** connect 15s，read 60s（LLM 推理可能较慢） */
        private val client: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

        /**
         * 进程内分析互斥锁：周期任务与「立即分析」可能并发执行，
         * 若不串行化，两个 Worker 会各自以空 caseId 集起步、把同一批会话
         * 重复分析并重复写入 analysis.jsonl。
         * 用协程 Mutex 而非 synchronized（临界区内有挂起点，synchronized 不允许）。
         */
        private val ANALYSIS_MUTEX = Mutex()
    }

    override suspend fun doWork(): Result {
        val config = AnalysisConfig(applicationContext)
        if (!config.enabled) return Result.success()

        // System 1 两条路径：本地 MiniCPM4-0.5B（端侧引擎）或 laya 服务（本地/自建地址）
        val s1Local = config.s1Type == AnalysisConfig.S1_TYPE_LOCAL_MODEL
        val slot = config.s1Slot()
        // 容忍用户填到 /v1 为止的地址（如 https://api.typesafe.ai/v1）：统一去掉再拼路径
        val baseUrl = slot.url.trim().trimEnd('/').removeSuffix("/v1")
        if (s1Local) {
            // 端侧 S1：模型未下载完 / 引擎未编译都属用户态，明确文案、不重试
            if (!LocalModelStore.isReady(applicationContext, LocalModelStore.MODEL_S1)) {
                recordResult(config, "失败：本地模型未就绪，请先在控制台下载")
                return Result.success()
            }
            val engine = LocalLlmEngines.forModel(applicationContext, LocalModelStore.MODEL_S1)
            if (!engine.isReady) {
                recordResult(config, "失败：" + engine.unavailableReason)
                return Result.success()
            }
        } else if (baseUrl.isEmpty()) {
            recordResult(config, "失败：未配置分析服务地址")
            return Result.success() // 配置未完成属用户态，重试无意义
        }

        // System 2 本地模式的跳过原因（拼进最终状态文案；null = 云端路径或已就绪）
        val s2LocalSkip = if (config.s2Enabled &&
            config.s2Provider == AnalysisConfig.S2_PROVIDER_LOCAL
        ) {
            when {
                !LocalModelStore.isReady(applicationContext, LocalModelStore.MODEL_S2) ->
                    "System 2 本地模型未就绪，已跳过"
                !LocalLlmEngines.forModel(
                    applicationContext, LocalModelStore.MODEL_S2
                ).isReady -> "System 2 本地引擎未就绪，已跳过"
                else -> null
            }
        } else null

        val forceConversation = inputData.getString(KEY_FORCE_CONVERSATION)
            ?.takeIf { it.isNotBlank() }
        val forceKey = forceConversation?.let {
            ConvKey(
                inputData.getString(KEY_FORCE_PKG)
                    ?.takeIf { p -> p.isNotBlank() }
                    ?: AppSourceRegistry.PKG_WECHAT,
                it
            )
        }
        // 串行化整个分析过程；后进的 Worker 拿到锁时会重新计算待分析集合，
        // 此时前者的结果已落盘，caseId 过滤自然跳过，不会重复分析。
        return ANALYSIS_MUTEX.withLock {
            doWorkExclusive(config, slot, baseUrl, forceKey, s2LocalSkip)
        }
    }

    private suspend fun doWorkExclusive(
        config: AnalysisConfig,
        slot: AnalysisConfig.SlotConfig,
        baseUrl: String,
        forceKey: ConvKey?,
        s2LocalSkip: String? = null
    ): Result {

        // 按 (pkg, 会话名) 分组 -> 组 case -> 过滤重点关注 -> caseId 去重。
        // M2 起必须按 ConvKey 分组：只按会话名会把不同 App 里的同名会话拼成一个 case。
        val analyzedIds = AnalysisStore.loadCaseIds(applicationContext)
        val byConversation = MessageStore.readRecent(applicationContext, 500)
            .groupBy { it.convKey }

        val cases = byConversation
            .asSequence()
            .filter { (key, _) ->
                forceKey == null || key == forceKey
            }
            .filter { (key, _) -> WatchlistStore.isWatched(applicationContext, key) }
            .mapNotNull { (key, list) -> AnalysisCase.fromMessages(key.pkg, key.conversation, list) }
            // 强制模式跳过去重（用户显式要求重分析）；否则窗口没变就跳过
            .filter { forceKey == null || it.caseId !in analyzedIds }
            .sortedBy { it.windowEnd } // 先旧后新
            .take(MAX_CONVERSATIONS_PER_ROUND)
            .toList()

        if (cases.isEmpty()) {
            recordResult(
                config,
                if (forceKey != null) "成功：本会话无新消息可分析"
                else "成功：无待分析会话"
            )
            return Result.success()
        }

        var done = 0
        var escalatedCount = 0
        var filteredCount = 0
        for ((index, kase) in cases.withIndex()) {
            try {
                // ---- fork 1：廉价本地启发式预筛（零网络成本） ----
                val pre = ForkPrefilter.evaluate(kase)
                val preFork = AnalysisCaseRecord.ForkRecord(
                    fork = "prefilter",
                    prob = pre.prob,
                    verdict = if (pre.sharp) "sharp" else "split",
                    note = pre.note
                )
                if (pre.sharp) {
                    // 就地结案：记录 case（标记 filtered）但不调 laya/S2——
                    // caseId 随之落库进已分析集合，窗口不变不会重复评估。
                    Log.i(
                        TAG_FORK,
                        "fork prefilter p=%.2f sharp → settle | %s | %s"
                            .format(pre.prob, kase.conversation, pre.note)
                    )
                    AnalysisStore.append(applicationContext, filteredRecord(kase, preFork))
                    filteredCount++
                } else {
                    Log.i(
                        TAG_FORK,
                        "fork prefilter p=%.2f split → s1 | %s"
                            .format(pre.prob, kase.conversation)
                    )
                    val record = analyzeOneCase(config, slot, baseUrl, kase, preFork)
                    AnalysisStore.append(applicationContext, record)
                    done++
                    if (record.escalated) escalatedCount++

                    // needAction 且提醒开启 -> 日历事件（dedupKey=caseId，内部双重去重）
                    if (record.s1NeedAction && config.reminderEnabled) {
                        val note = CalendarHelper.createReminder(
                            applicationContext,
                            dedupKey = record.caseId,
                            message = kase.messages.last(),
                            summary = record.s2Summary.ifEmpty {
                                kase.messages.last().text.replace("\n", " ").take(15)
                            },
                            dueTimeText = record.s2DueTime.ifEmpty { record.s1DueWindow },
                            baseTime = record.analyzedAt,
                            leadMinutes = config.reminderLeadMinutes
                        )
                        Log.i(TAG, "提醒处理: $note")
                    }
                }
            } catch (e: IOException) {
                recordResult(config, "失败：网络错误 ${e.message ?: "未知"}（已完成 $done 个会话）")
                Log.w(TAG, "分析失败: 网络错误", e)
                return Result.retry()
            } catch (e: HttpException) {
                val detail = if (e.body.isBlank()) "" else " ${e.body.take(120)}"
                recordResult(config, "失败：HTTP ${e.code}$detail（已完成 $done 个会话）")
                Log.w(TAG, "分析失败: HTTP ${e.code}$detail")
                return Result.retry()
            } catch (e: LocalEngineException) {
                // 本地引擎层失败（未编译/加载失败/内存不足）：用户态，不重试
                recordResult(config, "失败：${e.message}（已完成 $done 个会话）")
                Log.w(TAG, "分析失败: 本地引擎", e)
                return Result.success()
            }

            // 会话间节流，最后一个不必等
            if (index < cases.size - 1) delay(REQUEST_INTERVAL_MS)
        }

        recordResult(
            config,
            "成功：分析 $done 个会话" +
                if (escalatedCount > 0) "（升级深分析 $escalatedCount 个）" else "" +
                if (filteredCount > 0) "（预筛过滤 $filteredCount 个）" else "" +
                (s2LocalSkip?.let { "（$it）" } ?: "")
        )
        Log.i(TAG, "本轮分析完成: $done 个会话, 升级 $escalatedCount 个, 预筛过滤 $filteredCount 个")
        return Result.success()
    }

    /**
     * fork 1 预筛 sharp 的就地结案记录：不调 laya/S2，s1/s2 字段全部兜底，
     * filtered=true + forks 只含 prefilter 一条。protocol 记 "prefilter" 以区分真实 S1 协议。
     */
    private fun filteredRecord(
        kase: AnalysisCase,
        preFork: AnalysisCaseRecord.ForkRecord
    ): AnalysisCaseRecord = AnalysisCaseRecord(
        caseId = kase.caseId,
        conversation = kase.conversation,
        pkg = kase.pkg,
        windowEnd = kase.windowEnd,
        messageCount = kase.messages.size,
        analyzedAt = System.currentTimeMillis(),
        protocol = "prefilter",
        rawJson = "",
        s1NeedAction = false,
        s1NeedActionProb = preFork.prob,
        s1Importance = 0.0,
        s1DueWindow = "none",
        s1Topic = "filtered",
        s1Confidence = 1.0,
        escalated = false,
        s2Summary = "",
        s2DueTime = "",
        s2SuggestedAction = "",
        s2Tasks = emptyList(),
        forks = listOf(preFork),
        filtered = true
    )

    /** 非 2xx 响应用异常上抛，由 doWork 统一记录并 retry；body 摘要用于定位服务端拒绝原因。 */
    private class HttpException(val code: Int, val body: String = "") : Exception("HTTP $code")

    /**
     * 分析一个 case：fork 2 S1 判定 -> fork 3 条件触发 S2 深分析 -> 组装 case 记录。
     * S1：本地模型类型走端侧引擎，其余走 laya 协议服务；
     * S2：本地服务商走端侧引擎，否则走 openai 协议槽位。
     * [prefilterFork] 是 fork 1 的判定记录（split 才会走到这里），并入 forks 轨迹首位。
     */
    private suspend fun analyzeOneCase(
        config: AnalysisConfig,
        slot: AnalysisConfig.SlotConfig,
        baseUrl: String,
        kase: AnalysisCase,
        prefilterFork: AnalysisCaseRecord.ForkRecord
    ): AnalysisCaseRecord {
        val forks = mutableListOf(prefilterFork)
        // 会话级自定义提示词（PromptStore，空=全局默认）：S1 state/S1 prompt/S2 prompt 三处注入
        val background = PromptStore.getPrompt(
            applicationContext, ConvKey(kase.pkg, kase.conversation)
        )
        val s1Local = config.s1Type == AnalysisConfig.S1_TYPE_LOCAL_MODEL

        // ---- fork 2：S1 判定 ----
        val result = if (s1Local) {
            runS1Local(kase, background)
        } else {
            runS1SystemOne(config, slot, baseUrl, kase, background)
        }
        val s1Raw = result.first
        val s1 = result.second
        forks += AnalysisCaseRecord.ForkRecord(
            fork = "s1",
            prob = s1.needActionProb,
            verdict = if (s1.needAction) "split" else "sharp",
            note = s1.toInjectText()
        )
        Log.i(
            TAG_FORK,
            "fork s1 p=%.2f %s | %s | %s"
                .format(s1.needActionProb, if (s1.needAction) "split" else "sharp",
                    kase.conversation, s1.toInjectText())
        )

        // ---- fork 3：S1→S2 自动升级判定 ----
        var escalated = false
        var s2Summary = ""
        var s2DueTime = ""
        var s2SuggestedAction = ""
        var s2Tasks = listOf<AnalysisCaseRecord.Task>()
        var rawJson = s1Raw

        // 升级概率依据：need_action 概率与 importance 归一化取大者（记录用，判定仍走 shouldEscalate）
        val escalateProb = maxOf(s1.needActionProb, s1.importance / 9.0)
        val escalateVerdict = if (s1.shouldEscalate()) "split" else "sharp"
        val escalateNote = "need_action_prob=%.2f(≥0.5升级) importance=%.1f(≥6升级) confidence=%.2f(<0.5升级)"
            .format(s1.needActionProb, s1.importance, s1.confidence)
        forks += AnalysisCaseRecord.ForkRecord(
            fork = "escalate",
            prob = escalateProb,
            verdict = escalateVerdict,
            note = escalateNote
        )
        Log.i(
            TAG_FORK,
            "fork escalate p=%.2f %s → %s | %s"
                .format(escalateProb, escalateVerdict,
                    if (escalateVerdict == "split") "s2" else "settle", kase.conversation)
        )

        if (s1.shouldEscalate()) {
            if (config.s2Enabled &&
                config.s2Provider == AnalysisConfig.S2_PROVIDER_LOCAL
            ) {
                // S2 本地端侧引擎（模型/引擎未就绪已在 doWork 预检并写入状态文案）
                try {
                    val s2 = runS2Local(kase, s1, background)
                    escalated = true
                    s2Summary = s2.summary
                    s2DueTime = s2.dueTime
                    s2SuggestedAction = s2.suggestedAction
                    s2Tasks = s2.tasks
                    rawJson = s1Raw + "\n--- s2(local) ---\n" + s2.rawJson
                    Log.i(TAG, "S1->S2 本地升级: ${kase.conversation} (${s1.toInjectText()})")
                } catch (e: Exception) {
                    // S2 失败不拖垮整个 case：保留 S1 结论，标记未升级
                    Log.w(TAG, "S2 本地深分析失败，保留 S1 结论: ${e.message}")
                }
            } else {
                // S2 走云端 openai 协议槽位（开关关闭或未配置则跳过，只出 S1）
                val s2Slot = config.s2Slot()
                if (s2Slot != null) {
                    try {
                        val s2 = runS2OpenAi(s2Slot, kase, s1, background)
                        escalated = true
                        s2Summary = s2.summary
                        s2DueTime = s2.dueTime
                        s2SuggestedAction = s2.suggestedAction
                        s2Tasks = s2.tasks
                        rawJson = s1Raw + "\n--- s2 ---\n" + s2.rawJson
                        Log.i(TAG, "S1->S2 升级: ${kase.conversation} (${s1.toInjectText()})")
                    } catch (e: Exception) {
                        // S2 失败不拖垮整个 case：保留 S1 结论，标记未升级
                        Log.w(TAG, "S2 深分析失败，保留 S1 结论: ${e.message}")
                    }
                } else {
                    Log.i(TAG, "满足升级条件但 System 2 未启用或未配置，跳过 S2: ${kase.conversation}")
                }
            }
        }

        return AnalysisCaseRecord(
            caseId = kase.caseId,
            conversation = kase.conversation,
            pkg = kase.pkg,
            windowEnd = kase.windowEnd,
            messageCount = kase.messages.size,
            analyzedAt = System.currentTimeMillis(),
            protocol = if (s1Local) AnalysisConfig.PROTOCOL_LOCAL else AnalysisConfig.PROTOCOL_LAYA,
            rawJson = rawJson,
            s1NeedAction = s1.needAction,
            s1NeedActionProb = s1.needActionProb,
            s1Importance = s1.importance,
            s1DueWindow = s1.dueWindow,
            s1Topic = s1.topic,
            s1Confidence = s1.confidence,
            escalated = escalated,
            s2Summary = s2Summary,
            s2DueTime = s2DueTime,
            s2SuggestedAction = s2SuggestedAction,
            s2Tasks = s2Tasks,
            forks = forks
        )
    }

    // ---------------- S1：systemone 协议（laya / JEV） ----------------

    /**
     * S1 判定（systemone 协议）：state 用 JEV build_state 结构，
     * 题集一次批量发出（fan-out），防御式解析各题答案。
     *
     * background（会话级自定义提示词）挂进 state.background（对齐 JEV v1.3）；
     * 旧版服务端对未知字段可能 4xx——按 jev_case_research.md §2 的防御模式
     * 降级重试一次不带 background 的请求，保证兼容不崩。
     */
    private fun runS1SystemOne(
        config: AnalysisConfig,
        slot: AnalysisConfig.SlotConfig,
        baseUrl: String,
        kase: AnalysisCase,
        background: String = ""
    ): Pair<String, AnalysisCase.Companion.S1Result> {

        fun postWithState(stateJson: JSONObject): String {
            // 不带 stream 字段：线上 api.typesafe.ai 严格校验会 400（Invalid request），
            // laya 本地端 stream 有默认值 false，缺省行为一致
            val requestJson = JSONObject().apply {
                put("state", stateJson)
                put("questions", AnalysisCase.buildS1QuestionsJson())
                put("model", slot.model)
            }
            val request = Request.Builder()
                .url("$baseUrl/v1/systemone")
                .post(requestJson.toString().toRequestBody(JSON_MEDIA_TYPE))
                .apply {
                    // 鉴权优先 nginx Basic（公网入口）；否则槽位密钥按 Bearer 带
                    // （JEV api.typesafe.ai 直连）；都为空则不带（本地 adb reverse）
                    if (config.basicUser.isNotEmpty()) {
                        header("Authorization", Credentials.basic(config.basicUser, config.basicPass))
                    } else if (slot.key.isNotEmpty()) {
                        header("Authorization", "Bearer ${slot.key}")
                    }
                }
                .build()
            return client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw HttpException(
                    response.code, response.body?.string().orEmpty()
                )
                response.body?.string().orEmpty()
            }
        }

        val raw = try {
            postWithState(kase.buildStateJson(background))
        } catch (e: HttpException) {
            if (background.isNotBlank() && e.code in 400..499) {
                Log.w(TAG, "服务端拒收 background 字段（HTTP ${e.code}），降级重试不带 background")
                postWithState(kase.buildStateJson())
            } else {
                throw e
            }
        }

        // 防御式解析：{"answers":{"need_action":{"noul":0.94,"confidence":...},
        //   "importance":{"score":..., 或 choice 概率档}, "due_window":{"choice":...},
        //   "topic":{"choice":...}}}，字段缺失/类型不符不崩溃。
        val obj = try {
            JSONObject(raw)
        } catch (e: Exception) {
            JSONObject()
        }
        val answers = obj.optJSONObject("answers") ?: JSONObject()

        val needActionProb = answers.optJSONObject("need_action")
            ?.optDouble("noul", 0.0) ?: 0.0

        // importance 是 0-9 的 score 题：score 归一化(0-1)时乘 9 还原档位
        val importanceRaw = answers.optJSONObject("importance")
            ?.optDouble("score", 0.0) ?: 0.0
        val importance = if (importanceRaw <= 1.0) importanceRaw * 9.0
        else importanceRaw.coerceAtMost(9.0)

        val dueWindow = answers.optJSONObject("due_window")
            ?.optString("choice", "none").orEmpty().ifEmpty { "none" }
        val topic = answers.optJSONObject("topic")
            ?.optString("choice", "notice").orEmpty().ifEmpty { "notice" }

        // 整体置信度：各题 confidence 取最小（最没把握的题目决定整体可信度）
        var confidence = 1.0
        val keys = answers.keys()
        var hasConfidence = false
        while (keys.hasNext()) {
            val answer = answers.optJSONObject(keys.next()) ?: continue
            if (answer.has("confidence")) {
                confidence = minOf(confidence, answer.optDouble("confidence", 1.0))
                hasConfidence = true
            }
        }
        if (!hasConfidence) confidence = 1.0 // 后端不给置信度时不误触发升级

        return raw to AnalysisCase.Companion.S1Result(
            needActionProb = needActionProb,
            importance = importance,
            dueWindow = dueWindow,
            topic = topic,
            confidence = confidence
        )
    }

    // ---------------- S1：openai 协议（glm/custom 槽位模拟判定题） ----------------

    /**
     * S1 判定（openai 协议）：对话模型按 S1_OPENAI_SYSTEM_PROMPT 只输出
     * JSON 判定，防御式截取第一个 { 到最后一个 } 解析。
     */
    private fun runS1OpenAi(
        slot: AnalysisConfig.SlotConfig,
        kase: AnalysisCase,
        background: String = ""
    ): Pair<String, AnalysisCase.Companion.S1Result> {
        val raw = postChatCompletions(
            slot,
            AnalysisCase.buildS1OpenAiSystemPrompt(background),
            "会话「${kase.conversation}」最近消息：\n${kase.windowText()}"
        )
        return raw to AnalysisParsing.parseS1Result(AnalysisParsing.extractJsonPayload(raw), "openai")
    }

    /**
     * S1 判定（本地端侧引擎）：同一套 system prompt 与解析，仅把
     * HTTP chat/completions 换成 LocalLlmEngine.chat（引擎直接返回文本，
     * 无需剥 choices 包装）。引擎未就绪抛 LocalEngineException 由上层接住。
     */
    private suspend fun runS1Local(
        kase: AnalysisCase,
        background: String = ""
    ): Pair<String, AnalysisCase.Companion.S1Result> {
        val engine = LocalLlmEngines.forModel(applicationContext, LocalModelStore.MODEL_S1)
        val raw = engine.chat(
            AnalysisCase.buildS1OpenAiSystemPrompt(background),
            "会话「${kase.conversation}」最近消息：\n${kase.windowText()}",
            1024
        )
        return raw to AnalysisParsing.parseS1Result(AnalysisParsing.extractJsonFromText(raw), "local")
    }

    // ---------------- S2：深分析（openai 槽位） ----------------

    /**
     * S2 深分析：同一会话窗口 + S1 判定结论注入 prompt，
     * 输出严格 JSON {summary, due_time, suggested_action, tasks[]}。
     */
    private fun runS2OpenAi(
        slot: AnalysisConfig.SlotConfig,
        kase: AnalysisCase,
        s1: AnalysisCase.Companion.S1Result,
        background: String = ""
    ): AnalysisParsing.S2Output {
        val raw = postChatCompletions(
            slot,
            AnalysisCase.buildS2SystemPrompt(s1, background),
            "会话「${kase.conversation}」最近消息：\n${kase.windowText()}"
        )
        return AnalysisParsing.parseS2Output(AnalysisParsing.extractJsonPayload(raw), kase, raw)
    }

    /**
     * S2 深分析（本地端侧引擎）：同一套 prompt 与解析，HTTP 换成
     * LocalLlmEngine.chat。引擎未就绪抛 LocalEngineException 由上层接住。
     */
    private suspend fun runS2Local(
        kase: AnalysisCase,
        s1: AnalysisCase.Companion.S1Result,
        background: String = ""
    ): AnalysisParsing.S2Output {
        val engine = LocalLlmEngines.forModel(applicationContext, LocalModelStore.MODEL_S2)
        val raw = engine.chat(
            AnalysisCase.buildS2SystemPrompt(s1, background),
            "会话「${kase.conversation}」最近消息：\n${kase.windowText()}",
            1024
        )
        return AnalysisParsing.parseS2Output(AnalysisParsing.extractJsonFromText(raw), kase, raw)
    }

    // ---------------- 公共 HTTP / 解析工具 ----------------

    /** POST {url}/chat/completions，返回响应原文；非 2xx 抛 HttpException。 */
    private fun postChatCompletions(
        slot: AnalysisConfig.SlotConfig,
        systemPrompt: String,
        userContent: String
    ): String {
        // URL 归一：用户填到 /v4（或任意前缀）为止就自动补 /chat/completions
        var url = slot.url.trim().trimEnd('/')
        if (!url.endsWith("/chat/completions")) url += "/chat/completions"

        val requestJson = JSONObject().apply {
            put("model", slot.model)
            put("messages", org.json.JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", systemPrompt)
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", userContent)
                })
            })
            // GLM 支持 json_object 约束；不支持的服务端会忽略或报错（失败走 retry 记录）
            put("response_format", JSONObject().put("type", "json_object"))
            put("max_tokens", 1024)
            put("stream", false)
        }

        val request = Request.Builder()
            .url(url)
            .post(requestJson.toString().toRequestBody(JSON_MEDIA_TYPE))
            .apply {
                if (slot.key.isNotEmpty()) {
                    header("Authorization", "Bearer ${slot.key}")
                }
            }
            .build()

        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw HttpException(
                response.code, response.body?.string().orEmpty()
            )
            response.body?.string().orEmpty()
        }
    }

    private fun recordResult(config: AnalysisConfig, result: String) {
        config.lastAnalysisTime = System.currentTimeMillis()
        config.lastAnalysisResult = result
    }
}
