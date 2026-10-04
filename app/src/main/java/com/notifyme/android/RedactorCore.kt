// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

/**
 * 脱敏结果：替换后的文本 + 命中次数 + 命中规则。
 *
 * 刻意保留「类型占位符」（如 `[手机号]`）而不是整段抹掉——
 * 模型仍能从占位符判断消息结构，脱敏不等于把分析质量废掉。
 */
data class RedactionResult(
    val text: String,
    /** 命中替换总次数（同一规则多次命中分别计数） */
    val hitCount: Int,
    /** 命中的规则 id（去重，按首次命中顺序） */
    val rules: List<String>
) {
    val redacted: Boolean get() = hitCount > 0
}

/**
 * 姓名/群名稳定别名（M3）：无状态、无需本地映射表。
 *
 * token = 前缀 + sha256(名字) 前 6 位十六进制——同一个名字在任意时间、
 * 任意会话里都得到同一个别名，模型仍能在窗口内追踪「同一个人」；
 * 不同名字得到不同别名。别名不含名字原文，也不保存身份映射。
 *
 * 局限（写进 PRIVACY.md）：哈希别名不是加密强度的匿名——拿到候选名单者
 * 可逐个哈希比对。真正强匿名请关闭姓名规则或用本地随机映射（未实现）。
 */
object NameAliases {
    fun person(name: String): String = "[人名${hashShort(name)}]"
    fun group(name: String): String = "[群${hashShort(name)}]"

    private fun hashShort(name: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(name.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(6)
    }

    /**
     * 为一个分析窗口构建姓名映射：会话名（群→群别名）+ 窗口内全部发送者。
     * 发送者与会话同名时不重复（群里发言人恰为群名的情况沿用会话别名）。
     */
    fun nameMapFor(conversation: String, isGroup: Boolean, senders: Collection<String>): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        if (conversation.isNotEmpty()) {
            map[conversation] = if (isGroup) group(conversation) else person(conversation)
        }
        senders.filter { it.isNotEmpty() && it != conversation }.forEach { s ->
            map.putIfAbsent(s, person(s))
        }
        return map
    }
}

/**
 * 脱敏规则清单与默认启用集合。规则 id 会原样写进分析记录（只存 id，不存原文）。
 */
object RedactorRules {
    const val PHONE = "phone"           // 手机号 / 400 电话
    const val ID_CARD = "id_card"        // 18 位身份证
    const val BANK_CARD = "bank_card"    // 13-19 位长数字（银行卡等）
    const val EMAIL = "email"
    const val URL = "url"
    const val AMOUNT = "amount"          // 带币种符号或元/万元/块后缀的金额
    const val ADDRESS = "address"        // 需含「省/市/区/县」+「路/号/楼…」两级锚点
    const val NAME = "name"              // 姓名/昵称映射表命中

    /** 默认全规则启用；用户可在设置里勾选子集。 */
    val ALL: Set<String> = setOf(
        PHONE, ID_CARD, BANK_CARD, EMAIL, URL, AMOUNT, ADDRESS, NAME
    )
}

/**
 * 端侧脱敏内核（M3）：纯 JVM 逻辑、无 Android 依赖，可直接 JUnit 单测，
 * 口径与 [ForkPrefilter] 一致——规则只减信息、不引入新内容。
 *
 * 串行执行（顺序即优先级）：身份证 → 手机号 → 银行卡 → 邮箱 → URL →
 * 金额 → 地址 → 姓名。顺序不能乱：身份证 18 位、手机号 11 位先替换后，
 * 银行卡规则才不会被短号干扰；各类规则都带非数字边界，避免数字串内部误匹配。
 *
 * @param enabledRules 启用的规则 id 子集；null = [RedactorRules.ALL]
 * @param nameMap      姓名/昵称 → 稳定别名（如 `"张三" to "[人名1]"`），
 *                     由上层从会话/发送者名单生成；为空则姓名规则不生效
 */
class RedactorCore(
    private val enabledRules: Set<String> = RedactorRules.ALL,
    private val nameMap: Map<String, String> = emptyMap()
) {

    private data class Rule(val id: String, val regex: Regex, val token: String)

    fun redact(text: String): RedactionResult {
        var current = text
        var hitCount = 0
        val hitRules = mutableListOf<String>()

        for (rule in RULES) {
            if (rule.id !in enabledRules) continue
            var ruleHits = 0
            current = rule.regex.replace(current) {
                ruleHits += 1
                rule.token
            }
            if (ruleHits > 0) {
                hitCount += ruleHits
                hitRules += rule.id
            }
        }

        // 姓名映射放最后：用字面量匹配，长名字优先（防止短名是长名子串时先被换掉）
        if (RedactorRules.NAME in enabledRules && nameMap.isNotEmpty()) {
            val names = nameMap.keys.filter { it.isNotEmpty() }
                .sortedByDescending { it.length }
            if (names.isNotEmpty()) {
                val pattern = Regex(names.joinToString("|") { Regex.escape(it) })
                var nameHits = 0
                current = pattern.replace(current) { m ->
                    nameHits += 1
                    nameMap[m.value].orEmpty()
                }
                if (nameHits > 0) {
                    hitCount += nameHits
                    hitRules += RedactorRules.NAME
                }
            }
        }

        return RedactionResult(current, hitCount, hitRules)
    }

    companion object {
        private val RULES: List<Rule> = listOf(
            Rule(
                RedactorRules.ID_CARD,
                // 18 位：17 数字 + 数字/X；两侧不能紧邻字母数字（避免长数字串内误匹配）
                Regex("""(?<![0-9A-Za-z])\d{17}[0-9Xx](?![0-9A-Za-z])"""),
                "[身份证]"
            ),
            Rule(
                RedactorRules.PHONE,
                // 大陆手机号 11 位；或 400 服务号（允许 - 或空格分段）
                Regex(
                    """(?<!\d)(?:1[3-9]\d{9}|400[-\s]?\d{3}[-\s]?\d{4})(?!\d)"""
                ),
                "[手机号]"
            ),
            Rule(
                RedactorRules.BANK_CARD,
                // 剩余 13-19 位连续数字（银行卡/账号；单号等长数字也会被保守遮盖，
                // 隐私优先，取舍写进 PRIVACY.md）
                Regex("""(?<!\d)\d{13,19}(?!\d)"""),
                "[银行卡]"
            ),
            Rule(
                RedactorRules.EMAIL,
                Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}"""),
                "[邮箱]"
            ),
            Rule(
                RedactorRules.URL,
                // http(s) 链接或 www. 开头域名；中文标点/括号/空格截断
                Regex(
                    """https?://[^\s，。、）)】]+|""" +
                        """(?<![A-Za-z0-9])www\.[A-Za-z0-9.-]+\.[A-Za-z]{2,}[^\s，。、）)】]*"""
                ),
                "[链接]"
            ),
            Rule(
                RedactorRules.AMOUNT,
                // 币种符号开头；或数字 + 元/万元/块/块钱 结尾（千分位逗号允许）
                Regex(
                    """[¥￥]\s?\d+(?:[,，]\d{3})*(?:\.\d+)?(?:\s?万元)?""" +
                        """|\$\s?\d+(?:,\d{3})*(?:\.\d+)?""" +
                        """|\d+(?:\.\d+)?\s?(?:万元|元|块钱|块)(?![A-Za-z0-9])"""
                ),
                "[金额]"
            ),
            Rule(
                RedactorRules.ADDRESS,
                // 两级锚点：行政锚（省/市/区/县）+ 细节锚（路/街/大道/巷/号/楼/栋/单元/室），
                // 中间字符限长防止吃掉整段；两级锚都有才替换，刻意保守
                Regex(
                    """[\u4e00-\u9fa5A-Za-z0-9]{1,12}?(?:省|市|区|县)""" +
                        """[\u4e00-\u9fa5A-Za-z0-9]{0,12}?""" +
                        """(?:路|街道|大道|巷|号|楼|栋|单元|室)""" +
                        // 门牌后缀：数字+号/楼/栋/单元/室 可重复（如 100号3单元501室）；
                        // 只能由数字和锚词组成，不会吞掉后续正常汉字
                        """(?:[0-9]{0,4}(?:号|楼|栋|层|室|单元|铺)[0-9]{0,4}){0,3}"""
                ),
                "[地址]"
            )
        )
    }
}

/**
 * 窗口级脱敏产物（M3.4）：出设备 case 副本 + 证据。
 * 证据只含规则 id 与命中数，绝不包含原文片段。
 * caseId/pkg/windowEnd 保持不变——身份与去重仍是本地口径，
 * 云端只看到占位符，本地提醒/记录仍挂在同一个 case 上。
 */
data class RedactedCase(
    val case: AnalysisCase,
    private val core: RedactorCore,
    val hitCount: Int,
    val rules: List<String>
) {
    /** 出设备的旁路文本（会话级自定义提示词等）走同一把脱敏器，口径一致。 */
    fun redactExtra(text: String): String =
        if (text.isEmpty()) text else core.redact(text).text
}

/**
 * 构造 case 的脱敏副本（M3.4）：窗口级收集姓名（会话名 + 全部发送者），
 * 用同一把 [RedactorCore] 逐条替换消息文本与发送者名——窗口内同一个人
 * 始终得到同一个别名，模型仍能追踪对话关系。
 *
 * 注意：消息文本里出现、但既不是会话名也不在窗口发送者集合里的名字
 * 不会被替换（无法无中生有地识别人名），该局限写进 PRIVACY.md。
 */
fun redactCase(
    kase: AnalysisCase,
    enabledRules: Set<String> = RedactorRules.ALL
): RedactedCase {
    val senders = LinkedHashSet<String>()
    kase.messages.forEach { if (it.sender.isNotEmpty()) senders.add(it.sender) }
    val nameMap = NameAliases.nameMapFor(kase.conversation, kase.isGroup, senders)
    val core = RedactorCore(enabledRules, nameMap)

    var hits = 0
    val hitRules = LinkedHashSet<String>()
    val aliasConversation = nameMap[kase.conversation] ?: kase.conversation
    val newMessages = kase.messages.map { msg ->
        val r = core.redact(msg.text)
        hits += r.hitCount
        hitRules += r.rules
        msg.copy(
            text = r.text,
            sender = nameMap[msg.sender] ?: msg.sender,
            conversation = aliasConversation
        )
    }
    return RedactedCase(
        case = kase.copy(conversation = aliasConversation, messages = newMessages),
        core = core,
        hitCount = hits,
        rules = hitRules.toList()
    )
}

/**
 * 批量消息脱敏（M3.5 上报通道）：输入跨会话的消息集合（PendingQueue），
 * 按 [ChatMessage.convKey] 分组、各自建立窗口级姓名映射，返回与输入
 * **一一对应、顺序不变**的脱敏副本（id 等其余字段原样保留）。
 */
fun redactMessages(
    messages: List<ChatMessage>,
    enabledRules: Set<String> = RedactorRules.ALL
): List<ChatMessage> {
    class Ctx(val core: RedactorCore, val nameMap: Map<String, String>)

    val byConv = HashMap<ConvKey, Ctx>()
    messages.groupBy { it.convKey }.forEach { (_, group) ->
        val nameMap = NameAliases.nameMapFor(
            group.first().conversation,
            group.any { it.isGroup },
            group.mapTo(LinkedHashSet()) { it.sender }
        )
        byConv[group.first().convKey] =
            Ctx(RedactorCore(enabledRules, nameMap), nameMap)
    }
    return messages.map { msg ->
        val ctx = byConv.getValue(msg.convKey)
        msg.copy(
            text = ctx.core.redact(msg.text).text,
            sender = ctx.nameMap[msg.sender] ?: msg.sender,
            conversation = ctx.nameMap[msg.conversation] ?: msg.conversation
        )
    }
}
