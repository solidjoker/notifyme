// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AdvisorSyncCore] 的切批策略与水位门槛单测。
 *
 * 背景（上一轮报告外发现第 4 条）：顾问报告原先被拼成**一个** JSON 数组一次 POST，
 * 撞服务端 `MAX_CONTENT_LENGTH = 8 MiB`（server/app.py:59）拿 413，413 不算成功
 * → 顾问报告永远推不上去（与评审 C9 同源问题）。
 *
 * 这里钉住四件事：
 *  - 每批都不超过条数 / 字节阈值（单条超大时它独占一批）；
 *  - 条目**不丢、不改序**（丢一条 = 一份顾问报告永久不报）；
 *  - body 是合法的 JSON 数组。
 *
 * 水位口径已经迁到 [SyncWatermarkCore]（「已结算前缀」：洞绝不越过），
 * 那边有独立单测 [SyncWatermarkCoreTest]。
 */
class AdvisorSyncCoreTest {

    private fun entries(count: Int, jsonSize: Int = 64): List<AdvisorSyncCore.Entry> =
        (1..count).map {
            AdvisorSyncCore.Entry(
                reportId = "report-$it",
                createdAt = it.toLong(),
                json = """{"report_id":"report-$it","summary":"${"x".repeat(jsonSize)}"}"""
            )
        }

    @Test
    fun `空列表切出零批`() {
        assertTrue(AdvisorSyncCore.splitBatches(emptyList()).isEmpty())
    }

    @Test
    fun `条目数超阈值时按条数切批`() {
        val batches = AdvisorSyncCore.splitBatches(entries(450))
        assertEquals(3, batches.size)
        assertEquals(200, batches[0].size)
        assertEquals(200, batches[1].size)
        assertEquals(50, batches[2].size)
    }

    @Test
    fun `每批条数都不超过阈值且总数守恒`() {
        val batches = AdvisorSyncCore.splitBatches(entries(1000))
        assertTrue(batches.all { it.size <= SyncBatchCore.MAX_BATCH_ITEMS })
        assertEquals(1000, batches.sumOf { it.size })
    }

    @Test
    fun `条目体积超阈值时按字节切批`() {
        // 每条约 600 KiB：两条就超过 1 MiB，所以每批只能装 1 条
        val batches = AdvisorSyncCore.splitBatches(entries(3, jsonSize = 600 * 1024))
        assertEquals(3, batches.size)
        assertTrue(batches.all { it.size == 1 })
    }

    @Test
    fun `单条超过字节阈值时独占一批且不会被丢弃`() {
        val huge = AdvisorSyncCore.Entry("report-huge", 1L, """{"t":"${"x".repeat(2 * 1024 * 1024)}"}""")
        val batches = AdvisorSyncCore.splitBatches(listOf(huge) + entries(2))
        assertEquals(2, batches.size)
        assertEquals(1, batches[0].size)
        assertEquals("report-huge", batches[0][0].reportId)
        assertEquals(2, batches[1].size)
        assertEquals(3, batches.flatten().size)
    }

    @Test
    fun `条目不丢且顺序不变`() {
        val input = entries(450)
        val flat = AdvisorSyncCore.splitBatches(input).flatten()
        assertEquals(input.size, flat.size)
        assertEquals(input.map { it.reportId }, flat.map { it.reportId })
        assertEquals(input.map { it.createdAt }, flat.map { it.createdAt })
        assertEquals(input.map { it.json }, flat.map { it.json })
    }

    @Test
    fun `buildBody 输出形如 JSON 数组且能被解析`() {
        val body = AdvisorSyncCore.buildBody(entries(3))
        assertTrue(body.startsWith("["))
        assertTrue(body.endsWith("]"))
        val array = JSONArray(body)
        assertEquals(3, array.length())
        assertEquals("report-1", array.getJSONObject(0).getString("report_id"))
        assertEquals("report-3", array.getJSONObject(2).getString("report_id"))
    }

    @Test
    fun `buildBody 空批次输出空数组`() {
        assertEquals("[]", AdvisorSyncCore.buildBody(emptyList()))
    }
}
