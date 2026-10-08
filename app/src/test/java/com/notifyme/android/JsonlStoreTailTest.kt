// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [JsonlStore.readTailLines] 的契约测试。
 *
 * 这是「只为取最近 N 条，不要把整个 jsonl 读进内存」的基础设施，出错的方式很隐蔽：
 * 从文件中间开始时把被截断的半行当成一条记录返回（JSON 解析失败 → 看起来像数据损坏）、
 * 或者窗口凑不满时直接少返回几条（最新消息静默丢失）。两种都不是崩溃，而是数据缺失。
 */
class JsonlStoreTailTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun newStore(name: String = "data.jsonl"): JsonlStore =
        JsonlStore(File(File(tmp.root, "files"), name))

    private fun writeLines(store: JsonlStore, lines: List<String>) {
        store.file.parentFile?.mkdirs()
        store.file.writeText(lines.joinToString("") { "$it\n" }, Charsets.UTF_8)
    }

    @Test
    fun `小文件返回全部非空行且保持文件顺序`() {
        val store = newStore()
        writeLines(store, listOf("a", "", "  ", "b", "c"))
        assertEquals(listOf("a", "b", "c"), store.readTailLines(100))
    }

    @Test
    fun `文件不存在或 maxLines 非正数返回空列表`() {
        assertEquals(emptyList<String>(), newStore().readTailLines(10))
        val store = newStore()
        store.appendLines(listOf("a", "b"))
        assertEquals(emptyList<String>(), store.readTailLines(0))
        assertEquals(emptyList<String>(), store.readTailLines(-1))
    }

    @Test
    fun `返回行数超过上限时只保留最后 maxLines 行`() {
        val store = newStore()
        writeLines(store, (1..50).map { "line-$it" })
        val tail = store.readTailLines(10)
        assertEquals(10, tail.size)
        assertEquals("line-41", tail.first())
        assertEquals("line-50", tail.last())
    }

    @Test
    fun `大于窗口的文件只读尾部且首行不是被截断的半行`() {
        val store = newStore()
        // 每行约 100 字节，共约 300 KB：显式给 64 KiB 窗口必然从文件中间开始
        val lines = (1..3000).map { "line-$it-" + "x".repeat(90) }
        writeLines(store, lines)

        val tail = store.readTailLines(100, maxBytes = 64L * 1024)

        assertEquals(100, tail.size)
        assertEquals(lines.last(), tail.last())
        tail.forEach { line ->
            assertTrue("尾部读取不该返回被截断的半行：$line", line.startsWith("line-"))
            assertTrue("被截断的半行会留下替换字符：$line", !line.contains('\uFFFD'))
        }
        // 结果必须是文件里真实存在的行（截断处丢掉的半行不会出现在结果中）
        assertEquals(lines.takeLast(100), tail)
    }

    @Test
    fun `窗口凑不满时会扩大窗口直到读到文件头`() {
        val store = newStore()
        // 每行 40 字节、共约 1.2 MB：默认 1 MiB 窗口装不下 30000 行，必须扩容
        val lines = (1..30_000).map { "line-$it-" + "y".repeat(30) }
        writeLines(store, lines)

        val tail = store.readTailLines(28_000)

        assertEquals(28_000, tail.size)
        assertEquals(lines.last(), tail.last())
        assertEquals("扩容后应取到更早的行", lines[lines.size - 28_000], tail.first())
    }

    @Test
    fun `多字节内容被窗口切断时不产生乱码行`() {
        val store = newStore()
        // 中文行：按字节切窗口可能切在一个字符中间
        val lines = (1..2000).map { "第${it}条消息：明天正常训练，请准时到明德校区体育馆集合。" }
        writeLines(store, lines)

        val tail = store.readTailLines(50, maxBytes = 64L * 1024)

        assertEquals(lines.takeLast(50), tail)
        assertFalse(tail.any { it.contains('\uFFFD') })
    }
}
