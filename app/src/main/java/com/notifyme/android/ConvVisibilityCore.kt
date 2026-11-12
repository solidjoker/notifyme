// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

/**
 * 「这个会话在主页/悬浮面板里可不可见」的唯一口径（纯函数，可直接 JVM 单测）。
 *
 * 之前是同一条三段式判定被抄了四遍，且抄漏一处：
 *  - `MainActivity.buildFeed` 的 `enabledGroups`（关注名单 + 自身/系统源 + 启用集合）；
 *  - `MainActivity.buildFeed` 的 `todoVisibleKeys`（同上 + 首页来源筛选）；
 *  - `OverlayService.showPanel` 的 `visibleKeys`（同上，**漏了首页来源筛选**——
 *    首页筛到「飞书」时悬浮面板仍列微信待办，真机可复现）；
 *  - `AnalysisWorker.doWorkExclusive` 的分析候选门禁（同三段，但 forced 放行启用集合）。
 *
 * 四层门槛，顺序即 why() 的判断顺序（全部是 AND，顺序只影响「拦截原因」归到哪一类）：
 *  1. 关注名单：显式「不关注」的会话在主页与面板都不出现，分析也不做（M11）；
 *  2. 自身/系统源：[AppSourceRegistry.isBlocked] **任何情况都拦**，forced 也不例外——
 *     a11y 自抓噪声与自身包名不是「用户点名的会话」，放行只会污染分析与列表；
 *  3. 启用集合：[AppSourceStore.isEnabled]，被用户停掉的来源不进主页也不分析；
 *     forced 只放宽这一层（M12 点名会话 / forceKey / 会话级「立即分析」是显式指定，
 *     不该被采集开关悄悄拦下）；
 *  4. 首页 App 筛选：[AppFilterCore.matches]（空串＝全部恒通过）。
 *     只影响「展示」，不影响「分析」——所以分析门禁传 [AppFilterCore.ALL]。
 *
 * 两条容易写错的边界：
 *  - **不要因为 [AppSourceStore.isEnabled] 内部已经判过一次 blocked 就把这里的 blocked 删掉**：
 *    forced 分支绕过 isEnabled，此时只有这一行能拦住自身/系统源；
 *  - 拦截原因码（[WHY_UNWATCHED] / [WHY_BLOCKED] / [WHY_DISABLED] / [WHY_FILTERED]）
 *    是给计数与排查用的**码值**，Core 不碰资源与日志文案 —— 需要中文由调用方映射。
 *
 * 改门槛顺序、加第五层或改 forced 语义前先跑
 * `app/src/test/java/com/notifyme/android/ConvVisibilityCoreTest.kt`。
 */
internal object ConvVisibilityCore {

    /** 关注名单里被显式标为「不关注」（[WatchlistStore.getUnwatched] 的口径）。 */
    const val WHY_UNWATCHED = "unwatched"

    /** 自身包名或系统/噪声源（[AppSourceRegistry.isBlocked]）。 */
    const val WHY_BLOCKED = "blocked"

    /** 来源被用户停用，或白名单模式下不在白名单里（[AppSourceStore.isEnabled] 返 false）。 */
    const val WHY_DISABLED = "disabled"

    /** 首页来源筛选未命中（[AppFilterCore.matches] 返 false）。 */
    const val WHY_FILTERED = "filtered"

    /**
     * 判定一个会话为什么不可见：可见返回 null，否则返回拦截原因码。
     *
     * @param unwatchedIds [WatchlistStore.getUnwatched] 的结果集（会话 id，形如 `pkg|会话名`）
     * @param appFilter    首页来源筛选（空串＝全部）；分析门禁请传 [AppFilterCore.ALL]
     * @param blocked      该包的 [AppSourceRegistry.isBlocked] 结果，由调用方采集
     * @param enabled      该包的 [AppSourceStore.isEnabled] 结果，由调用方采集
     * @param forced       显式指定（forceKey / 点名会话）时放宽「启用集合」这一层
     */
    fun why(
        key: ConvKey,
        unwatchedIds: Set<String>,
        appFilter: String,
        blocked: Boolean,
        enabled: Boolean,
        forced: Boolean = false
    ): String? {
        if (key.id in unwatchedIds) return WHY_UNWATCHED
        if (blocked) return WHY_BLOCKED
        if (!forced && !enabled) return WHY_DISABLED
        if (!AppFilterCore.matches(key.pkg, appFilter)) return WHY_FILTERED
        return null
    }

    /** [why] 的布尔形态：主页/悬浮面板/分析门禁统一用它。 */
    fun isVisible(
        key: ConvKey,
        unwatchedIds: Set<String>,
        appFilter: String,
        blocked: Boolean,
        enabled: Boolean,
        forced: Boolean = false
    ): Boolean = why(key, unwatchedIds, appFilter, blocked, enabled, forced) == null

    /**
     * 从一串会话键里筛出可见集合（保留首次出现顺序）。
     *
     * 主页「最近待办」的 `todoVisibleKeys` 与悬浮面板的 `visibleKeys` 都走这里 ——
     * 两处必须同口径，否则会出现「首页筛了飞书、面板还列微信待办」。
     */
    fun visibleKeys(
        keys: Collection<ConvKey>,
        unwatchedIds: Set<String>,
        appFilter: String,
        blockedOf: (String) -> Boolean,
        enabledOf: (String) -> Boolean,
        forced: Boolean = false
    ): Set<ConvKey> {
        val out = LinkedHashSet<ConvKey>(keys.size)
        for (key in keys) {
            if (isVisible(
                    key = key,
                    unwatchedIds = unwatchedIds,
                    appFilter = appFilter,
                    blocked = blockedOf(key.pkg),
                    enabled = enabledOf(key.pkg),
                    forced = forced
                )
            ) {
                out += key
            }
        }
        return out
    }

    /** 序列重载：输入可以是「每条消息一个键」的重复序列（内部先收敛再走同一个实现）。 */
    fun visibleKeys(
        keys: Sequence<ConvKey>,
        unwatchedIds: Set<String>,
        appFilter: String,
        blockedOf: (String) -> Boolean,
        enabledOf: (String) -> Boolean,
        forced: Boolean = false
    ): Set<ConvKey> = visibleKeys(keys.toList(), unwatchedIds, appFilter, blockedOf, enabledOf, forced)
}
