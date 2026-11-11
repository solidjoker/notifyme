// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import org.json.JSONObject
import java.io.File

/**
 * 顾问通道的**被拒计数与隔离**（纯逻辑 + 文件级落盘，无 Android 依赖，可 JVM 单测）。
 *
 * 背景（第五轮审计）：顾问通道原先只有「水位不动」这一招——一条报告被服务端
 * 持续拒绝，它就把水位永久钉死，此后每轮**全量重传**所有顾问报告（越积越多）。
 * 分析通道上一轮已经用「被拒计数 + 隔离」收口了同一族问题（见 [AnalysisRejectCore]），
 * 顾问通道这里补上同一套机制。
 *
 * 口径与消息通道（[PendingRejectCore]）、分析通道（[AnalysisRejectCore]）**完全一致**，
 * 决策逻辑直接复用 [AnalysisRejectCore.onRejected]（同一把尺子，避免两套规则漂移）：
 *  - rejected_count = 累计被拒次数；first_rejected_at = 首次被拒时间（后续不续期）；
 *  - 累计达 [MAX_REJECT_COUNT] 次即**隔离**（quarantine）：报告仍留在
 *    advisor_reports.jsonl 里可人工查看，只是不再上报、不再阻塞水位；
 *  - 隔离超过 [REJECT_TTL_MS] 自动复活，重新获得一次上报机会。
 *
 * 顾问报告是模型产出的复盘（用户资产），所以用「隔离」而不是「丢弃」。
 */
internal object AdvisorRejectCore {

    /** 累计被拒次数上限：达到即隔离该报告（不再上报、不再阻塞水位）。 */
    const val MAX_REJECT_COUNT = AnalysisRejectCore.MAX_REJECT_COUNT

    /** 被隔离报告的最长滞留时间：7 天（与本地数据保留策略同口径）。 */
    const val REJECT_TTL_MS = AnalysisRejectCore.REJECT_TTL_MS

    /** 一次被拒（或隔离到期）之后的处置结果。 */
    data class Decision(
        /** 累计被拒次数（含本次；隔离到期清零）。 */
        val rejectedCount: Int,
        /** 首次被拒时间（毫秒）；从未被拒过时为 0。 */
        val firstRejectedAt: Long,
        /** true = 该报告应被隔离（不再上报、不再阻塞水位）。 */
        val quarantined: Boolean,
        /** true = 本次是「隔离到期复活」或「过期重置」，计数/首拒时间被清零。 */
        val revived: Boolean
    )

    /**
     * 记一次被拒（207 部分被拒时调用）。**直接复用** [AnalysisRejectCore.onRejected]：
     * 三条通道同一把尺子，行为不会漂移。
     */
    fun onRejected(
        rejectedCount: Int,
        firstRejectedAt: Long,
        quarantined: Boolean,
        nowMs: Long,
        maxCount: Int = MAX_REJECT_COUNT,
        ttlMs: Long = REJECT_TTL_MS
    ): Decision {
        val d = AnalysisRejectCore.onRejected(
            rejectedCount = rejectedCount,
            firstRejectedAt = firstRejectedAt,
            quarantined = quarantined,
            nowMs = nowMs,
            maxCount = maxCount,
            ttlMs = ttlMs
        )
        return Decision(d.rejectedCount, d.firstRejectedAt, d.quarantined, d.revived)
    }

    /** 过期判定：被拒过（[firstRejectedAt] > 0）且距首次被拒已超过 [ttlMs]。 */
    fun isExpired(firstRejectedAt: Long, nowMs: Long, ttlMs: Long = REJECT_TTL_MS): Boolean =
        AnalysisRejectCore.isExpired(firstRejectedAt, nowMs, ttlMs)
}

/**
 * 顾问报告文件的**被拒 / 隔离**落盘操作（文件级、无 Context，可 JVM 单测）。
 *
 * 写盘口径与既有 store 一致：整体读入 -> 按 report_id 原地打补丁 -> 原子重写
 * （临时文件 + fsync + rename，见 [JsonlStore.atomicWrite]）；损坏行原样保留，
 * 绝不因为一次打补丁丢掉用户数据。
 */
internal class AdvisorRejectFile(private val file: File) {

    /** [markRejected] 的结果。 */
    data class MarkOutcome(
        /** 本次**新**被隔离的 report_id 集合。 */
        val newlyQuarantined: Set<String>,
        /** 文件是否被重写。 */
        val rewritten: Boolean
    )

    /**
     * 给命中的报告记一次被拒：累计计数，达到上限即隔离。
     *
     * @return 本次新被隔离的 report_id 集合（调用方用它判断「还有没有报告阻塞水位」）
     */
    fun markRejected(reportIds: Set<String>, nowMs: Long): MarkOutcome {
        if (reportIds.isEmpty() || !file.exists()) return MarkOutcome(emptySet(), false)
        val newly = LinkedHashSet<String>()
        val rewritten = patchReports(reportIds) { report ->
            val decision = AdvisorRejectCore.onRejected(
                rejectedCount = report.rejectedCount,
                firstRejectedAt = report.firstRejectedAt,
                quarantined = report.quarantined,
                nowMs = nowMs
            )
            if (decision.quarantined && !report.quarantined) {
                newly += report.reportId
            }
            report.copy(
                rejectedCount = decision.rejectedCount,
                firstRejectedAt = decision.firstRejectedAt,
                quarantined = decision.quarantined
            )
        }
        return MarkOutcome(newly, rewritten)
    }

    /**
     * 强制隔离：立即把这些报告标记为不再上报（**413 毒丸报告**专用）。
     *
     * 413 = 这条报告单独就超过服务端上限，重试无用 —— 立刻隔离，
     * 别再每轮把整份报告往上传。报告仍留在 advisor_reports.jsonl 里可人工查看。
     *
     * @return 文件是否被重写
     */
    fun quarantine(reportIds: Set<String>, nowMs: Long): Boolean {
        if (reportIds.isEmpty() || !file.exists()) return false
        return patchReports(reportIds) { report ->
            if (report.quarantined) {
                report
            } else {
                report.copy(
                    quarantined = true,
                    firstRejectedAt = if (report.firstRejectedAt > 0L) report.firstRejectedAt else nowMs
                )
            }
        }
    }

    /**
     * 隔离到期复活：解除隔离 + 清零被拒计数 + 清零首拒时间。
     *
     * @return 实际被复活的 report_id 集合
     */
    fun reviveExpired(nowMs: Long): Set<String> {
        if (!file.exists()) return emptySet()
        val revived = LinkedHashSet<String>()
        patchReports(null) { report ->
            if (report.quarantined && AdvisorRejectCore.isExpired(report.firstRejectedAt, nowMs)) {
                revived += report.reportId
                report.copy(quarantined = false, rejectedCount = 0, firstRejectedAt = 0L)
            } else {
                report
            }
        }
        return revived
    }

    /** 当前被隔离的 report_id 集合（文件级扫描）。 */
    fun quarantinedReportIds(): Set<String> {
        if (!file.exists()) return emptySet()
        val out = LinkedHashSet<String>()
        for (line in file.readLines(Charsets.UTF_8)) {
            if (line.isBlank()) continue
            val report = try {
                AdvisorReport.fromJson(JSONObject(line))
            } catch (e: Exception) {
                null
            } ?: continue
            if (report.quarantined) out += report.reportId
        }
        return out
    }

    /**
     * 逐行打补丁后原子重写：只有真正发生变化的行才被改写，其余行原样保留
     *（含解析不出来的损坏行）。[target] 为 null 表示「全文件扫一遍」。
     *
     * @return 文件是否被重写
     */
    private fun patchReports(
        target: Set<String>?,
        patch: (AdvisorReport) -> AdvisorReport
    ): Boolean {
        if (!file.exists()) return false
        val out = ArrayList<String>()
        var changed = false
        for (line in file.readLines(Charsets.UTF_8)) {
            if (line.isBlank()) {
                out += line
                continue
            }
            val report = try {
                AdvisorReport.fromJson(JSONObject(line))
            } catch (e: Exception) {
                null
            }
            if (report == null || (target != null && report.reportId !in target)) {
                out += line
                continue
            }
            val updated = patch(report)
            if (updated == report) {
                out += line
                continue
            }
            out += updated.toJson().toString()
            changed = true
        }
        if (changed) JsonlStore.atomicWrite(file, out)
        return changed
    }
}
