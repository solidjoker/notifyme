// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

/**
 * 待发队列的**分批**策略（纯逻辑，可单测）。
 *
 * 背景（评审 C9）：端上原来把整个待同步队列拼成**一个** JSON 数组一次 POST，
 * 而服务端 `MAX_CONTENT_LENGTH = 8 MiB`（server/app.py:59）。队列一大就 413，
 * 而端上对任何非 2xx 一律 retry → 死循环重推同一个超大请求，永远推不上去。
 *
 * 现在按**双阈值**切批：每批条数 ≤ [MAX_BATCH_ITEMS]，且序列化后字节数 ≤ [MAX_BATCH_BYTES]，
 * 任一超限就切下一批 → 单批远小于服务端上限，不会再撞 413。
 */
internal object SyncBatchCore {

    /** 单批最大条数。 */
    const val MAX_BATCH_ITEMS = 200

    /** 单批最大字节数（1 MiB），远小于服务端 8 MiB 上限。 */
    const val MAX_BATCH_BYTES = 1 shl 20

    /** 批次里的一条待发记录：队列 id + 序列化后的 JSON 片段。 */
    internal data class Item(val id: Long, val json: String)

    /**
     * 切批。
     *
     * 不变量：条目不丢、不改序；每个批次要么条数 ≤ [MAX_BATCH_ITEMS]，要么单条就超过
     * 字节阈值（此时它独占一批，没法再拆）。
     */
    fun splitBatches(items: List<Item>): List<List<Item>> {
        val batches = mutableListOf<List<Item>>()
        var current = mutableListOf<Item>()
        var currentBytes = 2 // 一对中括号
        for (item in items) {
            val size = item.json.toByteArray(Charsets.UTF_8).size
            val add = size + if (current.isEmpty()) 0 else 1 // 多一个逗号
            val overItems = current.size >= MAX_BATCH_ITEMS
            val overBytes = current.isNotEmpty() && currentBytes + add > MAX_BATCH_BYTES
            if (overItems || overBytes) {
                batches += current
                current = mutableListOf()
                currentBytes = 2
            }
            current += item
            currentBytes += add
        }
        if (current.isNotEmpty()) batches += current
        return batches
    }

    /** 拼一批的 JSON 数组体：`["a","b"]`。 */
    fun buildBody(batch: List<Item>): String =
        batch.joinToString(separator = ",", prefix = "[", postfix = "]") { it.json }

    /**
     * 413 二分：把超限批次拆成两半继续试。
     *
     * 背景（第五轮审计）：分批后单批只有 ~1 MiB，能撞上服务端 8 MiB 上限的
     * 只可能是「**单条就超限**」的毒丸条目。这种条目没法再切分，若按普通失败
     * 处理（break + Result.retry），它会永久卡住该批**及其后所有队列条目**，
     * 每轮退避无限重推 —— 队列再也不流动。
     *
     * 处置：拆小重试；拆到单条仍超限 -> 返回空列表，调用方单独隔离该条目并继续下一批。
     *
     * @return 需要继续尝试的子批；空列表 = 已经拆到单条（该条目就是毒丸）
     */
    fun <T> bisect(batch: List<T>): List<List<T>> {
        if (batch.size <= 1) return emptyList()
        val mid = batch.size / 2
        return listOf(
            batch.subList(0, mid).toList(),
            batch.subList(mid, batch.size).toList()
        )
    }

    /** 一批序列化后的字节数（含中括号与逗号）。 */
    fun bodySize(batch: List<Item>): Int {
        if (batch.isEmpty()) return 2
        return 2 + batch.sumOf { it.json.toByteArray(Charsets.UTF_8).size } + (batch.size - 1)
    }
}
