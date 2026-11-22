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

    /**
     * 造一条标注样本。
     *
     * 注意 [S1Accuracy.Case.caseId] 是**计算属性**（按 pkg + 会话名 + 窗口末条时间派生），
     * 不是构造参数 —— 所以「让样本彼此不同」要靠会话名与时间戳，这里两个都给。
     */
    private fun case(
        name: String = "c1",
        needAction: Boolean = true,
        importance: Double = 5.0,
        dueWindow: String = "today",
        topic: String = "work_task"
    ) = S1Accuracy.Case(
        pkg = "com.tencent.mm",
        conversation = "会话-" + name,
        messages = listOf(
            ChatMessage(
                sender = "同事",
                text = "明天十点开会",
                timestamp = 1_700_000_000_000L + name.hashCode().toLong() * 1000L,
                conversation = "会话-" + name,
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
    fun sampleFingerprintIsStableAndShort() {
        // 报告头写指纹：两份报告用了同一批样本就必须同指纹（推错文件要能看出来）。
        val a = S2EvalReport.sampleFingerprint("样本".toByteArray(Charsets.UTF_8))
        val b = S2EvalReport.sampleFingerprint("样本".toByteArray(Charsets.UTF_8))
        val c = S2EvalReport.sampleFingerprint("另一批".toByteArray(Charsets.UTF_8))
        assertEquals(a, b)
        assertTrue(a != c)
        assertEquals(12, a.length)
    }

    @Test
    fun takeCasesIsSharedByBothSides() {
        // limit <= 0 → 全取；limit > 条数 → 全取；limit 正常 → 前 N 条。
        // 两侧共用这一个实现（HTTP 侧评测与真机采集），否则样本集会分叉。
        val all = (1..20).map { case(name = "c$it") }
        assertEquals(20, S2EvalRunner.takeCases(all, 0).size)
        assertEquals(20, S2EvalRunner.takeCases(all, -1).size)
        assertEquals(20, S2EvalRunner.takeCases(all, 99).size)
        assertEquals(5, S2EvalRunner.takeCases(all, 5).size)
        // 取到的必须是**同一批**样本（caseId 是按 pkg+会话+窗口末条派生的计算属性）。
        assertEquals(all.take(5).map { it.caseId }, S2EvalRunner.takeCases(all, 5).map { it.caseId })
        // 20 条样本的 caseId 必须互不相同（否则报告里两条会撞成同一个 key）。
        assertEquals(20, all.map { it.caseId }.distinct().size)
    }

    @Test
    fun failureGroupsMergesSameReasonAndKeepsSide() {
        // 20 条全因同一个原因失败时不能列 20 行；更要紧的是**分不出是哪一侧失败**
        // ——云端与端侧的未交付混在一张列表里，读的人会以为「两端都挂了」。
        val outcomes = mapOf(
            "a" to S2Usability.Outcome("a", "老板", null, 0L, "模型尚未下载完成：MiniCPM5-2B"),
            "b" to S2Usability.Outcome("b", "朋友", null, 0L, "模型尚未下载完成：MiniCPM5-2B"),
            "c" to S2Usability.Outcome("c", "妈妈", null, 0L, "端侧引擎未就绪")
        )
        val groups = S2EvalReport.failureGroups("端侧", outcomes)
        assertEquals(2, groups.size)
        assertTrue(
            "同原因要合并成一行：\n" + groups.joinToString("\n"),
            groups.any { it.contains("老板、朋友（共 2 条）") && it.contains("模型尚未下载完成") }
        )
        assertTrue(
            "每行都要标明是哪一侧：\n" + groups.joinToString("\n"),
            groups.all { it.startsWith("端侧 · ") }
        )
    }

    @Test
    fun failureGroupsSkipsDeliveredOnes() {
        val delivered = AnalysisParsing.S2Output(
            summary = "开会",
            dueTime = "",
            suggestedAction = "准备",
            tasks = emptyList(),
            rawJson = "{}"
        )
        val outcomes = mapOf(
            "a" to S2Usability.Outcome("a", "老板", null, 0L, "未交付"),
            "b" to S2Usability.Outcome("b", "朋友", delivered, 10L, null)
        )
        val groups = S2EvalReport.failureGroups("云端", outcomes)
        assertEquals(1, groups.size)
        assertTrue(groups[0].contains("老板"))
    }

    @Test
    fun failureGroupKeepsLongReasonIntact() {
        // 整行 take(120) 会把行尾的**原因**先切掉（实测离 120 只差 4 字就断了），
        // 而原因正是这一行唯一的信息量。
        val reason = "端侧推理失败：" + "很长的原因".repeat(30)
        val outcomes = mapOf("a" to S2Usability.Outcome("a", "老板", null, 0L, reason))
        val groups = S2EvalReport.failureGroups("端侧", outcomes)
        assertEquals(1, groups.size)
        assertTrue(
            "原因不能被截断：\n" + groups[0],
            groups[0].contains(reason)
        )
    }

    @Test
    fun failureGroupFoldsLongConversationList() {
        // 会话名太多时折叠成「等 N 个」，但**原因**原样保留。
        val reason = "模型尚未下载完成：MiniCPM5-2B（GGUF Q4_K_M）"
        val outcomes = (1..12).associate { i ->
            "c$i" to S2Usability.Outcome("c$i", "会话$i", null, 0L, reason)
        }
        val groups = S2EvalReport.failureGroups("端侧", outcomes)
        assertEquals(1, groups.size)
        assertTrue("要标出总数：\n" + groups[0], groups[0].contains("共 12 条"))
        assertTrue("会话名要折叠：\n" + groups[0], groups[0].contains("等 12 个"))
        assertTrue("原因要原样：\n" + groups[0], groups[0].contains(reason))
    }

    @Test
    fun renderKeepsRawOutputUntruncated() {
        // 原始输出不做截断：截到 120 字符只剩半截 JSON，复核不了解析是否走形。
        val raw = "{\"summary\":\"" + "长".repeat(300) + "\"}"
        val md = S2EvalReport.render(
            header = listOf("> 头"),
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
