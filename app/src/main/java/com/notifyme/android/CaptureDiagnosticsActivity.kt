// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 采集诊断页（W4）：回答「消息为什么没被抓到 / 现在链路通不通」。
 *
 * 四段：
 *  1. 权限与服务状态：通知监听授权 + 服务是否真的连着、无障碍、悬浮窗；
 *  2. 本次启动以来的通知漏斗（[CaptureStats]）：posted → 各级丢弃 → stored；
 *  3. 本地存储：messages.jsonl 行数 / 损坏行 / 体积；
 *  4. 各来源累计统计：按 App 的全部历史条数、会话数、自发消息数。
 *
 * 纯只读诊断，不提供任何清理/修改动作。
 */
class CaptureDiagnosticsActivity : Activity() {

    private companion object {
        /**
         * 全量统计的扫描上限：历史无限增长时不能一次性读爆内存；
         * 超出部分只影响统计口径（页面上会明确提示），不影响任何数据。
         */
        const val ALLTIME_SCAN_LIMIT = 200_000
    }

    private lateinit var container: LinearLayout
    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())

    /** 异步统计的世代号：刷新后旧线程的结果直接丢弃，避免重复渲染。 */
    private var allTimeToken = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_diagnostics)

        container = findViewById(R.id.container)
        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnRefresh).setOnClickListener { render() }
        render()
    }

    override fun onResume() {
        super.onResume()
        if (::container.isInitialized) render()
    }

    private fun render() {
        container.removeAllViews()
        renderServiceStatus()
        renderSessionFunnel()
        renderStorage()
        renderHints()
        // 全量统计在后台线程算完再填充，放在最后：同步渲染完再追加，顺序稳定且不阻塞主线程
        renderAllTimeByApp()
    }

    // ---------------- 1) 服务与权限 ----------------

    private fun renderServiceStatus() {
        section(getString(R.string.diag_section_services))

        val listenerAuthorized = Settings.Secure.getString(
            contentResolver, "enabled_notification_listeners"
        ).orEmpty().contains(packageName)

        statusRow(getString(R.string.diag_listener_auth), listenerAuthorized)
        statusRow(getString(R.string.diag_listener_connected), NotifyMeListener.connected)
        statusRow(getString(R.string.diag_a11y), A11yExtractService.isEnabled(this))
        statusRow(getString(R.string.diag_overlay), OverlayManager.canDrawOverlays(this))
    }

    // ---------------- 2) 本次启动漏斗 ----------------

    private fun renderSessionFunnel() {
        section(getString(R.string.diag_section_session))
        valueRow(getString(R.string.diag_posted), CaptureStats.posted.toString())
        valueRow(getString(R.string.diag_stored), CaptureStats.stored.toString())
        val last = CaptureStats.lastStoredAt
        valueRow(
            getString(R.string.diag_last_stored),
            if (last > 0) timeFormat.format(Date(last)) else getString(R.string.diag_value_na)
        )

        section(getString(R.string.diag_section_dropped))
        valueRow(getString(R.string.diag_drop_blocked), CaptureStats.blocked.toString())
        valueRow(getString(R.string.diag_drop_disabled), CaptureStats.disabled.toString())
        valueRow(getString(R.string.diag_drop_ongoing), CaptureStats.ongoing.toString())
        valueRow(getString(R.string.diag_drop_empty), CaptureStats.empty.toString())
        valueRow(getString(R.string.diag_drop_duplicate), CaptureStats.duplicate.toString())
        valueRow(getString(R.string.diag_drop_parse), CaptureStats.parseFailed.toString())

        val byPkg = CaptureStats.storedByPkg()
        if (byPkg.isNotEmpty()) {
            section(getString(R.string.diag_section_session_pkg))
            byPkg.forEach { (pkg, n) ->
                valueRow(appLabelOf(pkg), getString(R.string.msg_count, n))
            }
        }
    }

    // ---------------- 3) 存储 ----------------

    private fun renderStorage() {
        section(getString(R.string.diag_section_storage))
        val stats = MessageStore.storageStats(this)
        valueRow(getString(R.string.diag_file_size), formatBytes(stats.sizeBytes))
        valueRow(getString(R.string.diag_total_lines), stats.totalLines.toString())
        valueRow(getString(R.string.diag_valid_lines), stats.validLines.toString())
        valueRow(
            getString(R.string.diag_corrupt_lines),
            stats.corruptLines.toString(),
            ok = if (stats.corruptLines == 0) true else null
        )
    }

    // ---------------- 4) 各 App 累计 ----------------

    /**
     * 全部历史按 App 统计。解析整个 JSONL 可能上万行，**必须离开主线程**：
     * 旧实现在主线程同步 readRecent(1_000_000)，历史变大后会直接 ANR。
     */
    private fun renderAllTimeByApp() {
        section(getString(R.string.diag_section_alltime))
        val loading = hint(getString(R.string.diag_alltime_loading))
        val token = ++allTimeToken
        Thread {
            val totalLines = MessageStore.storageStats(this).totalLines
            val all = MessageStore.readRecent(this, ALLTIME_SCAN_LIMIT)
            val summary = AllTimeSummary.of(all)
            runOnUiThread {
                if (token != allTimeToken || isFinishing || isDestroyed) return@runOnUiThread
                container.removeView(loading)
                renderAllTimeSummary(summary, totalLines)
            }
        }.start()
    }

    /** 单遍聚合结果：避免对同一批消息反复 map/distinct 产生多份临时集合。 */
    private class AllTimeSummary(
        val total: Int,
        val conversations: Int,
        val selfMessages: Int,
        val byPkg: List<PkgStat>
    ) {
        class PkgStat(
            val pkg: String,
            val messages: Int,
            val conversations: Int,
            val groups: Int,
            val latest: Long
        )

        companion object {
            fun of(messages: List<ChatMessage>): AllTimeSummary {
                val allConvs = HashSet<ConvKey>()
                val countByPkg = HashMap<String, Int>()
                val convsByPkg = HashMap<String, HashSet<ConvKey>>()
                val groupConvsByPkg = HashMap<String, HashSet<ConvKey>>()
                val latestByPkg = HashMap<String, Long>()
                var selfMessages = 0
                messages.forEach { msg ->
                    allConvs.add(msg.convKey)
                    if (msg.isSelf) selfMessages += 1
                    countByPkg[msg.pkg] = (countByPkg[msg.pkg] ?: 0) + 1
                    convsByPkg.getOrPut(msg.pkg) { HashSet() }.add(msg.convKey)
                    if (msg.isGroup) {
                        groupConvsByPkg.getOrPut(msg.pkg) { HashSet() }.add(msg.convKey)
                    }
                    val cur = latestByPkg[msg.pkg] ?: 0L
                    if (msg.timestamp > cur) latestByPkg[msg.pkg] = msg.timestamp
                }
                val stats = countByPkg.keys.sorted().map { pkg ->
                    PkgStat(
                        pkg = pkg,
                        messages = countByPkg.getValue(pkg),
                        conversations = convsByPkg[pkg]?.size ?: 0,
                        groups = groupConvsByPkg[pkg]?.size ?: 0,
                        latest = latestByPkg[pkg] ?: 0L
                    )
                }
                return AllTimeSummary(messages.size, allConvs.size, selfMessages, stats)
            }
        }
    }

    private fun renderAllTimeSummary(s: AllTimeSummary, totalLines: Int) {
        if (s.total == 0) {
            hint(getString(R.string.diag_no_data))
            return
        }
        valueRow(getString(R.string.diag_conversations), s.conversations.toString())
        valueRow(getString(R.string.diag_self_messages), s.selfMessages.toString())
        if (totalLines > s.total) {
            hint(getString(R.string.diag_alltime_capped, ALLTIME_SCAN_LIMIT, totalLines))
        }
        s.byPkg.forEach { st ->
            section(appLabelOf(st.pkg))
            valueRow(getString(R.string.diag_pkg_total), st.messages.toString())
            valueRow(getString(R.string.diag_pkg_conversations), st.conversations.toString())
            valueRow(getString(R.string.diag_pkg_groups), st.groups.toString())
            valueRow(
                getString(R.string.diag_pkg_latest),
                if (st.latest > 0) {
                    timeFormat.format(Date(st.latest))
                } else {
                    getString(R.string.diag_value_na)
                }
            )
        }
    }

    private fun renderHints() {
        section(getString(R.string.diag_section_hints))
        hint(getString(R.string.diag_hint_wechat_notification))
        hint(getString(R.string.diag_hint_foreground))
        hint(getString(R.string.diag_hint_dnd))
        hint(getString(R.string.diag_hint_restart))
    }

    // ================= 视图构造 =================

    private fun section(title: String) {
        val tv = TextView(this).apply {
            text = title
            setTextColor(getColor(R.color.text_primary))
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = dp(14)
            lp.bottomMargin = dp(6)
            layoutParams = lp
        }
        container.addView(tv)
    }

    private fun statusRow(label: String, ok: Boolean) {
        // 两态必须是不同字符：旧实现两个分支都返回 "● "，状态点完全没有区分度
        val dot = if (ok) "● " else "○ "
        valueRow(label, if (ok) getString(R.string.diag_status_on) else getString(R.string.diag_status_off),
            dotPrefix = dot, ok = ok)
    }

    private fun valueRow(
        label: String,
        value: String,
        ok: Boolean? = null,
        dotPrefix: String? = null
    ) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = dp(3)
            lp.bottomMargin = dp(3)
            layoutParams = lp
        }

        val tvLabel = TextView(this).apply {
            text = if (dotPrefix != null) "$dotPrefix$label" else label
            textSize = 13f
            setTextColor(
                when (ok) {
                    true -> getColor(R.color.brand_green)
                    false -> getColor(R.color.diag_red)
                    null -> getColor(R.color.text_secondary)
                }
            )
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val tvValue = TextView(this).apply {
            text = value
            textSize = 13f
            setTextColor(getColor(R.color.text_primary))
            gravity = Gravity.END
        }

        row.addView(tvLabel)
        row.addView(tvValue)
        container.addView(row)
    }

    /** 提示行；返回视图本身，便于异步填充完成后移除占位文案。 */
    private fun hint(text: String): TextView {
        val tv = TextView(this).apply {
            this.text = "· $text"
            textSize = 12f
            setTextColor(getColor(R.color.text_secondary))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = dp(2)
            lp.bottomMargin = dp(2)
            layoutParams = lp
        }
        container.addView(tv)
        return tv
    }

    private fun appLabelOf(pkg: String): String {
        val seen = AppSourceStore.observed(this).firstOrNull { it.pkg == pkg }?.label
        if (!seen.isNullOrEmpty()) return seen
        return runCatching {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(pkg, 0)
            ).toString()
        }.getOrDefault(pkg)
    }

    private fun formatBytes(bytes: Long): String =
        if (bytes < 1024) "$bytes B"
        else if (bytes < 1024 * 1024) "%.1f KB".format(bytes / 1024.0)
        else "%.2f MB".format(bytes / (1024.0 * 1024.0))

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
