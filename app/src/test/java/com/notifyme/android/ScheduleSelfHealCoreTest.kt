// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 排程自愈纯决策逻辑单测（ScheduleSelfHealCore）。
 * 背景：国产 ROM 夜间丢弃 WorkManager 的 job 注册，进程活着但分析永不触发。
 */
class ScheduleSelfHealCoreTest {

    private val min = 60_000L

    @Test
    fun `健康排程不触发自愈`() {
        val now = 1_000_000_000L
        // 周期 30 分钟，阈值 90 分钟；10 分钟前刚成功分析
        assertFalse(ScheduleSelfHealCore.shouldRepair(now, now - 10 * min, now - 1000, 30, 0))
    }

    @Test
    fun `失联超过阈值触发自愈`() {
        val now = 1_000_000_000L
        // 周期 15 分钟，阈值 max(45,30)=45 分钟；进程活了 2 小时、已 60 分钟没有成功记录
        assertTrue(ScheduleSelfHealCore.shouldRepair(now, now - 60 * min, now - 120 * min, 15, 0))
    }

    @Test
    fun `短周期也不会三十分钟内误判`() {
        val now = 1_000_000_000L
        // 周期 5 分钟，3 倍=15 分钟 < 最低水位 30 分钟 → 20 分钟仍健康
        assertFalse(ScheduleSelfHealCore.shouldRepair(now, now - 20 * min, now - 120 * min, 5, 0))
        // 31 分钟 → 超最低水位，判定失联
        assertTrue(ScheduleSelfHealCore.shouldRepair(now, now - 31 * min, now - 120 * min, 5, 0))
    }

    @Test
    fun `新进程刚重排过_不立即误判`() {
        val now = 1_000_000_000L
        // 昨晚的成功记录很旧，但进程 1 分钟前刚创建（启动路径会重新排程）
        assertFalse(ScheduleSelfHealCore.shouldRepair(now, now - 10 * 60 * min, now - 1 * min, 30, 0))
    }

    @Test
    fun `冷却期内不重复重注册`() {
        val now = 1_000_000_000L
        val stale = now - 5 * 60 * min
        // 距上次自愈 10 分钟 < 冷却 15 分钟
        assertFalse(ScheduleSelfHealCore.shouldRepair(now, stale, now - 120 * min, 30, now - 10 * min))
        // 距上次自愈 16 分钟 ≥ 冷却 → 允许再次尝试
        assertTrue(ScheduleSelfHealCore.shouldRepair(now, stale, now - 120 * min, 30, now - 16 * min))
    }

    @Test
    fun `从未成功分析过_按进程起点判定`() {
        val now = 1_000_000_000L
        // lastAnalysisTime=0（从未跑过），进程活了 2 小时 > 阈值 → 需要救活
        assertTrue(ScheduleSelfHealCore.shouldRepair(now, 0, now - 120 * min, 30, 0))
        // 进程只活了 5 分钟 → 给排程机会，不动手
        assertFalse(ScheduleSelfHealCore.shouldRepair(now, 0, now - 5 * min, 30, 0))
    }

    @Test
    fun `非法周期直接跳过`() {
        assertFalse(ScheduleSelfHealCore.shouldRepair(1000, 0, 0, 0, 0))
    }
}
