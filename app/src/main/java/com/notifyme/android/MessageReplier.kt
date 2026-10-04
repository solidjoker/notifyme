// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * 消息回复器 —— PC 端「UIA 发送」在 Android 上的零注入等价方案。
 *
 * 原理：微信消息通知自带「直接回复」action（Notification.Action），
 * 该 action 携带 RemoteInput。把回复文本填进 RemoteInput 的 results Bundle，
 * 再 send 这个 action 的 PendingIntent，即等价于用户在通知栏里手动输入并发送。
 * 全程走系统公开 API，不注入、不 hook、不碰协议。
 *
 * ⚠️ 使用边界（与母项目安全红线一致）：
 *  - 仅供「用户手动触发」的单条回复（例如在 UI 上长按某条消息选择回复）；
 *  - 禁止自动群发、禁止无人值守的批量回复；
 *  - 回复动作由微信自己的通知 PendingIntent 执行，本 app 无法伪造会话。
 */
object MessageReplier {

    private const val TAG = "MessageReplier"

    /**
     * 在一条通知里查找「可直接回复」的 action。
     * 判定标准：action.remoteInputs 非空（即系统要求通过 RemoteInput 传文本）。
     *
     * @return 找到的 action，没有则 null（该通知不支持直接回复）
     */
    fun findReplyAction(sbn: StatusBarNotification): Notification.Action? {
        val actions = sbn.notification?.actions ?: return null
        return actions.firstOrNull { action ->
            !action.remoteInputs.isNullOrEmpty()
        }
    }

    /**
     * 执行「直接回复」。
     *
     * @param context 上下文（PendingIntent.send 需要）
     * @param action  由 [findReplyAction] 找到的带 RemoteInput 的 action
     * @param replyText 回复文本
     * @return true 表示 PendingIntent 已成功发出（不代表微信侧一定送达）
     */
    fun reply(context: Context, action: Notification.Action, replyText: String): Boolean {
        val remoteInputs = action.remoteInputs ?: return false

        // 把回复文本按每个 RemoteInput 的 resultKey 填入 results Bundle
        val results = Bundle()
        for (input in remoteInputs) {
            results.putCharSequence(input.resultKey, replyText)
        }

        // addResultsToIntent 会把 results 打包进一个 fill-in Intent，
        // 系统在处理 PendingIntent 时把它递交给微信
        val fillIn = Intent().apply {
            RemoteInput.addResultsToIntent(remoteInputs, this, results)
        }

        return try {
            action.actionIntent.send(context, 0, fillIn)
            Log.i(TAG, "直接回复已发出: $replyText")
            true
        } catch (e: PendingIntent.CanceledException) {
            // 通知已过期（被滑掉/已回复）时 PendingIntent 会失效
            Log.w(TAG, "直接回复失败，PendingIntent 已失效", e)
            false
        }
    }
}
