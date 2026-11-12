// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

/**
 * 顾问通道的输入摘要口径（M3 补强 · 顾问别名映射）：把「最近 N 天的分析记录」
 * 压成模型可读的紧凑摘要，并在出设备前接上**窗口级姓名别名**。
 *
 * 为什么需要单独一个内核：顾问复盘（[AdvisorWorker.buildDigest]）与顾问报告上报
 * （[SyncWorker.pushAdvisor]）两条通道以前都只走七类规则脱敏 —— 因为
 * [AnalysisCaseRecord] 里既没有 isGroup 也没有窗口发送者名单，拿不到
 * [NameAliases.nameMapFor] 需要的三元组，**姓名规则永远不命中**。这里改成
 * 「按最近消息窗口反推每个会话的发送者集合」：消息日志里同时有 pkg / conversation /
 * isGroup / sender，足以重建与 [redactCase] 完全一致的窗口级映射，
 * 不用给分析记录加 schema。
 *
 * 下限（同步写进 PRIVACY.md 与 AGENTS.md，别当零风险）：
 *  - 窗口内一条消息都没有的会话（保留策略已剪掉、或换机后新装）只能退回七类规则，
 *    正文里出现的人名不会被换成别名 —— 那是「无法无中生有地识别人名」，不是漏脱敏；
 *  - 别名是设备级稳定伪匿名，「同一个人」在摘要里可被模型追踪，这是分析需要的能力，
 *    也意味着别名可关联（见 [AliasSaltCore] 的边界说明）。
 */
internal object AdvisorDigestCore {

    /** 反推窗口级映射时取的最近消息条数（与消息通道 / 分析通道的窗口口径一致）。 */
    const val DEFAULT_MESSAGE_WINDOW = 500

    /** 输入摘要最多携带的 case 数（防爆 token）：取**最新**的这些条。 */
    const val MAX_CASES_IN_PROMPT = 50

    /** 每条 case 的 S2 摘要截断长度。 */
    const val SUMMARY_TRUNCATE = 30

    /** 会话名截断长度（模型只需能区分会话，不需要全长）。 */
    const val CONVERSATION_TRUNCATE = 20

    /** 顾问复盘窗口（天）：摘要首行文案用，真正的窗口过滤在 Worker 里做。 */
    const val DEFAULT_PERIOD_DAYS = 7

    /**
     * 一个会话在消息窗口里留下的事实：建 [NameAliases.nameMapFor] 需要的全部输入。
     * [senders] 去重保序，含「我」这类规范发送者（映射时由 nameMapFor 自行过滤空串与同名）。
     */
    data class ConvWindow(
        val convId: String,
        val conversation: String,
        val isGroup: Boolean,
        val senders: List<String>
    )

    /**
     * 会话归一化键：与分析记录的 [AnalysisCaseRecord.convKey] 同口径，
     * 但会话名先 trim —— 消息日志里同一会话可能带首尾空白（不同采集通道写法不一），
     * 记录侧不会。trim 后为空（纯空白会话名）则退回原文，绝不把会话键打成空串。
     */
    fun keyOf(pkg: String, conversation: String): String {
        val trimmed = conversation.trim()
        return ConvKey(pkg, if (trimmed.isEmpty()) conversation else trimmed).id
    }

    /**
     * 从最近消息窗口反推「会话键 → 窗口事实」。
     *
     * 顺序为消息出现顺序（先到先得）；同一会话的 isGroup 取「任一条标为群聊」，
     * 与 [ConvNormalize.group] 的推断口径一致（老行 isGroup 缺省为 false）。
     */
    fun windows(messages: List<ChatMessage>): Map<String, ConvWindow> {
        class Builder(val convId: String, var conversation: String, var isGroup: Boolean) {
            val senders = LinkedHashSet<String>()
        }

        val builders = LinkedHashMap<String, Builder>()
        messages.forEach { m ->
            val id = keyOf(m.pkg, m.conversation)
            val b = builders[id] ?: Builder(id, m.conversation.trim(), m.isGroup).also {
                builders[id] = it
            }
            if (m.isGroup) b.isGroup = true
            if (m.sender.isNotEmpty()) b.senders.add(m.sender)
        }
        return builders.mapValuesTo(LinkedHashMap()) { (id, b) ->
            ConvWindow(id, b.conversation, b.isGroup, b.senders.toList())
        }
    }

    /**
     * 单会话的姓名映射：窗口里查不到该会话时返回空表（只走七类规则）。
     * 记录侧的会话名与窗口里的写法可能差首尾空白，额外把记录侧写法也映射一份，
     * 保证字面量替换能命中原文。
     */
    fun nameMapFor(
        windows: Map<String, ConvWindow>,
        pkg: String,
        conversation: String,
        salt: ByteArray
    ): Map<String, String> {
        val w = windows[keyOf(pkg, conversation)] ?: return emptyMap()
        val aliases = NameAliases(salt)
        val map = LinkedHashMap(aliases.nameMapFor(w.conversation, w.isGroup, w.senders))
        if (conversation.isNotEmpty() && conversation != w.conversation) {
            // 同名实体必须同别名：记录侧带空白时用**窗口里的规范化写法**算摘要，
            // 否则同一会话会因首尾空白不同得到两个别名，跨 case 关联就断了。
            map.putIfAbsent(
                conversation,
                if (w.isGroup) aliases.group(w.conversation) else aliases.person(w.conversation)
            )
        }
        return map
    }

    /**
     * 窗口内**全部会话**的姓名映射合并成一张表：顾问报告是模型产出的聚合文本，
     * 不区分会话，只能拿整窗口的名单去过一遍。冲突（同一名字在不同会话里
     * 一种是群名、一种是私聊名）先到先得，遍历按会话键排序保证结果可复现。
     */
    fun mergedNameMap(windows: Map<String, ConvWindow>, salt: ByteArray): Map<String, String> {
        val aliases = NameAliases(salt)
        val out = LinkedHashMap<String, String>()
        windows.keys.sorted().forEach { id ->
            val w = windows.getValue(id)
            aliases.nameMapFor(w.conversation, w.isGroup, w.senders).forEach { (name, alias) ->
                out.putIfAbsent(name, alias)
            }
        }
        return out
    }

    /**
     * 组装顾问复盘摘要（输入防爆 token）：最新 [MAX_CASES_IN_PROMPT] 个 case，
     * 每行一条 —— 会话名、S1 判定、是否升级/被预筛、S2 摘要与任务数。
     *
     * @param salt 别名盐；**null = 本次不出设备（隐私模式非云端脱敏）**，不做任何替换。
     *             非 null 时按 [windows] 反推的窗口级映射替换会话名 / 正文里的人名，
     *             同时仍跑七类规则（手机号 / 证件 / 卡号 / 邮箱 / 链接 / 金额 / 地址）。
     * @param timeLabel 时间戳转「MM-dd HH:mm」的格式化函数，由调用方注入 ——
     *             内核不碰 Locale，单测才能钉死输出文本。
     */
    fun digest(
        cases: List<AnalysisCaseRecord>,
        windows: Map<String, ConvWindow>,
        salt: ByteArray?,
        enabledRules: Set<String> = RedactorRules.ALL,
        periodDays: Int = DEFAULT_PERIOD_DAYS,
        timeLabel: (Long) -> String
    ): String {
        val sb = StringBuilder("最近 $periodDays 天共 ${cases.size} 个分析 case：\n")
        // 同一会话可能出现多个 case：脱敏器（含姓名正则）按会话键复用，别每条重建。
        val cores = HashMap<String, RedactorCore>()
        cases.takeLast(MAX_CASES_IN_PROMPT).forEach { rec ->
            val core = if (salt == null) null else cores.getOrPut(
                keyOf(rec.pkg, rec.conversation)
            ) { RedactorCore(enabledRules, nameMapFor(windows, rec.pkg, rec.conversation, salt)) }
            val conversation = core?.redact(rec.conversation)?.text ?: rec.conversation
            val topic = core?.redact(rec.s1Topic)?.text ?: rec.s1Topic
            val summary = core?.redact(rec.s2Summary.replace("\n", " "))?.text
                ?: rec.s2Summary.replace("\n", " ")
            sb.append("- 「").append(conversation.take(CONVERSATION_TRUNCATE)).append("」")
                .append(" ").append(timeLabel(rec.analyzedAt))
            if (rec.filtered) {
                sb.append(" 预筛过滤(prob=%.2f)".format(rec.s1NeedActionProb))
            } else {
                sb.append(" need_action=%.2f".format(rec.s1NeedActionProb))
                    .append(" importance=%.1f".format(rec.s1Importance))
                    .append(" topic=").append(topic)
                if (rec.escalated) {
                    sb.append(" 已深分析 摘要=")
                        .append(summary.take(SUMMARY_TRUNCATE))
                        .append(" 任务数=").append(rec.s2Tasks.size)
                }
            }
            sb.append("\n")
        }
        if (cases.size > MAX_CASES_IN_PROMPT) {
            sb.append("（其余 ${cases.size - MAX_CASES_IN_PROMPT} 个较早 case 略）\n")
        }
        return sb.toString()
    }
}
