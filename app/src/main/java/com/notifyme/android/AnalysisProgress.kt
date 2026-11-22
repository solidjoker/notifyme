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
 * 批次里单个任务的状态（首页「正在分析」清单用）。
 *
 * 为什么要有清单：一次「分析一轮」是**多个会话**逐个跑（Worker 里的 `cases`），
 * 但 [AnalysisRunState] 只带**当前那一个**会话的阶段与 token —— 页面上因此只可能出现
 * 「某个会话头上的一根细进度条」，看不到「这轮一共要分析哪几个、现在轮到谁、谁已经好了」。
 * 卡片一旦滚出屏幕就彻底没信息了。
 */
enum class AnalysisTaskStatus {
    /** 队列里等着，还没轮到 */
    PENDING,

    /** 正在跑（当前那个） */
    RUNNING,

    /** 跑完了（分析出结果，或被预筛就地结案） */
    DONE,

    /** 跑砸了（异常 / 模型不可用） */
    FAILED
}

/**
 * 批次里的一个任务（首页清单的一行）。
 *
 * @param convKeyId 会话复合键（与 [AnalysisRunState.convKeyId] 同口径）
 * @param conversation 会话名（清单里显示什么）
 * @param status 当前状态
 * @param note 补充说明（如「预筛结案」「端侧模型未就绪」）
 */
data class AnalysisBatchTask(
    val convKeyId: String,
    val conversation: String,
    val status: AnalysisTaskStatus,
    val note: String = ""
)

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
 * @param batch       本批任务清单（首页「正在分析」卡片的数据源）；空 = 单会话分析
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
    val startedAt: Long = SystemClock.elapsedRealtime(),
    val batch: List<AnalysisBatchTask> = emptyList()
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

    /**
     * 延迟清空进度态（M26）。
     *
     * 为什么不能立刻 `publish(null)`：发布是**合并派发**（同一帧内多次 publish 只派发一次），
     * 所以「最后一条全部 DONE」的状态紧接着被 `publish(null)` 合并掉 ——
     * 首页那张「正在分析」卡片在**≤1 帧**就没了，「✓ 分析完成 N/N」用户根本看不到。
     *
     * 延迟一小段时间再清：完成态能停留一下，用户能看清「几个好了、几个失败」。
     *
     * @param delayMs 延迟毫秒数；<=0 表示立刻清（等价 publish(null)）
     */
    fun clearAfter(delayMs: Long) {
        if (delayMs <= 0L) {
            publish(null)
            return
        }
        mainHandler.removeCallbacks(clearRunnable)
        mainHandler.postDelayed(clearRunnable, delayMs)
    }

    private val clearRunnable = Runnable { publish(null) }
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
            context.templateText(R.string.analysis_progress_case, state.caseIndex, state.caseTotal) + " · "
        } else ""
        return when (state.stage) {
            AnalysisStage.PREPARING ->
                casePrefix + context.getString(R.string.analysis_progress_elapsed,
                    context.getString(R.string.analysis_stage_preparing), elapsed)

            AnalysisStage.LOADING_MODEL ->
                casePrefix + context.getString(R.string.analysis_progress_elapsed,
                    context.getString(R.string.analysis_stage_loading), elapsed)

            AnalysisStage.LOCAL_PREFILL ->
                casePrefix + context.templateText(
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
                    casePrefix + context.templateText(
                        R.string.analysis_progress_decode,
                        context.getString(R.string.analysis_stage_decode),
                        percent, state.token, elapsed
                    )
                }
            }

            AnalysisStage.LOCAL_QUESTIONS ->
                casePrefix + context.templateText(
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

    // ---------------- 首页「正在分析」清单（M26） ----------------

    /** 清单最多列出几行，剩下的折叠成「还有 N 个」（一屏放不下 20 行任务）。 */
    const val MAX_VISIBLE_ROWS = 5

    /**
     * 清单标题用的资源 id（纯函数，可单测）。
     *
     * **别在 Core 里写中文串**：界面文案走 `strings.xml` + [Context.templateText]，
     * 数字槽位才能恒 ASCII（`getString(id, 数字)` 在 ar-EG 下出 `٢٠٠`）。
     * 「哪种状态用哪句话」的映射留在这里，是为了让这份映射只有一份。
     */
    fun runningTitleRes(allDone: Boolean): Int =
        if (allDone) R.string.analysis_running_title_done else R.string.analysis_running_title

    /**
     * 清单标题：`⚡ 正在分析 2/8`（= 正在跑第 2 个，共 8 个）。
     *
     * 语义分三种，别混：
     *  - 有人在跑 → 「⚡ 正在分析 2/8」= **正在跑第 2 个**，共 8 个；
     *  - 还没开跑 → 「⚡ 正在分析 0/8」；
     *  - 全部跑完 → 「✓ 分析完成 8/8」。
     *
     * 刻意**不**用「已完成/总数」当标题：那读起来像「0/1 = 一个都没跑成」，
     * 而实际正跑着第一个（已完成数在每行状态里已经能看到）。
     *
     * @param batch 本批任务
     * @return 标题文案；batch 为空时返 null（整块隐藏）
     */
    fun runningTitle(context: Context, batch: List<AnalysisBatchTask>): String? {
        if (batch.isEmpty()) return null
        val runningIdx = batch.indexOfFirst { it.status == AnalysisTaskStatus.RUNNING }
        val allDone = doneCount(batch) == batch.size
        if (runningIdx >= 0) {
            return context.templateText(runningTitleRes(false), runningIdx + 1, batch.size)
        }
        return if (allDone) {
            context.templateText(runningTitleRes(true), batch.size, batch.size)
        } else {
            context.templateText(runningTitleRes(false), 0, batch.size)
        }
    }

    /**
     * 推进批次名单里第 [index] 条的状态（**纯函数，可单测**）。
     *
     * 为什么放 Core：Worker 里的同名私有方法此前**从没被单测覆盖**，
     * 而它决定了清单上「这条到底好了没」—— 逻辑错一次，整轮清单都是错的。
     *
     * @param index 在批次里的位置（与 Worker 的 `cases` 下标一一对应）
     * @return 新的名单；index 越界时**原样返回**（调用方自己判，不要静默改别人的条目）
     */
    fun advance(
        batch: List<AnalysisBatchTask>,
        index: Int,
        status: AnalysisTaskStatus,
        note: String = ""
    ): List<AnalysisBatchTask> {
        if (index !in batch.indices) return batch
        val copy = ArrayList(batch)
        copy[index] = copy[index].copy(status = status, note = note)
        return copy
    }

    /**
     * 清单里要显示的行（最多 [MAX_VISIBLE_ROWS] 条，其余折叠）。
     *
     * **保持处理顺序**（Worker 按窗口时间升序排的 `cases`）—— 把 RUNNING 挪到最前会
     * 让用户读成「7 后面是 1」，而且「下一个是谁」就再也看不出来了。
     *
     * 唯一的例外：正在跑的那条**不能被折掉** —— 它落在可见窗口之外时，
     * 用它顶掉窗口里**最后一条**，其余行的相对顺序不动。
     */
    fun visibleRows(batch: List<AnalysisBatchTask>): List<AnalysisBatchTask> {
        if (batch.size <= MAX_VISIBLE_ROWS) return batch
        val visible = batch.take(MAX_VISIBLE_ROWS).toMutableList()
        val runningIdx = batch.indexOfFirst { it.status == AnalysisTaskStatus.RUNNING }
        if (runningIdx >= MAX_VISIBLE_ROWS) {
            // 正在跑的那条在折叠区里 → 顶掉可见区最后一条（位置保持不变）
            visible[MAX_VISIBLE_ROWS - 1] = batch[runningIdx]
        }
        return visible
    }

    /** 折叠掉的条数（0 = 全列出来了）。 */
    fun hiddenCount(batch: List<AnalysisBatchTask>): Int =
        (batch.size - visibleRows(batch).size).coerceAtLeast(0)

    /** 单个任务的状态文案资源 id（纯函数，可单测）。 */
    fun taskStatusRes(status: AnalysisTaskStatus): Int = when (status) {
        AnalysisTaskStatus.PENDING -> R.string.analysis_status_pending
        AnalysisTaskStatus.RUNNING -> R.string.analysis_status_running
        AnalysisTaskStatus.DONE -> R.string.analysis_status_done
        AnalysisTaskStatus.FAILED -> R.string.analysis_status_failed
    }

    /** 单个任务的状态文案。 */
    fun taskStatus(context: Context, status: AnalysisTaskStatus): String =
        context.getString(taskStatusRes(status))

    /**
     * 已完成条数：状态为 [AnalysisTaskStatus.DONE] 的任务数（纯函数，可单测）。
     *
     * 口径说明：「完成」= 分析出了结果**或**被预筛就地结案（预筛 sharp 的会话不调模型，
     * 但对用户来说这条也确实处理完了，不该一直挂在「排队中」）。
     */
    fun doneCount(batch: List<AnalysisBatchTask>): Int =
        batch.count { it.status == AnalysisTaskStatus.DONE }

    /** 清单里要不要显示：只有真在跑（有任务）时才显示。纯函数，可单测。 */
    fun shouldShowRunning(batch: List<AnalysisBatchTask>): Boolean = batch.isNotEmpty()

    /**
     * 清单的「内容指纹」：只有指纹变了才需要重建那几行（纯函数，可单测）。
     *
     * 为什么：decode 阶段每 8 个 token 一次回调，一次分析几百次 —— 每次都把
     * 十几行 View 重新 inflate 一遍会白烧主线程。指纹只吃**状态与顺序**，
     * 不吃 token 数（token 变的是进度条，不是清单）。
     */
    fun batchSignature(batch: List<AnalysisBatchTask>): String =
        batch.joinToString("|") { it.convKeyId + ":" + it.status.name + ":" + it.note }
}
