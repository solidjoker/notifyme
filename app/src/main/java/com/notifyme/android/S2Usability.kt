// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * S2 深分析输出的「可用性」度量（ROADMAP M4 任务 6 的遗留项：S2 摘要可用性）。
 *
 * 为什么要单独一个内核：S1 有「判定对不对」的客观答案（need_action / importance /
 * due_window / topic 四项），S2 没有——摘要、建议动作、分派条目都是自由文本，
 * 直接问「写得好不好」只能靠人。这里把它拆成**六个人工可复核、机器可复现的判据**：
 *
 *  1. [CHECK_DELIVERED] HTTP 成功且能抽出 JSON（[AnalysisParsing.extractJsonPayload]）；
 *  2. [CHECK_NO_PLACEHOLDER] 没有照抄 prompt 骨架的字段说明（[AnalysisParsing.S2Output.placeholderEcho]）；
 *  3. [CHECK_SUMMARY] 摘要非空、≤[MAX_SUMMARY_CHARS] 字、且不是「截取窗口末条原文」的兜底；
 *  4. [CHECK_DUE] due_time 能被 [DueTimeCore.parse] 解析，且日期落在 S1 判定的同一个桶里；
 *  5. [CHECK_ACTION] 建议动作非空、≤[MAX_ACTION_CHARS] 字、不是占位文案；
 *  6. [CHECK_TASKS] 分派条目结构完整（who/what 非空），S1 判「需要行动」时不能给空数组。
 *
 * 六项全过才算「可用」。这样「端侧 2B 能不能替代云端做深分析」就变成一个可复算的比例，
 * 而不是一句「看着还行」。判据刻意都用窗口内的客观事实（S1 桶、窗口末条时间），
 * 不看模型措辞好坏——措辞好坏留给人看报告里的样例。
 *
 * 口径与生产一致的两处：摘要兜底文案由 [fallbackSummary] 提供（[AnalysisParsing.parseS2Output]
 * 就调它，别在别处另写一份）；due_time 的解析走 [DueTimeCore]，和提醒链同一个解析器。
 */
internal object S2Usability {

    /** 摘要字符上限：S2 prompt 要求「≤20字」，超出说明模型没按格式收口。 */
    const val MAX_SUMMARY_CHARS = 20

    /** 建议动作字符上限：同上，prompt 要求「≤20字」。 */
    const val MAX_ACTION_CHARS = 20

    const val CHECK_DELIVERED = "delivered"
    const val CHECK_NO_PLACEHOLDER = "no_placeholder"
    const val CHECK_SUMMARY = "summary"
    const val CHECK_DUE = "due"
    const val CHECK_ACTION = "action"
    const val CHECK_TASKS = "tasks"

    /** 六项判据的固定顺序（报告表头与逐样本状态串都用它）。 */
    val CHECKS = listOf(
        CHECK_DELIVERED,
        CHECK_NO_PLACEHOLDER,
        CHECK_SUMMARY,
        CHECK_DUE,
        CHECK_ACTION,
        CHECK_TASKS
    )

    /** due_time 早于窗口末条这么多秒也算「过期的截止」，不算可用。 */
    const val PAST_TOLERANCE_MS = 60_000L

    /** 桶名：早于窗口当天（S1 口径里不存在这个桶，出现即矛盾）。 */
    const val BUCKET_PAST = "past"

    data class Check(val name: String, val ok: Boolean, val note: String = "")

    /**
     * 一侧对某条样本的观测。
     *
     * [out] 为 null 表示这条没拿到可用输出（HTTP 失败 / JSON 抽不出 / 解析异常），
     * 原因记在 [error]；`delivered` 判据由此决定。
     */
    data class Outcome(
        val caseId: String,
        val conversation: String,
        val out: AnalysisParsing.S2Output?,
        val elapsedMs: Long = 0L,
        val error: String? = null
    )

    /** 逐样本明细：两侧各自的六项布尔。 */
    data class Row(
        val caseId: String,
        val conversation: String,
        val cloud: List<Check>,
        val local: List<Check>
    ) {
        val cloudUsable: Boolean get() = cloud.all { it.ok }
        val localUsable: Boolean get() = local.all { it.ok }
    }

    /** 一侧的汇总。 */
    data class Side(
        val name: String,
        val total: Int,
        val delivered: Int,
        val usable: Int,
        val pass: Map<String, Int>,
        val medianMs: Long
    ) {
        val usableRate: Double get() = rate(usable)
        val deliveredRate: Double get() = rate(delivered)

        /** 分项通过率的分母是「已交付」——没交付的样本不该拉低字段质量。 */
        fun checkRate(check: String): Double =
            if (delivered == 0) 0.0 else (pass[check] ?: 0).toDouble() / delivered

        private fun rate(n: Int): Double = if (total == 0) 0.0 else n.toDouble() / total
    }

    /**
     * 与 [AnalysisParsing.parseS2Output] 完全同口径的摘要兜底：
     * 窗口末条原文、换行压成空格、截 [MAX_SUMMARY_CHARS] 字。
     * 生产侧直接调这里，避免评测与线上两份实现慢慢漂开。
     */
    fun fallbackSummary(kase: AnalysisCase): String =
        kase.messages.lastOrNull()?.text.orEmpty()
            .replace("\n", " ")
            .take(MAX_SUMMARY_CHARS)

    /** 字符数（按码点算，避免 emoji 在 UTF-16 下算成两个）。 */
    fun charCount(text: String): Int {
        val t = text.trim()
        return if (t.isEmpty()) 0 else t.codePointCount(0, t.length)
    }

    /**
     * 把一个毫秒时间落到 S1 的 due_window 桶里（桶名与 [TopicLabelCore.DUE_*] 一致）。
     * 以 [baseMs] 当天为第 0 天：0=今天、1=明天、2..7=本周内、>7=更晚、<0=过期。
     */
    fun bucketOf(ms: Long, baseMs: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val base = Instant.ofEpochMilli(baseMs).atZone(zone).toLocalDate()
        val day = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
        val diff = ChronoUnit.DAYS.between(base, day)
        return when {
            diff < 0L -> BUCKET_PAST
            diff == 0L -> TopicLabelCore.DUE_TODAY
            diff == 1L -> TopicLabelCore.DUE_TOMORROW
            diff <= 7L -> TopicLabelCore.DUE_THIS_WEEK
            else -> TopicLabelCore.DUE_LATER
        }
    }

    /** 逐条跑六项判据（顺序即 [CHECKS]）。 */
    fun checks(
        outcome: Outcome?,
        kase: AnalysisCase,
        s1: AnalysisCase.Companion.S1Result,
        zone: ZoneId = ZoneId.systemDefault()
    ): List<Check> {
        val out = outcome?.out
        if (out == null) {
            val why = outcome?.error?.take(60).orEmpty()
            return CHECKS.map { Check(it, false, why) }
        }
        return listOf(
            Check(CHECK_DELIVERED, true),
            placeholderCheck(out),
            summaryCheck(out, kase),
            dueCheck(out, kase, s1, zone),
            actionCheck(out),
            tasksCheck(out, s1)
        )
    }

    private fun placeholderCheck(out: AnalysisParsing.S2Output): Check = Check(
        CHECK_NO_PLACEHOLDER,
        !out.placeholderEcho,
        if (out.placeholderEcho) "照抄了 prompt 骨架的字段说明" else ""
    )

    private fun summaryCheck(out: AnalysisParsing.S2Output, kase: AnalysisCase): Check {
        val summary = out.summary.trim()
        if (summary.isEmpty()) return Check(CHECK_SUMMARY, false, "摘要为空")
        if (summary == fallbackSummary(kase).trim()) {
            return Check(CHECK_SUMMARY, false, "摘要就是窗口末条原文（兜底，不是真的概括）")
        }
        val n = charCount(summary)
        if (n > MAX_SUMMARY_CHARS) {
            return Check(CHECK_SUMMARY, false, "摘要 " + n + " 字，超过 " + MAX_SUMMARY_CHARS)
        }
        return Check(CHECK_SUMMARY, true)
    }

    /**
     * due_time 判据：空串只在 S1 也判 none 时通过；非空必须能被 [DueTimeCore.parse] 解析，
     * 不能早于窗口末条，且落进 S1 判的同一个桶（端侧常见失败：把「当前时间」直接抄进 due_time）。
     */
    private fun dueCheck(
        out: AnalysisParsing.S2Output,
        kase: AnalysisCase,
        s1: AnalysisCase.Companion.S1Result,
        zone: ZoneId
    ): Check {
        val due = out.dueTime.trim()
        val bucket = TopicLabelCore.normalizeDue(s1.dueWindow)
        if (due.isEmpty()) {
            return Check(
                CHECK_DUE,
                bucket == TopicLabelCore.DUE_NONE,
                if (bucket == TopicLabelCore.DUE_NONE) "" else "due_time 空，但 S1 判 " + bucket
            )
        }
        val parsed = DueTimeCore.parse(due, kase.windowEnd, zone)
            ?: return Check(CHECK_DUE, false, "due_time 解析不出：" + due.take(30))
        if (parsed < kase.windowEnd - PAST_TOLERANCE_MS) {
            return Check(CHECK_DUE, false, "due_time 早于窗口末条：" + due.take(30))
        }
        if (bucket == TopicLabelCore.DUE_NONE) {
            return Check(CHECK_DUE, false, "S1 判无截止，S2 却给了 " + due.take(30))
        }
        val actual = bucketOf(parsed, kase.windowEnd, zone)
        if (actual != bucket) {
            return Check(CHECK_DUE, false, "落在 " + actual + "，S1 判 " + bucket)
        }
        return Check(CHECK_DUE, true)
    }

    private fun actionCheck(out: AnalysisParsing.S2Output): Check {
        val action = out.suggestedAction.trim()
        if (action.isEmpty()) return Check(CHECK_ACTION, false, "建议动作为空")
        val n = charCount(action)
        if (n > MAX_ACTION_CHARS) {
            return Check(CHECK_ACTION, false, "建议动作 " + n + " 字，超过 " + MAX_ACTION_CHARS)
        }
        return Check(CHECK_ACTION, true)
    }

    private fun tasksCheck(
        out: AnalysisParsing.S2Output,
        s1: AnalysisCase.Companion.S1Result
    ): Check {
        val broken = out.tasks.firstOrNull { it.who.isBlank() || it.what.isBlank() }
        if (broken != null) return Check(CHECK_TASKS, false, "分派条目缺 who/what")
        if (s1.needAction && out.tasks.isEmpty()) {
            return Check(CHECK_TASKS, false, "S1 判需要行动，但 tasks 为空")
        }
        return Check(CHECK_TASKS, true)
    }

    /** 把一侧的观测汇总成 [Side]。 */
    fun score(
        name: String,
        cases: List<S1Accuracy.Case>,
        outcomes: Map<String, Outcome>,
        s1Of: (S1Accuracy.Case) -> AnalysisCase.Companion.S1Result,
        zone: ZoneId = ZoneId.systemDefault()
    ): Side {
        var delivered = 0
        var usable = 0
        val pass = LinkedHashMap<String, Int>()
        val latencies = ArrayList<Long>(cases.size)
        for (c in cases) {
            val kase = AnalysisCase.fromMessages(c.pkg, c.conversation, c.messages) ?: continue
            val outcome = outcomes[c.caseId]
            val checks = checks(outcome, kase, s1Of(c), zone)
            if (checks.first { it.name == CHECK_DELIVERED }.ok) {
                delivered++
                outcome?.elapsedMs?.let { latencies.add(it) }
                for (chk in checks) {
                    if (chk.ok) pass[chk.name] = (pass[chk.name] ?: 0) + 1
                }
                if (checks.all { it.ok }) usable++
            }
        }
        for (n in CHECKS) if (n !in pass) pass[n] = 0
        latencies.sort()
        val median = if (latencies.isEmpty()) 0L else latencies[latencies.size / 2]
        return Side(name, cases.size, delivered, usable, pass, median)
    }

    /** 逐样本明细。 */
    fun rows(
        cases: List<S1Accuracy.Case>,
        cloud: Map<String, Outcome>,
        local: Map<String, Outcome>,
        s1Of: (S1Accuracy.Case) -> AnalysisCase.Companion.S1Result,
        zone: ZoneId = ZoneId.systemDefault()
    ): List<Row> = cases.mapNotNull { c ->
        val kase = AnalysisCase.fromMessages(c.pkg, c.conversation, c.messages) ?: return@mapNotNull null
        Row(
            caseId = c.caseId,
            conversation = c.conversation,
            cloud = checks(cloud[c.caseId], kase, s1Of(c), zone),
            local = checks(local[c.caseId], kase, s1Of(c), zone)
        )
    }

    private fun pct(v: Double): String = LocaleTextCore.fixed(v * 100, 1) + "%"

    /** 综合可用性表（Markdown）。 */
    fun markdown(cloud: Side, local: Side): String {
        val sb = StringBuilder()
        sb.append("| 指标 | 云端 S2 | 端侧 S2 | 两侧差值 |\n")
        sb.append("|---|---|---|---|\n")
        sb.append("| 样本数 | ").append(cloud.total).append(" | ").append(local.total).append(" | - |\n")
        sb.append("| 已交付（HTTP + JSON） | ")
            .append(pair(cloud.delivered, cloud.total)).append(" | ")
            .append(pair(local.delivered, local.total)).append(" | ")
            .append(pct(local.deliveredRate - cloud.deliveredRate)).append(" |\n")
        sb.append("| 六项全过（可用） | ")
            .append(pair(cloud.usable, cloud.total)).append(" | ")
            .append(pair(local.usable, local.total)).append(" | ")
            .append(pct(local.usableRate - cloud.usableRate)).append(" |\n")
        sb.append("| 单次调用中位时延 | ")
            .append(ms(cloud.medianMs)).append(" | ").append(ms(local.medianMs)).append(" | - |\n")
        return sb.toString()
    }

    /** 分项通过率表（分母 = 已交付）。 */
    fun checksMarkdown(cloud: Side, local: Side): String {
        val sb = StringBuilder()
        sb.append("| 判据 | 云端 S2 | 端侧 S2 | 两侧差值 |\n")
        sb.append("|---|---|---|---|\n")
        for (n in CHECKS) {
            val cr = cloud.checkRate(n)
            val lr = local.checkRate(n)
            sb.append("| ").append(label(n)).append(" | ")
            .append(pct(cr)).append(" (").append(cloud.pass[n] ?: 0).append(")").append(" | ")
            .append(pct(lr)).append(" (").append(local.pass[n] ?: 0).append(")").append(" | ")
            .append(pct(lr - cr)).append(" |\n")
        }
        return sb.toString()
    }

    /** 逐样本状态串：`✓✓✗✓✓✓`（顺序同 [CHECKS]）。 */
    fun marks(checks: List<Check>): String =
        checks.joinToString("") { if (it.ok) "\u2713" else "\u2717" }

    /** 报告表头的判据说明（一行一条，避免读者去翻代码）。 */
    fun legend(): String = CHECKS.joinToString(" · ") { label(it) }

    /** 判据的中文标签（报告表头与 legend 共用，测试也直接引用，别在别处另写一份）。 */
    fun label(check: String): String = when (check) {
        CHECK_DELIVERED -> "已交付"
        CHECK_NO_PLACEHOLDER -> "非占位照抄"
        CHECK_SUMMARY -> "摘要非空且非原文兜底"
        CHECK_DUE -> "due_time 可解析且与 S1 同桶"
        CHECK_ACTION -> "建议动作合规"
        else -> "分派条目完整"
    }

    private fun pair(ok: Int, total: Int): String = ok.toString() + "/" + total + "（" + pct(if (total == 0) 0.0 else ok.toDouble() / total) + "）"

    private fun ms(v: Long): String = if (v <= 0L) "-" else LocaleTextCore.fixed(v / 1000.0, 1) + "s"

    /** 失败样本的说明列表（报告里「为什么没交付」用）。 */
    fun failureLines(outcomes: Map<String, Outcome>): List<String> =
        outcomes.values.mapNotNull { o ->
            if (o.out != null) null else o.conversation + "：" + (o.error ?: "未交付").take(120)
        }
}
