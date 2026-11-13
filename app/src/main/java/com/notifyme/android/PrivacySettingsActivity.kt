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

    /** 由 [setupPrivacySection] 装上的刷新闭包：onResume 复用，从预览页返回时标签不会停在旧态。 */
    private var refreshUi: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_privacy_settings)

        // 标题栏返回
        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }

        setupPrivacySection()
    }

    override fun onResume() {
        super.onResume()
        // 从预览页回来时，盐可能已经在那里被首次生成（PreviewActivity 走别名会 ensure），
        // 不刷新的话「别名盐：未生成」会一直挂着，和实际落盘状态矛盾。
        refreshUi?.invoke()
    }

    /** 隐私卡片初始化：三模式按钮 + 规则/白名单对话框 + 预览入口。 */
    private fun setupPrivacySection() {
        val btnOff = findViewById<Button>(R.id.btnPrivacyOff)
        val btnCloud = findViewById<Button>(R.id.btnPrivacyCloud)
        val btnLocal = findViewById<Button>(R.id.btnPrivacyLocal)
        val tvSummary = findViewById<TextView>(R.id.tvPrivacySummary)
        val btnRules = findViewById<Button>(R.id.btnPrivacyRules)
        val btnBypass = findViewById<Button>(R.id.btnPrivacyBypass)
        val btnAliasSalt = findViewById<Button>(R.id.btnPrivacyAliasSalt)
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
            btnRules.text = templateText(
                R.string.privacy_rules_btn, state.enabledRules.size, RedactorRules.ALL.size
            )
            btnBypass.text = templateText(R.string.privacy_bypass_btn, state.bypassConvIds.size)
            btnAliasSalt.text = getString(
                if (AliasSaltStore.hasSalt(this)) R.string.privacy_alias_btn_set
                else R.string.privacy_alias_btn_unset
            )
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

        // 白名单多选：候选 = 消息库里出现过的会话（按会话各留一窗，安静的会话也在）
        btnBypass.setOnClickListener {
            // 解析整窗要读 messages.jsonl 尾部 20000 行：放后台线程，避免点击就卡住
            Thread {
                // 不能再用 readRecent(1000)：跨会话尾部窗口会被活跃会话占满，
                // 安静会话根本进不了白名单候选（口径见 [FeedWindowCore]）。
                val keys = MessageStore.feedGroups(this).keys()
                    .filter { it.conversation.isNotEmpty() }
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

        // 别名盐重置：设备被他人拿过 / 怀疑盐外泄时的补救手段。
        // 没生成过盐就不必重置（真需要时首次脱敏会自动生成），给个提示即可。
        btnAliasSalt.setOnClickListener {
            if (!AliasSaltStore.hasSalt(this)) {
                Toast.makeText(this, R.string.privacy_alias_reset_empty, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            android.app.AlertDialog.Builder(this)
                .setTitle(R.string.privacy_alias_reset_title)
                .setMessage(R.string.privacy_alias_reset_msg)
                .setNegativeButton(R.string.common_cancel, null)
                .setPositiveButton(R.string.privacy_alias_reset) { _, _ ->
                    AliasSaltStore.reset(this)
                    refresh()
                    Toast.makeText(this, R.string.privacy_alias_reset_done, Toast.LENGTH_SHORT).show()
                }
                .show()
        }

        btnPreview.setOnClickListener {
            startActivity(Intent(this, RedactorPreviewActivity::class.java))
        }

        refreshUi = { refresh() }
        refresh()
    }
}
