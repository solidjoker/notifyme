// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.qiyeweixin.weixin_android

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
 *  - 内置看门狗每 60 秒检查一次 WeChatNotificationListener 的连接状态，
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
            if (!WeChatNotificationListener.connected) {
                Log.w(TAG, "通知监听服务未连接，请求系统重绑")
                try {
                    NotificationListenerService.requestRebind(
                        ComponentName(
                            this@KeepAliveService,
                            WeChatNotificationListener::class.java
                        )
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "requestRebind 调用失败", e)
                }
            }
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
        // 前台服务类型 dataSync 在 Manifest 中声明（消息同步用途）
        startForeground(NOTIFICATION_ID, notification)

        handler.postDelayed(watchdog, WATCHDOG_INTERVAL_MS)
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
