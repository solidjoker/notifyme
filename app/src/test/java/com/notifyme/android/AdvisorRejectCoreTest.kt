// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [AdvisorRejectCore] / [AdvisorRejectFile] 的被拒计数与隔离单测。
 *
 * 背景（第五轮审计）：顾问通道原先只有「水位不动」这一招 —— 一条报告被服务端
 * 持续拒绝，它就把水位永久钉死，此后每轮全量重传所有顾问报告。
 * 这里补上与分析通道、消息通道**同一把尺子**的「被拒计数 + 隔离」：
 *  - 累计被拒达 [AdvisorRejectCore.MAX_REJECT_COUNT] 次即隔离（不再上报、不再阻塞水位）；
 *  - 报告本体仍留在 advisor_reports.jsonl 里可人工查看；
 *  - 隔离超过 TTL 自动复活，重新获得一次上报机会；
 *  - 413 毒丸报告走 [AdvisorRejectFile.quarantine] **立即隔离**（重试无用）。
 */
class AdvisorRejectCoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun report(id: String, createdAt: Long) = AdvisorReport(
        reportId = id,
        createdAt = createdAt,
        periodDays = 7,
        caseCount = 3,
        summary = "整体复盘摘要",
        suggestions = listOf(AdvisorReport.Suggestion("prompt", "标题", "详情")),
        modelUsed = "laya"
    )

    private fun write(file: File, reports: List<AdvisorReport>) {
        val text = reports.joinToString("\n") { it.toJson().toString() }
        file.writeText(text + "\n", Charsets.UTF_8)
    }

    @Test
    fun `被拒三次即隔离`() {
        val d1 = AdvisorRejectCore.onRejected(0, 0L, false, 1000L)
        assertEquals(1, d1.rejectedCount)
        assertFalse(d1.quarantined)

        val d2 = AdvisorRejectCore.onRejected(d1.rejectedCount, d1.firstRejectedAt, false, 2000L)
        assertEquals(2, d2.rejectedCount)
        assertFalse(d2.quarantined)

        val d3 = AdvisorRejectCore.onRejected(d2.rejectedCount, d2.firstRejectedAt, false, 3000L)
        assertEquals(3, d3.rejectedCount)
        assertTrue(d3.quarantined)
    }

    @Test
    fun `首次被拒时间不续期`() {
        val d1 = AdvisorRejectCore.onRejected(0, 0L, false, 1000L)
        val d2 = AdvisorRejectCore.onRejected(d1.rejectedCount, d1.firstRejectedAt, false, 999_999L)
        assertEquals(d1.firstRejectedAt, d2.firstRejectedAt)
    }

    @Test
    fun `被拒窗口过期视为过期`() {
        val ttl = AdvisorRejectCore.REJECT_TTL_MS
        assertTrue(AdvisorRejectCore.isExpired(1000L, 1000L + ttl))
        assertFalse(AdvisorRejectCore.isExpired(1000L, 1000L + ttl - 1))
        // 从未被拒过的记录永远不过期（不该被隔离逻辑误伤）
        assertFalse(AdvisorRejectCore.isExpired(0L, 1000L + ttl))
    }

    @Test
    fun `文件级标记被拒三次后隔离`() {
        val file = tmp.newFile("advisor_reports.jsonl")
        write(file, listOf(report("r1", 100L), report("r2", 200L)))
        val rf = AdvisorRejectFile(file)

        rf.markRejected(setOf("r1"), 1000L)
        rf.markRejected(setOf("r1"), 2000L)
        val outcome = rf.markRejected(setOf("r1"), 3000L)

        assertEquals(setOf("r1"), outcome.newlyQuarantined)
        assertEquals(setOf("r1"), rf.quarantinedReportIds())
    }

    @Test
    fun `强制隔离立即生效且不影响其它报告`() {
        val file = tmp.newFile("advisor_reports.jsonl")
        write(file, listOf(report("r1", 100L), report("r2", 200L)))
        val rf = AdvisorRejectFile(file)

        assertTrue(rf.quarantine(setOf("r1"), 5000L))
        assertEquals(setOf("r1"), rf.quarantinedReportIds())
    }

    @Test
    fun `隔离到期复活并清零计数`() {
        val file = tmp.newFile("advisor_reports.jsonl")
        write(file, listOf(report("r1", 100L)))
        val rf = AdvisorRejectFile(file)

        rf.quarantine(setOf("r1"), 1000L)
        val revived = rf.reviveExpired(1000L + AdvisorRejectCore.REJECT_TTL_MS)

        assertEquals(setOf("r1"), revived)
        assertTrue(rf.quarantinedReportIds().isEmpty())
    }

    @Test
    fun `损坏行不影响其它报告`() {
        val file = tmp.newFile("advisor_reports.jsonl")
        file.writeText("{坏掉的行\n" + report("r1", 100L).toJson().toString() + "\n", Charsets.UTF_8)
        val rf = AdvisorRejectFile(file)

        rf.markRejected(setOf("r1"), 1000L)

        val lines = file.readLines(Charsets.UTF_8)
        assertTrue(lines.any { it.contains("{坏掉的行") })
        assertTrue(lines.any { it.contains("r1") })
    }
}
