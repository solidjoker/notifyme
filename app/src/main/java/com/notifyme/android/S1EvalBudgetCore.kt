// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

/**
 * S1 真实 HTTP 评测的时间预算内核（纯函数，可单测）。
 *
 * 背景：评测要打两个真实推理端点（云端 + 端侧替身），20 条样本 × 2 侧 = 40 次 HTTP 调用。
 * 旧实现只有单次 readTimeout=180s，没有整轮预算——端点「能连上但迟迟不回」时（实测本机
 * llama-server 单次 chat 就要 85s，负载高时直接超时），最坏 40 × 180s = 2 小时，而且挂住的
 * 是整个 testOpenDebugUnitTest 任务（2026-11-12 全量自检因此卡死 40 分钟，只能 jstack 定位）。
 *
 * 因此评测必须同时具备两道上限：
 *  - 单次调用上限 [Plan.callTimeoutMs]（默认 90s，够本机慢模型出一次判定）；
 *  - 整轮总预算 [Plan.totalBudgetMs]（默认 15min），预算耗尽即停止发起新调用，
 *    把剩余样本记为「未测试」写进报告，而不是让构建无限等。
 *
 * 这里只做算术与判定：不读环境变量、不做 IO、不依赖时钟——当前时间由调用方传入。
 */
internal object S1EvalBudgetCore {

    /** 整轮总预算默认 15 分钟：40 次调用按 90s 上限也只需 1 小时，15min 足够小样本收敛。 */
    const val DEFAULT_TOTAL_BUDGET_MS = 15L * 60L * 1000L

    /** 单次调用默认上限 90s：本机 176B 模型冷启动后单条 chat 实测 85s，留一点余量。 */
    const val DEFAULT_CALL_TIMEOUT_MS = 90L * 1000L

    /** 小于这个剩余预算就不再发起调用：5s 连一个 JSON 首包都拿不到，发起只等于白等。 */
    const val MIN_CALL_TIMEOUT_MS = 5L * 1000L

    /** 总预算上限 1 小时：防止误配成天文数字把 CI 拖死。 */
    const val MAX_TOTAL_BUDGET_MS = 60L * 60L * 1000L

    /** 单次上限上限 10 分钟。 */
    const val MAX_CALL_TIMEOUT_MS = 10L * 60L * 1000L

    /** 一轮评测的时间预算。 */
    data class Plan(
        val totalBudgetMs: Long,
        val callTimeoutMs: Long
    )

    /**
     * 解析一个毫秒配置：空白 / 非数字 / 非正数一律回退 [defaultMs]；超出区间则夹到边界。
     * 任何输入都必须返回一个可用值——评测配置写错不该让整轮评测崩掉。
     */
    fun parseMillis(raw: String?, defaultMs: Long, minMs: Long, maxMs: Long): Long {
        val v = raw?.trim()?.toLongOrNull() ?: return defaultMs
        if (v <= 0L) return defaultMs
        return v.coerceIn(minMs, maxMs)
    }

    /** 由两项原始配置算出预算；两项都缺省时就是默认 90s / 15min。 */
    fun resolvePlan(totalBudgetRaw: String?, callTimeoutRaw: String?): Plan = Plan(
        totalBudgetMs = parseMillis(
            totalBudgetRaw, DEFAULT_TOTAL_BUDGET_MS, MIN_CALL_TIMEOUT_MS, MAX_TOTAL_BUDGET_MS
        ),
        callTimeoutMs = parseMillis(
            callTimeoutRaw, DEFAULT_CALL_TIMEOUT_MS, MIN_CALL_TIMEOUT_MS, MAX_CALL_TIMEOUT_MS
        )
    )

    /** 已用时长（毫秒）；时钟回拨返回 0，不返回负数。 */
    fun elapsedMs(startedAt: Long, nowMs: Long): Long =
        if (nowMs <= startedAt) 0L else nowMs - startedAt

    /** 剩余预算（毫秒）；耗尽返回 0，不返回负数。 */
    fun remainingMs(startedAt: Long, nowMs: Long, totalBudgetMs: Long): Long {
        val left = totalBudgetMs - elapsedMs(startedAt, nowMs)
        return if (left <= 0L) 0L else left
    }

    /** 预算是否已耗尽。 */
    fun exhausted(startedAt: Long, nowMs: Long, totalBudgetMs: Long): Boolean =
        remainingMs(startedAt, nowMs, totalBudgetMs) <= 0L

    /**
     * 下一次调用实际可用的上限：取「单次上限」与「剩余预算」的较小者；
     * 剩余预算不足 [MIN_CALL_TIMEOUT_MS] 时返回 0，表示这次调用不该发起。
     */
    fun callTimeoutMs(plan: Plan, remainingMs: Long): Long {
        if (remainingMs < MIN_CALL_TIMEOUT_MS) return 0L
        return if (remainingMs < plan.callTimeoutMs) remainingMs else plan.callTimeoutMs
    }

    /** 还能不能发起一次调用。 */
    fun canStartCall(plan: Plan, remainingMs: Long): Boolean =
        callTimeoutMs(plan, remainingMs) >= MIN_CALL_TIMEOUT_MS

    /** 写进评测报告首部的预算说明（避免数字散落在多处）。 */
    fun describe(plan: Plan): String {
        val totalMin = plan.totalBudgetMs / 60_000L
        return "单次上限 " + (plan.callTimeoutMs / 1000L) + "s · 整轮预算 " + totalMin + "min"
    }

    /** 预算耗尽时的报告文案。 */
    fun exhaustedNote(plan: Plan, skipped: Int): String =
        "本轮预算耗尽（" + describe(plan) + "），剩余 " + skipped + " 条样本未测试"
}
