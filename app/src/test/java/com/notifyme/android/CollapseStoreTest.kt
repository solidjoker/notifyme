// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT
package com.notifyme.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 折叠模型纯逻辑单测：latestDayKeyOf / isDateCollapsed / isConversationCollapsed 均为
 * 无 Context 依赖的纯函数，覆盖天级「显式折叠 > 显式展开 > 默认（非最新天折叠）」三态优先级、
 * 未知日期组不参与最大值的边界，以及 M16 恢复的会话级折叠（与会话互不串味、与天级互相独立）。
 */
class CollapseStoreTest {

    private val key = ConvKey("com.tencent.mm", "张三")

    // ---- latestDayKeyOf ----

    @Test
    fun `latest day picks max iso key`() {
        val days = setOf("2026-10-11", "2026-10-13", "2026-10-12")
        assertEquals("2026-10-13", CollapseStore.latestDayKeyOf(days, MessageStore.DAY_KEY_UNKNOWN))
    }

    @Test
    fun `unknown day never wins over real dates`() {
        // 哨兵 "__unknown__" 字典序（'_'=0x5F）大于数字，必须被排除在「最新天」之外
        val days = setOf("2026-10-01", MessageStore.DAY_KEY_UNKNOWN)
        assertEquals("2026-10-01", CollapseStore.latestDayKeyOf(days, MessageStore.DAY_KEY_UNKNOWN))
    }

    @Test
    fun `only unknown returns unknown`() {
        val days = setOf(MessageStore.DAY_KEY_UNKNOWN)
        assertEquals(
            MessageStore.DAY_KEY_UNKNOWN,
            CollapseStore.latestDayKeyOf(days, MessageStore.DAY_KEY_UNKNOWN)
        )
    }

    // ---- isDateCollapsed 三态 ----

    @Test
    fun `default collapses history days and keeps latest open`() {
        val latest = "2026-10-13"
        assertTrue(
            CollapseStore.isDateCollapsed(emptySet(), emptySet(), key, "2026-10-11", latest)
        )
        assertFalse(
            CollapseStore.isDateCollapsed(emptySet(), emptySet(), key, latest, latest)
        )
    }

    @Test
    fun `explicit collapsed set wins over latest default open`() {
        val latest = "2026-10-13"
        assertTrue(
            CollapseStore.isDateCollapsed(
                setOf("${key.id}\u0001$latest"), emptySet(), key, latest, latest
            )
        )
    }

    @Test
    fun `explicit expanded set opens history day`() {
        val composite = "${key.id}\u00012026-10-11"
        assertFalse(
            CollapseStore.isDateCollapsed(
                emptySet(), setOf(composite), key, "2026-10-11", "2026-10-13"
            )
        )
    }

    @Test
    fun `collapsed set beats expanded set when both contain composite`() {
        // 理论上互斥写不会同时命中；若存量数据异常，折叠优先（与实现顺序一致）
        val composite = "${key.id}\u00012026-10-11"
        assertTrue(
            CollapseStore.isDateCollapsed(
                setOf(composite), setOf(composite), key, "2026-10-11", "2026-10-13"
            )
        )
    }

    @Test
    fun `composites of different conversations do not interfere`() {
        val other = ConvKey("com.tencent.mm", "李四")
        val collapsed = setOf("${other.id}\u00012026-10-13")
        // 李四折叠其最新天不影响张三：张三同一天仍是默认展开（最新天）
        assertFalse(
            CollapseStore.isDateCollapsed(collapsed, emptySet(), key, "2026-10-13", "2026-10-13")
        )
    }

    // ---- M16 会话级折叠（与天级独立的两层） ----

    @Test
    fun `conversation collapsed only when its id is in set`() {
        val other = ConvKey("com.tencent.mm", "李四")
        val collapsed = setOf(key.id)
        assertTrue(CollapseStore.isConversationCollapsed(collapsed, key))
        assertFalse(CollapseStore.isConversationCollapsed(collapsed, other))
        // 集合外一律默认展开
        assertFalse(CollapseStore.isConversationCollapsed(emptySet(), key))
    }

    @Test
    fun `conversation level and day level states stay independent`() {
        // 会话整组收起时组内不进列表；天级的显式展开不会解除会话级折叠（两层各自记账）
        val collapsedConversations = setOf(key.id)
        val expandedDay = "${key.id}\u00012026-10-11"
        assertTrue(CollapseStore.isConversationCollapsed(collapsedConversations, key))
        assertFalse(
            CollapseStore.isDateCollapsed(
                emptySet(), setOf(expandedDay), key, "2026-10-11", "2026-10-13"
            )
        )
    }

    @Test
    fun `same conversation name across apps does not collide`() {
        // ConvKey.id 含 pkg：同名会话跨 App 不互相收起
        val weixin = ConvKey("com.tencent.mm", "张三")
        val qq = ConvKey("com.tencent.qq", "张三")
        val collapsed = setOf(weixin.id)
        assertTrue(CollapseStore.isConversationCollapsed(collapsed, weixin))
        assertFalse(CollapseStore.isConversationCollapsed(collapsed, qq))
    }
}
