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
 * 定时上报 Worker：四个通道依次执行——
 *  1. 消息：把 PendingQueue 里的待上报消息 POST 到 serverUrl；
 *  2. 分析结果：把 AnalysisStore 里未同步的 case 记录 POST 到
 *     {serverUrl 去末尾 /weixin}/analysis（与 /admin/extract 同款路径规则）；
 *  3. 顾问报告：把 AdvisorStore 里未同步的复盘报告 POST 到
 *     {serverUrl 去末尾 /weixin}/advisor（同款路径规则）；
 *  4. 排队状态：把端上排队中/运行中/失败的分析任务与待同步条数 POST 到
 *     {serverUrl 去末尾 /weixin}/queue（best-effort，失败不影响其它通道）。
 *
 * 行为约定：
 *  - serverUrl 为空（未配置）-> 直接 Result.success()，不产生网络请求；
 *  - 消息 body 为 JSON 数组，字段用蛇形命名（is_group）与 PC 端 Python 习惯对齐；
 *    分析 body 同为 JSON 数组（case_id 等 snake_case，s1/s2 嵌套对象，不带 raw_json）；
 *    JSON 拼接用平台内置 org.json（自带转义），不引 Gson/Moshi；
 *  - 消息按批上报（每批 <= 200 条且序列化后 <= 1 MiB，避免整队列撞上服务端
 *    MAX_CONTENT_LENGTH=8MiB 拿 413、又被当成普通失败无限重试）；
 *  - 消息 2xx -> 剔除本批已发条目；207（部分条目被服务端拒绝）->
 *    只剔除服务端明确接受的条目，被拒条目留在队列等下轮，**不**触发 retry
 *    （否则会退化成同一批被拒条目被无限重推的刷屏循环）；401 -> 鉴权失败，
 *    周期任务降级为 success（failure 会被 WorkManager 永久取消周期注册），
 *    一次性「立即同步」才返回 Result.failure()；
 *    其它非 2xx 且非 207/401（含 413、5xx、网络错误）-> Result.retry()
 *    交给 WorkManager 退避重试，未成功的批次留在队列里；
 *  - 分析 2xx -> 推进已同步水位；207（部分 case 被服务端拒绝）-> **水位不动**，
 *    被拒 case 绝不能被标成已同步，否则永久丢失；404（旧服务端无端点）-> 静默跳过；
 *    其它失败 -> 只记文案，水位不动、不触发 Worker 重试，下轮自然补报；
 *  - 分析通道的任何结果都不改变消息通道的处置；两通道结果文案合并进 lastSyncResult
 *   （如「成功：消息 5 条 + 分析 2 个」）；
 *  - 排队通道（/queue）是 best-effort：上传失败只记日志 + 在结果文案里追加一段，
 *    **绝不影响**消息通道对整轮 success/retry/failure 的判定；
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

    /** 本次是否由周期任务触发：周期路径下不允许失败（见 [SyncScheduler.TAG_PERIODIC]）。 */
    private fun isPeriodic(): Boolean = tags.contains(SyncScheduler.TAG_PERIODIC)

    override suspend fun doWork(): Result {
        // 数据保留策略：本地只留 7 天（用户拍板），每天最多执行一次全量清理
        Retention.pruneIfDue(applicationContext)

        val config = SyncConfig(applicationContext)
        val url = config.serverUrl
        if (url.isBlank()) return Result.success()

        // 安全策略：release 默认仅 HTTPS（NSC 在平台层拦截 + 这里显式跳过并记录原因）
        if (NetworkPolicy.isBlocked(url, BuildConfig.DEBUG)) {
            recordResult(config, "已跳过：明文 HTTP 被安全策略拦截（默认仅 HTTPS，本机回环除外）")
            return Result.success()
        }

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

        // ---- 第四步：排队状态上报（best-effort：失败只记日志 + 追加一段文案，
        //      绝不影响消息通道决定的整轮 success/retry/failure） ----
        pushQueue(config, url, okParts, extraParts)

        val parts = mutableListOf<String>()
        if (okParts.isNotEmpty()) parts += "成功：" + okParts.joinToString(" + ")
        parts += extraParts
        // 两通道都无待报数据时 parts 为空——也要落一条结果，
        // 否则用户点「立即同步」后控制台状态行停留在上一轮，像没点上
        recordResult(config, if (parts.isEmpty()) "成功：无新数据" else parts.joinToString("；"))

        return msgResult
    }


    /**
     * 单批提交的结果：状态码分类 + 被拒下标 + 服务端给出的原因。
     *
     * rejected = null 表示 207 的 body 解析不出来（读不出被拒清单）——
     * 此时必须一条都不删，整批留在队列里等下一轮。
     *
     * rejectedIds = 服务端 207 里的 rejected_ids（被拒条目的业务 id：分析通道是 case_id、
     * 顾问通道是 report_id）。**新字段**：老服务端没有它（null），此时退回下标口径。
     */
    private class BatchResult(
        val outcome: SyncOutcome,
        val code: Int,
        val rejected: Set<Int>?,
        val reason: String?,
        val rejectedIds: Set<String>? = null
    )

    /**
     * 提交一个 JSON 数组体并解析结果：HTTP 状态码 -> [SyncOutcome]；
     * 207 时额外解析 body 里的 rejected 下标与服务端给出的原因。
     * 网络异常统一归为 [SyncOutcome.FAILED]（由调用方按失败退避重试）。
     *
     * 消息 / 分析 / 顾问 / 排队四条通道共用这里，保证「207 先于 2xx 判定」的口径一致。
     */
    private fun postJson(config: SyncConfig, url: String, body: String): BatchResult {
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
                val outcome = SyncOutcomeCore.classify(response.code)
                if (outcome == SyncOutcome.PARTIAL_REJECTED) {
                    val text = response.body?.string()
                    BatchResult(
                        outcome = outcome,
                        code = response.code,
                        rejected = SyncOutcomeCore.parseRejected(text),
                        reason = SyncOutcomeCore.parseError(text),
                        // 服务端新字段：被拒条目的业务 id（老服务端没有 -> null -> 走下标口径）
                        rejectedIds = SyncOutcomeCore.parseRejectedIds(text)
                    )
                } else {
                    BatchResult(outcome, response.code, null, null)
                }
            }
        } catch (e: IOException) {
            Log.w(TAG, "上报失败: 网络错误", e)
            BatchResult(SyncOutcome.FAILED, 0, null, null)
        }
    }

    /**
     * 提交一批消息并解析结果（消息通道专用：body 由 [SyncBatchCore.buildBody] 生成）。
     */
    private fun postBatch(
        config: SyncConfig,
        url: String,
        batch: List<SyncBatchCore.Item>
    ): BatchResult = postJson(config, url, SyncBatchCore.buildBody(batch))

    /**
     * 消息上报通道：把待发队列**分批** POST 上去，逐批按 HTTP 状态码处置。
     * 成功/失败描述写入 okParts/extraParts，返回值为 Worker 层面的处置
     * （success / retry / failure）。待上报队列为空时不发请求，返回 success。
     *
     * 为什么分批（评审 C9）：原来把整个待发队列拼成一个 JSON 数组一次 POST，
     * 而服务端 MAX_CONTENT_LENGTH = 8 MiB（server/app.py:59）——队列一大就 413，
     * 而 413 又被当成普通失败一律 retry -> 死循环。现在按条数 + 字节双阈值切批
     * （见 [SyncBatchCore]），单批远小于服务端上限。
     *
     * 被拒条目的处置（上一轮报告外发现第 1 条）：207 被拒的条目留在队列等下轮重报，
     * 但**必须有上限**——否则服务端一直拒某条时队列会无限增长。现在给每条队列条目
     * 记「被拒次数 + 首拒时间」（见 [PendingRejectCore]），累计被拒达上限即丢弃该条；
     * 读取队列时顺带清理「被拒过且已过期」的僵尸条目。
     */
    private fun pushMessages(
        config: SyncConfig,
        url: String,
        privacy: PrivacyModeState,
        okParts: MutableList<String>,
        extraParts: MutableList<String>
    ): Result {
        // 读取队列时顺带清理「被拒过且已过期」的条目（僵尸条目不再一直占位）
        val nowMs = System.currentTimeMillis()
        val read = PendingQueue.readAllAndPruneExpired(applicationContext, nowMs)
        val pending = read.messages
        if (read.prunedExpired > 0) {
            extraParts += "清理过期被拒消息 ${read.prunedExpired} 条"
            Log.w(TAG, "清理过期被拒消息 ${read.prunedExpired} 条")
        }
        if (pending.isEmpty()) return Result.success()

        // M3 出设备脱敏：按会话分组替换消息文本/发送者/会话名；
        // PendingEntry.id 不在 message 内，removeSent 仍按原 id 剔除
        val outgoing = if (privacy.cloudRedact) {
            redactMessages(pending.map { it.message }, privacy.enabledRules)
        } else pending.map { it.message }

        // 批次条目：id 用于上报成功后精确剔除，json 是蛇形命名的上报片段
        val items = pending.mapIndexed { index, entry ->
            val message = outgoing[index]
            val json = message.toJson().apply {
                // 上报协议用蛇形命名，移除本地存储的驼峰键
                remove("isGroup")
                put("is_group", message.isGroup)
            }.toString()
            SyncBatchCore.Item(entry.id, json)
        }
        val batches = SyncBatchCore.splitBatches(items)
        if (batches.size > 1) {
            Log.i(TAG, "待发队列 ${pending.size} 条，分 ${batches.size} 批上报")
        }

        var sent = 0
        var rejected = 0
        var droppedRejected = 0
        var rejectReason: String? = null
        var retryNeeded = false

        // 队列化提交：413 时把超限批次二分后放回队首继续试（见 [SyncBatchCore.bisect]），
        // 不能像普通失败那样 break —— 一条毒丸条目会永久卡住该批**及其后所有条目**，
        // 每轮退避无限重推，队列再也不流动。
        val queue = ArrayDeque(batches)
        while (queue.isNotEmpty()) {
            val batch = queue.removeFirst()
            val result = postBatch(config, url, batch)
            when (result.outcome) {
                SyncOutcome.ACCEPTED -> {
                    // 只剔除本批实际发送的 id；上报期间新入队的消息保留
                    PendingQueue.removeSent(applicationContext, batch.map { it.id }.toSet())
                    sent += batch.size
                    Log.i(TAG, "上报成功: ${batch.size} 条 -> $url (HTTP ${result.code})")
                }
                SyncOutcome.PARTIAL_REJECTED -> {
                    // 服务端只收下一部分（ok=false + rejected 下标）：
                    // 只剔除被明确接受的条目，被拒条目留在队列里下轮重试，
                    // 绝不整批当成功删掉（那正是消息永久丢失的根因）。
                    val ids = batch.map { it.id }
                    val rejectedIdx = result.rejected
                    val accepted = if (rejectedIdx == null) {
                        // 207 body 解析失败：读不出被拒清单 -> 一条都不能删
                        emptySet<Long>()
                    } else {
                        SyncOutcomeCore.acceptedIds(ids, rejectedIdx)
                    }
                    if (accepted.isNotEmpty()) {
                        PendingQueue.removeSent(applicationContext, accepted)
                    }
                    rejected += batch.size - accepted.size
                    if (rejectReason == null) rejectReason = result.reason
                    // 被拒条目累计计数：达到上限即丢弃，避免队列被「服务端一直拒」的
                    // 僵尸条目无限撑大（首拒时间只写一次，过期清理靠它判断）。
                    if (rejectedIdx != null) {
                        val rejectedIds = ids.filterIndexed { index, _ -> index in rejectedIdx }.toSet()
                        val dropped = PendingQueue.markRejected(applicationContext, rejectedIds, nowMs)
                        if (dropped.isNotEmpty()) {
                            droppedRejected += dropped.size
                            Log.w(TAG, "被拒超限丢弃 ${dropped.size} 条: $dropped")
                        }
                    }
                    Log.w(TAG, "上报部分成功: HTTP 207，本批接受 ${accepted.size}/${batch.size}")
                    // 207 不触发 retry：被拒条目下轮自然补报；
                    // 这里 retry 会退化成「同一批被拒条目被无限重推」的刷屏循环。
                }
                SyncOutcome.PAYLOAD_TOO_LARGE -> {
                    // 413：整批超过服务端上限（server/app.py 的 MAX_CONTENT_LENGTH = 8 MiB）。
                    // 二分拆小继续试；拆到**单条**仍超限 -> 该条就是毒丸，单独丢弃
                    // （记被拒，累计到上限自然清出队列），然后继续下一批。
                    // **不触发整轮 retry**：重试无用，只会每轮刷屏重推。
                    val halves = SyncBatchCore.bisect(batch)
                    if (halves.isNotEmpty()) {
                        for (i in halves.indices.reversed()) queue.addFirst(halves[i])
                        Log.w(TAG, "消息批次过大(413)，二分后重试：${batch.size} 条")
                    } else {
                        val poison = batch.map { it.id }.toSet()
                        val dropped = PendingQueue.markRejected(applicationContext, poison, nowMs)
                        droppedRejected += dropped.size
                        rejected += batch.size
                        extraParts += "消息单条超限(413)已丢弃 ${batch.size} 条"
                        Log.w(TAG, "消息单条超限(413)丢弃 ${batch.size} 条: $poison")
                    }
                }
                SyncOutcome.UNAUTHORIZED -> {
                    // 鉴权失败属配置错误，重试无用。
                    // 但周期任务绝不能返回 failure：WorkManager 会把周期注册永久取消，
                    // 用户改好令牌后定时上报也不会再恢复（一次 401 = 永久停摆）。
                    extraParts += "消息鉴权失败(401)，请检查令牌"
                    Log.w(TAG, "上报失败: 401 鉴权失败")
                    return if (isPeriodic()) Result.success() else Result.failure()
                }
                SyncOutcome.NOT_SUPPORTED, SyncOutcome.FAILED -> {
                    // 其余非 2xx（404 端点不存在 / 5xx / 网络错误）一律按失败处理，
                    // 保持原来的退避重试行为；后续批次不再提交，
                    // 本批与之后的条目全部留在队列里等下一轮。
                    // （413 已单列成 [SyncOutcome.PAYLOAD_TOO_LARGE]，走上面的分支。）
                    val why = if (result.code > 0) "HTTP ${result.code}" else "网络错误"
                    extraParts += "消息失败：$why"
                    Log.w(TAG, "上报失败: $why")
                    retryNeeded = true
                    break
                }
            }
        }

        if (sent > 0) {
            okParts += "消息 $sent 条"
        }
        if (rejected > 0) {
            val reason = rejectReason?.let { "（$it）" } ?: ""
            extraParts += "消息部分被拒(207)：$rejected 条留在队列，下轮重试$reason"
        }
        if (droppedRejected > 0) {
            extraParts += "消息被拒超限丢弃 $droppedRejected 条"
        }
        // 累计丢弃数：被静默丢掉的条目用户完全看不到，这里给一个可见口径
        val totalDropped = PendingQueue.droppedRejectedCount(applicationContext)
        if (totalDropped > 0) {
            extraParts += "消息累计丢弃被拒 $totalDropped 条（服务端长期拒绝）"
        }
        return if (retryNeeded) Result.retry() else Result.success()
    }

    /**
     * 分析结果上报通道：POST {serverUrl 去末尾 /weixin}/analysis（与 /admin/extract 同款
     * 路径推导规则）。body 为 case 记录数组（snake_case，s1/s2 嵌套对象，不带 raw_json），
     * 按**条数 + 字节双阈值分批**逐批 POST（见 [AnalysisSyncCore]）——原来把全部未同步
     * case 拼成一个数组一次 POST，s1/s2 文本一大就撞服务端 MAX_CONTENT_LENGTH = 8 MiB
     *（server/app.py:59）拿 413，而 413 不算成功 -> 分析记录永远推不上去。
     *
     *  - 2xx -> **只有本轮所有批次都整批接受**才推进已同步水位到本次记录的最大 analyzedAt；
     *  - 207（部分 case 被服务端拒绝）-> 被拒的 case 绝不能被标成已同步
     *    （否则永久丢失）：未达隔离上限前水位不动（下轮补报，服务端按 case_id
     *    幂等去重）；累计被拒次数达到上限的 case 被**隔离**（记录留在本机，
     *    不再上报、不再阻塞水位），从根上收口「队首被持续拒绝 -> 队尾饿死」；
     *  - 401 -> 记鉴权失败文案，水位不动（重试无用）；
     *  - 404 -> 旧服务端没有分析端点，静默跳过本次（不记失败、不重试）；
     *  - 其它非 2xx 或 IOException -> 记失败文案并停止提交后续批次，水位不动，
     *    下个周期自然补报；
     *  - 任何结果都不改变消息通道已经写下的成功/失败处置。
     */
    private fun pushAnalysis(
        config: SyncConfig,
        serverUrl: String,
        privacy: PrivacyModeState,
        okParts: MutableList<String>,
        extraParts: MutableList<String>
    ) {
        // 隐私模式切换检测：模式一变就重置水位 + 扫描偏移。CLOUD_REDACT 期间
        // 被跳过的未脱敏记录、切回 OFF 后必须重新变成待上报，否则永远补报不了。
        if (AnalysisStore.syncPrivacyMode(applicationContext, privacy.mode)) {
            extraParts += "分析：隐私模式变更，已重置同步水位"
            Log.i(TAG, "隐私模式变更，重置分析同步水位与扫描偏移")
        }

        // M3 CLOUD_REDACT：只放行脱敏态产出的记录。模式切换前落库的旧记录
        // （redacted=false，会话名为原文、s2 文本可能引用原文）留本机不上云。
        // 关键：它们在**扫描层**被跳过（AnalysisScanCore.requireRedacted），
        // 绝不能钉住游标 —— 否则队首那条未脱敏记录会把游标永久钉住，
        // 它后面新追加的记录永远读不到、永远不上报。
        val pending = AnalysisStore.readPending(
            applicationContext,
            requireRedacted = privacy.cloudRedact
        )
        val records = pending.records
        if (pending.skippedUnredacted > 0) {
            extraParts += "分析：${pending.skippedUnredacted} 个未脱敏记录留本机"
        }
        if (records.isEmpty()) return

        var base = serverUrl.trim().trimEnd('/')
        if (base.endsWith("/weixin")) base = base.dropLast("/weixin".length)
        val url = "$base/analysis"

        // 分批条目：case_id 做去重键，analyzedAt 供水位使用，json 是上报片段
        val entries = records.map { rec ->
            val json = rec.toJson().apply {
                // 接口字段：case_id/conversation/window_end/message_count/s1/
                // escalated/s2/analyzed_at/protocol；raw_json 属本地防御存档，不上报
                remove("raw_json")
                // escalated=false 时 toJson 不含 s2，显式补 null 对齐接口字段
                if (!has("s2")) put("s2", JSONObject.NULL)
            }.toString()
            AnalysisSyncCore.Entry(
                caseId = rec.caseId,
                analyzedAt = rec.analyzedAt,
                json = json
            )
        }
        val batches = AnalysisSyncCore.splitBatches(entries)
        if (batches.size > 1) {
            Log.i(TAG, "未同步分析 ${records.size} 个，分 ${batches.size} 批上报")
        }

        var acceptedCount = 0
        var rejectedCount = 0
        var payloadTooLarge = 0
        // 本轮**已结算**的 case：被服务端接受 ∪ 本轮新被隔离。
        // 「已结算前缀」水位口径见 [SyncWatermarkCore]：洞（被拒未隔离 / 上传失败 /
        // 未脱敏）绝不被水位越过 —— 越过就等于那些记录永远补报不了。
        val settled = LinkedHashSet<String>()
        // 本轮被拒但还没到隔离上限的 case（洞）
        val stillPending = LinkedHashSet<String>()

        // 队列化提交：413 时把超限批次二分后放回队首继续试（SyncBatchCore.bisect），
        // 毒丸条目不卡住后续批次
        val queue = ArrayDeque(batches)
        while (queue.isNotEmpty()) {
            val batch = queue.removeFirst()
            val result = postJson(config, url, AnalysisSyncCore.buildBody(batch))
            when (result.outcome) {
                SyncOutcome.ACCEPTED -> {
                    acceptedCount += batch.size
                    settled += batch.map { it.caseId }
                    Log.i(TAG, "分析上报成功: ${batch.size} 个 -> $url (HTTP ${result.code})")
                }
                SyncOutcome.PARTIAL_REJECTED -> {
                    // 207：部分 case 被服务端校验拒绝。
                    //
                    //  - 被拒的 case 绝不能被标成已同步（P0-3）：那会让它永久消失。
                    //  - 但被拒的 case 会一直排在队首被重复上报（**分析通道饥饿**）：
                    //    只要队首有一条被持续拒绝的 case，水位永不推进，
                    //    队尾的记录永远读不到、永远不上报。
                    //  - 这里给它们累计被拒次数，达到上限即**隔离**（quarantine）：
                    //    记录仍留在 analysis.jsonl 里可人工查看，只是不再上报、
                    //    也不再阻塞水位——从根上收口饥饿。
                    //
                    // 下标 -> case_id 的映射前提：**请求体顺序 == 本批顺序**
                    //（AnalysisSyncCore.buildBody 按 batch 顺序拼装，服务端按数组
                    //  下标回报 rejected）。
                    val batchCaseIds = batch.map { it.caseId }
                    // 被拒清单**优先按 case_id 匹配**（服务端新字段 rejected_ids）：
                    // 不再依赖「请求体顺序 == 本批顺序」这个隐式前提；
                    // 匹配不到任何条目才退回「请求体下标」口径（老服务端没有该字段）。
                    val rejectedIdx = SyncOutcomeCore.matchRejectedIndices(
                        result.rejectedIds,
                        result.rejected,
                        batchCaseIds
                    )
                    if (rejectedIdx == null) {
                        // **读不出被拒清单 = 本轮结论不明**：一条都不标已同步、
                        // 不计被拒、不隔离（误判的代价是真丢消息，收益为零），
                        // 整批原样留待下一轮。
                        extraParts += "分析部分被拒(207)但清单不可读，本批留待下轮"
                        Log.w(TAG, "分析 207 清单不可读，保留整批不结算")
                        break
                    }
                    val rejectedCaseIds = SyncOutcomeCore.rejectedEntryIds(batchCaseIds, rejectedIdx)
                    rejectedCount += rejectedCaseIds.size
                    val quarantinedNow = AnalysisStore.markRejected(
                        applicationContext,
                        rejectedCaseIds,
                        System.currentTimeMillis()
                    )
                    if (quarantinedNow.isNotEmpty()) {
                        quarantinedNow.forEach { caseId ->
                            Log.w(TAG, "分析 case 被拒超限已隔离: $caseId")
                        }
                        extraParts += "分析隔离 ${quarantinedNow.size} 条（被拒超限，留本机不再上报）"
                    }
                    // 被接受的部分 + 新隔离的部分 = 已结算；未到隔离上限的被拒 case 是洞
                    settled += batchCaseIds - rejectedCaseIds
                    settled += quarantinedNow
                    val notSettled = rejectedCaseIds - quarantinedNow
                    stillPending += notSettled
                    if (notSettled.isNotEmpty()) {
                        extraParts += "分析部分被拒(207)：${notSettled.size} 个未入库，下轮补报"
                        Log.w(
                            TAG,
                            "分析上报部分成功: HTTP 207，被拒 ${notSettled.size} 个（未到隔离上限）"
                        )
                    } else {
                        Log.w(TAG, "分析上报部分成功: HTTP 207，被拒 case 已全部隔离")
                    }
                }
                SyncOutcome.PAYLOAD_TOO_LARGE -> {
                    // 413：二分拆小继续试；拆到单条仍超限 -> 该 case 就是毒丸，
                    // 单独记被拒（累计到上限即隔离），**不中断本轮后续批次**，
                    // 也不触发整轮 retry（重试无用，只会把队列卡死）。
                    val halves = SyncBatchCore.bisect(batch)
                    if (halves.isNotEmpty()) {
                        for (i in halves.indices.reversed()) queue.addFirst(halves[i])
                        Log.w(TAG, "分析批次过大(413)，二分后重试：${batch.size} 个")
                    } else {
                        // 毒丸记录：单独就超过服务端上限，重试永远不会成功
                        // -> **立即隔离**（记录留本机，不再每轮上传几 MiB）
                        val single = batch.first().caseId
                        payloadTooLarge += 1
                        val done = AnalysisStore.quarantineNow(
                            applicationContext,
                            setOf(single),
                            System.currentTimeMillis()
                        )
                        if (done) {
                            settled += single
                            extraParts += "分析单条超限(413)已隔离 $single"
                            Log.w(TAG, "分析单条超限(413)已隔离: $single")
                        } else {
                            stillPending += single
                            extraParts += "分析单条超限(413)待隔离 $single"
                        }
                    }
                }
                SyncOutcome.UNAUTHORIZED -> {
                    extraParts += "分析鉴权失败(401)，请检查令牌"
                    Log.w(TAG, "分析上报失败: 401 鉴权失败")
                    break
                }
                SyncOutcome.NOT_SUPPORTED -> {
                    // 旧服务端无分析端点：静默跳过，不记失败不重试；后续批次同样无意义
                    extraParts += "分析：服务端未支持(404)，已跳过"
                    Log.i(TAG, "分析端点 404（旧服务端），跳过分析上报")
                    break
                }
                SyncOutcome.FAILED -> {
                    // 其它失败（含 413/5xx/网络错误）：记失败文案并停止提交后续批次，
                    // 水位不动，下个周期整体补报
                    val why = if (result.code > 0) "HTTP ${result.code}" else "网络错误"
                    extraParts += "分析失败：$why"
                    Log.w(TAG, "分析上报失败: $why")
                    break
                }
            }
        }

        // 水位：只推进到「已结算前缀」的最大时间戳（[SyncWatermarkCore]）。
        // 洞（被拒未隔离 / 上传失败 / 未脱敏）绝不被越过 —— 越过就等于永久丢失；
        // 全部结算时行为与旧口径一致（推进到最大 analyzedAt）。
        val watermark = SyncWatermarkCore.prefixWatermark(
            records.map { SyncWatermarkCore.Entry(it.caseId, it.analyzedAt) },
            settled
        )
        if (watermark != null) {
            AnalysisStore.markSynced(applicationContext, watermark)
            if (acceptedCount > 0) okParts += "分析 $acceptedCount 个"
        }
        if (rejectedCount > 0) {
            Log.w(TAG, "分析水位未推进：$rejectedCount 个被拒，下轮补报")
        }
        if (stillPending.isNotEmpty()) {
            Log.w(TAG, "分析仍有 ${stillPending.size} 个未结算（被拒未隔离），水位停在洞前")
        }
        if (payloadTooLarge > 0) {
            extraParts += "分析 $payloadTooLarge 条超限(413)，已单独隔离"
        }
    }

    /**
     * 顾问报告上报通道：POST {serverUrl 去末尾 /weixin}/advisor（同款路径推导规则）。
     * body 为报告数组（snake_case：report_id/created_at/period_days/case_count/
     * summary/suggestions/model_used）。
     *
     * **分批**（上一轮报告外发现第 4 条，与评审 C9 同源）：原来把全部报告拼成一个
     * JSON 数组一次 POST，撞服务端 MAX_CONTENT_LENGTH = 8 MiB（server/app.py:59）
     * 拿 413，413 不算成功 → 顾问报告**永远推不上去**。现在按条数 + 字节双阈值
     * 切批逐批提交（见 [AdvisorSyncCore]），单批远小于服务端上限。
     *
     *  - 2xx -> **只有本轮所有批次都整批接受**才推进已同步水位到本次报告的最大
     *    createdAt（服务端按 report_id 去重）；
     *  - 207（部分报告被服务端拒绝）-> **水位不动**：被拒报告绝不能被标成已同步
     *    （否则永久丢失），下轮整体重报，服务端按 report_id 幂等去重；
     *  - 401 -> 记鉴权失败文案，水位不动（重试无用）；
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

        // 批次条目：report_id 是服务端去重键，createdAt 供水位使用
        val entries = reports.map { report ->
            val json = if (reportCore == null) {
                // 不脱敏分支同样走「上传口径」：本地存档才带 rejected_count /
                // first_rejected_at / quarantined 三个内部字段，服务端不需要，
                // 写进云端库只会污染服务端 schema。
                report.toUploadJson()
            } else {
                JSONObject().apply {
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
                }
            }
            AdvisorSyncCore.Entry(report.reportId, report.createdAt, json.toString())
        }
        val batches = AdvisorSyncCore.splitBatches(entries)
        if (batches.size > 1) {
            Log.i(TAG, "未同步顾问报告 ${reports.size} 份，分 ${batches.size} 批上报")
        }

        var accepted = 0
        var rejectedCount = 0
        var payloadTooLarge = 0
        // 已结算 = 被服务端接受 ∪ 本轮新被隔离（水位只推进到已结算前缀）
        val settled = LinkedHashSet<String>()
        // 被拒但未到隔离上限的报告（洞）：水位不许越过
        val stillPending = LinkedHashSet<String>()

        // 队列化提交：413 二分后放回队首继续试（毒丸条目不卡住后续批次）
        val queue = ArrayDeque(batches)
        while (queue.isNotEmpty()) {
            val batch = queue.removeFirst()
            // 与消息/分析通道共用 postJson：「207 先于 2xx 判定」的口径保持一致
            val result = postJson(config, url, AdvisorSyncCore.buildBody(batch))
            when (result.outcome) {
                SyncOutcome.ACCEPTED -> {
                    accepted += batch.size
                    settled += batch.map { it.reportId }
                    Log.i(TAG, "顾问报告上报成功: ${batch.size} 份 -> $url (HTTP ${result.code})")
                }
                SyncOutcome.PARTIAL_REJECTED -> {
                    // 207：部分报告被服务端拒绝。**水位不动**——被拒报告绝不能被标成
                    // 已同步（否则永久丢失），下轮整体重报，服务端按 report_id 幂等去重。
                    // 被拒清单**优先按 report_id 匹配**（服务端新字段 rejected_ids），
                    // 匹配不到才退回「请求体下标」口径；两者都读不出则保守按整批被拒计。
                    val batchReportIds = batch.map { it.reportId }
                    val rejectedIdx = SyncOutcomeCore.matchRejectedIndices(
                        result.rejectedIds,
                        result.rejected,
                        batchReportIds
                    )
                    if (rejectedIdx == null) {
                        // 清单不可读 = 本轮结论不明：不计被拒、不隔离，整批留待下轮
                        extraParts += "顾问报告部分被拒(207)但清单不可读，本批留待下轮"
                        Log.w(TAG, "顾问报告 207 清单不可读，保留整批不结算")
                        break
                    }
                    val rejectedIds = SyncOutcomeCore.rejectedEntryIds(batchReportIds, rejectedIdx)
                    rejectedCount += rejectedIds.size
                    // 被拒超限即隔离：与分析通道同一把尺子，隔离后照常推进水位，
                    // 单条被持续拒绝的报告不再把水位永久钉死
                    val quarantinedNow = AdvisorStore.markRejected(
                        applicationContext,
                        rejectedIds,
                        System.currentTimeMillis()
                    )
                    if (quarantinedNow.isNotEmpty()) {
                        extraParts += "顾问报告隔离 ${quarantinedNow.size} 份（被拒超限，留本机不再上报）"
                    }
                    settled += batchReportIds - rejectedIds
                    settled += quarantinedNow
                    val notSettled = rejectedIds - quarantinedNow
                    stillPending += notSettled
                    val bad = notSettled.size
                    if (bad > 0) {
                        extraParts += "顾问报告部分被拒(207)：$bad/${reports.size} 份未入库，下轮补报"
                        Log.w(TAG, "顾问报告上报部分成功: HTTP 207，被拒 $bad 份")
                    } else {
                        Log.w(TAG, "顾问报告上报部分成功: HTTP 207，被拒报告已全部隔离")
                    }
                }
                SyncOutcome.PAYLOAD_TOO_LARGE -> {
                    // 413：二分拆小继续试；拆到单条仍超限 -> 该报告就是毒丸，
                    // 单独记被拒（累计到上限即隔离），不中断后续批次、不触发 retry
                    val halves = SyncBatchCore.bisect(batch)
                    if (halves.isNotEmpty()) {
                        for (i in halves.indices.reversed()) queue.addFirst(halves[i])
                        Log.w(TAG, "顾问报告批次过大(413)，二分后重试：${batch.size} 份")
                    } else {
                        // 毒丸报告：单独就超过服务端上限，重试永远不会成功
                        // -> **立即隔离**（报告留本机，不再每轮上传）
                        val single = batch.first().reportId
                        payloadTooLarge += 1
                        val done = AdvisorStore.quarantineNow(
                            applicationContext,
                            setOf(single),
                            System.currentTimeMillis()
                        )
                        if (done) {
                            settled += single
                            extraParts += "顾问报告单条超限(413)已隔离 $single"
                            Log.w(TAG, "顾问报告单条超限(413)已隔离: $single")
                        } else {
                            stillPending += single
                            extraParts += "顾问报告单条超限(413)待隔离 $single"
                        }
                    }
                }
                SyncOutcome.UNAUTHORIZED -> {
                    extraParts += "顾问报告鉴权失败(401)，请检查令牌"
                    Log.w(TAG, "顾问报告上报失败: 401 鉴权失败")
                    break
                }
                SyncOutcome.NOT_SUPPORTED -> {
                    // 服务端无顾问端点：静默跳过，不记失败不重试
                    Log.i(TAG, "顾问端点 404（服务端未支持），跳过顾问报告上报")
                    break
                }
                SyncOutcome.FAILED -> {
                    val why = if (result.code > 0) "HTTP ${result.code}" else "网络错误"
                    extraParts += "顾问报告失败：$why"
                    Log.w(TAG, "顾问报告上报失败: $why")
                    break
                }
            }
        }

        // 水位：只推进到「已结算前缀」（与消息/分析通道同一把尺子，见 SyncWatermarkCore）
        val watermark = SyncWatermarkCore.prefixWatermark(
            reports.map { SyncWatermarkCore.Entry(it.reportId, it.createdAt) },
            settled
        )
        if (watermark != null) {
            AdvisorStore.markSynced(applicationContext, watermark)
            if (accepted > 0) okParts += "顾问报告 $accepted 份"
        }
        if (rejectedCount > 0) {
            Log.w(TAG, "顾问报告水位未推进：$rejectedCount 份被拒，下轮补报")
        }
        if (stillPending.isNotEmpty()) {
            Log.w(TAG, "顾问报告仍有 ${stillPending.size} 份未结算（被拒未隔离），水位停在洞前")
        }
        if (payloadTooLarge > 0) {
            extraParts += "顾问报告 $payloadTooLarge 份超限(413)，已单独隔离"
        }
    }

    /**
     * 排队状态上报通道（第 4 个通道）：把端上「排队中 / 运行中 / 失败」的
     * 分析任务与待同步条数 POST 到 {serverUrl 去末尾 /weixin}/queue。
     *
     * best-effort 语义（为什么要这么克制）：排队状态是给控制台看的辅助信息，
     * 缺一轮只是控制台慢一拍；而消息通道的成败判定牵着同步水位与队列清理，
     * 让一个辅助通道把整轮打成 retry 只会白白推迟消息上报。因此这里
     * **不返回任何结果码**：失败只写日志 + 在结果描述里追加一段，
     * 绝不改变 doWork 里 msgResult 的取值。
     *
     * 状态码口径：只认 2xx（207 也算 2xx，成功）——postJson 对 207 有
     * 「部分条目被拒」的特殊口径，那是消息/分析/顾问三通道的语义；
     * 排队上报是整份替换、没有「部分被拒」，故这里按状态码自行判定。
     */
    private fun pushQueue(
        config: SyncConfig,
        serverUrl: String,
        okParts: MutableList<String>,
        extraParts: MutableList<String>
    ) {
        var base = serverUrl.trim().trimEnd('/')
        if (base.endsWith("/weixin")) base = base.dropLast("/weixin".length)
        val url = "$base/queue"
        try {
            val tasks = QueueStateCollector.collect(applicationContext, System.currentTimeMillis())
            val payload = QueueStateCore.buildPayload(
                QueueStateCollector.deviceId(applicationContext),
                tasks
            )
            val result = postJson(config, url, payload)
            if (result.code in 200..299) {
                Log.i(TAG, "排队状态上报成功: ${tasks.size} 项")
                if (tasks.isNotEmpty()) okParts += "排队状态 ${tasks.size} 项"
            } else {
                // 不抛、不重试：下轮自然重报，控制台晚一拍不影响数据一致性
                // postJson 把网络异常统一记成 code=0，这里还原成「网络错误」文案
                val why = if (result.code > 0) "HTTP ${result.code}" else "网络错误"
                Log.w(TAG, "排队状态上报失败: ${why}")
                extraParts += "排队状态上报失败：${why}"
            }
        } catch (e: Exception) {
            Log.w(TAG, "排队状态上报异常", e)
            extraParts += "排队状态上报失败：网络错误"
        }
    }

    private fun recordResult(config: SyncConfig, result: String) {
        config.lastSyncTime = System.currentTimeMillis()
        config.lastSyncResult = result
    }
}
