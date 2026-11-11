// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// 分析通道被拒计数 / 隔离决策（纯逻辑，单测钉住口径）。
class AnalysisRejectCoreTest {

    private val now = 1_700_000_000_000L
    private val ttl = AnalysisRejectCore.REJECT_TTL_MS

    @Test
    fun `首次被拒计数为1并记录首拒时间且不隔离`() {
        val d = AnalysisRejectCore.onRejected(0, 0L, false, now)
        assertEquals(1, d.rejectedCount)
        assertEquals(now, d.firstRejectedAt)
        assertFalse(d.quarantined)
        assertFalse(d.revived)
    }

    @Test
    fun `后续被拒沿用首拒时间不续期`() {
        val d = AnalysisRejectCore.onRejected(1, now, false, now + 1000L)
        assertEquals(2, d.rejectedCount)
        assertEquals(now, d.firstRejectedAt)
        assertFalse(d.quarantined)
    }

    @Test
    fun `第三次被拒达到上限即隔离`() {
        val d = AnalysisRejectCore.onRejected(2, now, false, now + 1000L)
        assertEquals(3, d.rejectedCount)
        assertTrue(d.quarantined)
        assertFalse(d.revived)
    }

    @Test
    fun `未达上限不隔离`() {
        val d = AnalysisRejectCore.onRejected(1, now, false, now + 1000L)
        assertEquals(2, d.rejectedCount)
        assertFalse(d.quarantined)
    }

    @Test
    fun `从未被拒的记录永不过期`() {
        assertFalse(AnalysisRejectCore.isExpired(0L, now, ttl))
        assertFalse(AnalysisRejectCore.isExpired(0L, now + ttl * 10, ttl))
    }

    @Test
    fun `超过TTL才算过期边界即过期`() {
        assertFalse(AnalysisRejectCore.isExpired(now, now + ttl - 1L, ttl))
        assertTrue(AnalysisRejectCore.isExpired(now, now + ttl, ttl))
        assertTrue(AnalysisRejectCore.isExpired(now, now + ttl + 1L, ttl))
    }

    @Test
    fun `过期后重置计数并重新算第一次被拒`() {
        val d = AnalysisRejectCore.onRejected(2, now, false, now + ttl)
        assertTrue(d.revived)
        assertEquals(1, d.rejectedCount)
        assertEquals(now + ttl, d.firstRejectedAt)
        assertFalse(d.quarantined)
    }

    @Test
    fun `已隔离且未过期保持隔离且不重复计数`() {
        val d = AnalysisRejectCore.onRejected(3, now, true, now + 1000L)
        assertTrue(d.quarantined)
        assertEquals(3, d.rejectedCount)
        assertEquals(now, d.firstRejectedAt)
        assertFalse(d.revived)
    }

    @Test
    fun `已隔离且过期解除隔离并清零`() {
        val d = AnalysisRejectCore.onRejected(3, now, true, now + ttl)
        assertFalse(d.quarantined)
        assertEquals(0, d.rejectedCount)
        assertEquals(0L, d.firstRejectedAt)
        assertTrue(d.revived)
    }

    @Test
    fun `隔离阈值可配置`() {
        val d1 = AnalysisRejectCore.onRejected(3, now, false, now, maxCount = 5)
        assertEquals(4, d1.rejectedCount)
        assertFalse(d1.quarantined)
        val d2 = AnalysisRejectCore.onRejected(4, now, false, now, maxCount = 5)
        assertEquals(5, d2.rejectedCount)
        assertTrue(d2.quarantined)
    }

    @Test
    fun `上限与TTL口径固定`() {
        assertEquals(3, AnalysisRejectCore.MAX_REJECT_COUNT)
        assertEquals(7L * 24 * 60 * 60 * 1000, AnalysisRejectCore.REJECT_TTL_MS)
    }
}
