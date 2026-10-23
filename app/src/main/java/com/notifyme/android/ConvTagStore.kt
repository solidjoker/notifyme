// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import android.content.Context
import org.json.JSONObject

/**
 * 会话标签存储（M19）：SharedPreferences conv_tags，值为 JSON 映射
 * 「ConvKey.id → 标签码」。只存**已打标**的会话，未标记不落条目，
 * 所以「取消标签」= 删条目，名单里不会堆积一堆 none 值。
 *
 * 与 WatchlistStore 的关系：标签优先于名单（待办/跟踪强制纳入、已办结/无关强制排除），
 * 未打标的会话仍按名单判定——老用户不改行为，打标后才生效。
 */
object ConvTagStore {

    private const val PREFS_NAME = "conv_tags"
    private const val KEY_MAP = "tags"

    private fun prefs(context: Context) = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 原始映射（convKey.id → 标签码），损坏按空表处理。 */
    @Synchronized
    fun readRaw(context: Context): Map<String, String> {
        val raw = prefs(context).getString(KEY_MAP, "{}").orEmpty()
        val result = mutableMapOf<String, String>()
        try {
            val obj = JSONObject(raw)
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val v = obj.optString(k, "")
                if (k.isNotEmpty() && ConvTag.fromCode(v) != ConvTag.NONE) result[k] = v
            }
        } catch (e: Exception) {
            // JSON 损坏按空表处理（= 全部未标记，行为回落到关注名单）
        }
        return result
    }

    /** 某会话的标签；未打标返回 [ConvTag.NONE]。 */
    fun tagOf(context: Context, key: ConvKey): ConvTag = ConvTag.fromCode(readRaw(context)[key.id])

    /** 设置标签；[ConvTag.NONE] 等价于取消标签（删条目）。 */
    @Synchronized
    fun set(context: Context, key: ConvKey, tag: ConvTag) {
        val map = readRaw(context).toMutableMap()
        if (tag == ConvTag.NONE) map.remove(key.id) else map[key.id] = tag.code
        write(context, map)
    }

    /** 全部已打标会话（ConvKey → 标签）。 */
    fun tagged(context: Context): Map<ConvKey, ConvTag> = readRaw(context)
        .map { (id, code) -> ConvKey.parse(id) to ConvTag.fromCode(code) }.toMap()

    /** 各档计数（只统计已打标会话），供控制台概览。 */
    fun countByTag(context: Context): Map<ConvTag, Int> {
        val counts = mutableMapOf<ConvTag, Int>()
        readRaw(context).values.forEach {
            val tag = ConvTag.fromCode(it)
            if (tag != ConvTag.NONE) counts[tag] = (counts[tag] ?: 0) + 1
        }
        return counts
    }

    /** 该会话是否还要进后续分析（未打标时回落到 [watched]）。 */
    fun keepsAnalyzing(context: Context, key: ConvKey, watched: Boolean): Boolean =
        ConvTagCore.shouldAnalyze(tagOf(context, key), watched, forced = false)

    private fun write(context: Context, map: Map<String, String>) {
        val obj = JSONObject()
        map.forEach { (k, v) -> obj.put(k, v) }
        prefs(context).edit().putString(KEY_MAP, obj.toString()).apply()
    }
}
