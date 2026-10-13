// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * M3 评审回归：无 status 字段的旧提醒记录按 note 推断级别。
 * 核心约束——推断结果不得误落 FAILED（failed 不算生效，会让 containsActive
 * 去重失效，同一条消息重复创建日历事件）。
 */
class ReminderLegacyStatusTest {

    private fun legacy(note: String): ReminderRecord =
        ReminderRecord.fromJson(
            JSONObject()
                .put("dedup_key", "k1")
                .put("title", "任务：x")
                .put("description", "d")
                .put("conversation", "张三")
                .put("event_time", 100L)
                .put("calendar_event_id", -1L)
                .put("created_at", 1L)
                .put("note", note)
        )

    @Test
    fun `App内提醒文案推断为闹钟`() {
        assertEquals(ReminderRecord.STATUS_ALARM, legacy("已设App内提醒（闹钟）").status)
    }

    @Test
    fun `唤起日历文案推断为前台意图`() {
        assertEquals(ReminderRecord.STATUS_INTENT, legacy("已唤起日历，请手动保存").status)
    }

    @Test
    fun `成功与跳过推断为日历且去重生效`() {
        assertEquals(ReminderRecord.STATUS_CALENDAR, legacy("成功：已写入日历").status)
        assertEquals(ReminderRecord.STATUS_CALENDAR, legacy("跳过：已创建过（去重）").status)
    }

    @Test
    fun `失败文案仍推断为失败`() {
        assertEquals(ReminderRecord.STATUS_FAILED, legacy("失败：无日历账户且无闹钟权限").status)
    }

    @Test
    fun `未知文案保守按生效处理不得绕过去重`() {
        // 老版本改过文案：未知一律按 calendar（生效），绝不能落到 FAILED
        assertEquals(ReminderRecord.STATUS_CALENDAR, legacy("某种历史文案").status)
        assertEquals(ReminderRecord.STATUS_CALENDAR, legacy("").status)
    }

    @Test
    fun `显式status优先于note推断`() {
        val rec = ReminderRecord.fromJson(
            JSONObject()
                .put("dedup_key", "k2")
                .put("note", "成功：已写入日历")
                .put("status", ReminderRecord.STATUS_ALARM)
        )
        assertEquals(ReminderRecord.STATUS_ALARM, rec.status)
    }

    @Test
    fun `toJson读回保留status不再走推断`() {
        val original = ReminderRecord(
            dedupKey = "k3", title = "t", description = "d", conversation = "c",
            eventTime = 5L, calendarEventId = -1L, createdAt = 1L,
            status = ReminderRecord.STATUS_ALARM, note = "已设App内提醒（闹钟）"
        )
        assertEquals(ReminderRecord.STATUS_ALARM, ReminderRecord.fromJson(original.toJson()).status)
    }
}
