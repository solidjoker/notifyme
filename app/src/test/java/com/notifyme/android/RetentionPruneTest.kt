// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 代码评审 H3/H4 修复的纯决策单测：
 *  - AnalysisRetentionCore：analysis.jsonl 按 analyzed_at 过期删除；
 *  - ReminderRetentionCore：reminders.jsonl 过期删除但保留「仍指向未来事件」的记录；
 *  - ReminderRecovery.isRecoverable：重启后哪些记录需要补挂闹钟。
 */
class RetentionPruneTest {

    private val now = 1_700_000_000_000L
    private val day = 86_400_000L
    private val cutoff = now - 7 * day

    private fun analysisLine(analyzedAt: Long?): String {
        val obj = JSONObject().put("case_id", "c1")
        if (analyzedAt != null) obj.put("analyzed_at", analyzedAt)
        return obj.toString()
    }

    private fun reminderLine(createdAt: Long?, eventTime: Long, status: String?): String {
        val obj = JSONObject()
            .put("dedup_key", "k1")
            .put("event_time", eventTime)
        if (createdAt != null) obj.put("created_at", createdAt)
        if (status != null) obj.put("status", status)
        return obj.toString()
    }

    // ---- analysis ----

    @Test
    fun `analysis 早于 cutoff 的记录删除`() {
        assertFalse(AnalysisRetentionCore.shouldKeep(analysisLine(cutoff - 1), cutoff))
    }

    @Test
    fun `analysis 晚于或等于 cutoff 的记录保留`() {
        assertTrue(AnalysisRetentionCore.shouldKeep(analysisLine(cutoff), cutoff))
        assertTrue(AnalysisRetentionCore.shouldKeep(analysisLine(now), cutoff))
    }

    @Test
    fun `analysis 缺 analyzed_at 或为 0 的记录保守保留`() {
        assertTrue(AnalysisRetentionCore.shouldKeep(analysisLine(null), cutoff))
        assertTrue(AnalysisRetentionCore.shouldKeep(analysisLine(0L), cutoff))
        assertTrue(AnalysisRetentionCore.shouldKeep(analysisLine(-5L), cutoff))
    }

    @Test
    fun `analysis 损坏行保留`() {
        assertTrue(AnalysisRetentionCore.shouldKeep("{not-json", cutoff))
    }

    // ---- reminders ----

    @Test
    fun `reminders 过期且事件已过点的记录删除`() {
        assertFalse(
            ReminderRetentionCore.shouldKeep(
                reminderLine(cutoff - 1, eventTime = now - day, status = ReminderRecord.STATUS_CALENDAR),
                cutoff, now
            )
        )
    }

    @Test
    fun `reminders 过期但事件仍在未来的非失败记录保留`() {
        for (status in listOf(
            ReminderRecord.STATUS_ALARM,
            ReminderRecord.STATUS_CALENDAR,
            ReminderRecord.STATUS_INTENT
        )) {
            assertTrue(
                "status=$status",
                ReminderRetentionCore.shouldKeep(
                    reminderLine(cutoff - 1, eventTime = now + day, status = status),
                    cutoff, now
                )
            )
        }
    }

    @Test
    fun `reminders 过期且明确失败的记录删除`() {
        assertFalse(
            ReminderRetentionCore.shouldKeep(
                reminderLine(cutoff - 1, eventTime = now + day, status = ReminderRecord.STATUS_FAILED),
                cutoff, now
            )
        )
    }

    @Test
    fun `reminders legacy 无 status 且事件未过点时保留`() {
        assertTrue(
            ReminderRetentionCore.shouldKeep(
                reminderLine(cutoff - 1, eventTime = now + day, status = null),
                cutoff, now
            )
        )
    }

    @Test
    fun `reminders 未过期或缺 created_at 或损坏行保留`() {
        assertTrue(
            ReminderRetentionCore.shouldKeep(
                reminderLine(now, eventTime = now - day, status = ReminderRecord.STATUS_CALENDAR),
                cutoff, now
            )
        )
        assertTrue(
            ReminderRetentionCore.shouldKeep(
                reminderLine(null, eventTime = now - day, status = ReminderRecord.STATUS_FAILED),
                cutoff, now
            )
        )
        assertTrue(ReminderRetentionCore.shouldKeep("{broken", cutoff, now))
    }

    // ---- recovery ----

    private fun record(status: String, eventTime: Long) = ReminderRecord(
        dedupKey = "k", title = "任务：T（微信·张三）", description = "d",
        conversation = "张三", eventTime = eventTime, calendarEventId = -1L,
        createdAt = now, status = status, note = "n"
    )

    @Test
    fun `仅闹钟态且未到点的记录需要补挂`() {
        assertTrue(ReminderRecovery.isRecoverable(record(ReminderRecord.STATUS_ALARM, now + day), now))
        assertFalse(ReminderRecovery.isRecoverable(record(ReminderRecord.STATUS_ALARM, now - day), now))
        assertFalse(ReminderRecovery.isRecoverable(record(ReminderRecord.STATUS_CALENDAR, now + day), now))
        assertFalse(ReminderRecovery.isRecoverable(record(ReminderRecord.STATUS_FAILED, now + day), now))
    }
}
