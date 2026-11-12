// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 首页搜索口径单测（SearchCore）。
 *
 * 背景（回归用例见「搜索分析结论」那组）：首页搜索原来只匹配会话名 / 发送者 / 正文，
 * 用户明明在待办里看到「提交季度方案」，搜「方案」却整组消失 —— 因为会话名与消息都不含该词，
 * 会话被 overall 过滤掉。PWA 控制台早已覆盖分析字段，端上补齐并统一成一份口径。
 */
class SearchCoreTest {

    private fun msg(
        sender: String,
        text: String,
        conversation: String = "产品群",
        isGroup: Boolean = true,
        timestamp: Long = 1_000L,
        pkg: String = AppSourceRegistry.PKG_WECHAT,
        appLabel: String = "微信"
    ) = ChatMessage(
        sender = sender,
        text = text,
        timestamp = timestamp,
        conversation = conversation,
        isGroup = isGroup,
        pkg = pkg,
        appLabel = appLabel
    )

    private fun rec(
        conversation: String = "产品群",
        todoText: String = "",
        todoDue: String = "",
        s2Summary: String = "",
        s2DueTime: String = "",
        s2SuggestedAction: String = "",
        s2Tasks: List<AnalysisCaseRecord.Task> = emptyList(),
        s1Topic: String = "smalltalk",
        s1DueWindow: String = "none",
        pkg: String = AppSourceRegistry.PKG_WECHAT,
        caseId: String = "case-1"
    ) = AnalysisCaseRecord(
        caseId = caseId,
        conversation = conversation,
        windowEnd = 2_000L,
        messageCount = 3,
        analyzedAt = 2_000L,
        protocol = "laya",
        rawJson = "{}",
        pkg = pkg,
        s1NeedAction = true,
        s1NeedActionProb = 0.9,
        s1Importance = 6.0,
        s1DueWindow = s1DueWindow,
        s1Topic = s1Topic,
        s1Confidence = 0.8,
        escalated = true,
        s2Summary = s2Summary,
        s2DueTime = s2DueTime,
        s2SuggestedAction = s2SuggestedAction,
        s2Tasks = s2Tasks,
        todoText = todoText,
        todoDue = todoDue
    )

    /** 与 MainActivity.topicLabel / AnalysisListActivity.localizeTopic 同映射的简化版。 */
    private fun labelOf(code: String): String = when (code) {
        "work_task" -> "工作任务"
        "schedule" -> "日程安排"
        "notice" -> "通知公告"
        "smalltalk" -> "闲聊"
        "risk" -> "风险信号"
        "none" -> "无明确时间"
        "today" -> "今天"
        "tomorrow" -> "明天"
        "this_week" -> "本周内"
        "later" -> "更晚"
        else -> code
    }

    // ---------- normalize ----------

    @Test
    fun `归一化去空白并转小写`() {
        assertEquals("abc", SearchCore.normalize("  ABC "))
    }

    @Test
    fun `归一化空串与纯空白都是未搜索`() {
        assertEquals("", SearchCore.normalize(""))
        assertEquals("", SearchCore.normalize("   "))
    }

    @Test
    fun `归一化中文不做变形`() {
        assertEquals("方案", SearchCore.normalize(" 方案 "))
    }

    @Test
    fun `归一化幂等`() {
        assertEquals(SearchCore.normalize(" Foo "), SearchCore.normalize(SearchCore.normalize(" Foo ")))
    }

    // ---------- messageHit ----------

    @Test
    fun `消息发送者命中`() {
        assertTrue(SearchCore.messageHit(msg("李四", "好的"), "李四"))
    }

    @Test
    fun `消息正文命中`() {
        assertTrue(SearchCore.messageHit(msg("李四", "明天十点开会"), "开会"))
    }

    @Test
    fun `消息命中不区分大小写`() {
        assertTrue(SearchCore.messageHit(msg("Bob", "Ship It"), "ship"))
    }

    @Test
    fun `消息不命中返回 false`() {
        assertFalse(SearchCore.messageHit(msg("李四", "明天十点开会"), "方案"))
    }

    @Test
    fun `空搜索词不算消息命中`() {
        assertFalse(SearchCore.messageHit(msg("李四", "明天十点开会"), ""))
    }

    // ---------- caseParts / caseHit ----------

    @Test
    fun `分析可搜字段跳过空白项`() {
        val parts = SearchCore.caseParts(rec(s1Topic = "work_task"), ::labelOf)
        assertTrue(parts.contains("work_task"))
        assertTrue(parts.contains("工作任务"))
        assertFalse(parts.any { it.isBlank() })
    }

    @Test
    fun `分析可搜字段包含 S2 任务三要素`() {
        val r = rec(s2Tasks = listOf(AnalysisCaseRecord.Task("王五", "准备汇报材料", "周五")))
        val parts = SearchCore.caseParts(r)
        assertTrue(parts.contains("王五"))
        assertTrue(parts.contains("准备汇报材料"))
        assertTrue(parts.contains("周五"))
    }

    @Test
    fun `分析可搜字段带上本地化标签`() {
        val parts = SearchCore.caseParts(rec(s1DueWindow = "this_week"), ::labelOf)
        assertTrue(parts.contains("本周内"))
    }

    @Test
    fun `分析可搜字段不含 caseId 与原始 JSON`() {
        val r = rec(caseId = "case-SECRET-TOKEN").copy(rawJson = "{\"keep\":\"RAWONLY\"}")
        val parts = SearchCore.caseParts(r)
        assertFalse(parts.any { it.contains("SECRET") })
        assertFalse(parts.any { it.contains("RAWONLY") })
    }

    @Test
    fun `待办正文可搜`() {
        assertTrue(SearchCore.caseHit(rec(todoText = "提交季度方案"), "方案"))
    }

    @Test
    fun `待办截止可搜`() {
        assertTrue(SearchCore.caseHit(rec(todoDue = "本周五下班前"), "周五"))
    }

    @Test
    fun `S2 摘要可搜`() {
        assertTrue(SearchCore.caseHit(rec(s2Summary = "讨论季度预算分配"), "预算"))
    }

    @Test
    fun `S2 建议动作可搜`() {
        assertTrue(SearchCore.caseHit(rec(s2SuggestedAction = "回复确认参会"), "参会"))
    }

    @Test
    fun `S2 任务内容可搜`() {
        val r = rec(s2Tasks = listOf(AnalysisCaseRecord.Task("王五", "准备汇报材料", "")))
        assertTrue(SearchCore.caseHit(r, "汇报"))
    }

    @Test
    fun `S1 主题中文标签可搜`() {
        assertTrue(SearchCore.caseHit(rec(s1Topic = "risk"), "风险", ::labelOf))
    }

    @Test
    fun `S1 时间窗中文标签可搜`() {
        assertTrue(SearchCore.caseHit(rec(s1DueWindow = "tomorrow"), "明天", ::labelOf))
    }

    @Test
    fun `仅 S1 主题的记录也能被回退展示值搜到`() {
        // todoText/s2Summary 都空时 displayTodo() 回退到 s1Topic=work_task
        val r = rec(s1Topic = "work_task")
        assertTrue(SearchCore.caseHit(r, "work_task"))
    }

    @Test
    fun `分析不命中返回 false`() {
        assertFalse(SearchCore.caseHit(rec(todoText = "提交季度方案"), "报名"))
    }

    @Test
    fun `空搜索词不算分析命中`() {
        assertFalse(SearchCore.caseHit(rec(todoText = "提交季度方案"), ""))
    }

    @Test
    fun `分析命中不区分大小写`() {
        assertTrue(SearchCore.caseHit(rec(s2SuggestedAction = "Send the Deck"), "send"))
    }

    // ---------- conversationHit（含回归） ----------

    @Test
    fun `空搜索词整组保留`() {
        assertTrue(SearchCore.conversationHit("产品群", "微信", emptyList(), emptyList(), ""))
    }

    @Test
    fun `会话名命中保留整组`() {
        assertTrue(
            SearchCore.conversationHit("产品群", "微信", listOf(msg("李四", "好的")), emptyList(), "产品")
        )
    }

    @Test
    fun `来源 App 名命中保留整组`() {
        assertTrue(
            SearchCore.conversationHit("产品群", "飞书", listOf(msg("李四", "好的")), emptyList(), "飞书")
        )
    }

    @Test
    fun `消息命中保留整组`() {
        assertTrue(
            SearchCore.conversationHit(
                "产品群", "微信", listOf(msg("李四", "明天十点开会")), emptyList(), "开会"
            )
        )
    }

    @Test
    fun `回归 搜索分析结论能保留没有任何字面命中的会话`() {
        // 会话名、发送者、正文都不含「方案」，但该会话的待办里有
        val messages = listOf(msg("李四", "好的"), msg("张三", "收到"))
        val cases = listOf(rec(todoText = "提交季度方案"))
        assertTrue(SearchCore.conversationHit("产品群", "微信", messages, cases, "方案"))
    }

    @Test
    fun `回归 历史分析结论也参与命中而不只看最新一条`() {
        // 最新一条已被覆盖成闲聊，半年前那条才是「方案」
        val cases = listOf(
            rec(caseId = "old", todoText = "提交季度方案"),
            rec(caseId = "new", todoText = "今天的午饭")
        )
        assertTrue(
            SearchCore.conversationHit("产品群", "微信", listOf(msg("李四", "好的")), cases, "方案")
        )
    }

    @Test
    fun `四类来源都不命中才整组消失`() {
        val messages = listOf(msg("李四", "好的"))
        val cases = listOf(rec(todoText = "提交季度方案"))
        assertFalse(SearchCore.conversationHit("产品群", "微信", messages, cases, "报名"))
    }

    @Test
    fun `来源名为空不影响其它三类匹配`() {
        assertTrue(SearchCore.conversationHit("产品群", "", listOf(msg("李四", "好的")), emptyList(), "产品"))
    }

    // ---------- dayHit ----------

    @Test
    fun `日期组空搜索词恒保留`() {
        assertTrue(SearchCore.dayHit("产品群", "微信", emptyList(), emptyList(), ""))
    }

    @Test
    fun `日期组当天消息命中`() {
        assertTrue(
            SearchCore.dayHit("产品群", "微信", listOf(msg("李四", "明天十点开会")), emptyList(), "开会")
        )
    }

    @Test
    fun `日期组被覆盖的分析结论也参与命中`() {
        // 该天被两个窗口覆盖，命中落在非主展示（较早）的那个窗口上
        val dayCases = listOf(rec(caseId = "a", todoText = "提交季度方案"), rec(caseId = "b", todoText = "午饭"))
        assertTrue(SearchCore.dayHit("产品群", "微信", listOf(msg("李四", "好的")), dayCases, "方案"))
    }

    @Test
    fun `日期组不命中时返回 false`() {
        assertFalse(SearchCore.dayHit("产品群", "微信", listOf(msg("李四", "好的")), emptyList(), "方案"))
    }

    // ---------- forcedExpand ----------

    @Test
    fun `搜索态命中强制展开`() {
        assertTrue(SearchCore.forcedExpand("方案", true))
    }

    @Test
    fun `未搜索不强制展开`() {
        assertFalse(SearchCore.forcedExpand("", true))
        assertFalse(SearchCore.forcedExpand("", false))
    }

    @Test
    fun `搜索了但没命中不强制展开`() {
        assertFalse(SearchCore.forcedExpand("方案", false))
    }

    // ---------- hitKind ----------

    @Test
    fun `命中分析结论优先于消息`() {
        val messages = listOf(msg("李四", "方案收到"))
        val cases = listOf(rec(todoText = "提交季度方案"))
        assertEquals(
            SearchCore.HitKind.ANALYSIS,
            SearchCore.hitKind("产品群", "微信", messages, cases, "方案")
        )
    }

    @Test
    fun `命中来源 App 优先于消息`() {
        val messages = listOf(msg("李四", "飞书群里说的"))
        assertEquals(
            SearchCore.HitKind.APP,
            SearchCore.hitKind("产品群", "飞书", messages, emptyList(), "飞书")
        )
    }

    @Test
    fun `命中消息`() {
        assertEquals(
            SearchCore.HitKind.MESSAGE,
            SearchCore.hitKind("产品群", "微信", listOf(msg("李四", "明天十点开会")), emptyList(), "开会")
        )
    }

    @Test
    fun `只命中会话名时不给额外提示`() {
        assertEquals(
            SearchCore.HitKind.CONVERSATION,
            SearchCore.hitKind("产品群", "微信", listOf(msg("李四", "好的")), emptyList(), "产品")
        )
    }

    @Test
    fun `未搜索与未命中都返回 null`() {
        assertNull(SearchCore.hitKind("产品群", "微信", emptyList(), emptyList(), ""))
        assertNull(SearchCore.hitKind("产品群", "微信", emptyList(), emptyList(), "报名"))
    }
}
