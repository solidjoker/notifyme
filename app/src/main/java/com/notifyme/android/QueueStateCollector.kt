// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import android.content.Context
import android.util.Log
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 端上排队任务采集：把「还在排队的 / 正在跑的 / 失败的」任务收集出来，
 * 交给 [SyncWorker] 的排队通道上报到服务端控制台（控制台「排队」tab 可见）。
 *
 * 覆盖两类排队：
 *  - 分析任务：WorkManager 的一次性「立即分析」与会话级定时分析；
 *  - 同步队列：待上报消息条数（排队中）与累计被拒丢弃条数（失败）。
 *
 * 设备标识 [deviceId] 是**随机种子派生**的，不是硬件标识：
 * 首次生成一个随机 UUID 存进 SharedPreferences，之后一直用它派生出 8 位十六进制摘要；
 * 卸载重装即失效，不跨设备追踪，也不碰 ANDROID_ID 这类系统标识。
 */
internal object QueueStateCollector {

    private const val TAG = "QueueStateCollector"

    private const val PREFS_NAME = "queue_state"
    private const val KEY_SEED = "device_seed"

    /** 「立即分析」任务的 task_id。 */
    const val TASK_ON_DEMAND = "analysis:on_demand"

    /** 待同步消息队列的 task_id。 */
    const val TASK_PENDING = "sync:pending"

    /** 被拒丢弃消息的 task_id。 */
    const val TASK_REJECTED = "sync:rejected"

    /**
     * 会话级分析任务的 tag 前缀。
     *
     * AnalysisScheduler 里的前缀常量是 private（这一轮只允许放开 TAG_ON_DEMAND），
     * 所以这里镜像一份：构造 tag 一律走 [AnalysisScheduler.convTag]，
     * 只有「拿已有 tag 反解会话 id」这一处需要前缀字面量。
     */
    private const val CONV_TAG_PREFIX = "analysis_conv:"

    /** 「立即分析」任务的标题。 */
    private const val TITLE_ON_DEMAND = "立即分析"

    /** 立即分析任务的详情。 */
    private const val DETAIL_ON_DEMAND = "用户触发的立即分析任务"

    /** 会话级分析任务的详情。 */
    private const val DETAIL_CONV_TASK = "会话级定时分析任务"

    /** 待同步队列的详情。 */
    private const val DETAIL_PENDING = "等待同步到服务端"

    /** 被拒丢弃的详情。 */
    private const val DETAIL_REJECTED = "服务端持续拒绝后丢弃，未上报"

    /** WorkManager 查询超时（毫秒）：查不到就当没有排队任务，绝不拖住同步。 */
    private const val QUERY_TIMEOUT_MS = 2000L

    /** 设备标识：形如 android-1a2b3c4d。 */
    fun deviceId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val seed = prefs.getString(KEY_SEED, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(KEY_SEED, it).apply()
        }
        return QueueStateCore.deriveDeviceId(seed)
    }

    /**
     * 采集当前排队任务（已归一化）。
     *
     * @param nowMs 当前时间（毫秒），用作排队时间的近似值——WorkManager 的公开 API
     *   不暴露入队时刻，用采集时刻近似即可（服务端只按它做同状态内排序）。
     */
    fun collect(context: Context, nowMs: Long): List<QueueStateCore.QueueTask> {
        val raw = mutableListOf<QueueStateCore.QueueTask>()
        // WorkManager 还没初始化（进程刚起/测试环境）时只影响分析任务那部分，
        // 同步队列的两条照常采集——不能让一个可选数据源把整份队列状态拖没。
        val wm = try {
            WorkManager.getInstance(context)
        } catch (e: Exception) {
            Log.w(TAG, "WorkManager 不可用，跳过分析任务采集", e)
            null
        }
        if (wm != null) {
            raw += onDemandTasks(wm, nowMs)
            // 会话级排期：排期表就是「哪些会话有独立分析任务」的权威清单
            for (convId in ConvAnalysisScheduleStore.all(context).keys) {
                val key = ConvKey.parse(convId)
                raw += workTasks(
                    wm = wm,
                    tag = AnalysisScheduler.convTag(key),
                    taskId = "analysis:" + convId,
                    title = key.conversation,
                    detail = DETAIL_CONV_TASK,
                    nowMs = nowMs
                )
            }
        }

        val pendingCount = PendingQueue.readAll(context).size
        if (pendingCount > 0) {
            raw += QueueStateCore.QueueTask(
                taskId = TASK_PENDING,
                kind = QueueStateCore.KIND_SYNC,
                title = "待同步消息 " + pendingCount + " 条",
                state = QueueStateCore.STATE_QUEUED,
                detail = DETAIL_PENDING,
                queuedAt = nowMs
            )
        }

        val dropped = PendingQueue.droppedRejectedCount(context)
        if (dropped > 0) {
            raw += QueueStateCore.QueueTask(
                taskId = TASK_REJECTED,
                kind = QueueStateCore.KIND_SYNC,
                title = "已丢弃 " + dropped + " 条",
                state = QueueStateCore.STATE_FAILED,
                detail = DETAIL_REJECTED,
                queuedAt = nowMs
            )
        }

        return QueueStateCore.normalize(raw)
    }

    /**
     * 「立即分析」tag 下的任务。
     *
     * 会话排期触发的那次分析是「一次性任务 + 会话 tag」，它同时命中本通道和会话通道；
     * 这里按它身上的会话 tag 还原成会话级条目（task_id = analysis:<会话 id>），
     * 免得同一个任务被报成两条（一条叫「立即分析」、一条叫会话名）。
     */
    private fun onDemandTasks(wm: WorkManager, nowMs: Long): List<QueueStateCore.QueueTask> {
        val out = mutableListOf<QueueStateCore.QueueTask>()
        for (info in workInfos(wm, AnalysisScheduler.TAG_ON_DEMAND)) {
            val state = mapState(info.state) ?: continue
            val convTag = info.tags.firstOrNull { it.startsWith(CONV_TAG_PREFIX) }
            if (convTag != null) {
                val convId = convTag.removePrefix(CONV_TAG_PREFIX)
                val key = ConvKey.parse(convId)
                out += QueueStateCore.QueueTask(
                    taskId = "analysis:" + convId,
                    kind = QueueStateCore.KIND_ANALYSIS,
                    title = key.conversation,
                    state = state,
                    detail = DETAIL_CONV_TASK,
                    queuedAt = nowMs
                )
            } else {
                out += QueueStateCore.QueueTask(
                    taskId = TASK_ON_DEMAND,
                    kind = QueueStateCore.KIND_ANALYSIS,
                    title = TITLE_ON_DEMAND,
                    state = state,
                    detail = DETAIL_ON_DEMAND,
                    queuedAt = nowMs
                )
            }
        }
        return out
    }

    /**
     * 按 tag 取 WorkManager 任务并映射成排队条目。
     *
     * 状态映射：ENQUEUED/BLOCKED -> 排队中，RUNNING -> 运行中，FAILED -> 失败；
     * SUCCEEDED/CANCELLED 不收（已经结束的任务没有「排队」意义）。
     */
    private fun workTasks(
        wm: WorkManager,
        tag: String,
        taskId: String,
        title: String,
        detail: String,
        nowMs: Long
    ): List<QueueStateCore.QueueTask> {
        return workInfos(wm, tag).mapNotNull { info ->
            val state = mapState(info.state) ?: return@mapNotNull null
            QueueStateCore.QueueTask(
                taskId = taskId,
                kind = QueueStateCore.KIND_ANALYSIS,
                title = title,
                state = state,
                detail = detail,
                queuedAt = nowMs
            )
        }
    }

    /** 取某个 tag 下的 WorkInfo 列表；查不到就算没有排队任务（不能拖住同步）。 */
    private fun workInfos(wm: WorkManager, tag: String): List<WorkInfo> {
        return try {
            wm.getWorkInfosByTag(tag).get(QUERY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "查询 WorkManager 任务失败: tag=" + tag, e)
            emptyList()
        }
    }

    /** WorkManager 状态 -> 端上排队口径。已结束（成功/取消）返回 null（不收）。 */
    private fun mapState(state: WorkInfo.State): String? = when (state) {
        WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> QueueStateCore.STATE_QUEUED
        WorkInfo.State.RUNNING -> QueueStateCore.STATE_RUNNING
        WorkInfo.State.FAILED -> QueueStateCore.STATE_FAILED
        else -> null
    }
}
