// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * **提示词与报告里的绝对时间文本**口径（模型输入侧）：恒公历、恒 24 小时制，只有星期名走目标语言。
 *
 * 这不是风格问题，是正确性问题。此前这 7 处时间文本用 `SimpleDateFormat(pattern, Locale.CHINESE / Locale.US)`
 * + `Date(ms)`，并且**依赖一个没写进文档的巧合**：`SimpleDateFormat` 的历法取自传入 Locale 的 `ca` 扩展，
 * 没有扩展时回落到 `Calendar.getInstance(locale)` 的默认历法 —— `zh` / `en_US` 在那张表里恰好是公历。
 * 实测 JDK 17（`-Duser.language=th -Duser.country=TH -Duser.timezone=Asia/Bangkok`，同一毫秒
 * `1792318140000` = 2026-10-18 17:09 +07:00）：
 *
 *  | 写法 | 输出 |
 *  |---|---|
 *  | `SimpleDateFormat("yyyy", Locale.US)` | `2026`（安全，但只是因为 en_US 映射到公历） |
 *  | `SimpleDateFormat("yyyy", Locale.getDefault())`（th_TH） | `2569` |
 *  | `Calendar.getInstance().apply { set(2026, 9, 25) }`（th_TH） | 佛历 2026 年 = 公历 **1483** |
 *  | `Calendar.getInstance().get(YEAR)`（th_TH） | `2569` |
 *  | `DateTimeFormatter.ofPattern("yyyy", 任意 Locale)` | `2026` |
 *
 * 三条后果（第三条是本轮实测到的真实故障）：
 *  - 「钉 Locale 安全」靠的是 Locale→calendar 映射表里 zh/en 恰为公历，换个 Locale（`th_TH`）就崩，
 *    属于隐式依赖，一旦有人在新增调用点传 `Locale.getDefault()` 立刻变成生产事故：题面写
 *    `当前时间：2569-10-18`，模型把「明天」「本周五」全按佛历推，`due_window` 判桶直接错。
 *  - 「同年省略年份」曾被写成「比较格式化后的 `yyyy` 字符串」：当前 `Locale.US` 恰好映射公历所以没炸，
 *    但这是同一条隐式依赖 —— 一旦有人把 locale 换成 `Locale.getDefault()`（th_TH → `2569`），
 *    同一毫秒两年份分叉，等于永远判跨年、每条消息都写成 `[yyyy-MM-dd HH:mm]`。已改为 `year` 数值比较。
 *  - **单测夹具**用 `Calendar.getInstance()` 造基准时刻与期望值：佛历环境下 `set(2026, 9, 25)` 得到
 *    公历 1483 年，`AnalysisCaseTest` 5 条 + `CalendarDueTimeTest` 5 条全红在夹具上（代码是对的、
 *    测试是错的），等于把「换个系统语言就红」的脆弱性留在了门禁里。
 *
 * 五条契约：
 *  ① 时间点一律 `Instant.ofEpochMilli(ms).atZone(zone).format(...)`：**不许**再 new `SimpleDateFormat`
 *     （历法跟 Locale 的 `ca` 扩展走），**不许** `Calendar.getInstance()`（佛历设备上 `YEAR` 是 2569）；
 *  ② Locale 只用 `Locale.CHINESE`（中文星期）/ `Locale.US`（英文星期），它们**只决定星期名与词序**，
 *     年份永远是 ISO 公历年 —— `java.time` 的 `yyyy` 不随 Locale 历法变化；
 *  ③ `zone` 缺省 `ZoneId.systemDefault()`（用户看到的是本地时间），但每个函数都可注入：
 *     单测必须钉时区（CI runner 是 UTC、本机 +08，不钉就 `expected:<18:10> but was:<10:10>`）；
 *  ④ `messageLabel` 的「同年」按 `ZonedDateTime.year` **数值**比较，不许比较格式化后的字符串；
 *  ⑤ 输出文案逐字保持旧样（`当前时间：` / `今天是 …。` / `Today is ….` / `[… ]`）——
 *     它们是**提示词契约**，改一个字都等于换模型输入。
 *
 * 与相邻口径的分界：[LocaleTextCore] 管「界面上的数字与短时间戳」（`MM-dd HH:mm` 等），
 * [DueTimeCore] 管「日期文本 ↔ 毫秒」的往返，这里只管「喂给模型的绝对时间锚点」与报告抬头，
 * 三者不互相转发。改前跑 `PromptTimeCoreTest`（含 th-TH / 和历回归）。
 */
internal object PromptTimeCore {

    /** 中文星期缩写（`E`）+ 年月日时分：`2026-10-18 周六 17:09`。 */
    const val HEADER_PATTERN = "yyyy-MM-dd E HH:mm"

    /** 中文「今天」行：`2026年10月18日 星期日 17:09`。 */
    const val TODAY_CN_PATTERN = "yyyy年M月d日 EEEE HH:mm"

    /** 英文「今天」行：`Sunday, 2026-10-18, current time 17:09`。 */
    const val TODAY_EN_PATTERN = "EEEE, yyyy-MM-dd, 'current time' HH:mm"

    /** due_window 题面的英文锚点：`Sunday 2026-10-18 17:09`。 */
    const val DUE_ANCHOR_PATTERN = "EEEE yyyy-MM-dd HH:mm"

    /** 同年省略年份的消息时间标签：`MM-dd HH:mm`。 */
    const val LABEL_SHORT_PATTERN = "MM-dd HH:mm"

    /** 跨年带全年份的消息时间标签：`yyyy-MM-dd HH:mm`。 */
    const val LABEL_FULL_PATTERN = "yyyy-MM-dd HH:mm"

    /** 报告抬头用的 ISO-8601 带偏移时间戳：`2026-11-13T21:49:38+08:00`。 */
    const val ISO_PATTERN = "yyyy-MM-dd'T'HH:mm:ssXXX"

    private fun format(ms: Long, pattern: String, locale: Locale, zone: ZoneId): String =
        DateTimeFormatter.ofPattern(pattern, locale).format(Instant.ofEpochMilli(ms).atZone(zone))

    /** `2026-10-18 周六 17:09`（中文星期，供 `当前时间：` 行）。 */
    fun chineseStamp(ms: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        format(ms, HEADER_PATTERN, Locale.CHINESE, zone)

    /** `2026年10月18日 星期日 17:09`（`今天是 …。` 的主体）。 */
    fun todayCn(ms: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        format(ms, TODAY_CN_PATTERN, Locale.CHINESE, zone)

    /** `Sunday, 2026-10-18, current time 17:09`（英文 S1 题面）。 */
    fun todayEn(ms: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        format(ms, TODAY_EN_PATTERN, Locale.US, zone)

    /** `Sunday 2026-10-18 17:09`（due_window 题面的英文锚点）。 */
    fun dueAnchor(ms: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        format(ms, DUE_ANCHOR_PATTERN, Locale.US, zone)

    /** ISO-8601 带偏移：`2026-11-13T21:49:38+08:00`（评测报告抬头）。 */
    fun isoStamp(ms: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        format(ms, ISO_PATTERN, Locale.US, zone)

    /**
     * 单条消息时间标签：同年省略年份 `[MM-dd HH:mm]`，跨年带全年份 `[yyyy-MM-dd HH:mm]`。
     *
     * 同年判定按 `ZonedDateTime.year` **数值**比较（不是格式化后的 `yyyy` 字符串）：
     * 佛历/和历设备上同一毫秒的字符串年与公历年分叉，比较字符串等于永远判跨年。
     */
    fun messageLabel(timestamp: Long, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val at = Instant.ofEpochMilli(timestamp).atZone(zone)
        val nowYear = Instant.ofEpochMilli(nowMs).atZone(zone).year
        val pattern = if (at.year == nowYear) LABEL_SHORT_PATTERN else LABEL_FULL_PATTERN
        return "[" + at.format(DateTimeFormatter.ofPattern(pattern, Locale.US)) + "]"
    }
}
