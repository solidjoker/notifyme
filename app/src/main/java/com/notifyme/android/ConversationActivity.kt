// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.widget.NestedScrollView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 会话详情页：对话流 + 会话级分析面板（分析结果在对话后面显示）。
 *
 *  - 顶部：返回栏 + 会话名（群/私 badge）；
 *  - 中部：完整对话流，气泡样式（对方左灰白气泡、我方右绿气泡，
 *    对齐微信视觉；>5 分钟跨段显示时间分隔；群聊显示发言人小字）；
 *    说明：通知监听只捕获他人消息，当前所有消息都走左侧气泡，
 *    右侧气泡布局保留（未来接入聊天记录提取后展示双向对话）；
 *  - 底部（对话之后）：会话级分析面板——S1 判定 chips（need_action 含概率、
 *    importance 分数、due_window、topic），已升级 S2 时展示摘要/建议动作/任务清单，
 *    另有「立即分析本会话」按钮与最近分析时间；未分析过时显示引导文案。
 */
class ConversationActivity : Activity() {

    companion object {
        private const val EXTRA_PKG = "extra_pkg"
        private const val EXTRA_CONVERSATION = "extra_conversation"

        /** 相邻两条消息超过该间隔就插入时间分隔（5 分钟，对齐微信习惯） */
        private const val TIME_DIVIDER_GAP_MS = 5 * 60 * 1000L

        /** 点「立即分析本会话」后的轮询刷新：每 4s 一次，最多 10 次（40s 覆盖 S1+S2 耗时） */
        private const val POLL_INTERVAL_MS = 4000L
        private const val POLL_MAX_COUNT = 10

        /** ActionMode 多选删除菜单项 id */
        private const val MENU_MSG_DELETE = 1002

        fun start(context: Context, key: ConvKey) {
            context.startActivity(createIntent(context, key))
        }

        /** 构造详情页 intent（通知点击跳转等需要 PendingIntent 的场景用）。 */
        fun createIntent(context: Context, key: ConvKey): Intent {
            return Intent(context, ConversationActivity::class.java)
                .putExtra(EXTRA_PKG, key.pkg)
                .putExtra(EXTRA_CONVERSATION, key.conversation)
        }
    }

    private lateinit var key: ConvKey
    private val conversation: String get() = key.conversation
    private lateinit var scrollChat: NestedScrollView
    private lateinit var chatContainer: LinearLayout
    private lateinit var chipsScroll: View
    private lateinit var chipContainer: LinearLayout
    private lateinit var s2Container: LinearLayout
    private lateinit var s2TasksContainer: LinearLayout
    private lateinit var tvS2Summary: TextView
    private lateinit var tvS2Action: TextView
    private lateinit var tvS2TasksTitle: TextView
    private lateinit var tvAnalysisNone: TextView
    private lateinit var tvPromptBadge: TextView
    private lateinit var tvLastAnalysisTime: TextView
    private lateinit var btnAnalyzeThis: Button

    private val timeFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    private val handler = Handler(Looper.getMainLooper())

    /** 已展示的分析记录时间：轮询时只有拿到新结果才重渲染面板 */
    private var shownAnalyzedAt = 0L
    private var pollCount = 0

    /** 消息多选删除：ActionMode 与选中消息集合（ChatMessage 是 data class，按值去重） */
    private var selectActionMode: android.view.ActionMode? = null
    private val selectedMessages = linkedSetOf<ChatMessage>()

    /**
     * 新消息实时刷新：MessageStore 入库回调（主线程分发）。
     * 停在本页时对方又发了消息 -> 直接重渲染对话流并滚到底部。
     */
    private val storeListener = MessageStore.OnMessagesChangedListener { renderContent() }

    /** 分析后轮询刷新任务：有新 case 记录落库就重渲染并停止 */
    private val pollRefresh = object : Runnable {
        override fun run() {
            val record = AnalysisStore.latestByConvKey(this@ConversationActivity)[key]
            if (record != null && record.analyzedAt > shownAnalyzedAt) {
                renderContent()
                return // 拿到新结果，停止轮询
            }
            pollCount++
            if (pollCount < POLL_MAX_COUNT) {
                handler.postDelayed(this, POLL_INTERVAL_MS)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_conversation)

        val conv = intent.getStringExtra(EXTRA_CONVERSATION).orEmpty()
        if (conv.isEmpty()) {
            finish()
            return
        }
        val pkg = intent.getStringExtra(EXTRA_PKG)
            ?.takeIf { it.isNotBlank() } ?: AppSourceRegistry.PKG_WECHAT
        key = ConvKey(pkg, conv)

        scrollChat = findViewById(R.id.scrollChat)
        chatContainer = findViewById(R.id.chatContainer)
        chipsScroll = findViewById(R.id.chipsScroll)
        chipContainer = findViewById(R.id.chipContainer)
        s2Container = findViewById(R.id.s2Container)
        s2TasksContainer = findViewById(R.id.s2TasksContainer)
        tvS2Summary = findViewById(R.id.tvS2Summary)
        tvS2Action = findViewById(R.id.tvS2Action)
        tvS2TasksTitle = findViewById(R.id.tvS2TasksTitle)
        tvAnalysisNone = findViewById(R.id.tvAnalysisNone)
        tvPromptBadge = findViewById(R.id.tvPromptBadge)
        tvLastAnalysisTime = findViewById(R.id.tvLastAnalysisTime)
        btnAnalyzeThis = findViewById(R.id.btnAnalyzeThis)

        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<TextView>(R.id.tvTitle).text = conversation

        btnAnalyzeThis.setOnClickListener {
            AnalysisScheduler.enqueueAnalysisNow(this, key)
            Toast.makeText(this, R.string.analysis_enqueued_conv, Toast.LENGTH_SHORT).show()
            // 轮询等待新结果落库后自动刷新面板
            pollCount = 0
            handler.removeCallbacks(pollRefresh)
            handler.postDelayed(pollRefresh, POLL_INTERVAL_MS)
        }
    }

    override fun onResume() {
        super.onResume()
        renderContent()
        MessageStore.addOnMessagesChangedListener(storeListener)
    }

    override fun onPause() {
        MessageStore.removeOnMessagesChangedListener(storeListener)
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacks(pollRefresh)
        super.onDestroy()
    }

    /** 全量渲染：对话气泡 + 分析面板（数据量小，直接重建视图足够）。 */
    private fun renderContent() {
        val messages = MessageStore.readRecent(this, 500)
            .filter { it.convKey == key }
            .sortedBy { it.timestamp }
        renderHeader(messages.lastOrNull()?.isGroup ?: false)
        renderChat(messages)
        val record = AnalysisStore.latestByConvKey(this)[key]
        renderAnalysisPanel(record)
        shownAnalyzedAt = record?.analyzedAt ?: 0L

        // 滚动到底部：最后一条消息与分析面板入镜（分析结果在对话后面显示）
        scrollChat.post { scrollChat.fullScroll(View.FOCUS_DOWN) }
    }

    /** 顶部群/私 badge（与首页同款视觉：绿底白字=群聊，灰底灰字=私聊）。 */
    private fun renderHeader(isGroup: Boolean) {
        val tag = findViewById<TextView>(R.id.tvTag)
        if (isGroup) {
            tag.text = getString(R.string.group_tag)
            tag.setBackgroundResource(R.drawable.bg_tag_group)
            tag.setTextColor(getColor(R.color.card_bg))
        } else {
            tag.text = getString(R.string.private_tag)
            tag.setBackgroundResource(R.drawable.bg_tag_private)
            tag.setTextColor(getColor(R.color.text_secondary))
        }
    }

    /** 渲染对话流：逐条 inflate 气泡；>5 分钟跨段插入时间分隔；多选态附加勾选交互。 */
    private fun renderChat(messages: List<ChatMessage>) {
        chatContainer.removeAllViews()
        val inflater = LayoutInflater.from(this)
        var lastTimestamp = 0L
        val selecting = selectActionMode != null
        messages.forEach { msg ->
            val item = inflater.inflate(R.layout.item_chat_message, chatContainer, false)

            // 时间分隔：首条或跨段时显示。ts<=0 兜底不插时间
            if (msg.timestamp > 0 && msg.timestamp - lastTimestamp > TIME_DIVIDER_GAP_MS) {
                val divider = item.findViewById<TextView>(R.id.tvTimeDivider)
                divider.visibility = View.VISIBLE
                divider.text = timeFormat.format(Date(msg.timestamp))
            }
            lastTimestamp = msg.timestamp

            // 通知监听只捕获他人消息 -> 一律左侧气泡；群聊显示发言人
            val sender = item.findViewById<TextView>(R.id.tvSender)
            if (msg.isGroup && msg.sender.isNotEmpty() && msg.sender != conversation) {
                sender.visibility = View.VISIBLE
                sender.text = msg.sender
            } else {
                sender.visibility = View.GONE
            }

            val bubble = item.findViewById<TextView>(R.id.tvBubbleOther)
            bubble.text = msg.text
            // 多选态关闭文本选择，避免长按弹出系统复制菜单挡住勾选
            bubble.setTextIsSelectable(!selecting)
            val isSelected = msg in selectedMessages
            bubble.setBackgroundResource(
                if (isSelected) R.drawable.bg_bubble_selected else R.drawable.bg_bubble_other
            )

            // 长按气泡/条目进入多选；选择态点击气泡勾选/取消
            val handleLongPress = View.OnLongClickListener {
                startMsgSelection(msg)
                true
            }
            item.setOnLongClickListener(handleLongPress)
            bubble.setOnLongClickListener(handleLongPress)
            item.setOnClickListener {
                if (selectActionMode != null) toggleMsgSelection(msg)
            }
            bubble.setOnClickListener {
                if (selectActionMode != null) toggleMsgSelection(msg)
            }

            chatContainer.addView(item)
        }
    }

    // ================= 消息多选删除 =================

    /** 长按进入多选模式，首条消息直接勾上。 */
    private fun startMsgSelection(msg: ChatMessage) {
        if (selectActionMode != null) return
        selectedMessages.clear()
        selectedMessages.add(msg)
        selectActionMode = startActionMode(msgSelectCallback)
        syncMsgSelection()
    }

    /** 选择态点击：勾选/取消。 */
    private fun toggleMsgSelection(msg: ChatMessage) {
        if (!selectedMessages.add(msg)) selectedMessages.remove(msg)
        syncMsgSelection()
    }

    /** 同步 ActionMode 标题并重绘气泡（勾选背景）。 */
    private fun syncMsgSelection() {
        selectActionMode?.title =
            getString(R.string.msg_selected_n, selectedMessages.size)
        renderContent()
    }

    private val msgSelectCallback = object : android.view.ActionMode.Callback {
        override fun onCreateActionMode(mode: android.view.ActionMode, menu: Menu): Boolean {
            menu.add(0, MENU_MSG_DELETE, 0, R.string.batch_delete_action)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            return true
        }

        override fun onPrepareActionMode(mode: android.view.ActionMode, menu: Menu): Boolean = false

        override fun onActionItemClicked(mode: android.view.ActionMode, item: MenuItem): Boolean {
            if (item.itemId == MENU_MSG_DELETE) {
                confirmMsgDelete()
                return true
            }
            return false
        }

        override fun onDestroyActionMode(mode: android.view.ActionMode) {
            selectActionMode = null
            selectedMessages.clear()
            renderContent()
        }
    }

    /** 删除确认：从消息文件与待上报队列中移除选中消息。 */
    private fun confirmMsgDelete() {
        val msgs = selectedMessages.toList()
        if (msgs.isEmpty()) return
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.confirm_delete_messages, msgs.size))
            .setNegativeButton(R.string.common_cancel, null)
            .setPositiveButton(R.string.common_delete) { _, _ ->
                PendingQueue.removeByMessages(this, msgs)
                val n = MessageStore.deleteMessages(this, msgs)
                selectActionMode?.finish()
                renderContent()
                Toast.makeText(this, getString(R.string.delete_done, n), Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    /** 渲染分析面板：S1 chips + S2 区 + 按钮/时间；无记录时给引导文案。 */
    private fun renderAnalysisPanel(record: AnalysisCaseRecord?) {
        // 会话级自定义提示词小标：有无 case 记录都要体现（留空=全局默认则隐藏）
        tvPromptBadge.visibility =
            if (PromptStore.hasPrompt(this, key)) View.VISIBLE else View.GONE
        chipContainer.removeAllViews()
        if (record == null) {
            tvAnalysisNone.visibility = View.VISIBLE
            chipsScroll.visibility = View.GONE
            s2Container.visibility = View.GONE
            tvLastAnalysisTime.visibility = View.GONE
            return
        }
        tvAnalysisNone.visibility = View.GONE
        chipsScroll.visibility = View.VISIBLE

        // ---- S1 判定 chips ----
        // need_action：待办橙 chip / 无需行动绿字灰 chip（含概率百分比）
        addChip(
            text = if (record.s1NeedAction) {
                getString(
                    R.string.chip_need_action_yes,
                    (record.s1NeedActionProb * 100).toInt()
                )
            } else {
                getString(
                    R.string.chip_need_action_no,
                    (record.s1NeedActionProb * 100).toInt()
                )
            },
            highlight = record.s1NeedAction,
            highlightColor = getColor(R.color.accent_orange),
            normalColor = getColor(R.color.status_ok)
        )
        addChip(
            text = getString(R.string.chip_importance, record.s1Importance),
            highlight = record.s1Importance >= AnalysisCase.ESCALATE_IMPORTANCE,
            highlightColor = getColor(R.color.accent_orange),
            normalColor = getColor(R.color.text_secondary)
        )
        addChip(
            text = getString(R.string.chip_due_window, localizeDueWindow(record.s1DueWindow)),
            highlight = false,
            highlightColor = getColor(R.color.accent_orange),
            normalColor = getColor(R.color.text_secondary)
        )
        addChip(
            text = getString(R.string.chip_topic, localizeTopic(record.s1Topic)),
            highlight = false,
            highlightColor = getColor(R.color.accent_orange),
            normalColor = getColor(R.color.text_secondary)
        )
        if (record.escalated) {
            addChip(
                text = getString(R.string.chip_escalated),
                highlight = true,
                highlightColor = getColor(R.color.brand_green),
                normalColor = getColor(R.color.text_secondary)
            )
        }

        // ---- S2 深分析区 ----
        if (record.escalated) {
            s2Container.visibility = View.VISIBLE
            tvS2Summary.text = getString(R.string.s2_summary_label) + "：" + record.s2Summary
            if (record.s2DueTime.isNotEmpty()) {
                tvS2Summary.append("　" + getString(R.string.analysis_due_prefix) + record.s2DueTime)
            }
            tvS2Action.text = getString(R.string.s2_action_label) + "：" + record.s2SuggestedAction
            s2TasksContainer.removeAllViews()
            if (record.s2Tasks.isEmpty()) {
                tvS2TasksTitle.visibility = View.GONE
            } else {
                tvS2TasksTitle.visibility = View.VISIBLE
                record.s2Tasks.forEach { task ->
                    val line = TextView(this).apply {
                        textSize = 13f
                        setTextColor(getColor(R.color.text_primary))
                        setPadding(0, 8, 0, 0)
                        text = if (task.`when`.isNotEmpty()) {
                            getString(R.string.s2_task_format, task.who, task.what, task.`when`)
                        } else {
                            getString(R.string.s2_task_format_no_when, task.who, task.what)
                        }
                    }
                    s2TasksContainer.addView(line)
                }
            }
        } else {
            s2Container.visibility = View.GONE
        }

        tvLastAnalysisTime.visibility = View.VISIBLE
        tvLastAnalysisTime.text = getString(
            R.string.analysis_last_time,
            SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
                .format(Date(record.analyzedAt))
        )
    }

    /** 向 chips 行添加一个 chip（highlight=true 用浅橙底强调）。 */
    private fun addChip(text: String, highlight: Boolean, highlightColor: Int, normalColor: Int) {
        val chip = TextView(this).apply {
            this.text = text
            textSize = 12f
            setPadding(24, 10, 24, 10)
            setBackgroundResource(
                if (highlight) R.drawable.bg_chip_orange else R.drawable.bg_chip
            )
            setTextColor(if (highlight) highlightColor else normalColor)
        }
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { marginEnd = 16 }
        chipContainer.addView(chip, params)
    }

    /** topic 取值本地化（未知值原样显示，兼容 laya 自由输出）。 */
    private fun localizeTopic(topic: String): String = when (topic) {
        "work_task" -> getString(R.string.topic_work_task)
        "schedule" -> getString(R.string.topic_schedule)
        "notice" -> getString(R.string.topic_notice)
        "smalltalk" -> getString(R.string.topic_smalltalk)
        "risk" -> getString(R.string.topic_risk)
        else -> topic.ifEmpty { getString(R.string.topic_notice) }
    }

    /** due_window 取值本地化（兼容英文桶与 laya 中文桶名，未知值原样显示）。 */
    private fun localizeDueWindow(dueWindow: String): String = when (dueWindow) {
        "none", "无明确时间" -> getString(R.string.due_none)
        "today", "今天" -> getString(R.string.due_today)
        "tomorrow", "明天" -> getString(R.string.due_tomorrow)
        "this_week", "本周" -> getString(R.string.due_this_week)
        "later" -> getString(R.string.due_later)
        else -> dueWindow.ifEmpty { getString(R.string.due_none) }
    }
}
