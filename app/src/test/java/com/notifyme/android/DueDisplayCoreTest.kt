// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「什么时候要完成」的显示口径单测。
 *
 * 契约（与 DueDisplayCore 的注释一一对应）：
 * 1. 目标日 == 今天 -> 「今天」；== 明天 -> 「明天」；其余 -> 「M月d日」（跨年带年）。
 * 2. 文本 -> 毫秒只走 DueTimeCore.parse（显示日期与提醒落点必须一致）。
 * 3. 相对词的锚点是「说话那一刻」：手动覆盖用编辑时刻，模型结论用分析时刻。
 * 4. 桶码值也变绝对日期：today / tomorrow 按锚点推算，this_week 展开成周一到周日。
 * 5. 时刻只在文本里写了才显示（没写时刻不印 09:00）。
 * 6. 解析不出原样显示，不猜不吞。
 */
class DueDisplayCoreTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    private fun ms(
        year: Int,
        month: Int,
        day: Int,
        hour: Int = 0,
        minute: Int = 0
    ): Long = LocalDateTime.of(year, month, day, hour, minute)
        .atZone(zone)
        .toInstant()
        .toEpochMilli()

    /** 2026-11-16 10:00（当作「现在」）。 */
    private val now = ms(2026, 11, 16, 10, 0)

    private fun labels(id: Int): String = when (id) {
        R.string.due_today -> "今天"
        R.string.due_tomorrow -> "明天"
        R.string.due_later -> "更晚"
        R.string.due_none -> "无明确时间"
        else -> "?"
    }

    // ---- 空值与「没有截止」 ----

    @Test
    fun emptyRawMeansNoDue() {
        assertEquals(DueDisplayCore.KIND_NONE, DueDisplayCore.resolve("", now, now, zone).kind)
        assertEquals(DueDisplayCore.KIND_NONE, DueDisplayCore.resolve(null, now, now, zone).kind)
        assertEquals(DueDisplayCore.KIND_NONE, DueDisplayCore.resolve("   ", now, now, zone).kind)
    }

    @Test
    fun noneBucketAndAliasMeanNoDue() {
        assertEquals(DueDisplayCore.KIND_NONE, DueDisplayCore.resolve("none", now, now, zone).kind)
        assertEquals(DueDisplayCore.KIND_NONE, DueDisplayCore.resolve("无明确时间", now, now, zone).kind)
        assertEquals(DueDisplayCore.KIND_NONE, DueDisplayCore.resolve("无明确", now, now, zone).kind)
    }

    // ---- 桶码值（S1 的 due_window） ----

    @Test
    fun todayBucketBecomesToday() {
        val d = DueDisplayCore.resolve("today", now, now, zone)
        assertEquals(DueDisplayCore.KIND_TODAY, d.kind)
        assertEquals("", d.timeText)
    }

    @Test
    fun tomorrowBucketBecomesTomorrow() {
        val d = DueDisplayCore.resolve("tomorrow", now, now, zone)
        assertEquals(DueDisplayCore.KIND_TOMORROW, d.kind)
    }

    @Test
    fun chineseBucketAliasesAlsoWork() {
        assertEquals(DueDisplayCore.KIND_TODAY, DueDisplayCore.resolve("今天", now, now, zone).kind)
        assertEquals(DueDisplayCore.KIND_TOMORROW, DueDisplayCore.resolve("明天", now, now, zone).kind)
        assertEquals(DueDisplayCore.KIND_LATER, DueDisplayCore.resolve("更晚些", now, now, zone).kind)
    }

    @Test
    fun laterBucketStaysLater() {
        // 「更晚」没有锚点可展开，原样保留（不猜哪天）
        val d = DueDisplayCore.resolve("later", now, now, zone)
        assertEquals(DueDisplayCore.KIND_LATER, d.kind)
        assertEquals("", d.dateText)
    }

    @Test
    fun thisWeekBucketExpandsToTheWeekRange() {
        val anchorDate = LocalDate.of(2026, 11, 16)
        val start = anchorDate.minusDays((anchorDate.dayOfWeek.value - 1).toLong())
        val end = start.plusDays(6)
        val expected =
            "${start.monthValue}月${start.dayOfMonth}日-" +
                "${end.monthValue}月${end.dayOfMonth}日"
        val d = DueDisplayCore.resolve("this_week", now, now, zone)
        assertEquals(DueDisplayCore.KIND_DATE, d.kind)
        assertEquals(expected, d.dateText)
    }

    // ---- 绝对日期 ----

    @Test
    fun monthDayBecomesAbsoluteDate() {
        val d = DueDisplayCore.resolve("11月20日", now, now, zone)
        assertEquals(DueDisplayCore.KIND_DATE, d.kind)
        assertEquals("11月20日", d.dateText)
        assertEquals("", d.timeText)
    }

    @Test
    fun explicitTimeIsKept() {
        val d = DueDisplayCore.resolve("11月20日 15:00", now, now, zone)
        assertEquals(DueDisplayCore.KIND_DATE, d.kind)
        assertEquals("11月20日", d.dateText)
        assertEquals("15:00", d.timeText)
    }

    @Test
    fun crossYearDateKeepsTheYear() {
        val d = DueDisplayCore.resolve("2027年1月5日", now, now, zone)
        assertEquals(DueDisplayCore.KIND_DATE, d.kind)
        assertEquals("2027年1月5日", d.dateText)
    }

    @Test
    fun sameYearDateTextHasNoYear() {
        assertEquals("11月16日", DueDisplayCore.dateText(LocalDate.of(2026, 11, 16), 2026))
        assertEquals("2027年1月5日", DueDisplayCore.dateText(LocalDate.of(2027, 1, 5), 2026))
    }

    // ---- 相对词 + 时刻 ----

    @Test
    fun relativeTomorrowKeepsTheTime() {
        val d = DueDisplayCore.resolve("明天15:00", now, now, zone)
        assertEquals(DueDisplayCore.KIND_TOMORROW, d.kind)
        assertEquals("15:00", d.timeText)
    }

    @Test
    fun afternoonHourIsPlusTwelve() {
        val d = DueDisplayCore.resolve("今天下午3点", now, now, zone)
        assertEquals(DueDisplayCore.KIND_TODAY, d.kind)
        assertEquals("15:00", d.timeText)
    }

    @Test
    fun bucketWithoutTimePrintsNoTime() {
        // 解析兜底是 09:00，但文本没写时刻 -> 不印出来
        val d = DueDisplayCore.resolve("明天", now, now, zone)
        assertEquals(DueDisplayCore.KIND_TOMORROW, d.kind)
        assertEquals("", d.timeText)
    }

    @Test
    fun unparsableTextIsShownVerbatim() {
        val d = DueDisplayCore.resolve("尽快", now, now, zone)
        assertEquals(DueDisplayCore.KIND_TEXT, d.kind)
        assertEquals("尽快", d.rawText)
    }

    // ---- 锚点：说话那一刻，不是「现在」 ----

    @Test
    fun anchorIsTheEditMomentNotNow() {
        // 昨天写的「明天」= 今天的明天？不对：昨天 + 1 天 = 今天 -> 显示「今天」
        val anchor = ms(2026, 11, 15, 10, 0)
        val d = DueDisplayCore.resolve("明天", anchor, now, zone)
        assertEquals(DueDisplayCore.KIND_TODAY, d.kind)
    }

    @Test
    fun modelDueUsesTheAnalyzedAtAnchor() {
        val d = DueDisplayCore.resolveOf(
            "", 0L, "明天", "", ms(2026, 11, 15, 10, 0), now, zone
        )
        assertEquals(DueDisplayCore.KIND_TODAY, d.kind)
    }

    @Test
    fun manualOverrideWinsOverModelDue() {
        val d = DueDisplayCore.resolveOf(
            "明天15:00", now, "12月1日", "this_week", now, now, zone
        )
        assertEquals(DueDisplayCore.KIND_TOMORROW, d.kind)
        assertEquals("15:00", d.timeText)
    }

    @Test
    fun modelDueWinsOverS1Window() {
        val d = DueDisplayCore.resolveOf("", 0L, "11月20日", "this_week", now, now, zone)
        assertEquals(DueDisplayCore.KIND_DATE, d.kind)
        assertEquals("11月20日", d.dateText)
    }

    @Test
    fun s1WindowIsUsedWhenNothingElse() {
        val d = DueDisplayCore.resolveOf("", 0L, "", "this_week", now, now, zone)
        assertEquals(DueDisplayCore.KIND_DATE, d.kind)
        assertTrue(d.dateText.contains("-"))
    }

    @Test
    fun nothingMeansNoDue() {
        val d = DueDisplayCore.resolveOf("", 0L, "", "", now, now, zone)
        assertEquals(DueDisplayCore.KIND_NONE, d.kind)
    }

    @Test
    fun missingEditMomentFallsBackToAnalyzedAt() {
        // 老记录没有 todo_due_at（0）-> 锚点退回分析时刻，而不是「现在」
        val analyzed = ms(2026, 11, 20, 9, 0)
        val d = DueDisplayCore.resolveOf("明天", 0L, "", "", analyzed, now, zone)
        assertEquals(DueDisplayCore.KIND_DATE, d.kind)
        assertEquals("11月21日", d.dateText)
    }

    // ---- 展示文本 ----

    @Test
    fun composeJoinsDateAndTime() {
        val d = DueDisplayCore.Display(DueDisplayCore.KIND_DATE, "11月20日", "15:00")
        assertEquals("11月20日 15:00", DueDisplayCore.compose(d, ::labels))
    }

    @Test
    fun composeUsesTheLabelForToday() {
        val d = DueDisplayCore.Display(DueDisplayCore.KIND_TODAY)
        assertEquals("今天", DueDisplayCore.compose(d, ::labels))
    }

    @Test
    fun composeKeepsVerbatimText() {
        val d = DueDisplayCore.Display(DueDisplayCore.KIND_TEXT, rawText = "尽快")
        assertEquals("尽快", DueDisplayCore.compose(d, ::labels))
    }

    @Test
    fun labelResMapsKinds() {
        assertEquals(R.string.due_today, DueDisplayCore.labelRes(DueDisplayCore.Display(DueDisplayCore.KIND_TODAY)))
        assertEquals(R.string.due_tomorrow, DueDisplayCore.labelRes(DueDisplayCore.Display(DueDisplayCore.KIND_TOMORROW)))
        assertEquals(R.string.due_later, DueDisplayCore.labelRes(DueDisplayCore.Display(DueDisplayCore.KIND_LATER)))
        assertEquals(R.string.due_none, DueDisplayCore.labelRes(DueDisplayCore.Display(DueDisplayCore.KIND_NONE)))
        assertNull(DueDisplayCore.labelRes(DueDisplayCore.Display(DueDisplayCore.KIND_DATE)))
        assertNull(DueDisplayCore.labelRes(DueDisplayCore.Display(DueDisplayCore.KIND_TEXT)))
    }

    @Test
    fun joinDateTimeSkipsEmptyTime() {
        assertEquals("11月20日", DueDisplayCore.joinDateTime("11月20日", ""));
        assertEquals("11月20日 15:00", DueDisplayCore.joinDateTime("11月20日", "15:00"))
    }
}
