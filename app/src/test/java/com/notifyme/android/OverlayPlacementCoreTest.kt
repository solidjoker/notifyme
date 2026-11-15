// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [OverlayPlacementCore] 悬浮层几何口径单测。
 *
 * 为什么值得测：悬浮层的坐标有两套（LayoutParams 用内容区坐标，人在说「在屏幕哪儿」时
 * 用绝对坐标），混用一次就错一整个状态栏；再加上窗口 margin 在部分 ROM 上不生效，
 * 这些光看代码看不出来，全靠真机量测兜底。
 *
 * 基准机型（真机 ebb079b5 / 小米 25098PN5AC / Android 17 / density 520）：
 * 屏 1220×2656，状态栏 inset 150，导航栏 inset 65。
 */
class OverlayPlacementCoreTest {

    private val screenWidth = 1220
    private val screenHeight = 2656
    private val topInset = 150
    private val bottomInset = 65

    private val cardGap = 26
    private val fallbackPitch = 312
    private val cardTopGap = 32

    @Test
    fun contentAndAbsoluteCoordinatesRoundTrip() {
        assertEquals(150, OverlayPlacementCore.toAbsolute(0, topInset))
        assertEquals(0, OverlayPlacementCore.toContent(150, topInset))
        // 真机实测：旧实现把状态栏算两遍，卡片落在绝对 y=332（内容区 182）
        assertEquals(182, OverlayPlacementCore.toContent(332, topInset))
        assertEquals(332, OverlayPlacementCore.toAbsolute(182, topInset))
    }

    @Test
    fun cardTopsStartBelowStatusBar() {
        val tops = OverlayPlacementCore.cardTopsAbsolute(
            count = 1,
            topInset = topInset,
            topGap = cardTopGap,
            heights = emptyList(),
            fallbackPitch = fallbackPitch,
            gap = cardGap
        )
        assertEquals(listOf(182), tops)
    }

    @Test
    fun cardTopsFollowMeasuredHeights() {
        val tops = OverlayPlacementCore.cardTopsAbsolute(
            count = 3,
            topInset = topInset,
            topGap = cardTopGap,
            heights = listOf(386, 386, 0),
            fallbackPitch = fallbackPitch,
            gap = cardGap
        )
        assertEquals(listOf(182, 182 + 386 + cardGap, 182 + 386 + cardGap + 386 + cardGap), tops)
    }

    @Test
    fun cardTopsFallBackWhenHeightUnknown() {
        val tops = OverlayPlacementCore.cardTopsAbsolute(
            count = 2,
            topInset = topInset,
            topGap = cardTopGap,
            heights = listOf(0),
            fallbackPitch = fallbackPitch,
            gap = cardGap
        )
        assertEquals(listOf(182, 182 + fallbackPitch + cardGap), tops)
    }

    @Test
    fun cardTopsEmptyForZeroCount() {
        val tops = OverlayPlacementCore.cardTopsAbsolute(
            count = 0,
            topInset = topInset,
            topGap = cardTopGap,
            heights = emptyList(),
            fallbackPitch = fallbackPitch,
            gap = cardGap
        )
        assertTrue(tops.isEmpty())
    }

    @Test
    fun cardKeepsSymmetricSideMargin() {
        val width = OverlayPlacementCore.cardWidth(screenWidth)
        val left = OverlayPlacementCore.cardLeft(screenWidth)
        assertEquals(1122, width)
        assertEquals(49, left)
        assertEquals(left, screenWidth - (left + width))
    }

    @Test
    fun panelKeepsSideMargin() {
        val width = OverlayPlacementCore.panelWidth(screenWidth)
        assertEquals(927, width)
        assertTrue(width < screenWidth)
    }

    @Test
    fun panelCentersOnWholeScreenNotContentArea() {
        // 内容区 [150, 2591] 的中心是 1370，整屏中心是 1328 → 面板要上移 42
        assertEquals(-42, OverlayPlacementCore.panelCenterOffsetY(topInset, bottomInset))
        val contentCenter = topInset + (screenHeight - bottomInset - topInset) / 2
        assertEquals(
            screenHeight / 2,
            contentCenter + OverlayPlacementCore.panelCenterOffsetY(topInset, bottomInset)
        )
    }

    @Test
    fun panelCenterOffsetIsZeroWithoutSystemBars() {
        assertEquals(0, OverlayPlacementCore.panelCenterOffsetY(0, 0))
    }

    @Test
    fun ballStartsAtOneThirdOfScreen() {
        assertEquals(885, OverlayPlacementCore.ballTopAbsolute(screenHeight))
        assertEquals(640, OverlayPlacementCore.ballTopAbsolute(1920))
    }

    @Test
    fun ballSticksToRightEdge() {
        assertEquals(1045, OverlayPlacementCore.ballLeft(screenWidth, 156, 19))
    }

    @Test
    fun ballClampRespectsSystemBars() {
        assertEquals(topInset, OverlayPlacementCore.clampBallTop(0, screenHeight, topInset, bottomInset, 156))
        assertEquals(
            screenHeight - bottomInset - 156,
            OverlayPlacementCore.clampBallTop(99999, screenHeight, topInset, bottomInset, 156)
        )
        assertEquals(885, OverlayPlacementCore.clampBallTop(885, screenHeight, topInset, bottomInset, 156))
    }

    @Test
    fun ballClampNeverGoesAboveStatusBar() {
        val clamped = OverlayPlacementCore.clampBallTop(-500, screenHeight, topInset, bottomInset, 156)
        assertEquals(topInset, clamped)
    }

    @Test
    fun ballSnapsToNearestEdge() {
        assertEquals(19, OverlayPlacementCore.snapBallLeft(100, screenWidth, 156, 19))
        assertEquals(1045, OverlayPlacementCore.snapBallLeft(1000, screenWidth, 156, 19))
    }

    @Test
    fun xiaomiRegressionMatchesMeasuredGeometry() {
        // 改前真机实测：卡片满屏 1220 宽、绝对顶 332；面板满屏、中心 1858；球绝对顶 963
        // 改后应为：卡片 1122 宽、绝对顶 182；面板 927 宽、中心 1328；球绝对顶 885
        val cardTop = OverlayPlacementCore.cardTopsAbsolute(
            count = 1,
            topInset = topInset,
            topGap = cardTopGap,
            heights = emptyList(),
            fallbackPitch = fallbackPitch,
            gap = cardGap
        ).first()
        assertEquals(182, cardTop)
        assertEquals(1122, OverlayPlacementCore.cardWidth(screenWidth))
        assertEquals(927, OverlayPlacementCore.panelWidth(screenWidth))
        val panelCenter = topInset + (screenHeight - bottomInset - topInset) / 2 +
            OverlayPlacementCore.panelCenterOffsetY(topInset, bottomInset)
        assertEquals(1328, panelCenter)
        assertEquals(885, OverlayPlacementCore.ballTopAbsolute(screenHeight))
    }
}
