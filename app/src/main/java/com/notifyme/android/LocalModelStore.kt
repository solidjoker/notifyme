// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import android.content.Context
import java.io.File

/**
 * 端侧本地模型清单与状态管理（SharedPreferences 文件名 local_model_store）。
 *
 * 模型清单内置两个（调研报告 docs/端侧本地模型接入调研报告.md §2.2/§2.3）：
 *  - MODEL_S1：Qwen2.5-0.5B-Instruct GGUF Q4_K_M（469 MB，llama.cpp），S1 快筛用
 *    （id 沿用 minicpm4_0_5b 历史值；Laya 本体是 ModernBERT 判别式架构，
 *    llama.cpp 上游支持 PR #29363 尚未合并，端侧暂以生成式小模型代行快筛）；
 *  - MODEL_S2：MiniCPM5-2B-GGUF Q4_K_M（约 1.5 GB），S2 深分析用（替代 MiniCPM3-4B）。
 *  - MODEL_LAYA：laya-multilingual LiteRT TFLite（~0.68 GB），S1 laya 判定用（端侧 TFLite 推理）。
 *
 * 下载源：ModelScope 直链（国内 CDN，已实测支持 Range 断点续传）。
 * 各文件预期大小已用 Range 0-0 探针实测（2026-10-03），下载完成后按字节数校验。
 *
 * 目录结构：files/models/{modelId}/{fileName}，下载中的临时文件为 {fileName}.part。
 * 状态机：none -> downloading -> ready / error；取消下载回到 none（.part 保留可续传）。
 */
object LocalModelStore {

    const val MODEL_S1 = "minicpm4_0_5b"
    const val MODEL_S2 = "minicpm5_2b"
    const val MODEL_LAYA = "laya_ml"

    private const val PREFS_NAME = "local_model_store"

    const val STATE_NONE = "none"
    const val STATE_DOWNLOADING = "downloading"
    const val STATE_READY = "ready"
    const val STATE_ERROR = "error"

    /** 单个模型文件：name=落盘文件名，url=ModelScope 直链，size=预期字节数（实测） */
    data class ModelFile(val name: String, val url: String, val size: Long)

    data class LocalModel(
        val id: String,
        val displayName: String,
        val roleLabel: String,   // 用途说明（S1 快筛 / S2 深分析）
        val sizeLabel: String,   // 人类可读大小
        val files: List<ModelFile>
    ) {
        val totalBytes: Long get() = files.sumOf { it.size }
    }

    private const val MS_S1 = "https://www.modelscope.cn/models/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/master"
    private const val MS_S2 = "https://www.modelscope.cn/models/OpenBMB/MiniCPM5-2B-GGUF/resolve/master"
    private const val HF_LAYA = "https://huggingface.co/litert-community/Laya-Multilingual-LiteRT/resolve/main"

    val MODELS: List<LocalModel> = listOf(
        LocalModel(
            id = MODEL_S1,
            displayName = "Qwen2.5-0.5B-Instruct（GGUF Q4_K_M）",
            roleLabel = "System 1 轻量判定 · 端侧快筛",
            sizeLabel = "469 MB",
            files = listOf(
                ModelFile("qwen2.5-0.5b-instruct-q4_k_m.gguf",
                    "$MS_S1/qwen2.5-0.5b-instruct-q4_k_m.gguf", 491_400_032L)
            )
        ),
        LocalModel(
            id = MODEL_S2,
            displayName = "MiniCPM5-2B（GGUF Q4_K_M）",
            roleLabel = "System 2 深度分析 · 端侧生成（128K 上下文 · 支持 Function Call）",
            sizeLabel = "~1.5 GB",
            files = listOf(
                ModelFile("MiniCPM5-2B-Q4_K_M.gguf", "$MS_S2/MiniCPM5-2B-Q4_K_M.gguf", 1_561_318_368L)
            )
        ),
        LocalModel(
            id = MODEL_LAYA,
            displayName = "Laya-Multilingual（LiteRT TFLite）",
            roleLabel = "System 1 Laya 判定 · 端侧 TFLite 推理（mmBERT 多语言 · 51ms/问）",
            sizeLabel = "~0.68 GB",
            files = listOf(
                ModelFile("laya_ml_s256_wfp16.tflite", "$HF_LAYA/laya_ml_s256_wfp16.tflite", 644_077_360L),
                ModelFile("laya_ml_act_head_fp32.tflite", "$HF_LAYA/laya_ml_act_head_fp32.tflite", 795_816L),
                ModelFile("tokenizer.json", "$HF_LAYA/multilingual/tokenizer.json", -1L),
                ModelFile("tokenizer_config.json", "$HF_LAYA/multilingual/tokenizer_config.json", -1L),
                ModelFile("calibration.json", "$HF_LAYA/multilingual/calibration.json", -1L)
            )
        )
    )

    fun model(id: String): LocalModel? = MODELS.firstOrNull { it.id == id }

    /** 模型落盘目录：files/models/{id}/ */
    fun modelDir(context: Context, id: String): File =
        File(context.applicationContext.filesDir, "models/$id")

    // ---------- 状态读写 ----------

    private fun prefs(context: Context) = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun state(context: Context, id: String): String =
        prefs(context).getString("${id}_state", STATE_NONE) ?: STATE_NONE

    /** 已下载字节数（worker 周期写入；含已完成文件 + 当前 .part 已下部分） */
    fun downloadedBytes(context: Context, id: String): Long =
        prefs(context).getLong("${id}_downloaded", 0L)

    fun lastError(context: Context, id: String): String =
        prefs(context).getString("${id}_error", "").orEmpty()

    fun setState(context: Context, id: String, state: String, error: String = "") {
        prefs(context).edit()
            .putString("${id}_state", state)
            .putString("${id}_error", error)
            .apply()
    }

    fun setDownloaded(context: Context, id: String, bytes: Long) {
        prefs(context).edit().putLong("${id}_downloaded", bytes).apply()
    }

    /**
     * 就绪判断：状态为 ready 且所有文件存在且字节数与预期一致（防半拉子文件）。
     */
    fun isReady(context: Context, id: String): Boolean {
        if (state(context, id) != STATE_READY) return false
        val m = model(id) ?: return false
        val dir = modelDir(context, id)
        return m.files.all { f ->
            val file = File(dir, f.name)
            file.exists() && (f.size <= 0 || file.length() == f.size)
        }
    }

    /**
     * 进度 0..100；totalBytes 为 0 时返回 0。
     * 下载中以外按实际落盘文件计算（下载中用 worker 写入的累计值）。
     */
    fun progressPercent(context: Context, id: String): Int {
        val m = model(id) ?: return 0
        if (m.totalBytes <= 0) return 0
        val done = if (state(context, id) == STATE_DOWNLOADING) {
            downloadedBytes(context, id)
        } else {
            val dir = modelDir(context, id)
            m.files.sumOf { f -> File(dir, f.name).let { if (it.exists()) it.length() else 0L } }
        }
        return ((done * 100) / m.totalBytes).toInt().coerceIn(0, 100)
    }

    /**
     * 磁盘上该模型已占用的字节数（含 .part），用于「删除」按钮前的展示。
     */
    fun onDiskBytes(context: Context, id: String): Long {
        val dir = modelDir(context, id)
        if (!dir.exists()) return 0L
        return dir.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L
    }

    /** 删除整个模型目录（含 .part 残留），状态归零。 */
    fun delete(context: Context, id: String) {
        modelDir(context, id).deleteRecursively()
        prefs(context).edit()
            .putString("${id}_state", STATE_NONE)
            .putString("${id}_error", "")
            .putLong("${id}_downloaded", 0L)
            .apply()
    }

    /** 人类可读字节数（MB/GB 一位小数）。 */
    fun formatBytes(bytes: Long): String = when {
        bytes >= 1_000_000_000L -> "%.2f GB".format(bytes / 1_000_000_000.0)
        bytes >= 1_000_000L -> "%.1f MB".format(bytes / 1_000_000.0)
        else -> "%.0f KB".format(bytes / 1_000.0)
    }
}
