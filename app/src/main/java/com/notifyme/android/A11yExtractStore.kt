// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import android.content.Context

/**
 * 无障碍直读提取的运行状态存储：WeChatA11yExtractService（后台线程）写、
 * ConsoleActivity（UI 轮询）读，用 SharedPreferences 做跨组件共享。
 *
 * 语义：
 *  - running=true 表示提取进行中，status 为滚动进度文案；
 *  - running=false + status 非空 = 最近一次提取的终态（完成/停止/失败）；
 *  - finishedAt 为终态时间戳，供界面区分「还没跑过」与「跑过」。
 */
object A11yExtractStore {

    private const val PREFS_NAME = "a11y_extract"
    private const val KEY_RUNNING = "running"
    private const val KEY_STATUS = "status"
    private const val KEY_COUNT = "count"
    private const val KEY_CONVERSATION = "conversation"
    private const val KEY_FINISHED_AT = "finished_at"

    data class State(
        val running: Boolean,
        val status: String,
        val count: Int,
        val conversation: String,
        val finishedAt: Long
    )

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 提取进行中滚动更新进度。 */
    @Synchronized
    fun update(context: Context, running: Boolean, status: String, count: Int, conversation: String) {
        prefs(context).edit()
            .putBoolean(KEY_RUNNING, running)
            .putString(KEY_STATUS, status)
            .putInt(KEY_COUNT, count)
            .putString(KEY_CONVERSATION, conversation)
            .apply()
    }

    /** 提取结束（完成/手动停止/异常），落终态。 */
    @Synchronized
    fun finish(context: Context, status: String, count: Int, conversation: String) {
        prefs(context).edit()
            .putBoolean(KEY_RUNNING, false)
            .putString(KEY_STATUS, status)
            .putInt(KEY_COUNT, count)
            .putString(KEY_CONVERSATION, conversation)
            .putLong(KEY_FINISHED_AT, System.currentTimeMillis())
            .apply()
    }

    fun read(context: Context): State {
        val p = prefs(context)
        return State(
            running = p.getBoolean(KEY_RUNNING, false),
            status = p.getString(KEY_STATUS, "").orEmpty(),
            count = p.getInt(KEY_COUNT, 0),
            conversation = p.getString(KEY_CONVERSATION, "").orEmpty(),
            finishedAt = p.getLong(KEY_FINISHED_AT, 0L)
        )
    }
}
