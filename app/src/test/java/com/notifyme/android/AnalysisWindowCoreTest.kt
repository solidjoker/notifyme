// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

// 分析记录按会话取窗口（AnalysisWindowCore + AnalysisWindowFile）单测。
class AnalysisWindowCoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun rec(
        caseId: String,
        conversation: String = "甲项目群",
        pkg: String = "com.tencent.mm",
        windowEnd: Long = 1000L,
        analyzedAt: Long = windowEnd,
        needAction: Boolean = false,
        todoText: String = ""
    ): AnalysisCaseRecord = AnalysisCaseRecord(
        caseId = caseId,
        conversation = conversation,
        windowEnd = windowEnd,
        messageCount = 3,
        analyzedAt = analyzedAt,
        protocol = "laya",
        rawJson = "{}",
        pkg = pkg,
        s1NeedAction = needAction,
        s1NeedActionProb = 0.2,
        s1Importance = 5.0,
        s1DueWindow = "",
        s1Topic = "话题",
        s1Confidence = 0.8,
        escalated = false,
        s2Summary = "",
        s2DueTime = "",
        s2SuggestedAction = "",
        s2Tasks = emptyList(),
        todoText = todoText
    )

    private fun writeFile(records: List<AnalysisCaseRecord>): File {
        val file = tmp.newFile("analysis.jsonl")
        file.writeText(
            records.joinToString("") { it.toJson().toString() + "\n" },
            Charsets.UTF_8
        )
        return file
    }

    // ---------- group ----------

    @Test
    fun `空输入返回空表`() {
        assertTrue(AnalysisWindowCore.group(emptyList()).isEmpty())
    }

    @Test
    fun `按会话分组且组内按窗口末时间升序`() {
        val groups = AnalysisWindowCore.group(
            listOf(
                rec("a3", windowEnd = 3000L),
                rec("a1", windowEnd = 1000L),
                rec("a2", windowEnd = 2000L)
            )
        )
        assertEquals(1, groups.size)
        assertEquals(listOf("a1", "a2", "a3"), groups.values.single().map { it.caseId })
    }

    @Test
    fun `同名会话在不同 App 里分开`() {
        val groups = AnalysisWindowCore.group(
            listOf(
                rec("w1", conversation = "工作群", pkg = "com.tencent.mm"),
                rec("f1", conversation = "工作群", pkg = "com.ss.android.lark")
            )
        )
        assertEquals(2, groups.size)
        assertEquals(
            listOf("w1"),
            groups[ConvKey("com.tencent.mm", "工作群")]?.map { it.caseId }
        )
        assertEquals(
            listOf("f1"),
            groups[ConvKey("com.ss.android.lark", "工作群")]?.map { it.caseId }
        )
    }

    @Test
    fun `分组顺序保持首次出现序`() {
        val groups = AnalysisWindowCore.group(
            listOf(
                rec("b1", conversation = "乙群"),
                rec("a1", conversation = "甲群"),
                rec("b2", conversation = "乙群")
            )
        )
        assertEquals(listOf("乙群", "甲群"), groups.keys.map { it.conversation })
    }

    @Test
    fun `同一份输入两次分组结果一致`() {
        val records = listOf(
            rec("b2", conversation = "乙群", windowEnd = 20L),
            rec("a1", conversation = "甲群", windowEnd = 10L),
            rec("b1", conversation = "乙群", windowEnd = 10L)
        )
        val first = AnalysisWindowCore.group(records)
        val second = AnalysisWindowCore.group(records)
        assertEquals(first.keys.toList(), second.keys.toList())
        assertEquals(
            first.values.map { list -> list.map { it.caseId } },
            second.values.map { list -> list.map { it.caseId } }
        )
    }

    // ---------- latest / latestByConvKey ----------

    @Test
    fun `latest 取组内窗口末时间最大的一条`() {
        val latest = AnalysisWindowCore.latest(
            AnalysisWindowCore.group(
                listOf(
                    rec("a1", windowEnd = 1000L, analyzedAt = 9_000L),
                    rec("a2", windowEnd = 2000L, analyzedAt = 1_000L)
                )
            )
        )
        // 按 windowEnd 而不是 analyzedAt 判定「最新窗口」：重跑旧窗口不算更新
        assertEquals("a2", latest.values.single().caseId)
    }

    @Test
    fun `latest 空输入返回空表`() {
        assertTrue(AnalysisWindowCore.latest(emptyMap()).isEmpty())
    }

    @Test
    fun `latestByConvKey 直接吃原始记录`() {
        val latest = AnalysisWindowCore.latestByConvKey(
            listOf(
                rec("a1", windowEnd = 1000L),
                rec("a2", windowEnd = 3000L),
                rec("b1", conversation = "乙群", windowEnd = 5000L)
            )
        )
        assertEquals("a2", latest[ConvKey("com.tencent.mm", "甲项目群")]?.caseId)
        assertEquals("b1", latest[ConvKey("com.tencent.mm", "乙群")]?.caseId)
    }

    @Test
    fun `同一窗口末时间取最后写入的一条`() {
        val latest = AnalysisWindowCore.latestByConvKey(
            listOf(
                rec("old", windowEnd = 1000L, todoText = "旧"),
                rec("new", windowEnd = 1000L, todoText = "新")
            )
        )
        assertEquals("new", latest.values.single().caseId)
    }

    // ---------- forConversation ----------

    @Test
    fun `forConversation 只回本会话并按分析时间倒序`() {
        val records = listOf(
            rec("a1", windowEnd = 1000L, analyzedAt = 3_000L),
            rec("b1", conversation = "乙群", analyzedAt = 4_000L),
            rec("a2", windowEnd = 2000L, analyzedAt = 5_000L)
        )
        val hit = AnalysisWindowCore.forConversation(records, ConvKey("com.tencent.mm", "甲项目群"))
        assertEquals(listOf("a2", "a1"), hit.map { it.caseId })
    }

    @Test
    fun `forConversation 支持条数上限`() {
        val records = (1..5).map { rec("a$it", windowEnd = it * 1000L, analyzedAt = it * 1000L) }
        val hit = AnalysisWindowCore.forConversation(
            records,
            ConvKey("com.tencent.mm", "甲项目群"),
            limit = 2
        )
        assertEquals(listOf("a5", "a4"), hit.map { it.caseId })
    }

    @Test
    fun `forConversation 的 limit 为 0 时返回空表`() {
        val records = listOf(rec("a1"))
        assertTrue(
            AnalysisWindowCore.forConversation(records, ConvKey("com.tencent.mm", "甲项目群"), 0)
                .isEmpty()
        )
    }

    @Test
    fun `forConversation 负 limit 表示不限条数`() {
        val records = (1..5).map { rec("a$it", windowEnd = it * 1000L, analyzedAt = it * 1000L) }
        assertEquals(
            5,
            AnalysisWindowCore.forConversation(
                records,
                ConvKey("com.tencent.mm", "甲项目群"),
                AnalysisWindowCore.UNLIMITED
            ).size
        )
    }

    // ---------- recent ----------

    @Test
    fun `recent 全局按分析时间倒序取前 N 条`() {
        val records = listOf(
            rec("a1", analyzedAt = 1_000L),
            rec("b1", conversation = "乙群", analyzedAt = 3_000L),
            rec("c1", conversation = "丙群", analyzedAt = 2_000L)
        )
        assertEquals(listOf("b1", "c1"), AnalysisWindowCore.recent(records, 2).map { it.caseId })
    }

    @Test
    fun `recent 的 limit 非正时返回空表`() {
        val records = listOf(rec("a1"))
        assertTrue(AnalysisWindowCore.recent(records, 0).isEmpty())
        assertTrue(AnalysisWindowCore.recent(records, -1).isEmpty())
    }

    // ---------- 文件级 ----------

    @Test
    fun `文件不存在时返回空结果`() {
        val file = AnalysisWindowFile(JsonlStore(File(tmp.root, "missing.jsonl")))
        assertTrue(file.readAll().isEmpty())
        assertTrue(file.group().isEmpty())
        assertTrue(file.latestByConvKey().isEmpty())
        assertTrue(file.forConversation(ConvKey("com.tencent.mm", "甲项目群")).isEmpty())
        assertTrue(file.recent().isEmpty())
    }

    @Test
    fun `文件级 group 与 latestByConvKey 能读到尾部窗口之外的安静会话`() {
        // 600 条噪声占满旧实现的 readRecent(200) 尾部窗口，本会话 3 条在最前面
        val noise = (1..600).map {
            rec(
                caseId = "noise$it",
                conversation = "乙闲聊群",
                windowEnd = 1_000L + it,
                analyzedAt = 10_000L + it
            )
        }
        val mine = (1..3).map {
            rec(
                caseId = "mine$it",
                conversation = "甲项目群",
                windowEnd = 100L * it,
                analyzedAt = 100L * it
            )
        }
        val file = writeFile(mine + noise)
        val store = JsonlStore(file)

        // 旧写法：尾部 200 行里一条本会话的都看不到
        val oldWindow = store.readTailLines(200).mapNotNull {
            try {
                AnalysisCaseRecord.fromJson(JSONObject(it))
            } catch (e: Exception) {
                null
            }
        }
        assertTrue(oldWindow.none { it.conversation == "甲项目群" })

        // 新写法：放大窗口后本会话的 case 全在
        val window = AnalysisWindowFile(store)
        val groups = window.group()
        assertEquals(3, groups[ConvKey("com.tencent.mm", "甲项目群")]?.size)
        assertEquals("mine3", window.latestByConvKey()[ConvKey("com.tencent.mm", "甲项目群")]?.caseId)
    }

    @Test
    fun `文件级 readAll 跳过损坏行与旧格式行`() {
        val file = tmp.newFile("broken.jsonl")
        val good = rec("good", windowEnd = 1000L).toJson().toString()
        file.writeText(
            listOf(
                good,
                "not json at all",
                "{}", // 旧格式：没有 case_id
                "{\"case_id\":\"\"}", // 空 case_id 同样跳过
                rec("good2", windowEnd = 2000L).toJson().toString()
            ).joinToString("\n") + "\n",
            Charsets.UTF_8
        )
        val records = AnalysisWindowFile(JsonlStore(file)).readAll()
        assertEquals(listOf("good", "good2"), records.map { it.caseId })
    }

    @Test
    fun `文件级 forConversation 只回本会话且不受全局条数限制`() {
        val mine = (1..40).map { rec("mine$it", windowEnd = 1000L * it, analyzedAt = 1000L * it) }
        val other = (1..40).map {
            rec("other$it", conversation = "乙群", windowEnd = 1000L * it, analyzedAt = 1000L * it)
        }
        val file = writeFile(mine + other)
        val hit = AnalysisWindowFile(JsonlStore(file))
            .forConversation(ConvKey("com.tencent.mm", "甲项目群"))
        assertEquals(40, hit.size)
        assertEquals("mine40", hit.first().caseId)
        assertTrue(hit.all { it.conversation == "甲项目群" })
    }

    @Test
    fun `文件级 recent 按分析时间倒序截断`() {
        val records = listOf(
            rec("a1", analyzedAt = 1_000L),
            rec("b1", conversation = "乙群", analyzedAt = 3_000L),
            rec("c1", conversation = "丙群", analyzedAt = 2_000L)
        )
        val file = writeFile(records)
        assertEquals(
            listOf("b1", "c1"),
            AnalysisWindowFile(JsonlStore(file)).recent(2).map { it.caseId }
        )
    }

    @Test
    fun `文件级 group 里组内升序即窗口推进顺序`() {
        val records = listOf(
            rec("a3", windowEnd = 3000L),
            rec("a1", windowEnd = 1000L),
            rec("a2", windowEnd = 2000L)
        )
        val file = writeFile(records)
        val list = AnalysisWindowFile(JsonlStore(file)).group().values.single()
        assertEquals(listOf(1000L, 2000L, 3000L), list.map { it.windowEnd })
    }

    @Test
    fun `文件级 latestByConvKey 在多会话下各取一条`() {
        val records = listOf(
            rec("a1", windowEnd = 1000L),
            rec("a2", windowEnd = 2000L),
            rec("b1", conversation = "乙群", windowEnd = 9000L),
            rec("c1", conversation = "丙群", windowEnd = 500L)
        )
        val file = writeFile(records)
        val latest = AnalysisWindowFile(JsonlStore(file)).latestByConvKey()
        assertEquals(3, latest.size)
        assertEquals("a2", latest[ConvKey("com.tencent.mm", "甲项目群")]?.caseId)
        assertEquals("b1", latest[ConvKey("com.tencent.mm", "乙群")]?.caseId)
        assertEquals("c1", latest[ConvKey("com.tencent.mm", "丙群")]?.caseId)
    }

    @Test
    fun `常量的量级关系必须保持`() {
        // 展示窗口 > 轮询窗口 > 列表条数：轮询更便宜但必须比旧实现的 500 宽
        assertTrue(AnalysisWindowCore.POLL_LINES > 500)
        assertTrue(AnalysisWindowCore.TAIL_LINES >= AnalysisWindowCore.POLL_LINES)
        assertTrue(AnalysisWindowCore.LIST_LIMIT > 0)
        assertTrue(AnalysisWindowCore.UNLIMITED < 0)
        assertFalse(AnalysisWindowCore.TAIL_LINES > 50_000)
    }
}
