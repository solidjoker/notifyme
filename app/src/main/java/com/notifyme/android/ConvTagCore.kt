// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

/**
 * 会话标签（M19）：给「分析过的会话」分类，并据此决定它要不要继续进后续分析。
 *
 * 语义约定：
 *  - [ConvTag.TODO] 待办——有我要做的事：继续分析，且是「最近待办」区的候选；
 *  - [ConvTag.TRACK] 跟踪——持续观察但不催办：继续分析，只更新摘要；
 *  - [ConvTag.DONE] 已办结——事情处理完了：不再自动分析，历史结果与记录保留；
 *  - [ConvTag.NOISE] 无关——不再自动分析，新消息只落库不评估；
 *  - [ConvTag.NONE] 未标记——跟随「关注名单/全部关注」的既有判定（升级前行为不变）。
 *
 * 手动「分析本会话」是显式指定，无视标签（forced 分支）。
 */
enum class ConvTag(val code: String, val emoji: String) {
    NONE("none", ""),
    TODO("todo", "🟠"),
    TRACK("track", "🔵"),
    DONE("done", "✅"),
    NOISE("noise", "⚪");

    companion object {
        /** 未知/损坏值一律按「未标记」处理，绝不因脏数据把会话永久拉黑。 */
        fun fromCode(raw: String?): ConvTag = values().firstOrNull { it.code == raw } ?: NONE
    }
}

object ConvTagCore {

    /** 弹窗展示顺序：先「会继续分析」的两档，再「停止分析」的两档，最后「未标记」。 */
    val PICKER_ORDER: List<ConvTag> = listOf(ConvTag.TODO, ConvTag.TRACK, ConvTag.DONE, ConvTag.NOISE, ConvTag.NONE)

    /** 打了标签、需要进后续分析的档位。 */
    fun keepsAnalyzing(tag: ConvTag): Boolean = tag == ConvTag.TODO || tag == ConvTag.TRACK

    /** 明确「无需后续」的档位：后续定时分析一律跳过。 */
    fun stopsAnalyzing(tag: ConvTag): Boolean = tag == ConvTag.DONE || tag == ConvTag.NOISE

    /**
     * 后续分析门禁（纯函数，单测覆盖）：手动分析恒通过；待办/跟踪通过；
     * 已办结/无关拒绝；未标记交回关注名单判定。
     */
    fun shouldAnalyze(tag: ConvTag, watched: Boolean, forced: Boolean): Boolean = when {
        forced -> true
        keepsAnalyzing(tag) -> true
        stopsAnalyzing(tag) -> false
        else -> watched
    }

    /** 徽标文案：emoji + 名称；未标记返回空串（调用方隐藏徽标）。 */
    fun badgeText(tag: ConvTag, label: String): String = if (tag == ConvTag.NONE) "" else "${tag.emoji} $label"
}
