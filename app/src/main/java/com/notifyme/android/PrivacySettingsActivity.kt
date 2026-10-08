// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast

/** 隐私脱敏设置页（自 ConsoleActivity 拆出，行为不变）：三模式按钮 + 规则/白名单对话框 + 预览入口。 */
class PrivacySettingsActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_privacy_settings)

        // 标题栏返回
        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }

        setupPrivacySection()
    }

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
            // readRecent 是整文件解析，1000 条也要读整个 messages.jsonl：
            // 放后台线程，避免历史一大点击就卡住
            Thread {
                val keys = mutableListOf<ConvKey>()
                MessageStore.readRecent(this, 1000).forEach { m ->
                    if (m.conversation.isNotEmpty() && m.convKey !in keys) keys.add(m.convKey)
                }
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    if (keys.isEmpty()) {
                        Toast.makeText(this, R.string.privacy_bypass_empty, Toast.LENGTH_SHORT).show()
                        return@runOnUiThread
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
            }.start()
        }

        btnPreview.setOnClickListener {
            startActivity(Intent(this, RedactorPreviewActivity::class.java))
        }

        refresh()
    }
}
