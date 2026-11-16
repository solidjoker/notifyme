// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [S1ScaleCore] 的分制判定测试。
 *
 * 旧实现按「score ≤ 1 就乘 9」猜分制，把闲聊档（0-1 档）的期望抬到 6.3~9.0，
 * 直接越过 S2 升级闸（ESCALATE_IMPORTANCE = 6.0）——闲聊被当成危机级，
 * 既多花一次云端深分析，又在 UI 上显示「重要度 9.0」。
 * 这里逐条钉住两条口径：**只夹取、不缩放**；**分制从答案声明的档位读**。
 */
class S1ScaleCoreTest {

    private val delta = 1e-9

    /** 造一个 score 题答案：score + legend + probabilities（档位数 = levels）。 */
    private fun scoreAnswer(score: Double, levels: Int = 10): JSONObject {
        val legend = JSONObject()
        val probs = JSONObject()
        for (i in 0 until levels) {
            legend.put(i.toString(), "$i: level $i")
            probs.put(i.toString(), if (i == 0) 1.0 else 0.0)
        }
        return JSONObject()
            .put("type", "score")
            .put("score", score)
            .put("legend", legend)
            .put("probabilities", probs)
    }

    @Test
    fun `闲聊档的期望不再被抬到升级闸之上`() {
        // 旧实现：0.7 ≤ 1 → 乘 9 → 6.3（≥ 6.0 直接触发 S2 深分析）
        assertEquals(0.7, S1ScaleCore.importanceOf(scoreAnswer(0.7)), delta)
    }

    @Test
    fun `score 恰好等于 1 时保持 1 而不是 9`() {
        assertEquals(1.0, S1ScaleCore.importanceOf(scoreAnswer(1.0)), delta)
    }

    @Test
    fun `score 为 0 时保持 0`() {
        assertEquals(0.0, S1ScaleCore.importanceOf(scoreAnswer(0.0)), delta)
    }

    @Test
    fun `score 超上限时夹到 9`() {
        assertEquals(9.0, S1ScaleCore.importanceOf(scoreAnswer(12.0)), delta)
    }

    @Test
    fun `score 为负时夹到 0`() {
        assertEquals(0.0, S1ScaleCore.importanceOf(scoreAnswer(-2.0)), delta)
    }

    @Test
    fun `答案缺 score 但给了各档概率时按档位期望算`() {
        val probs = JSONObject()
        probs.put("0", 0.5)
        probs.put("1", 0.5)
        val answer = JSONObject().put("probabilities", probs)
        assertEquals(0.5, S1ScaleCore.importanceOf(answer), delta)
    }

    @Test
    fun `概率是数组时下标就是档位`() {
        val arr = JSONArray()
        arr.put(0.5)
        arr.put(0.5)
        assertEquals(0.5, S1ScaleCore.importanceOf(JSONObject().put("probabilities", arr)), delta)
        val arr2 = JSONArray()
        arr2.put(0.0)
        arr2.put(0.0)
        arr2.put(1.0)
        assertEquals(2.0, S1ScaleCore.importanceOf(JSONObject().put("probabilities", arr2)), delta)
    }

    @Test
    fun `答案声明 5 档时上限是 4`() {
        val answer = scoreAnswer(6.5, levels = 5)
        assertEquals(5, S1ScaleCore.levelsOf(answer))
        assertEquals(4.0, S1ScaleCore.importanceOf(answer), delta)
    }

    @Test
    fun `档位声明不可信时回落题面 10 档`() {
        val answer = JSONObject().put("legend", JSONObject().put("0", "only one"))
        assertEquals(S1ScaleCore.IMPORTANCE_LEVELS, S1ScaleCore.levelsOf(answer))
    }

    @Test
    fun `没有 legend 也没有 probabilities 时按题面 10 档`() {
        assertEquals(S1ScaleCore.IMPORTANCE_LEVELS, S1ScaleCore.levelsOf(JSONObject()))
        assertEquals(7.0, S1ScaleCore.onScale(7.0), delta)
    }

    @Test
    fun `答案为 null 时重要度是 0`() {
        assertEquals(0.0, S1ScaleCore.importanceOf(null), delta)
    }

    @Test
    fun `NaN 夹成 0`() {
        assertEquals(0.0, S1ScaleCore.onScale(Double.NaN), delta)
    }

    @Test
    fun `档位数上限是题面的 10 档`() {
        assertEquals(10, S1ScaleCore.levelsOf(scoreAnswer(1.0, levels = 12)))
        assertEquals(9.0, S1ScaleCore.onScale(9.5, levels = 10), delta)
    }

    @Test
    fun `expectationOf 忽略非数字档位键`() {
        val probs = JSONObject()
        probs.put("foo", 1.0)
        assertNull(S1ScaleCore.expectationOf(probs, null))
    }

    @Test
    fun `期望值同样只夹取不缩放`() {
        val probs = JSONObject()
        probs.put("9", 1.0)
        assertEquals(9.0, S1ScaleCore.importanceOf(JSONObject().put("probabilities", probs)), delta)
    }

    @Test
    fun `重要度仍然能越过升级闸`() {
        val importance = S1ScaleCore.importanceOf(scoreAnswer(7.0))
        assertEquals(7.0, importance, delta)
        val r = AnalysisCase.Companion.S1Result(
            needActionProb = 0.0,
            importance = importance,
            dueWindow = "none",
            topic = "notice",
            confidence = 1.0
        )
        assertTrue("重要度 7 仍应触发升级", r.shouldEscalate())
    }

    @Test
    fun `闲聊档不再触发升级`() {
        val r = AnalysisCase.Companion.S1Result(
            needActionProb = 0.1,
            importance = S1ScaleCore.importanceOf(scoreAnswer(0.7)),
            dueWindow = "none",
            topic = "smalltalk",
            confidence = 1.0
        )
        assertFalse("闲聊档不该被当成危机级", r.shouldEscalate())
    }

    @Test
    fun `分制常量与题面一致`() {
        assertEquals(10, S1ScaleCore.IMPORTANCE_LEVELS)
        assertEquals(9.0, S1ScaleCore.IMPORTANCE_MAX, delta)
    }
}
