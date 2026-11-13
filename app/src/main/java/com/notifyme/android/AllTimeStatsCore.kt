// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

/**
 * 采集诊断页「各来源累计统计」的聚合口径（W4）。
 *
 * 为什么抽出来：这段聚合此前以私有类 AllTimeSummary 形式住在
 * CaptureDiagnosticsActivity 里，靠 MessageStore.readRecent(this, 200000) 取数。
 * 两处都是错的：
 *  1. readRecent 的窗口是 MessageStoreCore.TAIL_LINES = 5000 行 —— 传 200000 进去
 *     也只拿到尾部 5000 条，于是「累计消息 / 会话数 / 群聊数」在历史超过 5000 行后
 *     静默偏小；而截断提示的判据 totalLines > total 从 5000 行起就恒真、
 *     200000 这个上限又几乎永远达不到，提示要么误报要么永不触发。
 *  2. 聚合本身是纯函数，却住在 Activity 私有类里 → 进不了 app/src/test。
 *
 * 口径（与页面文案一一对应）：
 *  - total = 参与统计的消息条数（= 扫到且能解析的行数）；
 *  - conversations = 全库去重会话数（跨 App 用 (pkg, conversation) 复合键）；
 *  - selfMessages = sender == ChatMessage.SENDER_SELF 的条数；
 *  - groups = 该 App 下 isGroup 的会话数（不是消息数）；
 *  - latest = 该 App 内最大 timestamp（<= 0 不参与，保持 0 表示「没有可用时间」）；
 *  - byPkg 按包名升序，保证同一份数据每次渲染顺序一致。
 *
 * 「扫了多少行」是性能事实，「文件有多少行」是数据事实，两件事由 [capped] 分开判定 ——
 * 混用就会出现上面那种误报。
 */
internal object AllTimeStatsCore {

    /**
     * 一次聚合最多扫多少条原始行（尾部窗口）。
     *
     * 旧实现写着 200000，但它根本走不到那个量级（真窗口只有 5000 行）；这里按「真的会读到」
     * 的量级定：50000 行 ≈ 个人设备数年的通知量，峰值内存只有尾部行字符串本身，
     * 解析出的消息对象边扫边丢（[Accumulator]），不整批驻留。
     */
    const val SCAN_LINES = 50_000

    /** 单个来源 App 的累计统计。 */
    data class PkgStat(
        val pkg: String,
        val messages: Int,
        val conversations: Int,
        val groups: Int,
        val latest: Long
    )

    /** 一次聚合的结果（与页面的汇总行 + 每个 App 一段对应）。 */
    data class Summary(
        val total: Int,
        val conversations: Int,
        val selfMessages: Int,
        val byPkg: List<PkgStat>
    )

    /** 流式扫描结果：聚合值 + 实际扫到的原始行数（用于判截断）。 */
    data class Scan(val summary: Summary, val scannedLines: Int)

    /**
     * 单遍累加器：边读边丢，不保留整批消息。
     *
     * 为什么不是 summarize(list) 一条路径：诊断页要扫几万行，把解析出的 ChatMessage
     * 全存成 List 再聚合，等于为了让统计多占数万个对象。累加器让内存只与本轮的
     * 去重集合规模相关，并且天然可以在 JVM 单测里逐条喂。
     */
    class Accumulator {
        private val conversations = HashSet<ConvKey>()
        private val countByPkg = HashMap<String, Int>()
        private val convsByPkg = HashMap<String, HashSet<ConvKey>>()
        private val groupConvsByPkg = HashMap<String, HashSet<ConvKey>>()
        private val latestByPkg = HashMap<String, Long>()
        private var selfMessages = 0
        private var total = 0

        fun add(msg: ChatMessage) {
            total += 1
            conversations.add(msg.convKey)
            if (msg.isSelf) selfMessages += 1
            countByPkg[msg.pkg] = (countByPkg[msg.pkg] ?: 0) + 1
            convsByPkg.getOrPut(msg.pkg) { HashSet() }.add(msg.convKey)
            if (msg.isGroup) {
                groupConvsByPkg.getOrPut(msg.pkg) { HashSet() }.add(msg.convKey)
            }
            if (msg.timestamp > 0) {
                val cur = latestByPkg[msg.pkg] ?: 0L
                if (msg.timestamp > cur) latestByPkg[msg.pkg] = msg.timestamp
            }
        }

        fun finish(): Summary {
            val stats = countByPkg.keys.sorted().map { pkg ->
                PkgStat(
                    pkg = pkg,
                    messages = countByPkg.getValue(pkg),
                    conversations = convsByPkg[pkg]?.size ?: 0,
                    groups = groupConvsByPkg[pkg]?.size ?: 0,
                    latest = latestByPkg[pkg] ?: 0L
                )
            }
            return Summary(
                total = total,
                conversations = conversations.size,
                selfMessages = selfMessages,
                byPkg = stats
            )
        }
    }

    /** 聚合一批已在内存里的消息（测试与小数据量场景）。 */
    fun summarize(messages: List<ChatMessage>): Summary {
        val acc = Accumulator()
        for (msg in messages) acc.add(msg)
        return acc.finish()
    }

    /**
     * 本次统计是否没覆盖全文件（尾部窗口没读到文件头）。
     *
     * 只能拿「文件原始行数」与「实际扫描行数」比：拿 summary.total 比 totalLines 会把
     * 损坏行也算成「被截断」，而 summary.total 又受解析失败影响，两边都不是同一个量。
     */
    fun capped(totalLines: Int, scannedLines: Int): Boolean = totalLines > scannedLines
}
