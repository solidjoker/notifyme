// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话可见性内核单测（ConvVisibilityCore）。
 *
 * 覆盖点：四层门槛的判断顺序与原因码、forced 只放宽「启用集合」不放宽自身/系统源、
 * 首页筛选只影响展示不影响分析（传 ALL）、visibleKeys 的去重与保序、
 * 以及「首页筛了飞书、悬浮面板不再列微信待办」这条真机复现过的口径不一致。
 */
class ConvVisibilityCoreTest {

    private val wechat = AppSourceRegistry.PKG_WECHAT
    private val feishu = AppSourceRegistry.PKG_FEISHU
    private val self = BuildConfig.APPLICATION_ID

    private val keyWechat = ConvKey.of(wechat, "张三")
    private val keyFeishu = ConvKey.of(feishu, "产品群")

    private fun why(
        key: ConvKey = keyWechat,
        unwatched: Set<String> = emptySet(),
        filter: String = AppFilterCore.ALL,
        blocked: Boolean = false,
        enabled: Boolean = true,
        forced: Boolean = false
    ) = ConvVisibilityCore.why(key, unwatched, filter, blocked, enabled, forced)

    // ---------- 全部放行 ----------

    @Test
    fun `四层全过时可见`() {
        assertNull(why())
        assertTrue(ConvVisibilityCore.isVisible(keyWechat, emptySet(), AppFilterCore.ALL, false, true))
    }

    @Test
    fun `筛选为空串等于全部`() {
        assertNull(why(filter = ""))
        assertNull(why(filter = "   "))
    }

    @Test
    fun `筛选命中同一包名时仍然可见`() {
        assertNull(why(filter = wechat))
        assertTrue(ConvVisibilityCore.isVisible(keyWechat, emptySet(), wechat, false, true))
    }

    // ---------- 各层拦截与原因码 ----------

    @Test
    fun `关注名单不关注的会话被拦且原因为 unwatched`() {
        assertEquals(ConvVisibilityCore.WHY_UNWATCHED, why(unwatched = setOf(keyWechat.id)))
    }

    @Test
    fun `关注名单只按 id 精确匹配不误伤同会话名的其它 App`() {
        // 微信的「张三」与飞书里的「张三」是两个会话（ConvKey 的由来）
        val other = ConvKey.of(feishu, "张三")
        assertNull(why(key = other, unwatched = setOf(keyWechat.id)))
    }

    @Test
    fun `自身与系统源被拦且原因为 blocked`() {
        assertEquals(ConvVisibilityCore.WHY_BLOCKED, why(key = ConvKey.of(self, "自己"), blocked = true))
    }

    @Test
    fun `force 也不放行自身与系统源`() {
        // forced 只放宽启用集合；a11y 自抓噪声与自身包名任何情况都不进分析与列表
        assertEquals(
            ConvVisibilityCore.WHY_BLOCKED,
            why(key = ConvKey.of(self, "自己"), blocked = true, forced = true)
        )
    }

    @Test
    fun `来源被停用时被拦且原因为 disabled`() {
        assertEquals(ConvVisibilityCore.WHY_DISABLED, why(enabled = false))
    }

    @Test
    fun `force 放宽启用集合`() {
        assertNull(why(enabled = false, forced = true))
    }

    @Test
    fun `来源筛未命中时被拦且原因为 filtered`() {
        assertEquals(ConvVisibilityCore.WHY_FILTERED, why(filter = feishu))
    }

    @Test
    fun `筛选的包名区分大小写且不做前缀模糊`() {
        assertEquals(ConvVisibilityCore.WHY_FILTERED, why(filter = "com.tencent"))
    }

    // ---------- 判断顺序 ----------

    @Test
    fun `拦截原因按关注名单优先于自身源`() {
        assertEquals(
            ConvVisibilityCore.WHY_UNWATCHED,
            why(unwatched = setOf(keyWechat.id), blocked = true)
        )
    }

    @Test
    fun `拦截原因按自身源优先于停用`() {
        assertEquals(ConvVisibilityCore.WHY_BLOCKED, why(blocked = true, enabled = false))
    }

    @Test
    fun `拦截原因按停用优先于筛选`() {
        assertEquals(
            ConvVisibilityCore.WHY_DISABLED,
            why(enabled = false, filter = feishu)
        )
    }

    @Test
    fun `force 下停用被放宽但筛选仍然生效`() {
        // 分析门禁传 ALL 所以不受影响；但若有人在展示路径传了 force + 具体筛选，
        // 筛选仍要拦 —— forced 的语义只关于「采集开关」
        assertNull(why(enabled = false, forced = true, filter = wechat))
        assertEquals(
            ConvVisibilityCore.WHY_FILTERED,
            why(enabled = false, forced = true, filter = feishu)
        )
    }

    // ---------- isVisible 与 why 一致 ----------

    @Test
    fun `isVisible 与 why 判定完全一致`() {
        val cases = listOf(
            Triple(emptySet<String>(), AppFilterCore.ALL, true),
            Triple(setOf(keyWechat.id), AppFilterCore.ALL, true),
            Triple(emptySet<String>(), feishu, true),
            Triple(emptySet<String>(), AppFilterCore.ALL, false)
        )
        for ((unwatched, filter, enabled) in cases) {
            assertEquals(
                why(unwatched = unwatched, filter = filter, enabled = enabled) == null,
                ConvVisibilityCore.isVisible(keyWechat, unwatched, filter, false, enabled)
            )
        }
    }

    // ---------- visibleKeys ----------

    private fun visible(
        keys: List<ConvKey>,
        unwatched: Set<String> = emptySet(),
        filter: String = AppFilterCore.ALL,
        blocked: Set<String> = emptySet(),
        disabled: Set<String> = emptySet()
    ) = ConvVisibilityCore.visibleKeys(
        keys = keys,
        unwatchedIds = unwatched,
        appFilter = filter,
        blockedOf = { it in blocked },
        enabledOf = { it !in disabled }
    )

    @Test
    fun `visibleKeys 去重并保留首次出现顺序`() {
        val out = visible(listOf(keyWechat, keyFeishu, keyWechat))
        assertEquals(listOf(keyWechat, keyFeishu), out.toList())
    }

    @Test
    fun `visibleKeys 按首页筛选砍掉其它来源`() {
        // 这就是本轮修的真机 bug：首页筛了飞书，悬浮面板不该还列微信待办
        val out = visible(listOf(keyWechat, keyFeishu), filter = feishu)
        assertEquals(listOf(keyFeishu), out.toList())
    }

    @Test
    fun `visibleKeys 去掉停用与自身源`() {
        val out = visible(
            listOf(keyWechat, keyFeishu, ConvKey.of(self, "自己")),
            blocked = setOf(self),
            disabled = setOf(wechat)
        )
        assertEquals(listOf(keyFeishu), out.toList())
    }

    @Test
    fun `visibleKeys 去掉不关注的会话`() {
        val out = visible(listOf(keyWechat, keyFeishu), unwatched = setOf(keyFeishu.id))
        assertEquals(listOf(keyWechat), out.toList())
    }

    @Test
    fun `visibleKeys 空输入返回空集`() {
        assertTrue(visible(emptyList()).isEmpty())
    }

    @Test
    fun `visibleKeys 全被拦时返回空集而不是抛`() {
        assertTrue(visible(listOf(keyWechat), filter = feishu).isEmpty())
    }

    @Test
    fun `visibleKeys 的序列重载与列表重载同口径`() {
        val keys = listOf(keyWechat, keyFeishu, keyWechat)
        val fromSeq = ConvVisibilityCore.visibleKeys(
            keys = keys.asSequence(),
            unwatchedIds = emptySet(),
            appFilter = feishu,
            blockedOf = { false },
            enabledOf = { true }
        )
        assertEquals(visible(keys, filter = feishu), fromSeq)
    }

    @Test
    fun `分析门禁传 ALL 时不受首页筛选影响`() {
        val keys = listOf(keyWechat, keyFeishu)
        assertEquals(2, visible(keys, filter = AppFilterCore.ALL).size)
        assertEquals(1, visible(keys, filter = wechat).size)
    }

    @Test
    fun `forced 的 visibleKeys 放行停用来源但保留筛选`() {
        val keys = listOf(keyWechat, keyFeishu)
        val forced = ConvVisibilityCore.visibleKeys(
            keys = keys,
            unwatchedIds = emptySet(),
            appFilter = AppFilterCore.ALL,
            blockedOf = { false },
            enabledOf = { false },
            forced = true
        )
        assertEquals(2, forced.size)

        val forcedFiltered = ConvVisibilityCore.visibleKeys(
            keys = keys,
            unwatchedIds = emptySet(),
            appFilter = feishu,
            blockedOf = { false },
            enabledOf = { false },
            forced = true
        )
        assertEquals(listOf(keyFeishu), forcedFiltered.toList())
    }

    // ---------- 与真实注册表串起来 ----------

    @Test
    fun `微信与飞书都不是系统源`() {
        assertFalse(AppSourceRegistry.isBlocked(wechat))
        assertFalse(AppSourceRegistry.isBlocked(feishu))
    }

    @Test
    fun `自身包名是系统源`() {
        assertTrue(AppSourceRegistry.isBlocked(self))
    }

    @Test
    fun `空会话名与空包名不特殊处理交给上层判空`() {
        val empty = ConvKey.of("", "")
        assertNull(why(key = empty))
    }
}
