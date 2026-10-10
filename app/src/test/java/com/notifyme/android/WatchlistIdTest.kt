// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [WatchlistStore.isWatchedId] 纯函数口径测试——主页关注 chip、
 * isWatched(prefs 版) 与 AnalysisWorker 跳过判定必须保持同一语义。
 */
class WatchlistIdTest {

    private val key = "com.tencent.mm|工作群"
    private val other = "com.tencent.mm|其他群"

    @Test
    fun `空关注名单等于全部关注`() {
        assertTrue(WatchlistStore.isWatchedId(key, emptySet(), emptySet()))
    }

    @Test
    fun `黑名单优先级最高命中即否`() {
        assertFalse(WatchlistStore.isWatchedId(key, setOf(key), emptySet()))
        // 即使名单里也有它，黑名单先判
        assertFalse(WatchlistStore.isWatchedId(key, setOf(key), setOf(key)))
    }

    @Test
    fun `非空名单按包含判断`() {
        assertTrue(WatchlistStore.isWatchedId(key, emptySet(), setOf(key)))
        assertFalse(WatchlistStore.isWatchedId(key, emptySet(), setOf(other)))
    }

    @Test
    fun `仅含哨兵表示全不关注`() {
        assertFalse(
            WatchlistStore.isWatchedId(key, emptySet(), setOf(WatchlistStore.SENTINEL_NONE))
        )
    }
}
