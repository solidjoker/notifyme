// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 首页按来源 App 筛选的内核单测（AppFilterCore）。
 *
 * 覆盖点：chip 顺序与 PWA 一致、窗口内没出现过的来源不出 chip、
 * 消失的选中项回落「全部」、包名精确匹配不带前缀模糊、空串口径统一。
 */
class AppFilterCoreTest {

    private val allLabel = "全部"

    private fun label(pkg: String): String = AppSourceRegistry.displayLabel(pkg)

    private fun tabs(pkgs: List<String>) = AppFilterCore.tabs(pkgs, ::label, allLabel)

    @Test
    fun `chip 第一项恒为全部`() {
        val t = tabs(listOf(AppSourceRegistry.PKG_WECHAT))
        assertEquals(2, t.size)
        assertTrue(t[0].isAll)
        assertEquals(allLabel, t[0].label)
    }

    @Test
    fun `微信排在其它来源之前，其余按展示名码点升序`() {
        val t = tabs(
            listOf(
                AppSourceRegistry.PKG_FEISHU,
                AppSourceRegistry.PKG_WECHAT,
                AppSourceRegistry.PKG_DINGTALK
            )
        )
        // 微信恒第二；其余按展示名码点升序（与 PWA 的 a[1] < b[1] 同口径）：钉钉(9489) < 飞书(98DE)
        assertEquals(
            listOf(
                "",
                AppSourceRegistry.PKG_WECHAT,
                AppSourceRegistry.PKG_DINGTALK,
                AppSourceRegistry.PKG_FEISHU
            ),
            t.map { it.pkg }
        )
    }

    @Test
    fun `其余来源按展示名升序且同建稳定`() {
        val t = tabs(listOf("com.b.app", "com.a.app", "com.a.app"))
        assertEquals(listOf("", "com.a.app", "com.b.app"), t.map { it.pkg })
    }

    @Test
    fun `未收录包名回落包名本身作展示`() {
        val t = tabs(listOf("com.example.unlisted"))
        assertEquals("com.example.unlisted", t[1].label)
    }

    @Test
    fun `重复包名只出一个 chip`() {
        val t = tabs(listOf("com.tencent.mm", "com.tencent.mm", ""))
        assertEquals(2, t.size)
    }

    @Test
    fun `空包名不出 chip`() {
        val t = tabs(listOf("", "   "))
        assertEquals(1, t.size)
        assertTrue(t[0].isAll)
    }

    @Test
    fun `没有消息时只剩全部`() {
        assertEquals(listOf(AppFilterCore.ALL), tabs(emptyList()).map { it.pkg })
    }

    @Test
    fun `空筛选全部通过`() {
        assertTrue(AppFilterCore.matches("com.tencent.mm", ""))
        assertTrue(AppFilterCore.matches("com.tencent.mm", "  "))
    }

    @Test
    fun `包名精确匹配不做前缀模糊`() {
        assertTrue(AppFilterCore.matches("com.tencent.mm", "com.tencent.mm"))
        assertFalse(AppFilterCore.matches("com.tencent.mm.sandbox", "com.tencent.mm"))
        assertFalse(AppFilterCore.matches("com.ss.android.lark", "com.tencent.mm"))
    }

    @Test
    fun `筛选值去首尾空白后比较`() {
        assertTrue(AppFilterCore.matches("com.tencent.mm", "  com.tencent.mm  "))
        assertEquals("com.tencent.mm", AppFilterCore.normalize("  com.tencent.mm  "))
    }

    @Test
    fun `选中项仍在时保留`() {
        val f = AppFilterCore.resolve("com.tencent.mm", listOf("com.tencent.mm", "com.ss.android.lark"))
        assertEquals("com.tencent.mm", f)
    }

    @Test
    fun `选中项已消失时回落全部`() {
        val f = AppFilterCore.resolve(AppSourceRegistry.PKG_FEISHU, listOf(AppSourceRegistry.PKG_WECHAT))
        assertEquals(AppFilterCore.ALL, f)
    }

    @Test
    fun `全部筛选永不需要校正`() {
        assertEquals(AppFilterCore.ALL, AppFilterCore.resolve("", emptyList()))
        assertEquals(AppFilterCore.ALL, AppFilterCore.resolve("   ", listOf("com.tencent.mm")))
    }

    @Test
    fun `chip 选中态只有一项为真`() {
        val t = tabs(listOf("com.tencent.mm", "com.ss.android.lark"))
        val filter = "com.ss.android.lark"
        assertEquals(1, t.count { AppFilterCore.chipSelected(it.pkg, filter) })
        assertTrue(AppFilterCore.chipSelected("", AppFilterCore.ALL))
        assertFalse(AppFilterCore.chipSelected("", filter))
    }

    @Test
    fun `筛选文案全部与具体来源区分`() {
        assertEquals(allLabel, AppFilterCore.filterLabel("", ::label, allLabel))
        assertEquals("微信", AppFilterCore.filterLabel(AppSourceRegistry.PKG_WECHAT, ::label, allLabel))
    }

    @Test
    fun `持久化归一后的值与内存口径一致`() {
        assertEquals("", AppFilterCore.normalize(""))
        assertEquals(AppSourceRegistry.PKG_WECHAT, AppFilterCore.normalize(" com.tencent.mm "))
    }
}
