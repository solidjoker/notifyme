// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 定时上报 Worker：三个通道依次执行——
 *  1. 消息：把 PendingQueue 里的待上报消息 POST 到 serverUrl；
 *  2. 分析结果：把 AnalysisStore 里未同步的 case 记录 POST 到
 *     {serverUrl 去末尾 /weixin}/analysis（与 /admin/extract 同款路径规则）；
 *  3. 顾问报告：把 AdvisorStore 里未同步的复盘报告 POST 到
 *     {serverUrl 去末尾 /weixin}/advisor（同款路径规则）。
 *
 * 行为约定：
 *  - serverUrl 为空（未配置）-> 直接 Result.success()，不产生网络请求；
 *  - 消息 body 为 JSON 数组，字段用蛇形命名（is_group）与 PC 端 Python 习惯对齐；
 *    分析 body 同为 JSON 数组（case_id 等 snake_case，s1/s2 嵌套对象，不带 raw_json）；
 *    JSON 拼接用平台内置 org.json（自带转义），不引 Gson/Moshi；
 *  - 消息 2xx -> 剔除已发条目 -> success；401 -> 鉴权失败 Result.failure() 终止；
 *    其它非 2xx 或 IOException -> Result.retry() 交给 WorkManager 退避重试；
 *  - 分析 2xx -> 推进已同步水位；404（旧服务端无端点）-> 静默跳过；
 *    其它失败 -> 只记文案，水位不动、不触发 Worker 重试，下轮自然补报；
 *  - 分析通道的任何结果都不改变消息通道的处置；两通道结果文案合并进 lastSyncResult
 *   （如「成功：消息 5 条 + 分析 2 个」）；
 *  - 网络约束（NetworkType.CONNECTED）在 SyncScheduler 建请求时声明；
 *  - authToken 非空时带 X-Token 请求头，与服务端 WEIXIN_TOKEN 环境变量对应。
 */
class SyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "SyncWorker"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /** connect/read 超时各 15s；Worker 频次低，共享一个 client 即可 */
        private val client: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    override suspend fun doWork(): Result {
        val config = SyncConfig(applicationContext)
        val url = config.serverUrl
        if (url.isBlank()) return Result.success()

        // 推送总开关关闭：消息与分析两个通道都不消耗，记录跳过状态让控制台可见。
        // pending.jsonl / 分析未同步水位继续保持，等用户重新开启后下个周期一并上报。
        if (!config.pushEnabled) {
            recordResult(config, "已跳过：推送开关已关闭（消息继续累积）")
            return Result.success()
        }

        // M3 隐私模式门控：
        //  - LOCAL_ONLY：消息/分析/顾问三通道整体不发送，队列与水位保持，
        //    模式切回后下个周期自然补报；
        //  - CLOUD_REDACT：各通道在发送一刻替换（见 push* 内部）。
        val privacy = PrivacyConfig.get(applicationContext)
        if (privacy.localOnly) {
            recordResult(config, "已跳过：仅端侧模式，云端上报整体停用（数据继续留在本机）")
            return Result.success()
        }

        val okParts = mutableListOf<String>()
        val extraParts = mutableListOf<String>()

        // ---- 第一步：消息上报（原链路，其结果决定 Worker 的 success/retry/failure） ----
        val msgResult = pushMessages(config, url, privacy, okParts, extraParts)

        // ---- 第二步：分析结果上报（独立通道，失败不影响消息通道已成功的状态，
        //      也不触发 WorkManager 重试——未推进水位，下轮自然补报） ----
        pushAnalysis(config, url, privacy, okParts, extraParts)

        // ---- 第三步：顾问报告上报（独立通道，语义与分析通道一致：
        //      404 静默跳过，失败不影响主通道、不触发重试，下轮自然补报） ----
        pushAdvisor(config, url, privacy, okParts, extraParts)

        val parts = mutableListOf<String>()
        if (okParts.isNotEmpty()) parts += "成功：" + okParts.joinToString(" + ")
        parts += extraParts
        // 两通道都无待报数据时 parts 为空——也要落一条结果，
        // 否则用户点「立即同步」后控制台状态行停留在上一轮，像没点上
        recordResult(config, if (parts.isEmpty()) "成功：无新数据" else parts.joinToString("；"))

        return msgResult
    }

    /**
     * 消息上报通道（原 doWork 主体逻辑，行为不变）：
     * 成功/失败描述写入 okParts/extraParts，返回值为 Worker 层面的处置
     * （success / retry / failure）。待上报队列为空时不发请求，返回 success。
     */
    private fun pushMessages(
        config: SyncConfig,
        url: String,
        privacy: PrivacyModeState,
        okParts: MutableList<String>,
        extraParts: MutableList<String>
    ): Result {
        val pending = PendingQueue.readAll(applicationContext)
        if (pending.isEmpty()) return Result.success()

        // M3 出设备脱敏：按会话分组替换消息文本/发送者/会话名；
        // PendingEntry.id 不在 message 内，removeSent 仍按原 id 剔除
        val outgoing = if (privacy.cloudRedact) {
            redactMessages(pending.map { it.message }, privacy.enabledRules)
        } else pending.map { it.message }

        val body = JSONArray().apply {
            outgoing.forEach { message ->
                put(message.toJson().apply {
                    // 上报协议用蛇形命名，移除本地存储的驼峰键
                    remove("isGroup")
                    put("is_group", message.isGroup)
                })
            }
        }.toString()

        val request = Request.Builder()
            .url(url)
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .apply {
                // 令牌非空时带 X-Token 头，与服务端 WEIXIN_TOKEN 鉴权对应
                val token = config.authToken
                if (token.isNotEmpty()) header("X-Token", token)
            }
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> {
                        // 只剔除本次实际发送的 id；上报期间新入队的消息保留
                        PendingQueue.removeSent(applicationContext, pending.map { it.id }.toSet())
                        okParts += "消息 ${pending.size} 条"
                        Log.i(TAG, "上报成功: ${pending.size} 条 -> $url (HTTP ${response.code})")
                        Result.success()
                    }
                    response.code == 401 -> {
                        // 鉴权失败属配置错误，重试无用，直接失败终止本次
                        extraParts += "消息鉴权失败(401)，请检查令牌"
                        Log.w(TAG, "上报失败: 401 鉴权失败")
                        Result.failure()
                    }
                    else -> {
                        extraParts += "消息失败：HTTP ${response.code}"
                        Log.w(TAG, "上报失败: HTTP ${response.code}")
                        Result.retry()
                    }
                }
            }
        } catch (e: IOException) {
            extraParts += "消息失败：网络错误 ${e.message ?: "未知"}"
            Log.w(TAG, "上报失败: 网络错误", e)
            Result.retry()
        }
    }

    /**
     * 分析结果上报通道：POST {serverUrl 去末尾 /weixin}/analysis（与 /admin/extract 同款
     * 路径推导规则）。body 为 case 记录数组（snake_case，s1/s2 嵌套对象，不带 raw_json）。
     *
     *  - 2xx -> 推进已同步水位到本次记录的最大 analyzedAt；
     *  - 404 -> 旧服务端没有分析端点，静默跳过本次（不记失败、不重试）；
     *  - 其它非 2xx 或 IOException -> 记失败文案，水位不动，下个周期自然补报；
     *  - 任何结果都不改变消息通道已经写下的成功/失败处置。
     */
    private fun pushAnalysis(
        config: SyncConfig,
        serverUrl: String,
        privacy: PrivacyModeState,
        okParts: MutableList<String>,
        extraParts: MutableList<String>
    ) {
        val allRecords = AnalysisStore.readUnsynced(applicationContext)
        if (allRecords.isEmpty()) return

        // M3 CLOUD_REDACT：只放行脱敏态产出的记录。模式切换前落库的旧记录
        // （redacted=false，会话名为原文、s2 文本可能引用原文）直接隔离在本机——
        // 水位一旦越过它们就不再补报，宁可不传也不发送未脱敏内容。
        val records = if (privacy.cloudRedact) {
            val quarantined = allRecords.count { !it.redacted }
            if (quarantined > 0) {
                extraParts += "分析：$quarantined 个旧记录未脱敏，已留本机"
            }
            allRecords.filter { it.redacted }
        } else allRecords
        if (records.isEmpty()) {
            // 全被隔离：把水位推进到全部记录的最大 analyzedAt，
            // 避免每个周期重复写隔离提示（记录已明确永不以原文上报）
            AnalysisStore.markSynced(applicationContext, allRecords.maxOf { it.analyzedAt })
            return
        }

        var base = serverUrl.trim().trimEnd('/')
        if (base.endsWith("/weixin")) base = base.dropLast("/weixin".length)
        val url = "$base/analysis"

        val body = JSONArray().apply {
            records.forEach { rec ->
                put(rec.toJson().apply {
                    // 接口字段：case_id/conversation/window_end/message_count/s1/
                    // escalated/s2/analyzed_at/protocol；raw_json 属本地防御存档，不上报
                    remove("raw_json")
                    // escalated=false 时 toJson 不含 s2，显式补 null 对齐接口字段
                    if (!has("s2")) put("s2", JSONObject.NULL)
                })
            }
        }.toString()

        val request = Request.Builder()
            .url(url)
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .apply {
                val token = config.authToken
                if (token.isNotEmpty()) header("X-Token", token)
            }
            .build()

        try {
            client.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> {
                        // 水位推进到本次记录的最大 analyzedAt（不用当前时间，
                        // 避免误标上报期间新落库的记录）；服务端按 case_id 去重，
                        // 重复上报返回 duplicated，幂等无害
                        AnalysisStore.markSynced(
                            applicationContext,
                            records.maxOf { it.analyzedAt }
                        )
                        okParts += "分析 ${records.size} 个"
                        Log.i(TAG, "分析上报成功: ${records.size} 个 -> $url (HTTP ${response.code})")
                    }
                    response.code == 404 -> {
                        // 旧服务端无分析端点：静默跳过，不记失败不重试
                        extraParts += "分析：服务端未支持(404)，已跳过"
                        Log.i(TAG, "分析端点 404（旧服务端），跳过分析上报")
                    }
                    else -> {
                        extraParts += "分析失败：HTTP ${response.code}"
                        Log.w(TAG, "分析上报失败: HTTP ${response.code}")
                    }
                }
            }
        } catch (e: IOException) {
            extraParts += "分析失败：网络错误 ${e.message ?: "未知"}"
            Log.w(TAG, "分析上报失败: 网络错误", e)
        }
    }

    /**
     * 顾问报告上报通道：POST {serverUrl 去末尾 /weixin}/advisor（同款路径推导规则）。
     * body 为报告数组（snake_case：report_id/created_at/period_days/case_count/
     * summary/suggestions/model_used）。
     *
     *  - 2xx -> 推进已同步水位到本次报告的最大 createdAt（服务端按 report_id 去重）；
     *  - 404 -> 服务端无顾问端点，静默跳过（不记失败、不重试）；
     *  - 其它失败 -> 记文案，水位不动，下轮自然补报；
     *  - 任何结果都不改变消息/分析通道的处置。
     */
    private fun pushAdvisor(
        config: SyncConfig,
        serverUrl: String,
        privacy: PrivacyModeState,
        okParts: MutableList<String>,
        extraParts: MutableList<String>
    ) {
        val reports = AdvisorStore.readUnsynced(applicationContext)
        if (reports.isEmpty()) return

        // M3 CLOUD_REDACT：报告是模型产出的聚合文本，没有窗口发送者名单可做
        // 姓名映射；用无 nameMap 的脱敏器过一遍自由文本字段（summary 与
        // suggestions 的 title/detail），手机号/身份证/银行卡/邮箱/链接/金额/
        // 地址七类仍被遮盖；type/report_id/model_used 等结构字段不动。
        val reportCore = if (privacy.cloudRedact) {
            RedactorCore(privacy.enabledRules, emptyMap())
        } else null

        var base = serverUrl.trim().trimEnd('/')
        if (base.endsWith("/weixin")) base = base.dropLast("/weixin".length)
        val url = "$base/advisor"

        val body = JSONArray().apply {
            reports.forEach { report ->
                put(if (reportCore == null) report.toJson() else JSONObject().apply {
                    put("report_id", report.reportId)
                    put("created_at", report.createdAt)
                    put("period_days", report.periodDays)
                    put("case_count", report.caseCount)
                    put("summary", reportCore.redact(report.summary).text)
                    put("suggestions", JSONArray().apply {
                        report.suggestions.forEach { s ->
                            put(JSONObject().apply {
                                put("type", s.type)
                                put("title", reportCore.redact(s.title).text)
                                put("detail", reportCore.redact(s.detail).text)
                            })
                        }
                    })
                    put("model_used", report.modelUsed)
                })
            }
        }.toString()

        val request = Request.Builder()
            .url(url)
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .apply {
                val token = config.authToken
                if (token.isNotEmpty()) header("X-Token", token)
            }
            .build()

        try {
            client.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> {
                        AdvisorStore.markSynced(
                            applicationContext,
                            reports.maxOf { it.createdAt }
                        )
                        okParts += "顾问报告 ${reports.size} 份"
                        Log.i(TAG, "顾问报告上报成功: ${reports.size} 份 -> $url (HTTP ${response.code})")
                    }
                    response.code == 404 -> {
                        // 服务端无顾问端点：静默跳过，不记失败不重试
                        Log.i(TAG, "顾问端点 404（服务端未支持），跳过顾问报告上报")
                    }
                    else -> {
                        extraParts += "顾问报告失败：HTTP ${response.code}"
                        Log.w(TAG, "顾问报告上报失败: HTTP ${response.code}")
                    }
                }
            }
        } catch (e: IOException) {
            extraParts += "顾问报告失败：网络错误 ${e.message ?: "未知"}"
            Log.w(TAG, "顾问报告上报失败: 网络错误", e)
        }
    }

    private fun recordResult(config: SyncConfig, result: String) {
        config.lastSyncTime = System.currentTimeMillis()
        config.lastSyncResult = result
    }
}
