// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import android.content.Context
import com.notifyme.android.laya.LayaEngine
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Laya 端侧推理引擎门面：封装 [LayaEngine]（官方契约移植，双图 embeds+act head）。
 *
 * 与旧实现的区别（旧版按错误假设写：单图、token_ids 直入、全词表 logits 输出——
 * 真实模型是 embeds 输入 [1,N,768] + token_logits [1,N] 标量输出，见 HOST_CONTRACT.md）：
 *  - prompt 构建、fp16 嵌入查表、温度校准、解码全部走官方移植类（com.notifyme.android.laya）
 *  - 引擎实例按模型目录缓存，S1 四个问题共享同一次 tokenizer/图加载
 *  - isReady 用 LocalModelStore 清单的真实文件名+字节数判断（手动 push 的模型也能就绪）
 */
object LayaTfliteEngine {

    private const val TAG = "LayaTfliteEngine"
    private const val WINDOW = 256

    @Volatile
    private var cachedEngine: LayaEngine? = null
    @Volatile
    private var cachedDir: String? = null
    @Volatile
    private var initializedDir: String? = null

    /** 模型目录存在且全部清单文件字节匹配（不依赖 prefs 状态，手动 adb push 亦可用）。 */
    fun isReady(context: Context): Boolean {
        val dir = modelDir(context)
        val manifest = LocalModelStore.model(LocalModelStore.MODEL_LAYA)?.files ?: return false
        return manifest.all { f ->
            val file = File(dir, f.name)
            file.isFile && (f.size <= 0L || file.length() == f.size)
        }
    }

    private fun modelDir(context: Context): File =
        LocalModelStore.modelDir(context.applicationContext, LocalModelStore.MODEL_LAYA)

    /**
     * 取（并缓存）引擎。tokenizer.json 解析 + 393MB 嵌入表映射 + CPU 图编译为一次性开销，
     * 目录不变时后续调用直接复用。仅在 [androidx.work] 的后台线程调用。
     */
    @Synchronized
    internal fun obtainEngine(context: Context): LayaEngine {
        val dir = modelDir(context)
        val path = dir.absolutePath
        val existing = cachedEngine
        if (existing != null && cachedDir == path) return existing
        existing?.close()
        val engine = LayaEngine(context.applicationContext, dir)
        cachedEngine = engine
        cachedDir = path
        initializedDir = null
        return engine
    }

    /**
     * 批量执行 S1 判定题（官方 answer schema：choice/score/noul + probabilities + confidence + action）。
     *
     * @param questions 有序的 (questionId, 题目 map)，题目 map 形如
     *   {"type":"choice","instructions":"...","criteria":{"label":"desc",...}}；
     *   score 的 criteria 为 ["c0","c1",...]，noul 可省略 criteria（false/true 兜底文案）。
     * @return 与 questions 同序的答案 map 列表（保插入序）。
     */
    fun answerQuestions(
        context: Context,
        questions: List<Pair<String, Map<String, Any?>>>,
        stateText: String,
        window: Int = WINDOW,
    ): List<Map<String, Any?>> {
        val dir = modelDir(context).absolutePath
        val engine = obtainEngine(context)
        if (initializedDir != dir) {
            engine.initialize(LayaEngine.Backend.CPU, window)
            initializedDir = dir
        }
        return questions.map { (id, question) ->
            engine.answer(stateText, question, LayaEngine.Backend.CPU, id, window).answer
        }
    }

    /** org.json → 保插入序 Map（Android JSONObject 底层 LinkedHashMap；JSONArray 保序）。 */
    fun toOrderedMap(obj: JSONObject): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        for (key in obj.keys()) {
            out[key] = unwrap(obj.get(key))
        }
        return out
    }

    private fun unwrap(value: Any?): Any? = when (value) {
        is JSONObject -> toOrderedMap(value)
        is JSONArray -> (0 until value.length()).map { unwrap(value.get(it)) }
        JSONObject.NULL -> null
        else -> value
    }

    /** Map（含嵌套 List/Map/标量）→ JSONObject/JSONArray，保插入序。 */
    fun toJson(value: Any?): Any = when (value) {
        is Map<*, *> -> JSONObject().also { o ->
            value.forEach { (k, v) -> o.put(k.toString(), toJson(v)) }
        }
        is List<*> -> JSONArray().also { a -> value.forEach { a.put(toJson(it)) } }
        null -> JSONObject.NULL
        else -> value
    }

    /** 释放引擎（模型更新/内存压力大时调用）。 */
    @Synchronized
    fun releaseAll() {
        cachedEngine?.close()
        cachedEngine = null
        cachedDir = null
        initializedDir = null
    }
}
