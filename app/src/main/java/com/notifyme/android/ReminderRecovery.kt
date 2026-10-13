// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import android.content.Context
import android.util.Log

/**
 * 提醒自愈：设备重启后补挂丢失的 App 内闹钟（三级降级链 L3）。
 *
 * AlarmManager.setAndAllowWhileIdle 注册的闹钟**不跨重启**，但 reminders.jsonl 里的
 * alarm 记录仍是生效态：不补挂的话，主页/列表会一直显示「已加日历」而到点永不响，
 * 且 containsActive 去重会挡住重建——提醒被静默吞掉。
 *
 * 幂等性：PendingIntent 按 dedupKey.hashCode() 作 requestCode + FLAG_UPDATE_CURRENT，
 * 同一指纹重复设置只会覆盖旧闹钟，不会产生双份通知；因此开机与保活服务启动
 * 两处都调、重复调都安全。已过点的记录不补挂（避免开机就炸一串迟到的旧提醒），
 * 留给用户手动重试路径处理。
 */
object ReminderRecovery {

    private const val TAG = "ReminderRecovery"

    /** 该记录是否属于「重启后需要补挂」的丢失闹钟：L3 态且事件未过点（纯判定，可单测）。 */
    fun isRecoverable(record: ReminderRecord, nowMs: Long): Boolean =
        record.status == ReminderRecord.STATUS_ALARM && record.eventTime > nowMs

    /** 重排所有「闹钟态且未到点」的提醒；返回补挂条数。需在后台线程调用（读全量 jsonl）。 */
    @Synchronized
    fun rescheduleLostAlarms(context: Context): Int {
        val now = System.currentTimeMillis()
        val pending = ReminderStore.readAll(context).filter { isRecoverable(it, now) }
        if (pending.isEmpty()) return 0
        val lead = AnalysisConfig(context).reminderLeadMinutes
        var rearmed = 0
        for (record in pending) {
            // summary 口径与 retryReminder 一致：从标题还原待办文本
            val summary = record.title
                .removePrefix("任务：")
                .substringBeforeLast("（")
            val ok = AlarmHelper.scheduleReminder(
                context, record.dedupKey, record.title, summary,
                record.pkg, record.conversation, record.eventTime, lead
            )
            if (ok) rearmed += 1 else Log.w(TAG, "补挂闹钟失败: ${record.dedupKey}")
        }
        Log.i(TAG, "重启补挂 App 内闹钟：${pending.size} 条待恢复，成功 $rearmed 条")
        return rearmed
    }
}
