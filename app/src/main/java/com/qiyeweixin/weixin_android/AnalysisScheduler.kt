// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.qiyeweixin.weixin_android

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

    /**
     * 立即分析一次（不等周期到点），结果同样写回 AnalysisConfig。
     * @param forceConversation 非空时只分析该会话并跳过窗口去重
     *   （会话详情页「立即分析本会话」用）；null 走常规去重流程。
     */
    fun enqueueAnalysisNow(context: Context, forceConversation: String? = null) {
        val builder = OneTimeWorkRequestBuilder<AnalysisWorker>()
            .setConstraints(networkConstraint())
            // 通用 tag：所有立即分析；UI 可观察整体/单会话进度
            .addTag(TAG_ON_DEMAND)
        if (!forceConversation.isNullOrBlank()) {
            builder.setInputData(
                androidx.work.Data.Builder()
                    .putString(AnalysisWorker.KEY_FORCE_CONVERSATION, forceConversation)
                    .build()
            )
            // 单会话 tag：首页 chip / 会话详情按钮按会话观察进度
            builder.addTag("$TAG_CONV_PREFIX$forceConversation")
        }
        WorkManager.getInstance(context).enqueue(builder.build())
    }

    /** 返回观察指定会话分析进度所用的 tag。 */
    fun convTag(conversation: String): String = "$TAG_CONV_PREFIX$conversation"

    private const val TAG_ON_DEMAND = "analysis_on_demand"
    private const val TAG_CONV_PREFIX = "analysis_conv:"
}
