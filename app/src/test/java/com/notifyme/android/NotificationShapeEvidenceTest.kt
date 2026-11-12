package com.notifyme.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 通知来源解析形态的「口径回归」。
 *
 * 与 NotificationParserTest 的分工：那边测解析器边界（分隔符、前缀长度、空正文…），
 * 这里固化**真实通知形状**到入库字段（sender / text / conversation / isGroup / pkg）的映射，
 * 即「某家 App 的通知长什么样，解析后应该落成什么」。改解析器或改监听器抽取字段时，
 * 本类先红，逼你确认口径变化是有意的。
 *
 * 采集方法（记录在案，待真机补真实 dump）：
 *
 *   adb -s <serial> shell dumpsys notification --noredact <pkg>
 *
 * 本类只关心解析器实际读到的公开 extras：
 *
 *   EXTRA_TITLE / EXTRA_TEXT / EXTRA_BIG_TEXT / EXTRA_SUB_TEXT / EXTRA_MESSAGES
 *
 * 以及 postTime 与 pkg。其余字段（tickerText、category、flags、smallIcon 等）
 * 由 NotifyMeListener 过滤或忽略，与入库口径无关。
 *
 * **样本是按各 App 通知样态构造的合成数据，不是真机 dump 原文**：真机取证仍待补
 * （见 docs/ROADMAP.md M2「a11y 直读扩展」）。拿到真实 dump 后只需把 shape(...) 的
 * 实参换成实测值，断言随之更新。
 */
class NotificationShapeEvidenceTest {

    private fun shape(
        pkg: String,
        title: String,
        text: String,
        bigText: String = "",
        subText: String = "",
        style: List<RawStyleMessage> = emptyList()
    ): RawNotification = RawNotification(
        pkg = pkg,
        appLabel = AppSourceRegistry.labelFor(pkg).orEmpty(),
        title = title,
        text = text,
        postTime = POST_TIME,
        bigText = bigText,
        subText = subText,
        styleMessages = style
    )

    private fun parsed(
        pkg: String,
        title: String,
        text: String,
        bigText: String = "",
        subText: String = "",
        style: List<RawStyleMessage> = emptyList()
    ): ChatMessage? = AppSourceRegistry.parserFor(pkg).parse(
        shape(pkg, title, text, bigText, subText, style)
    )

    // ---------------- 飞书（Lark）样本形态 ----------------

    @Test
    fun `飞书 单人群通知：标题即对方，正文无发言人前缀`() {
        val m = parsed(
            AppSourceRegistry.PKG_FEISHU,
            title = "王五",
            text = "明天下午三点的评审改到周五了"
        )!!
        assertEquals("王五", m.sender)
        assertEquals("明天下午三点的评审改到周五了", m.text)
        assertEquals("王五", m.conversation)
        assertEquals(false, m.isGroup)
        assertEquals(AppSourceRegistry.PKG_FEISHU, m.pkg)
    }

    @Test
    fun `飞书 群通知：正文全角冒号前缀切出发言人`() {
        val m = parsed(
            AppSourceRegistry.PKG_FEISHU,
            title = "项目攻坚群",
            text = "李四：接口文档我更新好了，麻烦确认一下"
        )!!
        assertEquals("李四", m.sender)
        assertEquals("接口文档我更新好了，麻烦确认一下", m.text)
        assertEquals("项目攻坚群", m.conversation)
        assertEquals(true, m.isGroup)
    }

    @Test
    fun `飞书 群通知展开态 bigText 优先于折叠态 text`() {
        val m = parsed(
            AppSourceRegistry.PKG_FEISHU,
            title = "项目攻坚群",
            text = "赵六：周末加班",
            bigText = "赵六：周末加班需要两位同学支持，报名到周五"
        )!!
        assertEquals("赵六", m.sender)
        assertEquals("周末加班需要两位同学支持，报名到周五", m.text)
        assertEquals(true, m.isGroup)
    }

    // ---------------- 钉钉样本形态 ----------------

    @Test
    fun `钉钉 群通知：半角冒号加空格前缀切出发言人`() {
        val m = parsed(
            AppSourceRegistry.PKG_DINGTALK,
            title = "运维值班群",
            text = "张三: 生产库告警已经恢复了"
        )!!
        assertEquals("张三", m.sender)
        assertEquals("生产库告警已经恢复了", m.text)
        assertEquals(true, m.isGroup)
        assertEquals(AppSourceRegistry.PKG_DINGTALK, m.pkg)
    }

    @Test
    fun `钉钉 群通知 MessagingStyle 明细优先于正文前缀`() {
        val m = parsed(
            AppSourceRegistry.PKG_DINGTALK,
            title = "运维值班群",
            text = "（3 条）李四：我这边好了",
            style = listOf(
                RawStyleMessage("李四", "我这边好了", 4_000L),
                RawStyleMessage("王五", "收到", 4_500L)
            )
        )!!
        assertEquals("王五", m.sender)
        assertEquals("收到", m.text)
        assertEquals(4_500L, m.timestamp)
        assertEquals(true, m.isGroup)
    }

    @Test
    fun `钉钉 私聊通知：前缀与会话名相同只切前缀 不判群聊`() {
        val m = parsed(
            AppSourceRegistry.PKG_DINGTALK,
            title = "项目经理",
            text = "项目经理: 明天上午十点对齐进度"
        )!!
        assertEquals("项目经理", m.sender)
        // 现状口径：前缀照样切掉（splitSenderPrefix 不看会话名），只是 isGroup=false。
        assertEquals("明天上午十点对齐进度", m.text)
        assertEquals("项目经理", m.conversation)
        assertEquals(false, m.isGroup)
    }

    // ---------------- 未知 App 形态 ----------------

    @Test
    fun `未收录 App 回落通用解析器：无发言人前缀时按私聊入库`() {
        val pkg = "com.example.unlisted"
        val m = parsed(pkg, title = "短信通知", text = "您的快递已到驿站，取件码 1234")!!
        assertEquals("短信通知", m.sender)
        assertEquals("短信通知", m.conversation)
        assertEquals(false, m.isGroup)
        assertEquals(pkg, m.pkg)
    }

    @Test
    fun `注册表路由：已收录源解析器与通用兜底不混`() {
        assertEquals(
            PrefixImParser,
            AppSourceRegistry.parserFor(AppSourceRegistry.PKG_FEISHU)
        )
        assertEquals(
            PrefixImParser,
            AppSourceRegistry.parserFor(AppSourceRegistry.PKG_DINGTALK)
        )
        assertTrue(
            AppSourceRegistry.parserFor("com.example.unlisted") is GenericNotificationParser
        )
    }

    // ---------------- 口径不变量 ----------------

    @Test
    fun `时间戳缺失时一律回落 postTime`() {
        val m = parsed(
            AppSourceRegistry.PKG_FEISHU,
            title = "王五",
            text = "在吗",
            style = listOf(RawStyleMessage("王五", "在吗", 0L))
        )!!
        assertEquals(POST_TIME, m.timestamp)
    }

    @Test
    fun `正文为空的通知不入库存`() {
        assertEquals(null, parsed(AppSourceRegistry.PKG_FEISHU, title = "群", text = "  "))
        assertEquals(null, parsed(AppSourceRegistry.PKG_DINGTALK, title = "群", text = ""))
    }

    private companion object {
        const val POST_TIME = 1_762_000_000_000L
    }
}