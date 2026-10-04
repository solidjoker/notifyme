// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AnalysisParsing] 的防御式解析测试（M1 从 AnalysisWorker 抽出）。
 *
 * 模型输出的形态天然不稳定：可能包一层 `{"answer": {...}}`、可能夹带散文或 markdown 围栏、
 * 可能字段缺失、可能类型不符（把概率写成"很高"）。原实现的约定是**一律兜底默认值、
 * 绝不抛异常打断分析流程**，这些分支此前完全没有测试；一旦哪天改坏，
 * 表现是「分析结果全是默认值」而不是崩溃，很难被发现。
 */
class AnalysisParsingTest {

    private val delta = 1e-9

    private fun kase(vararg texts: String): AnalysisCase = AnalysisCase(
        caseId = "test-case",
        conversation = "张三",
        pkg = AppSourceRegistry.PKG_WECHAT,
        isGroup = false,
        windowEnd = 1_000L,
        messages = texts.mapIndexed { i, t ->
            ChatMessage(
                sender = "张三",
                text = t,
                timestamp = 1_000L + i,
                conversation = "张三",
                isGroup = false
            )
        }
    )

    // ---------------- parseS1Result ----------------

    @Test
    fun `payload 为 null 时返回安全默认值`() {
        val r = AnalysisParsing.parseS1Result(null, "openai")

        assertEquals(0.0, r.needActionProb, delta)
        assertEquals(0.0, r.importance, delta)
        assertEquals("none", r.dueWindow)
        assertEquals("notice", r.topic)
        assertEquals(1.0, r.confidence, delta)
        assertFalse("默认值不该触发升级", r.shouldEscalate())
    }

    @Test
    fun `顶层字段完整解析`() {
        val payload = JSONObject(
            """{"need_action_prob":0.85,"importance":7,"due_window":"today",
               "topic":"work_task","confidence":0.9}"""
        )

        val r = AnalysisParsing.parseS1Result(payload, "openai")

        assertEquals(0.85, r.needActionProb, delta)
        assertEquals(7.0, r.importance, delta)
        assertEquals("today", r.dueWindow)
        assertEquals("work_task", r.topic)
        assertEquals(0.9, r.confidence, delta)
        assertTrue(r.needAction)
        assertTrue(r.shouldEscalate())
    }

    @Test
    fun `包一层 answer 也能解析`() {
        // laya/JEV 侧的回答形态与 openai 槽位不同，字段可能嵌在 answer 里
        val payload = JSONObject(
            """{"answer":{"need_action_prob":0.7,"importance":6,"due_window":"tomorrow",
                 "topic":"schedule","confidence":0.8}}"""
        )

        val r = AnalysisParsing.parseS1Result(payload, "systemone")

        assertEquals(0.7, r.needActionProb, delta)
        assertEquals("tomorrow", r.dueWindow)
        assertEquals("schedule", r.topic)
    }

    @Test
    fun `answer 不是对象时回落顶层字段而不抛异常`() {
        val payload = JSONObject("""{"answer":"模型把答案写成了字符串"}""")

        val r = AnalysisParsing.parseS1Result(payload, "openai")

        assertEquals(0.0, r.needActionProb, delta)
        assertEquals("none", r.dueWindow)
    }

    @Test
    fun `类型不符时按字段兜底不影响其他字段`() {
        val payload = JSONObject(
            """{"need_action_prob":"很高","importance":8,"due_window":42,
               "topic":"risk","confidence":"不确定"}"""
        )

        val r = AnalysisParsing.parseS1Result(payload, "openai")

        assertEquals("非数值概率应回落 0.0", 0.0, r.needActionProb, delta)
        assertEquals(8.0, r.importance, delta)
        assertEquals("数值型 due_window 会被字符串化保留", "42", r.dueWindow)
        assertEquals("risk", r.topic)
        assertEquals("非数值置信度应回落 1.0", 1.0, r.confidence, delta)
        assertTrue("importance 8 仍应触发升级", r.shouldEscalate())
    }

    @Test
    fun `字段是嵌套对象时兜底默认值`() {
        val payload = JSONObject("""{"need_action_prob":{"value":0.9},"importance":{"v":7}}""")
        val r = AnalysisParsing.parseS1Result(payload, "openai")
        assertEquals(0.0, r.needActionProb, delta)
        assertEquals(0.0, r.importance, delta)
    }

    @Test
    fun `越界数值 coerce 回合法区间`() {
        val over = AnalysisParsing.parseS1Result(
            JSONObject("""{"need_action_prob":1.5,"importance":12,"confidence":3}"""), "openai"
        )
        assertEquals(1.0, over.needActionProb, delta)
        assertEquals(9.0, over.importance, delta)
        assertEquals(1.0, over.confidence, delta)

        val under = AnalysisParsing.parseS1Result(
            JSONObject("""{"need_action_prob":-0.2,"importance":-3,"confidence":-1}"""), "openai"
        )
        assertEquals(0.0, under.needActionProb, delta)
        assertEquals(0.0, under.importance, delta)
        assertEquals(0.0, under.confidence, delta)
    }

    @Test
    fun `空字符串枚举值回落默认桶`() {
        val r = AnalysisParsing.parseS1Result(JSONObject("""{"due_window":"","topic":""}"""), "openai")
        assertEquals("none", r.dueWindow)
        assertEquals("notice", r.topic)
    }

    @Test
    fun `部分字段缺失时只兜底缺失的那些`() {
        val r = AnalysisParsing.parseS1Result(JSONObject("""{"importance":9}"""), "openai")
        assertEquals(9.0, r.importance, delta)
        assertEquals(0.0, r.needActionProb, delta)
        assertEquals("none", r.dueWindow)
        assertEquals(1.0, r.confidence, delta)
        assertTrue(r.shouldEscalate())
    }

    // ---------------- parseS2Output ----------------

    @Test
    fun `S2 完整字段与 tasks 解析`() {
        val payload = JSONObject(
            """{"summary":"结算方案评审改期","due_time":"今天下午3点",
               "suggested_action":"提前准备上次意见",
               "tasks":[{"who":"我","what":"整理评审意见","when":"今天下午2点前"},
                        {"who":"张三","what":"确认会议室","when":""}]}"""
        )

        val out = AnalysisParsing.parseS2Output(payload, kase("评审改到三点"), "raw-text")

        assertEquals("结算方案评审改期", out.summary)
        assertEquals("今天下午3点", out.dueTime)
        assertEquals("提前准备上次意见", out.suggestedAction)
        assertEquals(2, out.tasks.size)
        assertEquals("我", out.tasks[0].who)
        assertEquals("整理评审意见", out.tasks[0].what)
        assertEquals("今天下午2点前", out.tasks[0].`when`)
        assertEquals("张三", out.tasks[1].who)
        assertEquals("", out.tasks[1].`when`)
        assertEquals("raw-text", out.rawJson)
    }

    @Test
    fun `S2 包一层 answer 也能解析`() {
        val payload = JSONObject("""{"answer":{"summary":"周报待交","due_time":"今晚","suggested_action":"下班前发","tasks":[]}}""")
        val out = AnalysisParsing.parseS2Output(payload, kase("周报"), "raw")
        assertEquals("周报待交", out.summary)
        assertEquals("今晚", out.dueTime)
        assertEquals(emptyList<AnalysisCaseRecord.Task>(), out.tasks)
    }

    @Test
    fun `S2 摘要缺失时兜底窗口末条原文`() {
        val out = AnalysisParsing.parseS2Output(JSONObject("""{"due_time":"明天"}"""), kase("第一条", "第二条"), "raw")
        assertEquals("第二条", out.summary)
        assertEquals("明天", out.dueTime)
    }

    @Test
    fun `S2 摘要兜底会把换行压成空格并截 20 字`() {
        val long = "第一行\n第二行" + "很长".repeat(20)
        val out = AnalysisParsing.parseS2Output(JSONObject("""{"summary":"   "}"""), kase(long), "raw")

        assertEquals(20, out.summary.length)
        assertFalse("换行必须压掉，否则进不了 UI 单行摘要", out.summary.contains("\n"))
        assertTrue(out.summary.startsWith("第一行 第二行"))
    }

    @Test
    fun `S2 payload 为 null 时兜底摘要且其余字段为空`() {
        val out = AnalysisParsing.parseS2Output(null, kase("就这一条"), "raw")

        assertEquals("就这一条", out.summary)
        assertEquals("", out.dueTime)
        assertEquals("", out.suggestedAction)
        assertEquals(emptyList<AnalysisCaseRecord.Task>(), out.tasks)
        assertEquals("raw", out.rawJson)
    }

    @Test
    fun `S2 空窗口不再抛异常（抽出时加固的一处）`() {
        // 生产路径不会出现空窗口，但原实现用 messages.last() 会直接 NoSuchElementException
        val out = AnalysisParsing.parseS2Output(JSONObject("""{"due_time":"明天"}"""), kase(), "raw")
        assertEquals("", out.summary)
        assertEquals("明天", out.dueTime)
    }

    @Test
    fun `S2 tasks 里混入非对象元素时跳过`() {
        val payload = JSONObject(
            """{"summary":"s","tasks":["不是对象",{"who":"我","what":"做事","when":""},null]}"""
        )
        val out = AnalysisParsing.parseS2Output(payload, kase("x"), "raw")
        assertEquals(1, out.tasks.size)
        assertEquals("做事", out.tasks[0].what)
    }

    @Test
    fun `S2 tasks 缺失时为空列表`() {
        val out = AnalysisParsing.parseS2Output(JSONObject("""{"summary":"s"}"""), kase("x"), "raw")
        assertEquals(emptyList<AnalysisCaseRecord.Task>(), out.tasks)
    }

    @Test
    fun `S2 原文始终保留在 rawJson 里`() {
        // 解析失败也要能把模型原文存下来，方便事后排查「为什么这条没提醒」
        val raw = "模型这次输出了一堆散文，完全没有 JSON"
        val out = AnalysisParsing.parseS2Output(
            AnalysisParsing.extractJsonFromText(raw), kase("窗口末条"), raw
        )
        assertEquals(raw, out.rawJson)
        assertEquals("窗口末条", out.summary)
    }

    // ---------------- extractJsonPayload ----------------

    @Test
    fun `从 chat_completions 响应里取出 content 并解析`() {
        val raw = """{"choices":[{"message":{"role":"assistant","content":"{\"need_action_prob\":0.9}"}}]}"""
        val payload = AnalysisParsing.extractJsonPayload(raw)

        assertEquals(0.9, payload!!.getDouble("need_action_prob"), delta)
    }

    @Test
    fun `content 夹带散文时仍能截出 JSON`() {
        val raw = """{"choices":[{"message":{"content":"好的，判定如下：{\"importance\":6} 希望有帮助"}}]}"""
        assertEquals(6.0, AnalysisParsing.extractJsonPayload(raw)!!.getDouble("importance"), delta)
    }

    @Test
    fun `content 被 markdown 围栏包住时仍能解析`() {
        val raw = """{"choices":[{"message":{"content":"```json\n{\"topic\":\"risk\"}\n```"}}]}"""
        assertEquals("risk", AnalysisParsing.extractJsonPayload(raw)!!.getString("topic"))
    }

    @Test
    fun `响应结构不完整时返回 null`() {
        assertNull(AnalysisParsing.extractJsonPayload("""{"choices":[]}"""))
        assertNull(AnalysisParsing.extractJsonPayload("""{"choices":[{"message":{}}]}"""))
        assertNull(AnalysisParsing.extractJsonPayload("""{"error":"rate limited"}"""))
        assertNull(AnalysisParsing.extractJsonPayload("不是 JSON"))
        assertNull(AnalysisParsing.extractJsonPayload(""))
    }

    // ---------------- extractJsonFromText ----------------

    @Test
    fun `裸 JSON 与带前后缀文本都能截取`() {
        assertEquals(1.0, AnalysisParsing.extractJsonFromText("""{"a":1}""")!!.getDouble("a"), delta)
        assertEquals(2.0, AnalysisParsing.extractJsonFromText("""判定：{"a":2} 完毕""")!!.getDouble("a"), delta)
    }

    @Test
    fun `嵌套对象按第一个左括号到最后一个右括号截取`() {
        val text = """前置说明 {"outer":{"inner":3}} 后置说明"""
        val obj = AnalysisParsing.extractJsonFromText(text)!!
        assertEquals(3.0, obj.getJSONObject("outer").getDouble("inner"), delta)
    }

    @Test
    fun `没有完整括号时返回 null`() {
        assertNull(AnalysisParsing.extractJsonFromText("完全没有括号"))
        assertNull(AnalysisParsing.extractJsonFromText("{"))
        assertNull(AnalysisParsing.extractJsonFromText("}"))
        assertNull(AnalysisParsing.extractJsonFromText(""))
    }

    @Test
    fun `两个 JSON 对象拼在一起时只取第一个完整对象`() {
        // 截取范围是首 { 到末 }，但 org.json 解析到第一个完整对象就停、忽略尾部残留，
        // 所以拿到的是第一个对象 {"a":1}。这是「模型多吐了一段」时的实际行为，钉住它以防回归。
        val obj = AnalysisParsing.extractJsonFromText("""{"a":1} 和 {"b":2}""")
        assertEquals(1.0, obj!!.getDouble("a"), delta)
        assertFalse("第二个对象不该被合并进来", obj.has("b"))
    }

    @Test
    fun `括号内容非法时返回 null 而不是抛异常`() {
        assertNull(AnalysisParsing.extractJsonFromText("{这不是 JSON}"))
    }
}
