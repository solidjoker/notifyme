// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [LocaleTextCore.format] 的口径回归：资源模板里的数字槽位代入后恒 ASCII，且降级不抛异常。
 *
 * 与 `String.format` 的三处差别在这里逐条钉死（改 Core 前先看这里）：
 *  - 固定 `Locale.ROOT`（ar-EG 下不出现 `٢٠٠`、de-DE 下不出现 `2,0`）；
 *  - `%N$d` 吃任何 `Number`（`String.format` 对 `Double` 抛 `IllegalFormatConversionException`）；
 *  - 悬空 `%` / 未知占位 / 缺参一律降级（宁可少一个数，也不让整条文案崩）。
 */
class FormatTemplateTest {

    private var savedLocale: Locale = Locale.getDefault()

    @Before
    fun rememberLocale() {
        savedLocale = Locale.getDefault()
    }

    @After
    fun restoreLocale() {
        Locale.setDefault(savedLocale)
    }

    private fun <T> withLocale(tag: String, block: () -> T): T {
        Locale.setDefault(Locale.forLanguageTag(tag))
        try {
            return block()
        } finally {
            Locale.setDefault(savedLocale)
        }
    }

    // ---- 数字形态不随默认 Locale 变 ----

    @Test
    fun integerSlotStaysAsciiUnderArabicLocale() {
        withLocale("ar-EG") {
            assertEquals("200 条", LocaleTextCore.format("%1\$d 条", listOf(200)))
            // 对照：同一模板交给 String.format 会得到阿拉伯-印度数字
            val old = String.format(Locale.getDefault(), "%1\$d 条", 200)
            assertTrue("旧写法应出本地数字，实际 " + old, old != "200 条")
        }
    }

    @Test
    fun decimalSlotKeepsDotUnderGermanLocale() {
        withLocale("de-DE") {
            assertEquals("重要度 2.0", LocaleTextCore.format("重要度 %1\$.1f", listOf(2.0)))
            val old = String.format(Locale.getDefault(), "重要度 %1\$.1f", 2.0)
            assertEquals("重要度 2,0", old)
        }
    }

    @Test
    fun decimalPrecisionComesFromTemplate() {
        assertEquals("2", LocaleTextCore.format("%1\$.0f", listOf(2.0)))
        assertEquals("6.25", LocaleTextCore.format("%1\$.2f", listOf(6.25)))
        // 模板没写精度时按 6 位（与 String.format 对 %f 的缺省一致）
        assertEquals("6.250000", LocaleTextCore.format("%1\$f", listOf(6.25)))
    }

    // ---- 槽位语义 ----

    @Test
    fun percentEscapeBecomesSinglePercent() {
        assertEquals("10%", LocaleTextCore.format("%1\$d%%", listOf(10)))
        assertEquals("100% 完成", LocaleTextCore.format("100%% 完成", emptyList()))
    }

    @Test
    fun slotsFollowTheirIndexNotArgumentOrder() {
        assertEquals("甲 · 3", LocaleTextCore.format("%2\$s · %1\$d", listOf(3, "甲")))
    }

    @Test
    fun sameSlotMayBeUsedTwice() {
        assertEquals("5/5", LocaleTextCore.format("%1\$d/%1\$d", listOf(5)))
    }

    @Test
    fun nonNumericSlotPassesThrough() {
        assertEquals("备注：明天交付", LocaleTextCore.format("备注：%1\$s", listOf("明天交付")))
    }

    @Test
    fun noSlotTemplateIsReturnedUntouched() {
        val raw = "仅显示最近的消息"
        assertTrue("无 % 时应原样返回（同一实例即可）", LocaleTextCore.format(raw, listOf(1)) === raw)
    }

    // ---- 降级：不抛异常 ----

    @Test
    fun danglingPercentIsKeptVerbatim() {
        assertEquals("100% 完成", LocaleTextCore.format("100% 完成", emptyList()))
        assertEquals("50%", LocaleTextCore.format("50%", emptyList()))
    }

    @Test
    fun unknownConversionIsKeptVerbatim() {
        assertEquals("%1\$x 次", LocaleTextCore.format("%1\$x 次", listOf(3)))
    }

    @Test
    fun missingArgumentBecomesEmpty() {
        assertEquals("1/", LocaleTextCore.format("%1\$d/%2\$d", listOf(1)))
        assertEquals(" 条", LocaleTextCore.format("%1\$d 条", emptyList()))
    }

    @Test
    fun nullArgumentBecomesEmpty() {
        assertEquals("· ", LocaleTextCore.format("· %1\$s", listOf(null)))
    }

    @Test
    fun numberGoesIntoIntegerSlotWithoutThrowing() {
        // String.format 对 %d + Double 抛 IllegalFormatConversionException，这里要能过
        assertEquals("2", LocaleTextCore.format("%1\$d", listOf(2.0)))
        assertEquals("2", LocaleTextCore.format("%1\$d", listOf(2.9)))
        assertEquals("2", LocaleTextCore.format("%1\$d", listOf(2L)))
    }

    // ---- 契约查询 ----

    @Test
    fun requiredArgsIsMaxSlotIndex() {
        assertEquals(0, LocaleTextCore.requiredArgs("没有槽位"))
        assertEquals(1, LocaleTextCore.requiredArgs("%1\$d 条"))
        assertEquals(3, LocaleTextCore.requiredArgs("%1\$d年%2\$d月%3\$d日"))
        assertEquals(2, LocaleTextCore.requiredArgs("%2\$s 在前"))
        assertEquals(0, LocaleTextCore.requiredArgs("100%% 完成"))
    }

    // ---- 资源契约：模板里的数字槽位必须位置化 ----

    @Test
    fun numericTemplatesArePositional() {
        // [LocaleTextCore.format] 只认 `%N$`；写成裸 `%d` 的话模板会被原样漏出（用户看到字面 %d）。
        val f = java.io.File("src/main/res/values/strings.xml")
        if (!f.exists()) return // 资源缺失时不阻断（例如被裁剪的构建）
        val text = f.readText()
        val slot = Regex("%\\d+\\$(?:\\.\\d+)?[dfs]")
        val offenders = ArrayList<String>()
        var checked = 0
        for (m in Regex("<string name=\"([^\"]+)\"[^>]*>(.*?)</string>", RegexOption.DOT_MATCHES_ALL).findAll(text)) {
            val name = m.groupValues[1]
            val body = m.groupValues[2]
            if (!Regex("%\\d+\\$(?:\\.\\d+)?[df]").containsMatchIn(body)) continue
            checked++
            // 把位置化槽位与 %% 摘掉，剩下的只要还有 % 就说明是裸占位
            val left = slot.replace(body, "").replace("%%", "")
            if (left.indexOf('%') >= 0) offenders.add(name + " → " + body)
        }
        assertTrue("应至少扫到 50 条数值模板，实际 " + checked, checked >= 50)
        assertTrue("存在非位置化的数值占位：" + offenders.joinToString("; "), offenders.isEmpty())
    }
}
