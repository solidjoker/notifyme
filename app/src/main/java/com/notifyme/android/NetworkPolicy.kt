// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import java.net.URI

/**
 * 网络安全策略（用户拍板：默认仅 HTTPS）。
 *
 * release 构建里明文 HTTP 一律拦截（由 res/xml/network_security_config.xml 在平台层兜底），
 * 本类在业务层做前置校验，给用户明确的错误提示而不是等上报时抛一个莫名的 IOException。
 *
 * 放行规则：
 *  - debug 构建整体放行（方便连局域网自建服务联调）；
 *  - 非 http scheme（即 https 或未填 scheme 的空串）放行；
 *  - http scheme 时的本机回环地址（localhost / 127.0.0.1 / 10.0.2.2 模拟器宿主）放行。
 */
object NetworkPolicy {

    /** 与 res/xml/network_security_config.xml 的 domain-config 保持一致。 */
    private val CLEARTEXT_HOSTS = setOf("localhost", "127.0.0.1", "10.0.2.2")

    /** @return true = 该 URL 会被明文拦截策略挡下来。 */
    fun isBlocked(url: String, debugBuild: Boolean): Boolean {
        if (debugBuild) return false
        if (url.isBlank()) return false // 未配置服务器地址走别的校验
        if (!url.contains("://")) return true // 没有 scheme 视为 http（OkHttp 也会拒绝）
        val scheme = url.substringBefore("://").lowercase()
        if (scheme != "http") return true // https / ftp 等非 http scheme 视为安全
        val host = runCatching { URI(url).host }.getOrNull() ?: return true
        return host !in CLEARTEXT_HOSTS
    }
}
