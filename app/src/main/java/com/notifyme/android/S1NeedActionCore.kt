// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import org.json.JSONObject

/**
 * S1 判定里「need_action 概率」只有一份读取口径。
 *
 * 为什么要单独抽：模型输出的形态天然不稳（字段缺失 / 类型不符 / 写成对象 /
 * 写成「很高」）。旧实现一律兜底 0.0，而 0.0 在 S1Result 里等于「明确判定
 * 无需行动」—— 于是**读不出来**被当成**高置信度的否定结论**：会话既不升级
 * 深分析、也不进待办，最后只剩一句「无需行动」。
 *
 * 三条口径：
 *  ① **读不出来 ≠ 0**：read* 系列读不到时返 null，调用方把该题置信度置
 *     [UNREADABLE_CONFIDENCE]（0.0），交给升级闸（confidence < 0.5）重判；
 *  ② **概率一律夹到 [0, 1]**：1.5 / -0.2 这类越界值先夹取再参与判定，
 *     不让「prob >= 0.5」被越界值带偏，也不把越界值原样上报；
 *  ③ **只有标量算概率**：布尔、对象、非数字字符串一律读不出来。
 */
internal object S1NeedActionCore {
    /** 读不出来时的置信度：不可信 → 交给升级闸（S2 / 云端）重判。 */
    const val UNREADABLE_CONFIDENCE = 0.0

    const val MIN_PROB = 0.0
    const val MAX_PROB = 1.0

    /** systemone 的 need_action 题对象里可能出现的概率键（按优先级）。 */
    private val NEED_ACTION_KEYS = arrayOf("noul", "prob", "value")

    /**
     * 读一个标量概率：Number / 数字字符串；其余形态读不出来返 null。
     */
    fun readProb(raw: Any?): Double? {
        return when (raw) {
            null -> null
            is Boolean -> null
            is Number -> raw.toDouble().takeIf { it.isFinite() }
            is String -> raw.trim().toDoubleOrNull()?.takeIf { it.isFinite() }
            else -> null
        }
    }

    /**
     * 读 openai 协议答案里的 need_action 概率（键 need_action_prob）。
     * 只有标量算读得出来：写成对象（如 {"value":0.9}）不猜，判读不出来。
     * 没写 need_action_prob 但写了 need_action 时按 need_action 题读。
     */
    fun readAnswerProb(answer: JSONObject?): Double? {
        if (answer == null) return null
        if (answer.has("need_action_prob")) {
            return readProb(answer.opt("need_action_prob"))
        }
        if (answer.has("need_action")) {
            return readNeedActionAnswer(answer.opt("need_action"))
        }
        return null
    }

    /**
     * 读 systemone 协议里 need_action 题的概率：
     * 对象取 noul / prob / value；直接给标量也认。
     */
    fun readNeedActionAnswer(value: Any?): Double? {
        if (value is JSONObject) {
            for (key in NEED_ACTION_KEYS) {
                if (value.has(key)) {
                    val p = readProb(value.opt(key))
                    if (p != null) return p
                }
            }
            return null
        }
        return readProb(value)
    }

    /** 概率夹取到 [0,1]；null 保持 null（读不出来不是 0）。 */
    fun clampProb(value: Double?): Double? = value?.coerceIn(MIN_PROB, MAX_PROB)
}
