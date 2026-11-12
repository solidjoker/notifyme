// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 历史重复消息归并的契约测试（[MessageDedupeCore] + [MessageStoreCore.dedupeRepeated]）。
 *
 * 保护的是用户已有的 messages.jsonl：归并只能删「确定是同一句话的重复捕获」，
 * 任何可能误删真实连发消息的情况都必须保持不动。
 */
class MessageDedupeCoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var dir: File
    private lateinit var core: MessageStoreCore

    private val dataFile: File get() = File(dir, "messages.jsonl")

    private val wx = AppSourceRegistry.PKG_WECHAT

    @Before
    fun setUp() {
        dir = tmp.newFolder("files")
        core = MessageStore.coreIn(dir) {}
    }

    private fun writeRaw(vararg lines: String) {
        dataFile.parentFile?.mkdirs()
        dataFile.writeText(lines.joinToString("") { "$it\n" })
    }

    private fun line(
        conversation: String,
        sender: String,
        text: String,
        ts: Long,
        pkg: String = AppSourceRegistry.PKG_WECHAT
    ): String = JSONObject()
        .put("conversation", conversation)
        .put("sender", sender)
        .put("text", text)
        .put("timestamp", ts)
        .put("isGroup", false)
        .put("pkg", pkg)
        .toString()

    // ---------------- 会归并的情形 ----------------

    @Test
    fun `未读计数变化造成的重复会归并成一条并保留最早那条`() {
        writeRaw(
            line("张三", "张三", "[3条]明天上午十点开会", 1_000L),
            line("张三", "张三", "[4条]明天上午十点开会", 2_000L),
            line("张三", "张三", "[5条]明天上午十点开会", 3_000L)
        )

        val plan = MessageDedupeCore.plan(dataFile.readLines().filter { it.isNotBlank() })

        assertEquals("三条同文只留最早一条", 2, plan.removed)
        assertEquals(1, plan.kept.size)
        assertTrue("保留的必须是最早捕获的那条", plan.kept.single().contains("[3条]"))
        val group = plan.groups.single()
        assertEquals(3, group.count)
        assertEquals(2, group.dropCount)
        assertEquals(1_000L, group.firstTs)
        assertEquals(3_000L, group.lastTs)
    }

    @Test
    fun `发言人前缀与会话名不同的群聊消息按同一发送者归并`() {
        writeRaw(
            line("项目攻坚群", "李四", "[2条]李四: 接口文档我更新好了", 1_000L),
            line("项目攻坚群", "李四", "[3条]李四: 接口文档我更新好了", 2_000L)
        )

        val plan = MessageDedupeCore.plan(dataFile.readLines().filter { it.isNotBlank() })

        assertEquals(1, plan.removed)
        assertEquals("李四", plan.groups.single().sender)
    }

    @Test
    fun `老行没切出发言人时先还原真实发送者再归并`() {
        // 旧解析器把 sender 填成了会话名；新解析器会切成「李四」。
        // 若不还原，两行会因 sender 不同被当成不同消息，历史重复永远删不掉。
        writeRaw(
            line("项目攻坚群", "项目攻坚群", "[2条]李四: 接口文档我更新好了", 1_000L),
            line("项目攻坚群", "李四", "[3条]李四: 接口文档我更新好了", 2_000L)
        )

        val plan = MessageDedupeCore.plan(dataFile.readLines().filter { it.isNotBlank() })

        assertEquals(1, plan.removed)
        assertEquals("李四", plan.groups.single().sender)
    }

    @Test
    fun `归并后损坏行原样保留且顺序不变`() {
        val corrupt = "{半截 JSON"
        writeRaw(
            corrupt,
            line("张三", "张三", "[3条]好的", 1_000L),
            line("李四", "李四", "[1条]收到", 1_500L),
            line("张三", "张三", "[4条]好的", 2_000L)
        )

        val plan = MessageDedupeCore.plan(dataFile.readLines().filter { it.isNotBlank() })

        assertEquals(1, plan.removed)
        assertEquals(3, plan.kept.size)
        assertEquals("损坏行必须原样留在原位", corrupt, plan.kept.first())
        assertTrue(plan.kept[1].contains("[3条]好的"))
        assertTrue(plan.kept[2].contains("[1条]收到"))
    }

    @Test
    fun `跨 App 的同名同文不互相归并`() {
        writeRaw(
            line("张三", "张三", "[3条]好的", 1_000L, pkg = wx),
            line("张三", "张三", "[4条]好的", 2_000L, pkg = AppSourceRegistry.PKG_FEISHU)
        )

        val plan = MessageDedupeCore.plan(dataFile.readLines().filter { it.isNotBlank() })

        assertEquals(0, plan.removed)
        assertTrue(plan.isEmpty)
    }

    // ---------------- 不该动的保守情形 ----------------

    @Test
    fun `全是裸正文的相同文本不归并`() {
        // 用户真的连发了两条「好的」：没有未读前缀做证据，一律不动
        writeRaw(
            line("张三", "张三", "好的", 1_000L),
            line("张三", "张三", "好的", 2_000L)
        )

        val plan = MessageDedupeCore.plan(dataFile.readLines().filter { it.isNotBlank() })

        assertEquals(0, plan.removed)
    }

    @Test
    fun `未读前缀不同但剥完正文不同的不归并`() {
        writeRaw(
            line("张三", "张三", "[3条]明天十点开会", 1_000L),
            line("张三", "张三", "[4条]明天十一点开会", 2_000L)
        )

        val plan = MessageDedupeCore.plan(dataFile.readLines().filter { it.isNotBlank() })

        assertEquals(0, plan.removed)
    }

    @Test
    fun `不同发送者的同文不归并`() {
        writeRaw(
            line("项目群", "张三", "[3条]收到", 1_000L),
            line("项目群", "李四", "[4条]收到", 2_000L)
        )

        val plan = MessageDedupeCore.plan(dataFile.readLines().filter { it.isNotBlank() })

        assertEquals(0, plan.removed)
    }

    @Test
    fun `空正文行不参与归并且原样保留`() {
        writeRaw(
            line("张三", "张三", "", 1_000L),
            line("张三", "张三", "", 2_000L)
        )

        val plan = MessageDedupeCore.plan(dataFile.readLines().filter { it.isNotBlank() })

        assertEquals(0, plan.removed)
        assertEquals(2, plan.kept.size)
    }

    @Test
    fun `没有归并对象时计划为空且不产生组`() {
        val plan = MessageDedupeCore.plan(listOf(line("张三", "张三", "你好", 1_000L)))

        assertTrue(plan.isEmpty)
        assertEquals(0, plan.removed)
        assertTrue(plan.groups.isEmpty())
    }

    @Test
    fun `空行列表直接返回空计划`() {
        val plan = MessageDedupeCore.plan(emptyList())

        assertEquals(0, plan.removed)
        assertTrue(plan.kept.isEmpty())
    }

    // ---------------- 预览文案 ----------------

    @Test
    fun `预览优先带上发送者并在超长时截断`() {
        val short = MessageDedupeCore.Group(wx, "项目群", "张三", "收到", 3, 1L, 2L)
        assertEquals("张三：收到", short.preview)

        val long = MessageDedupeCore.Group(wx, "张三", "张三", "好".repeat(80), 2, 1L, 2L)
        assertEquals(41, long.preview.length)
        assertTrue(long.preview.endsWith("…"))
    }

    // ---------------- 存储层接线 ----------------

    @Test
    fun `dedupeRepeated 真的删掉重复行并保留最早一条`() {
        core.append(ChatMessage("张三", "[3条]明天上午十点开会", 1_000L, "张三", false))
        core.append(ChatMessage("张三", "[4条]明天上午十点开会", 2_000L, "张三", false))
        core.append(ChatMessage("李四", "[1条]收到", 1_500L, "李四", false))

        val plan = core.dedupeRepeated()

        assertEquals(1, plan.removed)
        val left = core.readRecent()
        assertEquals(2, left.size)
        assertTrue(left.any { it.text == "[3条]明天上午十点开会" })
        assertFalse(left.any { it.text == "[4条]明天上午十点开会" })
    }

    @Test
    fun `dedupeRepeated 没有重复时不碰文件`() {
        core.append(ChatMessage("张三", "好的", 1_000L, "张三", false))
        core.append(ChatMessage("张三", "好的", 2_000L, "张三", false))
        val before = dataFile.lastModified()

        val plan = core.dedupeRepeated()

        assertEquals(0, plan.removed)
        assertEquals("没有重复不该重写文件", before, dataFile.lastModified())
        assertEquals(2, core.readRecent().size)
    }

    @Test
    fun `repeatedPlan 只读不写`() {
        core.append(ChatMessage("张三", "[3条]开会", 1_000L, "张三", false))
        core.append(ChatMessage("张三", "[4条]开会", 2_000L, "张三", false))
        val before = dataFile.readText()

        val plan = core.repeatedPlan()

        assertEquals(1, plan.removed)
        assertEquals("只读扫描不能改文件", before, dataFile.readText())
    }

    @Test
    fun `dedupeRepeated 二次调用幂等`() {
        core.append(ChatMessage("张三", "[3条]开会", 1_000L, "张三", false))
        core.append(ChatMessage("张三", "[4条]开会", 2_000L, "张三", false))

        assertEquals(1, core.dedupeRepeated().removed)
        assertEquals(0, core.dedupeRepeated().removed)
    }

    @Test
    fun `dedupeRepeated 在文件不存在时返回空计划`() {
        assertFalse(dataFile.exists())
        val plan = core.dedupeRepeated()

        assertEquals(0, plan.removed)
        assertNotNull(plan.groups)
    }
}
