// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

/**
 * 「已结算前缀」水位（纯逻辑、无 Android 依赖，可 JVM 单测）。
 *
 * 背景（第五轮审计）：旧的水位门槛是「本轮所有批次都整批接受才推进」
 * （见 [SyncOutcomeCore.watermarkIfAllAccepted]）。这条门槛**过严且有洞**：
 *  - 过严：只要有一批被拒，**整轮**水位按住，本轮已经成功的记录也要整轮重传；
 *  - 有洞：旧逻辑里「全部记录都被隔离」时会 markSynced(全部 max analyzedAt)，
 *    水位会越过那些**从未上报**的记录 —— 用户把隐私模式切回后，它们
 *    analyzedAt <= 水位，扫描会当「已同步」跳过，**永远补报不了**（数据丢失）。
 *
 * 新口径：**水位只允许推进到「已结算前缀」**。
 *  - 已结算 = 本轮被服务端接受 ∪ 本轮新被隔离（隔离后不再上报、不再阻塞）；
 *  - 按时间正序遍历，遇到第一个**未结算**的记录就停 —— 水位绝不能越过它，
 *    否则这条记录永远补报不了；
 *  - 全部结算时行为与旧口径一致（推进到最大时间戳）。
 *
 * 纯函数：便于单测钉住「洞不被越过」这条口径。
 */
internal object SyncWatermarkCore {

    /** 一条参与水位计算的记录：业务 id + 时间戳（毫秒）。 */
    data class Entry(val id: String, val timestamp: Long)

    /**
     * 计算可推进的水位（已结算前缀的最大时间戳）。
     *
     * @param entries 本轮参与上报的记录（顺序不限，内部按时间正序处理；同时间戳保持原序）
     * @param settledIds 本轮已结算的记录 id（被接受 ∪ 新隔离）
     * @return 可推进的水位；没有已结算前缀时返回 null（调用方**不得** markSynced）
     */
    fun prefixWatermark(entries: List<Entry>, settledIds: Set<String>): Long? {
        var watermark: Long? = null
        for (entry in entries.sortedBy { it.timestamp }) {
            // 洞：水位不许越过 —— 越过的记录以后 analyzedAt <= 水位，永远补报不了
            if (entry.id !in settledIds) break
            watermark = maxOf(watermark ?: entry.timestamp, entry.timestamp)
        }
        return watermark
    }

    /**
     * 已结算前缀的长度（便于单测钉住「洞之后的东西没被算进来」）。
     */
    fun settledPrefixSize(entries: List<Entry>, settledIds: Set<String>): Int {
        var count = 0
        for (entry in entries.sortedBy { it.timestamp }) {
            if (entry.id !in settledIds) break
            count++
        }
        return count
    }
}
