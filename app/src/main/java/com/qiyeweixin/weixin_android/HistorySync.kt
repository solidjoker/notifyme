// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.qiyeweixin.weixin_android

import android.content.Context
import android.util.Log
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * 启动时自动提取已有微信聊天记录（沿用服务端 /admin/extract 链路）：
 *
 * 流程：POST /admin/extract 触发服务端直读安卓微信库 -> 轮询 /admin/extract/status
 * 直到跑完 -> GET /messages?limit=1000 拉全量（含 android-db 来源）->
 * MessageStore.merge 秒级去重回填本地会话存储，首页/会话页随之可见。
 *
 * 降级策略（全部静默，不阻塞 UI、不崩）：
 *  - 开关关闭 / 冷却期内（6 小时）/ 未配置服务器地址 -> 直接跳过并记录状态；
 *  - 服务端不可达、无 root、微信未装（服务端 error）-> 记录状态行，界面正常；
 *  - 409（服务端正在提取）视为正常，接管轮询等结果。
 *
 * 状态存 SharedPreferences（history_sync），控制台「聊天记录提取」卡展示。
 */
object HistorySync {

    private const val TAG = "HistorySync"
    private const val PREFS_NAME = "history_sync"
    private const val KEY_AUTO_ENABLED = "auto_enabled"
    private const val KEY_LAST_RUN_AT = "last_run_at"
    private const val KEY_LAST_STATUS = "last_status"

    /** 冷却：6 小时内不重复自动提取 */
    private const val COOLDOWN_MS = 6L * 3600 * 1000

    /** 单次拉取上限（与服务端 /messages 的 limit 上限一致） */
    private const val FETCH_LIMIT = 1000

    /** 轮询提取状态：每 2s 一次，最多 90s（提取通常 10-30s） */
    private const val POLL_INTERVAL_MS = 2000L
    private const val POLL_TIMEOUT_MS = 90_000L

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isAutoEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_ENABLED, true) // 默认开

    fun setAutoEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_ENABLED, enabled).apply()
    }

    fun lastStatus(context: Context): String =
        prefs(context).getString(KEY_LAST_STATUS, "").orEmpty()

    /**
     * App 冷启动入口（MainApplication.onCreate 调用）。
     * 条件不满足时只写状态（冷却期内跳过不写，避免覆盖上一次有效结果）。
     */
    fun maybeRunOnStartup(context: Context) {
        val app = context.applicationContext
        if (!isAutoEnabled(app)) {
            Log.i(TAG, "自动提取开关关闭，跳过")
            return
        }
        val lastRun = prefs(app).getLong(KEY_LAST_RUN_AT, 0L)
        if (System.currentTimeMillis() - lastRun < COOLDOWN_MS) {
            Log.i(TAG, "冷却期内（6h），跳过自动提取")
            return
        }
        val syncConfig = SyncConfig(app)
        if (syncConfig.serverUrl.isBlank()) {
            record(app, "跳过：未配置服务器地址")
            return
        }
        // 先占位再跑，防止多入口并发触发两轮
        prefs(app).edit().putLong(KEY_LAST_RUN_AT, System.currentTimeMillis()).apply()
        thread(name = "history-sync", isDaemon = true) { run(app, syncConfig) }
    }

    private fun run(context: Context, syncConfig: SyncConfig) {
        try {
            // 1) 触发提取（409 = 服务端正在跑，同样转轮询）
            val trigger = request(syncConfig, endpoint(syncConfig, "admin/extract"))
                .post(FormBody.Builder().build()).build()
            client.newCall(trigger).execute().use { resp ->
                if (!resp.isSuccessful && resp.code != 409) {
                    record(context, "失败：触发提取 HTTP ${resp.code}")
                    return
                }
            }

            // 2) 轮询直到跑完
            val deadline = System.currentTimeMillis() + POLL_TIMEOUT_MS
            var last: JSONObject? = null
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(POLL_INTERVAL_MS)
                val status = fetchStatus(syncConfig) ?: continue // 网络抖动继续等
                if (!status.optBoolean("running", true)) {
                    last = status.optJSONObject("last")
                    break
                }
            }
            if (last == null) {
                record(context, "失败：等待提取结果超时")
                return
            }
            if (!last.optBoolean("ok")) {
                // 服务端侧失败（无 root / 微信未装 / 库解密失败等），原样透传
                record(context, "失败：" + last.optString("error", "未知错误"))
                return
            }

            // 3) 拉全量消息回填本地（秒级去重）
            val messages = fetchMessages(syncConfig)
                ?: run { record(context, "失败：拉取历史消息网络错误"); return }
            val incoming = messages.mapNotNull { obj ->
                val text = obj.optString("text")
                val conv = obj.optString("conversation")
                if (text.isBlank() || conv.isBlank()) null
                else ChatMessage(
                    sender = obj.optString("sender"),
                    text = text,
                    timestamp = obj.optLong("timestamp"),
                    conversation = conv,
                    isGroup = obj.optBoolean("is_group") // 服务端蛇形命名
                )
            }
            val added = MessageStore.merge(context, incoming)
            Log.i(TAG, "历史回填完成：拉取 ${incoming.size} 条，新增 $added 条")
            record(context, "成功：回填 $added 条历史消息（服务端共 ${incoming.size} 条）")
        } catch (e: Exception) {
            Log.w(TAG, "自动提取失败", e)
            record(context, "失败：" + (e.message ?: "未知错误"))
        }
    }

    // ---------- 内部工具 ----------

    private fun record(context: Context, result: String) {
        val time = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
            .format(Date())
        prefs(context).edit()
            .putString(KEY_LAST_STATUS, "$time  $result")
            .apply()
    }

    /** 与 ConsoleActivity.extractEndpoint 同一推导规则：去末尾 /weixin 再拼路径。 */
    private fun endpoint(syncConfig: SyncConfig, path: String): String {
        var base = syncConfig.serverUrl.trim().trimEnd('/')
        if (base.endsWith("/weixin")) base = base.dropLast("/weixin".length)
        return "$base/$path"
    }

    private fun request(syncConfig: SyncConfig, url: String): Request.Builder {
        val builder = Request.Builder().url(url)
        if (syncConfig.authToken.isNotBlank()) {
            builder.header("X-Token", syncConfig.authToken)
        }
        return builder
    }

    private fun fetchStatus(syncConfig: SyncConfig): JSONObject? {
        return try {
            client.newCall(
                request(syncConfig, endpoint(syncConfig, "admin/extract/status")).get().build()
            ).execute().use { resp ->
                if (!resp.isSuccessful) null
                else try {
                    JSONObject(resp.body?.string().orEmpty())
                } catch (e: Exception) {
                    null
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    /** GET /messages?limit=1000，网络/解析失败返回 null。 */
    private fun fetchMessages(syncConfig: SyncConfig): List<JSONObject>? {
        return try {
            client.newCall(
                request(syncConfig, endpoint(syncConfig, "messages?limit=$FETCH_LIMIT"))
                    .get().build()
            ).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val arr = JSONObject(resp.body?.string().orEmpty())
                    .optJSONArray("messages") ?: return null
                (0 until arr.length()).map { arr.getJSONObject(it) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "拉取历史消息失败", e)
            null
        }
    }
}
