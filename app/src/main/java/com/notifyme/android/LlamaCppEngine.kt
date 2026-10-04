// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import java.io.Closeable
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 基于 llama.cpp 的端侧引擎实现（M4）。
 *
 * 构造即加载 GGUF（调用方应在 IO 线程上首次获取）；加载失败由
 * [LocalLlmEngines.forModel] 接住并降级为 Unavailable，不直接暴露异常给 UI。
 *
 * @param ggufFile    本地 GGUF 文件（app 私有目录）
 * @param nCtx        上下文 token 数
 * @param temperature 采样温度；分析任务要稳定 JSON，默认 0.3
 */
class LlamaCppEngine(
    ggufFile: File,
    private val nCtx: Int,
    private val temperature: Float = 0.3f
) : LocalLlmEngine, Closeable {

    private var handle: Long = 0L

    init {
        LlamaJni.nativeBackendInit()
        handle = LlamaJni.nativeCreate(ggufFile.absolutePath, nCtx)
    }

    override val isReady: Boolean get() = handle != 0L
    override val unavailableReason: String get() = "llama.cpp 引擎未加载"

    override suspend fun chat(system: String, user: String, maxTokens: Int): String =
        withContext(Dispatchers.IO) {
            try {
                LlamaJni.nativeComplete(
                    handle, system, user, maxTokens, temperature
                ).orEmpty()
            } catch (e: Throwable) {
                throw LocalEngineException(e.message ?: "本地推理失败")
            }
        }

    override fun close() {
        val h = handle
        if (h != 0L) {
            handle = 0L
            runCatching { LlamaJni.nativeDestroy(h) }
        }
    }
}
