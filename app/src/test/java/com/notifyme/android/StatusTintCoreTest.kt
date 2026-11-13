// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [StatusTintCore] 的状态行着色判定测试。
 *
 * 为什么值得测：`AnalysisSettingsActivity` 与 `CaptureActivity` 各有一份逐字相同的
 * `tintStatus`（9 个调用点），两份并存时「改一处漏一处」会让同一句失败文案
 * 在一页红、在另一页灰。这里把判定口径钉在 Core 上，并把真实文案（来自
 * SyncWorker / AnalysisWorker / AdvisorWorker / HistorySync / CaptureActivity）
 * 逐条喂进来，防止将来改措辞时判定悄悄翻面。
 */
class StatusTintCoreTest {

    // ---------------- 错误优先 ----------------

    @Test
    fun failureTextIsError() {
        assertEquals(R.color.status_error, StatusTintCore.colorRes("失败：网络错误"))
    }

    @Test
    fun authFailureIsError() {
        assertEquals(R.color.status_error, StatusTintCore.colorRes("鉴权失败(401)，请检查令牌"))
    }

    @Test
    fun authWordAloneIsError() {
        assertEquals(R.color.status_error, StatusTintCore.colorRes("鉴权异常"))
    }

    @Test
    fun errorWinsOverOkWhenBothPresent() {
        // SyncWorker 的「半成功」文案：轮询通了但鉴权挂了，取红更符合「有事要处理」
        assertEquals(
            R.color.status_error,
            StatusTintCore.colorRes("成功：轮询 3 次 + 鉴权失败(401)")
        )
    }

    @Test
    fun messageFailureWithOkWordIsError() {
        assertEquals(
            R.color.status_error,
            StatusTintCore.colorRes("失败：消息失败：连接超时（成功：0 条）")
        )
    }

    // ---------------- 成功 ----------------

    @Test
    fun successTextIsOk() {
        assertEquals(R.color.status_ok, StatusTintCore.colorRes("成功：消息 3 + 分析 2"))
    }

    @Test
    fun noDataSuccessIsOk() {
        assertEquals(R.color.status_ok, StatusTintCore.colorRes("成功：无新数据"))
    }

    @Test
    fun doneWordIsOk() {
        // CaptureActivity.showExtractLast 的提取终态
        assertEquals(R.color.status_ok, StatusTintCore.colorRes("提取完成：新增 12 条，耗时 1.4s"))
    }

    @Test
    fun interruptedWithDoneWordStaysOk() {
        // 历史行为：含「完成」即绿，本次重构刻意不变（见 Core 契约 ③）
        assertEquals(
            R.color.status_ok,
            StatusTintCore.colorRes("已中断：完成 4 个会话，等待重试续跑")
        )
    }

    @Test
    fun backfillSuccessIsOk() {
        assertEquals(R.color.status_ok, StatusTintCore.colorRes("成功：回填 128 条消息"))
    }

    // ---------------- 其余 ----------------

    @Test
    fun skipTextIsGray() {
        assertEquals(R.color.status_gray, StatusTintCore.colorRes("已跳过：当前未配置服务地址"))
        assertEquals(R.color.status_gray, StatusTintCore.colorRes("跳过：当前为本地模式，云端已关闭"))
    }

    @Test
    fun blankTextIsGray() {
        assertEquals(R.color.status_gray, StatusTintCore.colorRes(""))
        assertEquals(R.color.status_gray, StatusTintCore.colorRes("   "))
    }

    @Test
    fun neutralTextIsGray() {
        assertEquals(R.color.status_gray, StatusTintCore.colorRes("等待中"))
        assertEquals(R.color.status_gray, StatusTintCore.colorRes("尚未开始"))
    }

    @Test
    fun permissionMissingTextIsGray() {
        // 权限文案不含成功/失败字，落到 else（权限状态由 CaptureActivity 的语义化分支着色）
        assertEquals(R.color.status_gray, StatusTintCore.colorRes("未授予通知使用权"))
    }

    // ---------------- 与旧实现逐字对照 ----------------

    /** 旧写法（两个 Activity 里逐字相同的 tintStatus 判定体）。 */
    private fun legacyColorRes(result: String): Int = when {
        result.contains("失败") || result.contains("鉴权") -> R.color.status_error
        result.contains("成功") || result.contains("完成") -> R.color.status_ok
        else -> R.color.status_gray
    }

    @Test
    fun coreAgreesWithLegacyOnRealCorpus() {
        val corpus = listOf(
            "成功：消息 12 + 分析 3 + 顾问报告 1",
            "成功：无新数据",
            "已跳过：云端已关闭",
            "跳过：无待分析会话",
            "失败：network timeout",
            "消息失败：EOFException",
            "分析失败：本地模型未就绪",
            "顾问报告失败：HTTP 500",
            "*鉴权失败(401)，请检查令牌",
            "排队状态上报失败：网络错误",
            "提取完成：新增 3 条，耗时 0.8s",
            "提取失败：数据库被占用",
            "已中断：完成 2 个会话，等待重试续跑",
            "成功：仅端侧模式，未配置云端槽位",
            "",
            "未知状态"
        )
        for (text in corpus) {
            assertEquals("与旧实现不一致: $text", legacyColorRes(text), StatusTintCore.colorRes(text))
        }
    }

    @Test
    fun colorIdsAreDistinct() {
        // 三个资源 id 必须互不相同，否则判定再对也看不出差异
        val ids = setOf(R.color.status_error, R.color.status_ok, R.color.status_gray)
        assertEquals(3, ids.size)
    }
}
