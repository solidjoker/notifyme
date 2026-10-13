// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.util.Log

/**
 * 前台保活服务：解决通知监听器所在进程被系统懒回收导致漏消息的问题。
 *
 * 原理：
 *  - START_STICKY 前台服务让进程保有优先级，被杀后系统会尝试拉起；
 *  - 内置看门狗每 60 秒检查一次 NotifyMeListener 的连接状态，
 *    断绑时用官方 API requestRebind 请求系统重绑（API 24+）。
 *
 * 注意：本服务只是「提高存活概率 + 断绑自愈」，无法对抗用户手动「强行停止」
 * （force-stop 后任何组件都不会被拉起，属系统设计）。
 */
class KeepAliveService : Service() {

    companion object {
        private const val TAG = "KeepAliveService"
        private const val CHANNEL_ID = "keep_alive"
        private const val NOTIFICATION_ID = 1001
        private const val WATCHDOG_INTERVAL_MS = 60_000L
        /** 启动后补挂丢失闹钟的延迟：给系统/存储一点就绪时间 */
        private const val RECOVERY_DELAY_MS = 5_000L

        /** 心跳存储：服务自己写，界面自己读，跨进程重启不失真 */
        private const val PREFS_NAME = "keepalive_state"
        private const val KEY_HEARTBEAT = "keepalive_heartbeat"

        /** 心跳有效期：看门狗 60s 一轮，留 30s 余量 */
        private const val HEARTBEAT_TIMEOUT_MS = 90_000L

        /** 进程内运行标志，供主界面展示；进程重建后由 onCreate 重新置位 */
        @Volatile
        var running: Boolean = false
            private set

        /** 写心跳：onCreate/onStartCommand/每次看门狗巡检各写一次 */
        private fun beat(context: Context) {
            context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putLong(KEY_HEARTBEAT, System.currentTimeMillis())
                .apply()
        }

        /** 清心跳：服务真正销毁时抹掉，避免「死了还显示运行中」的 90s 假活窗口 */
        private fun clearBeat(context: Context) {
            context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_HEARTBEAT)
                .apply()
        }

        /** 界面侧判定：90 秒内有心跳视为运行中（不依赖进程内存标志） */
        fun isAlive(context: Context): Boolean {
            val ts = context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getLong(KEY_HEARTBEAT, 0L)
            return ts > 0L &&
                System.currentTimeMillis() - ts <= HEARTBEAT_TIMEOUT_MS
        }
    }

    private val handler = Handler(Looper.getMainLooper())

    /** 看门狗：周期性检查监听器连接状态，断绑则请求重绑；每轮顺手写心跳 */
    private val watchdog = object : Runnable {
        override fun run() {
            beat(this@KeepAliveService)
            if (!NotifyMeListener.connected) {
                Log.w(TAG, "通知监听服务未连接，请求系统重绑")
                try {
                    NotificationListenerService.requestRebind(
                        ComponentName(
                            this@KeepAliveService,
                            NotifyMeListener::class.java
                        )
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "requestRebind 调用失败", e)
                }
            }
            // 分析排程失联自愈：国产 ROM 会静默丢弃 WorkManager 的 job 注册，
            // 进程活着但定时/立即分析永不触发（W2 真机实测夜间停摆 10 小时）
            ScheduleSelfHeal.repairIfNeeded(this@KeepAliveService, "看门狗巡检")
            // 悬浮球失联自愈：OverlayService 不是 START_STICKY，被 ROM 回收后
            // 只有这里能周期性把它补回来（球开关关闭/无权限时内部自动跳过）
            OverlayManager.ensureBallVisible(this@KeepAliveService)
            handler.postDelayed(this, WATCHDOG_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        running = true
        beat(this)
        // 低重要性渠道：不响铃不震动不打扰，仅作前台服务常驻占位
        val channel = NotificationChannel(
            CHANNEL_ID, "后台保活", NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        beat(this)
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.keepalive_notification_title))
            .setContentText("点击打开管理页面")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
        // 前台服务类型 specialUse 在 Manifest 中声明（常驻看门狗用途，无 dataSync 的 6h 时长预算）
        startForeground(NOTIFICATION_ID, notification)
        // 开机/进程重建后补挂丢失的 App 内闹钟（L3 闹钟不跨重启；幂等，可重复调）。
        // 延迟 5s 让系统起来，读文件/设闹钟在后台线程做，不占主线程。
        handler.postDelayed({
            Thread { ReminderRecovery.rescheduleLostAlarms(applicationContext) }.start()
        }, RECOVERY_DELAY_MS)

        // 先摘掉旧链再挂新链：onStartCommand 可能被多次调用，
        // 只 postDelayed 不 removeCallbacks 会留下多条并行看门狗（重复巡检/重复 requestRebind）
        handler.removeCallbacks(watchdog)
        handler.postDelayed(watchdog, WATCHDOG_INTERVAL_MS)
        // 开机/重启后立即补挂悬浮球，不等第一跳 60s（监听器此刻可能还没重连）
        OverlayManager.ensureBallVisible(this)
        Log.i(TAG, "前台保活服务已启动")
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(watchdog)
        running = false
        clearBeat(this)
        Log.i(TAG, "前台保活服务已停止")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
