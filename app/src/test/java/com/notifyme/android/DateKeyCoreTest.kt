// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import java.text.SimpleDateFormat
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [DateKeyCore] 的回归用例：day key 必须是公历 ISO、年份比较走数值，
 * 以及「非公历 Locale 下 SimpleDateFormat 会给出别历法的 key」这条根因本身。
 *
 * 固定时区 Asia/Shanghai：与其余单测同口径，别依赖跑测试的机器时区。
 */
class DateKeyCoreTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private lateinit var savedLocale: Locale

    @Before
    fun setUp() {
        savedLocale = Locale.getDefault()
    }

    @After
    fun tearDown() {
        Locale.setDefault(savedLocale)
    }

    /** 2026-11-12 15:30:00 +08:00 */
    private val noon = 1794478200000L

    @Test
    fun dayKeyOfFormatsIsoDate() {
        assertEquals("2026-11-12", DateKeyCore.dayKeyOf(noon, zone))
    }

    @Test
    fun dayKeyOfUsesZoneNotUtc() {
        // 2026-11-12 23:30 UTC → 上海已经是 13 日 07:30
        val lateUtc = 1794526200000L
        assertEquals("2026-11-13", DateKeyCore.dayKeyOf(lateUtc, zone))
        assertEquals("2026-11-12", DateKeyCore.dayKeyOf(lateUtc, ZoneId.of("UTC")))
    }

    @Test
    fun dayKeyOfNonPositiveIsUnknown() {
        assertEquals(DateKeyCore.UNKNOWN, DateKeyCore.dayKeyOf(0L, zone))
        assertEquals(DateKeyCore.UNKNOWN, DateKeyCore.dayKeyOf(-1L, zone))
    }

    @Test
    fun dayKeyIsStableForSameTimestamp() {
        val a = DateKeyCore.dayKeyOf(noon, zone)
        val b = DateKeyCore.dayKeyOf(noon, zone)
        assertEquals(a, b)
    }

    /**
     * 根因本身：SimpleDateFormat 的历法跟 Locale 走（佛历 2569、和历 8），
     * 而 [DateKeyCore.dayKeyOf] 恒为公历 —— 两者曾在 MainActivity / ConsoleActivity 混用，
     * 设备 Locale 一旦非公历，两处算出的 key 永远不相等（删不掉天、折叠键错位）。
     */
    @Test
    fun simpleDateFormatUnderThaiLocaleDivergesFromCore() {
        Locale.setDefault(Locale.forLanguageTag("th-TH"))
        val legacy = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(noon))
        assertEquals("2026-11-12", DateKeyCore.dayKeyOf(noon, zone))
        assertTrue("佛历应给出 2569 年，实测 " + legacy, legacy.startsWith("2569"))
    }

    @Test
    fun simpleDateFormatUnderJapaneseImperialDivergesFromCore() {
        Locale.setDefault(Locale.forLanguageTag("ja-JP-u-ca-japanese"))
        val legacy = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(noon))
        assertEquals("2026-11-12", DateKeyCore.dayKeyOf(noon, zone))
        assertFalse("和历不该出现公历年 2026，实测 " + legacy, legacy.startsWith("2026"))
    }

    @Test
    fun dayKeySurvivesNonGregorianDefaultLocale() {
        // 这里故意走「默认时区」重载，所以时间点必须按默认时区现造：
        // 写死 noon 在 UTC+14（Pacific/Kiritimati）下已经是 11-13，断言会误红。
        Locale.setDefault(Locale.forLanguageTag("th-TH"))
        val defaultZone = ZoneId.systemDefault()
        val localNoon = java.time.LocalDateTime.of(2026, 11, 12, 15, 30)
            .atZone(defaultZone).toInstant().toEpochMilli()
        assertEquals("2026-11-12", DateKeyCore.dayKeyOf(localNoon))
        assertEquals("2026-11-12", DateKeyCore.dayKeyOf(localNoon, defaultZone))
    }

    @Test
    fun isUnknownAcceptsBlankAndNull() {
        assertTrue(DateKeyCore.isUnknown(null))
        assertTrue(DateKeyCore.isUnknown(""))
        assertTrue(DateKeyCore.isUnknown(DateKeyCore.UNKNOWN))
        assertFalse(DateKeyCore.isUnknown("2026-11-12"))
    }

    @Test
    fun partsParseValidKey() {
        assertEquals(DateKeyCore.Parts(2026, 11, 12), DateKeyCore.parts("2026-11-12"))
    }

    @Test
    fun partsRejectMalformed() {
        assertNull(DateKeyCore.parts(""))
        assertNull(DateKeyCore.parts("2026-1-2"))
        assertNull(DateKeyCore.parts("2026/11/12"))
        assertNull(DateKeyCore.parts("2026-11-12 "))
        assertNull(DateKeyCore.parts("abcd-ef-gh"))
        assertNull(DateKeyCore.parts(DateKeyCore.UNKNOWN))
    }

    @Test
    fun partsRejectImpossibleDate() {
        // 2 月 30 日不能静默变成 3 月 2 日
        assertNull(DateKeyCore.parts("2026-02-30"))
        assertNull(DateKeyCore.parts("2026-13-01"))
        assertNull(DateKeyCore.parts("2026-00-10"))
        assertNull(DateKeyCore.parts("2026-04-31"))
    }

    @Test
    fun partsAcceptLeapDay() {
        assertEquals(DateKeyCore.Parts(2024, 2, 29), DateKeyCore.parts("2024-02-29"))
        assertNull(DateKeyCore.parts("2026-02-29"))
    }

    @Test
    fun yearOfReadsNumericYear() {
        assertEquals(2026, DateKeyCore.yearOf("2026-11-12"))
        assertNull(DateKeyCore.yearOf(DateKeyCore.UNKNOWN))
    }

    @Test
    fun labelSameYear() {
        assertEquals(
            DateKeyCore.DayLabel(2026, 3, 5, crossYear = false, unknown = false),
            DateKeyCore.labelOf("2026-03-05", noon, zone)
        )
    }

    @Test
    fun labelCrossYear() {
        assertEquals(
            DateKeyCore.DayLabel(2025, 12, 31, crossYear = true, unknown = false),
            DateKeyCore.labelOf("2025-12-31", noon, zone)
        )
    }

    @Test
    fun labelUnknownForBadKey() {
        val label = DateKeyCore.labelOf(DateKeyCore.UNKNOWN, noon, zone)
        assertTrue(label.unknown)
        assertEquals(0, label.year)
    }

    @Test
    fun labelCrossYearUsesNumericYearNotStringPrefix() {
        // 佛历下旧实现拿 SimpleDateFormat("yyyy") 得 "2569"，"2026-11-12".startsWith("2569") 为假
        // → 同年分组全被渲染成跨年格式。这里直接断言同年不跨年。
        Locale.setDefault(Locale.forLanguageTag("th-TH"))
        assertFalse(DateKeyCore.labelOf("2026-11-12", noon, zone).crossYear)
    }

    @Test
    fun labelTreatsNonPositiveNowAsSameYear() {
        assertFalse(DateKeyCore.labelOf("2026-11-12", 0L, zone).crossYear)
    }

    @Test
    fun isCrossYearMatchesLabel() {
        assertFalse(DateKeyCore.isCrossYear("2026-01-01", noon, zone))
        assertTrue(DateKeyCore.isCrossYear("2025-01-01", noon, zone))
        assertFalse(DateKeyCore.isCrossYear(DateKeyCore.UNKNOWN, noon, zone))
    }

    @Test
    fun latestOfPicksMaxIsoKey() {
        assertEquals(
            "2026-11-12",
            DateKeyCore.latestOf(listOf("2026-03-05", "2026-11-12", "2025-12-31"))
        )
    }

    @Test
    fun latestOfSkipsUnknownWhenRealDaysExist() {
        // "__unknown__" 里 "_"=0x5F 大于数字，字典序上会赢；必须显式排除
        assertEquals(
            "2026-11-12",
            DateKeyCore.latestOf(listOf(DateKeyCore.UNKNOWN, "2026-11-12"))
        )
    }

    @Test
    fun latestOfFallsBackToUnknown() {
        assertEquals(DateKeyCore.UNKNOWN, DateKeyCore.latestOf(listOf(DateKeyCore.UNKNOWN)))
        assertEquals(DateKeyCore.UNKNOWN, DateKeyCore.latestOf(emptyList()))
    }

    @Test
    fun latestOfHonoursCustomUnknownToken() {
        assertEquals("-", DateKeyCore.latestOf(listOf("-"), unknown = "-"))
    }

    @Test
    fun unknownTokenMatchesMessageStore() {
        // 两处字面量必须一致：老数据里已经写进 JSONL 与 prefs 的就是这个值
        assertEquals(MessageStore.DAY_KEY_UNKNOWN, DateKeyCore.UNKNOWN)
    }

    @Test
    fun messageStoreDayKeyDelegatesToCore() {
        val msg = ChatMessage(
            pkg = "com.tencent.mm",
            sender = "李四",
            text = "明天交付",
            timestamp = noon,
            conversation = "甲项目群",
            isGroup = true
        )
        assertEquals(
            DateKeyCore.dayKeyOf(noon, ZoneId.systemDefault()),
            MessageStore.dayKeyOf(msg)
        )
        assertEquals(DateKeyCore.UNKNOWN, MessageStore.dayKeyOf(msg.copy(timestamp = 0L)))
    }

    @Test
    fun collapseStoreLatestDelegatesToCore() {
        assertEquals(
            "2026-11-12",
            CollapseStore.latestDayKeyOf(
                listOf(DateKeyCore.UNKNOWN, "2026-11-12"),
                DateKeyCore.UNKNOWN
            )
        )
    }
}
