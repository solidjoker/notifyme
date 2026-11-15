// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

/**
 * 悬浮层几何口径：悬浮球 / 悬浮卡 / 待办面板的位置与尺寸计算（纯函数，无 Android 依赖）。
 *
 * 三条铁律（真机 ebb079b5 / 小米 25098PN5AC / Android 17 实测）：
 *
 * 1. **悬浮窗的 y 是内容区坐标**：绝对位置 = y + 状态栏高度。
 *    旧代码把状态栏高度又加进 y，等于算了两遍——悬浮卡实测落在绝对 y=332（应为 182），
 *    整整低了一个状态栏（150px）。
 * 2. **MATCH_PARENT + horizontalMargin 不生效**：待办面板与悬浮卡实测都是满屏宽
 *    （1220/1220），要留白必须自己算宽度 + 居中 x。
 * 3. **gravity=CENTER + verticalMargin 会把窗口整体下推 0.2×内容区高**：
 *    面板实测中心在 1858（整屏中心 1328），两台设备都偏下；
 *    居中就别设 margin，要对齐整屏中心用 [panelCenterOffsetY] 补上下系统栏的差。
 *
 * 坐标约定：本文件内部一律用**绝对坐标**（相对整块屏幕），
 * 与 LayoutParams 交换时用 [toContent] / [toAbsolute] 一处转换，别混用。
 */
internal object OverlayPlacementCore {

    /** 悬浮卡宽度占屏宽比例（旧实现 horizontalMargin=0.04 双边留白 → 92%） */
    const val CARD_WIDTH_RATIO = 0.92f

    /** 待办面板宽度占屏宽比例（旧实现 horizontalMargin=0.12 双边留白 → 76%） */
    const val PANEL_WIDTH_RATIO = 0.76f

    /** 悬浮卡之间的间距 */
    const val CARD_GAP_DP = 8

    /** 悬浮卡高度还没测量出来时的兜底步进（沿用旧实现的行距 96dp） */
    const val CARD_FALLBACK_PITCH_DP = 96

    /** 内容区坐标 → 绝对坐标 */
    fun toAbsolute(contentY: Int, topInset: Int): Int = contentY + topInset

    /** 绝对坐标 → 内容区坐标（写回 LayoutParams.y 前用） */
    fun toContent(absoluteY: Int, topInset: Int): Int = absoluteY - topInset

    /**
     * 悬浮卡自上而下的绝对顶部：首张贴在状态栏之下 [topGap]，
     * 之后按前一张的**实测高度** + [gap] 顺延（高度未测量时用 [fallbackPitch]）。
     */
    fun cardTopsAbsolute(
        count: Int,
        topInset: Int,
        topGap: Int,
        heights: List<Int>,
        fallbackPitch: Int,
        gap: Int
    ): List<Int> {
        val out = ArrayList<Int>(count.coerceAtLeast(0))
        var top = topInset + topGap
        for (i in 0 until count) {
            out.add(top)
            val h = heights.getOrNull(i) ?: 0
            top += (if (h > 0) h else fallbackPitch) + gap
        }
        return out
    }

    /** 悬浮卡宽度 = 屏宽 × 92% */
    fun cardWidth(displayWidth: Int): Int = (displayWidth * CARD_WIDTH_RATIO).toInt()

    /** 悬浮卡左边距 = 水平居中 */
    fun cardLeft(displayWidth: Int): Int = (displayWidth - cardWidth(displayWidth)) / 2

    /** 待办面板宽度 = 屏宽 × 76% */
    fun panelWidth(displayWidth: Int): Int = (displayWidth * PANEL_WIDTH_RATIO).toInt()

    /**
     * gravity=CENTER 时把窗口中心对齐到**整屏**中心的 y 偏移（内容区坐标）。
     *
     * 窗口默认居中于内容区（状态栏与导航栏之间），整屏中心比内容区中心高
     * (topInset - bottomInset)/2，所以要往上补这么多。
     */
    fun panelCenterOffsetY(topInset: Int, bottomInset: Int): Int = (bottomInset - topInset) / 2

    /** 悬浮球初始绝对顶部 = 屏高 1/3 */
    fun ballTopAbsolute(displayHeight: Int): Int = displayHeight / 3

    /** 悬浮球初始左边 = 贴右边缘留 [gap] */
    fun ballLeft(displayWidth: Int, ballSize: Int, gap: Int): Int = displayWidth - ballSize - gap

    /** 悬浮球拖拽时的绝对顶部夹取：不越过状态栏，也不被导航栏压住 */
    fun clampBallTop(top: Int, displayHeight: Int, topInset: Int, bottomInset: Int, ballSize: Int): Int {
        val min = topInset
        val max = (displayHeight - bottomInset - ballSize).coerceAtLeast(min)
        return top.coerceIn(min, max)
    }

    /** 松手吸附：球心在左半屏贴左，否则贴右 */
    fun snapBallLeft(centerX: Int, displayWidth: Int, ballSize: Int, gap: Int): Int =
        if (centerX * 2 < displayWidth) gap else displayWidth - ballSize - gap
}
