// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import org.json.JSONObject

/**
 * 分析记录（`analysis.jsonl`）的「按会话取窗口」口径与聚合函数。
 *
 * 为什么需要它：`AnalysisStore.readRecent(limit)` 读的是**文件尾部 limit 行**，
 * 那些行可能全部来自同几个会话。于是「从某个会话进入分析列表」「悬浮面板取每会话最新结论」
 * 「首页把历史结论挂回消息」这些**按会话取内容**的调用点，一旦别的会话刷满尾部窗口，
 * 本会话的 case 一条都读不到 —— 列表空白、详情页没有分析、首页搜不到历史结论，
 * 而数据完整躺在文件里。这是 `readRecent` 同源隐患在 MessageStore 之外的第四次、
 * 第五次复现（MessageStore 侧已由 [ConvWindowCore] / [CandidateWindowCore] 收口）。
 *
 * 三条契约：
 * ① **聚合前先放大窗口，聚合后按会话分组**：先读 [TAIL_LINES] 行的尾部窗口，
 *    再分组 —— 不再拿「跨会话的最近 N 条」当某个会话的内容来源。
 * ② **组内按 `windowEnd` 升序**（与 [CandidateWindowCore.group] 同口径）：
 *    末条就是该会话最新的 case，`latest()` 直接取末条，不必再扫一遍求最大值。
 * ③ **不按会话设条数上限**：上限会静默截断历史结论，搜索与历史天挂结果都会凭空少数据；
 *    内存由 [TAIL_LINES] 一个数字兜住（解析后的记录只持有一次）。
 *
 * 已知局限（写清楚，别拿它当 bug 修）：尾部窗口之外的老 case 依然读不到。
 * 单个会话要把 [TAIL_LINES] 行占满，需要 5000 个互不相同的窗口（caseId 去重后才落库），
 * 现实中远达不到；更大的公平性要靠给 analysis.jsonl 建索引，不在当前范围内。
 */
internal object AnalysisWindowCore {

    /**
     * 列表 / 首页聚合读的尾部行窗口（原始行数）。
     *
     * 与 `MessageStoreCore.TAIL_LINES` 同量级：窗口越大，「安静会话的 case 也在里面」
     * 越稳；内存只与窗口成正比、与文件总大小无关（[JsonlStore.readTailLines] 从尾部读）。
     */
    const val TAIL_LINES = 5_000

    /**
     * 轮询路径的尾部行窗口（原始行数）。
     *
     * 详情页每 [POLL_INTERVAL_MS] 轮询 `latestByConvKey`、悬浮面板每次刷新也调它，
     * 全用 [TAIL_LINES] 会让每 4 秒多解析几千条 JSON。1500 行是旧实现（500）的 3 倍，
     * 足以让「别的会话刷了上千条」这种真实场景不再挤出本会话的最新结论。
     */
    const val POLL_LINES = 1_500

    /** 列表页默认展示条数（与旧 `readRecent(this, 200)` 对齐）。 */
    const val LIST_LIMIT = 200

    /**
     * 按会话分组，组内按 `windowEnd` 升序。
     *
     * 分组顺序保持首次出现序（`LinkedHashMap`），配合稳定排序，同一份输入永远得到
     * 同一份输出 —— 列表与悬浮面板的可复现性靠这条。
     */
    fun group(records: List<AnalysisCaseRecord>): Map<ConvKey, List<AnalysisCaseRecord>> {
        if (records.isEmpty()) return emptyMap()
        val buckets = LinkedHashMap<ConvKey, MutableList<AnalysisCaseRecord>>()
        for (record in records) {
            buckets.getOrPut(record.convKey) { mutableListOf() }.add(record)
        }
        val out = LinkedHashMap<ConvKey, List<AnalysisCaseRecord>>(buckets.size)
        for ((key, list) in buckets) {
            out[key] = list.sortedBy { it.windowEnd }
        }
        return out
    }

    /**
     * 每会话最新一条（`windowEnd` 最大）。
     *
     * 同一 `windowEnd` 有多条时取**最后写入**的那条（升序排序的末条）；
     * 旧实现 `maxByOrNull` 取的是第一条 —— 分析窗口不会重复落库（caseId 含 windowEnd），
     * 这条差别只在手工造数据或导入时可见。
     */
    fun latest(groups: Map<ConvKey, List<AnalysisCaseRecord>>): Map<ConvKey, AnalysisCaseRecord> {
        if (groups.isEmpty()) return emptyMap()
        val out = LinkedHashMap<ConvKey, AnalysisCaseRecord>(groups.size)
        for ((key, list) in groups) {
            val last = list.lastOrNull() ?: continue
            out[key] = last
        }
        return out
    }

    /** [latest] 的便捷入口：直接从原始记录算，给「只要每会话一条」的调用方用。 */
    fun latestByConvKey(records: List<AnalysisCaseRecord>): Map<ConvKey, AnalysisCaseRecord> =
        latest(group(records))

    /**
     * 某个会话的 case，按分析时间倒序。
     *
     * @param limit >0 时只取最近若干条；<=0 表示不限（负数与 0 的区别见下）
     *
     * 注意：`limit == 0` 返回空表（调用方传 0 是「不要结果」，不是「全都要」），
     * 不限条数请传负数或省略参数 —— 与 [CandidateWindowCore.group] 的保守退化一致。
     */
    fun forConversation(
        records: List<AnalysisCaseRecord>,
        key: ConvKey,
        limit: Int = UNLIMITED
    ): List<AnalysisCaseRecord> {
        if (records.isEmpty() || limit == 0) return emptyList()
        val hit = records.filter { it.convKey == key }.sortedByDescending { it.analyzedAt }
        return if (limit > 0) hit.take(limit) else hit
    }

    /** 全局最近 [limit] 条（分析时间倒序），给「不分会话」的列表页用。 */
    fun recent(records: List<AnalysisCaseRecord>, limit: Int = LIST_LIMIT): List<AnalysisCaseRecord> {
        if (records.isEmpty() || limit <= 0) return emptyList()
        return records.sortedByDescending { it.analyzedAt }.take(limit)
    }

    /** 「不限条数」的显式取值，避免调用方在 0 与 -1 之间猜语义。 */
    const val UNLIMITED = -1
}

/**
 * `analysis.jsonl` 的窗口读取（文件级）。
 *
 * 与 [AnalysisRejectFile] 同一个范式：纯口径留在 [AnalysisWindowCore]，文件 IO 与
 * 解析放在同文件的这个类里，好让单测拿 `TemporaryFolder` 造真实文件（[AnalysisWindowCoreTest]）。
 *
 * 线程安全：不加锁，由调用方（[AnalysisStore] 的 `@Synchronized`）保证串行。
 */
internal class AnalysisWindowFile(private val store: JsonlStore) {

    /** 尾部 [lines] 行的全部可解析记录（不做任何截断或重排）。 */
    fun readAll(lines: Int = AnalysisWindowCore.TAIL_LINES): List<AnalysisCaseRecord> {
        if (!store.exists()) return emptyList()
        return store.readTailLines(lines.coerceAtLeast(1)).mapNotNull { line ->
            try {
                AnalysisCaseRecord.fromJson(JSONObject(line))
            } catch (e: Exception) {
                null // 旧格式（无 case_id）与损坏行防御式跳过，与 readRecent 同口径
            }
        }
    }

    fun group(lines: Int = AnalysisWindowCore.TAIL_LINES): Map<ConvKey, List<AnalysisCaseRecord>> =
        AnalysisWindowCore.group(readAll(lines))

    fun latestByConvKey(lines: Int = AnalysisWindowCore.POLL_LINES): Map<ConvKey, AnalysisCaseRecord> =
        AnalysisWindowCore.latestByConvKey(readAll(lines))

    fun forConversation(
        key: ConvKey,
        lines: Int = AnalysisWindowCore.TAIL_LINES,
        limit: Int = AnalysisWindowCore.UNLIMITED
    ): List<AnalysisCaseRecord> =
        AnalysisWindowCore.forConversation(readAll(lines), key, limit)

    fun recent(
        limit: Int = AnalysisWindowCore.LIST_LIMIT,
        lines: Int = AnalysisWindowCore.TAIL_LINES
    ): List<AnalysisCaseRecord> = AnalysisWindowCore.recent(readAll(lines), limit)
}
