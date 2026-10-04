// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.qiyeweixin.weixin_android

import android.content.Context

/**
 * 后端分析配置与最近分析状态，存 SharedPreferences（文件名 analysis_config）。
 *
 * 双协议模型：
 *  - preset：systemone（laya 决策，POST /v1/systemone）/ glm（OpenAI 兼容对话，
 *    POST /chat/completions）/ custom（协议可手选）；
 *  - 每个预设独立记住自己上次填的 URL/密钥/模型（slot_<preset>_*），
 *    切换预设时回填该预设的值，互不覆盖；
 *  - protocol：systemone 固定 laya，glm 固定 openai，custom 存 custom_protocol；
 *  - basicUser/basicPass：laya 走公网 nginx Basic Auth 时用（全局，不入槽位）；
 *  - 槽位密钥 key：laya 直连 JEV（api.typesafe.ai）时是 Bearer key，
 *    openai 协议时是 OpenAI Bearer key；
 *  - test 包首次启动用 BuildConfig.DEFAULT_* 播种默认值（仅填充，用户可改），
 *    open 包 DEFAULT_* 全为空，行为与旧版一致；
 *  - 旧版本迁移：已存的 base_url/model 等迁移进 systemone 槽位。
 */
class AnalysisConfig(context: Context) {

    companion object {
        private const val PREFS_NAME = "analysis_config"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_PRESET = "preset"
        private const val KEY_CUSTOM_PROTOCOL = "custom_protocol"
        private const val KEY_BASIC_USER = "basic_user"
        private const val KEY_BASIC_PASS = "basic_pass"
        private const val KEY_INTERVAL_MINUTES = "interval_minutes"
        private const val KEY_REMINDER_ENABLED = "reminder_enabled"
        private const val KEY_REMINDER_LEAD_MINUTES = "reminder_lead_minutes"
        private const val KEY_LAST_ANALYSIS_TIME = "last_analysis_time"
        private const val KEY_LAST_ANALYSIS_RESULT = "last_analysis_result"
        private const val KEY_SEEDED_V2 = "seeded_v2"
        private const val KEY_SEEDED_V3 = "seeded_v3"
        private const val KEY_SEEDED_V4 = "seeded_v4"

        // System 1 / System 2 分组配置（v3 起）
        private const val KEY_S1_TYPE = "s1_type"
        private const val KEY_S2_ENABLED = "s2_enabled"
        private const val KEY_S2_PROVIDER = "s2_provider"

        // 旧版字段（仅迁移用）
        private const val KEY_LEGACY_BASE_URL = "base_url"
        private const val KEY_LEGACY_MODEL = "model"

        const val PRESET_SYSTEMONE = "systemone"
        const val PRESET_GLM = "glm"
        const val PRESET_CUSTOM = "custom"

        /** S2 的 JEV 服务商独立槽位（伪预设，仅作 slot_ 前缀，不出现在 S1 预设列表） */
        const val PRESET_S2_JEV = "s2jev"

        const val PROTOCOL_LAYA = "laya"
        const val PROTOCOL_OPENAI = "openai"

        /** 端侧本地引擎协议标记（仅用于分析记录 protocol 字段，非 HTTP 协议） */
        const val PROTOCOL_LOCAL = "local"

        /** System 1 类型：本地启发式（本机 laya 服务）/ 自建服务地址 */
        const val S1_TYPE_LOCAL = "local"
        const val S1_TYPE_REMOTE = "remote"

        /** System 1 类型（追加）：本地 MiniCPM4-0.5B 端侧快筛（LocalLlmEngine） */
        const val S1_TYPE_LOCAL_MODEL = "local_model"

        /** System 2 服务商：JEV / GLM coding plan / 自定义 OpenAI 兼容 */
        const val S2_PROVIDER_JEV = "jev"
        const val S2_PROVIDER_GLM = "glm"
        const val S2_PROVIDER_CUSTOM = "custom"

        /** System 2 服务商（追加）：本地 MiniCPM3-4B 端侧生成（LocalLlmEngine） */
        const val S2_PROVIDER_LOCAL = "local"

        /** S2 服务商预填值（用户可改；test 包密钥由 BuildConfig 播种） */
        const val S2_JEV_URL = "https://api.typesafe.ai/v1"
        const val S2_JEV_MODEL = "jev-latest"
        const val S2_GLM_URL = "https://open.bigmodel.cn/api/coding/paas/v4"
        const val S2_GLM_MODEL = "glm-5.3-flash"

        /** 本地 laya 默认地址（open 包 systemone 槽位兜底） */
        const val DEFAULT_URL_SYSTEMONE = "http://127.0.0.1:8124"

        const val DEFAULT_MODEL = "laya-multilingual"
        const val DEFAULT_MODEL_JEV = "jev-latest"
        const val DEFAULT_INTERVAL_MINUTES = 15L

        /** 分析周期可选项；顺序与 strings.xml 的 sync_interval_labels 一一对应（复用） */
        val INTERVAL_OPTIONS = longArrayOf(15, 30, 60, 120)

        /** 提醒提前量可选项（分钟）；顺序与 strings.xml 的 reminder_lead_labels 一一对应 */
        val REMINDER_LEAD_OPTIONS = longArrayOf(15, 60, 1440)

        /** 预设 UI 顺序：与 strings.xml 的 analysis_preset_labels 一一对应 */
        val PRESET_VALUES = arrayOf(PRESET_SYSTEMONE, PRESET_GLM, PRESET_CUSTOM)

        /** custom 协议可选值；与 strings.xml 的 analysis_protocol_labels 一一对应 */
        val PROTOCOL_VALUES = arrayOf(PROTOCOL_LAYA, PROTOCOL_OPENAI)

        private fun slotKey(preset: String, field: String) = "slot_${preset}_$field"
    }

    /** 单个预设记住的连接参数 */
    data class SlotConfig(val url: String, val key: String, val model: String)

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        seedDefaultsOnce()
        seedS1S2Once()
        seedS1S2V4Once()
    }

    /** 首次运行（或升级后未播种）时填充各预设默认值 + 迁移旧版配置。 */
    private fun seedDefaultsOnce() {
        if (prefs.getBoolean(KEY_SEEDED_V2, false)) return

        // 旧版迁移：老的 base_url/model 归入 systemone 槽位
        val legacyUrl = prefs.getString(KEY_LEGACY_BASE_URL, "").orEmpty()
        val legacyModel = prefs.getString(KEY_LEGACY_MODEL, "").orEmpty()

        // systemone 槽位：test 包默认 JEV 直连；open 包默认本地 laya
        val s1Url = when {
            legacyUrl.isNotEmpty() -> legacyUrl
            BuildConfig.DEFAULT_LAYA_URL.isNotEmpty() -> BuildConfig.DEFAULT_LAYA_URL
            else -> DEFAULT_URL_SYSTEMONE
        }
        val s1Model = when {
            legacyModel.isNotEmpty() -> legacyModel
            BuildConfig.DEFAULT_LAYA_URL.isNotEmpty() -> DEFAULT_MODEL_JEV
            else -> DEFAULT_MODEL
        }
        saveSlot(PRESET_SYSTEMONE, SlotConfig(s1Url, BuildConfig.DEFAULT_LAYA_KEY, s1Model))

        // glm 槽位：test 包注入 GLM coding plan 端点与密钥
        saveSlot(
            PRESET_GLM,
            SlotConfig(
                BuildConfig.DEFAULT_ANALYSIS_URL,
                BuildConfig.DEFAULT_ANALYSIS_KEY,
                BuildConfig.DEFAULT_ANALYSIS_MODEL
            )
        )

        // custom 槽位留空
        saveSlot(PRESET_CUSTOM, SlotConfig("", "", ""))

        // 默认预设：test 包（注入了 openai 默认值）落 glm，否则 systemone
        if (!prefs.contains(KEY_PRESET)) {
            prefs.edit().putString(
                KEY_PRESET,
                if (BuildConfig.DEFAULT_ANALYSIS_PROTOCOL == PROTOCOL_OPENAI)
                    PRESET_GLM else PRESET_SYSTEMONE
            ).apply()
        }
        // 旧版 systemtwo 预设已下线，归并到 custom
        if (preset == "systemtwo") preset = PRESET_CUSTOM

        prefs.edit().putBoolean(KEY_SEEDED_V2, true).apply()
    }

    /**
     * v3 播种（S1/S2 分组改造）：对新老安装各跑一次。
     *  - S1 类型：按现有 systemone 槽位地址推断（空或本机默认地址 -> 本地启发式）；
     *  - S2 槽位（复用 glm 槽位键）：已有用户配置优先，否则用 BuildConfig 预置
     *   （test 包 GLM），open 包为空 -> S2 默认关闭；
     *  - S2 服务商按地址域名推断，仅作 UI 回填，不影响协议（S2 固定 openai）。
     */
    private fun seedS1S2Once() {
        if (prefs.getBoolean(KEY_SEEDED_V3, false)) return

        if (!prefs.contains(KEY_S1_TYPE)) {
            val s1Url = loadSlot(PRESET_SYSTEMONE).url
            prefs.edit().putString(
                KEY_S1_TYPE,
                if (s1Url.isBlank() || s1Url == DEFAULT_URL_SYSTEMONE)
                    S1_TYPE_LOCAL else S1_TYPE_REMOTE
            ).apply()
        }

        val glmSlot = loadSlot(PRESET_GLM)
        val s2Url = glmSlot.url.ifBlank { BuildConfig.DEFAULT_ANALYSIS_URL }
        val s2Key = glmSlot.key.ifBlank { BuildConfig.DEFAULT_ANALYSIS_KEY }
        val s2Model = glmSlot.model.ifBlank {
            BuildConfig.DEFAULT_ANALYSIS_MODEL.ifBlank { S2_GLM_MODEL }
        }
        if (s2Url.isNotBlank()) {
            saveSlot(PRESET_GLM, SlotConfig(s2Url, s2Key, s2Model))
        }
        if (!prefs.contains(KEY_S2_PROVIDER)) {
            prefs.edit().putString(
                KEY_S2_PROVIDER,
                when {
                    s2Url.contains("bigmodel.cn") -> S2_PROVIDER_GLM
                    s2Url.contains("typesafe.ai") -> S2_PROVIDER_JEV
                    s2Url.isBlank() -> S2_PROVIDER_GLM
                    else -> S2_PROVIDER_CUSTOM
                }
            ).apply()
        }
        if (!prefs.contains(KEY_S2_ENABLED)) {
            prefs.edit().putBoolean(KEY_S2_ENABLED, s2Url.isNotBlank()).apply()
        }

        prefs.edit().putBoolean(KEY_SEEDED_V3, true).apply()
    }

    /**
     * v4 播种（test 包模型配置开箱即用）：对新老安装各跑一次（v3 已播过的老装机
     * 也会进入），只补仍为空的字段，不覆盖用户已改的非空值。
     *  - systemone / glm 槽：单字段为空时用 BuildConfig 默认值补齐；
     *  - 新增 S2 JEV 独立槽位（PRESET_S2_JEV）：空则播种 S2_JEV_URL/JEV key/
     *    S2_JEV_MODEL，控制台切换 S2 服务商到 JEV 时整组回填、免手填密钥；
     *  - open 包 BuildConfig 默认值全空：不播种任何值，直接标记完成（零预置不变）。
     */
    private fun seedS1S2V4Once() {
        if (prefs.getBoolean(KEY_SEEDED_V4, false)) return

        val hasDefaults = BuildConfig.DEFAULT_LAYA_KEY.isNotEmpty() ||
            BuildConfig.DEFAULT_ANALYSIS_KEY.isNotEmpty()
        if (hasDefaults) {
            val s1 = loadSlot(PRESET_SYSTEMONE)
            if (BuildConfig.DEFAULT_LAYA_URL.isNotEmpty()) {
                saveSlot(
                    PRESET_SYSTEMONE,
                    SlotConfig(
                        s1.url.ifBlank { BuildConfig.DEFAULT_LAYA_URL },
                        s1.key.ifBlank { BuildConfig.DEFAULT_LAYA_KEY },
                        s1.model.ifBlank { DEFAULT_MODEL_JEV }
                    )
                )
            }

            val glm = loadSlot(PRESET_GLM)
            if (BuildConfig.DEFAULT_ANALYSIS_URL.isNotEmpty()) {
                saveSlot(
                    PRESET_GLM,
                    SlotConfig(
                        glm.url.ifBlank { BuildConfig.DEFAULT_ANALYSIS_URL },
                        glm.key.ifBlank { BuildConfig.DEFAULT_ANALYSIS_KEY },
                        glm.model.ifBlank {
                            BuildConfig.DEFAULT_ANALYSIS_MODEL.ifBlank { S2_GLM_MODEL }
                        }
                    )
                )
            }

            val jev = loadSlot(PRESET_S2_JEV)
            saveSlot(
                PRESET_S2_JEV,
                SlotConfig(
                    jev.url.ifBlank { S2_JEV_URL },
                    jev.key.ifBlank { BuildConfig.DEFAULT_LAYA_KEY },
                    jev.model.ifBlank { S2_JEV_MODEL }
                )
            )

            // S2 开关兜底：键不存在且地址具备时默认开（v3 已兜，双保险）
            if (!prefs.contains(KEY_S2_ENABLED) &&
                loadSlot(PRESET_GLM).url.isNotBlank()
            ) {
                prefs.edit().putBoolean(KEY_S2_ENABLED, true).apply()
            }

            // 总开关兜底：全新装机的 test 包默认开（键不存在才写；
            // 用户手动关过的老装机不覆盖——否则「立即分析」被静默跳过，
            // 与零配置可分析的目标矛盾）
            if (!prefs.contains(KEY_ENABLED)) {
                prefs.edit().putBoolean(KEY_ENABLED, true).apply()
            }
        }

        prefs.edit().putBoolean(KEY_SEEDED_V4, true).apply()
    }

    /** S2 服务商对应的槽位：JEV 有独立槽位，GLM/自定义/本地共用 glm 槽位 */
    fun s2SlotPreset(provider: String): String =
        if (provider == S2_PROVIDER_JEV) PRESET_S2_JEV else PRESET_GLM

    // ---------- System 1 / System 2 分组配置 ----------

    var s1Type: String
        get() = prefs.getString(KEY_S1_TYPE, S1_TYPE_LOCAL).orEmpty()
        set(value) = prefs.edit().putString(KEY_S1_TYPE, value).apply()

    var s2Enabled: Boolean
        get() = prefs.getBoolean(KEY_S2_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_S2_ENABLED, value).apply()

    var s2Provider: String
        get() = prefs.getString(KEY_S2_PROVIDER, S2_PROVIDER_GLM).orEmpty()
        set(value) = prefs.edit().putString(KEY_S2_PROVIDER, value).apply()

    /** System 1 槽位（固定 systemone，laya 协议） */
    fun s1Slot(): SlotConfig = loadSlot(PRESET_SYSTEMONE)

    /** System 2 槽位（按服务商取：JEV 独立槽位，其余 glm 槽位，openai 协议）；开关关闭或地址为空视为未配置 */
    fun s2Slot(): SlotConfig? {
        if (!s2Enabled) return null
        return loadSlot(s2SlotPreset(s2Provider)).takeIf { it.url.isNotBlank() }
    }

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    var preset: String
        get() = prefs.getString(KEY_PRESET, PRESET_SYSTEMONE).orEmpty()
        set(value) = prefs.edit().putString(KEY_PRESET, value).apply()

    /** 当前生效协议：systemone 固定 laya，glm 固定 openai，custom 取手选值 */
    val protocol: String
        get() = when (preset) {
            PRESET_SYSTEMONE -> PROTOCOL_LAYA
            PRESET_GLM -> PROTOCOL_OPENAI
            else -> customProtocol
        }

    var customProtocol: String
        get() = prefs.getString(KEY_CUSTOM_PROTOCOL, PROTOCOL_OPENAI).orEmpty()
        set(value) = prefs.edit().putString(KEY_CUSTOM_PROTOCOL, value).apply()

    /** laya 走公网 nginx 时的 Basic 凭据（全局，不属于任何槽位） */
    var basicUser: String
        get() = prefs.getString(KEY_BASIC_USER, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_BASIC_USER, value.trim()).apply()

    var basicPass: String
        get() = prefs.getString(KEY_BASIC_PASS, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_BASIC_PASS, value).apply()

    var intervalMinutes: Long
        get() = prefs.getLong(KEY_INTERVAL_MINUTES, DEFAULT_INTERVAL_MINUTES)
        set(value) = prefs.edit().putLong(KEY_INTERVAL_MINUTES, value).apply()

    var reminderEnabled: Boolean
        get() = prefs.getBoolean(KEY_REMINDER_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_REMINDER_ENABLED, value).apply()

    var reminderLeadMinutes: Long
        get() = prefs.getLong(KEY_REMINDER_LEAD_MINUTES, 15L)
        set(value) = prefs.edit().putLong(KEY_REMINDER_LEAD_MINUTES, value).apply()

    var lastAnalysisTime: Long
        get() = prefs.getLong(KEY_LAST_ANALYSIS_TIME, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_ANALYSIS_TIME, value).apply()

    var lastAnalysisResult: String
        get() = prefs.getString(KEY_LAST_ANALYSIS_RESULT, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_LAST_ANALYSIS_RESULT, value).apply()

    // ---------- 预设槽位 ----------

    fun loadSlot(preset: String): SlotConfig = SlotConfig(
        url = prefs.getString(slotKey(preset, "url"), "").orEmpty(),
        key = prefs.getString(slotKey(preset, "key"), "").orEmpty(),
        model = prefs.getString(slotKey(preset, "model"), "").orEmpty()
    )

    fun saveSlot(preset: String, slot: SlotConfig) {
        prefs.edit()
            .putString(slotKey(preset, "url"), slot.url.trim())
            .putString(slotKey(preset, "key"), slot.key.trim())
            .putString(slotKey(preset, "model"), slot.model.trim())
            .apply()
    }

    /** 当前预设的连接参数（Worker 与测试连接统一从这里取） */
    fun activeSlot(): SlotConfig = loadSlot(preset)
}
