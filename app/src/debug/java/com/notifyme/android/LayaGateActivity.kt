// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import android.content.Context
import android.os.Bundle
import android.util.Log
import com.notifyme.android.laya.LayaEngine

/**
 * 调试入口（仅 debug 源集）：无界面跑官方 201 行 fixture 设备门，产出
 * files/laya_gate_<backend>_<window>.json 证据报告并打 LAYA_GATE logcat。
 *
 * 触发：adb shell am start -n com.notifyme.android.test/.LayaGateActivity --es backend cpu --ei window 256
 * 前置：模型在 files/models/laya_ml/，fixture 在 files/fixtures/gate_rows_s256.json。
 */
class LayaGateActivity : android.app.Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val backendName = intent?.getStringExtra("backend") ?: "cpu"
        val window = intent?.getIntExtra("window", 256) ?: 256
        val appCtx: Context = applicationContext
        Log.i(TAG, "gate starting backend=" + backendName + " window=" + window)
        Thread {
            val line = try {
                val backend = LayaEngine.Backend.fromArgument(backendName)
                val engine = LayaTfliteEngine.obtainEngine(appCtx)
                val report = LayaGateRunner(appCtx, engine).run(backend, window)
                "gate " + report.status + " -> " + report.path + " error=" + (report.error ?: "none")
            } catch (e: Throwable) {
                Log.e(TAG, "gate execution failed", e)
                "gate EXCEPTION: " + e.javaClass.simpleName + ": " + e.message
            }
            Log.i(TAG, line)
        }.apply {
            name = "LayaGate"
            // 非守护线程：NoDisplay 入口必须 onCreate 里立即 finish()（否则 IllegalStateException
            // "did not call finish() prior to onResume() completing"），finish 后进程若转后台，
            // 守护线程会被丢掉；非守护线程保证 201 行门跑完。
            isDaemon = false
            start()
        }
        finish()
    }

    companion object {
        private const val TAG = "LAYA_GATE"
    }
}
