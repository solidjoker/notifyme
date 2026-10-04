// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import androidx.work.WorkManager
import okhttp3.Credentials
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * 移动端控制台：分区卡片式配置中心。
 *  - 微信对接状态：监听权限 + 监听服务连接状态 + 去授权；
 *  - 重点关注会话：入口跳 WatchlistActivity；
 *  - 服务器上报：总开关 + 地址/令牌/周期（从首页搬来）+ 立即同步 + 状态；
 *  - 后端分析：开关 + System 1 轻量判定（类型/地址/Basic 账号密码/模型）+
 *    System 2 深度分析（开关/服务商/地址/密钥/模型）+ 周期 + 各自测试连接 + 立即分析 + 状态；
 *  - 日历提醒：开关 + 提前量 + 已创建提醒列表入口。
 */
class ConsoleActivity : Activity() {

    companion object {
        private const val TAG = "ConsoleActivity"

        /** 日历读写运行时权限请求码 */
        private const val REQ_CALENDAR = 43

        /** 测试连接用（laya）：短超时，独立 client 不影响 Worker 的 */
        private val testClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()

        /** 测试连接用（openai）：生成式较慢，read 60s */
        private val openaiTestClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    private lateinit var syncConfig: SyncConfig
    private lateinit var analysisConfig: AnalysisConfig

    private lateinit var tvListenerStatus: TextView
    private lateinit var switchPushEnabled: Switch
    private lateinit var etServerUrl: EditText
    private lateinit var etAuthToken: EditText
    private lateinit var spinnerInterval: Spinner
    private lateinit var tvSyncStatus: TextView

    private lateinit var switchAnalysisEnabled: Switch
    private lateinit var spinnerS1Type: Spinner
    private lateinit var etBaseUrl: EditText
    private lateinit var etBasicUser: EditText
    private lateinit var etBasicPass: EditText
    private lateinit var etModel: EditText
    private lateinit var etApiKey: EditText
    private lateinit var btnTestConnection: Button
    private lateinit var switchS2Enabled: Switch
    private lateinit var spinnerS2Provider: Spinner
    private lateinit var etS2BaseUrl: EditText
    private lateinit var etS2ApiKey: EditText
    private lateinit var etS2Model: EditText
    private lateinit var btnTestS2Connection: Button
    private lateinit var spinnerAnalysisInterval: Spinner
    private lateinit var tvAnalysisStatus: TextView
    private lateinit var tvAnalysisStats: TextView

    /** S1 类型 spinner 顺序值：与 strings.xml 的 s1_type_labels 一一对应 */
    private val s1TypeValues = arrayOf(
        AnalysisConfig.S1_TYPE_LOCAL,
        AnalysisConfig.S1_TYPE_REMOTE,
        AnalysisConfig.S1_TYPE_LOCAL_MODEL
    )

    /** S2 服务商 spinner 顺序值：与 strings.xml 的 s2_provider_labels 一一对应 */
    private val s2ProviderValues = arrayOf(
        AnalysisConfig.S2_PROVIDER_JEV,
        AnalysisConfig.S2_PROVIDER_GLM,
        AnalysisConfig.S2_PROVIDER_CUSTOM,
        AnalysisConfig.S2_PROVIDER_LOCAL
    )

    private lateinit var switchReminderEnabled: Switch
    private lateinit var spinnerReminderLead: Spinner

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

    private lateinit var switchAdvisorAuto: Switch
    private lateinit var tvAdvisorStatus: TextView
    private lateinit var tvAdvisorReport: TextView

    /** 提取轮询进行中标志：期间按钮置灰、onResume 的状态查询不抢占界面 */
    @Volatile
    private var extractPolling = false

    /** Spinner 初始化期屏蔽 listener 回调，避免回填时被当成用户修改 */
    private var spinnerInitializing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_console)

        syncConfig = SyncConfig(this)
        analysisConfig = AnalysisConfig(this)

        // 标题栏返回
        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }

        bindViews()
        setupListenerSection()
        setupSyncSection()
        setupExtractSection()
        setupA11yExtractSection()
        setupAnalysisSection()
        setupAdvisorSection()
        setupReminderSection()
        setupPrivacySection()
        setupOverlaySection()
    }

    override fun onResume() {
        super.onResume()
        refreshListenerStatus()
        refreshSyncStatus()
        refreshExtractStatus()
        refreshAutoExtractStatus()
        refreshAnalysisStatus()
        refreshAdvisorStatus()
        refreshA11yStatus()
        refreshOverlayPermission?.invoke()
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

    /** M9：从悬浮窗权限设置页返回后刷新按钮状态（setupOverlaySection 中赋值） */
    private var refreshOverlayPermission: (() -> Unit)? = null
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

        switchAnalysisEnabled = findViewById(R.id.switchAnalysisEnabled)
        spinnerS1Type = findViewById(R.id.spinnerS1Type)
        etBaseUrl = findViewById(R.id.etBaseUrl)
        etBasicUser = findViewById(R.id.etBasicUser)
        etBasicPass = findViewById(R.id.etBasicPass)
        etModel = findViewById(R.id.etModel)
        etApiKey = findViewById(R.id.etApiKey)
        switchS2Enabled = findViewById(R.id.switchS2Enabled)
        spinnerS2Provider = findViewById(R.id.spinnerS2Provider)
        etS2BaseUrl = findViewById(R.id.etS2BaseUrl)
        etS2ApiKey = findViewById(R.id.etS2ApiKey)
        etS2Model = findViewById(R.id.etS2Model)
        btnTestS2Connection = findViewById(R.id.btnTestS2Connection)
        btnTestConnection = findViewById(R.id.btnTestConnection)
        spinnerAnalysisInterval = findViewById(R.id.spinnerAnalysisInterval)
        tvAnalysisStatus = findViewById(R.id.tvAnalysisStatus)
        tvAnalysisStats = findViewById(R.id.tvAnalysisStats)

        switchReminderEnabled = findViewById(R.id.switchReminderEnabled)
        spinnerReminderLead = findViewById(R.id.spinnerReminderLead)

        btnExtractNow = findViewById(R.id.btnExtractNow)
        tvExtractStatus = findViewById(R.id.tvExtractStatus)
        switchAutoExtract = findViewById(R.id.switchAutoExtract)
        tvAutoExtractStatus = findViewById(R.id.tvAutoExtractStatus)

        spinnerA11yTarget = findViewById(R.id.spinnerA11yTarget)
        btnA11yPermission = findViewById(R.id.btnA11yPermission)
        btnA11yStart = findViewById(R.id.btnA11yStart)
        btnA11yStop = findViewById(R.id.btnA11yStop)
        tvA11yStatus = findViewById(R.id.tvA11yStatus)

        switchAdvisorAuto = findViewById(R.id.switchAdvisorAuto)
        tvAdvisorStatus = findViewById(R.id.tvAdvisorStatus)
        tvAdvisorReport = findViewById(R.id.tvAdvisorReport)
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

    /** 进入控制台自动查一次提取状态；若服务端正在跑则接管轮询。 */
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

    // ---------- 后端分析 ----------

    private fun setupAnalysisSection() {
        val s1TypeLabels = resources.getStringArray(R.array.s1_type_labels)
        spinnerS1Type.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, s1TypeLabels
        )
        val s2ProviderLabels = resources.getStringArray(R.array.s2_provider_labels)
        spinnerS2Provider.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, s2ProviderLabels
        )
        val intervalLabels = resources.getStringArray(R.array.sync_interval_labels)
        spinnerAnalysisInterval.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, intervalLabels
        )

        // 回填（先屏蔽 listener，回填完再挂上）
        spinnerInitializing = true
        switchAnalysisEnabled.isChecked = analysisConfig.enabled

        // S1：类型 + 槽位字段（固定 systemone 槽位，laya 协议）
        val s1TypeIndex = s1TypeValues.indexOf(analysisConfig.s1Type).coerceAtLeast(0)
        spinnerS1Type.setSelection(s1TypeIndex)
        val s1Slot = analysisConfig.s1Slot()
        etBaseUrl.setText(
            s1Slot.url.ifEmpty {
                if (s1TypeValues[s1TypeIndex] == AnalysisConfig.S1_TYPE_LOCAL)
                    AnalysisConfig.DEFAULT_URL_SYSTEMONE else ""
            }
        )
        etApiKey.setText(s1Slot.key)
        etModel.setText(s1Slot.model)
        applyS1TypeEditable(s1TypeValues[s1TypeIndex])
        etBasicUser.setText(analysisConfig.basicUser)
        etBasicPass.setText(analysisConfig.basicPass)

        // S2：开关 + 服务商 + 槽位字段（固定 glm 槽位，openai 协议）
        switchS2Enabled.isChecked = analysisConfig.s2Enabled
        spinnerS2Provider.setSelection(
            s2ProviderValues.indexOf(analysisConfig.s2Provider).coerceAtLeast(0)
        )
        val s2Slot = analysisConfig.loadSlot(
            analysisConfig.s2SlotPreset(analysisConfig.s2Provider)
        )
        etS2BaseUrl.setText(s2Slot.url)
        etS2ApiKey.setText(s2Slot.key)
        etS2Model.setText(s2Slot.model)
        applyS2FieldsEnabled(analysisConfig.s2Enabled)

        val intervalIndex = AnalysisConfig.INTERVAL_OPTIONS.indexOf(analysisConfig.intervalMinutes)
        if (intervalIndex >= 0) spinnerAnalysisInterval.setSelection(intervalIndex)
        spinnerInitializing = false

        // S1 类型切换：「本地启发式」锁定默认本机地址，「自建服务地址」放开编辑，
        // 「本地 MiniCPM4-0.5B」走端侧引擎、连接字段整组不用
        spinnerS1Type.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (spinnerInitializing) return
                val type = s1TypeValues[position]
                analysisConfig.s1Type = type
                if (type == AnalysisConfig.S1_TYPE_LOCAL) {
                    etBaseUrl.setText(AnalysisConfig.DEFAULT_URL_SYSTEMONE)
                }
                applyS1TypeEditable(type)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // S2 开关即时生效：关闭时 S2 字段整组置灰（值保留）
        switchS2Enabled.setOnCheckedChangeListener { _, isChecked ->
            analysisConfig.s2Enabled = isChecked
            applyS2FieldsEnabled(isChecked)
        }

        // S2 服务商切换：JEV/GLM 从各自槽位整组回填（含密钥，test 包已由 v4 播种，
        // 切换免手填）；custom/本地不动现有值；本地服务商连接字段置灰
        spinnerS2Provider.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (spinnerInitializing) return
                val provider = s2ProviderValues[position]
                analysisConfig.s2Provider = provider
                when (provider) {
                    AnalysisConfig.S2_PROVIDER_JEV,
                    AnalysisConfig.S2_PROVIDER_GLM -> {
                        val slot = analysisConfig.loadSlot(
                            analysisConfig.s2SlotPreset(provider)
                        )
                        if (slot.url.isNotBlank()) {
                            etS2BaseUrl.setText(slot.url)
                            etS2ApiKey.setText(slot.key)
                            etS2Model.setText(slot.model)
                        } else {
                            // 槽位为空（open 包无预置）：只预填地址/模型，密钥保持手填
                            etS2BaseUrl.setText(
                                if (provider == AnalysisConfig.S2_PROVIDER_JEV)
                                    AnalysisConfig.S2_JEV_URL else AnalysisConfig.S2_GLM_URL
                            )
                            etS2Model.setText(
                                if (provider == AnalysisConfig.S2_PROVIDER_JEV)
                                    AnalysisConfig.S2_JEV_MODEL else AnalysisConfig.S2_GLM_MODEL
                            )
                        }
                    }
                }
                applyS2FieldsEnabled(switchS2Enabled.isChecked)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        findViewById<Button>(R.id.btnSaveAnalysis).setOnClickListener {
            analysisConfig.enabled = switchAnalysisEnabled.isChecked
            analysisConfig.s1Type = s1TypeValues[spinnerS1Type.selectedItemPosition]
            analysisConfig.saveSlot(
                AnalysisConfig.PRESET_SYSTEMONE,
                AnalysisConfig.SlotConfig(
                    url = etBaseUrl.text.toString().trim(),
                    key = etApiKey.text.toString().trim(),
                    model = etModel.text.toString().trim()
                )
            )
            analysisConfig.basicUser = etBasicUser.text.toString().trim()
            analysisConfig.basicPass = etBasicPass.text.toString()
            analysisConfig.s2Enabled = switchS2Enabled.isChecked
            analysisConfig.s2Provider = s2ProviderValues[spinnerS2Provider.selectedItemPosition]
            // 按当前服务商写入对应槽位（JEV 独立槽位，其余 glm 槽位），互不覆盖
            analysisConfig.saveSlot(
                analysisConfig.s2SlotPreset(analysisConfig.s2Provider),
                AnalysisConfig.SlotConfig(
                    url = etS2BaseUrl.text.toString().trim(),
                    key = etS2ApiKey.text.toString().trim(),
                    model = etS2Model.text.toString().trim()
                )
            )
            analysisConfig.intervalMinutes =
                AnalysisConfig.INTERVAL_OPTIONS[spinnerAnalysisInterval.selectedItemPosition]

            // 调度条件：本地模型类型不看地址（worker 内自查模型就绪并降级），
            // 其余类型要求 S1 地址非空
            val s1Configured =
                analysisConfig.s1Type == AnalysisConfig.S1_TYPE_LOCAL_MODEL ||
                    analysisConfig.s1Slot().url.isNotEmpty()
            if (analysisConfig.enabled && s1Configured) {
                AnalysisScheduler.schedule(this, analysisConfig.intervalMinutes)
            } else {
                AnalysisScheduler.cancel(this)
            }
            Toast.makeText(this, R.string.analysis_saved, Toast.LENGTH_SHORT).show()
        }

        // S1 固定 laya 协议；S2 固定 openai 协议，各自独立测试
        btnTestConnection.setOnClickListener { testConnectionLaya() }
        btnTestS2Connection.setOnClickListener { testConnectionOpenAi() }

        findViewById<Button>(R.id.btnAnalysisNow).setOnClickListener {
            AnalysisScheduler.enqueueAnalysisNow(this)
            Toast.makeText(this, R.string.analysis_enqueued, Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnOpenAnalysis).setOnClickListener {
            startActivity(Intent(this, AnalysisListActivity::class.java))
        }

        findViewById<Button>(R.id.btnOpenModels).setOnClickListener {
            startActivity(Intent(this, ModelListActivity::class.java))
        }
    }

    /**
     * S1 字段可编辑性：本地启发式锁地址（密钥/模型可改）；
     * 本地 MiniCPM4-0.5B（端侧）连接字段整组不用，全部置灰；自建服务全开放。
     */
    private fun applyS1TypeEditable(type: String) {
        val localModel = type == AnalysisConfig.S1_TYPE_LOCAL_MODEL
        etBaseUrl.isEnabled = type == AnalysisConfig.S1_TYPE_REMOTE
        etBasicUser.isEnabled = !localModel
        etBasicPass.isEnabled = !localModel
        etModel.isEnabled = !localModel
        etApiKey.isEnabled = !localModel
        btnTestConnection.isEnabled = !localModel
    }

    /**
     * S2 字段可编辑性：开关关闭整组置灰（值保留，重开即恢复）；
     * 服务商为「本地 MiniCPM3-4B」时连接字段不用，置灰（开关仍可用）。
     */
    private fun applyS2FieldsEnabled(enabled: Boolean) {
        val position = spinnerS2Provider.selectedItemPosition
        val localProvider = position >= 0 &&
            s2ProviderValues[position] == AnalysisConfig.S2_PROVIDER_LOCAL
        spinnerS2Provider.isEnabled = enabled
        etS2BaseUrl.isEnabled = enabled && !localProvider
        etS2ApiKey.isEnabled = enabled && !localProvider
        etS2Model.isEnabled = enabled && !localProvider
        btnTestS2Connection.isEnabled = enabled && !localProvider
    }

    /** laya 协议：GET {baseUrl}/v1/models，展示可用模型或错误。 */
    private fun testConnectionLaya() {
        // 容忍填到 /v1 为止的地址（与 AnalysisWorker 同一规范化）
        val base = etBaseUrl.text.toString().trim().trimEnd('/').removeSuffix("/v1")
        if (base.isEmpty()) {
            tvAnalysisStatus.text = getString(R.string.analysis_baseurl_hint)
            return
        }
        val user = etBasicUser.text.toString().trim()
        val pass = etBasicPass.text.toString()
        val apiKey = etApiKey.text.toString().trim()
        tvAnalysisStatus.text = getString(R.string.analysis_testing)

        thread {
            val result = try {
                val request = Request.Builder()
                    .url("$base/v1/models")
                    .get()
                    .apply {
                        // 优先 nginx Basic；否则 Bearer（JEV 直连）；都空则不带
                        if (user.isNotEmpty()) {
                            header("Authorization", Credentials.basic(user, pass))
                        } else if (apiKey.isNotEmpty()) {
                            header("Authorization", "Bearer $apiKey")
                        }
                    }
                    .build()
                testClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string().orEmpty()
                        val names = mutableListOf<String>()
                        try {
                            val models = JSONObject(body).optJSONArray("models")
                            if (models != null) {
                                for (i in 0 until models.length()) {
                                    names.add(models.optJSONObject(i)?.optString("name").orEmpty())
                                }
                            }
                        } catch (e: Exception) {
                            // 响应结构不符也展示原始片段
                        }
                        if (names.isEmpty()) "连接成功（HTTP ${response.code}），但未解析到模型列表"
                        else "连接成功，可用模型：${names.joinToString(", ")}"
                    } else {
                        "连接失败：HTTP ${response.code}"
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "测试连接失败", e)
                "连接失败：${e.message ?: "未知错误"}"
            }
            runOnUiThread {
                tvAnalysisStatus.text = result
                tintStatus(tvAnalysisStatus, result)
            }
        }
    }

    /** S2 测试连接：POST {base}/chat/completions 最小调用（max_tokens=1）验证连通与密钥。 */
    private fun testConnectionOpenAi() {
        val base = etS2BaseUrl.text.toString().trim().trimEnd('/')
        if (base.isEmpty()) {
            tvAnalysisStatus.text = getString(R.string.s2_baseurl_hint)
            return
        }
        val url = if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
        val apiKey = etS2ApiKey.text.toString().trim()
        val model = etS2Model.text.toString().trim()
        tvAnalysisStatus.text = getString(R.string.analysis_testing)

        thread {
            val result = try {
                val requestJson = JSONObject().apply {
                    put("model", model)
                    put("messages", org.json.JSONArray().put(
                        JSONObject().apply {
                            put("role", "user")
                            put("content", "ping")
                        }
                    ))
                    put("max_tokens", 1)
                    put("stream", false)
                }
                val request = Request.Builder()
                    .url(url)
                    .post(requestJson.toString()
                        .toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .apply {
                        if (apiKey.isNotEmpty()) {
                            header("Authorization", "Bearer $apiKey")
                        }
                    }
                    .build()
                openaiTestClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        "连接成功，模型 $model 可用（HTTP ${response.code}）"
                    } else {
                        val body = response.body?.string().orEmpty()
                        if (body.isEmpty()) "连接失败：HTTP ${response.code}"
                        else "连接失败：HTTP ${response.code} ${body.take(160)}"
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "测试连接失败(openai)", e)
                "连接失败：${e.message ?: "未知错误"}"
            }
            runOnUiThread {
                tvAnalysisStatus.text = result
                tintStatus(tvAnalysisStatus, result)
            }
        }
    }

    private fun refreshAnalysisStatus() {
        val time = analysisConfig.lastAnalysisTime
        tvAnalysisStatus.text = if (time <= 0L) {
            getString(R.string.analysis_status_none)
        } else {
            val timeText = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
                .format(Date(time))
            "$timeText  ${analysisConfig.lastAnalysisResult}"
        }
        tintStatus(tvAnalysisStatus, analysisConfig.lastAnalysisResult)

        // 分析结果统计（case 版）：已分析会话数 / 待办会话数（按会话去重）
        val records = AnalysisStore.readRecent(this, 500)
        val conversations = records.map { it.conversation }.toSet()
        val todoConversations = records.filter { it.s1NeedAction }
            .map { it.conversation }.toSet()
        tvAnalysisStats.text = getString(
            R.string.analysis_stats_format, conversations.size, todoConversations.size
        )
        tvAnalysisStats.setTextColor(
            getColor(
                if (todoConversations.isNotEmpty()) R.color.accent_orange
                else R.color.status_gray
            )
        )
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

    // ---------- 顾问复盘（Fable 层） ----------

    private fun setupAdvisorSection() {
        // 开关即时生效：开=注册每 7 天周期任务，关=取消。默认开（AdvisorStore 兜底）。
        switchAdvisorAuto.isChecked = AdvisorStore.isAutoEnabled(this)
        switchAdvisorAuto.setOnCheckedChangeListener { _, isChecked ->
            AdvisorStore.setAutoEnabled(this, isChecked)
            if (isChecked) AdvisorScheduler.schedule(this) else AdvisorScheduler.cancel(this)
        }
        // 进入控制台时确保周期任务存在（UPDATE 策略幂等，不叠加）
        if (AdvisorStore.isAutoEnabled(this)) {
            AdvisorScheduler.schedule(this)
        }

        findViewById<Button>(R.id.btnAdvisorNow).setOnClickListener {
            AdvisorScheduler.enqueueAdvisorNow(this)
            Toast.makeText(this, R.string.advisor_enqueued, Toast.LENGTH_SHORT).show()
        }
    }

    /** 顾问状态行 + 最新报告展示。 */
    private fun refreshAdvisorStatus() {
        val time = AdvisorStore.lastTime(this)
        tvAdvisorStatus.text = if (time <= 0L) {
            getString(R.string.advisor_status_none)
        } else {
            val timeText = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
                .format(Date(time))
            "$timeText  ${AdvisorStore.lastResult(this)}"
        }
        tintStatus(tvAdvisorStatus, AdvisorStore.lastResult(this))

        val report = AdvisorStore.latest(this)
        if (report == null) {
            tvAdvisorReport.text = getString(R.string.advisor_report_none)
            tvAdvisorReport.setTextColor(getColor(R.color.status_gray))
        } else {
            val timeText = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
                .format(Date(report.createdAt))
            val sb = StringBuilder()
            sb.append("报告 ").append(timeText)
                .append(" · 复盘 ").append(report.caseCount).append(" 个 case")
                .append(" · 模型 ").append(report.modelUsed)
                .append("\n").append(report.summary)
            report.suggestions.forEachIndexed { i, s ->
                sb.append("\n").append(i + 1).append(". [").append(s.type).append("] ")
                    .append(s.title)
                if (s.detail.isNotEmpty()) sb.append("：").append(s.detail)
            }
            tvAdvisorReport.text = sb.toString()
            tvAdvisorReport.setTextColor(getColor(R.color.text_primary))
        }
    }

    // ---------- 日历提醒 ----------

    private fun setupReminderSection() {
        val leadLabels = resources.getStringArray(R.array.reminder_lead_labels)
        spinnerReminderLead.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, leadLabels
        )

        spinnerInitializing = true
        switchReminderEnabled.isChecked = analysisConfig.reminderEnabled
        val leadIndex = AnalysisConfig.REMINDER_LEAD_OPTIONS
            .indexOf(analysisConfig.reminderLeadMinutes)
        if (leadIndex >= 0) spinnerReminderLead.setSelection(leadIndex)
        spinnerInitializing = false

        // 开关即时生效；开启时确保日历权限
        switchReminderEnabled.setOnCheckedChangeListener { _, isChecked ->
            analysisConfig.reminderEnabled = isChecked
            if (isChecked && !CalendarHelper.hasCalendarPermission(this)) {
                requestCalendarPermission()
            }
        }

        spinnerReminderLead.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (spinnerInitializing) return
                analysisConfig.reminderLeadMinutes =
                    AnalysisConfig.REMINDER_LEAD_OPTIONS[position]
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        findViewById<Button>(R.id.btnOpenReminders).setOnClickListener {
            startActivity(Intent(this, ReminderListActivity::class.java))
        }
    }

    private fun requestCalendarPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestPermissions(
                arrayOf(
                    android.Manifest.permission.READ_CALENDAR,
                    android.Manifest.permission.WRITE_CALENDAR
                ),
                REQ_CALENDAR
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CALENDAR) {
            val granted = grantResults.isNotEmpty() &&
                grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (!granted) {
                // 三级降级链下日历权限只影响 Level 1（直写系统日历）：
                // 拒绝后不再回退开关，Level 2 唤起日历 App / Level 3 App 内闹钟仍可用
                Toast.makeText(this, R.string.reminder_no_permission, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ---------- M3 隐私脱敏 ----------

    /** 隐私卡片初始化：三模式按钮 + 规则/白名单对话框 + 预览入口。 */
    private fun setupPrivacySection() {
        val btnOff = findViewById<Button>(R.id.btnPrivacyOff)
        val btnCloud = findViewById<Button>(R.id.btnPrivacyCloud)
        val btnLocal = findViewById<Button>(R.id.btnPrivacyLocal)
        val tvSummary = findViewById<TextView>(R.id.tvPrivacySummary)
        val btnRules = findViewById<Button>(R.id.btnPrivacyRules)
        val btnBypass = findViewById<Button>(R.id.btnPrivacyBypass)
        val btnPreview = findViewById<Button>(R.id.btnPrivacyPreview)

        var state = PrivacyConfig.get(this)

        fun refresh() {
            state = PrivacyConfig.get(this)
            // 选中模式按钮变绿，其余恢复普通文字色
            btnOff.setTextColor(getColor(if (state.isOff) R.color.brand_green else R.color.text_primary))
            btnCloud.setTextColor(getColor(if (state.cloudRedact) R.color.brand_green else R.color.text_primary))
            btnLocal.setTextColor(getColor(if (state.localOnly) R.color.brand_green else R.color.text_primary))
            tvSummary.text = when {
                state.isOff -> getString(R.string.privacy_mode_off_desc)
                state.cloudRedact -> getString(R.string.privacy_mode_cloud_desc)
                else -> getString(R.string.privacy_mode_local_desc)
            }
            btnRules.text = getString(
                R.string.privacy_rules_btn, state.enabledRules.size, RedactorRules.ALL.size
            )
            btnBypass.text = getString(R.string.privacy_bypass_btn, state.bypassConvIds.size)
        }

        fun setMode(mode: String) {
            PrivacyConfig.set(this, state.copy(mode = mode))
            refresh()
        }

        btnOff.setOnClickListener { setMode(PrivacyModeState.OFF) }
        btnCloud.setOnClickListener { setMode(PrivacyModeState.CLOUD_REDACT) }
        btnLocal.setOnClickListener { setMode(PrivacyModeState.LOCAL_ONLY) }

        // 规则多选对话框：显示顺序固定，勾选即启用；空集合也允许（等于只关规则、
        // 仍保留模式语义——姓名等全关时出设备文本就是原文，仅在用户明确这么配时）
        val ruleIds = listOf(
            RedactorRules.PHONE, RedactorRules.ID_CARD, RedactorRules.BANK_CARD,
            RedactorRules.EMAIL, RedactorRules.URL, RedactorRules.AMOUNT,
            RedactorRules.ADDRESS, RedactorRules.NAME
        )
        val ruleLabelRes = listOf(
            R.string.rule_label_phone, R.string.rule_label_id_card,
            R.string.rule_label_bank_card, R.string.rule_label_email,
            R.string.rule_label_url, R.string.rule_label_amount,
            R.string.rule_label_address, R.string.rule_label_name
        )
        btnRules.setOnClickListener {
            val labels = ruleLabelRes.map { getString(it) }.toTypedArray()
            val checked = ruleIds.map { it in state.enabledRules }.toBooleanArray()
            android.app.AlertDialog.Builder(this)
                .setTitle(R.string.privacy_rules_dialog_title)
                .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                    checked[which] = isChecked
                }
                .setNegativeButton(R.string.common_cancel, null)
                .setPositiveButton(R.string.common_save) { _, _ ->
                    val enabled = ruleIds.filterIndexed { i, _ -> checked[i] }.toSet()
                    PrivacyConfig.set(this, state.copy(enabledRules = enabled))
                    refresh()
                }
                .show()
        }

        // 白名单多选：候选 = 消息库里出现过的会话（readRecent 倒序，按首次出现保留）
        btnBypass.setOnClickListener {
            val keys = mutableListOf<ConvKey>()
            MessageStore.readRecent(this, 1000).forEach { m ->
                if (m.conversation.isNotEmpty() && m.convKey !in keys) keys.add(m.convKey)
            }
            if (keys.isEmpty()) {
                Toast.makeText(this, R.string.privacy_bypass_empty, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val labels = keys.map { it.conversation }.toTypedArray()
            val checked = keys.map { it.id in state.bypassConvIds }.toBooleanArray()
            android.app.AlertDialog.Builder(this)
                .setTitle(R.string.privacy_bypass_dialog_title)
                .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                    checked[which] = isChecked
                }
                .setNegativeButton(R.string.common_cancel, null)
                .setPositiveButton(R.string.common_save) { _, _ ->
                    val bypass = keys.filterIndexed { i, _ -> checked[i] }
                        .map { it.id }.toSet()
                    PrivacyConfig.set(this, state.copy(bypassConvIds = bypass))
                    refresh()
                }
                .show()
        }

        btnPreview.setOnClickListener {
            startActivity(Intent(this, RedactorPreviewActivity::class.java))
        }

        refresh()
    }

    // ---------- M9 悬浮通知 ----------

    /**
     * 悬浮卡片初始化：卡片/球两个开关 + 悬浮窗权限按钮。
     * 开关切换即写配置并下发 OverlayService reconcile；无悬浮窗权限时触发链路
     * 自动降级为 heads-up（OverlayManager 内部判断）。
     */
    private fun setupOverlaySection() {
        val switchCards = findViewById<Switch>(R.id.switchOverlayCards)
        val switchBall = findViewById<Switch>(R.id.switchOverlayBall)
        val btnPermission = findViewById<Button>(R.id.btnOverlayPermission)
        val btnDebugTest = findViewById<Button>(R.id.btnOverlayDebugTest)

        // 仅 debug 构建显示：合成一条微信风格卡片验证悬浮层，release 自动隐藏
        if (BuildConfig.DEBUG) btnDebugTest.visibility = View.VISIBLE
        btnDebugTest.setOnClickListener {
            OverlayManager.show(
                this,
                OverlayManager.Card(
                    pkg = AppSourceRegistry.PKG_WECHAT,
                    conversation = "项目群",
                    sender = "张三",
                    text = "明天下午3点前把方案发我，记得打电话 13812345678"
                )
            )
        }

        var state = OverlayConfig.get(this)

        fun syncService() {
            val intent = Intent(this, OverlayService::class.java).apply {
                action = OverlayService.ACTION_RECONCILE
            }
            androidx.core.content.ContextCompat.startForegroundService(this, intent)
        }

        switchCards.isChecked = state.cardsEnabled
        switchBall.isChecked = state.ballEnabled
        switchCards.setOnCheckedChangeListener { _, checked ->
            state = OverlayConfig.get(this).copy(cardsEnabled = checked)
            OverlayConfig.set(this, state)
            if (checked) syncService()
        }
        switchBall.setOnCheckedChangeListener { _, checked ->
            state = OverlayConfig.get(this).copy(ballEnabled = checked)
            OverlayConfig.set(this, state)
            if (checked) syncService()
        }

        fun refreshPermission() {
            val granted = OverlayManager.canDrawOverlays(this)
            btnPermission.text = if (granted) getString(R.string.overlay_permission_ok)
            else getString(R.string.overlay_permission_btn)
            btnPermission.isEnabled = !granted
        }
        btnPermission.setOnClickListener {
            startActivity(OverlayManager.overlaySettingsIntent(this))
        }

        refreshOverlayPermission = ::refreshPermission
        refreshPermission()
    }
}
