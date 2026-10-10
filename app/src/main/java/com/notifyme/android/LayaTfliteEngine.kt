// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.tensorflow.lite.Interpreter
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * Laya 端侧 TFLite 推理引擎：加载 laya-multilingual LiteRT 双图（编码器 + 判定头），
 * 对 System One 四类判定题（choice / score / noul）逐题推理，
 * 输出与 laya_host.py 参考实现一致的概率分布。
 *
 * 模型来源：litert-community/Laya-Multilingual-LiteRT
 * 架构：mmBERT-base 编码器 + laya 判定头（分类式，非生成式 LLM）。
 *
 * 推理流程：
 *  1. Tokenizer 分词 → token ids + attention mask
 *  2. 构建 laya prompt：[bos] <type> question [sep] [mask] opt0 [mask] opt1 … [sep] <state> [sep]
 *  3. 主图推理 → 在 [mask] 位置收集 token_logits
 *  4. 按题型温度校准 → softmax → 判定结果
 */
object LayaTfliteEngine {

    private const val TAG = "LayaTfliteEngine"
    private const val WINDOW = 256

    // ---------- SentencePiece 贪心分词器 ----------

    internal class SpTokenizer private constructor(
        private val pieceToId: Map<String, Int>
    ) {
        companion object {
            fun load(file: File): SpTokenizer {
                val json = JSONObject(file.readText())
                val vocab = json.getJSONObject("model").getJSONArray("vocab")
                val map = HashMap<String, Int>(vocab.length())
                for (i in 0 until vocab.length()) {
                    val entry = vocab.getJSONArray(i)
                    map[entry.getString(0)] = entry.getInt(1)
                }
                return SpTokenizer(map)
            }
        }

        val clsId: Int get() = pieceToId["<bos>"] ?: 0
        val sepId: Int get() = pieceToId["</s>"] ?: 1
        val maskId: Int get() = pieceToId["<mask>"] ?: 3
        val unkId: Int get() = pieceToId["<unk>"] ?: 0

        fun encode(text: String): List<Int> {
            val ids = mutableListOf<Int>()
            var pos = 0
            val chars = text.toCharArray()
            while (pos < chars.size) {
                var len = minOf(24, chars.size - pos)
                var hit = false
                while (len >= 1) {
                    val sub = String(chars, pos, len)
                    val id = pieceToId[sub]
                    if (id != null) { ids.add(id); pos += len; hit = true; break }
                    len--
                }
                if (!hit) { ids.add(unkId); pos += 1 }
            }
            return ids
        }
    }

    // ---------- 温度校准 ----------

    private data class Calibration(val choice: Double, val score: Double, val noul: Double) {
        companion object {
            fun load(f: File): Calibration {
                val j = JSONObject(f.readText())
                // calibration.json 格式可能为 {type: temp} 或嵌套，这里防御式读取
                val choice = j.optDouble("choice", j.optDouble("choice_temperature", 1.0))
                val score = j.optDouble("score", j.optDouble("score_temperature", 1.0))
                val noul = j.optDouble("noul", j.optDouble("noul_temperature", 1.0))
                return Calibration(choice, score, noul)
            }
        }
    }

    // ---------- 推理结果 ----------

    data class LayAnswer(
        val labels: List<String>,   // 选项标签（按 criteria 顺序）
        val probs: List<Double>,    // softmax 概率（与 labels 一一对应）
        val bestIdx: Int            // 最大概率的下标
    )

    // ---------- 公共入口 ----------

    fun isReady(context: Context): Boolean {
        val dir = modelDir(context)
        return dir.resolve("laya_ml_s256_wfp16.tflite").exists() &&
            dir.resolve("tokenizer.json").exists()
    }

    private fun modelDir(context: Context): File =
        File(context.applicationContext.filesDir, "models/${LocalModelStore.MODEL_LAYA}")

    /**
     * 执行单题 laya 推理，返回各选项概率。
     * 每次调用独立加载模型（上层按需缓存 Interpreter 实例可优化）。
     */
    fun runQuestion(
        context: Context,
        questionType: String,  // "choice" | "score" | "noul"
        instructions: String,
        options: List<String>, // choice 的选项列表或 score 的分档标签
        stateText: String,
        window: Int = WINDOW
    ): LayAnswer {
        val dir = modelDir(context)
        val tokenizer = SpTokenizer.load(dir.resolve("tokenizer.json"))
        val calib = if (dir.resolve("calibration.json").exists())
            Calibration.load(dir.resolve("calibration.json")) else Calibration(1.0, 1.0, 1.0)

        // 构建 laya prompt
        val typeTag = questionType // choice / score / noul
        // 构建 token 序列：[bos] <type> question [sep] [mask] opt0 [mask] opt1 ... [sep] <state> [sep]
        val tokens = mutableListOf<Int>()
        tokens.add(tokenizer.clsId)
        // 类型标签分词
        tokens.addAll(tokenizer.encode(typeTag))
        // 指令分词
        tokens.addAll(tokenizer.encode(" question: $instructions"))
        tokens.add(tokenizer.sepId)

        // 记录 [MASK] 位置
        val maskPositions = mutableListOf<Int>()
        for (opt in options) {
            maskPositions.add(tokens.size)
            tokens.add(tokenizer.maskId)
            tokens.addAll(tokenizer.encode(" $opt"))
        }
        tokens.add(tokenizer.sepId)

        // 状态文本分词（截断到 window - 已有 token 数 - 1）
        val remaining = window - tokens.size - 1
        val stateIds = tokenizer.encode(stateText).take(remaining.coerceAtLeast(0))
        tokens.addAll(stateIds)
        tokens.add(tokenizer.sepId)

        // 填充到 window
        val inputIds = IntArray(window) { i -> tokens.getOrElse(i) { tokenizer.clsId } }
        val attentionMask = FloatArray(window) { i -> if (i < tokens.size) 1f else 0f }
        val qtypeOnehot = when (questionType) {
            "choice" -> floatArrayOf(1f, 0f, 0f)
            "score" -> floatArrayOf(0f, 1f, 0f)
            else -> floatArrayOf(0f, 0f, 1f)
        }

        // 加载模型并推理
        val modelFile = File(dir, "laya_ml_s256_wfp16.tflite")
        val buffer = loadModelFile(modelFile)
        val interpreter = Interpreter(buffer, Interpreter.Options().apply { setNumThreads(4) })
        try {
            val inputIdsBuf = arrayOf(inputIds)
            val maskBuf = arrayOf(attentionMask)
            val qtypeBuf = arrayOf(qtypeOnehot)

            // 输出：token_logits [1, window, vocab_size] + pooled_cls [1, D]
            val vocabSize = 250002  // mmBERT 词表大小
            val tokenLogits = Array(1) { Array(window) { FloatArray(vocabSize) } }
            val pooledDim = 768  // mmBERT-base hidden size
            val pooledCls = Array(1) { FloatArray(pooledDim) }

            val inputs = arrayOf<Any>(inputIdsBuf as Any, maskBuf as Any, qtypeBuf as Any)
            val outputs = java.util.HashMap<Int, Any>()
            outputs.put(0, tokenLogits)  // 输出张量 0：token_logits
            outputs.put(1, pooledCls)    // 输出张量 1：pooled_cls
            interpreter.runForMultipleInputsOutputs(inputs, outputs)

            // 在 [MASK] 位置收集 logits（每个 MASK 对应一个选项）
            val optionLogits = maskPositions.map { pos ->
                tokenLogits[0][pos.coerceIn(0, window - 1)]
            }

            // 按题型选温度
            val temp = when (questionType) {
                "choice" -> calib.choice
                "score" -> calib.score
                else -> calib.noul
            }

            // softmax（取第一个 MASK 位置的 logits，长度 = options.size）
            val finalLogits = optionLogits.firstOrNull() ?: FloatArray(options.size)
            val maxL = finalLogits.max()
            val exps = finalLogits.map { Math.exp(((it - maxL).toDouble()) / temp) }
            val sumExp = exps.sum()
            val finalProbs = exps.map { it / sumExp }

            val bestIdx = finalProbs.indices.maxByOrNull { finalProbs[it] } ?: 0
            return LayAnswer(options, finalProbs, bestIdx)
        } finally {
            interpreter.close()
        }
    }

    private fun loadModelFile(file: File): MappedByteBuffer {
        val fc = java.io.RandomAccessFile(file, "r").channel
        return fc.map(FileChannel.MapMode.READ_ONLY, 0, fc.size())
    }
}