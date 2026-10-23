// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo

/**
 * 分析任务的「后台可见」（M21）：AnalysisWorker 一经实际开跑就提升为前台服务，
 * 系统下拉通知栏常驻一条进度通知，App 切后台/锁屏/被划掉近期任务后分析不中断，
 * 用户随时能看到当前阶段（读取上下文/端侧推理/云端/第 i/n 个会话/已用时间）。
 *
 * 生命周期完全交给 WorkManager 的 SystemForegroundService（Manifest 已声明
 * dataSync 类型，权限 FOREGROUND_SERVICE(_DATA_SYNC) 已在清单中）：
 * Worker 每次 AnalysisProgressBus 发布就 setForegroundAsync 刷新本通知，
 * Worker 返回后 WorkManager 自动撤掉通知，不需要也不应该由本对象 cancel。
 */
object AnalysisProgressNotifier {

    private const val TAG = "AnalysisNotifier"
    const val CHANNEL_ID = "analysis_progress"
    const val NOTIFICATION_ID = 4711

    /** 渠道只需建一次；LOW 重要性：静默、不响铃，只在状态栏占位。 */
    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.analysis_notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = context.getString(R.string.analysis_notif_channel_desc)
                setShowBadge(false)
                enableVibration(false)
                enableLights(false)
            }
            manager.createNotificationChannel(channel)
        }
    }

    /** 通知标题：单会话显示会话名；整轮多个会话显示「正在分析会话（i/n）」。 */
    fun title(context: Context, state: AnalysisRunState): String {
        val conv = state.conversation
        return when {
            state.caseTotal > 1 ->
                context.getString(R.string.analysis_notif_title_round, state.caseIndex, state.caseTotal)
            conv.isNotBlank() ->
                context.getString(R.string.analysis_notif_title_conv, conv.take(20))
            else -> context.getString(R.string.analysis_notif_title)
        }
    }

    /**
     * 点击通知：能从 convKeyId 解出会话就直接进该会话详情页（那里有分析 chips），
     * 解不出来（准备阶段 id 为空）回落控制台。
     */
    private fun contentIntent(context: Context, state: AnalysisRunState): PendingIntent {
        val intent = runCatching { ConvKey.parse(state.convKeyId) }.getOrNull()?.let { key ->
            ConversationActivity.createIntent(context, key)
        } ?: Intent(context, ConsoleActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        return PendingIntent.getActivity(
            context,
            // 不同会话给不同 PI，避免系统复用旧 intent 打开上一个会话
            state.convKeyId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun buildNotification(context: Context, state: AnalysisRunState) =
        NotificationCompat.Builder(context, CHANNEL_ID).apply {
            ensureChannel(context)
            setContentTitle(title(context, state))
            setContentText(AnalysisProgressUi.text(context, state))
            setSmallIcon(android.R.drawable.stat_notify_sync)
            setOngoing(true)
            setOnlyAlertOnce(true)
            setSilent(true)
            priority = NotificationCompat.PRIORITY_LOW
            setContentIntent(contentIntent(context, state))
            val percent = state.percent()
            if (percent == null) {
                setProgress(0, 0, true)
            } else {
                setProgress(100, percent, false)
            }
            // 展开态也能看到完整阶段文案（会话名/已用时间可能较长）
            runCatching {
                setStyle(NotificationCompat.BigTextStyle().bigText(AnalysisProgressUi.text(context, state)))
            }
        }.build()

    /** 供 CoroutineWorker.setForeground(Async) 使用；Android 14+ 必须带 dataSync 类型。 */
    fun foregroundInfo(context: Context, state: AnalysisRunState): ForegroundInfo {
        val notification = buildNotification(context, state)
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ForegroundInfo(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                ForegroundInfo(NOTIFICATION_ID, notification)
            }
        } catch (ex: Exception) {
            // 极端情况下（如厂商 ROM 临时拒绝 FGS 提升）不能让分析主流程崩掉：
            // 退回无类型 ForegroundInfo；真被拒绝时进度仍可由 UI/日志观察。
            Log.w(TAG, "构造带类型的 ForegroundInfo 失败，退回无类型: " + ex.message)
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }
}
