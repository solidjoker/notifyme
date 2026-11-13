// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * 已创建提醒列表：按 [ReminderListCore] 的口径分组（需要处理 → 生效中 → 已过期）、
 * 组内按事件时间排序，并支持顶部 chips 筛选。
 *
 * 每条带状态徽标（三级降级链落点）：
 *  绿=已建日历事件 / 蓝=已唤起日历待保存 / 橙=已设App内提醒 / 红=失败。
 * 「已唤起日历待保存」的条目可「再次唤起」重放 intent；
 * 失败条目可「重试」重跑三级链（dedupKey 不变，先删旧记录再写新记录）。
 *
 * 排序与分组**不在这里写**：只把记录交给 [ReminderListCore] 排好、按组切标题。
 * 该 Core 还负责两条保守判定（event_time<=0 不算过期、failed 永不过期），
 * 别再在 Adapter 或 reload 里各写一份。
 */
class ReminderListActivity : Activity() {

    companion object {
        private const val EXTRA_PKG = "pkg"
        private const val EXTRA_CONVERSATION = "conversation"
        private const val STATE_FILTER = "filter"

        fun start(context: android.content.Context, key: ConvKey? = null) {
            context.startActivity(
                android.content.Intent(context, ReminderListActivity::class.java).apply {
                    if (key != null) {
                        putExtra(EXTRA_PKG, key.pkg)
                        putExtra(EXTRA_CONVERSATION, key.conversation)
                    }
                }
            )
        }
    }

    private lateinit var recycler: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var chipRow: LinearLayout
    private lateinit var chipScroller: View

    /** 会话过滤；null = 全部会话 */
    private var filterKey: ConvKey? = null

    /** 分组筛选码值，见 [ReminderListCore.FILTERS]；切页重建走 saved state。 */
    private var filter: String = ReminderListCore.FILTER_ALL

    /** 最近一次读到的记录（已按会话过滤），切 chip 时不必重读文件。 */
    private var loaded: List<ReminderRecord> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reminder_list)

        // 空会话 = 不筛选（Intent 里的 extras 口径在 ConvExtrasCore）
        filterKey = ConvExtrasCore.key(
            intent.getStringExtra(EXTRA_CONVERSATION),
            intent.getStringExtra(EXTRA_PKG)
        )
        filter = ReminderListCore.normalizeFilter(savedInstanceState?.getString(STATE_FILTER))

        // 标题栏返回
        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<TextView>(R.id.tvReminderListTitle)?.let { titleView ->
            titleView.text = filterKey?.let { getString(R.string.reminder_list_title_conv, it.conversation) }
                ?: getString(R.string.reminder_list_title)
        }

        recycler = findViewById(R.id.recyclerReminders)
        emptyView = findViewById(R.id.tvReminderEmpty)
        chipRow = findViewById(R.id.llReminderChips)
        chipScroller = findViewById(R.id.svReminderChips)
        recycler.layoutManager = LinearLayoutManager(this)
        reload()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_FILTER, filter)
    }

    /** 重读记录并刷新列表（重试/删除后状态会变化）。H5：readAll 上后台，token 防过期覆盖。 */
    private var reloadToken = 0

    private fun reload() {
        val token = ++reloadToken
        val convFilter = filterKey
        Thread {
            var records = ReminderStore.readAll(this)
            convFilter?.let { f -> records = records.filter { it.convKey == f } }
            val snapshot = records
            runOnUiThread {
                if (token != reloadToken || isFinishing || isDestroyed) return@runOnUiThread
                loaded = snapshot
                renderChips()
                renderList()
            }
        }.start()
    }

    /** 筛选码值 → chip / 组标题文案；未知码值回落到「全部」。 */
    private fun filterLabelRes(code: String): Int = when (code) {
        ReminderListCore.FILTER_ATTENTION -> R.string.reminder_filter_attention
        ReminderListCore.FILTER_ACTIVE -> R.string.reminder_filter_active
        ReminderListCore.FILTER_EXPIRED -> R.string.reminder_filter_expired
        else -> R.string.reminder_filter_all
    }

    /**
     * 分组 chips：带条数（全部 = 总数）。一条记录都没有时整行隐藏 ——
     * 那时四个 chip 都是 0，点哪个都是同一句空态文案，没有信息量。
     */
    private fun renderChips() {
        chipRow.removeAllViews()
        if (loaded.isEmpty()) {
            chipScroller.visibility = View.GONE
            return
        }
        chipScroller.visibility = View.VISIBLE
        chipScroller.contentDescription = getString(R.string.reminder_filter_desc)
        val now = System.currentTimeMillis()
        val counts = ReminderListCore.counts(
            loaded, now,
            statusOf = { it.status },
            eventTimeOf = { it.eventTime }
        )
        val dp = resources.displayMetrics.density
        ReminderListCore.FILTERS.forEach { code ->
            val selected = code == filter
            val label = getString(filterLabelRes(code))
            val count = counts[code] ?: 0
            val chip = TextView(this)
            chip.text = templateText(R.string.reminder_filter_chip_label, label, count)
            chip.textSize = 13f
            chip.gravity = android.view.Gravity.CENTER
            chip.setPadding(
                (12 * dp).toInt(), (6 * dp).toInt(), (12 * dp).toInt(), (6 * dp).toInt()
            )
            chip.background = getDrawable(
                if (selected) R.drawable.bg_chip_selected else R.drawable.bg_chip
            )
            chip.setTextColor(getColor(if (selected) R.color.card_bg else R.color.text_secondary))
            // isSelected 让 TalkBack 读「已选中」；contentDescription 只补「状态 X，N 条」
            chip.isSelected = selected
            chip.contentDescription = templateText(R.string.reminder_filter_chip_desc, label, count)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.marginEnd = (8 * dp).toInt()
            chip.layoutParams = lp
            chip.setOnClickListener {
                if (code == filter) return@setOnClickListener
                filter = code
                renderChips()
                renderList()
            }
            chipRow.addView(chip)
        }
    }

    /** 主线程渲染：筛选 → 分组排序 → 装列表；空态文案按「一条都没有」与「本组没有」区分。 */
    private fun renderList() {
        val now = System.currentTimeMillis()
        val kept = ReminderListCore.keep(
            loaded, filter, now,
            statusOf = { it.status },
            eventTimeOf = { it.eventTime }
        )
        val arranged = ReminderListCore.arrange(
            kept, now,
            statusOf = { it.status },
            eventTimeOf = { it.eventTime }
        )
        renderEmptyState(kept.size)
        val rows = buildRows(arranged, now)
        recycler.visibility = if (rows.isEmpty()) View.GONE else View.VISIBLE
        recycler.adapter = ReminderAdapter(
            rows,
            onReopen = { record ->
                // 再次唤起日历 App：重放预填 intent，不产生新记录
                val ok = CalendarHelper.openCalendarInsert(
                    this, record.title, record.description,
                    if (record.eventTime > 0) record.eventTime
                    else System.currentTimeMillis() + 60L * 60_000L
                )
                if (!ok) {
                    Toast.makeText(this, R.string.reminder_status_failed, Toast.LENGTH_SHORT).show()
                }
            },
            onRetry = { record ->
                // 重试：用记录里的标题/描述/时间重跑三级链，然后刷新列表
                val note = CalendarHelper.retryReminder(
                    this, record, AnalysisConfig(this).reminderLeadMinutes
                )
                val done = getString(R.string.reminder_retry_done) + "：" + note
                Toast.makeText(this, done, Toast.LENGTH_LONG).show()
                reload()
            },
            onDelete = { record ->
                // 破坏性操作：先确认再删，避免误点把日历事件一起丢掉
                AlertDialog.Builder(this)
                    .setMessage(R.string.reminder_delete_confirm)
                    .setNegativeButton(R.string.common_cancel, null)
                    .setPositiveButton(R.string.common_delete) { _, _ -> deleteReminder(record) }
                    .show()
            }
        )
    }

    /**
     * 空态：完全没有记录走通用文案；否则按当前筛选说「本组没有」——
     * 用户点了一个 chip 看到空白时，必须能立刻明白是筛选没命中，不是数据丢了。
     */
    private fun renderEmptyState(visibleCount: Int) {
        val none = ReminderListCore.emptyFilter(loaded.size, visibleCount, filter)
        if (none == null) {
            emptyView.visibility = View.GONE
            return
        }
        val textRes = when (none) {
            ReminderListCore.FILTER_ATTENTION -> R.string.reminder_empty_attention
            ReminderListCore.FILTER_ACTIVE -> R.string.reminder_empty_active
            ReminderListCore.FILTER_EXPIRED -> R.string.reminder_empty_expired
            else -> R.string.reminder_list_empty
        }
        emptyView.setText(textRes)
        emptyView.visibility = View.VISIBLE
    }

    /**
     * 把已排序记录切成「分组标题 + 条目」的行序列。
     * 只在「全部」视图插入组标题：筛选到某一组时整页都是同一组，标题只是噪音。
     */
    private fun buildRows(sorted: List<ReminderRecord>, nowMs: Long): List<ReminderRow> {
        if (sorted.isEmpty()) return emptyList()
        val showHeaders = filter == ReminderListCore.FILTER_ALL
        val rows = ArrayList<ReminderRow>(sorted.size + 3)
        var currentGroup: String? = null
        sorted.forEach { record ->
            val group = ReminderListCore.groupOf(record.status, record.eventTime, nowMs)
            if (showHeaders && group != currentGroup) {
                currentGroup = group
                val count = sorted.count {
                    ReminderListCore.groupOf(it.status, it.eventTime, nowMs) == group
                }
                rows.add(ReminderRow.Group(group, count))
            }
            rows.add(ReminderRow.Item(record))
        }
        return rows
    }

    /**
     * 删除/取消提醒：取消闹钟 → 删除已落库的日历事件 → 移除记录。
     * 三级链各状态都取消一遍，保证不漏；操作幂等。
     */
    private fun deleteReminder(record: ReminderRecord) {
        AlarmHelper.cancelReminder(this, record.dedupKey)
        var calSuffix = ""
        if (record.calendarEventId > 0L) {
            val ok = CalendarHelper.deleteEvent(this, record.calendarEventId)
            calSuffix = if (ok) "，日历事件已删除" else "，日历事件删除失败"
        }
        ReminderStore.remove(this, record.dedupKey)
        Toast.makeText(
            this,
            getString(R.string.reminder_deleted, calSuffix),
            Toast.LENGTH_SHORT
        ).show()
        reload()
    }

    /** 列表行：分组标题或一条记录。 */
    private sealed class ReminderRow {
        class Group(val code: String, val count: Int) : ReminderRow()
        class Item(val record: ReminderRecord) : ReminderRow()
    }

    private class ReminderAdapter(
        private val rows: List<ReminderRow>,
        private val onReopen: (ReminderRecord) -> Unit,
        private val onRetry: (ReminderRecord) -> Unit,
        private val onDelete: (ReminderRecord) -> Unit
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        companion object {
            private const val TYPE_GROUP = 0
            private const val TYPE_ITEM = 1
        }

        override fun getItemViewType(position: Int): Int =
            if (rows[position] is ReminderRow.Group) TYPE_GROUP else TYPE_ITEM

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == TYPE_GROUP) {
                GroupVH(inflater.inflate(R.layout.item_reminder_group, parent, false))
            } else {
                VH(inflater.inflate(R.layout.item_reminder, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is ReminderRow.Group -> (holder as GroupVH).bind(row)
                is ReminderRow.Item -> (holder as VH).bind(row.record)
            }
        }

        override fun getItemCount(): Int = rows.size

        private fun groupLabelRes(code: String): Int = when (code) {
            ReminderListCore.FILTER_ATTENTION -> R.string.reminder_filter_attention
            ReminderListCore.FILTER_ACTIVE -> R.string.reminder_filter_active
            ReminderListCore.FILTER_EXPIRED -> R.string.reminder_filter_expired
            else -> R.string.reminder_filter_all
        }

        inner class GroupVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val title: TextView = itemView.findViewById(R.id.tvReminderGroupTitle)
            private val count: TextView = itemView.findViewById(R.id.tvReminderGroupCount)

            fun bind(row: ReminderRow.Group) {
                val ctx = itemView.context
                title.text = ctx.getString(groupLabelRes(row.code))
                count.text = ctx.templateText(R.string.reminder_group_count, row.count)
            }
        }

        inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val title: TextView = itemView.findViewById(R.id.tvReminderTitle)
            private val status: TextView = itemView.findViewById(R.id.tvReminderStatus)
            private val time: TextView = itemView.findViewById(R.id.tvReminderTime)
            private val note: TextView = itemView.findViewById(R.id.tvReminderNote)
            private val btnReopen: Button = itemView.findViewById(R.id.btnRemindAgain)
            private val btnRetry: Button = itemView.findViewById(R.id.btnRemindRetry)
            private val btnDelete: Button = itemView.findViewById(R.id.btnRemindDelete)

            fun bind(record: ReminderRecord) {
                val ctx = itemView.context
                title.text = record.title

                // 状态徽标：文案 + 底色按三级链落点着色
                val (labelRes, bgRes) = when (record.status) {
                    ReminderRecord.STATUS_CALENDAR ->
                        R.string.reminder_status_calendar to R.drawable.bg_tag_group
                    ReminderRecord.STATUS_INTENT ->
                        R.string.reminder_status_intent to R.drawable.bg_tag_status_blue
                    ReminderRecord.STATUS_ALARM ->
                        R.string.reminder_status_alarm to R.drawable.bg_tag_todo
                    else ->
                        R.string.reminder_status_failed to R.drawable.bg_tag_status_red
                }
                status.text = ctx.getString(labelRes)
                status.setBackgroundResource(bgRes)

                val eventText = if (record.eventTime > 0) {
                    LocaleTextCore.minutes(record.eventTime)
                } else {
                    ctx.getString(R.string.reminder_no_time)
                }
                time.text = ctx.getString(
                    R.string.reminder_time_line,
                    eventText,
                    LocaleTextCore.minutes(record.createdAt)
                )
                note.text = record.note

                // 操作按钮：按状态出现
                btnReopen.visibility =
                    if (record.status == ReminderRecord.STATUS_INTENT) View.VISIBLE else View.GONE
                btnRetry.visibility =
                    if (record.status == ReminderRecord.STATUS_FAILED) View.VISIBLE else View.GONE
                btnReopen.setOnClickListener { onReopen(record) }
                btnRetry.setOnClickListener { onRetry(record) }
                btnDelete.setOnClickListener { onDelete(record) }
            }
        }
    }
}
