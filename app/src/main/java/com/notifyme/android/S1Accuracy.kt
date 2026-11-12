// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import org.json.JSONArray
import org.json.JSONObject

/**
 * 标注样本的「单侧准确率」度量（ROADMAP M4 任务 6 / docs/ROADMAP.md:237）。
 *
 * 与 [S1Agreement] 的分工：
 *  - [S1Agreement] 回答「云端与端侧彼此有多一致」（两侧互比，不需要标注）；
 *  - 本对象回答「每一侧相对人工标注有多准」（一侧对答案，必须有标注）。
 * 两个都要，才能说明「一致」到底是「都对」还是「都错得一样」。
 *
 * 口径刻意与生产一致，避免另起一套：
 *  - 样本 [Case] 的 caseId / windowEnd 走 [AnalysisCase.caseIdOf]，
 *    窗口取尾走 [AnalysisCase.fromMessages]（调用方负责，见 S1EvalHttpTest）；
 *  - importance 判对允许 ±[IMPORTANCE_TOLERANCE]（0-9 十档，模型给小数是常态，
 *    要求逐档命中会把「6.9 vs 7.0」误判成错）；
 *  - need_action / due_window / topic 必须精确命中（due_window、topic 都是固定选项集）。
 */
internal object S1Accuracy {

    /** 样本格式版本（写进 labels JSON 的 version 字段）。 */
    const val VERSION = 1

    /** importance 判对口径：允许 ±1.5 档。 */
    const val IMPORTANCE_TOLERANCE = 1.5

    /** 人工标注的期望值。 */
    data class Expect(
        val needAction: Boolean,
        val importance: Double,
        val dueWindow: String,
        val topic: String
    )

    /** 一条标注样本：一个会话窗口 + 期望值。 */
    data class Case(
        val pkg: String,
        val conversation: String,
        val isGroup: Boolean,
        val messages: List<ChatMessage>,
        val expect: Expect
    ) {
        /** 窗口结束时间 = 最后一条消息时间（与 fromMessages 同口径）。 */
        val windowEnd: Long get() = messages.lastOrNull()?.timestamp ?: 0L

        /** 与生产同口径的 caseId（用于把判定结果对回样本）。 */
        val caseId: String get() = AnalysisCase.caseIdOf(pkg, conversation, windowEnd)
    }

    /** 一侧（云端或端侧）在标注样本上的命中统计。 */
    data class Side(
        val name: String,
        val total: Int,
        val needActionOk: Int,
        val importanceOk: Int,
        val dueOk: Int,
        val topicOk: Int,
        val exactOk: Int
    ) {
        val needActionRate: Double get() = rate(needActionOk)
        val importanceRate: Double get() = rate(importanceOk)
        val dueRate: Double get() = rate(dueOk)
        val topicRate: Double get() = rate(topicOk)

        /** 四项全对的样本占比（最严格的一档）。 */
        val exactRate: Double get() = rate(exactOk)

        private fun rate(n: Int): Double = if (total == 0) 0.0 else n.toDouble() / total
    }

    /** 逐样本明细（报告里的「翻错样本」表用）。 */
    data class Row(
        val caseId: String,
        val conversation: String,
        val expect: Expect,
        val cloud: AnalysisCase.Companion.S1Result?,
        val local: AnalysisCase.Companion.S1Result?
    )

    /**
     * 解析标注样本 JSON：
     * ```json
     * {"version":1,"cases":[
     *   {"conversation":"老板","pkg":"com.tencent.mm","isGroup":false,
     *    "expect":{"need_action":true,"importance":7,"due_window":"tomorrow","topic":"work_task"},
     *    "messages":[{"sender":"对方","text":"明天上午十点开会","timestamp":1762500000000}]}
     * ]}
     * ```
     *
     * 坏条目跳过而不抛（与 [EvalDataset] 同风格）：样本文件是人手写的，
     * 一条写错不该让整批评测失败。
     */
    fun parse(text: String): List<Case> {
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return emptyList()
        val arr: JSONArray = root.optJSONArray("cases") ?: return emptyList()
        val out = ArrayList<Case>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val conversation = o.optString("conversation").trim()
            if (conversation.isEmpty()) continue
            val rawMessages = o.optJSONArray("messages") ?: continue
            val messages = ArrayList<ChatMessage>(rawMessages.length())
            for (j in 0 until rawMessages.length()) {
                val m = rawMessages.optJSONObject(j) ?: continue
                val ts = m.optLong("timestamp")
                val sender = m.optString("sender").trim()
                val body = m.optString("text")
                if (ts <= 0L || (sender.isEmpty() && body.isBlank())) continue
                messages.add(
                    ChatMessage(
                        sender = sender,
                        text = body,
                        timestamp = ts,
                        conversation = conversation,
                        isGroup = o.optBoolean("isGroup", false),
                        source = "eval",
                        pkg = o.optString("pkg").ifEmpty { AppSourceRegistry.PKG_WECHAT }
                    )
                )
            }
            if (messages.isEmpty()) continue
            val e = o.optJSONObject("expect") ?: continue
            out.add(
                Case(
                    pkg = o.optString("pkg").ifEmpty { AppSourceRegistry.PKG_WECHAT },
                    conversation = conversation,
                    isGroup = o.optBoolean("isGroup", false),
                    messages = messages,
                    expect = Expect(
                        needAction = e.optBoolean("need_action", false),
                        importance = e.optDouble("importance", 0.0),
                        dueWindow = e.optString("due_window").ifEmpty { "none" },
                        topic = e.optString("topic").ifEmpty { "notice" }
                    )
                )
            )
        }
        return out
    }

    /**
     * 统计一侧的命中情况。
     *
     * @param results caseId -> 该侧判定；缺失的样本只计入 total，不算命中
     *                （缺失多半是 HTTP 失败或解析失败，属于「没答」，不是「答错」，
     *                但也不能当答对，故只压分母）
     */
    fun score(
        name: String,
        cases: List<Case>,
        results: Map<String, AnalysisCase.Companion.S1Result>
    ): Side {
        var needActionOk = 0
        var importanceOk = 0
        var dueOk = 0
        var topicOk = 0
        var exactOk = 0
        for (c in cases) {
            val r = results[c.caseId] ?: continue
            val na = r.needAction == c.expect.needAction
            val im = kotlin.math.abs(r.importance - c.expect.importance) <= IMPORTANCE_TOLERANCE
            val due = r.dueWindow.trim().equals(c.expect.dueWindow.trim(), ignoreCase = true)
            val tp = r.topic.trim().equals(c.expect.topic.trim(), ignoreCase = true)
            if (na) needActionOk++
            if (im) importanceOk++
            if (due) dueOk++
            if (tp) topicOk++
            if (na && im && due && tp) exactOk++
        }
        return Side(name, cases.size, needActionOk, importanceOk, dueOk, topicOk, exactOk)
    }

    /** 逐样本明细（两侧判定并排）。 */
    fun rows(
        cases: List<Case>,
        cloud: Map<String, AnalysisCase.Companion.S1Result>,
        local: Map<String, AnalysisCase.Companion.S1Result>
    ): List<Row> = cases.map {
        Row(it.caseId, it.conversation, it.expect, cloud[it.caseId], local[it.caseId])
    }

    private fun pct(v: Double): String = String.format(java.util.Locale.US, "%.1f%%", v * 100)

    /** 准确率表（Markdown）。 */
    fun markdown(cloud: Side, local: Side): String {
        val sb = StringBuilder()
        sb.append("| 指标 | 云端 S1 | 端侧 S1 | 两侧差值 |\n")
        sb.append("|---|---|---|---|\n")
        sb.append("| 样本数 | ").append(cloud.total).append(" | ").append(local.total).append(" | - |\n")
        row(sb, "need_action 判对", cloud.needActionRate, local.needActionRate, cloud.needActionOk, local.needActionOk)
        row(sb, "importance 判对（±" + IMPORTANCE_TOLERANCE + "）", cloud.importanceRate, local.importanceRate, cloud.importanceOk, local.importanceOk)
        row(sb, "due_window 判对", cloud.dueRate, local.dueRate, cloud.dueOk, local.dueOk)
        row(sb, "topic 判对", cloud.topicRate, local.topicRate, cloud.topicOk, local.topicOk)
        row(sb, "四项全对", cloud.exactRate, local.exactRate, cloud.exactOk, local.exactOk)
        return sb.toString()
    }

    private fun row(
        sb: StringBuilder,
        label: String,
        cloudRate: Double,
        localRate: Double,
        cloudOk: Int,
        localOk: Int
    ) {
        val diff = localRate - cloudRate
        val sign = if (diff >= 0) "+" else ""
        sb.append("| ").append(label)
            .append(" | ").append(pct(cloudRate)).append(" (").append(cloudOk).append(")")
            .append(" | ").append(pct(localRate)).append(" (").append(localOk).append(")")
            .append(" | ").append(sign).append(pct(diff))
            .append(" |\n")
    }
}
