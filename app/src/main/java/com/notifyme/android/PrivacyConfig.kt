// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import android.content.Context

/**
 * 隐私脱敏策略（M3）。三档模式，口径刻意写死、不靠用户记：
 *
 *  - [OFF]：不脱敏，一切照旧（默认值，保持升级后行为不突变）。
 *  - [CLOUD_REDACT]：**只在出设备的一刻**脱敏——云端 S1/S2、消息/分析上报
 *    拿到的都是占位符文本；本地 JSONL、本地模型分析仍是原文。
 *  - [LOCAL_ONLY]：云端链路整体停用（云端 S1/S2、上报都不执行），
 *    只允许端侧本地模型分析；配置了云端模型也不调用，避免误发。
 *
 * @property enabledRules 启用的规则 id 子集；默认 [RedactorRules.ALL]
 * @property bypassConvIds 会话白名单：这些 [ConvKey.id] 的内容允许不脱敏发送
 *           （空 = 无白名单）。只在 CLOUD_REDACT 下有意义
 */
data class PrivacyModeState(
    val mode: String,
    val enabledRules: Set<String>,
    val bypassConvIds: Set<String>
) {
    val isOff: Boolean get() = mode == OFF
    val cloudRedact: Boolean get() = mode == CLOUD_REDACT
    val localOnly: Boolean get() = mode == LOCAL_ONLY

    /** 云端分析/上报是否被该模式完全禁止。 */
    val cloudBlocked: Boolean get() = mode == LOCAL_ONLY

    /** 该会话是否需要脱敏：模式 CLOUD_REDACT 且不在白名单内。 */
    fun shouldRedact(key: ConvKey): Boolean =
        mode == CLOUD_REDACT && key.id !in bypassConvIds

    companion object {
        const val OFF = "off"
        const val CLOUD_REDACT = "cloud_redact"
        const val LOCAL_ONLY = "local_only"

        val DEFAULT = PrivacyModeState(
            mode = OFF,
            enabledRules = RedactorRules.ALL,
            bypassConvIds = emptySet()
        )
    }
}

/**
 * 隐私模式持久化：SharedPreferences `privacy_config`。
 * 与其它配置 object 同口径——损坏/缺失回落默认，不抛异常。
 */
object PrivacyConfig {
    private const val PREFS_NAME = "privacy_config"
    private const val KEY_MODE = "mode"
    private const val KEY_RULES = "enabled_rules"
    private const val KEY_BYPASS = "bypass_conv_ids"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun get(context: Context): PrivacyModeState {
        val sp = prefs(context)
        val mode = sp.getString(KEY_MODE, PrivacyModeState.OFF)
            ?: PrivacyModeState.OFF
        if (mode !in setOf(PrivacyModeState.OFF, PrivacyModeState.CLOUD_REDACT,
                PrivacyModeState.LOCAL_ONLY)) {
            return PrivacyModeState.DEFAULT
        }
        val rules = sp.getStringSet(KEY_RULES, RedactorRules.ALL)
            ?: RedactorRules.ALL
        val bypass = sp.getStringSet(KEY_BYPASS, emptySet()).orEmpty()
        return PrivacyModeState(mode, rules, bypass)
    }

    @Synchronized
    fun set(context: Context, state: PrivacyModeState) {
        prefs(context).edit()
            .putString(KEY_MODE, state.mode)
            .putStringSet(KEY_RULES, state.enabledRules)
            .putStringSet(KEY_BYPASS, state.bypassConvIds)
            .apply()
    }
}
