// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import android.content.Context
import org.json.JSONArray

/**
 * 重点关注会话名单，存 SharedPreferences（文件名 watchlist，值为 JSON 数组字符串）。
 *
 * 语义约定（重要，v3 反转默认）：
 *  - **空名单 = 默认不关注**（新装/未配置时不分析、不弹卡，通知仅落库存着）；
 *  - 名单里有真实会话键 = 只分析这些会话（显式 ⭐ 白名单）；
 *  - 「全部关注」通过哨兵值 [SENTINEL_ALL] 实现——名单里放一个专用标记，
 *    表达「所有会话（含未来的新会话）都关注」，不用把名单物化成全量；
 *  - [SENTINEL_NONE] 是 v2 的「全不关注」旧哨兵，v3 起不再写入：
 *    它匹配不到任何真实会话，效果与空名单一致，老用户升级后行为不变。
 *
 * 黑名单（[KEY_UNWATCHED]，M11）与白名单正交：命中黑名单恒不关注且主列表隐藏。
 * 「全部关注」态下单独取消某个会话 = 进黑名单；白名单态下 = 移出白名单即可。
 *
 * M2（跨应用通知管理）：名单元素从裸会话名升级为 [ConvKey.id]（`包名|会话名`），
 * 否则「微信的『工作群』」和「飞书的『工作群』」会被当成同一个关注项。
 * 兼容读＝**读时归一**：老条目没有分隔符，一律按微信处理（[ConvKey.parse]），
 * 因此升级后老名单照常生效；下次写回时自然变成新格式。
 */
object WatchlistStore {

    private const val PREFS_NAME = "watchlist"
    private const val KEY_WATCHED = "watched_conversations"

    // M11：显式不关注（黑名单）——名单里的会话在主列表隐藏、分析跳过。

    private const val KEY_UNWATCHED = "unwatched_conversations"

    /** v2 遗留哨兵：表达「全部取消关注」。v3 起空名单本身即不关注，此值不再写入，仅作兼容保留。 */
    const val SENTINEL_NONE = "__watch_none__"

    /** v3 哨兵：表达「全部关注」（含未来新会话）。名单里只放它，不物化全量。 */
    const val SENTINEL_ALL = "__watch_all__"

    private fun prefs(context: Context) = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * 读取关注名单（已归一为 [ConvKey.id]，可能含哨兵值，调用方按需过滤展示）。
     * 损坏的 JSON 按空名单处理（= 默认不关注）。
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
            // JSON 损坏按空名单处理（= 默认不关注）
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

    /** 该会话（复合键）是否应被分析：黑名单命中恒否；全部关注态为真；否则需显式在白名单内（空名单=默认不关注）。 */
    fun isWatched(context: Context, key: ConvKey): Boolean =
        isWatchedId(key.id, getUnwatched(context), getWatched(context))

    /**
     * 纯函数判定（与 Context 解耦，可在构建列表时预先算好渲染字段）：
     * 黑名单命中恒 false；名单含「全部关注」哨兵 → 其余会话恒 true；
     * 否则按包含判断——空名单 = 默认不关注，旧「全不关注」哨兵同样匹配不到任何真实键。
     */
    fun isWatchedId(keyId: String, unwatched: Set<String>, watched: Set<String>): Boolean =
        keyId !in unwatched && (SENTINEL_ALL in watched || keyId in watched)

    /** 读取显式不关注集合（已归一为 [ConvKey.id]）。 */
    @Synchronized
    fun getUnwatched(context: Context): Set<String> {
        val raw = prefs(context).getString(KEY_UNWATCHED, "[]").orEmpty()
        val result = mutableSetOf<String>()
        try {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                result.add(normalize(array.optString(i)))
            }
        } catch (e: Exception) {
            // 损坏按空集合
        }
        return result
    }

    /** 整体写回显式不关注集合（元素应为 [ConvKey.id] 或哨兵）。 */
    @Synchronized
    fun setUnwatched(context: Context, unwatched: Set<String>) {
        val array = JSONArray()
        unwatched.forEach { array.put(it) }
        prefs(context).edit().putString(KEY_UNWATCHED, array.toString()).apply()
    }

    /** 名单里的显式 ⭐ 真实会话键（已剔除新旧哨兵）。全部关注态下为空，即时弹卡路径因此只服务白名单会话。 */
    fun watchedKeys(context: Context): Set<ConvKey> =
        getWatched(context).filter { it != SENTINEL_NONE && it != SENTINEL_ALL }
            .map { ConvKey.parse(it) }.toSet()

    /** 用会话键整体写回名单。 */
    fun setWatchedKeys(context: Context, keys: Set<ConvKey>) =
        setWatched(context, keys.map { it.id }.toSet())

    /**
     * 归一：v1 存的是裸会话名，v2 存 [ConvKey.id]。
     * 哨兵原样返回（它们不是会话，绝不能被解析成 `com.tencent.mm|__watch_none__` 之类）。
     */
    private fun normalize(raw: String): String =
        if (raw == SENTINEL_NONE || raw == SENTINEL_ALL || raw.isBlank()) raw
        else ConvKey.parse(raw).id
}
