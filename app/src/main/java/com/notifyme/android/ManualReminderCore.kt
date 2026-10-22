package com.notifyme.android

import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * M17 会话内自建提醒的纯逻辑（不碰 Android API，可单测）。
 *
 * 与「分析自动提醒」的区别：自动提醒的时间来自模型给的中文文本（parseDueTime 有损、失败回退 +1h），
 * 手动提醒的时间由用户在弹窗里确定，必须**先解析成功再落库**，不能静默回退。
 */
object ManualReminderCore {

    /** 弹窗时间框的规范格式：CalendarHelper.tryParseDueTime 的「完整年月日」分支可直接解析。 */
    const val EVENT_TIME_PATTERN = "yyyy-MM-dd HH:mm"

    fun eventTimeFormat(): SimpleDateFormat = SimpleDateFormat(EVENT_TIME_PATTERN, Locale.getDefault())

    /** 默认填充「下一个整点」，避免用户面对空框。 */
    fun defaultTimeText(now: Long, format: SimpleDateFormat = eventTimeFormat()): String {
        val next = nextWholeHour(now)
        return format.format(Date(next))
    }

    /** 取严格大于 now 的下一个整点（毫秒级）。 */
    fun nextWholeHour(now: Long): Long {
        val hourMillis = 60L * 60_000L
        return (now / hourMillis + 1) * hourMillis
    }

    /**
     * 手动提醒去重键：同会话 + 同标题 + 同时间 = 同一条提醒。
     * 连点两次「存入日历」时 CalendarHelper 会命中 ReminderStore.containsActive 而跳过，
     * 用户改了标题或时间即视为新提醒（不去重），符合「再提醒我一遍别的事」的直觉。
     */
    fun dedupKey(pkg: String, conversation: String, title: String, eventTime: Long): String {
        val raw = "man|$pkg|$conversation|$title|$eventTime"
        val hex = MessageDigest.getInstance("SHA-1")
            .digest(raw.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return "man:" + hex.take(16)
    }

    /** 长按消息建提醒时的预填：折叠换行、去掉首尾空白、限长。 */
    fun prefillTitle(text: String, maxLen: Int = 40): String =
        text.replace("\\s+".toRegex(), " ").trim().take(maxLen)

    /** 标题长度上限（日历事件标题过长在通知栏会被截断）。 */
    const val MAX_TITLE_LEN = 60

    /** 时间是否已经过去（留 1 分钟容差，避免用户刚点「选时间」就判定无效）。 */
    fun isInPast(eventTime: Long, now: Long): Boolean = eventTime < now - 60_000L

    /** 提前量档位对应的可读文案（index 与 AnalysisConfig.REMINDER_LEAD_OPTIONS 对齐）。 */
    fun leadLabel(minutes: Long): String = when (minutes) {
        0L -> "不提前"
        15L -> "提前 15 分钟"
        60L -> "提前 1 小时"
        1440L -> "提前 1 天"
        else -> "提前 $minutes 分钟"
    }
}
