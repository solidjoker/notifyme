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
            Log.e(TAG, "缺少 --es dataset")
            Toast.makeText(this, "缺少 --es dataset", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val datasetFile = File(dataset)
        if (!datasetFile.exists()) {
            // 静默退出过一次：只弹 Toast，logcat 里什么都没有，排查时以为采集器崩了。
            // 每次提前退出都留一行日志（TAG=S2Eval），adb logcat -s S2Eval 就能看到原因。
            Log.e(
                TAG,
                "样本不存在：$dataset（绝对路径=${datasetFile.absolutePath}，" +
                    "可读=${datasetFile.canRead()}）"
            )
            Toast.makeText(this, "样本不存在：$dataset", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        Thread {
            // 采集期间把 App 的「分析总开关」关掉并停掉周期排期，采完恢复原状。
            //
            // 为什么必须：端侧引擎是**进程级单例**（LocalLlmEngines），App 自己的
            // 周期分析与本采集共用同一个 llama context。两者同时 prefill 会把 KV
            // cache 的位置写乱（llama_decode 报 "inconsistent sequence positions"），
            // 真机实测：采集条目全部「prefill 解码失败」、0 交付，App 进程还会崩。
            // AnalysisWorker.ANALYSIS_MUTEX 已经把两边的推理串行化，但排队要等
            // 整个生产轮跑完（真机一轮 10 个会话 ≈ 20 分钟），会把采集的整轮预算吃光 ——
            // 所以这里直接停掉周期排期。
            //
            // 只在 debug 源集里做，release 包不含这个 Activity，生产链路零影响。
            val cfg = AnalysisConfig(applicationContext)
            val savedEnabled = cfg.enabled
            val savedInterval = cfg.intervalMinutes
            var paused = false
            try {
                if (savedEnabled) {
                    cfg.enabled = false
                    AnalysisScheduler.cancel(applicationContext)
                    paused = true
                    Log.i(TAG, "采集期间暂停周期分析（结束后恢复 enabled=$savedEnabled）")
                }
                val slot = cfg.s2Slot()
                val plan = S1EvalBudgetCore.resolvePlan(
                    budgetMs.toString(),
                    timeoutMs.toString()
                )
                val labelBytes = datasetFile.readBytes()
                val outFile = File(out)
                // 中间结果落盘：真机跑 25 分钟可能被系统杀掉（模型常驻 1.5GB + KV，
                // 是低内存 killer 的首选目标），被杀时至少留一份半成品，
                // 而不是「报告文件连 0 字节都没有」。
                val partialFile = File(out + ".partial")
                val report = runBlocking {
                    S2EvalRunner.collect(
                        context = applicationContext,
                        datasetPath = datasetFile,
                        cloudSlot = slot,
                        limit = limit,
                        plan = plan,
                        onProgress = { done, total, conv ->
                            Log.i(TAG, "进度 $done/$total · $conv")
                        },
                        onPartial = { done ->
                            runCatching {
                                partialFile.parentFile?.mkdirs()
                                partialFile.writeText(
                                    partialMarkdown(done, slot, labelBytes, plan),
                                    Charsets.UTF_8
                                )
                            }.onFailure { Log.w(TAG, "中间结果落盘失败", it) }
                        }
                    )
                }
                val md = S2EvalReport.render(
                    header = listOf(
                        "> 生成时间：" + LocaleTextCore.seconds(System.currentTimeMillis()),
                        "> 样本：" + report.cases.size + " 条人工标注合成会话",
                        "> 样本指纹：sha256=" + S2EvalReport.sampleFingerprint(labelBytes),
                        // 端侧写**实际交付情况**：写死「真机 MiniCPM5-2B」会把
                        // 「设备/模型没就绪」读成「2B 模型不行」——
                        // 报告里最容易被误读的就是这一句。
                        // 判据用**实际交付条数**，不只看引擎就绪快照：
                        // 引擎就绪但推理全失败（OOM/超时/解码失败）也是 0 交付，
                        // 那时写「已就绪」照样会被读成「2B 不行」。
                        // 三态要分清：**没就绪**（没跑）/ **跑了但全挂**（跑了，输出不合格）/
                        // **交付了**。第二态写「引擎已就绪但 0 交付：<真实原因>」，
                        // 别写成「引擎未加载」——那是没就绪时的说法。
                        "> 端侧：" + if (report.localDelivered > 0) {
                            "MiniCPM5-2B（**已交付 ${report.localDelivered} 条**，" +
                                "LocalLlmEngines，与生产 runS2Local 同一条路径）"
                        } else if (report.localEngineReady) {
                            "MiniCPM5-2B（引擎**已就绪**，但**本轮 0 交付**：" +
                                "${report.localUnavailableReason.ifBlank { "全部推理失败" }})" +
                                "——下面那列 0% 读作「未测」，不是「2B 不行」"
                        } else {
                            "MiniCPM5-2B（**本轮 0 交付**：${report.localUnavailableReason}）" +
                                "——下面那列 0% 读作「未测」，不是「2B 不行」"
                        },
                        "> 云端端点：" + (slot?.let { it.model + " @ " + it.url } ?: "未配置（本轮只采端侧）"),
                        "> " + S2EvalReport.timezoneLine(),
                        "> 时间预算：" + S1EvalBudgetCore.describe(report.plan),
                        "> 判据：" + S2Usability.legend()
                    ),
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
                            "评测里保留输出并记一条 `非占位照抄` 失败，才能统计「模型跑飞」的比例。",
                        "**端侧采样没钉**：LlamaCppEngine 默认 temperature=0.3、无 seed（与生产同路径），" +
                            "实际生成上限 = n_ctx(4096) − prompt 占用（≈3586），不是 4096 —— " +
                            "端侧数字两次跑会不同，按区间读；云端那一侧钉了 temperature=0 + seed。",
                        "**端侧单次调用没有硬超时**：native 推理是阻塞 JNI 调用，协程取消不了它；" +
                            "报告里「单次上限」对端侧这一列是尽力而为，实际耗时可能超过。",
                        "**「未交付」只不进分项通过率的分母**：第一节「六项全过」那格的分母是**样本数**，" +
                            "所以端侧一条都没交付时那一格会显示 0/20 —— 读作**未测**，不是 0% 可用。",
                        "**采集期间别让 App 同时分析**：端侧引擎不持 App 的分析互斥锁，" +
                            "两边共用同一个 llama context，会互相清 KV、把输出弄走形。" +
                            "跑之前先关掉周期分析与悬浮窗。"
                    ),
                    plan = report.plan
                )
                val reportFile = File(out)
                reportFile.parentFile?.mkdirs()
                reportFile.writeText(md, Charsets.UTF_8)
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
            } finally {
                // 恢复周期分析（只在暂停过的情况下恢复，别把用户原本关着的开关打开）
                if (paused) {
                    runCatching {
                        cfg.enabled = savedEnabled
                        if (savedEnabled) {
                            AnalysisScheduler.schedule(applicationContext, savedInterval)
                        }
                        Log.i(TAG, "已恢复周期分析（enabled=$savedEnabled，间隔 ${savedInterval}min）")
                    }.onFailure { Log.w(TAG, "恢复周期分析失败", it) }
                }
            }
        }.apply {
            name = "S2Eval"
            isDaemon = false
            start()
        }
        finish()
    }

    /** 中间结果用的半成品报告（结构与最终报告一致，只是样本还没跑完）。 */
    private fun partialMarkdown(
        done: List<S2EvalRunner.Outcome>,
        slot: AnalysisConfig.SlotConfig?,
        labelBytes: ByteArray,
        plan: S1EvalBudgetCore.Plan
    ): String {
        val cases = done.map {
            S1Accuracy.Case(
                pkg = it.cloud.conversation.ifEmpty { "com.unknown" },
                conversation = it.conversation,
                isGroup = false,
                messages = emptyList(),
                expect = S1Accuracy.Expect(false, 0.0, "none", "smalltalk")
            )
        }
        val s1Of: (S1Accuracy.Case) -> AnalysisCase.Companion.S1Result = { S2Usability.labelAsS1(it) }
        val cloudOutcomes = done.associate { it.caseId to it.cloud }
        val localOutcomes = done.associate { it.caseId to it.local }
        return S2EvalReport.render(
            header = listOf(
                "> 生成时间：" + LocaleTextCore.seconds(System.currentTimeMillis()),
                "> **中间结果（采集未完成）**：已完成 " + done.size + " 条",
                "> 样本指纹：sha256=" + S2EvalReport.sampleFingerprint(labelBytes),
                "> 云端端点：" + (slot?.let { it.model + " @ " + it.url } ?: "未配置（本轮只采端侧）"),
                "> " + S2EvalReport.timezoneLine()
            ),
            cloud = S2Usability.score("云端", cases, cloudOutcomes, s1Of),
            local = S2Usability.score("端侧", cases, localOutcomes, s1Of),
            rows = emptyList(),
            cloudOutcomes = cloudOutcomes,
            localOutcomes = localOutcomes,
            cloudRaw = emptyMap(),
            localRaw = emptyMap(),
            skipped = emptyList(),
            limits = listOf("这是采集被中断时的半成品，最终报告以正式那份为准。"),
            plan = plan
        )
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
