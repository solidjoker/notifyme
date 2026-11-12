// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

/**
 * 分析候选窗口的口径（[AnalysisWorker] 每轮「哪些会话值得算」的输入）。
 *
 * 为什么要抽：候选集合此前是
 * `MessageStore.readRecent(context, 500).groupBy { it.convKey }` —— 先在全库范围取
 * 最近 500 条（**跨所有会话**）再分组，于是「某个会话能不能进分析候选」取决于
 * 别的会话有多活跃：几个群刷满 500 条，安静但有待办的会话在窗口里一条都没有，
 * 永远不会被分析（而数据其实还在 messages.jsonl 里）。这是继会话详情页 / 日历补上下文 /
 * 分析列表回看原文之后的**第四处**同源隐患，前三处已由 [ConvWindowCore] 收口。
 *
 * 与 [ConvWindowCore] 的分工：那边回答「某个会话在给定窗口里怎么取」；
 * 这边回答「从一份尾部窗口里怎么给每个会话留下够用的候选条数」。
 *
 * 条数口径：[PER_CONV] 只需覆盖 [AnalysisCase.WINDOW_SIZE]（10 条）再留出范围过滤的余量。
 * 范围是过去时段（自定义区间）时，比区间更新的消息会先占掉保留名额，余量越大越不容易
 * 让区间内的消息整块消失；但再多也不该退化成「把文件全读进来」——尾读窗口
 * （[MessageStoreCore.TAIL_LINES] 行）才是硬边界。
 */
internal object CandidateWindowCore {

    /**
     * 每个会话保留的候选条数。
     *
     * 取 50 = 5 倍 [AnalysisCase.WINDOW_SIZE]：一个 case 只需要最近 10 条，
     * 剩下 40 条是给「范围过滤先把新消息砍掉」留的余量（自定义区间落在过去时）。
     * 别调到与尾读窗口同量级：那等于放弃「每会话只留一窗」的内存约束。
     */
    const val PER_CONV = 50

    /**
     * 把一份消息列表按会话分组，每组按时间**升序**只保留最近 [perConv] 条。
     *
     * 保留口径与 [ConvWindowCore.slice] 一致：`timestamp <= 0`（未标注日期）按 0 参与排序，
     * 即排在该会话最前，不会被更晚的真实消息顶掉。
     *
     * 分组顺序 = 各会话**首次出现**的顺序（LinkedHashMap），与旧实现
     * `groupBy { it.convKey }` 的行为一致；后续 `sortedBy { windowEnd }` 是稳定排序，
     * 所以同一份输入总能得到同一轮入选顺序（可复现）。
     *
     * @param perConv 每会话最多保留多少条；`<= 0` 表示不保留任何候选（调用方传错时的保守退化）
     */
    fun group(
        messages: List<ChatMessage>,
        perConv: Int = PER_CONV
    ): Map<ConvKey, List<ChatMessage>> {
        if (messages.isEmpty() || perConv <= 0) return emptyMap()
        val buckets = LinkedHashMap<ConvKey, MutableList<ChatMessage>>()
        for (message in messages) {
            buckets.getOrPut(message.convKey) { ArrayList() }.add(message)
        }
        val out = LinkedHashMap<ConvKey, List<ChatMessage>>(buckets.size)
        for ((key, bucket) in buckets) {
            val ordered = bucket.sortedBy { if (it.timestamp <= 0L) 0L else it.timestamp }
            out[key] = if (ordered.size <= perConv) ordered else ordered.takeLast(perConv)
        }
        return out
    }

    /**
     * 候选集合里「够不够格进分析」的会话数统计口径：与 [group] 的键数相同。
     * 单独留一个函数只是为了让日志与测试共用同一句话，不含隐藏过滤。
     */
    fun candidateCount(groups: Map<ConvKey, List<ChatMessage>>): Int = groups.size
}
