// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ConvKey] 的持久化/兼容规则测试。
 *
 * 关键点：会话名里含 `|`（微信群名很常见）时，解析只切第一个分隔符；
 * schema v1 的裸会话名一律按微信处理。
 */
class ConvKeyTest {

    private val wx = AppSourceRegistry.PKG_WECHAT

    @Test
    fun `id 与 toString 是 pkg|conversation`() {
        val key = ConvKey("com.ss.android.lark", "项目群")
        assertEquals("com.ss.android.lark|项目群", key.id)
        assertEquals(key.id, key.toString())
    }

    @Test
    fun `of 消息 取 pkg 与 conversation`() {
        val m = ChatMessage(
            sender = "李四", text = "在吗", timestamp = 1_000L,
            conversation = "项目群", isGroup = true, pkg = "com.alibaba.android.rimet"
        )
        assertEquals(ConvKey("com.alibaba.android.rimet", "项目群"), ConvKey.of(m))
    }

    @Test
    fun `legacy 把裸会话名归到微信`() {
        assertEquals(wx, ConvKey.legacy("张三").pkg)
        assertEquals("张三", ConvKey.legacy("张三").conversation)
    }

    @Test
    fun `parse 无分隔符按微信兼容`() {
        assertEquals(ConvKey(wx, "张三"), ConvKey.parse("张三"))
    }

    @Test
    fun `parse 分隔符在首位（空包名）按微信兼容`() {
        assertEquals(ConvKey(wx, "张三"), ConvKey.parse("|张三"))
    }

    @Test
    fun `parse 只切第一个分隔符 会话名里的竖线保留`() {
        val key = ConvKey.parse("$wx|工作|生活")
        assertEquals(wx, key.pkg)
        assertEquals("工作|生活", key.conversation)
    }

    @Test
    fun `parse 正常复合键往返`() {
        assertEquals(
            ConvKey("com.ss.android.lark", "李四"),
            ConvKey.parse("com.ss.android.lark|李四")
        )
    }

    @Test
    fun `parse 空串不抛异常`() {
        val key = ConvKey.parse("")
        assertEquals(wx, key.pkg)
        assertEquals("", key.conversation)
    }

    @Test
    fun `parseAll 跳过空白项`() {
        val keys = ConvKey.parseAll(listOf("", "张三", "com.ss.android.lark|群", "   "))
        assertEquals(2, keys.size)
        assertTrue(keys.contains(ConvKey(wx, "张三")))
        assertTrue(keys.contains(ConvKey("com.ss.android.lark", "群")))
    }

    @Test
    fun `displayLabel 注册表内置名优先 再 appLabel 再包名`() {
        assertEquals("微信", ConvKey(wx, "x").displayLabel())
        assertEquals("飞书", ConvKey("com.ss.android.lark", "x").displayLabel("旧标签"))
        assertEquals("我的 App", ConvKey("com.example.app", "x").displayLabel("我的 App"))
        assertEquals("com.example.app", ConvKey("com.example.app", "x").displayLabel())
    }

    @Test
    fun `equals 同 pkg 同会话才相等`() {
        assertTrue(ConvKey(wx, "张三") == ConvKey(wx, "张三"))
        assertFalse(ConvKey(wx, "张三") == ConvKey("com.ss.android.lark", "张三"))
        assertFalse(ConvKey(wx, "张三") == ConvKey(wx, "李四"))
    }
}
