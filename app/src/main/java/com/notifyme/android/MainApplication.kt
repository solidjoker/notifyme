// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.util.Log

/**
 * Application 入口。当前仅做启动日志，预留给后续扩展：
 *  - 崩溃收集 / 统一日志初始化；
 *  - 与 PC 端 web/api.py 对接的 HTTP 转发器初始化（见 README「未来扩展」）。
 */
class MainApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(ForegroundTracker)
        Log.i("MainApplication", "notifyme 启动，数据目录: ${filesDir.absolutePath}")
        // App 内提醒（三级降级链 Level 3）的通知渠道，提前建好
        ReminderReceiver.ensureChannel(this)
        // 冷启动自动提取一次已有聊天记录（开关/冷却/降级逻辑在 HistorySync 内）
        HistorySync.maybeRunOnStartup(this)
        // 数据保留策略：启动时也跑一次（每天最多一次）
        Retention.pruneIfDue(this)
        // M11.4 清理会话级定时分析的孤儿任务（会话被取消关注后排期失效）
        AnalysisScheduler.syncAll(this)

        // DEBUG 包自检通道：adb 广播触发无障碍直读的「开始提取」，
        // 配合服务的 DEBUG 本 App 监听，可在没有微信会话时验证采集引擎端到端链路
        if (BuildConfig.DEBUG) {
            registerReceiver(
                object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) {
                        Log.i("MainApplication", "收到 A11Y_DEBUG_START 广播，置位开始提取")
                        A11yExtractService.requestStart(packageName)
                    }
                },
                IntentFilter("$packageName.A11Y_DEBUG_START"),
                Context.RECEIVER_EXPORTED
            )
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // 端侧引擎常驻内存最贵；到达明显的内存压力档位就全部释放，
        // 下次分析时按需重新加载
        if (level >= TRIM_MEMORY_MODERATE) {
            LocalLlmEngines.releaseAll()
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        LocalLlmEngines.releaseAll()
    }

    companion object {
        /**
         * 进程内是否至少有一个 Activity 处于「已启动」状态。
         *
         * 提醒三级降级链的 L2 要 startActivity 唤起系统日历，而 Android 10+ 限制
         * 后台启动 Activity（会被静默拦截）；只有前台时才值得尝试这一级，
         * 否则应直接降级到 L3 的 App 内闹钟并如实记录级别。
         */
        @Volatile
        var isInForeground = false
            private set
    }

    /** 用 Activity 生命周期回调跟踪前台状态（不引入 lifecycle 依赖）。 */
    private object ForegroundTracker : ActivityLifecycleCallbacks {
        private var started = 0

        override fun onActivityStarted(activity: Activity) {
            started += 1
            isInForeground = true
        }

        override fun onActivityStopped(activity: Activity) {
            started = (started - 1).coerceAtLeast(0)
            if (started == 0) isInForeground = false
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }
}
