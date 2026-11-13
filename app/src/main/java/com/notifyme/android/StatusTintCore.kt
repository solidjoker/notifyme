// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

/**
 * 状态行文字着色的判定口径（纯函数，只回资源 id，不碰 View）。
 *
 * 为什么抽：`AnalysisSettingsActivity` 与 `CaptureActivity` 各写了一份**逐字相同**的
 * `private fun tintStatus(view: TextView, result: String)`（11 行，连注释都一样），
 * 一共 9 个调用点。两份同时存在意味着「换个措辞判定」必须两处一起改 ——
 * 漏一处就是同一句「失败：…」在分析页是红的、在采集页还是灰的。
 *
 * 四条契约：
 * ① **错误优先于成功**：`失败` / `鉴权` 先判，再看 `成功` / `完成`。顺序不能反 ——
 *    `SyncWorker` 的失败文案形如 `失败：消息失败：…`，但也存在 `成功：轮询 3 次 + 鉴权失败(401)`
 *    这类「半成功」文本，取红更符合用户对「有事要处理」的预期。
 * ② **判定面只有四个词**，不做正则、不认标点：全仓 UI 层已核实无「含『成功』但实际失败」的
 *    反例（`未成功` / `未完成` / `待完成` 在 UI 文案里 0 命中，只有注释里有）。新增状态文案
 *    若带「成功」二字却表示失败，必须先改文案，别在这里加否定词表 —— 那会让判定变成猜。
 * ③ **`跳过` / `已中断` / 空串一律灰**：它们落到 else 分支。`已中断：完成 N 个会话，等待重试续跑`
 *    含「完成」故判绿，这是索引前就有的行为，本次重构**刻意保持不变**。
 * ④ **只回资源 id**：`getColor(...)` 与 `setTextColor(...)` 留在 Activity ——
 *    纯 JVM 单测没有 `Context`，Core 里出现 `Context` 就只能靠 Robolectric 测（本仓库不用）。
 *
 * 刻意不做的事：不接管「待办数为 0 才灰、有值才橙」这类**语义化着色**（`AnalysisSettingsActivity`
 * 的 `tvAnalysisStats`、`CaptureActivity` 的权限三态）—— 它们判的是状态字段而不是文案字符串，
 * 硬套这份口径会把「已授权」染成绿、把「未授权」染成灰，反而丢信息。
 */
internal object StatusTintCore {

    /** 失败类关键词（任一命中即判红）。 */
    private val ERROR_WORDS = arrayOf("失败", "鉴权")

    /** 成功类关键词（任一命中即判绿；仅在没命中失败类时生效）。 */
    private val OK_WORDS = arrayOf("成功", "完成")

    /**
     * 状态文案 → 颜色资源 id。
     *
     * `result` 为空串 / 纯空白时同样走 else 返 [R.color.status_gray]。
     */
    fun colorRes(result: String): Int {
        if (ERROR_WORDS.any { result.contains(it) }) return R.color.status_error
        if (OK_WORDS.any { result.contains(it) }) return R.color.status_ok
        return R.color.status_gray
    }
}
