// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [S1NeedActionCore] 的读取口径测试。
 *
 * 核心契约：**读不出来 ≠ 判定为 0**。need_action 概率读不出来时返 null（不是 0.0），
 * 调用方把该题置信度归 0，交给升级闸（confidence < 0.5）重判；旧实现一律兜底 0.0，
 * 等于把「读不出来」当成「明确判定无需行动」定案。
 */
class S1NeedActionCoreTest {

    private val delta = 1e-9

    private fun prob(value: Double?): Double = requireNotNull(value)

    @Test
    fun `数字概率能读出来`() {
        assertEquals(0.5, prob(S1NeedActionCore.readProb(0.5)), delta)
        assertEquals(1.0, prob(S1NeedActionCore.readProb(1)), delta)
        assertEquals(0.0, prob(S1NeedActionCore.readProb(0.0)), delta)
    }

    @Test
    fun `数字字符串能读出来`() {
        assertEquals(0.8, prob(S1NeedActionCore.readProb("0.8")), delta)
        assertEquals(0.8, prob(S1NeedActionCore.readProb("  0.8  ")), delta)
    }

    @Test
    fun `非数字字符串读不出来`() {
        assertNull(S1NeedActionCore.readProb("很高"))
        assertNull(S1NeedActionCore.readProb(""))
        assertNull(S1NeedActionCore.readProb("   "))
    }

    @Test
    fun `布尔读不出来`() {
        assertNull(S1NeedActionCore.readProb(true))
        assertNull(S1NeedActionCore.readProb(false))
    }

    @Test
    fun `null 读不出来`() {
        assertNull(S1NeedActionCore.readProb(null))
    }

    @Test
    fun `NaN 与无穷读不出来`() {
        assertNull(S1NeedActionCore.readProb(Double.NaN))
        assertNull(S1NeedActionCore.readProb(Double.POSITIVE_INFINITY))
        assertNull(S1NeedActionCore.readProb(Double.NEGATIVE_INFINITY))
    }

    @Test
    fun `对象读不出来`() {
        assertNull(S1NeedActionCore.readProb(JSONObject("""{"value":0.9}""")))
        assertNull(S1NeedActionCore.readProb(listOf(1, 2)))
    }

    @Test
    fun `答案里没有 need_action_prob 时读不出来`() {
        assertNull(S1NeedActionCore.readAnswerProb(JSONObject("""{"importance":8}""")))
        assertNull(S1NeedActionCore.readAnswerProb(null))
    }

    @Test
    fun `答案里的标量概率能读出来`() {
        assertEquals(0.9, prob(S1NeedActionCore.readAnswerProb(JSONObject("""{"need_action_prob":0.9}"""))), delta)
        assertEquals(0.9, prob(S1NeedActionCore.readAnswerProb(JSONObject("""{"need_action_prob":"0.9"}"""))), delta)
    }

    @Test
    fun `答案里的对象形态不猜`() {
        assertNull(S1NeedActionCore.readAnswerProb(JSONObject("""{"need_action_prob":{"value":0.9}}""")))
    }

    @Test
    fun `答案里只有 need_action 题时按题读`() {
        val answer = JSONObject("""{"need_action":{"noul":0.7}}""")
        assertEquals(0.7, prob(S1NeedActionCore.readAnswerProb(answer)), delta)
    }

    @Test
    fun `need_action 题对象按 noul 读`() {
        assertEquals(0.94, prob(S1NeedActionCore.readNeedActionAnswer(JSONObject("""{"noul":0.94}"""))), delta)
    }

    @Test
    fun `need_action 题对象按 prob 读`() {
        assertEquals(0.6, prob(S1NeedActionCore.readNeedActionAnswer(JSONObject("""{"prob":0.6}"""))), delta)
    }

    @Test
    fun `need_action 题对象按 value 读`() {
        assertEquals(0.3, prob(S1NeedActionCore.readNeedActionAnswer(JSONObject("""{"value":0.3}"""))), delta)
    }

    @Test
    fun `need_action 题对象没有概率键时读不出来`() {
        assertNull(S1NeedActionCore.readNeedActionAnswer(JSONObject("""{"confidence":0.9}""")))
        assertNull(S1NeedActionCore.readNeedActionAnswer(JSONObject("""{"noul":"很高"}""")))
    }

    @Test
    fun `need_action 直接给标量也认`() {
        assertEquals(0.45, prob(S1NeedActionCore.readNeedActionAnswer(0.45)), delta)
        assertEquals(0.45, prob(S1NeedActionCore.readNeedActionAnswer("0.45")), delta)
    }

    @Test
    fun `need_action 为 null 时读不出来`() {
        assertNull(S1NeedActionCore.readNeedActionAnswer(null))
    }

    @Test
    fun `概率夹取到合法区间`() {
        assertEquals(1.0, prob(S1NeedActionCore.clampProb(1.5)), delta)
        assertEquals(0.0, prob(S1NeedActionCore.clampProb(-0.2)), delta)
        assertEquals(0.5, prob(S1NeedActionCore.clampProb(0.5)), delta)
    }

    @Test
    fun `夹取不改变读不出来的语义`() {
        assertNull(S1NeedActionCore.clampProb(null))
    }

    @Test
    fun `不可信置信度是 0`() {
        assertEquals(0.0, S1NeedActionCore.UNREADABLE_CONFIDENCE, delta)
        assertTrue("读不出来必须低于升级闸阈值", S1NeedActionCore.UNREADABLE_CONFIDENCE < 0.5)
    }

    @Test
    fun `读不出来不等于判定为 0`() {
        val unreadable = S1NeedActionCore.readProb("很高")
        val decidedZero = S1NeedActionCore.readProb(0.0)
        assertNull("读不出来必须是 null", unreadable)
        assertEquals("判定为 0 是读得出来的 0.0", 0.0, prob(decidedZero), delta)
    }
}
