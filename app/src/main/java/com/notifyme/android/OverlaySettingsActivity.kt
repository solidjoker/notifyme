// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.Switch
import android.widget.TextView

/**
 * 悬浮通知设置页（自 ConsoleActivity 拆出，行为不变）：
 * 卡片/球两个开关 + 悬浮窗权限按钮 + debug 冒烟入口。
 * 开关切换即写配置并下发 OverlayService reconcile；无悬浮窗权限时触发链路
 * 自动降级为 heads-up（OverlayManager 内部判断）。
 */
class OverlaySettingsActivity : Activity() {

    /** 从悬浮窗权限设置页返回后刷新按钮状态（setupOverlaySection 中赋值） */
    private var refreshOverlayPermission: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_overlay_settings)

        // 标题栏返回
        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }

        setupOverlaySection()
    }

    override fun onResume() {
        super.onResume()
        refreshOverlayPermission?.invoke()
    }

    private fun setupOverlaySection() {
        val switchCards = findViewById<Switch>(R.id.switchOverlayCards)
        val switchBall = findViewById<Switch>(R.id.switchOverlayBall)
        val btnPermission = findViewById<Button>(R.id.btnOverlayPermission)
        val btnDebugTest = findViewById<Button>(R.id.btnOverlayDebugTest)
        val btnEngineSmoke = findViewById<Button>(R.id.btnEngineSmoke)

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

        // 仅 debug：端侧推理冒烟页
        if (BuildConfig.DEBUG) btnEngineSmoke.visibility = View.VISIBLE
        btnEngineSmoke.setOnClickListener {
            startActivity(Intent(this, EngineSmokeActivity::class.java))
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
