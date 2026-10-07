// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckedTextView
import android.widget.Switch
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * 通知来源管理（控制台入口）。
 *
 * 列表 = [AppSourceRegistry.knownSources] 内置三源打底 + [AppSourceStore.observed]
 * 里实际收到过通知的其它 App，按包名去重。勾选 = 启用（监听器按此过滤）。
 *
 * 语义与 AppSourceStore 一致：
 *  - 默认全部启用，关掉的包写进 disabled；黑名单（自身/系统）永远不入库，
 *    也不会出现在这个列表里（recordSeen 直接拒收）；
 *  - 未知 App 来通知无需先配置——observed 之后自动出现在这里。
 */
class SourcesActivity : Activity() {

    /** 一行展示数据（合并内置源与已观察源后的结果）。 */
    private data class Row(
        val pkg: String,
        val label: String,
        val enabled: Boolean,
        val canA11y: Boolean
    )

    private lateinit var tvEmpty: TextView
    private lateinit var adapter: SourceAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sources)

        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }
        tvEmpty = findViewById(R.id.tvSourcesEmpty)

        adapter = SourceAdapter { pkg, enabled ->
            AppSourceStore.setEnabled(this, pkg, enabled)
            reload()
        }

        val recycler = findViewById<RecyclerView>(R.id.recyclerSources)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        // M11 监控模式：白名单开关 + 添加应用选择器
        val modeSwitch = findViewById<Switch>(R.id.switchWhitelistMode)
        val btnAddApps = findViewById<TextView>(R.id.btnAddApps)
        modeSwitch.isChecked = AppSourceStore.mode(this) == AppSourceStore.MODE_WHITELIST
        btnAddApps.visibility =
            if (modeSwitch.isChecked) View.VISIBLE else View.GONE
        modeSwitch.setOnCheckedChangeListener { _, checked ->
            AppSourceStore.setMode(
                this,
                if (checked) AppSourceStore.MODE_WHITELIST else AppSourceStore.MODE_ALL
            )
            btnAddApps.visibility = if (checked) View.VISIBLE else View.GONE
            reload()
        }
        btnAddApps.setOnClickListener { showAppPicker() }

        reload()
    }

    /** M11 白名单模式的应用选择器：列出所有有桌面入口的已安装应用，多选。 */
    private fun showAppPicker() {
        val pm = packageManager
        val launchables = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
        )
        data class AppInfo(val pkg: String, val label: String)
        val apps = launchables.mapNotNull { ri ->
            val pkg = ri.activityInfo.packageName
            if (pkg == packageName || AppSourceRegistry.isBlocked(pkg)) return@mapNotNull null
            AppInfo(pkg, ri.loadLabel(pm).toString().ifBlank { pkg })
        }.distinctBy { it.pkg }.sortedBy { it.label }

        val current = AppSourceStore.whitelist(this)
        val preselected = apps.map { it.pkg in current }.toBooleanArray()
        val labels = apps.map { it.label }.toTypedArray()

        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.sources_pick_title)
            .setMultiChoiceItems(labels, preselected) { _, which, isChecked ->
                preselected[which] = isChecked
            }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val picked = apps.filterIndexed { idx, _ -> preselected[idx] }.map { it.pkg }.toSet()
                AppSourceStore.setWhitelist(this, picked)
                reload()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** 重新合并数据源并刷新（启停切换后状态立即回读，不依赖内存）。 */
    private fun reload() {
        val observed = AppSourceStore.observed(this).associate { it.pkg to it.label }
        val rows = mutableListOf<Row>()
        val seen = linkedSetOf<String>()

        AppSourceRegistry.knownSources().forEach { src ->
            seen.add(src.pkg)
            // 已观察到的实际显示名优先于内置名（用户可能改过应用名）
            val label = observed[src.pkg]?.takeIf { it.isNotEmpty() } ?: src.label
            rows += Row(
                pkg = src.pkg,
                label = label,
                enabled = AppSourceStore.isEnabled(this, src.pkg),
                canA11y = src.a11yConfig != null
            )
        }
        observed.keys.filter { it !in seen }.forEach { pkg ->
            rows += Row(
                pkg = pkg,
                label = observed[pkg]?.takeIf { it.isNotEmpty() } ?: pkg,
                enabled = AppSourceStore.isEnabled(this, pkg),
                canA11y = AppSourceRegistry.a11yConfigFor(pkg) != null
            )
        }
        // M11 白名单模式：白名单里的 App 即使还没收到过通知也要出现在列表里（可勾选）
        val whitelistMode = AppSourceStore.mode(this) == AppSourceStore.MODE_WHITELIST
        if (whitelistMode) {
            AppSourceStore.whitelist(this).filter { it !in seen && it !in observed.keys }
                .forEach { pkg ->
                    rows += Row(
                        pkg = pkg,
                        label = pkg,
                        enabled = true,
                        canA11y = AppSourceRegistry.a11yConfigFor(pkg) != null
                    )
                }
        }
        adapter.submit(rows)
        tvEmpty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
    }

    private class SourceAdapter(
        private val onToggle: (String, Boolean) -> Unit
    ) : RecyclerView.Adapter<SourceAdapter.VH>() {

        private val items = mutableListOf<Row>()

        fun submit(rows: List<Row>) {
            items.clear()
            items.addAll(rows)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_source, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val row = items[position]
            holder.name.text = row.label
            holder.name.isChecked = row.enabled
            // 副标题：包名；支持无障碍直读的加「直读」标记（目前只有微信）
            val a11y = if (row.canA11y) " · 直读" else ""
            holder.pkg.text = row.pkg + a11y
            holder.name.setOnClickListener {
                onToggle(row.pkg, !row.enabled)
            }
        }

        override fun getItemCount(): Int = items.size

        class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val name: CheckedTextView = itemView.findViewById(R.id.tvSourceName)
            val pkg: TextView = itemView.findViewById(R.id.tvSourcePkg)
        }
    }
}
