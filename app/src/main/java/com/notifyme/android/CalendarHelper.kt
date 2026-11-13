// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import android.util.Log

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
        return PermissionGateCore.isGranted(
            context.checkSelfPermission(android.Manifest.permission.READ_CALENDAR)
        ) && PermissionGateCore.isGranted(
            context.checkSelfPermission(android.Manifest.permission.WRITE_CALENDAR)
        )
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
    ): String = createReminder(
        context, dedupKey, message.pkg, message.conversation,
        message.sender, message.text, summary, dueTimeText, baseTime, leadMinutes
    )

    /**
     * 提醒链结构化结果（M6）：UI 分支不再耦合中文文案前缀。
     * created=true 仅表示「本次真正创建了提醒」（L1 插入成功 / L3 闹钟成功）；
     * 去重跳过、L2 待手动保存、失败均为 false。status 为落库状态（跳过时 null）。
     */
    data class ReminderOutcome(
        val note: String,
        val created: Boolean,
        val status: String? = null
    )

    /** 字符串版（Worker / 日志用）：返回写库 note。UI 分支请用 [createReminderResult]。 */
    @Synchronized
    fun createReminder(
        context: Context,
        dedupKey: String,
        pkg: String,
        conversation: String,
        sender: String,
        sourceText: String,
        summary: String,
        dueTimeText: String,
        baseTime: Long,
        leadMinutes: Long
    ): String = createReminderResult(
        context, dedupKey, pkg, conversation, sender, sourceText,
        summary, dueTimeText, baseTime, leadMinutes
    ).note

    /**
     * 原语字段版（M12 主页待办手动「加日历」不依赖消息对象）：
     * sender/sourceText 可为空串，描述行按非空逐条拼接。
     */
    @Synchronized
    fun createReminderResult(
        context: Context,
        dedupKey: String,
        pkg: String,
        conversation: String,
        sender: String,
        sourceText: String,
        summary: String,
        dueTimeText: String,
        baseTime: Long,
        leadMinutes: Long,
        titleOverride: String = "",
        explicitEventTime: Long? = null
    ): ReminderOutcome {
        // 手动提醒（M17）用用户自己写的标题，不加「任务：」前缀；会话信息留在描述里
        val title = titleOverride.ifEmpty {
            "任务：${summary.ifEmpty { TextClipCore.clip(sourceText, 12) }}（$conversation）"
        }
        val description = listOfNotNull(
            "发送者：$sender".takeIf { sender.isNotEmpty() },
            "原文：$sourceText".takeIf { sourceText.isNotEmpty() },
            "会话：$conversation"
        ).joinToString("\n")

        // 防重 1：只对「生效中」的记录去重（failed 不算，否则一次失败会永久挡掉后续提醒）
        if (ReminderStore.containsActive(context, dedupKey)) {
            return ReminderOutcome("跳过：已创建过（去重）", created = false)
        }

        // explicitEventTime：UI 已经算好精确时间，不再走「文本再解析」的有损往返
        val eventTime = explicitEventTime ?: parseDueTime(dueTimeText, baseTime)
        val result = runReminderChain(
            context, dedupKey, title, description, pkg, conversation,
            summary, eventTime, leadMinutes
        )
        // 写库策略：成功级别先清掉同指纹的旧记录（多为上一轮失败记录），保证一条消息只有一条生效记录；
        // 失败时若已有记录就不再追加，避免每轮分析都堆一条 failed（重试走列表页「重试」）。
        if (result.status != ReminderRecord.STATUS_FAILED) {
            ReminderStore.remove(context, dedupKey)
        } else if (ReminderStore.contains(context, dedupKey)) {
            return ReminderOutcome(result.note, created = false, status = result.status)
        }
        ReminderStore.append(
            context, ReminderRecord(
                dedupKey = dedupKey,
                title = title,
                description = description,
                conversation = conversation,
                eventTime = eventTime,
                calendarEventId = result.eventId ?: -1L,
                createdAt = System.currentTimeMillis(),
                status = result.status,
                note = result.note,
                pkg = pkg
            )
        )
        // created：L1 真实插入（eventId 非空）或 L3 闹钟成功才算本次创建了提醒；
        // L1 去重跳过（CALENDAR 但无 eventId）与 L2 待手动保存均不算
        val created = (result.status == ReminderRecord.STATUS_CALENDAR && result.eventId != null) ||
            result.status == ReminderRecord.STATUS_ALARM
        return ReminderOutcome(result.note, created = created, status = result.status)
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
     *
     * 决策本身在 [ReminderChainCore]（纯函数、可单测）；这里只做三件事：
     * 采集环境事实 → 执行该级别对应的副作用 → 把结果翻译回 [ChainResult]。
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
        // Level 1 事实采集：权限 + 可写日历账户 + 系统日历里是否已有同标题同起始时间的事件（防重 2）
        val hasPermission = hasCalendarPermission(context)
        val calendarId = if (hasPermission) findWritableCalendar(context) else null
        val duplicate = if (calendarId != null) eventExists(context, title, eventTime) else false
        val env = ReminderChainCore.Env(
            hasCalendarPermission = hasPermission,
            hasWritableCalendar = calendarId != null,
            eventAlreadyExists = duplicate
        )
        when (ReminderChainCore.calendarAction(env)) {
            ReminderChainCore.CalendarAction.SKIP_DUPLICATE -> {
                Log.i(TAG, "L1 跳过：系统日历已存在相同事件")
                return chain(ReminderChainCore.duplicateSkip())
            }
            ReminderChainCore.CalendarAction.INSERT -> {
                val eventId = insertEvent(
                    context, calendarId!!, title, description, eventTime, leadMinutes
                )
                if (eventId != null) {
                    Log.i(TAG, "L1 日历事件已创建: id=$eventId title=$title")
                    return chain(ReminderChainCore.calendarSuccess(eventId))
                }
                Log.w(TAG, "L1 插入失败，降级 L2")
            }
            ReminderChainCore.CalendarAction.UNAVAILABLE -> {
                if (hasPermission) Log.i(TAG, "L1 跳过：无可写日历账户，降级 L2")
                else Log.i(TAG, "L1 跳过：无日历权限，降级 L2")
            }
        }

        // Level 2：唤起系统日历 App（预填，用户手动保存）
        // 仅在 App 有可见界面时尝试：后台（Worker / 通知回调）里 startActivity 会被
        // Android 10+ 的「后台启动 Activity」限制静默拦截，却让链路误记为已提醒成功。
        // openCalendarInsert 内部先 resolveActivity 再 startActivity，"能否唤起" 直接取它的返回值，
        // 不再单独做一次包查询（重复查询没有额外信息，只会让 L2 多一次 IPC）。
        val inForeground = MainApplication.isInForeground
        val intentShown = inForeground && openCalendarInsert(context, title, description, eventTime)
        val envL2 = env.copy(inForeground = inForeground, canOpenCalendar = intentShown)
        if (intentShown) {
            Log.i(TAG, "L2 已唤起日历 App: title=$title")
            return chain(ReminderChainCore.intentOpened())
        }
        Log.i(TAG, "L2 跳过：App 不在前台或无日历 App，降级 L3")

        // Level 3：App 内闹钟到点通知兜底
        val alarmSet = AlarmHelper.scheduleReminder(
            context, dedupKey, title, summary, pkg, conversation, eventTime, leadMinutes
        )
        val outcome = ReminderChainCore.decide(
            envL2, insertedEventId = null, intentShown = intentShown, alarmSet = alarmSet
        )
        if (alarmSet) Log.i(TAG, "L3 已设 App 内闹钟") else Log.w(TAG, "L3 闹钟设置失败")
        return chain(outcome)
    }

    /** 纯函数结果 → 链路内部结果（eventId 必须继续传到 ReminderRecord，否则提醒管理删不掉日历事件）。 */
    private fun chain(outcome: ReminderChainCore.Outcome): ChainResult =
        ChainResult(outcome.status, outcome.note, outcome.eventId)

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
                note = "重试：${result.note}"
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
     * 解析截止时间文本；**解析不出返回 null**。
     *
     * 口径全部搬进 [DueTimeCore]（`java.time`，恒公历）：这里以前用 `Calendar.getInstance()` +
     * 默认 Locale 的历法，泰语（佛历）/ 和历设备上会把年份解释错、甚至静默丢掉日期。
     * 支持：完整年月日 / M月d日（已过顺延明年）/ 今天明天后天 / 周X / 下午晚上 +12 /
     * 纯 HH:mm（今天该时刻，已过顺延明天，M17 手动提醒常见写法）。
     */
    fun tryParseDueTime(
        text: String,
        baseTime: Long,
        zone: java.time.ZoneId = java.time.ZoneId.systemDefault()
    ): Long? = DueTimeCore.parse(text, baseTime, zone)

    /** 解析不出时回退 baseTime + 1 小时（分析链路沿用：模型没给时间也要有提醒时间）。 */
    fun parseDueTime(text: String, baseTime: Long): Long =
        tryParseDueTime(text, baseTime) ?: (baseTime + 60L * 60_000L)
}
