// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 已创建提醒列表的分组 / 排序 / 筛选口径回归。
 *
 * 用固定 now = 2026-11-13 12:00 (+08:00) 的毫秒值，事件时间围绕它前后铺开，
 * 避免用 System.currentTimeMillis() 让断言随运行时刻漂移。
 */
class ReminderListCoreTest {

    private val now = 1_763_007_600_000L // 2026-11-13 12:00:00 +08:00
    private val hour = 3_600_000L
    private val day = 24 * hour

    private fun rec(
        status: String,
        eventTime: Long,
        createdAt: Long = 0L,
        key: String = status + "-" + eventTime
    ) = ReminderRecord(
        dedupKey = key,
        title = "t-" + key,
        description = "",
        conversation = "c",
        eventTime = eventTime,
        calendarEventId = -1L,
        createdAt = createdAt,
        status = status,
        note = "n"
    )

    private fun arrange(items: List<ReminderRecord>) = ReminderListCore.arrange(
        items, now,
        statusOf = { it.status },
        eventTimeOf = { it.eventTime }
    )

    private fun keys(items: List<ReminderRecord>) = items.map { it.dedupKey }

    // ---------------- normalizeFilter ----------------

    @Test
    fun `筛选码值原样通过`() {
        assertEquals(ReminderListCore.FILTER_ALL, ReminderListCore.normalizeFilter(""))
        assertEquals(ReminderListCore.FILTER_ATTENTION, ReminderListCore.normalizeFilter("attention"))
        assertEquals(ReminderListCore.FILTER_ACTIVE, ReminderListCore.normalizeFilter("active"))
        assertEquals(ReminderListCore.FILTER_EXPIRED, ReminderListCore.normalizeFilter("expired"))
    }

    @Test
    fun `筛选码值大小写与空白归一`() {
        assertEquals(ReminderListCore.FILTER_EXPIRED, ReminderListCore.normalizeFilter("  EXPIRED "))
        assertEquals(ReminderListCore.FILTER_ATTENTION, ReminderListCore.normalizeFilter("\tAttention\n"))
    }

    @Test
    fun `未知或空筛选回落全部`() {
        assertEquals(ReminderListCore.FILTER_ALL, ReminderListCore.normalizeFilter(null))
        assertEquals(ReminderListCore.FILTER_ALL, ReminderListCore.normalizeFilter("   "))
        assertEquals(ReminderListCore.FILTER_ALL, ReminderListCore.normalizeFilter("archived"))
        // 中文档名不是码值，不能被当成合法筛选
        assertEquals(ReminderListCore.FILTER_ALL, ReminderListCore.normalizeFilter("已过期"))
    }

    @Test
    fun `筛选顺序固定为全部在最前`() {
        assertEquals(
            listOf("", "attention", "active", "expired"),
            ReminderListCore.FILTERS
        )
        assertEquals(ReminderListCore.FILTER_ALL, ReminderListCore.FILTERS.first())
    }

    // ---------------- isAttention ----------------

    @Test
    fun `失败与待手动保存都需要用户动手`() {
        assertTrue(ReminderListCore.isAttention(ReminderRecord.STATUS_FAILED))
        assertTrue(ReminderListCore.isAttention(ReminderRecord.STATUS_INTENT))
    }

    @Test
    fun `已生效级别不需要用户动手`() {
        assertFalse(ReminderListCore.isAttention(ReminderRecord.STATUS_CALENDAR))
        assertFalse(ReminderListCore.isAttention(ReminderRecord.STATUS_ALARM))
        assertFalse(ReminderListCore.isAttention("no_such_status"))
    }

    // ---------------- isExpired ----------------

    @Test
    fun `事件时间过去算过期`() {
        assertTrue(ReminderListCore.isExpired(ReminderRecord.STATUS_CALENDAR, now - hour, now))
        assertTrue(ReminderListCore.isExpired(ReminderRecord.STATUS_ALARM, now - hour, now))
        assertTrue(ReminderListCore.isExpired(ReminderRecord.STATUS_INTENT, now - day, now))
    }

    @Test
    fun `事件时间未到不算过期`() {
        assertFalse(ReminderListCore.isExpired(ReminderRecord.STATUS_CALENDAR, now + hour, now))
        assertFalse(ReminderListCore.isExpired(ReminderRecord.STATUS_ALARM, now + day, now))
    }

    @Test
    fun `事件时间正好等于当下算过期`() {
        // <= 而不是 <：从这一刻起「再次唤起」已经没有意义
        assertTrue(ReminderListCore.isExpired(ReminderRecord.STATUS_CALENDAR, now, now))
    }

    @Test
    fun `无事件时间不算过期`() {
        // 解析失败 / 历史记录：标成过期会误导用户删掉可能有效的提醒
        assertFalse(ReminderListCore.isExpired(ReminderRecord.STATUS_CALENDAR, 0L, now))
        assertFalse(ReminderListCore.isExpired(ReminderRecord.STATUS_CALENDAR, -1L, now))
    }

    @Test
    fun `失败记录即使时间过去也不算过期`() {
        // 失败记录的唯一语义是「等你重试」，归进过期组等于把该处理的条目藏起来
        assertFalse(ReminderListCore.isExpired(ReminderRecord.STATUS_FAILED, now - day, now))
        assertFalse(ReminderListCore.isExpired(ReminderRecord.STATUS_FAILED, 0L, now))
    }

    // ---------------- groupOf ----------------

    @Test
    fun `失败永远归需要处理组`() {
        assertEquals(ReminderListCore.FILTER_ATTENTION, ReminderListCore.groupOf(ReminderRecord.STATUS_FAILED, now - day, now))
        assertEquals(ReminderListCore.FILTER_ATTENTION, ReminderListCore.groupOf(ReminderRecord.STATUS_FAILED, now + day, now))
        assertEquals(ReminderListCore.FILTER_ATTENTION, ReminderListCore.groupOf(ReminderRecord.STATUS_FAILED, 0L, now))
    }

    @Test
    fun `待手动保存未到期归需要处理`() {
        assertEquals(ReminderListCore.FILTER_ATTENTION, ReminderListCore.groupOf(ReminderRecord.STATUS_INTENT, now + hour, now))
    }

    @Test
    fun `待手动保存但已过期归过期组`() {
        // 事件时间已过，重放 intent 只会创建一条过去的日程
        assertEquals(ReminderListCore.FILTER_EXPIRED, ReminderListCore.groupOf(ReminderRecord.STATUS_INTENT, now - hour, now))
    }

    @Test
    fun `已生效未到期归生效中`() {
        assertEquals(ReminderListCore.FILTER_ACTIVE, ReminderListCore.groupOf(ReminderRecord.STATUS_CALENDAR, now + hour, now))
        assertEquals(ReminderListCore.FILTER_ACTIVE, ReminderListCore.groupOf(ReminderRecord.STATUS_ALARM, now + day, now))
    }

    @Test
    fun `已生效已过期归过期组`() {
        assertEquals(ReminderListCore.FILTER_EXPIRED, ReminderListCore.groupOf(ReminderRecord.STATUS_CALENDAR, now - hour, now))
        assertEquals(ReminderListCore.FILTER_EXPIRED, ReminderListCore.groupOf(ReminderRecord.STATUS_ALARM, now - day, now))
    }

    @Test
    fun `无事件时间的已生效记录归生效中`() {
        // 不知道时间就不该判过期；它也不是需要用户动手的条目
        assertEquals(ReminderListCore.FILTER_ACTIVE, ReminderListCore.groupOf(ReminderRecord.STATUS_CALENDAR, 0L, now))
    }

    @Test
    fun `未知状态按时间落组`() {
        assertEquals(ReminderListCore.FILTER_ACTIVE, ReminderListCore.groupOf("weird", now + hour, now))
        assertEquals(ReminderListCore.FILTER_EXPIRED, ReminderListCore.groupOf("weird", now - hour, now))
    }

    // ---------------- arrange ----------------

    @Test
    fun `组顺序为需要处理生效中已过期`() {
        val expired = rec(ReminderRecord.STATUS_CALENDAR, now - day)
        val active = rec(ReminderRecord.STATUS_CALENDAR, now + day)
        val attention = rec(ReminderRecord.STATUS_FAILED, now, key = "att")
        val sorted = arrange(listOf(expired, active, attention))
        // 需要处理 → 生效中 → 已过期（我自己第一版把这行写反了，Core 是对的）
        assertEquals(listOf("att", active.dedupKey, expired.dedupKey), keys(sorted))
    }

    @Test
    fun `需要处理组内失败排在待保存之前`() {
        val intent = rec(ReminderRecord.STATUS_INTENT, now + hour, key = "intent")
        val failed = rec(ReminderRecord.STATUS_FAILED, now + day, key = "failed")
        assertEquals(listOf("failed", "intent"), keys(arrange(listOf(intent, failed))))
    }

    @Test
    fun `待保存组内按事件时间升序`() {
        val far = rec(ReminderRecord.STATUS_INTENT, now + 3 * day, key = "far")
        val near = rec(ReminderRecord.STATUS_INTENT, now + hour, key = "near")
        assertEquals(listOf("near", "far"), keys(arrange(listOf(far, near))))
    }

    @Test
    fun `已过期组内按事件时间降序`() {
        val old = rec(ReminderRecord.STATUS_CALENDAR, now - 3 * day, key = "old")
        val recent = rec(ReminderRecord.STATUS_CALENDAR, now - hour, key = "recent")
        assertEquals(listOf("recent", "old"), keys(arrange(listOf(old, recent))))
    }

    @Test
    fun `生效中组内按事件时间升序`() {
        val far = rec(ReminderRecord.STATUS_ALARM, now + 5 * day, key = "far")
        val near = rec(ReminderRecord.STATUS_ALARM, now + hour, key = "near")
        assertEquals(listOf("near", "far"), keys(arrange(listOf(far, near))))
    }

    @Test
    fun `无事件时间的记录排在组内最后`() {
        val timed = rec(ReminderRecord.STATUS_CALENDAR, now + day, key = "timed")
        val untimed = rec(ReminderRecord.STATUS_CALENDAR, 0L, key = "untimed")
        assertEquals(listOf("timed", "untimed"), keys(arrange(listOf(untimed, timed))))
    }

    @Test
    fun `同键记录保持传入顺序`() {
        // 调用方传 created_at 倒序，稳定排序保证「完全同键」时新建的仍在上
        val a = rec(ReminderRecord.STATUS_ALARM, now + hour, createdAt = 3L, key = "a")
        val b = rec(ReminderRecord.STATUS_ALARM, now + hour, createdAt = 2L, key = "b")
        assertEquals(listOf("a", "b"), keys(arrange(listOf(a, b))))
        assertEquals(listOf("b", "a"), keys(arrange(listOf(b, a))))
    }

    @Test
    fun `空与单条原样返回同一实例`() {
        val empty = emptyList<ReminderRecord>()
        assertSame(empty, arrange(empty))
        val one = listOf(rec(ReminderRecord.STATUS_FAILED, 0L))
        assertSame(one, arrange(one))
    }

    @Test
    fun `四类混合的完整顺序`() {
        val failed = rec(ReminderRecord.STATUS_FAILED, now - day, key = "failed")
        val intentOld = rec(ReminderRecord.STATUS_INTENT, now - 2 * hour, key = "intentOld")
        val intentNear = rec(ReminderRecord.STATUS_INTENT, now + hour, key = "intentNear")
        val activeNear = rec(ReminderRecord.STATUS_CALENDAR, now + 2 * hour, key = "activeNear")
        val activeFar = rec(ReminderRecord.STATUS_ALARM, now + 2 * day, key = "activeFar")
        val expiredRecent = rec(ReminderRecord.STATUS_CALENDAR, now - hour, key = "expiredRecent")
        val expiredOld = rec(ReminderRecord.STATUS_CALENDAR, now - 5 * day, key = "expiredOld")
        val sorted = arrange(
            listOf(expiredOld, activeFar, intentOld, expiredRecent, activeNear, intentNear, failed)
        )
        // 过期组按事件时间降序：expiredRecent(-1h) > intentOld(-2h) > expiredOld(-5d)
        assertEquals(
            listOf("failed", "intentNear", "activeNear", "activeFar", "expiredRecent", "intentOld", "expiredOld"),
            keys(sorted)
        )
    }

    // ---------------- keep ----------------

    @Test
    fun `全部筛选原样返回同一实例`() {
        val items = listOf(rec(ReminderRecord.STATUS_FAILED, now), rec(ReminderRecord.STATUS_CALENDAR, now + day))
        assertSame(
            items,
            ReminderListCore.keep(items, ReminderListCore.FILTER_ALL, now, { it.status }, { it.eventTime })
        )
    }

    @Test
    fun `按组筛选只留本组记录`() {
        val failed = rec(ReminderRecord.STATUS_FAILED, now)
        val intentOld = rec(ReminderRecord.STATUS_INTENT, now - day)
        val active = rec(ReminderRecord.STATUS_CALENDAR, now + day)
        val items = listOf(failed, intentOld, active)
        fun keepIt(f: String) = ReminderListCore.keep(items, f, now, { it.status }, { it.eventTime })
        assertEquals(listOf(failed), keepIt(ReminderListCore.FILTER_ATTENTION))
        assertEquals(listOf(active), keepIt(ReminderListCore.FILTER_ACTIVE))
        assertEquals(listOf(intentOld), keepIt(ReminderListCore.FILTER_EXPIRED))
    }

    @Test
    fun `未知筛选码值等于不过滤`() {
        val items = listOf(rec(ReminderRecord.STATUS_FAILED, now))
        assertSame(
            items,
            ReminderListCore.keep(items, "bogus", now, { it.status }, { it.eventTime })
        )
    }

    // ---------------- counts ----------------

    @Test
    fun `计数覆盖全部筛选键`() {
        val items = listOf(
            rec(ReminderRecord.STATUS_FAILED, now),
            rec(ReminderRecord.STATUS_INTENT, now + hour),
            rec(ReminderRecord.STATUS_CALENDAR, now + day),
            rec(ReminderRecord.STATUS_ALARM, now - day)
        )
        val counts = ReminderListCore.counts(items, now, { it.status }, { it.eventTime })
        assertEquals(ReminderListCore.FILTERS.toSet(), counts.keys)
        assertEquals(4, counts[ReminderListCore.FILTER_ALL])
        assertEquals(2, counts[ReminderListCore.FILTER_ATTENTION])
        assertEquals(1, counts[ReminderListCore.FILTER_ACTIVE])
        assertEquals(1, counts[ReminderListCore.FILTER_EXPIRED])
    }

    @Test
    fun `空列表的计数全为 0`() {
        val counts = ReminderListCore.counts(emptyList<ReminderRecord>(), now, { it.status }, { it.eventTime })
        assertEquals(ReminderListCore.FILTERS.toSet(), counts.keys)
        assertTrue(counts.values.all { it == 0 })
    }

    // ---------------- emptyFilter ----------------

    @Test
    fun `完全没有记录时返回 null 走通用空态`() {
        assertNull(ReminderListCore.emptyFilter(0, 0, ReminderListCore.FILTER_ATTENTION))
        assertNull(ReminderListCore.emptyFilter(0, 0, ReminderListCore.FILTER_ALL))
    }

    @Test
    fun `有记录、筛选后仍有命中时不弹空态`() {
        // 真机 bug 回归：只要 totalCount>0 且 filter!=ALL 就返回码值，
        // 会让「需要处理」筛选下明明有 1 条的页面同时展示列表和空态横幅。
        assertNull(ReminderListCore.emptyFilter(5, 2, ReminderListCore.FILTER_ATTENTION))
        assertNull(ReminderListCore.emptyFilter(5, 5, ReminderListCore.FILTER_ALL))
    }

    @Test
    fun `有记录但不过滤时返回 null`() {
        assertNull(ReminderListCore.emptyFilter(5, 0, ReminderListCore.FILTER_ALL))
        assertNull(ReminderListCore.emptyFilter(5, 0, "bogus"))
    }

    @Test
    fun `筛选后一条都不剩时返回该筛选码值`() {
        assertEquals(
            ReminderListCore.FILTER_ATTENTION,
            ReminderListCore.emptyFilter(5, 0, ReminderListCore.FILTER_ATTENTION)
        )
        assertEquals(
            ReminderListCore.FILTER_EXPIRED,
            ReminderListCore.emptyFilter(2, 0, ReminderListCore.FILTER_EXPIRED)
        )
    }
}
