// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 观察到的一个通知来源（用于「设置 → 通知来源」列表）。
 *
 * @param pkg       包名
 * @param label     最近一次拿到的应用显示名；拿不到时为空，UI 回落包名
 * @param firstSeen 首次有消息成功入库的时间（毫秒）
 */
data class ObservedSource(
    val pkg: String,
    val label: String,
    val firstSeen: Long
)

/**
 * 应用源启用状态与已观察来源：监听器按「已启用应用源集合」过滤，
 * 已观察名单则是设置页多选列表的候选（注册表内置源打底 + 实际收到过通知的 App）。
 *
 * 口径：
 *  - 没被显式关掉的包＝启用（新 App 来通知无需先配置）；
 *  - 永不入库的包（[AppSourceRegistry.isBlocked]）即使被 recordSeen 也不收；
 *  - label 为空时不覆盖之前拿到过的名字（避免一次 PackageManager 可见性失败把名字抹掉）。
 *
 * 分层与 M1 一致：持久化逻辑在 [AppSourceStateCore]（只认一个极简 KV，可直接 JVM 单测）。
 */
object AppSourceStore {

    private const val PREFS_NAME = "app_sources"
    private const val KEY_OBSERVED = "observed"
    private const val KEY_DISABLED = "disabled"

    // M11 监控模式：all=全部监控（可单独关闭）；whitelist=只监控白名单内的 App
    const val MODE_ALL = "all"
    const val MODE_WHITELIST = "whitelist"
    private const val KEY_MODE = "mode"
    private const val KEY_WHITELIST = "whitelist"

    /** 记录一次「有消息成功入库」：返回新增/更新的来源；黑名单/空包名返回 null。 */
    @Synchronized
    fun recordSeen(context: Context, pkg: String, label: String): ObservedSource? =
        core(context).recordSeen(pkg, label, System.currentTimeMillis())

    /** 已观察来源（顺序＝首次出现先后）。 */
    @Synchronized
    fun observed(context: Context): List<ObservedSource> = core(context).observed()

    /** 该包是否启用：黑名单恒不启用；其余看是否被显式关掉。 */
    fun isEnabled(context: Context,pkg: String): Boolean = core(context).isEnabled(pkg)

    /** 启用/停用某来源（停用黑名单是无意义操作，直接忽略）。 */
    @Synchronized
    fun setEnabled(context: Context, pkg: String, enabled: Boolean) =
        core(context).setEnabled(pkg, enabled)

    // ---------- M11 监控模式与白名单 ----------

    fun mode(context: Context): String = core(context).mode()

    @Synchronized
    fun setMode(context: Context, mode: String) = core(context).setMode(mode)

    fun whitelist(context: Context): Set<String> = core(context).whitelist()

    @Synchronized
    fun setWhitelist(context: Context, pkgs: Set<String>) =
        core(context).setWhitelist(pkgs)

    private fun prefs(context: Context) = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private var cachedCore: AppSourceStateCore? = null

    @Synchronized
    private fun core(context: Context): AppSourceStateCore {
        cachedCore?.let { return it }
        val sp = prefs(context)
        return AppSourceStateCore(
            kv = object : AppSourceStateCore.Kv {
                override fun getString(key: String, default: String): String =
                    sp.getString(key, default) ?: default

                override fun putString(key: String, value: String) {
                    sp.edit().putString(key, value).apply()
                }
            }
        ).also { cachedCore = it }
    }
}

/**
 * [AppSourceStore] 的无 Android 依赖内核。
 *
 * observed 存 JSON 数组（每项 `{pkg,label,first_seen}`），disabled 存包名 JSON 数组。
 * 损坏的持久化按空集合处理，不能让设置页/监听器读一次坏数据就崩。
 */
internal class AppSourceStateCore(private val kv: Kv) {

    /** 最小键值抽象：生产端是 SharedPreferences，测试端用 HashMap。 */
    interface Kv {
        fun getString(key: String, default: String): String
        fun putString(key: String, value: String)
    }

    /** @see AppSourceStore.recordSeen */
    fun recordSeen(pkg: String, label: String, now: Long): ObservedSource? {
        if (pkg.isEmpty() || AppSourceRegistry.isBlocked(pkg)) return null

        val list = readObserved().toMutableList()
        val idx = list.indexOfFirst { it.pkg == pkg }
        if (idx >= 0) {
            val old = list[idx]
            // 空 label 不覆盖旧名字；名字没变就别写盘——
            // 每条通知都会调到这里，整表 JSON 序列化 + prefs 写入是采集热路径上的 O(n) 放大。
            if (label.isEmpty() || label == old.label) return old
            val updated = old.copy(label = label)
            list[idx] = updated
            writeObserved(list)
            return updated
        }
        val created = ObservedSource(pkg, label, now)
        list += created
        writeObserved(list)
        return created
    }

    fun observed(): List<ObservedSource> = readObserved()

    // ---------------- M11 监控模式与白名单 ----------------

    fun mode(): String = kv.getString("mode", "all")

    fun setMode(mode: String) {
        kv.putString("mode", if (mode == "whitelist") "whitelist" else "all")
    }

    fun whitelist(): Set<String> = readWhitelist()

    fun setWhitelist(pkgs: Set<String>) {
        val arr = JSONArray()
        pkgs.forEach { arr.put(it) }
        kv.putString("whitelist", arr.toString())
    }

    private fun readWhitelist(): Set<String> {
        val raw = kv.getString("whitelist", "[]")
        val result = mutableSetOf<String>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val pkg = arr.optString(i)
                if (pkg.isNotEmpty()) result.add(pkg)
            }
        } catch (e: Exception) {
            // 损坏按空白名单（= 白名单模式下什么都不收，宁可保守）
        }
        return result
    }

    private fun readMode(): String = kv.getString("mode", "all")

    fun isEnabled(pkg: String): Boolean {
        if (AppSourceRegistry.isBlocked(pkg)) return false
        if (readMode() == "whitelist") return pkg in readWhitelist()
        return pkg !in readDisabled()
    }

    fun setEnabled(pkg: String, enabled: Boolean) {
        if (AppSourceRegistry.isBlocked(pkg)) return
        val disabled = readDisabled().toMutableSet()
        if (enabled) disabled.remove(pkg) else disabled.add(pkg)
        writeDisabled(disabled)
    }

    // ---------------- observed ----------------

    private fun readObserved(): List<ObservedSource> {
        val raw = kv.getString(KEY_OBSERVED, "[]")
        val result = mutableListOf<ObservedSource>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val pkg = obj.optString("pkg")
                if (pkg.isEmpty()) continue
                result += ObservedSource(
                    pkg = pkg,
                    label = obj.optString("label"),
                    firstSeen = obj.optLong("first_seen")
                )
            }
        } catch (e: Exception) {
            // 损坏按空集合
        }
        return result
    }

    private fun writeObserved(list: List<ObservedSource>) {
        val arr = JSONArray()
        list.forEach { s ->
            arr.put(JSONObject().apply {
                put("pkg", s.pkg)
                put("label", s.label)
                put("first_seen", s.firstSeen)
            })
        }
        kv.putString(KEY_OBSERVED, arr.toString())
    }

    // ---------------- disabled ----------------

    private fun readDisabled(): Set<String> {
        val raw = kv.getString(KEY_DISABLED, "[]")
        val result = mutableSetOf<String>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val pkg = arr.optString(i)
                if (pkg.isNotEmpty()) result.add(pkg)
            }
        } catch (e: Exception) {
            // 损坏按空集合
        }
        return result
    }

    private fun writeDisabled(disabled: Set<String>) {
        val arr = JSONArray()
        disabled.forEach { arr.put(it) }
        kv.putString(KEY_DISABLED, arr.toString())
    }

    companion object {
        const val KEY_OBSERVED = "observed"
        const val KEY_DISABLED = "disabled"

        /** 测试用：内存 KV。 */
        fun memoryKv(): Kv = object : Kv {
            private val map = HashMap<String, String>()
            override fun getString(key: String, default: String): String = map[key] ?: default
            override fun putString(key: String, value: String) {
                map[key] = value
            }
        }
    }
}
