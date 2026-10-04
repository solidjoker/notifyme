// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * 分析 case：分析单位从「单条消息」升级为「会话窗口」。
 *
 * 对齐 JEV 体系（见 jev_case_research.md §1/§7）：
 *  - 一条 case = 一个重点关注会话的最近 ≤10 条消息组成的窗口；
 *  - state 结构与 JEV build_state() 输出对齐：
 *    state.chat{relationship:"wechat_work", conversation, messages:[[from,text]...], latest_from}
 *    from 只用 me/other（通知监听只捕获他人消息，故恒为 other；
 *    群聊消息在 text 前带发言者名，保留发言人信息）；
 *  - caseId = sha1(conversation|windowEnd) 前 16 位：
 *    窗口内容没变（无新消息 -> 窗口末条时间戳不变）就不重分析。
 *
 * S1 判定题集（对齐 JEV 校准题风格：instructions/criteria 一律英文、
 * 聊天内容保留中文、noul/choice/score 题型互斥）：
 *  - need_action(noul)：会话近期内容是否含需要执行的任务/请求；
 *  - importance(score 0-9)：紧急重要程度，每档写具体情景；
 *  - due_window(choice)：none/today/tomorrow/this_week/later；
 *  - topic(choice)：work_task/schedule/notice/smalltalk/risk。
 *
 * S1→S2 自动升级（JEV 体系没有、本 App 新增的差异化能力）：
 * S1 跑完满足任一条件自动追加 S2 深分析（openai 槽位，GLM）：
 *  - need_action 概率 ≥ 0.5；或 importance ≥ 6；或 S1 整体置信度 < 0.5。
 * S1 判定结论注入 S2 prompt（对齐 we_jev 的 S1→S2 注入模式）。
 */
data class AnalysisCase(
    val caseId: String,
    val conversation: String,
    val isGroup: Boolean,
    /** 窗口末条消息时间戳（毫秒）：窗口指纹的一部分 */
    val windowEnd: Long,
    /** 窗口内消息（时间正序，≤10 条） */
    val messages: List<ChatMessage>
) {

    /**
     * 构造对齐 JEV build_state 的 state JSON：
     * {"chat":{"relationship":"wechat_work",
     *          "messages":[{"from":"other","text":"..."}...],"latest_from":"other"},
     *  "background":"会话级自定义提示词（可省）"}
     *
     * background 对齐 jev_case_research.md §2：JEV v1.3 起 state 支持 background
     * 知识上下文字段；旧版服务端收到未知字段可能 4xx，调用方需做降级重试。
     *
     * 注意：chat 内不放 conversation 字段——线上 api.typesafe.ai 对未知字段
     * 严格校验（400 parsing error），jarvis 官方 build_state 也没有该字段。
     */
    fun buildStateJson(background: String = ""): JSONObject {
        val arr = JSONArray()
        messages.forEach { msg ->
            // 群聊在 text 前带发言者名（from 只有 me/other 两个取值，发言人信息只能进 text）
            val text = if (isGroup && msg.sender.isNotEmpty() && msg.sender != conversation) {
                "${msg.sender}: ${msg.text}"
            } else msg.text
            arr.put(JSONObject().apply {
                put("from", "other")
                put("text", text)
            })
        }
        return JSONObject().apply {
            put("chat", JSONObject().apply {
                put("relationship", "wechat_work")
                put("messages", arr)
                put("latest_from", "other")
            })
            if (background.isNotBlank()) put("background", background)
        }
    }

    /** 人类可读的窗口文本（openai 协议 S1/S2 的 user 消息用） */
    fun windowText(): String = messages.joinToString("\n") { msg ->
        val text = if (isGroup && msg.sender.isNotEmpty() && msg.sender != conversation) {
            "${msg.sender}: ${msg.text}"
        } else msg.text
        "对方: $text"
    }

    companion object {
        /** 会话窗口大小：对齐 JEV build_state 取最近 10 条的惯例 */
        const val WINDOW_SIZE = 10

        /** caseId：sha1(conversation|windowEnd) 前 16 位十六进制。 */
        fun caseIdOf(conversation: String, windowEnd: Long): String {
            val digest = MessageDigest.getInstance("SHA-1")
                .digest("$conversation|$windowEnd".toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }.take(16)
        }

        /**
         * 从一个会话的消息列表构建 case。
         * @param messages 该会话全部已知消息（任意顺序），内部取最近 [WINDOW_SIZE] 条
         * @return 消息为空时返回 null
         */
        fun fromMessages(conversation: String, messages: List<ChatMessage>): AnalysisCase? {
            if (messages.isEmpty()) return null
            val window = messages.sortedBy { it.timestamp }.takeLast(WINDOW_SIZE)
            val windowEnd = window.last().timestamp
            return AnalysisCase(
                caseId = caseIdOf(conversation, windowEnd),
                conversation = conversation,
                isGroup = window.last().isGroup,
                windowEnd = windowEnd,
                messages = window
            )
        }

        // ---------------- S1 判定题集 ----------------

        /** need_action 题（noul）：是否含需要我执行的任务/请求 */
        private const val Q_NEED_ACTION = "need_action"

        /** importance 题（score 0-9）：紧急重要程度 */
        private const val Q_IMPORTANCE = "importance"

        /** due_window 题（choice）：最近截止桶 */
        private const val Q_DUE_WINDOW = "due_window"

        /** topic 题（choice）：会话主题 */
        private const val Q_TOPIC = "topic"

        /**
         * S1 题集（systemone 协议用）：一次请求批量发出（JEV 官方推荐的 fan-out）。
         * 硬规则（对齐 JEV TASK.md 口径）：instructions/criteria 一律英文，
         * 题型互斥不互相矛盾；聊天内容保留中文。
         */
        fun buildS1QuestionsJson(): JSONObject = JSONObject().apply {
            put(Q_NEED_ACTION, JSONObject().apply {
                put("type", "noul")
                put(
                    "instructions",
                    "Judge whether the recent conversation window contains a task, request, " +
                        "or deadline that the user (me) needs to act on. Judge the window as " +
                        "a whole, not any single line."
                )
                put("criteria", JSONObject().apply {
                    put(
                        "true",
                        "The window asks the user to do something, confirm something, " +
                            "deliver something, attend something, or reply with substance"
                    )
                    put(
                        "false",
                        "Pure smalltalk, FYI notices requiring no action, or tasks " +
                            "clearly assigned to others only"
                    )
                })
            })
            put(Q_IMPORTANCE, JSONObject().apply {
                put("type", "score")
                put(
                    "instructions",
                    "Rate how urgent and important this conversation window is for the " +
                        "user on a 0-9 scale. Use the per-level scenarios in criteria."
                )
                // score criteria 是有序数组（2-10 档），每档写具体情景（JEV 官方明确要求）
                put("criteria", JSONArray().apply {
                    put("0: pure smalltalk or jokes, zero action value")
                    put("1: casual chat with mild social value, nothing to do")
                    put("2: FYI notice, awareness only, no response expected")
                    put("3: minor request with no deadline, can be done anytime")
                    put("4: routine work item, due this week, low pressure")
                    put("5: clear task with a soft deadline, should schedule it")
                    put("6: task due today or tomorrow, direct ask to the user")
                    put("7: urgent request from a superior or key stakeholder, deadline near")
                    put("8: hard deadline or escalation risk, immediate action required")
                    put("9: crisis level: blocking incident, ultimatum, or severe complaint")
                })
            })
            put(Q_DUE_WINDOW, JSONObject().apply {
                put("type", "choice")
                put(
                    "instructions",
                    "Pick the bucket that best matches the nearest deadline mentioned in " +
                        "the conversation window."
                )
                put("criteria", JSONObject().apply {
                    put("none", "No deadline is mentioned anywhere in the window")
                    put("today", "The nearest deadline is today")
                    put("tomorrow", "The nearest deadline is tomorrow")
                    put("this_week", "The nearest deadline is later this week")
                    put("later", "The deadline is beyond this week or only vaguely future")
                })
            })
            put(Q_TOPIC, JSONObject().apply {
                put("type", "choice")
                put(
                    "instructions",
                    "Pick the dominant topic of this conversation window."
                )
                put("criteria", JSONObject().apply {
                    put("work_task", "Concrete work assignment, deliverable, review or report")
                    put("schedule", "Meeting, appointment, visit or other scheduling")
                    put("notice", "Announcement or FYI information, no response needed")
                    put("smalltalk", "Casual chat, greetings, jokes, pleasantries")
                    put("risk", "Complaint, conflict, warning, blame or other risk signal")
                })
            })
        }

        /**
         * S1 判定（openai 协议槽位当判定题用时的 system prompt）：
         * 对话模型按同一套题集只输出 JSON 判定，字段与 systemone 答案一一对应。
         */
        const val S1_OPENAI_SYSTEM_PROMPT =
            "You are a strict conversation judgment engine for WeChat Work messages. " +
                "The user gives you one conversation window (recent lines, Chinese). " +
                "Judge the window as a whole and output ONLY a JSON object, no other text:\n" +
                "{\"need_action_prob\": 0.0-1.0 (probability that the window contains a task, " +
                "request or deadline the user must act on), " +
                "\"importance\": 0-9 (0=pure smalltalk, 3=minor request no deadline, " +
                "5=clear task soft deadline, 6=task due today/tomorrow, " +
                "7=urgent ask from superior, 8=hard deadline/escalation risk, 9=crisis), " +
                "\"due_window\": \"none|today|tomorrow|this_week|later\", " +
                "\"topic\": \"work_task|schedule|notice|smalltalk|risk\", " +
                "\"confidence\": 0.0-1.0 (your overall confidence in this judgment)}"

        /** S1（openai 槽位）system prompt：有自定义提示词时追加「背景信息」段。 */
        fun buildS1OpenAiSystemPrompt(background: String = ""): String =
            if (background.isBlank()) S1_OPENAI_SYSTEM_PROMPT
            else S1_OPENAI_SYSTEM_PROMPT +
                "\n背景信息（判定该会话时必须纳入考量）：$background"

        /** S1 判定结果（两种协议归一化后的统一形态） */
        data class S1Result(
            val needActionProb: Double,   // 0.0-1.0
            val importance: Double,       // 0-9（score 题原始档位；概率加权时可带小数）
            val dueWindow: String,        // none/today/tomorrow/this_week/later（或 laya 中文桶名）
            val topic: String,            // work_task/schedule/notice/smalltalk/risk（或原文）
            val confidence: Double        // S1 整体置信度（各题 confidence 取最小）
        ) {
            val needAction: Boolean get() = needActionProb >= 0.5

            /** S1→S2 自动升级判定：满足任一条件即升级 */
            fun shouldEscalate(): Boolean =
                needActionProb >= ESCALATE_NEED_ACTION_PROB ||
                    importance >= ESCALATE_IMPORTANCE ||
                    confidence < ESCALATE_MIN_CONFIDENCE

            /** 注入 S2 prompt 的判定结论文本（对齐 we_jev S1→S2 注入模式） */
            fun toInjectText(): String =
                "need_action_prob=%.2f, importance=%.1f, due_window=%s, topic=%s, confidence=%.2f"
                    .format(needActionProb, importance, dueWindow, topic, confidence)
        }

        /** 升级阈值：need_action 概率 ≥ 0.5 */
        const val ESCALATE_NEED_ACTION_PROB = 0.5

        /** 升级阈值：importance ≥ 6 */
        const val ESCALATE_IMPORTANCE = 6.0

        /** 升级阈值：S1 整体置信度 < 0.5（判断存疑，追加深分析） */
        const val ESCALATE_MIN_CONFIDENCE = 0.5

        /**
         * S2 深分析 system prompt（openai 槽位，GLM）：
         * 输入同一会话窗口 + S1 判定结论，输出严格 JSON。
         * due_time 文案要求落在 CalendarHelper.parseDueTime 可解析的形式。
         * 有自定义提示词时以「背景信息」段注入（对齐 JEV background 知识上下文）。
         */
        fun buildS2SystemPrompt(s1: S1Result, background: String = ""): String {
            val bgSection = if (background.isBlank()) "" else
                "背景信息（分析该会话时必须纳入考量）：$background\n"
            return "你是企业微信会话深分析助手。S1 快速判定的结论：${s1.toInjectText()}。\n" +
                bgSection +
                "请基于同一会话窗口做深度分析，只输出一个 JSON 对象，不要输出任何其他文字：\n" +
                "{\"summary\": \"≤20字的会话摘要\", " +
                "\"due_time\": \"最晚完成时间的简短中文描述（如 今天下午3点、明天上午9点、10月5日14:00），没有明确时间则为空字符串\", " +
                "\"suggested_action\": \"给用户的建议动作，≤20字\", " +
                "\"tasks\": [{\"who\": \"责任人\", \"what\": \"要做的事\", \"when\": \"时间要求，无则空字符串\"}]}"
        }
    }
}
