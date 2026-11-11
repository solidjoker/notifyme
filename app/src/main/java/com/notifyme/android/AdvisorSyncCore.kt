// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

/**
 * 顾问通道的**分批**策略与**水位门槛**（纯逻辑、无 Android 依赖，可 JVM 单测）。
 *
 * 背景（上一轮报告外发现第 4 条）：顾问报告原先被拼成**一个** JSON 数组一次 POST，
 * 撞服务端 `MAX_CONTENT_LENGTH = 8 MiB`（server/app.py:59）拿 413，413 不算成功
 * → 顾问报告永远推不上去（与评审 C9 同源问题）。
 *
 * 现在按 [SyncBatchCore] 的双阈值（条数 ≤ 200 且序列化后 ≤ 1 MiB）切批后逐批提交；
 * 水位只在「本轮所有批次都整批接受」时推进——被拒报告绝不能被标成已同步
 * （否则永久丢失），下轮整体重报，服务端按 report_id 幂等去重。
 */
internal object AdvisorSyncCore {

    /** 一条待上报的顾问报告：report 去重键 + 生成时间 + 序列化后的 JSON 片段。 */
    internal data class Entry(
        val reportId: String,
        val createdAt: Long,
        val json: String
    )

    /**
     * 切批：条数 + 字节双阈值，与 [SyncBatchCore.splitBatches] 完全一致。
     *
     * 不变量：条目不丢、不改序；单条就超过字节阈值时它独占一批（没法再拆）。
     */
    fun splitBatches(entries: List<Entry>): List<List<Entry>> {
        // 把 report 维度的条目包成带序号的 Item，切批后再按序号映射回 Entry
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
}
