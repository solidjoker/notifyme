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

    /** S2 输出（防御式解析后的归一化形态） */
    data class S2Output(
        val summary: String,
        val dueTime: String,
        val suggestedAction: String,
        val tasks: List<AnalysisCaseRecord.Task>,
        val rawJson: String
    )

    /** S1 判定字段读取（openai / 本地共用）：字段缺失、类型不符一律兜底默认值。 */
    fun parseS1Result(
        payload: JSONObject?,
        channel: String
    ): AnalysisCase.Companion.S1Result {
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
        // 摘要兜底：模型没给时本地截取窗口末条原文
        if (summary.isEmpty()) {
            summary = kase.messages.lastOrNull()?.text.orEmpty()
                .replace("\n", " ")
                .take(20)
        }

        return S2Output(summary, dueTime, suggestedAction, tasks, raw)
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
     * 从纯文本里截取第一个 `{` 到最后一个 `}` 解析成 JSON 对象；
     * 本地引擎输出（无 choices 包装）与云端 content 共用。失败返回 null。
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
