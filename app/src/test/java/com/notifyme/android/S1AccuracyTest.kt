// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [S1Accuracy] 的纯逻辑单测：标注解析、命中判定、准确率表。
 *
 * 这里不碰网络、不碰模型：准确率怎么算错了，比模型答错更误导人。
 */
class S1AccuracyTest {

    private fun msg(sender: String, text: String, ts: Long, conv: String) =
        ChatMessage(
            sender = sender,
            text = text,
            timestamp = ts,
            conversation = conv,
            isGroup = false
        )

    private fun expect(na: Boolean, imp: Double, due: String, topic: String) =
        S1Accuracy.Expect(na, imp, due, topic)

    private fun case(
        conv: String,
        messages: List<ChatMessage>,
        e: S1Accuracy.Expect,
        pkg: String = AppSourceRegistry.PKG_WECHAT,
        group: Boolean = false
    ) = S1Accuracy.Case(pkg, conv, group, messages, e)

    private fun result(prob: Double, imp: Double, due: String, topic: String) =
        AnalysisCase.Companion.S1Result(prob, imp, due, topic, 0.9)

    @Test
    fun `解析标注样本并保留窗口口径`() {
        val text = """
            {"version":1,"cases":[
              {"conversation":"老板","pkg":"com.tencent.mm","isGroup":false,
               "expect":{"need_action":true,"importance":7,"due_window":"tomorrow","topic":"work_task"},
               "messages":[
                 {"sender":"对方","text":"明天开会","timestamp":1762761600000},
                 {"sender":"我","text":"好","timestamp":1762761660000}]},
              {"conversation":"群","isGroup":true,
               "expect":{"need_action":false,"importance":1,"due_window":"none","topic":"smalltalk"},
               "messages":[{"sender":"他","text":"哈哈","timestamp":1762761700000}]}
            ]}
        """.trimIndent()
        val cases = S1Accuracy.parse(text)
        assertEquals(2, cases.size)

        val first = cases[0]
        assertEquals("老板", first.conversation)
        assertEquals(AppSourceRegistry.PKG_WECHAT, first.pkg)
        assertEquals(1762761660000L, first.windowEnd)
        assertTrue(first.expect.needAction)
        assertEquals(7.0, first.expect.importance, 1e-9)
        assertEquals("tomorrow", first.expect.dueWindow)
        assertEquals("work_task", first.expect.topic)
        // caseId 必须和生产同口径（窗口末条时间参与哈希）
        assertEquals(
            AnalysisCase.caseIdOf("com.tencent.mm", "老板", 1762761660000L),
            first.caseId
        )

        assertTrue(cases[1].isGroup)
        assertEquals(AppSourceRegistry.PKG_WECHAT, cases[1].pkg)
    }

    @Test
    fun `坏样本被跳过而不是整批失败`() {
        assertEquals(0, S1Accuracy.parse("not json").size)
        assertEquals(0, S1Accuracy.parse("{}").size)
        // 无 messages / 无 expect / 空会话名 / 时间非法 都该跳过
        val text = """
            {"cases":[
              {"conversation":"无消息","expect":{"need_action":true,"importance":5,"due_window":"none","topic":"notice"}},
              {"conversation":"无标注","messages":[{"sender":"对方","text":"在吗","timestamp":1762761600000}]},
              {"conversation":"","messages":[{"sender":"对方","text":"在吗","timestamp":1762761600000}],
               "expect":{"need_action":true,"importance":5,"due_window":"none","topic":"notice"}},
              {"conversation":"坏时间","messages":[{"sender":"对方","text":"在吗","timestamp":0}],
               "expect":{"need_action":true,"importance":5,"due_window":"none","topic":"notice"}},
              {"conversation":"好样本","messages":[{"sender":"对方","text":"在吗","timestamp":1762761600000}],
               "expect":{"need_action":false,"importance":0,"due_window":"none","topic":"smalltalk"}}
            ]}
        """.trimIndent()
        val cases = S1Accuracy.parse(text)
        assertEquals(1, cases.size)
        assertEquals("好样本", cases[0].conversation)
    }

    @Test
    fun `四项全对才记 exact`() {
        val kase = case(
            "老板",
            listOf(msg("对方", "明天开会", 1762761600000L, "老板")),
            expect(true, 7.0, "tomorrow", "work_task")
        )
        val side = S1Accuracy.score(
            "云端",
            listOf(kase),
            mapOf(kase.caseId to result(0.9, 7.0, "tomorrow", "work_task"))
        )
        assertEquals(1, side.total)
        assertEquals(1, side.exactOk)
        assertEquals(1.0, side.exactRate, 1e-9)
        assertEquals(1.0, side.needActionRate, 1e-9)
    }

    @Test
    fun `importance 允许容差但不放过跳档`() {
        val kase = case(
            "老板",
            listOf(msg("对方", "明天开会", 1762761600000L, "老板")),
            expect(true, 7.0, "tomorrow", "work_task")
        )
        // 7.0 vs 7.0 命中；7.0 vs 8.4（差 1.4）仍算命中；7.0 vs 8.6（差 1.6）不算
        assertEquals(
            1,
            S1Accuracy.score("a", listOf(kase), mapOf(kase.caseId to result(0.9, 8.4, "tomorrow", "work_task")))
                .importanceOk
        )
        assertEquals(
            0,
            S1Accuracy.score("a", listOf(kase), mapOf(kase.caseId to result(0.9, 8.6, "tomorrow", "work_task")))
                .importanceOk
        )
        // importance 对但其它三项错 → 不记 exact
        val onlyImp = S1Accuracy.score(
            "a",
            listOf(kase),
            mapOf(kase.caseId to result(0.2, 7.0, "none", "smalltalk"))
        )
        assertEquals(1, onlyImp.importanceOk)
        assertEquals(0, onlyImp.exactOk)
        assertEquals(0, onlyImp.needActionOk)
    }

    @Test
    fun `need_action 按阈值判定且与概率大小无关`() {
        val kase = case(
            "老板",
            listOf(msg("对方", "明天开会", 1762761600000L, "老板")),
            expect(true, 7.0, "tomorrow", "work_task")
        )
        // 概率 0.51 判为需要行动；0.49 判为不需要 → 与标注 false 一致、与 true 不一致
        assertEquals(
            0,
            S1Accuracy.score("a", listOf(kase), mapOf(kase.caseId to result(0.49, 7.0, "tomorrow", "work_task")))
                .needActionOk
        )
        val notNeed = case(
            "朋友",
            listOf(msg("对方", "哈哈", 1762761600000L, "朋友")),
            expect(false, 1.0, "none", "smalltalk")
        )
        assertEquals(
            1,
            S1Accuracy.score("b", listOf(notNeed), mapOf(notNeed.caseId to result(0.49, 1.0, "none", "smalltalk")))
                .needActionOk
        )
    }

    @Test
    fun `due_window 与 topic 大小写空白容错`() {
        val kase = case(
            "老板",
            listOf(msg("对方", "明天开会", 1762761600000L, "老板")),
            expect(true, 7.0, "tomorrow", "work_task")
        )
        val side = S1Accuracy.score(
            "a",
            listOf(kase),
            mapOf(kase.caseId to result(0.9, 7.0, "  Tomorrow ", "WORK_TASK"))
        )
        assertEquals(1, side.dueOk)
        assertEquals(1, side.topicOk)
        // 但取值必须真的属于同一选项，不能靠模糊匹配蒙对
        assertEquals(
            0,
            S1Accuracy.score("b", listOf(kase), mapOf(kase.caseId to result(0.9, 7.0, "tomorrow_week", "work")))
                .dueOk
        )
    }

    @Test
    fun `缺失判定只压分母不算命中`() {
        val kase = case(
            "老板",
            listOf(msg("对方", "明天开会", 1762761600000L, "老板")),
            expect(true, 7.0, "tomorrow", "work_task")
        )
        val side = S1Accuracy.score("云端", listOf(kase), emptyMap())
        assertEquals(1, side.total)
        assertEquals(0, side.needActionOk)
        assertEquals(0.0, side.exactRate, 1e-9)
    }

    @Test
    fun `准确率表包含两侧与全部指标`() {
        val kase = case(
            "老板",
            listOf(msg("对方", "明天开会", 1762761600000L, "老板")),
            expect(true, 7.0, "tomorrow", "work_task")
        )
        val cloud = S1Accuracy.score("云端", listOf(kase), mapOf(kase.caseId to result(0.9, 7.0, "tomorrow", "work_task")))
        val local = S1Accuracy.score("端侧", listOf(kase), emptyMap())
        val md = S1Accuracy.markdown(cloud, local)
        assertTrue(md.contains("need_action"))
        assertTrue(md.contains("importance"))
        assertTrue(md.contains("due_window"))
        assertTrue(md.contains("topic"))
        assertTrue(md.contains("四项全对"))
        assertTrue(md.contains("100.0%"))
        assertTrue(md.contains("0.0%"))
        assertTrue(md.contains("-100.0%"))
    }

    @Test
    fun `逐样本明细两侧并排且缺失显示占位`() {
        val kase = case(
            "老板",
            listOf(msg("对方", "明天开会", 1762761600000L, "老板")),
            expect(true, 7.0, "tomorrow", "work_task")
        )
        val cloudMap: Map<String, AnalysisCase.Companion.S1Result> =
            mapOf(kase.caseId to result(0.9, 7.0, "tomorrow", "work_task"))
        val rows = S1Accuracy.rows(listOf(kase), cloudMap, emptyMap())
        assertEquals(1, rows.size)
        assertEquals("老板", rows[0].conversation)
        assertEquals(7.0, rows[0].expect.importance, 1e-9)
        assertTrue(rows[0].cloud !== null)
        assertEquals(null, rows[0].local)
    }

    @Test
    fun `真实标注样本文件可解析且规模符合预期`() {
        val f = java.io.File("src/test/resources/eval-labels.json")
        if (!f.exists()) return // 资源缺失时不阻断（例如被裁剪的构建）
        val cases = S1Accuracy.parse(f.readText())
        assertTrue("样本数应 >= 20，实际 " + cases.size, cases.size >= 20)
        // 期望取值必须落在固定选项集内，否则准确率口径会和 S1 schema 对不上
        val dueOptions = setOf("none", "today", "tomorrow", "this_week", "later")
        val topicOptions = setOf("work_task", "schedule", "notice", "smalltalk", "risk")
        for (c in cases) {
            assertTrue("非法 due_window: " + c.expect.dueWindow, c.expect.dueWindow in dueOptions)
            assertTrue("非法 topic: " + c.expect.topic, c.expect.topic in topicOptions)
            assertTrue("importance 越界: " + c.expect.importance, c.expect.importance in 0.0..9.0)
            assertFalse("会话名为空", c.conversation.isBlank())
        }
    }
}
