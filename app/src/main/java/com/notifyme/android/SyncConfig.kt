// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import android.content.Context

/**
 * 定时上报配置与最近同步状态，存 SharedPreferences（文件名 sync_config）。
 *
 *  - serverUrl：服务器地址，空串 = 未配置，不上报（定时任务也不启用）；
 *  - authToken：访问令牌，空串 = 不带鉴权头；与服务端环境变量 WEIXIN_TOKEN 对应，
 *    服务端设了令牌时此处必须填同样的值，否则请求会被 401 拒绝；
 *  - intervalMinutes：上报周期（分钟），默认 30，UI 可选 15/30/60/120，
 *    注意 WorkManager 周期任务的下限就是 15 分钟，更短无法承诺；
 *  - lastSyncTime / lastSyncResult：最近一次同步的时间与结果，供主界面展示。
 */
class SyncConfig(context: Context) {

    companion object {
        private const val PREFS_NAME = "sync_config"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_AUTH_TOKEN = "auth_token"
        private const val KEY_PUSH_ENABLED = "push_enabled"
        private const val KEY_INTERVAL_MINUTES = "interval_minutes"
        private const val KEY_LAST_SYNC_TIME = "last_sync_time"
        private const val KEY_LAST_SYNC_RESULT = "last_sync_result"

        const val DEFAULT_INTERVAL_MINUTES = 30L

        /** UI 可选项；顺序与 strings.xml 的 sync_interval_labels 一一对应 */
        val INTERVAL_OPTIONS = longArrayOf(15, 30, 60, 120)
    }

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        // test 包首次启动用 BuildConfig 预填服务器地址/令牌（仅填充，用户可改）；
        // open 包 DEFAULT_* 为空串，不写入任何值
        if (!prefs.contains(KEY_SERVER_URL) && BuildConfig.DEFAULT_SERVER_URL.isNotEmpty()) {
            prefs.edit().putString(KEY_SERVER_URL, BuildConfig.DEFAULT_SERVER_URL).apply()
        }
        if (!prefs.contains(KEY_AUTH_TOKEN) && BuildConfig.DEFAULT_MONITOR_TOKEN.isNotEmpty()) {
            prefs.edit().putString(KEY_AUTH_TOKEN, BuildConfig.DEFAULT_MONITOR_TOKEN).apply()
        }
    }

    var serverUrl: String
        get() = prefs.getString(KEY_SERVER_URL, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_SERVER_URL, value.trim()).apply()

    var authToken: String
        get() = prefs.getString(KEY_AUTH_TOKEN, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_AUTH_TOKEN, value.trim()).apply()

    /** 推送总开关：默认 true（兼容旧版本行为）；关闭后消息继续入队，重新开启时一并上报 */
    var pushEnabled: Boolean
        get() = prefs.getBoolean(KEY_PUSH_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_PUSH_ENABLED, value).apply()

    var intervalMinutes: Long
        get() = prefs.getLong(KEY_INTERVAL_MINUTES, DEFAULT_INTERVAL_MINUTES)
        set(value) = prefs.edit().putLong(KEY_INTERVAL_MINUTES, value).apply()

    var lastSyncTime: Long
        get() = prefs.getLong(KEY_LAST_SYNC_TIME, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_SYNC_TIME, value).apply()

    var lastSyncResult: String
        get() = prefs.getString(KEY_LAST_SYNC_RESULT, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_LAST_SYNC_RESULT, value).apply()
}
