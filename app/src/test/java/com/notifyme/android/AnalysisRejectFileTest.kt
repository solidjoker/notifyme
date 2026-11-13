// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

// 分析文件级被拒 / 隔离落盘单测（第四轮任务 A）。
class AnalysisRejectFileTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun rec(
        caseId: String,
        analyzedAt: Long = 1000L,
        rejectedCount: Int = 0,
        firstRejectedAt: Long = 0L,
        quarantined: Boolean = false
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
        rejectedCount = rejectedCount,
        firstRejectedAt = firstRejectedAt,
        quarantined = quarantined
    )

    private fun writeFile(file: File, records: List<AnalysisCaseRecord>) {
        file.writeText(records.joinToString("\n") { it.toJson().toString() } + "\n", Charsets.UTF_8)
    }

    private fun readFirst(file: File): AnalysisCaseRecord {
        val line = file.readLines(Charsets.UTF_8).first { it.isNotBlank() }
        return AnalysisCaseRecord.fromJson(JSONObject(line))!!
    }

    @Test
    fun `标记被拒后记录写入rejected_count与first_rejected_at`() {
        val file = tmp.newFile("analysis.jsonl")
        writeFile(file, listOf(rec("c1")))
        val outcome = AnalysisRejectFile(file).markRejected(setOf("c1"), 1000L)
        assertTrue(outcome.rewritten)
        assertTrue(outcome.newlyQuarantined.isEmpty())
        val saved = readFirst(file)
        assertEquals(1, saved.rejectedCount)
        assertEquals(1000L, saved.firstRejectedAt)
        assertFalse(saved.quarantined)
    }

    @Test
    fun `累计到上限后隔离且集合可查可统计`() {
        val file = tmp.newFile("analysis.jsonl")
        writeFile(file, listOf(rec("c1"), rec("c2")))
        val f = AnalysisRejectFile(file)
        f.markRejected(setOf("c1"), 1000L)
        f.markRejected(setOf("c1"), 2000L)
        val third = f.markRejected(setOf("c1"), 3000L)
        assertTrue(third.newlyQuarantined.containsKey("c1"))
        assertEquals(setOf("c1"), f.quarantinedCaseIds())
        assertEquals(1, f.quarantinedCount())
        assertEquals(3, readFirst(file).rejectedCount)
    }

    @Test
    fun `老记录缺少新字段时读写兼容`() {
        val json = JSONObject().put("case_id", "c0").put("analyzed_at", 5L)
            .put(
                "s1",
                JSONObject()
                    .put("importance", 5.0)
                    .put("confidence", 0.5)
                    .put("need_action", JSONObject().put("value", false).put("prob", 0.1))
            )
        val r = AnalysisCaseRecord.fromJson(json)!!
        assertEquals(0, r.rejectedCount)
        assertEquals(0L, r.firstRejectedAt)
        assertFalse(r.quarantined)
        assertFalse(r.toJson().has("rejected_count"))
        assertFalse(r.toJson().has("first_rejected_at"))
        assertFalse(r.toJson().has("quarantined"))
    }

    @Test
    fun `复活解除隔离并清零计数`() {
        val file = tmp.newFile("analysis.jsonl")
        writeFile(file, listOf(rec("c1")))
        val f = AnalysisRejectFile(file)
        f.markRejected(setOf("c1"), 1000L)
        f.markRejected(setOf("c1"), 2000L)
        f.markRejected(setOf("c1"), 3000L)
        assertEquals(setOf("c1"), f.quarantinedCaseIds())
        val revived = f.revive(setOf("c1"))
        assertEquals(setOf("c1"), revived)
        assertTrue(f.quarantinedCaseIds().isEmpty())
        assertEquals(0, f.quarantinedCount())
        val saved = readFirst(file)
        assertEquals(0, saved.rejectedCount)
        assertEquals(0L, saved.firstRejectedAt)
        assertFalse(saved.quarantined)
    }

    @Test
    fun `强制隔离立即生效`() {
        val file = tmp.newFile("analysis.jsonl")
        writeFile(file, listOf(rec("c1")))
        val f = AnalysisRejectFile(file)
        assertTrue(f.quarantine(setOf("c1"), 5000L))
        assertEquals(setOf("c1"), f.quarantinedCaseIds())
        assertTrue(readFirst(file).quarantined)
    }

    @Test
    fun `损坏行原样保留不会丢数据`() {
        val file = tmp.newFile("analysis.jsonl")
        file.writeText("{\"case_id\":\"c1\", broken\n" + rec("c2").toJson().toString() + "\n", Charsets.UTF_8)
        val f = AnalysisRejectFile(file)
        val outcome = f.markRejected(setOf("c2"), 1000L)
        assertTrue(outcome.rewritten)
        val lines = file.readLines(Charsets.UTF_8)
        assertTrue(lines[0].contains("broken"))
        // 损坏行原样保留，命中行仍被打上被拒计数（未达上限不隔离）
        val saved = AnalysisCaseRecord.fromJson(JSONObject(lines[1]))!!
        assertEquals(1, saved.rejectedCount)
        assertEquals(0, f.quarantinedCount())
    }

    @Test
    fun `命中不到任何记录时不重写文件`() {
        val file = tmp.newFile("analysis.jsonl")
        writeFile(file, listOf(rec("c1")))
        val before = file.readText(Charsets.UTF_8)
        val outcome = AnalysisRejectFile(file).markRejected(setOf("nope"), 1000L)
        assertFalse(outcome.rewritten)
        assertTrue(outcome.newlyQuarantined.isEmpty())
        assertEquals(before, file.readText(Charsets.UTF_8))
    }
}
