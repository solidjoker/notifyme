// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * App 内闹钟提醒（三级降级链的 Level 3 兜底）。
 *
 * 系统没有可写日历、也没有日历 App 可唤起时，用 AlarmManager 定一个到点通知。
 *  - 用 setAndAllowWhileIdle 而非 setExactAndAllowWhileIdle：**不申请
 *    SCHEDULE_EXACT_ALARM 权限**，接受系统按电量策略漂移几分钟，对个人提醒足够；
 *  - PendingIntent requestCode 取 dedupKey.hashCode() 且 intent 不含变化 data，
 *    配合 FLAG_UPDATE_CURRENT：**同一 dedupKey 重复设置会覆盖旧闹钟，天然去重**；
 *  - 触发时间 = 事件时间 - 提前量；已过点则尽快补提醒（1 秒后）。
 */
object AlarmHelper {

    private const val TAG = "AlarmHelper"

    /** 到点触发；已过点时 1 秒后补提醒。 */
    private fun triggerTime(eventTime: Long, leadMinutes: Long): Long {
        val at = eventTime - leadMinutes * 60_000L
        return maxOf(at, System.currentTimeMillis() + 1_000L)
    }

    private fun buildPendingIntent(
        context: Context,
        dedupKey: String,
        title: String,
        summary: String,
        conversation: String,
        eventTime: Long
    ): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ReminderReceiver.ACTION_REMIND
            putExtra(ReminderReceiver.EXTRA_DEDUP_KEY, dedupKey)
            putExtra(ReminderReceiver.EXTRA_TITLE, title)
            putExtra(ReminderReceiver.EXTRA_SUMMARY, summary)
            putExtra(ReminderReceiver.EXTRA_CONVERSATION, conversation)
            putExtra(ReminderReceiver.EXTRA_EVENT_TIME, eventTime)
        }
        return PendingIntent.getBroadcast(
            context,
            dedupKey.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    /** 设置到点通知闹钟；设置成功返回 true。 */
    fun scheduleReminder(
        context: Context,
        dedupKey: String,
        title: String,
        summary: String,
        conversation: String,
        eventTime: Long,
        leadMinutes: Long
    ): Boolean {
        return try {
            val alarmManager = context.getSystemService(AlarmManager::class.java)
            val triggerAt = triggerTime(eventTime, leadMinutes)
            val pendingIntent = buildPendingIntent(
                context, dedupKey, title, summary, conversation, eventTime
            )
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            Log.i(TAG, "App 内提醒已设置: key=$dedupKey triggerAt=$triggerAt title=$title")
            true
        } catch (e: Exception) {
            Log.w(TAG, "设置 App 内提醒失败: $dedupKey", e)
            false
        }
    }

    /** 取消某指纹的闹钟（重试换级别、或未来支持手动取消时用）。 */
    fun cancelReminder(context: Context, dedupKey: String) {
        try {
            val alarmManager = context.getSystemService(AlarmManager::class.java)
            val pendingIntent = buildPendingIntent(context, dedupKey, "", "", "", 0L)
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
        } catch (e: Exception) {
            Log.w(TAG, "取消 App 内提醒失败: $dedupKey", e)
        }
    }
}
