// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AdvisorDigestCore] 单测：顾问通道的窗口级姓名别名映射 + 摘要文本口径。
 *
 * 钉死的是两条契约：
 *  1. 别名映射的输入必须**从消息窗口反推**（分析记录里没有发送者名单 / isGroup），
 *     且与分析通道 [RedactorCore.redactCase] 同口径（会话名 + 窗口发送者、群聊走群别名）；
 *  2. 摘要文本逐字稳定 —— 首行、每行字段顺序、截断长度、超出省略行都不能漂，
 *     否则顾问模型的输入分布会变，历史报告无法对照。
 *
 * 固定盐向量来自独立实现（Python hashlib：sha256(salt + b"\x00" + name)[:6]），
 * saltA = 0x00..0x1f：张三=baf6b4 / 李四=3021d8 / 王五=35ccbf / 产品群=51dba0。
 */
class AdvisorDigestCoreTest {

    /** 固定盐 A：0x00..0x1f（32 字节），与 [AliasSaltCoreTest] 同一把。 */
    private val saltA = ByteArray(32) { it.toByte() }

    private val pkg = AppSourceRegistry.PKG_WECHAT

    // ---------------- 构造工具 ----------------

    private fun msg(
        sender: String,
        text: String = "正文",
        conversation: String = "会话",
        isGroup: Boolean = false,
        pkg: String = this.pkg,
        timestamp: Long = 1L
    ): ChatMessage = ChatMessage(
        sender = sender,
        text = text,
        timestamp = timestamp,
        conversation = conversation,
        isGroup = isGroup,
        pkg = pkg
    )

    private fun rec(
        caseId: String = "c1",
        conversation: String = "会话",
        analyzedAt: Long = 1_000L,
        pkg: String = this.pkg,
        filtered: Boolean = false,
        escalated: Boolean = false,
        prob: Double = 0.2,
        importance: Double = 7.0,
        topic: String = "话题",
        summary: String = "",
        tasks: List<AnalysisCaseRecord.Task> = emptyList()
    ): AnalysisCaseRecord = AnalysisCaseRecord(
        caseId = caseId,
        conversation = conversation,
        windowEnd = analyzedAt,
        messageCount = 3,
        analyzedAt = analyzedAt,
        protocol = "laya",
        rawJson = "{}",
        pkg = pkg,
        s1NeedAction = !filtered,
        s1NeedActionProb = prob,
        s1Importance = importance,
        s1DueWindow = "",
        s1Topic = topic,
        s1Confidence = 0.8,
        escalated = escalated,
        s2Summary = summary,
        s2DueTime = "",
        s2SuggestedAction = "",
        s2Tasks = tasks,
        filtered = filtered
    )

    private val label: (Long) -> String = { "T" + it }

    // ---------------- keyOf ----------------

    @Test
    fun `keyOf 与分析记录的 convKey 逐字一致`() {
        assertEquals(rec(conversation = "张三").convKey.id, AdvisorDigestCore.keyOf(pkg, "张三"))
    }

    @Test
    fun `keyOf 吸收消息日志里的首尾空白`() {
        assertEquals(
            AdvisorDigestCore.keyOf(pkg, "张三"),
            AdvisorDigestCore.keyOf(pkg, "  张三\t")
        )
    }

    @Test
    fun `keyOf 纯空白会话名退回原文而不是空串`() {
        assertEquals(ConvKey(pkg, "   ").id, AdvisorDigestCore.keyOf(pkg, "   "))
    }

    @Test
    fun `keyOf 区分 App`() {
        assertFalse(
            AdvisorDigestCore.keyOf(pkg, "张三") ==
                AdvisorDigestCore.keyOf(AppSourceRegistry.PKG_FEISHU, "张三")
        )
    }

    // ---------------- windows ----------------

    @Test
    fun `windows 按消息出现顺序建表`() {
        val w = AdvisorDigestCore.windows(
            listOf(
                msg("张三", conversation = "会话甲"),
                msg("李四", conversation = "会话乙"),
                msg("王五", conversation = "会话丙")
            )
        )
        assertEquals(3, w.size)
        assertEquals(
            listOf(AdvisorDigestCore.keyOf(pkg, "会话甲"), AdvisorDigestCore.keyOf(pkg, "会话乙"), AdvisorDigestCore.keyOf(pkg, "会话丙")),
            w.keys.toList()
        )
    }

    @Test
    fun `windows 合并同一会话的发送者并去重保序`() {
        val w = AdvisorDigestCore.windows(
            listOf(
                msg("张三"),
                msg("李四"),
                msg("张三")
            )
        )
        val one = w.values.single()
        assertEquals(listOf("张三", "李四"), one.senders)
    }

    @Test
    fun `windows isGroup 取任一条标为群聊`() {
        val w = AdvisorDigestCore.windows(
            listOf(msg("张三", isGroup = false), msg("李四", isGroup = true))
        )
        assertTrue(w.values.single().isGroup)
    }

    @Test
    fun `windows 复用首次出现的会话名写法`() {
        val w = AdvisorDigestCore.windows(
            listOf(msg("张三", conversation = " 会话 "), msg("李四", conversation = "会话"))
        )
        // trim 后同一会话；先到先得，取首次出现时的 trim 结果
        assertEquals("会话", w.values.single().conversation)
    }

    @Test
    fun `windows 跳过空发送者`() {
        val w = AdvisorDigestCore.windows(listOf(msg(""), msg("张三")))
        assertEquals(listOf("张三"), w.values.single().senders)
    }

    @Test
    fun `windows 空输入给空表`() {
        assertTrue(AdvisorDigestCore.windows(emptyList()).isEmpty())
    }

    // ---------------- nameMapFor ----------------

    @Test
    fun `nameMapFor 私聊给会话名与人名都换成别名`() {
        val w = AdvisorDigestCore.windows(listOf(msg("李四", conversation = "张三")))
        val map = AdvisorDigestCore.nameMapFor(w, pkg, "张三", saltA)
        assertEquals("[人名baf6b4]", map["张三"])
        assertEquals("[人名3021d8]", map["李四"])
    }

    @Test
    fun `nameMapFor 群聊的会话名走群别名发送者走人名别名`() {
        val w = AdvisorDigestCore.windows(
            listOf(msg("张三", conversation = "产品群", isGroup = true))
        )
        val map = AdvisorDigestCore.nameMapFor(w, pkg, "产品群", saltA)
        assertEquals("[群51dba0]", map["产品群"])
        assertEquals("[人名baf6b4]", map["张三"])
    }

    @Test
    fun `nameMapFor 窗口查不到该会话返回空表`() {
        val w = AdvisorDigestCore.windows(listOf(msg("张三", conversation = "别的会话")))
        assertTrue(AdvisorDigestCore.nameMapFor(w, pkg, "张三", saltA).isEmpty())
    }

    @Test
    fun `nameMapFor 记录侧会话名带空白也映射一份`() {
        val w = AdvisorDigestCore.windows(listOf(msg("李四", conversation = "张三")))
        val map = AdvisorDigestCore.nameMapFor(w, pkg, " 张三 ", saltA)
        // 字面量替换要能命中记录侧原文写法
        assertEquals("[人名baf6b4]", map[" 张三 "])
        assertEquals("[人名baf6b4]", map["张三"])
    }

    @Test
    fun `nameMapFor 发送者与会话同名不重复插入`() {
        val w = AdvisorDigestCore.windows(
            listOf(msg("张三", conversation = "张三", isGroup = true))
        )
        val map = AdvisorDigestCore.nameMapFor(w, pkg, "张三", saltA)
        // 群里发言人恰为群名：沿用会话别名（群别名），不额外插一条人名别名
        assertEquals(1, map.size)
        // 张三 的摘要 hex 与群/人前缀无关：[群baf6b4]
        assertEquals("[群baf6b4]", map["张三"])
    }

    @Test
    fun `nameMapFor 空盐退化为无盐别名`() {
        val w = AdvisorDigestCore.windows(listOf(msg("李四", conversation = "张三")))
        val map = AdvisorDigestCore.nameMapFor(w, pkg, "张三", AliasSaltCore.LEGACY_SALT)
        assertEquals(AliasSaltCore.personAlias("张三", AliasSaltCore.LEGACY_SALT), map["张三"])
        // 历史别名可复现：张三 -> [人名1d841b]
        assertEquals("[人名1d841b]", map["张三"])
    }

    // ---------------- mergedNameMap ----------------

    @Test
    fun `mergedNameMap 合并窗口内全部会话`() {
        val w = AdvisorDigestCore.windows(
            listOf(
                msg("张三", conversation = "会话甲"),
                msg("李四", conversation = "会话乙")
            )
        )
        val map = AdvisorDigestCore.mergedNameMap(w, saltA)
        assertEquals("[人名baf6b4]", map["张三"])
        assertEquals("[人名3021d8]", map["李四"])
        assertEquals(AliasSaltCore.personAlias("会话甲", saltA), map["会话甲"])
    }

    @Test
    fun `mergedNameMap 遍历按会话键排序冲突先到先得`() {
        // 同名「张三」在两个会话里：一个当会话名（人名别名），一个当群名（群别名）
        val w = LinkedHashMap<String, AdvisorDigestCore.ConvWindow>()
        w[AdvisorDigestCore.keyOf(pkg, "会话甲")] = AdvisorDigestCore.ConvWindow(
            AdvisorDigestCore.keyOf(pkg, "会话甲"), "会话甲", false, listOf("张三")
        )
        w[AdvisorDigestCore.keyOf(pkg, "张三")] = AdvisorDigestCore.ConvWindow(
            AdvisorDigestCore.keyOf(pkg, "张三"), "张三", true, emptyList()
        )
        // 键排序后「会话甲」的键 ... 只要冲突先到先得，值就固定为其中一种且两次调用一致
        val first = AdvisorDigestCore.mergedNameMap(w, saltA)["张三"]
        val second = AdvisorDigestCore.mergedNameMap(w, saltA)["张三"]
        assertEquals(first, second)
        assertTrue(first == "[人名baf6b4]" || first == "[群baf6b4]")
    }

    @Test
    fun `mergedNameMap 空窗口给空表`() {
        assertTrue(AdvisorDigestCore.mergedNameMap(emptyMap(), saltA).isEmpty())
    }

    // ---------------- digest：文本口径 ----------------

    @Test
    fun `digest 空输入只有首行`() {
        val out = AdvisorDigestCore.digest(emptyList(), emptyMap(), saltA, timeLabel = label)
        assertEquals("最近 7 天共 0 个分析 case：\n", out)
    }

    @Test
    fun `digest 首行周期可配`() {
        val out = AdvisorDigestCore.digest(
            emptyList(), emptyMap(), saltA, periodDays = 14, timeLabel = label
        )
        assertEquals("最近 14 天共 0 个分析 case：\n", out)
    }

    @Test
    fun `digest salt 为 null 时原样输出不脱敏`() {
        val out = AdvisorDigestCore.digest(
            listOf(rec(conversation = "张三", topic = "打 13800138000", prob = 0.2, importance = 7.0)),
            emptyMap(),
            null,
            timeLabel = label
        )
        assertEquals(
            "最近 7 天共 1 个分析 case：\n" +
                "- 「张三」 T1000 need_action=0.20 importance=7.0 topic=打 13800138000\n",
            out
        )
    }

    @Test
    fun `digest 有盐时替换会话名与人名`() {
        val w = AdvisorDigestCore.windows(listOf(msg("李四", conversation = "张三")))
        val out = AdvisorDigestCore.digest(
            listOf(rec(conversation = "张三", topic = "李四说明天开会")),
            w,
            saltA,
            timeLabel = label
        )
        assertTrue(out.contains("「[人名baf6b4]」"))
        assertTrue(out.contains("topic=[人名3021d8]说明天开会"))
        assertFalse(out.contains("张三"))
    }

    @Test
    fun `digest 七类规则在 topic 上仍生效`() {
        val w = AdvisorDigestCore.windows(listOf(msg("李四", conversation = "张三")))
        val out = AdvisorDigestCore.digest(
            listOf(rec(conversation = "张三", topic = "打 13800138000 找李四")),
            w,
            saltA,
            timeLabel = label
        )
        assertTrue(out.contains("[手机号]"))
        assertTrue(out.contains("[人名3021d8]"))
    }

    @Test
    fun `digest 关掉姓名规则时只跑其余七类`() {
        val w = AdvisorDigestCore.windows(listOf(msg("李四", conversation = "张三")))
        val out = AdvisorDigestCore.digest(
            listOf(rec(conversation = "张三", topic = "打 13800138000")),
            w,
            saltA,
            enabledRules = RedactorRules.ALL - RedactorRules.NAME,
            timeLabel = label
        )
        assertTrue(out.contains("「张三」"))
        assertTrue(out.contains("[手机号]"))
    }

    @Test
    fun `digest filtered 分支输出预筛过滤`() {
        val out = AdvisorDigestCore.digest(
            listOf(rec(filtered = true, prob = 0.07, escalated = false)),
            emptyMap(), null, timeLabel = label
        )
        assertTrue(out.contains(" 预筛过滤(prob=0.07)"))
        assertFalse(out.contains("need_action="))
    }

    @Test
    fun `digest escalated 分支带摘要与任务数`() {
        val out = AdvisorDigestCore.digest(
            listOf(
                rec(
                    escalated = true,
                    summary = "周一前把合同发出去",
                    tasks = listOf(AnalysisCaseRecord.Task("张三", "发合同", "周一"))
                )
            ),
            emptyMap(), null, timeLabel = label
        )
        assertTrue(out.contains(" 已深分析 摘要=周一前把合同发出去 任务数=1"))
    }

    @Test
    fun `digest 摘要换行压成空格并截断 30 字`() {
        val long = "一".repeat(40)
        val out = AdvisorDigestCore.digest(
            listOf(rec(escalated = true, summary = "第一行\n第二行 " + long)),
            emptyMap(), null, timeLabel = label
        )
        assertFalse(out.contains("\n第"))
        assertTrue(out.contains("摘要=第一行 第二行 " + "一".repeat(AdvisorDigestCore.SUMMARY_TRUNCATE - 10)))
        // 截断后拼接部分不超过 30 字：取摘要段后逐字验证长度
        val seg = out.substringAfter("摘要=").substringBefore(" 任务数=")
        assertEquals(AdvisorDigestCore.SUMMARY_TRUNCATE, seg.length)
    }

    @Test
    fun `digest 会话名截断 20 字`() {
        val name = "群".repeat(30)
        val out = AdvisorDigestCore.digest(
            listOf(rec(conversation = name)), emptyMap(), null, timeLabel = label
        )
        val shown = out.substringAfter("「").substringBefore("」")
        assertEquals(AdvisorDigestCore.CONVERSATION_TRUNCATE, shown.length)
    }

    @Test
    fun `digest 超过上限只留最新 50 个并追加省略行`() {
        val cases = (1..55).map { rec(caseId = "c$it", conversation = "会话$it", analyzedAt = it.toLong()) }
        val out = AdvisorDigestCore.digest(cases, emptyMap(), null, timeLabel = label)
        val lines = out.trimEnd().split("\n")
        // 首行 + 50 行 + 省略行
        assertEquals(52, lines.size)
        assertEquals("最近 7 天共 55 个分析 case：", lines.first())
        assertTrue(out.contains("（其余 5 个较早 case 略）"))
        // 保留的是最新 50 个：最早的 5 个不出现
        assertFalse(out.contains("「会话1」"))
        assertTrue(out.contains("「会话6」"))
        assertTrue(out.contains("「会话55」"))
    }

    @Test
    fun `digest 刚好等于上限时不追加省略行`() {
        val cases = (1..AdvisorDigestCore.MAX_CASES_IN_PROMPT).map {
            rec(caseId = "c$it", conversation = "会话$it")
        }
        val out = AdvisorDigestCore.digest(cases, emptyMap(), null, timeLabel = label)
        assertFalse(out.contains("较早 case 略"))
        assertEquals(1 + AdvisorDigestCore.MAX_CASES_IN_PROMPT, out.trimEnd().split("\n").size)
    }

    @Test
    fun `digest 同一会话的多个 case 共享同一份映射`() {
        val w = AdvisorDigestCore.windows(listOf(msg("李四", conversation = "张三")))
        val out = AdvisorDigestCore.digest(
            listOf(
                rec(caseId = "c1", conversation = "张三", topic = "李四 甲"),
                rec(caseId = "c2", conversation = "张三", topic = "李四 乙")
            ),
            w, saltA, timeLabel = label
        )
        assertEquals(2, Regex(Regex.escape("[人名baf6b4]")).findAll(out).count())
        assertEquals(2, Regex(Regex.escape("[人名3021d8]")).findAll(out).count())
    }

    @Test
    fun `digest 时间文案由调用方注入`() {
        val out = AdvisorDigestCore.digest(
            listOf(rec(analyzedAt = 1_700_000_000_000L)), emptyMap(), null,
            timeLabel = { "自定义" }
        )
        assertTrue(out.contains("「会话」 自定义"))
    }

    @Test
    fun `digest 窗口外的会话不做替换`() {
        val w = AdvisorDigestCore.windows(listOf(msg("李四", conversation = "别的会话")))
        val out = AdvisorDigestCore.digest(
            listOf(rec(conversation = "张三", topic = "张三说明天")), w, saltA, timeLabel = label
        )
        // 窗口里没有这个会话：会话名与正文人名都保持原文（下限，写进文档）
        assertTrue(out.contains("「张三」"))
        assertTrue(out.contains("topic=张三说明天"))
    }

    @Test
    fun `digest 输出以换行结尾`() {
        val out = AdvisorDigestCore.digest(listOf(rec()), emptyMap(), null, timeLabel = label)
        assertTrue(out.endsWith("\n"))
    }

    @Test
    fun `digest 不泄漏 pkg 与 caseId`() {
        val out = AdvisorDigestCore.digest(
            listOf(rec(caseId = "秘密caseid")), emptyMap(), null, timeLabel = label
        )
        assertFalse(out.contains("秘密caseid"))
        assertFalse(out.contains(pkg))
    }
}