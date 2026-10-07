// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * 后端分析调度：对 WorkManager 的薄封装，模式与 SyncScheduler 一致。
 * 唯一名 wechat_analysis，UPDATE 策略保证改周期后旧任务被替换。
 */
object AnalysisScheduler {

    private const val UNIQUE_WORK_NAME = "wechat_analysis"

    private fun networkConstraint(): Constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    /** 按配置周期启用/更新定时分析。 */
    fun schedule(context: Context, intervalMinutes: Long) {
        val request = PeriodicWorkRequestBuilder<AnalysisWorker>(intervalMinutes, TimeUnit.MINUTES)
            .setConstraints(networkConstraint())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    /** 停用定时分析（如关闭分析开关时）。 */
    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
    }

    // ---------------- M11.4 会话级定时分析 ----------------

    private const val UNIQUE_CONV_PREFIX = "conv_analysis_"

    /**
     * 为单个已关注会话设置独立分析周期（每会话一个 unique periodic 任务，UPDATE 策略）。
     * 任务带 forceKey 输入（跳过关注名单过滤，排期本身就代表显式指定）+ quiet 标记
     * （窗口未变化时静默跳过，不覆写全局分析状态文案）。
     */
    fun scheduleForConv(context: Context, key: ConvKey, intervalMinutes: Long) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_CONV_PREFIX + key.id,
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<AnalysisWorker>(intervalMinutes, TimeUnit.MINUTES)
                .setInputData(
                    androidx.work.Data.Builder()
                        .putString(AnalysisWorker.KEY_FORCE_PKG, key.pkg)
                        .putString(AnalysisWorker.KEY_FORCE_CONVERSATION, key.conversation)
                        .putBoolean(AnalysisWorker.KEY_QUIET, true)
                        .build()
                )
                .build()
        )
        // 兜底：设置周期后立刻先跑一轮，用户马上能看到分析结果
        enqueueAnalysisNow(context, key)
    }

    /** 取消单个会话的定时分析。 */
    fun cancelForConv(context: Context, key: ConvKey) {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_CONV_PREFIX + key.id)
    }

    /**
     * 同步全部会话级排期：App 启动/设置变化后调用。
     * 会话被取消关注（不在名单）或总开关关闭时，对应 unique 任务被取消（清孤儿）。
     */
    fun syncAll(context: Context) {
        val wm = WorkManager.getInstance(context)
        ConvAnalysisScheduleStore.all(context).forEach { (id, minutes) ->
            val key = ConvKey.parse(id)
            if (minutes <= 0 || !WatchlistStore.isWatched(context, key)) {
                wm.cancelUniqueWork(UNIQUE_CONV_PREFIX + id)
            }
        }
    }

    /**
     * 立即分析一次（不等周期到点），结果同样写回 AnalysisConfig。
     * @param forceKey 非空时只分析该会话并跳过窗口去重
     *   （会话详情页「立即分析本会话」用）；null 走常规去重流程。
     */
    fun enqueueAnalysisNow(context: Context, forceKey: ConvKey? = null) {
        val builder = OneTimeWorkRequestBuilder<AnalysisWorker>()
        // M11：纯端侧配置不需要网络——加 CONNECTED 约束会让离线端侧分析
        // 永远卡在 ENQUEUED（进度条不出现/一直转）。只有可能走云端的配置才要求网络。
        val cfg = AnalysisConfig(context)
        val fullyLocal = cfg.s1Type == AnalysisConfig.S1_TYPE_LOCAL_MODEL &&
            (!cfg.s2Enabled || cfg.s2Provider == AnalysisConfig.S2_PROVIDER_LOCAL)
        if (!fullyLocal) {
            builder.setConstraints(networkConstraint())
        }
        builder
            // 通用 tag：所有立即分析；UI 可观察整体/单会话进度
            .addTag(TAG_ON_DEMAND)
        if (forceKey != null) {
            builder.setInputData(
                androidx.work.Data.Builder()
                    .putString(AnalysisWorker.KEY_FORCE_PKG, forceKey.pkg)
                    .putString(AnalysisWorker.KEY_FORCE_CONVERSATION, forceKey.conversation)
                    .build()
            )
            // 单会话 tag：首页 chip / 会话详情按钮按会话观察进度
            builder.addTag(convTag(forceKey))
        }
        WorkManager.getInstance(context).enqueue(builder.build())
    }

    /** 返回观察指定会话分析进度所用的 tag（pkg+会话名都入 tag，避免同名串台）。 */
    fun convTag(key: ConvKey): String = "$TAG_CONV_PREFIX${key.id}"

    private const val TAG_ON_DEMAND = "analysis_on_demand"
    private const val TAG_CONV_PREFIX = "analysis_conv:"
}
