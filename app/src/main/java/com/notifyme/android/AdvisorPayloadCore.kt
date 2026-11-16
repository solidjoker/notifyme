// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import org.json.JSONArray
import org.json.JSONObject

/**
 * 顾问报告的**上传口径唯一出口**。
 *
 * 以前这份 JSON 写了两遍：
 *  1. `AdvisorReport.toUploadJson()`（不脱敏分支）；
 *  2. `SyncWorker.pushAdvisor` 的脱敏分支（手写第二份同结构 JSON）。
 * 两份各写各的键名，任一处加字段 / 改字段名就漂移，而且漂移是**静默**的：
 * 服务端只认自己那几把钥匙，多出来的键直接丢掉，控制台看不出「这字段没了」。
 *
 * 现在两条分支都走这里：
 *  1. 先（可选）把报告脱敏成一份新 report；
 *  2. 再由 [uploadJson] 出**唯一**一份上传 JSON。
 *
 * 三条契约：
 *  1. **顶层键恰好七个**（服务端 `storage._normalize_advisor_item` 认识的全部）：
 *     report_id / created_at / period_days / case_count / summary / suggestions / model_used。
 *     本地存档另带的 rejected_count / first_rejected_at / quarantined
 *     是端侧自己的账，**绝不出设备**。
 *  2. **长度不在这儿裁**：服务端是唯一的裁剪口径（存档保护），截断时由服务端
 *     落 `truncated` 标记，控制台据此显示「已截断」。端上再裁一遍就会出现
 *     「谁截的、截了多少」两套口径。
 *  3. **脱敏只碰正文**：summary / suggestion.title / suggestion.detail 走回调，
 *     type / model_used / report_id / created_at 是结构字段，原样保留。
 */
internal object AdvisorPayloadCore {

    /** 上传体顶层键（顺序固定；服务端按名取，钉死顺序只为比对方便）。 */
    val UPLOAD_KEYS: List<String> = listOf(
        "report_id",
        "created_at",
        "period_days",
        "case_count",
        "summary",
        "suggestions",
        "model_used"
    )

    /** suggestion 的子键（服务端只认这三个）。 */
    val SUGGESTION_KEYS: List<String> = listOf("type", "title", "detail")

    /**
     * 构造一份报告的上传 JSON。
     *
     * @param report 报告（脱敏与否都行）
     * @param redact 正文脱敏回调；null = 不脱敏。回调只作用于
     *   summary / suggestion.title / suggestion.detail。
     */
    fun uploadJson(report: AdvisorReport, redact: ((String) -> String)? = null): JSONObject {
        val text: (String) -> String = { raw -> redact?.invoke(raw) ?: raw }
        return JSONObject().apply {
            put("report_id", report.reportId)
            put("created_at", report.createdAt)
            put("period_days", report.periodDays)
            put("case_count", report.caseCount)
            put("summary", text(report.summary))
            put("suggestions", JSONArray().apply {
                report.suggestions.forEach { s ->
                    put(JSONObject().apply {
                        put("type", s.type)
                        put("title", text(s.title))
                        put("detail", text(s.detail))
                    })
                }
            })
            put("model_used", report.modelUsed)
        }
    }

    /** 顶层键集合（自检用：上传体不该多出任何键）。 */
    fun keysOf(json: JSONObject): Set<String> {
        val out = LinkedHashSet<String>()
        val it = json.keys()
        while (it.hasNext()) out.add(it.next())
        return out
    }
}
