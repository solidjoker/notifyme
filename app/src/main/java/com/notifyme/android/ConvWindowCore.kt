// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

/**
 * 会话详情页的「按会话取消息窗口」口径。
 *
 * 为什么要抽：会话详情页此前是
 * `MessageStore.readRecent(500).filter { it.convKey == key }` —— 先在全库范围取
 * 最近 500 条（**跨所有会话**）再过滤本会话，于是「本会话能看到多少历史」取决于
 * 别的会话有多活跃：其它会话刷满 500 条，这个会话就只剩几条甚至一条都没有
 * （详情页空白，可数据其实还在 messages.jsonl 里）。同一个窗口还被用来算
 * `chat_truncated` 的「共 N 条」—— 那句 N 是窗口内的命中数，不是本会话的真实条数。
 *
 * 本 Core 只回答纯函数问题，不碰 Context、不碰资源表：
 *  1. [requestLines]：为了「本会话凑够 N 条」该向 [JsonlStore.readTailLines] 要多少行；
 *  2. [slice]：从一个窗口里挑出某会话的消息并按时间升序（气泡从上到下）；
 *  3. [reachedHead]：这次尾读有没有读到文件头；
 *  4. [preview]：渲染前要的三个数（命中条数 / 是否超渲染上限 / 条数是否等于真实总数）。
 *
 * 与 [MessageStoreCore] 的分工：它负责「全库最近 N 条怎么取」，本 Core 负责
 * 「某个会话在给定窗口里怎么取」，谁也不改对方的排序假设。
 */
internal object ConvWindowCore {

    /** 全库窗口上限（跨会话）。与既有 `readRecent(500)` 口径一致，改它要连带看首页 / 悬浮面板 / 分析候选。 */
    const val ALL_LIMIT = 500

    /** 详情页渲染上限（气泡视图数）。超过只渲染最近这么多条并在顶部插提示，避免 inflate 几千项卡死。 */
    const val MAX_RENDER = 200

    /**
     * 请求行数 = 基础条数 × 8。详情页只服务单个会话，多读几行换
     * 「别的会话刷屏也不丢本会话历史」；解析成本只与窗口行数成正比，
     * [JsonlStore.readTailLines] 只读尾部字节窗口（1 MiB 起步，不够才 ×4），不会退化为全量读。
     */
    private const val REQUEST_MULTIPLIER = 8

    /**
     * 请求行数硬上限。行数给爆等于让尾读扩容一路读到文件头（退化为全量读取），
     * 大文件会 OOM —— 这是兜底，别为了「读全历史」调高。
     */
    const val MAX_REQUEST_LINES = 50_000

    /** 详情页请求行数（受 [MAX_REQUEST_LINES] 约束）。 */
    fun requestLines(limit: Int = ALL_LIMIT): Int =
        (limit.coerceAtLeast(1).toLong() * REQUEST_MULTIPLIER)
            .coerceAtMost(MAX_REQUEST_LINES.toLong())
            .toInt()

    /**
     * 一条消息是否属于 [key] 会话。
     *
     * 必须按 [ChatMessage.convKey]（pkg + 会话名）比，不能只比会话名：
     * 不同 App 里的同名会话会被拼成同一个。
     */
    fun isFor(message: ChatMessage, key: ConvKey): Boolean = message.convKey == key

    /**
     * 从 [haystack]（任意顺序，通常是 `readRecent` 的倒序结果）里取出该会话的消息，
     * 按**时间升序**返回（气泡从上到下）。超过 [limit] 时保留最近的 [limit] 条。
     *
     * `timestamp <= 0`（未标注日期）按 0 参与排序，即排在最前 —— 与既有
     * `sortedBy { it.timestamp }` 的显示顺序一致。别改成「排最后」：
     * a11y 直读估算失败的消息会跳到气泡流末尾，比它更晚的真实消息反而在它上面。
     */
    fun slice(
        haystack: List<ChatMessage>,
        key: ConvKey,
        limit: Int = Int.MAX_VALUE
    ): List<ChatMessage> {
        val hits = haystack.filter { isFor(it, key) }
        if (hits.isEmpty()) return emptyList()
        val ordered = hits.sortedBy { if (it.timestamp <= 0L) 0L else it.timestamp }
        return if (limit >= ordered.size) ordered else ordered.takeLast(limit)
    }

    /**
     * 这次尾读有没有读到文件头：请求 [requested] 行、实际只拿到 [linesRead] 行
     * （linesRead < requested）说明文件就这么长，窗口覆盖了全部记录。
     *
     * 当 linesRead == requested 时无法区分「文件刚好这么长」与「更早的行在窗口外」，
     * 保守地算「没读全」—— 宁可不说「共 N 条」，也不能报一个偏小的总数。
     */
    fun reachedHead(linesRead: Int, requested: Int): Boolean =
        requested <= 0 || linesRead < requested

    /**
     * 一次按会话尾读的结果。
     *
     * @param messages 已按时间升序、只含 [key] 会话、最多 [limit] 条
     * @param linesRead 尾读实际拿到的行数（含别的会话的行）
     * @param requested 尾读请求的行数
     * @param limit     本会话最多保留多少条（被砍掉时 [preview] 不会声称条数是总数）
     */
    data class Window(
        val key: ConvKey,
        val messages: List<ChatMessage>,
        val linesRead: Int,
        val requested: Int,
        val limit: Int
    ) {
        val size: Int get() = messages.size
    }

    /**
     * 渲染前口径。
     *
     * @param visible    本会话在该窗口里的命中条数
     * @param truncated  是否超过渲染上限（顶部要插截断提示）
     * @param countedAll 这个条数是否等于本会话的真实总数（只有「读到文件头」且「没被 limit 砍」才成立）
     */
    data class Preview(val visible: Int, val truncated: Boolean, val countedAll: Boolean)

    /** 由一次 [Window] 算渲染前口径。 */
    fun preview(window: Window, renderLimit: Int = MAX_RENDER): Preview {
        val visible = window.size
        return Preview(
            visible = visible,
            truncated = visible > renderLimit,
            countedAll = reachedHead(window.linesRead, window.requested) && visible < window.limit
        )
    }
}
