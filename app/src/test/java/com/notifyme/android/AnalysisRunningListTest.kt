// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 首页「正在分析」任务清单的口径（M26）。
 *
 * 这个功能是补「手机上看不到当下在分析的任务清单」这个洞的：
 * 一次分析是多个会话逐个跑，但进度状态里原本只带**当前那一个**会话的阶段，
 * 页面上只剩一根细进度条。
 *
 * 钉的是四件事：
 *  1. 清单为空 = 不显示（不该占着首页顶部的位置）；
 *  2. 文案走**资源 id**（`runningTitleRes` / `taskStatusRes`）——
 *     界面文案在 strings.xml，数字槽位才能恒 ASCII（`getString(id, 数字)` 在 ar-EG 下出 `٢٠٠`）；
 *  3. 状态四态各自一个资源，同一句文案盖住两种状态 = 清单读不懂；
 *  4. **内容指纹只吃状态与顺序，不吃 token 数** —— 否则 decode 阶段每 8 个 token
 *     一次回调都会重建十几行 View，白烧主线程。
 */
class AnalysisRunningListTest {

    private fun task(
        name: String = "会话A",
        status: AnalysisTaskStatus = AnalysisTaskStatus.PENDING,
        note: String = ""
    ) = AnalysisBatchTask(
        convKeyId = "com.tencent.mm|" + name,
        conversation = name,
        status = status,
        note = note
    )

    @Test
    fun emptyBatchShowsNothing() {
        assertFalse(AnalysisProgressUi.shouldShowRunning(emptyList()))
    }

    @Test
    fun titleResPicksRunningOrDone() {
        // 标题分两种资源：跑着的时候用「正在分析」，全跑完用「分析完成」——
        // 「0/1」读起来像「一个都没跑成」，而实际正跑着第一个。
        assertEquals(
            R.string.analysis_running_title,
            AnalysisProgressUi.runningTitleRes(allDone = false)
        )
        assertEquals(
            R.string.analysis_running_title_done,
            AnalysisProgressUi.runningTitleRes(allDone = true)
        )
    }

    @Test
    fun statusResourcesAreOnePerState() {
        assertEquals(
            R.string.analysis_status_pending,
            AnalysisProgressUi.taskStatusRes(AnalysisTaskStatus.PENDING)
        )
        assertEquals(
            R.string.analysis_status_running,
            AnalysisProgressUi.taskStatusRes(AnalysisTaskStatus.RUNNING)
        )
        assertEquals(
            R.string.analysis_status_done,
            AnalysisProgressUi.taskStatusRes(AnalysisTaskStatus.DONE)
        )
        assertEquals(
            R.string.analysis_status_failed,
            AnalysisProgressUi.taskStatusRes(AnalysisTaskStatus.FAILED)
        )
        val ids = listOf(
            AnalysisTaskStatus.PENDING,
            AnalysisTaskStatus.RUNNING,
            AnalysisTaskStatus.DONE,
            AnalysisTaskStatus.FAILED
        ).map { AnalysisProgressUi.taskStatusRes(it) }
        assertEquals(4, ids.distinct().size)
    }

    @Test
    fun doneCountTreatsPrefilterSettleAsDone() {
        // 预筛 sharp 的会话不调模型，但对用户来说这条确实处理完了，
        // 不该一直挂在「排队中」。
        val batch = listOf(
            task("A", AnalysisTaskStatus.DONE, "预筛结案"),
            task("B", AnalysisTaskStatus.PENDING)
        )
        assertEquals(1, AnalysisProgressUi.doneCount(batch))
    }

    @Test
    fun failedTaskIsNotCountedAsDone() {
        val batch = listOf(
            task("A", AnalysisTaskStatus.DONE),
            task("B", AnalysisTaskStatus.FAILED)
        )
        assertEquals(1, AnalysisProgressUi.doneCount(batch))
    }

    @Test
    fun longBatchIsFoldedAndKeepsTheRunningOne() {
        // 20 个任务一屏放不下 → 折叠；但**正在跑的那条必须留着**，
        // 折掉了用户就看不到「现在轮到谁」了。
        val batch = (1..20).map { i ->
            task(
                name = "会话$i",
                status = if (i == 12) AnalysisTaskStatus.RUNNING else AnalysisTaskStatus.PENDING
            )
        }
        val shown = AnalysisProgressUi.visibleRows(batch)
        assertEquals(AnalysisProgressUi.MAX_VISIBLE_ROWS, shown.size)
        assertTrue(
            "正在跑的那条必须在可见行里",
            shown.any { it.status == AnalysisTaskStatus.RUNNING }
        )
        assertEquals(20 - AnalysisProgressUi.MAX_VISIBLE_ROWS, AnalysisProgressUi.hiddenCount(batch))
    }

    @Test
    fun shortBatchIsNotFolded() {
        val batch = (1..3).map { task(name = "会话$it") }
        assertEquals(3, AnalysisProgressUi.visibleRows(batch).size)
        assertEquals(0, AnalysisProgressUi.hiddenCount(batch))
    }

    @Test
    fun signatureChangesWithStatus() {
        val a = listOf(task("A", AnalysisTaskStatus.PENDING))
        val b = listOf(task("A", AnalysisTaskStatus.RUNNING))
        assertTrue(
            "状态变了指纹必须跟着变",
            AnalysisProgressUi.batchSignature(a) != AnalysisProgressUi.batchSignature(b)
        )
    }

    @Test
    fun signatureIgnoresIrrelevantFieldsButKeepsOrder() {
        // 同状态同顺序 → 指纹不变（token 变化不该触发重建）
        val a = listOf(task("A", AnalysisTaskStatus.RUNNING), task("B", AnalysisTaskStatus.PENDING))
        val b = listOf(task("A", AnalysisTaskStatus.RUNNING), task("B", AnalysisTaskStatus.PENDING))
        assertEquals(AnalysisProgressUi.batchSignature(a), AnalysisProgressUi.batchSignature(b))

        // 顺序变了 → 指纹要变（清单显示顺序变了，得重排）
        val c = listOf(task("B", AnalysisTaskStatus.RUNNING), task("A", AnalysisTaskStatus.PENDING))
        assertTrue(
            "顺序变了指纹必须跟着变",
            AnalysisProgressUi.batchSignature(a) != AnalysisProgressUi.batchSignature(c)
        )
    }

    @Test
    fun signatureDistinguishesDifferentConversations() {
        val a = listOf(task("A", AnalysisTaskStatus.PENDING))
        val b = listOf(task("B", AnalysisTaskStatus.PENDING))
        assertTrue(
            AnalysisProgressUi.batchSignature(a) != AnalysisProgressUi.batchSignature(b)
        )
    }
}
