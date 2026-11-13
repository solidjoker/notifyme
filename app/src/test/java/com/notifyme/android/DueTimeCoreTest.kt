// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 「公历时间文本 ↔ 毫秒」的唯一口径（[DueTimeCore]）。
 *
 * 两层回归：
 *  - **Locale 无关**：`th-TH`（佛历）/ `ja-JP-u-ca-japanese`（和历）下格式化与解析必须与公历一致；
 *    同时用旧的 SimpleDateFormat + Calendar.getInstance() 做对照，证明旧写法确实会分叉
 *    （这就是这条 bug 的根因，不依赖某台机器的语言设置、可重复执行）。
 *  - **语义不变**：五个解析分支（完整年月日 / M月d日 / 今天明天后天 / 周X / 纯时刻）与
 *    `CalendarHelper.tryParseDueTime` 的旧实现逐条对齐，parseDay 仍然严格回读校验。
 */
class DueTimeCoreTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    /** 2026-11-12 18:10:00 +08:00（周四）。 */
    private val nowMs = 1794478200000L

    /** 2026-11-12 00:00:00 +08:00。 */
    private val todayStart = 1794412800000L

    private var savedLocale: Locale? = null

    @Before
    fun saveLocale() {
        savedLocale = Locale.getDefault()
    }

    @After
    fun restoreLocale() {
        Locale.setDefault(savedLocale ?: Locale.SIMPLIFIED_CHINESE)
    }

    private fun at(year: Int, month1: Int, day: Int, hour: Int, minute: Int): Long =
        LocalDate.of(year, month1, day).atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun fields(ms: Long): List<Int> {
        val t = Instant.ofEpochMilli(ms).atZone(zone)
        return listOf(t.year, t.monthValue, t.dayOfMonth, t.hour, t.minute)
    }

    // ---------- 格式化：恒公历 ----------

    @Test
    fun `提醒时间格式化为公历文本`() {
        assertEquals("2026-11-12 18:10", DueTimeCore.formatEventTime(nowMs, zone))
        assertEquals("2026-01-02 09:00", DueTimeCore.formatEventTime(at(2026, 1, 2, 9, 0), zone))
    }

    @Test
    fun `默认时间是严格大于 now 的本地整点`() {
        assertEquals(at(2026, 11, 12, 19, 0), DueTimeCore.nextWholeHour(nowMs, zone))
        assertEquals(at(2026, 11, 12, 19, 0), DueTimeCore.nextWholeHour(at(2026, 11, 12, 18, 0), zone))
        assertEquals("2026-11-12 19:00", DueTimeCore.defaultEventTimeText(nowMs, zone))
        // 偏移带 30/45 分钟的时区：本地钟点对齐，不是 epoch 整除一小时
        for (oddZone in listOf(ZoneId.of("Asia/Kathmandu"), ZoneId.of("Australia/Adelaide"))) {
            val localNow = LocalDate.of(2026, 11, 12).atTime(22, 17).atZone(oddZone).toInstant().toEpochMilli()
            assertEquals("2026-11-12 23:00", DueTimeCore.defaultEventTimeText(localNow, oddZone))
            val boundary = LocalDate.of(2026, 11, 12).atTime(23, 0).atZone(oddZone).toInstant().toEpochMilli()
            assertEquals(
                LocalDate.of(2026, 11, 13).atTime(0, 0).atZone(oddZone).toInstant().toEpochMilli(),
                DueTimeCore.nextWholeHour(boundary, oddZone)
            )
        }
    }

    @Test
    fun `泰语佛历下格式化仍是公历`() {
        Locale.setDefault(Locale.forLanguageTag("th-TH"))
        assertEquals("2026-11-12 18:10", DueTimeCore.formatEventTime(nowMs, zone))
        assertEquals("2026-11-12", DueTimeCore.formatDay(todayStart, zone))
    }

    @Test
    fun `和历下格式化仍是公历`() {
        Locale.setDefault(Locale.forLanguageTag("ja-JP-u-ca-japanese"))
        assertEquals("2026-11-12 18:10", DueTimeCore.formatEventTime(nowMs, zone))
        assertEquals("2026-11-12", DueTimeCore.formatDay(todayStart, zone))
    }

    @Test
    fun `对照实验-SimpleDateFormat 在佛历下给出佛历年`() {
        Locale.setDefault(Locale.forLanguageTag("th-TH"))
        val legacy = SimpleDateFormat(DueTimeCore.EVENT_TIME_PATTERN, Locale.getDefault())
        legacy.timeZone = TimeZone.getTimeZone(zone)
        val text = legacy.format(Date(nowMs))
        assertTrue("旧写法给出的是佛历年：" + text, text.startsWith("2569-11-12"))
        assertNotEquals(DueTimeCore.formatEventTime(nowMs, zone), text)
        val cal = Calendar.getInstance().apply { timeInMillis = nowMs }
        assertEquals(2569, cal.get(Calendar.YEAR))
    }

    @Test
    fun `对照实验-和历下旧写法丢掉四位年份`() {
        Locale.setDefault(Locale.forLanguageTag("ja-JP-u-ca-japanese"))
        val legacy = SimpleDateFormat(DueTimeCore.EVENT_TIME_PATTERN, Locale.getDefault())
        legacy.timeZone = TimeZone.getTimeZone(zone)
        val text = legacy.format(Date(nowMs))
        assertTrue("旧写法给出的是和历年份：" + text, !text.startsWith("2026") && text.startsWith("8-"))
        assertEquals("2026-11-12 18:10", DueTimeCore.formatEventTime(nowMs, zone))
    }

    // ---------- 解析：五个分支 ----------

    @Test
    fun `完整年月日按公历解析`() {
        assertEquals(at(2026, 11, 12, 18, 10), DueTimeCore.parse("2026-11-12 18:10", nowMs, zone))
        assertEquals(at(2026, 12, 1, 8, 30), DueTimeCore.parse("2026/12/1 8:30", nowMs, zone))
        assertEquals(at(2026, 12, 1, 8, 30), DueTimeCore.parse("2026年12月1日 8:30", nowMs, zone))
        assertEquals(at(2026, 12, 1, 9, 0), DueTimeCore.parse("2026-12-01", nowMs, zone))
    }

    @Test
    fun `非法日期不静默顺延而是返回null`() {
        assertNull(DueTimeCore.parse("2026-02-30 08:30", nowMs, zone))
        assertNull(DueTimeCore.parse("2026-13-01 08:30", nowMs, zone))
        assertNull(DueTimeCore.parse("2026-04-31 08:30", nowMs, zone))
        assertEquals(at(2028, 2, 29, 9, 0), DueTimeCore.parse("2028-02-29", nowMs, zone))
        assertNull(DueTimeCore.parse("2027-02-29", nowMs, zone))
    }

    @Test
    fun `M月d日未过取本年`() {
        assertEquals(at(2026, 11, 20, 10, 0), DueTimeCore.parse("11月20日 10:00", nowMs, zone))
    }

    @Test
    fun `M月d日已过顺延明年`() {
        assertEquals(at(2027, 11, 1, 10, 0), DueTimeCore.parse("11月1日 10:00", nowMs, zone))
        assertEquals(at(2027, 11, 12, 10, 0), DueTimeCore.parse("11月12日 10:00", nowMs, zone))
    }

    @Test
    fun `M月d日在容差内不回退`() {
        val base = at(2026, 11, 12, 18, 10)
        assertEquals(at(2026, 11, 12, 18, 9), DueTimeCore.parse("11月12日 18:09", base, zone))
    }

    @Test
    fun `今天明天后天`() {
        assertEquals(at(2026, 11, 12, 20, 0), DueTimeCore.parse("今天 20:00", nowMs, zone))
        assertEquals(at(2026, 11, 13, 9, 0), DueTimeCore.parse("明天", nowMs, zone))
        assertEquals(at(2026, 11, 14, 9, 0), DueTimeCore.parse("后天 09:00", nowMs, zone))
    }

    @Test
    fun `周X当天时刻未到取当天`() {
        assertEquals(at(2026, 11, 12, 19, 0), DueTimeCore.parse("周四 19:00", nowMs, zone))
    }

    @Test
    fun `周X当天时刻已过顺延下周`() {
        assertEquals(at(2026, 11, 19, 18, 0), DueTimeCore.parse("周四 18:00", nowMs, zone))
        assertEquals(at(2026, 11, 13, 9, 0), DueTimeCore.parse("周五 09:00", nowMs, zone))
        assertEquals(at(2026, 11, 14, 9, 0), DueTimeCore.parse("周六 09:00", nowMs, zone))
    }

    @Test
    fun `星期写法与阿拉伯数字一致`() {
        val a = DueTimeCore.parse("星期四 16:30", nowMs, zone)
        val b = DueTimeCore.parse("周4 16:30", nowMs, zone)
        val c = DueTimeCore.parse("星期4 16:30", nowMs, zone)
        assertEquals(a, b)
        assertEquals(a, c)
    }

    @Test
    fun `纯时刻未到算今天`() {
        assertEquals(at(2026, 11, 12, 20, 0), DueTimeCore.parse("20:00", nowMs, zone))
        assertEquals(at(2026, 11, 12, 20, 0), DueTimeCore.parse("晚上8点", nowMs, zone))
    }

    @Test
    fun `纯时刻已过顺延明天`() {
        assertEquals(at(2026, 11, 13, 9, 0), DueTimeCore.parse("09:00", nowMs, zone))
        assertEquals(at(2026, 11, 13, 9, 0), DueTimeCore.parse("9点", nowMs, zone))
    }

    @Test
    fun `下午晚上加十二小时`() {
        assertEquals(at(2026, 11, 13, 15, 0), DueTimeCore.parse("明天 下午3点", nowMs, zone))
        assertEquals(at(2026, 11, 13, 21, 30), DueTimeCore.parse("明天 晚上9:30", nowMs, zone))
        assertEquals(at(2026, 11, 13, 15, 0), DueTimeCore.parse("明天 下午15:00", nowMs, zone))
    }

    @Test
    fun `越界时刻与空输入返回null`() {
        assertNull(DueTimeCore.parse("25:00", nowMs, zone))
        assertNull(DueTimeCore.parse("18:61", nowMs, zone))
        assertNull(DueTimeCore.parse("有空聊", nowMs, zone))
        assertNull(DueTimeCore.parse("   ", nowMs, zone))
        assertNull(DueTimeCore.parse("", nowMs, zone))
    }

    @Test
    fun `往返一致-格式化出来的文本能被原样解析回同一毫秒`() {
        val samples = listOf(
            at(2026, 11, 12, 18, 10),
            at(2026, 1, 2, 9, 0),
            at(2027, 3, 31, 23, 59),
            at(2026, 12, 1, 0, 0)
        )
        samples.forEach { ms ->
            val text = DueTimeCore.formatEventTime(ms, zone)
            assertEquals("往返漂移：" + text, ms, DueTimeCore.parse(text, nowMs, zone))
        }
    }

    @Test
    fun `佛历下的往返与公历设备一致`() {
        Locale.setDefault(Locale.forLanguageTag("th-TH"))
        val text = DueTimeCore.formatEventTime(nowMs, zone)
        assertEquals("2026-11-12 18:10", text)
        assertEquals(nowMs, DueTimeCore.parse(text, nowMs, zone))
        assertEquals(todayStart, DueTimeCore.parseDay("2026-11-12", zone))
    }

    // ---------- 日期 ↔ 当天零点 ----------

    @Test
    fun `dayStart 对齐本地零点`() {
        assertEquals(todayStart, DueTimeCore.dayStart(nowMs, zone))
        assertEquals(todayStart, DueTimeCore.dayStart(todayStart + 1234L, zone))
        assertEquals(todayStart, DueTimeCore.dayStart(todayStart + AnalysisScopeCore.DAY_MS - 1, zone))
        assertEquals(
            todayStart + AnalysisScopeCore.DAY_MS,
            DueTimeCore.dayStart(todayStart + AnalysisScopeCore.DAY_MS, zone)
        )
    }

    @Test
    fun `佛历下 dayStart 与 parseDay 仍是公历零点`() {
        Locale.setDefault(Locale.forLanguageTag("th-TH"))
        assertEquals(todayStart, DueTimeCore.dayStart(nowMs, zone))
        assertEquals(todayStart, DueTimeCore.parseDay("2026-11-12", zone))
        val cal = Calendar.getInstance(TimeZone.getTimeZone(zone)).apply {
            clear()
            set(2026, 10, 12, 0, 0, 0)
        }
        assertNotEquals(todayStart, cal.timeInMillis)
    }

    @Test
    fun `parseDay 严格回读校验`() {
        assertNull(DueTimeCore.parseDay("2026-02-30", zone))
        assertNull(DueTimeCore.parseDay("2026-13-01", zone))
        assertNull(DueTimeCore.parseDay("2026-04-31", zone))
        assertNull(DueTimeCore.parseDay("2027-02-29", zone))
        assertEquals(at(2028, 2, 29, 0, 0), DueTimeCore.parseDay("2028-02-29", zone))
    }

    @Test
    fun `parseDay 接受斜线并拒绝脏输入`() {
        assertEquals(todayStart, DueTimeCore.parseDay("2026/11/12", zone))
        assertEquals(todayStart, DueTimeCore.parseDay("  2026-11-12  ", zone))
        assertNull(DueTimeCore.parseDay("", zone))
        assertNull(DueTimeCore.parseDay(null, zone))
        assertNull(DueTimeCore.parseDay("2026-11", zone))
        assertNull(DueTimeCore.parseDay("abc", zone))
        assertNull(DueTimeCore.parseDay("1969-12-31", zone))
    }

    @Test
    fun `formatDay 与 parseDay 往返`() {
        assertEquals("2026-11-12", DueTimeCore.formatDay(todayStart, zone))
        assertEquals(todayStart, DueTimeCore.parseDay(DueTimeCore.formatDay(todayStart, zone), zone))
        assertEquals("2027-01-01", DueTimeCore.formatDay(at(2027, 1, 1, 0, 0), zone))
    }

    // ---------- 日期选择器年份换算 ----------

    @Test
    fun `公历设备上 picker 年份原样采用`() {
        assertEquals(2026, DueTimeCore.adjustPickedYear(2026, 2026, 2026))
        assertEquals(2027, DueTimeCore.adjustPickedYear(2027, 2026, 2026))
        assertEquals(2025, DueTimeCore.adjustPickedYear(2025, 2026, 2026))
    }

    @Test
    fun `佛历设备上按改动的年数平移`() {
        assertEquals(2026, DueTimeCore.adjustPickedYear(2569, 2569, 2026))
        assertEquals(2027, DueTimeCore.adjustPickedYear(2570, 2569, 2026))
        assertEquals(2025, DueTimeCore.adjustPickedYear(2568, 2569, 2026))
    }

    @Test
    fun `和历设备同样按改动年数平移`() {
        assertEquals(2026, DueTimeCore.adjustPickedYear(8, 8, 2026))
        assertEquals(2027, DueTimeCore.adjustPickedYear(9, 8, 2026))
    }

    @Test
    fun `脏年份回落基准年`() {
        assertEquals(2026, DueTimeCore.adjustPickedYear(1900, 2026, 2026))
        assertEquals(2026, DueTimeCore.adjustPickedYear(3000, 2026, 2026))
    }

    // ---------- 年份边界 ----------

    @Test
    fun `年份上下限常量与 parseDay 一致`() {
        assertEquals(1970, DueTimeCore.MIN_YEAR)
        assertEquals(2200, DueTimeCore.MAX_YEAR)
        assertEquals(at(1970, 1, 1, 0, 0), DueTimeCore.parseDay("1970-01-01", zone))
        assertEquals(at(2200, 12, 31, 0, 0), DueTimeCore.parseDay("2200-12-31", zone))
        assertNull(DueTimeCore.parseDay("2201-01-01", zone))
    }
}
