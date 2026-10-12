// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [WatchlistStore.isWatchedId] 纯函数口径测试——主页关注 chip、
 * isWatched(prefs 版) 与 AnalysisWorker 跳过判定必须保持同一语义。
 *
 * v3 语义：**默认不关注**——空白名单 = 一个都不关注；
 * 「全部关注」用 [WatchlistStore.SENTINEL_ALL] 显式表达；
 * 黑名单命中恒否（且隐藏会话）。
 */
class WatchlistIdTest {

    private val key = "com.tencent.mm|工作群"
    private val other = "com.tencent.mm|其他群"

    @Test
    fun `空关注名单等于默认不关注`() {
        assertFalse(WatchlistStore.isWatchedId(key, emptySet(), emptySet()))
        assertFalse(WatchlistStore.isWatchedId(other, emptySet(), emptySet()))
    }

    @Test
    fun `全部关注哨兵命中所有会话`() {
        val all = setOf(WatchlistStore.SENTINEL_ALL)
        assertTrue(WatchlistStore.isWatchedId(key, emptySet(), all))
        assertTrue(WatchlistStore.isWatchedId(other, emptySet(), all))
    }

    @Test
    fun `黑名单优先级最高命中即否`() {
        // 全部关注态下，黑名单仍然一票否决
        assertFalse(
            WatchlistStore.isWatchedId(key, setOf(key), setOf(WatchlistStore.SENTINEL_ALL))
        )
        // 显式白名单里也有它时，黑名单先判
        assertFalse(WatchlistStore.isWatchedId(key, setOf(key), setOf(key)))
    }

    @Test
    fun `显式白名单按包含判断`() {
        assertTrue(WatchlistStore.isWatchedId(key, emptySet(), setOf(key)))
        assertFalse(WatchlistStore.isWatchedId(key, emptySet(), setOf(other)))
        assertFalse(WatchlistStore.isWatchedId(other, emptySet(), setOf(key)))
    }

    @Test
    fun `遗留全不关注哨兵保持不关注`() {
        // v2 用 SENTINEL_NONE 表达全不关注；v3 里它不再是白名单成员，
        // 天然得到「不关注」，老用户数据无需迁移
        assertFalse(
            WatchlistStore.isWatchedId(key, emptySet(), setOf(WatchlistStore.SENTINEL_NONE))
        )
    }

    @Test
    fun `新会话只有全部关注态才被自动关注`() {
        val newConv = "com.lark.messenger|刚进来的群"
        assertFalse(WatchlistStore.isWatchedId(newConv, emptySet(), setOf(key)))
        assertTrue(
            WatchlistStore.isWatchedId(
                newConv, emptySet(), setOf(WatchlistStore.SENTINEL_ALL)
            )
        )
    }
}
