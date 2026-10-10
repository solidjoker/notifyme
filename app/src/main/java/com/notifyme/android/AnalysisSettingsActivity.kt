// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import android.app.Activity
import android.content.Intent
import android.os.Bundle
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
import okhttp3.Credentials
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
 * 分析设置页（自 ConsoleActivity 拆出，行为不变）：
 *  - 后端分析：开关 + System 1 轻量判定（类型/地址/Basic 账号密码/模型）+
 *    System 2 深度分析（开关/服务商/地址/密钥/模型）+ 周期 + 各自测试连接 + 立即分析 + 状态；
 *  - AI 评审（M10 advisor 槽位，第二模型评审）；
 *  - 顾问复盘（Fable 层，7 天复盘，与 AI 评审不同功能）。
 */
class AnalysisSettingsActivity : Activity() {

    companion object {
        private const val TAG = "AnalysisSettingsActivity"

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

    private lateinit var analysisConfig: AnalysisConfig

    private lateinit var switchAnalysisEnabled: Switch
    private lateinit var spinnerS1Type: Spinner
    private lateinit var etBaseUrl: EditText
    private lateinit var etBasicUser: EditText
    private lateinit var rowBasicAuth: android.view.ViewGroup
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
    private lateinit var switchAdvisorEnabled: Switch
    private lateinit var etAdvisorBaseUrl: EditText
    private lateinit var etAdvisorApiKey: EditText
    private lateinit var etAdvisorModel: EditText
    private lateinit var btnTestAdvisorConnection: Button
    private lateinit var spinnerAnalysisInterval: Spinner
    private lateinit var tvAnalysisStatus: TextView
    private lateinit var tvAnalysisStats: TextView

    private lateinit var switchAdvisorAuto: Switch
    private lateinit var tvAdvisorStatus: TextView
    private lateinit var tvAdvisorReport: TextView

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

    /** Spinner 初始化期屏蔽 listener 回调，避免回填时被当成用户修改 */
    private var spinnerInitializing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_analysis_settings)

        analysisConfig = AnalysisConfig(this)

        // 标题栏返回
        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }

        bindViews()
        setupAnalysisSection()
        setupAdvisorSection()
    }

    override fun onResume() {
        super.onResume()
        refreshAnalysisStatus()
        refreshAdvisorStatus()
    }

    private fun bindViews() {
        switchAnalysisEnabled = findViewById(R.id.switchAnalysisEnabled)
        spinnerS1Type = findViewById(R.id.spinnerS1Type)
        etBaseUrl = findViewById(R.id.etBaseUrl)
        etBasicUser = findViewById(R.id.etBasicUser)
        rowBasicAuth = findViewById(R.id.rowBasicAuth)
        etBasicPass = findViewById(R.id.etBasicPass)
        etModel = findViewById(R.id.etModel)
        etApiKey = findViewById(R.id.etApiKey)
        switchS2Enabled = findViewById(R.id.switchS2Enabled)
        spinnerS2Provider = findViewById(R.id.spinnerS2Provider)
        etS2BaseUrl = findViewById(R.id.etS2BaseUrl)
        etS2ApiKey = findViewById(R.id.etS2ApiKey)
        etS2Model = findViewById(R.id.etS2Model)
        btnTestS2Connection = findViewById(R.id.btnTestS2Connection)
        switchAdvisorEnabled = findViewById(R.id.switchAdvisorEnabled)
        etAdvisorBaseUrl = findViewById(R.id.etAdvisorBaseUrl)
        etAdvisorApiKey = findViewById(R.id.etAdvisorApiKey)
        etAdvisorModel = findViewById(R.id.etAdvisorModel)
        btnTestAdvisorConnection = findViewById(R.id.btnTestAdvisorConnection)
        btnTestConnection = findViewById(R.id.btnTestConnection)
        spinnerAnalysisInterval = findViewById(R.id.spinnerAnalysisInterval)
        tvAnalysisStatus = findViewById(R.id.tvAnalysisStatus)
        tvAnalysisStats = findViewById(R.id.tvAnalysisStats)

        switchAdvisorAuto = findViewById(R.id.switchAdvisorAuto)
        tvAdvisorStatus = findViewById(R.id.tvAdvisorStatus)
        tvAdvisorReport = findViewById(R.id.tvAdvisorReport)
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
            // M10 advisor 槽位（固定 advisor 槽位，openai 协议）
            analysisConfig.advisorEnabled = switchAdvisorEnabled.isChecked
            analysisConfig.saveSlot(
                AnalysisConfig.PRESET_ADVISOR,
                AnalysisConfig.SlotConfig(
                    url = etAdvisorBaseUrl.text.toString().trim(),
                    key = etAdvisorApiKey.text.toString().trim(),
                    model = etAdvisorModel.text.toString().trim()
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

        // M10 advisor：开关即时生效 + 槽位回填 + 独立连接测试（固定 openai 协议）
        switchAdvisorEnabled.isChecked = analysisConfig.advisorEnabled
        val advisorSlot = analysisConfig.loadSlot(AnalysisConfig.PRESET_ADVISOR)
        etAdvisorBaseUrl.setText(advisorSlot.url)
        etAdvisorApiKey.setText(advisorSlot.key)
        etAdvisorModel.setText(advisorSlot.model)
        applyAdvisorFieldsEnabled(analysisConfig.advisorEnabled)
        switchAdvisorEnabled.setOnCheckedChangeListener { _, isChecked ->
            analysisConfig.advisorEnabled = isChecked
            applyAdvisorFieldsEnabled(isChecked)
        }
        btnTestAdvisorConnection.setOnClickListener {
            testConnectionOpenAi(etAdvisorBaseUrl, etAdvisorApiKey, etAdvisorModel)
        }

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
        val remote = type == AnalysisConfig.S1_TYPE_REMOTE
        val vis = if (localModel) View.GONE else View.VISIBLE
        etBaseUrl.visibility = vis
        rowBasicAuth.visibility = vis
        etModel.visibility = vis
        etApiKey.visibility = vis
        btnTestConnection.visibility = vis
        etBaseUrl.isEnabled = remote
        etBasicUser.isEnabled = remote
        etBasicPass.isEnabled = remote
        etModel.isEnabled = remote
        etApiKey.isEnabled = remote
        btnTestConnection.isEnabled = remote
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
        val connVis = if (enabled && !localProvider) View.VISIBLE else View.GONE
        etS2BaseUrl.visibility = connVis
        etS2ApiKey.visibility = connVis
        etS2Model.visibility = connVis
        btnTestS2Connection.visibility = connVis
        etS2BaseUrl.isEnabled = enabled && !localProvider
        etS2ApiKey.isEnabled = enabled && !localProvider
        etS2Model.isEnabled = enabled && !localProvider
        btnTestS2Connection.isEnabled = enabled && !localProvider
    }

    /** M10：advisor 连接字段随开关整组置灰（值保留） */
    private fun applyAdvisorFieldsEnabled(enabled: Boolean) {
        etAdvisorBaseUrl.isEnabled = enabled
        etAdvisorApiKey.isEnabled = enabled
        etAdvisorModel.isEnabled = enabled
        btnTestAdvisorConnection.isEnabled = enabled
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

    /** S2 / Advisor 测试连接：POST {base}/chat/completions 最小调用（max_tokens=1）验证连通与密钥。 */
    private fun testConnectionOpenAi(
        baseField: EditText = etS2BaseUrl,
        keyField: EditText = etS2ApiKey,
        modelField: EditText = etS2Model
    ) {
        val base = baseField.text.toString().trim().trimEnd('/')
        if (base.isEmpty()) {
            tvAnalysisStatus.text = getString(R.string.s2_baseurl_hint)
            return
        }
        val url = if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
        val apiKey = keyField.text.toString().trim()
        val model = modelField.text.toString().trim()
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
        // 进入分析页时确保周期任务存在（UPDATE 策略幂等，不叠加）
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
}
