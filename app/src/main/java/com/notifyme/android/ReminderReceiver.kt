// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * App 内提醒的到点接收器（三级降级链 Level 3 的出口）。
 *
 * 收到 AlarmManager 广播后发一条高优先级通知：
 *  - 渠道 reminder_alert（IMPORTANCE_HIGH，头显 + 声音走系统默认）；
 *  - 标题「微信任务提醒」，内容含事件标题与摘要；
 *  - 点击跳来源会话详情页（ConversationActivity），无会话名回退首页。
 *
 * POST_NOTIFICATIONS 运行时权限主界面已引导授予，发送前再防御性检查一次。
 */
class ReminderReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_REMIND = "com.notifyme.android.action.REMINDER_ALERT"
        const val CHANNEL_ID = "reminder_alert"

        const val EXTRA_DEDUP_KEY = "extra_dedup_key"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_SUMMARY = "extra_summary"
        const val EXTRA_PKG = "extra_pkg"
        const val EXTRA_CONVERSATION = "extra_conversation"
        const val EXTRA_EVENT_TIME = "extra_event_time"

        /** 建通知渠道；Application 启动与接收器发送前各调一次（幂等）。 */
        fun ensureChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "任务提醒",
                        NotificationManager.IMPORTANCE_HIGH
                    ).apply { description = "任务到点提醒（App 内闹钟兜底）" }
                )
            }
        }
    }

    private val timeFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_REMIND) return

        val dedupKey = intent.getStringExtra(EXTRA_DEDUP_KEY).orEmpty()
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val summary = intent.getStringExtra(EXTRA_SUMMARY).orEmpty()
        val pkg = intent.getStringExtra(EXTRA_PKG)
            ?.takeIf { it.isNotBlank() } ?: AppSourceRegistry.PKG_WECHAT
        val conversation = intent.getStringExtra(EXTRA_CONVERSATION).orEmpty()
        val eventTime = intent.getLongExtra(EXTRA_EVENT_TIME, 0L)
        Log.i("ReminderReceiver", "到点提醒触发: key=$dedupKey title=$title")

        // Android 13+ 通知运行时权限：未授予时这次提醒发不出去，但必须如实回写状态——
        // 原先直接 return，提醒记录仍显示「已设App内提醒」，用户以为提醒在，实则静默丢失。
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.w("ReminderReceiver", "无通知权限，提醒无法弹出: $dedupKey")
            ReminderStore.markStatus(
                context, dedupKey, ReminderRecord.STATUS_FAILED,
                "失败：无通知权限，提醒未弹出（请在系统设置里允许通知）"
            )
            return
        }

        ensureChannel(context)

        // 点击跳转：优先来源会话详情页，其次首页
        val tapIntent = if (conversation.isNotEmpty()) {
            ConversationActivity.createIntent(context, ConvKey(pkg, conversation))
        } else {
            Intent(context, MainActivity::class.java)
        }.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        val tapPendingIntent = PendingIntent.getActivity(
            context,
            dedupKey.hashCode(),
            tapIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val timeText = if (eventTime > 0) "（${timeFormat.format(Date(eventTime))}）" else ""
        val content = buildString {
            append(title).append(timeText)
            if (summary.isNotEmpty() && summary != title) append("\n").append(summary)
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("任务提醒")
            .setContentText("$title$timeText")
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            // 锁屏不显示提醒正文（PRIVATE 只显示"内容已隐藏"）
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .setContentIntent(tapPendingIntent)
            .build()

        androidx.core.app.NotificationManagerCompat.from(context)
            .notify(dedupKey.hashCode(), notification)
    }
}
