// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import org.json.JSONArray
import org.json.JSONObject

/**
 * 评测样本载体（ROADMAP M4 任务 6）。
 *
 * 文件格式（UTF-8，无 BOM）：
 * ```json
 * {
 *   "version": 1,
 *   "generatedAt": "2026-11-12T16:00:00+08:00",
 *   "messages": [
 *     {"sender":"张伟", "text":"…", "timestamp":1762900000000,
 *      "conversation":"产品群", "isGroup":true, "pkg":"com.tencent.mm"}
 *   ]
 * }
 * ```
 *
 * 只读 JSON、零 Android 依赖：样本既可以是真机导出，也可以是人工标注的合成会话。
 * 字段缺失一律兜底默认值，绝不抛异常（坏样本按空消息集处理，由调用方决定跳过）。
 */
internal object EvalDataset {

    const val VERSION = 1

    /** 解析结果：[ConvNormalize.Conv] 已按 (pkg, conversation) 分组、时间正序。 */
    data class Parsed(
        val version: Int,
        val generatedAt: String,
        val messages: List<ChatMessage>,
        val convs: List<ConvNormalize.Conv>
    ) {
        val size: Int get() = messages.size
    }

    private fun empty(): Parsed = Parsed(VERSION, "", emptyList(), emptyList())

    /** 从 JSON 文本解析；解析失败返回空集（不抛）。 */
    fun parse(text: String): Parsed {
        if (text.isBlank()) return empty()
        return try {
            val root = JSONObject(text)
            val arr = root.optJSONArray("messages") ?: JSONArray()
            val msgs = ArrayList<ChatMessage>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val sender = o.optString("sender")
                val body = o.optString("text")
                if (sender.isBlank() && body.isBlank()) continue
                val ts = o.optLong("timestamp", 0L)
                if (ts <= 0L) continue
                msgs.add(
                    ChatMessage(
                        sender = sender,
                        text = body,
                        timestamp = ts,
                        conversation = o.optString("conversation"),
                        isGroup = o.optBoolean("isGroup", false),
                        source = o.optString("source", "eval"),
                        pkg = o.optString("pkg", AppSourceRegistry.PKG_WECHAT).ifBlank {
                            AppSourceRegistry.PKG_WECHAT
                        },
                        appLabel = o.optString("appLabel", "")
                    )
                )
            }
            Parsed(
                version = root.optInt("version", VERSION),
                generatedAt = root.optString("generatedAt", ""),
                messages = msgs,
                convs = ConvNormalize.group(msgs)
            )
        } catch (e: Exception) {
            empty()
        }
    }

    /** 写回 JSON 文本（保留 pkg / source，便于把采集结果直接入库）。 */
    fun toJson(parsed: Parsed): String = JSONObject().apply {
        put("version", parsed.version)
        put("generatedAt", parsed.generatedAt)
        put("messages", JSONArray().apply {
            for (m in parsed.messages) {
                put(JSONObject().apply {
                    put("sender", m.sender)
                    put("text", m.text)
                    put("timestamp", m.timestamp)
                    put("conversation", m.conversation)
                    put("isGroup", m.isGroup)
                    put("source", m.source)
                    put("pkg", m.pkg)
                    if (m.appLabel.isNotEmpty()) put("appLabel", m.appLabel)
                })
            }
        })
    }.toString(2)
}
