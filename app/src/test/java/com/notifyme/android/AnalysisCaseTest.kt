// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AnalysisCase] 的构造与 prompt 契约测试。
 *
 * 这里钉住的都是「改了就会静默失效」的约定：
 *  - state JSON 里 chat 内**不能有 conversation 字段**（线上 api.typesafe.ai 严格校验未知字段 → 400）；
 *  - S1 题集的 instructions/criteria 一律英文、聊天内容保留中文（JEV 硬规则）；
 *  - S1→S2 升级阈值（≥0.5 / ≥6 / <0.5）；
 *  - 群聊发言人名必须进 text（from 只有 me/other 两个取值，没别的地方放）。
 */
class AnalysisCaseTest {

    private fun msg(
        text: String,
        ts: Long,
        conversation: String = "张三",
        sender: String = "张三",
        isGroup: Boolean = false
    ) = ChatMessage(
        sender = sender,
        text = text,
        timestamp = ts,
        conversation = conversation,
        isGroup = isGroup
    )

    private fun s1(
        prob: Double = 0.2,
        importance: Double = 3.0,
        dueWindow: String = "none",
        topic: String = "notice",
        confidence: Double = 0.9
    ) = AnalysisCase.Companion.S1Result(
        needActionProb = prob,
        importance = importance,
        dueWindow = dueWindow,
        topic = topic,
        confidence = confidence
    )

    // ---------------- fromMessages ----------------

    @Test
    fun `fromMessages 空列表返回 null`() {
        assertNull(AnalysisCase.fromMessages(AppSourceRegistry.PKG_WECHAT, "张三", emptyList()))
    }

    @Test
    fun `fromMessages 内部按时间正序取最近 WINDOW_SIZE 条`() {
        // 故意乱序传入：调用方（AnalysisWorker 按会话分组）不保证顺序
        val all = listOf(
            msg("m5", 5_000L),
            msg("m1", 1_000L),
            msg("m3", 3_000L),
            msg("m2", 2_000L),
            msg("m4", 4_000L)
        )

        val kase = AnalysisCase.fromMessages(AppSourceRegistry.PKG_WECHAT, "张三", all)

        assertNotNull(kase)
        assertEquals(listOf("m1", "m2", "m3", "m4", "m5"), kase!!.messages.map { it.text })
        assertEquals(5_000L, kase.windowEnd)
        assertEquals("张三", kase.conversation)
    }

    @Test
    fun `fromMessages 超过窗口大小时只留最新 10 条`() {
        val all = (1..15).map { i -> msg("m$i", i * 1_000L) }

        val kase = AnalysisCase.fromMessages(AppSourceRegistry.PKG_WECHAT, "张三", all)!!

        assertEquals(AnalysisCase.WINDOW_SIZE, kase.messages.size)
        assertEquals((6..15).map { "m$it" }, kase.messages.map { it.text })
        assertEquals(15_000L, kase.windowEnd)
    }

    @Test
    fun `fromMessages 的 isGroup 取窗口末条`() {
        // 会话从私聊升级为群聊（或反之）时，以最新一条为准
        val all = listOf(
            msg("早", 1_000L, isGroup = false),
            msg("开会了", 2_000L, conversation = "张三", sender = "李四", isGroup = true)
        )
        assertTrue(AnalysisCase.fromMessages(AppSourceRegistry.PKG_WECHAT, "张三", all)!!.isGroup)

        val reverted = listOf(
            msg("开会了", 1_000L, conversation = "张三", sender = "李四", isGroup = true),
            msg("好的", 2_000L, isGroup = false)
        )
        assertFalse(AnalysisCase.fromMessages(AppSourceRegistry.PKG_WECHAT, "张三", reverted)!!.isGroup)
    }

    @Test
    fun `WINDOW_SIZE 是 10`() {
        assertEquals(10, AnalysisCase.WINDOW_SIZE)
    }

    // ---------------- caseId ----------------

    @Test
    fun `caseIdOf 给出 16 位小写十六进制且稳定`() {
        val id = AnalysisCase.caseIdOf(AppSourceRegistry.PKG_WECHAT, "张三", 1_700_000_000_000L)
        assertEquals(16, id.length)
        assertTrue("caseId 应当是十六进制，实际 $id", Regex("[0-9a-f]{16}").matches(id))
        assertEquals(id, AnalysisCase.caseIdOf(AppSourceRegistry.PKG_WECHAT, "张三", 1_700_000_000_000L))
    }

    @Test
    fun `caseId 随会话名与窗口末条变化`() {
        val base = AnalysisCase.caseIdOf(AppSourceRegistry.PKG_WECHAT, "张三", 1_000L)
        assertFalse(base == AnalysisCase.caseIdOf(AppSourceRegistry.PKG_WECHAT, "李四", 1_000L))
        assertFalse(base == AnalysisCase.caseIdOf(AppSourceRegistry.PKG_WECHAT, "张三", 2_000L))
    }

    @Test
    fun `fromMessages 的 caseId 等于 caseIdOf(会话, 窗口末条)`() {
        val all = listOf(msg("a", 1_000L), msg("b", 9_000L))
        val kase = AnalysisCase.fromMessages(AppSourceRegistry.PKG_WECHAT, "张三", all)!!
        assertEquals(AnalysisCase.caseIdOf(AppSourceRegistry.PKG_WECHAT, "张三", 9_000L), kase.caseId)
    }

    @Test
    fun `窗口内容没变时 caseId 不变（避免重复分析）`() {
        val all = listOf(msg("a", 1_000L), msg("b", 2_000L))
        val first = AnalysisCase.fromMessages(AppSourceRegistry.PKG_WECHAT, "张三", all)!!
        // 又来了一条更旧的消息（时间戳在窗口内但不是末条）：末条不变 → 指纹不变
        val withOlder = listOf(msg("a0", 500L)) + all
        val second = AnalysisCase.fromMessages(AppSourceRegistry.PKG_WECHAT, "张三", withOlder)!!
        assertEquals(first.caseId, second.caseId)
    }

    // ---------------- buildStateJson ----------------

    @Test
    fun `buildStateJson 的 chat 内不放 conversation 字段`() {
        // 回归测试：线上 api.typesafe.ai 对未知字段严格校验，多一个键就是 400 parsing error
        val kase = AnalysisCase.fromMessages(AppSourceRegistry.PKG_WECHAT, "张三", listOf(msg("在吗", 1_000L), msg("在", 2_000L)))!!

        val chat = kase.buildStateJson().getJSONObject("chat")

        assertFalse("chat 内出现 conversation 会被服务端 400 拒绝", chat.has("conversation"))
        assertEquals("wechat_work", chat.getString("relationship"))
        assertEquals("other", chat.getString("latest_from"))
    }

    @Test
    fun `buildStateJson 按窗口顺序输出 messages 且通知消息 from 为 other`() {
        val kase = AnalysisCase.fromMessages(
            AppSourceRegistry.PKG_WECHAT,
            "张三",
            listOf(msg("第一条", 1_000L), msg("第二条", 2_000L))
        )!!

        val arr = kase.buildStateJson().getJSONObject("chat").getJSONArray("messages")

        assertEquals(2, arr.length())
        assertEquals("other", arr.getJSONObject(0).getString("from"))
        assertEquals("第一条", arr.getJSONObject(0).getString("text"))
        assertEquals("other", arr.getJSONObject(1).getString("from"))
        assertEquals("第二条", arr.getJSONObject(1).getString("text"))
    }

    @Test
    fun `buildStateJson 群聊把发言人名放进 text 前缀`() {
        // from 只有 me/other 两个取值，发言人信息只能进 text
        val kase = AnalysisCase.fromMessages(
            AppSourceRegistry.PKG_WECHAT,
            "项目群",
            listOf(
                msg("评审改到三点", 1_000L, conversation = "项目群", sender = "李四", isGroup = true),
                msg("收到", 2_000L, conversation = "项目群", sender = "王五", isGroup = true)
            )
        )!!

        val arr = kase.buildStateJson().getJSONObject("chat").getJSONArray("messages")

        assertEquals("李四: 评审改到三点", arr.getJSONObject(0).getString("text"))
        assertEquals("王五: 收到", arr.getJSONObject(1).getString("text"))
    }

    @Test
    fun `buildStateJson 自己发出的消息 from 为 me 且 latest_from 跟随末条`() {
        val kase = AnalysisCase.fromMessages(
            AppSourceRegistry.PKG_WECHAT,
            "张三",
            listOf(
                msg("好的我来处理", 1_000L, sender = ChatMessage.SENDER_SELF),
                msg("麻烦尽快", 2_000L)
            )
        )!!

        val chat = kase.buildStateJson().getJSONObject("chat")
        val arr = chat.getJSONArray("messages")
        assertEquals("me", arr.getJSONObject(0).getString("from"))
        assertEquals("好的我来处理", arr.getJSONObject(0).getString("text"))
        assertEquals("other", arr.getJSONObject(1).getString("from"))
        assertEquals("末条是对方消息", "other", chat.getString("latest_from"))
    }

    @Test
    fun `buildStateJson 末条是自己消息时 latest_from 为 me`() {
        val kase = AnalysisCase.fromMessages(
            AppSourceRegistry.PKG_WECHAT,
            "张三",
            listOf(
                msg("明天交", 1_000L),
                msg("收到", 2_000L, sender = ChatMessage.SENDER_SELF)
            )
        )!!
        assertEquals("me", kase.buildStateJson().getJSONObject("chat").getString("latest_from"))
    }

    @Test
    fun `buildStateJson 群聊自己消息 text 带名前缀且 from 为 me`() {
        val kase = AnalysisCase.fromMessages(
            AppSourceRegistry.PKG_WECHAT,
            "项目群",
            listOf(
                msg("我来发版", 1_000L, conversation = "项目群",
                    sender = ChatMessage.SENDER_SELF, isGroup = true)
            )
        )!!
        val row = kase.buildStateJson().getJSONObject("chat")
            .getJSONArray("messages").getJSONObject(0)
        assertEquals("me", row.getString("from"))
        assertEquals("我: 我来发版", row.getString("text"))
    }

    @Test
    fun `buildStateJson 私聊不加发言人前缀`() {
        val kase = AnalysisCase.fromMessages(
            AppSourceRegistry.PKG_WECHAT,
            "张三",
            listOf(msg("在吗", 1_000L, conversation = "张三", sender = "张三"))
        )!!
        val arr = kase.buildStateJson().getJSONObject("chat").getJSONArray("messages")
        assertEquals("在吗", arr.getJSONObject(0).getString("text"))
    }

    @Test
    fun `buildStateJson 群聊里发言人等于会话名时不加前缀`() {
        val kase = AnalysisCase.fromMessages(
            AppSourceRegistry.PKG_WECHAT,
            "张三",
            listOf(msg("在吗", 1_000L, conversation = "张三", sender = "张三", isGroup = true))
        )!!
        val arr = kase.buildStateJson().getJSONObject("chat").getJSONArray("messages")
        assertEquals("在吗", arr.getJSONObject(0).getString("text"))
    }

    @Test
    fun `buildStateJson 仅在背景非空时输出 background 字段`() {
        val kase = AnalysisCase.fromMessages(AppSourceRegistry.PKG_WECHAT, "张三", listOf(msg("在吗", 1_000L)))!!

        assertFalse("空背景不该出现 background 键（旧版服务端会 4xx）", kase.buildStateJson().has("background"))
        assertFalse(kase.buildStateJson("   ").has("background"))

        val withBg = kase.buildStateJson("张三是我的直属上级")
        assertEquals("张三是我的直属上级", withBg.getString("background"))
    }

    // ---------------- windowText ----------------

    @Test
    fun `windowText 每行以 对方 开头并按窗口顺序换行`() {
        val kase = AnalysisCase.fromMessages(
            AppSourceRegistry.PKG_WECHAT,
            "张三",
            listOf(msg("在吗", 1_000L), msg("有个事想确认", 2_000L))
        )!!

        assertEquals("对方: 在吗\n对方: 有个事想确认", kase.windowText())
    }

    @Test
    fun `windowText 群聊保留发言人名`() {
        val kase = AnalysisCase.fromMessages(
            AppSourceRegistry.PKG_WECHAT,
            "项目群",
            listOf(msg("评审改到三点", 1_000L, conversation = "项目群", sender = "李四", isGroup = true))
        )!!
        assertEquals("对方: 李四: 评审改到三点", kase.windowText())
    }

    @Test
    fun `windowText 自己消息以 我 开头`() {
        val kase = AnalysisCase.fromMessages(
            AppSourceRegistry.PKG_WECHAT,
            "张三",
            listOf(
                msg("明天能交吗", 1_000L),
                msg("可以", 2_000L, sender = ChatMessage.SENDER_SELF)
            )
        )!!
        assertEquals("对方: 明天能交吗\n我: 可以", kase.windowText())
    }

    // ---------------- S1 题集 ----------------

    @Test
    fun `buildS1QuestionsJson 恰好四道题且题型正确`() {
        val q = AnalysisCase.buildS1QuestionsJson()

        assertEquals(setOf("need_action", "importance", "due_window", "topic"), q.keys().asSequence().toSet())
        assertEquals("noul", q.getJSONObject("need_action").getString("type"))
        assertEquals("score", q.getJSONObject("importance").getString("type"))
        assertEquals("choice", q.getJSONObject("due_window").getString("type"))
        assertEquals("choice", q.getJSONObject("topic").getString("type"))
    }

    @Test
    fun `importance 是 0-9 十档有序 criteria`() {
        val criteria = AnalysisCase.buildS1QuestionsJson()
            .getJSONObject("importance").getJSONArray("criteria")

        assertEquals(10, criteria.length())
        assertTrue(criteria.getString(0).startsWith("0:"))
        assertTrue(criteria.getString(9).startsWith("9:"))
    }

    @Test
    fun `due_window 与 topic 的选项集合固定`() {
        val q = AnalysisCase.buildS1QuestionsJson()

        assertEquals(
            setOf("none", "today", "tomorrow", "this_week", "later"),
            q.getJSONObject("due_window").getJSONObject("criteria").keys().asSequence().toSet()
        )
        assertEquals(
            setOf("work_task", "schedule", "notice", "smalltalk", "risk"),
            q.getJSONObject("topic").getJSONObject("criteria").keys().asSequence().toSet()
        )
    }

    @Test
    fun `need_action 的 criteria 是 true-false 两档`() {
        val criteria = AnalysisCase.buildS1QuestionsJson()
            .getJSONObject("need_action").getJSONObject("criteria")
        assertEquals(setOf("true", "false"), criteria.keys().asSequence().toSet())
    }

    @Test
    fun `题集的 instructions 与 criteria 一律英文（JEV 硬规则）`() {
        // 聊天内容保留中文，但判定题本身必须英文：混中文会让 laya 的判定口径漂移
        val cjk = Regex("[\\u4e00-\\u9fff]")
        val q = AnalysisCase.buildS1QuestionsJson()

        fun checkText(where: String, text: String) {
            assertFalse("$where 含中文：$text", cjk.containsMatchIn(text))
        }

        for (key in q.keys()) {
            val question = q.getJSONObject(key)
            checkText("$key.instructions", question.optString("instructions"))
            when (val criteria = question.opt("criteria")) {
                is JSONObject -> for (c in criteria.keys()) {
                    checkText("$key.criteria.$c", criteria.optString(c))
                }
                is JSONArray -> for (i in 0 until criteria.length()) {
                    checkText("$key.criteria[$i]", criteria.optString(i))
                }
                else -> throw AssertionError("$key 缺少 criteria")
            }
        }
    }

    // ---------------- S1Result ----------------

    @Test
    fun `needAction 在 0_5 处取真`() {
        assertTrue(s1(prob = 0.5).needAction)
        assertTrue(s1(prob = 0.9).needAction)
        assertFalse(s1(prob = 0.499).needAction)
    }

    @Test
    fun `shouldEscalate 三个条件任一命中即升级`() {
        assertFalse("三个条件都不该命中", s1(prob = 0.2, importance = 3.0, confidence = 0.9).shouldEscalate())

        assertTrue(s1(prob = 0.5, importance = 0.0, confidence = 1.0).shouldEscalate())
        assertTrue(s1(prob = 0.0, importance = 6.0, confidence = 1.0).shouldEscalate())
        assertTrue(s1(prob = 0.0, importance = 0.0, confidence = 0.499).shouldEscalate())
    }

    @Test
    fun `shouldEscalate 的边界值不升级`() {
        // 阈值是 >= / >= / <：confidence 恰好 0.5 不算「存疑」
        assertFalse(s1(prob = 0.4999, importance = 5.999, confidence = 0.5).shouldEscalate())
    }

    @Test
    fun `升级阈值常量口径`() {
        assertEquals(0.5, AnalysisCase.ESCALATE_NEED_ACTION_PROB, 0.0)
        assertEquals(6.0, AnalysisCase.ESCALATE_IMPORTANCE, 0.0)
        assertEquals(0.5, AnalysisCase.ESCALATE_MIN_CONFIDENCE, 0.0)
    }

    @Test
    fun `toInjectText 带全部五个字段`() {
        val text = s1(prob = 0.8, importance = 7.0, dueWindow = "today", topic = "work_task", confidence = 0.9)
            .toInjectText()

        assertTrue(text, text.contains("need_action_prob="))
        assertTrue(text, text.contains("importance="))
        assertTrue(text, text.contains("due_window=today"))
        assertTrue(text, text.contains("topic=work_task"))
        assertTrue(text, text.contains("confidence="))
    }

    // ---------------- prompt 构造 ----------------

    @Test
    fun `buildS1OpenAiSystemPrompt 无背景时原样返回常量`() {
        assertEquals(AnalysisCase.S1_OPENAI_SYSTEM_PROMPT, AnalysisCase.buildS1OpenAiSystemPrompt())
        assertEquals(AnalysisCase.S1_OPENAI_SYSTEM_PROMPT, AnalysisCase.buildS1OpenAiSystemPrompt("  "))
    }

    @Test
    fun `buildS1OpenAiSystemPrompt 有背景时追加背景段`() {
        val prompt = AnalysisCase.buildS1OpenAiSystemPrompt("张三是我的直属上级，负责结算模块")

        assertTrue(prompt.startsWith(AnalysisCase.S1_OPENAI_SYSTEM_PROMPT))
        assertTrue(prompt.contains("背景信息（判定该会话时必须纳入考量）：张三是我的直属上级，负责结算模块"))
    }

    @Test
    fun `S1 system prompt 要求只输出 JSON 且列出五个字段`() {
        val p = AnalysisCase.S1_OPENAI_SYSTEM_PROMPT
        assertTrue(p.contains("output ONLY a JSON object"))
        listOf("need_action_prob", "importance", "due_window", "topic", "confidence").forEach {
            assertTrue("prompt 里应当声明字段 $it", p.contains(it))
        }
    }

    @Test
    fun `buildS2SystemPrompt 注入 S1 结论`() {
        val s1r = s1(prob = 0.8, importance = 7.0, dueWindow = "today", topic = "work_task", confidence = 0.9)
        val prompt = AnalysisCase.buildS2SystemPrompt(s1r)

        assertTrue(prompt.contains(s1r.toInjectText()))
        assertTrue(prompt.contains("S1 快速判定的结论"))
    }

    @Test
    fun `buildS2SystemPrompt 要求输出四个键`() {
        val prompt = AnalysisCase.buildS2SystemPrompt(s1())
        listOf("summary", "due_time", "suggested_action", "tasks", "who", "what", "when").forEach {
            assertTrue("S2 prompt 应当声明 $it", prompt.contains(it))
        }
    }

    @Test
    fun `buildS2SystemPrompt 仅在背景非空时插入背景段`() {
        assertFalse(AnalysisCase.buildS2SystemPrompt(s1()).contains("背景信息"))
        assertFalse(AnalysisCase.buildS2SystemPrompt(s1(), "   ").contains("背景信息"))

        val prompt = AnalysisCase.buildS2SystemPrompt(s1(), "对方是甲方项目经理")
        assertTrue(prompt.contains("背景信息（分析该会话时必须纳入考量）：对方是甲方项目经理"))
    }
}
