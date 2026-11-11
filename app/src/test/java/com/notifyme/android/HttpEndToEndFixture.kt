// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 真·HTTP 端到端测试的**本地服务端夹具**。
 *
 * 在 127.0.0.1 的**随机端口**上真的 listen/accept，真的按 HTTP/1.1 解析请求行、
 * 请求头与请求体，真的把响应字节写回 socket —— 不是 mock 框架假装的「像 HTTP 的对象」。
 * 客户端用生产同款 OkHttp（com.squareup.okhttp3:okhttp:4.12.0，已经是 app 的
 * implementation 依赖，单测 classpath 里可用），所以请求形态（方法 / 头 / 体）
 * 与线上完全一致。
 *
 * 为什么自己写这几十行 socket 循环，而不用 JDK 自带的 com.sun.net.httpserver：
 * Android 单测编译时 classpath 是 android.jar（java.* 只有平台桩）+ 显式依赖，
 * **com.sun.net.httpserver 不在其中**（compile 时直接报 Unresolved reference 'sun'），
 * 而 java.net.ServerSocket 在 android.jar 里有桩、运行时又回到真 JDK，
 * 是纯 JVM 单测里能稳定起真实 socket 的最小依赖方案。
 */
internal class LocalHttpFixture(
    private val handler: (Recorded) -> Reply
) : AutoCloseable {

    /** 服务端收到的一次请求（已解析出 method / path / query / headers / body）。 */
    internal data class Recorded(
        val method: String,
        val path: String,
        val rawQuery: String?,
        val headers: Map<String, String>,
        val body: String
    ) {
        /** 取一个 query 参数（值已 URL 解码）。 */
        fun query(name: String): String? {
            val raw = rawQuery ?: return null
            for (part in raw.split("&")) {
                if (part.isEmpty()) continue
                val idx = part.indexOf('=')
                if (idx <= 0) continue
                if (part.substring(0, idx) == name) {
                    return URLDecoder.decode(part.substring(idx + 1), "UTF-8")
                }
            }
            return null
        }
    }

    /** 夹具要回给客户端的响应。 */
    internal data class Reply(
        val code: Int,
        val body: String = "",
        val contentType: String = "application/json; charset=utf-8"
    )

    private val requests: MutableList<Recorded> = Collections.synchronizedList(mutableListOf<Recorded>())

    private val running = AtomicBoolean(true)

    private val serverSocket: ServerSocket =
        ServerSocket(0, 64, InetAddress.getByName("127.0.0.1"))

    private val executor = Executors.newFixedThreadPool(4)

    private val acceptThread: Thread = Thread {
        while (running.get()) {
            val socket = try {
                serverSocket.accept()
            } catch (e: IOException) {
                if (running.get()) continue else break
            }
            try {
                executor.execute { serve(socket) }
            } catch (e: RuntimeException) {
                runCatching { socket.close() }
            }
        }
    }.apply {
        isDaemon = true
        start()
    }

    /** 实际监听端口（构造时传 0 = 由系统分配随机端口，避免端口冲突）。 */
    val port: Int get() = serverSocket.localPort

    /** 服务端基址，例如 http://127.0.0.1:53123 。 */
    val baseUrl: String get() = "http://127.0.0.1:$port"

    /** 按到达顺序记录的所有请求。 */
    val recorded: List<Recorded> get() = synchronized(requests) { requests.toList() }

    /** 处理一条连接：解析请求 -> 交给 handler -> 写响应。 */
    private fun serve(socket: Socket) {
        socket.use { s ->
            try {
                s.soTimeout = 15_000
                val input = s.getInputStream()
                val requestLine = readLine(input) ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) return
                val method = parts[0]
                val target = parts[1]
                val headers = LinkedHashMap<String, String>()
                while (true) {
                    val line = readLine(input) ?: break
                    if (line.isEmpty()) break
                    val idx = line.indexOf(':')
                    if (idx <= 0) continue
                    val name = line.substring(0, idx).trim().lowercase()
                    headers[name] = line.substring(idx + 1).trim()
                }
                val length = headers["content-length"]?.toIntOrNull() ?: 0
                val body = ByteArray(if (length > 0) length else 0)
                var read = 0
                while (read < body.size) {
                    val n = input.read(body, read, body.size - read)
                    if (n < 0) break
                    read += n
                }
                val qIndex = target.indexOf('?')
                val path = if (qIndex >= 0) target.substring(0, qIndex) else target
                val rawQuery = if (qIndex >= 0) target.substring(qIndex + 1) else null
                val text = if (read > 0) String(body, 0, read, Charsets.UTF_8) else ""
                val rec = Recorded(method, path, rawQuery, headers, text)
                requests.add(rec)
                val reply = try {
                    handler(rec)
                } catch (e: Throwable) {
                    Reply(500, """{"ok":false,"error":"fixture handler failed"}""")
                }
                write(s.getOutputStream(), reply)
            } catch (e: IOException) {
                // 客户端提前断开：忽略
            }
        }
    }

    /** 写一个完整的 HTTP/1.1 响应（Connection: close，省掉 keep-alive 复杂度）。 */
    private fun write(out: OutputStream, reply: Reply) {
        val payload = reply.body.toByteArray(Charsets.UTF_8)
        val head = StringBuilder()
        head.append("HTTP/1.1 ")
            .append(reply.code)
            .append(" ")
            .append(reason(reply.code))
            .append("\r\n")
            .append("Content-Type: ")
            .append(reply.contentType)
            .append("\r\n")
            .append("Content-Length: ")
            .append(payload.size)
            .append("\r\n")
            .append("Connection: close\r\n")
            .append("\r\n")
        out.write(head.toString().toByteArray(Charsets.UTF_8))
        if (payload.isNotEmpty()) out.write(payload)
        out.flush()
    }

    private fun reason(code: Int): String = when (code) {
        200 -> "OK"
        201 -> "Created"
        207 -> "Multi-Status"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        413 -> "Payload Too Large"
        422 -> "Unprocessable Entity"
        500 -> "Internal Server Error"
        502 -> "Bad Gateway"
        503 -> "Service Unavailable"
        else -> "Status"
    }

    /** 读一行（以 CRLF 结束），返回去掉行尾 CR 的文本。 */
    private fun readLine(input: InputStream): String? {
        val buffer = ByteArrayOutputStream()
        while (true) {
            val b = try {
                input.read()
            } catch (e: IOException) {
                return null
            }
            if (b < 0) {
                if (buffer.size() == 0) return null
                break
            }
            if (b == '\n'.code) break
            buffer.write(b)
        }
        val bytes = buffer.toByteArray()
        val len = if (bytes.isNotEmpty() && bytes[bytes.size - 1] == '\r'.code.toByte()) {
            bytes.size - 1
        } else {
            bytes.size
        }
        return String(bytes, 0, len, Charsets.UTF_8)
    }

    override fun close() {
        running.set(false)
        runCatching { serverSocket.close() }
        executor.shutdownNow()
        runCatching { acceptThread.interrupt() }
    }
}

/**
 * 复刻 [SyncWorker.postJson]（app/src/main/java/com/notifyme/android/SyncWorker.kt:148）的
 * HTTP 边界：真实发 HTTP + 用**生产核心** [SyncOutcomeCore] 分类。
 *
 * 为什么复刻而不是直接调 SyncWorker：SyncWorker 是 CoroutineWorker，需要 Android 的
 * Context / WorkerParameters，纯 JVM 单测（无 Robolectric）无法实例化。这里把
 * postJson 里**与 Android 无关**的部分原样搬过来：同样的 POST、同样的
 * application/json; charset=utf-8、同样的 X-Token 头、同样的 classify +
 * parseRejected / parseError / parseRejectedIds、IOException 同样归为 FAILED(code=0)。
 */
internal class HttpReporter(
    private val baseUrl: String,
    private val token: String = ""
) {

    /** 一次 POST 的结果（字段与生产 BatchResult 一致）。 */
    internal data class PostResult(
        val outcome: SyncOutcome,
        val code: Int,
        val rejected: Set<Int>?,
        val reason: String?,
        val rejectedIds: Set<String>?
    )

    /** 一次 GET 的结果。 */
    internal data class GetResult(val code: Int, val body: String)

    private val client: OkHttpClient = OkHttpClient.Builder()
        // 明确不走任何代理：夹具是 127.0.0.1 本地服务，走系统代理只会引入不确定性
        .proxy(Proxy.NO_PROXY)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    /** POST 一个 JSON 数组体，返回生产口径的分类结果。 */
    fun postJson(path: String, body: String): PostResult {
        val builder = Request.Builder()
            .url(baseUrl + path)
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .apply {
                if (token.isNotEmpty()) header("X-Token", token)
            }
            .build()
        return try {
            client.newCall(builder).execute().use { response ->
                val outcome = SyncOutcomeCore.classify(response.code)
                if (outcome == SyncOutcome.PARTIAL_REJECTED) {
                    val text = response.body?.string()
                    PostResult(
                        outcome = outcome,
                        code = response.code,
                        rejected = SyncOutcomeCore.parseRejected(text),
                        reason = SyncOutcomeCore.parseError(text),
                        rejectedIds = SyncOutcomeCore.parseRejectedIds(text)
                    )
                } else {
                    PostResult(outcome, response.code, null, null, null)
                }
            }
        } catch (e: IOException) {
            // 与生产一致：网络异常归 FAILED（code 0），由调用方按失败退避重试
            PostResult(SyncOutcome.FAILED, 0, null, null, null)
        }
    }

    /** GET 一个路径（历史翻页用）。 */
    fun get(path: String): GetResult {
        val builder = Request.Builder()
            .url(baseUrl + path)
            .get()
            .apply {
                if (token.isNotEmpty()) header("X-Token", token)
            }
            .build()
        return try {
            client.newCall(builder).execute().use { response ->
                GetResult(response.code, response.body?.string().orEmpty())
            }
        } catch (e: IOException) {
            GetResult(0, "")
        }
    }

    companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

/**
 * 复刻 [SyncWorker.pushMessages]（SyncWorker.kt:205-350）的**批次提交循环**，
 * 用生产的 [SyncBatchCore] / [SyncOutcomeCore] 做分批、二分与结果分类。
 *
 * 与生产的一处刻意差异：生产的处置落盘到 PendingQueue / AnalysisStore（需要 Android
 * Context），这里只把处置结果收集到 [Report] 里，方便单测断言。
 */
internal class SyncChannelClient(private val reporter: HttpReporter) {

    /** 一轮提交后的处置结果。 */
    internal data class Report(
        /** 被服务端明确接受的条目 id（整批 200，或 207 中未被拒的那些）。 */
        val accepted: List<Long>,
        /** 207 里被服务端拒收的条目 id。 */
        val rejected: List<Long>,
        /** 413 拆到单条仍超限、被单独隔离的条目 id。 */
        val isolated: List<Long>,
        /** 需要整轮重试（5xx / 网络错误 / 401）。 */
        val retryNeeded: Boolean,
        /** 401 鉴权失败。 */
        val unauthorized: Boolean,
        /** 404 端点不存在（老服务端）。 */
        val notSupported: Boolean,
        /** 每一次请求的分类结果（按请求顺序）。 */
        val outcomes: List<SyncOutcome>
    )

    /**
     * 消息通道口径：207 按**批次内下标**匹配被拒条目（与 SyncWorker.pushMessages 一致）。
     */
    fun pushMessages(path: String, items: List<SyncBatchCore.Item>): Report =
        push(path, items, emptyMap(), byBusinessId = false, stopOnUnreadable = false)

    /**
     * 分析 / 顾问通道口径：207 优先按**业务 id**（case_id / report_id）匹配被拒条目，
     * 与 SyncWorker.pushAnalysis 的 matchRejectedIndices 口径一致。
     */
    fun pushByBusinessId(
        path: String,
        items: List<SyncBatchCore.Item>,
        businessIds: Map<Long, String>
    ): Report = push(path, items, businessIds, byBusinessId = true, stopOnUnreadable = true)

    private fun push(
        path: String,
        items: List<SyncBatchCore.Item>,
        businessIds: Map<Long, String>,
        byBusinessId: Boolean,
        stopOnUnreadable: Boolean
    ): Report {
        val accepted = mutableListOf<Long>()
        val rejected = mutableListOf<Long>()
        val isolated = mutableListOf<Long>()
        val outcomes = mutableListOf<SyncOutcome>()
        var retryNeeded = false
        var unauthorized = false
        var notSupported = false

        val queue = ArrayDeque(SyncBatchCore.splitBatches(items))
        loop@ while (queue.isNotEmpty()) {
            val batch = queue.removeFirst()
            val result = reporter.postJson(path, SyncBatchCore.buildBody(batch))
            outcomes += result.outcome
            when (result.outcome) {
                SyncOutcome.ACCEPTED -> {
                    accepted += batch.map { it.id }
                }
                SyncOutcome.PARTIAL_REJECTED -> {
                    val ids = batch.map { it.id }
                    val rejectedIdx = if (byBusinessId) {
                        SyncOutcomeCore.matchRejectedIndices(
                            result.rejectedIds,
                            result.rejected,
                            ids.map { businessIds[it] ?: "" }
                        )
                    } else {
                        result.rejected
                    }
                    if (rejectedIdx == null) {
                        // 读不出被拒清单 = 本轮结论不明：一条都不能算已同步
                        if (stopOnUnreadable) break@loop
                    } else {
                        accepted += SyncOutcomeCore.acceptedIds(ids, rejectedIdx)
                        rejected += ids.filterIndexed { index, _ -> index in rejectedIdx }
                    }
                }
                SyncOutcome.PAYLOAD_TOO_LARGE -> {
                    // 二分拆小放回队首继续试；拆到单条仍超限 = 毒丸，单独隔离
                    val halves = SyncBatchCore.bisect(batch)
                    if (halves.isNotEmpty()) {
                        for (i in halves.indices.reversed()) queue.addFirst(halves[i])
                    } else {
                        isolated += batch.map { it.id }
                    }
                }
                SyncOutcome.UNAUTHORIZED -> {
                    unauthorized = true
                    retryNeeded = true
                    break@loop
                }
                SyncOutcome.NOT_SUPPORTED -> {
                    notSupported = true
                    break@loop
                }
                SyncOutcome.FAILED -> {
                    retryNeeded = true
                    break@loop
                }
            }
        }
        return Report(
            accepted = accepted,
            rejected = rejected,
            isolated = isolated,
            retryNeeded = retryNeeded,
            unauthorized = unauthorized,
            notSupported = notSupported,
            outcomes = outcomes
        )
    }
}
