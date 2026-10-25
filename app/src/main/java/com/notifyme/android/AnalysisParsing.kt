// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import android.util.Log
import org.json.JSONObject

/**
 * S1 / S2 模型输出的防御式解析（M1 从 [AnalysisWorker] 抽出）。
 *
 * 为什么抽：这几个函数本身是纯逻辑（字符串 / JSONObject 进，归一化结果出），
 * 但原来是 `AnalysisWorker`（CoroutineWorker 子类）的 private 方法，单测要碰它们
 * 就得先造 Context + WorkerParameters（等于引入 Robolectric）。抽成 internal object 后，
 * 「模型输出形态不稳定」这条最容易回归的路径可以用普通 JUnit 覆盖。
 *
 * 口径与原实现逐条一致（搬移，不重写）：
 *  - 字段可能在顶层，也可能包一层 `{"answer": {...}}`，两种都接；
 *  - 字段缺失 / 类型不符一律兜底默认值，绝不抛异常打断分析流程；
 *  - 数值 coerce 回合法区间（prob、confidence 0-1；importance 0-9）；
 *  - S2 摘要缺失时兜底窗口末条原文（换行压成空格后截 20 字）。
 *
 * 唯一一处加固：摘要兜底改用 `lastOrNull()`——空窗口不再抛 NoSuchElementException。
 * 生产路径不会出现空窗口（[AnalysisCase.fromMessages] 对空列表返回 null），
 * 但单测会刻意构造，且崩在这里毫无意义。
 */
internal object AnalysisParsing {

    /** 与 [AnalysisWorker] 保持同一个 tag，既有 logcat 过滤命令不用改。 */
    private const val TAG = "AnalysisWorker"

    /**
     * S2 输出（防御式解析后的归一化形态）。
     * [placeholderEcho]=true 表示模型把 system prompt 里 JSON 骨架的字段说明
     * 原样抄回（小模型跑飞时的典型失败，M24），调用方必须视为 S2 无效、回退 S1。
     */
    data class S2Output(
        val summary: String,
        val dueTime: String,
        val suggestedAction: String,
        val tasks: List<AnalysisCaseRecord.Task>,
        val rawJson: String,
        val placeholderEcho: Boolean = false
    )

    /** S1 判定字段读取（openai / 本地共用）：字段缺失、类型不符一律兜底默认值。 */
    fun parseS1Result(
        payload: JSONObject?,
        channel: String
    ): AnalysisCase.Companion.S1Result {
        // 整体解析不出 JSON：不能兜底成「高置信度无需行动」把垃圾输出定案。
        // 置信度置 0.0，让升级闸（confidence < 0.5）把它交给 S2 深分析端或云端重判。
        if (payload == null) {
            return AnalysisCase.Companion.S1Result(
                needActionProb = 0.0,
                importance = 0.0,
                dueWindow = "none",
                topic = "notice",
                confidence = 0.0
            )
        }
        var prob = 0.0
        var importance = 0.0
        var dueWindow = "none"
        var topic = "notice"
        var confidence = 1.0
        try {
            // 模型输出形态不稳定：字段可能在顶层，也可能包一层 {"answer": {...}}，两种都接
            val answer = payload?.optJSONObject("answer") ?: payload
            if (answer != null) {
                prob = answer.optDouble("need_action_prob", 0.0).coerceIn(0.0, 1.0)
                importance = answer.optDouble("importance", 0.0).coerceIn(0.0, 9.0)
                dueWindow = answer.optString("due_window", "none").ifEmpty { "none" }
                topic = answer.optString("topic", "notice").ifEmpty { "notice" }
                confidence = answer.optDouble("confidence", 1.0).coerceIn(0.0, 1.0)
            }
        } catch (e: Exception) {
            Log.w(TAG, "S1($channel) 响应解析失败，已保留 raw_json", e)
        }
        return AnalysisCase.Companion.S1Result(
            needActionProb = prob,
            importance = importance,
            dueWindow = dueWindow,
            topic = topic,
            confidence = confidence
        )
    }

    /** S2 输出字段读取（openai / 本地共用）；摘要缺失时兜底窗口末条原文。 */
    fun parseS2Output(
        payload: JSONObject?,
        kase: AnalysisCase,
        raw: String
    ): S2Output {
        var summary = ""
        var dueTime = ""
        var suggestedAction = ""
        val tasks = mutableListOf<AnalysisCaseRecord.Task>()
        try {
            // 与 S1 同款防御：字段可能包一层 {"answer": {...}}
            val answer = payload?.optJSONObject("answer") ?: payload
            if (answer != null) {
                summary = answer.optString("summary", "").trim()
                dueTime = answer.optString("due_time", "").trim()
                suggestedAction = answer.optString("suggested_action", "").trim()
                answer.optJSONArray("tasks")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        arr.optJSONObject(i)?.let {
                            tasks.add(AnalysisCaseRecord.Task.fromJson(it))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "S2 响应解析失败，已保留 raw_json", e)
        }
        // M24：占位照抄检测。MiniCPM2B 等小模型在边界条件下会把 system prompt 里
        // JSON 骨架的字段说明原样填回（真机连续两轮逐字一致），这种「结果」与对话
        // 内容无关，必须判无效让上层回退 S1，不能当成功升级落库。指纹只比对骨架原文。
        val placeholderEcho = summary == S2_SKELETON_SUMMARY ||
            dueTime.contains(S2_SKELETON_DUE_HINT) ||
            suggestedAction == S2_SKELETON_ACTION ||
            tasks.any {
                it.who.trim() == S2_SKELETON_WHO ||
                    it.what.trim() == S2_SKELETON_WHAT ||
                    it.`when`.trim() == S2_SKELETON_WHEN
            }
        if (placeholderEcho) {
            Log.w(TAG, "S2 输出照抄 prompt 字段说明（占位文案），判定无效，回退 S1")
        }

        // 摘要兜底：模型没给时本地截取窗口末条原文
        if (summary.isEmpty()) {
            summary = kase.messages.lastOrNull()?.text.orEmpty()
                .replace("\n", " ")
                .take(20)
        }

        return S2Output(summary, dueTime, suggestedAction, tasks, raw, placeholderEcho)
    }

    // S2 prompt 骨架里的字段说明原文（与 AnalysisCase.buildS2SystemPrompt 保持一致）。
    // 若改动骨架文案，这几处指纹必须同步，否则照抄检测会漏。
    private const val S2_SKELETON_SUMMARY = "≤20字的会话摘要"
    private const val S2_SKELETON_DUE_HINT = "最晚完成时间的简短中文描述"
    private const val S2_SKELETON_ACTION = "给用户的建议动作，≤20字"
    private const val S2_SKELETON_WHO = "责任人"
    private const val S2_SKELETON_WHAT = "要做的事"
    private const val S2_SKELETON_WHEN = "时间要求，无则空字符串"

    /**
     * 占位照抄视为 S2 失败：抛 [IllegalStateException]，由调用方既有的
     * 「S2 失败不拖垮 case」catch 接住、保留 S1 结论（escalated 保持 false）。
     * 异常消息带原文前 300 字，方便 logcat 排查模型跑飞形态。
     */
    fun rejectPlaceholderEcho(out: S2Output) {
        if (out.placeholderEcho) {
            throw IllegalStateException(
                "S2 输出照抄字段说明（占位文案），判定无效，保留 S1 结论；原文: ${out.rawJson.take(300)}"
            )
        }
    }

    /**
     * 从 chat/completions 响应里取 choices[0].message.content，
     * 再走 [extractJsonFromText]；任何一步失败返回 null。
     */
    fun extractJsonPayload(raw: String): JSONObject? {
        return try {
            val content = JSONObject(raw)
                .optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content").orEmpty()
            extractJsonFromText(content)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 清洗推理模型（MiniCPM5 等）输出中的思考标签，避免草稿 JSON 干扰提取。
     * 真机实测：2B 常在 maxTokens 截断时不闭合
     */
    fun extractJsonFromText(content: String): JSONObject? {
        return try {
            val start = content.indexOf('{')
            val end = content.lastIndexOf('}')
            if (start >= 0 && end > start) {
                JSONObject(content.substring(start, end + 1))
            } else null
        } catch (e: Exception) {
            null
        }
    }
}
