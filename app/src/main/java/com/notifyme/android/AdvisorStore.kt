// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import android.content.Context
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * 一份顾问复盘报告（Fable 层产物）。
 *
 * 顾问（AdvisorWorker）通读最近 7 天的 case 分析结果后给出整体复盘与调优建议：
 * {report_id, created_at, period_days, case_count, summary,
 *  suggestions:[{type, title, detail}], model_used}
 *
 * @param reportId   UUID，服务端按此去重
 * @param createdAt  报告生成时间（毫秒）
 * @param periodDays 复盘覆盖的天数（当前固定 7）
 * @param caseCount  本次复盘的 case 数
 * @param summary    整体复盘摘要
 * @param suggestions 调优建议列表
 * @param modelUsed  生成报告所用模型名
 * @param rejectedCount 累计被服务端拒绝次数（207 部分被拒；0 = 从未被拒）
 * @param firstRejectedAt 首次被拒时间（毫秒；0 = 从未被拒）
 * @param quarantined true = 已隔离：仍留在本地文件里人工查看，但不再上报、不再阻塞水位
 */
data class AdvisorReport(
    val reportId: String,
    val createdAt: Long,
    val periodDays: Int,
    val caseCount: Int,
    val summary: String,
    val suggestions: List<Suggestion>,
    val modelUsed: String,
    val rejectedCount: Int = 0,
    val firstRejectedAt: Long = 0L,
    val quarantined: Boolean = false
) {
    /** 一条调优建议：type ∈ prompt / watchlist / threshold / other */
    data class Suggestion(val type: String, val title: String, val detail: String) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("type", type)
            put("title", title)
            put("detail", detail)
        }

        companion object {
            fun fromJson(obj: JSONObject): Suggestion = Suggestion(
                type = obj.optString("type", "other").ifEmpty { "other" },
                title = obj.optString("title"),
                detail = obj.optString("detail")
            )
        }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("report_id", reportId)
        put("created_at", createdAt)
        put("period_days", periodDays)
        put("case_count", caseCount)
        put("summary", summary)
        put("suggestions", JSONArray().apply { suggestions.forEach { put(it.toJson()) } })
        put("model_used", modelUsed)
        put("rejected_count", rejectedCount)
        put("first_rejected_at", firstRejectedAt)
        put("quarantined", quarantined)
    }

    /**
     * **上传口径**的 JSON：只含服务端认识的字段。
     *
     * 本地存档（[toJson]）带 rejected_count / first_rejected_at / quarantined
     * 三个内部字段，服务端并不需要 —— 上传时用这个方法剥离，避免把内部
     * 计数写进云端库里（污染服务端 schema）。
     */
    fun toUploadJson(): JSONObject = JSONObject().apply {
        put("report_id", reportId)
        put("created_at", createdAt)
        put("period_days", periodDays)
        put("case_count", caseCount)
        put("summary", summary)
        put("suggestions", JSONArray().apply { suggestions.forEach { put(it.toJson()) } })
        put("model_used", modelUsed)
    }

    companion object {
        fun fromJson(obj: JSONObject): AdvisorReport? {
            val reportId = obj.optString("report_id")
            if (reportId.isEmpty()) return null
            val suggestions = mutableListOf<Suggestion>()
            obj.optJSONArray("suggestions")?.let { arr ->
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { suggestions.add(Suggestion.fromJson(it)) }
                }
            }
            return AdvisorReport(
                reportId = reportId,
                createdAt = obj.optLong("created_at"),
                periodDays = obj.optInt("period_days"),
                caseCount = obj.optInt("case_count"),
                summary = obj.optString("summary"),
                suggestions = suggestions,
                modelUsed = obj.optString("model_used"),
                rejectedCount = obj.optInt("rejected_count", 0),
                firstRejectedAt = obj.optLong("first_rejected_at", 0L),
                quarantined = obj.optBoolean("quarantined", false)
            )
        }
    }
}

/**
 * 顾问报告存储：JSON Lines 追加写入 files/advisor_reports.jsonl，上限 20 份
 * （追加后整文件重写裁剪，报告体量小、频率极低，重写代价可忽略）。
 *
 * 另存三样东西（同一个 prefs 文件 advisor_store）：
 *  - 自动复盘开关（默认开）；
 *  - 最近一次复盘的时间与结果文案（控制台状态行）；
 *  - 已同步水位（created_at 单调推进，与 AnalysisStore 水位同款口径）。
 */
object AdvisorStore {

    private const val FILE_NAME = "advisor_reports.jsonl"
    private const val MAX_REPORTS = 20

    private const val PREFS_NAME = "advisor_store"
    private const val KEY_AUTO_ENABLED = "auto_enabled"
    private const val KEY_LAST_TIME = "last_report_time"
    private const val KEY_LAST_RESULT = "last_report_result"
    private const val KEY_LAST_SYNCED_AT = "last_synced_created_at"

    private fun prefs(context: Context) = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun storeFile(context: Context): File =
        File(context.filesDir, FILE_NAME)

    /** 追加一份报告并裁剪到上限（保留最新 [MAX_REPORTS] 份）。 */
    @Synchronized
    fun append(context: Context, report: AdvisorReport) {
        val all = readAll(context) + report
        val kept = all.sortedBy { it.createdAt }.takeLast(MAX_REPORTS)
        // 原子重写（临时文件 + fsync + rename）：直接 writeText 截断后被杀会丢全部报告
        JsonlStore.atomicWrite(storeFile(context), kept.map { it.toJson().toString() })
    }

    private fun readAll(context: Context): List<AdvisorReport> {
        val file = storeFile(context)
        if (!file.exists()) return emptyList()
        return file.readLines(Charsets.UTF_8)
            .asSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                try {
                    AdvisorReport.fromJson(JSONObject(line))
                } catch (e: Exception) {
                    null // 单行损坏不影响整体读取
                }
            }
            .toList()
    }

    /** 最近 [limit] 份报告，按生成时间倒序（最新在前）。 */
    @Synchronized
    fun readRecent(context: Context, limit: Int = MAX_REPORTS): List<AdvisorReport> =
        readAll(context).sortedByDescending { it.createdAt }.take(limit)

    /** 最新一份报告（控制台展示用），无报告返回 null。 */
    fun latest(context: Context): AdvisorReport? = readRecent(context, 1).firstOrNull()

    /**
     * 读取尚未同步到服务器的报告（createdAt 大于已同步水位），按时间正序。
     * 服务端按 report_id 去重，重复上报无害。
     */
    fun readUnsynced(context: Context): List<AdvisorReport> {
        // 先把到期的隔离复活（否则被隔离过的报告永远拿不回上报机会）
        reviveExpired(context, System.currentTimeMillis())
        val lastSynced = prefs(context).getLong(KEY_LAST_SYNCED_AT, 0L)
        return readAll(context)
            .filter { it.createdAt > lastSynced && !it.quarantined }
            .sortedBy { it.createdAt }
    }

    /**
     * 记一批被服务端拒绝的报告：累计被拒次数，达上限即隔离。
     *
     * 背景：顾问通道原先只有「水位不动」这一招——一条被持续拒绝的报告会把水位
     * 永久钉死，此后每轮全量重传所有顾问报告。现在与分析通道同一把尺子
     * （[AdvisorRejectCore]）：被拒 3 次即隔离，隔离后照常推进水位，
     * 队列不会再被单条毒丸拖死。
     *
     * @return 本次**新**被隔离的 report_id 集合
     */
    @Synchronized
    fun markRejected(context: Context, reportIds: Set<String>, nowMs: Long): Set<String> {
        if (reportIds.isEmpty()) return emptySet()
        val file = storeFile(context)
        if (!file.exists()) return emptySet()
        return AdvisorRejectFile(file).markRejected(reportIds, nowMs).newlyQuarantined
    }

    /**
     * 强制隔离：立即把这些报告标记为不再上报（**413 毒丸报告**专用）。
     * 重试无用（单条就超服务端上限），立刻隔离并保留报告本体。
     */
    @Synchronized
    fun quarantineNow(context: Context, reportIds: Set<String>, nowMs: Long): Boolean {
        if (reportIds.isEmpty()) return false
        val file = storeFile(context)
        if (!file.exists()) return false
        return AdvisorRejectFile(file).quarantine(reportIds, nowMs)
    }

    /** 当前被隔离的报告 id 集合（诊断面板用）。 */
    @Synchronized
    fun quarantinedReportIds(context: Context): Set<String> {
        val file = storeFile(context)
        if (!file.exists()) return emptySet()
        return AdvisorRejectFile(file).quarantinedReportIds()
    }

    /**
     * 隔离到期（7 天）复活：解除隔离 + 清零计数。
     *
     * 复活时必须**下调水位**：水位只前进不后退（[markSynced]），
     * 而被隔离的报告 created_at 早已落在水位之下 —— 不下调的话它复活后
     * 依然被 createdAt > lastSynced 过滤掉，等于白复活。
     */
    @Synchronized
    fun reviveExpired(context: Context, nowMs: Long): Set<String> {
        val file = storeFile(context)
        if (!file.exists()) return emptySet()
        val revived = AdvisorRejectFile(file).reviveExpired(nowMs)
        if (revived.isEmpty()) return revived
        val earliest = readAll(context)
            .filter { it.reportId in revived }
            .minOfOrNull { it.createdAt }
        if (earliest != null) {
            val p = prefs(context)
            if (p.getLong(KEY_LAST_SYNCED_AT, 0L) >= earliest) {
                p.edit().putLong(KEY_LAST_SYNCED_AT, earliest - 1).apply()
            }
        }
        return revived
    }

    /** 推进已同步水位（只前进不后退；传本次实际上报记录的最大 createdAt）。 */
    @Synchronized
    fun markSynced(context: Context, timestamp: Long) {
        val p = prefs(context)
        if (timestamp > p.getLong(KEY_LAST_SYNCED_AT, 0L)) {
            p.edit().putLong(KEY_LAST_SYNCED_AT, timestamp).apply()
        }
    }

    // ---------- 自动复盘开关与最近状态 ----------

    /** 每 7 天自动复盘开关：默认开（键不存在视为开）。 */
    fun isAutoEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_ENABLED, true)

    fun setAutoEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_ENABLED, enabled).apply()
    }

    fun recordResult(context: Context, result: String) {
        prefs(context).edit()
            .putLong(KEY_LAST_TIME, System.currentTimeMillis())
            .putString(KEY_LAST_RESULT, result)
            .apply()
    }

    fun lastTime(context: Context): Long = prefs(context).getLong(KEY_LAST_TIME, 0L)

    fun lastResult(context: Context): String =
        prefs(context).getString(KEY_LAST_RESULT, "").orEmpty()
}
