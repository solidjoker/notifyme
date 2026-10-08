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
 * 定时上报调度：对 WorkManager 的薄封装，统一入口便于 UI 与 Worker 复用约束。
 *
 *  - schedule()：enqueueUniquePeriodicWork，唯一名 "wechat_sync"，
 *    UPDATE 策略保证改周期后旧任务被替换而不是并存；
 *  - cancel()：未配置服务器地址时停用定时任务；
 *  - enqueueSyncNow()：OneTimeWorkRequest，供主界面「立即同步一次」按钮；
 *  - 所有请求都带 NetworkType.CONNECTED 约束，无网时不唤醒。
 *
 * 注意：WorkManager 周期任务下限 15 分钟，且实际触发时间会随系统
 * 省电策略（Doze 等）漂移，不适合要求精确到点的场景。
 */
object SyncScheduler {

    private const val UNIQUE_WORK_NAME = "wechat_sync"

    /**
     * 周期任务标记：Worker 用它区分「周期上报」与「立即同步一次」。
     * 周期任务一旦返回 Result.failure() 会被 WorkManager 永久取消
     * （一次 401 就等于定时上报彻底停摆），因此 401 在周期路径下必须降级为 success。
     */
    internal const val TAG_PERIODIC = "sync_periodic"

    /** 一次性「立即同步」标记。 */
    internal const val TAG_ON_DEMAND = "sync_on_demand"

    private fun networkConstraint(): Constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    /** 按配置周期启用/更新定时上报。 */
    fun schedule(context: Context, intervalMinutes: Long) {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(intervalMinutes, TimeUnit.MINUTES)
            .setConstraints(networkConstraint())
            .addTag(TAG_PERIODIC)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    /** 停用定时上报（如清空服务器地址时）。 */
    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
    }

    /** 立即同步一次（不等周期到点），结果同样写回 SyncConfig；返回请求 id 供 UI 观察完成态。 */
    fun enqueueSyncNow(context: Context): java.util.UUID {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(networkConstraint())
            .addTag(TAG_ON_DEMAND)
            .build()
        WorkManager.getInstance(context).enqueue(request)
        return request.id
    }
}
