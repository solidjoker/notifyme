// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 历史回填的**真实 HTTP 端到端**翻页测试。
 *
 * 覆盖 [HistorySync.fetchMessages] 的游标翻页链路：真起 HTTP 服务、真发 GET、
 * 真读响应体里的 next_before 游标，再按 [HistorySyncCore.shouldFetchNext] 判断
 * 是否继续翻页。分页路径与终止条件全部调用生产代码（[HistorySyncCore]）。
 *
 * 服务端契约：GET /messages?limit=N[&before=<游标>]，响应体
 * {"messages":[...],"next_before":"<毫秒>_<rowid>"}；next_before 为空表示到底。
 */
class HistorySyncPagingHttpTest {

    /** 与生产一致的单页条数上限（HistorySync.kt:49 FETCH_LIMIT）。 */
    private val fetchLimit = 1000

    /** 复刻 [HistorySync.fetchMessages] 的翻页循环（HistorySync.kt:230-243）。 */
    private class PagingClient(private val reporter: HttpReporter) {

        data class Page(val messages: List<String>, val nextBefore: String?)

        data class Result(
            val pages: List<Page>,
            val total: Int,
            val failed: Boolean,
            val requests: Int
        )

        fun fetchAll(limit: Int): Result {
            val pages = mutableListOf<Page>()
            val all = mutableListOf<String>()
            var cursor: String? = null
            var requests = 0
            while (true) {
                val res = reporter.get("/" + HistorySyncCore.messagesPath(cursor, limit))
                requests++
                if (res.code !in 200..299) {
                    // 与生产一致：非 2xx = 本页失败 -> 整轮拉取失败
                    return Result(pages, 0, true, requests)
                }
                val obj = JSONObject(res.body)
                val arr = obj.optJSONArray("messages") ?: JSONArray()
                val messages = (0 until arr.length()).map { readMessage(arr.get(it)) }
                val next = obj.optString("next_before").takeIf { it.isNotBlank() }
                pages += Page(messages, next)
                all += messages
                if (!HistorySyncCore.shouldFetchNext(next, messages.size, cursor, pages.size)) {
                    break
                }
                cursor = next
            }
            return Result(pages, all.size, false, requests)
        }

        private fun readMessage(raw: Any): String = when (raw) {
            is JSONObject -> raw.optString("text").ifEmpty { raw.toString() }
            else -> raw.toString()
        }
    }

    /** 造一页消息响应。 */
    private fun page(texts: List<String>, nextBefore: String?): String {
        val arr = JSONArray()
        for ((index, text) in texts.withIndex()) {
            arr.put(
                JSONObject()
                    .put("id", index + 1)
                    .put("text", text)
                    .put("timestamp", 1_700_000_000_000L + index)
                    .put("conversation", "会话A")
            )
        }
        val obj = JSONObject().put("messages", arr)
        if (nextBefore != null) obj.put("next_before", nextBefore)
        return obj.toString()
    }

    // ---------------- 翻页：第二页带游标，到底后停 ----------------

    @Test
    fun `按 next_before 游标翻页 第二页请求带游标 到底后不再请求`() {
        val fixture = LocalHttpFixture { req ->
            when (req.query("before")) {
                null -> LocalHttpFixture.Reply(200, page(listOf("第一条", "第二条"), "1700000000000_42"))
                "1700000000000_42" -> LocalHttpFixture.Reply(200, page(listOf("第三条"), null))
                else -> LocalHttpFixture.Reply(500, """{"ok":false}""")
            }
        }
        try {
            val client = PagingClient(HttpReporter(fixture.baseUrl, token = "tok-abc"))

            val result = client.fetchAll(fetchLimit)

            assertTrue("整轮拉取不应失败", !result.failed)
            assertEquals(2, result.pages.size)
            assertEquals(3, result.total)
            assertEquals(2, result.requests)

            // 第一页：没有 before 游标
            val first = fixture.recorded[0]
            assertEquals("GET", first.method)
            assertEquals("/messages", first.path)
            assertNull("第一页不应带 before 游标", first.query("before"))
            assertEquals("1000", first.query("limit"))
            assertEquals("tok-abc", first.headers["x-token"])

            // 第二页：带服务端给的游标
            val second = fixture.recorded[1]
            assertEquals("/messages", second.path)
            assertEquals("1700000000000_42", second.query("before"))
            assertEquals("1000", second.query("limit"))

            // 第三页没有发生（到底了就停）
            assertEquals(2, fixture.recorded.size)
            assertEquals(listOf("第一条", "第二条", "第三条"), result.pages.flatMap { it.messages })
        } finally {
            fixture.close()
        }
    }

    // ---------------- 游标没变 ----------------

    @Test
    fun `服务端游标没变时停止翻页 不会无限循环`() {
        val fixture = LocalHttpFixture {
            LocalHttpFixture.Reply(200, page(listOf("一条"), "1700000000000_7"))
        }
        try {
            val client = PagingClient(HttpReporter(fixture.baseUrl, token = "tok-abc"))

            val result = client.fetchAll(fetchLimit)

            assertTrue(!result.failed)
            // 第一页拿到游标 -> 翻第二页；第二页游标与上一页相同 -> 停
            assertEquals(2, result.requests)
            assertEquals(2, result.pages.size)
            assertNull(fixture.recorded[0].query("before"))
            assertEquals("1700000000000_7", fixture.recorded[1].query("before"))
        } finally {
            fixture.close()
        }
    }

    // ---------------- 空页 ----------------

    @Test
    fun `本页没有消息时停止 不再发下一页请求`() {
        val fixture = LocalHttpFixture {
            LocalHttpFixture.Reply(200, page(emptyList(), "1700000000000_9"))
        }
        try {
            val client = PagingClient(HttpReporter(fixture.baseUrl, token = "tok-abc"))

            val result = client.fetchAll(fetchLimit)

            assertTrue(!result.failed)
            assertEquals(0, result.total)
            assertEquals(1, result.requests)
            assertEquals(1, fixture.recorded.size)
        } finally {
            fixture.close()
        }
    }

    // ---------------- 页数上限 ----------------

    @Test
    fun `连续翻页达到页数上限后停止 不会无限拉取`() {
        var counter = 0
        val fixture = LocalHttpFixture {
            counter++
            // 游标每页都变，模拟服务端有无限多历史
            LocalHttpFixture.Reply(200, page(listOf("消息"), "cursor-" + counter))
        }
        try {
            val client = PagingClient(HttpReporter(fixture.baseUrl, token = "tok-abc"))

            val result = client.fetchAll(fetchLimit)

            assertTrue(!result.failed)
            assertEquals(HistorySyncCore.MAX_PAGES, result.requests)
            assertEquals(HistorySyncCore.MAX_PAGES, result.pages.size)
            assertEquals(HistorySyncCore.MAX_PAGES, fixture.recorded.size)
        } finally {
            fixture.close()
        }
    }

    // ---------------- 401：整轮失败 ----------------

    @Test
    fun `401 时整轮历史拉取失败 立刻停止并保留鉴权头`() {
        val fixture = LocalHttpFixture {
            LocalHttpFixture.Reply(401, """{"ok":false,"error":"unauthorized"}""")
        }
        try {
            val client = PagingClient(HttpReporter(fixture.baseUrl, token = "bad-token"))

            val result = client.fetchAll(fetchLimit)

            assertTrue("鉴权失败应判定整轮失败", result.failed)
            assertEquals(0, result.total)
            assertEquals(1, result.requests)
            assertEquals(1, fixture.recorded.size)
            assertEquals("bad-token", fixture.recorded.single().headers["x-token"])
        } finally {
            fixture.close()
        }
    }
}
