// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class S2UsabilityTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    /** 窗口末条：2026-11-12 18:10 +08:00（与 S1 评测的 nowMs 同一天）。 */
    private val windowEnd = ms(2026, 11, 12, 18, 10)

    private fun ms(y: Int, m: Int, d: Int, h: Int, mi: Int): Long =
        java.time.LocalDateTime.of(y, m, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    private fun msg(text: String, ts: Long = windowEnd, sender: String = "对方"): ChatMessage =
        ChatMessage(sender = sender, text = text, timestamp = ts, conversation = "甲项目群", isGroup = true)

    private fun kase(vararg texts: String): AnalysisCase =
        AnalysisCase.fromMessages("com.tencent.mm", "甲项目群", texts.map { msg(it) })!!

    private fun s1(
        needActionProb: Double = 0.8,
        dueWindow: String = TopicLabelCore.DUE_TODAY,
        importance: Double = 7.0
    ): AnalysisCase.Companion.S1Result = AnalysisCase.Companion.S1Result(
        needActionProb = needActionProb,
        importance = importance,
        dueWindow = dueWindow,
        topic = TopicLabelCore.TOPIC_WORK_TASK,
        confidence = 0.9
    )

    private fun out(
        summary: String = "老板要求明天上午开会",
        dueTime: String = "2026-11-12 20:00",
        action: String = "今天把材料整理好",
        tasks: List<AnalysisCaseRecord.Task> = listOf(AnalysisCaseRecord.Task("我", "准备材料", "")),
        placeholderEcho: Boolean = false
    ): AnalysisParsing.S2Output = AnalysisParsing.S2Output(
        summary = summary,
        dueTime = dueTime,
        suggestedAction = action,
        tasks = tasks,
        rawJson = "{}",
        placeholderEcho = placeholderEcho
    )

    private fun outcome(key: String, o: AnalysisParsing.S2Output?, elapsed: Long = 0L, err: String? = null) =
        S2Usability.Outcome(caseId = key, conversation = "由于", out = o, elapsedMs = elapsed, error = err)

    private fun check(checks: List<S2Usability.Check>, name: String): S2Usability.Check =
        checks.first { it.name == name }

    // ---------- 字符数 / 桶名 / 兜底 ----------

    @Test
    fun charCountCountsCodePoints() {
        assertEquals(0, S2Usability.charCount("   "))
        assertEquals(4, S2Usability.charCount(" 明天上午 "))
        assertEquals(1, S2Usability.charCount("😀"))
        assertEquals(3, S2Usability.charCount("你😀好"))
    }

    @Test
    fun bucketOfMapsToS1Buckets() {
        assertEquals(TopicLabelCore.DUE_TODAY, S2Usability.bucketOf(ms(2026, 11, 12, 23, 59), windowEnd, zone))
        assertEquals(TopicLabelCore.DUE_TOMORROW, S2Usability.bucketOf(ms(2026, 11, 13, 0, 1), windowEnd, zone))
        assertEquals(TopicLabelCore.DUE_THIS_WEEK, S2Usability.bucketOf(ms(2026, 11, 19, 9, 0), windowEnd, zone))
        assertEquals(TopicLabelCore.DUE_LATER, S2Usability.bucketOf(ms(2026, 11, 20, 9, 0), windowEnd, zone))
        assertEquals(S2Usability.BUCKET_PAST, S2Usability.bucketOf(ms(2026, 11, 11, 9, 0), windowEnd, zone))
    }

    @Test
    fun fallbackSummaryUsesLastMessageWithNewlinesFolded() {
        val k = kase("第一条", "第二条\n换行\n三")
        assertEquals("第二条 换行 三", S2Usability.fallbackSummary(k))
    }

    @Test
    fun fallbackSummaryTruncatesAtTwentyChars() {
        val long = "一二三四五六七八九十一二三四五六七八九十甲乙"
        val k = kase(long)
        assertEquals(20, S2Usability.fallbackSummary(k).length)
    }

    @Test
    fun parseS2OutputSharesFallbackWithCore() {
        val k = kase("今天的最后一条 \u5e26\n换行")
        val parsed = AnalysisParsing.parseS2Output(null, k, "")
        assertEquals(S2Usability.fallbackSummary(k), parsed.summary)
    }

    // ---------- 六项判据 ----------

    @Test
    fun allChecksPassOnGoodOutput() {
        val checks = S2Usability.checks(outcome("a", out()), kase("明天上午十点开会"), s1(), zone)
        assertTrue(checks.all { it.ok })
        assertEquals(S2Usability.CHECKS, checks.map { it.name })
    }

    @Test
    fun undeliveredOutputFailsEveryCheckWithReason() {
        val checks = S2Usability.checks(outcome("a", null, err = "HTTP 500"), kase("开会"), s1(), zone)
        assertFalse(checks.any { it.ok })
        assertTrue(checks.first().note.contains("HTTP 500"))
    }

    @Test
    fun missingOutcomeIsTreatedAsUndelivered() {
        val checks = S2Usability.checks(null, kase("开会"), s1(), zone)
        assertFalse(checks.any { it.ok })
        assertFalse(check(checks, S2Usability.CHECK_DELIVERED).ok)
    }

    @Test
    fun placeholderEchoFailsNoPlaceholderOnly() {
        val checks = S2Usability.checks(
            outcome("a", out(placeholderEcho = true)),
            kase("明天上午十点开会"), s1(), zone
        )
        assertFalse(check(checks, S2Usability.CHECK_NO_PLACEHOLDER).ok)
        assertTrue(check(checks, S2Usability.CHECK_SUMMARY).ok)
        assertTrue(check(checks, S2Usability.CHECK_DELIVERED).ok)
    }

    @Test
    fun summaryEqualToFallbackFails() {
        val text = "今天下午三点过来"
        val checks = S2Usability.checks(outcome("a", out(summary = text)), kase(text), s1(), zone)
        assertFalse(check(checks, S2Usability.CHECK_SUMMARY).ok)
        assertTrue(check(checks, S2Usability.CHECK_SUMMARY).note.contains("兜底"))
    }

    @Test
    fun emptySummaryFails() {
        val checks = S2Usability.checks(outcome("a", out(summary = "  ")), kase("开会"), s1(), zone)
        assertFalse(check(checks, S2Usability.CHECK_SUMMARY).ok)
    }

    @Test
    fun summaryOverTwentyCharsFails() {
        val s21 = "一二三四五六七八九十一二三四五六七八九十甲"
        val checks = S2Usability.checks(outcome("a", out(summary = s21)), kase("开会"), s1(), zone)
        assertFalse(check(checks, S2Usability.CHECK_SUMMARY).ok)
        assertTrue(check(checks, S2Usability.CHECK_SUMMARY).note.contains("21"))
    }

    @Test
    fun summaryAtExactlyTwentyCharsPasses() {
        val s20 = "一二三四五六七八九十一二三四五六七八九十"
        val checks = S2Usability.checks(outcome("a", out(summary = s20)), kase("开会"), s1(), zone)
        assertTrue(check(checks, S2Usability.CHECK_SUMMARY).ok)
    }

    @Test
    fun emptyDuePassesOnlyWhenS1SaysNone() {
        val noneOut = out(dueTime = "")
        assertTrue(check(S2Usability.checks(outcome("a", noneOut), kase("闲聊"), s1(dueWindow = TopicLabelCore.DUE_NONE), zone), S2Usability.CHECK_DUE).ok)
        assertFalse(check(S2Usability.checks(outcome("a", noneOut), kase("开会"), s1(), zone), S2Usability.CHECK_DUE).ok)
    }

    @Test
    fun unparsableDueFails() {
        val checks = S2Usability.checks(outcome("a", out(dueTime = "尽快")), kase("开会"), s1(), zone)
        assertFalse(check(checks, S2Usability.CHECK_DUE).ok)
        assertTrue(check(checks, S2Usability.CHECK_DUE).note.contains("解析"))
    }

    @Test
    fun dueBeforeWindowEndFails() {
        val checks = S2Usability.checks(
            outcome("a", out(dueTime = "2026-11-12 09:00")),
            kase("开会"), s1(), zone
        )
        assertFalse(check(checks, S2Usability.CHECK_DUE).ok)
        assertTrue(check(checks, S2Usability.CHECK_DUE).note.contains("早于"))
    }

    @Test
    fun dueInSameBucketPasses() {
        val checks = S2Usability.checks(
            // 用带数字的时刻：中文数字的「八点」DueTimeCore 解析不出（生产提醒链也一样），
            // 用那样的文本去测《桶判定》会把解析失败误当成桶错位。
            outcome("a", out(dueTime = "今天 20:00")),
            kase("今天晚上八点交"), s1(), zone
        )
        assertTrue(check(checks, S2Usability.CHECK_DUE).ok)
    }

    @Test
    fun dueInOtherBucketFails() {
        val checks = S2Usability.checks(
            outcome("a", out(dueTime = "明天上午九点")),
            kase("明天上午九点交"), s1(dueWindow = TopicLabelCore.DUE_TODAY), zone
        )
        assertFalse(check(checks, S2Usability.CHECK_DUE).ok)
        assertTrue(check(checks, S2Usability.CHECK_DUE).note.contains(TopicLabelCore.DUE_TOMORROW))
    }

    @Test
    fun dueGivenWhenS1SaysNoneFails() {
        val checks = S2Usability.checks(
            outcome("a", out(dueTime = "今天下午三点")),
            kase("闲聊"), s1(dueWindow = TopicLabelCore.DUE_NONE), zone
        )
        assertFalse(check(checks, S2Usability.CHECK_DUE).ok)
    }

    @Test
    fun emptyActionFails() {
        val checks = S2Usability.checks(outcome("a", out(action = " ")), kase("开会"), s1(), zone)
        assertFalse(check(checks, S2Usability.CHECK_ACTION).ok)
    }

    @Test
    fun actionOverTwentyCharsFails() {
        val long = "一二三四五六七八九十一二三四五六七八九十甲"
        val checks = S2Usability.checks(outcome("a", out(action = long)), kase("开会"), s1(), zone)
        assertFalse(check(checks, S2Usability.CHECK_ACTION).ok)
    }

    @Test
    fun taskWithoutWhoFails() {
        val tasks = listOf(AnalysisCaseRecord.Task("", "准备材料", ""))
        val checks = S2Usability.checks(outcome("a", out(tasks = tasks)), kase("开会"), s1(), zone)
        assertFalse(check(checks, S2Usability.CHECK_TASKS).ok)
    }

    @Test
    fun needActionWithEmptyTasksFails() {
        val checks = S2Usability.checks(outcome("a", out(tasks = emptyList())), kase("开会"), s1(), zone)
        assertFalse(check(checks, S2Usability.CHECK_TASKS).ok)
    }

    @Test
    fun noActionWithEmptyTasksPasses() {
        val checks = S2Usability.checks(
            outcome("a", out(tasks = emptyList(), dueTime = "", action = "无需行动")),
            kase("闲聊"), s1(needActionProb = 0.1, dueWindow = TopicLabelCore.DUE_NONE), zone
        )
        assertTrue(check(checks, S2Usability.CHECK_TASKS).ok)
        assertTrue(checks.all { it.ok })
    }

    // ---------- 汇总 / 报告 ----------

    private fun sampleCases(): List<S1Accuracy.Case> {
        val ex = S1Accuracy.Expect(true, 7.0, TopicLabelCore.DUE_TODAY, TopicLabelCore.TOPIC_WORK_TASK)
        val ex2 = S1Accuracy.Expect(false, 1.0, TopicLabelCore.DUE_NONE, TopicLabelCore.TOPIC_SMALLTALK)
        return listOf(
            S1Accuracy.Case("com.tencent.mm", "甲项目群", true, listOf(msg("开会")), ex),
            S1Accuracy.Case("com.tencent.mm", "乙闲聊群", true, listOf(msg("哈哈")), ex2)
        )
    }

    @Test
    fun scoreCountsDeliveredUsableAndMedian() {
        val cases = sampleCases()
        val k1 = AnalysisCase.fromMessages(cases[0].pkg, cases[0].conversation, cases[0].messages)!!
        val k2 = AnalysisCase.fromMessages(cases[1].pkg, cases[1].conversation, cases[1].messages)!!
        val outcomes = mapOf(
            cases[0].caseId to S2Usability.Outcome(cases[0].caseId, cases[0].conversation, out(), 1500L),
            cases[1].caseId to S2Usability.Outcome(cases[1].caseId, cases[1].conversation, out(summary = "不靠谱", dueTime = "", action = "无需行动", tasks = emptyList()), 500L)
        )
        val side = S2Usability.score("云端", cases, outcomes, { s1() }, zone)
        assertEquals(2, side.total)
        assertEquals(2, side.delivered)
        assertEquals(1, side.usable)
        assertEquals(0.5, side.usableRate, 1e-9)
        assertEquals(1.0, side.deliveredRate, 1e-9)
        assertTrue(k1.windowEnd == k2.windowEnd)
        assertEquals(1500L, side.medianMs)
    }

    @Test
    fun scoreOnEmptyCasesIsAllZero() {
        val side = S2Usability.score("云端", emptyList(), emptyMap(), { s1() }, zone)
        assertEquals(0, side.total)
        assertEquals(0, side.delivered)
        assertEquals(0.0, side.usableRate, 1e-9)
        assertEquals(0.0, side.checkRate(S2Usability.CHECK_SUMMARY), 1e-9)
        assertEquals(0L, side.medianMs)
        for (n in S2Usability.CHECKS) assertEquals(0, side.pass[n])
    }

    @Test
    fun undeliveredDoesNotCountInCheckDenominator() {
        val cases = sampleCases()
        val outcomes = mapOf(
            cases[0].caseId to S2Usability.Outcome(cases[0].caseId, cases[0].conversation, null, 0L, "timeout")
        )
        val side = S2Usability.score("云端", cases, outcomes, { s1() }, zone)
        assertEquals(2, side.total)
        assertEquals(0, side.delivered)
        assertEquals(0, side.usable)
        assertEquals(0.0, side.checkRate(S2Usability.CHECK_DELIVERED), 1e-9)
        assertEquals(0.0, side.deliveredRate, 1e-9)
    }

    @Test
    fun rowsKeepCaseOrderAndBothSides() {
        val cases = sampleCases()
        val cloud = mapOf(cases[0].caseId to S2Usability.Outcome(cases[0].caseId, cases[0].conversation, out(), 100L))
        val local = mapOf(
            cases[0].caseId to S2Usability.Outcome(cases[0].caseId, cases[0].conversation, out(placeholderEcho = true), 90L)
        )
        val rows = S2Usability.rows(cases, cloud, local, { s1() }, zone)
        assertEquals(2, rows.size)
        assertEquals(cases[0].caseId, rows[0].caseId)
        assertTrue(rows[0].cloudUsable)
        assertFalse(rows[0].localUsable)
        assertFalse(rows[1].cloudUsable)
        assertNull(cloud[cases[1].caseId])
    }

    @Test
    fun markdownHasBothSidesAndRates() {
        val cloud = S2Usability.Side("云端", 20, 20, 15, emptyMap(), 24_000L)
        val local = S2Usability.Side("端侧", 20, 18, 2, emptyMap(), 1_300L)
        val md = S2Usability.markdown(cloud, local)
        assertTrue(md.startsWith("| 指标 | 云端 S2 | 端侧 S2 | 两侧差值 |"))
        assertTrue(md.contains("15/20"))
        assertTrue(md.contains("18/20"))
        assertTrue(md.contains("24.0s"))
        assertTrue(md.contains("1.3s"))
    }

    @Test
    fun checksMarkdownListsSixRows() {
        val cloud = S2Usability.Side("云端", 10, 10, 8, mapOf(S2Usability.CHECK_SUMMARY to 9), 1000L)
        val local = S2Usability.Side("端侧", 10, 10, 1, mapOf(S2Usability.CHECK_SUMMARY to 3), 100L)
        val md = S2Usability.checksMarkdown(cloud, local)
        val rows = md.trim().split("\n")
        assertEquals(2 + S2Usability.CHECKS.size, rows.size)
        assertTrue(md.contains("90.0% (9)"))
        assertTrue(md.contains("30.0% (3)"))
    }

    @Test
    fun marksRendersSixGlyphs() {
        val checks = S2Usability.checks(
            outcome("a", out(placeholderEcho = true)),
            kase("明天上午十点开会"), s1(), zone
        )
        val marks = S2Usability.marks(checks)
        assertEquals(S2Usability.CHECKS.size, marks.length)
        assertEquals("✓✗", marks.substring(0, 2))
    }

    @Test
    fun failureLinesOnlyCoverUndelivered() {
        val outcomes = mapOf(
            "a" to S2Usability.Outcome("a", "甲项目群", out()),
            "b" to S2Usability.Outcome("b", "乙闲聊群", null, 0L, "HTTP 429")
        )
        val lines = S2Usability.failureLines(outcomes)
        assertEquals(1, lines.size)
        assertTrue(lines[0].contains("乙闲聊群"))
        assertTrue(lines[0].contains("HTTP 429"))
    }

    @Test
    fun legendNamesEveryCheck() {
        val legend = S2Usability.legend()
        for (n in S2Usability.CHECKS) {
            assertTrue("missing " + n, legend.contains(S2Usability.label(n)))
        }
    }

    @Test
    fun bucketHonoursExplicitZone() {
        // 同一个绝对时刻 11-13 07:00 +08 在 UTC 下是 11-12 23:00，两个时区下的桶不同——
        // 桶判定必须跟传入的 zone，不能偷偷退回系统时区。
        val lateTomorrow = ms(2026, 11, 13, 7, 0)
        assertEquals(TopicLabelCore.DUE_TOMORROW, S2Usability.bucketOf(lateTomorrow, windowEnd, zone))
        assertEquals(TopicLabelCore.DUE_TODAY, S2Usability.bucketOf(lateTomorrow, windowEnd, ZoneId.of("UTC")))
    }
}
