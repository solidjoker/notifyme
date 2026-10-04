// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import android.app.Activity
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 分析结果列表页：倒序展示 analysis.jsonl 的分析记录。
 *  - needAction=true 的卡片带左侧橙色竖条 + ⚡ 待办 badge + 截止时间（橙色）；
 *  - 顶部「只看待办」开关过滤非待办记录。
 */
class AnalysisListActivity : Activity() {

    companion object {
        /** 可选：只展示该会话的分析结果；不传 = 全部会话 */
        private const val EXTRA_CONVERSATION = "conversation"

        fun start(context: android.content.Context, conversation: String? = null) {
            context.startActivity(
                android.content.Intent(context, AnalysisListActivity::class.java)
                    .apply {
                        if (conversation != null) putExtra(EXTRA_CONVERSATION, conversation)
                    }
            )
        }
    }

    /** 会话过滤；null = 全部会话 */
    private var filterConversation: String? = null

    private lateinit var switchOnlyTodo: Switch
    private lateinit var tvEmpty: TextView
    private val adapter = AnalysisAdapter { record ->
        // 阅读后清除闪电标记：删除该 case 记录，首页 ⚡ 角标随之消失
        AnalysisStore.deleteCase(this, record.caseId)
        Toast.makeText(this, R.string.analysis_mark_cleared, Toast.LENGTH_SHORT).show()
        refreshList()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_analysis_list)

        filterConversation = intent.getStringExtra(EXTRA_CONVERSATION)

        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }
        switchOnlyTodo = findViewById(R.id.switchOnlyTodo)
        tvEmpty = findViewById(R.id.tvEmptyAnalysis)

        // 从首页会话头进入时标题显示会话名
        findViewById<TextView>(R.id.tvAnalysisTitle).text =
            filterConversation?.let { getString(R.string.analysis_list_title_conv, it) }
                ?: getString(R.string.analysis_list_title)

        val recycler = findViewById<RecyclerView>(R.id.recyclerAnalysis)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        switchOnlyTodo.setOnCheckedChangeListener { _, _ -> refreshList() }
    }

    override fun onResume() {
        super.onResume()
        refreshList()
    }

    private fun refreshList() {
        var all = AnalysisStore.readRecent(this, 200)
        filterConversation?.let { f -> all = all.filter { it.conversation == f } }
        val shown = if (switchOnlyTodo.isChecked) all.filter { it.s1NeedAction } else all
        adapter.submit(shown)
        tvEmpty.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
    }

    /** 分析结果 Adapter（case 版）：会话/主题/摘要/截止/分析时间。 */
    private inner class AnalysisAdapter(
        private val onClearMark: (AnalysisCaseRecord) -> Unit
    ) : RecyclerView.Adapter<AnalysisAdapter.VH>() {

        private val items = mutableListOf<AnalysisCaseRecord>()
        private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())

        fun submit(records: List<AnalysisCaseRecord>) {
            items.clear()
            items.addAll(records)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_analysis, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val record = items[position]
            val ctx = holder.itemView.context

            // 待办视觉强调：左侧橙条 + badge + 清除标记按钮；非待办都隐藏
            holder.viewTodoBar.visibility =
                if (record.s1NeedAction) View.VISIBLE else View.GONE
            holder.tvTodoBadge.visibility =
                if (record.s1NeedAction) View.VISIBLE else View.GONE
            holder.btnClearMark.visibility =
                if (record.s1NeedAction) View.VISIBLE else View.GONE
            holder.btnClearMark.setOnClickListener { onClearMark(record) }

            holder.tvConversation.text = record.conversation
            // 发送者行复用为主题行：主题 + 重要度（S1 判定要点）
            holder.tvSender.text = localizeTopic(ctx, record.s1Topic) +
                " · " + ctx.getString(R.string.chip_importance, record.s1Importance)
            // 摘要：S2 深分析摘要优先，否则给 S1 判定概率
            holder.tvSummary.text = if (record.escalated) record.s2Summary
            else "need_action %.0f%%".format(record.s1NeedActionProb * 100)
            // 截止：S2 具体时间优先，其次 S1 截止桶（none 不显示）
            val dueText = record.s2DueTime.ifEmpty {
                localizeDueWindow(ctx, record.s1DueWindow)
            }
            if (dueText.isNotEmpty() && dueText != ctx.getString(R.string.due_none)) {
                holder.tvDue.visibility = View.VISIBLE
                holder.tvDue.text = ctx.getString(R.string.analysis_due_prefix) + dueText
            } else {
                holder.tvDue.visibility = View.GONE
            }
            // 正文行：S2 建议动作优先，否则窗口规模说明
            holder.tvText.text = if (record.escalated && record.s2SuggestedAction.isNotEmpty()) {
                ctx.getString(R.string.s2_action_label) + "：" + record.s2SuggestedAction
            } else {
                ctx.getString(R.string.msg_count, record.messageCount)
            }
            holder.tvTime.text = timeFormat.format(Date(record.analyzedAt))
        }

        /** topic 取值本地化（未知值原样显示，兼容 laya 自由输出） */
        private fun localizeTopic(ctx: android.content.Context, topic: String): String =
            when (topic) {
                "work_task" -> ctx.getString(R.string.topic_work_task)
                "schedule" -> ctx.getString(R.string.topic_schedule)
                "notice" -> ctx.getString(R.string.topic_notice)
                "smalltalk" -> ctx.getString(R.string.topic_smalltalk)
                "risk" -> ctx.getString(R.string.topic_risk)
                else -> topic
            }

        /** due_window 取值本地化（兼容英文桶与中文桶名） */
        private fun localizeDueWindow(ctx: android.content.Context, dueWindow: String): String =
            when (dueWindow) {
                "none", "无明确时间" -> ctx.getString(R.string.due_none)
                "today", "今天" -> ctx.getString(R.string.due_today)
                "tomorrow", "明天" -> ctx.getString(R.string.due_tomorrow)
                "this_week", "本周" -> ctx.getString(R.string.due_this_week)
                "later" -> ctx.getString(R.string.due_later)
                else -> dueWindow
            }

        override fun getItemCount(): Int = items.size

        inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val viewTodoBar: View = itemView.findViewById(R.id.viewTodoBar)
            val tvTodoBadge: TextView = itemView.findViewById(R.id.tvTodoBadge)
            val tvConversation: TextView = itemView.findViewById(R.id.tvAnalysisConv)
            val tvSender: TextView = itemView.findViewById(R.id.tvAnalysisSender)
            val tvSummary: TextView = itemView.findViewById(R.id.tvAnalysisSummary)
            val tvDue: TextView = itemView.findViewById(R.id.tvAnalysisDue)
            val tvText: TextView = itemView.findViewById(R.id.tvAnalysisText)
            val tvTime: TextView = itemView.findViewById(R.id.tvAnalysisTime)
            val btnClearMark: TextView = itemView.findViewById(R.id.btnClearMark)
        }
    }
}
