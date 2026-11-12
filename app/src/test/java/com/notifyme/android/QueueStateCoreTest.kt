// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [QueueStateCore] 的 JVM 单测：排队任务的上报口径必须稳定。
 *
 * 这些规则直接决定服务端控制台「排队」tab 看到什么：
 * 非法枚举怎么退、超长怎么截、同 id 怎么去重、按什么顺序排。
 */
class QueueStateCoreTest {

    private fun task(
        id: String = "t1",
        kind: String = QueueStateCore.KIND_ANALYSIS,
        title: String = "分析会话",
        state: String = QueueStateCore.STATE_QUEUED,
        detail: String = "",
        queuedAt: Long = 1000L
    ) = QueueStateCore.QueueTask(id, kind, title, state, detail, queuedAt)

    @Test
    fun `状态归一化非法值退回排队中`() {
        assertEquals(QueueStateCore.STATE_QUEUED, QueueStateCore.normalizeState(null))
        assertEquals(QueueStateCore.STATE_QUEUED, QueueStateCore.normalizeState(""))
        assertEquals(QueueStateCore.STATE_QUEUED, QueueStateCore.normalizeState("weird"))
        assertEquals(QueueStateCore.STATE_RUNNING, QueueStateCore.normalizeState("running"))
        assertEquals(QueueStateCore.STATE_FAILED, QueueStateCore.normalizeState("failed"))
        assertEquals(QueueStateCore.STATE_DONE, QueueStateCore.normalizeState("done"))
    }

    @Test
    fun `类型归一化非法值退回其它`() {
        assertEquals(QueueStateCore.KIND_OTHER, QueueStateCore.normalizeKind(null))
        assertEquals(QueueStateCore.KIND_OTHER, QueueStateCore.normalizeKind("weird"))
        assertEquals(QueueStateCore.KIND_ANALYSIS, QueueStateCore.normalizeKind("analysis"))
        assertEquals(QueueStateCore.KIND_SYNC, QueueStateCore.normalizeKind("sync"))
        assertEquals(QueueStateCore.KIND_MODEL, QueueStateCore.normalizeKind("model"))
    }

    @Test
    fun `归一化丢弃空任务号`() {
        val out = QueueStateCore.normalize(listOf(task(id = ""), task(id = "   ")))
        assertTrue(out.isEmpty())
    }

    @Test
    fun `归一化丢弃超长任务号`() {
        val out = QueueStateCore.normalize(listOf(task(id = "x".repeat(129))))
        assertTrue(out.isEmpty())
        val ok = QueueStateCore.normalize(listOf(task(id = "x".repeat(128))))
        assertEquals(1, ok.size)
    }

    @Test
    fun `归一化退回非法类型与状态`() {
        val out = QueueStateCore.normalize(listOf(task(kind = "weird", state = "weird")))
        assertEquals(1, out.size)
        assertEquals(QueueStateCore.KIND_OTHER, out[0].kind)
        assertEquals(QueueStateCore.STATE_QUEUED, out[0].state)
    }

    @Test
    fun `空标题给占位名`() {
        val out = QueueStateCore.normalize(listOf(task(title = "   ")))
        assertEquals(QueueStateCore.DEFAULT_TITLE, out[0].title)
    }

    @Test
    fun `标题超长截断到256`() {
        val out = QueueStateCore.normalize(listOf(task(title = "标".repeat(300))))
        assertEquals(QueueStateCore.MAX_TITLE_LEN, out[0].title.length)
    }

    @Test
    fun `详情超长截断到512`() {
        val out = QueueStateCore.normalize(listOf(task(detail = "详".repeat(600))))
        assertEquals(QueueStateCore.MAX_DETAIL_LEN, out[0].detail.length)
    }

    @Test
    fun `负排队时间归零`() {
        val out = QueueStateCore.normalize(listOf(task(queuedAt = -5L)))
        assertEquals(0L, out[0].queuedAt)
    }

    @Test
    fun `同任务号只留一条且后到覆盖`() {
        val out = QueueStateCore.normalize(
            listOf(
                task(id = "same", title = "旧", state = QueueStateCore.STATE_QUEUED),
                task(id = "same", title = "新", state = QueueStateCore.STATE_RUNNING)
            )
        )
        assertEquals(1, out.size)
        assertEquals("新", out[0].title)
        assertEquals(QueueStateCore.STATE_RUNNING, out[0].state)
    }

    @Test
    fun `排序按状态展示序再按排队时间`() {
        val out = QueueStateCore.normalize(
            listOf(
                task(id = "done-1", state = QueueStateCore.STATE_DONE, queuedAt = 1L),
                task(id = "q-2", state = QueueStateCore.STATE_QUEUED, queuedAt = 20L),
                task(id = "r-1", state = QueueStateCore.STATE_RUNNING, queuedAt = 30L),
                task(id = "f-1", state = QueueStateCore.STATE_FAILED, queuedAt = 40L),
                task(id = "q-1", state = QueueStateCore.STATE_QUEUED, queuedAt = 10L)
            )
        )
        assertEquals(
            listOf("r-1", "q-1", "q-2", "f-1", "done-1"),
            out.map { it.taskId }
        )
    }

    @Test
    fun `报文含设备号与任务数组`() {
        val payload = JSONObject(
            QueueStateCore.buildPayload("android-1a2b3c4d", listOf(task()))
        )
        assertEquals("android-1a2b3c4d", payload.getString("device_id"))
        val arr = payload.getJSONArray("tasks")
        assertEquals(1, arr.length())
        val one = arr.getJSONObject(0)
        assertEquals("t1", one.getString("task_id"))
        assertEquals(QueueStateCore.KIND_ANALYSIS, one.getString("kind"))
        assertEquals("分析会话", one.getString("title"))
        assertEquals(QueueStateCore.STATE_QUEUED, one.getString("state"))
        assertEquals("", one.getString("detail"))
        assertEquals(1000L, one.getLong("queued_at"))
    }

    @Test
    fun `报文只含服务端认识的字段`() {
        val payload = JSONObject(QueueStateCore.buildPayload("android-x", listOf(task())))
        val one = payload.getJSONArray("tasks").getJSONObject(0)
        assertEquals(6, one.length())
        assertTrue(one.has("task_id"))
        assertTrue(one.has("kind"))
        assertTrue(one.has("title"))
        assertTrue(one.has("state"))
        assertTrue(one.has("detail"))
        assertTrue(one.has("queued_at"))
    }

    @Test
    fun `空任务列表报文仍是合法JSON`() {
        val payload = JSONObject(QueueStateCore.buildPayload("android-x", emptyList()))
        assertEquals("android-x", payload.getString("device_id"))
        assertEquals(0, payload.getJSONArray("tasks").length())
    }

    @Test
    fun `设备号由种子稳定派生`() {
        val a = QueueStateCore.deriveDeviceId("seed-1")
        val b = QueueStateCore.deriveDeviceId("seed-1")
        val c = QueueStateCore.deriveDeviceId("seed-2")
        assertEquals(a, b)
        assertTrue(a != c)
        assertTrue(a.startsWith("android-"))
        assertEquals(8, a.length - "android-".length)
        assertTrue(a.removePrefix("android-").all { it in "0123456789abcdef" })
    }

    @Test
    fun `空任务列表归一化结果为空`() {
        assertTrue(QueueStateCore.normalize(emptyList()).isEmpty())
    }

    @Test
    fun `同状态按排队时间升序`() {
        val out = QueueStateCore.normalize(
            listOf(
                task(id = "q-3", state = QueueStateCore.STATE_QUEUED, queuedAt = 30L),
                task(id = "q-1", state = QueueStateCore.STATE_QUEUED, queuedAt = 10L),
                task(id = "q-2", state = QueueStateCore.STATE_QUEUED, queuedAt = 20L)
            )
        )
        assertEquals(listOf("q-1", "q-2", "q-3"), out.map { it.taskId })
    }

    @Test
    fun `任务号前后空白被去掉`() {
        val out = QueueStateCore.normalize(listOf(task(id = "  t1  ")))
        assertEquals(1, out.size)
        assertEquals("t1", out[0].taskId)
    }

    @Test
    fun `标题与详情到上限不截断`() {
        val out = QueueStateCore.normalize(
            listOf(task(title = "标".repeat(256), detail = "详".repeat(512)))
        )
        assertEquals(256, out[0].title.length)
        assertEquals(512, out[0].detail.length)
    }

    @Test
    fun `报文字段顺序固定`() {
        // 字段顺序是服务端契约的一部分：顶层 device_id -> tasks，
        // 单条任务 task_id -> kind -> title -> state -> detail -> queued_at
        val raw = QueueStateCore.buildPayload("android-1a2b3c4d", listOf(task()))
        assertEquals(
            "{\"device_id\":\"android-1a2b3c4d\",\"tasks\":[" +
                "{\"task_id\":\"t1\",\"kind\":\"analysis\",\"title\":\"分析会话\"," +
                "\"state\":\"queued\",\"detail\":\"\",\"queued_at\":1000}]}",
            raw
        )
    }

    @Test
    fun `排队时间是数字不是字符串`() {
        val one = JSONObject(
            QueueStateCore.buildPayload("android-x", listOf(task()))
        ).getJSONArray("tasks").getJSONObject(0)
        assertTrue(one.get("queued_at") is Number)
        assertEquals(1000L, one.getLong("queued_at"))
    }

    @Test
    fun `报文里的引号与换行被正确转义`() {
        val tricky = "他说\"好\"\n换行\\反斜杠"
        val raw = QueueStateCore.buildPayload("android-x", listOf(task(title = tricky)))
        val one = JSONObject(raw).getJSONArray("tasks").getJSONObject(0)
        assertEquals(tricky, one.getString("title"))
    }

    @Test
    fun `多条任务按归一化后的顺序入报`() {
        val out = QueueStateCore.normalize(
            listOf(
                task(id = "d", state = QueueStateCore.STATE_DONE, queuedAt = 1L),
                task(id = "r", state = QueueStateCore.STATE_RUNNING, queuedAt = 2L)
            )
        )
        val arr = JSONObject(QueueStateCore.buildPayload("android-x", out)).getJSONArray("tasks")
        assertEquals("r", arr.getJSONObject(0).getString("task_id"))
        assertEquals("d", arr.getJSONObject(1).getString("task_id"))
    }
}
