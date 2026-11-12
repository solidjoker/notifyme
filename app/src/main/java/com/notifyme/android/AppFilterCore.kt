// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import android.content.Context

/**
 * 首页「按来源 App 筛选」的选中态持久化。
 *
 * 只存一个包名（空串＝全部）。为什么不存列表：筛选是单选视图状态，
 * 与 CollapseStore 的多值折叠态不同，单值读写不会出现两个 chip 同时高亮的歧义。
 */
object AppFilterStore {

    private const val PREFS_NAME = "app_filter"
    private const val KEY_SELECTED = "selected_pkg"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 读回选中来源；没存过返回空串（＝全部）。 */
    fun load(context: Context): String =
        AppFilterCore.normalize(prefs(context).getString(KEY_SELECTED, AppFilterCore.ALL).orEmpty())

    fun save(context: Context, pkg: String) {
        prefs(context).edit()
            .putString(KEY_SELECTED, AppFilterCore.normalize(pkg))
            .apply()
    }
}

/**
 * 首页按来源 App 筛选的口径内核（纯函数，可直接 JVM 单测）。
 *
 * 对齐 PWA 控制台的 app-chips（server/console_ui.py 的 appTabList() / setAppFilter()）：
 *  - tab 顺序固定：全部 → 微信 → 其余按展示名升序（展示名相同按包名升序，保证稳定）；
 *  - 只列当前确实出现过的来源（不是注册表全集）——注册表里收录的 App 一条消息都没有时，
 *    给它一个空 chip 只会让用户点了看到空白列表；
 *  - 选中的来源若已没有任何消息（清库、停用、换手机），筛选自动回落「全部」，
 *    否则用户会停在「明明有消息却什么都看不到」且找不到能点的 chip。
 *
 * 与 PWA 的差异：PWA 一次拉全量，安卓首页只读最近 MessageStore.readRecent 窗口，
 * 因此 chip 集合也按窗口口径（窗口外的来源暂时不出现），这是刻意的——
 * 给一个点了必然空白的 chip 比不给更差。
 */
internal object AppFilterCore {

    /** 「全部」用空串表示：与 PWA 的 APP_FILTER = '' 同口径，避免和真实包名混淆。 */
    const val ALL = ""

    /** 一个筛选 chip：[pkg] 为空串表示「全部」。 */
    data class Tab(val pkg: String, val label: String) {
        val isAll: Boolean get() = pkg == ALL
    }

    /** 归一化：去首尾空白；空串即「全部」。包名区分大小写（Android 包名实际全小写）。 */
    fun normalize(raw: String): String = raw.trim()

    /**
     * 产出 chip 列表：第一项恒为「全部」，其后为实际出现过的来源。
     *
     * @param pkgs     窗口内出现过的包名（可含重复、空串；是否先剔除停用来源由调用方决定）
     * @param labelOf  包名 → 展示名
     * @param allLabel 「全部」的展示文案（来自资源，内核不碰资源）
     */
    fun tabs(
        pkgs: Collection<String>,
        labelOf: (String) -> String,
        allLabel: String
    ): List<Tab> {
        val unique = pkgs.asSequence()
            .map { normalize(it) }
            .filter { it.isNotEmpty() }
            .distinct()
            .sortedWith(
                compareBy<String>(
                    { if (it == AppSourceRegistry.PKG_WECHAT) 0 else 1 },
                    { labelOf(it) },
                    { it }
                )
            )
            .toList()
        val out = ArrayList<Tab>(unique.size + 1)
        out += Tab(ALL, allLabel)
        unique.forEach { out += Tab(it, labelOf(it)) }
        return out
    }

    /** 该消息/会话是否通过当前筛选：筛选为空恒通过，否则包名精确匹配。 */
    fun matches(pkg: String, filter: String): Boolean {
        val f = normalize(filter)
        return f.isEmpty() || normalize(pkg) == f
    }

    /**
     * 把存下来的筛选用「当前可选项」校正：选中项已不存在就回落「全部」。
     * 这一步必须在渲染前做，否则列表会因一个消失的包名整体空掉。
     */
    fun resolve(filter: String, available: Collection<String>): String {
        val f = normalize(filter)
        if (f.isEmpty()) return ALL
        val set = available.map { normalize(it) }.toSet()
        return if (f in set) f else ALL
    }

    /** chip 当前是否选中（空串＝「全部」）。 */
    fun chipSelected(pkg: String, filter: String): Boolean =
        normalize(pkg) == normalize(filter)

    /** 当前筛选的展示名：空筛选给「全部」文案，否则给来源展示名。 */
    fun filterLabel(filter: String, labelOf: (String) -> String, allLabel: String): String {
        val f = normalize(filter)
        return if (f.isEmpty()) allLabel else labelOf(f)
    }
}
