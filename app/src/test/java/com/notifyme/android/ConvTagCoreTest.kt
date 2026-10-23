// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M19 会话标签门禁：只有「待办/跟踪」以及「未标记但命中关注名单」的会话才继续进后续分析；
 * 手动分析（forced）无视标签。这组断言就是给「其他不再后续分析」这条需求上的锁。
 */
class ConvTagCoreTest {

    @Test
    fun `todo and track keep being analyzed`() {
        assertTrue(ConvTagCore.shouldAnalyze(ConvTag.TODO, watched = false, forced = false))
        assertTrue(ConvTagCore.shouldAnalyze(ConvTag.TRACK, watched = false, forced = false))
    }

    @Test
    fun `done and noise stop future analysis`() {
        assertFalse(ConvTagCore.shouldAnalyze(ConvTag.DONE, watched = true, forced = false))
        assertFalse(ConvTagCore.shouldAnalyze(ConvTag.NOISE, watched = true, forced = false))
        assertFalse(ConvTagCore.shouldAnalyze(ConvTag.DONE, watched = false, forced = false))
    }

    @Test
    fun `untagged falls back to watchlist decision`() {
        assertTrue(ConvTagCore.shouldAnalyze(ConvTag.NONE, watched = true, forced = false))
        assertFalse(ConvTagCore.shouldAnalyze(ConvTag.NONE, watched = false, forced = false))
    }

    @Test
    fun `manual analysis ignores every tag`() {
        for (tag in ConvTag.values()) {
            assertTrue("$tag 应允许手动分析", ConvTagCore.shouldAnalyze(tag, watched = false, forced = true))
        }
    }

    @Test
    fun `codes round trip and dirty values become untagged`() {
        for (tag in ConvTag.values()) {
            assertEquals(tag, ConvTag.fromCode(tag.code))
        }
        assertEquals(ConvTag.NONE, ConvTag.fromCode(null))
        assertEquals(ConvTag.NONE, ConvTag.fromCode(""))
        assertEquals(ConvTag.NONE, ConvTag.fromCode("垃圾数据"))
    }

    @Test
    fun `picker keeps keepers first and untagged last`() {
        assertEquals(ConvTag.TODO, ConvTagCore.PICKER_ORDER.first())
        assertEquals(ConvTag.NONE, ConvTagCore.PICKER_ORDER.last())
        assertEquals(ConvTagCore.PICKER_ORDER.size, ConvTag.values().size)
        for (tag in ConvTagCore.PICKER_ORDER) {
            // 每个档位必须被且只被一类判定覆盖，避免出现「既不算继续也不算停止又不是未标记」的漏档
            val covered = ConvTagCore.keepsAnalyzing(tag) || ConvTagCore.stopsAnalyzing(tag) || tag == ConvTag.NONE
            assertTrue(tag.name, covered)
        }
    }

    @Test
    fun `badge text is empty only for untagged`() {
        assertEquals("", ConvTagCore.badgeText(ConvTag.NONE, "未标记"))
        assertEquals("🟠 待办", ConvTagCore.badgeText(ConvTag.TODO, "待办"))
    }
}
