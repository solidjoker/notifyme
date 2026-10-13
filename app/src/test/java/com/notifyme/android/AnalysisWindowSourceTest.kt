// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * M13 回看原文：窗口重组口径（ts ≤ windowEnd 的最近 messageCount 条）
 * 与文本拼接的纯逻辑测试。
 */
class AnalysisWindowSourceTest {

    private fun msg(sender: String, text: String, ts: Long) = ChatMessage(
        sender = sender,
        text = text,
        timestamp = ts,
        conversation = "张三",
        isGroup = false
    )

    @Test
    fun `取 windowEnd 前最近 messageCount 条`() {
        val messages = (1..12).map { msg("s", "m$it", it * 1000L) }
        val window = AnalysisCase.windowOf(messages, windowEnd = 12_000L, messageCount = 3)
        assertEquals(listOf("m10", "m11", "m12"), window.map { it.text })
    }

    @Test
    fun `windowEnd 之后的消息被排除`() {
        val messages = listOf(msg("s", "old", 1000L), msg("s", "new", 9000L))
        val window = AnalysisCase.windowOf(messages, windowEnd = 5000L, messageCount = 10)
        assertEquals(listOf("old"), window.map { it.text })
    }

    @Test
    fun `乱序输入按时间正序取尾`() {
        val messages = listOf(msg("s", "c", 3000L), msg("s", "a", 1000L), msg("s", "b", 2000L))
        val window = AnalysisCase.windowOf(messages, windowEnd = 3000L, messageCount = 2)
        assertEquals(listOf("b", "c"), window.map { it.text })
    }

    @Test
    fun `可用消息不足时取全部`() {
        val messages = listOf(msg("s", "only", 1000L))
        val window = AnalysisCase.windowOf(messages, windowEnd = 2000L, messageCount = 5)
        assertEquals(1, window.size)
    }

    @Test
    fun `空窗口与负数条数防御`() {
        assertEquals(emptyList<ChatMessage>(), AnalysisCase.windowOf(emptyList(), 1000L, 3))
        assertEquals(emptyList<ChatMessage>(), AnalysisCase.windowOf(listOf(msg("s", "a", 1L)), 1000L, 0))
        assertEquals(emptyList<ChatMessage>(), AnalysisCase.windowOf(listOf(msg("s", "a", 1L)), 1000L, -2))
    }

    @Test
    fun `原文拼接为每行发送者冒号内容`() {
        val window = listOf(msg("张三", "报价单发我", 1000L), msg("我", "好的", 2000L))
        assertEquals("张三: 报价单发我\n我: 好的", AnalysisCase.formatWindowSource(window))
        assertEquals("", AnalysisCase.formatWindowSource(emptyList()))
    }
}
