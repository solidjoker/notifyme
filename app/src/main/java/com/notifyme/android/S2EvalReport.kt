// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

/**
 * S2 可用性评测报告的**唯一渲染器**（纯函数，无 IO）。
 *
 * ## 为什么单独抽一份
 *
 * 两边都要出同一份报告：
 *  - HTTP 侧评测（`S2EvalHttpTest`，test 源集）：端侧是 llama-server 上的 0.5B **替身**；
 *  - 真机端侧采集（`S2EvalRunner` + `S2EvalActivity`，debug 入口）：端侧是真机 MiniCPM5-2B。
 *
 * 两边的**数字必须能逐项对比**（替身只是下限，真机才是结论），所以逐样本状态串、
 * 分项判据表这些地方一个字都不能差。渲染逻辑写两遍必然漂开 —— 提到这里，
 * 两边只差「头部说明」与「限制那几条」，由调用方以参数传入。
 *
 * 判据本身只有一份，在 [S2Usability]；这里只做排版。
 */
internal object S2EvalReport {

    /**
     * @param header 头部说明行（调用方自定：生成时间 / 样本数 / 两端端点 / 时间预算 / 判据说明）
     * @param cases 参与评测的样本
     * @param cloud 云端一侧的汇总
     * @param local 端侧一侧的汇总
     * @param rows 逐样本状态
     * @param cloudRaw 云端原始输出（按 caseId）
     * @param localRaw 端侧原始输出（按 caseId）
     * @param skipped 预算耗尽未测试的样本（不进任何分母）
     * @param limits 口径与限制那几条（调用方自定：替身版与真机版的措辞不同）
     */
    fun render(
        header: List<String>,
        cases: List<S1Accuracy.Case>,
        cloud: S2Usability.Side,
        local: S2Usability.Side,
        rows: List<S2Usability.Row>,
        cloudOutcomes: Map<String, S2Usability.Outcome>,
        localOutcomes: Map<String, S2Usability.Outcome>,
        cloudRaw: Map<String, String>,
        localRaw: Map<String, String>,
        skipped: List<String>,
        limits: List<String>,
        plan: S1EvalBudgetCore.Plan
    ): String {
        val sb = StringBuilder()
        sb.append("# 云端 vs 端侧 S2 摘要可用性对比\n\n")
        for (line in header) sb.append(line).append("\n")
        sb.append("\n")

        sb.append("## 一、综合可用性\n\n")
        sb.append(S2Usability.markdown(cloud, local))
        sb.append("\n")

        sb.append("## 二、分项判据通过率（分母 = 已交付条数）\n\n")
        sb.append(S2Usability.checksMarkdown(cloud, local))
        sb.append("\n")

        sb.append("## 三、逐样本状态\n\n")
        sb.append("| 会话 | 云端 | 端侧 | 云端时延 | 端侧时延 |\n")
        sb.append("|---|---|---|---|---|\n")
        for (r in rows) {
            sb.append("| ").append(esc(r.conversation))
                .append(" | ").append(S2Usability.marks(r.cloud))
                .append(" | ").append(S2Usability.marks(r.local))
                .append(" | ").append(latencyOf(cloudOutcomes[r.caseId]))
                .append(" | ").append(latencyOf(localOutcomes[r.caseId]))
                .append(" |\n")
        }
        sb.append("\n> 状态串顺序：").append(S2Usability.legend()).append("（✓ 通过 / ✗ 不通过）\n\n")

        val failures = S2Usability.failureLines(cloudOutcomes) +
            S2Usability.failureLines(localOutcomes)
        if (failures.isNotEmpty()) {
            sb.append("## 四、未交付明细\n\n")
            for (f in failures) sb.append("- ").append(esc(f)).append("\n")
            sb.append("\n")
        }

        if (skipped.isNotEmpty()) {
            sb.append("## 四之二、预算耗尽未测试（不计入任何分母）\n\n")
            sb.append(S1EvalBudgetCore.exhaustedNote(plan, skipped.size)).append("\n\n")
            for (s in skipped) sb.append("- ").append(esc(s)).append("\n")
            sb.append("\n")
        }

        sb.append("## 五、原始输出样例（便于复核解析是否走形）\n\n")
        sb.append("### 云端样例\n\n").append(codeBlock(cloudRaw.values.take(2)))
        sb.append("### 端侧样例\n\n").append(codeBlock(localRaw.values.take(2)))

        sb.append("## 六、口径与限制\n\n")
        for (i in limits.indices) {
            sb.append(i + 1).append(". ").append(limits[i]).append("\n")
        }
        return sb.toString()
    }

    /** 单条时延：Outcome 里记的毫秒数。 */
    private fun latencyOf(outcome: S2Usability.Outcome?): String {
        val ms = outcome?.elapsedMs ?: 0L
        return if (ms <= 0L) "-" else LocaleTextCore.fixed(ms / 1000.0, 1) + "s"
    }

    private fun codeBlock(lines: Collection<String>): String {
        val fence = String(charArrayOf(96.toChar(), 96.toChar(), 96.toChar()))
        val sb = StringBuilder()
        sb.append(fence).append("json\n")
        if (lines.isEmpty()) sb.append("（无）\n") else lines.forEach {
            // 原始输出不做 esc() 截断：截到 120 字符只剩半截 JSON，复核不了解析是否走形。
            // 只中和围栏本身，避免模型输出里出现三个反引号把代码块提前闭合。
            sb.append(it.replace(fence, "```".replace("`", "'"))).append("\n")
        }
        sb.append(fence).append("\n\n")
        return sb.toString()
    }

    private fun esc(s: String?): String =
        (s ?: "").replace("|", "\\|").replace("\n", " ").take(120)
}
