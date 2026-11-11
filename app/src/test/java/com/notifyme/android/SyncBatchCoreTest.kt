// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SyncBatchCore] 的切批策略单测。
 *
 * 背景（评审 C9）：待发队列原来被拼成**一个** JSON 数组一次 POST，服务端
 * `MAX_CONTENT_LENGTH = 8 MiB`（server/app.py:59）→ 队列一大就 413 → 端上对任何失败
 * 一律 retry → 同一个超大请求被无限重推的死循环。
 *
 * 现在按「条数 ≤ 200 且序列化后 ≤ 1 MiB」双阈值切批，所以这里要钉住三件事：
 *  - 条目**不丢、不改序**（丢一条 = 一条消息永久不报）；
 *  - 每批都不超过服务端上限（单条超大时它独占一批）；
 *  - 实际序列化字节数与 bodySize 估算一致。
 */
class SyncBatchCoreTest {

    private fun items(count: Int, jsonSize: Int = 64): List<SyncBatchCore.Item> =
        (1..count).map { SyncBatchCore.Item(it.toLong(), """{"i":$it,"t":"${"x".repeat(jsonSize)}"}""") }

    @Test
    fun `空队列切出零批`() {
        assertTrue(SyncBatchCore.splitBatches(emptyList()).isEmpty())
    }

    @Test
    fun `条目数超阈值时按条数切批且不丢不改序`() {
        val input = items(450)
        val batches = SyncBatchCore.splitBatches(input)
        assertEquals(3, batches.size)
        assertEquals(200, batches[0].size)
        assertEquals(200, batches[1].size)
        assertEquals(50, batches[2].size)
        // 不丢不改序
        val flat = batches.flatten()
        assertEquals(input.size, flat.size)
        assertEquals(input.map { it.id }, flat.map { it.id })
        assertEquals(input.map { it.json }, flat.map { it.json })
    }

    @Test
    fun `每批条数都不超过阈值`() {
        val batches = SyncBatchCore.splitBatches(items(1000))
        assertTrue(batches.all { it.size <= SyncBatchCore.MAX_BATCH_ITEMS })
        assertEquals(1000, batches.flatten().size)
    }

    @Test
    fun `条目体积超阈值时按字节切批`() {
        // 每条约 600 KiB → 一批最多装 1 条（1 MiB 上限）
        val input = items(3, jsonSize = 600_000)
        val batches = SyncBatchCore.splitBatches(input)
        assertEquals(3, batches.size)
        assertTrue(batches.all { it.size == 1 })
        assertTrue(batches.all { SyncBatchCore.bodySize(it) <= SyncBatchCore.MAX_BATCH_BYTES })
    }

    @Test
    fun `单条超过字节阈值时独占一批但不会被丢弃`() {
        val huge = SyncBatchCore.Item(1L, """{"t":"${"x".repeat(SyncBatchCore.MAX_BATCH_BYTES + 1024)}"}""")
        val normal = SyncBatchCore.Item(2L, """{"t":"small"}""")
        val batches = SyncBatchCore.splitBatches(listOf(huge, normal))
        assertEquals(2, batches.size)
        assertEquals(listOf(huge), batches[0])
        assertEquals(listOf(normal), batches[1])
    }

    @Test
    fun `切批后每批的序列化字节数都不超上限`() {
        val batches = SyncBatchCore.splitBatches(items(500, jsonSize = 5_000))
        assertTrue(batches.all { SyncBatchCore.bodySize(it) <= SyncBatchCore.MAX_BATCH_BYTES })
    }

    @Test
    fun `bodySize 与实际序列化长度一致`() {
        val batch = items(7)
        val expected = SyncBatchCore.buildBody(batch).toByteArray(Charsets.UTF_8).size
        assertEquals(expected, SyncBatchCore.bodySize(batch))
    }

    @Test
    fun `二分把一批拆成两半且不丢不改序`() {
        val input = items(6)
        val halves = SyncBatchCore.bisect(input)
        assertEquals(2, halves.size)
        assertEquals(3, halves[0].size)
        assertEquals(3, halves[1].size)
        assertEquals(input.map { it.id }, halves.flatten().map { it.id })
    }

    @Test
    fun `二分对空批与单条返回空`() {
        assertTrue(SyncBatchCore.bisect(emptyList<SyncBatchCore.Item>()).isEmpty())
        assertTrue(SyncBatchCore.bisect(listOf(items(1)[0])).isEmpty())
    }

    @Test
    fun `buildBody 产出可解析的 JSON 数组且条数一致`() {
        val batch = items(3)
        val body = SyncBatchCore.buildBody(batch)
        val arr = JSONArray(body)
        assertEquals(3, arr.length())
        assertEquals(1, arr.getJSONObject(0).getInt("i"))
        assertEquals(3, arr.getJSONObject(2).getInt("i"))
    }
}