// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ForkPrefilter] 的规则测试（fork 1：零网络零成本的本地预筛）。
 *
 * 这个预筛的失败模式是**静默漏分析**：把一条真任务判成 sharp 就地结案，
 * 用户永远不会知道有条消息被跳过了。所以除了「该拦的拦住」，
 * 同样重要的是「不该拦的别拦」——正常分享链接、带文字的表情、长文本都要放行。
 *
 * prob 语义（forks 数组统一口径，勿改）：窗口值得进入 S1 的概率；
 * prob < [ForkPrefilter.SHARP_THRESHOLD] → sharp（就地结案，不调 laya）。
 */
class ForkPrefilterTest {

    private val delta = 1e-9

    private fun kase(vararg texts: String): AnalysisCase = AnalysisCase(
        caseId = "test-case",
        conversation = "张三",
        isGroup = false,
        windowEnd = 1_000L,
        messages = texts.mapIndexed { i, t ->
            ChatMessage(
                sender = "张三",
                text = t,
                timestamp = 1_000L + i,
                conversation = "张三",
                isGroup = false
            )
        }
    )

    private fun eval(vararg texts: String) = ForkPrefilter.evaluate(kase(*texts))

    // ---------------- 放行 ----------------

    @Test
    fun `正常对话窗口原样放行`() {
        val r = eval("明天上午十点评审结算方案", "好的我把上次的意见整理一下")

        assertEquals(1.0, r.prob, delta)
        assertEquals("", r.note)
        assertFalse("正常窗口不该就地结案", r.sharp)
    }

    @Test
    fun `带文字的表情不算纯表情`() {
        val r = eval("[微笑]好的", "收到我看看")
        assertEquals(1.0, r.prob, delta)
        assertEquals("", r.note)
    }

    @Test
    fun `正常分享链接不算广告`() {
        // 必须含 URL 且命中广告词、或去掉 URL 后几乎没内容才算广告，否则误伤正常分享
        val r = eval(
            "看一下这篇讲架构演进的文章 https://example.com/blog/arch",
            "写得挺清楚的，我们也踩过同样的坑"
        )
        assertEquals(1.0, r.prob, delta)
        assertEquals("", r.note)
    }

    @Test
    fun `超过 20 字的文本不走纯表情判定`() {
        // isPureEmoji 有长度上限：长文本即使堆满表情标签也保留分析价值
        val long = "[微笑][微笑][微笑][微笑][微笑][微笑][微笑]"
        assertTrue(long.length > 20)
        val r = eval(long, long)
        assertEquals(1.0, r.prob, delta)
    }

    // ---------------- 窗口太小 ----------------

    @Test
    fun `单条消息窗口降到 0_3 但仍不 sharp`() {
        val r = eval("明天记得交周报")

        assertEquals(0.3, r.prob, delta)
        assertEquals("窗口仅1条", r.note)
        assertFalse("单条真任务仍应进入 S1", r.sharp)
    }

    @Test
    fun `空窗口不抛异常且直接结案`() {
        // 生产路径不会出现（fromMessages 对空列表返回 null），但除零/last() 不能崩
        val r = ForkPrefilter.evaluate(kase())

        assertEquals(0.02, r.prob, delta)
        assertEquals("窗口仅0条；全窗口无文字内容", r.note)
        assertTrue(r.sharp)
    }

    // ---------------- 垃圾消息占比 ----------------

    @Test
    fun `纯表情窗口就地结案`() {
        val r = eval("[微笑]", "[旺柴]")

        assertEquals(0.05, r.prob, delta)
        assertEquals("垃圾消息占比100%（表情2）", r.note)
        assertTrue(r.sharp)
    }

    @Test
    fun `系统提示语窗口就地结案`() {
        val r = eval("张三撤回了一条消息", "李四加入了群聊")

        assertEquals(0.05, r.prob, delta)
        assertEquals("垃圾消息占比100%（系统2）", r.note)
        assertTrue(r.sharp)
    }

    @Test
    fun `各类系统提示语都能识别`() {
        listOf(
            "对方撤回了一条消息",
            "王五邀请你加入群聊",
            "以上是打招呼的内容",
            "你已添加了张三，现在可以开始聊天了",
            "对方不是你的好友"
        ).forEach { text ->
            val r = eval(text, text)
            assertTrue("应当识别为系统消息：$text（note=${r.note}）", r.note.contains("系统2"))
            assertTrue(r.sharp)
        }
    }

    @Test
    fun `广告链接窗口就地结案`() {
        val r = eval(
            "限时优惠券领取 https://example.com/coupon",
            "点击链接抢购 https://example.com/flash"
        )

        assertEquals(0.05, r.prob, delta)
        assertEquals("垃圾消息占比100%（广告2）", r.note)
        assertTrue(r.sharp)
    }

    @Test
    fun `裸链接算广告`() {
        val r = eval("https://t.cn/abc123", "明天下午三点对一下结算口径")

        assertEquals(0.3, r.prob, delta)
        assertEquals("垃圾消息占比50%（广告1）", r.note)
        assertFalse("还有一半是真内容，不该直接结案", r.sharp)
    }

    @Test
    fun `占比刚好一半走 0_3 档`() {
        val r = eval("[微笑]", "这个方案我看完给你反馈")
        assertEquals(0.3, r.prob, delta)
        assertEquals("垃圾消息占比50%（表情1）", r.note)
    }

    @Test
    fun `占比低于一半不扣分`() {
        // 4 条里 1 条垃圾 = 25%，低于 0.5 档，不该有任何惩罚
        val r = eval("[微笑]", "这个方案我看完给你反馈", "另外周报我今晚发你", "辛苦")
        assertEquals(1.0, r.prob, delta)
        assertEquals("", r.note)
    }

    @Test
    fun `混合三类垃圾时 breakdown 按 表情-系统-广告 顺序`() {
        val r = eval("[微笑]", "你已添加了对方", "领取红包 https://example.com/r")

        assertEquals(0.05, r.prob, delta)
        assertEquals("垃圾消息占比100%（表情1/系统1/广告1）", r.note)
    }

    @Test
    fun `空文本按系统消息计`() {
        val r = eval("", "明天开会记得带电脑")
        assertEquals(0.3, r.prob, delta)
        assertEquals("垃圾消息占比50%（系统1）", r.note)
    }

    // ---------------- 全窗口无文字 ----------------

    @Test
    fun `全窗口无文字内容时压到 0_02 并用分号拼接多条依据`() {
        val r = eval("。。。", "！！！")

        assertEquals(0.02, r.prob, delta)
        assertEquals("垃圾消息占比100%（表情2）；全窗口无文字内容", r.note)
        assertTrue(r.sharp)
    }

    @Test
    fun `无文字兜底是取小值而不是再乘一次`() {
        // 已经很低时不该被二次惩罚到更小：min(prob, 0.02) 而非 prob * 0.02
        val r = eval("。。。", "！！！", "……")
        assertEquals(0.02, r.prob, delta)
    }

    // ---------------- 阈值与 prob 口径 ----------------

    @Test
    fun `sharp 阈值是 0_15`() {
        assertEquals(0.15, ForkPrefilter.SHARP_THRESHOLD, delta)
    }

    @Test
    fun `prob 始终落在 0-1 且 sharp 只在低于阈值时为真`() {
        listOf(
            eval("明天上午十点评审结算方案", "好的我整理一下意见"),
            eval("明天记得交周报"),
            eval("[微笑]", "[旺柴]"),
            eval("。。。", "！！！"),
            eval("限时秒杀 https://example.com/s", "点击链接领取优惠券 https://example.com/c")
        ).forEach { r ->
            assertTrue("prob 必须在 0-1，实际 ${r.prob}", r.prob in 0.0..1.0)
            assertEquals(r.prob < ForkPrefilter.SHARP_THRESHOLD, r.sharp)
        }
    }
}
