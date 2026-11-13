// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Properties
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test

/**
 * 云端 vs 端侧 S2 深分析**摘要可用性**对比评测（ROADMAP M4 唯一剩余项）。
 *
 * 与 [S1EvalHttpTest] 的分工：那条量化「S1 判得对不对」（有客观答案），
 * 这条量化「S2 产出能不能用」（没有客观答案，所以拆成 [S2Usability] 的六项可机判判据）。
 *
 * ## 为什么不重跑 S1
 * S2 的输入是「同一个窗口 + S1 结论」。这里把**人工标注当 S1 输入**（need_action → prob 1.0/0.0），
 * 是为了把 S2 的质量和 S1 的准确率解耦：S1 判错会让 S2 跟着歪，那部分已经被 S1 评测量过了，
 * 再叠进来只会让「S2 到底行不行」这个结论变糊。报告第 6 节写明了这一点。
 *
 * ## 默认跳过
 * 同 S1：`NOTIFYME_EVAL=1` 或存在 `app/build/eval/eval.properties`。配置键沿用同一份文件
 * （CLOUD_URL/CLOUD_KEY/CLOUD_MODEL/LOCAL_URL/LOCAL_KEY/LOCAL_MODEL/LABEL_PATH/OUT_S2/
 * CALL_TIMEOUT_MS/TOTAL_BUDGET_MS/LIMIT）。
 *
 * ## 预算
 * S2 的 token 上限是 4096、云端 176B 实测单条 24s，20 条 × 2 侧不设闸就是几十分钟，
 * 所以复用 [S1EvalBudgetCore]：单次上限 + 整轮预算，耗尽即停并把剩余条数写进报告。
 *
 * ## 不在评测里抛占位照抄异常
 * 生产链路会 `rejectPlaceholderEcho` 抛异常让上层回退 S1；评测里**故意不抛**——
 * 要统计「有多少条照抄了骨架」，抛了就只剩「失败」两个字，量不出比例。
 */
class S2EvalHttpTest {

    private companion object {
        /** 固定采样：评测是回归比对，不是创作，温度必须为 0。 */
        const val SAMPLING_TEMPERATURE = 0.0
        const val SAMPLING_SEED = 20261113
    }

    private data class Endpoint(val url: String, val key: String, val model: String)

    private data class EvalConfig(
        val cloud: Endpoint,
        val local: Endpoint,
        val out: File,
        val labelPath: File?,
        val limit: Int,
        val plan: S1EvalBudgetCore.Plan
    )

    @Test
    fun cloudVsLocalS2Usability() {
        val cfg = readConfig()
        Assume.assumeTrue(
            "跳过：未配置评测端点（NOTIFYME_EVAL=1 或 app/build/eval/eval.properties）",
            cfg != null
        )
        val c = cfg!!

        val labelFile = c.labelPath ?: File("src/test/resources/eval-labels.json")
        Assume.assumeTrue("跳过：缺少标注样本 " + labelFile.absolutePath, labelFile.exists())

        val all = S1Accuracy.parse(labelFile.readText())
        Assume.assumeTrue("跳过：标注样本为空", all.isNotEmpty())
        val cases = all.take(c.limit.coerceAtLeast(1))

        val cloud = LinkedHashMap<String, S2Usability.Outcome>()
        val local = LinkedHashMap<String, S2Usability.Outcome>()
        val cloudRaw = LinkedHashMap<String, String>()
        val localRaw = LinkedHashMap<String, String>()
        val skipped = ArrayList<String>()
        val startedAt = System.currentTimeMillis()

        for (case in cases) {
            val kase = AnalysisCase.fromMessages(case.pkg, case.conversation, case.messages)
            if (kase == null) {
                val o = S2Usability.Outcome(case.caseId, case.conversation, null, 0L, "窗口为空")
                cloud[case.caseId] = o
                local[case.caseId] = o
                continue
            }
            val s1 = labelAsS1(case)
            val system = AnalysisCase.buildS2SystemPrompt(s1, "", kase.windowEnd)
            val user = kase.analysisUserPrompt(kase.windowEnd)

            val cloudTimeout = S1EvalBudgetCore.callTimeoutMs(
                c.plan,
                S1EvalBudgetCore.remainingMs(startedAt, System.currentTimeMillis(), c.plan.totalBudgetMs)
            )
            if (cloudTimeout <= 0L) {
                skipped.add(case.conversation + " [cloud] 预算耗尽未测试")
            } else {
                cloud[case.caseId] = callOne(c.cloud, case, kase, system, user, cloudTimeout, cloudRaw)
            }

            val localTimeout = S1EvalBudgetCore.callTimeoutMs(
                c.plan,
                S1EvalBudgetCore.remainingMs(startedAt, System.currentTimeMillis(), c.plan.totalBudgetMs)
            )
            if (localTimeout <= 0L) {
                skipped.add(case.conversation + " [local] 预算耗尽未测试")
            } else {
                local[case.caseId] = callOne(c.local, case, kase, system, user, localTimeout, localRaw)
            }

            if (S1EvalBudgetCore.exhausted(startedAt, System.currentTimeMillis(), c.plan.totalBudgetMs)) {
                for (i in (cases.indexOf(case) + 1) until cases.size) {
                    skipped.add(cases[i].conversation + " 预算耗尽未测试")
                }
                break
            }
        }

        val s1Of: (S1Accuracy.Case) -> AnalysisCase.Companion.S1Result = { labelAsS1(it) }
        val cloudSide = S2Usability.score("云端", cases, cloud, s1Of)
        val localSide = S2Usability.score("端侧", cases, local, s1Of)
        val rows = S2Usability.rows(cases, cloud, local, s1Of)

        val md = render(c, cases, cloudSide, localSide, rows, cloud, local, cloudRaw, localRaw, skipped)
        c.out.parentFile?.mkdirs()
        c.out.writeText(md)
        println("S2 评测报告：" + c.out.absolutePath)
        println("云端可用 " + cloudSide.usable + "/" + cloudSide.total + "，已交付 " + cloudSide.delivered)
        println("端侧可用 " + localSide.usable + "/" + localSide.total + "，已交付 " + localSide.delivered)

        // 断言的是「评测跑通」，不是「端侧行不行」——可用率是产出物，不该让构建挂掉。
        if (skipped.isEmpty() && cloud.isNotEmpty()) {
            assertTrue("云端一侧必须至少交付 1 条 S2 输出", cloud.values.any { it.out != null })
            assertTrue("端侧一侧必须至少交付 1 条 S2 输出", local.values.any { it.out != null })
        } else {
            println(S1EvalBudgetCore.exhaustedNote(c.plan, skipped.size))
        }
    }

    /** 把人工标注当 S1 结论注入（解耦 S1 准确率与 S2 可用性，见类注释）。 */
    private fun labelAsS1(case: S1Accuracy.Case): AnalysisCase.Companion.S1Result =
        AnalysisCase.Companion.S1Result(
            needActionProb = if (case.expect.needAction) 1.0 else 0.0,
            importance = case.expect.importance,
            dueWindow = case.expect.dueWindow,
            topic = case.expect.topic,
            confidence = 0.9
        )

    private fun callOne(
        ep: Endpoint,
        case: S1Accuracy.Case,
        kase: AnalysisCase,
        system: String,
        user: String,
        timeoutMs: Long,
        rawSink: MutableMap<String, String>
    ): S2Usability.Outcome {
        val t0 = System.currentTimeMillis()
        return try {
            val raw = chat(ep, system, user, timeoutMs)
            rawSink[case.caseId] = raw
            val parsed = AnalysisParsing.parseS2Output(
                AnalysisParsing.extractJsonPayload(raw), kase, raw
            )
            S2Usability.Outcome(
                caseId = case.caseId,
                conversation = case.conversation,
                out = parsed,
                elapsedMs = System.currentTimeMillis() - t0
            )
        } catch (e: Exception) {
            S2Usability.Outcome(
                caseId = case.caseId,
                conversation = case.conversation,
                out = null,
                elapsedMs = System.currentTimeMillis() - t0,
                error = e.message ?: e.javaClass.simpleName
            )
        }
    }

    private fun render(
        cfg: EvalConfig,
        cases: List<S1Accuracy.Case>,
        cloud: S2Usability.Side,
        local: S2Usability.Side,
        rows: List<S2Usability.Row>,
        cloudOutcomes: Map<String, S2Usability.Outcome>,
        localOutcomes: Map<String, S2Usability.Outcome>,
        cloudRaw: Map<String, String>,
        localRaw: Map<String, String>,
        skipped: List<String>
    ): String {
        val sb = StringBuilder()
        sb.append("# 云端 vs 端侧 S2 摘要可用性对比（ROADMAP M4 遗留项）\n\n")
        sb.append("> 生成时间：").append(timestamp()).append("\n")
        sb.append("> 样本：").append(cases.size).append(" 条人工标注合成会话（非真机采集）\n")
        sb.append("> 云端端点：").append(cfg.cloud.model).append(" @ ").append(cfg.cloud.url).append("\n")
        sb.append("> 端侧端点：").append(cfg.local.model).append(" @ ").append(cfg.local.url)
            .append("（端侧替身：真机 S2 是 MiniCPM5-2B，本机未备，用 qwen2.5-0.5b 当下限）\n")
        sb.append("> 时间预算：").append(S1EvalBudgetCore.describe(cfg.plan)).append("\n")
        sb.append("> 判据：").append(S2Usability.legend()).append("\n\n")

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
            val co = cloudOutcomes[r.caseId]
            val lo = localOutcomes[r.caseId]
            sb.append("| ").append(esc(r.conversation))
                .append(" | ").append(S2Usability.marks(r.cloud))
                .append(" | ").append(S2Usability.marks(r.local))
                .append(" | ").append(ms(co?.elapsedMs))
                .append(" | ").append(ms(lo?.elapsedMs))
                .append(" |\n")
        }
        sb.append("\n> 状态串顺序：").append(S2Usability.legend()).append("（✓ 通过 / ✗ 不通过）\n\n")

        val failures = S2Usability.failureLines(cloudOutcomes) + S2Usability.failureLines(localOutcomes)
        if (failures.isNotEmpty()) {
            sb.append("## 四、未交付明细\n\n")
            for (f in failures) sb.append("- ").append(esc(f)).append("\n")
            sb.append("\n")
        }

        if (skipped.isNotEmpty()) {
            sb.append("## 四之二、预算耗尽未测试（不计入任何分母）\n\n")
            sb.append(S1EvalBudgetCore.exhaustedNote(cfg.plan, skipped.size)).append("\n\n")
            for (s in skipped) sb.append("- ").append(esc(s)).append("\n")
            sb.append("\n")
        }

        sb.append("## 五、原始输出样例（便于复核解析是否走形）\n\n")
        sb.append("### 云端样例\n\n").append(codeBlock(cloudRaw.values.take(2)))
        sb.append("### 端侧样例\n\n").append(codeBlock(localRaw.values.take(2)))

        sb.append("## 六、口径与限制\n\n")
        sb.append("1. **S1 输入用人工标注而不是模型结论**：need_action 转 prob 1.0/0.0，" +
            "importance/due_window/topic 直接用标注值。这样做是为了把「S2 输出可不可用」" +
            "和「S1 判得准不准」解耦——后者已由 `docs/评测-云端vs端侧S1.md` 单独量化。" +
            "真实链路里 S1 误判会让 S2 跟着歪，那是 S1 准确率的后果，不是 S2 的能力问题。\n")
        sb.append("2. **端侧替身是 base 补全模型**：真机 S2 用 MiniCPM5-2B（chat 模板 + 结构化约束），" +
            "本机只有 qwen2.5-0.5b 且走 llama-server 的 chat 端点。" +
            "0.5B 的数字只能读作**下限**，不能当成端侧真机的实测结论。\n")
        sb.append("3. **「可用」是六项判据全过**，判据都在窗口内的客观事实（S1 桶、窗口末条时间）上，" +
            "不看措辞好坏。措辞好不好请直接看第五节的原始样例。\n")
        sb.append("4. **占位照抄不抛异常**：生产链路会 `rejectPlaceholderEcho` 回退 S1，" +
            "评测里保留输出并记一条 `非占位照抄` 失败，才能统计「模型跑飞」的比例。\n")
        return sb.toString()
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

    private fun timestamp(): String =
        LocaleTextCore.seconds(System.currentTimeMillis())

    private fun ms(v: Long?): String = if (v == null || v <= 0L) "-" else LocaleTextCore.fixed(v / 1000.0, 1) + "s"

    private fun esc(s: String?): String =
        (s ?: "").replace("|", "\\|").replace("\n", " ").take(120)

    private fun chat(ep: Endpoint, system: String, user: String, timeoutMs: Long): String {
        var url = ep.url.trim().trimEnd('/')
        if (!url.endsWith("/chat/completions")) url += "/chat/completions"
        val body = org.json.JSONObject().apply {
            put("model", ep.model)
            put("messages", org.json.JSONArray().apply {
                put(org.json.JSONObject().put("role", "system").put("content", system))
                put(org.json.JSONObject().put("role", "user").put("content", user))
            })
            put("response_format", org.json.JSONObject().put("type", "json_object"))
            // 采样参数必须钉住：不写 temperature / seed 时同一批样本两次跑给出不同数字
            // （实测 2026-11-13：第一轮六项全过 15/20、第二轮 16/20），评测就失去回归价值。
            put("temperature", SAMPLING_TEMPERATURE)
            put("seed", SAMPLING_SEED)
            // 与生产 runS2OpenAi 同口径：S2 的 max_tokens 是 4096（S1 才是 512）
            put("max_tokens", 4096)
            put("stream", false)
        }.toString()

        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = minOf(15_000L, timeoutMs).toInt()
            readTimeout = timeoutMs.toInt()
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            if (ep.key.isNotBlank()) setRequestProperty("Authorization", "Bearer " + ep.key)
        }
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
        if (code !in 200..299) error("HTTP " + code + ": " + text.take(200))
        return text
    }

    private fun readConfig(): EvalConfig? {
        val props = Properties()
        val f = File("build/eval/eval.properties")
        if (f.exists()) f.inputStream().use { props.load(it) }

        fun get(key: String, dflt: String = ""): String =
            System.getenv("NOTIFYME_EVAL_" + key)?.takeIf { it.isNotBlank() }
                ?: props.getProperty(key)?.takeIf { it.isNotBlank() }
                ?: dflt

        val enabled = System.getenv("NOTIFYME_EVAL") == "1" || f.exists()
        if (!enabled) return null

        val cloudUrl = get("CLOUD_URL")
        val localUrl = get("LOCAL_URL")
        if (cloudUrl.isBlank() || localUrl.isBlank()) return null

        val labelPath = get("LABEL_PATH").takeIf { it.isNotBlank() }?.let { File(it) }
        return EvalConfig(
            cloud = Endpoint(cloudUrl, get("CLOUD_KEY"), get("CLOUD_MODEL")),
            local = Endpoint(localUrl, get("LOCAL_KEY"), get("LOCAL_MODEL")),
            // 默认文件名必须是纯 ASCII：Windows 下 java.io.File 用平台编码往返，
            // 中文名会被 latin-1 化写成乱码文件（上一轮实测 ../docs/评测-云端vs端侧S2.md 落成
            // è¯æµ-äºç¯vsç¯ä¾§S2.md），README 里的链接随之失效。
            out = File(get("OUT_S2", "../docs/eval-s2-cloud-vs-local.md")),
            labelPath = labelPath,
            limit = get("LIMIT", "20").toIntOrNull() ?: 20,
            plan = S1EvalBudgetCore.resolvePlan(
                get("TOTAL_BUDGET_MS").takeIf { it.isNotBlank() },
                get("CALL_TIMEOUT_MS").takeIf { it.isNotBlank() }
            )
        )
    }
}
