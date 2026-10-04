// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat

/**
 * 悬浮卡 / 降级通知的动作接收（M9/W2）：
 * 「标记已处理」→ 有 caseId 删除对应分析记录（不再出现在待办角标），
 * 并取消降级通知；悬浮卡本身的按钮在 OverlayService 内直接处理。
 */
class OverlayActionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_DISMISS_CARD = "com.notifyme.android.action.DISMISS_CARD"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
        const val EXTRA_CASE_ID = "case_id"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_DISMISS_CARD) return
        val caseId = intent.getStringExtra(EXTRA_CASE_ID).orEmpty()
        if (caseId.isNotEmpty()) {
            AnalysisStore.deleteCase(context, caseId)
        }
        val notifId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0)
        if (notifId != 0 && NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            context.getSystemService(NotificationManager::class.java).cancel(notifId)
        }
    }
}
