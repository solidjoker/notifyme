// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * [JsonlPatchCore] 的逐行打补丁内核单测。
 *
 * 背景（第八轮）：`AnalysisRejectFile` 与 `AdvisorRejectFile` 两份实现此前几乎逐行复制
 * 同一段「整体读入 -> 按 id 打补丁 -> 只有变化才原子重写」，四条口径（变化才重写、
 * 损坏行保留、序列化失败保留、没命中不碰文件）各写一遍。抽到内核后这里钉住它们，
 * 两边的文件级类只留 schema 适配。
 *
 * 用一个与业务无关的假记录（[Row]）来测，证明内核确实不依赖任何 schema。
 */
class JsonlPatchCoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** 假记录：一行 = `id|value|quarantined`，不走 JSON，证明内核与格式无关。 */
    private data class Row(val id: String, val value: Int, val quarantined: Boolean = false)

    private fun parseRow(line: String): Row? {
        val parts = line.split('|')
        if (parts.size != 3) return null
        val value = parts[1].toIntOrNull() ?: return null
        return Row(parts[0], value, parts[2] == "q")
    }

    private fun encodeRow(row: Row): String =
        row.id + "|" + row.value + "|" + (if (row.quarantined) "q" else "-")

    private fun write(file: File, lines: List<String>) {
        file.writeText(lines.joinToString("") { it + "\n" }, Charsets.UTF_8)
    }

    private fun patch(
        file: File,
        target: Set<String>?,
        patch: (Row) -> Row
    ): Boolean = JsonlPatchCore.patch(
        file = file,
        target = target,
        keyOf = { it.id },
        parse = { parseRow(it) },
        encode = { encodeRow(it) },
        patch = patch
    )

    @Test
    fun `命中并改写后文件被重写且只改命中行`() {
        val file = tmp.newFile("rows.jsonl")
        write(file, listOf("a|1|-", "b|2|-", "c|3|-"))
        val rewritten = patch(file, setOf("b")) { it.copy(value = it.value + 10) }
        assertTrue(rewritten)
        assertEquals(listOf("a|1|-", "b|12|-", "c|3|-"), file.readLines(Charsets.UTF_8))
    }

    @Test
    fun `patch返回同一条记录时不重写文件`() {
        val file = tmp.newFile("rows.jsonl")
        write(file, listOf("a|1|-", "b|2|-"))
        val before = file.readText(Charsets.UTF_8)
        val rewritten = patch(file, setOf("a", "b")) { it }
        assertFalse(rewritten)
        assertEquals(before, file.readText(Charsets.UTF_8))
    }

    @Test
    fun `命中集合为空时不重写文件`() {
        val file = tmp.newFile("rows.jsonl")
        write(file, listOf("a|1|-"))
        val before = file.readText(Charsets.UTF_8)
        assertFalse(patch(file, setOf("zzz")) { it.copy(value = 99) })
        assertEquals(before, file.readText(Charsets.UTF_8))
    }

    @Test
    fun `文件不存在时返回false且不创建空文件`() {
        val file = File(tmp.root, "missing.jsonl")
        assertFalse(patch(file, setOf("a")) { it.copy(value = 1) })
        assertFalse(file.exists())
    }

    @Test
    fun `损坏行原样保留而命中行照常改写`() {
        val file = tmp.newFile("rows.jsonl")
        write(file, listOf("broken line", "a|1|-", "a|1"))
        val rewritten = patch(file, setOf("a")) { it.copy(value = it.value + 1) }
        assertTrue(rewritten)
        val lines = file.readLines(Charsets.UTF_8)
        assertEquals("broken line", lines[0])
        assertEquals("a|2|-", lines[1])
        // 字段数不对 = 同样算损坏行，原样保留
        assertEquals("a|1", lines[2])
    }

    @Test
    fun `空行原样保留不参与解析`() {
        val file = tmp.newFile("rows.jsonl")
        write(file, listOf("a|1|-", "", "b|2|-"))
        assertTrue(patch(file, setOf("a")) { it.copy(quarantined = true) })
        assertEquals(listOf("a|1|q", "", "b|2|-"), file.readLines(Charsets.UTF_8))
    }

    @Test
    fun `序列化失败时保留原行且不标记重写`() {
        val file = tmp.newFile("rows.jsonl")
        write(file, listOf("a|1|-"))
        val before = file.readText(Charsets.UTF_8)
        val rewritten = JsonlPatchCore.patch(
            file = file,
            target = setOf("a"),
            keyOf = { it.id },
            parse = { parseRow(it) },
            encode = { throw IllegalStateException("encode boom") },
            patch = { it.copy(value = 2) }
        )
        assertFalse(rewritten)
        assertEquals(before, file.readText(Charsets.UTF_8))
    }

    @Test
    fun `序列化部分失败时其它行仍被改写`() {
        val file = tmp.newFile("rows.jsonl")
        write(file, listOf("a|1|-", "b|2|-"))
        val rewritten = JsonlPatchCore.patch(
            file = file,
            target = setOf("a", "b"),
            keyOf = { it.id },
            parse = { parseRow(it) },
            encode = { row -> if (row.id == "a") throw IllegalStateException("boom") else encodeRow(row) },
            patch = { it.copy(value = it.value + 5) }
        )
        assertTrue(rewritten)
        assertEquals(listOf("a|1|-", "b|7|-"), file.readLines(Charsets.UTF_8))
    }

    @Test
    fun `target为null时扫描全文件`() {
        val file = tmp.newFile("rows.jsonl")
        write(file, listOf("a|1|q", "b|2|q", "c|3|-"))
        assertTrue(patch(file, null) { if (it.quarantined) it.copy(value = 0, quarantined = false) else it })
        assertEquals(listOf("a|0|-", "b|0|-", "c|3|-"), file.readLines(Charsets.UTF_8))
    }

    @Test
    fun `target为null且全都不满足条件时不碰文件`() {
        val file = tmp.newFile("rows.jsonl")
        write(file, listOf("a|1|-"))
        val before = file.readText(Charsets.UTF_8)
        assertFalse(patch(file, null) { it })
        assertEquals(before, file.readText(Charsets.UTF_8))
    }

    @Test
    fun `同一id出现多行时每一行都会被打补丁`() {
        val file = tmp.newFile("rows.jsonl")
        write(file, listOf("a|1|-", "b|2|-", "a|3|-"))
        assertTrue(patch(file, setOf("a")) { it.copy(quarantined = true) })
        assertEquals(listOf("a|1|q", "b|2|-", "a|3|q"), file.readLines(Charsets.UTF_8))
    }

    @Test
    fun `解析抛异常时按损坏行处理不中断整轮`() {
        val file = tmp.newFile("rows.jsonl")
        write(file, listOf("a|1|-", "boom", "b|2|-"))
        val rewritten = JsonlPatchCore.patch(
            file = file,
            target = setOf("a", "b"),
            keyOf = { it.id },
            parse = { if (it == "boom") throw IllegalArgumentException("parse boom") else parseRow(it) },
            encode = { encodeRow(it) },
            patch = { it.copy(value = it.value * 2) }
        )
        assertTrue(rewritten)
        assertEquals(listOf("a|2|-", "boom", "b|4|-"), file.readLines(Charsets.UTF_8))
    }

    @Test
    fun `collect只读不重写并按文件顺序去重`() {
        val file = tmp.newFile("rows.jsonl")
        write(file, listOf("a|1|q", "b|2|-", "a|3|q", "broken", "c|4|q"))
        val before = file.readText(Charsets.UTF_8)
        val ids = JsonlPatchCore.collect(
            file = file,
            keyOf = { it.id },
            parse = { parseRow(it) },
            include = { it.quarantined }
        )
        assertEquals(listOf("a", "c"), ids.toList())
        assertEquals(before, file.readText(Charsets.UTF_8))
    }

    @Test
    fun `collect在文件不存在时返回空集`() {
        val file = File(tmp.root, "nope.jsonl")
        assertTrue(
            JsonlPatchCore.collect(
                file = file,
                keyOf = { it.id },
                parse = { parseRow(it) },
                include = { true }
            ).isEmpty()
        )
        assertFalse(file.exists())
    }

    @Test
    fun `重写走原子写不留下临时文件`() {
        val file = tmp.newFile("rows.jsonl")
        write(file, listOf("a|1|-"))
        assertTrue(patch(file, setOf("a")) { it.copy(value = 9) })
        val names = (tmp.root.listFiles() ?: emptyArray()).map { it.name }
        assertEquals(listOf("rows.jsonl"), names)
    }
}
