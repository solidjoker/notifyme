// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TextClipCore] 的截断口径测试。
 *
 * 为什么值得测：以为 \`take(n)\` 是按「字」切的，其实是按 UTF-16 code unit 切。emoji
 * （U+1F600 这类）占两个 unit，截在中间留下孤立高代理，转 UTF-8 就成了 \`?\`。
 * 这里把「按码点切」钉住，并用「同一个字符串在旧写法下坏、新写法下好」的对照
 * 复现那个替换字符。
 */
class TextClipCoreTest {

    private val emoji = "\uD83D\uDE00" // 😀 U+1F600，2 个 UTF-16 unit / 1 个码点
    private val extB = String(Character.toChars(0x20000)) // 𠀀，CJK 扩展 B，同样 2 unit
    private val mixed = "123456789012345" + emoji + "xy" // 19 unit / 18 码点

    @Test
    fun codePointsCountsAsciiPerChar() {
        assertEquals(3, TextClipCore.codePoints("abc"))
        assertEquals(0, TextClipCore.codePoints(""))
    }

    @Test
    fun codePointsCountsEmojiAsOne() {
        assertEquals(1, TextClipCore.codePoints(emoji))
        assertEquals(2, emoji.length)
        assertEquals(18, TextClipCore.codePoints(mixed))
        assertEquals(19, mixed.length)
    }

    @Test
    fun codePointsCountsExtBAsOne() {
        assertEquals(1, TextClipCore.codePoints(extB))
        assertEquals(2, extB.length)
    }

    @Test
    fun clipWithoutNeedReturnsSameInstance() {
        assertSame(mixed, TextClipCore.clip(mixed, 18))
        assertSame(mixed, TextClipCore.clip(mixed, 999))
    }

    @Test
    fun clipEmptyReturnsSameInstance() {
        val empty = ""
        assertSame(empty, TextClipCore.clip(empty, 5))
    }

    @Test
    fun clipNonPositiveLimitReturnsEmpty() {
        assertEquals("", TextClipCore.clip(mixed, 0))
        assertEquals("", TextClipCore.clip(mixed, -3))
        assertEquals("", TextClipCore.clipWithEllipsis(mixed, 0))
    }

    @Test
    fun clipCutsAtCodePointBoundary() {
        val clipped = TextClipCore.clip(mixed, 16)
        assertEquals(16, TextClipCore.codePoints(clipped))
        // 16 个码点 = 15 个 ASCII + 1 个 emoji = 17 个 unit
        assertEquals(17, clipped.length)
        assertTrue(clipped.endsWith(emoji))
    }

    @Test
    fun oldTakeLeavesLoneHighSurrogate() {
        val old = mixed.take(16)
        assertTrue(Character.isHighSurrogate(old.last()))
        assertFalse(Character.isLowSurrogate(old.last()))
        // UTF-8 编码时孤立代理被替换成 '?'
        val bytes = old.toByteArray(StandardCharsets.UTF_8)
        assertEquals('?'.code.toByte(), bytes[bytes.size - 1])
    }

    @Test
    fun clipNeverLeavesLoneSurrogate() {
        for (n in 1..18) {
            val clipped = TextClipCore.clip(mixed, n)
            assertFalse("n=$n", Character.isHighSurrogate(clipped.last()))
            // 编码后不能出现替换字符：圆形往返必须无损
            val rt = String(clipped.toByteArray(StandardCharsets.UTF_8), StandardCharsets.UTF_8)
            assertEquals("n=$n", clipped, rt)
            assertFalse("n=$n 出现替换字符", rt.contains('\uFFFD'))
        }
    }

    @Test
    fun asciiClipIsByteIdenticalToTake() {
        val ascii = "abcdefghijklmnopqrstuvwxyz"
        for (n in 0..ascii.length + 2) {
            val expected = if (n <= 0) "" else ascii.take(n)
            assertEquals("n=$n", expected, TextClipCore.clip(ascii, n))
        }
    }

    @Test
    fun clipWithEllipsisAppendsMarker() {
        val clipped = TextClipCore.clipWithEllipsis(mixed, 10)
        assertTrue(clipped.endsWith(TextClipCore.ELLIPSIS))
        assertEquals(11, TextClipCore.codePoints(clipped))
    }

    @Test
    fun ellipsisDoesNotEmptyBudget() {
        val long = "a".repeat(50)
        val clipped = TextClipCore.clipWithEllipsis(long, 20)
        assertEquals(21, TextClipCore.codePoints(clipped))
        assertEquals("a".repeat(20) + TextClipCore.ELLIPSIS, clipped)
    }

    @Test
    fun clipWithEllipsisOmitsMarkerWhenShort() {
        assertEquals("abc", TextClipCore.clipWithEllipsis("abc", 5))
        assertEquals("abc", TextClipCore.clipWithEllipsis("abc", 3))
    }

    @Test
    fun ellipsisConstantIsSingleCodePoint() {
        assertEquals(1, TextClipCore.codePoints(TextClipCore.ELLIPSIS))
        assertEquals(0x2026, TextClipCore.ELLIPSIS.codePointAt(0))
    }

    @Test
    fun extBClusterIsNotSplit() {
        val s = "a" + extB + "b"
        val clipped = TextClipCore.clip(s, 3)
        assertEquals(s, clipped)
        val cut = TextClipCore.clip(s, 2)
        assertEquals("a" + extB, cut)
        assertEquals(3, cut.length)
    }

    @Test
    fun emojiOnlyStringTruncatesCleanly() {
        val s = emoji.repeat(5)
        val clipped = TextClipCore.clip(s, 3)
        assertEquals(3, TextClipCore.codePoints(clipped))
        assertEquals(6, clipped.length)
        assertEquals(emoji.repeat(3), clipped)
    }

    @Test
    fun clipLeavesTextUnchangedWhenExactlyAtLimit() {
        assertEquals("abcde", TextClipCore.clip("abcde", 5))
        assertEquals(emoji + emoji, TextClipCore.clip(emoji + emoji, 2))
    }

    @Test
    fun customEllipsisIsHonoured() {
        assertEquals("abc>>", TextClipCore.clip("abcdef", 3, ">>"))
    }

    @Test
    fun zeroWidthJoinerFamilyMaySplitAcrossCodePoints() {
        // 已知边界：👨‍👩‍👧 是 3 个码点 + 2 个 ZWJ = 5 个码点，按码点切可能落在 ZWJ 上。
        val family = "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67"
        val clipped = TextClipCore.clip(family, 2)
        assertEquals(2, TextClipCore.codePoints(clipped))
        assertEquals(family.substring(0, family.offsetByCodePoints(0, 2)), clipped)
        // 不产生孤立代理，但会留下 ZWJ 结尾（前两码点是 👨 + ZWJ）——
        // 这是契约里写明的取舍（宁可少画一个 emoji，绝不产出非法字符），不是缺陷。
        assertFalse(Character.isHighSurrogate(clipped.last()))
        assertEquals('\u200D', clipped.last())
    }

    @Test
    fun surrogatePairAtStartIsKeptWhole() {
        val s = emoji + "abcdef"
        assertEquals(emoji, TextClipCore.clip(s, 1))
        assertEquals(2, TextClipCore.clip(s, 1).length)
    }
}
