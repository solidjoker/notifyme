// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// 增量扫描游标的纯字节计算单测（第四轮任务 B）。
class AnalysisScanCursorTest {

    private val buf = "a\nbb\nccc\n".toByteArray(Charsets.UTF_8)
    private val now = 1_700_000_000_000L

    @Test
    fun `从指定偏移继续读行`() {
        val first = AnalysisScanCursor.readLine(buf, 0)!!
        assertEquals("a", first.first)
        assertEquals(2, first.second)
        val second = AnalysisScanCursor.readLine(buf, 2)!!
        assertEquals("bb", second.first)
        assertEquals(5, second.second)
    }

    @Test
    fun `nextOffset凑满行数返回新偏移`() {
        assertEquals(5, AnalysisScanCursor.nextOffset(buf, 0, 2))
        assertEquals(9, AnalysisScanCursor.nextOffset(buf, 0, 3))
    }

    @Test
    fun `nextOffset凑不满返回null表示已到末尾`() {
        assertNull(AnalysisScanCursor.nextOffset(buf, 0, 5))
        assertNull(AnalysisScanCursor.nextOffset(buf, 10, 1))
    }

    @Test
    fun `找不到换行返回负一`() {
        assertEquals(-1, AnalysisScanCursor.indexOfNewline("abc".toByteArray(Charsets.UTF_8), 0))
        assertEquals(1, AnalysisScanCursor.indexOfNewline(buf, 0))
    }

    @Test
    fun `偏移越界时回落零`() {
        assertEquals(0L, AnalysisScanCursor.normalizeOffset(-1L, 100L))
        assertEquals(0L, AnalysisScanCursor.normalizeOffset(200L, 100L))
        assertEquals(50L, AnalysisScanCursor.normalizeOffset(50L, 100L))
    }

    @Test
    fun `行边界判定`() {
        assertTrue(AnalysisScanCursor.isLineBoundary(buf, 0))
        assertTrue(AnalysisScanCursor.isLineBoundary(buf, 2))
        assertFalse(AnalysisScanCursor.isLineBoundary(buf, 1))
    }

    @Test
    fun `未到末尾时保持原偏移`() {
        assertEquals(
            42L,
            AnalysisScanCursor.nextStartOffset(false, 42L, 0L, now)
        )
    }

    @Test
    fun `到末尾且从未全量重扫则从头开始`() {
        assertEquals(
            0L,
            AnalysisScanCursor.nextStartOffset(true, 42L, 0L, now)
        )
    }

    @Test
    fun `到末尾且间隔未到则停在末尾等新记录`() {
        assertEquals(
            42L,
            AnalysisScanCursor.nextStartOffset(true, 42L, now, now)
        )
    }

    @Test
    fun `到末尾且间隔已到则环形从头重扫`() {
        assertEquals(
            0L,
            AnalysisScanCursor.nextStartOffset(
                true,
                42L,
                now - AnalysisScanCursor.FULL_PASS_INTERVAL_MS,
                now
            )
        )
    }
}
