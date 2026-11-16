// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 提醒三级降级链的决策回归（ROADMAP「CalendarHelper 三级降级链抽成可测纯函数」）。
 *
 * 以前这条链嵌在 `CalendarHelper.runReminderChain` 里直接读 Context / ContentResolver /
 * MainApplication.isInForeground，纯 JVM 单测进不去，于是「哪一级生效、落什么状态、写什么备注」
 * 一直没有自动化保护。抽成 [ReminderChainCore] 之后，这里锁住四件事：
 *
 *  1. **级别顺序**：L1 可用就绝不落到 L2/L3；L1 插入失败才降级；L2 需要前台。
 *  2. **去重跳过算生效**（STATUS_CALENDAR 且无 eventId）—— 落 FAILED 会让
 *     `ReminderStore.containsActive` 失效，同一条消息会重复建日历事件。
 *  3. **created 语义**：只有 L1 真插入（有 eventId）与 L3 闹钟算「本次创建了提醒」，
 *     L1 去重跳过与 L2 待手动保存都不算。
 *  4. **note 文案是历史契约**：`ReminderRecord.fromJson` 对老记录靠「成功/跳过/失败/App内提醒/
 *     唤起日历」这几个前缀推断级别，文案一改老记录就会推断错。
 */
class ReminderChainCoreTest {

    /** 三件套齐备（有权限 + 有可写日历 + 无重复事件）。 */
    private fun fullCalendar() = ReminderChainCore.Env(
        hasCalendarPermission = true,
        hasWritableCalendar = true
    )

    // ── L1 ─────────────────────────────────────────────────────────────

    @Test
    fun `有权限有可写日历且无重复事件时去插入`() {
        assertEquals(
            ReminderChainCore.CalendarAction.INSERT,
            ReminderChainCore.calendarAction(fullCalendar())
        )
    }

    @Test
    fun `L1 插入成功落 calendar 并带事件 id`() {
        val outcome = ReminderChainCore.decide(fullCalendar(), insertedEventId = 4242L)
        assertEquals(ReminderRecord.STATUS_CALENDAR, outcome.status)
        assertEquals(ReminderChainCore.NOTE_CALENDAR_OK, outcome.note)
        assertEquals(4242L, outcome.eventId)
        assertTrue(ReminderChainCore.countsAsCreated(outcome))
    }

    @Test
    fun `系统日历已有同事件时跳过且算生效`() {
        val env = fullCalendar().copy(eventAlreadyExists = true)
        assertEquals(
            ReminderChainCore.CalendarAction.SKIP_DUPLICATE,
            ReminderChainCore.calendarAction(env)
        )
        // 就算调用方误传了 eventId / 闹钟成功，也必须停在「跳过」，不许再插一次
        val outcome = ReminderChainCore.decide(env, insertedEventId = 9L, alarmSet = true)
        assertEquals(ReminderRecord.STATUS_CALENDAR, outcome.status)
        assertEquals(ReminderChainCore.NOTE_DUPLICATE, outcome.note)
        assertNull(outcome.eventId)
        // 关键：去重跳过「算生效」但不能算「本次创建了提醒」
        assertFalse(ReminderChainCore.countsAsCreated(outcome))
    }

    // ── L1 不可用 → 降级 ────────────────────────────────────────────────

    @Test
    fun `无日历权限时不碰日历直接降级`() {
        val env = ReminderChainCore.Env(hasCalendarPermission = false, hasWritableCalendar = true)
        assertEquals(
            ReminderChainCore.CalendarAction.UNAVAILABLE,
            ReminderChainCore.calendarAction(env)
        )
        val outcome = ReminderChainCore.decide(env, alarmSet = true)
        assertEquals(ReminderRecord.STATUS_ALARM, outcome.status)
    }

    @Test
    fun `有权限但无可写日历账户时降级`() {
        val env = ReminderChainCore.Env(hasCalendarPermission = true, hasWritableCalendar = false)
        assertEquals(
            ReminderChainCore.CalendarAction.UNAVAILABLE,
            ReminderChainCore.calendarAction(env)
        )
    }

    @Test
    fun `L1 插入失败降级前台则唤起日历`() {
        val env = fullCalendar().copy(inForeground = true, canOpenCalendar = true)
        // 事实说该插（INSERT），但真实插入返回 null → 落到 L2
        assertEquals(
            ReminderChainCore.CalendarAction.INSERT,
            ReminderChainCore.calendarAction(env)
        )
        val outcome = ReminderChainCore.decide(
            env, insertedEventId = null, intentShown = true, alarmSet = true
        )
        assertEquals(ReminderRecord.STATUS_INTENT, outcome.status)
        assertEquals(ReminderChainCore.NOTE_INTENT, outcome.note)
        // L2 是「等用户手动保存」，不算本次创建了提醒
        assertFalse(ReminderChainCore.countsAsCreated(outcome))
    }

    // ── L2 ─────────────────────────────────────────────────────────────

    @Test
    fun `L2 必须在 App 前台才允许唤起日历`() {
        assertFalse(
            ReminderChainCore.shouldTryIntent(
                ReminderChainCore.Env(inForeground = false, canOpenCalendar = true)
            )
        )
        assertFalse(
            ReminderChainCore.shouldTryIntent(
                ReminderChainCore.Env(inForeground = true, canOpenCalendar = false)
            )
        )
        assertTrue(
            ReminderChainCore.shouldTryIntent(
                ReminderChainCore.Env(inForeground = true, canOpenCalendar = true)
            )
        )
    }

    @Test
    fun `后台时即使能唤起日历也直接落到闹钟`() {
        val env = ReminderChainCore.Env(
            hasCalendarPermission = false,
            inForeground = false,
            canOpenCalendar = true // 有日历 App，但后台 startActivity 会被系统静默拦截
        )
        val outcome = ReminderChainCore.decide(env, intentShown = true, alarmSet = true)
        assertEquals(ReminderRecord.STATUS_ALARM, outcome.status)
    }

    // ── L3 / 全失败 ─────────────────────────────────────────────────────

    @Test
    fun `闹钟成功落 alarm 且算创建`() {
        val outcome = ReminderChainCore.decide(ReminderChainCore.Env(), alarmSet = true)
        assertEquals(ReminderRecord.STATUS_ALARM, outcome.status)
        assertEquals(ReminderChainCore.NOTE_ALARM, outcome.note)
        assertNull(outcome.eventId)
        assertTrue(ReminderChainCore.countsAsCreated(outcome))
    }

    @Test
    fun `三级全不可用落 failed`() {
        val outcome = ReminderChainCore.decide(ReminderChainCore.Env())
        assertEquals(ReminderRecord.STATUS_FAILED, outcome.status)
        assertEquals(ReminderChainCore.NOTE_FAILED, outcome.note)
        assertNull(outcome.eventId)
        assertFalse(ReminderChainCore.countsAsCreated(outcome))
    }

    @Test
    fun `默认 Env 走最保守路径不误判可用`() {
        val env = ReminderChainCore.Env()
        assertEquals(ReminderChainCore.CalendarAction.UNAVAILABLE, ReminderChainCore.calendarAction(env))
        assertFalse(ReminderChainCore.shouldTryIntent(env))
        assertEquals(ReminderRecord.STATUS_FAILED, ReminderChainCore.decide(env).status)
    }

    // ── note 文案是历史契约 ──────────────────────────────────────────────

    @Test
    fun `五条终态文案仍能被老记录的级别推断认出来`() {
        // 与 ReminderRecord.fromJson 的推断分支逐条对齐（见 ReminderLegacyStatusTest）
        assertEquals(
            ReminderRecord.STATUS_CALENDAR,
            ReminderRecord.fromJson(
                org.json.JSONObject().put("note", ReminderChainCore.NOTE_CALENDAR_OK)
            ).status
        )
        assertEquals(
            ReminderRecord.STATUS_CALENDAR,
            ReminderRecord.fromJson(
                org.json.JSONObject().put("note", ReminderChainCore.NOTE_DUPLICATE)
            ).status
        )
        assertEquals(
            ReminderRecord.STATUS_ALARM,
            ReminderRecord.fromJson(
                org.json.JSONObject().put("note", ReminderChainCore.NOTE_ALARM)
            ).status
        )
        assertEquals(
            ReminderRecord.STATUS_INTENT,
            ReminderRecord.fromJson(
                org.json.JSONObject().put("note", ReminderChainCore.NOTE_INTENT)
            ).status
        )
        assertEquals(
            ReminderRecord.STATUS_FAILED,
            ReminderRecord.fromJson(
                org.json.JSONObject().put("note", ReminderChainCore.NOTE_FAILED)
            ).status
        )
    }

    @Test
    fun `终态的 status 与文案自洽`() {
        assertEquals(
            ReminderRecord.STATUS_CALENDAR,
            ReminderChainCore.decide(fullCalendar(), insertedEventId = 1L).status
        )
        assertEquals(ReminderRecord.STATUS_INTENT, ReminderChainCore.intentOpened().status)
        assertEquals(ReminderRecord.STATUS_ALARM, ReminderChainCore.alarmScheduled().status)
        assertEquals(ReminderRecord.STATUS_FAILED, ReminderChainCore.failed().status)
        assertEquals(ReminderRecord.STATUS_CALENDAR, ReminderChainCore.duplicateSkip().status)
    }

    // ── L1 两段写入：提醒行缺失不能算 L1 成功 ───────────────────────────────

    @Test
    fun `提醒行缺失时不算 L1 成功并降级到闹钟`() {
        // 事件行建出来了、提醒行没建出来：这条日历事件到点不会弹提醒
        val outcome = ReminderChainCore.decide(
            fullCalendar(), insertedEventId = 777L, alarmSet = true, alertInserted = false
        )
        assertEquals(ReminderRecord.STATUS_ALARM, outcome.status)
        assertEquals(ReminderChainCore.NOTE_CALENDAR_NO_ALERT, outcome.note)
        // eventId 保留下来：删除待办时才能把这条没有提醒的日历事件一并清掉
        assertEquals(777L, outcome.eventId)
        assertNull(outcome.dropEventId)
        assertTrue(ReminderChainCore.countsAsCreated(outcome))
    }

    @Test
    fun `提醒行缺失时不再唤起日历 App`() {
        // 前台 + 能唤起日历，但已经有半成品事件：
        // 唤起会让用户再存一条同名的重复事件，而日历里那条又没有提醒。
        val env = fullCalendar().copy(inForeground = true, canOpenCalendar = true)
        val outcome = ReminderChainCore.decide(
            env, insertedEventId = 777L, intentShown = true, alarmSet = true, alertInserted = false
        )
        assertEquals(ReminderRecord.STATUS_ALARM, outcome.status)
        assertEquals(ReminderChainCore.NOTE_CALENDAR_NO_ALERT, outcome.note)
    }

    @Test
    fun `提醒行缺失且闹钟也没设上时落 failed 并交回孤儿事件 id`() {
        val outcome = ReminderChainCore.decide(
            fullCalendar(), insertedEventId = 777L, alarmSet = false, alertInserted = false
        )
        assertEquals(ReminderRecord.STATUS_FAILED, outcome.status)
        assertEquals(ReminderChainCore.NOTE_FAILED, outcome.note)
        assertNull(outcome.eventId)
        // 交回调用方删掉这条没有提醒行的日历事件，否则下一轮去重会被它挡住
        assertEquals(777L, outcome.dropEventId)
        assertFalse(ReminderChainCore.countsAsCreated(outcome))
    }

    @Test
    fun `提醒行写入成功时行为不变`() {
        val outcome = ReminderChainCore.decide(
            fullCalendar(), insertedEventId = 42L, alarmSet = true, alertInserted = true
        )
        assertEquals(ReminderRecord.STATUS_CALENDAR, outcome.status)
        assertEquals(ReminderChainCore.NOTE_CALENDAR_OK, outcome.note)
        assertEquals(42L, outcome.eventId)
        assertNull(outcome.dropEventId)
    }

    @Test
    fun `去重跳过优先于提醒行缺失`() {
        // 系统日历里已经有同名事件：无论传什么都停在「跳过」，不许再建第二条
        val env = fullCalendar().copy(eventAlreadyExists = true)
        val outcome = ReminderChainCore.decide(
            env, insertedEventId = 777L, alarmSet = true, alertInserted = false
        )
        assertEquals(ReminderRecord.STATUS_CALENDAR, outcome.status)
        assertEquals(ReminderChainCore.NOTE_DUPLICATE, outcome.note)
        assertNull(outcome.dropEventId)
    }

    @Test
    fun `降级文案能被老记录的级别推断认成闹钟`() {
        assertEquals(
            ReminderRecord.STATUS_ALARM,
            ReminderRecord.fromJson(
                org.json.JSONObject().put("note", ReminderChainCore.NOTE_CALENDAR_NO_ALERT)
            ).status
        )
    }

    @Test
    fun `created 的两参口径与 Outcome 形式一致`() {
        val outcome = ReminderChainCore.decide(fullCalendar(), insertedEventId = 5L)
        assertTrue(ReminderChainCore.countsAsCreated(outcome.status, outcome.eventId))
        // 去重跳过（CALENDAR 但无 eventId）与 L2（INTENT）都不算创建
        assertFalse(ReminderChainCore.countsAsCreated(ReminderRecord.STATUS_CALENDAR, null))
        assertFalse(ReminderChainCore.countsAsCreated(ReminderRecord.STATUS_INTENT, null))
        assertTrue(ReminderChainCore.countsAsCreated(ReminderRecord.STATUS_ALARM, null))
    }
}
