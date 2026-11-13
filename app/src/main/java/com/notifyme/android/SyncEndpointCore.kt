// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import okhttp3.Request

/**
 * 自托管服务端的**地址推导与鉴权头**口径（纯字符串推导 + 一层 okhttp 适配，不发起任何网络调用）。
 *
 * 2026-11-13 收口：这套推导此前有五处同构实现，各自写着 `trim().trimEnd('/')` +
 * `dropLast("/weixin".length)` + 拼路径，任一处写错（少一个 trimEnd、多一个斜杠、
 * 忘了去 /weixin）就只会在该通道上出问题，且症状是「HTTP 404」而不是编译错误：
 *  - `HistorySync.endpoint` / `HistorySync.request`（admin/extract + 历史回填）
 *  - `CaptureActivity.extractEndpoint` / `CaptureActivity.extractRequest`（同两个接口的另一入口）
 *  - `SyncWorker` 分析 / 顾问 / 排队三处内联的 base 推导
 *  - `SyncWorker.postJson` 内联的 X-Token 头
 *
 * 语义边界（刻意不接管）：
 *  - 不接管 `/chat/completions` 归一 —— 那是模型通道，见 [ChatRequestCore.endpoint]，
 *    它补的是后缀、不碰 `/weixin`；两者混一层会让「模型端点被当成自托管服务端」误伤。
 *  - 不接管 `removeSuffix("/v1")` —— 模型通道的 OpenAI 兼容前缀，与自托管服务端无关。
 *  - 不做 HTTPS 策略 —— 明文拦截是 [NetworkPolicy.isBlocked] 的职责，与地址拼接无关。
 */
internal object SyncEndpointCore {

    /** 鉴权头的名字，与服务端环境变量 WEIXIN_TOKEN 对应（见 [SyncConfig.authToken]）。 */
    const val TOKEN_HEADER = "X-Token"

    /** 服务端常见部署形态：反代把服务挂在 `/weixin` 子路径下，而接口本身在根路径。 */
    const val WEIXIN_SUFFIX = "/weixin"

    /**
     * 服务器根的归一：去首尾空白 → 去尾部斜杠 → 去结尾的 `/weixin` 子路径。
     *
     * 顺序不能换：`http://h/weixin/` 必须先去掉斜杠才能匹配到 `/weixin`；
     * 只写 `endsWith` 不做去尾斜杠时，用户多打一个 `/` 就会拼出 `http://h/weixin/analysis`。
     * 空串输入返回空串（由调用方的 `isBlank()` 守卫决定要不要发请求，这里不抛）。
     */
    fun base(serverUrl: String): String {
        var trimmed = serverUrl.trim().trimEnd('/')
        if (trimmed.endsWith(WEIXIN_SUFFIX)) {
            trimmed = trimmed.dropLast(WEIXIN_SUFFIX.length)
        }
        return trimmed
    }

    /** 拼根路径下的接口：`url(server, "analysis")` → `http://h/analysis`。 */
    fun url(serverUrl: String, path: String): String = "${base(serverUrl)}/$path"

    /** 拼 `/admin` 下的接口：`adminUrl(server, "extract/status")` → `http://h/admin/extract/status`。 */
    fun adminUrl(serverUrl: String, path: String): String = "${base(serverUrl)}/admin/$path"

    /**
     * 鉴权头的取值：空白令牌返回 null（= 不带头，对应服务端不鉴权模式）。
     *
     * 历史上 `HistorySync` / `CaptureActivity` 用 `isNotBlank()`、`SyncWorker` 用 `isNotEmpty()`，
     * 差别只在「令牌全是空白」这一种输入 —— `SyncConfig.authToken` 的 setter 会 trim，
     * 落盘后再读出来不可能全是空白，所以统一到 `isNotBlank()` 不改变任何可达行为。
     */
    fun token(raw: String): String? = if (raw.isNotBlank()) raw else null

    /**
     * 给请求构造器补鉴权头（令牌空白时原样返回，不带头）。
     *
     * 取的是 `builder` 而不是自己 new 一个：各通道的 `.get()` / `.post(body)` / `.url()`
     * 顺序在自己手里，这里只管「头叫什么、什么时候带」。返回同一实例，便于链式调用。
     */
    fun authorized(builder: Request.Builder, rawToken: String): Request.Builder {
        token(rawToken)?.let { builder.header(TOKEN_HEADER, it) }
        return builder
    }
}
