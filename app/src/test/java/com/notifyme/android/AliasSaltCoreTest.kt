// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import java.security.SecureRandom
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AliasSaltCore] 强匿名内核单测：盐的生成 / 编解码 / 取值稳定性，以及
 * 「加盐别名」与「M3 无盐别名」的字节级关系。
 *
 * 固定盐向量来自独立实现（Python hashlib：sha256(salt + b"\x00" + name)[:6]），
 * 用来防止有人「顺手」改掉摘要口径而不自知——那会让所有历史别名换样，
 * 属于跨版本数据契约，必须靠硬向量兜住。
 */
class AliasSaltCoreTest {

    /** 固定盐 A：0x00..0x1f（32 字节）。 */
    private val saltA = ByteArray(32) { it.toByte() }

    /** 固定盐 B：0x01..0x20（32 字节），与 A 只差一个字节的位移。 */
    private val saltB = ByteArray(32) { (it + 1).toByte() }

    // ---------------- 生成与编解码 ----------------

    @Test
    fun `newSalt 长度固定且两次不同`() {
        val a = AliasSaltCore.newSalt()
        val b = AliasSaltCore.newSalt()
        assertEquals(AliasSaltCore.SALT_BYTES, a.size)
        assertEquals(32, a.size)
        assertFalse(a.contentEquals(b))
    }

    @Test
    fun `newSalt 用注入的 SecureRandom 可复现`() {
        val a = AliasSaltCore.newSalt(SecureRandom.getInstance("SHA1PRNG").apply { setSeed(42L) })
        val b = AliasSaltCore.newSalt(SecureRandom.getInstance("SHA1PRNG").apply { setSeed(42L) })
        assertArrayEquals(a, b)
    }

    @Test
    fun `encode 是 64 字符小写十六进制`() {
        val hex = AliasSaltCore.encode(saltA)
        assertEquals(64, hex.length)
        assertTrue(hex.all { it in '0'..'9' || it in 'a'..'f' })
        assertEquals(
            "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f",
            hex
        )
    }

    @Test
    fun `decode 与 encode 互为逆运算`() {
        assertArrayEquals(saltA, AliasSaltCore.decode(AliasSaltCore.encode(saltA))!!)
    }

    @Test
    fun `decode 大小写十六进制都接受`() {
        val upper = AliasSaltCore.encode(saltA).uppercase()
        assertArrayEquals(saltA, AliasSaltCore.decode(upper)!!)
    }

    @Test
    fun `decode 长度不对或含非 hex 返回 null 不抛异常`() {
        assertNull(AliasSaltCore.decode(null))
        assertNull(AliasSaltCore.decode(""))
        assertNull(AliasSaltCore.decode("abcd"))
        assertNull(AliasSaltCore.decode("0".repeat(63)))
        assertNull(AliasSaltCore.decode("0".repeat(65)))
        assertNull(AliasSaltCore.decode("z".repeat(64)))
        assertNull(AliasSaltCore.decode("x".repeat(64)))
    }

    @Test
    fun `ensure 能解码时复用且不需要写回`() {
        val stored = AliasSaltCore.encode(saltA)
        val (salt, writeBack) = AliasSaltCore.ensure(stored)
        assertArrayEquals(saltA, salt)
        assertNull(writeBack)
    }

    @Test
    fun `ensure 缺失或损坏时生成新盐并要求写回`() {
        val (saltNull, writeNull) = AliasSaltCore.ensure(null)
        assertEquals(32, saltNull.size)
        assertEquals(AliasSaltCore.encode(saltNull), writeNull)

        val (saltBad, writeBad) = AliasSaltCore.ensure("not-a-salt")
        assertEquals(32, saltBad.size)
        assertEquals(AliasSaltCore.encode(saltBad), writeBad)
        // 写回值能解回同一把盐：下次启动别名不变
        assertArrayEquals(saltBad, AliasSaltCore.decode(writeBad)!!)
    }

    // ---------------- 摘要口径（硬向量） ----------------

    @Test
    fun `无盐别名与 M3 初版逐字节一致`() {
        assertEquals("[人名1d841b]", AliasSaltCore.personAlias("张三", AliasSaltCore.LEGACY_SALT))
        assertEquals("[群4e5347]", AliasSaltCore.groupAlias("项目群", AliasSaltCore.LEGACY_SALT))
    }

    @Test
    fun `加盐别名向量固定`() {
        assertEquals("[人名baf6b4]", AliasSaltCore.personAlias("张三", saltA))
        assertEquals("[群e6be9b]", AliasSaltCore.groupAlias("项目群", saltA))
        assertEquals("[人名3021d8]", AliasSaltCore.personAlias("李四", saltA))
        assertEquals("[人名6ab42d]", AliasSaltCore.personAlias("张三", saltB))
    }

    @Test
    fun `换盐后同名得到不同别名`() {
        assertNotEquals(
            AliasSaltCore.personAlias("张三", saltA),
            AliasSaltCore.personAlias("张三", saltB)
        )
    }

    @Test
    fun `同一把盐内幂等异名不同`() {
        assertEquals(
            AliasSaltCore.personAlias("张三", saltA),
            AliasSaltCore.personAlias("张三", saltA)
        )
        assertNotEquals(
            AliasSaltCore.personAlias("张三", saltA),
            AliasSaltCore.personAlias("李 四", saltA)
        )
    }

    @Test
    fun `别名长度固定且不含原文`() {
        val alias = AliasSaltCore.personAlias("张三", saltA)
        assertEquals(AliasSaltCore.PERSON_PREFIX.length + AliasSaltCore.ALIAS_HEX_LEN + 1, alias.length)
        assertFalse(alias.contains("张三"))
        assertTrue(alias.endsWith("]"))
    }

    @Test
    fun `盐与名字之间用 0x00 分隔消除拼接歧义`() {
        // 若没有分隔符，salt="ab"+name="c" 与 salt="a"+name="bc" 会得到同一个摘要
        val x = AliasSaltCore.digest("c", "ab".toByteArray(Charsets.UTF_8))
        val y = AliasSaltCore.digest("bc", "a".toByteArray(Charsets.UTF_8))
        assertNotEquals(x, y)
        assertEquals("6c032e", x)
        assertEquals("40bb54", y)
    }

    @Test
    fun `人名与群名前缀不同同一名字两类别名不同`() {
        val person = AliasSaltCore.personAlias("项目群", saltA)
        val group = AliasSaltCore.groupAlias("项目群", saltA)
        assertTrue(person.startsWith("[人名"))
        assertTrue(group.startsWith("[群"))
        // 只是前缀不同，摘要相同——同一字符串在两处被引用时应能对上
        assertEquals(person.removePrefix("[人名"), group.removePrefix("[群"))
    }

    @Test
    fun `中文与表情都按 UTF-8 编码稳定`() {
        assertEquals(
            AliasSaltCore.personAlias("张三🙂", saltA),
            AliasSaltCore.personAlias("张三🙂", saltA)
        )
        assertNotEquals(
            AliasSaltCore.personAlias("张三🙂", saltA),
            AliasSaltCore.personAlias("张三", saltA)
        )
    }
}
