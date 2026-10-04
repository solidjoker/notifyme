// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 通知解析器（[WeChatNotificationParser] / [GenericNotificationParser] /
 * [PrefixImParser]）与 [AppSourceRegistry] 路由的测试。
 *
 * 全部以纯数据 [RawNotification] 喂入，不需要 Robolectric/通知框架。
 */
class NotificationParserTest {

    private val wx = AppSourceRegistry.PKG_WECHAT

    private fun raw(
        pkg: String = wx,
        title: String = "",
        text: String = "",
        postTime: Long = 5_000L,
        bigText: String = "",
        subText: String = "",
        appLabel: String = "",
        styleMessages: List<RawStyleMessage> = emptyList()
    ) = RawNotification(pkg, appLabel, title, text, postTime, bigText, subText, styleMessages)

    // ---------------- WeChatNotificationParser ----------------

    @Test
    fun `微信 群聊 MessagingStyle 取最后一条`() {
        val m = WeChatNotificationParser.parse(
            raw(
                title = "项目群",
                styleMessages = listOf(
                    RawStyleMessage("李四", "在吗", 3_000L),
                    RawStyleMessage("张三", "明天开会", 4_000L)
                )
            )
        )!!
        assertEquals("张三", m.sender)
        assertEquals("明天开会", m.text)
        assertEquals(4_000L, m.timestamp)
        assertEquals("项目群", m.conversation)
        assertTrue(m.isGroup)
        assertEquals(wx, m.pkg)
    }

    @Test
    fun `微信 style 发言人为空时回落标题`() {
        val m = WeChatNotificationParser.parse(
            raw(title = "项目群", styleMessages = listOf(RawStyleMessage("", "收到", 4_000L)))
        )!!
        assertEquals("项目群", m.sender)
    }

    @Test
    fun `微信 style 时间戳非正时回落通知时间`() {
        val m = WeChatNotificationParser.parse(
            raw(postTime = 9_000L, title = "群", styleMessages = listOf(RawStyleMessage("李四", "好", 0L)))
        )!!
        assertEquals(9_000L, m.timestamp)
    }

    @Test
    fun `微信 style 末条正文为空返回 null`() {
        assertNull(
            WeChatNotificationParser.parse(
                raw(title = "群", styleMessages = listOf(RawStyleMessage("李四", "   ", 1L)))
            )
        )
    }

    @Test
    fun `微信 私聊用标题与正文`() {
        val m = WeChatNotificationParser.parse(raw(title = "张三", text = "你好"))!!
        assertEquals("张三", m.sender)
        assertEquals("你好", m.text)
        assertEquals(5_000L, m.timestamp)
        assertFalse(m.isGroup)
    }

    @Test
    fun `微信 私聊正文空返回 null`() {
        assertNull(WeChatNotificationParser.parse(raw(title = "张三", text = "")))
    }

    @Test
    fun `微信 appLabel 透传`() {
        val m = WeChatNotificationParser.parse(raw(title = "张三", text = "好", appLabel = "微信"))!!
        assertEquals("微信", m.appLabel)
    }

    // ---------------- GenericNotificationParser ----------------

    @Test
    fun `通用 普通通知 title 为会话 text 为正文`() {
        val m = GenericNotificationParser.parse(raw(pkg = "com.example.app", title = "订单", text = "已发货"))!!
        assertEquals("订单", m.sender)
        assertEquals("订单", m.conversation)
        assertEquals("已发货", m.text)
        assertFalse(m.isGroup)
    }

    @Test
    fun `通用 bigText 优先于 text`() {
        val m = GenericNotificationParser.parse(raw(title = "订单", text = "短", bigText = "完整内容"))!!
        assertEquals("完整内容", m.text)
    }

    @Test
    fun `通用 text 空时回落 subText`() {
        val m = GenericNotificationParser.parse(raw(title = "账号", subText = "副标题内容"))!!
        assertEquals("副标题内容", m.text)
    }

    @Test
    fun `通用 标题空时回落 appLabel 再回落包名`() {
        assertEquals(
            "我的 App",
            GenericNotificationParser.parse(raw(pkg = "com.example.app", appLabel = "我的 App", text = "x"))!!.conversation
        )
        assertEquals(
            "com.example.app",
            GenericNotificationParser.parse(raw(pkg = "com.example.app", text = "x"))!!.conversation
        )
    }

    @Test
    fun `通用 MessagingStyle 发言人不同于会话名判群聊`() {
        val m = GenericNotificationParser.parse(
            raw(
                pkg = "com.example.app", title = "部门群",
                styleMessages = listOf(RawStyleMessage("王五", "收到", 4_000L))
            )
        )!!
        assertTrue(m.isGroup)
        assertEquals("王五", m.sender)
    }

    @Test
    fun `通用 MessagingStyle 发言人相同判私聊`() {
        val m = GenericNotificationParser.parse(
            raw(
                pkg = "com.example.app", title = "张三",
                styleMessages = listOf(RawStyleMessage("张三", "在", 4_000L))
            )
        )!!
        assertFalse(m.isGroup)
    }

    @Test
    fun `通用 什么内容都没有返回 null`() {
        assertNull(GenericNotificationParser.parse(raw(pkg = "com.example.app", title = "空")))
    }

    // ---------------- PrefixImParser ----------------

    @Test
    fun `企业IM 全角冒号切发言人判群聊`() {
        val m = PrefixImParser.parse(raw(pkg = AppSourceRegistry.PKG_FEISHU, title = "项目群", text = "李四：明天开会"))!!
        assertEquals("李四", m.sender)
        assertEquals("明天开会", m.text)
        assertTrue(m.isGroup)
        assertEquals("项目群", m.conversation)
    }

    @Test
    fun `企业IM 半角冒号加空格也能切`() {
        val m = PrefixImParser.parse(raw(pkg = AppSourceRegistry.PKG_DINGTALK, title = "群", text = "李四: 明天开会"))!!
        assertEquals("李四", m.sender)
        assertEquals("明天开会", m.text)
        assertTrue(m.isGroup)
    }

    @Test
    fun `企业IM 没有前缀按私聊 正文原样保留`() {
        val m = PrefixImParser.parse(raw(pkg = AppSourceRegistry.PKG_FEISHU, title = "张三", text = "你好"))!!
        assertEquals("张三", m.sender)
        assertEquals("你好", m.text)
        assertFalse(m.isGroup)
    }

    @Test
    fun `企业IM 前缀等于会话名时不判群聊`() {
        val m = PrefixImParser.parse(raw(title = "张三", text = "张三: 你好"))!!
        assertFalse(m.isGroup)
        assertEquals("你好", m.text)
    }

    @Test
    fun `企业IM 前缀超长不切`() {
        // 22 个字符（> MAX_SENDER_PREFIX_LEN=20）：宁可整句保留也不猜发言人
        val longName = "一个超级无敌特别非常极其十分很长的发送者名称"
        val m = PrefixImParser.parse(raw(title = "群", text = "$longName：内容"))!!
        assertFalse(m.isGroup)
        assertEquals("$longName：内容", m.text)
    }

    @Test
    fun `企业IM 提醒开头属于已知误判面 会被当发言人`() {
        // 记录现状，而不是假装它不存在；误判率高就把该源换回 GenericNotificationParser
        val m = PrefixImParser.parse(raw(title = "群", text = "提醒：明天放假"))!!
        assertEquals("提醒", m.sender)
        assertEquals("明天放假", m.text)
        assertTrue(m.isGroup)
    }

    @Test
    fun `企业IM 给了 MessagingStyle 时优先使用`() {
        val m = PrefixImParser.parse(
            raw(
                title = "项目群",
                styleMessages = listOf(RawStyleMessage("李四", "在吗", 4_000L))
            )
        )!!
        assertEquals("李四", m.sender)
        assertEquals("在吗", m.text)
    }

    // ---------------- 注册表路由 ----------------

    @Test
    fun `parserFor 已收录给专属 未收录回落通用`() {
        assertEquals(WeChatNotificationParser, AppSourceRegistry.parserFor(wx))
        assertEquals(PrefixImParser, AppSourceRegistry.parserFor(AppSourceRegistry.PKG_FEISHU))
        assertEquals(PrefixImParser, AppSourceRegistry.parserFor(AppSourceRegistry.PKG_DINGTALK))
        assertEquals(GenericNotificationParser, AppSourceRegistry.parserFor("com.unknown.app"))
    }

    @Test
    fun `a11yConfigFor 只有微信非空`() {
        assertEquals(5, AppSourceRegistry.a11yConfigFor(wx)?.titleViewIds?.size)
        assertNull(AppSourceRegistry.a11yConfigFor(AppSourceRegistry.PKG_FEISHU))
        assertNull(AppSourceRegistry.a11yConfigFor(AppSourceRegistry.PKG_DINGTALK))
    }

    @Test
    fun `labelFor 内置源有名 未收录 null`() {
        assertEquals("微信", AppSourceRegistry.labelFor(wx))
        assertEquals("飞书", AppSourceRegistry.labelFor(AppSourceRegistry.PKG_FEISHU))
        assertNull(AppSourceRegistry.labelFor("com.unknown.app"))
    }

    @Test
    fun `isBlocked 自身与系统包永不可入库`() {
        assertTrue(AppSourceRegistry.isBlocked(BuildConfig.APPLICATION_ID))
        assertTrue(AppSourceRegistry.isBlocked("android"))
        assertTrue(AppSourceRegistry.isBlocked("com.android.systemui"))
        assertFalse(AppSourceRegistry.isBlocked(wx))
    }

    @Test
    fun `knownSources 含微信飞书钉钉三个源`() {
        val pkgs = AppSourceRegistry.knownSources().map { it.pkg }.toSet()
        assertEquals(setOf(wx, AppSourceRegistry.PKG_FEISHU, AppSourceRegistry.PKG_DINGTALK), pkgs)
    }
}
