// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

/**
 * 文本截断的**唯一口径**：按 Unicode 码点（code point）切，不按 UTF-16 单元切。
 *
 * 这不是风格问题，是正确性问题。Kotlin 的 `String.take(n)` / `substring(0, n)` 数的是
 * **UTF-16 code unit**，而 emoji / 部分生僻字（CJK 扩展 B 起）在 UTF-16 里占**两个** unit
 * （一个高代理 + 一个低代理）。截在中间就会留下一个孤立代理，实测（JDK 17）：
 *
 *  | 表达式 | 结果 |
 *  |---|---|
 *  | 输入 `"123456789012345😀xy"`（15 个 ASCII + 1 个 emoji + 2 个 ASCII，共 19 unit / 18 码点） | — |
 *  | `s.take(16)` | 末位是**高代理** `\uD83D`，转 UTF-8 得 `...3f`（`?`，替换字符） |
 *  | `TextClipCore.clip(s, 16)` | 完整 16 个码点（含整个 emoji），UTF-8 合法 |
 *
 * 落在这些位置上的文本会**出设备或被落盘**，一个 `?` 在中间就是可见的乱码：
 *  - [AdvisorDigestCore.digest] 的摘要正文、[AnalysisWorker] 的错误诊断与顾问请求体 —— **模型输入**，
 *    字段结构被一个替换字符打断，模型读到的是残字；
 *  - [QueueStateCore.normalize] 的标题 / 详情 —— 上行 JSON，服务端 SQLite 与 PWA 控制台照原样存显；
 *  - [CalendarHelper] 的日历事件标题、「采集诊断」文案、通知标题 —— 用户直接看到的串。
 *
 * 四条契约：
 *  ① [clip] 是唯一入口：`maxCodePoints <= 0` 返空串；码点数不超过上限时**原样返回同一个实例**
 *     （不复制、不加省略号）；超限时截到第 `maxCodePoints` 个码点的边界再追加省略号。
 *  ② **省略号是追加的，不占预算**：`clipWithEllipsis(s, 20)` 最多 21 个码点。这与旧的
 *     `s.take(20) + "…"` 逐字一致，改口径不能顺手改长度语义。
 *  ③ **ASCII 输入输出逐字节不变**：单 unit 字符的 `take(n)` 与 `clip(n)` 结果相同 ——
 *     所以这个口径替换是纯改进，历史文案、服务端契约、单测断言都不需要改。
 *  ④ **已知边界，不是 bug**：`clip` 只保证不切断代理对，**不保证**不切断字素簇 ——
 *     国旗（2 个码点）、带 ZWJ 的家族 emoji、带组合附加符号的字母都可能被切在码点边界上，
 *     结果是「半面旗」「半个家庭」而不是替换字符。真要按字素簇切得上 ICU 的
 *     `BreakIterator`，代价远大于收益；这里选择「宁可少画一个 emoji，绝不产出非法字符」。
 *
 * 刻意不套用的地方：hex 摘要的 `"%02x".format(...).take(16)`（[AnalysisCase] 的消息指纹）——
 * 那是 ASCII 十六进制，两个口径结果相同，套过来只会让人以为它也需要防代理对。
 */
internal object TextClipCore {

    /** 截断标记（省略号 U+2026，不是三个点）。 */
    const val ELLIPSIS = "…"

    /** 码点数（与 [String.length] 不同：emoji 算 1，不是 2）。 */
    fun codePoints(text: String): Int =
        if (text.isEmpty()) 0 else text.codePointCount(0, text.length)

    /**
     * 截到 [maxCodePoints] 个码点，超限时在末尾追加 [ellipsis]。
     *
     * [maxCodePoints] <= 0 返空串；未超限返回 [text] 本身（同一个实例）。
     */
    fun clip(text: String, maxCodePoints: Int, ellipsis: String = ""): String {
        if (maxCodePoints <= 0) return ""
        if (text.isEmpty()) return text
        if (text.codePointCount(0, text.length) <= maxCodePoints) return text
        val end = text.offsetByCodePoints(0, maxCodePoints)
        return text.substring(0, end) + ellipsis
    }

    /** [clip] 的省略号版本（`最多 maxCodePoints + 1 个码点`），对应旧的 `take(n) + "…"`。 */
    fun clipWithEllipsis(text: String, maxCodePoints: Int): String =
        clip(text, maxCodePoints, ELLIPSIS)
}
