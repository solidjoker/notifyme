// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import java.text.SimpleDateFormat
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [PromptTimeCore] 的回归用例：提示词里的绝对时间文本必须**恒公历**。
 *
 * 前半段（中文/英文/ISO 三种格式）钉 `Asia/Shanghai` 与 `UTC` 两个时区，不依赖跑测试的机器；
 * 后半段（`nonGregorianDefaultLocale*`）把默认 Locale 切成佛历 `th-TH` 与和历
 * `ja-JP-u-ca-japanese`，断言 Core 输出不受影响、同时用旧的 `SimpleDateFormat` 做对照，
 * 证明「钉 Locale.CHINESE/US 就安全」是一条隐式依赖而不是保证。
 */
class PromptTimeCoreTest {

    private val shanghai: ZoneId = ZoneId.of("Asia/Shanghai")
    private val utc: ZoneId = ZoneId.of("UTC")
    private lateinit var savedLocale: Locale

    @Before
    fun setUp() {
        savedLocale = Locale.getDefault()
    }

    @After
    fun tearDown() {
        Locale.setDefault(savedLocale)
    }

    /** 2026-10-18 17:09:00 +08:00 —— 周日。 */
    private val sunday = 1792314540000L

    /** 2026-10-05 10:00:00 +08:00 —— 周一，与 [sunday] 同年。 */
    private val sameYearMonday = LocalDateTime.of(2026, 10, 5, 10, 0)
        .atZone(shanghai).toInstant().toEpochMilli()

    /** 2025-12-31 23:59:00 +08:00 —— 与 [sunday] 跨年。 */
    private val previousYear = LocalDateTime.of(2025, 12, 31, 23, 59)
        .atZone(shanghai).toInstant().toEpochMilli()

    // ---------------- 中文提示词时间锚点 ----------------

    @Test
    fun chineseStampUsesIsoYearAndChineseWeekday() {
        assertEquals("2026-10-18 周日 17:09", PromptTimeCore.chineseStamp(sunday, shanghai))
    }

    @Test
    fun chineseStampIs24HourWithLeadingZeros() {
        val early = LocalDateTime.of(2026, 1, 2, 3, 4)
            .atZone(shanghai).toInstant().toEpochMilli()
        assertEquals("2026-01-02 周五 03:04", PromptTimeCore.chineseStamp(early, shanghai))
    }

    @Test
    fun todayCnKeepsChineseYearMonthDayShape() {
        assertEquals("2026年10月18日 星期日 17:09", PromptTimeCore.todayCn(sunday, shanghai))
    }

    @Test
    fun todayCnDoesNotPadMonthOrDay() {
        val thin = LocalDateTime.of(2026, 3, 5, 9, 7)
            .atZone(shanghai).toInstant().toEpochMilli()
        assertEquals("2026年3月5日 星期四 09:07", PromptTimeCore.todayCn(thin, shanghai))
    }

    // ---------------- 英文提示词时间锚点 ----------------

    @Test
    fun todayEnMatchesLegacySentence() {
        assertEquals(
            "Sunday, 2026-10-18, current time 09:09",
            PromptTimeCore.todayEn(sunday, utc)
        )
    }

    @Test
    fun dueAnchorIsWeekdayThenIsoDateTime() {
        assertEquals("Sunday 2026-10-18 09:09", PromptTimeCore.dueAnchor(sunday, utc))
    }

    @Test
    fun englishAnchorsContainNoCjk() {
        val anchor = PromptTimeCore.dueAnchor(sunday, utc) + PromptTimeCore.todayEn(sunday, utc)
        assertFalse(anchor, Regex("[\u4e00-\u9fff]").containsMatchIn(anchor))
    }

    // ---------------- 报告抬头 ISO ----------------

    @Test
    fun isoStampCarriesOffset() {
        assertEquals("2026-10-18T17:09:00+08:00", PromptTimeCore.isoStamp(sunday, shanghai))
    }

    @Test
    fun isoStampForUtcUsesPlusZero() {
        assertEquals("2026-10-18T09:09:00Z", PromptTimeCore.isoStamp(sunday, utc))
    }

    // ---------------- 消息时间标签 ----------------

    @Test
    fun messageLabelOmitsYearWithinSameYear() {
        assertEquals("[10-05 10:00]", PromptTimeCore.messageLabel(sameYearMonday, sunday, shanghai))
    }

    @Test
    fun messageLabelKeepsYearAcrossYears() {
        assertEquals("[2025-12-31 23:59]", PromptTimeCore.messageLabel(previousYear, sunday, shanghai))
    }

    @Test
    fun messageLabelComparesYearsNumerically() {
        // 同一「年末 → 年初」跳变：2026-12-31 23:59 与 2027-01-01 00:05 互为「现在」，两年份必须都带全
        val endOfYear = LocalDateTime.of(2026, 12, 31, 23, 59)
            .atZone(shanghai).toInstant().toEpochMilli()
        val newYear = LocalDateTime.of(2027, 1, 1, 0, 5)
            .atZone(shanghai).toInstant().toEpochMilli()
        assertEquals("[2027-01-01 00:05]", PromptTimeCore.messageLabel(newYear, endOfYear, shanghai))
        assertEquals("[2026-12-31 23:59]", PromptTimeCore.messageLabel(endOfYear, newYear, shanghai))
    }

    @Test
    fun messageLabelWrapsInSingleBracketPair() {
        val label = PromptTimeCore.messageLabel(sameYearMonday, sunday, shanghai)
        assertTrue(label, label.startsWith("[") && label.endsWith("]"))
        assertEquals(1, label.count { it == '[' })
    }

    @Test
    fun messageLabelEpochZeroStaysFormatted() {
        // ts = 0 是「未标注时间」的常见占位，格式必须仍然成立（不在这里兜底文案）；
        // 与 2026 相比是跨年，所以落在全日期形态 —— 只钉「能格式化出完整日期」，不钉省略年份。
        val label = PromptTimeCore.messageLabel(0L, sunday, shanghai)
        assertTrue(label, Regex("""\[\d{4}-\d{2}-\d{2} \d{2}:\d{2}]""").matches(label))
    }

    // ---------------- 非公历默认 Locale 回归 ----------------

    @Test
    fun thaiBuddhistDefaultLocaleKeepsGregorianYear() {
        Locale.setDefault(Locale.forLanguageTag("th-TH"))
        assertEquals("2026-10-18 周日 17:09", PromptTimeCore.chineseStamp(sunday, shanghai))
        assertEquals("2026年10月18日 星期日 17:09", PromptTimeCore.todayCn(sunday, shanghai))
        assertEquals("Sunday 2026-10-18 09:09", PromptTimeCore.dueAnchor(sunday, utc))
        assertEquals("2026-10-18T17:09:00+08:00", PromptTimeCore.isoStamp(sunday, shanghai))
    }

    @Test
    fun japaneseImperialDefaultLocaleKeepsGregorianYear() {
        Locale.setDefault(Locale.forLanguageTag("ja-JP-u-ca-japanese"))
        assertEquals("2026-10-18 周日 17:09", PromptTimeCore.chineseStamp(sunday, shanghai))
        assertEquals("2026-10-18T17:09:00+08:00", PromptTimeCore.isoStamp(sunday, shanghai))
    }

    @Test
    fun arabicDefaultLocaleKeepsAsciiDigits() {
        Locale.setDefault(Locale.forLanguageTag("ar-EG"))
        val stamp = PromptTimeCore.chineseStamp(sunday, shanghai)
        assertFalse(stamp, Regex("[\u0660-\u0669\u06f0-\u06f9]").containsMatchIn(stamp))
        assertTrue(stamp, stamp.startsWith("2026-10-18"))
    }

    @Test
    fun messageLabelSameYearCheckSurvivesBuddhistLocale() {
        Locale.setDefault(Locale.forLanguageTag("th-TH"))
        assertEquals("[10-05 10:00]", PromptTimeCore.messageLabel(sameYearMonday, sunday, shanghai))
    }

    @Test
    fun legacySimpleDateFormatUnderBuddhistLocaleGivesOldYear() {
        // 对照实验：旧写法在佛历下给出 2569，而 Core 恒 2026 —— 差值就是当年整套断言红的原因
        Locale.setDefault(Locale.forLanguageTag("th-TH"))
        val legacy = SimpleDateFormat("yyyy", Locale.getDefault()).format(Date(sunday))
        assertTrue("佛历应给出 2569 年，实测 " + legacy, legacy == "2569")
        assertTrue(PromptTimeCore.chineseStamp(sunday, shanghai).startsWith("2026"))
    }

    @Test
    fun legacySimpleDateFormatPinnedToUsIsOnlyAccidentallySafe() {
        // 钉 Locale.US 在佛历默认 Locale 下仍给 2026 —— 安全边界来自 Locale→calendar 映射表，
        // 不是来自「钉了 Locale」。这条用例把这个前提固定下来：一旦 JDK 改表就会先红。
        Locale.setDefault(Locale.forLanguageTag("th-TH"))
        // 时区也必须显式钉住：`SimpleDateFormat` 吃默认时区，本用例要证明的是「Locale 钉了就安全」，
        // 不是「跑测试的机器是 +08」（Pacific/Midway 下 sunday 会落到 10-17）。
        val pinned = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone(shanghai)
        }.format(Date(sunday))
        assertEquals("2026-10-18", pinned)
    }

    @Test
    fun perCallZoneOverrideIsHonoured() {
        // 同一毫秒、两个时区：日期与时钟都变，说明 zone 参数没有被 systemDefault 吃掉
        assertEquals("2026-10-18 周日 17:09", PromptTimeCore.chineseStamp(sunday, shanghai))
        assertEquals("2026-10-18 周日 09:09", PromptTimeCore.chineseStamp(sunday, utc))
    }

    @Test
    fun negativeTimestampStaysFormatted() {
        // ts < 0（1969 年及更早）也在 Core 里正常格式化，不在这一层兜底
        val label = PromptTimeCore.messageLabel(-1L, sunday, shanghai)
        assertTrue(label, label.startsWith("[") && label.endsWith("]"))
    }
}
