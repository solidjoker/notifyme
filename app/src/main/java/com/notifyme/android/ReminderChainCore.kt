// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

/**
 * 提醒三级降级链的决策纯函数（不碰 Android API，可单测）。
 *
 * 为什么单独抽出来：`CalendarHelper.runReminderChain` 的三级选择原来是「权限 / 可写日历 / 去重 /
 * 前台 / 日历 App / 闹钟」一堆环境事实直接写在 if 里，纯 JVM 单测进不去（依赖 Context、
 * ContentResolver、MainApplication.isInForeground），于是这条链路一直是零覆盖。
 *
 * 拆法：调用方负责**采集环境事实**与**执行副作用**（插入事件 / 唤起日历 / 设闹钟），
 * 这里只负责「拿到这些事实后应该走哪一级、落什么状态、写什么备注」。
 *
 * 两条语义约束（改这里之前先读）：
 *  1. L1 的「去重跳过」必须落 [ReminderRecord.STATUS_CALENDAR]（算生效）——落 FAILED 会让
 *     `ReminderStore.containsActive` 失效，同一条消息会重复创建日历事件；
 *  2. note 文案是与历史记录兼容的契约（`ReminderRecord.fromJson` 对老记录靠文案前缀推断级别），
 *     改文案会改变老记录的推断结果，见 `ReminderLegacyStatusTest`。
 */
internal object ReminderChainCore {

    /** L1（直接写系统日历）本轮该怎么处置。 */
    enum class CalendarAction {
        /** 有权限 + 有可写日历 + 系统里没有同标题同起始时间的重复事件 → 真去插。 */
        INSERT,
        /** 有权限 + 有可写日历，但系统日历里已有同标题同起始时间的事件 → 跳过且算生效。 */
        SKIP_DUPLICATE,
        /** 无权限或无可写日历账户 → 降级 L2。 */
        UNAVAILABLE
    }

    /**
     * 环境事实。全部由调用方采集；缺省值一律取「不可用 / 不在前台」，
     * 保证默认构造出来的 [Env] 走最保守的那条路径（直奔 L3 兜底）。
     */
    data class Env(
        /** 是否已持有日历读写权限（READ_CALENDAR + WRITE_CALENDAR）。 */
        val hasCalendarPermission: Boolean = false,
        /** 是否存在至少一个 `CAL_ACCESS_CONTRIBUTOR` 及以上的日历账户。 */
        val hasWritableCalendar: Boolean = false,
        /** 系统日历里是否已存在同标题同起始时间的事件。 */
        val eventAlreadyExists: Boolean = false,
        /** App 是否有可见界面（后台里 startActivity 会被系统静默拦截）。 */
        val inForeground: Boolean = false,
        /** 是否有能处理 ACTION_INSERT 的日历 App。 */
        val canOpenCalendar: Boolean = false
    )

    /** 链路终态：[status] 直接落 `ReminderRecord.status`，[note] 直接落 note。 */
    data class Outcome(
        val status: String,
        val note: String,
        /** 仅 L1 真实插入成功时有值；其余级别为 null。 */
        val eventId: Long? = null,
        /**
         * 需要调用方删掉的「孤儿日历事件」id。
         *
         * 事件行建了但提醒行没建成、连 App 内闹钟也没设上时，这条日历事件到点不会响，
         * 留着还会骗下一轮去重（同名同起始时间 → 直接跳过）→ 提醒被永久钉死。
         */
        val dropEventId: Long? = null
    )

    /**
     * L1 写入结果：日历事件行 + 提醒行（提前量 + METHOD_ALERT）两段分开报。
     *
     * 为什么要分开：提醒行插不进去时，事件行往往已经建出来了。这种日历事件**到点不会弹提醒**，
     * 却在旧实现里被当成 L1 成功（Reminders 插入返回的 Uri 被丢弃、抛异常时又把事件 id 一起吞掉），
     * 用户永远收不到提醒，日历里还留着一条没有提醒的孤儿事件。
     */
    data class CalendarWrite(
        /** 事件行 id；null = 事件没建成（未尝试或插入失败）。 */
        val eventId: Long? = null,
        /** 提醒行是否写入成功。 */
        val alertInserted: Boolean = false
    )

    // ── note 文案（与历史记录兼容的契约，改动会让老记录的级别推断变化）────────────
    const val NOTE_DUPLICATE = "跳过：系统日历已存在相同事件（去重）"
    const val NOTE_CALENDAR_OK = "成功：已写入系统日历"
    const val NOTE_INTENT = "已唤起日历，待手动保存"
    const val NOTE_ALARM = "已设App内提醒"

    /**
     * L1 事件建了但提醒行没建成 → 降级到 L3 闹钟。
     *
     * 文案里必须带「App内提醒」（不带空格，与 `NOTE_ALARM` 同形）：
     * `ReminderRecord.fromJson` 对无 status 的老记录按文案推断级别，
     * 带上它才能被推成 alarm，而不是被「未知文案按生效处理」推成 calendar。
     */
    const val NOTE_CALENDAR_NO_ALERT = "降级：日历事件已建但提醒行缺失，已改用App内提醒"
    const val NOTE_FAILED = "失败：无日历账户、无日历App、闹钟设置异常"

    /** L1 该不该真去碰 ContentResolver（只有 INSERT 才该）。 */
    fun calendarAction(env: Env): CalendarAction {
        if (!env.hasCalendarPermission || !env.hasWritableCalendar) return CalendarAction.UNAVAILABLE
        return if (env.eventAlreadyExists) CalendarAction.SKIP_DUPLICATE else CalendarAction.INSERT
    }

    /** L2 该不该尝试唤起日历 App（必须有可见界面，否则后台 startActivity 会被静默拦截）。 */
    fun shouldTryIntent(env: Env): Boolean = env.inForeground && env.canOpenCalendar

    /** L1 去重跳过：算生效（STATUS_CALENDAR），但没有 eventId —— 不是本条消息创建的。 */
    fun duplicateSkip(): Outcome =
        Outcome(ReminderRecord.STATUS_CALENDAR, NOTE_DUPLICATE, eventId = null)

    /** L1 真实插入成功。 */
    fun calendarSuccess(eventId: Long): Outcome =
        Outcome(ReminderRecord.STATUS_CALENDAR, NOTE_CALENDAR_OK, eventId = eventId)

    /** L2 已唤起日历，等用户手动保存（不算本次创建了提醒）。 */
    fun intentOpened(): Outcome = Outcome(ReminderRecord.STATUS_INTENT, NOTE_INTENT)

    /** L3 已设 App 内闹钟（算本次创建了提醒）。 */
    fun alarmScheduled(): Outcome = Outcome(ReminderRecord.STATUS_ALARM, NOTE_ALARM)

    /**
     * L1 事件建了但提醒行没建成：不算 L1 成功，降级到 L3；
     * eventId 保留下来，删除待办时才能把这条没有提醒的日历事件一并清掉。
     */
    fun calendarWithoutAlert(eventId: Long): Outcome =
        Outcome(ReminderRecord.STATUS_ALARM, NOTE_CALENDAR_NO_ALERT, eventId = eventId)

    /**
     * 三级全不可用。
     *
     * [dropEventId] 非空 =「刚建的那条日历事件没有提醒行」→ 调用方应把它删掉，
     * 别留残骸骗下一轮去重。
     */
    fun failed(dropEventId: Long? = null): Outcome =
        Outcome(ReminderRecord.STATUS_FAILED, NOTE_FAILED, eventId = null, dropEventId = dropEventId)

    /**
     * 整条链的终态：按 L1 → L2 → L3 顺序取第一个成立的级别。
     *
     * @param env             采集到的环境事实
     * @param insertedEventId L1 真实插入返回的事件 id（未尝试或插入失败传 null）
     * @param intentShown     L2 是否真的成功唤起了日历 App
     * @param alarmSet        L3 是否真的设上了闹钟
     * @param alertInserted   L1 的提醒行是否写入成功（false = 事件建了但到点不会弹提醒）
     */
    fun decide(
        env: Env,
        insertedEventId: Long? = null,
        intentShown: Boolean = false,
        alarmSet: Boolean = false,
        alertInserted: Boolean = true
    ): Outcome {
        when (calendarAction(env)) {
            CalendarAction.SKIP_DUPLICATE -> return duplicateSkip()
            CalendarAction.INSERT ->
                if (insertedEventId != null && alertInserted) return calendarSuccess(insertedEventId)
            CalendarAction.UNAVAILABLE -> Unit
        }
        // 事件建了、提醒行没建成：这条日历事件到点不会弹提醒，不能算 L1 成功；
        // 也不再唤起日历 App（用户会再存一条同名的重复事件），直接落到 L3 闹钟兜底。
        val eventWithoutAlert = insertedEventId != null && !alertInserted
        if (!eventWithoutAlert && shouldTryIntent(env) && intentShown) return intentOpened()
        if (alarmSet) {
            return if (eventWithoutAlert) {
                calendarWithoutAlert(requireNotNull(insertedEventId))
            } else {
                alarmScheduled()
            }
        }
        // 连闹钟都没设上：这条没有提醒行的日历事件留着只会骗下一轮去重 → 交回调用方删掉。
        return failed(dropEventId = if (eventWithoutAlert) insertedEventId else null)
    }

    /** 这次结果是否算「本次真正创建了提醒」（L1 真插入 / L3 闹钟）；去重跳过与 L2 均不算。 */
    fun countsAsCreated(outcome: Outcome): Boolean = countsAsCreated(outcome.status, outcome.eventId)

    /** [countsAsCreated] 的两参形式：调用方手上只有 status 与 eventId 时用（口径只有这一份）。 */
    fun countsAsCreated(status: String, eventId: Long?): Boolean =
        (status == ReminderRecord.STATUS_CALENDAR && eventId != null) ||
            status == ReminderRecord.STATUS_ALARM
}
