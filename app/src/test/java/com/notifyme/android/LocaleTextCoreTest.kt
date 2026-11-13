// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * [LocaleTextCore] 的口径回归：数字形态不随系统语言变，且旧写法在非拉丁数字语言下确实会变。
 *
 * 钉死的行为（改 Core 前先看这里）：
 *  - `minutes` / `seconds` / `hourMinute` 在 ar-EG（阿拉伯-印度数字）、de-DE（逗号小数）下与 zh-CN 逐字节相同；
 *  - `fixed` 恒用 `.`（`0.80`，不是 `0,80` / 阿拉伯数字），`percent` 只出数字与 `%`；
 *  - `bytes` 是十进制单位（模型下载同口径）、`binaryBytes` 是 1024 进制（诊断页同口径），两者**不同**；
 *  - `zone` 缺省取系统时区，显式传入时才换区（`Locale.ROOT` 只管数字形态）。
 */
class LocaleTextCoreTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    /** 2026-11-12 18:10:00 +08:00，周四。 */
    private val ms = 1794478200000L

    private var savedLocale: Locale = Locale.getDefault()

    @Before
    fun rememberLocale() {
        savedLocale = Locale.getDefault()
    }

    @After
    fun restoreLocale() {
        Locale.setDefault(savedLocale)
    }

    private fun withLocale(tag: String, body: () -> Unit) {
        Locale.setDefault(Locale.forLanguageTag(tag))
        try {
            body()
        } finally {
            Locale.setDefault(savedLocale)
        }
    }

    // ---- 时间戳文本 ----

    @Test
    fun minutesUsesRootLocaleInEveryLanguage() {
        listOf("zh-CN", "th-TH", "de-DE", "ar-EG", "fa-IR", "bn-BD", "ne-NP", "ja-JP-u-ca-japanese")
            .forEach { tag ->
                withLocale(tag) {
                    assertEquals(tag, "11-12 18:10", LocaleTextCore.minutes(ms, zone))
                }
            }
    }

    @Test
    fun secondsUsesRootLocaleInEveryLanguage() {
        withLocale("ar-EG") {
            assertEquals("11-12 18:10:00", LocaleTextCore.seconds(ms, zone))
        }
    }

    @Test
    fun hourMinuteUsesRootLocaleInEveryLanguage() {
        withLocale("fa-IR") {
            assertEquals("18:10", LocaleTextCore.hourMinute(ms, zone))
        }
    }

    @Test
    fun oldSimpleDateFormatDoesDifferUnderArabicLocale() {
        // 对照实验：证明这不是假想问题 —— 旧写法在 ar-EG 下给出阿拉伯-印度数字。
        withLocale("ar-EG") {
            val old = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ms))
            assertNotEquals(old, "11-12 18:10")
            assertTrue("旧写法应是本地数字: " + old, old.none { it.code in 48..57 })
            assertEquals("11-12 18:10", LocaleTextCore.minutes(ms, zone))
        }
    }

    @Test
    fun oldSimpleDateFormatIsSafeWhenLocaleIsPinned() {
        // AnalysisCase 里钉了 Locale.US 的那几处：钉了就安全，别去动。
        withLocale("ar-EG") {
            // 时区必须显式钉住：这里要证明的是「Locale 钉了就安全」，不是「默认时区是 +08」
            // （CI 的 runner 是 UTC，不钉时区会得到 10:10）。
            val pinned = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply {
                timeZone = TimeZone.getTimeZone(zone)
            }.format(Date(ms))
            assertEquals("2026-11-12 18:10", pinned)
        }
    }

    @Test
    fun zoneDefaultsToSystemButExplicitZoneWins() {
        val savedZone = ZoneId.systemDefault()
        try {
            // 固定成 UTC 后不传 zone 应拿到 UTC 文本：证明缺省取的是「系统的」，不是写死的。
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            assertEquals("11-12 10:10", LocaleTextCore.minutes(ms))
            assertEquals("11-12 18:10", LocaleTextCore.minutes(ms, zone))
        } finally {
            TimeZone.setDefault(TimeZone.getTimeZone(savedZone))
        }
    }

    // ---- 小数文本 ----

    @Test
    fun fixedKeepsDotInCommaDecimalLocales() {
        withLocale("de-DE") {
            assertEquals("0.80", LocaleTextCore.fixed(0.8, 2))
            assertEquals("7.0", LocaleTextCore.fixed(7.0, 1))
            assertEquals("0.809", LocaleTextCore.fixed(0.8094, 3))
        }
    }

    @Test
    fun oldStringFormatDoesUseCommaInGerman() {
        // 对照实验：默认 Locale 的 "%.2f" 在 de-DE 下是逗号小数。
        withLocale("de-DE") {
            assertEquals("0,80", "%.2f".format(0.8))
            assertEquals("0.80", LocaleTextCore.fixed(0.8, 2))
        }
    }

    @Test
    fun fixedZeroOrNegativeDigitsRoundsToInteger() {
        assertEquals("1", LocaleTextCore.fixed(0.8, 0))
        assertEquals("1", LocaleTextCore.fixed(1.4, -3))
        assertEquals("2", LocaleTextCore.fixed(1.5, 0))
    }

    @Test
    fun percentOnlyEmitsDigitsAndSign() {
        assertEquals("75.0%", LocaleTextCore.percent(0.75))
        assertEquals("0.0%", LocaleTextCore.percent(0.0))
        assertEquals("100.0%", LocaleTextCore.percent(1.0))
        assertEquals("82%", LocaleTextCore.percent(0.82, 0))
        withLocale("ar-EG") {
            assertEquals("75.0%", LocaleTextCore.percent(0.75))
        }
    }

    // ---- 字节数 ----

    @Test
    fun bytesUsesDecimalUnits() {
        assertEquals("0 KB", LocaleTextCore.bytes(0L))
        assertEquals("1 KB", LocaleTextCore.bytes(999L))
        assertEquals("1 KB", LocaleTextCore.bytes(1_000L))
        assertEquals("1.5 MB", LocaleTextCore.bytes(1_500_000L))
        assertEquals("1.20 GB", LocaleTextCore.bytes(1_200_000_000L))
    }

    @Test
    fun binaryBytesUses1024Units() {
        assertEquals("0 B", LocaleTextCore.binaryBytes(0L))
        assertEquals("1023 B", LocaleTextCore.binaryBytes(1023L))
        assertEquals("1.0 KB", LocaleTextCore.binaryBytes(1024L))
        assertEquals("1.00 MB", LocaleTextCore.binaryBytes(1024L * 1024L))
    }

    @Test
    fun bytesAndBinaryBytesAreDeliberatelyDifferent() {
        // 同一字节数在模型下载（十进制）与诊断页（1024 进制）给不同文案，别把两个函数合并。
        val oneMiB = 1024L * 1024L
        assertEquals("1.0 MB", LocaleTextCore.bytes(oneMiB))
        assertEquals("1.00 MB", LocaleTextCore.binaryBytes(oneMiB))
    }

    @Test
    fun bytesAreStableInLocalizedLocales() {
        withLocale("ar-EG") {
            assertEquals("1.5 MB", LocaleTextCore.bytes(1_500_000L))
            assertEquals("1.00 MB", LocaleTextCore.binaryBytes(1024L * 1024L))
        }
    }

    // ---- 与本轮接线有关的一致性 ----

    @Test
    fun minutesMatchesPatternConstant() {
        val fmt = DateTimeFormatter.ofPattern(LocaleTextCore.MINUTE_PATTERN, Locale.ROOT).withZone(zone)
        assertEquals("11-12 18:10", LocaleTextCore.minutes(ms, zone))
        assertEquals(fmt.format(Instant.ofEpochMilli(ms)), LocaleTextCore.minutes(ms, zone))
    }

    @Test
    fun digestFieldsKeepAsciiStructure() {
        // 顾问摘要的字段文本由 Core 拼出：验证「拼出来的数字里没有逗号小数点」。
        withLocale("de-DE") {
            val line = "need_action=" + LocaleTextCore.fixed(0.8, 2) +
                " importance=" + LocaleTextCore.fixed(7.0, 1)
            assertEquals("need_action=0.80 importance=7.0", line)
            assertTrue(line.indexOf(DECIMAL_COMMA) < 0)
        }
    }

    private companion object {
        /** 德语的逗号小数点（写成常量避免源码里出现裸逗号字面量）。 */
        private const val DECIMAL_COMMA = "0,80"
    }
}