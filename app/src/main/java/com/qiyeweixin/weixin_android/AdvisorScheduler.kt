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
 * 顾问复盘调度：对 WorkManager 的薄封装，模式与 AnalysisScheduler 一致。
 * 唯一名 wechat_advisor，UPDATE 策略保证重复注册不叠加。
 */
object AdvisorScheduler {

    private const val UNIQUE_WORK_NAME = "wechat_advisor"

    /** 自动复盘周期：每 7 天 */
    private const val PERIOD_DAYS = 7L

    private fun networkConstraint(): Constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    /** 启用/更新每 7 天的自动复盘。 */
    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<AdvisorWorker>(PERIOD_DAYS, TimeUnit.DAYS)
            .setConstraints(networkConstraint())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    /** 停用自动复盘（关闭顾问开关时）。 */
    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
    }

    /** 立即复盘一次（控制台「顾问复盘」按钮），不等周期到点。 */
    fun enqueueAdvisorNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<AdvisorWorker>()
            .setConstraints(networkConstraint())
            .build()
        WorkManager.getInstance(context).enqueue(request)
    }
}
