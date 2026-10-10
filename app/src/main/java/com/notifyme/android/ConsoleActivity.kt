// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Button
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
    }

    // 保活状态相关视图
    private lateinit var tvKeepAliveStatus: TextView
    private lateinit var btnBatteryWhitelist: Button

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

        // 快捷按钮：全部折叠 / 全部展开（与主页 ☰ 菜单同功能，方便从控制台一键操作）
        findViewById<Button>(R.id.btnCollapseAll).setOnClickListener {
            val keys = MessageStore.readRecent(this, 500).map { it.convKey }.toSet()
            CollapseStore.setAll(this, keys, true)
            Toast.makeText(this, R.string.quick_collapse_done, Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btnExpandAll).setOnClickListener {
            CollapseStore.setAll(this, emptySet(), false)
            CollapseStore.clearDates(this)
            Toast.makeText(this, R.string.quick_expand_done, Toast.LENGTH_SHORT).show()
        }
        // 快捷按钮：回到主页（全部折叠/展开已在主页标题栏，此处仅提供导航入口）
        findViewById<Button>(R.id.btnGoHome).setOnClickListener {
            finish()
        }

    }

    override fun onResume() {
        super.onResume()
        refreshKeepAliveStatus()
    }

    override fun onPause() {
        tvKeepAliveStatus.removeCallbacks(refreshKeepAliveOnce)
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
}
