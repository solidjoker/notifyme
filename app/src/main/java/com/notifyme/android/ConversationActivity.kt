// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.widget.NestedScrollView
import java.text.SimpleDateFormat
import java.util.Calendar
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

        /** ActionMode 多选菜单项 id */
        private const val MENU_MSG_DELETE = 1002
        private const val MENU_MSG_REMIND = 1003

        /** M17 手动提醒的日历权限请求码（与主页待办用的 1001 区分） */
        private const val REQ_CALENDAR_PERMISSION = 4001

        /** 单次渲染的消息上限 */
        private const val MAX_CHAT_RENDER = 200

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
    private lateinit var progressAnalyzeThis: android.widget.ProgressBar
    private lateinit var tvAnalysisProgress: TextView
    private lateinit var llQuickReply: View
    private lateinit var btnNewReminder: Button
    private lateinit var tvConvReminders: TextView

    private val timeFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    private val handler = Handler(Looper.getMainLooper())

    /** 已展示的分析记录时间：轮询时只有拿到新结果才重渲染面板 */
    private var shownAnalyzedAt = 0L

    /** 渲染代次：后台装载完成时若已有更新的渲染请求，丢弃过期结果 */
    private var renderToken = 0
    private var pollCount = 0

    /** 消息多选删除：ActionMode 与选中消息集合（ChatMessage 是 data class，按值去重） */
    private var selectActionMode: android.view.ActionMode? = null
    private val selectedMessages = linkedSetOf<ChatMessage>()

    /**
     * M17 手动提醒草稿：申请日历权限会中断当前操作，
     * onRequestPermissionsResult 回来后用草稿继续跑降级链。
     */
    private data class ReminderDraft(
        val title: String,
        val eventTime: Long,
        val leadMinutes: Long,
        val source: ChatMessage?
    )

    private var pendingDraft: ReminderDraft? = null

    /**
     * 新消息实时刷新：MessageStore 入库回调（主线程分发）。
     * 停在本页时对方又发了消息 -> 直接重渲染对话流并滚到底部。
     */
    private val storeListener = MessageStore.OnMessagesChangedListener { renderContent() }

    /**
     * 分析后轮询刷新任务：有新 case 记录落库就重渲染并停止。
     * 读 analysis.jsonl 是整文件解析，不能放主线程（每秒一次必然掉帧）。
     */
    private val pollRefresh = object : Runnable {
        override fun run() {
            Thread {
                val record = AnalysisStore.latestByConvKey(this@ConversationActivity)[key]
                val fresh = record != null && record.analyzedAt > shownAnalyzedAt
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    if (fresh) {
                        renderContent() // 拿到新结果，停止轮询
                        return@runOnUiThread
                    }
                    pollCount++
                    if (pollCount < POLL_MAX_COUNT) {
                        handler.postDelayed(this, POLL_INTERVAL_MS)
                    }
                }
            }.start()
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
        progressAnalyzeThis = findViewById(R.id.progressAnalyzeThis)
        tvAnalysisProgress = findViewById(R.id.tvAnalysisProgress)
        btnNewReminder = findViewById(R.id.btnNewReminder)
        tvConvReminders = findViewById(R.id.tvConvReminders)
        llQuickReply = findViewById(R.id.llQuickReply)

        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<TextView>(R.id.tvTitle).text = conversation

        findViewById<Button>(R.id.btnOpenApp).setOnClickListener {
            val intent = packageManager.getLaunchIntentForPackage(key.pkg)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            } else {
                Toast.makeText(this, R.string.open_source_app_failed, Toast.LENGTH_SHORT).show()
            }
        }

        setupQuickReply()

        btnAnalyzeThis.setOnClickListener {
            AnalysisScheduler.enqueueAnalysisNow(this, key)
            Toast.makeText(this, R.string.analysis_enqueued_conv, Toast.LENGTH_SHORT).show()
            // 轮询等待新结果落库后自动刷新面板
            pollCount = 0
            handler.removeCallbacks(pollRefresh)
            handler.postDelayed(pollRefresh, POLL_INTERVAL_MS)
        }

        // M17 会话内自建提醒：手动建一条 + 跳转本会话提醒管理
        btnNewReminder.setOnClickListener { showReminderDialog(null) }
        tvConvReminders.setOnClickListener { ReminderListActivity.start(this, key) }

        // M11 分析进度条：观察本会话 WorkManager tag，排队/运行时显示、结束消失
        androidx.work.WorkManager.getInstance(this)
            .getWorkInfosByTagLiveData(AnalysisScheduler.convTag(key))
            .observeForever { infos ->
                val active = infos?.any { !it.state.isFinished } == true
                progressAnalyzeThis.visibility =
                    if (active) View.VISIBLE else View.GONE
                btnAnalyzeThis.isEnabled = !active
            }

        // M11.4 定时分析本会话：开关 + 周期（改动即时生效，持久化在 ConvAnalysisScheduleStore）
        setupConvSchedule()
    }

    /** 会话级定时分析：开关 + 周期选择（取消关注时由 AnalysisScheduler.syncAll 清孤儿任务）。 */
    private fun setupConvSchedule() {
        val scheduleSwitch = findViewById<Switch>(R.id.switchConvSchedule)
        val intervalSpinner = findViewById<Spinner>(R.id.spinnerConvInterval)
        val intervalLabels = resources.getStringArray(R.array.sync_interval_labels)
        intervalSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, intervalLabels
        )

        val saved = ConvAnalysisScheduleStore.intervalMinutes(this, key)
        scheduleSwitch.isChecked = saved > 0
        val idx = AnalysisConfig.INTERVAL_OPTIONS.indexOf(saved).coerceAtLeast(0)
        intervalSpinner.setSelection(idx)

        scheduleSwitch.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                val minutes = AnalysisConfig.INTERVAL_OPTIONS[
                    intervalSpinner.selectedItemPosition.coerceAtLeast(0)
                ]
                ConvAnalysisScheduleStore.setInterval(this, key, minutes)
                AnalysisScheduler.scheduleForConv(this, key, minutes)
                Toast.makeText(this, R.string.conv_schedule_on, Toast.LENGTH_SHORT).show()
            } else {
                ConvAnalysisScheduleStore.setInterval(this, key, 0)
                AnalysisScheduler.cancelForConv(this, key)
                Toast.makeText(this, R.string.conv_schedule_off, Toast.LENGTH_SHORT).show()
            }
        }

        intervalSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!scheduleSwitch.isChecked) return
                val minutes = AnalysisConfig.INTERVAL_OPTIONS[position]
                ConvAnalysisScheduleStore.setInterval(this@ConversationActivity, key, minutes)
                AnalysisScheduler.scheduleForConv(this@ConversationActivity, key, minutes)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    /** M15 实时分析进度：Worker 发布 -> 本页展示阶段 / token 进度 / 已用时间。 */
    private val analysisProgressListener = AnalysisProgressBus.Listener { state ->
        renderAnalysisProgress(state)
    }

    /**
     * 只认本会话的运行态：分析别的会话时本页保持原样（进度条仍由
     * WorkManager observer 决定显隐），否则会把无关会话的百分比画上来。
     */
    private fun renderAnalysisProgress(state: AnalysisRunState?) {
        if (state == null || state.convKeyId != key.id) {
            handler.removeCallbacks(progressTick)
            tvAnalysisProgress.visibility = View.GONE
            progressAnalyzeThis.isIndeterminate = true
            return
        }
        AnalysisProgressUi.render(this, tvAnalysisProgress, progressAnalyzeThis, state)
        // 「已用 N秒」只有在重新渲染时才前进；prefill 一批可能要十几秒，
        // 这里每秒补一帧，避免进度文案看起来冻住。
        handler.removeCallbacks(progressTick)
        handler.postDelayed(progressTick, 1000)
    }

    /** 每秒重绘一帧（只读总线里最新状态，不新增任何跨线程状态）。 */
    private val progressTick = object : Runnable {
        override fun run() {
            val state = AnalysisProgressBus.state
            if (state != null && state.convKeyId == key.id) {
                AnalysisProgressUi.render(this@ConversationActivity, tvAnalysisProgress, progressAnalyzeThis, state)
                handler.postDelayed(this, 1000)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        renderContent()
        MessageStore.addOnMessagesChangedListener(storeListener)
        AnalysisProgressBus.addListener(analysisProgressListener)
    }

    override fun onPause() {
        AnalysisProgressBus.removeListener(analysisProgressListener)
        MessageStore.removeOnMessagesChangedListener(storeListener)
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacks(pollRefresh)
        super.onDestroy()
    }

    /**
     * 全量渲染：对话气泡 + 分析面板。
     * 读盘（messages.jsonl + analysis.jsonl 全量解析）放后台线程，视图更新回主线程；
     * [renderToken] 保证过期的装载结果不会覆盖更新的一次渲染。
     */
    private fun renderContent() {
        val token = ++renderToken
        val convKey = key
        Thread {
            val messages = MessageStore.readRecent(this, 500)
                .filter { it.convKey == convKey }
                .sortedBy { it.timestamp }
            val record = AnalysisStore.latestByConvKey(this)[convKey]
            val reminderCount = ReminderStore.readAll(this).count { it.convKey == convKey }
            runOnUiThread {
                if (token != renderToken || isFinishing || isDestroyed) return@runOnUiThread
                renderHeader(messages.lastOrNull()?.isGroup ?: false)
                renderChat(messages)
                refreshQuickReply()
                renderAnalysisPanel(record)
                renderReminderCount(reminderCount)
                shownAnalyzedAt = record?.analyzedAt ?: 0L

                // 滚动到底部：最后一条消息与分析面板入镜（分析结果在对话后面显示）
                scrollChat.post { scrollChat.fullScroll(View.FOCUS_DOWN) }
            }
        }.start()
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

    /** 已渲染的消息签名：列表未变 + 多选态未变时跳过重渲染，避免每秒轮询都重建整棵视图树。 */
    private var lastChatSignature: String = ""

    /** 渲染对话流：逐条 inflate 气泡；>5 分钟跨段插入时间分隔；多选态附加勾选交互。 */
    private fun renderChat(messages: List<ChatMessage>) {
        // 防重渲染：消息集合 + 多选状态都没变时跳过（轮询场景每秒调一次）
        val signature = messages.hashCode().toString() + "|" + (selectActionMode != null) + "|" +
            selectedMessages.hashCode()
        if (signature == lastChatSignature && chatContainer.childCount > 0) return
        lastChatSignature = signature

        chatContainer.removeAllViews()
        // 上限控制：只渲染最近 MAX_CHAT_RENDER 条，避免历史大时 OOM
        val renderList = if (messages.size > MAX_CHAT_RENDER) {
            val hint = TextView(this).apply {
                text = getString(R.string.chat_truncated, MAX_CHAT_RENDER, messages.size)
                setTextColor(getColor(R.color.text_secondary))
                textSize = 12f
                val density = resources.displayMetrics.density
                setPadding((8 * density).toInt(), (4 * density).toInt(), (8 * density).toInt(), (4 * density).toInt())
            }
            chatContainer.addView(hint)
            messages.takeLast(MAX_CHAT_RENDER)
        } else messages
        val inflater = LayoutInflater.from(this)
        var lastTimestamp = 0L
        val selecting = selectActionMode != null
        renderList.forEach { msg ->
            val item = inflater.inflate(R.layout.item_chat_message, chatContainer, false)

            // 时间分隔：首条或跨段时显示。ts<=0 兜底不插时间
            if (msg.timestamp > 0 && msg.timestamp - lastTimestamp > TIME_DIVIDER_GAP_MS) {
                val divider = item.findViewById<TextView>(R.id.tvTimeDivider)
                divider.visibility = View.VISIBLE
                divider.text = timeFormat.format(Date(msg.timestamp))
            }
            lastTimestamp = msg.timestamp

            val isSelected = msg in selectedMessages

            // 长按/点击（多选态）对左右气泡都生效，下面给两种气泡分别绑定
            val handleLongPress = View.OnLongClickListener {
                startMsgSelection(msg)
                true
            }
            val handleClick = View.OnClickListener {
                if (selectActionMode != null) toggleMsgSelection(msg)
            }

            if (msg.isSelf) {
                // 自己发出（a11y 直读右侧气泡）：隐藏左行，显示右绿气泡
                item.findViewById<View>(R.id.llOther).visibility = View.GONE
                val llMe = item.findViewById<View>(R.id.llMe)
                llMe.visibility = View.VISIBLE
                val meBubble = item.findViewById<TextView>(R.id.tvBubbleMe)
                meBubble.text = msg.text
                meBubble.setTextIsSelectable(!selecting)
                meBubble.setBackgroundResource(
                    if (isSelected) R.drawable.bg_bubble_selected else R.drawable.bg_bubble_me
                )
                item.setOnLongClickListener(handleLongPress)
                meBubble.setOnLongClickListener(handleLongPress)
                item.setOnClickListener(handleClick)
                meBubble.setOnClickListener(handleClick)
            } else {
                // 对方消息：左灰白气泡；群聊显示发言人
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
                bubble.setBackgroundResource(
                    if (isSelected) R.drawable.bg_bubble_selected else R.drawable.bg_bubble_other
                )

                item.setOnLongClickListener(handleLongPress)
                bubble.setOnLongClickListener(handleLongPress)
                item.setOnClickListener(handleClick)
                bubble.setOnClickListener(handleClick)
            }

            chatContainer.addView(item)
        }
    }

    // ================= 快速回复 =================

    /** 绑定快速回复栏：发送通过存活通知的 RemoteInput action，失败给明确文案。 */
    private fun setupQuickReply() {
        val et = findViewById<android.widget.EditText>(R.id.etQuickReply)
        findViewById<Button>(R.id.btnQuickReplySend).setOnClickListener {
            val text = et.text.toString().trim()
            if (text.isEmpty()) {
                Toast.makeText(this, R.string.quick_reply_empty, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val action = ReplyActionStore.actionFor(key)
            if (action == null) {
                Toast.makeText(this, R.string.quick_reply_failed, Toast.LENGTH_LONG).show()
                llQuickReply.visibility = View.GONE
                return@setOnClickListener
            }
            if (MessageReplier.reply(this, action, text)) {
                et.text.clear()
                Toast.makeText(this, R.string.quick_reply_sent, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, R.string.quick_reply_failed, Toast.LENGTH_LONG).show()
                llQuickReply.visibility = View.GONE
            }
        }
    }

    /** 回复栏可见性：只有该会话当前有存活的可回复通知 action 才显示。 */
    private fun refreshQuickReply() {
        llQuickReply.visibility =
            if (ReplyActionStore.actionFor(key) != null) View.VISIBLE else View.GONE
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
            menu.add(0, MENU_MSG_REMIND, 0, R.string.reminder_menu_action)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            return true
        }

        override fun onPrepareActionMode(mode: android.view.ActionMode, menu: Menu): Boolean = false

        override fun onActionItemClicked(mode: android.view.ActionMode, item: MenuItem): Boolean {
            if (item.itemId == MENU_MSG_DELETE) {
                confirmMsgDelete()
                return true
            }
            if (item.itemId == MENU_MSG_REMIND) {
                createReminderFromSelection()
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

    // ---------- M17 会话内自建提醒（写系统日历 + 提前量，无日历时降级） ----------

    /** 本会话已设提醒条数：0 条时隐藏入口（分析未跑过的会话也要能看到，故独立于分析面板）。 */
    private fun renderReminderCount(count: Int) {
        if (count <= 0) {
            tvConvReminders.visibility = View.GONE
            return
        }
        tvConvReminders.visibility = View.VISIBLE
        tvConvReminders.text = getString(R.string.reminder_conv_count, count)
    }

    /** 长按消息建提醒：先捕获选中消息，再退出多选态（退出会清空选中集）。 */
    private fun createReminderFromSelection() {
        val msg = selectedMessages.lastOrNull()
        selectActionMode?.finish()
        showReminderDialog(msg)
    }

    /**
     * 手动提醒弹窗：内容 + 时间（可手输可点选）+ 提前量。
     * [source] 非空时用消息文本预填内容并把原文带进日历描述。
     */
    private fun showReminderDialog(source: ChatMessage?) {
        val config = AnalysisConfig(this)
        val density = resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), 0)
        }

        val etTitle = EditText(this).apply {
            hint = getString(R.string.reminder_manual_hint_text)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            maxLines = 3
            filters = arrayOf(InputFilter.LengthFilter(ManualReminderCore.MAX_TITLE_LEN))
            setText(ManualReminderCore.prefillTitle(source?.text.orEmpty()))
        }
        container.addView(etTitle)

        val etTime = EditText(this).apply {
            hint = getString(R.string.reminder_manual_hint_time)
            inputType = InputType.TYPE_CLASS_DATETIME
            isSingleLine = true
            filters = arrayOf(InputFilter.LengthFilter(40))
            setText(ManualReminderCore.defaultTimeText(System.currentTimeMillis()))
        }
        val btnPick = TextView(this).apply {
            text = getString(R.string.reminder_manual_pick_time)
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(getColor(R.color.brand_green))
            setBackgroundResource(R.drawable.btn_secondary)
            setPadding(dp(10), dp(12), dp(10), dp(12))
            setOnClickListener {
                val now = System.currentTimeMillis()
                val initial = CalendarHelper.tryParseDueTime(etTime.text.toString(), now) ?: now
                pickDateTime(initial) { millis ->
                    etTime.setText(ManualReminderCore.eventTimeFormat().format(Date(millis)))
                }
            }
        }
        val timeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        timeRow.addView(
            etTime,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        timeRow.addView(
            btnPick,
            LinearLayout.LayoutParams(dp(92), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(8)
            }
        )
        container.addView(timeRow)

        val leadRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        leadRow.addView(
            TextView(this).apply {
                text = getString(R.string.reminder_manual_lead_label)
                textSize = 14f
                setTextColor(getColor(R.color.text_secondary))
            },
            LinearLayout.LayoutParams(dp(80), LinearLayout.LayoutParams.WRAP_CONTENT)
        )
        val spinnerLead = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@ConversationActivity,
                android.R.layout.simple_spinner_dropdown_item,
                resources.getStringArray(R.array.reminder_lead_labels)
            )
            // 沿用「提醒设置」里的提前量偏好（档位表与分析链路共用）
            val index = AnalysisConfig.REMINDER_LEAD_OPTIONS.indexOf(config.reminderLeadMinutes)
            setSelection(if (index >= 0) index else 1)
        }
        leadRow.addView(spinnerLead, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        container.addView(leadRow)

        if (source != null && source.text.isNotBlank()) {
            container.addView(TextView(this).apply {
                text = getString(R.string.reminder_manual_from_msg, source.sender.ifEmpty { conversation })
                textSize = 12f
                setTextColor(getColor(R.color.text_secondary))
                top = dp(8)
            })
        }
        container.addView(TextView(this).apply {
            text = getString(R.string.reminder_manual_desc)
            textSize = 12f
            setTextColor(getColor(R.color.text_secondary))
            setPadding(0, dp(8), 0, 0)
        })

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.reminder_manual_title)
            .setView(container)
            .setNegativeButton(R.string.common_cancel, null)
            .setPositiveButton(R.string.reminder_manual_save, null)
            .create()
        dialog.show()
        // 点确定自己校验：校验不过不关窗，避免用户白填一遍
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val title = etTitle.text.toString().trim()
            if (title.isEmpty()) {
                toast(R.string.reminder_manual_empty)
                return@setOnClickListener
            }
            val now = System.currentTimeMillis()
            val eventTime = CalendarHelper.tryParseDueTime(etTime.text.toString(), now)
            if (eventTime == null) {
                toast(R.string.reminder_manual_bad_time)
                return@setOnClickListener
            }
            if (ManualReminderCore.isInPast(eventTime, now)) {
                toast(R.string.reminder_manual_past_time)
                return@setOnClickListener
            }
            val leadMinutes = AnalysisConfig.REMINDER_LEAD_OPTIONS
                .getOrElse(spinnerLead.selectedItemPosition) { 15L }
            dialog.dismiss()
            requestCalendarThenRun(ReminderDraft(title, eventTime, leadMinutes, source))
        }
    }

    /** 日期 -> 时间两级系统选择器（不引第三方库）；结果按「yyyy-MM-dd HH:mm」回填。 */
    private fun pickDateTime(initial: Long, onPicked: (Long) -> Unit) {
        val cal = Calendar.getInstance().apply { timeInMillis = initial }
        DatePickerDialog(
            this,
            { _, year, month, day ->
                cal.set(year, month, day)
                TimePickerDialog(
                    this,
                    { _, hour, minute ->
                        cal.set(Calendar.HOUR_OF_DAY, hour)
                        cal.set(Calendar.MINUTE, minute)
                        cal.set(Calendar.SECOND, 0)
                        cal.set(Calendar.MILLISECOND, 0)
                        onPicked(cal.timeInMillis)
                    },
                    cal.get(Calendar.HOUR_OF_DAY),
                    cal.get(Calendar.MINUTE),
                    true
                ).show()
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    /** 有日历权限直接落库；没有就先要权限——**被拒也继续**，链内自带 L2/L3 降级。 */
    private fun requestCalendarThenRun(draft: ReminderDraft) {
        if (CalendarHelper.hasCalendarPermission(this)) {
            runManualReminder(draft)
            return
        }
        pendingDraft = draft
        requestPermissions(
            arrayOf(
                android.Manifest.permission.READ_CALENDAR,
                android.Manifest.permission.WRITE_CALENDAR
            ),
            REQ_CALENDAR_PERMISSION
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_CALENDAR_PERMISSION) return
        val draft = pendingDraft
        pendingDraft = null
        if (draft == null) return
        if (!CalendarHelper.hasCalendarPermission(this)) {
            toast(R.string.reminder_manual_no_permission)
        }
        runManualReminder(draft)
    }

    /**
     * 跑三级降级链（日历插入 -> 唤起日历 App -> App 内闹钟）。
     * ContentProvider 写入可能耗时，放独立线程；完成后刷新计数并重渲染。
     */
    private fun runManualReminder(draft: ReminderDraft) {
        val convKey = key
        Thread {
            val outcome = CalendarHelper.createReminderResult(
                this,
                dedupKey = ManualReminderCore.dedupKey(
                    convKey.pkg, convKey.conversation, draft.title, draft.eventTime
                ),
                pkg = convKey.pkg,
                conversation = convKey.conversation,
                sender = draft.source?.sender.orEmpty(),
                sourceText = draft.source?.text.orEmpty(),
                summary = draft.title,
                dueTimeText = "",
                baseTime = System.currentTimeMillis(),
                leadMinutes = draft.leadMinutes,
                titleOverride = draft.title,
                explicitEventTime = draft.eventTime
            )
            val message = when {
                outcome.status == ReminderRecord.STATUS_INTENT ->
                    getString(R.string.reminder_manual_intent)

                outcome.created -> getString(R.string.reminder_manual_created, outcome.note)
                // 「跳过：已创建过（去重）」等原样回显，别谎报失败
                else -> outcome.note
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                renderContent()
            }
        }.start()
    }

    private fun toast(resId: Int) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
    }
}
