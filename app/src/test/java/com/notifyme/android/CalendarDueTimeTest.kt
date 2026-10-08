// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

/**
 * 截止时间解析（提醒链的入口）回归测试。
 *
 * 锁住两条曾经踩过的语义：
 *  - 「周X」当天：时刻未到用当天，已过才顺延到下周（原先一律顺延，整周误差）；
 *  - 「明天/今天 HH:mm」按基准时间的日历日推算，不受跨月影响。
 *
 * 断言用 Calendar 现算期望值，因此不依赖运行时区。
 */
class CalendarDueTimeTest {

    /** 构造基准时刻（本地时区）。 */
    private fun at(year: Int, month1: Int, day: Int, hour: Int, minute: Int): Long {
        val c = Calendar.getInstance()
        c.set(year, month1 - 1, day, hour, minute, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    /** 从时间戳取出「年月日 时分」便于断言。 */
    private fun fields(ms: Long): List<Int> {
        val c = Calendar.getInstance()
        c.timeInMillis = ms
        return listOf(
            c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH),
            c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE)
        )
    }

    /** 取基准时间所在周的指定星期几（本周内）的年月日。 */
    private fun sameWeekDay(base: Long, target: Int): Triple<Int, Int, Int> {
        val c = Calendar.getInstance()
        c.timeInMillis = base
        val diff = (target - c.get(Calendar.DAY_OF_WEEK) + 7) % 7
        c.add(Calendar.DAY_OF_YEAR, diff)
        return Triple(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun `周一当天时刻未到时取当天`() {
        // 2026-10-05 是周一；基准 10:00，目标「周一 16:30」应落在同一天
        val base = at(2026, 10, 5, 10, 0)
        val due = CalendarHelper.parseDueTime("周一 16:30", base)
        assertEquals(listOf(2026, 10, 5, 16, 30), fields(due))
    }

    @Test
    fun `周一当天时刻已过时顺延到下周`() {
        // 基准 20:00，「周一 16:30」已过 → 下周一
        val base = at(2026, 10, 5, 20, 0)
        val due = CalendarHelper.parseDueTime("周一 16:30", base)
        assertEquals(listOf(2026, 10, 12, 16, 30), fields(due))
    }

    @Test
    fun `周中往后取本周内的那一天`() {
        // 2026-10-05 周一，目标周四：本周内的周四
        val base = at(2026, 10, 5, 9, 0)
        val due = CalendarHelper.parseDueTime("周四 16:30", base)
        val (y, m, d) = sameWeekDay(base, Calendar.THURSDAY)
        assertEquals(listOf(y, m, d, 16, 30), fields(due))
    }

    @Test
    fun `星期写法与阿拉伯数字一致`() {
        val base = at(2026, 10, 5, 9, 0)
        val a = CalendarHelper.parseDueTime("星期四 16:30", base)
        val b = CalendarHelper.parseDueTime("周4 16:30", base)
        val c = CalendarHelper.parseDueTime("星期4 16:30", base)
        assertEquals(a, b)
        assertEquals(a, c)
    }

    @Test
    fun `明天带时刻按次日推算并跨月`() {
        val base = at(2026, 10, 31, 9, 0)
        val due = CalendarHelper.parseDueTime("明天 16:30", base)
        assertEquals(listOf(2026, 11, 1, 16, 30), fields(due))
    }

    @Test
    fun `下午加十二小时`() {
        val base = at(2026, 10, 5, 9, 0)
        val due = CalendarHelper.parseDueTime("明天 下午3点", base)
        assertEquals(listOf(2026, 10, 6, 15, 0), fields(due))
    }

    @Test
    fun `解析不出时回退为一小时后`() {
        val base = at(2026, 10, 5, 9, 0)
        assertEquals(base + 60L * 60_000L, CalendarHelper.parseDueTime("有空聊", base))
    }
}
