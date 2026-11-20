// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 真机端侧 S2 采集的**口径**单测（`S2EvalRunner` / `S2EvalReport` / `S2Usability.labelAsS1`）。
 *
 * 采集本身要真机 + 真模型，不进单测；这里钉的是「两份评测必须一致」的那些点：
 *  1. **S1 输入只有一份**：HTTP 侧与真机侧都用 [S2Usability.labelAsS1]，
 *     两边写两遍会让「S1 输入」悄悄漂开，比分项差几个点更难发现；
 *  2. **报告只有一份渲染**：真机侧与 HTTP 侧共用 [S2EvalReport]，
 *     逐样本状态串 / 分项表 / 未交付明细 / 预算耗尽节必须逐字同构，
 *     否则两列数字没法逐项对比；
 *  3. **未交付不进分母**：端侧模型没就绪的样本按「未交付」记，不该拉低可用率。
 */
class S2EvalReportTest {

    private fun case(
        caseId: String = "c1",
        needAction: Boolean = true,
        importance: Double = 5.0,
        dueWindow: String = "today",
        topic: String = "work_task"
    ) = S1Accuracy.Case(
        pkg = "com.tencent.mm",
        conversation = "会话-" + caseId,
        messages = listOf(
            ChatMessage(
                sender = "同事",
                text = "明天十点开会",
                timestamp = 1_700_000_000_000L,
                conversation = "会话-" + caseId,
                isGroup = false,
                source = "eval",
                pkg = "com.tencent.mm"
            )
        ),
        expect = S1Accuracy.Expect(needAction, importance, dueWindow, topic),
        isGroup = false
    )

    // ── 1. S1 输入只有一份 ────────────────────────────────────────────────

    @Test
    fun labelAsS1MapsNeedActionToProb() {
        val yes = S2Usability.labelAsS1(case(needAction = true))
        val no = S2Usability.labelAsS1(case(needAction = false))
        assertEquals(1.0, yes.needActionProb, 1e-9)
        assertEquals(0.0, no.needActionProb, 1e-9)
    }

    @Test
    fun labelAsS1KeepsHumanLabelsAndFixesConfidence() {
        val s1 = S2Usability.labelAsS1(
            case(needAction = false, importance = 7.5, dueWindow = "this_week", topic = "risk")
        )
        assertEquals(7.5, s1.importance, 1e-9)
        assertEquals("this_week", s1.dueWindow)
        assertEquals("risk", s1.topic)
        // 标注当 S1 结论时置信度固定 0.9：不让「S1 有多确信」影响 S2 的输入形状。
        assertEquals(0.9, s1.confidence, 1e-9)
    }

    // ── 2. 报告只有一份渲染 ────────────────────────────────────────────────

    @Test
    fun renderProducesAllSections() {
        val md = S2EvalReport.render(
            header = listOf("> 生成时间：测试", "> 端侧：真机 MiniCPM5-2B"),
            cases = listOf(case()),
            cloud = S2Usability.Side("云端", 1, 1, 1, mapOf(), 1000L),
            local = S2Usability.Side("端侧", 1, 1, 1, mapOf(), 2000L),
            rows = emptyList(),
            cloudOutcomes = emptyMap(),
            localOutcomes = emptyMap(),
            cloudRaw = emptyMap(),
            localRaw = emptyMap(),
            skipped = emptyList(),
            limits = listOf("限制一", "限制二"),
            plan = S1EvalBudgetCore.resolvePlan(null, null)
        )
        for (section in listOf(
            "## 一、综合可用性",
            "## 二、分项判据通过率",
            "## 三、逐样本状态",
            "## 五、原始输出样例",
            "## 六、口径与限制"
        )) {
            assertTrue("缺章节 $section", md.contains(section))
        }
        assertTrue(md.contains("> 生成时间：测试"))
        assertTrue(md.contains("限制一"))
        assertTrue(md.contains("限制二"))
    }

    @Test
    fun renderNumbersMatchTheSides() {
        val md = S2EvalReport.render(
            header = listOf("> 头"),
            cases = listOf(case()),
            cloud = S2Usability.Side("云端", 1, 1, 1, mapOf(), 1000L),
            local = S2Usability.Side("端侧", 1, 1, 1, mapOf(), 2000L),
            rows = emptyList(),
            cloudOutcomes = emptyMap(),
            localOutcomes = emptyMap(),
            cloudRaw = emptyMap(),
            localRaw = emptyMap(),
            skipped = emptyList(),
            limits = emptyList(),
            plan = S1EvalBudgetCore.resolvePlan(null, null)
        )
        // 综合表里的中位时延直接取 Side 的值：1.0s / 2.0s。
        assertTrue("缺云端时延 1.0s：\n" + md, md.contains("1.0s"))
        assertTrue("缺端侧时延 2.0s：\n" + md, md.contains("2.0s"))
    }

    @Test
    fun renderListsUndeliveredAndSkipped() {
        val md = S2EvalReport.render(
            header = listOf("> 头"),
            cases = listOf(case()),
            cloud = S2Usability.Side("云端", 1, 0, 0, mapOf(), 0L),
            local = S2Usability.Side("端侧", 1, 0, 0, mapOf(), 0L),
            rows = emptyList(),
            cloudOutcomes = mapOf(
                "c1" to S2Usability.Outcome("c1", "会话-c1", null, 0L, "HTTP 500")
            ),
            localOutcomes = mapOf(
                "c1" to S2Usability.Outcome("c1", "会话-c1", null, 0L, "端侧模型未就绪")
            ),
            cloudRaw = emptyMap(),
            localRaw = emptyMap(),
            skipped = listOf("会话-c2 预算耗尽未测试"),
            limits = emptyList(),
            plan = S1EvalBudgetCore.resolvePlan(null, null)
        )
        assertTrue("缺未交付明细节：\n" + md, md.contains("## 四、未交付明细"))
        assertTrue("缺 HTTP 500 说明：\n" + md, md.contains("HTTP 500"))
        assertTrue("缺端侧未就绪说明：\n" + md, md.contains("端侧模型未就绪"))
        assertTrue("缺预算耗尽节：\n" + md, md.contains("## 四之二、预算耗尽未测试"))
        assertTrue("缺预算耗尽样本：\n" + md, md.contains("会话-c2 预算耗尽未测试"))
    }

    @Test
    fun renderKeepsRawOutputUntruncated() {
        // 原始输出不做截断：截到 120 字符只剩半截 JSON，复核不了解析是否走形。
        val raw = "{\"summary\":\"" + "长".repeat(300) + "\"}"
        val md = S2EvalReport.render(
            header = listOf("> 头"),
            cases = emptyList(),
            cloud = S2Usability.Side("云端", 0, 0, 0, mapOf(), 0L),
            local = S2Usability.Side("端侧", 0, 0, 0, mapOf(), 0L),
            rows = emptyList(),
            cloudOutcomes = emptyMap(),
            localOutcomes = emptyMap(),
            cloudRaw = mapOf("c1" to raw),
            localRaw = emptyMap(),
            skipped = emptyList(),
            limits = emptyList(),
            plan = S1EvalBudgetCore.resolvePlan(null, null)
        )
        assertTrue("原始输出被截断了：\n" + md, md.contains(raw))
    }
}
