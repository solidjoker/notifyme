// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * 悬浮通知入口（M9/W2）：判断悬浮窗权限——
 * 有权限 → 拉起 [OverlayService] 显示悬浮卡；
 * 无权限 → 降级为高优先级 heads-up 系统通知（带「标记已处理」与点击进会话），
 * 功能不中断，只是不能覆盖在其它 App 之上。
 */
object OverlayManager {

    /** 降级 heads-up 渠道（重要消息卡片） */
    const val CHANNEL_FALLBACK = "overlay_card_fallback"

    /** 悬浮层前台服务常驻渠道（静默） */
    const val CHANNEL_SERVICE = "overlay_service"

    private const val TAG = "OverlayManager"

    /** 悬浮卡数据（全部走 extras，无 Parcelable 依赖） */
    data class Card(
        val pkg: String,
        val conversation: String,
        val sender: String,
        val text: String,
        val appLabel: String = "",
        /** 分析产出的卡带 caseId，标记已处理时删除对应 case */
        val caseId: String = ""
    )

    fun canDrawOverlays(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

    fun show(context: Context, card: Card) {
        if (!canDrawOverlays(context)) {
            showFallbackNotification(context, card)
            return
        }
        val intent = Intent(context, OverlayService::class.java).apply {
            action = OverlayService.ACTION_SHOW_CARD
            putExtra(OverlayService.EXTRA_PKG, card.pkg)
            putExtra(OverlayService.EXTRA_CONVERSATION, card.conversation)
            putExtra(OverlayService.EXTRA_SENDER, card.sender)
            putExtra(OverlayService.EXTRA_TEXT, card.text)
            putExtra(OverlayService.EXTRA_APP_LABEL, card.appLabel)
            putExtra(OverlayService.EXTRA_CASE_ID, card.caseId)
        }
        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (e: RuntimeException) {
            // Android 12+ 在后台启动前台服务会抛 ForegroundServiceStartNotAllowedException；
            // 调用点（通知回调 / Worker）都没有接住它，异常会一路冒到 onNotificationPosted
            // 让监听进程崩溃并丢消息。悬浮卡只是可选展示通道，失败就降级成通知兜底。
            Log.w(TAG, "悬浮卡前台服务启动失败，降级为 heads-up 通知", e)
            showFallbackNotification(context, card)
        }
    }

    /** 悬浮窗权限设置页 intent（控制台引导用）。 */
    fun overlaySettingsIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        )

    // ---------------- 降级 heads-up ----------------

    private fun showFallbackNotification(context: Context, card: Card) {
        ensureChannels(context)
        val notifId = notificationId(card)
        val key = ConvKey(card.pkg, card.conversation)

        val tapIntent = ConversationActivity.createIntent(context, key)
        val tapPending = PendingIntent.getActivity(
            context, notifId, tapIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // 「标记已处理」：广播 OverlayActionReceiver 删 case + 取消本通知
        val dismissIntent = Intent(context, OverlayActionReceiver::class.java).apply {
            action = OverlayActionReceiver.ACTION_DISMISS_CARD
            putExtra(OverlayActionReceiver.EXTRA_NOTIFICATION_ID, notifId)
            putExtra(OverlayActionReceiver.EXTRA_CASE_ID, card.caseId)
        }
        val dismissPending = PendingIntent.getBroadcast(
            context, notifId + 1, dismissIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val appTag = card.appLabel.ifEmpty { AppSourceRegistry.displayLabel(card.pkg) }
        val title = "$appTag · ${card.conversation}"
        val content = if (card.sender.isNotEmpty()) "${card.sender}: ${card.text}" else card.text

        val notification = NotificationCompat.Builder(context, CHANNEL_FALLBACK)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            // 锁屏上不直接显示消息正文（PRIVATE 只显示"内容已隐藏"），
            // 避免锁屏/通知栏泄露会话内容。
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .setContentIntent(tapPending)
            .addAction(0, "标记已处理", dismissPending)
            .build()

        androidx.core.app.NotificationManagerCompat.from(context)
            .notify(notifId, notification)
    }


    /** 通知 id：有 caseId 用它，保证同 case 不堆多条；否则按内容哈希。 */
    private fun notificationId(card: Card): Int =
        if (card.caseId.isNotEmpty()) card.caseId.hashCode()
        else "${card.pkg}|${card.conversation}|${card.sender}|${card.text}".hashCode()

    // ---------------- 渠道 ----------------

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_FALLBACK) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_FALLBACK,
                    "重要消息悬浮卡",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply { description = "无悬浮窗权限时降级为系统横幅通知" }
            )
        }
        if (manager.getNotificationChannel(CHANNEL_SERVICE) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_SERVICE,
                    "悬浮通知服务",
                    NotificationManager.IMPORTANCE_LOW
                ).apply { description = "常驻悬浮球与悬浮卡服务" }
            )
        }
    }
}
