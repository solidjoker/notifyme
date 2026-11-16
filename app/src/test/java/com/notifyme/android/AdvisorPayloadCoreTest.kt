// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AdvisorPayloadCore] 的上传口径测试。
 *
 * 为什么值得测：顾问报告的这份 JSON 以前写了两遍（不脱敏分支的
 * `toUploadJson()` 与脱敏分支手写的第二份）。两份各写各的键名，任一处加字段
 * 就**静默**漂移 —— 服务端只认自己那几把钥匙，多出来的键直接丢掉，控制台
 * 看不出「这字段没了」。这里把「顶层键恰好七个」「内部状态键绝不出设备」
 * 「脱敏只碰正文」钉死。
 */
class AdvisorPayloadCoreTest {

    private fun report(
        reportId: String = "r-1",
        createdAt: Long = 1790000000000L,
        periodDays: Int = 7,
        caseCount: Int = 12,
        summary: String = "本次复盘摘要",
        suggestions: List<AdvisorReport.Suggestion> = listOf(
            AdvisorReport.Suggestion("prompt", "标题甲", "细节甲"),
            AdvisorReport.Suggestion("watchlist", "标题乙", "细节乙")
        ),
        modelUsed: String = "qwen-model"
    ) = AdvisorReport(
        reportId = reportId,
        createdAt = createdAt,
        periodDays = periodDays,
        caseCount = caseCount,
        summary = summary,
        suggestions = suggestions,
        modelUsed = modelUsed
    )

    @Test
    fun uploadKeysAreExactlySeven() {
        val keys = AdvisorPayloadCore.keysOf(AdvisorPayloadCore.uploadJson(report()))
        assertEquals(7, keys.size)
        assertEquals(
            setOf(
                "report_id", "created_at", "period_days", "case_count",
                "summary", "suggestions", "model_used"
            ),
            keys
        )
    }

    @Test
    fun uploadKeysNeverLeakLocalStateFields() {
        val keys = AdvisorPayloadCore.keysOf(AdvisorPayloadCore.uploadJson(report()))
        // 这三个是端侧自己的账（被拒次数 / 首次被拒时间 / 是否隔离）
        assertFalse(keys.contains("rejected_count"))
        assertFalse(keys.contains("first_rejected_at"))
        assertFalse(keys.contains("quarantined"))
    }

    @Test
    fun suggestionKeysAreTypeTitleDetail() {
        val arr = AdvisorPayloadCore.uploadJson(report()).optJSONArray("suggestions")
        assertEquals(2, arr?.length())
        val first = arr?.optJSONObject(0)
        assertEquals(
            setOf("type", "title", "detail"),
            AdvisorPayloadCore.keysOf(first ?: org.json.JSONObject()),
        )
        assertEquals("prompt", first?.optString("type"))
        assertEquals("标题甲", first?.optString("title"))
        assertEquals("细节甲", first?.optString("detail"))
    }

    @Test
    fun valuesMatchTheReport() {
        val json = AdvisorPayloadCore.uploadJson(report())
        assertEquals("r-1", json.optString("report_id"))
        assertEquals(1790000000000L, json.optLong("created_at"))
        assertEquals(7, json.optInt("period_days"))
        assertEquals(12, json.optInt("case_count"))
        assertEquals("本次复盘摘要", json.optString("summary"))
        assertEquals("qwen-model", json.optString("model_used"))
    }

    @Test
    fun redactOnlyTouchesBodyText() {
        val json = AdvisorPayloadCore.uploadJson(report()) { "[已脱敏]" }
        assertEquals("[已脱敏]", json.optString("summary"))
        val arr = json.optJSONArray("suggestions")
        assertEquals("[已脱敏]", arr?.optJSONObject(0)?.optString("title"))
        assertEquals("[已脱敏]", arr?.optJSONObject(0)?.optString("detail"))
        // 结构字段不动：type / model_used / report_id / created_at
        assertEquals("prompt", arr?.optJSONObject(0)?.optString("type"))
        assertEquals("qwen-model", json.optString("model_used"))
        assertEquals("r-1", json.optString("report_id"))
        assertEquals(1790000000000L, json.optLong("created_at"))
    }

    @Test
    fun redactWithoutCallbackKeepsOriginalText() {
        val json = AdvisorPayloadCore.uploadJson(report(), null)
        assertEquals("本次复盘摘要", json.optString("summary"))
        val arr = json.optJSONArray("suggestions")
        assertEquals("标题甲", arr?.optJSONObject(0)?.optString("title"))
    }

    @Test
    fun emptySuggestionsBecomeEmptyArray() {
        val json = AdvisorPayloadCore.uploadJson(report(suggestions = emptyList()))
        assertEquals(0, json.optJSONArray("suggestions")?.length())
    }

    @Test
    fun createdAtStaysANumber() {
        // 服务端按 created_at 排序（util._advisor_ts），写成字符串就多一层口径
        val json = AdvisorPayloadCore.uploadJson(report())
        assertTrue(json.opt("created_at") is Number)
    }

    @Test
    fun longSummaryIsNotClippedOnDevice() {
        // 裁剪只有服务端一处（存档保护），端上再裁一遍就出现两套口径
        val long = "x".repeat(5000)
        val json = AdvisorPayloadCore.uploadJson(report(summary = long))
        assertEquals(5000, json.optString("summary").length)
    }

    @Test
    fun toUploadJsonDelegatesToTheCore() {
        val viaMember = report().toUploadJson()
        val viaCore = AdvisorPayloadCore.uploadJson(report())
        assertEquals(
            AdvisorPayloadCore.keysOf(viaCore),
            AdvisorPayloadCore.keysOf(viaMember),
        )
        assertEquals(viaCore.toString(), viaMember.toString())
    }

    @Test
    fun emptyReportStillHasAllSevenKeys() {
        val empty = AdvisorReport(
            reportId = "",
            createdAt = 0L,
            periodDays = 0,
            caseCount = 0,
            summary = "",
            suggestions = emptyList(),
            modelUsed = ""
        )
        val json = AdvisorPayloadCore.uploadJson(empty)
        assertEquals(7, AdvisorPayloadCore.keysOf(json).size)
        assertEquals(0, json.optJSONArray("suggestions")?.length())
        assertTrue(json.optJSONArray("suggestions") is JSONArray)
    }
}
