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
 * 调试入口（仅 debug 源集）：跑一次「云端 vs 端侧 S1 一致率」采集。
 *
 * 用法：
 * ```
 * adb -s <serial> shell am start -n com.notifyme.android/.S1EvalActivity \
 *     --es dataset /sdcard/Android/data/com.notifyme.android/files/eval.json \
 *     --es out     /sdcard/Android/data/com.notifyme.android/files/s1-agreement.md \
 *     --ei windows 2 --ei limit 20
 * ```
 * 结果：报告写到 --es out 指定的文件；同时在 logcat 打一行摘要（TAG=S1Eval）。
 *
 * 依赖：App 里已配置可用的云端 S1 槽位（分析设置），且端侧 S1 模型已下载。
 */
class S1EvalActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dataset = intent.getStringExtra("dataset")
            ?: ""
        val out = intent.getStringExtra("out")
            ?: File(filesDir, "s1-agreement.md").absolutePath
        val windows = intent.getIntExtra("windows", S1Eval.DEFAULT_WINDOWS)
        val limit = intent.getIntExtra("limit", S1Eval.DEFAULT_LIMIT)
        val background = intent.getStringExtra("background") ?: ""

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
                val md = runBlocking { S1Eval.runToFile(
                    context = applicationContext,
                    config = config,
                    datasetPath = datasetFile,
                    out = File(out),
                    windows = windows,
                    limit = limit,
                    background = background,
                    onProgress = { done, total, conv ->
                        Log.i(TAG, "进度 $done/$total · $conv")
                    }
                ) }
                Log.i(TAG, "报告已写入 $out，共 ${md.length} 字符")
                // 摘要直接打一行，方便 CI / adb 直接 grep 结论
                for (line in md.lineSequence().filter { it.startsWith("结论：") }) {
                    Log.i(TAG, line)
                }
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
            name = "S1Eval"
            isDaemon = false
            start()
        }
        finish()
    }

    companion object {
        private const val TAG = "S1Eval"
    }
}
