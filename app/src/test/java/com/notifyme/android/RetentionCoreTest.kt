// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 数据保留策略纯决策逻辑测试。
 */
class RetentionCoreTest {

    private val cutoff = 1_700_000_000_000L

    private fun tsOf(line: String): Long? =
        try { JSONObject(line).optLong("timestamp", 0L).takeIf { it > 0 } } catch (e: Exception) { null }

    @Test
    fun `新鲜保留 超期删除`() {
        val fresh = """{"text":"new","timestamp":${cutoff + 1_000}}"""
        val stale = """{"text":"old","timestamp":${cutoff - 1_000}}"""
        val r = RetentionCore.prune(listOf(fresh, stale), cutoff, ::tsOf)
        assertEquals(listOf(fresh), r.kept)
        assertEquals(1, r.removed)
    }

    @Test
    fun `timestamp为0或缺失保留`() {
        val noTs = """{"text":"no-ts"}"""
        val zeroTs = """{"text":"zero","timestamp":0}"""
        val r = RetentionCore.prune(listOf(noTs, zeroTs), cutoff, ::tsOf)
        assertEquals(listOf(noTs, zeroTs), r.kept)
        assertEquals(0, r.removed)
    }

    @Test
    fun `损坏行保留`() {
        val corrupt = "{半截"
        val r = RetentionCore.prune(listOf(corrupt), cutoff, ::tsOf)
        assertEquals(listOf(corrupt), r.kept)
    }

    @Test
    fun `边界值cutoff当天保留`() {
        val atCutoff = """{"text":"edge","timestamp":${cutoff}}"""
        val r = RetentionCore.prune(listOf(atCutoff), cutoff, ::tsOf)
        assertEquals(listOf(atCutoff), r.kept)
    }
}