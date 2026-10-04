// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import android.content.Context

/**
 * 会话级自定义提示词存储（SharedPreferences `prompt_config`）。
 *
 * 语义：[ConvKey] → 提示词文本；不存在/空白 = 用全局默认分析口径。
 * 提示词作为背景上下文注入分析链路（对齐 JEV case 的 background 字段）：
 *  - S1（systemone）：挂进 state.background；服务端 4xx 拒收时降级重试不带；
 *  - S1（openai 槽位）：追加进 system prompt「背景信息」段；
 *  - S2：追加进 system prompt「背景信息」段。
 *
 * M2 起 key 是 ConvKey.id（`pkg|会话名`），否则不同 App 的同名会话会共用一份提示词。
 */
object PromptStore {

    private const val PREFS_NAME = "prompt_config"

    private fun prefs(context: Context) = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 该会话的自定义提示词；未设置返回空串（= 全局默认口径）。 */
    fun getPrompt(context: Context, key: ConvKey): String =
        prefs(context).getString(key.id, "").orEmpty()

    /** 该会话是否已设自定义提示词。 */
    fun hasPrompt(context: Context, key: ConvKey): Boolean =
        getPrompt(context, key).isNotBlank()

    /** 保存/覆盖该会话的自定义提示词；空白文本等价于清除。 */
    @Synchronized
    fun setPrompt(context: Context, key: ConvKey, prompt: String) {
        val trimmed = prompt.trim()
        if (trimmed.isEmpty()) {
            clearPrompt(context, key)
        } else {
            prefs(context).edit().putString(key.id, trimmed).apply()
        }
    }

    /** 清除该会话的自定义提示词（恢复全局默认）。 */
    @Synchronized
    fun clearPrompt(context: Context, key: ConvKey) {
        prefs(context).edit().remove(key.id).apply()
    }
}
