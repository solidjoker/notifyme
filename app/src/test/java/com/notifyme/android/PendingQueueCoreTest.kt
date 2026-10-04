// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [PendingQueueCore] 的队列语义测试（pending.jsonl）。
 *
 * 这个文件是「待上报」的瞬态队列，删错条目的后果是消息永远不上报（静默丢数据），
 * 所以三条删除路径 + 自增 id 恢复都要有测试兜住。
 *
 * 其中一处**与 [MessageStoreCore] 的已知差异**在这里被显式记档：
 * 重写按「解析后的对象」重新序列化，所以损坏行会在重写时被丢弃
 * （MessageStore 的重写则原样保留损坏行）。差异是原实现就有的，M1 不擅自统一，
 * 留到 M2 改 schema 时一起决定——测试先把它钉住，避免以后无意改动。
 */
class PendingQueueCoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var dir: File
    private lateinit var core: PendingQueueCore

    private val dataFile: File get() = File(dir, "pending.jsonl")

    @Before
    fun setUp() {
        dir = tmp.newFolder("files")
        core = PendingQueue.coreIn(dir)
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

    private fun writeRaw(vararg lines: String) {
        dataFile.parentFile?.mkdirs()
        dataFile.writeText(lines.joinToString("") { "$it\n" })
    }

    private fun rawEntry(id: Long, text: String, ts: Long, conversation: String = "张三") =
        """{"id":$id,"sender":"张三","text":"$text","timestamp":$ts,"conversation":"$conversation","isGroup":false}"""

    // ---------------- 入队与 id ----------------

    @Test
    fun `append 从 1 开始分配自增 id`() {
        core.append(msg("a", 1_000L))
        core.append(msg("b", 2_000L))

        assertEquals(listOf(1L, 2L), core.readAll().map { it.id })
        assertEquals(listOf("a", "b"), core.readAll().map { it.message.text })
    }

    @Test
    fun `readAll 文件不存在返回空列表`() {
        assertEquals(emptyList<PendingMessage>(), core.readAll())
    }

    @Test
    fun `readAll 按 id 升序即使文件里是乱序`() {
        writeRaw(rawEntry(3, "c", 3_000L), rawEntry(1, "a", 1_000L), rawEntry(2, "b", 2_000L))
        assertEquals(listOf(1L, 2L, 3L), core.readAll().map { it.id })
    }

    @Test
    fun `新的 core 实例从文件恢复 id 继续递增（进程重启场景）`() {
        core.append(msg("a", 1_000L))
        core.append(msg("b", 2_000L))

        val restarted = PendingQueue.coreIn(dir)
        restarted.append(msg("c", 3_000L))

        assertEquals(
            "重启后 id 不该回到 1（否则 removeSent 会误删旧条目）",
            listOf(1L, 2L, 3L),
            restarted.readAll().map { it.id }
        )
    }

    @Test
    fun `clear 后 id 计数器归零`() {
        core.append(msg("a", 1_000L))
        core.append(msg("b", 2_000L))
        core.clear()

        assertFalse(dataFile.exists())
        core.append(msg("c", 3_000L))
        assertEquals(listOf(1L), core.readAll().map { it.id })
    }

    // ---------------- removeSent ----------------

    @Test
    fun `removeSent 只剔除已发送的 id 保留上报期间新入队的`() {
        core.append(msg("a", 1_000L))
        core.append(msg("b", 2_000L))
        core.append(msg("c", 3_000L))

        core.removeSent(setOf(1L, 2L))

        assertEquals(listOf(3L), core.readAll().map { it.id })
    }

    @Test
    fun `removeSent 全部发完则删除文件不留 0 字节`() {
        core.append(msg("a", 1_000L))
        core.removeSent(setOf(1L))
        assertFalse(dataFile.exists())
    }

    @Test
    fun `removeSent 未知 id 不影响现有条目`() {
        core.append(msg("a", 1_000L))
        core.removeSent(setOf(99L))
        assertEquals(listOf(1L), core.readAll().map { it.id })
    }

    // ---------------- 三条删除路径 ----------------

    @Test
    fun `removeByConversations 返回删除条数并保留其他会话`() {
        core.append(msg("a", 1_000L, conversation = "张三"))
        core.append(msg("b", 2_000L, conversation = "李四", sender = "李四"))
        core.append(msg("c", 3_000L, conversation = "张三"))

        assertEquals(2, core.removeByConversations(listOf(ConvKey.legacy("张三"), ConvKey.legacy("王五"))))
        assertEquals(listOf("b"), core.readAll().map { it.message.text })
    }

    @Test
    fun `removeByConversations 无命中返回 0 且不碰文件`() {
        core.append(msg("a", 1_000L))
        val before = dataFile.lastModified()

        assertEquals(0, core.removeByConversations(listOf(ConvKey.legacy("不存在"))))

        assertEquals(before, dataFile.lastModified())
        assertEquals(1, core.readAll().size)
    }

    @Test
    fun `removeByConversationDay 只删该会话该天的条目`() {
        // 相差一天而不是相差一分钟：dayKeyOf 按本地日历格式化，同一天的条目都会被删掉
        val keep = msg("别的天", 1_700_000_000_000L, conversation = "张三")
        val drop = msg("这天", 1_700_086_460_000L, conversation = "张三")
        val other = msg("别的会话", 1_700_086_460_000L, conversation = "李四", sender = "李四")
        core.append(keep)
        core.append(drop)
        core.append(other)

        assertEquals(1, core.removeByConversationDay(ConvKey.legacy("张三"), MessageStore.dayKeyOf(drop)))
        // readAll 按 id 升序，剩下的仍是写入顺序
        assertEquals(listOf("别的天", "别的会话"), core.readAll().map { it.message.text })
    }

    @Test
    fun `removeByConversationDay 支持 DAY_KEY_UNKNOWN`() {
        core.append(msg("有时间", 1_700_000_000_000L))
        core.append(msg("没时间", 0L))

        assertEquals(1, core.removeByConversationDay(ConvKey.legacy("张三"), MessageStore.DAY_KEY_UNKNOWN))
        assertEquals(listOf("有时间"), core.readAll().map { it.message.text })
    }

    @Test
    fun `removeByMessages 按完整字段匹配且重复消息只删选中条数`() {
        val dup = msg("同样的话", 1_000L)
        core.append(dup)
        core.append(dup.copy())
        core.append(msg("另一条", 2_000L))

        // 界面上多选了一条 → 只应删掉一条，而不是把两条重复的都删了
        assertEquals(1, core.removeByMessages(listOf(dup)))
        // readAll 按 id 升序：删掉的是先写入的那条，剩下的 dup 仍是 id=2，排在 另一条(id=3) 之前
        assertEquals(listOf("同样的话", "另一条"), core.readAll().map { it.message.text })
    }

    @Test
    fun `removeByMessages 空集合返回 0`() {
        core.append(msg("a", 1_000L))
        assertEquals(0, core.removeByMessages(emptyList()))
        assertEquals(1, core.readAll().size)
    }

    @Test
    fun `删除路径与 MessageStore 用同一份全字段键口径`() {
        // M2 给 ChatMessage 加 pkg 时，两个队列必须同步升级，否则会一边删得掉一边删不掉
        val m = msg("同样的话", 1_000L, conversation = "项目群", sender = "李四", isGroup = true, source = "a11y-extract")
        core.append(m)

        assertEquals(1, core.removeByMessages(listOf(m.copy())))
        assertEquals(emptyList<PendingMessage>(), core.readAll())
    }

    // ---------------- 损坏行（已知差异，显式记档） ----------------

    @Test
    fun `读取时跳过损坏行`() {
        writeRaw(rawEntry(1, "a", 1_000L), """{坏行""", rawEntry(2, "b", 2_000L))
        assertEquals(listOf(1L, 2L), core.readAll().map { it.id })
    }

    @Test
    fun `重写时损坏行会被丢弃（与 MessageStore 的已知差异）`() {
        writeRaw(rawEntry(1, "a", 1_000L, conversation = "张三"), """{坏行""", rawEntry(2, "b", 2_000L, conversation = "李四"))

        val removed = core.removeByConversations(listOf(ConvKey.legacy("张三")))

        assertEquals("损坏行不计入删除条数", 1, removed)
        assertFalse(
            "记录现状（M2 已拍板不统一）：PendingQueue 重写按解析后的对象重新序列化，损坏行不保留；" +
                "MessageStore 的重写则原样保留损坏行。理由：损坏队列条目永远发不出去，" +
                "保留只会反复搬运、越积越多。",
            dataFile.readLines().contains("""{坏行""")
        )
        assertEquals(listOf("b"), core.readAll().map { it.message.text })
    }

    @Test
    fun `全部删完时删除文件`() {
        core.append(msg("a", 1_000L))
        core.append(msg("b", 2_000L))
        assertEquals(2, core.removeByConversations(listOf(ConvKey.legacy("张三"))))
        assertFalse(dataFile.exists())
    }

    // ---------------- 序列化 ----------------

    @Test
    fun `PendingMessage toJson 在消息 JSON 上加 id 且能往返`() {
        val m = msg("明天开会", 1_700_000_000_000L, conversation = "项目群", sender = "李四", isGroup = true)
        val entry = PendingMessage(7L, m)
        val json = entry.toJson()

        assertEquals(7L, json.optLong("id"))
        assertEquals("明天开会", json.optString("text"))
        assertEquals(entry, PendingMessage.fromJson(json))
    }

    @Test
    fun `队列条目保留 source 字段`() {
        // a11y 直读的条目上报时要带来源标记，否则服务端会当成通知捕获口径
        core.append(msg("直读", 1_000L, source = "a11y-extract"))
        assertTrue(dataFile.readText().contains("a11y-extract"))
        assertEquals("a11y-extract", core.readAll().single().message.source)
    }
}
