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
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Observer
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
 *  - RecyclerView 展示 MessageStore 里最近捕获的微信消息；
 *  - 支持手动刷新与清除记录；
 *  - 汇总分区（⭐ 重点关注 / 其它会话）+ 分层折叠（会话 → 日期）+ 搜索过滤。
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
    }

    private lateinit var tvPermissionStatus: TextView
    private lateinit var tvEmpty: TextView
    private lateinit var recyclerMessages: RecyclerView
    private lateinit var etSearch: EditText

    /** 当前搜索关键词（空串 = 不过滤）；由搜索框 TextWatcher 维护 */
    private var searchQuery = ""

    private val adapter: FeedAdapter = FeedAdapter(
        onToggle = { conversation ->
            if (adapter.selectionActive) return@FeedAdapter
            // 点击箭头：折叠/展开整组并持久化
            val collapsed = CollapseStore.load(this)
            CollapseStore.setCollapsed(this, conversation, conversation !in collapsed)
            refreshMessages()
        },
        onOpen = { conversation ->
            // 点击文字区：批量选择态=勾选；否则进会话详情页
            if (adapter.selectionActive) toggleSelection(conversation)
            else ConversationActivity.start(this, conversation)
        },
        onToggleDate = { dateKey ->
            if (adapter.selectionActive) return@FeedAdapter
            // 点击日期组头：折叠/展开该会话下这一天的消息并持久化
            val dates = CollapseStore.loadDates(this)
            CollapseStore.setDateCollapsed(this, dateKey, dateKey !in dates)
            refreshMessages()
        },
        onToggleWatch = { conversation ->
            if (!adapter.selectionActive) toggleWatch(conversation)
        },
        onHeaderLongPress = { conversation -> startSelection(conversation) },
        onToggleSelection = { conversation -> toggleSelection(conversation) },
        onOpenReminders = { conversation ->
            ReminderListActivity.start(this, conversation)
        }
    )

    /** 批量删除（整聊天室多选）：ActionMode 与选中集合 */
    private var batchActionMode: android.view.ActionMode? = null
    private val selectedConversations = linkedSetOf<String>()

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

        recyclerMessages.layoutManager = LinearLayoutManager(this)
        recyclerMessages.adapter = adapter

        // 左滑删除：会话头=删整个会话；日期头=删该会话当天；其它行不响应
        ItemTouchHelper(SwipeDeleteCallback()).attachToRecyclerView(recyclerMessages)

        // 跳转系统「通知使用权」设置页（BIND_NOTIFICATION_LISTENER_SERVICE
        // 属于特殊权限，无法通过运行时权限弹窗申请，只能引导用户手动开启）
        findViewById<Button>(R.id.btnOpenSettings).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        findViewById<Button>(R.id.btnRefresh).setOnClickListener { refreshMessages() }

        findViewById<Button>(R.id.btnClear).setOnClickListener {
            MessageStore.clear(this)
            // 待上报队列一并清理，避免历史清空后旧消息仍被上报
            PendingQueue.clear(this)
            refreshMessages()
            Toast.makeText(this, R.string.clear_done, Toast.LENGTH_SHORT).show()
        }

        // 控制台入口（上报/分析/提醒等配置都在控制台）
        findViewById<Button>(R.id.btnConsole).setOnClickListener {
            startActivity(Intent(this, ConsoleActivity::class.java))
        }

        // 搜索框：实时过滤会话名/发送者/消息文本，命中自动展开并高亮；清空即恢复完整列表
        etSearch = findViewById(R.id.etSearch)
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                searchQuery = s?.toString().orEmpty()
                refreshMessages()
            }
        })

        // 标题栏快捷操作：全部折叠 / 全部展开（状态持久化；全部展开同时清掉日期组折叠态）
        findViewById<TextView>(R.id.btnCollapseAll).setOnClickListener {
            val convs = MessageStore.readRecent(this, 500)
                .map { it.conversation }.toSet()
            CollapseStore.setAll(this, convs, true)
            refreshMessages()
        }
        findViewById<TextView>(R.id.btnExpandAll).setOnClickListener {
            CollapseStore.setAll(this, emptySet(), false)
            CollapseStore.clearDates(this)
            refreshMessages()
        }

        // 首次启动弹出「对接微信」向导（可在控制台重新打开）
        if (OnboardingActivity.shouldShow(this)) {
            startActivity(Intent(this, OnboardingActivity::class.java))
        }

        setupKeepAliveSection()
    }

    override fun onResume() {
        super.onResume()
        // 从系统设置页返回时刷新权限状态与列表
        refreshPermissionStatus()
        refreshMessages()
        // 监听权限已授予则拉起前台保活服务（用户打开过 App 即常驻）
        if (isListenerEnabled()) startKeepAliveService()
        refreshKeepAliveStatus()
        // 前台期间新消息入库即时刷新列表
        MessageStore.addOnMessagesChangedListener(storeListener)
    }

    override fun onPause() {
        MessageStore.removeOnMessagesChangedListener(storeListener)
        super.onPause()
    }

    /** 保活区初始化：电池优化白名单按钮 + 通知运行时权限请求。 */
    private fun setupKeepAliveSection() {
        btnBatteryWhitelist = findViewById(R.id.btnBatteryWhitelist)
        tvKeepAliveStatus = findViewById(R.id.tvKeepAliveStatus)

        btnBatteryWhitelist.setOnClickListener {
            // 跳系统弹窗请求把本 App 加入电池优化白名单
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
            )
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

    /** 拉起前台保活服务（幂等：已运行时系统只走 onStartCommand）。 */
    private fun startKeepAliveService() {
        try {
            startForegroundService(Intent(this, KeepAliveService::class.java))
        } catch (e: Exception) {
            // 极端情况下（如刚被用户划掉且处于后台限制窗口）启动失败，看门狗周期内再试
            android.util.Log.w("MainActivity", "拉起前台保活服务失败", e)
        }
    }

    /** 刷新保活状态显示：前台服务是否运行 + 电池优化是否已忽略。
     *  运行判定读 SharedPreferences 心跳（KeepAliveService.isAlive），
     *  不依赖进程内存标志——进程被系统重启后服务仍在时也能正确显示。 */
    private fun refreshKeepAliveStatus() {
        val pm = getSystemService(PowerManager::class.java)
        val ignoringBattery = pm.isIgnoringBatteryOptimizations(packageName)

        tvKeepAliveStatus.text = getString(
            if (KeepAliveService.isAlive(this)) R.string.keepalive_running
            else R.string.keepalive_stopped
        ) + "\n" + getString(
            if (ignoringBattery) R.string.battery_whitelisted
            else R.string.battery_not_whitelisted
        )

        btnBatteryWhitelist.isEnabled = !ignoringBattery
        btnBatteryWhitelist.text = getString(
            if (ignoringBattery) R.string.btn_battery_whitelisted
            else R.string.btn_battery_whitelist
        )

        // startForegroundService 是异步的，服务写心跳有 1-2s 延迟，
        // 延迟再刷一次避免刚拉起时闪一下「未运行」
        tvKeepAliveStatus.removeCallbacks(refreshKeepAliveOnce)
        tvKeepAliveStatus.postDelayed(refreshKeepAliveOnce, 2000)
    }

    private val refreshKeepAliveOnce = Runnable {
        tvKeepAliveStatus.text = getString(
            if (KeepAliveService.isAlive(this)) R.string.keepalive_running
            else R.string.keepalive_stopped
        ) + "\n" + tvKeepAliveStatus.text.toString().substringAfter("\n", "")
    }

    /** 检查本 app 的通知监听服务是否已被用户授权。 */
    private fun isListenerEnabled(): Boolean {
        val enabledPackages = NotificationManagerCompat.getEnabledListenerPackages(this)
        return enabledPackages.contains(packageName)
    }

    /**
     * 首页会话头「关注」chip：切换某会话的重点关注状态。
     * 语义与 WatchlistActivity.setConversationWatched 一致：
     *  - 空名单=全部关注（默认）；在此态下取消某会话，先把名单物化为「当前全量 - 该项」；
     *  - 全不关注态（哨兵）下关注某会话，去掉哨兵只留该项；
     *  - 取消后名单变空需补哨兵，否则语义会反弹回全部关注。
     */
    private fun toggleWatch(conversation: String) {
        val watched = WatchlistStore.getWatched(this)
        val isWatchedNow = watched.isEmpty() || watched.contains(conversation)
        watched.remove(WatchlistStore.SENTINEL_NONE)
        if (watched.isEmpty() && isWatchedNow) {
            // 全部关注态 -> 取消该项：物化为当前已知全量会话再移除
            MessageStore.readRecent(this, 1000).forEach { watched.add(it.conversation) }
        }
        if (isWatchedNow) watched.remove(conversation) else watched.add(conversation)
        if (watched.isEmpty()) watched.add(WatchlistStore.SENTINEL_NONE)
        WatchlistStore.setWatched(this, watched)

        Toast.makeText(
            this,
            if (isWatchedNow) R.string.quick_watch_removed else R.string.quick_watch_added,
            Toast.LENGTH_SHORT
        ).show()
        refreshMessages()
    }

    // ================= 批量删除（首页长按多选整个聊天室） =================

    /** 长按会话头：进入 ActionMode 批量选择模式，首个会话直接勾上。 */
    private fun startSelection(conversation: String) {
        if (batchActionMode != null) return
        selectedConversations.clear()
        selectedConversations.add(conversation)
        batchActionMode = startActionMode(batchActionCallback)
        syncSelection()
    }

    /** 选择态下点击会话头：勾选/取消。 */
    private fun toggleSelection(conversation: String) {
        if (!selectedConversations.add(conversation)) selectedConversations.remove(conversation)
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
        val convs = selectedConversations.toList()
        if (convs.isEmpty()) return
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.confirm_delete_conversations, convs.size))
            .setNegativeButton(R.string.common_cancel, null)
            .setPositiveButton(R.string.common_delete) { _, _ ->
                val n = deleteConversationsFully(convs)
                batchActionMode?.finish()
                refreshMessages()
                Toast.makeText(this, getString(R.string.delete_done, n), Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    // ================= 删除执行（左滑 / 批量共用） =================

    /**
     * 彻底删除若干会话：消息 + 分析记录 + 待上报队列 + 提醒（闹钟/日历事件/记录）。
     * 返回删除的消息总条数。
     */
    private fun deleteConversationsFully(convs: Collection<String>): Int {
        val set = convs.toSet()
        // 提醒：取消闹钟 → 删除已落库日历事件 → 移除提醒记录
        ReminderStore.readAll(this).filter { it.conversation in set }.forEach { r ->
            AlarmHelper.cancelReminder(this, r.dedupKey)
            if (r.calendarEventId > 0L) CalendarHelper.deleteEvent(this, r.calendarEventId)
            ReminderStore.remove(this, r.dedupKey)
        }
        PendingQueue.removeByConversations(this, set)
        convs.forEach { AnalysisStore.deleteByConversation(this, it) }
        var total = 0
        convs.forEach { total += MessageStore.deleteConversation(this, it) }
        return total
    }

    /** 左滑会话头：确认后删除整个会话；取消则把行还原。 */
    private fun confirmSwipeConversation(position: Int, conv: String) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.confirm_delete_conversation, conv))
            .setNegativeButton(R.string.common_cancel) { _, _ ->
                adapter.notifyItemChanged(position)
            }
            .setPositiveButton(R.string.common_delete) { _, _ ->
                val n = deleteConversationsFully(listOf(conv))
                refreshMessages()
                Toast.makeText(this, getString(R.string.delete_done, n), Toast.LENGTH_SHORT).show()
            }
            .setOnCancelListener { adapter.notifyItemChanged(position) }
            .show()
    }

    /** 左滑日期头：确认后删除该会话某一天的消息；取消则把行还原。 */
    private fun confirmSwipeDate(position: Int, conv: String, dayKey: String, label: String, count: Int) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.confirm_delete_date, "$conv · $label", count))
            .setNegativeButton(R.string.common_cancel) { _, _ ->
                adapter.notifyItemChanged(position)
            }
            .setPositiveButton(R.string.common_delete) { _, _ ->
                PendingQueue.removeByConversationDay(this, conv, dayKey)
                val n = MessageStore.deleteDate(this, conv, dayKey)
                refreshMessages()
                Toast.makeText(this, getString(R.string.delete_done, n), Toast.LENGTH_SHORT).show()
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
                is FeedItem.Header -> confirmSwipeConversation(position, item.conversation)
                is FeedItem.DateHeader -> {
                    // key = "会话名|dayKey"
                    val conv = item.key.substringBefore('|')
                    val dayKey = item.key.substringAfter('|')
                    confirmSwipeDate(position, conv, dayKey, item.label, item.count)
                }
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

    private fun refreshPermissionStatus() {
        val granted = isListenerEnabled()
        tvPermissionStatus.text = getString(
            if (granted) R.string.permission_granted
            else R.string.permission_missing
        )
        // 状态着色：已授予绿 / 未授予红
        tvPermissionStatus.setTextColor(
            getColor(if (granted) R.color.status_ok else R.color.status_error)
        )
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
    private fun refreshMessages() {
        val messages = MessageStore.readRecent(this, 500)
        val collapsed = CollapseStore.load(this)
        val collapsedDates = CollapseStore.loadDates(this)
        val latestCases = AnalysisStore.latestByConversation(this)
        // 有提醒的会话集合：首页会话头显示 📅
        val reminderConvs = ReminderStore.readAll(this)
            .map { it.conversation }.toSet()
        val query = searchQuery.trim().lowercase()

        val dayKeyFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val currentYear = SimpleDateFormat("yyyy", Locale.getDefault()).format(Date())
        val labelSameYear = SimpleDateFormat("M月d日", Locale.getDefault())
        val labelCrossYear = SimpleDateFormat("yyyy年M月d日", Locale.getDefault())

        var groups = messages.groupBy { it.conversation }
            .map { (conv, list) -> conv to list.sortedBy { it.timestamp } }
            .sortedByDescending { (_, list) -> list.last().timestamp }

        // 搜索过滤：会话名 / 发送者 / 消息文本任一命中即保留
        if (query.isNotEmpty()) {
            groups = groups.filter { (conv, list) ->
                conv.lowercase().contains(query) || list.any {
                    it.sender.lowercase().contains(query) ||
                        it.text.lowercase().contains(query)
                }
            }
        }

        // 汇总分区：名单（去掉「全不关注」哨兵）非空时才分区
        val watched = WatchlistStore.getWatched(this)
        watched.remove(WatchlistStore.SENTINEL_NONE)
        val sections = mutableListOf<Pair<String?, List<Pair<String, List<ChatMessage>>>>>()
        if (watched.isEmpty()) {
            sections.add(null to groups)
        } else {
            val star = groups.filter { it.first in watched }
            val others = groups.filter { it.first !in watched }
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
            for ((conv, list) in sectionGroups) {
                // 搜索期间会话强制展开，保证命中内容可见
                val isCollapsed = query.isEmpty() && conv in collapsed
                val case = latestCases[conv]
                items += FeedItem.Header(
                    conversation = conv,
                    isGroup = list.last().isGroup,
                    count = list.size,
                    preview = "${list.last().sender}: ${list.last().text}",
                    collapsed = isCollapsed,
                    analysis = case,
                    hasReminder = conv in reminderConvs
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
                val convNameHit = query.isNotEmpty() && conv.lowercase().contains(query)
                val byDate = list.groupBy {
                    if (it.timestamp > 0) dayKeyFormat.format(Date(it.timestamp)) else DAY_KEY_UNKNOWN
                }
                for ((dayKey, dayMessages) in byDate) {
                    val dateKey = "$conv|$dayKey"
                    val dateHit = query.isNotEmpty() && dayMessages.any {
                        it.sender.lowercase().contains(query) ||
                            it.text.lowercase().contains(query)
                    }
                    // 搜索时含命中消息（或会话名命中）的日期组强制展开；其余尊重折叠态
                    val dateCollapsed = dateKey in collapsedDates &&
                        !(query.isNotEmpty() && (dateHit || convNameHit))
                    val label = if (dayKey == DAY_KEY_UNKNOWN) {
                        getString(R.string.date_unknown_label)
                    } else if (dayKey.startsWith(currentYear)) {
                        labelSameYear.format(Date(dayMessages.first().timestamp))
                    } else {
                        labelCrossYear.format(Date(dayMessages.first().timestamp))
                    }
                    items += FeedItem.DateHeader(
                        key = dateKey,
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
        adapter.submit(items)

        if (groups.isEmpty()) {
            tvEmpty.text = getString(
                if (query.isNotEmpty()) R.string.search_no_result else R.string.empty_hint
            )
            tvEmpty.visibility = View.VISIBLE
        } else {
            tvEmpty.visibility = View.GONE
        }
    }

    /** 列表数据项：分区标题 / 会话头（带可选分析角标） / 日期组头 / 消息（带可选分析结果与高亮） */
    private sealed class FeedItem {
        data class Section(val title: String) : FeedItem()

        data class Header(
            val conversation: String,
            val isGroup: Boolean,
            val count: Int,
            val preview: String,
            val collapsed: Boolean,
            val analysis: AnalysisCaseRecord?,
            val hasReminder: Boolean
        ) : FeedItem()

        data class DateHeader(
            val key: String,
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
        private val onToggle: (String) -> Unit,
        private val onOpen: (String) -> Unit,
        private val onToggleDate: (String) -> Unit,
        private val onToggleWatch: (String) -> Unit,
        private val onHeaderLongPress: (String) -> Unit,
        private val onToggleSelection: (String) -> Unit,
        private val onOpenReminders: (String) -> Unit
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        companion object {
            const val TYPE_HEADER = 0
            const val TYPE_MESSAGE = 1
            const val TYPE_SECTION = 2
            const val TYPE_DATE = 3
        }

        private val items = mutableListOf<FeedItem>()
        private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())

        /** 批量选择态：由 MainActivity 在 ActionMode 开关时同步 */
        var selectionActive: Boolean = false
        var selectedConversations: Set<String> = emptySet()

        /** 供 SwipeDeleteCallback 按位置取原始 item。 */
        fun feedItemAt(position: Int): FeedItem = items[position]

        fun submit(newItems: List<FeedItem>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
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
            bindTag(holder.tvTag, header.isGroup)
            holder.tvConversation.text = header.conversation
            holder.tvCount.text = ctx.getString(R.string.msg_count, header.count)
            holder.tvPreview.text = header.preview
            holder.tvArrow.text = if (header.collapsed) "▶" else "▼"

            // 批量选择视觉：勾选符 + 会话名着色
            val selected = selectionActive && header.conversation in selectedConversations
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
                holder.tvReminderBadge.setOnClickListener { onOpenReminders(header.conversation) }
            } else {
                holder.tvReminderBadge.visibility = View.GONE
            }

            // 热区：普通态文字区进详情页 / 长按进多选；选择态点击=勾选
            holder.itemView.setOnClickListener { onOpen(header.conversation) }
            holder.itemView.setOnLongClickListener {
                onHeaderLongPress(header.conversation)
                true
            }
            holder.tvArrow.setOnClickListener { onToggle(header.conversation) }

            // 快捷入口：关注（切换，文案/颜色反映状态）。选择态屏蔽 chips 操作
            val watchedNow = WatchlistStore.isWatched(ctx, header.conversation)
            holder.btnQuickWatch.text = ctx.getString(
                if (watchedNow) R.string.quick_watched else R.string.quick_watch
            )
            holder.btnQuickWatch.setTextColor(
                ctx.getColor(if (watchedNow) R.color.brand_green else R.color.text_secondary)
            )
            holder.btnQuickWatch.setOnClickListener { onToggleWatch(header.conversation) }

            // 提示词注入：已设自定义提示词则绿色，点击进编辑页
            val promptSet = PromptStore.hasPrompt(ctx, header.conversation)
            holder.btnQuickPrompt.text = ctx.getString(R.string.quick_prompt)
            holder.btnQuickPrompt.setTextColor(
                ctx.getColor(if (promptSet) R.color.brand_green else R.color.text_secondary)
            )
            holder.btnQuickPrompt.setOnClickListener {
                if (!selectionActive) PromptEditActivity.start(ctx, header.conversation)
            }

            // 立即分析本会话：入队 WorkManager，chip 下方进度条反映排队/运行状态
            holder.btnQuickAnalyze.text = ctx.getString(R.string.quick_analyze)
            holder.btnQuickAnalyze.setTextColor(ctx.getColor(R.color.text_secondary))
            holder.btnQuickAnalyze.setOnClickListener {
                if (selectionActive) return@setOnClickListener
                AnalysisScheduler.enqueueAnalysisNow(ctx, header.conversation)
                Toast.makeText(ctx, R.string.analysis_enqueued_conv, Toast.LENGTH_SHORT).show()
            }
            bindAnalyzeProgress(holder, header.conversation)

            // 分析结果：打开只含本会话的结果列表
            holder.btnQuickResult.text = ctx.getString(R.string.quick_result)
            holder.btnQuickResult.setTextColor(ctx.getColor(R.color.text_secondary))
            holder.btnQuickResult.setOnClickListener {
                if (!selectionActive) AnalysisListActivity.start(ctx, header.conversation)
            }
        }

        /**
         * 分析 chip 进度条：按会话 tag 观察 WorkManager。
         * 存在 ENQUEUED/RUNNING 的按需分析任务即显示进度条；
         * 复用的 ViewHolder 先摘掉旧 observer，回收时统一清理。
         */
        private fun bindAnalyzeProgress(holder: HeaderVH, conversation: String) {
            val ctx = holder.itemView.context
            val wm = WorkManager.getInstance(ctx)
            holder.progressObserver?.let { old ->
                holder.progressTag?.let { tag ->
                    runCatching { wm.getWorkInfosByTagLiveData(tag).removeObserver(old) }
                }
            }
            val tag = AnalysisScheduler.convTag(conversation)
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
            holder.itemView.setOnClickListener { onToggleDate(header.key) }
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

/** 首页折叠状态持久化：SharedPreferences 存「已折叠会话名集合」与「已折叠日期组 key 集合」。
 *  日期组 key = "会话名|yyyy-MM-dd"；两层折叠态互相独立，各自持久化。 */
object CollapseStore {
    private const val PREFS_NAME = "collapse_state"
    private const val KEY_COLLAPSED = "collapsed_conversations"
    private const val KEY_COLLAPSED_DATES = "collapsed_dates"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_COLLAPSED, emptySet()).orEmpty()

    fun setCollapsed(context: Context, conversation: String, collapsed: Boolean) {
        val set = load(context).toMutableSet()
        if (collapsed) set.add(conversation) else set.remove(conversation)
        prefs(context).edit().putStringSet(KEY_COLLAPSED, set).apply()
    }

    /** collapsed=true 收拢全部给定会话；false 清空集合（全部展开） */
    fun setAll(context: Context, conversations: Collection<String>, collapsed: Boolean) {
        val set = if (collapsed) conversations.toSet() else emptySet()
        prefs(context).edit().putStringSet(KEY_COLLAPSED, set).apply()
    }

    fun loadDates(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_COLLAPSED_DATES, emptySet()).orEmpty()

    /** 日期组折叠/展开，key = "会话名|yyyy-MM-dd" */
    fun setDateCollapsed(context: Context, dateKey: String, collapsed: Boolean) {
        val set = loadDates(context).toMutableSet()
        if (collapsed) set.add(dateKey) else set.remove(dateKey)
        prefs(context).edit().putStringSet(KEY_COLLAPSED_DATES, set).apply()
    }

    /** 清空全部日期组折叠态（「全部展开」时一并复位） */
    fun clearDates(context: Context) {
        prefs(context).edit().putStringSet(KEY_COLLAPSED_DATES, emptySet()).apply()
    }
}
