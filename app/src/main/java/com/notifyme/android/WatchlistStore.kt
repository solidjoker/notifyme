// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import android.content.Context
import org.json.JSONArray

/**
 * 重点关注会话名单，存 SharedPreferences（文件名 watchlist，值为 JSON 数组字符串）。
 *
 * 语义约定（重要）：
 *  - **空名单 = 全部关注**（默认状态，零配置即分析所有会话）；
 *  - 非空名单 = 只分析名单内的会话；
 *  - 「全部取消关注」通过哨兵值 [SENTINEL_NONE] 实现——名单里放一个
 *    永远不可能匹配真实会话的占位串，从而表达「一个都不关注」，
 *    同时保持「空=全部关注」的语义不被占用。
 *
 * M2（跨应用通知管理）：名单元素从裸会话名升级为 [ConvKey.id]（`包名|会话名`），
 * 否则「微信的『工作群』」和「飞书的『工作群』」会被当成同一个关注项。
 * 兼容读＝**读时归一**：老条目没有分隔符，一律按微信处理（[ConvKey.parse]），
 * 因此升级后老名单照常生效；下次写回时自然变成新格式。
 */
object WatchlistStore {

    private const val PREFS_NAME = "watchlist"
    private const val KEY_WATCHED = "watched_conversations"

    /** 哨兵：表达「全部取消关注」，不可能与真实会话撞车 */
    const val SENTINEL_NONE = "__watch_none__"

    private fun prefs(context: Context) = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * 读取关注名单（已归一为 [ConvKey.id]，可能含哨兵值，调用方按需过滤展示）。
     * 损坏的 JSON 按空名单处理（= 全部关注）。
     */
    @Synchronized
    fun getWatched(context: Context): MutableSet<String> {
        val raw = prefs(context).getString(KEY_WATCHED, "[]").orEmpty()
        val result = mutableSetOf<String>()
        try {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                result.add(normalize(array.optString(i)))
            }
        } catch (e: Exception) {
            // JSON 损坏按空名单处理（= 全部关注）
        }
        return result
    }

    /** 整体写回名单（元素应为 [ConvKey.id] 或哨兵）。 */
    @Synchronized
    fun setWatched(context: Context, watched: Set<String>) {
        val array = JSONArray()
        watched.forEach { array.put(it) }
        prefs(context).edit().putString(KEY_WATCHED, array.toString()).apply()
    }

    /** 该会话（复合键）是否应被分析：空名单=全部关注；非空名单按包含判断（哨兵天然不匹配）。 */
    fun isWatched(context: Context, key: ConvKey): Boolean {
        val watched = getWatched(context)
        return watched.isEmpty() || watched.contains(key.id)
    }

    /** 名单里的真实会话键（已剔除哨兵）；空名单返回空集合，语义仍是「全部关注」。 */
    fun watchedKeys(context: Context): Set<ConvKey> =
        getWatched(context).filter { it != SENTINEL_NONE }.map { ConvKey.parse(it) }.toSet()

    /** 用会话键整体写回名单。 */
    fun setWatchedKeys(context: Context, keys: Set<ConvKey>) =
        setWatched(context, keys.map { it.id }.toSet())

    /**
     * 归一：v1 存的是裸会话名，v2 存 [ConvKey.id]。
     * 哨兵原样返回（它不是会话，绝不能被解析成 `com.tencent.mm|__watch_none__`）。
     */
    private fun normalize(raw: String): String =
        if (raw == SENTINEL_NONE || raw.isBlank()) raw else ConvKey.parse(raw).id
}
