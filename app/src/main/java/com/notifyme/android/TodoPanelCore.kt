// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

/**
 * 「最近待办」悬浮面板的选取逻辑（纯函数，可单测）。
 *
 * 数据联动口径——面板只显示**主页信息流仍可见**的会话的最新待办：
 *  - 会话键在 [homepageVisible] 里（消息还在、App 在采集、未被黑名单/不关注屏蔽）；
 *  - 同一会话多条历史记录时只认最新窗口的结论（调用方用
 *    [AnalysisStore.latestByConvKey] 保证，这里不再去重）；
 *  - 按分析完成时间倒序，取前 [limit] 条。
 *
 * 背景：面板原来直接读 readRecent 全量记录，主页删了会话、把 App 移出采集、
 * 或标了「不关注」之后，面板仍显示这些陈旧待办；新分析出的待办又可能被
 * 旧记录挤出前 8 条——即用户报告的「最近待办和数据不联动」。
 */
object TodoPanelCore {

    const val DEFAULT_LIMIT = 8

    /**
     * @param latestByConvKey 每个 [ConvKey] 最新一条 case 结论
     * @param homepageVisible 主页信息流当前可见的会话键集合
     */
    fun selectTodos(
        latestByConvKey: Map<ConvKey, AnalysisCaseRecord>,
        homepageVisible: Set<ConvKey>,
        limit: Int = DEFAULT_LIMIT
    ): List<AnalysisCaseRecord> =
        latestByConvKey.values
            .filter { it.s1NeedAction && it.convKey in homepageVisible }
            .sortedByDescending { it.analyzedAt }
            .take(limit)
}
