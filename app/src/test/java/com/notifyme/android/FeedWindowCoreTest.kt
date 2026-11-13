// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [FeedWindowCore] 的首页取样口径测试。
 *
 * 为什么值得测：首页 \`buildFeed\` 此前是 \`MessageStore.readRecent(500)\` —— 跨会话的最近 500 条，
 * 几个群刷满窗口后安静会话一条都进不了首页，首页待办区（\`todoVisibleKeys\`）随之看不到它。
 * 这是同源隐患的第五处（前四处：详情页 / 日历补上下文 / 回看原文 / 分析候选），
 * 改名额、行窗口或截断判定前必跑本文件。
 */
class FeedWindowCoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var dir: File
    private lateinit var core: MessageStoreCore

    private val wechat = "com.tencent.mm"

    @Before
    fun setUp() {
        dir = tmp.newFolder("files")
        core = MessageStore.coreIn(dir)
    }

    private fun msg(
        text: String,
        ts: Long,
        conversation: String = "甲项目群",
        pkg: String = wechat
    ) = ChatMessage(
        sender = conversation,
        text = text,
        timestamp = ts,
        conversation = conversation,
        isGroup = false,
        pkg = pkg
    )

    private fun bucketOf(result: FeedWindowCore.Result, conversation: String) =
        result.buckets.firstOrNull { it.key == ConvKey(wechat, conversation) }

    // ---------------- perConvFor ----------------

    @Test
    fun `perConvFor 会话少时给满单会话上限`() {
        assertEquals(FeedWindowCore.PER_CONV, FeedWindowCore.perConvFor(1))
        assertEquals(FeedWindowCore.PER_CONV, FeedWindowCore.perConvFor(2))
        // 30 个会话：6000 / 30 = 200 = 上限，仍然给满
        assertEquals(200, FeedWindowCore.perConvFor(30))
    }

    @Test
    fun `perConvFor 会话多时按均值摊薄`() {
        // 100 个会话：6000 / 100 = 60，落在 [20, 200] 之间
        assertEquals(60, FeedWindowCore.perConvFor(100))
        // 200 个会话：6000 / 200 = 30
        assertEquals(30, FeedWindowCore.perConvFor(200))
    }

    @Test
    fun `perConvFor 摊薄不低于保底条数`() {
        // 1000 个会话：6000 / 1000 = 6 < MIN_PER_CONV(20) → 抬到保底
        assertEquals(FeedWindowCore.MIN_PER_CONV, FeedWindowCore.perConvFor(1000))
        assertEquals(FeedWindowCore.MIN_PER_CONV, FeedWindowCore.perConvFor(Int.MAX_VALUE))
    }

    @Test
    fun `perConvFor 非正参数保守返回 0`() {
        assertEquals(0, FeedWindowCore.perConvFor(0))
        assertEquals(0, FeedWindowCore.perConvFor(-3))
        assertEquals(0, FeedWindowCore.perConvFor(10, totalBudget = 0))
        assertEquals(0, FeedWindowCore.perConvFor(10, maxPerConv = 0))
    }

    @Test
    fun `perConvFor 保底高于上限时被夹到上限`() {
        // minPerConv=500 > maxPerConv=100 → lower 夹成 100，不会反超上限
        assertEquals(100, FeedWindowCore.perConvFor(1000, maxPerConv = 100, minPerConv = 500))
    }

    // ---------------- group ----------------

    @Test
    fun `group 空输入返回空表并透传 complete`() {
        val all = FeedWindowCore.group(emptyList())
        assertTrue(all.buckets.isEmpty())
        assertTrue(all.complete)

        val partial = FeedWindowCore.group(emptyList(), complete = false)
        assertTrue(partial.buckets.isEmpty())
        assertFalse(partial.complete)
    }

    @Test
    fun `group 非正上限时不产出任何会话`() {
        val messages = listOf(msg("在吗", 1_000L))
        assertTrue(FeedWindowCore.group(messages, maxPerConv = 0).buckets.isEmpty())
        assertTrue(FeedWindowCore.group(messages, maxPerConv = -3).buckets.isEmpty())
        assertTrue(FeedWindowCore.group(messages, maxConversations = 0).buckets.isEmpty())
    }

    @Test
    fun `group 会话按最近一条倒序、组内升序`() {
        val messages = listOf(
            msg("甲1", 1_000L, conversation = "甲"),
            msg("乙1", 5_000L, conversation = "乙"),
            msg("甲2", 9_000L, conversation = "甲"),
            msg("丙1", 3_000L, conversation = "丙")
        )

        val result = FeedWindowCore.group(messages)

        assertEquals(listOf("甲", "乙", "丙"), result.keys().map { it.conversation })
        assertEquals(listOf("甲1", "甲2"), bucketOf(result, "甲")?.messages?.map { it.text })
    }

    @Test
    fun `group 并列最新时间时保持首次出现顺序`() {
        val messages = listOf(
            msg("乙1", 7_000L, conversation = "乙"),
            msg("甲1", 7_000L, conversation = "甲")
        )

        // sortedByDescending 是稳定排序：并列不会因比较器抖动导致首页顺序漂移
        assertEquals(
            listOf("乙", "甲"),
            FeedWindowCore.group(messages).keys().map { it.conversation }
        )
    }

    @Test
    fun `group 单会话超上限时保留最近的若干条`() {
        val messages = (0 until 300).map { msg("第${it}条", 1_000L + it * 1_000L) }

        val bucket = bucketOf(FeedWindowCore.group(messages), "甲项目群")

        assertEquals(FeedWindowCore.PER_CONV, bucket?.messages?.size)
        assertEquals("第299条", bucket?.messages?.last()?.text)
        assertEquals("第100条", bucket?.messages?.first()?.text)
        assertTrue("窗口外还有更早的消息", bucket?.truncated == true)
    }

    @Test
    fun `group 别的会话刷屏时本会话仍自成一桶`() {
        // 600 条噪声（时间更近）+ 本会话 5 条较早 —— 旧写法 readRecent(500) 里本会话一条都读不到
        val noise = (0 until 600).map { msg("噪声${it}", 1_000_000L + it * 1_000L, conversation = "闲聊群") }
        val mine = (0 until 5).map { msg("我的${it}", 100_000L + it * 1_000L) }

        val result = FeedWindowCore.group(noise + mine)

        assertEquals(2, result.size)
        assertEquals(listOf("我的0", "我的1", "我的2", "我的3", "我的4"), bucketOf(result, "甲项目群")?.messages?.map { it.text })
        assertTrue("5 条远未到名额，不算截断", bucketOf(result, "甲项目群")?.truncated == false)
    }

    @Test
    fun `group 未读全时刚好顶到名额也算截断`() {
        val messages = (0 until 5).map { msg("第${it}条", 1_000L + it * 1_000L) }

        // 读到文件头：5 条 <= 名额 5，说明本会话确实只有 5 条 → 条数是确数
        val complete = FeedWindowCore.group(messages, maxPerConv = 5, complete = true)
        assertFalse(complete.buckets.single().truncated)

        // 没读到文件头：同样 5 条，但更早的可能就在窗口外 → 条数只能是下限
        val partial = FeedWindowCore.group(messages, maxPerConv = 5, complete = false)
        assertTrue(partial.buckets.single().truncated)
    }

    @Test
    fun `group 会话数超上限时只留最近的那些`() {
        val messages = (0 until 10).map { msg("会话${it}", 1_000L + it * 1_000L, conversation = "会话${it}") }

        val result = FeedWindowCore.group(messages, maxConversations = 3)

        assertEquals(3, result.size)
        // 最近的三条是 会话9 / 会话8 / 会话7
        assertEquals(listOf("会话9", "会话8", "会话7"), result.keys().map { it.conversation })
    }

    @Test
    fun `group 未标注日期的消息排最前且不顶掉最新`() {
        val messages = listOf(
            msg("无时间", 0L),
            msg("较新", 9_000L),
            msg("较旧", 1_000L)
        )

        val bucket = bucketOf(FeedWindowCore.group(messages), "甲项目群")

        assertEquals(listOf("无时间", "较旧", "较新"), bucket?.messages?.map { it.text })
        assertEquals("较新", bucket?.messages?.last()?.text)
    }

    @Test
    fun `group 名额按会话数摊薄到全表预算内`() {
        // 100 个会话、每会话 300 条 → 每会话应只留 perConvFor(100)=60 条，全表 6000 条
        val messages = ArrayList<ChatMessage>()
        for (c in 0 until 100) {
            for (i in 0 until 300) {
                messages += msg("C${c}-$i", 1_000L + i * 1_000L, conversation = "群$c")
            }
        }

        val result = FeedWindowCore.group(messages)

        assertEquals(100, result.size)
        assertTrue(result.buckets.all { it.messages.size == 60 })
        assertEquals(6_000, result.buckets.sumOf { it.messages.size })
        assertTrue(result.buckets.all { it.truncated })
    }

    // ---------------- Result ----------------

    @Test
    fun `Result toMap 保序且 size 与桶数一致`() {
        val messages = listOf(
            msg("甲1", 9_000L, conversation = "甲"),
            msg("乙1", 5_000L, conversation = "乙"),
            msg("丙1", 1_000L, conversation = "丙")
        )

        val result = FeedWindowCore.group(messages)
        val map = result.toMap()

        assertEquals(3, result.size)
        assertEquals(listOf("甲", "乙", "丙"), map.keys.map { it.conversation })
        assertEquals(listOf("甲1"), map.getValue(ConvKey(wechat, "甲")).map { it.text })
    }

    // ---------------- MessageStoreCore.feedGroups（集成） ----------------

    @Test
    fun `feedGroups 文件不存在返回空表且视为已读全`() {
        val result = core.feedGroups()

        assertTrue(result.buckets.isEmpty())
        assertTrue(result.complete)
    }

    @Test
    fun `feedGroups 跳过损坏行但仍保留其余消息`() {
        core.append(msg("正常", 1_000L))
        File(dir, "messages.jsonl").appendText("这不是JSON{\n")
        core.append(msg("也正常", 2_000L))

        val bucket = bucketOf(core.feedGroups(), "甲项目群")

        assertEquals(listOf("正常", "也正常"), bucket?.messages?.map { it.text })
    }

    @Test
    fun `feedGroups 别的会话刷满行窗口时本会话仍在首页`() {
        // 600 条噪声 + 本会话 4 条较早 —— 本轮修复的目标场景
        repeat(600) { i -> core.append(msg("噪声$i", 1_000_000L + i * 1_000L, conversation = "闲聊群")) }
        repeat(4) { i -> core.append(msg("我的$i", 100_000L + i * 1_000L)) }

        val result = core.feedGroups()

        val mine = bucketOf(result, "甲项目群")
        assertEquals(4, mine?.messages?.size)
        assertEquals(listOf("我的0", "我的1", "我的2", "我的3"), mine?.messages?.map { it.text })
        assertEquals("闲聊群", result.keys().first().conversation)
    }

    @Test
    fun `旧写法 readRecent 在同样场景下确实丢掉本会话`() {
        // 对照实验：钉死「旧写法为什么会漏」，否则后人会以为这次改动只因洁癖
        repeat(600) { i -> core.append(msg("噪声$i", 1_000_000L + i * 1_000L, conversation = "闲聊群")) }
        repeat(4) { i -> core.append(msg("我的$i", 100_000L + i * 1_000L)) }

        val byOld = core.readRecent(500).groupBy { it.convKey }

        assertNull(byOld[ConvKey(wechat, "甲项目群")])
    }

    @Test
    fun `feedGroups 行窗口读满时 complete 为假并按下限标截断`() {
        // 写满 4 行、请求 4 行 → 行窗口可能还有更早的消息，complete=false
        repeat(4) { i -> core.append(msg("第${i}条", 1_000L + i * 1_000L)) }

        val partial = core.feedGroups(maxPerConv = 4, requested = 4)
        assertFalse(partial.complete)
        assertTrue("没读到文件头，刚好顶到名额只能算下限", partial.buckets.single().truncated)

        // 请求 10 行但文件只有 4 行 → 已读到文件头，条数是确数
        val complete = core.feedGroups(maxPerConv = 4, requested = 10)
        assertTrue(complete.complete)
        assertFalse(complete.buckets.single().truncated)
    }

    @Test
    fun `feedGroups requested 非正数时不产出任何会话`() {
        repeat(3) { i -> core.append(msg("第${i}条", 1_000L + i * 1_000L)) }

        val result = core.feedGroups(requested = 0)

        assertTrue(result.buckets.isEmpty())
        assertFalse(result.complete)
    }
}
