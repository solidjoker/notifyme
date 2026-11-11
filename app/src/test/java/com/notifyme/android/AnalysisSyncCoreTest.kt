// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AnalysisSyncCore] 的切批策略与水位门槛单测。
 *
 * 背景（评审 C9 同源问题 + P0-3）：未同步的分析 case 原先被拼成**一个** JSON 数组一次
 * POST，s1/s2 文本一大就撞服务端 `MAX_CONTENT_LENGTH = 8 MiB`（server/app.py:59）拿 413，
 * 413 不算成功 → 分析记录永远推不上去；而服务端「部分 case 被拒」时返回 207，
 * 水位一旦被推过去，被拒的 case 就永久消失。
 *
 * 这里钉住四件事：
 *  - 每批都不超过条数 / 字节阈值（单条超大时它独占一批）；
 *  - 条目**不丢、不改序**（丢一条 = 一条 case 永久不报）；
 *  - body 是合法的 JSON 数组。
 *
 * 水位口径已经迁到 [SyncWatermarkCore]（「已结算前缀」：洞绝不越过），
 * 那边有独立单测 [SyncWatermarkCoreTest]。
 */
class AnalysisSyncCoreTest {

    private fun entries(count: Int, jsonSize: Int = 64): List<AnalysisSyncCore.Entry> =
        (1..count).map {
            AnalysisSyncCore.Entry(
                caseId = "case-$it",
                analyzedAt = it.toLong(),
                json = """{"case_id":"case-$it","t":"${"x".repeat(jsonSize)}"}"""
            )
        }

    @Test
    fun `空列表切出零批`() {
        assertTrue(AnalysisSyncCore.splitBatches(emptyList()).isEmpty())
    }

    @Test
    fun `条目数超阈值时按条数切批`() {
        val batches = AnalysisSyncCore.splitBatches(entries(450))
        assertEquals(3, batches.size)
        assertEquals(200, batches[0].size)
        assertEquals(200, batches[1].size)
        assertEquals(50, batches[2].size)
    }

    @Test
    fun `每批条数都不超过阈值且总数守恒`() {
        val batches = AnalysisSyncCore.splitBatches(entries(1000))
        assertTrue(batches.all { it.size <= SyncBatchCore.MAX_BATCH_ITEMS })
        assertEquals(1000, batches.sumOf { it.size })
    }

    @Test
    fun `条目体积超阈值时按字节切批`() {
        // 每条约 600 KiB：两条就超过 1 MiB，所以每批只能装 1 条
        val batches = AnalysisSyncCore.splitBatches(entries(3, jsonSize = 600 * 1024))
        assertEquals(3, batches.size)
        assertTrue(batches.all { it.size == 1 })
    }

    @Test
    fun `单条超过字节阈值时独占一批且不会被丢弃`() {
        val huge = AnalysisSyncCore.Entry("case-huge", 1L, """{"t":"${"x".repeat(2 * 1024 * 1024)}"}""")
        val small = entries(2)
        val batches = AnalysisSyncCore.splitBatches(listOf(huge) + small)
        // 大条目自己独占一批，后面的普通条目另起一批（大条目之后没有任何条目会跟它同批）
        assertEquals(2, batches.size)
        assertEquals(1, batches[0].size)
        assertEquals("case-huge", batches[0][0].caseId)
        // 大条目后面的普通条目照样被切出来，没有被吞掉
        assertEquals(2, batches[1].size)
        assertEquals(listOf("case-1", "case-2"), batches[1].map { it.caseId })
        assertEquals(3, batches.flatten().size)
    }

    @Test
    fun `条目不丢且顺序不变`() {
        val input = entries(450)
        val flat = AnalysisSyncCore.splitBatches(input).flatten()
        assertEquals(input.size, flat.size)
        assertEquals(input.map { it.caseId }, flat.map { it.caseId })
        assertEquals(input.map { it.analyzedAt }, flat.map { it.analyzedAt })
        assertEquals(input.map { it.json }, flat.map { it.json })
    }

    @Test
    fun `buildBody 输出形如 JSON 数组且能被解析`() {
        val batch = entries(3)
        val body = AnalysisSyncCore.buildBody(batch)
        assertTrue(body.startsWith("["))
        assertTrue(body.endsWith("]"))
        val array = JSONArray(body)
        assertEquals(3, array.length())
        assertEquals("case-1", array.getJSONObject(0).getString("case_id"))
        assertEquals("case-3", array.getJSONObject(2).getString("case_id"))
    }

    @Test
    fun `buildBody 空批次输出空数组`() {
        assertEquals("[]", AnalysisSyncCore.buildBody(emptyList()))
    }
}
