// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 顾问报告的「本地存档口径」与「上传口径」必须分开：
 *
 *  - 本地存档 [AdvisorReport.toJson] 带 rejected_count / first_rejected_at / quarantined
 *    三个内部字段（本机累计被拒次数与隔离态），用于本机存档与到期复活判定；
 *  - 上报走 [AdvisorReport.toUploadJson]，只含服务端认识的 7 个字段，
 *    免得把内部计数写进云端库污染服务端 schema。
 *
 * 服务端对应口径：server/util.py 的顾问字段白名单 + server/routes.py 的 /advisor。
 * 生产端两个分支（脱敏 / 不脱敏）都必须落到同一套字段集合上。
 */
class AdvisorPayloadTest {

    private fun sample() = AdvisorReport(
        reportId = "r-1",
        createdAt = 1_700_000_000_000L,
        periodDays = 7,
        caseCount = 3,
        summary = "本周重点",
        suggestions = listOf(AdvisorReport.Suggestion("todo", "跟进", "明天回复客户")),
        modelUsed = "local",
        rejectedCount = 2,
        firstRejectedAt = 1_700_000_100_000L,
        quarantined = true
    )

    @Test
    fun `上传口径只含服务端认识的字段`() {
        val keys = sample().toUploadJson().keys().asSequence().toSet()
        assertEquals(
            setOf(
                "report_id",
                "created_at",
                "period_days",
                "case_count",
                "summary",
                "suggestions",
                "model_used"
            ),
            keys
        )
    }

    @Test
    fun `上传口径不含内部计数与隔离态`() {
        val json = sample().toUploadJson()
        assertFalse(json.has("rejected_count"))
        assertFalse(json.has("first_rejected_at"))
        assertFalse(json.has("quarantined"))
    }

    @Test
    fun `本地存档口径保留内部字段`() {
        val json = sample().toJson()
        assertEquals(2, json.getInt("rejected_count"))
        assertEquals(1_700_000_100_000L, json.getLong("first_rejected_at"))
        assertTrue(json.getBoolean("quarantined"))
    }

    @Test
    fun `上传口径的字段值与本地存档一致`() {
        val report = sample()
        val upload = report.toUploadJson()
        val local = report.toJson()
        assertEquals(local.getString("report_id"), upload.getString("report_id"))
        assertEquals(local.getLong("created_at"), upload.getLong("created_at"))
        assertEquals(local.getInt("period_days"), upload.getInt("period_days"))
        assertEquals(local.getInt("case_count"), upload.getInt("case_count"))
        assertEquals(local.getString("summary"), upload.getString("summary"))
        assertEquals(local.getString("model_used"), upload.getString("model_used"))
        val suggestion = upload.getJSONArray("suggestions").getJSONObject(0)
        assertEquals("todo", suggestion.getString("type"))
        assertEquals("跟进", suggestion.getString("title"))
        assertEquals("明天回复客户", suggestion.getString("detail"))
    }

    @Test
    fun `上传口径反序列化后内部字段回到默认值`() {
        val restored = AdvisorReport.fromJson(sample().toUploadJson())
        assertEquals("r-1", restored?.reportId)
        assertEquals(0, restored?.rejectedCount)
        assertEquals(0L, restored?.firstRejectedAt)
        assertFalse(restored?.quarantined ?: true)
    }
}
