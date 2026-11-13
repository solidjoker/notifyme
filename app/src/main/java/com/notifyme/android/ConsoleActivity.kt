// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast

/**
 * 移动端控制台首页（原巨型配置中心拆分后的入口页）：
 *  - 五张功能入口卡：采集 / 分析 / 日历提醒 / 隐私脱敏 / 悬浮通知，点击进入对应子设置页；
 *  - 底部内嵌「保活状态」卡：前台服务心跳状态 + 电池优化白名单按钮
 *    （自旧 MainActivity 保活卡原样回归，运行判定读 SharedPreferences 心跳）。
 * 各子页：CaptureActivity / AnalysisSettingsActivity / ReminderSettingsActivity /
 * PrivacySettingsActivity / OverlaySettingsActivity，行为与拆分前完全一致。
 */
class ConsoleActivity : Activity() {

    companion object {
        private const val REQ_POST_NOTIFICATIONS = 42

        // 天组键统一走 DateKeyCore：以前这里自建 SimpleDateFormat("yyyy-MM-dd")，
        // 它在非公历 Locale（佛历 / 和历）下产出的 key 与主页 java.time 算的对不上，
        // 折叠态复合键两边各自成立、互相看不见。
    }

    // 保活状态相关视图
    private lateinit var tvKeepAliveStatus: TextView
    // M20 定时分析面板
    private lateinit var tvScheduleGlobal: TextView
    private lateinit var tvScheduleWarn: TextView
    private lateinit var llScheduleConvs: LinearLayout
    private lateinit var tvScheduleTags: TextView
    private lateinit var btnBatteryWhitelist: Button

    // 版本信息卡片
    private lateinit var tvVersionDetail: TextView

    // M21 后台分析实时进度行
    private lateinit var tvRunProgress: TextView
    private lateinit var progressRun: ProgressBar
    private val runProgressListener = AnalysisProgressBus.Listener { state ->
        renderRunProgress(state)
    }
    /** 远端/加载阶段总线不高频发布，每秒补一帧让「已用 N秒」继续走 */
    private val runProgressTick = object : Runnable {
        override fun run() {
            AnalysisProgressBus.state?.let { renderRunProgress(it) }
            tvRunProgress.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_console)

        // 标题栏返回
        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }

        // 五张功能入口卡
        findViewById<View>(R.id.cardCapture).setOnClickListener {
            startActivity(Intent(this, CaptureActivity::class.java))
        }
        findViewById<View>(R.id.cardAnalysis).setOnClickListener {
            startActivity(Intent(this, AnalysisSettingsActivity::class.java))
        }
        findViewById<View>(R.id.cardReminder).setOnClickListener {
            startActivity(Intent(this, ReminderSettingsActivity::class.java))
        }
        findViewById<View>(R.id.cardPrivacy).setOnClickListener {
            startActivity(Intent(this, PrivacySettingsActivity::class.java))
        }
        findViewById<View>(R.id.cardOverlay).setOnClickListener {
            startActivity(Intent(this, OverlaySettingsActivity::class.java))
        }

        setupKeepAliveSection()
        setupScheduleSection()
        setupVersionSection()

        // 快捷按钮：全部折叠 / 全部展开（M16：与主页标题栏同口径——会话级 + 天级一起写）
        findViewById<Button>(R.id.btnCollapseAll).setOnClickListener {
            CollapseStore.setAll(this, collapseTargets(), collapsed = true)
            Toast.makeText(this, R.string.quick_collapse_done, Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btnExpandAll).setOnClickListener {
            CollapseStore.setAll(this, collapseTargets(), collapsed = false)
            Toast.makeText(this, R.string.quick_expand_done, Toast.LENGTH_SHORT).show()
        }
        // 快捷按钮：回到主页（全部折叠/展开已在主页标题栏，此处仅提供导航入口）
        findViewById<Button>(R.id.btnGoHome).setOnClickListener {
            finish()
        }

    }

    /**
     * 版本信息卡：版本 / 渠道 / 构建 / 应用 ID / 架构 / 系统 / 设备，外加「复制版本信息」。
     *
     * 口径全部来自 [VersionInfoCore]（纯函数，有单测），这里只负责把 BuildConfig 与
     * Build.* 采集出来交给它渲染 —— 用户报障时贴过来的那一段就是 [VersionInfoCore.copyText]。
     */
    private fun setupVersionSection() {
        tvVersionDetail = findViewById(R.id.tvVersionDetail)
        val entries = VersionInfoCore.entries(
            versionName = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE,
            flavor = BuildConfig.FLAVOR,
            buildType = BuildConfig.BUILD_TYPE,
            applicationId = packageName,
            abis = Build.SUPPORTED_ABIS?.toList() ?: emptyList(),
            androidRelease = Build.VERSION.RELEASE ?: "",
            deviceModel = Build.MODEL ?: ""
        )
        tvVersionDetail.text = VersionInfoCore.detailText(entries)

        findViewById<Button>(R.id.btnCopyVersion).setOnClickListener {
            val text = VersionInfoCore.copyText(BuildConfig.VERSION_NAME, entries)
            val cm = getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager
            if (cm == null) {
                Toast.makeText(this, R.string.version_copy_done, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            cm.setPrimaryClip(ClipData.newPlainText(getString(R.string.version_copy_label), text))
            Toast.makeText(this, R.string.version_copy_done, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 折叠作用域：首页窗口（[FeedWindowCore]，每会话各留一窗）里出现的每个 (会话, 天) 组合，
     * 与主页 buildFeed 的 feedDayPairs 同口径（同一份数据、同一套复合键），两处按钮行为完全一致。
     * 别退回 readRecent(500)：那是跨会话窗口，静默会话的天组在这里会折叠不到。
     */
    private fun collapseTargets(): List<Pair<ConvKey, String>> =
        MessageStore.feedGroups(this).toMap()
            .flatMap { (k, l) ->
                l.map { DateKeyCore.dayKeyOf(it.timestamp) }.distinct().map { k to it }
            }

    /** 定时分析面板初始化：两个按钮——立即分析一轮、跳分析设置（周期与开关都在那里）。 */
    private fun setupScheduleSection() {
        tvScheduleGlobal = findViewById(R.id.tvScheduleGlobal)
        tvScheduleWarn = findViewById(R.id.tvScheduleWarn)
        llScheduleConvs = findViewById(R.id.llScheduleConvs)
        tvScheduleTags = findViewById(R.id.tvScheduleTags)
        tvRunProgress = findViewById(R.id.tvRunProgress)
        progressRun = findViewById(R.id.progressRun)
        findViewById<Button>(R.id.btnAnalyzeNow).setOnClickListener {
            AnalysisScheduler.enqueueAnalysisNow(this)
            Toast.makeText(this, R.string.console_schedule_enqueued, Toast.LENGTH_SHORT).show()
            // 排一轮会顺带自愈补排，稍后刷新把「失联」提示撤掉
            tvScheduleGlobal.postDelayed({ refreshAnalysisSchedule() }, 2500)
        }
        findViewById<Button>(R.id.btnScheduleSettings).setOnClickListener {
            startActivity(Intent(this, AnalysisSettingsActivity::class.java))
        }
    }

    /**
     * 刷新定时分析面板。标签表与排期表都是磁盘读，放后台线程，回到 UI 再渲染
     * （控制台首页一进来就卡主线程，体验上等同于「点了没反应」）。
     */
    private fun refreshAnalysisSchedule() {
        Thread {
            val config = AnalysisConfig(this)
            val now = System.currentTimeMillis()
            val status = AnalysisScheduleSummaryCore.evaluate(
                enabled = config.enabled,
                intervalMinutes = config.intervalMinutes,
                lastRunAt = config.lastAnalysisTime,
                now = now,
            )
            val schedules = ConvAnalysisScheduleStore.all(this)
            val tagMap = ConvTagStore.readRaw(this)
            val gated = tagMap.filterValues { ConvTagCore.stopsAnalyzing(ConvTag.fromCode(it)) }.keys
            val rows = AnalysisScheduleSummaryCore.convRows(schedules, gated, status.lastRunAt, now)
            val counts = ConvTagStore.countByTag(this)
            runOnUiThread {
                if (!isFinishing) renderSchedulePanel(status, rows, counts)
            }
        }.start()
    }

    private fun renderSchedulePanel(
        status: AnalysisScheduleSummaryCore.ScheduleStatus,
        rows: List<AnalysisScheduleSummaryCore.ConvRow>,
        counts: Map<ConvTag, Int>,
    ) {
        val last = LocaleTextCore.hourMinute(status.lastRunAt)
        tvScheduleGlobal.text = when {
            status.health == AnalysisScheduleSummaryCore.Health.DISABLED ->
                getString(R.string.console_schedule_disabled)
            status.health == AnalysisScheduleSummaryCore.Health.GLOBAL_OFF ->
                getString(R.string.console_schedule_global_off)
            status.health == AnalysisScheduleSummaryCore.Health.NEVER_RUN ->
                templateText(R.string.console_schedule_never, status.intervalMinutes)
            status.nextRunAt <= 0L ->
                templateText(R.string.console_schedule_last, status.intervalMinutes, last)
            status.nextRunAt <= System.currentTimeMillis() ->
                templateText(R.string.console_schedule_running_soon, status.intervalMinutes, last)
            else ->
                templateText(
                    R.string.console_schedule_running,
                    status.intervalMinutes,
                    last,
                    LocaleTextCore.hourMinute(status.nextRunAt),
                )
        }
        if (status.health == AnalysisScheduleSummaryCore.Health.STALE) {
            tvScheduleWarn.visibility = View.VISIBLE
            tvScheduleWarn.text = templateText(
                R.string.console_schedule_stale,
                status.minutesSinceLast,
                status.staleThresholdMs / 60_000L,
            )
        } else {
            tvScheduleWarn.visibility = View.GONE
        }

        llScheduleConvs.removeAllViews()
        if (rows.isEmpty()) {
            llScheduleConvs.addView(scheduleLine(getString(R.string.console_schedule_convs_none), R.color.text_secondary))
        } else {
            llScheduleConvs.addView(
                scheduleLine(templateText(R.string.console_schedule_convs_title, rows.size), R.color.text_primary, bold = true)
            )
            rows.take(5).forEach { row ->
                val name = ConvKey.parse(row.convKeyId).conversation
                val text = when {
                    row.gatedOut -> templateText(R.string.console_schedule_conv_gated, name, row.intervalMinutes)
                    row.nextRunAt <= 0L || row.nextRunAt <= System.currentTimeMillis() ->
                        templateText(R.string.console_schedule_conv_row_soon, name, row.intervalMinutes)
                    else -> templateText(
                        R.string.console_schedule_conv_row,
                        name,
                        row.intervalMinutes,
                        LocaleTextCore.hourMinute(row.nextRunAt),
                    )
                }
                llScheduleConvs.addView(
                    scheduleLine(text, if (row.gatedOut) R.color.status_gray else R.color.text_secondary)
                )
            }
            if (rows.size > 5) {
                llScheduleConvs.addView(
                    scheduleLine(templateText(R.string.console_schedule_convs_more, rows.size - 5), R.color.text_secondary)
                )
            }
        }

        if (counts.isEmpty()) {
            tvScheduleTags.text = getString(R.string.console_schedule_tags_none)
        } else {
            val parts = ConvTagCore.PICKER_ORDER.filter { (counts[it] ?: 0) > 0 }
                .joinToString(" · ") { ConvTagPicker.badgeText(this, it) + " " + counts[it] }
            val stopped = (counts[ConvTag.DONE] ?: 0) + (counts[ConvTag.NOISE] ?: 0)
            val body = if (stopped > 0) {
                parts + " · " + templateText(R.string.console_schedule_stop_hint, stopped)
            } else parts
            tvScheduleTags.text = getString(R.string.console_schedule_tags, body)
        }
    }

    private fun scheduleLine(text: String, colorRes: Int, bold: Boolean = false): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 12f
            setTextColor(getColor(colorRes))
            if (bold) setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = (4f * resources.displayMetrics.density).toInt() }
        }
    override fun onResume() {
        super.onResume()
        refreshKeepAliveStatus()
        refreshAnalysisSchedule()
        AnalysisProgressBus.addListener(runProgressListener)
        tvRunProgress.removeCallbacks(runProgressTick)
        tvRunProgress.post(runProgressTick)
    }

    override fun onPause() {
        tvKeepAliveStatus.removeCallbacks(refreshKeepAliveOnce)
        tvRunProgress.removeCallbacks(runProgressTick)
        AnalysisProgressBus.removeListener(runProgressListener)
        super.onPause()
    }

    /** M21：把 Worker 进度渲染到定时卡顶部的实时进度行（不区分会话，整轮都显示）。 */
    private fun renderRunProgress(state: AnalysisRunState?) {
        if (state == null) {
            tvRunProgress.visibility = View.GONE
            progressRun.visibility = View.GONE
            return
        }
        tvRunProgress.visibility = View.VISIBLE
        tvRunProgress.text = getString(R.string.analysis_run_progress, AnalysisProgressUi.text(this, state))
        progressRun.visibility = View.VISIBLE
        val percent = state.percent()
        if (percent == null) {
            progressRun.isIndeterminate = true
        } else {
            progressRun.isIndeterminate = false
            progressRun.progress = percent
        }
    }

    /** 保活区初始化：电池优化白名单按钮 + 通知运行时权限请求。 */
    @SuppressLint("BatteryLife") // 直跳白名单申请：应用不通过 Play 分发，为保活可靠性刻意保留
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

        // Android 13+ 前台服务通知需要运行时权限，否则常驻通知不显示（判定口径在 PermissionGateCore）
        if (PermissionGateCore.needsPostNotifications(
                Build.VERSION.SDK_INT,
                checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
            )
        ) {
            requestPermissions(
                PermissionGateCore.postNotificationPermissions(),
                REQ_POST_NOTIFICATIONS
            )
        }
    }

    /** 刷新保活状态显示：前台服务是否运行 + 电池优化是否已忽略。
     *  运行判定读 SharedPreferences 心跳（KeepAliveService.isAlive），
     *  不依赖进程内存标志——进程被系统重启后服务仍在时也能正确显示。 */
    private fun refreshKeepAliveStatus() {
        val pm = getSystemService(PowerManager::class.java)
        val ignoringBattery = pm.isIgnoringBatteryOptimizations(packageName)

        tvKeepAliveStatus.text = getString(
            R.string.keepalive_status_line,
            getString(if (KeepAliveService.isAlive(this)) R.string.keepalive_running else R.string.keepalive_stopped),
            getString(if (ignoringBattery) R.string.battery_whitelisted else R.string.battery_not_whitelisted)
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
            R.string.keepalive_status_line,
            getString(if (KeepAliveService.isAlive(this)) R.string.keepalive_running else R.string.keepalive_stopped),
            tvKeepAliveStatus.text.toString().substringAfter("\n", "")
        )
    }
}
