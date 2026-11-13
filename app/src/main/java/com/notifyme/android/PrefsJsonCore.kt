// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import org.json.JSONArray
import org.json.JSONObject

/**
 * SharedPreferences 里「存 JSON 字符串」的读写口径（容错解析的唯一实现）。
 *
 * 五处站点共用同一套语义，此前各自抄了一份 `try { ... } catch (e: Exception) { }`，
 * 「损坏按空」只写在注释里、一条单测都没有：
 *  - [WatchlistStore] 的 `watched_conversations` / `unwatched_conversations`（字符串数组）
 *  - [AppSourceStore] 的 `whitelist` / `disabled`（字符串数组）与 `observed`（对象数组）
 *  - [ConvTagStore] 的 `tags`（字符串映射）
 *  - [ConvAnalysisScheduleStore] 的 `schedules`（长整型映射）
 *  - [AnalysisScopeCore.decodeConvIds] 的 Worker inputData 会话 id 列表
 *
 * 契约：
 *  1. **读永不抛异常**：`null` / 空白 / 截断的 JSON / 类型不对的根（对象当成数组读）一律返回空集合，
 *     且返回的是不可变拷贝 —— 调用方拿到的表不会被别处顺手改掉。
 *  2. **只做形状裁剪，不做领域过滤**：这里只丢「空键 / 空值（可选）/ 非正数（可选）/ 非对象元素」，
 *     像 `ConvTag.fromCode(v) != NONE` 这类语义判断留在各自 Store 里，否则内核会被业务枚举绑死。
 *  3. **元素走 `optString`**：数字元素按字符串取出（`1` → `"1"`），对象元素会拿到它的 JSON 文本 ——
 *     这类脏值匹配不到任何包名/会话键，等价于被忽略，与旧实现逐字一致。
 *  4. **写不做排序**：顺序由调用方决定（会话名单要保持写入顺序）；需要稳定输出时显式传 `sort = true`。
 *  5. 与 [MessageStore] 的 JSONL 读写无关：那边是逐行流式文件，这边是单键整表，别互相顶替。
 */
internal object PrefsJsonCore {

    /** 字符串数组 → 列表（保序、去重复不去顺序）。损坏返回空列表。 */
    fun strings(raw: String?, trim: Boolean = false): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        val out = ArrayList<String>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val value = if (trim) arr.optString(i).trim() else arr.optString(i)
                if (value.isNotEmpty()) out.add(value)
            }
        } catch (e: Exception) {
            return emptyList()
        }
        return out
    }

    /** 字符串数组 → 集合（去重）。损坏返回空集合。 */
    fun stringSet(raw: String?, trim: Boolean = false): Set<String> = strings(raw, trim).toSet()

    /** 列表 → JSON 数组字符串；`dropBlank` 先丢空白项，`sort` 按字典序（会话 id 的稳定输出）。 */
    fun encodeStrings(
        values: Iterable<String>,
        sort: Boolean = false,
        dropBlank: Boolean = false
    ): String {
        val items = if (dropBlank) values.filter { it.isNotBlank() } else values.toList()
        val arr = JSONArray()
        (if (sort) items.sorted() else items).forEach { arr.put(it) }
        return arr.toString()
    }

    /** 字符串映射 → 只丢空键；值缺省为空串（是否丢弃空值由调用方判断）。损坏返回空表。 */
    fun stringMap(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        try {
            val obj = JSONObject(raw)
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (key.isEmpty()) continue
                out[key] = obj.optString(key, "")
            }
        } catch (e: Exception) {
            return emptyMap()
        }
        return out
    }

    /**
     * 整数映射 → 丢空键；`positiveOnly` 时再丢「非正数」与「取不出数」的条目（`optLong` 失败给 0）。
     * 排期表用 `positiveOnly = true`：0 与负数在语义上就是「未启用」，留着会污染 `all()`。
     */
    fun longMap(raw: String?, positiveOnly: Boolean = true): Map<String, Long> {
        if (raw.isNullOrBlank()) return emptyMap()
        val out = LinkedHashMap<String, Long>()
        try {
            val obj = JSONObject(raw)
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (key.isEmpty()) continue
                val value = obj.optLong(key, 0L)
                if (!positiveOnly || value > 0L) out[key] = value
            }
        } catch (e: Exception) {
            return emptyMap()
        }
        return out
    }

    /** 映射 → JSON 对象字符串（不做排序，顺序由 map 的迭代顺序决定）。 */
    fun encodeMap(map: Map<String, String>): String {
        val obj = JSONObject()
        map.forEach { (k, v) -> obj.put(k, v) }
        return obj.toString()
    }

    /** 整数映射 → JSON 对象字符串。 */
    fun encodeLongMap(map: Map<String, Long>): String {
        val obj = JSONObject()
        map.forEach { (k, v) -> obj.put(k, v) }
        return obj.toString()
    }

    /**
     * 对象数组 → 投影后的列表：[project] 返回 null 表示「这条不要」（缺字段 / 非对象元素 / 空包名）。
     * 单条坏数据不牵连整表 —— 这正是 `observed` 名单要的行为。
     */
    fun <T> objects(raw: String?, project: (JSONObject) -> T?): List<T> {
        if (raw.isNullOrBlank()) return emptyList()
        val out = ArrayList<T>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                project(obj)?.let { out.add(it) }
            }
        } catch (e: Exception) {
            return emptyList()
        }
        return out
    }
}
