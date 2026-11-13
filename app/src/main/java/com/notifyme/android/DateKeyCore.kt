// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 日期分组（day key）与日期组头文案的唯一口径。
 *
 * 为什么要抽：同一件事此前有四处各写一套 —— [MessageStore.dayKeyOf]（java.time）、
 * `MainActivity.buildFeed` 里的 `dayKeyFormat` / `currentYear` / `labelSameYear` / `labelCrossYear`、
 * `ConsoleActivity.dayKeyFmt`、`CollapseStore.latestDayKeyOf`。其中三处用的是
 * `SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())`。
 *
 * **这不是风格问题，是正确性问题**：`SimpleDateFormat` 的历法是跟着 Locale 走的 ——
 * 同一毫秒（2026-11-12 15:30 +08:00）在
 *  - `th-TH`（佛历）下给出 `2569-11-12`，
 *  - `ja-JP-u-ca-japanese`（和历）下给出 `8-11-12`，
 *  - `ar-EG` 下给出本地数字（`????-??-??`）；
 * 而 `java.time` 的 `Instant → atZone → toLocalDate` 恒为公历 ISO（`2026-11-12`）。
 * 于是设备的系统 Locale 一旦不是公历，`MainActivity` 生成的 day key 与 [MessageStore.dayKeyOf]
 * 算出来的**永远不相等**：左滑删某天删不掉、`CollapseStore` 的复合键对不上、
 * 「最新一天」判定和「跨年标签」也一起错（`dayKey.startsWith(currentYear)` 里 currentYear 是佛历年，
 * 前缀永不匹配，全部分组都渲染成跨年格式）。
 *
 * 所以这里只用 `java.time` 产生 key，并且**从 key 自身解析年月日**来算标签 ——
 * 不再回退到任何 `SimpleDateFormat`，也就不会出现「key 是公历、标签是佛历」的错位。
 * 文案本身不在这里：Core 只回数值（[DayLabel]），中文模板留在 `strings.xml`，
 * 这样纯 JVM 单测既能覆盖又没有资源依赖。
 */
internal object DateKeyCore {

    /** 「未标注日期」组的 key：timestamp ≤ 0（a11y 直读估算失败等）的消息归这里。 */
    const val UNKNOWN = "__unknown__"

    /** ISO 局部日期 key 的固定长度：`yyyy-MM-dd`。 */
    const val KEY_LENGTH = 10

    /**
     * 消息所属日期 key（本机时区公历 ISO，如 `2026-11-12`）；[timestampMs] ≤ 0 返回 [UNKNOWN]。
     *
     * 必须走 `java.time`：`SimpleDateFormat` 的历法随 Locale 变化（佛历 / 和历 / 本地数字），
     * 产出的 key 会与另一个调用点不一致。
     */
    fun dayKeyOf(timestampMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        if (timestampMs > 0L) {
            Instant.ofEpochMilli(timestampMs).atZone(zone).toLocalDate().toString()
        } else UNKNOWN

    /** 空串与 [UNKNOWN] 都算「没有日期」——老数据里两种写法都存在。 */
    fun isUnknown(dayKey: String?): Boolean = dayKey.isNullOrEmpty() || dayKey == UNKNOWN

    /** 拆出来的年月日（公历）。 */
    data class Parts(val year: Int, val month: Int, val day: Int)

    /**
     * 解析 `yyyy-MM-dd`：严格按位取数并回读校验（`2026-02-30` 返 null，不是静默变成 3 月 2 日）。
     * 解析不出来一律返 null，调用方当「未知」处理。
     */
    fun parts(dayKey: String): Parts? {
        if (isUnknown(dayKey) || dayKey.length != KEY_LENGTH) return null
        if (dayKey[4] != '-' || dayKey[7] != '-') return null
        val digits = intArrayOf(0, 1, 2, 3, 5, 6, 8, 9)
        var value = 0
        for (i in 0 until 8) {
            val c = dayKey[digits[i]]
            if (c < '0' || c > '9') return null
            value = value * 10 + (c - '0')
        }
        val year = value / 10000
        val month = (value / 100) % 100
        val day = value % 100
        if (year <= 0 || month !in 1..12 || day !in 1..31) return null
        return try {
            LocalDate.of(year, month, day)
            Parts(year, month, day)
        } catch (e: java.time.DateTimeException) {
            null
        }
    }

    /** 该 key 的年份；未知或解析失败返 null。 */
    fun yearOf(dayKey: String): Int? = parts(dayKey)?.year

    /**
     * 日期组头要显示什么：[crossYear] 为真时需要带年份（`yyyy年M月d日`），
     * 否则同年只显月日（`M月d日`）；[unknown] 为真时调用方走「未标注日期」文案。
     */
    data class DayLabel(
        val year: Int,
        val month: Int,
        val day: Int,
        val crossYear: Boolean,
        val unknown: Boolean
    )

    /**
     * 算日期组头：年份比较用 [Parts.year] 与 [nowMs] 的**年份数值**，
     * 不再用 `dayKey.startsWith(currentYear)` —— 那个写法在非公历 Locale 下先错在 currentYear 本身。
     */
    fun labelOf(
        dayKey: String,
        nowMs: Long,
        zone: ZoneId = ZoneId.systemDefault()
    ): DayLabel {
        val parts = parts(dayKey)
            ?: return DayLabel(0, 0, 0, crossYear = false, unknown = true)
        val nowYear = if (nowMs > 0L) {
            Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate().year
        } else {
            parts.year
        }
        return DayLabel(
            year = parts.year,
            month = parts.month,
            day = parts.day,
            crossYear = parts.year != nowYear,
            unknown = false
        )
    }

    /** 是否跨年（给不需要完整标签的调用方用）。 */
    fun isCrossYear(dayKey: String, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): Boolean =
        labelOf(dayKey, nowMs, zone).let { !it.unknown && it.crossYear }

    /**
     * 该会话的「最新一天」：取最大 key（`yyyy-MM-dd` 字典序即时间序）。
     * 「未标注日期」组是时间戳缺失的历史残渣，有正常天时永远不算最新；
     * 全是未知时回落 [unknown]。
     */
    fun latestOf(dayKeys: Collection<String>, unknown: String = UNKNOWN): String =
        dayKeys.filterNot { isUnknown(it) }.maxOrNull() ?: unknown
}
