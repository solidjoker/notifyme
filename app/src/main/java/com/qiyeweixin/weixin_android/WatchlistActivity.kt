// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.qiyeweixin.weixin_android

import android.app.Activity
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckedTextView
import android.widget.EditText
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * 重点关注会话管理。
 *
 * 数据源：MessageStore 里出现过的所有会话名（去重、按最近活跃排序）。
 * 语义：名单为空 = 全部关注；勾选框在「全部关注」态下全部显示勾选，
 * 首次取消某一项时把名单物化为「当前全量 - 被取消项」；
 * 「全部取消」用哨兵值 WatchlistStore.SENTINEL_NONE 表达。
 */
class WatchlistActivity : Activity() {

    private lateinit var tvEmpty: TextView
    private lateinit var etAdd: EditText
    private lateinit var adapter: WatchlistAdapter

    /** 当前已知会话名（消息里出现过的 + 手动添加的），有序 */
    private val allNames = mutableListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_watchlist)

        // 标题栏返回
        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }

        tvEmpty = findViewById(R.id.tvWatchlistEmpty)
        etAdd = findViewById(R.id.etAddConversation)

        // 从消息库收集会话名：readRecent 已按时间倒序，首个出现即最近活跃
        MessageStore.readRecent(this, 1000).forEach { msg ->
            if (msg.conversation.isNotEmpty() && msg.conversation !in allNames) {
                allNames.add(msg.conversation)
            }
        }

        val recycler = findViewById<RecyclerView>(R.id.recyclerWatchlist)
        recycler.layoutManager = LinearLayoutManager(this)
        // 勾选回调注入 Adapter（inner class 内不能再声明嵌套类，故 Adapter 用普通类）
        adapter = WatchlistAdapter { name, checked -> setConversationWatched(name, checked) }
        recycler.adapter = adapter
        refreshList()

        findViewById<Button>(R.id.btnWatchAll).setOnClickListener {
            // 空名单 = 全部关注
            WatchlistStore.setWatched(this, emptySet())
            refreshList()
        }

        findViewById<Button>(R.id.btnWatchNone).setOnClickListener {
            // 哨兵表达「一个都不关注」，不占用「空=全部关注」语义
            WatchlistStore.setWatched(this, setOf(WatchlistStore.SENTINEL_NONE))
            refreshList()
        }

        findViewById<Button>(R.id.btnAddConversation).setOnClickListener {
            val name = etAdd.text.toString().trim()
            if (name.isEmpty()) return@setOnClickListener
            if (name !in allNames) allNames.add(0, name)
            // 手动添加视为「关注它」
            setConversationWatched(name, true)
            etAdd.text.clear()
            refreshList()
        }
    }

    private fun refreshList() {
        adapter.submit(allNames, WatchlistStore.getWatched(this))
        tvEmpty.visibility = if (allNames.isEmpty()) View.VISIBLE else View.GONE
    }

    override fun onResume() {
        super.onResume()
        // 从提示词编辑页返回时刷新「✎ 有自定义提示词」小标
        if (::adapter.isInitialized) refreshList()
    }

    /**
     * 勾选状态变更的核心逻辑：
     *  - 当前为「空=全部关注」态时，任何变动先把名单物化为当前全量；
     *  - 取消勾选导致名单变空时补哨兵（否则语义会反弹回全部关注）。
     */
    private fun setConversationWatched(name: String, watched: Boolean) {
        val set = WatchlistStore.getWatched(this)
        set.remove(WatchlistStore.SENTINEL_NONE)
        if (set.isEmpty() && watched) {
            // 全部关注态下勾上一项：语义不变，无需物化
            return
        }
        if (set.isEmpty()) {
            // 全部关注态下取消一项：物化为「全量 - 该项」
            set.addAll(allNames)
        }
        if (watched) set.add(name) else set.remove(name)
        if (set.isEmpty() && !watched) set.add(WatchlistStore.SENTINEL_NONE)
        WatchlistStore.setWatched(this, set)
    }

    /** 会话列表 Adapter：勾选切换关注；右侧「提示词」按钮进编辑页，已设提示词显示 ✎ 小标。 */
    private class WatchlistAdapter(
        private val onCheckedChange: (String, Boolean) -> Unit
    ) : RecyclerView.Adapter<WatchlistAdapter.VH>() {

        private val items = mutableListOf<String>()
        private var watched: Set<String> = emptySet()

        fun submit(names: List<String>, watchedSet: Set<String>) {
            items.clear()
            items.addAll(names)
            watched = watchedSet
            notifyDataSetChanged()
        }

        /** 空名单=全部关注 -> 全部显示勾选；含哨兵或部分名单 -> 按包含判断 */
        private fun isChecked(name: String): Boolean =
            watched.isEmpty() || watched.contains(name)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_watchlist, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val name = items[position]
            val ctx = holder.itemView.context
            holder.checkBox.text = name
            holder.checkBox.isChecked = isChecked(name)
            holder.checkBox.setOnClickListener {
                holder.checkBox.toggle()
                onCheckedChange(name, holder.checkBox.isChecked)
            }
            holder.promptMarker.visibility =
                if (PromptStore.hasPrompt(ctx, name)) View.VISIBLE else View.GONE
            holder.btnEditPrompt.setOnClickListener {
                PromptEditActivity.start(ctx, name)
            }
        }

        override fun getItemCount(): Int = items.size

        class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val checkBox: CheckedTextView = itemView.findViewById(R.id.tvWatchName)
            val promptMarker: TextView = itemView.findViewById(R.id.tvPromptMarker)
            val btnEditPrompt: Button = itemView.findViewById(R.id.btnEditPrompt)
        }
    }
}
