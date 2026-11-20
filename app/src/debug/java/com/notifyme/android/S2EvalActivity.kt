// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import java.io.File
import kotlinx.coroutines.runBlocking

/**
 * 调试入口（仅 debug 源集）：在**真机**上跑一次「云端 vs 真机端侧 S2 摘要可用性」采集。
 *
 * ## 为什么需要它
 *
 * HTTP 侧评测（`S2EvalHttpTest`）的端侧那一列是 llama-server 上的 0.5B **替身**，
 * 参数差 4 倍，那 10~15% 的可用率只能当下限（见 `docs/评测-云端vs端侧S2.md` §4）。
 * 「端侧 2B 到底行不行」只有让真机自己跑才能回答。
 *
 * ## 用法
 *
 * ```
 * # 1) 把标注样本推到设备（与 HTTP 侧同一份 app/src/test/resources/eval-labels.json）
 * adb -s <serial> push app/src/test/resources/eval-labels.json \
 *     /sdcard/Android/data/com.notifyme.android/files/eval-labels.json
 *
 * # 2) 起采集
 * adb -s <serial> shell am start -n com.notifyme.android/.S2EvalActivity \
 *     --es dataset /sdcard/Android/data/com.notifyme.android/files/eval-labels.json \
 *     --es out     /sdcard/Android/data/com.notifyme.android/files/s2-usability.md \
 *     --ei limit 20
 *
 * # 3) 取报告
 * adb -s <serial> pull /sdcard/Android/data/com.notifyme.android/files/s2-usability.md docs/
 * ```
 *
 * ## 前置条件
 *
 * 1. **端侧 S2 模型已下载**（`LocalModelStore.MODEL_S2 = "minicpm5_2b"`，MiniCPM5-2B-GGUF，
 *    约 1.5 GB）。没下载时该样本按「未交付」记入报告，不进分母 —— 报告里会看到 0 条交付。
 * 2. **云端槽位已配置**（App 的分析设置里配好 URL / key / 模型）。没配就只采端侧一侧
 *    （云端那列全是「未交付」），仍然能给出端侧真机数字。
 * 3. 云端从设备访问本机服务端需要端口转发：
 *    `adb reverse tcp:8000 tcp:8000`（App 里填 `http://127.0.0.1:8000/v1`）。
 *
 * ## 时间
 *
 * 真机 2B 单条比 0.5B 替身慢一个量级（2B + 4096 tokens）。整轮预算由
 * `--ei budget_ms` / `--ei timeout_ms` 控制，耗尽即停并把剩余条数写进报告
 * （复用 `S1EvalBudgetCore`，与 S1/S2 评测同口径）。默认给 25 分钟。
 */
class S2EvalActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dataset = intent.getStringExtra("dataset") ?: ""
        val out = intent.getStringExtra("out")
            ?: File(filesDir, "s2-usability.md").absolutePath
        val limit = intent.getIntExtra("limit", DEFAULT_LIMIT)
        val budgetMs = intent.getLongExtra("budget_ms", DEFAULT_BUDGET_MS)
        val timeoutMs = intent.getLongExtra("timeout_ms", DEFAULT_TIMEOUT_MS)

        if (dataset.isBlank()) {
            Toast.makeText(this, "缺少 --es dataset", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val datasetFile = File(dataset)
        if (!datasetFile.exists()) {
            Toast.makeText(this, "样本不存在：$dataset", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        Thread {
            try {
                val config = AnalysisConfig(applicationContext)
                val plan = S1EvalBudgetCore.resolvePlan(
                    budgetMs.toString(),
                    timeoutMs.toString()
                )
                val report = runBlocking {
                    S2EvalRunner.collect(
                        context = applicationContext,
                        datasetPath = datasetFile,
                        cloudSlot = config.s2Slot(),
                        limit = limit,
                        plan = plan,
                        onProgress = { done, total, conv ->
                            Log.i(TAG, "进度 $done/$total · $conv")
                        }
                    )
                }
                val md = S2EvalReport.render(
                    header = listOf(
                        "> 生成时间：" + LocaleTextCore.seconds(System.currentTimeMillis()),
                        "> 样本：" + report.cases.size + " 条人工标注合成会话",
                        "> 端侧：**真机** MiniCPM5-2B（LocalLlmEngines，与生产 runS2Local 同一条路径）",
                        "> 云端：分析设置里的槽位（未配置则本轮只采端侧）",
                        "> 时间预算：" + S1EvalBudgetCore.describe(report.plan),
                        "> 判据：" + S2Usability.legend()
                    ),
                    cases = report.cases,
                    cloud = report.cloud,
                    local = report.local,
                    rows = report.rows,
                    cloudOutcomes = report.outcomes.associate { it.caseId to it.cloud },
                    localOutcomes = report.outcomes.associate { it.caseId to it.local },
                    cloudRaw = report.cloudRaw,
                    localRaw = report.localRaw,
                    skipped = report.skipped,
                    limits = listOf(
                        "**S1 输入用人工标注而不是模型结论**：need_action 转 prob 1.0/0.0，" +
                            "importance/due_window/topic 直接用标注值 —— 把「S2 输出可不可用」与" +
                            "「S1 判得准不准」解耦，后者已由 `docs/评测-云端vs端侧S1.md` 单独量化。",
                        "**端侧是真机 MiniCPM5-2B**（约 1.5 GB，chat 模板 + 结构化约束），" +
                            "走 `LocalLlmEngines.forModel` —— 与生产 `runS2Local` 同一条代码路径。" +
                            "与 HTTP 侧评测里那个 0.5B 替身（10~15%）**不是同一个模型**，" +
                            "两列数字不要直接相减看差值。",
                        "**「可用」是六项判据全过**，判据都在窗口内的客观事实（S1 桶、窗口末条时间）上，" +
                            "不看措辞好坏。措辞好不好请直接看第五节的原始样例。",
                        "**占位照抄不抛异常**：生产链路会 `rejectPlaceholderEcho` 回退 S1，" +
                            "评测里保留输出并记一条 `非占位照抄` 失败，才能统计「模型跑飞」的比例。"
                    ),
                    plan = report.plan
                )
                val outFile = File(out)
                outFile.parentFile?.mkdirs()
                outFile.writeText(md, Charsets.UTF_8)
                Log.i(TAG, "报告已写入 $out，共 ${md.length} 字符")
                Log.i(
                    TAG,
                    "端侧可用 ${report.local.usable}/${report.local.total}，" +
                        "已交付 ${report.local.delivered}"
                )
                runOnUiThread {
                    Toast.makeText(this, "报告：$out", Toast.LENGTH_LONG).show()
                }
            } catch (e: Throwable) {
                Log.e(TAG, "采集失败", e)
                runOnUiThread {
                    Toast.makeText(this, "采集失败：${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.apply {
            name = "S2Eval"
            isDaemon = false
            start()
        }
        finish()
    }

    companion object {
        private const val TAG = "S2Eval"

        /** 默认采 20 条（与 HTTP 侧评测同一批标注样本）。 */
        private const val DEFAULT_LIMIT = 20

        /** 整轮预算 25 分钟（真机 2B 比 0.5B 替身慢一个量级）。 */
        private const val DEFAULT_BUDGET_MS = 25L * 60L * 1000L

        /** 单次调用上限 120 秒。 */
        private const val DEFAULT_TIMEOUT_MS = 120L * 1000L
    }
}
