// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 界面与模型输入里的**数字 / 短时间戳文本**口径（恒 ASCII：数字系统与小数点不随系统语言变）。
 *
 * 这不是风格问题，是正确性问题。实测（JDK 17 / Android API 26+，同一毫秒 1794478200000
 * = 2026-11-12 18:10 +08:00）：
 *
 *  | 默认 Locale | `SimpleDateFormat("MM-dd HH:mm", getDefault())` | `"%.2f".format(0.8)` |
 *  |---|---|---|
 *  | zh-CN / th-TH / ja-JP | `11-12 18:10` | `0.80` |
 *  | de-DE / fr-FR | `11-12 18:10` | `0,80`（小数点是逗号） |
 *  | ar-EG / ar-SA / fa-IR / bn-BD / ne-NP | `١١-١٢ ١٨:١٠`（本地数字） | `٠٫٨٠`（本地数字 + 本地小数点） |
 *
 * 三个后果：
 *  - 顾问复盘摘要（[AdvisorDigestCore.digest]）的 `need_action=0.80 importance=7.0` 在 de-DE 下变成
 *    `need_action=0,80 importance=7,0` —— 逗号与行内 `,` 分隔混同，模型要解析的字段结构被破坏；
 *    ar-EG 下整行是阿拉伯数字，与中文行文混排。这份摘要**是模型输入**，不能随手机语言变形。
 *  - 顾问报告 / 评测报告（[S1Agreement]）的 Markdown 表格里出现 `0,80` / `٠٫٨٠`，复制进 docs 直接坏掉。
 *  - 首页 / 详情页 / 提醒列表的时间列在阿拉伯语设备上渲染成本地数字，而 App 界面本身只有中文
 *    （`res/values` 没有其他语言），中英混排里夹一串阿拉伯-印度数字既不可读也无法被用户复述。
 *
 * 与之相邻但**不需要**改的：
 *  - `"%02x".format(it)`（hex 摘要：盐编码、别名、模型校验和）—— 十六进制不受 Locale 影响，实测恒 `ab`。
 *  - `AnalysisCase` 里已显式钉 `Locale.US` / `Locale.CHINESE` 的题面时间 —— 钉了就安全（实测跨 Locale 恒
 *    `2026-11-12 18:10`），不要再动。
 *  - `DateKeyCore` / [DueTimeCore] 的「年-月-日 ↔ 毫秒」往返 —— 它们已经走 `java.time` + 公历，见各自条目。
 *
 * 五条契约：
 *  ① 时间戳格式化只走 [minutes] / [seconds] / [hourMinute]，`DateTimeFormatter.ofPattern(pattern, Locale.ROOT)`；
 *     不许再在 Activity / Adapter 里 new `SimpleDateFormat(_, Locale.getDefault())`。
 *  ② 小数只走 [fixed] / [percent] / [bytes]，一律 `String.format(Locale.ROOT, ...)`。
 *  ③ `Locale.ROOT` 只影响**数字形态**，不影响时区：`zone` 缺省仍是 `ZoneId.systemDefault()`，
 *     「用户看到的是本地时间」这条语义不变。
 *  ④ 时间戳 `<= 0`（未标注）不在这里兜底 —— 调用方各自保留「—」/「无时间」文案，Core 只做格式化。
 *  ⑤ 别把这条口径推广到 `getString(R.string.x, 数字)`：资源里的 `%1$d` 走 `Configuration` 的 Locale，
 *     要固定得先改资源调用方式，属于另一件事（当前 UI 只有中文，暂不动）。
 */
internal object LocaleTextCore {

    const val MINUTE_PATTERN = "MM-dd HH:mm"
    const val SECOND_PATTERN = "MM-dd HH:mm:ss"
    const val HOUR_PATTERN = "HH:mm"

    private fun formatter(pattern: String): DateTimeFormatter =
        DateTimeFormatter.ofPattern(pattern, Locale.ROOT)

    private fun format(ms: Long, pattern: String, zone: ZoneId): String =
        formatter(pattern).format(Instant.ofEpochMilli(ms).atZone(zone))

    /** `MM-dd HH:mm`（消息日期分隔行、提醒事件时间、顾问摘要行）。 */
    fun minutes(ms: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        format(ms, MINUTE_PATTERN, zone)

    /** `MM-dd HH:mm:ss`（列表时间列、状态行「上次成功」）。 */
    fun seconds(ms: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        format(ms, SECOND_PATTERN, zone)

    /** `HH:mm`（今天/下次运行时刻，只出现在同一天语境）。 */
    fun hourMinute(ms: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        format(ms, HOUR_PATTERN, zone)

    /** 定点小数，恒用 `.`；`digits <= 0` 时退化成整数。 */
    fun fixed(value: Double, digits: Int): String {
        if (digits <= 0) return String.format(Locale.ROOT, "%.0f", value)
        return String.format(Locale.ROOT, "%.${digits}f", value)
    }

    /** `1.5 KB` / `12.3 MB` / `1.20 GB`（十进制单位，与 [LocalModelStore] 同口径）。 */
    fun bytes(bytes: Long): String = when {
        bytes >= 1_000_000_000L -> fixed(bytes / 1_000_000_000.0, 2) + " GB"
        bytes >= 1_000_000L -> fixed(bytes / 1_000_000.0, 1) + " MB"
        else -> fixed(bytes / 1_000.0, 0) + " KB"
    }

    /** 二进制单位（1024 进制）的字节数：`0 B` / `1.0 KB` / `1.00 MB`（诊断页口径）。 */
    fun binaryBytes(bytes: Long): String = when {
        bytes < 1024L -> "$bytes B"
        bytes < 1024L * 1024L -> fixed(bytes / 1024.0, 1) + " KB"
        else -> fixed(bytes / (1024.0 * 1024.0), 2) + " MB"
    }

    /** `75.0%`（只出数字与 `%`，不带中文前缀）。 */
    fun percent(value: Double, digits: Int = 1): String = fixed(value * 100, digits) + "%"
}
