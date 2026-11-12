// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import android.content.Context

/**
 * topic / due_window 码值 -> 中文的单一事实源（纯函数为主，可直接 JVM 单测）。
 *
 * 抽出来的原因：改前这套映射在四个地方各写一份，且四份互不一致——
 *  - AnalysisListActivity.localizeTopic / localizeDueWindow（未知值原样、空值原样）；
 *  - ConversationActivity.localizeTopic / localizeDueWindow（未知值原样、空值回落 notice/none）；
 *  - MainActivity.searchLabel（只认英文码值，不认中文桶名，供 SearchCore 的 labelOf）；
 *  - MainActivity 的 Adapter 内 topicLabel（同 AnalysisList）。
 * 于是「模型直接输出中文桶名」时有的页面能认、有的页面把「本周」当未知值原样显示；
 * 首页消息卡还把码值与中文串比较（dueText != "none"），未编辑过的待办卡显示「· 截止：none」。
 *
 * 现在统一到这里，四条契约：
 * 1. 归一化双向兼容：英文码值（work_task / none …）与中文桶名（工作任务 / 无明确时间 / 本周）
 *    都归到同一个英文码值；大小写不敏感、去首尾空白；未知值原样返回——兼容端侧模型自由输出，
 *    不要为了「看起来整齐」把未知值吞成默认桶，那会让用户看不到模型到底给了什么。
 * 2. 资源 id 由 topicRes / dueRes 给出，Context.getString 留给调用方：单测是纯 JVM（没有真实
 *    Context），Core 只回 id；带 Context 的 label* 薄封装也放这里，只为让调用点不再重复 when。
 * 3. filtered 是第六种 topic 取值（AnalysisWorker 预筛就地结案时写 s1Topic = "filtered"），
 *    映射到「已预筛过滤」；它不是错误值，别落进「未知值原样」把它显示成英文。
 * 4. none / 空串 一律视为「没有截止」（isNoDue）：调用方据此隐藏整段，别再用
 *    text != "none" 这种码值与中文串比较的写法（永远不等，必然把 none 显示出来）。
 *
 * 与提醒链的关系：CalendarHelper.tryParseDueTime 只认中文（今天 / 明天 / 周X / HH:mm），
 * 不认 today 这种码值。所以提醒链路入参保持原样（码值落进「解析不出 -> baseTime + 1h」这条
 * 既有分支），不要把这里的本地化结果喂给 parseDueTime —— 桶名转成「今天」会被解析成今天
 * 09:00，分析发生在下午时事件直接落进过去、日历永不触发。
 */
internal object TopicLabelCore {

    // ---- topic 码值（唯一真源见 AnalysisCase 的 S1 题面；filtered 见 AnalysisWorker 预筛） ----
    const val TOPIC_WORK_TASK = "work_task"
    const val TOPIC_SCHEDULE = "schedule"
    const val TOPIC_NOTICE = "notice"
    const val TOPIC_SMALLTALK = "smalltalk"
    const val TOPIC_RISK = "risk"
    const val TOPIC_FILTERED = "filtered"

    // ---- due_window 码值 ----
    const val DUE_NONE = "none"
    const val DUE_TODAY = "today"
    const val DUE_TOMORROW = "tomorrow"
    const val DUE_THIS_WEEK = "this_week"
    const val DUE_LATER = "later"

    /** 五个 S1 主题码值（不含 filtered）。 */
    val TOPIC_CODES: List<String> =
        listOf(TOPIC_WORK_TASK, TOPIC_SCHEDULE, TOPIC_NOTICE, TOPIC_SMALLTALK, TOPIC_RISK)

    /** 四个截止桶码值（不含 none；none 是「没有截止」而不是一个桶）。 */
    val DUE_CODES: List<String> = listOf(DUE_TODAY, DUE_TOMORROW, DUE_THIS_WEEK, DUE_LATER)

    /**
     * topic 归一化：英文码值 / 中文桶名 -> 英文码值；未知值原样（去首尾空白）；空输入返回空串。
     *
     * 大小写不敏感（端侧模型偶尔输出 Work_Task），中文桶名来自模型直接抄题面中文。
     */
    fun normalizeTopic(raw: String?): String {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return ""
        return when (text.lowercase()) {
            TOPIC_WORK_TASK, "工作任务" -> TOPIC_WORK_TASK
            TOPIC_SCHEDULE, "日程安排", "日程" -> TOPIC_SCHEDULE
            TOPIC_NOTICE, "通知公告", "通知" -> TOPIC_NOTICE
            TOPIC_SMALLTALK, "闲聊" -> TOPIC_SMALLTALK
            TOPIC_RISK, "风险信号", "风险" -> TOPIC_RISK
            TOPIC_FILTERED, "已预筛过滤", "预筛过滤" -> TOPIC_FILTERED
            else -> text
        }
    }

    /** due_window 归一化：英文码值 / 中文桶名 -> 英文码值；未知值原样（去首尾空白）；空输入返回空串。 */
    fun normalizeDue(raw: String?): String {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return ""
        return when (text.lowercase()) {
            DUE_NONE, "无明确时间", "无明确", "没有明确时间" -> DUE_NONE
            DUE_TODAY, "今天", "今日" -> DUE_TODAY
            DUE_TOMORROW, "明天", "明日" -> DUE_TOMORROW
            DUE_THIS_WEEK, "本周内", "本周", "这周", "这周内" -> DUE_THIS_WEEK
            DUE_LATER, "更晚", "更晚些", "以后" -> DUE_LATER
            else -> text
        }
    }

    /** topic 码值 -> 字符串资源 id；未知值返回 null（调用方回落到原文）。 */
    fun topicRes(topic: String?): Int? = when (normalizeTopic(topic)) {
        TOPIC_WORK_TASK -> R.string.topic_work_task
        TOPIC_SCHEDULE -> R.string.topic_schedule
        TOPIC_NOTICE -> R.string.topic_notice
        TOPIC_SMALLTALK -> R.string.topic_smalltalk
        TOPIC_RISK -> R.string.topic_risk
        TOPIC_FILTERED -> R.string.topic_filtered
        else -> null
    }

    /** due_window 码值 -> 字符串资源 id；未知值返回 null。 */
    fun dueRes(due: String?): Int? = when (normalizeDue(due)) {
        DUE_NONE -> R.string.due_none
        DUE_TODAY -> R.string.due_today
        DUE_TOMORROW -> R.string.due_tomorrow
        DUE_THIS_WEEK -> R.string.due_this_week
        DUE_LATER -> R.string.due_later
        else -> null
    }

    /**
     * 「没有截止」：none / 中文「无明确时间」/ 空串都算。
     *
     * 空串算「没有」是刻意的：旧记录的 s1DueWindow 可能缺字段（读进来是空串），
     * 空串若判成「有截止」就会渲染出「截止：」这种没有下文的尾巴。
     */
    fun isNoDue(due: String?): Boolean {
        val code = normalizeDue(due)
        return code.isEmpty() || code == DUE_NONE
    }

    /** 该文本是不是「码值 / 桶名」（而不是用户手写的具体时间，如「明天15:00」）。 */
    fun isBucket(text: String?): Boolean = topicRes(text) != null || dueRes(text) != null

    /**
     * topic 展示文案：已知码值走资源，未知值原样；空输入回落「通知公告」。
     *
     * 空值回落而不是空串：话题 chip / 主题行留空会让用户以为渲染坏了。
     */
    fun topicLabel(context: Context, topic: String?): String {
        val res = topicRes(topic)
        if (res != null) return context.getString(res)
        return normalizeTopic(topic).ifEmpty { context.getString(R.string.topic_notice) }
    }

    /** due_window 展示文案：已知码值走资源（含 none），未知值原样；空输入回落「无明确时间」。 */
    fun dueLabel(context: Context, due: String?): String {
        val res = dueRes(due)
        if (res != null) return context.getString(res)
        return normalizeDue(due).ifEmpty { context.getString(R.string.due_none) }
    }

    /**
     * 不分领域的展示文案（给 SearchCore 的 labelOf 用：它拿到的可能是 topic 也可能是 due 码值）。
     *
     * 未知值原样返回——搜索匹配面必须保留模型自由输出的原文，否则用户搜不到它。
     */
    fun anyLabel(context: Context, code: String?): String {
        val topicRes = topicRes(code)
        if (topicRes != null) return context.getString(topicRes)
        val dueRes = dueRes(code)
        if (dueRes != null) return context.getString(dueRes)
        return code?.trim().orEmpty()
    }

    /**
     * 待办/消息卡上「截止」该显示什么码值（自由文本原样）：todoDue -> s2DueTime -> s1DueWindow。
     *
     * 顺序与 [AnalysisStore.displayDue] 一致，但把「桶名」与「用户写的具体时间」区分开：
     * 桶名走资源翻成中文，具体时间（明天15:00 / 2026-11-20）原样保留。
     */
    fun displayDueCode(todoDue: String?, s2DueTime: String?, s1DueWindow: String?): String {
        val edited = todoDue?.trim().orEmpty()
        if (edited.isNotEmpty()) return edited
        val concrete = s2DueTime?.trim().orEmpty()
        if (concrete.isNotEmpty()) return concrete
        return s1DueWindow?.trim().orEmpty()
    }

    /** 是否该渲染整段「截止」：none / 空串 一律不渲染（旧实现拿码值比中文串，永远不等）。 */
    fun hasDisplayDue(todoDue: String?, s2DueTime: String?, s1DueWindow: String?): Boolean =
        !isNoDue(displayDueCode(todoDue, s2DueTime, s1DueWindow))

    /** 「截止」展示文案：桶名 -> 中文，具体时间与未知自由文本原样。 */
    fun displayDueLabel(
        context: Context,
        todoDue: String?,
        s2DueTime: String?,
        s1DueWindow: String?
    ): String {
        val code = displayDueCode(todoDue, s2DueTime, s1DueWindow)
        val res = dueRes(code)
        if (res != null) return context.getString(res)
        return normalizeDue(code)
    }

    /**
     * 编辑框回填的「截止」文本：只看用户自己写的 todoDue（覆盖值），桶名翻成中文，其余原样。
     *
     * 刻意不回落 s2DueTime / s1DueWindow —— 编辑框留空 = 「沿用分析结果」，
     * 把兜底值预填进去等于把「兜底」变成「硬覆盖」，用户一保存就写死了一个他并没写过的截止。
     */
    fun editDueLabel(context: Context, todoDue: String?): String {
        val code = todoDue?.trim().orEmpty()
        if (code.isEmpty()) return ""
        return dueLabel(context, code)
    }

    /**
     * 待办文本该显示什么（自由文本原样）：todoText -> s2Summary -> s1Topic。
     *
     * 顺序与 [AnalysisStore.displayTodo] 一致；最后一级 s1Topic 是码值，展示时须过 [todoLabel]。
     */
    fun displayTodoCode(todoText: String?, s2Summary: String?, s1Topic: String?): String {
        val edited = todoText?.trim().orEmpty()
        if (edited.isNotEmpty()) return edited
        val summary = s2Summary?.trim().orEmpty()
        if (summary.isNotEmpty()) return summary
        return s1Topic?.trim().orEmpty()
    }

    /**
     * 待办展示文案：前两级是用户/模型写的自由文本（原样），只有第三级 s1Topic 是码值要走资源。
     *
     * 为什么要区分：预筛结案的记录整条没有 S2 概括，回落到 s1Topic = "filtered"，
     * 直接显示就是英文 filtered；同理未深分析的记录会显示 work_task / schedule。
     */
    fun todoLabel(context: Context, todoText: String?, s2Summary: String?, s1Topic: String?): String {
        val edited = todoText?.trim().orEmpty()
        if (edited.isNotEmpty()) return edited
        val summary = s2Summary?.trim().orEmpty()
        if (summary.isNotEmpty()) return summary
        val topic = s1Topic?.trim().orEmpty()
        if (topic.isEmpty()) return ""
        return topicLabel(context, topic)
    }
}
