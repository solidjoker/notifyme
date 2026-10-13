// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import android.content.Context
import org.json.JSONObject

/**
 * 数据保留策略（用户拍板：本地留存 1 周）。
 *
 * 每天最多跑一次全量清理（prefs 记录上次执行日期，跨天触发）。
 * 调用方：SyncWorker.doWork 开头 + MainApplication.onCreate。
 *
 * 清理范围：messages.jsonl、pending.jsonl、analysis.jsonl 与 reminders.jsonl 中
 * 超过 [RETENTION_DAYS] 天的条目。analysis 与消息同窗口清理后不会引发重复分析
 * （旧消息已不在建案范围）；reminders 中仍指向未来事件的记录一律保留。
 * timestamp ≤0（a11y 估算失败）与损坏行保守保留，不因清理丢数据。
 */
object Retention {

    /** 本地保留天数。 */
    const val RETENTION_DAYS = 7L

    private const val PREFS_NAME = "retention_state"
    private const val KEY_LAST_PRUNE_DAY = "last_prune_day"

    /** 清理统计。 */
    data class PruneReport(
        val messagesRemoved: Int,
        val pendingRemoved: Int,
        val analysisRemoved: Int = 0,
        val remindersRemoved: Int = 0
    )

    /** 每天最多执行一次清理；[force] = true 跳过节流。 */
    fun pruneIfDue(
        context: Context,
        nowMs: Long = System.currentTimeMillis(),
        force: Boolean = false
    ): PruneReport {
        val prefs = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val today = nowMs / 86_400_000L
        if (!force && prefs.getLong(KEY_LAST_PRUNE_DAY, 0L) == today) {
            return PruneReport(0, 0)
        }
        prefs.edit().putLong(KEY_LAST_PRUNE_DAY, today).apply()
        return pruneNow(context, nowMs)
    }

    /** 立即执行一轮清理（不受节流限制），返回各文件删除条数。 */
    fun pruneNow(context: Context, nowMs: Long = System.currentTimeMillis()): PruneReport {
        val cutoff = nowMs - RETENTION_DAYS * 86_400_000L
        val messagesRemoved = MessageStore.pruneOlderThan(context, cutoff)
        val pendingRemoved = PendingQueue.pruneOlderThan(context, cutoff)
        val analysisRemoved = AnalysisStore.pruneOlderThan(context, cutoff)
        val remindersRemoved = ReminderStore.pruneOlderThan(context, cutoff, nowMs)
        return PruneReport(messagesRemoved, pendingRemoved, analysisRemoved, remindersRemoved)
    }
}

/**
 * 保留策略的纯决策逻辑（无 Android 依赖，可直接单测）。
 *
 * [prune] 按 [cutoffMs] 过滤行：timestamp 在 (0, cutoff) 区间的行删除，
 * timestamp ≤0（a11y 估算失败）与损坏行（解析不出 timestamp）保守保留。
 */
internal object RetentionCore {

    data class Result(val kept: List<String>, val removed: Int)

    /**
     * @param lines       原始非空行
     * @param cutoffMs    截止时间戳（毫秒）；timestamp < cutoffMs 的行删除
     * @param timestampOf 从行提取 timestamp；返回 null 表示无法判定（保留）
     */
    fun prune(
        lines: List<String>,
        cutoffMs: Long,
        timestampOf: (String) -> Long?
    ): Result {
        val kept = mutableListOf<String>()
        var removed = 0
        for (line in lines) {
            val ts = timestampOf(line)
            if (ts != null && ts in 1 until cutoffMs) {
                removed += 1
            } else {
                kept += line
            }
        }
        return Result(kept, removed)
    }
}

/**
 * analysis.jsonl 保留判定（纯函数，可直接单测）：
 * analyzed_at 在 (0, cutoff) 区间的行删除；损坏行与 analyzed_at≤0 保守保留。
 */
internal object AnalysisRetentionCore {

    fun shouldKeep(line: String, cutoffMs: Long): Boolean {
        val analyzedAt = try {
            JSONObject(line).optLong("analyzed_at", -1L)
        } catch (e: Exception) {
            -1L
        }
        return analyzedAt <= 0L || analyzedAt >= cutoffMs
    }
}

/**
 * reminders.jsonl 保留判定（纯函数，可直接单测）：
 * 创建时间早于 cutoff 且已无生效价值的删除；仍指向未来事件的记录（非 failed
 * 且 event_time > now，含无 status 的 legacy 行）一律保留——它们是 L1 去重、
 * 重启补挂（ReminderRecovery）与列表页重试的依据。损坏行保守保留。
 */
internal object ReminderRetentionCore {

    fun shouldKeep(line: String, cutoffMs: Long, nowMs: Long): Boolean {
        val obj = try {
            JSONObject(line)
        } catch (e: Exception) {
            return true
        }
        val status = obj.optString("status")
        val eventTime = obj.optLong("event_time")
        val createdAt = obj.optLong("created_at")
        val stillPending = status != ReminderRecord.STATUS_FAILED && eventTime > nowMs
        return createdAt <= 0L || createdAt >= cutoffMs || stillPending
    }
}
