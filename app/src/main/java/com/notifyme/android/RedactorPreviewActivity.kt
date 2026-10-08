// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import android.app.Activity
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * 脱敏预览（M3）：对最近消息逐行展示「原文 → 将发送（出设备文本）」，
 * 让用户在真正发送前看见规则把什么换成了什么。
 *
 * 口径：
 *  - OFF：将发送 = 原文；
 *  - CLOUD_REDACT：按 [PrivacyModeState.shouldRedact] 判断，白名单会话不脱敏；
 *  - LOCAL_ONLY：云端不发送，仍计算一遍文本供用户核对规则。
 * 展示文本按单条消息构造（实际云端分析以会话窗口为单位，
 * 规则效果一致；姓名别名在窗口级别收集，预览里只用本条会话/发送者）。
 */
class RedactorPreviewActivity : Activity() {

    private data class Row(
        val meta: String,
        val original: String,
        val willSend: String
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_redactor_preview)

        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }

        val state = PrivacyConfig.get(this)
        findViewById<TextView>(R.id.tvBanner).text = getString(
            when {
                state.isOff -> R.string.privacy_preview_banner_off
                state.cloudRedact -> R.string.privacy_preview_banner_cloud
                else -> R.string.privacy_preview_banner_local
            }
        )

        val messages = MessageStore.readRecent(this, 200).reversed() // 时间正序展示
        val rows = messages.map { msg ->
            val display = if (msg.text.isNotEmpty()) msg.text else "(空文本)"
            val full = if (msg.isGroup && msg.sender.isNotEmpty())
                "${msg.sender}: $display" else display
            val willSend = when {
                state.isOff -> full
                state.cloudRedact && !state.shouldRedact(msg.convKey) -> full
                else -> {
                    // 姓名映射按本条信息即可（窗口里多人时预览逐行展示）
                    val nameMap = NameAliases.nameMapFor(
                        msg.conversation, msg.isGroup, listOf(msg.sender)
                    )
                    RedactorCore(state.enabledRules, nameMap).redact(full).text
                }
            }
            val appTag = if (msg.pkg == AppSourceRegistry.PKG_WECHAT) "" else
                AppSourceRegistry.displayLabel(msg.pkg) + " · "
            Row(
                meta = "$appTag${msg.conversation} · ${msg.sender}",
                original = full,
                willSend = willSend
            )
        }

        val tvEmpty = findViewById<TextView>(R.id.tvEmpty)
        tvEmpty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE

        val recycler = findViewById<RecyclerView>(R.id.recyclerPreview)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = PreviewAdapter(rows)
    }


    private class PreviewAdapter(rows: List<Row>) :
        RecyclerView.Adapter<PreviewAdapter.VH>() {

        private val items = rows

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_redactor_preview, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val row = items[position]
            holder.meta.text = row.meta
            holder.original.text = row.original
            holder.sent.text = row.willSend
            // 将发送与原文相同：绿色标签淡化为灰，提示无替换
            holder.sentLabel.setTextColor(
                holder.itemView.context.getColor(
                    if (row.willSend == row.original) R.color.text_secondary
                    else R.color.brand_green
                )
            )
        }

        override fun getItemCount(): Int = items.size

        class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val meta: TextView = itemView.findViewById(R.id.tvMeta)
            val original: TextView = itemView.findViewById(R.id.tvOriginal)
            val sent: TextView = itemView.findViewById(R.id.tvSent)
            val sentLabel: TextView = itemView.findViewById(R.id.tvSentLabel)
        }
    }
}
