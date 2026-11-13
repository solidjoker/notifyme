// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.regex.Pattern

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
 *  ⑤ 资源字符串里的格式槽位走 `Context.templateText(id, ...)`（= [format]）而不是 `getString(id, 数字)`：
 *     `Resources.getString` 带参走 `String.format(Configuration.locale)`，ar-EG 下 `%1$d` 就是 `١٠`、
 *     `%1$.1f` 就是 `٢٫٠`（真机实测）；[format] 用 `Locale.ROOT` 语义自己代入，数字与小数点恒 ASCII。
 *     模板本身不改（`%1$d` / `%1$.1f` 保留精度意图），只换调用方式。
 *  ⑥ [format] 只认 `%N$d` / `%N$.Mf` / `%N$s` 与 `%%`：悬空 `%`、未知占位、缺参都原样/空串降级 ——
 *     宁可少一个数也不要抛异常把整条文案吞掉。
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

    /** 资源模板里的一个槽位：`%N$d` / `%N$.Mf` / `%N$s`（N 从 1 起，精度可选）。 */
    private val SLOT = Pattern.compile("%(\\d+)\\$(?:\\.(\\d+))?([dfs])")

    /**
     * 资源模板 → 文本：`%N$` 用 [args] 的第 N 个（从 1 起）顶替，`%%` 恒字面 `%`。
     *
     * 与 `String.format` 的三处差别，都是为了「数字不随系统语言变形」：
     *  - 固定 `Locale.ROOT`（`String.format` 吃 `Configuration.locale`，ar-EG 下出 `٢٫٠`）；
     *  - `%N$d` 吃任何 `Number`（`String.format` 对 `Double` 抛 `IllegalFormatConversionException`）；
     *  - 悬空 `%` / 未知占位 / 缺参一律降级，不抛异常。
     */
    fun format(template: String, args: List<Any?>): String {
        if (template.indexOf('%') < 0) return template
        val m = SLOT.matcher(template)
        val sb = StringBuilder(template.length + 16)
        var i = 0
        while (i < template.length) {
            if (template[i] != '%' || i + 1 >= template.length) {
                sb.append(template[i])
                i++
                continue
            }
            if (template[i + 1] == '%') {
                sb.append('%')
                i += 2
                continue
            }
            m.region(i, template.length)
            if (!m.lookingAt()) {   // 悬空 `%` 或未知占位：原样保留
                sb.append(template[i])
                i++
                continue
            }
            val n = m.group(1)?.toIntOrNull() ?: 0
            val digits = m.group(2)?.toIntOrNull()
            sb.append(render(args.getOrNull(n - 1), m.group(3) ?: "s", digits))
            i = m.end()
        }
        return sb.toString()
    }

    private fun render(arg: Any?, conv: String, digits: Int?): String = when (conv) {
        "d" -> when (arg) {
            is Int -> arg.toString()
            is Long -> arg.toString()
            is Number -> arg.toLong().toString()
            else -> arg?.toString().orEmpty()
        }
        "f" -> if (arg is Number) fixed(arg.toDouble(), digits ?: 6) else arg?.toString().orEmpty()
        else -> arg?.toString().orEmpty()
    }

    /** 模板要几个槽位（= 出现过的最大 `%N$` 的 N）；资源契约测试用它。 */
    fun requiredArgs(template: String): Int {
        val m = SLOT.matcher(template)
        var max = 0
        while (m.find()) {
            val n = m.group(1)?.toIntOrNull() ?: continue
            if (n > max) max = n
        }
        return max
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
