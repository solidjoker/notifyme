// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import android.content.Context
import java.io.Closeable

/**
 * 端侧推理引擎抽象：对上层（AnalysisWorker）只暴露「system+user -> text」。
 *
 * 接入点设计（调研报告 §3 步骤 3）：
 *  - S1 快筛（MiniCPM4-0.5B-MNN）：复用 AnalysisWorker.runS1OpenAi 的
 *    system prompt + 防御式 JSON 解析，引擎只需实现本接口；
 *  - S2 深分析（MiniCPM3-4B-GGUF）：同理复用 runS2OpenAi 的 prompt 与解析。
 *
 * 下一步引擎实现路线（二选一或并存，此处均为占位）：
 *  1. MNN-LLM：NDK 源码编译 arm64-v8a + x86_64 so（build_64.sh 参数见调研报告
 *     §2.4），JNI 封装参考官方 MnnLlmChat 的 ChatService；加载入口为模型目录
 *     下的 config.json（llm_model/llm_weight/backend_type 等字段）。
 *  2. llama.cpp：MiniCPM3-4B GGUF 可用第三方 AAR
 *     `dev.ffmpegkit-maintained:llama-android:0.1.1`（仅 arm64-v8a、无流式），
 *     x86_64（MuMu 模拟器）需自编译或付费版。
 *
 * 当前交付的是 UnavailableLocalLlmEngine：模型文件已就绪时分析链路可走通到
 * 引擎调用点并优雅报「引擎未就绪」，JNI 库编好后替换实现即可，上层零改动。
 */
interface LocalLlmEngine {

    /** 引擎可用（native 库已加载）；不可用时应由上层降级，不要直接调 chat。 */
    val isReady: Boolean

    /** 引擎不可用的原因说明（用于状态文案与日志）。 */
    val unavailableReason: String

    /**
     * 生成式对话：system prompt + user 文本 -> 模型输出原文。
     * 实现要求：线程安全由上层 Mutex 保证（AnalysisWorker.ANALYSIS_MUTEX）；
     * 非流式，一次返回完整文本；超时/内存不足时抛 LocalEngineException。
     */
    suspend fun chat(system: String, user: String, maxTokens: Int): String
}

/** 本地引擎统一异常：message 直接展示到分析状态行。 */
class LocalEngineException(message: String) : Exception(message)

/** 推理库尚未编译进 APK 时的占位实现。 */
class UnavailableLocalLlmEngine : LocalLlmEngine {
    override val isReady: Boolean = false
    override val unavailableReason: String = "本地推理引擎未就绪（MNN/llama.cpp 原生库未编译）"
    override suspend fun chat(system: String, user: String, maxTokens: Int): String {
        throw LocalEngineException(unavailableReason)
    }
}

/**
 * 在飞请求计数包装：releaseAll()（onTrimMemory / 删除模型）会与 Worker 里正在
 * 执行的 chat() 并发，直接 close 就在 native 推理中途释放了句柄——use-after-free
 * 直接崩进程。这里把释放请求推迟到最后一个在飞请求结束，期间新请求直接拒绝。
 */
private class GuardedLocalLlmEngine(
    private val delegate: LocalLlmEngine
) : LocalLlmEngine, Closeable {

    private val lock = Any()
    private var inFlight = 0
    private var releaseRequested = false
    private var closed = false

    override val isReady: Boolean get() = delegate.isReady
    override val unavailableReason: String get() = delegate.unavailableReason

    override suspend fun chat(system: String, user: String, maxTokens: Int): String {
        synchronized(lock) {
            if (releaseRequested || closed) throw LocalEngineException("本地引擎已释放")
            inFlight += 1
        }
        try {
            return delegate.chat(system, user, maxTokens)
        } finally {
            synchronized(lock) { inFlight -= 1 }
            closeIfIdle()
        }
    }

    override fun close() {
        val immediate = synchronized(lock) {
            releaseRequested = true
            inFlight == 0 && !closed
        }
        if (immediate) closeIfIdle()
    }

    private fun closeIfIdle() {
        val doClose = synchronized(lock) {
            if (!releaseRequested || inFlight > 0 || closed) return
            closed = true
            true
        }
        if (doClose) (delegate as? Closeable)?.close()
    }
}

/**
 * 引擎工厂：按模型 id 返回对应引擎实例（全局单例，避免重复加载常驻内存）。
 * JNI 实现落地后在这里换为真实引擎（并按机型 RAM 做 4B 模型准入）。
 */
object LocalLlmEngines {

    /** S2（4B）上下文长度；留足会话窗口又控制内存。 */
    private const val S2_N_CTX = 4096
    private const val DEFAULT_N_CTX = 2048

    @Volatile
    private var instances = mapOf<String, LocalLlmEngine>()

    @Synchronized
    fun forModel(context: Context, modelId: String): LocalLlmEngine {
        instances[modelId]?.let { return it }
        val engine = createEngine(context.applicationContext, modelId)
        // 只缓存可用的引擎。unavailable 只是"当前不可用"的快照（模型没下载完、
        // 内存不足、文件缺失），把它永久缓存会让条件恢复后仍一直报不可用，只能重启。
        if (engine.isReady) {
            // 缓存的是带引用计数的包装：releaseAll 与在飞推理并发时不会中途释放句柄
            val guarded = GuardedLocalLlmEngine(engine)
            instances = instances + (modelId to guarded)
            return guarded
        }
        return engine
    }

    private fun unavailable(reason: String): LocalLlmEngine = object : LocalLlmEngine {
        override val isReady = false
        override val unavailableReason = reason
        override suspend fun chat(system: String, user: String, maxTokens: Int): String =
            throw LocalEngineException(reason)
    }

    private fun createEngine(context: Context, modelId: String): LocalLlmEngine {
        val model = LocalModelStore.model(modelId)
            ?: return unavailable("未知模型: $modelId")

        // llama.cpp only runs GGUF. S1 ships in MNN format and is not supported here.
        val gguf = model.files.firstOrNull { it.name.endsWith(".gguf") }
            ?: return unavailable("该模型为 MNN 格式，llama.cpp 路径不支持（待 MNN 接入）")

        if (!LocalModelStore.isReady(context, modelId)) {
            return unavailable("模型尚未下载完成：${model.displayName}")
        }

        // S2（4B）真机准入：架构/内存不达条件不开放，避免加载到一半 OOM
        if (modelId == LocalModelStore.MODEL_S2) {
            LocalDeviceCapabilities.s2BlockReason(context)?.let { return unavailable(it) }
        }

        val file = java.io.File(LocalModelStore.modelDir(context, modelId), gguf.name)
        if (!file.exists()) {
            return unavailable("模型文件缺失：${gguf.name}")
        }

        val nCtx = if (modelId == LocalModelStore.MODEL_S2) S2_N_CTX else DEFAULT_N_CTX
        return try {
            LlamaCppEngine(file, nCtx)
        } catch (e: Throwable) {
            unavailable("引擎加载失败：${e.message ?: "未知错误"}")
        }
    }

    /**
     * 释放全部已加载引擎（内存压力大或模型被删除时调用）。
     * 实际关闭可能被推迟到在飞推理结束——见 [GuardedLocalLlmEngine]。
     */
    @Synchronized
    fun releaseAll() {
        instances.values.forEach { (it as? java.io.Closeable)?.close() }
        instances = emptyMap()
    }
}
