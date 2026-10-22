// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Date

/**
 * M17 会话内自建提醒的纯逻辑测试 + 时间解析新增语义回归。
 *
 * 锁住三件事：
 *  - 手动提醒去重键「同标题同时间 = 同一条」，改任一维度就是新提醒（连点两次不会写进两个日历事件）；
 *  - 弹窗回填的时间文本能被 CalendarHelper 原样解析回来（往返一致，不存在二次解析漂移）；
 *  - 纯 HH:mm（「15:00」「晚上8点」）解析到今天该时刻，已过顺延明天；无时间信息返回 null。
 */
class ManualReminderCoreTest {

    private fun at(year: Int, month1: Int, day: Int, hour: Int, minute: Int): Long {
        val c = Calendar.getInstance()
        c.set(year, month1 - 1, day, hour, minute, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    private fun fields(ms: Long): List<Int> {
        val c = Calendar.getInstance()
        c.timeInMillis = ms
        return listOf(
            c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH),
            c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE)
        )
    }

    @Test
    fun `去重键稳定且随维度变化`() {
        val time = at(2026, 10, 24, 14, 0)
        val a = ManualReminderCore.dedupKey("com.tencent.mm", "张三", "交周报", time)
        val b = ManualReminderCore.dedupKey("com.tencent.mm", "张三", "交周报", time)
        assertEquals(a, b)
        assertTrue(a.startsWith("man:"))
        assertEquals(20, a.length)

        assertNotEquals(a, ManualReminderCore.dedupKey("com.tencent.mm", "张三", "交周报", time + 60_000L))
        assertNotEquals(a, ManualReminderCore.dedupKey("com.tencent.mm", "李四", "交周报", time))
        assertNotEquals(a, ManualReminderCore.dedupKey("com.sina.weibo", "张三", "交周报", time))
        assertNotEquals(a, ManualReminderCore.dedupKey("com.tencent.mm", "张三", "记得打卡", time))
    }

    @Test
    fun `弹窗时间文本能被日历解析原样往返`() {
        val millis = at(2026, 10, 24, 14, 30)
        val text = ManualReminderCore.eventTimeFormat().format(Date(millis))
        val parsed = CalendarHelper.tryParseDueTime(text, at(2026, 10, 22, 9, 0))
        assertNotNull(parsed)
        assertEquals(millis, parsed)
    }

    @Test
    fun `默认时间是下一个整点且可被解析`() {
        val now = at(2026, 10, 22, 22, 17)
        assertEquals("2026-10-22 23:00", ManualReminderCore.defaultTimeText(now))
        assertEquals(at(2026, 10, 22, 23, 0), CalendarHelper.tryParseDueTime(ManualReminderCore.defaultTimeText(now), now))
    }

    @Test
    fun `纯时刻未到时算今天`() {
        val base = at(2026, 10, 5, 10, 0)
        assertEquals(listOf(2026, 10, 5, 15, 0), fields(CalendarHelper.tryParseDueTime("15:00", base)!!))
        assertEquals(listOf(2026, 10, 5, 20, 0), fields(CalendarHelper.tryParseDueTime("晚上8点", base)!!))
    }

    @Test
    fun `纯时刻已过时顺延明天`() {
        val base = at(2026, 10, 5, 21, 0)
        assertEquals(listOf(2026, 10, 6, 15, 0), fields(CalendarHelper.tryParseDueTime("15:00", base)!!))
    }

    @Test
    fun `没有时间信息返回null而旧接口仍回退加一小时`() {
        val base = at(2026, 10, 5, 10, 0)
        assertNull(CalendarHelper.tryParseDueTime("有空聊", base))
        assertNull(CalendarHelper.tryParseDueTime("   ", base))
        // 分析链路依赖的兜底语义不能变：解析不出 = 基准 + 1 小时
        assertEquals(base + 60L * 60_000L, CalendarHelper.parseDueTime("有空聊", base))
    }

    @Test
    fun `长按文本预填折叠空白并限长`() {
        assertEquals("明天 15:00 开会", ManualReminderCore.prefillTitle("  明天\n 15:00\n开会 "))
        assertEquals(2, ManualReminderCore.prefillTitle("一二三", maxLen = 2).length)
        assertEquals("", ManualReminderCore.prefillTitle(""))
    }

    @Test
    fun `过去时间判定留一分钟容差`() {
        val now = at(2026, 10, 22, 10, 0)
        assertTrue(ManualReminderCore.isInPast(now - 120_000L, now))
        assertTrue(!ManualReminderCore.isInPast(now - 30_000L, now))
        assertTrue(!ManualReminderCore.isInPast(now + 60_000L, now))
    }

    @Test
    fun `提前量档位与分析链路共用同一张表`() {
        assertEquals("不提前", ManualReminderCore.leadLabel(0L))
        assertEquals("提前 15 分钟", ManualReminderCore.leadLabel(15L))
        assertEquals("提前 1 天", ManualReminderCore.leadLabel(1440L))
        // 档位表与提醒设置页下拉是同一份数据：第 0 档必须是「不提前」
        assertEquals(0L, AnalysisConfig.REMINDER_LEAD_OPTIONS[0])
        assertEquals(4, AnalysisConfig.REMINDER_LEAD_OPTIONS.size)
    }
}
