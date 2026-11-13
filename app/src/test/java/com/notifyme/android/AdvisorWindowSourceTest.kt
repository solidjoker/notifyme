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
 * 顾问通道的窗口来源测试（[AdvisorDigestCore.windowsOf] + [FeedWindowCore]）。
 *
 * 为什么值得单独一个文件：顾问复盘摘要（[AdvisorWorker.buildDigest]）与顾问报告上报
 * （[SyncWorker.pushAdvisor]）此前都用 MessageStore.readRecent(500) 反推窗口级姓名映射 ——
 * 那是**跨会话**的最近 500 条，几个群刷满窗口后安静会话在窗口里一条都没有，
 * nameMapFor 如实返回空表，于是该会话的会话名与正文人名**原样出设备**，
 * 而 PRIVACY.md 承诺的是窗口级别名。这是 readRecent 同源隐患的第六处。
 *
 * 固定盐向量（Python hashlib: sha256(salt + 0x00 + name)[:6]），saltA = 0x00..0x1f：
 * 张三=baf6b4 / 李四=3021d8 / 甲项目群=6f678e / 闲聊群=a23992。
 */
class AdvisorWindowSourceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var dir: File
    private lateinit var core: MessageStoreCore

    private val saltA = ByteArray(32) { it.toByte() }
    private val wechat = AppSourceRegistry.PKG_WECHAT

    @Before
    fun setUp() {
        dir = tmp.newFolder("files")
        core = MessageStore.coreIn(dir)
    }

    private fun msg(
        text: String,
        ts: Long,
        conversation: String,
        sender: String = conversation,
        isGroup: Boolean = false,
        pkg: String = wechat
    ) = ChatMessage(
        sender = sender,
        text = text,
        timestamp = ts,
        conversation = conversation,
        isGroup = isGroup,
        pkg = pkg
    )

    /** 一个刷屏群 600 条 + 安静会话 2 条（时间更早）。 */
    private fun seedNoisyNeighbour() {
        repeat(600) { i -> core.append(msg("噪声" + i, 1_000_000L + i * 1_000L, "闲聊群", isGroup = true)) }
        core.append(msg("明天交付", 100_000L, "甲项目群", sender = "李四", isGroup = true))
        core.append(msg("收到", 101_000L, "甲项目群", sender = "张三", isGroup = true))
    }

    // ---------------- windowsOf ----------------

    @Test
    fun windowsOfKeepsEveryConversation() {
        seedNoisyNeighbour()

        val windows = AdvisorDigestCore.windowsOf(core.feedGroups())

        assertEquals(2, windows.size)
        val quiet = windows.getValue(AdvisorDigestCore.keyOf(wechat, "甲项目群"))
        assertEquals("甲项目群", quiet.conversation)
        assertTrue(quiet.isGroup)
        assertEquals(listOf("李四", "张三"), quiet.senders)
    }

    @Test
    fun windowsOfAliasesNamesOfQuietConversation() {
        seedNoisyNeighbour()

        val windows = AdvisorDigestCore.windowsOf(core.feedGroups())
        val map = AdvisorDigestCore.nameMapFor(windows, wechat, "甲项目群", saltA)

        assertEquals("[群6f678e]", map["甲项目群"])
        assertEquals("[人名3021d8]", map["李四"])
        assertEquals("[人名baf6b4]", map["张三"])
    }

    @Test
    fun oldReadRecentLosesQuietConversation() {
        // 对照实验：钉死「旧写法为什么会让姓名映射静默失效」，否则后人会以为只是洁癖
        seedNoisyNeighbour()

        val byOld = AdvisorDigestCore.windows(core.readRecent(500))

        assertNull(byOld[AdvisorDigestCore.keyOf(wechat, "甲项目群")])
        assertTrue(AdvisorDigestCore.nameMapFor(byOld, wechat, "甲项目群", saltA).isEmpty())
    }

    @Test
    fun windowsOfEmptyStore() {
        assertTrue(AdvisorDigestCore.windowsOf(core.feedGroups()).isEmpty())
    }

    @Test
    fun mergedNameMapCoversEveryConversation() {
        seedNoisyNeighbour()

        val windows = AdvisorDigestCore.windowsOf(core.feedGroups())
        val merged = AdvisorDigestCore.mergedNameMap(windows, saltA)

        // 报告正文是聚合文本，两种会话的人名都必须能换
        assertEquals("[群a23992]", merged["闲聊群"])
        assertEquals("[群6f678e]", merged["甲项目群"])
        assertEquals("[人名3021d8]", merged["李四"])
        assertEquals("[人名baf6b4]", merged["张三"])
    }

    @Test
    fun oldMergedNameMapMissesQuietConversation() {
        seedNoisyNeighbour()

        val byOld = AdvisorDigestCore.windows(core.readRecent(500))
        val merged = AdvisorDigestCore.mergedNameMap(byOld, saltA)

        assertEquals("[群a23992]", merged["闲聊群"])
        assertFalse(merged.containsKey("甲项目群"))
        assertFalse(merged.containsKey("李四"))
    }

    @Test
    fun windowsOfKeepsDigestWording() {
        seedNoisyNeighbour()
        val windows = AdvisorDigestCore.windowsOf(core.feedGroups())
        val case = AnalysisCaseRecord(
            caseId = "c1",
            conversation = "甲项目群",
            windowEnd = 101_000L,
            messageCount = 2,
            analyzedAt = 102_000L,
            protocol = "laya",
            rawJson = "{}",
            pkg = wechat,
            s1NeedAction = true,
            s1NeedActionProb = 0.8,
            s1Importance = 7.0,
            s1DueWindow = "today",
            s1Topic = "work_task",
            s1Confidence = 0.9,
            escalated = false,
            s2Summary = "",
            s2DueTime = "",
            s2SuggestedAction = "",
            s2Tasks = emptyList(),
            filtered = false
        )

        val out = AdvisorDigestCore.digest(listOf(case), windows, saltA, timeLabel = { "T" })

        assertTrue(out.startsWith("最近 7 天共 1 个分析 case："))
        assertTrue(out.contains("「[群6f678e]」 T need_action=0.80 importance=7.0 topic=work_task"))
    }

    @Test
    fun windowsOfKeepsQuietConversationWhenBudgetThin() {
        // 会话数逼近上限时 perConv 被压到 MIN_PER_CONV，仍必须每个会话都进窗口
        repeat(30) { conv ->
            repeat(40) { i ->
                core.append(msg("m" + i, 1_000_000L + conv * 10_000L + i * 1_000L, "群" + conv, isGroup = true))
            }
        }
        core.append(msg("安静的正文", 5_000L, "安静群", sender = "王五", isGroup = true))

        val windows = AdvisorDigestCore.windowsOf(core.feedGroups())

        val quiet = windows[AdvisorDigestCore.keyOf(wechat, "安静群")]
        assertEquals(listOf("王五"), quiet?.senders)
    }
}
