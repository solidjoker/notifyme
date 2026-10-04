// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PrivacyModeState] 语义单测。三档模式的判定是「敏感信息会不会出设备」的闸门，
 * 错一个布尔就是泄露，纯逻辑必须钉死（持久化层 PrivacyConfig 读 SharedPreferences，
 * 属 Android 侧，不在 JVM 测试范围）。
 */
class PrivacyModeStateTest {

    private val keyWx = ConvKey(AppSourceRegistry.PKG_WECHAT, "张三")
    private val keyFs = ConvKey(AppSourceRegistry.PKG_FEISHU, "项目群")

    // ---------------- 默认 OFF ----------------

    @Test
    fun `默认模式 OFF 云端不阻断也不脱敏`() {
        val s = PrivacyModeState.DEFAULT
        assertTrue(s.isOff)
        assertFalse(s.cloudBlocked)
        assertFalse(s.shouldRedact(keyWx))
        assertFalse(s.shouldRedact(keyFs))
    }

    // ---------------- CLOUD_REDACT ----------------

    @Test
    fun `云端脱敏模式不阻断云端但所有会话默认脱敏`() {
        val s = PrivacyModeState(
            PrivacyModeState.CLOUD_REDACT, RedactorRules.ALL, emptySet()
        )
        assertTrue(s.cloudRedact)
        assertFalse(s.cloudBlocked)
        assertTrue(s.shouldRedact(keyWx))
        assertTrue(s.shouldRedact(keyFs))
    }

    @Test
    fun `白名单内会话不脱敏其余仍脱敏`() {
        val s = PrivacyModeState(
            PrivacyModeState.CLOUD_REDACT,
            RedactorRules.ALL,
            setOf(keyWx.id)
        )
        assertFalse("白名单会话允许原文发送", s.shouldRedact(keyWx))
        assertTrue("其它会话必须脱敏", s.shouldRedact(keyFs))
    }

    // ---------------- LOCAL_ONLY ----------------

    @Test
    fun `仅端侧模式阻断云端且不产生脱敏问题`() {
        val s = PrivacyModeState(
            PrivacyModeState.LOCAL_ONLY, RedactorRules.ALL, emptySet()
        )
        assertTrue(s.localOnly)
        assertTrue(s.cloudBlocked)
        // 云端整体不发送，shouldRedact 恒 false（没有「发送」这回事）
        assertFalse(s.shouldRedact(keyWx))
    }

    // ---------------- 规则集合透传 ----------------

    @Test
    fun `规则子集被原样保留`() {
        val s = PrivacyModeState(
            PrivacyModeState.CLOUD_REDACT,
            setOf(RedactorRules.PHONE, RedactorRules.EMAIL),
            emptySet()
        )
        assertEquals(2, s.enabledRules.size)
        assertTrue(s.enabledRules.contains(RedactorRules.PHONE))
        assertFalse(s.enabledRules.contains(RedactorRules.ID_CARD))
    }

    // ---------------- 与 RedactorCore 联动 ----------------

    @Test
    fun `云端脱敏模式下构造的 RedactorCore 替换白名单外会话内容`() {
        val state = PrivacyModeState(
            PrivacyModeState.CLOUD_REDACT, RedactorRules.ALL, setOf(keyWx.id)
        )
        // 模拟出设备文本：非白名单会话先脱敏
        val toSend = if (state.shouldRedact(keyFs)) {
            RedactorCore(state.enabledRules).redact("电话 13812345678").text
        } else "电话 13812345678"
        assertEquals("电话 [手机号]", toSend)
    }
}
