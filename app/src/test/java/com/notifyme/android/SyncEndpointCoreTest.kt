// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SyncEndpointCore] 的契约：服务器根归一的顺序、`/weixin` 子路径的剥离、Admin 路径拼接、
 * 鉴权头取值，以及与旧内联实现（五处逐字同构）的逐样本对照。
 */
class SyncEndpointCoreTest {

    // ---------- 旧实现逐字副本（重构前五个站点的写法，用于对照，不要当成新口径） ----------

    private fun legacyBase(serverUrl: String): String {
        var base = serverUrl.trim().trimEnd('/')
        if (base.endsWith("/weixin")) base = base.dropLast("/weixin".length)
        return base
    }

    private fun legacyEndpoint(serverUrl: String, path: String): String =
        "${legacyBase(serverUrl)}/$path"

    private fun legacyAdminEndpoint(serverUrl: String, path: String): String =
        "${legacyBase(serverUrl)}/admin/$path"

    /** SyncWorker.postJson 的旧写法：`isNotEmpty()`（与 HistorySync 的 `isNotBlank()` 不同）。 */
    private fun legacyAttachNotEmpty(token: String): String? = if (token.isNotEmpty()) token else null

    /** HistorySync.request / CaptureActivity.extractRequest 的旧写法。 */
    private fun legacyAttachNotBlank(token: String): String? = if (token.isNotBlank()) token else null

    // ---------- base：顺序与子路径剥离 ----------

    @Test
    fun baseDropsTrailingSlashes() {
        assertEquals("http://h", SyncEndpointCore.base("http://h/"))
        assertEquals("http://h", SyncEndpointCore.base("http://h///"))
    }

    @Test
    fun baseDropsWeixinSuffix() {
        assertEquals("http://h", SyncEndpointCore.base("http://h/weixin"))
    }

    @Test
    fun baseDropsTrailingSlashBeforeWeixinSuffix() {
        // 顺序契约：先 trimEnd('/') 才能匹配 /weixin；反序会留下 http://h/weixin
        assertEquals("http://h", SyncEndpointCore.base("http://h/weixin/"))
        assertEquals("http://h", SyncEndpointCore.base("http://h/weixin//"))
    }

    @Test
    fun baseKeepsPathPrefixBeforeWeixin() {
        assertEquals("http://h/notifyme", SyncEndpointCore.base("http://h/notifyme/weixin"))
        assertEquals("http://h/notifyme", SyncEndpointCore.base("http://h/notifyme/weixin/"))
    }

    @Test
    fun baseIsCaseSensitiveOnWeixin() {
        // 刻意保守：只认小写 /weixin；大写多半是别的路径，剥掉会拼出 404
        assertEquals("http://h/Weixin", SyncEndpointCore.base("http://h/Weixin"))
    }

    @Test
    fun baseKeepsWeixinNotAtTail() {
        assertEquals("http://h/weixin/api", SyncEndpointCore.base("http://h/weixin/api"))
        assertEquals("http://h/weixin/api", SyncEndpointCore.base("http://h/weixin/api/"))
    }

    @Test
    fun baseTrimsSurroundingWhitespace() {
        assertEquals("http://h", SyncEndpointCore.base("  http://h/weixin/  "))
        assertEquals("http://h", SyncEndpointCore.base("\thttp://h\n"))
    }

    @Test
    fun baseOfBlankIsBlank() {
        assertEquals("", SyncEndpointCore.base(""))
        assertEquals("", SyncEndpointCore.base("   "))
        // 只有子路径时剥完为空：调用方的 isBlank() 守卫会挡住后续请求
        assertEquals("", SyncEndpointCore.base("/weixin"))
        assertEquals("", SyncEndpointCore.base("/weixin/"))
    }

    @Test
    fun baseIsIdempotent() {
        val samples = listOf("http://h", "http://h/weixin/", "http://h/notifyme/weixin", "")
        for (sample in samples) {
            val once = SyncEndpointCore.base(sample)
            assertEquals(once, SyncEndpointCore.base(once))
        }
    }

    // ---------- url / adminUrl ----------

    @Test
    fun urlJoinsRootLevelPath() {
        assertEquals("http://h/analysis", SyncEndpointCore.url("http://h", "analysis"))
        assertEquals("http://h/advisor", SyncEndpointCore.url("http://h/weixin/", "advisor"))
        assertEquals("http://h/queue", SyncEndpointCore.url(" http://h/ ", "queue"))
    }

    @Test
    fun urlKeepsQueryString() {
        assertEquals(
            "http://h/messages?limit=1000&before=1700000000000_42",
            SyncEndpointCore.url("http://h/weixin", "messages?limit=1000&before=1700000000000_42")
        )
    }

    @Test
    fun adminUrlJoinsAdminPath() {
        assertEquals(
            "http://h/admin/extract",
            SyncEndpointCore.adminUrl("http://h/weixin", "extract")
        )
        assertEquals(
            "http://h/admin/extract/status",
            SyncEndpointCore.adminUrl("http://h/", "extract/status")
        )
    }

    @Test
    fun adminUrlDoesNotSwallowExistingAdminSegment() {
        // 用户把地址填到 /admin 结尾时不会把 admin 叠两次：base 不做前缀剥离，但 url() 只拼一次
        assertEquals("http://h/admin/admin/x", SyncEndpointCore.adminUrl("http://h/admin", "x"))
    }

    // ---------- token ----------

    @Test
    fun tokenIsNullWhenBlank() {
        assertNull(SyncEndpointCore.token(""))
        assertNull(SyncEndpointCore.token("   "))
        assertNull(SyncEndpointCore.token("\t\n"))
    }

    @Test
    fun tokenReturnsValueVerbatim() {
        // 不 trim：令牌由 SyncConfig 的 setter 负责裁剪，Core 不做二次改写
        assertEquals("abc123", SyncEndpointCore.token("abc123"))
        assertEquals(" abc123 ", SyncEndpointCore.token(" abc123 "))
        assertEquals("abc", SyncEndpointCore.token("abc"))
    }

    @Test
    fun headerNameMatchesServerContract() {
        assertEquals("X-Token", SyncEndpointCore.TOKEN_HEADER)
    }

    // ---------- 与旧内联实现的逐样本对照（重构不是修 bug） ----------

    @Test
    fun coreAgreesWithLegacyBaseOnReachableInputs() {
        val samples = listOf(
            "http://192.168.1.10:5000",
            "http://192.168.1.10:5000/",
            "http://192.168.1.10:5000//",
            "  http://192.168.1.10:5000/weixin  ",
            "https://example.com/weixin",
            "https://example.com/weixin/",
            "https://example.com/weixin//",
            "https://example.com/notifyme/weixin",
            "https://example.com/weixin/api",
            "https://example.com",
            "/weixin",
            "/weixin/",
            "",
            "   "
        )
        for (sample in samples) {
            assertEquals(
                "base 归一与旧实现不一致: [$sample]",
                legacyBase(sample),
                SyncEndpointCore.base(sample)
            )
        }
    }

    @Test
    fun coreAgreesWithLegacyPathJoining() {
        val servers = listOf("http://h", "http://h/", "http://h/weixin", "http://h/weixin/")
        val paths = listOf("analysis", "advisor", "queue", "admin/extract", "extract/status")
        for (server in servers) {
            for (path in paths) {
                assertEquals(
                    "根路径拼接与旧实现不一致: [$server] + [$path]",
                    legacyEndpoint(server, path),
                    SyncEndpointCore.url(server, path)
                )
                assertEquals(
                    "admin 拼接与旧实现不一致: [$server] + [$path]",
                    legacyAdminEndpoint(server, path),
                    SyncEndpointCore.adminUrl(server, path)
                )
            }
        }
    }

    @Test
    fun tokenUnificationOnlyDiffersOnBlankWhitespaceTokens() {
        val samples = listOf("", " ", "   ", "\t", "a", " a", "a ", "abc123", "  abc  ")
        for (sample in samples) {
            if (sample.isBlank()) {
                // 旧 SyncWorker 用 isNotEmpty() 会给「纯空白但非空」的令牌带上头（" " 是可达分歧点，
                // 只剩空白且非空 → isNotEmpty()=true）。统一到 isNotBlank() 后一律返 null。
                assertNull(SyncEndpointCore.token(sample))
                assertEquals(sample.isNotEmpty(), legacyAttachNotEmpty(sample) != null)
                assertTrue(legacyAttachNotBlank(sample) == null)
            } else {
                assertEquals(
                    "非空白令牌的取值必须逐字一致: [$sample]",
                    legacyAttachNotEmpty(sample),
                    SyncEndpointCore.token(sample)
                )
                assertEquals(
                    "HistorySync 与 SyncWorker 两套旧判定在非空白输入上本就一致: [$sample]",
                    legacyAttachNotBlank(sample),
                    SyncEndpointCore.token(sample)
                )
            }
        }
    }

    @Test
    fun tokenUnificationCannotBeObservedThroughSyncConfig() {
        // SyncConfig 的 setter 会 trim，落盘后读出来不可能全是空白 —— 这就是上面那条
        // 「分歧只存在于不可达输入」的论证，用纯字符串等价模拟。
        val setters = listOf("", " ", "   ", " token ")
        for (raw in setters) {
            val stored = raw.trim()
            assertEquals(
                "经 setter 归一后两套旧判定必须同结论: [$raw]",
                legacyAttachNotEmpty(stored),
                legacyAttachNotBlank(stored)
            )
        }
    }

    // ---------- 与 okhttp 的接线（鉴权头真的落在请求上） ----------

    private fun requestWithToken(serverUrl: String, path: String, rawToken: String): Request =
        SyncEndpointCore.authorized(
            Request.Builder().url(SyncEndpointCore.url(serverUrl, path)),
            rawToken
        ).get().build()

    @Test
    fun requestCarriesTokenHeaderWhenConfigured() {
        val request = requestWithToken("http://h/weixin", "analysis", "tok-123")
        assertEquals("http://h/analysis", request.url.toString())
        assertEquals("tok-123", request.header("X-Token"))
    }

    @Test
    fun requestOmitsTokenHeaderWhenBlank() {
        val request = requestWithToken("http://h", "queue", "  ")
        assertEquals("http://h/queue", request.url.toString())
        assertNull(request.header("X-Token"))
        assertTrue(request.headers.names().isEmpty())
        assertFalse(request.headers.names().contains("X-Token"))
    }

    @Test
    fun authorizedReturnsSameBuilderForChaining() {
        val builder = Request.Builder()
        assertSame(builder, SyncEndpointCore.authorized(builder, "tok"))
        assertSame(builder, SyncEndpointCore.authorized(builder, ""))
    }

    @Test
    fun authorizedOverwritesRatherThanDuplicatesHeader() {
        // 多通道共用同一个构造函数时不会累积出两个 X-Token（header() 是覆盖语义）
        val builder = SyncEndpointCore.authorized(Request.Builder(), "first")
        val request = SyncEndpointCore.authorized(builder, "second").url("http://h/x").get().build()
        assertEquals(listOf("second"), request.headers("X-Token"))
    }

    @Test
    fun authorizedOnPostRequestBodyKeepsMethodAndBody() {
        val body = "{\"a\":1}".toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = SyncEndpointCore.authorized(
            Request.Builder().url(SyncEndpointCore.url("http://h/weixin", "queue")).post(body),
            "tok"
        ).build()
        assertEquals("POST", request.method)
        assertEquals("http://h/queue", request.url.toString())
        assertEquals("tok", request.header("X-Token"))
    }
}
