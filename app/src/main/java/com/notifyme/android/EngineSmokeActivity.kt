// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * M4 推理冒烟页：加载 files/smoke.gguf 并跑一次 system+user 补全。
 *
 * 仅 debug 构建使用——release 没有入口按钮，且 onCreate 直接 finish 兜底。
 * 不接入 AnalysisWorker，目的是隔离验证「GGUF 加载 + 模板 + 解码 + 采样」
 * 原生链路本身是否工作。
 */
class EngineSmokeActivity : Activity() {

    companion object {
        private const val SMOKE_GGUF = "smoke.gguf"
        private const val SMOKE_N_CTX = 2048
        private const val SMOKE_MAX_TOKENS = 128
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!BuildConfig.DEBUG) { finish(); return }
        setContentView(R.layout.activity_engine_smoke)

        val tvStatus = findViewById<TextView>(R.id.tvSmokeStatus)
        val tvResult = findViewById<TextView>(R.id.tvSmokeResult)
        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }

        findViewById<Button>(R.id.btnRunSmoke).setOnClickListener {
            val gguf = File(filesDir, SMOKE_GGUF)
            if (!gguf.exists()) {
                tvStatus.setText(R.string.smoke_missing_model)
                return@setOnClickListener
            }
            tvStatus.setText(R.string.smoke_loading)
            tvResult.text = ""

            CoroutineScope(Dispatchers.Main).launch {
                val started = System.currentTimeMillis()
                val outcome = withContext(Dispatchers.IO) {
                    runCatching {
                        LlamaCppEngine(gguf, SMOKE_N_CTX).use { engine ->
                            engine.chat(
                                system = "你是一个简洁的助手，用一句中文回答。",
                                user = "用一句话介绍你自己。",
                                maxTokens = SMOKE_MAX_TOKENS
                            )
                        }
                    }
                }
                val ms = System.currentTimeMillis() - started
                outcome
                    .onSuccess {
                        tvStatus.text = getString(R.string.smoke_done, ms)
                        tvResult.text = it
                    }
                    .onFailure {
                        tvStatus.text = getString(R.string.smoke_failed, it.message ?: "")
                    }
            }
        }
    }
}
