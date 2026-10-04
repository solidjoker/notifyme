// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [MessageStoreCore] 的存储语义测试（messages.jsonl）。
 *
 * 为什么值得测：M2 要给 [ChatMessage] 加 `pkg` 字段并做 schema v2 读时补齐迁移，
 * 动的正是用户已有的 messages.jsonl——删除路径与去重口径一旦改错就是不可逆的数据丢失，
 * 而这些分支此前一行测试都没有。
 *
 * 覆盖点：追加 / 倒序读取与 limit、未标注日期占名额、损坏行读取跳过且重写保留、
 * 三条删除路径（会话 / 日期 / 多选消息）、merge 去重的两套口径（秒级 vs a11y 忽略时间戳）、
 * onChange 通知时机。
 */
class MessageStoreCoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var dir: File
    private lateinit var core: MessageStoreCore
    private var changeCount = 0

    private val dataFile: File get() = File(dir, "messages.jsonl")

    @Before
    fun setUp() {
        dir = tmp.newFolder("files")
        changeCount = 0
        core = MessageStore.coreIn(dir) { changeCount++ }
    }

    private fun msg(
        text: String,
        ts: Long,
        conversation: String = "张三",
        sender: String = "张三",
        isGroup: Boolean = false,
        source: String = ""
    ) = ChatMessage(
        sender = sender,
        text = text,
        timestamp = ts,
        conversation = conversation,
        isGroup = isGroup,
        source = source
    )

    /** 直接往数据文件里塞原始行（构造损坏行、乱序行等测试现场）。 */
    private fun writeRaw(vararg lines: String) {
        dataFile.parentFile?.mkdirs()
        dataFile.writeText(lines.joinToString("") { "$it\n" })
    }

    // ---------------- 基本读写 ----------------

    @Test
    fun `append 后 readRecent 按时间倒序返回`() {
        core.append(msg("第一条", 1_000L))
        core.append(msg("第二条", 3_000L))
        core.append(msg("第三条", 2_000L))

        assertEquals(listOf("第二条", "第三条", "第一条"), core.readRecent().map { it.text })
    }

    @Test
    fun `readRecent 文件不存在返回空列表`() {
        assertEquals(emptyList<ChatMessage>(), core.readRecent())
    }

    @Test
    fun `readRecent 按 limit 截断最新消息`() {
        repeat(5) { i -> core.append(msg("m$i", 1_000L + i * 1_000)) }
        assertEquals(listOf("m4", "m3"), core.readRecent(limit = 2).map { it.text })
    }

    @Test
    fun `readRecent 让未标注日期消息先占名额不被截断掉`() {
        // 背景：ts<=0（a11y 估算失败）的消息若参与倒序截断会永远排最末，
        // 总数超 limit 时必然丢失，UI 的「未标注日期」兜底永远不触发
        repeat(3) { i -> core.append(msg("dated$i", 1_000L + i * 1_000)) }
        core.append(msg("undated0", 0L))
        core.append(msg("undated1", -1L))

        val recent = core.readRecent(limit = 3)

        assertEquals(3, recent.size)
        assertEquals("dated2", recent.first().text)
        assertEquals(
            "两条未标注日期消息都应当保住",
            listOf("undated0", "undated1"),
            recent.drop(1).map { it.text }
        )
    }

    @Test
    fun `未标注日期消息多于 limit 时也只保留 limit 条`() {
        repeat(4) { i -> core.append(msg("undated$i", 0L)) }
        assertEquals(2, core.readRecent(limit = 2).size)
    }

    // ---------------- 损坏行 ----------------

    @Test
    fun `读取时跳过损坏行不影响其余消息`() {
        writeRaw(
            """{"sender":"张三","text":"好","timestamp":1000,"conversation":"张三","isGroup":false}""",
            """{这不是 JSON""",
            """{"sender":"张三","text":"收到","timestamp":2000,"conversation":"张三","isGroup":false}"""
        )
        assertEquals(listOf("收到", "好"), core.readRecent().map { it.text })
    }

    @Test
    fun `删除会话时损坏行原样保留`() {
        val corrupt = """{半截 JSON 没有结尾"""
        writeRaw(
            """{"sender":"张三","text":"好","timestamp":1000,"conversation":"张三","isGroup":false}""",
            corrupt,
            """{"sender":"李四","text":"在","timestamp":2000,"conversation":"李四","isGroup":false}"""
        )

        val removed = core.deleteConversation(ConvKey.legacy("张三"))

        assertEquals(1, removed)
        assertTrue(
            "损坏行必须原样写回：不能因为一次删除就丢掉无法解析的用户数据",
            dataFile.readLines().contains(corrupt)
        )
        assertEquals(listOf("在"), core.readRecent().map { it.text })
    }

    // ---------------- 三条删除路径 ----------------

    @Test
    fun `deleteConversation 返回实际删除条数并只删该会话`() {
        core.append(msg("a1", 1_000L, conversation = "张三"))
        core.append(msg("a2", 2_000L, conversation = "张三"))
        core.append(msg("b1", 3_000L, conversation = "项目群", sender = "李四", isGroup = true))
        changeCount = 0 // append 也会通知，这里只关心删除这一次

        assertEquals(2, core.deleteConversation(ConvKey.legacy("张三")))
        assertEquals(listOf("b1"), core.readRecent().map { it.text })
        assertEquals("删除命中应恰好通知一次", 1, changeCount)
    }

    @Test
    fun `deleteConversation 无命中时不重写文件也不通知`() {
        core.append(msg("a1", 1_000L, conversation = "张三"))
        val before = dataFile.lastModified()
        changeCount = 0

        assertEquals(0, core.deleteConversation(ConvKey.legacy("不存在的会话")))

        assertEquals("无命中不该碰文件", before, dataFile.lastModified())
        assertEquals("无命中不该触发 UI 刷新", 0, changeCount)
    }

    @Test
    fun `deleteDate 只删该会话该天的消息`() {
        // 相差一天而不是相差一分钟：dayKeyOf 按本地日历格式化，同一天内的任意时刻都会被删掉
        val keep = msg("别的天", 1_700_000_000_000L, conversation = "张三")
        val drop = msg("这天", 1_700_086_460_000L, conversation = "张三")
        val otherConv = msg("别的会话", 1_700_086_460_000L, conversation = "李四", sender = "李四")
        core.append(keep)
        core.append(drop)
        core.append(otherConv)

        val removed = core.deleteDate(ConvKey.legacy("张三"), MessageStore.dayKeyOf(drop))

        assertEquals(1, removed)
        assertEquals(listOf("别的会话", "别的天"), core.readRecent().map { it.text })
        assertNotEquals("两条消息必须落在不同的 dayKey 上", MessageStore.dayKeyOf(keep), MessageStore.dayKeyOf(drop))
    }

    @Test
    fun `deleteDate 能用 DAY_KEY_UNKNOWN 删掉未标注日期的消息`() {
        core.append(msg("有时间", 1_700_000_000_000L))
        core.append(msg("没时间", 0L))

        val removed = core.deleteDate(ConvKey.legacy("张三"), MessageStore.DAY_KEY_UNKNOWN)

        assertEquals(1, removed)
        assertEquals(listOf("有时间"), core.readRecent().map { it.text })
    }

    @Test
    fun `deleteMessages 按完整字段匹配且重复消息只删选中条数`() {
        val dup = msg("同样的话", 1_000L)
        core.append(dup)
        core.append(dup.copy())
        core.append(msg("另一条", 2_000L))

        // 界面上多选了一条 → 只应删掉一条，而不是把两条重复的都删了
        assertEquals(1, core.deleteMessages(listOf(dup)))
        assertEquals(listOf("另一条", "同样的话"), core.readRecent().map { it.text })
    }

    @Test
    fun `deleteMessages 选中两条重复消息时两条都删`() {
        val dup = msg("同样的话", 1_000L)
        core.append(dup)
        core.append(dup.copy())

        assertEquals(2, core.deleteMessages(listOf(dup, dup.copy())))
        assertEquals(emptyList<ChatMessage>(), core.readRecent())
    }

    @Test
    fun `deleteMessages 空集合直接返回 0 不碰文件`() {
        core.append(msg("a1", 1_000L))
        val before = dataFile.lastModified()

        assertEquals(0, core.deleteMessages(emptyList()))

        assertEquals(before, dataFile.lastModified())
    }

    @Test
    fun `deleteMessages 时间戳不同不算同一条`() {
        core.append(msg("同样的话", 1_000L))
        assertEquals(0, core.deleteMessages(listOf(msg("同样的话", 2_000L))))
    }

    // ---------------- merge 去重 ----------------

    @Test
    fun `merge 按秒级时间戳去重`() {
        // 通知捕获取通知发布时间、数据库提取取 createTime，同一消息毫秒值可能不同
        core.append(msg("会议改到三点", 1_700_000_000_123L))

        val added = core.merge(listOf(msg("会议改到三点", 1_700_000_000_987L)))

        assertEquals("同一秒内的同文本消息应判为重复", 0, added)
        assertEquals(1, core.readRecent().size)
    }

    @Test
    fun `merge 不同秒的同文本消息算新增`() {
        core.append(msg("好的", 1_700_000_000_000L))
        assertEquals(1, core.merge(listOf(msg("好的", 1_700_000_001_000L))))
        assertEquals(2, core.readRecent().size)
    }

    @Test
    fun `merge 不同会话或不同发送者的同文本算新增`() {
        core.append(msg("好的", 1_000L, conversation = "张三", sender = "张三"))
        assertEquals(
            2,
            core.merge(
                listOf(
                    msg("好的", 1_000L, conversation = "李四", sender = "李四"),
                    msg("好的", 1_000L, conversation = "项目群", sender = "王五", isGroup = true)
                )
            )
        )
    }

    @Test
    fun `merge 对 a11y 直读来源忽略时间戳去重`() {
        // 无障碍直读的时间戳是估算值，每次运行都变；不忽略就会反复插入重复消息
        core.append(msg("明天开会", 1_700_000_000_000L, source = "a11y-extract"))

        val added = core.mergeAndCollect(
            listOf(msg("明天开会", 1_799_999_999_999L, source = "a11y-extract"))
        )

        assertEquals(emptyList<ChatMessage>(), added)
        assertEquals(1, core.readRecent().size)
    }

    @Test
    fun `merge 跳过会话名或文本为空的条目`() {
        val added = core.mergeAndCollect(
            listOf(
                msg("", 1_000L, conversation = "张三"),
                msg("有内容", 2_000L, conversation = "  "),
                msg("正常", 3_000L, conversation = "张三")
            )
        )
        assertEquals(listOf("正常"), added.map { it.text })
        assertEquals(1, core.readRecent().size)
    }

    @Test
    fun `merge 空列表不创建文件不通知`() {
        assertEquals(0, core.merge(emptyList()))
        assertFalse(dataFile.exists())
        assertEquals(0, changeCount)
    }

    @Test
    fun `merge 批次内部也去重`() {
        val m = msg("重复的", 1_000L)
        assertEquals(1, core.merge(listOf(m, m.copy(), m.copy())))
    }

    @Test
    fun `merge 与已存在的损坏行共存不抛异常`() {
        writeRaw("""{坏行""")
        assertEquals(1, core.merge(listOf(msg("新消息", 1_000L))))
        assertTrue(dataFile.readLines().contains("""{坏行"""))
    }

    // ---------------- 清空与通知 ----------------

    @Test
    fun `clear 删除数据文件并通知`() {
        core.append(msg("a1", 1_000L))
        core.clear()
        assertFalse(dataFile.exists())
        assertEquals(emptyList<ChatMessage>(), core.readRecent())
        assertEquals(2, changeCount) // append 一次 + clear 一次
    }

    @Test
    fun `append 每次通知一次`() {
        core.append(msg("a1", 1_000L))
        core.append(msg("a2", 2_000L))
        assertEquals(2, changeCount)
    }

    // ---------------- 序列化契约 ----------------

    @Test
    fun `toJson 往返保留全部字段`() {
        val m = msg("明天十点评审", 1_700_000_000_000L, conversation = "项目群", sender = "李四", isGroup = true, source = "a11y-extract")
        val back = ChatMessage.fromJson(m.toJson())
        assertEquals(m, back)
    }

    @Test
    fun `toJson 来源为空时不写 source 字段`() {
        // 空来源要走服务端默认口径（通知监听），显式写空串会覆盖默认值
        val json = msg("hi", 1_000L).toJson()
        assertFalse("source 为空时不该出现在 JSON 里", json.has("source"))
        assertEquals("", ChatMessage.fromJson(json).source)
    }

    @Test
    fun `fromJson 缺字段时用默认值不抛异常`() {
        val m = ChatMessage.fromJson(JSONObject("""{"text":"只有文本"}"""))
        assertEquals("只有文本", m.text)
        assertEquals("", m.sender)
        assertEquals(0L, m.timestamp)
        assertFalse(m.isGroup)
    }

    // ---------------- dayKeyOf ----------------

    @Test
    fun `dayKeyOf 对正时间戳给出 yyyy-MM-dd 对非正给 UNKNOWN`() {
        assertEquals(MessageStore.DAY_KEY_UNKNOWN, MessageStore.dayKeyOf(msg("x", 0L)))
        assertEquals(MessageStore.DAY_KEY_UNKNOWN, MessageStore.dayKeyOf(msg("x", -5L)))

        val key = MessageStore.dayKeyOf(msg("x", 1_700_000_000_000L))
        assertTrue("dayKey 应当是 yyyy-MM-dd 形态，实际 $key", Regex("""\d{4}-\d{2}-\d{2}""").matches(key))
        assertEquals("同一时间戳的 dayKey 必须稳定", key, MessageStore.dayKeyOf(msg("y", 1_700_000_000_000L)))
    }
}
