// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TopicLabelCore 的口径守门：
 *  - 英文码值与中文桶名双向归一（改前四处实现互不一致，模型直接抄中文时部分页面把「本周」当未知值）；
 *  - 未知值一律原样（不进默认桶），filtered 必须落到「已预筛过滤」而不是英文原文；
 *  - none / 空串 = 没有截止（调用方据此隐藏整段，别再拿码值比中文串）；
 *  - 资源 id 与码值一一对应、topic 与 due 两个命名空间不串。
 */
class TopicLabelCoreTest {

    // ---------- normalizeTopic ----------

    @Test
    fun topicCodeIsIdempotent() {
        for (code in TopicLabelCore.TOPIC_CODES) {
            assertEquals(code, TopicLabelCore.normalizeTopic(code))
        }
    }

    @Test
    fun topicCodeCaseInsensitive() {
        assertEquals(TopicLabelCore.TOPIC_WORK_TASK, TopicLabelCore.normalizeTopic("Work_Task"))
        assertEquals(TopicLabelCore.TOPIC_RISK, TopicLabelCore.normalizeTopic("RISK"))
        assertEquals(TopicLabelCore.DUE_TODAY, TopicLabelCore.normalizeDue("Today"))
    }

    @Test
    fun topicCodeTrimsWhitespace() {
        assertEquals(TopicLabelCore.TOPIC_NOTICE, TopicLabelCore.normalizeTopic("  notice\t"))
        assertEquals(TopicLabelCore.TOPIC_SMALLTALK, TopicLabelCore.normalizeTopic("\nsmalltalk "))
    }

    @Test
    fun topicChineseBucketNamesNormalize() {
        assertEquals(TopicLabelCore.TOPIC_WORK_TASK, TopicLabelCore.normalizeTopic("工作任务"))
        assertEquals(TopicLabelCore.TOPIC_SCHEDULE, TopicLabelCore.normalizeTopic("日程安排"))
        assertEquals(TopicLabelCore.TOPIC_SCHEDULE, TopicLabelCore.normalizeTopic("日程"))
        assertEquals(TopicLabelCore.TOPIC_NOTICE, TopicLabelCore.normalizeTopic("通知公告"))
        assertEquals(TopicLabelCore.TOPIC_SMALLTALK, TopicLabelCore.normalizeTopic("闲聊"))
        assertEquals(TopicLabelCore.TOPIC_RISK, TopicLabelCore.normalizeTopic("风险信号"))
    }

    @Test
    fun topicFilteredNormalizesAndIsNotTreatedAsUnknown() {
        assertEquals(TopicLabelCore.TOPIC_FILTERED, TopicLabelCore.normalizeTopic("filtered"))
        assertEquals(TopicLabelCore.TOPIC_FILTERED, TopicLabelCore.normalizeTopic("已预筛过滤"))
        assertEquals(R.string.topic_filtered, TopicLabelCore.topicRes("filtered"))
    }

    @Test
    fun topicUnknownPassesThroughVerbatim() {
        assertEquals("\u5efa\u8bae\u770b\u4e00\u4e0b", TopicLabelCore.normalizeTopic("建议看一下"))
        assertEquals("weird_code", TopicLabelCore.normalizeTopic("weird_code"))
        assertNull(TopicLabelCore.topicRes("weird_code"))
    }

    @Test
    fun topicEmptyOrNullBecomesEmptyString() {
        assertEquals("", TopicLabelCore.normalizeTopic(null))
        assertEquals("", TopicLabelCore.normalizeTopic(""))
        assertEquals("", TopicLabelCore.normalizeTopic("   "))
    }

    // ---------- normalizeDue ----------

    @Test
    fun dueCodeIsIdempotent() {
        for (code in TopicLabelCore.DUE_CODES) {
            assertEquals(code, TopicLabelCore.normalizeDue(code))
        }
        assertEquals(TopicLabelCore.DUE_NONE, TopicLabelCore.normalizeDue(TopicLabelCore.DUE_NONE))
    }

    @Test
    fun dueChineseBucketNamesNormalize() {
        assertEquals(TopicLabelCore.DUE_NONE, TopicLabelCore.normalizeDue("无明确时间"))
        assertEquals(TopicLabelCore.DUE_NONE, TopicLabelCore.normalizeDue("没有明确时间"))
        assertEquals(TopicLabelCore.DUE_TODAY, TopicLabelCore.normalizeDue("今天"))
        assertEquals(TopicLabelCore.DUE_TODAY, TopicLabelCore.normalizeDue("今日"))
        assertEquals(TopicLabelCore.DUE_TOMORROW, TopicLabelCore.normalizeDue("明天"))
        assertEquals(TopicLabelCore.DUE_THIS_WEEK, TopicLabelCore.normalizeDue("本周"))
        assertEquals(TopicLabelCore.DUE_THIS_WEEK, TopicLabelCore.normalizeDue("本周内"))
        assertEquals(TopicLabelCore.DUE_LATER, TopicLabelCore.normalizeDue("更晚"))
    }

    @Test
    fun dueConcreteTimeIsNotABucket() {
        // 用户手写的具体时间是自由文本，归一化必须原样保留（否则编辑框回填会被改写）
        assertEquals("明天15:00", TopicLabelCore.normalizeDue("明天15:00"))
        assertFalse(TopicLabelCore.isBucket("明天15:00"))
        assertEquals("2026-11-20", TopicLabelCore.normalizeDue("2026-11-20"))
    }

    @Test
    fun dueEmptyOrNullBecomesEmptyString() {
        assertEquals("", TopicLabelCore.normalizeDue(null))
        assertEquals("", TopicLabelCore.normalizeDue("  "))
    }

    // ---------- 资源 id 映射 ----------

    @Test
    fun topicResMapsEveryCode() {
        assertEquals(R.string.topic_work_task, TopicLabelCore.topicRes(TopicLabelCore.TOPIC_WORK_TASK))
        assertEquals(R.string.topic_schedule, TopicLabelCore.topicRes(TopicLabelCore.TOPIC_SCHEDULE))
        assertEquals(R.string.topic_notice, TopicLabelCore.topicRes(TopicLabelCore.TOPIC_NOTICE))
        assertEquals(R.string.topic_smalltalk, TopicLabelCore.topicRes(TopicLabelCore.TOPIC_SMALLTALK))
        assertEquals(R.string.topic_risk, TopicLabelCore.topicRes(TopicLabelCore.TOPIC_RISK))
    }

    @Test
    fun topicResAcceptsChineseBucketNames() {
        assertEquals(R.string.topic_work_task, TopicLabelCore.topicRes("工作任务"))
        assertEquals(R.string.due_this_week, TopicLabelCore.dueRes("本周"))
    }

    @Test
    fun dueResMapsEveryCode() {
        assertEquals(R.string.due_none, TopicLabelCore.dueRes(TopicLabelCore.DUE_NONE))
        assertEquals(R.string.due_today, TopicLabelCore.dueRes(TopicLabelCore.DUE_TODAY))
        assertEquals(R.string.due_tomorrow, TopicLabelCore.dueRes(TopicLabelCore.DUE_TOMORROW))
        assertEquals(R.string.due_this_week, TopicLabelCore.dueRes(TopicLabelCore.DUE_THIS_WEEK))
        assertEquals(R.string.due_later, TopicLabelCore.dueRes(TopicLabelCore.DUE_LATER))
    }

    @Test
    fun topicAndDueNamespacesDoNotCollide() {
        for (code in TopicLabelCore.TOPIC_CODES) {
            assertNull("due 命名空间不该认 topic 码值: " + code, TopicLabelCore.dueRes(code))
        }
        for (code in TopicLabelCore.DUE_CODES) {
            assertNull("topic 命名空间不该认 due 码值: " + code, TopicLabelCore.topicRes(code))
        }
        assertNull(TopicLabelCore.topicRes(TopicLabelCore.DUE_NONE))
    }

    @Test
    fun resourceIdsAreDistinct() {
        val ids = TopicLabelCore.TOPIC_CODES.mapNotNull { TopicLabelCore.topicRes(it) } +
            TopicLabelCore.DUE_CODES.mapNotNull { TopicLabelCore.dueRes(it) } +
            listOfNotNull(TopicLabelCore.dueRes(TopicLabelCore.DUE_NONE)) +
            listOfNotNull(TopicLabelCore.topicRes(TopicLabelCore.TOPIC_FILTERED))
        assertEquals(11, ids.size)
        assertEquals(11, ids.toSet().size)
    }

    @Test
    fun codeListsHaveExpectedSize() {
        assertEquals(5, TopicLabelCore.TOPIC_CODES.size)
        assertEquals(4, TopicLabelCore.DUE_CODES.size)
        assertTrue(TopicLabelCore.TOPIC_CODES.contains(TopicLabelCore.TOPIC_RISK))
        assertFalse(TopicLabelCore.TOPIC_CODES.contains(TopicLabelCore.TOPIC_FILTERED))
        assertFalse(TopicLabelCore.DUE_CODES.contains(TopicLabelCore.DUE_NONE))
    }

    // ---------- isNoDue ----------

    @Test
    fun isNoDueCoversNoneBucketAndBlank() {
        assertTrue(TopicLabelCore.isNoDue(null))
        assertTrue(TopicLabelCore.isNoDue(""))
        assertTrue(TopicLabelCore.isNoDue("   "))
        assertTrue(TopicLabelCore.isNoDue(TopicLabelCore.DUE_NONE))
        assertTrue(TopicLabelCore.isNoDue("none"))
        assertTrue(TopicLabelCore.isNoDue("无明确时间"))
    }

    @Test
    fun isNoDueRejectsRealBucketsAndConcreteTimes() {
        assertFalse(TopicLabelCore.isNoDue(TopicLabelCore.DUE_TODAY))
        assertFalse(TopicLabelCore.isNoDue("今天"))
        assertFalse(TopicLabelCore.isNoDue(TopicLabelCore.DUE_THIS_WEEK))
        assertFalse(TopicLabelCore.isNoDue("明天15:00"))
    }

    // ---------- isBucket ----------

    @Test
    fun isBucketRecognizesBothNamespaces() {
        assertTrue(TopicLabelCore.isBucket(TopicLabelCore.TOPIC_WORK_TASK))
        assertTrue(TopicLabelCore.isBucket("已预筛过滤"))
        assertTrue(TopicLabelCore.isBucket(TopicLabelCore.DUE_LATER))
        assertFalse(TopicLabelCore.isBucket("随便写点什么"))
        assertFalse(TopicLabelCore.isBucket(null))
        assertFalse(TopicLabelCore.isBucket(""))
    }

    // ---------- 归一化幂等 ----------

    @Test
    fun normalizationIsIdempotent() {
        val samples = listOf(
            "  Work_Task ", "\u5de5\u4f5c\u4efb\u52a1", "filtered", "weird_code", "none", "\u672c\u5468",
            "\u660e\u592915:00", "", "Today"
        )
        for (s in samples) {
            val once = TopicLabelCore.normalizeTopic(s)
            assertEquals("topic 二次归一必须稳定: " + s, once, TopicLabelCore.normalizeTopic(once))
            val dueOnce = TopicLabelCore.normalizeDue(s)
            assertEquals("due 二次归一必须稳定: " + s, dueOnce, TopicLabelCore.normalizeDue(dueOnce))
        }
    }

    @Test
    fun unknownTopicLabelHasNoResource() {
        // 未知值不能悄悄落进默认桶（那会让用户看不到模型到底给了什么）
        assertNull(TopicLabelCore.topicRes("\u65b0\u7c7b\u578b"))
        assertNotEquals(R.string.topic_notice, TopicLabelCore.topicRes("\u65b0\u7c7b\u578b"))
    }

    // ---------- displayDueCode / hasDisplayDue（首页待办、消息卡、提醒链共用） ----------

    @Test
    fun displayDuePrefersUserEditedTodoDue() {
        assertEquals("明天15:00", TopicLabelCore.displayDueCode("明天15:00", "后天", "this_week"))
        // displayDueCode 只挑「该显示哪一份」，归一化留给 dueRes / dueLabel：返回原样文本
        assertEquals("今天", TopicLabelCore.displayDueCode("今天", "", "later"))
        assertEquals(TopicLabelCore.DUE_TODAY, TopicLabelCore.normalizeDue(TopicLabelCore.displayDueCode("今天", "", "later")))
    }

    @Test
    fun displayDueFallsBackToS2ThenS1() {
        assertEquals("2026-11-20 09:00", TopicLabelCore.displayDueCode("", "2026-11-20 09:00", "today"))
        assertEquals("本周", TopicLabelCore.displayDueCode(null, "  ", "本周"))
        assertEquals("", TopicLabelCore.displayDueCode(null, null, null))
    }

    @Test
    fun displayDueTrimsWhitespace() {
        assertEquals(TopicLabelCore.DUE_NONE, TopicLabelCore.displayDueCode("  none  ", null, null))
        assertEquals("明天15:00", TopicLabelCore.displayDueCode(" 明天15:00 ", null, null))
    }

    @Test
    fun hasDisplayDueHidesNoneAndBlank() {
        // 这就是首页消息卡旧 bug：displayDue() 返回 "none" 时拿码值比中文串永远不等 -> 渲染出「截止：none」
        assertFalse(TopicLabelCore.hasDisplayDue(null, null, null))
        assertFalse(TopicLabelCore.hasDisplayDue("", "", "none"))
        assertFalse(TopicLabelCore.hasDisplayDue(null, null, "无明确时间"))
    }

    @Test
    fun hasDisplayDueKeepsEveryRealBucketAndConcreteTime() {
        assertTrue(TopicLabelCore.hasDisplayDue(null, null, TopicLabelCore.DUE_TODAY))
        assertTrue(TopicLabelCore.hasDisplayDue(null, null, "今天"))
        assertTrue(TopicLabelCore.hasDisplayDue(null, null, TopicLabelCore.DUE_LATER))
        assertTrue(TopicLabelCore.hasDisplayDue(null, "明天15:00", TopicLabelCore.DUE_NONE))
    }

    @Test
    fun hasDisplayDueAndDisplayDueCodeAgree() {
        val samples = listOf(
            listOf<String?>(null, null, null),
            listOf<String?>("", "", "none"),
            listOf<String?>(null, null, "today"),
            listOf<String?>("今天", null, "none"),
            listOf<String?>(null, "2026-11-20", "this_week")
        )
        for (s in samples) {
            val shown = TopicLabelCore.hasDisplayDue(s[0], s[1], s[2])
            val code = TopicLabelCore.displayDueCode(s[0], s[1], s[2])
            assertEquals("hasDisplayDue 与 isNoDue(displayDueCode) 必须一致: " + s, shown, !TopicLabelCore.isNoDue(code))
        }
    }

    @Test
    fun displayDueCodeMatchesAnalysisStoreOrder() {
        // 顺序契约：todoDue -> s2DueTime -> s1DueWindow（与 AnalysisStore.displayDue 一致）
        assertEquals("a", TopicLabelCore.displayDueCode("a", "b", "c"))
        assertEquals("b", TopicLabelCore.displayDueCode("", "b", "c"))
        assertEquals("c", TopicLabelCore.displayDueCode("", "", "c"))
    }

    // ---------- displayTodoCode / todoLabel（待办文本三级回退，真机复现过代码值泄漏）----------

    @Test
    fun displayTodoCodePrefersManualThenS2ThenS1() {
        assertEquals("改过的", TopicLabelCore.displayTodoCode("改过的", "摘要", "work_task"))
        assertEquals("摘要", TopicLabelCore.displayTodoCode("", "摘要", "work_task"))
        assertEquals("work_task", TopicLabelCore.displayTodoCode("   ", "  ", "work_task"))
        assertEquals("", TopicLabelCore.displayTodoCode(null, null, null))
    }

    @Test
    fun displayTodoCodeTrimsEachLevel() {
        assertEquals("改过的", TopicLabelCore.displayTodoCode(" 改过的 ", "摘要", "work_task"))
        assertEquals("摘要", TopicLabelCore.displayTodoCode("", " 摘要 ", "work_task"))
        assertEquals("", TopicLabelCore.displayTodoCode("", "", ""))
    }

    /**
     * 真机复现（云手机 127.0.0.1:16384，预筛结案记录）：没有 S2 概括时 displayTodo() 回落到
     * s1Topic = "filtered"，旧实现把英文码值直接渲染到「最近待办」与消息卡；todoLabel 一级回落必须翻中文。
     */
    @Test
    fun filteredS1TopicIsLocalizedAtThirdLevel() {
        assertEquals(
            TopicLabelCore.TOPIC_FILTERED,
            TopicLabelCore.normalizeTopic(TopicLabelCore.displayTodoCode("", "", "filtered"))
        )
        assertEquals(TopicLabelCore.TOPIC_WORK_TASK, TopicLabelCore.normalizeTopic("work_task"))
        // 前两级是自由文本，即便内容恰好等于码值也原样返回（不能被再翻一次）
        assertEquals("filtered", TopicLabelCore.displayTodoCode("filtered", "", "work_task"))
    }

    @Test
    fun everyTopicCodeHasResource() {
        for (code in TopicLabelCore.TOPIC_CODES) {
            assertTrue("缺资源: $code", TopicLabelCore.topicRes(code) != null)
        }
        assertTrue(TopicLabelCore.topicRes(TopicLabelCore.TOPIC_FILTERED) != null)
    }
}
