// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** 日历提醒设置页（自 ConsoleActivity 拆出，行为不变）：开关 + 提前量 + 已创建提醒列表入口。 */
class ReminderSettingsActivity : Activity() {

    companion object {
        /** 日历读写运行时权限请求码 */
        private const val REQ_CALENDAR = 43
    }

    private lateinit var analysisConfig: AnalysisConfig

    private lateinit var switchReminderEnabled: Switch
    private lateinit var spinnerReminderLead: Spinner

    /** Spinner 初始化期屏蔽 listener 回调，避免回填时被当成用户修改 */
    private var spinnerInitializing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reminder_settings)

        analysisConfig = AnalysisConfig(this)

        // 标题栏返回
        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }

        switchReminderEnabled = findViewById(R.id.switchReminderEnabled)
        spinnerReminderLead = findViewById(R.id.spinnerReminderLead)

        setupReminderSection()
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
}
