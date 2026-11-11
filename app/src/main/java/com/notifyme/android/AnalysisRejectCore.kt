// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import org.json.JSONObject
import java.io.File

/**
 * 分析通道的**被拒计数与隔离**策略（纯逻辑 + 文件级落盘，无 Android 依赖，可 JVM 单测）。
 *
 * 背景（第四轮，评审 P0-3 的收口）：上一轮把「部分 case 被服务端拒绝（207）」处理成
 * 「水位不动、下一轮整体重报」——这对**偶发**被拒是对的，但对**持续**被拒是灾难：
 * 只要 analysis.jsonl 里有**一条** case 被服务端反复拒绝（case_id 冲突、字段不合法等），
 * 它就永远排在最前面被重复上报，水位永不推进 → 第 1001 条之后的记录永远读不到、
 * 永远不上报（分析通道饥饿）。
 *
 * 消息通道上一轮已经用「被拒计数 + 丢弃上限」解决了同一族问题（见 [PendingRejectCore]），
 * 但分析记录的语义更保守：**分析结果是已经生成的复盘，不能删**（消息队列是「待发消息」，
 * 丢掉可以接受；分析记录是用户资产）。所以分析通道用同款口径但换成**隔离（quarantine）**：
 *  - 记录仍留在 analysis.jsonl 里，可人工查看、可被 UI 读到；
 *  - 只是不再上报、也不再阻塞水位（不参与本轮 allAccepted 判定）；
 *  - 隔离到期（[REJECT_TTL_MS]）自动解除隔离，重新获得一次上报机会。
 *
 * 口径与消息通道保持一致：rejected_count = 累计被拒次数；
 * first_rejected_at = 首次被拒时间（毫秒，0 = 从未被拒；后续被拒不续期）。
 */
internal object AnalysisRejectCore {

    /** 累计被拒次数上限：达到即隔离该 case（不再上报、不再阻塞水位）。 */
    const val MAX_REJECT_COUNT = 3

    /** 被隔离记录的最长滞留时间：7 天（与本地数据保留策略同口径）。 */
    const val REJECT_TTL_MS = 7L * 24 * 60 * 60 * 1000

    /** 一次被拒（或隔离到期）之后的处置结果。 */
    data class Decision(
        /** 累计被拒次数（含本次；隔离到期清零）。 */
        val rejectedCount: Int,
        /** 首次被拒时间（毫秒）；从未被拒过时为 0。 */
        val firstRejectedAt: Long,
        /** true = 该 case 应被隔离（不再上报、不再阻塞水位）。 */
        val quarantined: Boolean,
        /** true = 本次是「隔离到期复活」或「过期重置」，计数/首拒时间被清零。 */
        val revived: Boolean
    )

    /**
     * 记一次被拒（207 部分被拒时调用）。
     *
     * 决策顺序（口径固定，单测钉住）：
     *  1. 已隔离且未过期 -> 保持隔离，不重复计数；
     *  2. 已隔离但已过期 -> 解除隔离 + 计数与首拒时间清零（重新给一次机会）；
     *  3. 未隔离但已过期（被拒过、窗口已过）-> 计数清零，本次算第 1 次被拒；
     *  4. 未隔离且未过期 -> 计数 +1，首拒时间只写一次，达到上限则隔离。
     *
     * @param rejectedCount 当前累计被拒次数
     * @param firstRejectedAt 首次被拒时间；0 表示此前从未被拒
     * @param quarantined 当前是否处于隔离态
     * @param nowMs 本次被拒时间（毫秒）
     * @param maxCount 隔离阈值；默认 [MAX_REJECT_COUNT]
     * @param ttlMs 被拒/隔离的最长滞留窗口；默认 [REJECT_TTL_MS]
     */
    fun onRejected(
        rejectedCount: Int,
        firstRejectedAt: Long,
        quarantined: Boolean,
        nowMs: Long,
        maxCount: Int = MAX_REJECT_COUNT,
        ttlMs: Long = REJECT_TTL_MS
    ): Decision {
        val expired = isExpired(firstRejectedAt, nowMs, ttlMs)
        if (quarantined) {
            return if (expired) Decision(0, 0L, false, true)
            else Decision(rejectedCount, firstRejectedAt, true, false)
        }
        if (expired) {
            // 之前被拒过、窗口已过：计数清零，本次重新算第 1 次
            return Decision(1, nowMs, false, true)
        }
        val count = rejectedCount + 1
        val first = if (firstRejectedAt > 0L) firstRejectedAt else nowMs
        return Decision(count, first, count >= maxCount, false)
    }

    /**
     * 过期判定：被拒过（[firstRejectedAt] > 0）且距首次被拒已超过 [ttlMs]。
     *
     * 从未被拒过的记录**永远不过期**——它们本来就该继续上报，
     * 不该被隔离/清理逻辑误伤。
     */
    fun isExpired(firstRejectedAt: Long, nowMs: Long, ttlMs: Long = REJECT_TTL_MS): Boolean =
        firstRejectedAt > 0L && nowMs - firstRejectedAt >= ttlMs

    /** 隔离到期：解除隔离并清零计数（记录重新获得上报机会）。 */
    fun revive(): Decision = Decision(0, 0L, false, true)
}


/**
 * 分析文件的**被拒 / 隔离**落盘操作（文件级、无 Context，可 JVM 单测）。
 *
 * 写盘口径与既有 store 一致：整体读入 -> 按 case_id 原地打补丁 -> 原子重写
 * （临时文件 + fsync + rename，见 [JsonlStore.overwrite]）；损坏行原样保留，
 * 绝不因为一次打补丁丢掉用户数据。
 */
internal class AnalysisRejectFile(private val file: File) {

    /** [markRejected] 的结果。 */
    data class MarkOutcome(
        /** 本次**新**被隔离的 case_id -> 首次被拒时间（毫秒）。 */
        val newlyQuarantined: Map<String, Long>,
        /** 文件是否被重写（重写后字节游标失效，下一轮必须从头扫）。 */
        val rewritten: Boolean
    )

    /**
     * 给命中的 case 记一次被拒：累计计数，达到上限即隔离。
     *
     * @return 本次新被隔离的 case_id 集合（调用方用它判断「还有没有 case 在阻塞水位」）
     */
    fun markRejected(caseIds: Set<String>, nowMs: Long): MarkOutcome {
        if (caseIds.isEmpty() || !file.exists()) return MarkOutcome(emptyMap(), false)
        val newly = LinkedHashMap<String, Long>()
        val rewritten = patchRecords(caseIds) { record ->
            val decision = AnalysisRejectCore.onRejected(
                rejectedCount = record.rejectedCount,
                firstRejectedAt = record.firstRejectedAt,
                quarantined = record.quarantined,
                nowMs = nowMs
            )
            if (decision.quarantined && !record.quarantined) {
                newly[record.caseId] = decision.firstRejectedAt
            }
            record.copy(
                rejectedCount = decision.rejectedCount,
                firstRejectedAt = decision.firstRejectedAt,
                quarantined = decision.quarantined
            )
        }
        return MarkOutcome(newly, rewritten)
    }

    /** 强制隔离：立即把这些 case 标记为不再上报（记录仍留在文件里）。 */
    fun quarantine(caseIds: Set<String>, nowMs: Long): Boolean {
        if (caseIds.isEmpty() || !file.exists()) return false
        return patchRecords(caseIds) { record ->
            if (record.quarantined) {
                record
            } else {
                record.copy(
                    quarantined = true,
                    firstRejectedAt = if (record.firstRejectedAt > 0L) record.firstRejectedAt else nowMs
                )
            }
        }
    }

    /**
     * 解除隔离（隔离到期）：计数与首拒时间清零，重新获得上报机会。
     *
     * @return 实际被复活（文件里确实存在）的 case_id 集合；文件里已经没有的
     *         case_id（被用户删掉了）不在返回里，调用方据此清理索引。
     */
    fun revive(caseIds: Set<String>): Set<String> {
        if (caseIds.isEmpty() || !file.exists()) return emptySet()
        val revived = LinkedHashSet<String>()
        patchRecords(caseIds) { record ->
            revived += record.caseId
            record.copy(quarantined = false, rejectedCount = 0, firstRejectedAt = 0L)
        }
        return revived
    }

    /** 当前被隔离的 case_id 集合（文件级扫描）。 */
    fun quarantinedCaseIds(): Set<String> {
        if (!file.exists()) return emptySet()
        val out = LinkedHashSet<String>()
        for (line in file.readLines(Charsets.UTF_8)) {
            if (line.isBlank()) continue
            val record = try {
                AnalysisCaseRecord.fromJson(JSONObject(line))
            } catch (e: Exception) {
                null
            } ?: continue
            if (record.quarantined) out += record.caseId
        }
        return out
    }

    /** 被隔离条数。 */
    fun quarantinedCount(): Int = quarantinedCaseIds().size

    /**
     * 逐行打补丁后原子重写：只有真正发生变化的行才被改写，其余行原样保留
     *（含解析不出来的损坏行）。返回文件是否被重写。
     */
    private fun patchRecords(
        target: Set<String>,
        patch: (AnalysisCaseRecord) -> AnalysisCaseRecord
    ): Boolean {
        if (!file.exists()) return false
        val out = ArrayList<String>()
        var changed = false
        for (line in file.readLines(Charsets.UTF_8)) {
            if (line.isBlank()) {
                out += line
                continue
            }
            val record = try {
                AnalysisCaseRecord.fromJson(JSONObject(line))
            } catch (e: Exception) {
                null
            }
            if (record == null || record.caseId !in target) {
                out += line
                continue
            }
            val updated = patch(record)
            if (updated == record) {
                out += line
                continue
            }
            val encoded = try {
                updated.toJson().toString()
            } catch (e: Exception) {
                // 旧记录字段缺失导致序列化失败（例如 s1 块缺失 -> importance 为 NaN，
                // org.json 不接受非有限数）：保留原行，既不丢用户数据，也不让整轮上报崩掉
                out += line
                continue
            }
            out += encoded
            changed = true
        }
        if (changed) JsonlStore.atomicWrite(file, out)
        return changed
    }
}