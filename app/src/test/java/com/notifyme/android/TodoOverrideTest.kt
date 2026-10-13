// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * M12 待办手动覆盖字段（todo_text / todo_due）：
 * 序列化向后兼容 + 显示与日历文本的取值优先级。
 */
class TodoOverrideTest {

    private fun record(
        s2Summary: String = "",
        s1Topic: String = "话题",
        todoText: String = "",
        s2DueTime: String = "",
        s1DueWindow: String = "本周内",
        todoDue: String = ""
    ) = AnalysisCaseRecord(
        caseId = "c1",
        conversation = "张三",
        windowEnd = 100L,
        messageCount = 2,
        analyzedAt = 200L,
        protocol = "openai",
        rawJson = "{}",
        s1NeedAction = true,
        s1NeedActionProb = 0.9,
        s1Importance = 5.0,
        s1DueWindow = s1DueWindow,
        s1Topic = s1Topic,
        s1Confidence = 0.8,
        escalated = s2Summary.isNotEmpty(),
        s2Summary = s2Summary,
        s2DueTime = s2DueTime,
        s2SuggestedAction = "",
        s2Tasks = emptyList(),
        todoText = todoText,
        todoDue = todoDue
    )

    @Test
    fun displayTodoPrefersManualThenS2ThenS1() {
        assertEquals("覆盖文本", record(s2Summary = "摘要", s1Topic = "话题", todoText = "覆盖文本").displayTodo())
        assertEquals("摘要", record(s2Summary = "摘要", todoText = "").displayTodo())
        assertEquals("话题", record(s2Summary = "", s1Topic = "话题").displayTodo())
    }

    @Test
    fun displayDuePrefersManualThenS2ThenS1() {
        assertEquals("明天15:00", record(s2DueTime = "周五", todoDue = "明天15:00").displayDue())
        assertEquals("周五", record(s2DueTime = "周五", todoDue = "").displayDue())
        assertEquals("本周内", record(s2DueTime = "", s1DueWindow = "本周内").displayDue())
    }

    @Test
    fun toJsonOmitsEmptyOverrideKeysOnlyWritesWhenEdited() {
        val untouched = record(s2Summary = "摘要").toJson()
        assertFalse(untouched.has("todo_text"))
        assertFalse(untouched.has("todo_due"))

        val edited = record(s2Summary = "摘要", todoText = "改过的", todoDue = "今晚8点").toJson()
        assertEquals("改过的", edited.optString("todo_text"))
        assertEquals("今晚8点", edited.optString("todo_due"))
    }

    @Test
    fun fromJsonRoundTripKeepsOverridesAndOldRecordsDefaultToEmpty() {
        val edited = record(todoText = "改过的", todoDue = "今晚8点")
        val restored = AnalysisCaseRecord.fromJson(JSONObject(edited.toJson().toString()))!!
        assertEquals("改过的", restored.todoText)
        assertEquals("今晚8点", restored.todoDue)
        assertEquals("改过的", restored.displayTodo())

        // 老记录（无覆盖键）读入默认空串，显示走分析结论
        val legacy = JSONObject(record(s2Summary = "摘要").toJson().toString())
        legacy.remove("todo_text")
        legacy.remove("todo_due")
        val legacyRec = AnalysisCaseRecord.fromJson(legacy)!!
        assertEquals("", legacyRec.todoText)
        assertEquals("", legacyRec.todoDue)
        assertEquals("摘要", legacyRec.displayTodo())
    }
}
