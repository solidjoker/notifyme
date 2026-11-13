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

// 文件级增量扫描单测（第四轮任务 B）。
class AnalysisScanCoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun rec(
        caseId: String,
        analyzedAt: Long,
        quarantined: Boolean = false,
        redacted: Boolean = false
    ): AnalysisCaseRecord = AnalysisCaseRecord(
        caseId = caseId,
        conversation = "会话",
        windowEnd = analyzedAt,
        messageCount = 3,
        analyzedAt = analyzedAt,
        protocol = "laya",
        rawJson = "{}",
        s1NeedAction = false,
        s1NeedActionProb = 0.2,
        s1Importance = 5.0,
        s1DueWindow = "",
        s1Topic = "话题",
        s1Confidence = 0.8,
        escalated = false,
        s2Summary = "",
        s2DueTime = "",
        s2SuggestedAction = "",
        s2Tasks = emptyList(),
        quarantined = quarantined,
        redacted = redacted
    )

    private fun write(file: File, records: List<AnalysisCaseRecord>, trailingNewline: Boolean = true) {
        val text = records.joinToString("\n") { it.toJson().toString() }
        file.writeText(if (trailingNewline) text + "\n" else text, Charsets.UTF_8)
    }

    private fun offsetOf(lines: List<String>, index: Int): Long {
        var off = 0L
        for (i in 0 until index) off += lines[i].toByteArray(Charsets.UTF_8).size + 1
        return off
    }

    @Test
    fun `跳过已同步记录只返回待上报的`() {
        val file = tmp.newFile("analysis.jsonl")
        val records = listOf(rec("c1", 100L), rec("c2", 200L), rec("c3", 300L))
        write(file, records)
        val result = AnalysisScanCore(file).scan(0L, 150L, 10)
        assertEquals(listOf("c2", "c3"), result.records.map { it.caseId })
        assertTrue(result.reachedEnd)
    }

    @Test
    fun `跳过被隔离记录并计数`() {
        val file = tmp.newFile("analysis.jsonl")
        write(file, listOf(rec("c1", 100L, quarantined = true), rec("c2", 200L)))
        val result = AnalysisScanCore(file).scan(0L, 0L, 10)
        assertEquals(listOf("c2"), result.records.map { it.caseId })
        assertEquals(1, result.skippedQuarantined)
    }

    @Test
    fun `nextOffset指向首个待上报记录的起始偏移`() {
        val file = tmp.newFile("analysis.jsonl")
        val records = listOf(rec("c1", 100L), rec("c2", 200L))
        write(file, records)
        val lines = records.map { it.toJson().toString() }
        val result = AnalysisScanCore(file).scan(0L, 150L, 10)
        assertEquals(offsetOf(lines, 1), result.nextOffset)
    }

    @Test
    fun `没有待上报记录时下一轮起点是文件末尾`() {
        val file = tmp.newFile("analysis.jsonl")
        write(file, listOf(rec("c1", 100L), rec("c2", 200L)))
        val result = AnalysisScanCore(file).scan(0L, 1000L, 10)
        assertTrue(result.records.isEmpty())
        assertEquals(file.length(), result.nextOffset)
        assertTrue(result.reachedEnd)
    }

    @Test
    fun `取满上限时标记未到末尾`() {
        val file = tmp.newFile("analysis.jsonl")
        write(file, listOf(rec("c1", 100L), rec("c2", 200L), rec("c3", 300L)))
        val result = AnalysisScanCore(file).scan(0L, 0L, 2)
        assertEquals(2, result.records.size)
        assertFalse(result.reachedEnd)
    }

    @Test
    fun `游标落在半行时从头开始`() {
        val file = tmp.newFile("analysis.jsonl")
        val records = listOf(rec("c1", 100L), rec("c2", 200L))
        write(file, records)
        val lines = records.map { it.toJson().toString() }
        val bad = offsetOf(lines, 1) + 5L
        val result = AnalysisScanCore(file).scan(bad, 0L, 10)
        assertEquals(listOf("c1", "c2"), result.records.map { it.caseId })
    }

    @Test
    fun `游标越界时从头开始`() {
        val file = tmp.newFile("analysis.jsonl")
        write(file, listOf(rec("c1", 100L)))
        val result = AnalysisScanCore(file).scan(999_999L, 0L, 10)
        assertEquals(listOf("c1"), result.records.map { it.caseId })
    }

    @Test
    fun `末尾无换行的残行也算一条记录`() {
        val file = tmp.newFile("analysis.jsonl")
        write(file, listOf(rec("c1", 100L), rec("c2", 200L)), trailingNewline = false)
        val result = AnalysisScanCore(file).scan(0L, 0L, 10)
        assertEquals(listOf("c1", "c2"), result.records.map { it.caseId })
    }

    @Test
    fun `要求脱敏时跳过未脱敏记录并计数`() {
        // CLOUD_REDACT 模式：未脱敏的记录绝不能上传，但也不能把扫描游标钉死
        val file = tmp.newFile("analysis.jsonl")
        write(
            file,
            listOf(
                rec("c1", 100L, redacted = false),
                rec("c2", 200L, redacted = true),
                rec("c3", 300L, redacted = true)
            )
        )
        val result = AnalysisScanCore(file).scan(0L, 0L, 10, requireRedacted = true)
        assertEquals(listOf("c2", "c3"), result.records.map { it.caseId })
        assertEquals(1, result.skippedUnredacted)
    }

    @Test
    fun `不要求脱敏时未脱敏记录照常返回`() {
        val file = tmp.newFile("analysis.jsonl")
        write(file, listOf(rec("c1", 100L, redacted = false)))
        val result = AnalysisScanCore(file).scan(0L, 0L, 10)
        assertEquals(listOf("c1"), result.records.map { it.caseId })
        assertEquals(0, result.skippedUnredacted)
    }

    @Test
    fun `文件不存在时返回空结果且不抛异常`() {
        val file = File(tmp.root, "missing.jsonl")
        val result = AnalysisScanCore(file).scan(0L, 0L, 10)
        assertTrue(result.records.isEmpty())
        assertTrue(result.reachedEnd)
        assertEquals(0L, result.nextOffset)
    }
}
