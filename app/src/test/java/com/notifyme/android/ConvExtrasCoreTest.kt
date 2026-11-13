// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ConvExtrasCore] 的取值口径测试。
 *
 * 契约（第十四轮审计）：包名空白回落微信且不 trim；会话名空/空白 = 没有会话；
 * 会话名不 trim 不切分隔符。
 */
class ConvExtrasCoreTest {

    private val wx = AppSourceRegistry.PKG_WECHAT

    // ---------------- pkg ----------------

    @Test
    fun `pkg 缺失回落微信`() {
        assertEquals(wx, ConvExtrasCore.pkg(null))
    }

    @Test
    fun `pkg 空串回落微信`() {
        assertEquals(wx, ConvExtrasCore.pkg(""))
    }

    @Test
    fun `pkg 纯空白回落微信`() {
        assertEquals(wx, ConvExtrasCore.pkg("   "))
        assertEquals(wx, ConvExtrasCore.pkg("\t\n"))
    }

    @Test
    fun `pkg 非空白原样透传不移除首尾空白`() {
        assertEquals("com.ss.android.lark", ConvExtrasCore.pkg("com.ss.android.lark"))
        // 包名带空白是调用方 bug，不该在这里被悄悄修掉，否则日志里看不到真实入参
        assertEquals(" com.ss.android.lark ", ConvExtrasCore.pkg(" com.ss.android.lark "))
    }

    // ---------------- conversation ----------------

    @Test
    fun `conversation 缺失变空串`() {
        assertEquals("", ConvExtrasCore.conversation(null))
    }

    @Test
    fun `conversation 空串与纯空白都变空串`() {
        assertEquals("", ConvExtrasCore.conversation(""))
        assertEquals("", ConvExtrasCore.conversation("  "))
        assertEquals("", ConvExtrasCore.conversation("\u3000"))
    }

    @Test
    fun `conversation 不 trim 保留首尾空白`() {
        assertEquals(" 项目群 ", ConvExtrasCore.conversation(" 项目群 "))
    }

    @Test
    fun `conversation 含竖线时原样保留`() {
        assertEquals("甲|乙", ConvExtrasCore.conversation("甲|乙"))
    }

    // ---------------- key ----------------

    @Test
    fun `key 正常拼出 pkg 与 conversation`() {
        val key = ConvExtrasCore.key("项目群", "com.alibaba.android.rimet")
        assertEquals(ConvKey("com.alibaba.android.rimet", "项目群"), key)
    }

    @Test
    fun `key 包名缺失回落微信`() {
        assertEquals(ConvKey(wx, "张三"), ConvExtrasCore.key("张三", null))
    }

    @Test
    fun `key 会话名 null 空串纯空白都返回 null`() {
        assertNull(ConvExtrasCore.key(null, "com.tencent.mm"))
        assertNull(ConvExtrasCore.key("", "com.tencent.mm"))
        assertNull(ConvExtrasCore.key("   ", "com.tencent.mm"))
    }

    @Test
    fun `key 入参顺序是会话名在前包名在后`() {
        // 反着传会得到一个 pkg 为会话名、conversation 为包名的怪键 —— 顺序必须能靠断言钉住
        val key = ConvExtrasCore.key("甲群", "com.sina.weibo")!!
        assertEquals("甲群", key.conversation)
        assertEquals("com.sina.weibo", key.pkg)
    }

    @Test
    fun `key 与 ConvKey legacy 同口径`() {
        // schema v1 的 Intent 不带包名：新口径必须和旧兜底落到同一个键
        assertEquals(ConvKey.legacy("张三"), ConvExtrasCore.key("张三", null))
        assertEquals(ConvKey.legacy("张三"), ConvExtrasCore.key("张三", "   "))
    }

    @Test
    fun `key 会话名含竖线时 id 与 ConvKey parse 一致`() {
        val key = ConvExtrasCore.key("甲|乙", null)!!
        assertEquals("甲|乙", key.conversation)
        // id 用竖线拼，parse 只切第一个分隔符：往返必须回到原键
        assertEquals(key, ConvKey.parse(key.id))
    }

    @Test
    fun `key 会话名含竖线不会被提前切坏`() {
        // 有人想在这里顺手 split("|") —— 群名里的竖线会被切成两半，会话就再也对不上
        val key = ConvExtrasCore.key("市场|运营群", "com.tencent.mm")!!
        assertEquals("市场|运营群", key.conversation)
        assertEquals("com.tencent.mm|市场|运营群", key.id)
    }

    // ---------------- 与旧实现的对照实验 ----------------

    /** 旧写法：四个 Activity 里逐字重复的那段内联解析。 */
    private fun legacyKey(conv: String?, pkg: String?): ConvKey? {
        val c = conv.orEmpty()
        if (c.isEmpty()) return null
        val p = pkg?.takeIf { it.isNotBlank() } ?: wx
        return ConvKey(p, c)
    }

    /** 旧写法（分析列表 / 提醒列表的变体）：空会话 = 不筛选，不做 finish。 */
    private fun legacyFilterKey(conv: String?, pkg: String?): ConvKey? {
        val c = conv ?: return null
        val p = pkg?.takeIf { it.isNotBlank() } ?: wx
        return ConvKey(p, c)
    }

    private val samples: List<Pair<String?, String?>> = listOf(
        null to null,
        "" to null,
        "张三" to null,
        "张三" to "",
        "张三" to "   ",
        "张三" to "com.tencent.mm",
        "项目群" to "com.alibaba.android.rimet",
        "甲|乙" to "com.sina.weibo",
        " 项目群 " to null,
        "   " to "com.ss.android.lark"
    )

    @Test
    fun `key 与旧的 finish 分支实现逐条一致`() {
        var same = 0
        for ((conv, pkg) in samples) {
            val fresh = ConvExtrasCore.key(conv, pkg)
            val old = legacyKey(conv, pkg)
            if (old == null) {
                assertNull("旧实现会 finish，新实现必须返回 null：conv=" + conv, fresh)
                same++
            } else if (old.conversation.isBlank()) {
                // 唯一的刻意差异：纯空白会话名旧实现会构造一个永远匹配不到的键，
                // 新口径按「没有会话」处理（页面关闭 / 不做筛选），否则界面显示空标题、列表恒空。
                assertNull("纯空白会话名新口径按无会话处理：conv=" + conv, fresh)
            } else {
                assertEquals("conv=" + conv + " pkg=" + pkg, old, fresh)
                same++
            }
        }
        assertTrue("样本里至少有 5 条走了完全一致的分支", same >= 5)
    }

    @Test
    fun `key 与旧的筛选分支实现逐条一致`() {
        for ((conv, pkg) in samples) {
            val fresh = ConvExtrasCore.key(conv, pkg)
            val old = legacyFilterKey(conv, pkg)
            if (old == null || old.conversation.isBlank()) {
                assertNull("conv=" + conv, fresh)
            } else {
                assertEquals("conv=" + conv + " pkg=" + pkg, old, fresh)
            }
        }
    }

    @Test
    fun `纯空白会话名是刻意的行为收敛`() {
        // 旧实现的空白分支在四个页面表现不一致：会话详情显示空标题，列表页空列表。
        // 新口径统一成「没有会话」，两处都落到各自的本意（关页 / 不筛选）。
        assertNull(ConvExtrasCore.key("   ", null))
        assertEquals(ConvKey(wx, "   x"), ConvExtrasCore.key("   x", null))
    }
}
