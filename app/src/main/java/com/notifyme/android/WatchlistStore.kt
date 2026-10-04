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
 *    永远不可能匹配真实会话名的占位串，从而表达「一个都不关注」，
 *    同时保持「空=全部关注」的语义不被占用。
 */
object WatchlistStore {

    private const val PREFS_NAME = "watchlist"
    private const val KEY_WATCHED = "watched_conversations"

    /** 哨兵：表达「全部取消关注」，不可能与真实会话名撞车 */
    const val SENTINEL_NONE = "__watch_none__"

    private fun prefs(context: Context) = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 读取关注名单（可能含哨兵值，调用方按需过滤展示）。 */
    @Synchronized
    fun getWatched(context: Context): MutableSet<String> {
        val raw = prefs(context).getString(KEY_WATCHED, "[]").orEmpty()
        val result = mutableSetOf<String>()
        try {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                result.add(array.optString(i))
            }
        } catch (e: Exception) {
            // JSON 损坏按空名单处理（= 全部关注）
        }
        return result
    }

    /** 整体写回名单。 */
    @Synchronized
    fun setWatched(context: Context, watched: Set<String>) {
        val array = JSONArray()
        watched.forEach { array.put(it) }
        prefs(context).edit().putString(KEY_WATCHED, array.toString()).apply()
    }

    /** 该会话是否应被分析：空名单=全部关注；非空名单按包含判断（哨兵天然不匹配）。 */
    fun isWatched(context: Context, conversation: String): Boolean {
        val watched = getWatched(context)
        return watched.isEmpty() || watched.contains(conversation)
    }
}
