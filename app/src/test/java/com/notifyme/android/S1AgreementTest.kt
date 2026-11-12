// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertNotNull
import junit.framework.TestCase.assertTrue
import junit.framework.TestCase.fail
import org.json.JSONObject
import org.junit.Test

/**
 * M4 任务 6「云端 vs 端侧 S1 判定一致率」的纯逻辑用例。
 *
 * 只测判定口径与统计口径（不发网络、不加载模型）：两条路径真实跑出来的 S1Result
 * 由 [S1Agreement.compare] 比对，明细与一致率由 [S1Agreement.summarize] 汇总。
 */
class S1AgreementTest {

    private fun r(
        prob: Double,
        imp: Double,
        due: String,
        topic: String,
        conf: Double
    ): AnalysisCase.Companion.S1Result = AnalysisCase.Companion.S1Result(prob, imp, due, topic, conf)

    private fun pair(
        cloud: AnalysisCase.Companion.S1Result,
        local: AnalysisCase.Companion.S1Result
    ): S1Agreement.Pair = S1Agreement.Pair(
        pkg = "com.tencent.mm",
        conversation = "产品群",
        windowEnd = 1_762_900_000_000L,
        cloud = cloud,
        local = local
    )

    // ---------------- 单条比对 ----------------

    @Test
    fun `完全一致的判定五项全对`() {
        val row = S1Agreement.compare(
            pair(r(0.9, 7.0, "tomorrow", "work_task", 0.9), r(0.9, 7.0, "tomorrow", "work_task", 0.9))
        )
        assertTrue("五项应全一致", row.agree)
        assertTrue(row.needActionAgree)
        assertTrue(row.escalateAgree)
        assertTrue(row.dueWindowAgree)
        assertTrue(row.topicAgree)
        assertEquals(0.0, row.probDelta, 1e-9)
        assertEquals(0.0, row.importanceDelta, 1e-9)
    }

    @Test
    fun `need_action 阈值两侧分居两侧时判不一致`() {
        // 云端 0.51 → 需要行动；端侧 0.49 → 不需要。这是最影响打扰用户的差异。
        val row = S1Agreement.compare(
            pair(r(0.51, 5.0, "none", "notice", 0.8), r(0.49, 5.0, "none", "notice", 0.8))
        )
        assertFalse("need_action 应判不一致", row.needActionAgree)
        assertFalse("五项不一致则整体不一致", row.agree)
    }

    @Test
    fun `need_action_prob 差在容差内仍算不一致因为阈值口径是布尔`() {
        // 0.10 vs 0.20：数值差 0.10 ≤ PROB_TOLERANCE，但两者 needAction 都是 false → 一致。
        val row = S1Agreement.compare(
            pair(r(0.10, 3.0, "none", "notice", 0.7), r(0.20, 3.0, "none", "notice", 0.7))
        )
        assertTrue("同侧布尔判定应一致", row.needActionAgree)
        assertTrue("概率差仍在统计里被记录", row.probDelta > 0.0)
    }

    @Test
    fun `升级判定取或口径两侧一致`() {
        // importance 6.0 单独命中升级（云端），端侧 5.9 不命中 → 不一致。
        val row = S1Agreement.compare(
            pair(r(0.2, 6.0, "none", "notice", 0.9), r(0.2, 5.9, "none", "notice", 0.9))
        )
        assertFalse(row.escalateAgree)
    }

    @Test
    fun `低置信度单独命中升级`() {
        // confidence 0.49 < 0.5 就升级，即使 prob/importance 都很低。
        val cloud = r(0.1, 2.0, "none", "notice", 0.9)
        val local = r(0.1, 2.0, "none", "notice", 0.49)
        assertFalse(S1Agreement.compare(pair(cloud, local)).escalateAgree)
    }

    @Test
    fun `due_window 与 topic 大小写与空白不影响一致`() {
        val row = S1Agreement.compare(
            pair(
                r(0.5, 3.0, "TODAY", "Work_Task", 0.6),
                r(0.5, 3.0, " today ", "work_task", 0.6)
            )
        )
        assertTrue(row.dueWindowAgree)
        assertTrue(row.topicAgree)
    }

    @Test
    fun `数值差被逐条记录`() {
        val row = S1Agreement.compare(
            pair(r(0.8, 7.0, "today", "work_task", 0.8), r(0.4, 5.0, "none", "notice", 0.6))
        )
        assertEquals(0.4, row.probDelta, 1e-9)
        assertEquals(2.0, row.importanceDelta, 1e-9)
    }

    // ---------------- 汇总统计 ----------------

    @Test
    fun `空样本汇总不崩且等级为无样本`() {
        val s = S1Agreement.of(emptyList())
        assertEquals(0, s.total)
        assertEquals(0.0, s.agreementRate, 1e-9)
        assertEquals("无样本", s.grade)
        assertEquals(0.0, s.meanProbDelta, 1e-9)
    }

    @Test
    fun `一致率按样本数加权`() {
        val same = pair(r(0.9, 7.0, "tomorrow", "work_task", 0.9), r(0.9, 7.0, "tomorrow", "work_task", 0.9))
        val diff = pair(r(0.9, 7.0, "tomorrow", "work_task", 0.9), r(0.1, 1.0, "none", "smalltalk", 0.9))
        val s = S1Agreement.of(listOf(same, same, same, diff))
        assertEquals(4, s.total)
        assertEquals(3, s.agreeCount)
        assertEquals(0.75, s.agreementRate, 1e-9)
        assertEquals("基本可用（差异需人工复核）", s.grade)
    }

    @Test
    fun `分级阈值边界`() {
        fun gradeOf(agree: Int, total: Int): String =
            S1Agreement.summarize(
                (0 until agree).map {
                    S1Agreement.compare(pair(r(0.9, 7.0, "a", "b", 0.9), r(0.9, 7.0, "a", "b", 0.9)))
                } + (0 until (total - agree)).map {
                    S1Agreement.compare(pair(r(0.9, 7.0, "a", "b", 0.9), r(0.1, 1.0, "c", "d", 0.9)))
                }
            ).grade
        // 阈值：≥0.9 可直接替代 / ≥0.75 基本可用 / ≥0.5 只能做粗筛 / 其余不可替代
        assertEquals("可直接替代", gradeOf(10, 10))
        assertEquals("可直接替代", gradeOf(9, 10))
        assertEquals("基本可用（差异需人工复核）", gradeOf(8, 10))
        // 7/10 = 0.70，落在 [0.5, 0.75) 区间 → 只能做粗筛；0.75 这个界由 8/10 覆盖
        assertEquals("只能做粗筛（差异偏大）", gradeOf(7, 10))
        assertEquals("只能做粗筛（差异偏大）", gradeOf(6, 10))
        assertEquals("只能做粗筛（差异偏大）", gradeOf(5, 10))
        assertEquals("不可替代（差异占多数）", gradeOf(4, 10))
    }

    @Test
    fun `均值统计只在成功配对的样本上算`() {
        val rows = listOf(
            S1Agreement.compare(pair(r(0.9, 7.0, "a", "b", 0.9), r(0.6, 6.0, "a", "b", 0.8))),
            S1Agreement.compare(pair(r(0.3, 3.0, "a", "b", 0.9), r(0.1, 1.0, "a", "b", 0.7)))
        )
        val s = S1Agreement.summarize(rows)
        assertEquals(0.25, s.meanProbDelta, 1e-9)
        assertEquals(1.5, s.meanImportanceDelta, 1e-9)
        assertEquals(0.75, s.meanConfidence, 1e-9)
    }

    @Test
    fun `两侧命中数分别统计`() {
        val rows = listOf(
            S1Agreement.compare(pair(r(0.9, 7.0, "a", "b", 0.9), r(0.2, 2.0, "a", "b", 0.9))),
            S1Agreement.compare(pair(r(0.8, 7.0, "a", "b", 0.9), r(0.7, 6.0, "a", "b", 0.9)))
        )
        val s = S1Agreement.summarize(rows)
        assertEquals(2, s.cloudFlagged)
        assertEquals(1, s.localFlagged)
    }

    // ---------------- Markdown 报告 ----------------

    @Test
    fun `markdown 报告含全部指标与结论`() {
        val s = S1Agreement.of(
            listOf(pair(r(0.9, 7.0, "tomorrow", "work_task", 0.9), r(0.9, 7.0, "tomorrow", "work_task", 0.9)))
        )
        val md = S1Agreement.markdown(s, "2026-11-12T16:00:00+08:00")
        assertTrue(md.contains("| 样本窗口数 | 1 |"))
        assertTrue(md.contains("| need_action_prob 平均绝对差 | 0.000 |"))
        assertTrue(md.contains("结论：可直接替代"))
        assertTrue(md.contains("生成时间：2026-11-12T16:00:00+08:00"))
    }

    // ---------------- 端到端：真实 JSON → 判定 → 一致率 ----------------

    @Test
    fun `两条真实形状的判定 JSON 能走完解析与比对`() {
        // 云端：英文 prompt 产出的形态
        val cloudJson = JSONObject(
            "{\"need_action_prob\": 0.9, \"importance\": 7.0, " +
                "\"due_window\": \"tomorrow\", \"topic\": \"work_task\", \"confidence\": 0.9}"
        )
        // 端侧：few-shot 后模型可能包一层 answer，也必须能解析（AnalysisParsing 兼容两种形态）
        val localJson = JSONObject(
            "{\"answer\": {\"need_action_prob\": 0.85, \"importance\": 7.0, " +
                "\"due_window\": \"tomorrow\", \"topic\": \"work_task\", \"confidence\": 0.8}}"
        )
        val cloud = AnalysisParsing.parseS1Result(cloudJson, "openai")
        val local = AnalysisParsing.parseS1Result(localJson, "local")
        val row = S1Agreement.compare(pair(cloud, local))
        assertTrue("真实形状下应判一致", row.agree)
        assertEquals(0.05, row.probDelta, 1e-9)
    }

    @Test
    fun `端侧输出不可解析时按置信度 0 进入升级闸从而两侧仍能比`() {
        // 0.5B 跑飞时的两种典型输出，两种都落到全默认值 + confidence 0：
        //   1) 整段复读 S1_LOCAL_SYSTEM_PROMPT 的占位符：`\"need_action_prob\": 0.0-1.0`
        //      —— 实测 Android org.json 会宽容解析成字符串 `"0.0-1.0"`（不是抛异常），
        //      所以 parseS1Result 里 `optDouble` 拿不到数字 → 0.0；
        //   2) 干脆不是 JSON（0.5B 常直接吐大白话）→ extractJsonFromText 返回 null。
        val cloud = AnalysisParsing.parseS1Result(
            JSONObject("{" + "\"need_action_prob\": 0.9, \"importance\": 7.0, " +
                "\"due_window\": \"tomorrow\", \"topic\": \"work_task\", \"confidence\": 0.9" + "}"),
            "openai"
        )
        assertTrue(cloud.needAction)
        assertTrue(cloud.shouldEscalate())

        val placeholder = AnalysisParsing.extractJsonFromText("{\"need_action_prob\": 0.0-1.0}")
        assertNotNull("占位 JSON 在 org.json 下仍会被解析（值是字符串）", placeholder)
        val fromPlaceholder = AnalysisParsing.parseS1Result(placeholder, "local")
        assertEquals(0.0, fromPlaceholder.needActionProb, 1e-9)
        assertEquals(0.0, fromPlaceholder.importance, 1e-9)
        // 占位符里没有 confidence 字段：读不到就退回 1.0（「不影响升级」的那一档），
        // 所以这条样本靠 prob=0 与云端 0.9 拉开差距，而不是靠升级闸。
        assertEquals(1.0, fromPlaceholder.confidence, 1e-9)
        // confidence 缺失时退回 1.0，prob=0、importance=0 → 三个升级条件都不命中
        assertFalse("这条不该触发升级", fromPlaceholder.shouldEscalate())
        assertTrue("云端 need_action、端侧不需要，这条必须判不一致",
            S1Agreement.compare(pair(cloud, fromPlaceholder)).needActionAgree.not()
        )

        // 另一种飞法：直接吐大白话，连 JSON 都取不到 → 全默认值 + confidence 0 → 必须升级。
        val garbage = AnalysisParsing.extractJsonFromText("这不是 JSON")
        if (garbage != null) fail("非 JSON 文本必须解析失败")
        val fromGarbage = AnalysisParsing.parseS1Result(garbage, "local")
        assertEquals(0.0, fromGarbage.confidence, 1e-9)
        assertTrue("confidence 0 必须触发升级，交给 S2 重判", fromGarbage.shouldEscalate())
        // 两侧都要升级，所以「升级闸」这一项仍算一致；不一致体现在 prob（0.0 vs 0.9）与 need_action 上。
        val garbageRow = S1Agreement.compare(pair(cloud, fromGarbage))
        assertTrue(garbageRow.escalateAgree)
        assertFalse(garbageRow.needActionAgree)
        assertFalse(garbageRow.agree)
    }

    @Test
    fun `normalize 后的会话能定位到唯一窗口键`() {
        val now = 1_762_900_000_000L
        val raw = listOf(
            ChatMessage("张伟", "先发一条", now - 600_000, "产品群", true),
            ChatMessage("李静", "我这边也收到", now - 300_000, "产品群", true),
            ChatMessage("我", "我稍后整理", now - 100_000, "产品群", true)
        )
        val convs = ConvNormalize.group(raw)
        assertEquals(1, convs.size)
        val conv = convs.first()
        assertEquals("产品群", conv.conversation)
        assertEquals(3, conv.size)
        assertTrue("群聊推断应成立（两个不同发言人）", conv.isGroup)
        val kase = AnalysisCase.fromMessages(conv.pkg, conv.conversation, raw.map {
            ChatMessage(it.sender, it.text, it.timestamp, it.conversation, it.isGroup)
        })
        assertNotNull(kase)
        assertEquals(3, kase!!.messages.size)
        assertEquals(now - 100_000, kase.windowEnd)
    }
}