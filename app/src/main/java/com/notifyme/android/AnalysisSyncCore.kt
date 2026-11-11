// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

/**
 * 分析通道的**分批**策略与**水位门槛**（纯逻辑、无 Android 依赖，可 JVM 单测）。
 *
 * 背景（评审 C9 同源问题）：未同步的分析 case 原先被拼成**一个** JSON 数组一次 POST，
 * 而服务端 `MAX_CONTENT_LENGTH = 8 MiB`（server/app.py:59）——s1/s2 文本一大就撞 413，
 * 413 不算成功 → 分析记录永远推不上去。
 *
 * 现在按**双阈值**切批（条数 ≤ [SyncBatchCore.MAX_BATCH_ITEMS] 且序列化后
 * ≤ [SyncBatchCore.MAX_BATCH_BYTES]），阈值实现直接复用 [SyncBatchCore]，
 * 本文件只负责「case 维度」的条目形态与水位判定，不改动既有契约。
 *
 * 水位门槛（评审 P0-3）：服务端在「部分 case 被拒」时返回 207，被拒的 case
 * **绝不能**被标成已同步 —— 水位一旦越过它们，这些 case 就永久消失了。
 */
internal object AnalysisSyncCore {

    /** 一条待上报的分析记录：case 去重键 + 分析时间 + 序列化后的 JSON 片段。 */
    internal data class Entry(
        val caseId: String,
        val analyzedAt: Long,
        val json: String
    )

    /**
     * 切批：条数 + 字节双阈值，与 [SyncBatchCore.splitBatches] 完全一致。
     *
     * 不变量：条目不丢、不改序；每个批次要么条数 ≤ [SyncBatchCore.MAX_BATCH_ITEMS]，
     * 要么单条就超过字节阈值（此时它独占一批，没法再拆）。
     */
    fun splitBatches(entries: List<Entry>): List<List<Entry>> {
        // 把 case 维度的条目包成带序号的 Item，切批后再按序号映射回 Entry。
        val items = entries.mapIndexed { index, entry ->
            SyncBatchCore.Item(index.toLong(), entry.json)
        }
        return SyncBatchCore.splitBatches(items).map { batch ->
            batch.map { item -> entries[item.id.toInt()] }
        }
    }

    /** 拼一批的 JSON 数组体：`[{...},{...}]`。 */
    fun buildBody(batch: List<Entry>): String =
        batch.joinToString(separator = ",", prefix = "[", postfix = "]") { it.json }

    /** 单页最多读多少条未同步记录。 */
    const val PAGE_SIZE = 200

    /**
     * 单轮最多读多少页（上限保护：最多 PAGE_SIZE * MAX_PAGES = 1000 条进内存）。
     *
     * 剩下的记录下一轮继续读 —— 扫描游标按字节位置推进，水位停在「已结算前缀」，
     * 被拒/失败的记录 analyzedAt 仍大于水位，下一轮会被重新读到，不会被跳过。
     */
    const val MAX_PAGES = 5

}
