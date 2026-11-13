// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [HistorySyncCore] 的隐私门控 + 翻页终止判定单测。
 *
 * 回归点（评审 C8）：HistorySync 原先完全没接 PrivacyConfig——LOCAL_ONLY（仅端侧）下
 * 照样触发服务端直读微信库 + 拉历史，隐私开关被架空；同时拉历史只取第一页，
 * 服务端给的 next_before 复合游标被丢掉，历史超过一页就永远补不齐。
 */
class HistorySyncCoreTest {

    private fun state(mode: String) = PrivacyModeState(mode, emptySet(), emptySet())

    // ---------------- 隐私门控 ----------------

    @Test
    fun `OFF 模式允许服务端提取与历史回填`() {
        val s = state(PrivacyModeState.OFF)
        assertTrue(HistorySyncCore.allowExtract(s))
        assertTrue(HistorySyncCore.allowFetch(s))
    }

    @Test
    fun `CLOUD_REDACT 禁止服务端直读 但允许回填服务端已有历史`() {
        // 服务端直读会把原文写进云端，端上没法脱敏 -> 不触发提取
        val s = state(PrivacyModeState.CLOUD_REDACT)
        assertFalse(HistorySyncCore.allowExtract(s))
        assertTrue(HistorySyncCore.allowFetch(s))
    }

    @Test
    fun `LOCAL_ONLY 整条链路停用`() {
        val s = state(PrivacyModeState.LOCAL_ONLY)
        assertFalse(HistorySyncCore.allowExtract(s))
        assertFalse(HistorySyncCore.allowFetch(s))
    }

    // ---------------- 翻页终止条件 ----------------

    @Test
    fun `没有 next_before 时不再翻页`() {
        assertFalse(HistorySyncCore.shouldFetchNext(null, 10, null, 1))
        assertFalse(HistorySyncCore.shouldFetchNext("", 10, null, 1))
    }

    @Test
    fun `本页为空时不再翻页`() {
        assertFalse(HistorySyncCore.shouldFetchNext("1700000000000_42", 0, null, 1))
    }

    @Test
    fun `游标没变时不再翻页 防死循环`() {
        assertFalse(HistorySyncCore.shouldFetchNext("1700000000000_42", 10, "1700000000000_42", 1))
    }

    @Test
    fun `有新游标且本页有数据时继续翻页`() {
        assertTrue(HistorySyncCore.shouldFetchNext("1700000000000_42", 10, null, 1))
        assertTrue(HistorySyncCore.shouldFetchNext("1700000000000_42", 10, "1700000000001_7", 1))
    }

    @Test
    fun `超过页数上限时停止翻页`() {
        assertFalse(HistorySyncCore.shouldFetchNext("1700000000000_42", 10, null, HistorySyncCore.MAX_PAGES))
        assertFalse(HistorySyncCore.shouldFetchNext("1700000000000_42", 10, null, 99))
        // 上限可配置，便于测试不同策略
        assertTrue(HistorySyncCore.shouldFetchNext("1700000000000_42", 10, null, 1, maxPages = 5))
        assertFalse(HistorySyncCore.shouldFetchNext("1700000000000_42", 10, null, 5, maxPages = 5))
    }

    // ---------------- 游标拼装 ----------------

    @Test
    fun `首屏只带 limit 翻页带上复合游标`() {
        assertEquals("messages?limit=1000", HistorySyncCore.messagesPath(null, 1000))
        assertEquals("messages?limit=1000", HistorySyncCore.messagesPath("", 1000))
        assertEquals(
            "messages?limit=1000&before=1700000000000_42",
            HistorySyncCore.messagesPath("1700000000000_42", 1000)
        )
    }
}
