// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

/**
 * 已创建提醒列表的「分组 / 排序 / 筛选」口径（纯函数，可在 JVM 单测里直接跑）。
 *
 * 为什么要有这一层：列表页原先只按 `created_at` 倒序平铺全部记录 ——
 *
 *  - **需要用户动手的条目会沉底**：一条 3 天前 FAILED 的记录（无日历账户 / 没给通知
 *    权限）排在最新一条旁边看起来一样，实际沉到列表末尾，用户永远看不到「重试」按钮；
 *  - **已过期与未到期混在一起**：`event_time` 早就过去的记录和明天的提醒同样是普通卡片，
 *    用户得自己逐条读时间才知道哪条还有用；
 *  - **没有筛选**：提醒攒到几十条后想找「哪几条失败了」只能滚到底。
 *
 * 所以这里把「先看什么」固化成三条契约（[groupOf] + [arrange]）：
 *
 *  1. **组顺序 = 需要处理 → 生效中 → 已过期**。需要处理（[GROUP_ATTENTION]）包含
 *     `failed` 与「`intent` 且事件还没到点」两类；已过期（[GROUP_EXPIRED]）单列，
 *     因为对它们「再次唤起」「重试」都已无意义（事件时间过去了），只剩删除。
 *  2. **`failed` 排在 `intent` 之前**：失败是「什么都没落地」，比「已唤起日历待用户保存」
 *     更需要立刻处理。
 *  3. **组内时间方向不同**：需要处理与生效中按事件时间**升序**（越近的越先看），
 *     已过期按事件时间**降序**（刚过期的在上面）。`event_time <= 0`（当时解析不出时间）
 *     一律排在组内最后 —— 不知道时间就不该抢占视线。
 *
 * 判定上还有两条保守规则（别改成"更聪明"的推断）：
 *  - `event_time <= 0` **不算过期**：没有时间信息的记录，标成过期会误导用户去删一条
 *    可能还有效的提醒（历史记录 / 解析失败都是这个形态）；
 *  - `failed` **永远不算过期**：失败记录的时间即使在过去，它的语义仍是「等你重试」，
 *    归进过期组等于把唯一该处理的条目藏起来。
 *
 * 排序稳定性：`sortedWith` 在 JVM 上是稳定排序，同键条目保持传入顺序（调用方传
 * `ReminderStore.readAll` 的结果，即 `created_at` 倒序），所以「组内完全同键」时
 * 仍是新建的在上。
 */
internal object ReminderListCore {

    /** 筛选码值：全部（不过滤）。 */
    const val FILTER_ALL = ""

    /** 筛选 / 分组码值：需要用户动手（失败 / 待手动保存且未到期）。 */
    const val FILTER_ATTENTION = "attention"

    /** 筛选 / 分组码值：生效中（未到期、且不需要用户动手）。 */
    const val FILTER_ACTIVE = "active"

    /** 筛选 / 分组码值：已过期（事件时间已过去，只剩删除）。 */
    const val FILTER_EXPIRED = "expired"

    /** chip 从左到右的顺序：全部 → 需要处理 → 生效中 → 已过期。 */
    val FILTERS = listOf(FILTER_ALL, FILTER_ATTENTION, FILTER_ACTIVE, FILTER_EXPIRED)

    /** 把落盘 / 传入的筛选码值归一；未知值与空串一律回落 [FILTER_ALL]。 */
    fun normalizeFilter(raw: String?): String {
        val text = raw?.trim().orEmpty().lowercase()
        return if (text in FILTERS) text else FILTER_ALL
    }

    /** 该状态是否需要用户动手（`failed` 与 `intent`）；其它状态为 false。 */
    fun isAttention(status: String): Boolean =
        status == ReminderRecord.STATUS_FAILED || status == ReminderRecord.STATUS_INTENT

    /**
     * 是否已过期：事件时间已过去，且这条记录的意义不在「等用户重试」。
     *
     * `event_time <= 0`（无时间）与 `failed` 一律判 false —— 见类注释的两条保守规则。
     */
    fun isExpired(status: String, eventTime: Long, nowMs: Long): Boolean {
        if (eventTime <= 0L) return false
        if (status == ReminderRecord.STATUS_FAILED) return false
        return eventTime <= nowMs
    }

    /**
     * 分组码值（三个之一）。
     *
     * 判定顺序有意义：`failed` 先判（永远算需要处理，绝不能落进过期组），
     * 再看是否过期（过了期的 `intent` 重放已无意义，归过期），最后才看 `intent`。
     */
    fun groupOf(status: String, eventTime: Long, nowMs: Long): String = when {
        status == ReminderRecord.STATUS_FAILED -> FILTER_ATTENTION
        isExpired(status, eventTime, nowMs) -> FILTER_EXPIRED
        status == ReminderRecord.STATUS_INTENT -> FILTER_ATTENTION
        else -> FILTER_ACTIVE
    }

    /** 组顺序权重：需要处理 0 / 生效中 1 / 已过期 2；未知码值排最后。 */
    private fun groupRank(group: String): Int = when (group) {
        FILTER_ATTENTION -> 0
        FILTER_ACTIVE -> 1
        FILTER_EXPIRED -> 2
        else -> 3
    }

    /** `failed` 0 / `intent` 1 / 其它 2 —— 失败最急。 */
    private fun attentionRank(status: String): Int = when (status) {
        ReminderRecord.STATUS_FAILED -> 0
        ReminderRecord.STATUS_INTENT -> 1
        else -> 2
    }

    /**
     * 排序：组顺序 → （需要处理组内）状态优先级 → 组内时间方向。
     *
     * @param items        原始记录（调用方通常传 `created_at` 倒序）
     * @param nowMs        判定过期的"当下"
     * @param statusOf     取 [ReminderRecord.status]
     * @param eventTimeOf  取 `event_time`

     比较器**只比到 event_time**，不再拿 created_at 兜底：多一级键会把「同键记录保持
     传入顺序」这条稳定性承诺打掉（同事件时间、不同创建时间的两条会被重排）。调用方
     传 `ReminderStore.readAll` 的结果（created_at 倒序），JVM 稳定排序即可保证
     完全同键时新建的在上。
     */
    fun <T> arrange(
        items: List<T>,
        nowMs: Long,
        statusOf: (T) -> String,
        eventTimeOf: (T) -> Long
    ): List<T> {
        if (items.size < 2) return items
        // 排序键一次算完再比，避免比较器里反复 groupOf（O(n log n) 次状态判定）
        val keyed = items.map { item ->
            val status = statusOf(item)
            val time = eventTimeOf(item)
            val group = groupOf(status, time, nowMs)
            Arranged(
                item = item,
                groupRank = groupRank(group),
                attentionRank = if (group == FILTER_ATTENTION) attentionRank(status) else 0,
                untimed = if (time <= 0L) 1 else 0,
                // 已过期按事件时间倒序（取负），其余升序 —— 过期组内 time 必 > 0，不会溢出
                timeKey = if (group == FILTER_EXPIRED) -time else time
            )
        }
        return keyed.sortedWith(
            compareBy<Arranged<T>> { it.groupRank }
                .thenBy { it.attentionRank }
                .thenBy { it.untimed }
                .thenBy { it.timeKey }
        ).map { it.item }
    }

    /** [arrange] 的排序键（私有包装，避免多次解析）。 */
    private class Arranged<T>(
        val item: T,
        val groupRank: Int,
        val attentionRank: Int,
        val untimed: Int,
        val timeKey: Long
    )

    /**
     * 只留 [filter] 对应的记录；[FILTER_ALL] 原样返回（不复制、不重排）。
     */
    fun <T> keep(
        items: List<T>,
        filter: String,
        nowMs: Long,
        statusOf: (T) -> String,
        eventTimeOf: (T) -> Long
    ): List<T> {
        val target = normalizeFilter(filter)
        if (target == FILTER_ALL) return items
        return items.filter { groupOf(statusOf(it), eventTimeOf(it), nowMs) == target }
    }

    /**
     * chip 上的数量：[FILTER_ALL] 是总数，其余按 [groupOf] 分组计数。
     * 返回的 Map 一定包含 [FILTERS] 里的全部键（没有的一律 0），方便 UI 直接取值。
     */
    fun <T> counts(
        items: List<T>,
        nowMs: Long,
        statusOf: (T) -> String,
        eventTimeOf: (T) -> Long
    ): Map<String, Int> {
        val result = LinkedHashMap<String, Int>()
        FILTERS.forEach { result[it] = 0 }
        result[FILTER_ALL] = items.size
        items.forEach { item ->
            val group = groupOf(statusOf(item), eventTimeOf(item), nowMs)
            result[group] = (result[group] ?: 0) + 1
        }
        return result
    }

    /**
     * 空态文案的码值（**不回中文字面量**，单测是纯 JVM 无资源）：
     *  - 完全没有记录 → `null`（调用方显示 `reminder_list_empty`）
     *  - 有记录但当前筛选为空 → 返回该筛选码值，调用方按它选「本页没有 XX 的提醒」文案
     */
    fun emptyFilter(totalCount: Int, filter: String): String? {
        if (totalCount == 0) return null
        val target = normalizeFilter(filter)
        return if (target == FILTER_ALL) null else target
    }
}
