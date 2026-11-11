// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 三条上报通道（/weixin、/analysis、/advisor）的**真实 HTTP 端到端**测试。
 *
 * 与已有的 [SyncOutcomeCoreTest] 的区别：那边是纯函数级（把状态码直接喂给
 * classify），这边是**真的起一个 HTTP 服务、真的发 HTTP 请求、真的收响应字节** ——
 * 覆盖的是「请求形态（方法 / 头 / 体）+ 响应分类 + 批次处置」这一整段链路。
 *
 * 夹具见 [LocalHttpFixture]（用 java.net.ServerSocket 自写的 HTTP/1.1 服务端，随机端口，真 socket；
 * JDK 自带的 com.sun.net.httpserver 在本仓库 Android 单测编译模型下不可见，见夹具文件头的说明）。
 * 分类、分批、二分、被拒匹配全部调用**生产代码**（[SyncOutcomeCore] / [SyncBatchCore]），
 * 不在测试里另写一套判定逻辑，避免测试与实现各说各话。
 *
 * 服务端契约对齐（server/app.py、server/routes.py、server/util.py）：
 *  - /weixin    逐条校验 sender/text/timestamp/conversation/is_group；缺字段回 207 + rejected 下标；
 *  - /analysis  需要 case_id / conversation；207 回 rejected + rejected_ids(=case_id)；
 *  - /advisor   需要 report_id；207 回 rejected + rejected_ids(=report_id)；
 *  - 401 鉴权失败、404 端点不存在、413 请求体超限（MAX_CONTENT_LENGTH = 8 MiB）、5xx 可重试。
 */
class SyncHttpEndToEndTest {

    /** 服务端逐条校验的必备字段（server/app.py:192-198 REQUIRED_FIELDS）。 */
    private val serverRequiredFields =
        listOf("sender", "text", "timestamp", "conversation", "is_group")

    /** 毒丸标记：请求体含它就模拟服务端 413（这条单条就超过服务端上限）。 */
    private val poisonMark = "POISON-ENTRY"

    // ---------------- 与生产一致的请求体构造 ----------------

    /** 消息通道上报片段：驼峰 isGroup 换成蛇形 is_group（SyncWorker.kt:231-235）。 */
    private fun wireMessage(msg: ChatMessage): String =
        msg.toJson().apply {
            remove("isGroup")
            put("is_group", msg.isGroup)
        }.toString()

    private fun messageItem(
        id: Long,
        sender: String = "张三",
        text: String = "你好",
        conversation: String = "会话A",
        isGroup: Boolean = false,
        timestamp: Long = 1_700_000_000_000L
    ): SyncBatchCore.Item = SyncBatchCore.Item(
        id,
        wireMessage(
            ChatMessage(
                sender = sender,
                text = text,
                timestamp = timestamp,
                conversation = conversation,
                isGroup = isGroup
            )
        )
    )

    /** 分析通道上报片段：去掉 raw_json，escalated=false 时补 s2=null（SyncWorker.kt:404-417）。 */
    private fun analysisJson(caseId: String, escalated: Boolean = false): String {
        val obj = JSONObject()
        obj.put("case_id", caseId)
        obj.put("conversation", "会话-" + caseId)
        obj.put("window_end", 1_700_000_000_000L)
        obj.put("message_count", 12)
        obj.put("analyzed_at", "2026-01-01T08:00:00")
        obj.put("protocol", "openai-v1")
        obj.put(
            "s1",
            JSONObject().apply {
                put(
                    "need_action",
                    JSONObject().apply {
                        put("value", true)
                        put("prob", 0.9)
                    }
                )
                put("importance", 0.8)
                put("due_window", "today")
                put("topic", "话题")
                put("confidence", 0.7)
            }
        )
        obj.put("escalated", escalated)
        obj.put("s2", JSONObject.NULL)
        return obj.toString()
    }

    // ---------------- 200：整批成功 ----------------

    @Test
    fun `200 整批成功 全部条目结算 请求带令牌且请求体符合服务端契约`() {
        val fixture = LocalHttpFixture {
            LocalHttpFixture.Reply(
                200,
                """{"ok":true,"received":2,"duplicated":0,"invalid":0}"""
            )
        }
        try {
            val client = SyncChannelClient(HttpReporter(fixture.baseUrl, token = "tok-abc"))
            val items = listOf(
                messageItem(1L, sender = "张三", text = "在吗"),
                messageItem(2L, sender = "李四", text = "在", isGroup = true)
            )

            val report = client.pushMessages("/weixin", items)

            assertEquals(listOf(SyncOutcome.ACCEPTED), report.outcomes)
            assertEquals(listOf(1L, 2L), report.accepted)
            assertTrue(report.rejected.isEmpty())
            assertTrue(report.isolated.isEmpty())
            assertFalse(report.retryNeeded)

            // 请求形态：POST + X-Token + application/json
            val req = fixture.recorded.single()
            assertEquals("POST", req.method)
            assertEquals("/weixin", req.path)
            assertEquals("tok-abc", req.headers["x-token"])
            assertTrue(
                "Content-Type 应为 application/json",
                req.headers["content-type"]!!.startsWith("application/json")
            )

            // 请求体是合法 JSON 数组，且每条都带齐服务端必备字段（类型也对）
            val arr = JSONArray(req.body)
            assertEquals(2, arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                for (field in serverRequiredFields) {
                    assertTrue("第 " + i + " 条缺少字段 " + field, obj.has(field))
                }
                assertTrue(obj.get("timestamp") is Number)
                assertTrue(obj.get("is_group") is Boolean)
                assertTrue(obj.getString("sender").isNotEmpty())
                assertTrue(obj.getString("text").isNotEmpty())
                assertTrue(obj.getString("conversation").isNotEmpty())
            }
            // 蛇形命名：本地驼峰键不能混进上报协议
            assertFalse(arr.getJSONObject(0).has("isGroup"))
        } finally {
            fixture.close()
        }
    }

    // ---------------- 207：部分成功 ----------------

    @Test
    fun `207 只结算被接受条目 被拒条目留待下轮 不整批当成功`() {
        val fixture = LocalHttpFixture {
            LocalHttpFixture.Reply(
                207,
                """{"ok":false,"received":2,"duplicated":0,"invalid":1,""" +
                    """"rejected":[1],"error":"some items were rejected"}"""
            )
        }
        try {
            val client = SyncChannelClient(HttpReporter(fixture.baseUrl, token = "tok-abc"))
            val items = listOf(
                messageItem(1L, text = "第一条"),
                messageItem(2L, text = "第二条（被拒）"),
                messageItem(3L, text = "第三条")
            )

            val report = client.pushMessages("/weixin", items)

            assertEquals(listOf(SyncOutcome.PARTIAL_REJECTED), report.outcomes)
            assertEquals(listOf(1L, 3L), report.accepted)
            assertEquals(listOf(2L), report.rejected)
            assertTrue(report.isolated.isEmpty())
            // 207 不触发整轮 retry（被拒条目下轮自然补报）
            assertFalse(report.retryNeeded)
            assertEquals(1, fixture.recorded.size)
        } finally {
            fixture.close()
        }
    }

    // ---------------- 401：鉴权失败 ----------------

    @Test
    fun `401 判定为鉴权失败 只发一次不再提交后续批次`() {
        val fixture = LocalHttpFixture {
            LocalHttpFixture.Reply(401, """{"ok":false,"error":"unauthorized"}""")
        }
        try {
            val client = SyncChannelClient(HttpReporter(fixture.baseUrl, token = "bad-token"))
            // 250 条 = 两批（200 + 50）：401 后不应该把第二批也发出去
            val items = (1L..250L).map { messageItem(it) }

            val report = client.pushMessages("/weixin", items)

            assertEquals(listOf(SyncOutcome.UNAUTHORIZED), report.outcomes)
            assertTrue(report.unauthorized)
            assertTrue(report.accepted.isEmpty())
            assertTrue(report.rejected.isEmpty())
            // 鉴权失败属配置错误：不再打第二轮请求
            assertEquals(1, fixture.recorded.size)
            assertEquals("/weixin", fixture.recorded.single().path)
        } finally {
            fixture.close()
        }
    }

    // ---------------- 5xx：失败可重试 ----------------

    @Test
    fun `5xx 判定为失败可重试 且不再提交后续批次`() {
        val fixture = LocalHttpFixture {
            LocalHttpFixture.Reply(503, """{"ok":false,"error":"service unavailable"}""")
        }
        try {
            val client = SyncChannelClient(HttpReporter(fixture.baseUrl, token = "tok-abc"))
            val items = (1L..250L).map { messageItem(it) }

            val report = client.pushMessages("/weixin", items)

            assertEquals(listOf(SyncOutcome.FAILED), report.outcomes)
            assertTrue(report.retryNeeded)
            assertTrue(report.accepted.isEmpty())
            assertFalse(report.unauthorized)
            // 失败后本批与后续批次都留在队列里等下一轮 -> 只发一次请求
            assertEquals(1, fixture.recorded.size)
        } finally {
            fixture.close()
        }
    }

    // ---------------- 413：二分重试 + 单条隔离 ----------------

    @Test
    fun `413 二分重试 拆到单条仍超限则单独隔离 不阻断后续条目`() {
        val fixture = LocalHttpFixture { req ->
            if (req.body.contains(poisonMark)) {
                LocalHttpFixture.Reply(413, """{"ok":false,"error":"payload too large"}""")
            } else {
                LocalHttpFixture.Reply(200, """{"ok":true,"received":1,"invalid":0}""")
            }
        }
        try {
            val client = SyncChannelClient(HttpReporter(fixture.baseUrl, token = "tok-abc"))
            val items = listOf(
                messageItem(1L, text = "正常条目 1"),
                messageItem(2L, text = poisonMark + " 毒丸"),
                messageItem(3L, text = "正常条目 3"),
                messageItem(4L, text = "正常条目 4")
            )

            val report = client.pushMessages("/weixin", items)

            // 4 条 -> 整批 413 -> 二分 [1,2] / [3,4] -> [1,2] 再 413 -> 二分 [1] / [2]
            // -> [1] 200、 [2] 单独仍 413（隔离）、 [3,4] 200
            assertEquals(
                listOf(
                    SyncOutcome.PAYLOAD_TOO_LARGE,
                    SyncOutcome.PAYLOAD_TOO_LARGE,
                    SyncOutcome.ACCEPTED,
                    SyncOutcome.PAYLOAD_TOO_LARGE,
                    SyncOutcome.ACCEPTED
                ),
                report.outcomes
            )
            assertEquals(listOf(1L, 3L, 4L), report.accepted)
            assertEquals(listOf(2L), report.isolated)
            assertTrue(report.rejected.isEmpty())
            // 413 不触发整轮 retry（重试无用），也不该卡住后面的条目
            assertFalse(report.retryNeeded)
            assertEquals(5, fixture.recorded.size)
        } finally {
            fixture.close()
        }
    }

    // ---------------- 多批发送 ----------------

    @Test
    fun `多批发送 批数正确 每批不超过 200 条`() {
        val fixture = LocalHttpFixture {
            LocalHttpFixture.Reply(200, """{"ok":true,"received":200,"invalid":0}""")
        }
        try {
            val client = SyncChannelClient(HttpReporter(fixture.baseUrl, token = "tok-abc"))
            val items = (1L..250L).map { messageItem(it) }

            val report = client.pushMessages("/weixin", items)

            assertEquals(2, fixture.recorded.size)
            assertEquals(200, JSONArray(fixture.recorded[0].body).length())
            assertEquals(50, JSONArray(fixture.recorded[1].body).length())
            assertEquals((1L..250L).toList(), report.accepted)
            assertEquals(listOf(SyncOutcome.ACCEPTED, SyncOutcome.ACCEPTED), report.outcomes)
        } finally {
            fixture.close()
        }
    }

    // ---------------- 分析通道 ----------------

    @Test
    fun `分析通道 207 按 case_id 匹配被拒条目 不依赖请求体下标`() {
        val fixture = LocalHttpFixture {
            LocalHttpFixture.Reply(
                207,
                """{"ok":false,"received":2,"duplicated":0,"invalid":1,""" +
                    """"rejected":[0],"rejected_ids":["case-2"],""" +
                    """"error":"some items were rejected"}"""
            )
        }
        try {
            val client = SyncChannelClient(HttpReporter(fixture.baseUrl, token = "tok-abc"))
            val items = listOf(
                SyncBatchCore.Item(1L, analysisJson("case-1")),
                SyncBatchCore.Item(2L, analysisJson("case-2")),
                SyncBatchCore.Item(3L, analysisJson("case-3"))
            )
            val businessIds = mapOf(1L to "case-1", 2L to "case-2", 3L to "case-3")

            val report = client.pushByBusinessId("/analysis", items, businessIds)

            // 服务端 rejected 下标是 0，但 rejected_ids 指向 case-2：
            // 生产口径优先按 id 匹配 -> 被拒的是 case-2（不是第 1 条）
            assertEquals(listOf(1L, 3L), report.accepted)
            assertEquals(listOf(2L), report.rejected)
            assertEquals(listOf(SyncOutcome.PARTIAL_REJECTED), report.outcomes)
            assertFalse(report.retryNeeded)

            // 请求体字段结构对齐服务端契约
            val req = fixture.recorded.single()
            assertEquals("/analysis", req.path)
            val arr = JSONArray(req.body)
            assertEquals(3, arr.length())
            val first = arr.getJSONObject(0)
            assertEquals("case-1", first.getString("case_id"))
            assertTrue(first.has("conversation"))
            assertTrue(first.has("window_end"))
            assertTrue(first.has("message_count"))
            assertTrue(first.has("analyzed_at"))
            assertTrue(first.has("protocol"))
            assertTrue(first.has("s1"))
            assertTrue(first.has("escalated"))
            assertTrue(first.has("s2"))
            // raw_json 是本地防御存档，不上报
            assertFalse(first.has("raw_json"))
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `分析通道 207 清单不可读 整批不结算 不误标已同步`() {
        val fixture = LocalHttpFixture {
            LocalHttpFixture.Reply(
                207,
                """{"ok":false,"received":0,"duplicated":0,"invalid":3,""" +
                    """"error":"some items were rejected"}"""
            )
        }
        try {
            val client = SyncChannelClient(HttpReporter(fixture.baseUrl, token = "tok-abc"))
            val items = listOf(
                SyncBatchCore.Item(1L, analysisJson("case-1")),
                SyncBatchCore.Item(2L, analysisJson("case-2"))
            )
            val businessIds = mapOf(1L to "case-1", 2L to "case-2")

            val report = client.pushByBusinessId("/analysis", items, businessIds)

            // 读不出被拒清单 = 本轮结论不明：一条都不能算已结算
            assertTrue(report.accepted.isEmpty())
            assertTrue(report.rejected.isEmpty())
            assertTrue(report.isolated.isEmpty())
            assertEquals(listOf(SyncOutcome.PARTIAL_REJECTED), report.outcomes)
            assertEquals(1, fixture.recorded.size)
        } finally {
            fixture.close()
        }
    }

    // ---------------- 顾问通道 ----------------

    @Test
    fun `顾问通道 200 整批成功 请求体字段符合服务端契约`() {
        val fixture = LocalHttpFixture {
            LocalHttpFixture.Reply(200, """{"ok":true,"inserted":2,"duplicated":0,"invalid":0}""")
        }
        try {
            val client = SyncChannelClient(HttpReporter(fixture.baseUrl, token = "tok-abc"))
            val report1 = AdvisorReport(
                reportId = "r-1",
                createdAt = 1_700_000_000_000L,
                periodDays = 7,
                caseCount = 12,
                summary = "整体复盘摘要",
                suggestions = listOf(
                    AdvisorReport.Suggestion("prompt", "提示词标题", "提示词细节")
                ),
                modelUsed = "test-model"
            )
            val items = listOf(
                SyncBatchCore.Item(1L, report1.toJson().toString()),
                SyncBatchCore.Item(2L, report1.copy(reportId = "r-2").toJson().toString())
            )
            val businessIds = mapOf(1L to "r-1", 2L to "r-2")

            val report = client.pushByBusinessId("/advisor", items, businessIds)

            assertEquals(listOf(SyncOutcome.ACCEPTED), report.outcomes)
            assertEquals(listOf(1L, 2L), report.accepted)
            assertTrue(report.rejected.isEmpty())

            val req = fixture.recorded.single()
            assertEquals("/advisor", req.path)
            val arr = JSONArray(req.body)
            assertEquals(2, arr.length())
            val first = arr.getJSONObject(0)
            assertEquals("r-1", first.getString("report_id"))
            assertTrue(first.has("created_at"))
            assertTrue(first.has("period_days"))
            assertTrue(first.has("case_count"))
            assertTrue(first.has("summary"))
            assertTrue(first.has("suggestions"))
            assertTrue(first.has("model_used"))
            val sug = first.getJSONArray("suggestions")
            assertEquals(1, sug.length())
            assertEquals("prompt", sug.getJSONObject(0).getString("type"))
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `顾问通道 207 按 report_id 匹配被拒报告`() {
        val fixture = LocalHttpFixture {
            LocalHttpFixture.Reply(
                207,
                """{"ok":false,"inserted":1,"duplicated":0,"invalid":1,""" +
                    """"rejected":[0],"rejected_ids":["r-2"],""" +
                    """"error":"some items were rejected"}"""
            )
        }
        try {
            val client = SyncChannelClient(HttpReporter(fixture.baseUrl, token = "tok-abc"))
            val report1 = AdvisorReport(
                reportId = "r-1",
                createdAt = 1_700_000_000_000L,
                periodDays = 7,
                caseCount = 12,
                summary = "摘要",
                suggestions = emptyList(),
                modelUsed = "test-model"
            )
            val items = listOf(
                SyncBatchCore.Item(1L, report1.toJson().toString()),
                SyncBatchCore.Item(2L, report1.copy(reportId = "r-2").toJson().toString()),
                SyncBatchCore.Item(3L, report1.copy(reportId = "r-3").toJson().toString())
            )
            val businessIds = mapOf(1L to "r-1", 2L to "r-2", 3L to "r-3")

            val report = client.pushByBusinessId("/advisor", items, businessIds)

            assertEquals(listOf(1L, 3L), report.accepted)
            assertEquals(listOf(2L), report.rejected)
            assertEquals(listOf(SyncOutcome.PARTIAL_REJECTED), report.outcomes)
        } finally {
            fixture.close()
        }
    }

    // ---------------- 404：老服务端没有该端点 ----------------

    @Test
    fun `404 判定为端点不存在 静默跳过不重试`() {
        val fixture = LocalHttpFixture {
            LocalHttpFixture.Reply(404, """{"ok":false,"error":"not found"}""")
        }
        try {
            val client = SyncChannelClient(HttpReporter(fixture.baseUrl, token = "tok-abc"))
            val items = listOf(SyncBatchCore.Item(1L, analysisJson("case-1")))

            val report = client.pushByBusinessId("/analysis", items, mapOf(1L to "case-1"))

            assertEquals(listOf(SyncOutcome.NOT_SUPPORTED), report.outcomes)
            assertTrue(report.notSupported)
            assertTrue(report.accepted.isEmpty())
            // 404 不是失败：老服务端没有该端点，不进重试
            assertFalse(report.retryNeeded)
            assertEquals(1, fixture.recorded.size)
        } finally {
            fixture.close()
        }
    }
}
