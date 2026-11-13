// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import org.json.JSONArray
import org.json.JSONObject

/**
 * OpenAI 兼容 `chat/completions` 的**请求体构造口径**（端点归一 + 消息体），纯函数。
 *
 * 为什么抽：三个模型通道此前各抄一份 ~20 行的同构代码 ——
 * S1/S2 分析（[AnalysisWorker.postChatCompletions]）、顾问复盘（[AdvisorWorker.postChatCompletions]）、
 * S1 评测（[S1EvalRunner.postChatCompletions]）。三份的 URL 归一、`max_tokens`、
 * `response_format` 逐字相同，但注释里的实测数值已经开始分叉（1024 / 1500 / 4096），
 * 异常类型也不一致（前两处抛 `HttpException`、评测处抛 `IllegalStateException`）——
 * 这正是「同一口径抄三遍」的典型走向：下一次调参必须记得改三处，漏一处就是某个通道静默退化。
 *
 * 三条契约：
 * ① **端点归一只有一个方向**：`trim` → 去末尾 `/` → 已带 `/chat/completions` 就原样返回，
 *    否则追加。用户填到 `/v1`、`/v4`、甚至带尾斜杠都能用；**不猜 provider 前缀**（不补 `/v1`）。
 * ② **`max_tokens` 只有一个默认值**（[MAX_TOKENS]）：推理模型的 `reasoning_tokens` 也占额度，
 *    给 1024 会被思考段吃满、`content` 变空串（真机 `finish_reason=length`）。调用方要更小的值
 *    必须显式传参，不允许在 Core 里留第二条常量。
 * ③ **请求体键集合是契约**：`model` / `messages` / `response_format` / `max_tokens` / `stream`,
 *    `messages` 恒为 `system` → `user` 两条。加字段先想清楚三个通道是否都该带上 ——
 *    单测 `payloadKeepsSingleRequestShape` 守着这份键集合。
 *
 * 刻意不做的事：HTTP 本身（OkHttp 的 `Request` / `MediaType` / 异常分类）留在各 Worker ——
 * 那是通道语义（重试 vs 静默），不是公共口径；`Authorization` 头也由调用方按 `slot.key` 决定。
 */
internal object ChatRequestCore {

    /** 端点后缀：判断「已经补过」与「需要补」都用它，别在调用方重复字面量。 */
    const val SUFFIX = "/chat/completions"

    /**
     * 单次响应的 `max_tokens` 上限口径。
     *
     * glm-5.3 等推理模型的思考段计入该额度：1024 会被吃满导致 `content` 空串，
     * 1500 仍偏紧（顾问通道实测空串），4096 是三个通道当前共同的实测值。
     */
    const val MAX_TOKENS = 4096

    /**
     * 端点归一：`"http://h:8080/v1"` → `"http://h:8080/v1/chat/completions"`。
     *
     * 已带后缀（含末尾多余斜杠）时返回原样（去空白与尾斜杠后）；空串仍会补出
     * `/chat/completions` —— 调用方负责先判空并给出「未配置服务地址」文案，
     * Core 不做配置校验（那是 UI 层的语义）。
     */
    fun endpoint(url: String): String {
        val trimmed = url.trim().trimEnd('/')
        return if (trimmed.endsWith(SUFFIX)) trimmed else trimmed + SUFFIX
    }

    /**
     * 请求体：`system` + `user` 两条消息，要求 JSON 输出，非流式。
     *
     * [systemPrompt] 允许为空串（评测通道拿不到 system 文案时会传空），
     * [maxTokens] 必须为正；返回的是**已序列化的字符串**，调用方直接当 body 用。
     */
    fun payload(
        model: String,
        systemPrompt: String,
        userContent: String,
        maxTokens: Int = MAX_TOKENS
    ): String = JSONObject().apply {
        put("model", model)
        put("messages", JSONArray().apply {
            put(message("system", systemPrompt))
            put(message("user", userContent))
        })
        // GLM 支持 json_object 约束；不支持的服务端会忽略或报错（失败由调用方记录）
        put("response_format", JSONObject().put("type", "json_object"))
        put("max_tokens", maxTokens)
        put("stream", false)
    }.toString()

    private fun message(role: String, content: String): JSONObject = JSONObject().apply {
        put("role", role)
        put("content", content)
    }
}
