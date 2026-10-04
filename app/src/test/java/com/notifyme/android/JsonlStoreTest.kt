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
 * [JsonlStore] 的文件原语契约测试。
 *
 * 这层是五个 JSONL store 的共用底座，也是最容易在重构中被无意改坏的地方
 * （尤其「空列表不碰文件」「removed==0 不重写」「overwrite 空则删文件」这三条
 * 看起来像优化、实则是数据安全的语义）。
 */
class JsonlStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun newStore(name: String = "data.jsonl"): JsonlStore =
        JsonlStore(File(File(tmp.root, "files"), name))

    @Test
    fun `appendLine 自动建目录并写一行带换行`() {
        val store = newStore()
        assertFalse("起点应当没有文件", store.exists())

        store.appendLine("""{"a":1}""")

        assertTrue(store.exists())
        assertEquals(listOf("""{"a":1}"""), store.readRawLines())
        assertTrue("行尾必须是 \\n（JSONL 约定）", store.file.readText().endsWith("\n"))
    }

    @Test
    fun `appendLines 空列表不创建文件`() {
        val store = newStore()
        store.appendLines(emptyList())
        assertFalse("空追加不该留下 0 字节文件", store.exists())
    }

    @Test
    fun `appendLines 多行一次落盘`() {
        val store = newStore()
        store.appendLines(listOf("l1", "l2", "l3"))
        assertEquals(listOf("l1", "l2", "l3"), store.readRawLines())
    }

    @Test
    fun `readRawLines 忽略空行与纯空白行`() {
        val store = newStore()
        store.file.parentFile?.mkdirs()
        store.file.writeText("a\n\n   \nb\n")
        assertEquals(listOf("a", "b"), store.readRawLines())
    }

    @Test
    fun `readRawLines 文件不存在返回空列表而不是抛异常`() {
        assertEquals(emptyList<String>(), newStore().readRawLines())
    }

    @Test
    fun `forEachRawLine 遍历全部非空行`() {
        val store = newStore()
        store.appendLines(listOf("x", "y"))
        val seen = mutableListOf<String>()
        store.forEachRawLine { seen += it }
        assertEquals(listOf("x", "y"), seen)
    }

    @Test
    fun `overwrite 非空列表整体替换`() {
        val store = newStore()
        store.appendLines(listOf("old1", "old2"))
        store.overwrite(listOf("new"))
        assertEquals(listOf("new"), store.readRawLines())
    }

    @Test
    fun `overwrite 空列表删除文件`() {
        val store = newStore()
        store.appendLine("only")
        store.overwrite(emptyList())
        assertFalse("清空后不应留 0 字节文件", store.exists())
    }

    @Test
    fun `delete 对不存在的文件是 no-op`() {
        val store = newStore()
        store.delete() // 不抛异常即可
        assertFalse(store.exists())
    }

    @Test
    fun `rewriteRaw 返回丢弃行数并只保留通过过滤的行`() {
        val store = newStore()
        store.appendLines(listOf("a", "b", "c"))

        val removed = store.rewriteRaw { it != "b" }

        assertEquals(1, removed)
        assertEquals(listOf("a", "c"), store.readRawLines())
    }

    @Test
    fun `rewriteRaw 没有删掉任何行时完全不碰文件`() {
        val store = newStore()
        store.appendLines(listOf("a", "b"))
        val before = store.file.lastModified()

        val removed = store.rewriteRaw { true }

        assertEquals(0, removed)
        assertEquals(
            "无命中重写不该改 mtime（否则用户看不出文件是否真的变过）",
            before, store.file.lastModified()
        )
    }

    @Test
    fun `rewriteRaw 全部丢弃时删除文件`() {
        val store = newStore()
        store.appendLines(listOf("a", "b"))
        val removed = store.rewriteRaw { false }
        assertEquals(2, removed)
        assertFalse(store.exists())
    }

    @Test
    fun `rewriteRaw 文件不存在返回 0`() {
        assertEquals(0, newStore().rewriteRaw { false })
    }

    @Test
    fun `rewriteRaw 保留原始行文本不重新序列化`() {
        // 损坏行 / 多余空格的行必须原样写回：调用方靠这一点实现「损坏行不丢」
        val store = newStore()
        store.file.parentFile?.mkdirs()
        store.file.writeText("not-json\n{\"ok\": true }\n")

        val removed = store.rewriteRaw { it.startsWith("{") }

        assertEquals(1, removed)
        assertEquals(listOf("{\"ok\": true }"), store.readRawLines())
    }
}
