// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 控制台定时分析面板的判定单测（M20）。重点锁两件事：
 * 失联阈值必须与 ScheduleSelfHealCore 同尺；被标签拦截的会话必须排最后且不给下次时间，
 * 否则控制台会出现「说好了不再分析、却还告诉你下次 07:30」这种自相矛盾的界面。
 */
class AnalysisScheduleSummaryCoreTest {

    private val min = 60_000L
    private val now = 1_700_000_000_000L

    @Test
    fun `disabled switch means nothing runs automatically`() {
        val st = AnalysisScheduleSummaryCore.evaluate(
            enabled = false, intervalMinutes = 30, lastRunAt = now - 5 * min, now = now
        )
        assertEquals(AnalysisScheduleSummaryCore.Health.DISABLED, st.health)
        assertEquals(0L, st.nextRunAt)
    }

    @Test
    fun `zero global interval is reported separately from off`() {
        val st = AnalysisScheduleSummaryCore.evaluate(
            enabled = true, intervalMinutes = 0, lastRunAt = now - 5 * min, now = now
        )
        assertEquals(AnalysisScheduleSummaryCore.Health.GLOBAL_OFF, st.health)
    }

    @Test
    fun `never run has no next time yet`() {
        val st = AnalysisScheduleSummaryCore.evaluate(
            enabled = true, intervalMinutes = 30, lastRunAt = 0, now = now
        )
        assertEquals(AnalysisScheduleSummaryCore.Health.NEVER_RUN, st.health)
        assertEquals(0L, st.nextRunAt)
    }

    @Test
    fun `healthy run reports next tick at last plus interval`() {
        val st = AnalysisScheduleSummaryCore.evaluate(
            enabled = true, intervalMinutes = 30, lastRunAt = now - 10 * min, now = now
        )
        assertEquals(AnalysisScheduleSummaryCore.Health.OK, st.health)
        assertEquals(now - 10 * min + 30 * min, st.nextRunAt)
        assertEquals(10L, st.minutesSinceLast)
    }

    @Test
    fun `stale threshold is the same ruler as self-heal`() {
        val threshold = ScheduleSelfHealCore.thresholdFor(30)
        val healthy = AnalysisScheduleSummaryCore.evaluate(
            enabled = true, intervalMinutes = 30, lastRunAt = now - threshold, now = now
        )
        val stale = AnalysisScheduleSummaryCore.evaluate(
            enabled = true, intervalMinutes = 30, lastRunAt = now - threshold - min, now = now
        )
        assertEquals(AnalysisScheduleSummaryCore.Health.OK, healthy.health)
        assertEquals(AnalysisScheduleSummaryCore.Health.STALE, stale.health)
        assertEquals(threshold, stale.staleThresholdMs)
        assertEquals((threshold + min) / min, stale.minutesSinceLast)
        assertEquals(0L, stale.nextRunAt)
    }

    @Test
    fun `gated conversations sort last and lose their next tick`() {
        val rows = AnalysisScheduleSummaryCore.convRows(
            schedules = mapOf("a" to 15L, "b" to 60L, "c" to 30L, "d" to 0L),
            gatedOutIds = setOf("b"),
            lastRunAt = now - 5 * min,
            now = now,
        )
        // d 的周期是 0，等于没设，不该出现在面板里
        assertEquals(listOf("a", "c", "b"), rows.map { it.convKeyId })
        assertFalse(rows.first().gatedOut)
        val gated = rows.last()
        assertTrue(gated.gatedOut)
        assertEquals(0L, gated.nextRunAt)
        assertEquals(now - 5 * min + 15 * min, rows.first().nextRunAt)
    }

    @Test
    fun `no history means no estimated next for every conversation`() {
        val rows = AnalysisScheduleSummaryCore.convRows(
            schedules = mapOf("a" to 15L),
            gatedOutIds = emptySet(),
            lastRunAt = 0L,
            now = now,
        )
        assertEquals(0L, rows.single().nextRunAt)
    }
}
