// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import android.content.Context
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 「什么时候要完成」的展示口径：把「明天15:00」「本周内」「today」这类文本换算成
 * **绝对日期**再显示（今天 / 明天 / 某年某月某日），而不是把原文照抄。
 *
 * 为什么抽出来：改前四处各写一份（首页待办卡 / 会话卡分析行 / 分析列表 / 会话详情 chip），
 * 口径还不一致 —— 有的地方显示原始文本「明天15:00」（用户看不出那是哪天），
 * 有的地方把 S1 的桶码值翻成「本周内」（没有绝对日期，用户不知道具体哪天）。
 *
 * 六条契约：
 * 1. **绝对日期优先**：先算出目标日期，再和「今天」比 ——
 *    目标是今天 -> 「今天」，是明天 -> 「明天」，其余 -> 「M月d日」（跨年才带年份）。
 * 2. **文本 -> 毫秒只有一条路**：一律委托 [DueTimeCore.parse]（完整年月日 / M月d日 /
 *    今天明天后天 / 周X / 下午H点 / HH:mm），不另写解析器 —— 卡片上显示的日期必须和
 *    提醒链的实际落点一致。
 * 3. **相对词的锚点是「说话那一刻」**：模型写的「今天下午3点」按 `analyzedAt` 算；
 *    用户自己写的「明天15:00」按**编辑那一刻**算（`todoDueAt`）—— 否则「明天」会
 *    随时间漂移：今天看是明天、明天看又变成后天。
 * 4. **桶码值也能变绝对日期**：S1 的 today / tomorrow 按锚点推算，this_week 展开成
 *    那一周的区间（周一到周日）；later（更晚）没有锚点，原样保留。
 * 5. **时刻只在文本里写了才显示**（[DueTimeCore.hasExplicitTime]）：没写时刻就只显示
 *    日期 —— 默认 09:00 是解析兜底，不是用户的意思。
 * 6. **解析不出就原样显示**：不猜、不吞（模型自由输出也要让用户看见）。
 *
 * 与提醒链的关系：提醒链入参仍保持原始文本（[TopicLabelCore.displayDueCode]），
 * 本 Core 只管**显示**；两者共用 [DueTimeCore.parse]，所以「显示的日期」与「提醒落点」
 * 永远一致。
 */
internal object DueDisplayCore {

    /** 展示种类：今天 / 明天 / 绝对日期 / 更晚（无锚点）/ 自由文本 / 没有截止。 */
    const val KIND_TODAY = "today"
    const val KIND_TOMORROW = "tomorrow"
    const val KIND_DATE = "date"
    const val KIND_LATER = "later"
    const val KIND_TEXT = "text"
    const val KIND_NONE = "none"

    /**
     * 一次解析的结果。
     *
     * @param kind     展示种类（见 KIND_*）
     * @param dateText 绝对日期文本（「11月20日」/「2027年1月5日」/「11月16日-11月22日」）
     * @param timeText 时刻文本（「15:00」）；文本里没写时刻时为空串
     * @param rawText  解析不出的自由文本（kind = KIND_TEXT 时用）
     */
    data class Display(
        val kind: String,
        val dateText: String = "",
        val timeText: String = "",
        val rawText: String = ""
    )

    private val TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)

    /**
     * 展示标签的资源 id：今天 / 明天 / 更晚 / 无明确时间；
     * 绝对日期与自由文本没有资源（null）。
     */
    fun labelRes(display: Display): Int? = when (display.kind) {
        KIND_TODAY -> R.string.due_today
        KIND_TOMORROW -> R.string.due_tomorrow
        KIND_LATER -> R.string.due_later
        KIND_NONE -> R.string.due_none
        else -> null
    }

    /** 最终展示文本（标签走 Context.getString；单测走下面那个重载）。 */
    fun compose(context: Context, display: Display): String =
        compose(display) { resId -> context.getString(resId) }

    /** 最终展示文本：标签由 [labelRes] 决定，日期与时刻由 Core 拼。 */
    fun compose(display: Display, label: (Int) -> String): String {
        val res = labelRes(display)
        return when (display.kind) {
            KIND_DATE -> joinDateTime(display.dateText, display.timeText)
            KIND_TEXT -> display.rawText
            // 「今天 / 明天」这类标签也要带时刻：文本里写了 15:00 就不该只剩一个「今天」
            else -> joinDateTime(if (res != null) label(res) else "", display.timeText)
        }
    }

    /** 「11月20日 15:00」；没有时刻就只给日期。 */
    fun joinDateTime(dateText: String, timeText: String): String =
        if (timeText.isEmpty()) dateText else dateText + " " + timeText

    /**
     * 解析一段截止文本 -> 展示形态。
     *
     * @param raw      原始文本（用户覆盖值 / 模型 s2 due_time / S1 桶码值）
     * @param anchorMs 相对词的锚点（这句话被说出来的时刻）；<= 0 时用 nowMs
     * @param nowMs    「现在」，用来判断目标日期是不是今天 / 明天
     */
    fun resolve(
        raw: String?,
        anchorMs: Long,
        nowMs: Long,
        zone: ZoneId = ZoneId.systemDefault()
    ): Display {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return Display(KIND_NONE)
        val anchor = if (anchorMs > 0L) anchorMs else nowMs
        val nowDate = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val anchorDate = Instant.ofEpochMilli(anchor).atZone(zone).toLocalDate()
        when (TopicLabelCore.normalizeDue(text)) {
            TopicLabelCore.DUE_NONE -> return Display(KIND_NONE)
            TopicLabelCore.DUE_TODAY -> return dayKind(anchorDate, "", nowDate)
            TopicLabelCore.DUE_TOMORROW -> return dayKind(anchorDate.plusDays(1), "", nowDate)
            TopicLabelCore.DUE_THIS_WEEK -> return weekKind(anchorDate, nowDate)
            TopicLabelCore.DUE_LATER -> return Display(KIND_LATER)
        }
        val parsed = DueTimeCore.parse(text, anchor, zone)
            ?: return Display(KIND_TEXT, rawText = text)
        val target = Instant.ofEpochMilli(parsed).atZone(zone)
        val timeText =
            if (DueTimeCore.hasExplicitTime(text)) target.toLocalTime().format(TIME_FMT) else ""
        return dayKind(target.toLocalDate(), timeText, nowDate)
    }

    /**
     * 按记录的优先级选源再解析：手动覆盖 > 模型 s2 截止时间 > S1 截止桶。
     *
     * 锚点分工（契约第 3 条）：
     *  - 手动覆盖用**编辑那一刻**（todoDueAt）—— 用户写「明天」就是「写的那天」的明天；
     *  - 模型结论与 S1 桶用**分析那一刻**（analyzedAt）；
     *  - 锚点缺失（老记录没有这些字段）一律回落 nowMs。
     */
    fun resolveOf(
        todoDue: String?,
        todoDueAt: Long,
        s2DueTime: String?,
        s1DueWindow: String?,
        analyzedAt: Long,
        nowMs: Long,
        zone: ZoneId = ZoneId.systemDefault()
    ): Display {
        val edited = todoDue?.trim().orEmpty()
        if (edited.isNotEmpty()) {
            return resolve(edited, anchorOf(todoDueAt, analyzedAt, nowMs), nowMs, zone)
        }
        val concrete = s2DueTime?.trim().orEmpty()
        if (concrete.isNotEmpty()) {
            return resolve(concrete, anchorOf(0L, analyzedAt, nowMs), nowMs, zone)
        }
        val window = s1DueWindow?.trim().orEmpty()
        if (window.isEmpty()) return Display(KIND_NONE)
        return resolve(window, anchorOf(0L, analyzedAt, nowMs), nowMs, zone)
    }

    private fun anchorOf(primary: Long, secondary: Long, nowMs: Long): Long =
        if (primary > 0L) primary else if (secondary > 0L) secondary else nowMs

    /** 目标日期与「今天 / 明天」的关系决定展示形态。 */
    private fun dayKind(date: LocalDate, timeText: String, nowDate: LocalDate): Display = when {
        date == nowDate -> Display(KIND_TODAY, timeText = timeText)
        date == nowDate.plusDays(1) -> Display(KIND_TOMORROW, timeText = timeText)
        else -> Display(KIND_DATE, dateText = dateText(date, nowDate.year), timeText = timeText)
    }

    /** 本周桶展开成那一周的区间（周一到周日）。 */
    private fun weekKind(anchor: LocalDate, nowDate: LocalDate): Display {
        val start = anchor.minusDays((anchor.dayOfWeek.value - 1).toLong())
        val end = start.plusDays(6)
        return Display(
            KIND_DATE,
            dateText = dateText(start, nowDate.year) + "-" + dateText(end, nowDate.year)
        )
    }

    /** 日期文本：同年只写「M月d日」，跨年才带年份（否则用户对不上是哪一年）。 */
    fun dateText(date: LocalDate, nowYear: Int): String =
        if (date.year == nowYear) {
            "${date.monthValue}月${date.dayOfMonth}日"
        } else {
            "${date.year}年${date.monthValue}月${date.dayOfMonth}日"
        }
}
