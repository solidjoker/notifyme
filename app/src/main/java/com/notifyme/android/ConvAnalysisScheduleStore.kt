// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import android.content.Context
import org.json.JSONObject

/**
 * 已关注会话的「定时分析」排期表（M11.4）。
 *
 * 存 SharedPreferences（文件名 conv_analysis_schedule），JSON 结构：
 * `{ "<convKey.id>": intervalMinutes, ... }`（intervalMinutes<=0 或缺失 = 未启用）。
 *
 * 语义：
 *  - 每个会话独立周期（WorkManager unique 名 "conv_analysis_<id>"，UPDATE 策略）；
 *  - 会话被取消关注时排期失效（AnalysisScheduler.syncAll 会清掉孤儿任务）；
 *  - 只存排期，不做任何 Android 调用，便于后续扩展。
 */
object ConvAnalysisScheduleStore {

    private const val PREFS_NAME = "conv_analysis_schedule"
    private const val KEY_MAP = "schedules"

    private fun prefs(context: Context) = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 读取某会话的分析周期（分钟）；未设置返回 0。 */
    fun intervalMinutes(context: Context, key: ConvKey): Long {
        val map = readMap(context)
        return map[key.id] ?: 0L
    }

    /** 设置/更新某会话的分析周期；minutes<=0 = 取消定时。 */
    @Synchronized
    fun setInterval(context: Context, key: ConvKey, minutes: Long) {
        val map = readMap(context).toMutableMap()
        if (minutes <= 0) map.remove(key.id) else map[key.id] = minutes
        writeMap(context, map)
    }

    /** 全部已设置的排期（convKey.id → 分钟）。 */
    fun all(context: Context): Map<String, Long> = readMap(context)

    private fun readMap(context: Context): Map<String, Long> {
        val raw = prefs(context).getString(KEY_MAP, "{}").orEmpty()
        val result = mutableMapOf<String, Long>()
        try {
            val obj = JSONObject(raw)
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val v = obj.optLong(k, 0L)
                if (k.isNotEmpty() && v > 0) result[k] = v
            }
        } catch (e: Exception) {
            // 损坏按空排期表处理
        }
        return result
    }

    private fun writeMap(context: Context, map: Map<String, Long>) {
        val obj = JSONObject()
        map.forEach { (k, v) -> obj.put(k, v) }
        prefs(context).edit().putString(KEY_MAP, obj.toString()).apply()
    }
}