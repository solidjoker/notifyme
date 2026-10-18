// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT
package com.notifyme.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * M15 端侧分析进度的纯逻辑单测：百分比换算与「已用时间」文案。
 *
 * [AnalysisRunState.percent]/[AnalysisProgressUi.formatElapsed] 都是无 Context 的纯函数
 * （构造时显式给 startedAt，避免踩 SystemClock 的 JVM 未 mock 异常）。
 */
class AnalysisProgressTest {

    private fun state(
        stage: AnalysisStage = AnalysisStage.LOCAL_DECODE,
        caseIndex: Int = 1,
        caseTotal: Int = 1,
        token: Int = 0,
        maxToken: Int = 0
    ) = AnalysisRunState(
        convKeyId = "com.tencent.mm|张三",
        conversation = "张三",
        stage = stage,
        caseIndex = caseIndex,
        caseTotal = caseTotal,
        token = token,
        maxToken = maxToken,
        startedAt = 0L
    )

    // ---- percent ----

    @Test
    fun `non decode stages have no percent`() {
        assertNull(state(stage = AnalysisStage.PREPARING).percent())
        assertNull(state(stage = AnalysisStage.LOADING_MODEL).percent())
        assertNull(state(stage = AnalysisStage.LOCAL_PREFILL, token = 512, maxToken = 1024).percent())
        assertNull(state(stage = AnalysisStage.REMOTE).percent())
        assertNull(state(stage = AnalysisStage.SAVING).percent())
    }

    @Test
    fun `decode percent folds case index over batch`() {
        // 第 1 个 / 共 4 个，本会话解码一半 -> 12.5% -> 12
        assertEquals(12, state(caseIndex = 1, caseTotal = 4, token = 5, maxToken = 10).percent())
        // 第 2 个 / 共 2 个，本会话解码完成 -> 100%
        assertEquals(100, state(caseIndex = 2, caseTotal = 2, token = 10, maxToken = 10).percent())
    }

    @Test
    fun `decode percent clamps tokens beyond budget`() {
        // max_tokens 是采样上限，native 侧结束时会补终值；越界一律夹到 100%
        assertEquals(100, state(token = 20, maxToken = 10).percent())
        assertEquals(0, state(token = -3, maxToken = 10).percent())
    }

    @Test
    fun `decode percent is null without budget`() {
        assertNull(state(token = 5, maxToken = 0).percent())
    }

    @Test
    fun `question stage steps per answered question`() {
        // Laya TFLite 按题回报：4 题答完 1 题 = 25%
        assertEquals(25, state(stage = AnalysisStage.LOCAL_QUESTIONS, token = 1, maxToken = 4).percent())
        assertEquals(100, state(stage = AnalysisStage.LOCAL_QUESTIONS, token = 4, maxToken = 4).percent())
        assertNull(state(stage = AnalysisStage.LOCAL_QUESTIONS, token = 1, maxToken = 0).percent())
    }

    // ---- formatElapsed ----

    @Test
    fun `elapsed under one minute is seconds`() {
        assertEquals("0秒", AnalysisProgressUi.formatElapsed(0L))
        assertEquals("1秒", AnalysisProgressUi.formatElapsed(1200L))
        assertEquals("59秒", AnalysisProgressUi.formatElapsed(59_999L))
    }

    @Test
    fun `elapsed over one minute pads seconds`() {
        assertEquals("1分00秒", AnalysisProgressUi.formatElapsed(60_000L))
        assertEquals("1分05秒", AnalysisProgressUi.formatElapsed(65_000L))
        assertEquals("59分59秒", AnalysisProgressUi.formatElapsed(3_599_000L))
    }

    @Test
    fun `negative elapsed is clamped to zero`() {
        assertEquals("0秒", AnalysisProgressUi.formatElapsed(-5_000L))
    }
}
