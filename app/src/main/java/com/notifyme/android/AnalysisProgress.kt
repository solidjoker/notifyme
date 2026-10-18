// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import java.util.concurrent.CopyOnWriteArraySet

/** 分析运行阶段（M15）：决定进度文案，以及进度条是确定态还是不确定态。 */
enum class AnalysisStage {
    /** 排队出队后的准备（读配置、拼窗口） */
    PREPARING,

    /** 首次加载本地 GGUF（几百毫秒到数秒） */
    LOADING_MODEL,

    /** 端侧 prefill：把会话文本读进上下文 */
    LOCAL_PREFILL,

    /** 端侧 decode：逐 token 生成结果 */
    LOCAL_DECODE,

    /** 端侧判定题（Laya TFLite：4 道题逐题推理，无 token 级进度） */
    LOCAL_QUESTIONS,

    /** 走云端接口（无 token 级进度，只报已用时间） */
    REMOTE,

    /** 落库/组装结果 */
    SAVING
}

/**
 * 一次分析运行中的实时状态（M15）。
 *
 * 只存在于内存：Worker 发布、Activity 订阅；进程被杀就自然消失，
 * 与 WorkManager 的进度上报（要写 Room）无关，因此可以 500ms 一次地刷。
 *
 * @param convKeyId   当前正在分析的会话 [ConvKey.id]
 * @param caseIndex   本批第几个（1 起），[caseTotal] 是本批总数
 * @param token       已生成 token（仅 [AnalysisStage.LOCAL_DECODE] 有意义）
 * @param maxToken    采样上限（不是预期输出长度）
 * @param startedAt   [SystemClock.elapsedRealtime] 起点，用于算已用时间
 */
data class AnalysisRunState(
    val convKeyId: String,
    val conversation: String,
    val stage: AnalysisStage,
    val branch: String = "",
    val caseIndex: Int = 1,
    val caseTotal: Int = 1,
    val token: Int = 0,
    val maxToken: Int = 0,
    val startedAt: Long = SystemClock.elapsedRealtime()
) {
    /** 已用毫秒数（调用方给 now 便于测试）。 */
    fun elapsedMs(now: Long = SystemClock.elapsedRealtime()): Long =
        (now - startedAt).coerceAtLeast(0L)

    /**
     * 当前会话完成比例 0..1；无法估算时 null（prefill/云端/加载阶段）。
     *
     * decode 阶段用「已生成/采样上限」：这是唯一客观可得的比例，但上限是
     * 预算不是预期值，模型常提前 EOG，所以 UI 同时显示真实 token 数。
     */
    val caseFraction: Float?
        get() = when {
            // decode 用「已生成/采样上限」；端侧判定题用「已答/总题数」
            stage == AnalysisStage.LOCAL_DECODE && maxToken > 0 ->
                (token.toFloat() / maxToken).coerceIn(0f, 1f)
            stage == AnalysisStage.LOCAL_QUESTIONS && maxToken > 0 ->
                (token.toFloat() / maxToken).coerceIn(0f, 1f)
            else -> null
        }

    /** 整批总进度百分比 0..100；无法估算时 null。 */
    fun percent(): Int? {
        val fraction = caseFraction ?: return null
        val total = caseTotal.coerceAtLeast(1)
        val done = (caseIndex - 1).coerceAtLeast(0)
        val overall = (done + fraction) / total
        return (overall * 100f).toInt().coerceIn(0, 100)
    }
}

/**
 * 运行状态广播（M15）：单进程内单例，Worker 写、Activity 读。
 *
 * 监听回调统一派发到主线程（UI 直接改控件）；同一帧内多次 publish 会合并，
 * 订阅方永远拿到最新值，不会出现旧状态覆盖新状态。
 */
object AnalysisProgressBus {

    fun interface Listener {
        fun onStateChanged(state: AnalysisRunState?)
    }

    private val listeners = CopyOnWriteArraySet<Listener>()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    var state: AnalysisRunState? = null
        private set

    private val dispatch = Runnable {
        val snapshot = state
        listeners.forEach { runCatching { it.onStateChanged(snapshot) } }
    }

    fun addListener(listener: Listener) {
        listeners.add(listener)
        mainHandler.post { runCatching { listener.onStateChanged(state) } }
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    /** 发布新状态；传 null 表示本轮分析结束（UI 收起进度）。 */
    fun publish(newState: AnalysisRunState?) {
        // 只在阶段切换时打一行：支持/真机排查「进度到哪一步停住」用，token 级不刷日志
        if (newState?.stage != state?.stage) {
            android.util.Log.i(
                "AnalysisProgress",
                "stage=" + (newState?.stage?.name ?: "idle") +
                    " case=" + (newState?.caseIndex ?: 0) + "/" + (newState?.caseTotal ?: 0) +
                    " progress=" + (newState?.token ?: 0) + "/" + (newState?.maxToken ?: 0)
            )
        }
        state = newState
        mainHandler.removeCallbacks(dispatch)
        mainHandler.post(dispatch)
    }
}

/** 进度展示：文案拼装 + 控件渲染（三处 UI 共用，避免各写一套）。 */
object AnalysisProgressUi {

    /** 已用时间："12秒" / "1分05秒"（纯函数，可单测）。 */
    fun formatElapsed(elapsedMs: Long): String {
        val totalSeconds = (elapsedMs / 1000L).coerceAtLeast(0L)
        if (totalSeconds < 60L) return "${totalSeconds}秒"
        val minutes = totalSeconds / 60L
        val seconds = (totalSeconds % 60L).toString().padStart(2, '0')
        return "${minutes}分${seconds}秒"
    }

    /** 进度文案：阶段名 +（可算时）百分比 + 真实 token 数 + 已用时间。 */
    fun text(context: Context, state: AnalysisRunState): String {
        val elapsed = formatElapsed(state.elapsedMs())
        val casePrefix = if (state.caseTotal > 1) {
            context.getString(R.string.analysis_progress_case, state.caseIndex, state.caseTotal) + " · "
        } else ""
        return when (state.stage) {
            AnalysisStage.PREPARING ->
                casePrefix + context.getString(R.string.analysis_progress_elapsed,
                    context.getString(R.string.analysis_stage_preparing), elapsed)

            AnalysisStage.LOADING_MODEL ->
                casePrefix + context.getString(R.string.analysis_progress_elapsed,
                    context.getString(R.string.analysis_stage_loading), elapsed)

            AnalysisStage.LOCAL_PREFILL ->
                casePrefix + context.getString(
                    R.string.analysis_progress_prefill,
                    context.getString(R.string.analysis_stage_prefill),
                    state.token, state.maxToken, elapsed
                )

            AnalysisStage.LOCAL_DECODE -> {
                val percent = state.percent()
                if (percent == null) {
                    casePrefix + context.getString(R.string.analysis_progress_elapsed,
                        context.getString(R.string.analysis_stage_decode), elapsed)
                } else {
                    casePrefix + context.getString(
                        R.string.analysis_progress_decode,
                        context.getString(R.string.analysis_stage_decode),
                        percent, state.token, elapsed
                    )
                }
            }

            AnalysisStage.LOCAL_QUESTIONS ->
                casePrefix + context.getString(
                    R.string.analysis_progress_questions,
                    context.getString(R.string.analysis_stage_questions),
                    state.token, state.maxToken, elapsed
                )

            AnalysisStage.REMOTE ->
                casePrefix + context.getString(R.string.analysis_progress_elapsed,
                    context.getString(R.string.analysis_stage_remote), elapsed)

            AnalysisStage.SAVING -> context.getString(R.string.analysis_stage_saving)
        }
    }

    /**
     * 把状态渲染到「文字 + 进度条」：
     * state 为 null 时两者隐藏；可算百分比走确定态，否则保持不确定态。
     */
    fun render(
        context: Context,
        textView: TextView,
        bar: ProgressBar?,
        state: AnalysisRunState?
    ) {
        if (state == null) {
            textView.visibility = View.GONE
            bar?.visibility = View.GONE
            return
        }
        textView.visibility = View.VISIBLE
        textView.text = text(context, state)
        bar?.let {
            it.visibility = View.VISIBLE
            val percent = state.percent()
            if (percent == null) {
                it.isIndeterminate = true
            } else {
                it.isIndeterminate = false
                it.progress = percent
            }
        }
    }
}
