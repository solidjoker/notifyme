// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.TimeZone

/**
 * M12 分析范围 [AnalysisScopeCore] 的纯函数口径。
 *
 * 覆盖点：
 *  1. 周期区间：今天 / 近 7 天 / 近 1 月 / 自定义 / 不限，右端一律夹到 now（未来消息永不进范围）；
 *  2. 自定义区间：单端缺省、整天对齐（结束日 23:59:59.999）、反填交换、坏输入回落；
 *  3. Scope：convIds 空 = 不点名（explicit = false），非空 = 显式（跳去重与名单/标签门禁）；
 *  4. keep / keepConvs 的时间与对象过滤；
 *  5. 摘要与周期名（设置页文案就是这里，别在 Activity 里另写一套）；
 *  6. formatDay / parseDay 往返与非法输入（2 月 30 日必须 null，不能被 Calendar 顺延吞掉）；
 *  7. convIds 编解码（会话名里含 | 与逗号，逗号拼接会被切坏，只能走 JSON）。
 *
 * 全部显式传 zone（Asia/Shanghai），别依赖 JVM 默认时区。customRange 用进程默认时区对齐
 * 「整天」，所以那几条用例在 @Before 里把默认时区钉住、@After 还原。
 */
class AnalysisScopeCoreTest {

    private val zone: TimeZone = TimeZone.getTimeZone("Asia/Shanghai")
    private var savedZone: TimeZone? = null

    /** 2026-11-12 15:30:00 +08:00（周四） */
    private val nowMs = 1794468600000L

    /** 2026-11-12 00:00:00 +08:00 */
    private val todayStart = 1794412800000L

    private val day = AnalysisScopeCore.DAY_MS

    @Before
    fun pinZone() {
        savedZone = TimeZone.getDefault()
        TimeZone.setDefault(zone)
    }

    @After
    fun restoreZone() {
        savedZone?.let { TimeZone.setDefault(it) }
    }

    // ---------- 周期 ----------

    @Test
    fun `周期顺序与设置页 spinner 一一对应`() {
        assertEquals(
            listOf("all", "today", "week", "month", "custom"),
            AnalysisScopeCore.PERIODS
        )
        assertEquals("不限", AnalysisScopeCore.periodLabel(AnalysisScopeCore.PERIOD_ALL))
        assertEquals("今天", AnalysisScopeCore.periodLabel(AnalysisScopeCore.PERIOD_TODAY))
        assertEquals("近 7 天", AnalysisScopeCore.periodLabel(AnalysisScopeCore.PERIOD_WEEK))
        assertEquals("近 1 月", AnalysisScopeCore.periodLabel(AnalysisScopeCore.PERIOD_MONTH))
        assertEquals("自定义", AnalysisScopeCore.periodLabel(AnalysisScopeCore.PERIOD_CUSTOM))
    }

    @Test
    fun `未知或脏 period 一律回落不限`() {
        assertEquals(AnalysisScopeCore.PERIOD_ALL, AnalysisScopeCore.normalizePeriod(null))
        assertEquals(AnalysisScopeCore.PERIOD_ALL, AnalysisScopeCore.normalizePeriod(""))
        assertEquals(AnalysisScopeCore.PERIOD_ALL, AnalysisScopeCore.normalizePeriod("  "))
        assertEquals(AnalysisScopeCore.PERIOD_ALL, AnalysisScopeCore.normalizePeriod("yesterday"))
        assertEquals(AnalysisScopeCore.PERIOD_WEEK, AnalysisScopeCore.normalizePeriod(" WEEK "))
    }

    @Test
    fun `dayStart 对齐本地零点`() {
        assertEquals(todayStart, AnalysisScopeCore.dayStart(nowMs, zone))
        assertEquals(todayStart, AnalysisScopeCore.dayStart(todayStart + 1234L, zone))
        // 当天最后一毫秒仍属当天
        assertEquals(todayStart, AnalysisScopeCore.dayStart(todayStart + day - 1, zone))
    }

    @Test
    fun `不限周期区间恒真`() {
        val r = AnalysisScopeCore.range(AnalysisScopeCore.PERIOD_ALL, nowMs, zone)
        assertTrue(r.isAll)
        assertTrue(r.contains(Long.MIN_VALUE))
        assertTrue(r.contains(nowMs + 100L * day))
    }

    @Test
    fun `今天区间从零点到此刻`() {
        val r = AnalysisScopeCore.range(AnalysisScopeCore.PERIOD_TODAY, nowMs, zone)
        assertEquals(todayStart, r.fromMs)
        assertEquals(nowMs, r.toMs)
        assertFalse(r.isAll)
        assertTrue(r.contains(todayStart))
        assertTrue(r.contains(nowMs))
        assertFalse(r.contains(todayStart - 1))
        assertFalse(r.contains(nowMs + 1))
    }

    @Test
    fun `近 7 天含今天共 7 个自然日`() {
        val r = AnalysisScopeCore.range(AnalysisScopeCore.PERIOD_WEEK, nowMs, zone)
        assertEquals(todayStart - 6 * day, r.fromMs)
        assertTrue(r.contains(todayStart - 6 * day))
        assertFalse(r.contains(todayStart - 6 * day - 1))
        assertEquals(nowMs, r.toMs)
    }

    @Test
    fun `近 1 月含今天共 30 个自然日`() {
        val r = AnalysisScopeCore.range(AnalysisScopeCore.PERIOD_MONTH, nowMs, zone)
        assertEquals(todayStart - 29 * day, r.fromMs)
        assertEquals(nowMs, r.toMs)
    }

    // ---------- 自定义 ----------

    @Test
    fun `自定义区间整天对齐且右端夹到此刻`() {
        val from = todayStart - 3 * day
        val to = todayStart - day
        val r = AnalysisScopeCore.customRange(from, to, nowMs)
        assertEquals(from, r.fromMs)
        // 结束日 23:59:59.999，否则「到某某日」会漏掉当天全部消息
        assertEquals(to + day - 1, r.toMs)
        assertTrue(r.contains(to + day - 1))
        assertFalse(r.contains(to + day))
    }

    @Test
    fun `自定义结束日为今天时右端夹到此刻`() {
        val r = AnalysisScopeCore.customRange(todayStart, todayStart, nowMs)
        assertEquals(todayStart, r.fromMs)
        assertEquals(nowMs, r.toMs)
    }

    @Test
    fun `自定义单端缺省其一侧不设限`() {
        val onlyFrom = AnalysisScopeCore.customRange(todayStart - day, null, nowMs)
        assertEquals(todayStart - day, onlyFrom.fromMs)
        assertEquals(nowMs, onlyFrom.toMs)

        val onlyTo = AnalysisScopeCore.customRange(null, todayStart - day, nowMs)
        assertEquals(Long.MIN_VALUE, onlyTo.fromMs)
        assertEquals(todayStart - day + day - 1, onlyTo.toMs)
    }

    @Test
    fun `自定义两端都缺省等价不限`() {
        assertTrue(AnalysisScopeCore.customRange(null, null, nowMs).isAll)
    }

    @Test
    fun `自定义开始晚于结束时交换`() {
        val early = todayStart - 10 * day
        val late = todayStart - 2 * day
        val r = AnalysisScopeCore.customRange(late, early, nowMs)
        assertEquals(early, r.fromMs)
        assertEquals(late + day - 1, r.toMs)
    }

    @Test
    fun `自定义周期走 customRange`() {
        val from = todayStart - 4 * day
        val to = todayStart - day
        assertEquals(
            AnalysisScopeCore.customRange(from, to, nowMs),
            AnalysisScopeCore.range(AnalysisScopeCore.PERIOD_CUSTOM, nowMs, zone, from, to)
        )
    }

    // ---------- Scope ----------

    @Test
    fun `默认 Scope 就是不限且不点名`() {
        val s = AnalysisScopeCore.UNLIMITED
        assertEquals(AnalysisScopeCore.PERIOD_ALL, s.period)
        assertTrue(s.range.isAll)
        assertFalse(s.explicit)
        val key = ConvKey("com.tencent.mm", "张三")
        assertTrue(s.acceptsConv(key))
        assertTrue(s.acceptsTime(0L))
        assertTrue(s.acceptsTime(Long.MAX_VALUE))
    }

    @Test
    fun `点名会话即显式且只接受被点名者`() {
        val a = ConvKey("com.tencent.mm", "张三")
        val b = ConvKey("com.ss.android.lark", "产品群")
        val s = AnalysisScopeCore.scope(
            AnalysisScopeCore.PERIOD_WEEK, nowMs, zone,
            convIds = setOf(a.id)
        )
        assertTrue(s.explicit)
        assertTrue(s.acceptsConv(a))
        assertFalse(s.acceptsConv(b))
        assertEquals(1, s.convIds.size)
    }

    @Test
    fun `空白会话 id 在组装时被丢弃`() {
        val s = AnalysisScopeCore.scope(
            AnalysisScopeCore.PERIOD_ALL, nowMs, zone,
            convIds = setOf("", "  ", "com.tencent.mm|张三")
        )
        assertEquals(setOf("com.tencent.mm|张三"), s.convIds)
        assertTrue(s.explicit)
    }

    @Test
    fun `范围外消息不进 acceptsTime`() {
        val s = AnalysisScopeCore.scope(AnalysisScopeCore.PERIOD_TODAY, nowMs, zone)
        assertTrue(s.acceptsTime(nowMs))
        assertFalse(s.acceptsTime(todayStart - 1))
    }

    // ---------- 批量过滤 ----------

    @Test
    fun `keep 按时间砍掉范围外条目`() {
        val items = listOf(0L, todayStart - day, todayStart, nowMs, nowMs + day)
        val r = AnalysisScopeCore.range(AnalysisScopeCore.PERIOD_TODAY, nowMs, zone)
        assertEquals(listOf(todayStart, nowMs), AnalysisScopeCore.keep(items, r) { it })
    }

    @Test
    fun `keep 不设限时原样返回`() {
        val items = listOf(1L, 2L, 3L)
        assertEquals(items, AnalysisScopeCore.keep(items, AnalysisScopeCore.ALL) { it })
    }

    @Test
    fun `keepConvs 未点名时全通过`() {
        val items = listOf(
            ConvKey("com.tencent.mm", "张三"),
            ConvKey("com.ss.android.lark", "产品群")
        )
        val s = AnalysisScopeCore.scope(AnalysisScopeCore.PERIOD_ALL, nowMs, zone)
        assertEquals(items, AnalysisScopeCore.keepConvs(items, s) { it })
    }

    @Test
    fun `keepConvs 点名后只留被点名会话`() {
        val a = ConvKey("com.tencent.mm", "张三")
        val b = ConvKey("com.ss.android.lark", "产品群")
        val items = listOf(a, b)
        val s = AnalysisScopeCore.scope(
            AnalysisScopeCore.PERIOD_ALL, nowMs, zone, convIds = setOf(b.id)
        )
        assertEquals(listOf(b), AnalysisScopeCore.keepConvs(items, s) { it })
    }

    // ---------- 摘要 ----------

    @Test
    fun `摘要区分全部与已选`() {
        val all = AnalysisScopeCore.scope(AnalysisScopeCore.PERIOD_WEEK, nowMs, zone)
        assertEquals("近 7 天 · 全部会话", AnalysisScopeCore.summary(all))
        assertEquals("近 7 天 · 全部会话（12 个）", AnalysisScopeCore.summary(all, convTotal = 12))

        val picked = AnalysisScopeCore.scope(
            AnalysisScopeCore.PERIOD_TODAY, nowMs, zone,
            convIds = setOf("com.tencent.mm|张三", "com.ss.android.lark|产品群")
        )
        assertEquals("今天 · 已选 2 个会话", AnalysisScopeCore.summary(picked, convTotal = 12))
    }

    // ---------- 日期输入 ----------

    @Test
    fun `formatDay 与 parseDay 往返`() {
        assertEquals("2026-11-12", AnalysisScopeCore.formatDay(nowMs, zone))
        assertEquals(todayStart, AnalysisScopeCore.parseDay("2026-11-12", zone))
        assertEquals(todayStart, AnalysisScopeCore.parseDay("2026/11/12", zone))
        assertEquals(todayStart, AnalysisScopeCore.parseDay(" 2026-11-12 ", zone))
    }

    @Test
    fun `parseDay 对非法输入返回 null`() {
        assertNull(AnalysisScopeCore.parseDay(null, zone))
        assertNull(AnalysisScopeCore.parseDay("", zone))
        assertNull(AnalysisScopeCore.parseDay("2026-11", zone))
        assertNull(AnalysisScopeCore.parseDay("2026-13-01", zone))
        assertNull(AnalysisScopeCore.parseDay("2026-02-30", zone))
        assertNull(AnalysisScopeCore.parseDay("1969-12-31", zone))
        assertNull(AnalysisScopeCore.parseDay("abcd-ef-gh", zone))
    }

    // ---------- Worker 决策（选会话链的分支，抽成纯函数才测得动） ----------

    @Test
    fun `forced 由 forceKey 或点名会话触发`() {
        val plain = AnalysisScopeCore.scope(AnalysisScopeCore.PERIOD_ALL, nowMs, zone)
        val picked = AnalysisScopeCore.scope(
            AnalysisScopeCore.PERIOD_ALL, nowMs, zone, convIds = setOf("com.tencent.mm|张三")
        )
        assertFalse(AnalysisScopeCore.forced(false, plain))
        assertTrue(AnalysisScopeCore.forced(true, plain))
        assertTrue(AnalysisScopeCore.forced(false, picked))
    }

    @Test
    fun `skipDedup 只在手动轮点名时才放开`() {
        val plain = AnalysisScopeCore.scope(AnalysisScopeCore.PERIOD_WEEK, nowMs, zone)
        val picked = AnalysisScopeCore.scope(
            AnalysisScopeCore.PERIOD_WEEK, nowMs, zone, convIds = setOf("com.tencent.mm|张三")
        )
        // 周期轮 + 点名：窗口没变就重算，纯烧 token，必须仍然去重
        assertFalse(AnalysisScopeCore.skipDedup(false, picked, manual = false))
        assertTrue(AnalysisScopeCore.skipDedup(false, picked, manual = true))
        // 只选周期不带点名：手动轮也不该跳去重（周期是相对时间，重算没有新窗口）
        assertFalse(AnalysisScopeCore.skipDedup(false, plain, manual = true))
        assertTrue(AnalysisScopeCore.skipDedup(true, plain, manual = false))
    }

    @Test
    fun `immediateScope 在 forceKey 时忽略已存范围`() {
        val picked = AnalysisScopeCore.scope(
            AnalysisScopeCore.PERIOD_TODAY, nowMs, zone, convIds = setOf("com.tencent.mm|张三")
        )
        assertEquals(AnalysisScopeCore.UNLIMITED, AnalysisScopeCore.immediateScope(true, picked))
        assertEquals(picked, AnalysisScopeCore.immediateScope(false, picked))
    }

    @Test
    fun `空结果文案区分被范围筛空与真的没活`() {
        val all = AnalysisScopeCore.scope(AnalysisScopeCore.PERIOD_ALL, nowMs, zone)
        val week = AnalysisScopeCore.scope(AnalysisScopeCore.PERIOD_WEEK, nowMs, zone)
        val weekPicked = AnalysisScopeCore.scope(
            AnalysisScopeCore.PERIOD_WEEK, nowMs, zone, convIds = setOf("com.tencent.mm|张三")
        )
        assertEquals("成功：本会话无新消息可分析", AnalysisScopeCore.emptyResultMessage(true, weekPicked))
        assertEquals("成功：无待分析会话", AnalysisScopeCore.emptyResultMessage(false, all))
        assertEquals("成功：近 7 天范围内无待分析会话", AnalysisScopeCore.emptyResultMessage(false, week))
        assertEquals(
            "成功：已选会话在近 7 天范围内无消息可分析",
            AnalysisScopeCore.emptyResultMessage(false, weekPicked)
        )
        val pickedAll = AnalysisScopeCore.scope(
            AnalysisScopeCore.PERIOD_ALL, nowMs, zone, convIds = setOf("com.tencent.mm|张三")
        )
        assertEquals("成功：已选会话无新消息可分析", AnalysisScopeCore.emptyResultMessage(false, pickedAll))
    }

    // ---------- 编解码 ----------

    @Test
    fun `convIds 编解码保住竖线与逗号`() {
        val ids = setOf(
            "com.tencent.mm|张三, 李四",
            "com.ss.android.lark|产品群|A,B",
            "com.tencent.mm|空"
        )
        val raw = AnalysisScopeCore.encodeConvIds(ids)
        assertEquals(ids, AnalysisScopeCore.decodeConvIds(raw))
        // 排序保证两次落盘同一字符串（否则每次 save 都写脏 diff）
        assertEquals(AnalysisScopeCore.encodeConvIds(ids), AnalysisScopeCore.encodeConvIds(ids))
    }

    @Test
    fun `convIds 空集合与坏 JSON`() {
        assertEquals(emptySet<String>(), AnalysisScopeCore.decodeConvIds(null))
        assertEquals(emptySet<String>(), AnalysisScopeCore.decodeConvIds(""))
        assertEquals(emptySet<String>(), AnalysisScopeCore.decodeConvIds("not-json"))
        assertEquals(emptySet<String>(), AnalysisScopeCore.decodeConvIds("[\"\", \"  \"]"))
        assertEquals(emptySet<String>(), AnalysisScopeCore.decodeConvIds(AnalysisScopeCore.encodeConvIds(emptySet())))
    }
}
