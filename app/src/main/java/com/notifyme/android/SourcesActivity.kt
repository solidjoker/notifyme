// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckedTextView
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
        reload()
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
