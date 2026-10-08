// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Schema v2 契约测试（M2 核心：pkg 字段 + 读时补齐 + 手动规范化）。
 *
 * 保护的是用户已有的 messages.jsonl：
 *  - 老行没有 pkg，读进来必须自动归微信（默认源）；
 *  - 两个 App 里同名的会话是两个会话，删一个不能带走另一个；
 *  - 规范化只补字段、不清理数据（损坏行原样写回），且幂等。
 */
class SchemaV2Test {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var dir: File
    private lateinit var core: MessageStoreCore

    private val dataFile: File get() = File(dir, "messages.jsonl")

    private val wx = AppSourceRegistry.PKG_WECHAT
    private val fs = AppSourceRegistry.PKG_FEISHU

    @Before
    fun setUp() {
        dir = tmp.newFolder("files")
        core = MessageStore.coreIn(dir) {}
    }

    private fun writeRaw(vararg lines: String) {
        dataFile.parentFile?.mkdirs()
        dataFile.writeText(lines.joinToString("") { "$it\n" })
    }

    private fun v1Line(conv: String, sender: String, text: String, ts: Long) =
        """{"sender":"$sender","text":"$text","timestamp":$ts,"conversation":"$conv","isGroup":false}"""

    // ---------------- 读时补齐 ----------------

    @Test
    fun `老行没有 pkg 读进来自动归微信`() {
        writeRaw(v1Line("张三", "张三", "你好", 1_000L))
        val m = core.readRecent().single()
        assertEquals(wx, m.pkg)
        assertEquals("张三", m.conversation)
    }

    @Test
    fun `新行 pkg 往返保留`() {
        core.append(
            ChatMessage(
                sender = "李四", text = "在吗", timestamp = 1_000L,
                conversation = "项目群", isGroup = true, pkg = fs, appLabel = "飞书"
            )
        )
        val m = core.readRecent().single()
        assertEquals(fs, m.pkg)
        assertEquals("飞书", m.appLabel)
        // 落盘行里确实写出了 pkg
        assertTrue(dataFile.readText().contains(""""pkg":"$fs""""))
    }

    // ---------------- 复合键删除 ----------------

    @Test
    fun `同名会话分属两个 App 删微信的不带走飞书的`() {
        core.append(ChatMessage("张三", "微信消息", 1_000L, "张三", false, pkg = wx))
        core.append(ChatMessage("张三", "飞书消息", 2_000L, "张三", false, pkg = fs))

        val removed = core.deleteConversation(ConvKey(wx, "张三"))

        assertEquals(1, removed)
        val left = core.readRecent()
        assertEquals(1, left.size)
        assertEquals("飞书消息", left.single().text)
        assertEquals(fs, left.single().pkg)
    }

    @Test
    fun `同名会话分属两个 App 删飞书的不带走微信的`() {
        core.append(ChatMessage("张三", "微信消息", 1_000L, "张三", false, pkg = wx))
        core.append(ChatMessage("张三", "飞书消息", 2_000L, "张三", false, pkg = fs))

        val removed = core.deleteConversation(ConvKey(fs, "张三"))

        assertEquals(1, removed)
        assertEquals("微信消息", core.readRecent().single().text)
    }

    @Test
    fun `按日期删只动指定 App 的同名会话`() {
        val dropWx = ChatMessage("张三", "微信删这天", 1_700_086_460_000L, "张三", false, pkg = wx)
        val keepFs = ChatMessage("张三", "飞书同一天要保留", 1_700_086_460_000L, "张三", false, pkg = fs)
        core.append(dropWx)
        core.append(keepFs)

        val removed = core.deleteDate(ConvKey(wx, "张三"), MessageStore.dayKeyOf(dropWx))

        assertEquals(1, removed)
        assertEquals("飞书同一天要保留", core.readRecent().single().text)
    }

    // ---------------- 手动规范化 ----------------

    @Test
    fun `normalizeSchema 补齐老行并返回行数`() {
        writeRaw(v1Line("张三", "张三", "你好", 1_000L), v1Line("李四", "李四", "在吗", 2_000L))

        val fixed = core.normalizeSchema()

        assertEquals(2, fixed)
        val lines = dataFile.readLines()
        assertEquals(2, lines.size)
        lines.forEach { line ->
            // 重写后每行都带上 pkg
            assertEquals(wx, JSONObject(line).optString("pkg"))
        }
    }

    @Test
    fun `normalizeSchema 已全部是 v2 时返回 0 且不碰文件`() {
        core.append(ChatMessage("张三", "你好", 1_000L, "张三", false))
        val before = dataFile.lastModified()

        val fixed = core.normalizeSchema()

        assertEquals(0, fixed)
        assertEquals("无老行不该重写文件", before, dataFile.lastModified())
    }

    @Test
    fun `normalizeSchema 损坏行原样保留不丢行`() {
        val corrupt = "{半截 JSON"
        writeRaw(v1Line("张三", "张三", "你好", 1_000L), corrupt)

        val fixed = core.normalizeSchema()

        assertEquals(1, fixed)
        val lines = dataFile.readLines()
        assertEquals("规范化只补字段，损坏行必须原样写回：共 2 行", 2, lines.size)
        assertTrue(lines.contains(corrupt))
        // 能补齐的那行确实补上了
        assertTrue(lines.first { it != corrupt }.contains(""""pkg":"$wx""""))
    }

    @Test
    fun `normalizeSchema 没有文件时返回 0`() {
        assertFalse(dataFile.exists())
        assertEquals(0, core.normalizeSchema())
    }

    @Test
    fun `规范化之后二次调用幂等返回 0`() {
        writeRaw(v1Line("张三", "张三", "你好", 1_000L))

        assertEquals(1, core.normalizeSchema())
        assertEquals(0, core.normalizeSchema())
    }

    @Test
    fun `normalizeSchema 保留本版本不认识的字段`() {
        // 更新版客户端可能写入新字段：规范化只补缺失的 pkg，
        // 不能靠 fromJson().toJson() 重建整行把不认识的字段洗掉（静默数据损失）
        writeRaw(
            """{"sender":"张三","text":"你好","timestamp":1000,"conversation":"张三","isGroup":false,"future":"x","nested":{"a":1}}"""
        )

        val fixed = core.normalizeSchema()

        assertEquals(1, fixed)
        val obj = JSONObject(dataFile.readText().trim())
        assertEquals(wx, obj.optString("pkg"))
        assertEquals("x", obj.optString("future"))
        assertEquals(1, obj.optJSONObject("nested")?.optInt("a"))
    }
}
