// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 顾问复盘 Worker（Fable 层）：通读最近 7 天的 case 分析结果，
 * 调当前 System 2 服务商（GLM/JEV，OpenAI 兼容通道）生成调优建议报告。
 *
 * 触发方式：
 *  - 控制台「顾问复盘」按钮（AdvisorScheduler.enqueueAdvisorNow，手动）；
 *  - 每 7 天周期任务（AdvisorScheduler.schedule，开关默认开）。
 *
 * 输入摘要做了截断防爆 token：最多取 50 个 case、每会话摘要截 30 字。
 * 输出契约：JSON {summary, suggestions:[{type:"prompt"|"watchlist"|"threshold"|"other",
 * title, detail}]}，防御式解析（第一个 { 到最后一个 }，字段缺失兜底）。
 *
 * 失败语义：网络/HTTP 失败记结果并 retry；未配置 S2 / 无 case 属用户态，
 * 记结果后直接 success 不重试。顾问复盘是纯增量功能，任何失败都不影响主分析链路。
 */
class AdvisorWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "AdvisorWorker"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /** 复盘窗口：最近 7 天 */
        private const val PERIOD_DAYS = 7

        /** 输入摘要最多携带的 case 数（防爆 token） */
        private const val MAX_CASES_IN_PROMPT = 50

        /** 每条 case 摘要的截断长度 */
        private const val SUMMARY_TRUNCATE = 30

        private val client: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            // 推理模型（glm-5.3 等）reasoning + 4096 token 生成可能超过 60s，
            // 周频后台任务放宽到 180s
            .readTimeout(180, TimeUnit.SECONDS)
            .build()

        /** 顾问 system prompt：角色 + 严格 JSON 输出契约 */
        private const val ADVISOR_SYSTEM_PROMPT =
            "你是微信监控分析系统的顾问。系统用一个两级流水线分析用户的企业微信会话：" +
                "System 1 轻量判定（need_action 概率、importance 0-9、due_window、topic），" +
                "满足升级条件（need_action≥0.5 / importance≥6 / 置信度<0.5）的会话进入 " +
                "System 2 深分析（摘要与任务清单）；另有一层本地预筛直接过滤垃圾消息。" +
                "用户会给你一批最近的分析结果摘要。请通读这批结果，评估分析质量，" +
                "给出可操作的调优建议（例如：某类会话应加自定义提示词、某些会话值得加入/移出重点关注、" +
                "升级阈值是否过松或过紧、哪类消息被误判）。" +
                "只输出一个 JSON 对象，不要输出任何其他文字：\n" +
                "{\"summary\": \"≤80字的整体复盘\", " +
                "\"suggestions\": [{\"type\": \"prompt|watchlist|threshold|other\", " +
                "\"title\": \"≤15字的建议标题\", \"detail\": \"≤60字的具体建议\"}]}"
    }

    private class HttpException(val code: Int, val body: String = "") : Exception("HTTP $code")

    override suspend fun doWork(): Result {
        val config = AnalysisConfig(applicationContext)

        // 隐私模式（M3）：本地模式禁止任何数据出端，顾问复盘走云端，整条链路直接跳过。
        // 必须放在最前——否则未配置 S2 时会先报「未配置」，掩盖真实原因。
        val privacy = PrivacyConfig.get(applicationContext)
        if (privacy.localOnly) {
            AdvisorStore.recordResult(applicationContext, "跳过：当前为本地模式，顾问复盘不出端")
            Log.i(TAG, "顾问复盘跳过：隐私模式=本地")
            return Result.success()
        }

        // 顾问复用当前 S2 服务商（GLM/JEV，OpenAI 兼容通道）；
        // S2 开关关闭或槽位未配置时无法复盘——用户态，不重试
        val slot = config.s2Slot()
        if (slot == null) {
            AdvisorStore.recordResult(applicationContext, "失败：未启用或未配置 System 2 服务")
            Log.i(TAG, "顾问复盘跳过：System 2 未配置")
            return Result.success()
        }

        val since = System.currentTimeMillis() - PERIOD_DAYS * 24L * 3600_000L
        val cases = AnalysisStore.readRecent(applicationContext, 500)
            .filter { it.analyzedAt >= since }
            .sortedBy { it.analyzedAt }
        if (cases.isEmpty()) {
            AdvisorStore.recordResult(applicationContext, "成功：近 $PERIOD_DAYS 天无分析记录，未生成报告")
            Log.i(TAG, "顾问复盘跳过：窗口内无 case")
            return Result.success()
        }

        return try {
            val raw = postChatCompletions(slot, buildDigest(cases, privacy))
            val report = parseReport(raw, cases.size, slot.model)
            AdvisorStore.append(applicationContext, report)
            AdvisorStore.recordResult(
                applicationContext,
                "成功：复盘 ${cases.size} 个 case，${report.suggestions.size} 条建议"
            )
            Log.i(
                TAG,
                "顾问报告已生成: ${report.reportId} (${cases.size} case, " +
                    "${report.suggestions.size} 建议, 模型 ${slot.model})"
            )
            Result.success()
        } catch (e: IOException) {
            AdvisorStore.recordResult(applicationContext, "失败：网络错误 ${e.message ?: "未知"}")
            Log.w(TAG, "顾问复盘失败: 网络错误", e)
            Result.retry()
        } catch (e: HttpException) {
            val detail = if (e.body.isBlank()) "" else " ${e.body.take(120)}"
            AdvisorStore.recordResult(applicationContext, "失败：HTTP ${e.code}$detail")
            Log.w(TAG, "顾问复盘失败: HTTP ${e.code}$detail")
            Result.retry()
        }
    }

    /**
     * 组装 case 摘要（输入防爆 token）：
     * 最新 50 个 case，每行一条——会话名、S1 判定、是否升级/被预筛、S2 摘要与任务数。
     */
    private fun buildDigest(cases: List<AnalysisCaseRecord>, privacy: PrivacyModeState): String {
        val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        val sb = StringBuilder("最近 $PERIOD_DAYS 天共 ${cases.size} 个分析 case：\n")
        // 出设备脱敏（M3）：云端可见的会话名/主题/摘要先过脱敏器；本地模式已在上游拦截。
        // caseId 与时间戳不含正文，保留原样以便与服务端分析记录对应。
        val core = if (privacy.cloudRedact) RedactorCore(privacy.enabledRules) else null
        cases.takeLast(MAX_CASES_IN_PROMPT).forEach { rec ->
            val conversation = core?.redact(rec.conversation)?.text ?: rec.conversation
            val topic = core?.redact(rec.s1Topic)?.text ?: rec.s1Topic
            val summary = core?.redact(rec.s2Summary.replace("\n", " "))?.text
                ?: rec.s2Summary.replace("\n", " ")
            sb.append("- 「").append(conversation.take(20)).append("」")
                .append(" ").append(fmt.format(Date(rec.analyzedAt)))
            if (rec.filtered) {
                sb.append(" 预筛过滤(prob=%.2f)".format(rec.s1NeedActionProb))
            } else {
                sb.append(" need_action=%.2f".format(rec.s1NeedActionProb))
                    .append(" importance=%.1f".format(rec.s1Importance))
                    .append(" topic=").append(topic)
                if (rec.escalated) {
                    sb.append(" 已深分析 摘要=")
                        .append(summary.take(SUMMARY_TRUNCATE))
                        .append(" 任务数=").append(rec.s2Tasks.size)
                }
            }
            sb.append("\n")
        }
        if (cases.size > MAX_CASES_IN_PROMPT) {
            sb.append("（其余 ${cases.size - MAX_CASES_IN_PROMPT} 个较早 case 略）\n")
        }
        return sb.toString()
    }

    /** 防御式解析顾问输出：第一个 { 到最后一个 }；字段缺失兜底；解析全崩也给兜底报告。 */
    private fun parseReport(raw: String, caseCount: Int, model: String): AdvisorReport {
        var summary = ""
        val suggestions = mutableListOf<AdvisorReport.Suggestion>()
        try {
            val content = JSONObject(raw)
                .optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content").orEmpty()
            val start = content.indexOf('{')
            val end = content.lastIndexOf('}')
            val payload = if (start >= 0 && end > start) {
                JSONObject(content.substring(start, end + 1))
            } else null
            val answer = payload?.optJSONObject("answer") ?: payload
            if (answer != null) {
                summary = answer.optString("summary", "").trim()
                answer.optJSONArray("suggestions")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        arr.optJSONObject(i)?.let {
                            suggestions.add(AdvisorReport.Suggestion.fromJson(it))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "顾问响应解析失败，保留兜底摘要", e)
        }
        // 摘要兜底：模型没给有效 JSON 时不至于空报告
        if (summary.isEmpty()) {
            summary = "顾问响应未能解析为结构化报告（原始输出已截断保留）。"
            if (suggestions.isEmpty()) {
                suggestions += AdvisorReport.Suggestion(
                    "other", "检查顾问输出", "本次顾问模型输出非 JSON，可查看日志或换个 S2 模型重试"
                )
            }
        }
        return AdvisorReport(
            reportId = UUID.randomUUID().toString(),
            createdAt = System.currentTimeMillis(),
            periodDays = PERIOD_DAYS,
            caseCount = caseCount,
            summary = summary,
            suggestions = suggestions,
            modelUsed = model
        )
    }

    /** POST {url}/chat/completions（OpenAI 兼容），返回响应原文；非 2xx 抛 HttpException。 */
    private fun postChatCompletions(slot: AnalysisConfig.SlotConfig, userContent: String): String {
        var url = slot.url.trim().trimEnd('/')
        if (!url.endsWith("/chat/completions")) url += "/chat/completions"

        val requestJson = JSONObject().apply {
            put("model", slot.model)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", ADVISOR_SYSTEM_PROMPT)
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", userContent)
                })
            })
            put("response_format", JSONObject().put("type", "json_object"))
            // glm-5.3 等推理模型的 reasoning_tokens 也占 max_tokens 额度，
            // 给太小会把正文顶空（实测 1500 下 content 为空串）
            put("max_tokens", 4096)
            put("stream", false)
        }

        val request = Request.Builder()
            .url(url)
            .post(requestJson.toString().toRequestBody(JSON_MEDIA_TYPE))
            .apply {
                if (slot.key.isNotEmpty()) {
                    header("Authorization", "Bearer ${slot.key}")
                }
            }
            .build()

        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw HttpException(
                response.code, response.body?.string().orEmpty()
            )
            response.body?.string().orEmpty()
        }
    }
}
