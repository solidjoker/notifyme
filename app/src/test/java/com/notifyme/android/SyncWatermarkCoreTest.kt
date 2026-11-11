// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [SyncWatermarkCore] 的「已结算前缀」水位单测。
 *
 * 背景（第五轮审计）：旧口径是「本轮所有批次都整批接受才推进水位」
 * （已删除的 SyncOutcomeCore.watermarkIfAllAccepted），它有两个问题：
 *  - 过严：一批被拒 -> 整轮水位按住，本轮已成功的记录也整轮重传；
 *  - 有洞：「全部记录都被隔离」时曾 markSynced(全部 max analyzedAt)，
 *    水位越过那些**从未上报**的记录 -> 它们 analyzedAt <= 水位，扫描当已同步跳过，
 *    永远补报不了（数据丢失）。
 *
 * 新口径：水位只推进到「已结算前缀」（本轮被接受 ∪ 本轮新被隔离），
 * 遇到第一个未结算的记录就停 —— 洞绝不被越过。
 */
class SyncWatermarkCoreTest {

    private fun entry(id: String, timestamp: Long) = SyncWatermarkCore.Entry(id, timestamp)

    @Test
    fun `全部结算时水位推进到最大时间戳`() {
        val entries = listOf(entry("a", 100L), entry("b", 200L), entry("c", 300L))
        assertEquals(300L, SyncWatermarkCore.prefixWatermark(entries, setOf("a", "b", "c")))
    }

    @Test
    fun `存在未结算的洞时水位停在洞前`() {
        // b 被拒但还没到隔离上限 -> b 下一轮还要报，水位绝不能越过它
        val entries = listOf(entry("a", 100L), entry("b", 200L), entry("c", 300L))
        assertEquals(100L, SyncWatermarkCore.prefixWatermark(entries, setOf("a", "c")))
    }

    @Test
    fun `队首未结算时水位完全不推进`() {
        val entries = listOf(entry("a", 100L), entry("b", 200L))
        assertNull(SyncWatermarkCore.prefixWatermark(entries, setOf("b")))
    }

    @Test
    fun `空列表返回 null`() {
        assertNull(SyncWatermarkCore.prefixWatermark(emptyList(), emptySet()))
    }

    @Test
    fun `输入乱序时按时间戳排序后再算前缀`() {
        val entries = listOf(entry("c", 300L), entry("a", 100L), entry("b", 200L))
        assertEquals(200L, SyncWatermarkCore.prefixWatermark(entries, setOf("a", "b")))
    }

    @Test
    fun `已结算前缀长度只统计连续结算的条目`() {
        val entries = listOf(entry("a", 100L), entry("b", 200L), entry("c", 300L))
        assertEquals(1, SyncWatermarkCore.settledPrefixSize(entries, setOf("a", "c")))
        assertEquals(3, SyncWatermarkCore.settledPrefixSize(entries, setOf("a", "b", "c")))
        assertEquals(0, SyncWatermarkCore.settledPrefixSize(entries, setOf("b")))
    }
}
