// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [AllTimeStatsCore] 的聚合口径测试 + 与 [MessageStoreCore.scanAll] 的接线回归。
 *
 * 为什么值得测：采集诊断页那四个数字（累计消息 / 会话总数 / 自发消息 / 每 App 群聊数）
 * 此前是 Activity 私有类里的一段纯函数、取数还走 readRecent(200000) —— 而 readRecent 的
 * 窗口写死 5000 行，历史一长数字就静默偏小，截断提示的判据又是拿「文件行数」比「解析出的
 * 消息数」，两件事混在一起。这里把口径钉住，顺带用「本会话在文件头 + 6000 行噪声」
 * 复现旧实现的失效。
 */
class AllTimeStatsCoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var dir: File
    private lateinit var core: MessageStoreCore

    private val dataFile: File get() = File(dir, "messages.jsonl")

    @Before
    fun setUp() {
        dir = tmp.newFolder("files")
        core = MessageStore.coreIn(dir) {}
    }

    private fun msg(
        text: String,
        ts: Long,
        conversation: String = "张三",
        sender: String = "张三",
        isGroup: Boolean = false,
        pkg: String = AppSourceRegistry.PKG_WECHAT
    ) = ChatMessage(
        sender = sender,
        text = text,
        timestamp = ts,
        conversation = conversation,
        isGroup = isGroup,
        pkg = pkg
    )

    // ---------------- summarize ----------------

    @Test
    fun `summarize 统计条数会话自发消息与来源`() {
        val msgs = listOf(
            msg("1", 1_000L, conversation = "甲"),
            msg("2", 2_000L, conversation = "甲", sender = ChatMessage.SENDER_SELF),
            msg("3", 3_000L, conversation = "乙", isGroup = true),
            msg("4", 4_000L, conversation = "丙", pkg = "com.sina.weibo")
        )
        val s = AllTimeStatsCore.summarize(msgs)
        assertEquals(4, s.total)
        assertEquals(3, s.conversations)
        assertEquals(1, s.selfMessages)
        assertEquals(2, s.byPkg.size)
        // 按包名升序："com.sina.weibo" < "com.tencent.mm"（s < t），微信不是第一段
        assertEquals("com.sina.weibo", s.byPkg[0].pkg)
        assertEquals(1, s.byPkg[0].messages)
        assertEquals(AppSourceRegistry.PKG_WECHAT, s.byPkg[1].pkg)
        assertEquals(3, s.byPkg[1].messages)
        assertEquals(2, s.byPkg[1].conversations)
        assertEquals(1, s.byPkg[1].groups)
    }

    @Test
    fun `会话身份是 pkg 加会话名的复合键`() {
        val msgs = listOf(
            msg("1", 1_000L, conversation = "工作群", isGroup = true),
            msg("2", 2_000L, conversation = "工作群", isGroup = true, pkg = "com.sina.weibo")
        )
        val s = AllTimeStatsCore.summarize(msgs)
        // 同名会话在不同 App 下是两条会话，不能合并
        assertEquals(2, s.conversations)
        assertEquals(1, s.byPkg[0].conversations)
        assertEquals(1, s.byPkg[1].conversations)
    }

    @Test
    fun `群聊数按会话计不按消息计`() {
        val msgs = listOf(
            msg("1", 1_000L, conversation = "群A", isGroup = true),
            msg("2", 2_000L, conversation = "群A", isGroup = true),
            msg("3", 3_000L, conversation = "群B", isGroup = true),
            msg("4", 4_000L, conversation = "私聊")
        )
        val s = AllTimeStatsCore.summarize(msgs)
        assertEquals(1, s.byPkg.size)
        assertEquals(3, s.byPkg[0].conversations)
        assertEquals(2, s.byPkg[0].groups)
    }

    @Test
    fun `最近消息时间忽略未标注日期`() {
        val msgs = listOf(
            msg("无时间", 0L, conversation = "甲"),
            msg("负时间", -5L, conversation = "甲"),
            msg("有", 1_700_000_000_000L, conversation = "乙")
        )
        val s = AllTimeStatsCore.summarize(msgs)
        assertEquals(3, s.total)
        assertEquals(1_700_000_000_000L, s.byPkg[0].latest)
    }

    @Test
    fun `全部没有时间戳时 latest 为零`() {
        val s = AllTimeStatsCore.summarize(listOf(msg("x", 0L), msg("y", -1L)))
        assertEquals(0L, s.byPkg[0].latest)
    }

    @Test
    fun `空输入返回全零`() {
        val s = AllTimeStatsCore.summarize(emptyList())
        assertEquals(0, s.total)
        assertEquals(0, s.conversations)
        assertEquals(0, s.selfMessages)
        assertTrue(s.byPkg.isEmpty())
    }

    @Test
    fun `来源按包名升序`() {
        val msgs = listOf(
            msg("1", 1_000L, pkg = "com.tencent.mm"),
            msg("2", 2_000L, pkg = "com.ss.android.lark"),
            msg("3", 3_000L, pkg = "com.alibaba.android.rimet")
        )
        val s = AllTimeStatsCore.summarize(msgs)
        assertEquals(
            listOf("com.alibaba.android.rimet", "com.ss.android.lark", "com.tencent.mm"),
            s.byPkg.map { it.pkg }
        )
    }

    // ---------------- Accumulator ----------------

    @Test
    fun `累加器逐条喂与整批 summarize 结果一致`() {
        val msgs = listOf(
            msg("1", 1_000L, conversation = "甲", sender = ChatMessage.SENDER_SELF),
            msg("2", 2_000L, conversation = "甲"),
            msg("3", 3_000L, conversation = "群", isGroup = true, pkg = "com.sina.weibo")
        )
        val acc = AllTimeStatsCore.Accumulator()
        for (m in msgs) acc.add(m)
        assertEquals(AllTimeStatsCore.summarize(msgs), acc.finish())
    }

    @Test
    fun `累加器空态 finish 可用`() {
        val s = AllTimeStatsCore.Accumulator().finish()
        assertEquals(0, s.total)
        assertTrue(s.byPkg.isEmpty())
    }

    // ---------------- capped ----------------

    @Test
    fun `扫描行数覆盖文件时不算截断`() {
        assertFalse(AllTimeStatsCore.capped(totalLines = 4_000, scannedLines = 4_000))
        assertFalse(AllTimeStatsCore.capped(totalLines = 0, scannedLines = 0))
    }

    @Test
    fun `文件行数多于扫描行数算截断`() {
        assertTrue(AllTimeStatsCore.capped(totalLines = 60_000, scannedLines = 50_000))
    }

    @Test
    fun `损坏行不会让覆盖判定误报截断`() {
        // 文件 10 行、全扫到 10 行，其中 2 行损坏 → summary.total 只有 8，
        // 旧判据 totalLines > total 会误判成「被截断」；capped 只看扫描行数。
        assertFalse(AllTimeStatsCore.capped(totalLines = 10, scannedLines = 10))
    }

    // ---------------- scanAll 接线 ----------------

    @Test
    fun `scanAll 空存储返回零且不崩`() {
        val scan = core.scanAll()
        assertEquals(0, scan.summary.total)
        assertEquals(0, scan.scannedLines)
    }

    @Test
    fun `scanAll 统计全文件而不是尾部五千行`() {
        // 6000 行：前 10 行是本会话（更早），其余 5990 行是噪声会话
        val lines = mutableListOf<String>()
        for (i in 1..10) {
            lines += msg("旧$i", i * 1_000L, conversation = "安静会话").toJson().toString()
        }
        for (i in 1..5_990) {
            lines += msg("噪声$i", 1_000_000L + i * 1_000L, conversation = "吵闹群", isGroup = true)
                .toJson().toString()
        }
        dataFile.writeText(lines.joinToString("\n") + "\n")

        val scan = core.scanAll()
        assertEquals(6_000, scan.scannedLines)
        assertEquals(6_000, scan.summary.total)
        assertEquals(2, scan.summary.conversations)
        assertEquals(1, scan.summary.byPkg.size)
        assertEquals(6_000, scan.summary.byPkg[0].messages)
        assertEquals(2, scan.summary.byPkg[0].conversations)
        assertEquals(1, scan.summary.byPkg[0].groups)
        // 旧实现在这里只能看到尾部 5000 行 → total 5000、会话 1 个
        assertEquals(5_000, core.readRecent(200_000).size)
        assertEquals(1, core.readRecent(200_000).map { it.convKey }.distinct().size)
    }

    @Test
    fun `scanAll 跳过损坏行但计入扫描行数`() {
        val rows = listOf(
            msg("ok", 1_000L).toJson().toString(),
            "{不是 JSON",
            msg("ok2", 2_000L).toJson().toString()
        )
        dataFile.writeText(rows.joinToString("\n") + "\n")
        val scan = core.scanAll()
        assertEquals(3, scan.scannedLines)
        assertEquals(2, scan.summary.total)
        assertFalse(AllTimeStatsCore.capped(totalLines = 3, scannedLines = scan.scannedLines))
    }

    @Test
    fun `scanAll 的扫描上限由调用方指定`() {
        val lines = (1..200).map { msg("m$it", it * 1_000L).toJson().toString() }
        dataFile.writeText(lines.joinToString("\n") + "\n")
        val scan = core.scanAll(limit = 50)
        assertEquals(50, scan.scannedLines)
        assertEquals(50, scan.summary.total)
        assertTrue(AllTimeStatsCore.capped(totalLines = 200, scannedLines = scan.scannedLines))
    }
}
