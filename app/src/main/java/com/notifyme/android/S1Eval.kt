// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import android.content.Context
import java.io.File

/**
 * 云端 vs 端侧 S1 一致率的采集入口（ROADMAP M4 任务 6 / docs/ROADMAP.md:237）。
 *
 * 这个文件本身不含判定逻辑，只做「接线」：读样本 → [S1EvalRunner.collect] →
 * [S1Agreement.markdown] 写文件。判定口径全在 [S1Agreement]，采集与 prompt 复用
 * 全在 [S1EvalRunner]，所以评测数字与线上行为是一套代码。
 *
 * ## 怎么用（debug 源集）
 *
 * 1. 准备样本 JSON（[EvalDataset] 格式，可手搓、可从 files/messages.jsonl 导出）：
 *    ```
 *    adb -s <serial> shell run-as com.notifyme.android cat files/messages.jsonl > .tmp\msgs.jsonl
 *    ```
 * 2. 触发采集（debug 源集的 [S1EvalActivity]，见 app/src/debug）：
 *    ```
 *    adb -s <serial> shell am start -n com.notifyme.android/.S1EvalActivity \
 *        --es dataset /sdcard/Android/data/com.notifyme.android/files/eval.json \
 *        --es out /sdcard/Android/data/com.notifyme.android/files/s1-agreement.md \
 *        --ei windows 2 --ei limit 20
 *    ```
 * 3. 拉回报告，贴进 docs/ 评测记录：
 *    ```
 *    adb -s <serial> pull /sdcard/Android/data/com.notifyme.android/files/s1-agreement.md docs/
 *    ```
 *
 * 云端槽位从 [AnalysisConfig.loadSlot]（PRESET_SYSTEMONE）读，端侧走
 * [LocalLlmEngines] 的 MODEL_S1——两侧都用 App 里已配置的那一套，不另设参数，
 * 否则量出来的就不是线上差异。
 */
internal object S1Eval {

    private const val TAG = "S1Eval"

    /** 单个会话默认切几个窗口（重叠滑窗）。 */
    const val DEFAULT_WINDOWS = 2

    /** 单次采集默认上限（0 = 不限；防手滑把整库都跑一遍）。 */
    const val DEFAULT_LIMIT = 20

    /** 云端 S1 槽位。 */
    fun cloudSlot(config: AnalysisConfig): AnalysisConfig.SlotConfig =
        config.loadSlot(AnalysisConfig.PRESET_SYSTEMONE)

    /**
     * 采集一批窗口并生成 Markdown 报告文本。
     *
     * @return 报告文本；采集过程中没有可用配对时返回带失败原因的说明文本（不抛）。
     */
    suspend fun runMarkdown(
        context: Context,
        config: AnalysisConfig,
        datasetPath: File,
        windows: Int = DEFAULT_WINDOWS,
        limit: Int = DEFAULT_LIMIT,
        background: String = "",
        onProgress: ((done: Int, total: Int, conv: String) -> Unit)? = null
    ): String {
        val slot = cloudSlot(config)
        val report = S1EvalRunner.collect(
            context = context,
            datasetPath = datasetPath,
            cloudSlot = slot,
            windowCount = windows,
            limit = limit,
            background = background,
            onProgress = onProgress
        )
        // 口径在 PromptTimeCore.isoStamp（java.time，恒公历）：SimpleDateFormat 的历法随 Locale 走
        val generatedAt = PromptTimeCore.isoStamp(System.currentTimeMillis())

        val sb = StringBuilder()
        sb.append("# 云端 vs 端侧 S1 判定一致率（M4 任务 6）\n")
        sb.append("\n样本：").append(datasetPath.absolutePath).append("\n")
        sb.append("窗口：每会话 ").append(windows).append(" 个重叠窗口，共 ")
        sb.append(report.outcomes.size).append(" 个，成功配对 ").append(report.compared)
        sb.append("，失败 ").append(report.failures).append("\n")
        sb.append("云端槽位：").append(slot.model).append(" @ ").append(slot.url).append("\n")
        sb.append("端侧模型：").append(LocalModelStore.MODEL_S1).append("\n")
        sb.append("\n## 一致率\n\n")
        sb.append(S1Agreement.markdown(report.summary, generatedAt)).append("\n")

        val failed = report.outcomes.filter { it.pair == null }
        if (failed.isNotEmpty()) {
            sb.append("\n## 未配对成功的窗口（不计入一致率）\n\n")
            sb.append("| 会话 | windowEnd | 原因 |\n")
            sb.append("|---|---|---|\n")
            for (o in failed.take(20)) {
                sb.append("| ").append(o.conversation)
                sb.append(" | ").append(o.windowEnd)
                sb.append(" | ").append(escape(o.error ?: "未知"))
                sb.append(" |\n")
            }
            if (failed.size > 20) {
                sb.append("\n（其余 ").append(failed.size - 20).append(" 条省略）\n")
            }
        }
        return sb.toString()
    }

    /** 采集并落盘（报告写 [out]，同时返回文本）。 */
    suspend fun runToFile(
        context: Context,
        config: AnalysisConfig,
        datasetPath: File,
        out: File,
        windows: Int = DEFAULT_WINDOWS,
        limit: Int = DEFAULT_LIMIT,
        background: String = "",
        onProgress: ((done: Int, total: Int, conv: String) -> Unit)? = null
    ): String {
        val md = runMarkdown(
            context, config, datasetPath, windows, limit, background, onProgress
        )
        out.parentFile?.mkdirs()
        out.writeText(md)
        return md
    }

    /** 表格单元格里的管道符会把 Markdown 结构打乱，统一转义。 */
    private fun escape(s: String): String =
        TextClipCore.clip(s.replace("|", "\\|").replace("\n", " "), 120)
}
