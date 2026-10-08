// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.util.Log
import java.util.Calendar
import java.util.regex.Pattern

/**
 * 日历提醒助手：分析判定 needAction 后，按三级降级链确保用户能被提醒到。
 *
 *  - **Level 1 直接写入**：有可写日历账户时直接 provider 插入事件，
 *    并写入 CalendarContract.Reminders 行（用户配置的提前量 + METHOD_ALERT），
 *    日历到点自己弹提醒；
 *  - **Level 2 唤起日历 App**：没有可写日历但有日历 App 时，用
 *    Intent(ACTION_INSERT) 预填标题/时间/描述唤起系统日历，由用户手动保存；
 *  - **Level 3 App 内闹钟**：连日历 App 都没有（如本模拟器）时，
 *    AlarmManager 定一个到点高优先级通知兜底（见 AlarmHelper/ReminderReceiver）。
 *
 * 防重机制：
 *  1. 先查 ReminderStore 的 dedupKey（caseId），同一窗口永不重复创建；
 *  2. Level 1 再用 CalendarContract.Events 按 title+dtstart 查系统日历，
 *     防住「reminders.jsonl 被删但日历里已有事件」的场景。
 */
object CalendarHelper {

    private const val TAG = "CalendarHelper"

    /** 是否已持有日历读写权限。 */
    fun hasCalendarPermission(context: Context): Boolean {
        return context.checkSelfPermission(android.Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED &&
            context.checkSelfPermission(android.Manifest.permission.WRITE_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED
    }

    /**
     * 为一条任务消息创建提醒（三级降级链 + 去重 + 结果落库）。
     *
     * @param dedupKey   消息指纹（caseId）
     * @param message    窗口末条消息（取发送者/原文/会话名）
     * @param summary    分析出的一句话概括
     * @param dueTimeText 分析出的截止时间文本（可能为空/不可解析）
     * @param baseTime   解析回退基准时间（分析完成时间）
     * @param leadMinutes 提醒提前量（分钟）
     * @return 结果备注（写入 ReminderStore.note 并打到分析日志）
     */
    @Synchronized
    fun createReminder(
        context: Context,
        dedupKey: String,
        message: ChatMessage,
        summary: String,
        dueTimeText: String,
        baseTime: Long,
        leadMinutes: Long
    ): String {
        val title = "任务：${summary.ifEmpty { message.text.take(12) }}（${message.conversation}）"
        val description = "发送者：${message.sender}\n原文：${message.text}\n会话：${message.conversation}"

        // 防重 1：只对「生效中」的记录去重（failed 不算，否则一次失败会永久挡掉后续提醒）
        if (ReminderStore.containsActive(context, dedupKey)) {
            return "跳过：已创建过（去重）"
        }

        val eventTime = parseDueTime(dueTimeText, baseTime)
        val result = runReminderChain(
            context, dedupKey, title, description, message.pkg, message.conversation,
            summary, eventTime, leadMinutes
        )
        // 写库策略：成功级别先清掉同指纹的旧记录（多为上一轮失败记录），保证一条消息只有一条生效记录；
        // 失败时若已有记录就不再追加，避免每轮分析都堆一条 failed（重试走列表页「重试」）。
        if (result.status != ReminderRecord.STATUS_FAILED) {
            ReminderStore.remove(context, dedupKey)
        } else if (ReminderStore.contains(context, dedupKey)) {
            return result.note
        }
        ReminderStore.append(
            context, ReminderRecord(
                dedupKey = dedupKey,
                title = title,
                description = description,
                conversation = message.conversation,
                eventTime = eventTime,
                calendarEventId = result.eventId ?: -1L,
                createdAt = System.currentTimeMillis(),
                status = result.status,
                note = result.note,
                pkg = message.pkg
            )
        )
        return result.note
    }

    /**
     * 三级降级链结果：状态 + 备注 + 已创建的系统日历事件 id（仅 L1 成功时有值）。
     * eventId 必须落库到 ReminderRecord，否则提醒管理无法删除对应日历事件。
     */
    private data class ChainResult(
        val status: String,
        val note: String,
        val eventId: Long? = null
    )

    /**
     * 三级降级链主体（createReminder 与列表页「重试」共用）。
     */
    private fun runReminderChain(
        context: Context,
        dedupKey: String,
        title: String,
        description: String,
        pkg: String,
        conversation: String,
        summary: String,
        eventTime: Long,
        leadMinutes: Long
    ): ChainResult {
        // Level 1：直接写入系统日历（有权限 + 有可写日历账户才走）
        if (hasCalendarPermission(context)) {
            val calendarId = findWritableCalendar(context)
            if (calendarId != null) {
                // 防重 2：系统日历里已存在同标题同起始时间的事件
                if (eventExists(context, title, eventTime)) {
                    Log.i(TAG, "L1 跳过：系统日历已存在相同事件")
                    return ChainResult(
                        ReminderRecord.STATUS_CALENDAR,
                        "跳过：系统日历已存在相同事件（去重）"
                    )
                }
                val eventId = insertEvent(context, calendarId, title, description, eventTime, leadMinutes)
                if (eventId != null) {
                    Log.i(TAG, "L1 日历事件已创建: id=$eventId title=$title")
                    return ChainResult(
                        ReminderRecord.STATUS_CALENDAR,
                        "成功：已写入系统日历",
                        eventId
                    )
                }
                Log.w(TAG, "L1 插入失败，降级 L2")
            } else {
                Log.i(TAG, "L1 跳过：无可写日历账户，降级 L2")
            }
        } else {
            Log.i(TAG, "L1 跳过：无日历权限，降级 L2")
        }

        // Level 2：唤起系统日历 App（预填，用户手动保存）
        // 仅在 App 有可见界面时尝试：后台（Worker / 通知回调）里 startActivity 会被
        // Android 10+ 的「后台启动 Activity」限制静默拦截，却让链路误记为已提醒成功。
        if (MainApplication.isInForeground &&
            openCalendarInsert(context, title, description, eventTime)
        ) {
            Log.i(TAG, "L2 已唤起日历 App: title=$title")
            return ChainResult(ReminderRecord.STATUS_INTENT, "已唤起日历，待手动保存")
        }
        Log.i(TAG, "L2 跳过：App 不在前台或无日历 App，降级 L3")

        // Level 3：App 内闹钟到点通知兜底
        return if (AlarmHelper.scheduleReminder(
                context, dedupKey, title, summary, pkg, conversation, eventTime, leadMinutes
            )
        ) {
            ChainResult(ReminderRecord.STATUS_ALARM, "已设App内提醒")
        } else {
            ChainResult(
                ReminderRecord.STATUS_FAILED,
                "失败：无日历账户、无日历App、闹钟设置异常"
            )
        }
    }

    /**
     * 列表页「重试」：用记录里已存的标题/描述/时间重跑三级链。
     * 先删旧记录再写新记录，dedupKey 不变（同窗口只允许一条生效记录）。
     */
    @Synchronized
    fun retryReminder(context: Context, record: ReminderRecord, leadMinutes: Long): String {
        val eventTime = if (record.eventTime > 0) {
            record.eventTime
        } else {
            // 老失败记录没解析出时间：按 parseDueTime 的回退语义补一个 1 小时后
            System.currentTimeMillis() + 60L * 60_000L
        }
        val summary = record.title
            .removePrefix("任务：")
            .substringBeforeLast("（")
        ReminderStore.remove(context, record.dedupKey)
        val result = runReminderChain(
            context, record.dedupKey, record.title, record.description,
            record.pkg, record.conversation, summary, eventTime, leadMinutes
        )
        ReminderStore.append(
            context, record.copy(
                eventTime = eventTime,
                calendarEventId = result.eventId ?: record.calendarEventId,
                createdAt = System.currentTimeMillis(),
                status = result.status,
                note = "重试：$result.note"
            )
        )
        return result.note
    }

    /** 删除指定 id 的系统日历事件（提醒管理「删除/取消」用），返回是否删除成功。 */
    fun deleteEvent(context: Context, eventId: Long): Boolean {
        return try {
            val rows = context.contentResolver.delete(
                CalendarContract.Events.CONTENT_URI,
                "${CalendarContract.Events._ID} = ?",
                arrayOf(eventId.toString())
            )
            rows > 0
        } catch (e: Exception) {
            Log.w(TAG, "删除日历事件失败", e)
            false
        }
    }

    /** 构造「唤起系统日历 App」的预填 intent（Level 2 与列表页「再次唤起」共用）。 */
    fun buildCalendarInsertIntent(title: String, description: String, eventTime: Long): Intent {
        return Intent(Intent.ACTION_INSERT)
            .setData(CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, title)
            .putExtra(CalendarContract.Events.DESCRIPTION, description)
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, eventTime)
            // 无持续时长信息的任务提醒，默认占 1 小时
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, eventTime + 60L * 60_000L)
            .putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, false)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** 唤起系统日历 App；无可处理 App 或启动异常返回 false（降级下一级）。 */
    fun openCalendarInsert(
        context: Context,
        title: String,
        description: String,
        eventTime: Long
    ): Boolean {
        val intent = buildCalendarInsertIntent(title, description, eventTime)
        if (intent.resolveActivity(context.packageManager) == null) return false
        return try {
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.w(TAG, "唤起日历 App 失败", e)
            false
        }
    }

    /** 查询本机第一个可写日历 id；没有（如模拟器未登录账户）返回 null。 */
    private fun findWritableCalendar(context: Context): Long? {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL
        )
        return try {
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI, projection, null, null, null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val access = cursor.getInt(1)
                    if (access >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR) {
                        return cursor.getLong(0)
                    }
                }
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "查询日历列表失败", e)
            null
        }
    }

    /** 系统日历中是否已存在同标题同起始时间的事件。 */
    private fun eventExists(context: Context, title: String, dtstart: Long): Boolean {
        val projection = arrayOf(CalendarContract.Events._ID)
        val selection = "${CalendarContract.Events.TITLE} = ? AND ${CalendarContract.Events.DTSTART} = ?"
        return try {
            context.contentResolver.query(
                CalendarContract.Events.CONTENT_URI, projection, selection,
                arrayOf(title, dtstart.toString()), null
            )?.use { it.count > 0 } ?: false
        } catch (e: Exception) {
            Log.w(TAG, "查询日历事件失败", e)
            false // 查询失败不阻塞创建（宁可信本 App 的去重记录）
        }
    }

    /** 插入日历事件与提前提醒（Reminders 行含提前量 + METHOD_ALERT），返回事件 id；失败返回 null。 */
    private fun insertEvent(
        context: Context,
        calendarId: Long,
        title: String,
        description: String,
        dtstart: Long,
        leadMinutes: Long
    ): Long? {
        return try {
            val values = ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calendarId)
                put(CalendarContract.Events.TITLE, title)
                put(CalendarContract.Events.DESCRIPTION, description)
                put(CalendarContract.Events.DTSTART, dtstart)
                // 无持续时长信息的任务提醒，默认占 1 小时
                put(CalendarContract.Events.DTEND, dtstart + 60L * 60_000L)
                put(CalendarContract.Events.EVENT_TIMEZONE, java.util.TimeZone.getDefault().id)
            }
            val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            val eventId = uri?.lastPathSegment?.toLongOrNull() ?: return null

            // 提前提醒（通知方式）：用户配置的提前量，决定日历时能否真正弹提醒
            val reminderValues = ContentValues().apply {
                put(CalendarContract.Reminders.EVENT_ID, eventId)
                put(CalendarContract.Reminders.MINUTES, leadMinutes)
                put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
            }
            context.contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, reminderValues)
            eventId
        } catch (e: Exception) {
            Log.w(TAG, "插入日历事件失败", e)
            null
        }
    }

    /**
     * 解析中文截止时间文本，返回毫秒时间戳；解析不出回退为 baseTime + 1 小时。
     *
     * 支持模式：
     *  - 「yyyy-MM-dd HH:mm」（也兼容 yyyy/M/d、yyyy年M月d日）
     *  - 「M月d日[H点|HH:mm]」（缺年份取本年，已过去则取明年）
     *  - 「今天/明天/后天 HH:mm」（无时间默认 9:00）
     *  - 「周X/星期X HH:mm」（当天时刻未到取当天，已过才取下周）
     *  - 时间部分支持「下午/晚上」加 12 小时
     */
    fun parseDueTime(text: String, baseTime: Long): Long {
        val fallback = baseTime + 60L * 60_000L
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return fallback

        try {
            val cal = Calendar.getInstance()
            cal.timeInMillis = baseTime

            // 时间部分（可选）：HH:mm / H点 / 下午H点
            val timeMatcher = Pattern.compile("(\\d{1,2})\\s*[:：点时]\\s*(\\d{1,2})?").matcher(trimmed)
            var hour = 9
            var minute = 0
            if (timeMatcher.find()) {
                hour = timeMatcher.group(1)?.toIntOrNull() ?: 9
                minute = timeMatcher.group(2)?.toIntOrNull() ?: 0
                if ((trimmed.contains("下午") || trimmed.contains("晚上") || trimmed.contains("傍晚")) && hour < 12) {
                    hour += 12
                }
                if (hour > 23 || minute > 59) return fallback
            }

            fun buildTime(year: Int, month0: Int, day: Int): Long {
                val c = Calendar.getInstance()
                c.set(year, month0, day, hour, minute, 0)
                c.set(Calendar.MILLISECOND, 0)
                return c.timeInMillis
            }

            // 1) 带完整年月日：2025-01-01 / 2025/1/1 / 2025年1月1日
            val full = Pattern.compile("(\\d{4})\\s*[-/年]\\s*(\\d{1,2})\\s*[-/月]\\s*(\\d{1,2})").matcher(trimmed)
            if (full.find()) {
                return buildTime(
                    full.group(1)!!.toInt(),
                    full.group(2)!!.toInt() - 1,
                    full.group(3)!!.toInt()
                )
            }

            // 2) M月d日（无年份，已过去则顺延到明年）
            val md = Pattern.compile("(\\d{1,2})\\s*月\\s*(\\d{1,2})\\s*[日号]").matcher(trimmed)
            if (md.find()) {
                val year = cal.get(Calendar.YEAR)
                var t = buildTime(year, md.group(1)!!.toInt() - 1, md.group(2)!!.toInt())
                if (t < baseTime - 60_000L) t = buildTime(year + 1, md.group(1)!!.toInt() - 1, md.group(2)!!.toInt())
                return t
            }

            // 3) 今天/明天/后天
            val dayOffset = when {
                trimmed.contains("后天") -> 2
                trimmed.contains("明天") -> 1
                trimmed.contains("今天") -> 0
                else -> -1
            }
            if (dayOffset >= 0) {
                val c = Calendar.getInstance()
                c.timeInMillis = baseTime
                c.add(Calendar.DAY_OF_YEAR, dayOffset)
                return buildTime(c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH))
            }

            // 4) 周X / 星期X：取未来最近一次
            val week = Pattern.compile("[周星]\\s*期?\\s*([一二三四五六日天1-7])").matcher(trimmed)
            if (week.find()) {
                val target = when (week.group(1)) {
                    "一", "1" -> Calendar.MONDAY
                    "二", "2" -> Calendar.TUESDAY
                    "三", "3" -> Calendar.WEDNESDAY
                    "四", "4" -> Calendar.THURSDAY
                    "五", "5" -> Calendar.FRIDAY
                    "六", "6" -> Calendar.SATURDAY
                    else -> Calendar.SUNDAY
                }
                val c = Calendar.getInstance()
                c.timeInMillis = baseTime
                var diff = (target - c.get(Calendar.DAY_OF_WEEK) + 7) % 7
                if (diff == 0) {
                    // 就是今天：时刻还没到就用今天，已过才顺延到下周。
                    // 原先一律顺延，会把「今天 16:30」的通知算成一周后。
                    val todayAt = buildTime(
                        c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)
                    )
                    if (todayAt <= baseTime) diff = 7
                }
                c.add(Calendar.DAY_OF_YEAR, diff)
                return buildTime(c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH))
            }
        } catch (e: Exception) {
            Log.w(TAG, "截止时间解析失败: $trimmed", e)
        }
        return fallback
    }
}
