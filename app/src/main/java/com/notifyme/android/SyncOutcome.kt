// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import org.json.JSONObject

/**
 * 上报通道（消息 / 分析）的**结果分类**：把「HTTP 状态码 → 该怎么处置」从 Worker 的 IO
 * 代码里抽成纯函数，好让 JVM 单测直接覆盖。
 *
 * 为什么非抽不可：OkHttp 的 `Response.isSuccessful` 覆盖 **200..299**，而本项目服务端在
 * 「部分条目被拒」时返回的正是 **207 Multi-Status**（`{"ok":false,"rejected":[下标,...]}`）。
 * 207 落在 200..299 里 → 旧代码先判 isSuccessful 就把 207 当成「整批成功」，
 * 于是 removeSent 把整批（含被拒条目）从本地队列删掉，消息**永久丢失**（评审 P0-2）；
 * 分析通道同理把水位推过去，被拒 case 被永久标成「已同步」（评审 P0-3）。
 *
 * 判定顺序是契约的一部分，**不可调整**：
 *  1. 先判 207 → [SyncOutcome.PARTIAL_REJECTED]（部分成功，必须读 body 里的 rejected 下标）；
 *  2. 再判 2xx → [SyncOutcome.ACCEPTED]（整批成功）；
 *  3. 401 → [SyncOutcome.UNAUTHORIZED]（配置错误，重试无用）；
 *     404 → [SyncOutcome.NOT_SUPPORTED]（老服务端没有该端点，不是故障）；
 *  4. 413 → [SyncOutcome.PAYLOAD_TOO_LARGE]（**单条毒丸**超过服务端体积上限，
 *     重试无用且会永久卡住整批队列，必须单独处置）；
 *  5. 其它（含 5xx、网络错误）→ [SyncOutcome.FAILED]。
 */
internal enum class SyncOutcome {
    /** 整批被服务端接收。 */
    ACCEPTED,

    /** 207：服务端只接收了一部分，body.rejected 里是**批次内下标**。 */
    PARTIAL_REJECTED,

    /** 401：令牌不对，重试也没用。 */
    UNAUTHORIZED,

    /** 404：服务端没有这个端点（旧版服务端），跳过即可。 */
    NOT_SUPPORTED,

    /**
     * 413：请求体超过服务端上限（MAX_CONTENT_LENGTH = 8 MiB）。
     *
     * 为什么必须单列（第五轮审计）：分批后单批只有 ~1 MiB，能撞上 413 的
     * 只可能是「**单条就超 8 MiB**」的毒丸条目。这种条目**没法再切分**，
     * 如果按普通失败处理（break + Result.retry），它会永久卡住该批**及其后
     * 所有队列条目**，并且每轮退避无限重推 —— 队列再也不流动。
     * 正确处置：单独隔离该条目后继续提交下一批，不触发整轮重试。
     */
    PAYLOAD_TOO_LARGE,

    /** 其它失败（含 5xx、网络错误）。 */
    FAILED
}

/**
 * [SyncOutcome] 的纯逻辑实现：无 Android 依赖，可直接 JVM 单测。
 *
 * 只用平台内置的 org.json（不引 Gson/Moshi）；单测里 app/build.gradle.kts 已经挂了
 * testImplementation(org.json:json:20250107)，能跑真的解析器。
 */
internal object SyncOutcomeCore {

    /** 服务端「部分成功」的 HTTP 状态码。 */
    const val HTTP_MULTI_STATUS = 207

    /** 令牌错误。 */
    const val HTTP_UNAUTHORIZED = 401

    /** 端点不存在。 */
    const val HTTP_NOT_FOUND = 404

    /** 请求体过大（毒丸条目：单条超过服务端 MAX_CONTENT_LENGTH）。 */
    const val HTTP_PAYLOAD_TOO_LARGE = 413

    /**
     * HTTP 状态码 → 结果分类。
     *
     * 顺序是契约的一部分：**207 必须先于 2xx 判定**，否则又会退化成「整批成功」。
     */
    fun classify(code: Int): SyncOutcome = when {
        code == HTTP_MULTI_STATUS -> SyncOutcome.PARTIAL_REJECTED
        code in 200..299 -> SyncOutcome.ACCEPTED
        code == HTTP_UNAUTHORIZED -> SyncOutcome.UNAUTHORIZED
        code == HTTP_NOT_FOUND -> SyncOutcome.NOT_SUPPORTED
        code == HTTP_PAYLOAD_TOO_LARGE -> SyncOutcome.PAYLOAD_TOO_LARGE
        else -> SyncOutcome.FAILED
    }

    /**
     * 解析 207 响应体里的 rejected 下标集合。
     *
     * @return rejected 下标集合；body 缺失 / 不是 JSON / 没有 rejected 字段 / 元素不是合法
     *   下标 → null。null 的含义是「读不出被拒清单」，调用方必须保守处理：**一条都不能删**，
     *   整批留在队列里等下一轮。
     */
    fun parseRejected(bodyText: String?): Set<Int>? {
        if (bodyText.isNullOrBlank()) return null
        return try {
            val array = JSONObject(bodyText).optJSONArray("rejected") ?: return null
            val out = LinkedHashSet<Int>()
            for (i in 0 until array.length()) {
                val index = array.optInt(i, -1)
                if (index < 0) return null
                out += index
            }
            out
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 解析 207 响应体里服务端给出的原因（error 字段）。
     *
     * @return 原因文本；body 缺失 / 不是 JSON / 没有 error 字段 -> null
     */
    fun parseError(bodyText: String?): String? {
        if (bodyText.isNullOrBlank()) return null
        return try {
            val error = JSONObject(bodyText).optString("error")
            if (error.isNullOrBlank()) null else error
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 解析 207 响应体里的 rejected_ids（被拒条目的**业务 id** 列表）。
     *
     * 服务端在 /analysis 回 case_id、在 /advisor 回 report_id；老服务端没有这个字段，
     * 此时返回 null，调用方退回「请求体下标」口径（见 [matchRejectedIndices]）。
     *
     * @return id 集合；body 缺失 / 不是 JSON / 没有 rejected_ids 字段 / 字段不是数组
     *   -> null（= 老服务端，走了下标口径）。字段存在但为空数组时返回空集合。
     */
    fun parseRejectedIds(bodyText: String?): Set<String>? {
        if (bodyText.isNullOrBlank()) return null
        return try {
            val array = JSONObject(bodyText).optJSONArray("rejected_ids") ?: return null
            val out = LinkedHashSet<String>()
            for (i in 0 until array.length()) {
                // 只认字符串：null / 数字 / 对象一律忽略，避免把 "null" 当成业务 id
                val value = array.opt(i)
                if (value is String && value.isNotEmpty()) out += value
            }
            out
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 把服务端回报的「被拒清单」映射到**本批条目下标**。
     *
     * 口径优先级（**服务端新字段优先**）：
     *  1. 有 rejected_ids 且能命中本批条目 -> 按 id 匹配，不再依赖「请求体顺序 == 分批顺序」
     *     这个隐式前提（上一轮报告外发现第 3 条）；
     *  2. id 命中不了任何条目 -> 退回 rejected 下标口径（老服务端没有 rejected_ids）；
     *  3. 下标也读不出 -> 返回 null，调用方必须**保守处理**（一条都不标已同步）。
     *
     * 匹配不上的 id 一律忽略——宁可漏标（该条目下轮自然重报）也不能误删别的条目。
     */
    fun matchRejectedIndices(
        rejectedIds: Set<String>?,
        rejectedIndices: Set<Int>?,
        batchIds: List<String>
    ): Set<Int>? {
        if (rejectedIds != null) {
            val matched = batchIds.indices.filter { batchIds[it] in rejectedIds }.toSet()
            if (matched.isNotEmpty()) return matched
        }
        return rejectedIndices
    }

    /**
     * 取被拒下标对应的业务 id（越界下标忽略）。
     */
    fun rejectedEntryIds(batchIds: List<String>, matchedIndices: Set<Int>): Set<String> =
        matchedIndices.mapNotNull { index -> batchIds.getOrNull(index) }.toSet()

    /**
     * 从一批条目里挑出「服务端明确接受」的那些 id。
     *
     * @param ids 本批提交的所有条目 id（顺序 = 提交顺序 = 批次内下标顺序）
     * @param rejected 服务端给出的被拒**批次内下标**
     */
    fun acceptedIds(ids: List<Long>, rejected: Set<Int>): Set<Long> {
        val rejectedPositions = rejected.filter { it in ids.indices }.toSet()
        return ids.filterIndexed { index, _ -> index !in rejectedPositions }.toSet()
    }
}
