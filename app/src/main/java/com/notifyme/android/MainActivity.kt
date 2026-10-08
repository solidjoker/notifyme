// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Observer
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 主界面：
 *  - 检查通知监听权限，未授予则引导跳转系统设置；
 *  - RecyclerView 展示 MessageStore 里最近捕获的消息；
 *  - 支持手动刷新与清除记录；
 *  - 汇总分区（⭐ 重点关注 / 其它会话）+ 分层折叠（会话 → 日期）+ 搜索过滤。
 *
 * M2 起所有身份均为 [ConvKey]：同名会话在不同 App 里分别分组/折叠/删除/关注。
 *
 * 说明：刻意使用传统 View + RecyclerView（而非 Compose），
 * 把第三方依赖控制在 androidx.core + recyclerview 两个，降低首次编译风险。
 */
class MainActivity : Activity() {

    companion object {
        /** POST_NOTIFICATIONS 运行时权限请求码 */
        private const val REQ_POST_NOTIFICATIONS = 42

        /** 日期分组兜底 key：时间戳 ≤0（如 a11y 直读估算失败）的消息归「未标注日期」组 */
        private const val DAY_KEY_UNKNOWN = "__unknown__"

        /** ActionMode 批量删除菜单项 id */
        private const val MENU_BATCH_DELETE = 1001

        /** 搜索防抖窗口：输入停顿这么久后才真正刷新列表 */
        private const val SEARCH_DEBOUNCE_MS = 300L
    }

    private lateinit var tvPermissionStatus: TextView
    private lateinit var tvEmpty: TextView
    private lateinit var recyclerMessages: RecyclerView
    private lateinit var etSearch: EditText

    /** 当前搜索关键词（空串 = 不过滤）；由搜索框 TextWatcher 维护 */
    private var searchQuery = ""

    /** 刷新代次：后台装载完成时若已有更新的刷新，丢弃过期结果（旧查询不覆盖新列表） */
    private var refreshToken = 0

    /** 搜索防抖：输入停顿 [SEARCH_DEBOUNCE_MS] 后刷新一次，避免每个按键都全量读盘 */
    private val searchHandler by lazy { Handler(Looper.getMainLooper()) }
    private val searchRefresh = Runnable { refreshMessages() }

    private val adapter: FeedAdapter = FeedAdapter(
        onToggle = { key ->
            if (adapter.selectionActive) return@FeedAdapter
            // 点击箭头：折叠/展开整组并持久化
            val collapsed = CollapseStore.load(this)
            CollapseStore.setCollapsed(this, key, key.id !in collapsed)
            refreshMessages()
        },
        onOpen = { key ->
            // 点击文字区：批量选择态=勾选；否则进会话详情页
            if (adapter.selectionActive) toggleSelection(key)
            else ConversationActivity.start(this, key)
        },
        onToggleDate = { key, dayKey ->
            if (adapter.selectionActive) return@FeedAdapter
            // 点击日期组头：折叠/展开该会话下这一天的消息并持久化
            val dates = CollapseStore.loadDates(this)
            CollapseStore.setDateCollapsed(
                this, key, dayKey,
                !CollapseStore.isDateCollapsed(dates, key, dayKey)
            )
            refreshMessages()
        },
        onToggleWatch = { key ->
            if (!adapter.selectionActive) toggleWatch(key)
        },
        onHeaderLongPress = { key -> startSelection(key) },
        onToggleSelection = { key -> toggleSelection(key) },
        onOpenReminders = { key ->
            ReminderListActivity.start(this, key)
        }
    )

    /** 批量删除（整聊天室多选）：ActionMode 与选中集合 */
    private var batchActionMode: android.view.ActionMode? = null
    private val selectedConversations = linkedSetOf<ConvKey>()

    // 保活状态相关视图
    private lateinit var tvKeepAliveStatus: TextView
    private lateinit var btnBatteryWhitelist: Button

    /**
     * 新消息实时刷新：MessageStore 入库回调（主线程分发）。
     * onResume 注册 / onPause 注销，避免后台 Activity 反复重建列表。
     */
    private val storeListener = MessageStore.OnMessagesChangedListener { refreshMessages() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvPermissionStatus = findViewById(R.id.tvPermissionStatus)
        tvEmpty = findViewById(R.id.tvEmpty)
        recyclerMessages = findViewById(R.id.recyclerMessages)

        // M11 标题栏 ☰ 菜单：控制台 / 刷新 / 清除记录（主界面纯对话化）
        setupMainMenu()

        recyclerMessages.layoutManager = LinearLayoutManager(this)
        recyclerMessages.adapter = adapter

        // 左滑删除：会话头=删整个会话；日期头=删该会话当天；其它行不响应
        ItemTouchHelper(SwipeDeleteCallback()).attachToRecyclerView(recyclerMessages)

        // 跳转系统「通知使用权」设置页（BIND_NOTIFICATION_LISTENER_SERVICE
        // 属于特殊权限，无法通过运行时权限弹窗申请，只能引导用户手动开启）
        findViewById<Button>(R.id.btnOpenSettings).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        // 搜索框：实时过滤会话名/发送者/消息文本，命中自动展开并高亮；清空即恢复完整列表
        etSearch = findViewById(R.id.etSearch)
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                searchQuery = s?.toString().orEmpty()
                // 防抖 300ms：每敲一个键都全量重读列表既费 IO 又会让列表抖动，
                // 输入停顿后再刷新一次
                searchHandler.removeCallbacks(searchRefresh)
                searchHandler.postDelayed(searchRefresh, SEARCH_DEBOUNCE_MS)
            }
        })

        // 标题栏快捷操作：全部折叠 / 全部展开（状态持久化；全部展开同时清掉日期组折叠态）
        findViewById<TextView>(R.id.btnCollapseAll).setOnClickListener {
            // 读盘放后台：历史大时主线程 readRecent 会卡住点击响应
            Thread {
                val keys = MessageStore.readRecent(this, 500).map { it.convKey }.toSet()
                CollapseStore.setAll(this, keys, true)
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    refreshMessages()
                }
            }.start()
        }
        findViewById<TextView>(R.id.btnExpandAll).setOnClickListener {
            CollapseStore.setAll(this, emptySet(), false)
            CollapseStore.clearDates(this)
            refreshMessages()
        }

        // 首次启动弹出对接向导（可在控制台重新打开）
        if (OnboardingActivity.shouldShow(this)) {
            startActivity(Intent(this, OnboardingActivity::class.java))
        }

        // Android 13+ 前台服务通知需要运行时权限，否则常驻通知不显示
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                REQ_POST_NOTIFICATIONS
            )
        }
    }

    /** M11 标题栏 ☰ 菜单：控制台 / 刷新列表 / 清除记录。 */
    private fun setupMainMenu() {
        findViewById<TextView>(R.id.btnMenu).setOnClickListener { anchor ->
            val popup = PopupMenu(this, anchor)
            popup.menu.add(getString(R.string.menu_console))
            popup.menu.add(getString(R.string.menu_refresh))
            popup.menu.add(getString(R.string.menu_clear))
            popup.setOnMenuItemClickListener { item ->
                when (item.title) {
                    getString(R.string.menu_console) -> {
                        startActivity(Intent(this, ConsoleActivity::class.java))
                        true
                    }
                    getString(R.string.menu_refresh) -> {
                        refreshMessages()
                        true
                    }
                    getString(R.string.menu_clear) -> {
                        confirmClearAll()
                        true
                    }
                    else -> false
                }
            }
            popup.show()
        }
    }

    /**
     * 「清除记录」二次确认（P0-6）：一次性清空 messages.jsonl 与待上报队列，
     * 没有撤销入口，误触即永久丢失全部历史，必须先确认再执行。
     */
    private fun confirmClearAll() {
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_clear)
            .setMessage(R.string.confirm_clear_all)
            .setNegativeButton(R.string.common_cancel, null)
            .setPositiveButton(R.string.common_delete) { _, _ ->
                MessageStore.clear(this)
                // 待上报队列一并清理，避免历史清空后旧消息仍被上报
                PendingQueue.clear(this)
                refreshMessages()
                Toast.makeText(this, R.string.clear_done, Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    override fun onResume() {
        super.onResume()
        // 从系统设置页返回时刷新权限状态与列表
        refreshPermissionStatus()
        refreshMessages()
        // 监听权限已授予则拉起前台保活服务（用户打开过 App 即常驻）
        if (isListenerEnabled()) startKeepAliveService()
        // 前台期间新消息入库即时刷新列表
        MessageStore.addOnMessagesChangedListener(storeListener)
    }

    override fun onPause() {
        MessageStore.removeOnMessagesChangedListener(storeListener)
        super.onPause()
    }

    /** 拉起前台保活服务（幂等：已运行时系统只走 onStartCommand）。 */
    private fun startKeepAliveService() {
        try {
            startForegroundService(Intent(this, KeepAliveService::class.java))
        } catch (e: Exception) {
            // 极端情况下（如刚被用户划掉且处于后台限制窗口）启动失败，看门狗周期内再试
            android.util.Log.w("MainActivity", "拉起前台保活服务失败", e)
        }
    }

    /** 检查本 app 的通知监听服务是否已被用户授权。 */
    private fun isListenerEnabled(): Boolean {
        val enabledPackages = NotificationManagerCompat.getEnabledListenerPackages(this)
        return enabledPackages.contains(packageName)
    }

    private fun refreshPermissionStatus() {
        val granted = isListenerEnabled()
        // M11：主界面纯对话化——已授权时整条告警隐藏，未授权才显示
        findViewById<View>(R.id.barPermission).visibility =
            if (granted) View.GONE else View.VISIBLE
        tvPermissionStatus.text = getString(
            if (granted) R.string.permission_granted
            else R.string.permission_missing
        )
        // 状态着色：已授予绿 / 未授予红
        tvPermissionStatus.setTextColor(
            getColor(if (granted) R.color.status_ok else R.color.status_error)
        )
    }

    // ================= 批量删除（首页长按多选整个聊天室） =================

    /** 长按会话头：进入 ActionMode 批量选择模式，首个会话直接勾上。 */
    private fun startSelection(key: ConvKey) {
        if (batchActionMode != null) return
        selectedConversations.clear()
        selectedConversations.add(key)
        batchActionMode = startActionMode(batchActionCallback)
        syncSelection()
    }

    /** 选择态下点击会话头：勾选/取消。 */
    private fun toggleSelection(key: ConvKey) {
        if (!selectedConversations.add(key)) selectedConversations.remove(key)
        syncSelection()
    }

    /** 把选择态同步给 adapter（勾选视觉 + chips 屏蔽）并刷新 ActionMode 标题。 */
    private fun syncSelection() {
        adapter.selectionActive = batchActionMode != null
        adapter.selectedConversations = selectedConversations.toSet()
        batchActionMode?.title = getString(R.string.batch_selected_n, selectedConversations.size)
        adapter.notifyDataSetChanged()
    }

    private val batchActionCallback = object : android.view.ActionMode.Callback {
        override fun onCreateActionMode(mode: android.view.ActionMode, menu: Menu): Boolean {
            menu.add(0, MENU_BATCH_DELETE, 0, R.string.batch_delete_action)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            return true
        }

        override fun onPrepareActionMode(mode: android.view.ActionMode, menu: Menu): Boolean = false

        override fun onActionItemClicked(mode: android.view.ActionMode, item: MenuItem): Boolean {
            if (item.itemId == MENU_BATCH_DELETE) {
                confirmBatchDelete()
                return true
            }
            return false
        }

        override fun onDestroyActionMode(mode: android.view.ActionMode) {
            batchActionMode = null
            selectedConversations.clear()
            syncSelection()
        }
    }

    /** 批量删除确认：选中的所有会话整体删除。 */
    private fun confirmBatchDelete() {
        val keys = selectedConversations.toList()
        if (keys.isEmpty()) return
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.confirm_delete_conversations, keys.size))
            .setNegativeButton(R.string.common_cancel, null)
            .setPositiveButton(R.string.common_delete) { _, _ ->
                deleteConversationsFully(keys) { n ->
                    batchActionMode?.finish()
                    refreshMessages()
                    Toast.makeText(this, getString(R.string.delete_done, n), Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    // ================= 删除执行（左滑 / 批量共用） =================

    /**
     * 彻底删除若干会话：消息 + 分析记录 + 待上报队列 + 提醒（闹钟/日历事件/记录）。
     * 返回删除的消息总条数。
     */
    private fun deleteConversationsFully(keys: Collection<ConvKey>, onDone: (Int) -> Unit) {
        val set = keys.toSet()
        // 这里要动六类存储（提醒/闹钟/日历/待上报/分析/消息），全放后台线程：
        // 主线程做这些磁盘与 ContentResolver IO，历史一大会直接 ANR。
        Thread {
            // 提醒：取消闹钟 → 删除已落库日历事件 → 移除提醒记录
            ReminderStore.readAll(this).filter { it.convKey in set }.forEach { r ->
                AlarmHelper.cancelReminder(this, r.dedupKey)
                if (r.calendarEventId > 0L) CalendarHelper.deleteEvent(this, r.calendarEventId)
                ReminderStore.remove(this, r.dedupKey)
            }
            PendingQueue.removeByConversations(this, set)
            keys.forEach { AnalysisStore.deleteByConversation(this, it) }
            var total = 0
            keys.forEach { total += MessageStore.deleteConversation(this, it) }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                onDone(total)
            }
        }.start()
    }

    /** 左滑会话头：确认后删除整个会话；取消则把行还原。 */
    private fun confirmSwipeConversation(position: Int, key: ConvKey) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.confirm_delete_conversation, key.conversation))
            .setNegativeButton(R.string.common_cancel) { _, _ ->
                adapter.notifyItemChanged(position)
            }
            .setPositiveButton(R.string.common_delete) { _, _ ->
                deleteConversationsFully(listOf(key)) { n ->
                    refreshMessages()
                    Toast.makeText(this, getString(R.string.delete_done, n), Toast.LENGTH_SHORT).show()
                }
            }
            .setOnCancelListener { adapter.notifyItemChanged(position) }
            .show()
    }

    /** 左滑日期头：确认后删除该会话某一天的消息；取消则把行还原。 */
    private fun confirmSwipeDate(
        position: Int,
        key: ConvKey,
        dayKey: String,
        label: String,
        count: Int
    ) {
        AlertDialog.Builder(this)
            .setMessage(
                getString(
                    R.string.confirm_delete_date,
                    "${key.conversation} · $label", count
                )
            )
            .setNegativeButton(R.string.common_cancel) { _, _ ->
                adapter.notifyItemChanged(position)
            }
            .setPositiveButton(R.string.common_delete) { _, _ ->
                Thread {
                    PendingQueue.removeByConversationDay(this, key, dayKey)
                    val n = MessageStore.deleteDate(this, key, dayKey)
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        refreshMessages()
                        Toast.makeText(this, getString(R.string.delete_done, n), Toast.LENGTH_SHORT).show()
                    }
                }.start()
            }
            .setOnCancelListener { adapter.notifyItemChanged(position) }
            .show()
    }

    /**
     * 左滑删除手势：仅会话头/日期组头可滑，滑动区铺红色底。
     * 批量选择态禁用滑动，避免与多选操作冲突。
     */
    private inner class SwipeDeleteCallback : ItemTouchHelper.Callback() {
        private val deleteBg = ColorDrawable(Color.parseColor("#E5484D"))

        override fun getMovementFlags(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
            if (adapter.selectionActive) return 0
            return when (viewHolder.itemViewType) {
                FeedAdapter.TYPE_HEADER, FeedAdapter.TYPE_DATE ->
                    makeMovementFlags(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT)
                else -> 0
            }
        }

        override fun onMove(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder
        ): Boolean = false

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
            val position = viewHolder.bindingAdapterPosition
            if (position == RecyclerView.NO_POSITION) return
            when (val item = adapter.feedItemAt(position)) {
                is FeedItem.Header -> confirmSwipeConversation(position, item.key)
                is FeedItem.DateHeader ->
                    confirmSwipeDate(position, item.key, item.dayKey, item.label, item.count)
                else -> adapter.notifyItemChanged(position)
            }
        }

        override fun onChildDraw(
            c: Canvas,
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            dX: Float,
            dY: Float,
            actionState: Int,
            isCurrentlyActive: Boolean
        ) {
            if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE) {
                with(viewHolder.itemView) {
                    deleteBg.setBounds(left, top, right, bottom)
                    deleteBg.draw(c)
                }
            }
            super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
        }
    }

    /**
     * 刷新消息列表：汇总分区 + 会话/日期两级折叠 + 搜索过滤 + 分析结果联动。
     *
     * 结构（自上而下）：
     *  - 分区标题（⭐ 重点关注 N / 其它会话 N）：仅当重点关注名单（去掉哨兵）非空时出现；
     *    空名单（=全部关注）或仅哨兵（=全不关注）时扁平展示，不出分区标题。
     *  - 会话头：组按最新消息时间倒序；点击箭头折叠整组。
     *  - 日期组头：会话展开后，组内消息按日期（yyyy-MM-dd）再分组（时间正序），
     *    点击日期胶囊折叠/展开当天的消息，折叠态独立持久化。
     *  - 消息：窗口内消息带分析标签；待办会话只在窗口末条标 ⚡。
     *
     * 搜索：会话名 / 发送者 / 消息文本（不分大小写）任一命中即保留该会话；
     * 命中会话强制展开，含命中消息的日期组强制展开（其余日期组尊重折叠态），
     * 命中消息卡片橙框高亮；无匹配时提示「没有匹配的会话或消息」。
     */
    /**
     * 刷新消息列表（后台装载 → 主线程渲染）。
     *
     * 读盘与列表组装放后台线程：原来每次按键 / 每次入库都在主线程全量读
     * messages.jsonl + analysis.jsonl + reminders.jsonl，历史一大就卡顿甚至 ANR。
     * 刷新代次 [refreshToken] 保证过期结果不会覆盖更新的列表。
     */
    private fun refreshMessages() {
        val token = ++refreshToken
        val query = searchQuery.trim().lowercase()
        Thread {
            val result = buildFeed(query)
            runOnUiThread {
                if (token != refreshToken || isFinishing || isDestroyed) return@runOnUiThread
                applyFeed(result, query)
            }
        }.start()
    }

    /** 后台装载结果：列表项 + 是否为空态。 */
    private class FeedResult(val items: List<FeedItem>, val empty: Boolean)

    /** 后台线程执行：读存储 + 过滤 + 组装 [FeedItem]（不碰视图）。 */
    private fun buildFeed(query: String): FeedResult {
        val messages = MessageStore.readRecent(this, 500)
        val collapsed = CollapseStore.load(this)
        val collapsedDates = CollapseStore.loadDates(this)
        val latestCases = AnalysisStore.latestByConvKey(this)
        // 有提醒的会话集合：首页会话头显示 📅
        val reminderKeys = ReminderStore.readAll(this)
            .map { it.convKey }.toSet()

        val dayKeyFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val currentYear = SimpleDateFormat("yyyy", Locale.getDefault()).format(Date())
        val labelSameYear = SimpleDateFormat("M月d日", Locale.getDefault())
        val labelCrossYear = SimpleDateFormat("yyyy年M月d日", Locale.getDefault())

        var groups = messages.groupBy { it.convKey }
            .map { (k, list) -> k to list.sortedBy { it.timestamp } }
            .sortedByDescending { (_, list) -> list.last().timestamp }

        // 搜索过滤：会话名 / 发送者/消息文本任一命中即保留
        if (query.isNotEmpty()) {
            groups = groups.filter { (k, list) ->
                k.conversation.lowercase().contains(query) || list.any {
                    it.sender.lowercase().contains(query) ||
                        it.text.lowercase().contains(query)
                }
            }
        }

        // M11：显式不关注的会话直接隐藏（不进主列表，分析也已跳过）
        val unwatchedIds = WatchlistStore.getUnwatched(this)
        // 按采集开关过滤：黑名单勾选 / 白名单外的 App，其会话不进主页；
        // 自身与系统源（如 a11y 自抓噪声）同样不展示
        val visibleGroups = groups.filter { it.first.id !in unwatchedIds }
            .filter {
                !AppSourceRegistry.isBlocked(it.first.pkg) &&
                    AppSourceStore.isEnabled(this, it.first.pkg)
            }

        // 汇总分区：名单（去掉「全不关注」哨兵）非空时才分区
        val watched = WatchlistStore.getWatched(this)
        watched.remove(WatchlistStore.SENTINEL_NONE)
        val watchedKeys = ConvKey.parseAll(watched)
        val sections =
            mutableListOf<Pair<String?, List<Pair<ConvKey, List<ChatMessage>>>>>()
        if (watchedKeys.isEmpty()) {
            sections.add(null to visibleGroups)
        } else {
            val star = visibleGroups.filter { it.first in watchedKeys }
            val others = visibleGroups.filter { it.first !in watchedKeys }
            if (star.isNotEmpty()) {
                sections.add(getString(R.string.section_watchlist, star.size) to star)
            }
            if (others.isNotEmpty()) {
                sections.add(getString(R.string.section_others, others.size) to others)
            }
        }

        val items = mutableListOf<FeedItem>()
        for ((sectionTitle, sectionGroups) in sections) {
            if (sectionTitle != null) items += FeedItem.Section(sectionTitle)
            for ((key, list) in sectionGroups) {
                // 搜索期间会话强制展开，保证命中内容可见
                val isCollapsed = query.isEmpty() && key.id in collapsed
                val case = latestCases[key]
                items += FeedItem.Header(
                    key = key,
                    appLabel = list.last().appLabel,
                    isGroup = list.last().isGroup,
                    count = list.size,
                    preview = "${list.last().sender}: ${list.last().text}",
                    collapsed = isCollapsed,
                    analysis = case,
                    hasReminder = key in reminderKeys
                )
                if (isCollapsed) continue

                // 窗口内消息集合：时间戳 ≤ windowEnd 的最近 messageCount 条
                val windowMessages = case?.let { c ->
                    list.filter { it.timestamp <= c.windowEnd }
                        .takeLast(c.messageCount)
                        .toSet()
                }.orEmpty()

                // 二级分组：按日期切分（groupBy 保序，组内消息已是时间正序）。
                // 时间戳兜底：a11y 直读等来源可能带来 ts<=0 的估算失败值，归「未标注日期」组，
                // 避免被格式化成 1970 年。
                val convNameHit = query.isNotEmpty() &&
                    key.conversation.lowercase().contains(query)
                val byDate = list.groupBy {
                    if (it.timestamp > 0) dayKeyFormat.format(Date(it.timestamp))
                    else DAY_KEY_UNKNOWN
                }
                for ((dayKey, dayMessages) in byDate) {
                    val dateHit = query.isNotEmpty() && dayMessages.any {
                        it.sender.lowercase().contains(query) ||
                            it.text.lowercase().contains(query)
                    }
                    val dateCollapsed = CollapseStore.isDateCollapsed(
                        collapsedDates, key, dayKey
                    ) && !(query.isNotEmpty() && (dateHit || convNameHit))
                    val label = if (dayKey == DAY_KEY_UNKNOWN) {
                        getString(R.string.date_unknown_label)
                    } else if (dayKey.startsWith(currentYear)) {
                        labelSameYear.format(Date(dayMessages.first().timestamp))
                    } else {
                        labelCrossYear.format(Date(dayMessages.first().timestamp))
                    }
                    items += FeedItem.DateHeader(
                        key = key,
                        dayKey = dayKey,
                        label = label,
                        count = dayMessages.size,
                        collapsed = dateCollapsed
                    )
                    if (dateCollapsed) continue
                    dayMessages.forEach { msg ->
                        val hit = query.isNotEmpty() && (
                            msg.sender.lowercase().contains(query) ||
                                msg.text.lowercase().contains(query)
                            )
                        items += FeedItem.Msg(
                            message = msg,
                            // 仅窗口内消息带分析标签；待办会话只在窗口末条上标 ⚡
                            analysis = if (msg in windowMessages) case else null,
                            isWindowLast = case != null && msg.timestamp == case.windowEnd,
                            highlight = hit
                        )
                    }
                }
            }
        }
        return FeedResult(items, visibleGroups.isEmpty())
    }

    /** 主线程渲染：提交列表数据 + 空态提示。 */
    private fun applyFeed(result: FeedResult, query: String) {
        adapter.submit(result.items)
        if (result.empty) {
            tvEmpty.text = getString(
                if (query.isNotEmpty()) R.string.search_no_result else R.string.empty_hint
            )
            tvEmpty.visibility = View.VISIBLE
        } else {
            tvEmpty.visibility = View.GONE
        }
    }

    /**
     * 首页会话头「关注」chip：切换某会话的重点关注状态。
     * M11 语义：显式不关注的会话进 unwatched 黑名单——主列表隐藏、分析跳过。
     *  - 关注它 = 从黑名单移除（旧白名单物化数据兼容保留）；
     *  - 取消关注 = 加入黑名单（主列表隐藏 + 分析跳过）。
     */
    private fun toggleWatch(key: ConvKey) {
        val unwatched = WatchlistStore.getUnwatched(this).toMutableSet()
        val isWatchedNow = WatchlistStore.isWatched(this, key)
        if (isWatchedNow) {
            unwatched.add(key.id)
        } else {
            unwatched.remove(key.id)
        }
        WatchlistStore.setUnwatched(this, unwatched)

        // 兼容旧白名单物化数据：名单非空且不含哨兵时，维持原有增删
        val watched = WatchlistStore.getWatched(this)
        watched.remove(WatchlistStore.SENTINEL_NONE)
        if (watched.isNotEmpty()) {
            if (isWatchedNow) watched.remove(key.id) else watched.add(key.id)
            if (watched.isEmpty()) watched.add(WatchlistStore.SENTINEL_NONE)
            WatchlistStore.setWatched(this, watched)
        }

        Toast.makeText(
            this,
            if (isWatchedNow) R.string.quick_watch_removed else R.string.quick_watch_added,
            Toast.LENGTH_SHORT
        ).show()
        refreshMessages()
    }

    /** 列表数据项：分区标题 / 会话头（带可选分析角标） / 日期组头 / 消息（带可选分析结果与高亮） */
    private sealed class FeedItem {
        data class Section(val title: String) : FeedItem()

        data class Header(
            val key: ConvKey,
            val appLabel: String,
            val isGroup: Boolean,
            val count: Int,
            val preview: String,
            val collapsed: Boolean,
            val analysis: AnalysisCaseRecord?,
            val hasReminder: Boolean
        ) : FeedItem()

        data class DateHeader(
            val key: ConvKey,
            val dayKey: String,
            val label: String,
            val count: Int,
            val collapsed: Boolean
        ) : FeedItem()

        data class Msg(
            val message: ChatMessage,
            val analysis: AnalysisCaseRecord?,
            val isWindowLast: Boolean,
            val highlight: Boolean
        ) : FeedItem()
    }

    /** 消息列表 Adapter：分区标题 / 会话头 / 日期组头 / 消息四种 view type，
     *  折叠的组（会话或日期）内消息不入数据集。
     *  会话头热区分开：文字区进详情页，箭头折叠/展开；日期组头整行可点折叠。 */
    private class FeedAdapter(
        private val onToggle: (ConvKey) -> Unit,
        private val onOpen: (ConvKey) -> Unit,
        private val onToggleDate: (ConvKey, String) -> Unit,
        private val onToggleWatch: (ConvKey) -> Unit,
        private val onHeaderLongPress: (ConvKey) -> Unit,
        private val onToggleSelection: (ConvKey) -> Unit,
        private val onOpenReminders: (ConvKey) -> Unit
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        companion object {
            const val TYPE_HEADER = 0
            const val TYPE_MESSAGE = 1
            const val TYPE_SECTION = 2
            const val TYPE_DATE = 3
        }

        /** 当前数据集：submit() 整体替换，不再原地清空/追加。 */
        private var items: List<FeedItem> = emptyList()
        private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())

        /** 批量选择态：由 MainActivity 在 ActionMode 开关时同步 */
        var selectionActive: Boolean = false
        var selectedConversations: Set<ConvKey> = emptySet()

        /** 供 SwipeDeleteCallback 按位置取原始 item。 */
        fun feedItemAt(position: Int): FeedItem = items[position]

        /**
         * 提交新数据集：用 DiffUtil 只重绘真正变化的行。
         * 原来每次都 notifyDataSetChanged()，500 条消息的列表会被整体重建，
         * 搜索/折叠/新消息到达时都会明显掉帧。
         */
        fun submit(newItems: List<FeedItem>) {
            val old = items
            items = newItems
            val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize(): Int = old.size

                override fun getNewListSize(): Int = newItems.size

                override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean =
                    identityOf(old[oldPos]) == identityOf(newItems[newPos])

                override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean =
                    old[oldPos] == newItems[newPos]
            })
            diff.dispatchUpdatesTo(this)
        }

        /**
         * 数据项身份：身份相同才复用同一个 ViewHolder。
         * 各子类的「内容」字段（折叠态、分析角标、搜索高亮）刻意不参与身份判定。
         */
        private fun identityOf(item: FeedItem): Any = when (item) {
            is FeedItem.Section -> item.title
            is FeedItem.Header -> item.key
            is FeedItem.DateHeader -> item.key to item.dayKey
            is FeedItem.Msg -> item.message
        }

        override fun getItemViewType(position: Int): Int = when (items[position]) {
            is FeedItem.Header -> TYPE_HEADER
            is FeedItem.Msg -> TYPE_MESSAGE
            is FeedItem.Section -> TYPE_SECTION
            is FeedItem.DateHeader -> TYPE_DATE
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return when (viewType) {
                TYPE_HEADER -> HeaderVH(
                    inflater.inflate(R.layout.item_conversation_header, parent, false)
                )
                TYPE_SECTION -> SectionVH(
                    inflater.inflate(R.layout.item_section_header, parent, false)
                )
                TYPE_DATE -> DateVH(
                    inflater.inflate(R.layout.item_date_header, parent, false)
                )
                else -> MsgVH(inflater.inflate(R.layout.item_message, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val item = items[position]) {
                is FeedItem.Header -> bindHeader(holder as HeaderVH, item)
                is FeedItem.Msg -> bindMessage(holder as MsgVH, item)
                is FeedItem.Section -> (holder as SectionVH).tvSectionTitle.text = item.title
                is FeedItem.DateHeader -> bindDateHeader(holder as DateVH, item)
            }
        }

        private fun bindTag(tag: TextView, isGroup: Boolean) {
            val ctx = tag.context
            // 群/私 badge：绿底白字=群聊，灰底灰字=私聊
            if (isGroup) {
                tag.text = ctx.getString(R.string.group_tag)
                tag.setBackgroundResource(R.drawable.bg_tag_group)
                tag.setTextColor(ctx.getColor(R.color.card_bg))
            } else {
                tag.text = ctx.getString(R.string.private_tag)
                tag.setBackgroundResource(R.drawable.bg_tag_private)
                tag.setTextColor(ctx.getColor(R.color.text_secondary))
            }
        }

        private fun bindHeader(holder: HeaderVH, header: FeedItem.Header) {
            val ctx = holder.itemView.context
            val key = header.key
            bindTag(holder.tvTag, header.isGroup)
            holder.tvConversation.text = key.conversation
            holder.tvCount.text = ctx.getString(R.string.msg_count, header.count)
            holder.tvPreview.text = header.preview
            holder.tvArrow.text = if (header.collapsed) "▶" else "▼"

            // 来源 App 标记（M11：所有 App 都显示，含微信——多来源场景下来源必须可见）
            holder.tvApp.visibility = View.VISIBLE
            holder.tvApp.text = key.displayLabel(header.appLabel)

            // 批量选择视觉：勾选符 + 会话名着色
            val selected = selectionActive && key in selectedConversations
            holder.tvSelectCheck.visibility = if (selected) View.VISIBLE else View.GONE
            holder.tvSelectCheck.text = "✓"
            holder.tvSelectCheck.setTextColor(ctx.getColor(R.color.brand_green))
            holder.tvConversation.setTextColor(
                ctx.getColor(if (selected) R.color.brand_green else R.color.text_primary)
            )

            // 分析角标：有待办=橙底「⚡N」（N=任务数，无 S2 任务时按 1 计），
            // 已分析=灰点，未分析隐藏
            val case = header.analysis
            when {
                case == null -> holder.tvAnalysisBadge.visibility = View.GONE
                case.s1NeedAction -> {
                    holder.tvAnalysisBadge.visibility = View.VISIBLE
                    val todoCount = if (case.s2Tasks.isNotEmpty()) case.s2Tasks.size else 1
                    holder.tvAnalysisBadge.text =
                        ctx.getString(R.string.header_badge_todo, todoCount)
                    holder.tvAnalysisBadge.setBackgroundResource(R.drawable.bg_tag_todo)
                    holder.tvAnalysisBadge.setTextColor(ctx.getColor(R.color.card_bg))
                }
                else -> {
                    holder.tvAnalysisBadge.visibility = View.VISIBLE
                    holder.tvAnalysisBadge.text =
                        ctx.getString(R.string.header_badge_analyzed)
                    holder.tvAnalysisBadge.setBackgroundResource(0)
                    holder.tvAnalysisBadge.setTextColor(ctx.getColor(R.color.time_gray))
                }
            }

            // 📅 提醒角标：该会话存在提醒（日历/闹钟/待保存）时显示，点击进提醒管理
            if (header.hasReminder) {
                holder.tvReminderBadge.visibility = View.VISIBLE
                holder.tvReminderBadge.setOnClickListener { onOpenReminders(key) }
            } else {
                holder.tvReminderBadge.visibility = View.GONE
            }

            // 热区：普通态文字区进详情页 / 长按进多选；选择态点击=勾选
            holder.itemView.setOnClickListener { onOpen(key) }
            holder.itemView.setOnLongClickListener {
                onHeaderLongPress(key)
                true
            }
            holder.tvArrow.setOnClickListener { onToggle(key) }

            // 快捷入口：关注（切换，文案/颜色反映状态）。选择态屏蔽 chips 操作
            val watchedNow = WatchlistStore.isWatched(ctx, key)
            holder.btnQuickWatch.text = ctx.getString(
                if (watchedNow) R.string.quick_watched else R.string.quick_watch
            )
            holder.btnQuickWatch.setTextColor(
                ctx.getColor(if (watchedNow) R.color.brand_green else R.color.text_secondary)
            )
            holder.btnQuickWatch.setOnClickListener { onToggleWatch(key) }

            // 提示词注入：已设自定义提示词则绿色，点击进编辑页
            val promptSet = PromptStore.hasPrompt(ctx, key)
            holder.btnQuickPrompt.text = ctx.getString(R.string.quick_prompt)
            holder.btnQuickPrompt.setTextColor(
                ctx.getColor(if (promptSet) R.color.brand_green else R.color.text_secondary)
            )
            holder.btnQuickPrompt.setOnClickListener {
                if (!selectionActive) PromptEditActivity.start(ctx, key)
            }

            // 立即分析本会话：入队 WorkManager，chip 下方进度条反映排队/运行状态
            holder.btnQuickAnalyze.text = ctx.getString(R.string.quick_analyze)
            holder.btnQuickAnalyze.setTextColor(ctx.getColor(R.color.text_secondary))
            holder.btnQuickAnalyze.setOnClickListener {
                if (selectionActive) return@setOnClickListener
                AnalysisScheduler.enqueueAnalysisNow(ctx, key)
                Toast.makeText(ctx, R.string.analysis_enqueued_conv, Toast.LENGTH_SHORT).show()
            }
            bindAnalyzeProgress(holder, key)

            // 分析结果：打开只含本会话的结果列表
            holder.btnQuickResult.text = ctx.getString(R.string.quick_result)
            holder.btnQuickResult.setTextColor(ctx.getColor(R.color.text_secondary))
            holder.btnQuickResult.setOnClickListener {
                if (!selectionActive) AnalysisListActivity.start(ctx, key)
            }
        }

        /**
         * 分析 chip 进度条：按会话 tag 观察 WorkManager。
         * 存在 ENQUEUED/RUNNING 的按需分析任务即显示进度条；
         * 复用的 ViewHolder 先摘掉旧 observer，回收时统一清理。
         */
        private fun bindAnalyzeProgress(holder: HeaderVH, key: ConvKey) {
            val ctx = holder.itemView.context
            val wm = WorkManager.getInstance(ctx)
            holder.progressObserver?.let { old ->
                holder.progressTag?.let { tag ->
                    runCatching { wm.getWorkInfosByTagLiveData(tag).removeObserver(old) }
                }
            }
            val tag = AnalysisScheduler.convTag(key)
            val observer = Observer<List<WorkInfo>> { infos ->
                val active = infos?.any { !it.state.isFinished } == true
                holder.progressAnalyze.visibility =
                    if (active) View.VISIBLE else View.GONE
            }
            holder.progressTag = tag
            holder.progressObserver = observer
            wm.getWorkInfosByTagLiveData(tag).observeForever(observer)
        }

        private fun bindDateHeader(holder: DateVH, header: FeedItem.DateHeader) {
            val ctx = holder.itemView.context
            holder.tvDateLabel.text = header.label
            holder.tvDateCount.text = ctx.getString(R.string.msg_count, header.count)
            holder.tvDateArrow.text = if (header.collapsed) "▶" else "▼"
            // 整行点击折叠/展开该日期组
            holder.itemView.setOnClickListener { onToggleDate(header.key, header.dayKey) }
        }

        private fun bindMessage(holder: MsgVH, item: FeedItem.Msg) {
            val msg = item.message
            val ctx = holder.itemView.context
            bindTag(holder.tvTag, msg.isGroup)
            holder.tvConversation.text = msg.conversation
            holder.tvSender.text = msg.sender
            holder.tvText.text = msg.text
            // 时间戳兜底：ts<=0 显示「—」，避免出现 1970-01-01
            holder.tvTime.text =
                if (msg.timestamp > 0) timeFormat.format(Date(msg.timestamp)) else "—"

            // 搜索命中：卡片换橙底描边高亮；未命中恢复默认白卡
            holder.itemView.setBackgroundResource(
                if (item.highlight) R.drawable.bg_card_highlight else R.drawable.bg_card
            )

            // 分析结果联动（会话级 case 映射）：窗口内消息标「已分析」；
            // 待办会话只在窗口末条上显示「⚡ 待办 + 摘要 + 截止」
            val analysis = item.analysis
            when {
                analysis == null -> holder.tvAnalysis.visibility = View.GONE
                item.isWindowLast && analysis.s1NeedAction -> {
                    holder.tvAnalysis.visibility = View.VISIBLE
                    holder.tvAnalysis.setBackgroundResource(R.drawable.bg_tag_todo)
                    holder.tvAnalysis.setTextColor(ctx.getColor(R.color.card_bg))
                    val summary = analysis.s2Summary.ifEmpty {
                        msg.text.replace("\n", " ").take(15)
                    }
                    val dueText = analysis.s2DueTime.ifEmpty { analysis.s1DueWindow }
                    val due = if (dueText.isNotEmpty() && dueText != "none") {
                        " · " + ctx.getString(R.string.analysis_due_prefix) + dueText
                    } else ""
                    holder.tvAnalysis.text =
                        ctx.getString(R.string.analysis_label_todo) + " " + summary + due
                }
                else -> {
                    holder.tvAnalysis.visibility = View.VISIBLE
                    holder.tvAnalysis.setBackgroundResource(R.drawable.bg_tag_analyzed)
                    holder.tvAnalysis.setTextColor(ctx.getColor(R.color.text_secondary))
                    holder.tvAnalysis.text = ctx.getString(R.string.analysis_label_done)
                }
            }
        }

        override fun getItemCount(): Int = items.size

        /** 回收会话头时移除进度 observer，防止 observeForever 泄漏。 */
        override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
            if (holder is HeaderVH) {
                val observer = holder.progressObserver
                val tag = holder.progressTag
                if (observer != null && tag != null) {
                    runCatching {
                        WorkManager.getInstance(holder.itemView.context)
                            .getWorkInfosByTagLiveData(tag).removeObserver(observer)
                    }
                }
                holder.progressObserver = null
                holder.progressTag = null
            }
            super.onViewRecycled(holder)
        }

        /** RecyclerView 销毁时清理仍挂载在可见会话头上的 observer。 */
        override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
            for (i in 0 until recyclerView.childCount) {
                val holder = recyclerView.getChildViewHolder(recyclerView.getChildAt(i))
                if (holder is HeaderVH) {
                    val observer = holder.progressObserver
                    val tag = holder.progressTag
                    if (observer != null && tag != null) {
                        runCatching {
                            WorkManager.getInstance(holder.itemView.context)
                                .getWorkInfosByTagLiveData(tag).removeObserver(observer)
                        }
                    }
                    holder.progressObserver = null
                    holder.progressTag = null
                }
            }
            super.onDetachedFromRecyclerView(recyclerView)
        }

        class SectionVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val tvSectionTitle: TextView = itemView.findViewById(R.id.tvSectionTitle)
        }

        class HeaderVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val tvTag: TextView = itemView.findViewById(R.id.tvTag)
            val tvApp: TextView = itemView.findViewById(R.id.tvApp)
            val tvSelectCheck: TextView = itemView.findViewById(R.id.tvSelectCheck)
            val tvConversation: TextView = itemView.findViewById(R.id.tvConversation)
            val tvAnalysisBadge: TextView = itemView.findViewById(R.id.tvAnalysisBadge)
            val tvReminderBadge: TextView = itemView.findViewById(R.id.tvReminderBadge)
            val tvCount: TextView = itemView.findViewById(R.id.tvCount)
            val tvPreview: TextView = itemView.findViewById(R.id.tvPreview)
            val tvArrow: TextView = itemView.findViewById(R.id.tvArrow)
            val btnQuickWatch: TextView = itemView.findViewById(R.id.btnQuickWatch)
            val btnQuickPrompt: TextView = itemView.findViewById(R.id.btnQuickPrompt)
            val btnQuickAnalyze: TextView = itemView.findViewById(R.id.btnQuickAnalyze)
            val btnQuickResult: TextView = itemView.findViewById(R.id.btnQuickResult)
            val progressAnalyze: android.widget.ProgressBar =
                itemView.findViewById(R.id.progressAnalyze)

            // WorkManager 进度观察句柄（observeForever，必须成对移除）
            var progressTag: String? = null
            var progressObserver: Observer<List<WorkInfo>>? = null
        }

        class DateVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val tvDateLabel: TextView = itemView.findViewById(R.id.tvDateLabel)
            val tvDateCount: TextView = itemView.findViewById(R.id.tvDateCount)
            val tvDateArrow: TextView = itemView.findViewById(R.id.tvDateArrow)
        }

        class MsgVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val tvTag: TextView = itemView.findViewById(R.id.tvTag)
            val tvConversation: TextView = itemView.findViewById(R.id.tvConversation)
            val tvSender: TextView = itemView.findViewById(R.id.tvSender)
            val tvText: TextView = itemView.findViewById(R.id.tvText)
            val tvTime: TextView = itemView.findViewById(R.id.tvTime)
            val tvAnalysis: TextView = itemView.findViewById(R.id.tvAnalysis)
        }
    }
}

/**
 * 首页折叠状态持久化：SharedPreferences 存「已折叠会话集合」与「已折叠日期组集合」。
 *  - 会话层元素是 ConvKey.id；
 *  - 日期层元素是 ConvKey.id + [DATE_SEP] + yyyy-MM-dd；
 *  两层折叠态互相独立，各自持久化。
 */
object CollapseStore {
    private const val PREFS_NAME = "collapse_state"
    private const val KEY_COLLAPSED = "collapsed_conversations"
    private const val KEY_COLLAPSED_DATES = "collapsed_dates"

    /** 日期组复合 key 的分隔符：刻意不用 '|'（那是 ConvKey 内部分隔符） */
    private const val DATE_SEP = ""

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_COLLAPSED, emptySet()).orEmpty()

    fun setCollapsed(context: Context, key: ConvKey, collapsed: Boolean) {
        val set = load(context).toMutableSet()
        if (collapsed) set.add(key.id) else set.remove(key.id)
        prefs(context).edit().putStringSet(KEY_COLLAPSED, set).apply()
    }

    /** collapsed=true 收拢全部给定会话；false 清空集合（全部展开） */
    fun setAll(context: Context, keys: Collection<ConvKey>, collapsed: Boolean) {
        val set = if (collapsed) keys.map { it.id }.toSet() else emptySet()
        prefs(context).edit().putStringSet(KEY_COLLAPSED, set).apply()
    }

    fun loadDates(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_COLLAPSED_DATES, emptySet()).orEmpty()

    private fun dateComposite(key: ConvKey, dayKey: String): String =
        "${key.id}$DATE_SEP$dayKey"

    /** 某会话某天是否处于折叠态。 */
    fun isDateCollapsed(
        collapsedDates: Set<String>, key: ConvKey, dayKey: String
    ): Boolean = collapsedDates.contains(dateComposite(key, dayKey))

    /** 日期组折叠/展开。true=折叠；false=展开；切换语义由调用方决定。 */
    fun setDateCollapsed(
        context: Context, key: ConvKey, dayKey: String, collapsed: Boolean
    ) {
        val set = loadDates(context).toMutableSet()
        val composite = dateComposite(key, dayKey)
        if (collapsed) set.add(composite) else set.remove(composite)
        prefs(context).edit().putStringSet(KEY_COLLAPSED_DATES, set).apply()
    }

    /** 清空全部日期组折叠态（「全部展开」时一并复位） */
    fun clearDates(context: Context) {
        prefs(context).edit().putStringSet(KEY_COLLAPSED_DATES, emptySet()).apply()
    }
}
