// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [CandidateWindowCore] 的候选窗口口径测试。
 *
 * 为什么值得测：分析候选此前是 readRecent(500).groupBy { convKey } ——
 * 跨会话的最近 500 条。几个群刷满窗口，安静但有待办的会话在候选里一条都没有，
 * 永远不会被分析（数据其实还在 messages.jsonl 里）。这是继详情页 / 日历补上下文 /
 * 回看原文之后的第四处同源隐患，改窗口大小、保留名额或排序方向前必跑本文件。
 */
class CandidateWindowCoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var dir: File
    private lateinit var core: MessageStoreCore

    private val wechat = "com.tencent.mm"
    private val feishu = "com.ss.android.lark"

    @Before
    fun setUp() {
        dir = tmp.newFolder("files")
        core = MessageStore.coreIn(dir)
    }

    private fun msg(
        text: String,
        ts: Long,
        conversation: String = "张三",
        pkg: String = wechat,
        sender: String = conversation
    ) = ChatMessage(
        sender = sender,
        text = text,
        timestamp = ts,
        conversation = conversation,
        isGroup = false,
        pkg = pkg
    )

    // ---------------- group ----------------

    @Test
    fun `group 空输入返回空表`() {
        assertTrue(CandidateWindowCore.group(emptyList()).isEmpty())
    }

    @Test
    fun `group perConv 非正数时不产出任何候选`() {
        val messages = listOf(msg("在吗", 1_000L))
        assertTrue(CandidateWindowCore.group(messages, perConv = 0).isEmpty())
        assertTrue(CandidateWindowCore.group(messages, perConv = -3).isEmpty())
    }

    @Test
    fun `group 保持会话首次出现顺序`() {
        val grouped = CandidateWindowCore.group(
            listOf(
                msg("a", 1_000L, conversation = "乙"),
                msg("b", 2_000L, conversation = "甲"),
                msg("c", 3_000L, conversation = "乙")
            )
        )
        assertEquals(listOf("乙", "甲"), grouped.keys.map { it.conversation })
    }

    @Test
    fun `group 组内按时间升序`() {
        val grouped = CandidateWindowCore.group(
            listOf(
                msg("晚", 3_000L),
                msg("早", 1_000L),
                msg("中", 2_000L)
            )
        )
        assertEquals(listOf("早", "中", "晚"), grouped.values.single().map { it.text })
    }

    @Test
    fun `group 未标注日期按 0 排在组内最前`() {
        val grouped = CandidateWindowCore.group(
            listOf(
                msg("真实", 5_000L),
                msg("估算失败", 0L),
                msg("负时间", -1L)
            )
        )
        val texts = grouped.values.single().map { it.text }
        assertEquals("估算失败", texts[0])
        assertEquals("负时间", texts[1])
        assertEquals("真实", texts[2])
    }

    @Test
    fun `group 超过 perConv 时保留最近的若干条`() {
        val messages = (1..5).map { i -> msg("m$i", i * 1_000L) }
        val grouped = CandidateWindowCore.group(messages, perConv = 3)
        assertEquals(listOf("m3", "m4", "m5"), grouped.values.single().map { it.text })
    }

    @Test
    fun `group 恰好等于 perConv 时不做裁剪`() {
        val messages = (1..3).map { i -> msg("m$i", i * 1_000L) }
        val grouped = CandidateWindowCore.group(messages, perConv = 3)
        assertEquals(3, grouped.values.single().size)
    }

    @Test
    fun `group 同名会话跨 App 不合并`() {
        val grouped = CandidateWindowCore.group(
            listOf(
                msg("微信侧", 1_000L, pkg = wechat),
                msg("飞书侧", 2_000L, pkg = feishu)
            )
        )
        assertEquals(2, grouped.size)
        assertEquals(listOf("微信侧"), grouped.getValue(ConvKey(wechat, "张三")).map { it.text })
        assertEquals(listOf("飞书侧"), grouped.getValue(ConvKey(feishu, "张三")).map { it.text })
    }

    @Test
    fun `candidateCount 等于会话键数`() {
        val grouped = CandidateWindowCore.group(
            listOf(msg("a", 1_000L, conversation = "甲"), msg("b", 2_000L, conversation = "乙"))
        )
        assertEquals(2, CandidateWindowCore.candidateCount(grouped))
        assertEquals(0, CandidateWindowCore.candidateCount(emptyMap()))
    }

    @Test
    fun `perConv 比一个 case 的窗口更宽`() {
        // 契约：一个 case 只吃最近 WINDOW_SIZE 条；候选若比它还窄，
        // 范围过滤一旦砍掉新消息，本会话就整块没内容
        assertTrue(CandidateWindowCore.PER_CONV >= AnalysisCase.WINDOW_SIZE)
        assertTrue(CandidateWindowCore.PER_CONV > AnalysisCase.WINDOW_SIZE)
    }

    // ---------------- MessageStoreCore.candidateGroups（集成） ----------------

    @Test
    fun `candidateGroups 文件不存在返回空表`() {
        assertTrue(core.candidateGroups().isEmpty())
    }

    @Test
    fun `candidateGroups 跳过损坏行但仍保留其余消息`() {
        core.append(msg("正常", 1_000L))
        File(dir, "messages.jsonl").appendText("这不是JSON{\n")
        core.append(msg("也正常", 2_000L))

        val grouped = core.candidateGroups()
        assertEquals(listOf("正常", "也正常"), grouped.values.single().map { it.text })
    }

    @Test
    fun `candidateGroups 别的会话刷屏时本会话仍在候选里`() {
        // 600 条噪声（另一个群，时间更近）+ 本会话 5 条较早的消息。
        // 旧写法 readRecent(500).groupBy{convKey} 在这个场景里本会话一条都读不到。
        repeat(600) { i ->
            core.append(msg("噪声$i", 1_000_000L + i * 1_000L, conversation = "闲聊群"))
        }
        repeat(5) { i ->
            core.append(msg("我的$i", 100_000L + i * 1_000L, conversation = "项目群"))
        }

        val grouped = core.candidateGroups()
        val mine = grouped[ConvKey(wechat, "项目群")]

        assertEquals(5, mine?.size)
        assertEquals(listOf("我的0", "我的1", "我的2", "我的3", "我的4"), mine?.map { it.text })
    }

    @Test
    fun `旧写法 readRecent 在同样场景下确实丢掉本会话`() {
        // 对照实验：钉死「旧写法为什么会漏」，否则后人会以为这次改动只因洁癖
        repeat(600) { i ->
            core.append(msg("噪声$i", 1_000_000L + i * 1_000L, conversation = "闲聊群"))
        }
        repeat(5) { i ->
            core.append(msg("我的$i", 100_000L + i * 1_000L, conversation = "项目群"))
        }

        val byOld = core.readRecent(500).groupBy { it.convKey }
        assertNull(byOld[ConvKey(wechat, "项目群")])
    }

    @Test
    fun `candidateGroups 每个会话各自保留 perConv 条互不挤占`() {
        repeat(80) { i -> core.append(msg("甲$i", 500_000L + i * 1_000L, conversation = "甲")) }
        repeat(80) { i -> core.append(msg("乙$i", 500_000L + i * 1_000L, conversation = "乙")) }

        val grouped = core.candidateGroups(perConv = 10)
        assertEquals(10, grouped.getValue(ConvKey(wechat, "甲")).size)
        assertEquals(10, grouped.getValue(ConvKey(wechat, "乙")).size)
        assertEquals("甲79", grouped.getValue(ConvKey(wechat, "甲")).last().text)
        assertEquals("乙79", grouped.getValue(ConvKey(wechat, "乙")).last().text)
    }

    @Test
    fun `candidateGroups 的保留余量扛得住范围把新消息砍掉`() {
        // 本会话 60 条：40 条落在「过去区间」内、20 条更新（区间外）。
        // 每会话只留 10 条时，这 20 条新的会把区间内的消息全挤掉 → case 为空；
        // 留 50 条时仍有 30 条区间内消息可用。
        for (i in 0 until 40) {
            core.append(msg("区间内$i", 100_000L + i * 1_000L, conversation = "项目群"))
        }
        for (i in 0 until 20) {
            core.append(msg("区间外$i", 900_000L + i * 1_000L, conversation = "项目群"))
        }

        val range = AnalysisScopeCore.Range(0L, 500_000L)

        val narrow = core.candidateGroups(perConv = 10)
            .getValue(ConvKey(wechat, "项目群"))
        val narrowInRange = AnalysisScopeCore.keep(narrow, range) { it.timestamp }
        assertTrue("只留 10 条时区间内一条都不剩", narrowInRange.isEmpty())

        val wide = core.candidateGroups().getValue(ConvKey(wechat, "项目群"))
        val wideInRange = AnalysisScopeCore.keep(wide, range) { it.timestamp }
        assertEquals(30, wideInRange.size)
        val case = AnalysisCase.fromMessages(wechat, "项目群", wideInRange)
        assertEquals("区间内39", case?.messages?.last()?.text)
    }
}
