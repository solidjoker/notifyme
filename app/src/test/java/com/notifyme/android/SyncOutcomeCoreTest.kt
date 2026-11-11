// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SyncOutcomeCore] 的状态码分类 + 207 拒绝清单解析单测。
 *
 * 这段逻辑原本是死代码：SyncWorker 先判 `response.isSuccessful`（OkHttp 覆盖 200..299），
 * 服务端的 207（部分条目被拒）被当成「整批成功」，于是 removeSent 把整批——包括被拒条目——
 * 从本地队列删掉，消息**永久丢失**（评审 P0-2）；分析通道同理把水位推过去（评审 P0-3）。
 *
 * 所以这里把「207 必须先于 2xx 判定」钉成契约：
 *  - classify(207) 必须是 PARTIAL_REJECTED，绝不能是 ACCEPTED；
 *  - parseRejected 读不出清单时必须返回 null（调用方据此一条都不删）；
 *  - acceptedIds 只剔除被拒下标对应的条目。
 */
class SyncOutcomeCoreTest {

    // ---------------- 状态码分类 ----------------

    @Test
    fun `207 判定为部分成功而不是整批成功`() {
        // 回归点：isSuccessful 覆盖 200..299，207 必须在 2xx 之前单独判定
        assertEquals(SyncOutcome.PARTIAL_REJECTED, SyncOutcomeCore.classify(207))
        assertFalse(SyncOutcomeCore.classify(207) == SyncOutcome.ACCEPTED)
    }

    @Test
    fun `普通 2xx 判定为整批成功`() {
        assertEquals(SyncOutcome.ACCEPTED, SyncOutcomeCore.classify(200))
        assertEquals(SyncOutcome.ACCEPTED, SyncOutcomeCore.classify(201))
        assertEquals(SyncOutcome.ACCEPTED, SyncOutcomeCore.classify(204))
        assertEquals(SyncOutcome.ACCEPTED, SyncOutcomeCore.classify(299))
    }

    @Test
    fun `401 判定为鉴权失败 404 判定为端点不存在`() {
        assertEquals(SyncOutcome.UNAUTHORIZED, SyncOutcomeCore.classify(401))
        assertEquals(SyncOutcome.NOT_SUPPORTED, SyncOutcomeCore.classify(404))
    }

    @Test
    fun `413 单独判定为载荷过大而不是普通失败`() {
        // 413 = 单条毒丸超过服务端上限（MAX_CONTENT_LENGTH = 8 MiB）：
        // 重试无用、且会永久卡住整批队列 -> 必须单独分支（二分重试 / 单独隔离）
        assertEquals(SyncOutcome.PAYLOAD_TOO_LARGE, SyncOutcomeCore.classify(413))
        assertFalse(SyncOutcomeCore.classify(413) == SyncOutcome.FAILED)
    }

    @Test
    fun `5xx 与其它错误码判定为失败`() {
        assertEquals(SyncOutcome.FAILED, SyncOutcomeCore.classify(500))
        assertEquals(SyncOutcome.FAILED, SyncOutcomeCore.classify(503))
        assertEquals(SyncOutcome.FAILED, SyncOutcomeCore.classify(0))
        assertEquals(SyncOutcome.FAILED, SyncOutcomeCore.classify(-1))
    }

    // ---------------- 207 拒绝清单解析 ----------------

    @Test
    fun `解析服务端 207 响应体里的被拒下标`() {
        val body = """{"ok":false,"received":3,"duplicated":0,"invalid":1,""" +
            """"rejected":[1],"error":"some items were rejected"}"""
        assertEquals(setOf(1), SyncOutcomeCore.parseRejected(body))
        assertEquals("some items were rejected", SyncOutcomeCore.parseError(body))
    }

    @Test
    fun `解析多个被拒下标`() {
        val body = """{"ok":false,"rejected":[0,2,5],"error":"some items were rejected"}"""
        assertEquals(setOf(0, 2, 5), SyncOutcomeCore.parseRejected(body))
    }

    @Test
    fun `拒绝清单为空数组时返回空集合而不是 null`() {
        val body = """{"ok":false,"rejected":[],"error":"some items were rejected"}"""
        assertEquals(emptySet<Int>(), SyncOutcomeCore.parseRejected(body))
    }

    @Test
    fun `读不出拒绝清单时返回 null 调用方据此一条都不删`() {
        assertNull(SyncOutcomeCore.parseRejected(null))
        assertNull(SyncOutcomeCore.parseRejected(""))
        assertNull(SyncOutcomeCore.parseRejected("   "))
        assertNull(SyncOutcomeCore.parseRejected("not a json"))
        assertNull(SyncOutcomeCore.parseRejected("""{"ok":false}""")) // 没有 rejected 字段
        assertNull(SyncOutcomeCore.parseRejected("""{"rejected":"x"}""")) // 字段类型不对
        assertNull(SyncOutcomeCore.parseRejected("""{"rejected":[-1]}""")) // 非法下标
    }

    @Test
    fun `错误原因缺失时返回 null`() {
        assertNull(SyncOutcomeCore.parseError(null))
        assertNull(SyncOutcomeCore.parseError("""{"ok":false,"rejected":[0]}"""))
        assertNull(SyncOutcomeCore.parseError("garbage"))
    }

    // ---------------- 只删被明确接受的条目 ----------------

    @Test
    fun `只剔除被拒下标对应的条目`() {
        val ids = listOf(10L, 11L, 12L, 13L)
        assertEquals(setOf(10L, 12L, 13L), SyncOutcomeCore.acceptedIds(ids, setOf(1)))
    }

    @Test
    fun `没有被拒条目时全部保留`() {
        val ids = listOf(1L, 2L, 3L)
        assertEquals(ids.toSet(), SyncOutcomeCore.acceptedIds(ids, emptySet()))
    }

    @Test
    fun `越界的被拒下标被忽略`() {
        val ids = listOf(1L, 2L)
        assertEquals(ids.toSet(), SyncOutcomeCore.acceptedIds(ids, setOf(5, 99)))
    }

    @Test
    fun `全部条目被拒时接受集合为空`() {
        val ids = listOf(1L, 2L)
        assertTrue(SyncOutcomeCore.acceptedIds(ids, setOf(0, 1)).isEmpty())
    }

    // ---------------- 207 的 rejected_ids（服务端新字段） ----------------

    @Test
    fun `解析服务端 207 响应体里的 rejected_ids`() {
        val body = """{"ok":false,"received":3,"invalid":2,"rejected":[0,2],""" +
            """"rejected_ids":["c0","c2"],"error":"some items were rejected"}"""
        assertEquals(setOf("c0", "c2"), SyncOutcomeCore.parseRejectedIds(body))
    }

    @Test
    fun `老服务端没有 rejected_ids 字段时返回 null 走下标口径`() {
        assertNull(SyncOutcomeCore.parseRejectedIds(null))
        assertNull(SyncOutcomeCore.parseRejectedIds(""))
        assertNull(SyncOutcomeCore.parseRejectedIds("garbage"))
        assertNull(SyncOutcomeCore.parseRejectedIds("""{"ok":false,"rejected":[1]}"""))
        assertNull(SyncOutcomeCore.parseRejectedIds("""{"rejected_ids":"c0"}"""))
    }

    @Test
    fun `rejected_ids 为空数组时返回空集合而不是 null`() {
        assertEquals(emptySet<String>(), SyncOutcomeCore.parseRejectedIds("""{"rejected_ids":[]}"""))
    }

    @Test
    fun `rejected_ids 里非字符串元素被忽略`() {
        val body = """{"rejected_ids":["c0",null,7,{"x":1},""]}"""
        assertEquals(setOf("c0"), SyncOutcomeCore.parseRejectedIds(body))
    }

    @Test
    fun `有 rejected_ids 时按 id 匹配 不依赖请求体顺序`() {
        // 服务端回的是 id 不是下标：即使下标对不上也必须按 id 命中
        val batchIds = listOf("c0", "c1", "c2")
        val matched = SyncOutcomeCore.matchRejectedIndices(setOf("c2"), setOf(0), batchIds)
        assertEquals(setOf(2), matched)
        assertEquals(setOf("c2"), SyncOutcomeCore.rejectedEntryIds(batchIds, matched!!))
    }

    @Test
    fun `没有 rejected_ids 时退回下标口径`() {
        val batchIds = listOf("c0", "c1", "c2")
        val matched = SyncOutcomeCore.matchRejectedIndices(null, setOf(0, 2), batchIds)
        assertEquals(setOf(0, 2), matched)
        assertEquals(setOf("c0", "c2"), SyncOutcomeCore.rejectedEntryIds(batchIds, matched!!))
    }

    @Test
    fun `id 命中不到任何条目时退回下标口径`() {
        val batchIds = listOf("c0", "c1")
        val matched = SyncOutcomeCore.matchRejectedIndices(setOf("other-id"), setOf(1), batchIds)
        assertEquals(setOf(1), matched)
    }

    @Test
    fun `id 与下标都读不出时返回 null 调用方必须保守处理`() {
        assertNull(SyncOutcomeCore.matchRejectedIndices(null, null, listOf("c0")))
        assertNull(SyncOutcomeCore.matchRejectedIndices(setOf("nope"), null, listOf("c0")))
    }

    @Test
    fun `匹配不上的 id 被忽略 不会误伤别的条目`() {
        val batchIds = listOf("c0", "c1", "c2")
        val matched = SyncOutcomeCore.matchRejectedIndices(setOf("c1", "ghost"), setOf(0, 1, 2), batchIds)
        assertEquals(setOf(1), matched)
        assertEquals(setOf("c1"), SyncOutcomeCore.rejectedEntryIds(batchIds, matched!!))
    }

    @Test
    fun `顾问通道按 report_id 匹配`() {
        val batchIds = listOf("r0", "r1")
        val matched = SyncOutcomeCore.matchRejectedIndices(setOf("r1"), setOf(0), batchIds)
        assertEquals(setOf("r1"), SyncOutcomeCore.rejectedEntryIds(batchIds, matched!!))
    }
}