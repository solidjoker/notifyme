// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import android.content.Context
import android.os.Bundle
import android.util.Log

/**
 * 调试入口（仅 debug 源集）：端到端触发真实 S1=laya_ondevice 分析链路。
 *
 * 做四件事：
 *  1. AnalysisConfig：enabled=true、s1_type=laya_ondevice、s2_enabled=false（排除云端依赖）；
 *  2. MessageStore 注入一组真实形态的微信消息（sender/text/timestamp/conversation）；
 *  3. AnalysisScheduler.enqueueAnalysisNow(forceKey)——force 模式跳过关注名单与窗口去重；
 *  4. 立即 finish，结果看 logcat TAG=AnalysisWorker / files/analysis.jsonl。
 *
 * 触发：adb shell am start -n com.notifyme.android/.AnalysisE2EActivity --es conv laya-e2e
 * 前置：files/models/laya_ml/ 七文件齐全（或先跑 LayaGateActivity 确认模型就绪）。
 */
class AnalysisE2EActivity : android.app.Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val conv = intent?.getStringExtra("conv")?.takeIf { it.isNotBlank() } ?: "laya-e2e"
        val appCtx: Context = applicationContext
        Thread {
            try {
                val config = AnalysisConfig(appCtx)
                config.enabled = true
                config.s1Type = AnalysisConfig.S1_TYPE_LAYA_ONDEVICE
                config.s2Enabled = false
                // --es advisor local：用 App 自己的 API 开本地评审（绕开 adb 改文件的 prefs 缓存竞态）
                if (intent?.getStringExtra("advisor") == "local") {
                    config.advisorEnabled = true
                    config.advisorProvider = AnalysisConfig.ADVISOR_PROVIDER_LOCAL
                }
                Log.i(TAG, "config: enabled=" + config.enabled + " s1=" + config.s1Type + " advisor=" + config.advisorProvider)

                val pkg = AppSourceRegistry.PKG_WECHAT
                val base = System.currentTimeMillis()
                val msgs = listOf(
                    ChatMessage("张伟", "周五之前把分发包的验收报告发我一下，客户那边催得紧。", base - 300_000, conv, false),
                    ChatMessage("李静", "收到。另外测试机的 crash 日志也一起看下，昨天又闪退了一次。", base - 240_000, conv, false),
                    ChatMessage("张伟", "好，优先级排高一点，周四上午我要看到东西。", base - 180_000, conv, false)
                )
                for (m in msgs) MessageStore.append(appCtx, m)
                Log.i(TAG, "injected " + msgs.size + " messages into " + conv)

                AnalysisScheduler.enqueueAnalysisNow(appCtx, ConvKey(pkg, conv))
                Log.i(TAG, "analysis enqueued (force " + pkg + "/" + conv + ")")
            } catch (e: Throwable) {
                Log.e(TAG, "e2e setup failed", e)
            }
        }.apply {
            name = "AnalysisE2E"
            isDaemon = false
            start()
        }
        finish()
    }

    companion object {
        private const val TAG = "AnalysisE2E"
    }
}
