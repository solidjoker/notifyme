// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import android.content.Context
import android.util.Log

/**
 * 排程自愈的纯决策逻辑（无 Android 依赖，可直接单测）。
 *
 * 背景：MIUI/HyperOS 等国产 ROM 会在夜间静默丢弃 WorkManager 注册到
 * JobScheduler 的定时任务，而进程因前台服务存活时 WorkManager 不会重新
 * 注册——表现为「定时分析不再触发、点立即分析也没有任何反应」。
 * 对策：以「距上次成功分析的时间」作为排程健康信号，超阈值即认为排程失联，
 * 用 UPDATE 策略重新 enqueueUniquePeriodicWork 向系统重新注册 job。
 */
object ScheduleSelfHealCore {

    /** 失联判定的最低水位：30 分钟。周期再短也不会 30 分钟内误判。 */
    const val MIN_STALE_MS = 30 * 60_000L

    /** 两次重注册的冷却间隔：防止失联状态下每分钟反复 enqueue。 */
    const val COOLDOWN_MS = 15 * 60_000L

    /** 失联阈值 = max(3 x 周期, MIN_STALE_MS)。周期任务健康时永远达不到。 */
    fun thresholdFor(intervalMinutes: Long): Long =
        maxOf(intervalMinutes * 3 * 60_000L, MIN_STALE_MS)

    /**
     * 是否应执行一次重注册。
     * @param lastAnalysisTime 最近一次分析轮完成时间（0 = 从未跑过）
     * @param bornAt 本进程/判定基准的创建时间——新进程刚把周期任务重新
     *   enqueue 过，天然健康，给它一个完整阈值周期再判定
     * @param lastRepairAt 上次自愈重注册时间（冷却用）
     */
    fun shouldRepair(
        now: Long,
        lastAnalysisTime: Long,
        bornAt: Long,
        intervalMinutes: Long,
        lastRepairAt: Long
    ): Boolean {
        if (intervalMinutes <= 0L) return false
        val anchor = maxOf(lastAnalysisTime, bornAt)
        if (now - anchor < thresholdFor(intervalMinutes)) return false
        return now - lastRepairAt >= COOLDOWN_MS
    }
}

/**
 * 排程自愈的执行侧：读实时配置 + 进程内冷却状态，命中即重注册周期分析。
 * 调用方：KeepAliveService 看门狗（每分钟巡检）与 enqueueAnalysisNow
 * （用户显式点「立即分析」时抢先救活排程，避免等下一个巡检周期）。
 */
object ScheduleSelfHeal {

    private const val TAG = "ScheduleSelfHeal"

    /** 进程存活起点：进程刚启动时 WorkManager 由启动路径重新排过程，视为新 */
    private val bornAt: Long = System.currentTimeMillis()

    @Volatile
    private var lastRepairAt: Long = 0L

    /** 命中失联判定则重新注册周期分析；返回是否执行了自愈（供日志观察） */
    fun repairIfNeeded(context: Context, reason: String): Boolean {
        val config = AnalysisConfig(context)
        if (!config.enabled) return false
        val now = System.currentTimeMillis()
        if (!ScheduleSelfHealCore.shouldRepair(
                now, config.lastAnalysisTime, bornAt, config.intervalMinutes, lastRepairAt
            )
        ) return false
        lastRepairAt = now
        val staleMinutes = ScheduleSelfHealCore.thresholdFor(config.intervalMinutes) / 60_000L
        Log.w(TAG, reason + "：距上次分析成功已超过 " + staleMinutes + " 分钟，" +
            "判定排程被系统丢弃，按周期 " + config.intervalMinutes + " 分钟重新注册定时分析")
        AnalysisScheduler.schedule(context, config.intervalMinutes)
        return true
    }
}
