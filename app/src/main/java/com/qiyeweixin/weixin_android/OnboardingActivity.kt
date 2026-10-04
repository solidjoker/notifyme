// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.qiyeweixin.weixin_android

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationManagerCompat

/**
 * 初次使用向导：三步分页 + 底部固定导航栏（FrameLayout 切页，不引 ViewPager）。
 *  1. 欢迎 / 原理说明
 *  2. 对接微信：引导授予通知监听权限，未授予时拦截「下一步」
 *  3. 完成：勾选「不再显示」后写 app_state.onboarding_done
 *
 * 翻页能力显式可见：底部导航栏固定「← 上一步 / 第 N / 3 步 / 下一步 →」，
 * 首页上一步置灰，末页下一步变「完成」；返回键在第 2/3 页回上一页。
 *
 * 首次启动由 MainActivity 自动弹出；之后可从控制台重新打开。
 */
class OnboardingActivity : Activity() {

    companion object {
        const val PREFS_NAME = "app_state"
        const val KEY_ONBOARDING_DONE = "onboarding_done"

        /** 是否需要弹出向导（供 MainActivity 首启判断）。 */
        fun shouldShow(context: Context): Boolean {
            return !context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_ONBOARDING_DONE, false)
        }
    }

    private lateinit var pages: List<LinearLayout>
    private lateinit var tvPermStatus: TextView
    private lateinit var btnPrev: Button
    private lateinit var btnNext: Button
    private lateinit var tvPageIndicator: TextView
    private var currentPage = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)

        pages = listOf(
            findViewById(R.id.pageWelcome),
            findViewById(R.id.pageConnect),
            findViewById(R.id.pageDone)
        )
        tvPermStatus = findViewById(R.id.tvOnboardingPermStatus)
        btnPrev = findViewById(R.id.btnPrev)
        btnNext = findViewById(R.id.btnNext)
        tvPageIndicator = findViewById(R.id.tvPageIndicator)

        findViewById<Button>(R.id.btnOpenListenerSettings).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        btnPrev.setOnClickListener {
            if (currentPage > 0) showPage(currentPage - 1)
        }

        btnNext.setOnClickListener {
            when (currentPage) {
                // 第 2 页（索引 1）：未授予通知监听权限则拦截并提示
                1 -> {
                    if (isListenerEnabled()) {
                        showPage(2)
                    } else {
                        tvPermStatus.text = getString(R.string.onboarding_step2_pending)
                    }
                }
                // 末页：右按钮即「完成」
                pages.size - 1 -> finishOnboarding()
                else -> showPage(currentPage + 1)
            }
        }

        showPage(0)
    }

    override fun onResume() {
        super.onResume()
        // 从系统设置页返回时刷新授权状态
        refreshPermissionStatus()
    }

    /** 返回键：第 2/3 页回上一页而不是直接退出；第 1 页正常退出。 */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (currentPage > 0) {
            showPage(currentPage - 1)
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    /** 统一切页：更新分页可见性、页码指示、按钮状态与文案。 */
    private fun showPage(index: Int) {
        currentPage = index
        pages.forEachIndexed { i, page ->
            page.visibility = if (i == index) View.VISIBLE else View.GONE
        }
        tvPageIndicator.text =
            getString(R.string.onboarding_page_indicator, index + 1, pages.size)
        btnPrev.isEnabled = index > 0
        btnNext.text = getString(
            if (index == pages.size - 1) R.string.btn_finish else R.string.btn_next
        )
        if (index == 1) refreshPermissionStatus()
    }

    /** 「完成」：勾选「不再显示」则写标记，随后退出向导。 */
    private fun finishOnboarding() {
        val noMore = findViewById<CheckBox>(R.id.cbNoMore).isChecked
        if (noMore) {
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_ONBOARDING_DONE, true).apply()
        }
        finish()
    }

    private fun refreshPermissionStatus() {
        tvPermStatus.text = getString(
            if (isListenerEnabled()) R.string.permission_granted
            else R.string.permission_missing
        )
    }

    private fun isListenerEnabled(): Boolean {
        return NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
    }
}
