// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「最近待办」面板选取纯逻辑单测（TodoPanelCore）。
 * 背景：用户报告面板与主页数据不联动——已删除/不采集/不关注会话的
 * 待办仍留在面板里，新分析出的待办被陈旧记录挤出前 8 条。
 */
class TodoPanelCoreTest {

    private fun rec(
        conv: String,
        needAction: Boolean,
        analyzedAt: Long,
        pkg: String = "com.tencent.mm",
        windowEnd: Long = analyzedAt
    ) = AnalysisCaseRecord(
        caseId = "${pkg}|$conv|$windowEnd",
        conversation = conv,
        windowEnd = windowEnd,
        messageCount = 3,
        analyzedAt = analyzedAt,
        protocol = "laya",
        rawJson = "{}",
        pkg = pkg,
        s1NeedAction = needAction,
        s1NeedActionProb = if (needAction) 0.9 else 0.1,
        s1Importance = 5.0,
        s1DueWindow = "today",
        s1Topic = conv,
        s1Confidence = 0.8,
        escalated = false,
        s2Summary = "",
        s2DueTime = "",
        s2SuggestedAction = "",
        s2Tasks = emptyList()
    )

    private fun k(s: String) = ConvKey("com.tencent.mm", s)

    @Test
    fun `待办且主页可见的会话入选`() {
        val r = rec("班级群", true, 100)
        val out = TodoPanelCore.selectTodos(mapOf(k("班级群") to r), setOf(k("班级群")))
        assertEquals(listOf(r), out)
    }

    @Test
    fun `非待办不入选`() {
        val r = rec("闲聊群", false, 100)
        val out = TodoPanelCore.selectTodos(mapOf(k("闲聊群") to r), setOf(k("闲聊群")))
        assertTrue(out.isEmpty())
    }

    @Test
    fun `主页已不可见的会话被剔除`() {
        val r = rec("已删会话", true, 100)
        val out = TodoPanelCore.selectTodos(mapOf(k("已删会话") to r), emptySet())
        assertTrue(out.isEmpty())
    }

    @Test
    fun `按分析时间倒序新待办排前面`() {
        val old = rec("旧", true, 100)
        val fresh = rec("新", true, 200)
        val out = TodoPanelCore.selectTodos(
            mapOf(k("旧") to old, k("新") to fresh),
            setOf(k("旧"), k("新"))
        )
        assertEquals(listOf(fresh, old), out)
    }

    @Test
    fun `陈旧记录不会把新待办挤出名额`() {
        // 10 个陈旧待办 + 1 个新待办：新待办必须出现在前 8 里（按时间倒序）
        val stale = (1..10).map { rec("陈旧$it", true, it.toLong()) }
        val fresh = rec("新鲜", true, 9999)
        val map = (stale + fresh).associateBy { it.convKey }
        val out = TodoPanelCore.selectTodos(map, map.keys.toSet(), limit = 8)
        assertEquals(8, out.size)
        assertTrue(out.first() === fresh)
    }

    @Test
    fun `limit 截断生效`() {
        val many = (1..12).map { rec("c$it", true, it.toLong()) }.associateBy { it.convKey }
        val out = TodoPanelCore.selectTodos(many, many.keys.toSet(), limit = 5)
        assertEquals(5, out.size)
        assertFalse(out.any { it.analyzedAt <= 7 })
    }

    @Test
    fun `多 App 会话键互不串扰`() {
        val wechat = rec("张三", true, 100)
        val ding = rec("张三", true, 200, pkg = "com.alibaba.android.rimet")
        val out = TodoPanelCore.selectTodos(
            mapOf(wechat.convKey to wechat, ding.convKey to ding),
            setOf(ding.convKey) // 微信侧同名会话不可见
        )
        assertEquals(listOf(ding), out)
    }
}
