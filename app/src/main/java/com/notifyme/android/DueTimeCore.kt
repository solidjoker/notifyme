// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.regex.Pattern

/**
 * 本地「公历时间文本 ↔ 毫秒」的唯一口径。
 *
 * 为什么要抽：这类往返此前散在三处，且都吃**默认 Locale**——
 *  - [ManualReminderCore.eventTimeFormat] 用 `SimpleDateFormat(pattern, Locale.getDefault())` 格式化手动提醒时间；
 *  - `CalendarHelper.tryParseDueTime` 用 `Calendar.getInstance()` 解析同一条文本；
 *  - [AnalysisScopeCore.dayStart] / [AnalysisScopeCore.parseDay] 用 `Calendar.getInstance(zone)` 造当天零点与日期。
 *
 * **这不是风格问题，是正确性问题**：`SimpleDateFormat` / `Calendar.getInstance()` 的历法跟着 Locale 走——
 * 同一毫秒（2026-11-12 15:30 +08:00）在 `th-TH`（佛历）下格式化成 `2569-11-12`、
 * 在 `ja-JP-u-ca-japanese`（和历）下是 `8-11-12`，而 `java.time` 恒为公历。
 * 于是泰语设备上手动提醒弹窗默认填 `2569-11-12 18:00`；用户在日期选择器里改年份（widget 里显示的也是佛历年），
 * `Calendar.set(2027, …)` 被当成佛历 2027 = 公历 1484 年，保存时判成「时间已经过去」；
 * 和历下更糟，`format` 只给一位年份，`\d{4}` 正则不命中、整个日期被静默丢弃。
 * 同一台设备上 `parseDay("2026-11-12")` 也会得到公历 1483 年（自定义分析范围整段失效）。
 *
 * 所以这里**只用 `java.time`**（ISO 历法）产生与解析文本，不碰 `Calendar` / `SimpleDateFormat` 的默认历法。
 * 类注释是契约：新增「文本 ↔ 毫秒」的转换一律加在这里，别在 Activity 里 new 一个 formatter。
 * 中文相对词（今天 / 明天 / 周X / 下午）是应用的单语约定，直接写在这里，Core 不依赖资源表。
 */
internal object DueTimeCore {

    /** 提醒时间框的规范格式：`yyyy-MM-dd HH:mm`，公历、24 小时制、本地时区。 */
    const val EVENT_TIME_PATTERN = "yyyy-MM-dd HH:mm"

    /** 日文本格式（自定义分析范围的日期输入框）：`yyyy-MM-dd`。 */
    const val DAY_PATTERN = "yyyy-MM-dd"

    /** 时刻片段：`HH:mm` / `H点` / `下午3点`（分钟可省）。 */
    private val TIME_PATTERN: Pattern = Pattern.compile("(\\d{1,2})\\s*[:：点时]\\s*(\\d{1,2})?")

    /** 完整年月日：`2026-11-12` / `2026/11/12` / `2026年11月12日`。 */
    private val FULL_DATE: Pattern = Pattern.compile("(\\d{4})\\s*[-/年]\\s*(\\d{1,2})\\s*[-/月]\\s*(\\d{1,2})")

    /** 无年份的 `M月d日`（已过则顺延明年）。 */
    private val MONTH_DAY: Pattern = Pattern.compile("(\\d{1,2})\\s*月\\s*(\\d{1,2})\\s*[日号]")

    /** `周X` / `星期X`。 */
    private val WEEKDAY: Pattern = Pattern.compile("[周星]\\s*期?\\s*([一二三四五六日天1-7])")

    /** 手工构造 formatter：`DateTimeFormatter` 的 `yyyy` 是 ISO 公历年，不受默认 Locale 的历法影响。 */
    fun formatter(pattern: String = EVENT_TIME_PATTERN): DateTimeFormatter =
        DateTimeFormatter.ofPattern(pattern, Locale.ROOT)

    // ---------- 手动提醒：整点填充 + 文本 ↔ 毫秒 ----------

    /** 取严格大于 [now] 的下一个整点（毫秒级）。 */
    fun nextWholeHour(now: Long): Long {
        val hourMillis = 60L * 60_000L
        return (now / hourMillis + 1) * hourMillis
    }

    /** 格式化提醒时间（公历 `yyyy-MM-dd HH:mm`）。 */
    fun formatEventTime(ms: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(ms).atZone(zone).format(formatter())

    /** 弹窗默认时间文本：下一个整点，成为用户不用改就能保存的合法值。 */
    fun defaultEventTimeText(now: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        formatEventTime(nextWholeHour(now), zone)

    /**
     * 解析用户/模型给的时间文本，**解析不出返回 null**（不在这里做 +1h 兜底，那是提醒链的选择）。
     *
     * 支持：完整年月日 / `M月d日`（已过顺延明年）/ 今天明天后天 / 周X / 下午晚上 +12 / 纯 `HH:mm`。
     * [baseTime] 只用来取「今天」与判断「已过」，全部按 [zone] 的公历日推算。
     */
    fun parse(text: String, baseTime: Long, zone: ZoneId = ZoneId.systemDefault()): Long? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        return try {
            val base = Instant.ofEpochMilli(baseTime).atZone(zone)
            val today = base.toLocalDate()

            // 时间部分（可选）：HH:mm / H点 / 下午H点；没有就按 9:00
            var hour = 9
            var minute = 0
            val tm = TIME_PATTERN.matcher(trimmed)
            val hasTime = tm.find()
            if (hasTime) {
                hour = tm.group(1)?.toIntOrNull() ?: 9
                minute = tm.group(2)?.toIntOrNull() ?: 0
                if ((trimmed.contains("下午") || trimmed.contains("晚上") || trimmed.contains("傍晚")) && hour < 12) {
                    hour += 12
                }
                if (hour > 23 || minute > 59) return null
            }

            fun at(date: LocalDate): Long =
                date.atTime(LocalTime.of(hour, minute)).atZone(zone).toInstant().toEpochMilli()

            // 1) 带完整年月日 —— LocalDate.of 对 2026-02-30 这类非法日期抛异常，直接返 null（不静默顺延）
            val full = FULL_DATE.matcher(trimmed)
            if (full.find()) {
                val date = LocalDate.of(
                    full.group(1)!!.toInt(),
                    full.group(2)!!.toInt(),
                    full.group(3)!!.toInt()
                )
                return at(date)
            }

            // 2) M月d日：缺年份取本年，已过去顺延明年
            val md = MONTH_DAY.matcher(trimmed)
            if (md.find()) {
                val month = md.group(1)!!.toInt()
                val day = md.group(2)!!.toInt()
                val thisYear = at(LocalDate.of(today.year, month, day))
                // 已过去（留 1 分钟容差，与旧实现一致）才顺延明年
                return if (thisYear < baseTime - 60_000L) at(LocalDate.of(today.year + 1, month, day)) else thisYear
            }

            // 3) 今天 / 明天 / 后天
            val dayOffset = when {
                trimmed.contains("后天") -> 2
                trimmed.contains("明天") -> 1
                trimmed.contains("今天") -> 0
                else -> -1
            }
            if (dayOffset >= 0) return at(today.plusDays(dayOffset.toLong()))

            // 4) 周X：取未来最近一次；就是今天时，时刻未到用今天、已过才顺延下周
            val week = WEEKDAY.matcher(trimmed)
            if (week.find()) {
                val target = when (week.group(1)) {
                    "一", "1" -> DayOfWeek.MONDAY
                    "二", "2" -> DayOfWeek.TUESDAY
                    "三", "3" -> DayOfWeek.WEDNESDAY
                    "四", "4" -> DayOfWeek.THURSDAY
                    "五", "5" -> DayOfWeek.FRIDAY
                    "六", "6" -> DayOfWeek.SATURDAY
                    else -> DayOfWeek.SUNDAY
                }
                var diff = (target.value - today.dayOfWeek.value + 7) % 7
                if (diff == 0 && at(today) <= baseTime) diff = 7
                return at(today.plusDays(diff.toLong()))
            }

            // 5) 只有时刻：今天该时刻，已过顺延明天
            if (hasTime) {
                val todayAt = at(today)
                return if (todayAt <= baseTime) at(today.plusDays(1L)) else todayAt
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 系统 DatePicker 回传的年份按默认 Locale 的历法显示（泰语 widget 上 [shownYear]=2569），
     * 换算成公历年：**用「widget 年份 − widget 当时的年份」的平移量**，加到基准公历年上。
     *
     * 公历设备上 [shownYear] == [baseYear]，平移量恒为 0（等价于直接用 widget 的值）；
     * 佛历设备上 2569 ↔ 2026、2571 ↔ 2028；和历 8 ↔ 2026 同理（和历的偏移随年号变，
     * 所以只能按「改了多少年」平移，不能写死 +1988 / +543 之类的常数）。
     * 结果超出 [MIN_YEAR]..[MAX_YEAR] 一律回落 [baseYear]（widget 异常时不让脏值落库）。
     */
    fun adjustPickedYear(pickedYear: Int, shownYear: Int, baseYear: Int): Int {
        val year = baseYear + (pickedYear - shownYear)
        return if (year in MIN_YEAR..MAX_YEAR) year else baseYear
    }

    /** [parse] / [parseDay] 接受的年份下限（与 Settings 输入框口径一致）。 */
    const val MIN_YEAR = 1970

    /** 上限给足到 2200：提醒/分析范围不需要更远的未来。 */
    const val MAX_YEAR = 2200

    // ---------- 日期文本 ↔ 当天零点 ----------

    /** 本地时区当天零点（公历）。 */
    fun dayStart(nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
        Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
            .atStartOfDay(zone).toInstant().toEpochMilli()

    /** 格式化日期文本（`yyyy-MM-dd`，公历）。 */
    fun formatDay(ms: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(ms).atZone(zone).format(formatter(DAY_PATTERN))

    /**
     * 解析 `yyyy-MM-dd`（也接受 `yyyy/MM/dd`）为当天零点；非法输入返回 **null** 而不是抛异常。
     * 严格回读：`2026-02-30` / `2026-13-01` / `2026-04-31` 一律 null，不静默改成 3 月 2 日。
     */
    fun parseDay(text: String?, zone: ZoneId = ZoneId.systemDefault()): Long? {
        val raw = text?.trim().orEmpty().replace('/', '-')
        if (raw.isEmpty()) return null
        val parts = raw.split('-')
        if (parts.size != 3) return null
        val y = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        val d = parts[2].toIntOrNull() ?: return null
        if (y < MIN_YEAR || y > MAX_YEAR || m !in 1..12 || d !in 1..31) return null
        return try {
            LocalDate.of(y, m, d).atStartOfDay(zone).toInstant().toEpochMilli()
        } catch (e: java.time.DateTimeException) {
            null
        }
    }
}
