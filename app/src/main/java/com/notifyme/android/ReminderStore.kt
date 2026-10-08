// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * 一条提醒记录（三级降级链的产物）。
 *
 * @param dedupKey        消息指纹（caseId），去重键
 * @param title           事件标题
 * @param description     事件描述（发送者/原文/会话），「再次唤起」重放 intent 用
 * @param conversation    来源会话名，App 内提醒通知点击跳详情页用
 * @param pkg             来源 App 包名（M2，缺省＝微信，老记录读进来自动归微信）
 * @param eventTime       事件开始时间（毫秒）；0 表示当时解析失败
 * @param calendarEventId 系统日历事件 id；非日历直写级别为 -1
 * @param createdAt       记录创建时间（毫秒）
 * @param status          结果级别，见 STATUS_* 常量
 * @param note            结果备注（成功/失败原因/去重跳过等细节）
 */
data class ReminderRecord(
    val dedupKey: String,
    val title: String,
    val description: String,
    val conversation: String,
    val eventTime: Long,
    val calendarEventId: Long,
    val createdAt: Long,
    val status: String,
    val note: String,
    val pkg: String = AppSourceRegistry.PKG_WECHAT
) {
    /** 来源会话复合键（M2）。 */
    val convKey: ConvKey get() = ConvKey(pkg, conversation)

    fun toJson(): JSONObject = JSONObject().apply {
        put("dedup_key", dedupKey)
        put("title", title)
        put("description", description)
        put("conversation", conversation)
        put("event_time", eventTime)
        put("calendar_event_id", calendarEventId)
        put("created_at", createdAt)
        put("status", status)
        put("note", note)
        put("pkg", pkg)
    }

    companion object {
        /** Level 1：已直接写入系统日历 */
        const val STATUS_CALENDAR = "calendar"
        /** Level 2：已唤起系统日历 App，等用户手动保存 */
        const val STATUS_INTENT = "intent"
        /** Level 3：已设 App 内闹钟到点通知 */
        const val STATUS_ALARM = "alarm"
        /** 三级都不可用 */
        const val STATUS_FAILED = "failed"

        fun fromJson(obj: JSONObject): ReminderRecord {
            val note = obj.optString("note")
            // 旧记录无 status 字段：按 note 前缀推断，保证列表徽标不空
            val status = obj.optString("status").ifEmpty {
                when {
                    note.startsWith("成功") || note.startsWith("跳过") -> STATUS_CALENDAR
                    note.startsWith("失败") -> STATUS_FAILED
                    else -> STATUS_FAILED
                }
            }
            return ReminderRecord(
                dedupKey = obj.optString("dedup_key"),
                title = obj.optString("title"),
                description = obj.optString("description"),
                conversation = obj.optString("conversation"),
                eventTime = obj.optLong("event_time"),
                calendarEventId = obj.optLong("calendar_event_id", -1L),
                createdAt = obj.optLong("created_at"),
                status = status,
                note = note,
                pkg = obj.optString("pkg").ifEmpty { AppSourceRegistry.PKG_WECHAT }
            )
        }
    }
}

/**
 * 提醒记录存储：JSON Lines 追加写入 files/reminders.jsonl。
 * 插入日历事件前先查 contains() 去重；重试时用 remove() 清掉旧记录再重写。
 */
object ReminderStore {

    private const val FILE_NAME = "reminders.jsonl"

    private fun storeFile(context: Context): File =
        File(context.filesDir, FILE_NAME)

    /** 追加一条提醒记录。 */
    @Synchronized
    fun append(context: Context, record: ReminderRecord) {
        storeFile(context).appendText(record.toJson().toString() + "\n", Charsets.UTF_8)
    }

    /** 该消息指纹是否已有提醒记录（不区分状态，用于「这条消息到底提醒过没有」）。 */
    @Synchronized
    fun contains(context: Context, dedupKey: String): Boolean {
        val file = storeFile(context)
        if (!file.exists()) return false

        return file.readLines(Charsets.UTF_8)
            .asSequence()
            .filter { it.isNotBlank() }
            .any { line ->
                try {
                    JSONObject(line).optString("dedup_key") == dedupKey
                } catch (e: Exception) {
                    false
                }
            }
    }

    /**
     * 该指纹是否已有**生效中**的提醒（去重只认这个）。
     *
     * `failed` 记录不算数：否则一次链路失败（无日历账户 / 无通知权限 / 闹钟异常）会把这条
     * 消息的提醒永久钉死——后续每轮分析都会被「已创建过」挡回去，用户再也收不到提醒。
     */
    @Synchronized
    fun containsActive(context: Context, dedupKey: String): Boolean {
        return readAll(context).any {
            it.dedupKey == dedupKey && it.status != ReminderRecord.STATUS_FAILED
        }
    }

    /**
     * 就地改某指纹记录的状态与备注（到点却发不出通知等运行期失败回写用）。
     *
     * 只改匹配行；损坏行原样保留（与其他 JSONL store 同一口径：不借修改之名丢数据）。
     * 返回是否命中了记录。
     */
    @Synchronized
    fun markStatus(context: Context, dedupKey: String, status: String, note: String): Boolean {
        val file = storeFile(context)
        if (!file.exists()) return false

        var touched = false
        val kept = file.readLines(Charsets.UTF_8).mapNotNull { line ->
            if (line.isBlank()) return@mapNotNull null
            val obj = try {
                JSONObject(line)
            } catch (e: Exception) {
                return@mapNotNull line // 损坏行保留
            }
            if (obj.optString("dedup_key") != dedupKey) return@mapNotNull line
            touched = true
            ReminderRecord.fromJson(obj).copy(status = status, note = note).toJson().toString()
        }
        if (touched) JsonlStore.atomicWrite(file, kept)
        return touched
    }

    /** 删除指定指纹的全部记录（重试前清理失败/旧级别记录用）。 */
    @Synchronized
    fun remove(context: Context, dedupKey: String) {
        val file = storeFile(context)
        if (!file.exists()) return

        val kept = file.readLines(Charsets.UTF_8)
            .filter { line ->
                if (line.isBlank()) return@filter false
                try {
                    JSONObject(line).optString("dedup_key") != dedupKey
                } catch (e: Exception) {
                    true // 损坏行保留，不借重试之名丢数据
                }
            }
        // 空则删文件，否则原子重写（与其余 JSONL store 同一落盘口径）
        JsonlStore.atomicWrite(file, kept)
    }

    /** 读取全部提醒记录，按创建时间倒序（最新在前），供列表页展示。 */
    @Synchronized
    fun readAll(context: Context): List<ReminderRecord> {
        val file = storeFile(context)
        if (!file.exists()) return emptyList()

        return file.readLines(Charsets.UTF_8)
            .asSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                try {
                    ReminderRecord.fromJson(JSONObject(line))
                } catch (e: Exception) {
                    null
                }
            }
            .toList()
            .sortedByDescending { it.createdAt }
    }
}
