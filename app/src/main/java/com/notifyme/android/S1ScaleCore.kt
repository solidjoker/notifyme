// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import org.json.JSONArray
import org.json.JSONObject

/**
 * S1「重要度」的分制只有一份口径。
 *
 * importance 是 score 题（题面固定 10 档：0=纯闲聊 … 9=危机级，见
 * [AnalysisCase.buildS1QuestionsJson]），官方 answer schema 给的 score 是
 * 「各档概率 × 档位序号」的期望（见 [laya.LayaDecoder] 的 score 分支），
 * 因此 **score 本身就是 0-9 的档位**，不是 0-1 的归一化值。
 *
 * 旧实现曾在 systemone 通道用「score ≤ 1 就乘 9」的猜法还原分制，
 * 恰恰把最该压低的那一段抬到了升级闸以上：闲聊（0-1 档）算出的期望
 * 常见落在 0.0~1.0 → 乘 9 → 0.7 变 6.3、1.0 变 9.0 → 直接触发
 * S2 深分析（云端成本 + 通知噪音），UI 还显示「重要度 9.0」。
 * 分制不该从数值猜，要从**答案自己声明的档位**读：score 题答案带
 * legend / probabilities（条目数 = 档位数），读不到才回落题面的 10 档。
 *
 * 只夹取、不缩放：0-9 分制里的低分档就该保持低分。
 */
internal object S1ScaleCore {

    /** importance 题的档位数（题面固定 10 档：0..9）。 */
    const val IMPORTANCE_LEVELS = 10

    /** importance 题的最高档（9）；与 [IMPORTANCE_LEVELS] 同源，改一处必须改另一处。 */
    const val IMPORTANCE_MAX = 9.0

    /** 少于此档位数的 legend / probabilities 视为不可信，回落题面默认档位数。 */
    private const val MIN_LEVELS = 2

    /**
     * 答案声明的档位数：legend（档位 → 题面文案）或 probabilities（档位 → 概率）的条目数。
     * 读不到 / 不可信时回落题面默认的 [IMPORTANCE_LEVELS]。
     */
    fun levelsOf(answer: JSONObject?): Int {
        val legend = answer?.optJSONObject("legend")
        if (legend != null && legend.length() >= MIN_LEVELS) {
            return legend.length().coerceAtMost(IMPORTANCE_LEVELS)
        }
        val probs = answer?.optJSONObject("probabilities")
        if (probs != null && probs.length() >= MIN_LEVELS) {
            return probs.length().coerceAtMost(IMPORTANCE_LEVELS)
        }
        val arr = answer?.optJSONArray("probabilities")
        if (arr != null && arr.length() >= MIN_LEVELS) {
            return arr.length().coerceAtMost(IMPORTANCE_LEVELS)
        }
        return IMPORTANCE_LEVELS
    }

    /**
     * 把原始分夹到 0..(档位数-1)：**只夹取，不缩放**。
     */
    fun onScale(raw: Double, levels: Int = IMPORTANCE_LEVELS): Double {
        if (raw.isNaN()) return 0.0
        val max = (levels - 1).coerceIn(0, IMPORTANCE_LEVELS - 1).toDouble()
        return raw.coerceIn(0.0, max)
    }

    /**
     * 从 score 题答案读重要度（0..9 档）。
     *
     * 优先用答案直接给的 score；没给 score 但给了各档概率时，按
     * 「Σ 档位序号 × 该档概率」算期望（与 [laya.LayaDecoder] 同口径）。
     * 两者都读不到时返 0.0（未知即最低，不误触发升级）。
     */
    fun importanceOf(answer: JSONObject?): Double {
        if (answer == null) return 0.0
        val levels = levelsOf(answer)
        if (answer.has("score")) return onScale(answer.optDouble("score", 0.0), levels)
        val expectation = expectationOf(
            answer.optJSONObject("probabilities"),
            answer.optJSONArray("probabilities")
        )
        return if (expectation == null) 0.0 else onScale(expectation, levels)
    }

    /**
     * 各档概率 → 档位期望（Σ 档位序号 × 该档概率）。
     * probabilities 可以是对象（键为档位序号）或数组（下标即档位）。
     */
    fun expectationOf(probs: JSONObject?, arr: JSONArray?): Double? {
        if (probs != null && probs.length() > 0) {
            var sum = 0.0
            var any = false
            val keys = probs.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val index = key.toIntOrNull() ?: continue
                sum += index * probs.optDouble(key, 0.0)
                any = true
            }
            return if (any) sum else null
        }
        if (arr != null && arr.length() > 0) {
            var sum = 0.0
            for (i in 0 until arr.length()) sum += i * arr.optDouble(i, 0.0)
            return sum
        }
        return null
    }
}
