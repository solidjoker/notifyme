// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import android.content.Context
import org.json.JSONArray

/**
 * M12 分析范围：**周期 + 对象多选**，全部收敛在这里，别在 Worker / 设置页各写一套。
 *
 * 三件事分开看，混在一起就会把「过滤」和「门禁」搞拧：
 *
 *  1. [Range] 是**时间过滤**（近 7 天 / 近 1 月 / 今天 / 自定义），只用来砍掉范围外的消息，
 *     **不改变任何门禁**——周期任务带「近 7 天」，被标了 ✅ 已办结的会话照样不进分析。
 *  2. [Scope.convIds] 是**用户点名**：选中 N 个会话 = 显式指定目标，与 forceKey 同口径，
 *     跳过 caseId 去重、跳过关注名单、跳过标签门禁（[Scope.explicit]）。
 *     理由与「立即分析本会话」一致：用户亲手勾了，系统不该悄悄拦下。
 *  3. 自身/系统源（[AppSourceRegistry.isBlocked]）**任何时候都不进分析**，显式指定也不例外。
 *
 * 周期是相对时间，落盘只存「选择」不存「算出来的毫秒」——否则「近 7 天」会钉死在
 * 保存那一刻的 7 天，第二天再跑就漏掉新消息。[AnalysisScopeStore] 存 period + 自定义边界。
 */
object AnalysisScopeCore {

    /** 不限（默认，等价于 M12 之前的行为） */
    const val PERIOD_ALL = "all"
    const val PERIOD_TODAY = "today"
    const val PERIOD_WEEK = "week"
    const val PERIOD_MONTH = "month"
    const val PERIOD_CUSTOM = "custom"

    /** 与设置页 spinner 顺序一一对应（scope_period_labels） */
    val PERIODS: List<String> = listOf(PERIOD_ALL, PERIOD_TODAY, PERIOD_WEEK, PERIOD_MONTH, PERIOD_CUSTOM)

    const val DAY_MS = 24L * 60L * 60L * 1000L

    /**
     * 时间区间（闭区间）。[isAll] 用 Long.MIN_VALUE / Long.MAX_VALUE 表示「该侧不设限」，
     * 这样「不限」不需要在调用方写分支：contains 恒真。
     */
    data class Range(val fromMs: Long, val toMs: Long) {
        val isAll: Boolean get() = fromMs == Long.MIN_VALUE && toMs == Long.MAX_VALUE
        fun contains(ts: Long): Boolean = ts in fromMs..toMs
    }

    val ALL: Range = Range(Long.MIN_VALUE, Long.MAX_VALUE)

    /**
     * 一次分析的范围。默认值是「不限 + 不点名」，即 M12 之前的行为，老调用方不用改。
     */
    data class Scope(
        val period: String = PERIOD_ALL,
        val range: Range = ALL,
        val convIds: Set<String> = emptySet()
    ) {
        /** 用户点名了会话 = 显式范围：跳过去重与名单/标签门禁。 */
        val explicit: Boolean get() = convIds.isNotEmpty()

        fun acceptsConv(key: ConvKey): Boolean = convIds.isEmpty() || key.id in convIds

        fun acceptsTime(ts: Long): Boolean = range.contains(ts)
    }

    val UNLIMITED: Scope = Scope()

    /** 未知/损坏值一律回落「不限」，绝不因脏数据把分析范围锁成空的。 */
    fun normalizePeriod(raw: String?): String {
        val v = raw?.trim().orEmpty().lowercase()
        return if (v in PERIODS) v else PERIOD_ALL
    }

    /**
     * 本地时区当天零点。单测必须显式传 zone，别依赖默认时区。
     *
     * 口径在 [DueTimeCore.dayStart]（`java.time`）：以前用 `java.util.Calendar.getInstance(zone)`
     * 太过默认 Locale 的历法，泰语（佛历）设备上「今天零点」会落到别的年份上。
     * 这里保留 `java.util.TimeZone` 签名只是为了不改设置页与既有单测的调用点。
     */
    fun dayStart(nowMs: Long, zone: java.util.TimeZone): Long =
        DueTimeCore.dayStart(nowMs, zone.toZoneId())

    /**
     * 周期 -> 区间。**明天/未来**的消息永不进范围：今天与自定义的右端都夹到 nowMs。
     * 「近 N 天」含今天（from = 今天零点 - (N-1) 天），与用户口语一致。
     */
    fun range(
        period: String,
        nowMs: Long,
        zone: java.util.TimeZone,
        customFromMs: Long? = null,
        customToMs: Long? = null
    ): Range = when (normalizePeriod(period)) {
        PERIOD_TODAY -> Range(dayStart(nowMs, zone), nowMs)
        PERIOD_WEEK -> Range(dayStart(nowMs, zone) - 6 * DAY_MS, nowMs)
        PERIOD_MONTH -> Range(dayStart(nowMs, zone) - 29 * DAY_MS, nowMs)
        PERIOD_CUSTOM -> customRange(customFromMs, customToMs, nowMs)
        else -> ALL
    }

    /**
     * 自定义区间：两端都可缺省（null = 该侧不设限）；给了就按「整天」算——
     * 结束日取当天 23:59:59.999，否则用户选「到 9 月 3 日」会把 9 月 3 日的消息全漏掉。
     * 反了就交换（用户先填结束再填开始很常见）。
     */
    fun customRange(fromMs: Long?, toMs: Long?, nowMs: Long = Long.MAX_VALUE): Range {
        val f = fromMs?.let { dayStart(it, zone = DEFAULT_ZONE_FOR_DAY) } ?: Long.MIN_VALUE
        val t = toMs?.let { dayStart(it, zone = DEFAULT_ZONE_FOR_DAY) } ?: Long.MAX_VALUE
        return when {
            f == Long.MIN_VALUE && t == Long.MAX_VALUE -> ALL
            f == Long.MIN_VALUE -> Range(Long.MIN_VALUE, minOf(t + DAY_MS - 1, nowMs))
            t == Long.MAX_VALUE -> Range(f, nowMs)
            f <= t -> Range(f, minOf(t + DAY_MS - 1, nowMs))
            else -> Range(t, minOf(f + DAY_MS - 1, nowMs))
        }
    }

    /** [customRange] 里对「用户选的日期」做整天对齐时用的时区：进程默认时区（UI 口径）。 */
    private val DEFAULT_ZONE_FOR_DAY: java.util.TimeZone get() = java.util.TimeZone.getDefault()

    /**
     * 组装 Scope（设置页 / Store / Worker 共用）。range 由 period + 自定义边界算出来，
     * 免得三处各算一遍还各不相同。
     */
    fun scope(
        period: String,
        nowMs: Long,
        zone: java.util.TimeZone,
        customFromMs: Long? = null,
        customToMs: Long? = null,
        convIds: Set<String> = emptySet()
    ): Scope = Scope(
        period = normalizePeriod(period),
        range = range(period, nowMs, zone, customFromMs, customToMs),
        convIds = convIds.filter { it.isNotBlank() }.toSet()
    )

    // ---------- 选会话决策（Worker 用，抽出来是为了能纯 JVM 单测） ----------

    /**
     * 「立即分析」入队时该带哪份范围。forceKey（会话详情页「立即分析本会话」）不带范围：
     * 单会话显式点名要的是这一整窗，不该被设置页存的「今天 / 近 7 天」砍掉后半截。
     */
    fun immediateScope(hasForceKey: Boolean, picked: Scope): Scope =
        if (hasForceKey) UNLIMITED else picked

    /** 显式点名 = 单会话 forceKey，或范围里勾了会话。两者同口径：跳名单/标签门禁。 */
    fun forced(hasForceKey: Boolean, scope: Scope): Boolean = hasForceKey || scope.explicit

    /**
     * 跳 caseId 去重（窗口没变也重算一遍）。只有手动轮值得这么干：
     * 周期轮若也跳，「近 7 天」每 15 分钟就会把同一批窗口重算一遍，纯烧 token。
     */
    fun skipDedup(hasForceKey: Boolean, scope: Scope, manual: Boolean): Boolean =
        hasForceKey || (scope.explicit && manual)

    /**
     * 空结果文案。必须区分「真的没活」与「被范围筛空了」——
     * 后者若也只说「无待分析会话」，用户会以为分析坏了。
     */
    fun emptyResultMessage(hasForceKey: Boolean, scope: Scope): String = when {
        hasForceKey -> "成功：本会话无新消息可分析"
        scope.explicit && !scope.range.isAll ->
            "成功：已选会话在" + periodLabel(scope.period) + "范围内无消息可分析"
        scope.explicit -> "成功：已选会话无新消息可分析"
        !scope.range.isAll -> "成功：" + periodLabel(scope.period) + "范围内无待分析会话"
        else -> "成功：无待分析会话"
    }
    /** 只保留落在区间内的条目（时间取值由调用方注入，便于纯 JVM 单测）。 */
    fun <T> keep(items: List<T>, range: Range, tsOf: (T) -> Long): List<T> =
        if (range.isAll) items else items.filter { range.contains(tsOf(it)) }

    /** 只保留被点名的会话；未点名（空集合）= 全通过。 */
    fun <T> keepConvs(items: List<T>, scope: Scope, keyOf: (T) -> ConvKey): List<T> =
        if (!scope.explicit) items else items.filter { scope.acceptsConv(keyOf(it)) }

    /** 周期名（设置页 spinner 与摘要共用；应用是中文单语，文案直接写在这里）。 */
    fun periodLabel(period: String): String = when (normalizePeriod(period)) {
        PERIOD_TODAY -> "今天"
        PERIOD_WEEK -> "近 7 天"
        PERIOD_MONTH -> "近 1 月"
        PERIOD_CUSTOM -> "自定义"
        else -> "不限"
    }

    /** 一行摘要：周期 + 对象口径。selectedCount 是点名会话数（0 = 全部会话）。 */
    fun summary(scope: Scope, convTotal: Int = 0): String {
        val head = periodLabel(scope.period)
        val tail = if (scope.explicit) {
            "已选 " + scope.convIds.size + " 个会话"
        } else {
            "全部会话" + if (convTotal > 0) "（$convTotal 个）" else ""
        }
        return head + " · " + tail
    }

    /** yyyy-MM-dd（自定义日期输入框回填用）。口径在 [DueTimeCore.formatDay]。 */
    fun formatDay(ms: Long, zone: java.util.TimeZone = java.util.TimeZone.getDefault()): String =
        DueTimeCore.formatDay(ms, zone.toZoneId())

    /**
     * 解析 yyyy-MM-dd（也接受 yyyy/MM/dd）。非法输入返回 null 而不是抛异常——
     * 输入框里随时可能是半截字符串，抛异常会把整页保存按钮打挂。
     */
    fun parseDay(text: String?, zone: java.util.TimeZone = java.util.TimeZone.getDefault()): Long? =
        DueTimeCore.parseDay(text, zone.toZoneId())

    // ---------- Worker inputData 编解码 ----------

    const val KEY_PERIOD = "scope_period"
    const val KEY_FROM = "scope_from"
    const val KEY_TO = "scope_to"
    const val KEY_CONV_IDS = "scope_conv_ids"

    /** 序列化成 JSON 数组字符串（会话 id 里含 `|`，逗号拼接会被群名里的逗号切坏）。 */
    fun encodeConvIds(ids: Set<String>): String {
        val arr = JSONArray()
        ids.filter { it.isNotBlank() }.sorted().forEach { arr.put(it) }
        return arr.toString()
    }

    fun decodeConvIds(raw: String?): Set<String> {
        if (raw.isNullOrBlank()) return emptySet()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                arr.optString(i).trim().takeIf { it.isNotEmpty() }
            }.toSet()
        }.getOrElse { emptySet() }
    }
}

/**
 * 分析范围的持久化（SharedPreferences `analysis_scope`）。
 *
 * 只存「选择」：period / 自定义边界（ms，无则 -1）/ 点名会话 id 列表；
 * [Scope.range] 每次 load 时按当下时间重算，所以「近 7 天」永远是相对今天。
 */
internal object AnalysisScopeStore {

    private const val PREFS_NAME = "analysis_scope"
    private const val KEY_PERIOD = "period"
    private const val KEY_FROM = "custom_from"
    private const val KEY_TO = "custom_to"
    private const val KEY_CONV_IDS = "conv_ids"

    /** 落盘的原始选择：周期 + 自定义边界（原样，不按当下重算）+ 点名会话。 */
    data class Stored(
        val period: String,
        val customFromMs: Long?,
        val customToMs: Long?,
        val convIds: Set<String>
    )

    /** 读原始选择 —— 设置页回填自定义日期框要的是存进去的那两天，不是算出来的区间。 */
    fun loadStored(context: Context): Stored {
        val prefs = prefs(context)
        return Stored(
            period = AnalysisScopeCore.normalizePeriod(prefs.getString(KEY_PERIOD, null)),
            customFromMs = prefs.getLong(KEY_FROM, -1L).takeIf { it > 0 },
            customToMs = prefs.getLong(KEY_TO, -1L).takeIf { it > 0 },
            convIds = AnalysisScopeCore.decodeConvIds(prefs.getString(KEY_CONV_IDS, null))
        )
    }

    fun load(
        context: Context,
        nowMs: Long = System.currentTimeMillis(),
        zone: java.util.TimeZone = java.util.TimeZone.getDefault()
    ): AnalysisScopeCore.Scope {
        val stored = loadStored(context)
        return AnalysisScopeCore.scope(
            stored.period, nowMs, zone,
            stored.customFromMs, stored.customToMs, stored.convIds
        )
    }

    fun save(
        context: Context,
        period: String,
        customFromMs: Long?,
        customToMs: Long?,
        convIds: Set<String>
    ) {
        prefs(context).edit()
            .putString(KEY_PERIOD, AnalysisScopeCore.normalizePeriod(period))
            .putLong(KEY_FROM, customFromMs ?: -1L)
            .putLong(KEY_TO, customToMs ?: -1L)
            .putString(KEY_CONV_IDS, AnalysisScopeCore.encodeConvIds(convIds))
            .apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
