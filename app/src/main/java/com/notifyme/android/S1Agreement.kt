// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import org.json.JSONObject

/**
 * 云端 vs 端侧 S1 判定一致率（ROADMAP M4 任务 6 / docs/ROADMAP.md:237）。
 *
 * 为什么需要：两条 S1 路径读**同一个窗口**、走**不同的 system prompt**
 * （云端 S1_OPENAI_SYSTEM_PROMPT 是英文 schema；端侧 S1_LOCAL_SYSTEM_PROMPT 是中文 few-shot），
 * 而判定结果直接决定要不要打扰用户（need_action ≥ 0.5 才提醒、importance ≥ 6 才升级 S2）。
 * 没有量化对比，就说不清「端侧 S1 能不能替代云端 S1」。
 *
 * 口径与 AnalysisCase / AnalysisParsing 逐条一致，不另立定义：
 *  - 窗口 = 最近 AnalysisCase.WINDOW_SIZE 条，时间正序；
 *  - need_action = needActionProb >= 0.5（S1Result.needAction）；
 *  - 升级 = prob >= 0.5 || importance >= 6 || confidence < 0.5（S1Result.shouldEscalate）；
 *  - 解析 = [AnalysisParsing.parseS1Result]（payload 为 null 时 confidence 归 0，交给升级闸）。
 *
 * 纯函数：只吃已采集的判定结果，不发网络、不加载模型、不碰 Context。
 */
internal object S1Agreement {

    /** 概率容差：两侧差 ≤ 0.15 视为同档（输出都只保留 2 位小数，取半格多一点）。 */
    const val PROB_TOLERANCE = 0.15

    /** importance 容差：0-9 档差 ≤ 1.5 档视为同档。 */
    const val IMPORTANCE_TOLERANCE = 1.5

    /** 一条窗口的双方判定（由调用方分别跑云端 / 端侧得到）。 */
    data class Pair(
        val pkg: String,
        val conversation: String,
        val windowEnd: Long,
        val cloud: AnalysisCase.Companion.S1Result,
        val local: AnalysisCase.Companion.S1Result
    )

    /** 单条窗口的比对明细。 */
    data class Row(
        val pkg: String,
        val conversation: String,
        val windowEnd: Long,
        val cloud: AnalysisCase.Companion.S1Result,
        val local: AnalysisCase.Companion.S1Result,
        val needActionAgree: Boolean,
        val escalateAgree: Boolean,
        val dueWindowAgree: Boolean,
        val topicAgree: Boolean,
        val probDelta: Double,
        val importanceDelta: Double
    ) {
        val key: String get() = pkg + "/" + conversation

        /** 五项全一致才算「判定一致」。 */
        val agree: Boolean
            get() = needActionAgree && escalateAgree && dueWindowAgree && topicAgree
    }

    /** 汇总结果。 */
    data class Summary(
        val rows: List<Row>,
        val total: Int,
        val agreeCount: Int,
        val needActionAgreeCount: Int,
        val escalateAgreeCount: Int,
        val dueWindowAgreeCount: Int,
        val topicAgreeCount: Int,
        val meanProbDelta: Double,
        val meanImportanceDelta: Double,
        val cloudFlagged: Int,
        val localFlagged: Int,
        val meanConfidence: Double
    ) {
        /** 判定一致率（0-1），total=0 时 0.0。 */
        val agreementRate: Double get() = ratio(agreeCount, total)
        val needActionAgreementRate: Double get() = ratio(needActionAgreeCount, total)
        val escalateAgreementRate: Double get() = ratio(escalateAgreeCount, total)
        val dueWindowAgreementRate: Double get() = ratio(dueWindowAgreeCount, total)
        val topicAgreementRate: Double get() = ratio(topicAgreeCount, total)

        /** 一致性等级：写进评测记录的一句话结论。 */
        val grade: String
            get() = when {
                total == 0 -> "无样本"
                agreementRate >= 0.9 -> "可直接替代"
                agreementRate >= 0.75 -> "基本可用（差异需人工复核）"
                agreementRate >= 0.5 -> "只能做粗筛（差异偏大）"
                else -> "不可替代（差异占多数）"
            }

        private fun ratio(n: Int, d: Int): Double = if (d <= 0) 0.0 else n.toDouble() / d
    }

    /** 比较一条窗口。 */
    fun compare(pair: Pair): Row {
        val c = pair.cloud
        val l = pair.local
        return Row(
            pkg = pair.pkg,
            conversation = pair.conversation,
            windowEnd = pair.windowEnd,
            cloud = c,
            local = l,
            needActionAgree = c.needAction == l.needAction,
            escalateAgree = c.shouldEscalate() == l.shouldEscalate(),
            dueWindowAgree = c.dueWindow.trim().equals(l.dueWindow.trim(), ignoreCase = true),
            topicAgree = c.topic.trim().equals(l.topic.trim(), ignoreCase = true),
            probDelta = kotlin.math.abs(c.needActionProb - l.needActionProb),
            importanceDelta = kotlin.math.abs(c.importance - l.importance)
        )
    }

    /** 汇总（行序保持入参顺序，便于人工对号）。 */
    fun summarize(rows: List<Row>): Summary {
        val total = rows.size
        return Summary(
            rows = rows,
            total = total,
            agreeCount = rows.count { it.agree },
            needActionAgreeCount = rows.count { it.needActionAgree },
            escalateAgreeCount = rows.count { it.escalateAgree },
            dueWindowAgreeCount = rows.count { it.dueWindowAgree },
            topicAgreeCount = rows.count { it.topicAgree },
            meanProbDelta = if (total == 0) 0.0 else rows.sumOf { it.probDelta } / total,
            meanImportanceDelta = if (total == 0) 0.0 else rows.sumOf { it.importanceDelta } / total,
            cloudFlagged = rows.count { it.cloud.needAction },
            localFlagged = rows.count { it.local.needAction },
            meanConfidence = if (total == 0) 0.0 else rows.sumOf { it.local.confidence } / total
        )
    }

    /** 便捷入口：两侧判定列表按窗口配对后直接汇总。 */
    fun of(pairs: List<Pair>): Summary = summarize(pairs.map { compare(it) })

    /** Markdown 报告正文（写进 docs/ 评测记录；调用方负责加标题与元信息）。 */
    fun markdown(summary: Summary, generatedAt: String): String {
        val sb = StringBuilder()
        sb.append("| 指标 | 数值 |\n")
        sb.append("|---|---|\n")
        sb.append("| 样本窗口数 | ").append(summary.total).append(" |\n")
        sb.append("| 判定完全一致 | ").append(summary.agreeCount)
        sb.append(" (").append(pct(summary.agreementRate)).append(") |\n")
        sb.append("| need_action 一致 | ").append(summary.needActionAgreeCount)
        sb.append(" (").append(pct(summary.needActionAgreementRate)).append(") |\n")
        sb.append("| 升级 S2 一致 | ").append(summary.escalateAgreeCount)
        sb.append(" (").append(pct(summary.escalateAgreementRate)).append(") |\n")
        sb.append("| due_window 一致 | ").append(summary.dueWindowAgreeCount)
        sb.append(" (").append(pct(summary.dueWindowAgreementRate)).append(") |\n")
        sb.append("| topic 一致 | ").append(summary.topicAgreeCount)
        sb.append(" (").append(pct(summary.topicAgreementRate)).append(") |\n")
        sb.append("| 云端 need_action 命中 | ").append(summary.cloudFlagged).append(" |\n")
        sb.append("| 端侧 need_action 命中 | ").append(summary.localFlagged).append(" |\n")
        sb.append("| need_action_prob 平均绝对差 | ")
        sb.append("%.3f".format(summary.meanProbDelta)).append(" |\n")
        sb.append("| importance 平均绝对差 | ")
        sb.append("%.2f".format(summary.meanImportanceDelta)).append(" |\n")
        sb.append("| 端侧 confidence 均值 | ")
        sb.append("%.3f".format(summary.meanConfidence)).append(" |\n")
        sb.append("\n结论：").append(summary.grade).append("\n")
        sb.append("\n> 生成时间：").append(generatedAt).append("\n")
        return sb.toString()
    }

    private fun pct(v: Double): String = "%.1f%%".format(v * 100)
}