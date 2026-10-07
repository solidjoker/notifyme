// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [AppSourceStateCore] 的启用/观察名单语义测试。
 *
 * 关键口径：新 App 默认启用（不要求先配置）；黑名单永不入库、永不启用；
 * 空 label 不覆盖旧名字。
 */
class AppSourceStateCoreTest {

    private lateinit var core: AppSourceStateCore

    @Before
    fun setUp() {
        core = AppSourceStateCore(AppSourceStateCore.memoryKv())
    }

    // ---------------- recordSeen ----------------

    @Test
    fun `recordSeen 新包加入观察名单`() {
        val s = core.recordSeen("com.example.app", "我的 App", 1_000L)
        assertEquals("com.example.app", s?.pkg)
        assertEquals("我的 App", s?.label)
        assertEquals(1_000L, s?.firstSeen)

        val all = core.observed()
        assertEquals(1, all.size)
        assertEquals("com.example.app", all.single().pkg)
    }

    @Test
    fun `recordSeen 同一包不新增 只保留一行`() {
        core.recordSeen("com.example.app", "名", 1_000L)
        core.recordSeen("com.example.app", "新名", 2_000L)

        val all = core.observed()
        assertEquals(1, all.size)
        assertEquals("首次出现时间不变", 1_000L, all.single().firstSeen)
    }

    @Test
    fun `recordSeen 新 label 非空才更新`() {
        core.recordSeen("com.example.app", "旧名", 1_000L)
        core.recordSeen("com.example.app", "新名", 2_000L)
        assertEquals("新名", core.observed().single().label)
    }

    @Test
    fun `recordSeen 空 label 不覆盖旧名字`() {
        core.recordSeen("com.example.app", "旧名", 1_000L)
        core.recordSeen("com.example.app", "", 2_000L)
        assertEquals("旧名", core.observed().single().label)
    }

    @Test
    fun `recordSeen 黑名单包与空包名返回 null`() {
        assertNull(core.recordSeen(BuildConfig.APPLICATION_ID, "x", 1_000L))
        assertNull(core.recordSeen("android", "x", 1_000L))
        assertNull(core.recordSeen("com.android.systemui", "x", 1_000L))
        assertNull(core.recordSeen("", "x", 1_000L))
        assertEquals(0, core.observed().size)
    }

    // ---------------- isEnabled ----------------

    @Test
    fun `isEnabled 未配置的包默认启用`() {
        assertTrue(core.isEnabled("com.example.app"))
        assertTrue(core.isEnabled(AppSourceRegistry.PKG_WECHAT))
    }

    @Test
    fun `isEnabled 黑名单恒不启用`() {
        assertFalse(core.isEnabled(BuildConfig.APPLICATION_ID))
        assertFalse(core.isEnabled("android"))
        assertFalse(core.isEnabled("com.android.systemui"))
    }

    @Test
    fun `setEnabled false 后该包不启用 再 true 恢复`() {
        core.setEnabled("com.example.app", false)
        assertFalse(core.isEnabled("com.example.app"))

        core.setEnabled("com.example.app", true)
        assertTrue(core.isEnabled("com.example.app"))
    }

    @Test
    fun `setEnabled 对黑名单无效`() {
        core.setEnabled("android", true)
        assertFalse(core.isEnabled("android"))
    }

    // ---------------- 持久化与损坏容错 ----------------

    // ---------------- M11 监控模式与白名单 ----------------

    @Test
    fun `默认模式 all 未配置包默认启用`() {
        assertEquals("all", core.mode())
        assertTrue(core.isEnabled("com.example.app"))
    }

    @Test
    fun `whitelist 模式只启用名单内的包`() {
        core.setMode("whitelist")
        core.setWhitelist(setOf("com.example.app"))
        assertTrue(core.isEnabled("com.example.app"))
        assertFalse(core.isEnabled("com.other.app"))
    }

    @Test
    fun `whitelist 模式黑名单仍然恒不启用`() {
        core.setMode("whitelist")
        core.setWhitelist(setOf("android"))
        assertFalse(core.isEnabled("android"))
    }

    @Test
    fun `切回 all 模式 恢复 disabled 语义`() {
        core.setWhitelist(setOf("com.example.app"))
        core.setEnabled("com.other.app", false)
        core.setMode("whitelist")
        assertFalse(core.isEnabled("com.other.app"))
        core.setMode("all")
        assertFalse(core.isEnabled("com.other.app"))
        assertTrue(core.isEnabled("com.example.app"))
    }

    @Test
    fun `mode 与 whitelist 在同一 KV 上持久`() {
        val kv = AppSourceStateCore.memoryKv()
        val first = AppSourceStateCore(kv)
        first.setMode("whitelist")
        first.setWhitelist(setOf("com.a", "com.b"))
        val reopened = AppSourceStateCore(kv)
        assertEquals("whitelist", reopened.mode())
        assertEquals(setOf("com.a", "com.b"), reopened.whitelist())
    }

    @Test
    fun `状态在同一 KV 上持久 新建 core 实例仍读得到`() {
        val kv = AppSourceStateCore.memoryKv()
        AppSourceStateCore(kv).apply {
            recordSeen("com.example.app", "名", 1_000L)
            setEnabled("com.example.app2", false)
        }

        val reopened = AppSourceStateCore(kv)
        assertEquals(listOf("com.example.app"), reopened.observed().map { it.pkg })
        assertFalse(reopened.isEnabled("com.example.app2"))
        assertTrue(reopened.isEnabled("com.example.app"))
    }

    @Test
    fun `observed 顺序按首次出现先后`() {
        core.recordSeen("com.a", "A", 3_000L)
        core.recordSeen("com.b", "B", 1_000L)
        core.recordSeen("com.c", "C", 2_000L)
        assertEquals(listOf("com.a", "com.b", "com.c"), core.observed().map { it.pkg })
    }

    @Test
    fun `损坏的持久化内容按空集合处理不崩`() {
        val kv = object : AppSourceStateCore.Kv {
            override fun getString(key: String, default: String): String = "不是 JSON"
            override fun putString(key: String, value: String) {}
        }
        val broken = AppSourceStateCore(kv)
        assertEquals(0, broken.observed().size)
        // disabled 损坏 → 不影响默认启用
        assertTrue(broken.isEnabled("com.example.app"))
    }
}
