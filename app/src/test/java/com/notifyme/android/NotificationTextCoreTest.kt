// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [NotificationTextCore] 的口径回归。
 *
 * 用例里的会话名/昵称都是占位（真机 dump 的原文不进仓库），但**形态**照真机抄：
 * 未读计数前缀、半角冒号无空格、昵称与会话名部分重合等，都是 2026-11-12 真机取证看到的形状。
 */
class NotificationTextCoreTest {

    // ---------------- 未读计数前缀 ----------------

    @Test
    fun `识别微信未读计数前缀`() {
        assertTrue(NotificationTextCore.hasUnreadPrefix("[11条]张三: 好的，谢谢"))
        assertTrue(NotificationTextCore.hasUnreadPrefix("[3条] 张三: 好的"))
        assertFalse(NotificationTextCore.hasUnreadPrefix("张三: 好的，谢谢"))
        assertFalse(NotificationTextCore.hasUnreadPrefix("[图片]"))
    }

    @Test
    fun `剥掉未读计数前缀`() {
        assertEquals("张三: 好的，谢谢", NotificationTextCore.stripUnreadPrefix("[11条]张三: 好的，谢谢"))
        assertEquals("张三: 好的", NotificationTextCore.stripUnreadPrefix("[3条] 张三: 好的"))
        assertEquals("没有前缀的正文", NotificationTextCore.stripUnreadPrefix("没有前缀的正文"))
    }

    @Test
    fun `剥完为空时保留原文不丢消息`() {
        // 「[3条]」后面什么都没有：宁可正文带个前缀，也不能变成空串被调用方丢弃
        assertEquals("[3条]", NotificationTextCore.stripUnreadPrefix("[3条]"))
        assertEquals("[3条]  ", NotificationTextCore.stripUnreadPrefix("[3条]  "))
    }

    // ---------------- 发言人切分 ----------------

    @Test
    fun `未读前缀加半角冒号无空格也能切出发言人`() {
        val s = NotificationTextCore.splitSender("[11条]张三: 好的，谢谢", "家长群")
        assertEquals("张三", s.sender)
        assertEquals("好的，谢谢", s.text)
        assertTrue(s.isGroup)
        assertTrue(s.unreadStripped)
    }

    @Test
    fun `钉钉代发形态 昵称带角色括号 半角冒号无空格`() {
        val s = NotificationTextCore.splitSender("李四(老师):「明天交作业」通知确认情况", "2025级1班")
        assertEquals("李四(老师)", s.sender)
        assertEquals("「明天交作业」通知确认情况", s.text)
        assertTrue(s.isGroup)
    }

    @Test
    fun `全角冒号与冒号加空格仍然生效`() {
        val a = NotificationTextCore.splitSender("李四：明天开会", "项目群")
        assertEquals("李四", a.sender)
        assertEquals("明天开会", a.text)
        assertTrue(a.isGroup)

        val b = NotificationTextCore.splitSender("李四: 明天开会", "项目群")
        assertEquals("李四", b.sender)
        assertEquals("明天开会", b.text)
        assertTrue(b.isGroup)
    }

    @Test
    fun `私聊 前缀等于会话名 不算群聊`() {
        val s = NotificationTextCore.splitSender("张三: 明天见", "张三")
        assertEquals("张三", s.sender)
        assertEquals("明天见", s.text)
        assertFalse(s.isGroup)
    }

    @Test
    fun `私聊 昵称是会话名的子串 不算群聊`() {
        // 真机上会话名常见「昵称_备注_备注」拼接，prefix 只是其中一段
        val s = NotificationTextCore.splitSender("[2条]张三: 收到", "张三_某机构_某班")
        assertEquals("张三", s.sender)
        assertEquals("收到", s.text)
        assertFalse(s.isGroup)
    }

    @Test
    fun `没有前缀时整串当正文`() {
        val s = NotificationTextCore.splitSender("明天下午三点的评审改到周五了", "张三")
        assertNull(s.sender)
        assertEquals("明天下午三点的评审改到周五了", s.text)
        assertFalse(s.isGroup)
    }

    @Test
    fun `前缀过长不切`() {
        val longName = "一".repeat(NotificationTextCore.MAX_SENDER_PREFIX_LEN + 1)
        val s = NotificationTextCore.splitSender("$longName：内容", "群")
        assertNull(s.sender)
        assertFalse(s.isGroup)
    }

    @Test
    fun `多行前缀不切`() {
        val s = NotificationTextCore.splitSender("第一行\n第二行：内容", "群")
        assertNull(s.sender)
    }

    @Test
    fun `切完正文为空则不切`() {
        val s = NotificationTextCore.splitSender("张三:   ", "群")
        assertNull(s.sender)
        assertEquals("张三:", s.text)
    }

    // ---------------- 半角冒号收紧规则（防误切） ----------------

    @Test
    fun `时间形态 十点三十 不被当发言人`() {
        val s = NotificationTextCore.splitSender("10:30 开会", "群")
        assertNull(s.sender)
        assertEquals("10:30 开会", s.text)
        assertFalse(s.isGroup)
    }

    @Test
    fun `单字前缀加半角冒号不切`() {
        // 「A:」这种单字前缀几乎都是正文标点，不是昵称
        val s = NotificationTextCore.splitSender("答: 明天开会", "群")
        assertNull(s.sender)
    }

    @Test
    fun `链接里的双斜杠不切`() {
        val s = NotificationTextCore.splitSender("https://example.com/a", "群")
        assertNull(s.sender)
    }

    @Test
    fun `全角冒号不受收紧规则影响`() {
        // 单字 + 全角冒号仍按老口径切（既有行为不改）
        val s = NotificationTextCore.splitSender("甲：到", "群")
        assertEquals("甲", s.sender)
        assertEquals("到", s.text)
    }

    @Test
    fun `正文里的冒号不会被当成前缀`() {
        val body = "明天 10:30 在三楼会议室，记得带上：笔记本和充电器"
        val s = NotificationTextCore.splitSender(body, "项目群")
        assertNull(s.sender)
        assertEquals(body, s.text)
    }

    // ---------------- 稳定性 ----------------

    @Test
    fun `未读数变化不影响切分结果`() {
        val a = NotificationTextCore.splitSender("[3条]张三: 好的", "家长群")
        val b = NotificationTextCore.splitSender("[12条]张三: 好的", "家长群")
        assertEquals(a.sender, b.sender)
        assertEquals(a.text, b.text)
        assertEquals(a.isGroup, b.isGroup)
    }

    @Test
    fun `空正文不抛异常`() {
        val s = NotificationTextCore.splitSender("   ", "群")
        assertNull(s.sender)
        assertEquals("", s.text)
        assertFalse(s.isGroup)
    }
}
