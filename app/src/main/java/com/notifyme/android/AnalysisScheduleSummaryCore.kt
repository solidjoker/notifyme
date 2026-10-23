// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

/**
 * 控制台「定时分析」面板的判定核（M20，纯 JVM，无 Android 依赖，可单测）。
 *
 * 只回答三个问题：现在会不会自动跑、下一次大概什么时候、是不是已经失联。
 * 失联阈值复用 [ScheduleSelfHealCore.thresholdFor]——与自愈巡检同一把尺，
 * 免得控制台说「健康」而自愈却在补排（两套阈值口径不一致是最难查的坑）。
 */
object AnalysisScheduleSummaryCore {

    enum class Health {
        /** 总开关关闭：永远不会自动跑，只有手动点分析才动 */
        DISABLED,

        /** 开关开着但全局周期=0：只剩「按会话单独定时」的会话会自己跑 */
        GLOBAL_OFF,

        /** 开关开着但一次都没跑过（首轮还没到） */
        NEVER_RUN,

        /** 节奏正常 */
        OK,

        /** 超过失联阈值：排程大概率已被系统丢弃，需要手动跑一轮补排 */
        STALE,
    }

    data class ScheduleStatus(
        val health: Health,
        val intervalMinutes: Long,
        val lastRunAt: Long,
        /** 预计下一次时刻；无法预估（未跑过/关闭）为 0 */
        val nextRunAt: Long,
        /** 失联阈值（毫秒），仅 STALE 文案需要 */
        val staleThresholdMs: Long = 0,
        val minutesSinceLast: Long = 0,
    )

    fun evaluate(enabled: Boolean, intervalMinutes: Long, lastRunAt: Long, now: Long): ScheduleStatus {
        if (!enabled) return ScheduleStatus(Health.DISABLED, intervalMinutes, lastRunAt, 0L)
        if (intervalMinutes <= 0L) return ScheduleStatus(Health.GLOBAL_OFF, intervalMinutes, lastRunAt, 0L)
        val threshold = ScheduleSelfHealCore.thresholdFor(intervalMinutes)
        if (lastRunAt <= 0L) return ScheduleStatus(Health.NEVER_RUN, intervalMinutes, 0L, 0L)
        val sinceLast = now - lastRunAt
        val minutes = if (sinceLast > 0) sinceLast / 60_000L else 0L
        return if (sinceLast > threshold) {
            ScheduleStatus(Health.STALE, intervalMinutes, lastRunAt, 0L, threshold, minutes)
        } else {
            ScheduleStatus(Health.OK, intervalMinutes, lastRunAt, lastRunAt + intervalMinutes * 60_000L, 0L, minutes)
        }
    }

    /**
     * 每会话定时行：只列「设了周期」的会话。
     * [gatedOutIds] = 标签为已办结/无关的会话键——它们即便设了周期也会被 AnalysisWorker 拦下，
     * 必须照实说明，否则用户会以为定时失灵。
     */
    data class ConvRow(val convKeyId: String, val intervalMinutes: Long, val nextRunAt: Long, val gatedOut: Boolean)

    fun convRows(
        schedules: Map<String, Long>,
        gatedOutIds: Set<String>,
        lastRunAt: Long,
        now: Long,
    ): List<ConvRow> = schedules.entries
        .filter { it.value > 0 }
        .map { (id, minutes) ->
            val gated = id in gatedOutIds
            // 会话级排程与全局同轮触发，没有独立时间戳，用全局上次时间估算下一轮
            val next = if (gated || lastRunAt <= 0L) 0L else lastRunAt + minutes * 60_000L
            ConvRow(id, minutes, next, gated)
        }
        .sortedWith(compareBy<ConvRow> { it.gatedOut }.thenBy { it.intervalMinutes }.thenBy { it.convKeyId })

}
