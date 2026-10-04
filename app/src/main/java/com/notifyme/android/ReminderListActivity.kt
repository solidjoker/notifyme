// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import android.app.Activity
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 已创建提醒列表：倒序展示 reminders.jsonl。
 * 每条带状态徽标（三级降级链落点）：
 *  绿=已建日历事件 / 蓝=已唤起日历待保存 / 橙=已设App内提醒 / 红=失败。
 * 「已唤起日历待保存」的条目可「再次唤起」重放 intent；
 * 失败条目可「重试」重跑三级链（dedupKey 不变，先删旧记录再写新记录）。
 */
class ReminderListActivity : Activity() {

    companion object {
        private const val EXTRA_CONVERSATION = "conversation"

        fun start(context: android.content.Context, conversation: String? = null) {
            context.startActivity(
                android.content.Intent(context, ReminderListActivity::class.java).apply {
                    if (conversation != null) putExtra(EXTRA_CONVERSATION, conversation)
                }
            )
        }
    }

    private val timeFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    private lateinit var recycler: RecyclerView
    private lateinit var emptyView: TextView

    /** 会话过滤；null = 全部会话 */
    private var filterConversation: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reminder_list)

        filterConversation = intent.getStringExtra(EXTRA_CONVERSATION)

        // 标题栏返回
        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<TextView>(R.id.tvReminderListTitle)?.let { titleView ->
            titleView.text = filterConversation?.let { getString(R.string.reminder_list_title_conv, it) }
                ?: getString(R.string.reminder_list_title)
        }

        recycler = findViewById(R.id.recyclerReminders)
        emptyView = findViewById(R.id.tvReminderEmpty)
        recycler.layoutManager = LinearLayoutManager(this)
        reload()
    }

    /** 重读记录并刷新列表（重试/删除后状态会变化）。 */
    private fun reload() {
        var records = ReminderStore.readAll(this)
        filterConversation?.let { f -> records = records.filter { it.conversation == f } }
        emptyView.visibility = if (records.isEmpty()) View.VISIBLE else View.GONE
        recycler.adapter = ReminderAdapter(
            records,
            timeFormat,
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
                Toast.makeText(this, "${getString(R.string.reminder_retry_done)}：$note", Toast.LENGTH_LONG).show()
                reload()
            },
            onDelete = { record -> deleteReminder(record) }
        )
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

    private class ReminderAdapter(
        private val items: List<ReminderRecord>,
        private val timeFormat: SimpleDateFormat,
        private val onReopen: (ReminderRecord) -> Unit,
        private val onRetry: (ReminderRecord) -> Unit,
        private val onDelete: (ReminderRecord) -> Unit
    ) : RecyclerView.Adapter<ReminderAdapter.VH>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_reminder, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val record = items[position]
            val ctx = holder.itemView.context

            holder.title.text = record.title

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
            holder.status.text = ctx.getString(labelRes)
            holder.status.setBackgroundResource(bgRes)

            val eventText = if (record.eventTime > 0) {
                timeFormat.format(Date(record.eventTime))
            } else {
                "无时间"
            }
            holder.time.text = "事件时间 $eventText ｜ 创建于 ${timeFormat.format(Date(record.createdAt))}"
            holder.note.text = record.note

            // 操作按钮：按状态出现
            holder.btnReopen.visibility =
                if (record.status == ReminderRecord.STATUS_INTENT) View.VISIBLE else View.GONE
            holder.btnRetry.visibility =
                if (record.status == ReminderRecord.STATUS_FAILED) View.VISIBLE else View.GONE
            holder.btnReopen.setOnClickListener { onReopen(record) }
            holder.btnRetry.setOnClickListener { onRetry(record) }
            holder.btnDelete.setOnClickListener { onDelete(record) }
        }

        override fun getItemCount(): Int = items.size

        class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val title: TextView = itemView.findViewById(R.id.tvReminderTitle)
            val status: TextView = itemView.findViewById(R.id.tvReminderStatus)
            val time: TextView = itemView.findViewById(R.id.tvReminderTime)
            val note: TextView = itemView.findViewById(R.id.tvReminderNote)
            val btnReopen: Button = itemView.findViewById(R.id.btnRemindAgain)
            val btnRetry: Button = itemView.findViewById(R.id.btnRemindRetry)
            val btnDelete: Button = itemView.findViewById(R.id.btnRemindDelete)
        }
    }
}
