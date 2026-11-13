// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

/**
 * 首页信息流的取样口径（每个会话各留几条，而不是全库最近 N 条）。
 *
 * 为什么要抽：首页 `buildFeed` 此前是 `MessageStore.readRecent(500)` —— 先在全库范围取
 * 最近 500 条（**跨所有会话**）再按会话分组，于是「某个会话能不能出现在首页」取决于
 * 别的会话有多活跃：几个群刷满窗口，安静但有待办的会话一条都进不来，首页待办区
 * （`TodoPanelCore.selectTodos` 的 `homepageVisible`）也就永远看不到它。
 * 这是继会话详情页 / 日历补上下文 / 分析列表回看原文 / 分析候选之后的**第五处**同源隐患，
 * 前四处已由 [ConvWindowCore] / [CandidateWindowCore] / [AnalysisWindowCore] 收口。
 *
 * 与 [CandidateWindowCore] 的分工：那边给「分析候选」留 50 条（够 `AnalysisCase.WINDOW_SIZE`）；
 * 这边给**人看的**信息流留更多——用户在首页会按天折叠着往回翻，条数太少就翻不出东西。
 *
 * 名额**按会话数自适应**：会话越多个会话留得越少，但每个会话至少 [MIN_PER_CONV] 条，
 * 全表总量不超过 [TOTAL_BUDGET] 条。这样「单个会话再吵也只占自己的名额」与
 * 「几百个会话不会把内存撑爆」两件事同时成立。
 */
internal object FeedWindowCore {

    /**
     * 首页的尾读行窗口。
     *
     * 比 [MessageStoreCore.TAIL_LINES]（5000）大：首页要按会话各留一窗，
     * 行窗口太浅时安静会话本来就排在文件靠前的位置，照样读不到。
     * 20000 行的解析代价在后台线程可接受（首页本来就整表解析分析记录）。
     */
    const val REQUEST_LINES = 20_000

    /**
     * 单个会话的条数上限。
     *
     * 取 200：够翻几天的历史（按天折叠后一天通常几十条），又不至于让单个群独占整页。
     */
    const val PER_CONV = 200

    /**
     * 单个会话的保底条数。
     *
     * 取 20 = 2 倍 [AnalysisCase.WINDOW_SIZE]：即便会话数多到把名额压到最低，
     * 每个会话仍能凑出一个完整分析窗口，首页角标与该会话的分析结论才不会错位。
     */
    const val MIN_PER_CONV = 20

    /** 全表消息条数上限（所有会话加起来）。 */
    const val TOTAL_BUDGET = 6_000

    /** 首页最多列多少个会话（再多也不该一次全渲染，宁可让用户用搜索 / 筛选）。 */
    const val MAX_CONVERSATIONS = 200

    /**
     * 给定会话数，算出每个会话该保留多少条。
     *
     * `budget / convCount` 再夹进 `[minPerConv, maxPerConv]`：会话少就给满上限（人均富余），
     * 会话多就按均值摊薄但不低于保底。
     *
     * 会话数 `<= 0`、`totalBudget <= 0` 或 `maxPerConv <= 0` 返回 0 —— 与
     * [CandidateWindowCore.group] 的 `perConv <= 0` 同风格：调用方传错时保守退化，不抛异常。
     */
    fun perConvFor(
        convCount: Int,
        totalBudget: Int = TOTAL_BUDGET,
        maxPerConv: Int = PER_CONV,
        minPerConv: Int = MIN_PER_CONV
    ): Int {
        if (convCount <= 0 || totalBudget <= 0 || maxPerConv <= 0) return 0
        val upper = maxPerConv
        val lower = minPerConv.coerceIn(0, upper)
        return (totalBudget / convCount).coerceIn(lower, upper)
    }

    /**
     * 一个会话在首页窗口里的切片。
     *
     * @param messages  该会话最近若干条，按时间**升序**（气泡从上到下、天组正序）
     * @param truncated 该会话还有更早的消息没进窗口 —— 会话头要把条数写成 `N+ 条`，
     *                  不能把「窗口里 N 条」说成本会话总共只有 N 条
     */
    data class Bucket(
        val key: ConvKey,
        val messages: List<ChatMessage>,
        val truncated: Boolean
    )

    /**
     * 一次取样的结果。
     *
     * @param complete 行窗口是否覆盖到了文件头。`false` 表示窗口外还有更早的消息，
     *                 任何「刚好顶到名额上限」的会话都可能仍有历史没读进来
     */
    data class Result(
        val buckets: List<Bucket>,
        val complete: Boolean
    ) {
        val size: Int get() = buckets.size

        fun keys(): List<ConvKey> = buckets.map { it.key }

        fun toMap(): Map<ConvKey, List<ChatMessage>> =
            LinkedHashMap<ConvKey, List<ChatMessage>>(buckets.size).also { out ->
                for (bucket in buckets) out[bucket.key] = bucket.messages
            }
    }

    /**
     * 把一份消息列表切成「每会话一窗」，并按最近活跃度倒序。
     *
     * 排序口径与旧实现 `groupBy { convKey }.sortedByDescending { list.last().timestamp }` 一致：
     * 谁的**最新一条**更新谁在前；并列时保持会话首次出现顺序（`sortedByDescending` 是稳定排序），
     * 因此同一份输入总能得到同一个首页顺序（可复现）。
     *
     * `timestamp <= 0`（未标注日期）按 0 参与排序，即排在该会话最前，不会被更晚的真实消息顶掉。
     *
     * @param complete 行窗口是否已读到文件头（决定 `truncated` 的判定，见 [Bucket]）
     */
    fun group(
        messages: List<ChatMessage>,
        maxPerConv: Int = PER_CONV,
        maxConversations: Int = MAX_CONVERSATIONS,
        totalBudget: Int = TOTAL_BUDGET,
        complete: Boolean = true
    ): Result {
        if (messages.isEmpty() || maxPerConv <= 0 || maxConversations <= 0) {
            return Result(emptyList(), complete)
        }
        val buckets = LinkedHashMap<ConvKey, MutableList<ChatMessage>>()
        for (message in messages) {
            buckets.getOrPut(message.convKey) { ArrayList() }.add(message)
        }
        val ordered = buckets.entries
            .sortedByDescending { (_, bucket) ->
                bucket.maxOf { if (it.timestamp <= 0L) 0L else it.timestamp }
            }
            .take(maxConversations)
        val keep = minOf(maxPerConv, perConvFor(ordered.size, totalBudget, maxPerConv))
        if (keep <= 0) return Result(emptyList(), complete)
        val out = ArrayList<Bucket>(ordered.size)
        for ((key, bucket) in ordered) {
            val ascending = bucket.sortedBy { if (it.timestamp <= 0L) 0L else it.timestamp }
            val truncated = if (complete) {
                ascending.size > keep
            } else {
                ascending.size >= keep
            }
            out += Bucket(
                key = key,
                messages = if (ascending.size <= keep) ascending else ascending.takeLast(keep),
                truncated = truncated
            )
        }
        return Result(out, complete)
    }
}
