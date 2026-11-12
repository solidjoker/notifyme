// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ConvWindowCore] 的窗口口径测试。
 *
 * 为什么值得测：会话详情页此前的读法是 `readRecent(500).filter { convKey }` ——
 * 跨会话窗口，别的会话刷屏就把本会话历史挤掉（详情页空白而数据仍在文件里）；
 * 同一个窗口还被当成「共 N 条」的分母，于是提示文案会报一个偏小的总数。
 * 这些分支全在纯函数里，改窗口大小、排序方向或「条数是否可信」的判据前必跑本文件。
 */
class ConvWindowCoreTest {

    private val key = ConvKey("com.tencent.mm", "张三")
    private val other = ConvKey("com.tencent.mm", "李四")

    private fun msg(
        text: String,
        ts: Long,
        conversation: String = "张三",
        pkg: String = "com.tencent.mm"
    ) = ChatMessage(
        sender = conversation,
        text = text,
        timestamp = ts,
        conversation = conversation,
        isGroup = false,
        pkg = pkg
    )

    // ---------------- requestLines ----------------

    @Test
    fun `requestLines 默认是基础条数的八倍`() {
        assertEquals(ConvWindowCore.ALL_LIMIT * 8, ConvWindowCore.requestLines())
        assertEquals(4000, ConvWindowCore.requestLines(500))
        assertEquals(800, ConvWindowCore.requestLines(100))
    }

    @Test
    fun `requestLines 对非正数收敛为一次请求`() {
        assertEquals(8, ConvWindowCore.requestLines(0))
        assertEquals(8, ConvWindowCore.requestLines(-3))
    }

    @Test
    fun `requestLines 受硬上限约束不退化为全量读取`() {
        assertEquals(ConvWindowCore.MAX_REQUEST_LINES, ConvWindowCore.requestLines(100_000))
        assertEquals(ConvWindowCore.MAX_REQUEST_LINES, ConvWindowCore.requestLines(Int.MAX_VALUE))
    }

    // ---------------- isFor ----------------

    @Test
    fun `isFor 按包名与会话名同时匹配`() {
        assertTrue(ConvWindowCore.isFor(msg("在吗", 1L), key))
        assertFalse(ConvWindowCore.isFor(msg("在吗", 1L, conversation = "李四"), key))
    }

    @Test
    fun `isFor 同名会话跨 App 不串台`() {
        val sameNameOtherPkg = msg("在吗", 1L, pkg = "com.android.mms")
        assertFalse(ConvWindowCore.isFor(sameNameOtherPkg, key))
        assertTrue(ConvWindowCore.isFor(sameNameOtherPkg, ConvKey("com.android.mms", "张三")))
    }

    // ---------------- slice ----------------

    @Test
    fun `slice 只留本会话且按时间升序`() {
        val haystack = listOf(
            msg("第二条", 3_000L),
            msg("别人的", 2_500L, conversation = "李四"),
            msg("第一条", 1_000L),
            msg("第三条", 5_000L)
        )
        assertEquals(
            listOf("第一条", "第二条", "第三条"),
            ConvWindowCore.slice(haystack, key).map { it.text }
        )
    }

    @Test
    fun `slice 空窗口返回空列表`() {
        assertTrue(ConvWindowCore.slice(emptyList(), key).isEmpty())
        assertTrue(ConvWindowCore.slice(listOf(msg("别人的", 1L, conversation = "李四")), key).isEmpty())
    }

    @Test
    fun `slice 超过 limit 时保留最近的若干条`() {
        val haystack = (1..10).map { msg("第" + it + "条", it * 1_000L) }
        val sliced = ConvWindowCore.slice(haystack, key, limit = 3)
        assertEquals(listOf("第8条", "第9条", "第10条"), sliced.map { it.text })
    }

    @Test
    fun `slice 未标注日期消息排最前且不被截断丢到末尾`() {
        val haystack = listOf(
            msg("有时间的", 9_000L),
            msg("无时间的", 0L),
            msg("负时间的", -5L)
        )
        val sliced = ConvWindowCore.slice(haystack, key)
        // ts<=0 一律按 0 参与排序（与既有 sortedBy { it.timestamp } 同口径），
        // 两条未标注日期的相对次序交给稳定排序 = 输入顺序
        assertEquals(listOf("无时间的", "负时间的", "有时间的"), sliced.map { it.text })
    }

    @Test
    fun `slice limit 恰好等于条数时不丢任何条`() {
        val haystack = listOf(msg("a", 1L), msg("b", 2L))
        assertEquals(2, ConvWindowCore.slice(haystack, key, limit = 2).size)
    }

    // ---------------- reachedHead ----------------

    @Test
    fun `reachedHead 实际行数少于请求行数说明读到文件头`() {
        assertTrue(ConvWindowCore.reachedHead(linesRead = 12, requested = 4000))
        assertTrue(ConvWindowCore.reachedHead(linesRead = 0, requested = 4000))
    }

    @Test
    fun `reachedHead 行数打满请求时保守当作没读全`() {
        assertFalse(ConvWindowCore.reachedHead(linesRead = 4000, requested = 4000))
        assertFalse(ConvWindowCore.reachedHead(linesRead = 9000, requested = 4000))
    }

    @Test
    fun `reachedHead 请求非正数时视为读到文件头`() {
        assertTrue(ConvWindowCore.reachedHead(linesRead = 5, requested = 0))
    }

    // ---------------- Window / preview ----------------

    private fun window(
        messages: List<ChatMessage>,
        linesRead: Int,
        requested: Int = 4000,
        limit: Int = ConvWindowCore.ALL_LIMIT
    ) = ConvWindowCore.Window(key, messages, linesRead, requested, limit)

    @Test
    fun `window size 等于命中条数`() {
        val w = window(listOf(msg("a", 1L), msg("b", 2L)), linesRead = 10)
        assertEquals(2, w.size)
    }

    @Test
    fun `preview 条数小于渲染上限时不截断`() {
        val p = ConvWindowCore.preview(window((1..10).map { msg("m" + it, it.toLong()) }, linesRead = 12))
        assertEquals(10, p.visible)
        assertFalse(p.truncated)
        assertTrue(p.countedAll)
    }

    @Test
    fun `preview 超过渲染上限时截断`() {
        val many = (1..250).map { msg("m" + it, it.toLong()) }
        val p = ConvWindowCore.preview(window(many, linesRead = 300))
        assertEquals(250, p.visible)
        assertTrue(p.truncated)
        assertTrue(p.countedAll)
    }

    @Test
    fun `preview 恰好等于渲染上限时不算截断`() {
        val rows = (1..ConvWindowCore.MAX_RENDER).map { msg("m" + it, it.toLong()) }
        val p = ConvWindowCore.preview(window(rows, linesRead = rows.size))
        assertTrue(p.countedAll)
        assertFalse(p.truncated)
    }

    @Test
    fun `preview 行数打满请求时条数不可信`() {
        val rows = (1..30).map { msg("m" + it, it.toLong()) }
        val p = ConvWindowCore.preview(window(rows, linesRead = 4000, requested = 4000))
        assertEquals(30, p.visible)
        assertFalse(p.countedAll)
    }

    @Test
    fun `preview 命中数被 limit 砍掉时条数不可信`() {
        val rows = (1..500).map { msg("m" + it, it.toLong()) }
        val w = window(rows, linesRead = 100, requested = 4000, limit = 500)
        val p = ConvWindowCore.preview(w)
        assertEquals(500, p.visible)
        assertFalse(p.countedAll)
    }

    @Test
    fun `preview 空窗口条数为零且可信`() {
        val p = ConvWindowCore.preview(window(emptyList(), linesRead = 0))
        assertEquals(0, p.visible)
        assertFalse(p.truncated)
        assertTrue(p.countedAll)
    }

    @Test
    fun `preview 支持自定义渲染上限`() {
        val rows = (1..5).map { msg("m" + it, it.toLong()) }
        val p = ConvWindowCore.preview(window(rows, linesRead = 5), renderLimit = 3)
        assertTrue(p.truncated)
        assertEquals(5, p.visible)
    }

    // ---------------- latest / knownIsGroup（跨会话窗口的两个受害者） ----------------

    @Test
    fun `latest 取窗口里时间最晚的那条`() {
        // Window.messages 的契约是「slice 出来的升序」，所以末条即最新；这里按升序喂
        val rows = listOf(msg("早", 1_000L), msg("中", 3_000L), msg("晚", 5_000L))
        assertEquals("晚", ConvWindowCore.latest(window(rows, linesRead = 3))?.text)
    }

    @Test
    fun `latest 对空窗口返回 null`() {
        assertEquals(null, ConvWindowCore.latest(window(emptyList(), linesRead = 0)))
    }

    @Test
    fun `latest 不会被同会话的未标注日期消息顶掉`() {
        // ts<=0 在升序里排最前，所以它永远不可能是 lastOrNull 的结果
        val rows = listOf(msg("无时间", 0L), msg("最新", 9_000L))
        assertEquals("最新", ConvWindowCore.latest(window(rows, linesRead = 2))?.text)
    }

    @Test
    fun `knownIsGroup 空窗口返回 null 而不是 false`() {
        // null = 首次提取（调用方按昵称回推），false = 历史判定私聊（写死，不再回推）
        assertEquals(null, ConvWindowCore.knownIsGroup(window(emptyList(), linesRead = 0)))
    }

    @Test
    fun `knownIsGroup 沿用最新一条的群聊标记`() {
        val group = msg("群消息", 5_000L).copy(isGroup = true)
        val older = msg("早的私聊口气", 1_000L).copy(isGroup = false)
        assertEquals(true, ConvWindowCore.knownIsGroup(window(listOf(older, group), linesRead = 2)))
    }

    @Test
    fun `knownIsGroup 私聊历史返回 false`() {
        assertEquals(false, ConvWindowCore.knownIsGroup(window(listOf(msg("a", 1_000L)), linesRead = 1)))
    }

    // ---------------- MessageStoreCore.readWindow（按会话尾读的接线） ----------------

    @get:org.junit.Rule
    val tmp = org.junit.rules.TemporaryFolder()

    private fun coreWith(rows: List<ChatMessage>): MessageStoreCore {
        val dir = tmp.newFolder("files")
        val core = MessageStore.coreIn(dir)
        rows.forEach { core.append(it) }
        return core
    }

    @Test
    fun `readWindow 不被别的会话刷屏挤掉本会话历史`() {
        // 别的会话塞 600 条（远超旧的 readRecent(500) 窗口），本会话仍要完整读到
        val noise = (1..600).map { msg("噪声" + it, 1_000L + it, conversation = "李四") }
        val mine = (1..5).map { msg("我的" + it, 10_000L + it) }
        val window = coreWith(noise + mine).readWindow(key)

        assertEquals(
            listOf("我的1", "我的2", "我的3", "我的4", "我的5"),
            window.messages.map { it.text }
        )
        assertTrue(ConvWindowCore.reachedHead(window.linesRead, window.requested))
    }

    @Test
    fun `readWindow 只含该会话的消息且按时间升序`() {
        val core = coreWith(
            listOf(
                msg("b", 2_000L),
                msg("别人的", 2_500L, conversation = "李四"),
                msg("a", 1_000L)
            )
        )
        val window = core.readWindow(key)
        assertEquals(listOf("a", "b"), window.messages.map { it.text })
    }

    @Test
    fun `readWindow 文件不存在返回空窗口`() {
        val core = MessageStore.coreIn(tmp.newFolder("files"))
        val window = core.readWindow(key)
        assertEquals(0, window.size)
        assertEquals(0, window.linesRead)
        assertEquals(ConvWindowCore.requestLines(), window.requested)
        assertTrue(ConvWindowCore.preview(window).countedAll)
    }

    @Test
    fun `readWindow 超过 limit 时保留最近若干条`() {
        val rows = (1..10).map { msg("m" + it, it * 1_000L) }
        val window = coreWith(rows).readWindow(key, limit = 3)
        assertEquals(listOf("m8", "m9", "m10"), window.messages.map { it.text })
        assertFalse(ConvWindowCore.preview(window).countedAll)
    }

    // ---------------- sourceKeys / sourceMap（分析列表回看原文） ----------------

    private fun rec(
        conv: String,
        caseId: String = "case-$conv",
        windowEnd: Long = 30_000L,
        messageCount: Int = 10,
        pkg: String = "com.tencent.mm"
    ) = AnalysisCaseRecord(
        caseId = caseId,
        conversation = conv,
        windowEnd = windowEnd,
        messageCount = messageCount,
        analyzedAt = windowEnd,
        protocol = "openai",
        rawJson = "{}",
        pkg = pkg,
        s1NeedAction = false,
        s1NeedActionProb = 0.1,
        s1Importance = 2.0,
        s1DueWindow = "none",
        s1Topic = "notice",
        s1Confidence = 0.8,
        escalated = false,
        s2Summary = "",
        s2DueTime = "",
        s2SuggestedAction = "",
        s2Tasks = emptyList()
    )

    @Test
    fun `sourceKeys 去重并保持首次出现顺序`() {
        val records = listOf(rec("张三"), rec("李四"), rec("张三"), rec("李四"))
        assertEquals(listOf(key, other), ConvWindowCore.sourceKeys(records))
    }

    @Test
    fun `sourceKeys 跳过空会话名的老记录`() {
        val records = listOf(rec(""), rec("张三"))
        assertEquals(listOf(key), ConvWindowCore.sourceKeys(records))
    }

    @Test
    fun `sourceMap 按会话自己的窗口取原文，不被别的会话刷屏挤掉`() {
        // 别的会话塞 600 条（远超旧的 readRecent(500) 窗口），本会话 3 条
        val noise = (1..600).map { msg("噪声" + it, 1_000L + it, conversation = "李四") }
        val mine = (1..3).map { msg("我的" + it, 10_000L + it) }
        val core = coreWith(noise + mine)
        val windows = mapOf(key to core.readWindow(key))
        val out = ConvWindowCore.sourceMap(listOf(rec("张三", windowEnd = 10_003L)), windows, nowMs = 20_000L)

        val text = out.getValue("case-张三")
        assertTrue(text.contains("我的3"))
        assertFalse(text.contains("噪声"))
    }

    @Test
    fun `sourceMap 只取 windowEnd 之前且最近 messageCount 条`() {
        val core = coreWith(
            listOf(
                msg("a", 1_000L),
                msg("b", 2_000L),
                msg("c", 3_000L),
                msg("d", 4_000L)
            )
        )
        val windows = mapOf(key to core.readWindow(key))
        val out = ConvWindowCore.sourceMap(
            listOf(rec("张三", windowEnd = 3_000L, messageCount = 2)),
            windows,
            nowMs = 10_000L
        )

        val text = out.getValue("case-张三")
        // windowEnd=3000 → 只剩 a/b/c；messageCount=2 → 取最近两条 b/c
        assertTrue(text.contains(": b") || text.contains("b"))
        assertTrue(text.contains("c"))
        assertFalse(text.contains("d"))
    }

    @Test
    fun `sourceMap 该会话没有窗口时整条不出现`() {
        val out = ConvWindowCore.sourceMap(listOf(rec("张三")), emptyMap(), nowMs = 10_000L)
        assertTrue(out.isEmpty())
    }

    @Test
    fun `sourceMap 窗口外取不满时返回空串而不是别的会话内容`() {
        // case 的窗口在 1_000 附近，但会话窗口只保留了最近的近期消息（limit=1 且时间更晚）
        val core = coreWith(listOf(msg("近期", 9_000L)))
        val windows = mapOf(key to core.readWindow(key))
        val out = ConvWindowCore.sourceMap(
            listOf(rec("张三", windowEnd = 1_000L)),
            windows,
            nowMs = 10_000L
        )
        assertTrue(out.isEmpty())
    }

    @Test
    fun `sourceMap 空记录返回空表`() {
        assertTrue(ConvWindowCore.sourceMap(emptyList(), emptyMap()).isEmpty())
    }
}
