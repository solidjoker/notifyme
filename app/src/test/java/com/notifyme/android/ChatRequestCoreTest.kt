// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ChatRequestCore] 的端点归一与请求体口径测试。
 *
 * 为什么值得测：三个通道（S1/S2 分析、顾问复盘、S1 评测）此前各抄一份构造代码，
 * 注释里的 `max_tokens` 实测值已经分叉（1024 / 1500 / 4096）。这里把「同一份输入
 * 只能产出一个请求体」钉死，并保留旧实现的逐字对照 —— 重构不是改行为。
 */
class ChatRequestCoreTest {

    // ---------------- 端点归一 ----------------

    @Test
    fun endpointAppendsSuffixToBareHost() {
        assertEquals(
            "http://10.0.2.2:8080/chat/completions",
            ChatRequestCore.endpoint("http://10.0.2.2:8080")
        )
    }

    @Test
    fun endpointAppendsSuffixAfterVersionPrefix() {
        assertEquals(
            "https://open.bigmodel.cn/api/paas/v4/chat/completions",
            ChatRequestCore.endpoint("https://open.bigmodel.cn/api/paas/v4")
        )
    }

    @Test
    fun endpointKeepsAlreadyCompleteUrl() {
        val url = "https://open.bigmodel.cn/api/paas/v4/chat/completions"
        assertEquals(url, ChatRequestCore.endpoint(url))
    }

    @Test
    fun endpointTrimsWhitespaceAndTrailingSlash() {
        // 用户从文档里复制常带尾斜杠与首尾空格
        assertEquals(
            "http://10.0.2.2:8080/chat/completions",
            ChatRequestCore.endpoint("  http://10.0.2.2:8080/  ")
        )
        assertEquals(
            "http://10.0.2.2:8080/chat/completions",
            ChatRequestCore.endpoint("http://10.0.2.2:8080/chat/completions/")
        )
    }

    @Test
    fun endpointDoesNotInventVersionPrefix() {
        // 刻意不猜 provider：填 v4 就补在 v4 后面，填裸 host 就补在裸 host 后面
        assertEquals(
            "http://10.0.2.2:8080/chat/completions",
            ChatRequestCore.endpoint("http://10.0.2.2:8080")
        )
        assertFalse(ChatRequestCore.endpoint("http://10.0.2.2:8080").contains("/v1"))
    }

    @Test
    fun endpointOfEmptyStringStillYieldsSuffix() {
        // 配置校验是 UI 层的事（「未配置服务地址」），Core 不做语义判断
        assertEquals("/chat/completions", ChatRequestCore.endpoint(""))
        assertEquals("/chat/completions", ChatRequestCore.endpoint("   "))
    }

    // ---------------- 请求体 ----------------

    @Test
    fun payloadKeepsSingleRequestShape() {
        val body = JSONObject(ChatRequestCore.payload("glm-5.3", "sys", "usr"))
        // 键集合是契约：三个通道都只能带这五个顶层键
        assertEquals(
            setOf("model", "messages", "response_format", "max_tokens", "stream"),
            body.keys().asSequence().toSet()
        )
        assertEquals("glm-5.3", body.getString("model"))
        assertEquals(4096, body.getInt("max_tokens"))
        assertFalse(body.getBoolean("stream"))
        assertEquals("json_object", body.getJSONObject("response_format").getString("type"))
    }

    @Test
    fun payloadMessagesAreSystemThenUser() {
        val messages = JSONObject(ChatRequestCore.payload("m", "SYS", "USR"))
            .getJSONArray("messages")
        assertEquals(2, messages.length())
        assertEquals("system", messages.getJSONObject(0).getString("role"))
        assertEquals("SYS", messages.getJSONObject(0).getString("content"))
        assertEquals("user", messages.getJSONObject(1).getString("role"))
        assertEquals("USR", messages.getJSONObject(1).getString("content"))
    }

    @Test
    fun payloadAllowsEmptySystemPrompt() {
        // 评测通道拿不到 system 文案时会传空串，不能因此少一条 message
        val messages = JSONObject(ChatRequestCore.payload("m", "", "USR"))
            .getJSONArray("messages")
        assertEquals(2, messages.length())
        assertEquals("", messages.getJSONObject(0).getString("content"))
    }

    @Test
    fun payloadMaxTokensOverridable() {
        val body = JSONObject(ChatRequestCore.payload("m", "s", "u", maxTokens = 512))
        assertEquals(512, body.getInt("max_tokens"))
    }

    @Test
    fun payloadEscapesControlCharacters() {
        // 会话原文里有换行与引号，必须被 JSON 转义而不是拼坏请求体
        val text = "第一行\n第二行 \"引号\" 结尾"
        val body = JSONObject(ChatRequestCore.payload("m", "s", text))
        assertEquals(text, body.getJSONArray("messages").getJSONObject(1).getString("content"))
    }

    @Test
    fun payloadIsReparsableAfterSerialization() {
        val raw = ChatRequestCore.payload("m", "s", "u")
        assertTrue(raw.startsWith("{"))
        assertTrue(raw.endsWith("}"))
        assertTrue(JSONObject(raw).has("messages"))
    }

    // ---------------- 与旧实现逐字对照 ----------------

    /** 旧写法（三处 Worker 里的原样实现），保留在测试里做对照实验。 */
    private fun legacyPayload(model: String, systemPrompt: String, userContent: String): String {
        var url = "http://h:1/v1".trim().trimEnd('/')
        if (!url.endsWith("/chat/completions")) url += "/chat/completions"
        return JSONObject().apply {
            put("model", model)
            put("messages", org.json.JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", systemPrompt)
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", userContent)
                })
            })
            put("response_format", JSONObject().put("type", "json_object"))
            put("max_tokens", 4096)
            put("stream", false)
        }.toString()
    }

    @Test
    fun coreAgreesWithLegacyPayloadOnNormalInput() {
        val cases = listOf(
            Triple("glm-5.3", "你是分析助手", "窗口原文"),
            Triple("qwen25-0.5b", "", "单条消息"),
            Triple("custom", "多行\nsystem", "含 \"引号\" 与 \\ 反斜杠")
        )
        for ((model, sys, user) in cases) {
            assertEquals(
                "payload 与旧实现不一致: $model",
                legacyPayload(model, sys, user),
                ChatRequestCore.payload(model, sys, user)
            )
        }
    }

    @Test
    fun coreAgreesWithLegacyEndpointOnCommonUrls() {
        val urls = listOf(
            "http://10.0.2.2:8080",
            "http://10.0.2.2:8080/",
            "https://open.bigmodel.cn/api/paas/v4",
            "https://open.bigmodel.cn/api/paas/v4/chat/completions",
            "  http://127.0.0.1:8082  "
        )
        for (u in urls) {
            var legacy = u.trim().trimEnd('/')
            if (!legacy.endsWith("/chat/completions")) legacy += "/chat/completions"
            assertEquals("endpoint 与旧实现不一致: $u", legacy, ChatRequestCore.endpoint(u))
        }
    }

    @Test
    fun suffixConstantMatchesLiteralUsedInWorkers() {
        // 常量值本身是契约（旧代码写字面量，改动会让「已带后缀」判定失效）
        assertEquals("/chat/completions", ChatRequestCore.SUFFIX)
        assertEquals(4096, ChatRequestCore.MAX_TOKENS)
    }

    @Test
    fun payloadDefaultEqualsExplicitMaxTokens() {
        val a = JSONObject(ChatRequestCore.payload("m", "s", "u"))
        val b = JSONObject(ChatRequestCore.payload("m", "s", "u", ChatRequestCore.MAX_TOKENS))
        assertEquals(a.getInt("max_tokens"), b.getInt("max_tokens"))
    }
}
