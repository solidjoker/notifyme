// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PendingRejectCore] 的被拒计数策略单测。
 *
 * 背景（上一轮报告外发现第 1 条）：服务端返回 207（部分条目被拒）时，被拒的队列条目
 * 会**永久**留在待发队列里，没有任何丢弃上限——只要服务端一直拒某一条（字段校验不过、
 * 去重键冲突等），队列就被无限撑大。
 *
 * 这里钉住四件事：
 *  - 累计次数只增不减，达到阈值（默认 3 次）才丢弃；
 *  - 首拒时间**只写一次**（后续被拒不续期，否则过期窗口永远不触发）；
 *  - 从未被拒过的条目永不过期（清理逻辑不许误伤正常排队条目）；
 *  - 过期窗口 7 天，与本地数据保留策略同口径。
 */
class PendingRejectCoreTest {

    @Test
    fun `首次被拒计数为 1 且记录首拒时间不丢弃`() {
        val decision = PendingRejectCore.onRejected(0, 0L, 1_000L)
        assertEquals(1, decision.rejectedCount)
        assertEquals(1_000L, decision.firstRejectedAt)
        assertFalse("第一次被拒不丢弃（还要再试两次）", decision.dropped)
    }

    @Test
    fun `后续被拒沿用首次被拒时间不被续期`() {
        val decision = PendingRejectCore.onRejected(1, 1_000L, 9_000L)
        assertEquals(2, decision.rejectedCount)
        assertEquals(
            "首拒时间只写一次：否则过期窗口永远不触发",
            1_000L,
            decision.firstRejectedAt
        )
        assertFalse(decision.dropped)
    }

    @Test
    fun `累计被拒达到上限即丢弃该条`() {
        val decision = PendingRejectCore.onRejected(2, 1_000L, 2_000L)
        assertEquals(3, decision.rejectedCount)
        assertTrue("第 3 次被拒达到默认上限，必须丢弃", decision.dropped)
    }

    @Test
    fun `未达上限时不丢弃`() {
        assertFalse(PendingRejectCore.onRejected(0, 0L, 1L).dropped)
        assertFalse(PendingRejectCore.onRejected(1, 1_000L, 2_000L).dropped)
    }

    @Test
    fun `丢弃阈值可配置`() {
        assertTrue(PendingRejectCore.onRejected(1, 1_000L, 2_000L, maxCount = 2).dropped)
        assertFalse(PendingRejectCore.onRejected(1, 1_000L, 2_000L, maxCount = 5).dropped)
    }

    @Test
    fun `从未被拒的条目永不过期`() {
        assertFalse(
            "没被拒过的条目本来就该继续排队上报，不许被清理逻辑误伤",
            PendingRejectCore.isExpired(0L, 9_999_999_999_999L)
        )
    }

    @Test
    fun `被拒后超过保留窗口即过期`() {
        val now = 10L * PendingRejectCore.REJECT_TTL_MS
        assertTrue(PendingRejectCore.isExpired(now - PendingRejectCore.REJECT_TTL_MS, now))
        assertTrue(PendingRejectCore.isExpired(now - PendingRejectCore.REJECT_TTL_MS - 1L, now))
    }

    @Test
    fun `未到保留窗口不过期`() {
        val now = 10L * PendingRejectCore.REJECT_TTL_MS
        assertFalse(PendingRejectCore.isExpired(now - PendingRejectCore.REJECT_TTL_MS + 1L, now))
    }

    @Test
    fun `默认上限为三次且过期窗口为七天`() {
        assertEquals(3, PendingRejectCore.MAX_REJECT_COUNT)
        assertEquals(7L * 24 * 60 * 60 * 1000, PendingRejectCore.REJECT_TTL_MS)
    }
}
