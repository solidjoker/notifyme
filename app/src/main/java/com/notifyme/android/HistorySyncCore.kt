// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

/**
 * 历史同步（冷启动回填）的**纯逻辑**部分：隐私门控 + 翻页终止判定。
 *
 * 评审 C8：HistorySync 原来完全没有接 PrivacyConfig（SyncWorker 接了），
 * LOCAL_ONLY（仅端侧）模式下仍会触发服务端直读微信库 + 拉历史，隐私开关等于被架空；
 * 同时拉历史只取第一页，服务端返回的 next_before 被丢掉，历史超过一页就永远补不齐。
 */
internal object HistorySyncCore {

    /** 翻页安全上限：防止服务端游标异常导致无限翻页。 */
    const val MAX_PAGES = 20

    /**
     * 是否允许触发服务端直读微信库（admin/extract）。
     *
     * CLOUD_REDACT（云端脱敏）模式下服务端直读会把**原文**写进云端，端上没法脱敏 → 不触发。
     */
    fun allowExtract(state: PrivacyModeState): Boolean = state.isOff

    /**
     * 是否允许从服务端拉历史消息回填本地。
     *
     * LOCAL_ONLY 下整个云端链路停用；CLOUD_REDACT 只禁服务端提取，
     * 服务端已有的（已脱敏）历史仍可回填。
     */
    fun allowFetch(state: PrivacyModeState): Boolean = !state.localOnly

    /**
     * 是否继续翻下一页。
     *
     * 终止条件（任一命中即停）：没有 next_before / 本页为空 / 游标没变 / 超过页数上限。
     */
    fun shouldFetchNext(
        nextBefore: String?,
        receivedCount: Int,
        previousCursor: String?,
        fetchedPages: Int,
        maxPages: Int = MAX_PAGES
    ): Boolean {
        if (nextBefore.isNullOrBlank()) return false
        if (receivedCount <= 0) return false
        if (previousCursor != null && nextBefore == previousCursor) return false
        if (fetchedPages >= maxPages) return false
        return true
    }

    /**
     * 拼 /messages 查询串。
     *
     * 首屏 `messages?limit=N`，翻页 `messages?limit=N&before=<毫秒>_<rowid>`
     * （服务端游标是 timestamp+rowid 的复合游标，只含数字与下划线，可直接进 query）。
     */
    fun messagesPath(nextBefore: String?, limit: Int): String {
        val base = "messages?limit=$limit"
        return if (nextBefore.isNullOrBlank()) base else "$base&before=$nextBefore"
    }
}
