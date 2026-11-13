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
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * 分析结果列表页：倒序展示 analysis.jsonl 的分析记录。
 *  - needAction=true 的卡片带左侧橙色竖条 + ⚡ 待办 badge + 截止时间（橙色）；
 *  - 顶部「只看待办」开关过滤非待办记录。
 */
class AnalysisListActivity : Activity() {

    companion object {
        /** 可选：只展示该会话的分析结果；不传 = 全部会话 */
        private const val EXTRA_PKG = "pkg"
        private const val EXTRA_CONVERSATION = "conversation"

        fun start(context: android.content.Context, key: ConvKey? = null) {
            context.startActivity(
                android.content.Intent(context, AnalysisListActivity::class.java)
                    .apply {
                        if (key != null) {
                            putExtra(EXTRA_PKG, key.pkg)
                            putExtra(EXTRA_CONVERSATION, key.conversation)
                        }
                    }
            )
        }
    }

    /** 会话过滤；null = 全部会话 */
    private var filterKey: ConvKey? = null

    private lateinit var switchOnlyTodo: Switch
    private lateinit var tvEmpty: TextView

    /** M12：本轮分析的实时进度（顶部一行，与设置页共用 [AnalysisProgressUi]） */
    private lateinit var rowAnalysisProgress: View
    private lateinit var tvAnalysisProgress: TextView
    private lateinit var progressAnalysisList: android.widget.ProgressBar

    private val analysisProgressListener =
        AnalysisProgressBus.Listener { state -> renderAnalysisProgress(state) }

    private val progressTick = object : Runnable {
        override fun run() {
            val state = AnalysisProgressBus.state ?: return
            renderAnalysisProgress(state)
        }
    }
    private val adapter = AnalysisAdapter { record ->
        // 阅读后清除闪电标记：删除该 case 记录，首页 ⚡ 角标随之消失
        AnalysisStore.deleteCase(this, record.caseId)
        Toast.makeText(this, R.string.analysis_mark_cleared, Toast.LENGTH_SHORT).show()
        refreshList()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_analysis_list)

        val conv = intent.getStringExtra(EXTRA_CONVERSATION)
        filterKey = conv?.let {
            ConvKey(
                intent.getStringExtra(EXTRA_PKG)
                    ?.takeIf { p -> p.isNotBlank() } ?: AppSourceRegistry.PKG_WECHAT,
                it
            )
        }

        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }
        switchOnlyTodo = findViewById(R.id.switchOnlyTodo)
        tvEmpty = findViewById(R.id.tvEmptyAnalysis)
        rowAnalysisProgress = findViewById(R.id.rowAnalysisProgress)
        tvAnalysisProgress = findViewById(R.id.tvAnalysisProgress)
        progressAnalysisList = findViewById(R.id.progressAnalysisList)

        // 从首页会话头进入时标题显示会话名
        findViewById<TextView>(R.id.tvAnalysisTitle).text =
            filterKey?.let { getString(R.string.analysis_list_title_conv, it.conversation) }
                ?: getString(R.string.analysis_list_title)

        val recycler = findViewById<RecyclerView>(R.id.recyclerAnalysis)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        switchOnlyTodo.setOnCheckedChangeListener { _, _ -> refreshList() }
    }

    override fun onResume() {
        super.onResume()
        refreshList()
        // addListener 会立刻回调当前运行态，所以进页面时正在跑的轮次能马上显示
        AnalysisProgressBus.addListener(analysisProgressListener)
    }

    override fun onPause() {
        AnalysisProgressBus.removeListener(analysisProgressListener)
        tvAnalysisProgress.removeCallbacks(progressTick)
        super.onPause()
    }

    /**
     * M12 第④要素：TAG_ON_DEMAND 那一轮跑到哪了。
     * 分析结果要等整轮结束才出现在列表里，如果只看空列表，用户会以为「点了没反应」。
     */
    private fun renderAnalysisProgress(state: AnalysisRunState?) {
        if (state == null) {
            tvAnalysisProgress.removeCallbacks(progressTick)
            rowAnalysisProgress.visibility = View.GONE
            // 轮次结束：列表大概率刚变，刷新一次拿到新记录
            refreshList()
            return
        }
        rowAnalysisProgress.visibility = View.VISIBLE
        tvAnalysisProgress.removeCallbacks(progressTick)
        tvAnalysisProgress.postDelayed(progressTick, 1000)
        tvAnalysisProgress.text = AnalysisProgressUi.text(this, state)
        val percent = state.percent()
        if (percent == null) {
            progressAnalysisList.isIndeterminate = true
        } else {
            progressAnalysisList.isIndeterminate = false
            progressAnalysisList.progress = percent
        }
    }

    /** H5 评审：readRecent 上后台线程；token 保证过期装载结果不覆盖新列表。 */
    private var listToken = 0

    private fun refreshList() {
        val token = ++listToken
        val onlyTodo = switchOnlyTodo.isChecked
        val filter = filterKey
        Thread {
            // 有会话过滤时按会话读窗口（不受全局条数截断）；否则读最近 LIMIT 条。
            // 旧写法 readRecent(200).filter { convKey } 在别的会话刷满尾部 200 行后
            // 会让本会话的列表整页空白（数据仍在文件里），口径见 AnalysisWindowCore
            val all = if (filter != null) {
                AnalysisStore.readForConversation(this, filter)
            } else {
                AnalysisStore.readRecentWindow(this, AnalysisWindowCore.LIST_LIMIT)
            }
            val shown = if (onlyTodo) all.filter { it.s1NeedAction } else all
            val sources = if (shown.isEmpty()) emptyMap() else buildSourceMap(shown)
            runOnUiThread {
                if (token != listToken || isFinishing || isDestroyed) return@runOnUiThread
                adapter.submit(shown, sources)
                tvEmpty.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
            }
        }.start()
    }

    /**
     * M13 回看原文：按 case 窗口口径（ts ≤ windowEnd 的最近 messageCount 条）
     * 从本地消息库重组原文。不落库到 analysis.jsonl，隐私口径不变；
     * 消息已被裁剪的旧记录自然无原文（隐藏该行）。
     */
    private fun buildSourceMap(records: List<AnalysisCaseRecord>): Map<String, String> {
        // 按会话各读一次窗口（ConvWindowCore）：不能拿跨会话的 readRecent(500) 去切 ——
        // 别的会话刷满 500 条后本会话在窗口里一条都没有，原文行会整块消失（数据仍在文件里）
        val windows = ConvWindowCore.sourceKeys(records).associateWith { key ->
            MessageStore.readWindow(this, key)
        }
        val prefix = getString(R.string.analysis_source_prefix)
        return ConvWindowCore.sourceMap(records, windows)
            .mapValues { (_, text) -> "$prefix\n$text" }
    }

    /** 分析结果 Adapter（case 版）：会话/主题/摘要/截止/窗口原文/分析时间。 */
    private inner class AnalysisAdapter(
        private val onClearMark: (AnalysisCaseRecord) -> Unit
    ) : RecyclerView.Adapter<AnalysisAdapter.VH>() {

        private val items = mutableListOf<AnalysisCaseRecord>()
        private var sources: Map<String, String> = emptyMap()
        /** M13 展开原文的 caseId 集合：刷新列表后保持展开态不跳变 */
        private val expanded = mutableSetOf<String>()

        /** 用 DiffUtil 做增量刷新：避免整表 notifyDataSetChanged 造成的重绘与动画抖动。 */
        fun submit(records: List<AnalysisCaseRecord>, sources: Map<String, String>) {
            val old = items.toList()
            val oldSources = this.sources
            val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize(): Int = old.size
                override fun getNewListSize(): Int = records.size
                override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean =
                    old[oldPos].caseId == records[newPos].caseId
                override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean =
                    old[oldPos] == records[newPos]
            })
            items.clear()
            items.addAll(records)
            this.sources = sources
            expanded.retainAll(records.map { it.caseId }.toSet())
            diff.dispatchUpdatesTo(this)
            // 窗口原文（sources）不在记录本身里，来源文本变了也要重画对应行
            if (oldSources != sources) {
                notifyItemRangeChanged(0, items.size)
            }
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
            holder.tvSender.text = ctx.getString(
                R.string.analysis_sender_line,
                TopicLabelCore.topicLabel(ctx, record.s1Topic),
                ctx.templateText(R.string.chip_importance, record.s1Importance)
            )
            // 摘要：S2 深分析摘要优先，否则给 S1 判定概率
            holder.tvSummary.text = if (record.escalated) record.s2Summary
            else ctx.templateText(R.string.analysis_prob_fmt, record.s1NeedActionProb * 100)
            // 截止：S2 具体时间优先，其次 S1 截止桶（none 不显示）
            val dueText = record.s2DueTime.ifEmpty {
                TopicLabelCore.dueLabel(ctx, record.s1DueWindow)
            }
            // 「有没有截止」只能问 TopicLabelCore：旧实现拿码值比中文串（none != 无明确时间）永远不等，
            // 于是未编辑过的卡片会显示成「截止：无明确时间」
            val showDue = record.s2DueTime.isNotEmpty() || !TopicLabelCore.isNoDue(record.s1DueWindow)
            if (showDue) {
                holder.tvDue.visibility = View.VISIBLE
                holder.tvDue.text = ctx.getString(
                    R.string.analysis_due_line,
                    ctx.getString(R.string.analysis_due_prefix),
                    dueText
                )
            } else {
                holder.tvDue.visibility = View.GONE
            }
            // 正文行：S2 建议动作优先，否则窗口规模说明
            holder.tvText.text = if (record.escalated && record.s2SuggestedAction.isNotEmpty()) {
                ctx.getString(
                    R.string.s2_action_line,
                    ctx.getString(R.string.s2_action_label),
                    record.s2SuggestedAction
                )
            } else {
                ctx.templateText(R.string.msg_count, record.messageCount)
            }
            // M13 窗口原文：折叠 4 行，点击展开/收起；无原文（旧消息已裁剪）隐藏
            val source = sources[record.caseId]
            if (source.isNullOrEmpty()) {
                holder.tvSource.visibility = View.GONE
                holder.tvSource.setOnClickListener(null)
            } else {
                holder.tvSource.visibility = View.VISIBLE
                holder.tvSource.text = source
                holder.tvSource.maxLines = if (record.caseId in expanded) 64 else 4
                holder.tvSource.setOnClickListener {
                    if (!expanded.add(record.caseId)) expanded.remove(record.caseId)
                    holder.tvSource.maxLines = if (record.caseId in expanded) 64 else 4
                }
            }
            holder.tvTime.text = LocaleTextCore.seconds(record.analyzedAt)
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
            val tvSource: TextView = itemView.findViewById(R.id.tvAnalysisSource)
            val tvTime: TextView = itemView.findViewById(R.id.tvAnalysisTime)
            val btnClearMark: TextView = itemView.findViewById(R.id.btnClearMark)
        }
    }
}
