// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

/**
 * 待发队列的**被拒计数**策略（纯逻辑、无 Android 依赖，可 JVM 单测）。
 *
 * 背景（上一轮报告外发现第 1 条）：服务端「部分条目被拒」时返回 207，端上把被拒条目
 * 留在待发队列里等下轮重试——但**没有任何丢弃上限**：只要服务端一直拒某一条（字段校验
 * 不过、去重键冲突等），这条就永远留在队列里，队列被无限撑大。
 *
 * 现在给每条队列条目挂两个字段（写在 pending.jsonl 那一行里，与消息字段同层）：
 *  - `rejected_count`：累计被拒次数；
 *  - `first_rejected_at`：首次被拒时间（毫秒，0 = 从未被拒）。
 *
 * 累计被拒达到 [MAX_REJECT_COUNT] 次即丢弃该条；被拒后长期（[REJECT_TTL_MS]）仍没能
 * 上报成功的条目，在读取队列时顺手清理掉（避免「读一次搬一次」的僵尸条目）。
 */
internal object PendingRejectCore {

    /** 累计被拒次数上限：达到即丢弃该条（队列不再被永久被拒的条目撑大）。 */
    const val MAX_REJECT_COUNT = 3

    /** 被拒条目的最长滞留时间：7 天（与本地数据保留策略同口径）。 */
    const val REJECT_TTL_MS = 7L * 24 * 60 * 60 * 1000

    /** 一次被拒之后的处置结果。 */
    data class Decision(
        /** 累计被拒次数（含本次）。 */
        val rejectedCount: Int,
        /** 首次被拒时间（毫秒）；本次是第一次被拒时填 [nowMs]。 */
        val firstRejectedAt: Long,
        /** true = 达到上限，该条应被丢弃。 */
        val dropped: Boolean
    )

    /**
     * 记一次被拒：累计次数 +1，首拒时间**只写一次**（后续被拒沿用首次时间，
     * 这样过期窗口是从第一次被拒开始算，而不是被无限续期）。
     *
     * @param rejectedCount 当前累计被拒次数
     * @param firstRejectedAt 首次被拒时间；0 表示此前从未被拒
     * @param nowMs 本次被拒时间
     * @param maxCount 丢弃阈值；默认 [MAX_REJECT_COUNT]
     */
    fun onRejected(
        rejectedCount: Int,
        firstRejectedAt: Long,
        nowMs: Long,
        maxCount: Int = MAX_REJECT_COUNT
    ): Decision {
        val count = rejectedCount + 1
        val first = if (firstRejectedAt > 0L) firstRejectedAt else nowMs
        return Decision(count, first, count >= maxCount)
    }

    /**
     * 过期判定：被拒过（[firstRejectedAt] > 0）且距首次被拒已超过 [ttlMs]。
     *
     * 从未被拒过的条目**永远不过期**——它们本来就该继续排队上报，
     * 不该被这个清理逻辑误伤。
     */
    fun isExpired(firstRejectedAt: Long, nowMs: Long, ttlMs: Long = REJECT_TTL_MS): Boolean =
        firstRejectedAt > 0L && nowMs - firstRejectedAt >= ttlMs
}
