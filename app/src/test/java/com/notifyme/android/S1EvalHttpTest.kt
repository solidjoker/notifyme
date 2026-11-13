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
 * 云端 vs 端侧 S1 真实 HTTP 对比评测（ROADMAP M4 任务 6 / docs/ROADMAP.md:237）。
 *
 * 为什么要放在 app/src/test 而不是 androidTest：
 * 被测对象是 [AnalysisCase] 的 prompt 构造、[AnalysisParsing] 的解析、
 * [S1Accuracy] / [S1Agreement] 的度量——全是纯 JVM 代码（[AnalysisParsing] 只用
 * org.json + android.util.Log，单测里 Log 返回默认值）。所以这条链路能在
 * `testOpenDebugUnitTest` 里真跑，CI 也能复现，不需要设备、不需要模拟器。
 *
 * ## 默认跳过
 * 评测要两个真实推理端点，CI 上没有，故默认 `Assume.abort`：
 *  - 环境变量 `NOTIFYME_EVAL=1` 打开；
 *  - 或存在 `app/build/eval/eval.properties`（gitignore，本地一次性配置）。
 *
 * 配置键（环境变量优先，其次 properties）：
 * ```
 * NOTIFYME_EVAL_CLOUD_URL=http://127.0.0.1:8080/v1
 * NOTIFYME_EVAL_CLOUD_KEY=
 * NOTIFYME_EVAL_CLOUD_MODEL=Qwen3.8-Flash-Next-....gguf
 * NOTIFYME_EVAL_LOCAL_URL=http://127.0.0.1:8081/v1
 * NOTIFYME_EVAL_LOCAL_KEY=
 * NOTIFYME_EVAL_LOCAL_MODEL=qwen25-0.5b-q4km.gguf
 * NOTIFYME_EVAL_OUT=../docs/评测-云端vs端侧S1-<日期>.md
 * NOTIFYME_EVAL_CALL_TIMEOUT_MS=90000   # 单次调用上限（默认 90s）
 * NOTIFYME_EVAL_TOTAL_BUDGET_MS=900000  # 整轮预算（默认 15min，耗尽即停并写进报告）
 * ```
 *
 * ## 必须有时间预算（2026-11-12 的实际事故）
 * 端点「TCP 能连、chat 迟迟不回」时，旧实现只有 readTimeout=180s，20 条 × 2 侧最坏
 * 两小时；实测把整轮 testOpenDebugUnitTest 挂死 40 分钟，最后靠 jstack 才定位到
 * [chat] 卡在 Net.poll。预算算术抽在 [S1EvalBudgetCore]（纯函数、可单测）。
 *
 * ## 两条链路都用「生产同款 prompt」
 * 云端走 [AnalysisCase.buildS1OpenAiSystemPrompt]（英文 schema），
 * 端侧走 [AnalysisCase.buildS1LocalSystemPrompt]（中文 few-shot）。
 * 这正是线上两套 prompt 的差异，不是评测自己另写一套。
 */
class S1EvalHttpTest {

    private data class Endpoint(
        val url: String,
        val key: String,
        val model: String
    )

    private data class EvalConfig(
        val cloud: Endpoint,
        val local: Endpoint,
        val out: File,
        val labelPath: File?,
        val plan: S1EvalBudgetCore.Plan
    )

    @Test
    fun cloudVsLocalS1AgreementAndAccuracy() {
        val cfg = readConfig()
        Assume.assumeTrue(
            "跳过：未配置评测端点（NOTIFYME_EVAL=1 或 app/build/eval/eval.properties）",
            cfg != null
        )
        val c = cfg!!

        val labelFile = c.labelPath ?: File("src/test/resources/eval-labels.json")
        Assume.assumeTrue("跳过：缺少标注样本 " + labelFile.absolutePath, labelFile.exists())

        val cases = S1Accuracy.parse(labelFile.readText())
        Assume.assumeTrue("跳过：标注样本为空", cases.isNotEmpty())

        val cloudResults = LinkedHashMap<String, AnalysisCase.Companion.S1Result>()
        val localResults = LinkedHashMap<String, AnalysisCase.Companion.S1Result>()
        val cloudRaw = LinkedHashMap<String, String>()
        val localRaw = LinkedHashMap<String, String>()
        val failures = ArrayList<String>()

        // 整轮预算：端点「能连上但不回」时，没有这道闸会一直等到单次 readTimeout × 样本数，
        // 实测把整个 testOpenDebugUnitTest 挂住 40 分钟。见 S1EvalBudgetCore 的类注释。
        val startedAt = System.currentTimeMillis()
        val skipped = ArrayList<String>()

        for (case in cases) {
            // 走生产窗口口径：取尾 WINDOW_SIZE、windowEnd 取末条时间、caseId 由 caseIdOf 生成
            val kase = AnalysisCase.fromMessages(case.pkg, case.conversation, case.messages)
            if (kase == null) {
                failures.add(case.conversation + ": 窗口为空")
                continue
            }
            val user = kase.analysisUserPrompt(kase.windowEnd)

            val cloudTimeout = S1EvalBudgetCore.callTimeoutMs(
                c.plan,
                S1EvalBudgetCore.remainingMs(startedAt, System.currentTimeMillis(), c.plan.totalBudgetMs)
            )
            if (cloudTimeout <= 0L) {
                skipped.add(case.conversation + " [cloud] 预算耗尽未测试")
            } else {
                runCatching {
                    val raw = chat(
                        c.cloud,
                        AnalysisCase.buildS1OpenAiSystemPrompt("", kase.windowEnd),
                        user,
                        cloudTimeout
                    )
                    cloudRaw[case.caseId] = raw
                    cloudResults[case.caseId] = AnalysisParsing.parseS1Result(
                        AnalysisParsing.extractJsonPayload(raw), "eval-cloud"
                    )
                }.onFailure { failures.add(case.conversation + " [cloud] " + it.message) }
            }

            val localTimeout = S1EvalBudgetCore.callTimeoutMs(
                c.plan,
                S1EvalBudgetCore.remainingMs(startedAt, System.currentTimeMillis(), c.plan.totalBudgetMs)
            )
            if (localTimeout <= 0L) {
                skipped.add(case.conversation + " [local] 预算耗尽未测试")
            } else {
                runCatching {
                    val raw = chat(
                        c.local,
                        AnalysisCase.buildS1LocalSystemPrompt("", kase.windowEnd),
                        user,
                        localTimeout
                    )
                    localRaw[case.caseId] = raw
                    localResults[case.caseId] = AnalysisParsing.parseS1Result(
                        AnalysisParsing.extractJsonFromText(raw), "eval-local"
                    )
                }.onFailure { failures.add(case.conversation + " [local] " + it.message) }
            }

            // 两侧都拿不到且预算已尽：后面每条都必然跳过，直接收尾，别再空转。
            if (S1EvalBudgetCore.exhausted(startedAt, System.currentTimeMillis(), c.plan.totalBudgetMs)) {
                val rest = cases.indexOf(case) + 1
                for (i in rest until cases.size) {
                    skipped.add(cases[i].conversation + " 预算耗尽未测试")
                }
                break
            }
        }

        val cloudSide = S1Accuracy.score("云端", cases, cloudResults)
        val localSide = S1Accuracy.score("端侧", cases, localResults)

        val pairs = cases.mapNotNull { case ->
            val cl = cloudResults[case.caseId] ?: return@mapNotNull null
            val lo = localResults[case.caseId] ?: return@mapNotNull null
            S1Agreement.Pair(
                pkg = case.pkg,
                conversation = case.conversation,
                windowEnd = case.windowEnd,
                cloud = cl,
                local = lo
            )
        }
        val summary = S1Agreement.of(pairs)
        val rows = S1Accuracy.rows(cases, cloudResults, localResults)

        val md = render(
            c, cases, cloudSide, localSide, summary, rows, cloudRaw, localRaw, failures, skipped
        )
        c.out.parentFile?.mkdirs()
        c.out.writeText(md)
        println("S1 评测报告：" + c.out.absolutePath)
        println("云端 need_action 命中 " + cloudSide.needActionOk + "/" + cloudSide.total)
        println("端侧 need_action 命中 " + localSide.needActionOk + "/" + localSide.total)
        println("两侧一致率 " + summary.agreementRate)

        // 断言的是「评测本身跑通」，不是「模型答对多少」——
        // 模型准确率是产出物，不该让构建挂掉。
        //
        // 预算耗尽时允许一侧为空：那时评测没有「跑不通」，只是没跑完，
        // 报告里已写明剩余多少条未测试。真正要拦的是「端点通、样本在、却解析不出」。
        if (skipped.isEmpty()) {
            assertTrue("云端一侧必须至少解析出 1 条判定", cloudResults.isNotEmpty())
            assertTrue("端侧一侧必须至少解析出 1 条判定", localResults.isNotEmpty())
            assertTrue(
                "云端解析率过低（" + cloudResults.size + "/" + cases.size + "），先查端点与 prompt",
                cloudResults.size * 2 >= cases.size
            )
            assertTrue(
                "端侧解析率过低（" + localResults.size + "/" + cases.size + "），先查端点与 prompt",
                localResults.size * 2 >= cases.size
            )
        } else {
            println(S1EvalBudgetCore.exhaustedNote(c.plan, skipped.size))
        }
    }

    private fun render(
        cfg: EvalConfig,
        cases: List<S1Accuracy.Case>,
        cloud: S1Accuracy.Side,
        local: S1Accuracy.Side,
        summary: S1Agreement.Summary,
        rows: List<S1Accuracy.Row>,
        cloudRaw: Map<String, String>,
        localRaw: Map<String, String>,
        failures: List<String>,
        skipped: List<String>
    ): String {
        val sb = StringBuilder()
        sb.append("# 云端 vs 端侧 S1 判定对比（ROADMAP M4 任务 6）\n\n")
        sb.append("> 生成时间：").append(timestamp()).append("\n")
        sb.append("> 样本：").append(cases.size).append(" 条人工标注合成会话（非真机采集）\n")
        sb.append("> 云端端点：").append(cfg.cloud.model).append(" @ ").append(cfg.cloud.url).append("\n")
        sb.append("> 端侧端点：").append(cfg.local.model).append(" @ ").append(cfg.local.url).append("\n")
        sb.append("> 判定口径：need_action = prob ≥ 0.5；escalate = prob ≥ 0.5 或 importance ≥ 6 或 confidence < 0.5\n")
        sb.append("> 时间预算：").append(S1EvalBudgetCore.describe(cfg.plan)).append("\n\n")

        sb.append("## 一、单侧准确率（相对人工标注）\n\n")
        sb.append(S1Accuracy.markdown(cloud, local))
        sb.append("\n")

        sb.append("## 二、两侧一致性（云端 vs 端侧，不需标注）\n\n")
        sb.append(S1Agreement.markdown(summary, timestamp()))
        sb.append("\n")

        sb.append("## 三、逐样本明细\n\n")
        sb.append("| 会话 | 标注 need_action | 云端 | 端侧 | 标注 importance | 云端 | 端侧 | 标注 due | 云端 | 端侧 | 标注 topic | 云端 | 端侧 |\n")
        sb.append("|---|---|---|---|---|---|---|---|---|---|---|---|---|\n")
        for (r in rows) {
            sb.append("| ").append(esc(r.conversation))
                .append(" | ").append(if (r.expect.needAction) "是" else "否")
                .append(" | ").append(yn(r.cloud?.needAction))
                .append(" | ").append(yn(r.local?.needAction))
                .append(" | ").append(fmt(r.expect.importance))
                .append(" | ").append(fmt(r.cloud?.importance))
                .append(" | ").append(fmt(r.local?.importance))
                .append(" | ").append(esc(r.expect.dueWindow))
                .append(" | ").append(esc(r.cloud?.dueWindow))
                .append(" | ").append(esc(r.local?.dueWindow))
                .append(" | ").append(esc(r.expect.topic))
                .append(" | ").append(esc(r.cloud?.topic))
                .append(" | ").append(esc(r.local?.topic))
                .append(" |\n")
        }
        sb.append("\n")

        val failed = rows.filter { it.cloud == null || it.local == null }
        if (failed.isNotEmpty() || failures.isNotEmpty()) {
            sb.append("## 四、未配对 / 失败明细（不计入准确率分母的命中）\n\n")
            for (f in failures) sb.append("- ").append(esc(f)).append("\n")
            sb.append("\n")
        }

        if (skipped.isNotEmpty()) {
            sb.append("## 四之二、预算耗尽未测试（不计入任何分母）\n\n")
            sb.append(S1EvalBudgetCore.exhaustedNote(cfg.plan, skipped.size)).append("\n\n")
            for (s in skipped) sb.append("- ").append(esc(s)).append("\n")
            sb.append("\n")
        }

        sb.append("## 五、原始输出（便于复核解析是否走形）\n\n")
        sb.append("### 云端样例\n\n")
        sb.append("\u0060\u0060\u0060json\n")
        cloudRaw.values.take(3).forEach { sb.append(esc(it)).append("\n") }
        sb.append("\u0060\u0060\u0060\n\n")
        sb.append("### 端侧样例\n\n")
        sb.append("\u0060\u0060\u0060json\n")
        localRaw.values.take(3).forEach { sb.append(esc(it)).append("\n") }
        sb.append("\u0060\u0060\u0060\n")
        return sb.toString()
    }

    private fun timestamp(): String =
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.CHINA).format(java.util.Date())

    private fun yn(v: Boolean?): String = when (v) {
        true -> "是"
        false -> "否"
        null -> "-"
    }

    private fun fmt(v: Double?): String = if (v == null) "-" else String.format(java.util.Locale.US, "%.1f", v)

    private fun esc(s: String?): String =
        (s ?: "").replace("|", "\\|").replace("\n", " ").take(80)

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
            put("max_tokens", 512)
            put("stream", false)
        }.toString()

        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            // 连接超时同样受整轮预算约束：写死 15s 时端点不可达会白等 15s × 样本数。
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
            out = File(get("OUT", "../docs/评测-云端vs端侧S1.md")),
            labelPath = labelPath,
            plan = S1EvalBudgetCore.resolvePlan(
                get("TOTAL_BUDGET_MS").takeIf { it.isNotBlank() },
                get("CALL_TIMEOUT_MS").takeIf { it.isNotBlank() }
            )
        )
    }
}
