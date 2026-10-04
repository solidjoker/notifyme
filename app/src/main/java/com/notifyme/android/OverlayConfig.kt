// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import android.content.Context

/**
 * 悬浮通知配置（M9/W2）：SharedPreferences `overlay_config`。
 *
 * @property cardsEnabled 悬浮卡总开关（默认 false——新能力默认不改变行为，
 *   需用户在控制台显式开启并授权悬浮窗）
 * @property ballEnabled 常驻悬浮球（cardsEnabled 时才有意义）
 * @property autoDismissSeconds 卡片自动消失秒数（默认 5；≤0 = 不自动消失）
 */
data class OverlayConfigState(
    val cardsEnabled: Boolean,
    val ballEnabled: Boolean,
    val autoDismissSeconds: Int
) {
    companion object {
        const val DEFAULT_AUTO_DISMISS = 5
        val DEFAULT = OverlayConfigState(
            cardsEnabled = false,
            ballEnabled = true,
            autoDismissSeconds = DEFAULT_AUTO_DISMISS
        )
    }
}

object OverlayConfig {
    private const val PREFS_NAME = "overlay_config"
    private const val KEY_CARDS_ENABLED = "cards_enabled"
    private const val KEY_BALL_ENABLED = "ball_enabled"
    private const val KEY_AUTO_DISMISS = "auto_dismiss_seconds"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun get(context: Context): OverlayConfigState = OverlayConfigState(
        cardsEnabled = prefs(context).getBoolean(KEY_CARDS_ENABLED, false),
        ballEnabled = prefs(context).getBoolean(KEY_BALL_ENABLED, true),
        autoDismissSeconds = prefs(context)
            .getInt(KEY_AUTO_DISMISS, OverlayConfigState.DEFAULT_AUTO_DISMISS)
    )

    @Synchronized
    fun set(context: Context, state: OverlayConfigState) {
        prefs(context).edit()
            .putBoolean(KEY_CARDS_ENABLED, state.cardsEnabled)
            .putBoolean(KEY_BALL_ENABLED, state.ballEnabled)
            .putInt(KEY_AUTO_DISMISS, state.autoDismissSeconds)
            .apply()
    }
}
