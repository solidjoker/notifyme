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
