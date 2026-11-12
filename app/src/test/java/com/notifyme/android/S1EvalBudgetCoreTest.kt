// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [S1EvalBudgetCore] 的行为锁：评测的时间闸不能靠「跑一次看会不会挂」来验证。
 *
 * 每条用例对应 2026-11-12 那次事故的一个环节：配置写错不能崩（[parseMillis]）、
 * 剩余预算要能把单次上限压下来（[S1EvalBudgetCore.callTimeoutMs]）、
 * 预算耗尽必须停而不是继续发（[S1EvalBudgetCore.canStartCall]）。
 */
class S1EvalBudgetCoreTest {

    @Test
    fun 缺省配置给出默认单次上限90s与整轮预算15min() {
        val plan = S1EvalBudgetCore.resolvePlan(null, null)
        assertEquals(900_000L, plan.totalBudgetMs)
        assertEquals(90_000L, plan.callTimeoutMs)
    }

    @Test
    fun 空白与非法数字一律回退默认值() {
        assertEquals(S1EvalBudgetCore.DEFAULT_CALL_TIMEOUT_MS,
            S1EvalBudgetCore.parseMillis("   ", S1EvalBudgetCore.DEFAULT_CALL_TIMEOUT_MS,
                S1EvalBudgetCore.MIN_CALL_TIMEOUT_MS, S1EvalBudgetCore.MAX_CALL_TIMEOUT_MS))
        assertEquals(S1EvalBudgetCore.DEFAULT_CALL_TIMEOUT_MS,
            S1EvalBudgetCore.parseMillis("abc", S1EvalBudgetCore.DEFAULT_CALL_TIMEOUT_MS,
                S1EvalBudgetCore.MIN_CALL_TIMEOUT_MS, S1EvalBudgetCore.MAX_CALL_TIMEOUT_MS))
        assertEquals(S1EvalBudgetCore.DEFAULT_CALL_TIMEOUT_MS,
            S1EvalBudgetCore.parseMillis(null, S1EvalBudgetCore.DEFAULT_CALL_TIMEOUT_MS,
                S1EvalBudgetCore.MIN_CALL_TIMEOUT_MS, S1EvalBudgetCore.MAX_CALL_TIMEOUT_MS))
    }

    @Test
    fun 非正数回退默认值而不是当场耗尽预算() {
        // 0 与负数如果被当真的吃掉，整轮评测会「一秒都没测就结束」，报告全空却 exit 0。
        assertEquals(90_000L, S1EvalBudgetCore.resolvePlan(null, "0").callTimeoutMs)
        assertEquals(900_000L, S1EvalBudgetCore.resolvePlan("-1", null).totalBudgetMs)
    }

    @Test
    fun 超上限被夹到边界而不是拖死构建() {
        val plan = S1EvalBudgetCore.resolvePlan("999999999", "999999999")
        assertEquals(S1EvalBudgetCore.MAX_TOTAL_BUDGET_MS, plan.totalBudgetMs)
        assertEquals(S1EvalBudgetCore.MAX_CALL_TIMEOUT_MS, plan.callTimeoutMs)
    }

    @Test
    fun 低于下限被抬到最小可发起值() {
        val plan = S1EvalBudgetCore.resolvePlan("100", "100")
        assertEquals(S1EvalBudgetCore.MIN_CALL_TIMEOUT_MS, plan.totalBudgetMs)
        assertEquals(S1EvalBudgetCore.MIN_CALL_TIMEOUT_MS, plan.callTimeoutMs)
    }

    @Test
    fun 显式配置按毫秒生效() {
        val plan = S1EvalBudgetCore.resolvePlan("600000", "45000")
        assertEquals(600_000L, plan.totalBudgetMs)
        assertEquals(45_000L, plan.callTimeoutMs)
    }

    @Test
    fun 已用时长对时钟回拨返回0而不是负数() {
        assertEquals(0L, S1EvalBudgetCore.elapsedMs(1_000L, 900L))
        assertEquals(0L, S1EvalBudgetCore.elapsedMs(1_000L, 1_000L))
        assertEquals(500L, S1EvalBudgetCore.elapsedMs(1_000L, 1_500L))
    }

    @Test
    fun 剩余预算耗尽返回0而不是负数() {
        assertEquals(0L, S1EvalBudgetCore.remainingMs(0L, 900_000L, 900_000L))
        assertEquals(0L, S1EvalBudgetCore.remainingMs(0L, 1_000_000L, 900_000L))
        assertEquals(400_000L, S1EvalBudgetCore.remainingMs(0L, 500_000L, 900_000L))
    }

    @Test
    fun 整轮预算耗尽判定与剩余量一致() {
        assertFalse(S1EvalBudgetCore.exhausted(0L, 899_999L, 900_000L))
        assertTrue(S1EvalBudgetCore.exhausted(0L, 900_000L, 900_000L))
        assertTrue(S1EvalBudgetCore.exhausted(0L, 1_000_000L, 900_000L))
    }

    @Test
    fun 单次上限取单次配置与剩余预算的较小者() {
        val plan = S1EvalBudgetCore.Plan(totalBudgetMs = 900_000L, callTimeoutMs = 90_000L)
        assertEquals(90_000L, S1EvalBudgetCore.callTimeoutMs(plan, 500_000L))
        assertEquals(30_000L, S1EvalBudgetCore.callTimeoutMs(plan, 30_000L))
    }

    @Test
    fun 剩余预算不足最小可发起值时不发起调用() {
        val plan = S1EvalBudgetCore.Plan(totalBudgetMs = 900_000L, callTimeoutMs = 90_000L)
        assertEquals(0L, S1EvalBudgetCore.callTimeoutMs(plan, 4_999L))
        assertEquals(0L, S1EvalBudgetCore.callTimeoutMs(plan, 0L))
        assertFalse(S1EvalBudgetCore.canStartCall(plan, 4_999L))
        assertFalse(S1EvalBudgetCore.canStartCall(plan, 0L))
    }

    @Test
    fun 剩余预算刚好等于最小可发起值仍可发起() {
        val plan = S1EvalBudgetCore.Plan(totalBudgetMs = 900_000L, callTimeoutMs = 90_000L)
        assertEquals(S1EvalBudgetCore.MIN_CALL_TIMEOUT_MS, S1EvalBudgetCore.callTimeoutMs(plan, 5_000L))
        assertTrue(S1EvalBudgetCore.canStartCall(plan, 5_000L))
    }

    @Test
    fun 预算说明文案带单次秒数与整轮分钟数() {
        val plan = S1EvalBudgetCore.Plan(totalBudgetMs = 900_000L, callTimeoutMs = 90_000L)
        assertEquals("单次上限 90s · 整轮预算 15min", S1EvalBudgetCore.describe(plan))
    }

    @Test
    fun 耗尽文案写清剩多少条未测试() {
        val plan = S1EvalBudgetCore.Plan(totalBudgetMs = 600_000L, callTimeoutMs = 60_000L)
        val note = S1EvalBudgetCore.exhaustedNote(plan, 7)
        assertTrue(note.contains("单次上限 60s"))
        assertTrue(note.contains("整轮预算 10min"))
        assertTrue(note.contains("剩余 7 条样本未测试"))
    }
}
