// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

/**
 * llama.cpp 原生桥（M4）。.so 由 `app/src/main/cpp/CMakeLists.txt` 构建，
 * 名称 `notifyme-llama`，CPU 后端静态链接、arm64-v8a + x86_64 双 ABI。
 *
 * 句柄是 native 侧 llama_state 的指针；用完必须 [nativeDestroy]，
 * 进程死亡时 OS 回收也可，但正常路径交给 [LlamaCppEngine.close]。
 */
internal object LlamaJni {

    init {
        System.loadLibrary("notifyme-llama")
    }

    /**
     * 原生推理进度接收端（M15）。
     *
     * native 侧在 prefill 每批、decode 每若干 token 调用一次；实现必须
     * 立刻返回且不抛异常（JNI 侧会清异常，但抛异常会拖慢推理）。
     */
    fun interface ProgressSink {
        /** @param phase [LocalLlmProgress.PHASE_PREFILL] / [LocalLlmProgress.PHASE_DECODE] */
        fun onProgress(phase: Int, current: Int, total: Int)
    }

    /** 进程级一次性初始化（llama_backend_init，内部有幂等保护）。 */
    external fun nativeBackendInit()

    /** 加载 GGUF 并建上下文，返回句柄；失败抛 RuntimeException。 */
    external fun nativeCreate(modelPath: String, nCtx: Int): Long

    /**
     * 非流式补全：返回完整助手文本；输入超长/解码失败抛 RuntimeException。
     *
     * [progress] 可为 null（不需要进度时省掉 JNI 回调开销）。
     */
    external fun nativeComplete(
        handle: Long,
        system: String,
        user: String,
        maxTokens: Int,
        temperature: Float,
        progress: ProgressSink?
    ): String?

    external fun nativeDestroy(handle: Long)

    /** 原生系统信息（CPU 特性串），供诊断展示。 */
    external fun nativeSystemInfo(): String
}
