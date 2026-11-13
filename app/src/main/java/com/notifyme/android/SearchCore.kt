package com.notifyme.android

/**
 * 首页搜索的匹配口径（纯函数，无 Android 依赖，全部可进纯 JVM 单测）。
 *
 * 为什么要有这个 Core：`MainActivity.buildFeed` 原来只有一处 `groups.filter { ... }`，
 * 只拿「会话名 / 发送者 / 正文」三处做 `contains`，于是 **分析结论搜不到** ——
 * 用户明明在待办里看到过「提交季度方案」，搜「方案」却整组消失（会话被整体过滤掉）。
 * PWA 控制台的全局搜索早已覆盖 `todo_text` / `s2` / 建议正文（`server/console_ui.py` 的
 * `analysisSearchText`），端上却漏了，这里补齐并统一成一份口径。
 *
 * 三条契约（改之前先看 `SearchCoreTest`）：
 * ① **命中集合 = 会话名 ∪ 来源 App 名 ∪ 消息（发送者/正文）∪ 该会话的分析结论**；
 *   分析结论的可搜字段由 [caseParts] 统一列出，别在 Activity 里再写一套字段清单；
 * ② **搜索态下折叠必须强制展开**（会话级 + 日期级），否则「搜得到看不见」；
 *   展开判定统一走 [forcedExpand]，别在两处各写一遍 `query.isNotEmpty() && hit`；
 * ③ 会话头要能说明「为什么这条会话在结果里」——[hitKind] 只回码值，文案与本地化留在 Activity。
 */
internal object SearchCore {

    /** 命中来源：会话名 / 来源 App / 消息本身 / 分析结论。仅用于会话头提示，不参与过滤。 */
    enum class HitKind { CONVERSATION, APP, MESSAGE, ANALYSIS }

    /**
     * 归一化搜索词：去首尾空白 + 转小写。空串代表「未搜索」。
     *
     * 逐处 `trim().lowercase()` 是幂等的，重复调用不会出错；
     * 但**只在入口归一化一次**（`MainActivity.refreshMessages`），下游一律当作已归一化处理。
     */
    fun normalize(raw: String): String = raw.trim().lowercase()

    /** 单条消息是否命中：发送者或正文包含搜索词。 */
    fun messageHit(msg: ChatMessage, query: String): Boolean =
        query.isNotEmpty() &&
            (msg.sender.lowercase().contains(query) || msg.text.lowercase().contains(query))

    /**
     * 单条分析记录的可搜字段清单。
     *
     * [labelOf] 把码值翻成展示文案（`work_task` → 「工作任务」、`this_week` → 「本周内」），
     * 这样用户搜中文标签也能命中；单测传恒等函数即可，不必依赖资源表。
     * 覆盖：待办正文 / 截止时间 / S2 摘要 / 建议动作 / S2 任务三要素 / S1 主题与时间窗。
     * 刻意**不**收录 `caseId`、`rawJson` 与脱敏占位符——那些是内部排障用的，搜到了也没有内容可看。
     */
    fun caseParts(rec: AnalysisCaseRecord, labelOf: (String) -> String = { it }): List<String> {
        val parts = ArrayList<String>(12)
        parts += rec.todoText
        parts += rec.todoDue
        parts += rec.s2Summary
        parts += rec.s2DueTime
        parts += rec.s2SuggestedAction
        parts += rec.s1Topic
        parts += labelOf(rec.s1Topic)
        parts += rec.s1DueWindow
        parts += labelOf(rec.s1DueWindow)
        // displayTodo/displayDue 是三级回退后的展示值（todoText→s2Summary→s1Topic），
        // 单独再算一遍是为了覆盖回退值本身也可被搜到的情形（例如只有 s1Topic=work_task 的老记录）
        parts += rec.displayTodo()
        parts += rec.displayDue()
        for (task in rec.s2Tasks) {
            parts += task.who
            parts += task.what
            parts += task.`when`
        }
        // 「none」是 due_window 的码值，不是可读内容；留着会让用户搜 "none" 命中全部记录
        return parts.filter { it.isNotBlank() && it != "none" }
    }

    /** 单条分析记录是否命中。 */
    fun caseHit(rec: AnalysisCaseRecord, query: String, labelOf: (String) -> String = { it }): Boolean {
        if (query.isEmpty()) return false
        return caseParts(rec, labelOf).any { it.lowercase().contains(query) }
    }

    /**
     * 会话级命中：四类来源任一命中即保留整组。
     *
     * [label] 是会话头展示的来源名（`ConvKey.displayLabel(appLabel)` 的结果），
     * 由调用方算好传进来——Core 不碰 `AppSourceRegistry`，单测不需要 Android 环境。
     * [cases] 传该会话**全部**历史 case（不只是最新一条），否则搜半年前那条结论又会「搜不到」。
     */
    fun conversationHit(
        conversation: String,
        label: String,
        messages: List<ChatMessage>,
        cases: List<AnalysisCaseRecord>,
        query: String,
        labelOf: (String) -> String = { it }
    ): Boolean {
        if (query.isEmpty()) return true
        if (conversation.lowercase().contains(query)) return true
        if (label.isNotEmpty() && label.lowercase().contains(query)) return true
        if (messages.any { messageHit(it, query) }) return true
        return cases.any { caseHit(it, query, labelOf) }
    }

    /**
     * 日期组命中：当天消息、会话名、来源名，或**覆盖当天**的分析结论（含历史窗口）。
     *
     * 注意 `dayCases` 要传该天被覆盖的全部 case（`casesOfDay[dayKey]`），不是只传主展示那条：
     * 同一天可能被多个窗口覆盖，命中可能落在非主展示的那个窗口上。
     */
    fun dayHit(
        conversation: String,
        label: String,
        dayMessages: List<ChatMessage>,
        dayCases: List<AnalysisCaseRecord>,
        query: String,
        labelOf: (String) -> String = { it }
    ): Boolean = conversationHit(conversation, label, dayMessages, dayCases, query, labelOf)

    /** 搜索态强制展开：有搜索词且该组命中时，折叠一律失效（会话级与日期级同口径）。 */
    fun forcedExpand(query: String, hit: Boolean): Boolean = query.isNotEmpty() && hit

    /**
     * 会话头的「命中来源」提示：优先级 分析结论 > 来源 App > 消息 > 会话名。
     * 只命中会话名时返回 [HitKind.CONVERSATION]，但 Activity 不为它渲染提示——
     * 名字就在标题上，再说一句「命中会话名」只是噪音。
     * 空搜索词也返回 null（未搜索没有「命中」可言）。
     */
    fun hitKind(
        conversation: String,
        label: String,
        messages: List<ChatMessage>,
        cases: List<AnalysisCaseRecord>,
        query: String,
        labelOf: (String) -> String = { it }
    ): HitKind? {
        if (query.isEmpty()) return null
        if (cases.any { caseHit(it, query, labelOf) }) return HitKind.ANALYSIS
        if (label.isNotEmpty() && label.lowercase().contains(query)) return HitKind.APP
        if (messages.any { messageHit(it, query) }) return HitKind.MESSAGE
        if (conversation.lowercase().contains(query)) return HitKind.CONVERSATION
        return null
    }
}
