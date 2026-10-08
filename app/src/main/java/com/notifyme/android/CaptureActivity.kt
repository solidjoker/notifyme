// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import androidx.work.WorkManager
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * 采集设置页（自 ConsoleActivity 拆出，行为不变）：
 *  - 微信对接状态：监听权限 + 监听服务连接状态 + 去授权 + 重点关注/来源/诊断入口；
 *  - 服务器上报：总开关 + 地址/令牌/周期 + 立即同步 + 状态；
 *  - 聊天记录提取（服务端 /admin/extract）+ 自动提取开关；
 *  - 本机无障碍直读（无需 root）。
 */
class CaptureActivity : Activity() {

    companion object {
        private const val TAG = "CaptureActivity"

        /** 测试连接用（laya）：短超时，独立 client 不影响 Worker 的 */
        private val testClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    private lateinit var syncConfig: SyncConfig

    private lateinit var tvListenerStatus: TextView
    private lateinit var switchPushEnabled: Switch
    private lateinit var etServerUrl: EditText
    private lateinit var etAuthToken: EditText
    private lateinit var spinnerInterval: Spinner
    private lateinit var tvSyncStatus: TextView

    private lateinit var btnExtractNow: Button
    private lateinit var tvExtractStatus: TextView
    private lateinit var switchAutoExtract: Switch
    private lateinit var tvAutoExtractStatus: TextView

    // 本机无障碍直读（无需 root）
    private lateinit var spinnerA11yTarget: Spinner
    private lateinit var btnA11yPermission: Button
    private lateinit var btnA11yStart: Button
    private lateinit var btnA11yStop: Button
    private lateinit var tvA11yStatus: TextView

    /** 无障碍提取目标：与 spinner 顺序一一对应的包名（仅装有 a11y 配置且已安装的源） */
    private val a11yTargetPkgs = mutableListOf<String>()
    private val a11yTargetLabels = mutableListOf<String>()

    /** 提取轮询进行中标志：期间按钮置灰、onResume 的状态查询不抢占界面 */
    @Volatile
    private var extractPolling = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_capture)

        syncConfig = SyncConfig(this)

        // 标题栏返回
        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }

        bindViews()
        setupListenerSection()
        setupSyncSection()
        setupExtractSection()
        setupA11yExtractSection()
    }

    override fun onResume() {
        super.onResume()
        refreshListenerStatus()
        refreshSyncStatus()
        refreshExtractStatus()
        refreshAutoExtractStatus()
        refreshA11yStatus()
        // 提取进行中状态由服务写 prefs，这里每秒轮询刷新
        a11yPollHandler.removeCallbacks(a11yPollRunnable)
        a11yPollHandler.postDelayed(a11yPollRunnable, 1000)
    }

    override fun onPause() {
        a11yPollHandler.removeCallbacks(a11yPollRunnable)
        super.onPause()
    }

    /** 无障碍直读状态轮询：服务在 worker 线程跑，UI 轮询 prefs 同步进度与按钮态 */
    private val a11yPollHandler = Handler(Looper.getMainLooper())

    private val a11yPollRunnable = object : Runnable {
        override fun run() {
            refreshA11yStatus()
            a11yPollHandler.postDelayed(this, 1000)
        }
    }

    private fun bindViews() {
        tvListenerStatus = findViewById(R.id.tvConsoleListenerStatus)
        switchPushEnabled = findViewById(R.id.switchPushEnabled)
        etServerUrl = findViewById(R.id.etServerUrl)
        etAuthToken = findViewById(R.id.etAuthToken)
        spinnerInterval = findViewById(R.id.spinnerInterval)
        tvSyncStatus = findViewById(R.id.tvSyncStatus)

        btnExtractNow = findViewById(R.id.btnExtractNow)
        tvExtractStatus = findViewById(R.id.tvExtractStatus)
        switchAutoExtract = findViewById(R.id.switchAutoExtract)
        tvAutoExtractStatus = findViewById(R.id.tvAutoExtractStatus)

        spinnerA11yTarget = findViewById(R.id.spinnerA11yTarget)
        btnA11yPermission = findViewById(R.id.btnA11yPermission)
        btnA11yStart = findViewById(R.id.btnA11yStart)
        btnA11yStop = findViewById(R.id.btnA11yStop)
        tvA11yStatus = findViewById(R.id.tvA11yStatus)
    }

    // ---------- 微信对接状态 ----------

    private fun setupListenerSection() {
        findViewById<Button>(R.id.btnConsoleGrant).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        findViewById<Button>(R.id.btnReopenOnboarding).setOnClickListener {
            startActivity(Intent(this, OnboardingActivity::class.java))
        }
        findViewById<Button>(R.id.btnOpenWatchlist).setOnClickListener {
            startActivity(Intent(this, WatchlistActivity::class.java))
        }
        findViewById<Button>(R.id.btnOpenSources).setOnClickListener {
            startActivity(Intent(this, SourcesActivity::class.java))
        }
        findViewById<Button>(R.id.btnOpenDiagnostics).setOnClickListener {
            startActivity(Intent(this, CaptureDiagnosticsActivity::class.java))
        }
    }

    private fun refreshListenerStatus() {
        val granted = NotificationManagerCompat.getEnabledListenerPackages(this)
            .contains(packageName)
        val connected = NotifyMeListener.connected
        tvListenerStatus.text = getString(
            if (granted) R.string.listener_granted else R.string.listener_missing
        ) + "\n" + getString(
            if (connected) R.string.listener_connected
            else R.string.listener_disconnected
        )
        // 状态着色：权限+连接双正常绿，权限缺失红，其余灰
        tvListenerStatus.setTextColor(
            getColor(
                when {
                    granted && connected -> R.color.status_ok
                    !granted -> R.color.status_error
                    else -> R.color.status_gray
                }
            )
        )
    }

    // ---------- 服务器上报 ----------

    private fun setupSyncSection() {
        val labels = resources.getStringArray(R.array.sync_interval_labels)
        spinnerInterval.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, labels
        )

        // 回填
        switchPushEnabled.isChecked = syncConfig.pushEnabled
        etServerUrl.setText(syncConfig.serverUrl)
        etAuthToken.setText(syncConfig.authToken)
        val savedIndex = SyncConfig.INTERVAL_OPTIONS.indexOf(syncConfig.intervalMinutes)
        if (savedIndex >= 0) spinnerInterval.setSelection(savedIndex)

        // 总开关即时生效：关闭后 SyncWorker 到点直接跳过，队列继续累积
        switchPushEnabled.setOnCheckedChangeListener { _, isChecked ->
            syncConfig.pushEnabled = isChecked
        }

        findViewById<Button>(R.id.btnSaveSync).setOnClickListener {
            val url = etServerUrl.text.toString().trim()
            // 安全策略：默认仅 HTTPS（明文只放行本机回环；debug 构建放行），
            // 拦截时给出明确提示，而不是等上报时抛一个莫名的 IOException
            if (NetworkPolicy.isBlocked(url, BuildConfig.DEBUG)) {
                Toast.makeText(this, R.string.sync_url_https_required, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            syncConfig.serverUrl = url
            syncConfig.authToken = etAuthToken.text.toString().trim()
            syncConfig.intervalMinutes =
                SyncConfig.INTERVAL_OPTIONS[spinnerInterval.selectedItemPosition]

            if (url.isEmpty()) {
                SyncScheduler.cancel(this)
                Toast.makeText(this, R.string.sync_disabled, Toast.LENGTH_SHORT).show()
            } else {
                SyncScheduler.schedule(this, syncConfig.intervalMinutes)
                Toast.makeText(this, R.string.sync_saved, Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<Button>(R.id.btnSyncNow).setOnClickListener {
            if (syncConfig.serverUrl.isBlank()) {
                Toast.makeText(this, R.string.sync_url_empty, Toast.LENGTH_SHORT).show()
            } else {
                val syncId = SyncScheduler.enqueueSyncNow(this)
                Toast.makeText(this, R.string.sync_enqueued, Toast.LENGTH_SHORT).show()
                // 任务结束后原地刷新状态行，不用退出重进才看到结果。
                // 本 Activity 继承原生 Activity（非 LifecycleOwner），
                // 用 observeForever + 完成后自移除。
                val live = WorkManager.getInstance(this).getWorkInfoByIdLiveData(syncId)
                val observer = object : androidx.lifecycle.Observer<androidx.work.WorkInfo?> {
                    override fun onChanged(info: androidx.work.WorkInfo?) {
                        if (info != null && info.state.isFinished) {
                            refreshSyncStatus()
                            live.removeObserver(this)
                        }
                    }
                }
                live.observeForever(observer)
            }
        }
    }

    private fun refreshSyncStatus() {
        val time = syncConfig.lastSyncTime
        tvSyncStatus.text = if (time <= 0L) {
            getString(R.string.sync_status_none)
        } else {
            val timeText = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
                .format(Date(time))
            "$timeText  ${syncConfig.lastSyncResult}"
        }
        tintStatus(tvSyncStatus, syncConfig.lastSyncResult)
    }

    // ---------- 聊天记录提取（服务端 /admin/extract） ----------

    /**
     * 提取接口地址：serverUrl 去掉末尾 /weixin 再拼 /admin/<path>；
     * 没有 /weixin 后缀时直接在原地址后拼（健壮处理）。
     */
    private fun extractEndpoint(path: String): String {
        var base = syncConfig.serverUrl.trim().trimEnd('/')
        if (base.endsWith("/weixin")) base = base.dropLast("/weixin".length)
        return "$base/admin/$path"
    }

    /** 提取请求统一带 X-Token 头（令牌为空则不带，与服务端不鉴权模式对应）。 */
    private fun extractRequest(url: String): Request.Builder {
        val builder = Request.Builder().url(url)
        if (syncConfig.authToken.isNotBlank()) {
            builder.header("X-Token", syncConfig.authToken)
        }
        return builder
    }

    private fun setupExtractSection() {
        btnExtractNow.setOnClickListener { startExtract() }
        // 启动自动提取开关：即时生效，默认开（HistorySync 内兜底）
        switchAutoExtract.isChecked = HistorySync.isAutoEnabled(this)
        switchAutoExtract.setOnCheckedChangeListener { _, isChecked ->
            HistorySync.setAutoEnabled(this, isChecked)
        }
    }

    /** 自动提取状态行：未运行过给占位文案，否则原样展示 HistorySync 记录。 */
    private fun refreshAutoExtractStatus() {
        val status = HistorySync.lastStatus(this)
        if (status.isEmpty()) {
            tvAutoExtractStatus.text = getString(R.string.extract_auto_status_none)
            tvAutoExtractStatus.setTextColor(getColor(R.color.status_gray))
        } else {
            tvAutoExtractStatus.text = status
            tintStatus(tvAutoExtractStatus, status)
        }
    }

    /** 按钮可用性：轮询中或未配置服务器地址时置灰。 */
    private fun updateExtractButton() {
        btnExtractNow.isEnabled = !extractPolling && syncConfig.serverUrl.isNotBlank()
        btnExtractNow.text = getString(
            if (extractPolling) R.string.btn_extract_running else R.string.btn_extract_now
        )
    }

    /** 进入页面自动查一次提取状态；若服务端正在跑则接管轮询。 */
    private fun refreshExtractStatus() {
        updateExtractButton()
        if (extractPolling) return // 轮询进行中，界面由轮询循环接管
        if (syncConfig.serverUrl.isBlank()) {
            tvExtractStatus.text = getString(R.string.extract_need_server)
            tvExtractStatus.setTextColor(getColor(R.color.status_gray))
            return
        }
        thread {
            val status = fetchExtractStatus()
            runOnUiThread {
                if (extractPolling) return@runOnUiThread
                when {
                    status == null -> {
                        tvExtractStatus.text = getString(R.string.extract_status_query_fail)
                        tvExtractStatus.setTextColor(getColor(R.color.status_error))
                    }
                    status.optBoolean("running") -> {
                        setExtractRunningUi()
                        pollExtractStatus()
                    }
                    else -> showExtractLast(status.optJSONObject("last"))
                }
            }
        }
    }

    /** GET /admin/extract/status，网络或解析失败返回 null（防御式）。 */
    private fun fetchExtractStatus(): JSONObject? {
        return try {
            val request = extractRequest(extractEndpoint("extract/status")).get().build()
            testClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) null
                else try {
                    JSONObject(response.body?.string().orEmpty())
                } catch (e: Exception) {
                    null
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "查询提取状态失败", e)
            null
        }
    }

    /** 展示最近一次提取结果：成功给统计，失败给错误，从未提取给占位。 */
    private fun showExtractLast(last: JSONObject?) {
        val text = when {
            last == null -> getString(R.string.extract_status_none)
            last.optBoolean("ok") -> {
                val sb = StringBuilder("提取完成")
                val inserted = last.optInt("inserted", -1)
                if (inserted >= 0) sb.append("：新增 ").append(inserted).append(" 条")
                val elapsed = last.optDouble("elapsed_sec", Double.NaN)
                if (!elapsed.isNaN()) sb.append("，耗时 ").append(elapsed).append("s")
                sb.toString()
            }
            else -> "提取失败：" + last.optString("error", "未知错误")
        }
        tvExtractStatus.text = text
        tintStatus(tvExtractStatus, text)
    }

    private fun setExtractRunningUi() {
        extractPolling = true
        updateExtractButton()
        tvExtractStatus.text = getString(R.string.extract_status_running)
        tvExtractStatus.setTextColor(getColor(R.color.status_gray))
    }

    /** POST /admin/extract 触发提取，成功后转轮询；409（已在跑）同样转轮询。 */
    private fun startExtract() {
        if (syncConfig.serverUrl.isBlank()) {
            Toast.makeText(this, R.string.sync_url_empty, Toast.LENGTH_SHORT).show()
            return
        }
        setExtractRunningUi()
        thread {
            val triggerError = try {
                val request = extractRequest(extractEndpoint("extract"))
                    .post(FormBody.Builder().build())
                    .build()
                testClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful || response.code == 409) null
                    else "HTTP ${response.code}".let {
                        val body = response.body?.string().orEmpty()
                        if (body.isEmpty()) it else "$it ${body.take(120)}"
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "触发提取失败", e)
                e.message ?: "未知错误"
            }
            runOnUiThread {
                if (triggerError != null) {
                    extractPolling = false
                    updateExtractButton()
                    val text = getString(R.string.extract_trigger_fail, triggerError)
                    tvExtractStatus.text = text
                    tintStatus(tvExtractStatus, text)
                } else {
                    pollExtractStatus()
                }
            }
        }
    }

    /** 轮询 status：每 2s 一次、最多 60s；running 变 false 后展示结果统计。 */
    private fun pollExtractStatus() {
        thread {
            var finalStatus: JSONObject? = null
            var timedOut = true
            for (i in 1..30) {
                Thread.sleep(2000)
                val status = fetchExtractStatus()
                // 查询失败（网络抖动等）不视为结束，继续等下一轮
                if (status != null && !status.optBoolean("running", true)) {
                    finalStatus = status
                    timedOut = false
                    break
                }
            }
            runOnUiThread {
                extractPolling = false
                updateExtractButton()
                if (timedOut) {
                    tvExtractStatus.text = getString(R.string.extract_poll_timeout)
                    tvExtractStatus.setTextColor(getColor(R.color.status_gray))
                } else {
                    showExtractLast(finalStatus?.optJSONObject("last"))
                }
            }
        }
    }

    // ---------- 本机无障碍直读（无需 root） ----------

    private fun setupA11yExtractSection() {
        setupA11yTargetSpinner()
        btnA11yPermission.setOnClickListener {
            // 无障碍权限只能用户在系统设置手动开启，引导跳转
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        btnA11yStart.setOnClickListener {
            val targetPkg = selectedA11yPkg()
            if (targetPkg == null) {
                Toast.makeText(this, R.string.a11y_no_app, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            A11yExtractService.requestStart(targetPkg)
            // 跳回目标 App：它会恢复上次打开的会话，服务在窗口事件里自动接管
            val intent = packageManager.getLaunchIntentForPackage(targetPkg)
            if (intent != null) {
                startActivity(intent)
                Toast.makeText(
                    this,
                    getString(R.string.a11y_jump_app, selectedA11yLabel()),
                    Toast.LENGTH_LONG
                ).show()
            } else {
                A11yExtractService.requestStop() // 没装目标 App，撤回开始标记
                Toast.makeText(
                    this,
                    getString(R.string.a11y_no_app, selectedA11yLabel()),
                    Toast.LENGTH_SHORT
                ).show()
            }
            refreshA11yStatus()
        }
        btnA11yStop.setOnClickListener {
            A11yExtractService.requestStop()
            refreshA11yStatus()
        }
    }

    /**
     * 填充提取目标下拉框：注册表里带 a11yConfig 的源，且本机确实安装了才列出。
     * 顺序沿用注册表（当前只有微信；飞书/钉钉取证后自动出现，无需再改界面）。
     */
    private fun setupA11yTargetSpinner() {
        a11yTargetPkgs.clear()
        a11yTargetLabels.clear()
        for (source in AppSourceRegistry.knownSources()) {
            if (source.a11yConfig == null) continue
            if (!isPackageInstalled(source.pkg)) continue
            a11yTargetPkgs += source.pkg
            a11yTargetLabels += source.label
        }
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            a11yTargetLabels
        ).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        spinnerA11yTarget.adapter = adapter
    }

    private fun isPackageInstalled(pkg: String): Boolean = try {
        packageManager.getPackageInfo(pkg, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    private fun selectedA11yPkg(): String? =
        spinnerA11yTarget.selectedItemPosition
            .takeIf { it in a11yTargetPkgs.indices }
            ?.let { a11yTargetPkgs[it] }

    private fun selectedA11yLabel(): String =
        spinnerA11yTarget.selectedItemPosition
            .takeIf { it in a11yTargetLabels.indices }
            ?.let { a11yTargetLabels[it] }
            .orEmpty()

    /** 状态行 + 三按钮联动：未开权限 / 空闲 / 提取中 三态 */
    private fun refreshA11yStatus() {
        val enabled = A11yExtractService.isEnabled(this)
        val state = A11yExtractStore.read(this)
        val hasTarget = a11yTargetPkgs.isNotEmpty()
        spinnerA11yTarget.isEnabled = !state.running
        btnA11yPermission.isEnabled = !enabled
        btnA11yStart.isEnabled = enabled && hasTarget && !state.running
        btnA11yStop.isEnabled = enabled && state.running
        val text = when {
            !enabled -> getString(R.string.a11y_status_permission_missing)
            state.status.isBlank() -> getString(R.string.a11y_status_none)
            else -> state.status
        }
        tvA11yStatus.text = text
        tintStatus(tvA11yStatus, text)
    }

    /** 状态文字着色：含「成功/完成」绿，含「失败/鉴权」红，其余次要灰。 */
    private fun tintStatus(view: TextView, result: String) {
        view.setTextColor(
            getColor(
                when {
                    result.contains("失败") || result.contains("鉴权") -> R.color.status_error
                    result.contains("成功") || result.contains("完成") -> R.color.status_ok
                    else -> R.color.status_gray
                }
            )
        )
    }
}
