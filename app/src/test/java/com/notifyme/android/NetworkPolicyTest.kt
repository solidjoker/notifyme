// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [NetworkPolicy] 的 URL 放行/拦截判定单测。
 *
 * 回归点（评审 C7）：原实现里 `if (scheme != "http") return true` 与本文件 KDoc 自相矛盾——
 * KDoc 写的是「只拦明文 http」，代码却把 https 也拦了。唯一调用点在 CaptureActivity 保存
 * 服务器地址时，release 版因此弹 Toast「需要 https」且**根本存不下 https 地址**。
 *
 * 判定顺序固定：debug 放行 -> 空串放行 -> 无 scheme 拦截 -> 非 http scheme 放行
 * -> http 时看 host 是否回环。
 */
class NetworkPolicyTest {

    // ---------------- https 必须放行（C7 回归） ----------------

    @Test
    fun `release 构建放行 https 地址`() {
        assertFalse(NetworkPolicy.isBlocked("https://example.com", false))
        assertFalse(NetworkPolicy.isBlocked("https://example.com:8443/weixin", false))
        assertFalse(NetworkPolicy.isBlocked("https://192.168.1.10:8443", false))
    }

    @Test
    fun `scheme 大小写不敏感 仍放行 https`() {
        assertFalse(NetworkPolicy.isBlocked("HTTPS://example.com", false))
    }

    @Test
    fun `非 http 的加密 scheme 同样放行`() {
        assertFalse(NetworkPolicy.isBlocked("wss://example.com", false))
    }

    // ---------------- 明文 http 仍按策略拦截 ----------------

    @Test
    fun `release 构建拦截明文 http 外网地址`() {
        assertTrue(NetworkPolicy.isBlocked("http://example.com", false))
        assertTrue(NetworkPolicy.isBlocked("http://192.168.1.10:5000", false))
        assertTrue(NetworkPolicy.isBlocked("HTTP://example.com", false))
    }

    @Test
    fun `http 回环地址放行`() {
        assertFalse(NetworkPolicy.isBlocked("http://localhost:5000", false))
        assertFalse(NetworkPolicy.isBlocked("http://127.0.0.1:5000", false))
        assertFalse(NetworkPolicy.isBlocked("http://10.0.2.2:5000", false))
    }

    // ---------------- 其它分支 ----------------

    @Test
    fun `debug 构建整体放行`() {
        assertFalse(NetworkPolicy.isBlocked("http://example.com", true))
        assertFalse(NetworkPolicy.isBlocked("https://example.com", true))
    }

    @Test
    fun `空地址不拦 未配置服务器走别的校验`() {
        assertFalse(NetworkPolicy.isBlocked("", false))
        assertFalse(NetworkPolicy.isBlocked("   ", false))
    }

    @Test
    fun `没有 scheme 的地址按明文拦截`() {
        assertTrue(NetworkPolicy.isBlocked("example.com", false))
    }
}
