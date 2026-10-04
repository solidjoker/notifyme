// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import android.animation.ValueAnimator
import android.app.Notification
import android.app.Service
import android.content.Intent
import android.content.res.Resources
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import kotlin.math.abs

/**
 * 悬浮层服务（M9/W2）：维护常驻悬浮球、显示重要消息悬浮卡、展开待办面板。
 *
 * 窗口全部 TYPE_APPLICATION_OVERLAY；Android 14 起 FGS 类型 specialUse。
 * 触发与权限判定在 [OverlayManager]，无权限时不会走到这里（降级 heads-up）。
 */
class OverlayService : Service() {

    companion object {
        const val ACTION_SHOW_CARD = "com.notifyme.android.overlay.SHOW_CARD"

        /** 仅按当前配置挂/摘悬浮球（控制台开关切换时用） */
        const val ACTION_RECONCILE = "com.notifyme.android.overlay.RECONCILE"

        const val EXTRA_PKG = "extra_pkg"
        const val EXTRA_CONVERSATION = "extra_conversation"
        const val EXTRA_SENDER = "extra_sender"
        const val EXTRA_TEXT = "extra_text"
        const val EXTRA_APP_LABEL = "extra_app_label"
        const val EXTRA_CASE_ID = "extra_case_id"

        private const val NOTIF_ID = 0xF10A
        private const val CLICK_SLOP_DP = 8
        private const val BALL_SIZE_DP = 48
    }

    private lateinit var wm: WindowManager
    private val handler = Handler(Looper.getMainLooper())

    private data class CardEntry(
        val view: View,
        val params: WindowManager.LayoutParams,
        val autoRemove: Runnable?
    )

    private val activeCards = mutableListOf<CardEntry>()

    private var ballView: View? = null
    private var ballParams: WindowManager.LayoutParams? = null
    private var panelView: View? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        OverlayManager.ensureChannels(this)
        startForegroundCompat()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW_CARD -> showCardFromIntent(intent)
            ACTION_RECONCILE, null -> reconcileBall()
        }
        // 不要求系统重启后自动重建：由触发路径重新拉起，避免无配置时复活
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        activeCards.forEach { runCatching { wm.removeView(it.view) } }
        panelView?.let { runCatching { wm.removeView(it) } }
        ballView?.let { runCatching { wm.removeView(it) } }
        super.onDestroy()
    }

    // ---------------- 悬浮卡 ----------------

    private fun showCardFromIntent(intent: Intent) {
        val pkg = intent.getStringExtra(EXTRA_PKG)
            ?.takeIf { it.isNotBlank() } ?: AppSourceRegistry.PKG_WECHAT
        val conversation = intent.getStringExtra(EXTRA_CONVERSATION).orEmpty()
        val sender = intent.getStringExtra(EXTRA_SENDER).orEmpty()
        val text = intent.getStringExtra(EXTRA_TEXT).orEmpty()
        val appLabel = intent.getStringExtra(EXTRA_APP_LABEL).orEmpty()
        val caseId = intent.getStringExtra(EXTRA_CASE_ID).orEmpty()

        val view = LayoutInflater.from(this)
            .inflate(R.layout.view_overlay_card, null)

        val appTag = appLabel.ifEmpty { pkgLabel(pkg) }
        view.findViewById<TextView>(R.id.tvCardTitle).text =
            "$appTag · $conversation"
        val contentView = view.findViewById<TextView>(R.id.tvCardContent)
        contentView.text = if (sender.isNotEmpty()) "$sender: $text" else text

        val params = baseOverlayParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            horizontalMargin = 0.04f
            y = statusBarHeight() + dp(10) + activeCards.size * dp(96)
        }
        wm.addView(view, params)

        val config = OverlayConfig.get(this)
        val autoRemove = if (config.autoDismissSeconds > 0) {
            Runnable { removeCard(view) }.also {
                handler.postDelayed(it, config.autoDismissSeconds * 1000L)
            }
        } else null

        activeCards += CardEntry(view, params, autoRemove)

        ensureBall()

        view.findViewById<Button>(R.id.btnCardDismiss).setOnClickListener {
            if (caseId.isNotEmpty()) AnalysisStore.deleteCase(this, caseId)
            removeCard(view)
        }
        view.findViewById<Button>(R.id.btnCardOpen).setOnClickListener {
            openConversation(ConvKey(pkg, conversation))
            removeCard(view)
        }
    }

    private fun removeCard(view: View) {
        val entry = activeCards.firstOrNull { it.view === view } ?: return
        entry.autoRemove?.let { handler.removeCallbacks(it) }
        runCatching { wm.removeView(view) }
        activeCards.remove(entry)
        // 重排剩余卡片的纵向位置
        activeCards.forEachIndexed { i, c ->
            c.params.y = statusBarHeight() + dp(10) + i * dp(96)
            runCatching { wm.updateViewLayout(c.view, c.params) }
        }
        maybeStopSelf()
    }

    /** 球未启用、卡片清空后服务没有继续存在的理由。 */
    private fun maybeStopSelf() {
        if (activeCards.isEmpty() && ballView == null) stopSelf()
    }

    // ---------------- 悬浮球 ----------------

    /** 卡片事件到来时，若配置允许则确保球已挂上。 */
    private fun ensureBall() {
        if (ballView != null) return
        if (!OverlayConfig.get(this).ballEnabled) return

        val view = LayoutInflater.from(this).inflate(R.layout.view_overlay_ball, null)
        val size = dp(BALL_SIZE_DP)
        val params = baseOverlayParams(size, size).apply {
            gravity = Gravity.TOP or Gravity.START
            x = displayWidth() - size - dp(6)
            y = displayHeight() / 3
        }
        wm.addView(view, params)
        ballView = view
        ballParams = params
        bindBallTouch(view, params)
    }

    /**
     * 按当前配置挂/摘球：配置关闭且球在 → 移除；配置开启且无球 → 挂上。
     * 控制台每次切换开关都会下发 [ACTION_RECONCILE]。
     */
    private fun reconcileBall() {
        val enabled = OverlayConfig.get(this).ballEnabled
        if (!enabled && ballView != null) {
            runCatching { wm.removeView(ballView!!) }
            ballView = null
            ballParams = null
            removePanel()
            maybeStopSelf()
        } else if (enabled && ballView == null) {
            ensureBall()
        }
    }

    private fun bindBallTouch(view: View, params: WindowManager.LayoutParams) {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false

        view.setOnTouchListener { v, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX; downY = ev.rawY
                    startX = params.x; startY = params.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downX
                    val dy = ev.rawY - downY
                    if (abs(dx) > dp(CLICK_SLOP_DP) || abs(dy) > dp(CLICK_SLOP_DP)) moved = true
                    params.x = (startX + dx).toInt().coerceIn(0, (displayWidth() - dp(BALL_SIZE_DP)).coerceAtLeast(0))
                    params.y = (startY + dy).toInt().coerceIn(0, (displayHeight() - dp(BALL_SIZE_DP)).coerceAtLeast(0))
                    wm.updateViewLayout(view, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        togglePanel()
                    } else {
                        snapToEdge(view, params)
                    }
                    true
                }
                else -> false
            }
        }
    }

    /** 松手吸附到最近的左右边缘。 */
    private fun snapToEdge(view: View, params: WindowManager.LayoutParams) {
        val target = if (params.x + dp(BALL_SIZE_DP) / 2 < displayWidth() / 2)
            dp(6)
        else displayWidth() - dp(BALL_SIZE_DP) - dp(6)
        val anim = ValueAnimator.ofInt(params.x, target)
        anim.duration = 200
        anim.addUpdateListener {
            params.x = it.animatedValue as Int
            runCatching { wm.updateViewLayout(view, params) }
        }
        anim.start()
    }

    // ---------------- 待办面板 ----------------

    private fun togglePanel() {
        if (panelView != null) {
            removePanel()
            return
        }
        val view = LayoutInflater.from(this).inflate(R.layout.view_overlay_panel, null)
        val params = baseOverlayParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER
            horizontalMargin = 0.12f
            verticalMargin = 0.2f
        }

        val todos = AnalysisStore.readRecent(this, 500)
            .filter { it.s1NeedAction }
            .take(8)
        val container = view.findViewById<LinearLayout>(R.id.llPanelTodos)
        if (todos.isEmpty()) {
            container.addView(buildPanelRow(getString(R.string.overlay_panel_empty), null))
        } else {
            todos.forEach { rec ->
                val summary = rec.s2Summary.ifEmpty { rec.s1Topic }
                container.addView(
                    buildPanelRow("${rec.conversation} · $summary") {
                        openConversation(rec.convKey)
                        removePanel()
                    }
                )
            }
        }
        view.findViewById<Button>(R.id.btnPanelOpenApp).setOnClickListener {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            removePanel()
        }

        wm.addView(view, params)
        panelView = view
    }

    private fun buildPanelRow(text: String, onClick: (() -> Unit)?): View =
        TextView(this).apply {
            this.text = "• $text"
            setTextColor(getColor(R.color.text_primary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(6), 0, dp(6))
            if (onClick != null) setOnClickListener { onClick() }
        }

    private fun removePanel() {
        panelView?.let { runCatching { wm.removeView(it) } }
        panelView = null
    }

    // ---------------- 工具 ----------------

    private fun openConversation(key: ConvKey) {
        startActivity(
            ConversationActivity.createIntent(this, key)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private fun pkgLabel(pkg: String): String = when (pkg) {
        AppSourceRegistry.PKG_WECHAT -> "微信"
        AppSourceRegistry.PKG_FEISHU -> "飞书"
        AppSourceRegistry.PKG_DINGTALK -> "钉钉"
        else -> pkg
    }

    private fun baseOverlayParams(width: Int, height: Int): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        return WindowManager.LayoutParams(
            width, height, type,
            // 不抢输入焦点；按钮仍可点击
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
    }

    private fun startForegroundCompat() {
        val notification: Notification = NotificationCompat.Builder(this, OverlayManager.CHANNEL_SERVICE)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("悬浮通知运行中")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIF_ID, notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), Resources.getSystem().displayMetrics
    ).toInt()

    private fun displayWidth(): Int = Resources.getSystem().displayMetrics.widthPixels
    private fun displayHeight(): Int = Resources.getSystem().displayMetrics.heightPixels

    private fun statusBarHeight(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else 0
    }
}
