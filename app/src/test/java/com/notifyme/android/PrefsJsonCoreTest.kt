// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PrefsJsonCore] 的口径测试：SharedPreferences 里存 JSON 字符串的五处站点
 * （WatchlistStore / AppSourceStore / ConvTagStore / ConvAnalysisScheduleStore / AnalysisScopeCore）
 * 都靠这套「读永不抛、损坏按空」的语义，此前只有注释、没有任何回归。
 *
 * 每条断言都写明「为什么必须这样」——损坏的持久化只能让功能退回保守状态，
 * 不能让设置页 / 监听器读一次坏数据就崩。
 */
class PrefsJsonCoreTest {

    // ------------------------------------------------------------------ strings

    @Test
    fun stringArrayKeepsOrderAndDuplicates() {
        val list = PrefsJsonCore.strings("""["b","a","b"]""")
        assertEquals("写入顺序就是展示顺序，不能顺手去重", listOf("b", "a", "b"), list)
    }

    @Test
    fun stringArrayDropsEmptyElements() {
        val list = PrefsJsonCore.strings("""["","com.tencent.mm|x","  "]""")
        assertEquals(
            "只丢真正的空串（旧实现就是 isNotEmpty）；纯空白要等 trim=true 才丢",
            listOf("com.tencent.mm|x", "  "),
            list
        )
        assertEquals(
            "trim=true 时空白串先被削成空串、再被丢掉",
            listOf("com.tencent.mm|x"),
            PrefsJsonCore.strings("""["","com.tencent.mm|x","  "]""", trim = true)
        )
    }

    @Test
    fun stringArrayTrimIsOptIn() {
        assertEquals(listOf("  a  "), PrefsJsonCore.strings("""["  a  "]"""))
        assertEquals(listOf("a"), PrefsJsonCore.strings("""["  a  "]""", trim = true))
    }

    @Test
    fun stringArrayNullAndBlankAreEmpty() {
        assertTrue(PrefsJsonCore.strings(null).isEmpty())
        assertTrue(PrefsJsonCore.strings("").isEmpty())
        assertTrue(PrefsJsonCore.strings("   ").isEmpty())
    }

    @Test
    fun stringArrayMalformedIsEmpty() {
        assertTrue("半截 JSON：写盘中途掉电", PrefsJsonCore.strings("[\"a\",").isEmpty())
        assertTrue("根是对象不是数组", PrefsJsonCore.strings("""{"a":1}""").isEmpty())
        assertTrue("纯垃圾文本", PrefsJsonCore.strings("not json at all").isEmpty())
    }

    @Test
    fun stringArrayReadsNumbersAsStrings() {
        assertEquals(
            "元素走 optString：数字按文本取出，匹配不到任何包名，等价于被忽略",
            listOf("1", "2"),
            PrefsJsonCore.strings("[1,2]")
        )
    }

    @Test
    fun stringSetDedupes() {
        assertEquals(setOf("a", "b"), PrefsJsonCore.stringSet("""["a","b","a"]"""))
    }

    // ------------------------------------------------------------- encodeStrings

    @Test
    fun encodeStringsKeepsIterationOrderByDefault() {
        val encoded = PrefsJsonCore.encodeStrings(listOf("c", "a", "b"))
        assertEquals("关注名单要保持用户点选的顺序", listOf("c", "a", "b"), PrefsJsonCore.strings(encoded))
    }

    @Test
    fun encodeStringsSortsWhenAsked() {
        val encoded = PrefsJsonCore.encodeStrings(listOf("c", "a", "b"), sort = true)
        assertEquals(listOf("a", "b", "c"), PrefsJsonCore.strings(encoded))
    }

    @Test
    fun encodeStringsDropsBlankWhenAsked() {
        val encoded = PrefsJsonCore.encodeStrings(listOf("", "  ", "a"), dropBlank = true)
        assertEquals(listOf("a"), PrefsJsonCore.strings(encoded))
    }

    @Test
    fun encodeStringsOfEmptyIsEmptyArray() {
        assertEquals("[]", PrefsJsonCore.encodeStrings(emptyList()))
    }

    @Test
    fun encodeStringsRoundTripsAwkwardValues() {
        val values = listOf("com.tencent.mm|工作群", "a|b", "带「引号」", "换行\n在里面")
        assertEquals(
            "会话名里带竖线 / 引号 / 换行都要原样往返",
            values,
            PrefsJsonCore.strings(PrefsJsonCore.encodeStrings(values))
        )
    }

    // --------------------------------------------------------------- stringMap

    @Test
    fun stringMapReadsPairs() {
        val map = PrefsJsonCore.stringMap("""{"com.tencent.mm|x":"todo"}""")
        assertEquals(mapOf("com.tencent.mm|x" to "todo"), map)
    }

    @Test
    fun stringMapDropsEmptyKeyButKeepsEmptyValue() {
        val map = PrefsJsonCore.stringMap("""{"":"todo","k":""}""")
        assertEquals(
            "空键不可能对应任何会话；空值由调用方判断语义（标签码非法就该丢）",
            mapOf("k" to ""),
            map
        )
    }

    @Test
    fun stringMapMissingValueBecomesEmptyString() {
        val map = PrefsJsonCore.stringMap("""{"k":null}""")
        assertEquals(mapOf("k" to ""), map)
    }

    @Test
    fun stringMapMalformedIsEmpty() {
        assertTrue(PrefsJsonCore.stringMap(null).isEmpty())
        assertTrue("根是数组不是对象", PrefsJsonCore.stringMap("""["a"]""").isEmpty())
        assertTrue("半截对象", PrefsJsonCore.stringMap("{\"a\":").isEmpty())
    }

    @Test
    fun encodeMapRoundTrips() {
        val map = linkedMapOf("com.tencent.mm|a" to "todo", "com.lark|x" to "noise")
        assertEquals(map, PrefsJsonCore.stringMap(PrefsJsonCore.encodeMap(map)))
    }

    // ----------------------------------------------------------------- longMap

    @Test
    fun longMapKeepsPositiveIntervals() {
        val map = PrefsJsonCore.longMap("""{"com.tencent.mm|x":30}""")
        assertEquals(mapOf("com.tencent.mm|x" to 30L), map)
    }

    @Test
    fun longMapDropsNonPositiveAndGarbage() {
        val map = PrefsJsonCore.longMap(
            """{"a":0,"b":-5,"c":"abc","d":15}""",
            positiveOnly = true
        )
        assertEquals(
            "0 / 负数 / 取不出数的条目在语义上都是「未启用」，留着会污染 all()",
            mapOf("d" to 15L),
            map
        )
    }

    @Test
    fun longMapCanKeepZeroWhenNotFiltering() {
        val map = PrefsJsonCore.longMap("""{"a":0}""", positiveOnly = false)
        assertEquals(mapOf("a" to 0L), map)
    }

    @Test
    fun longMapMalformedIsEmpty() {
        assertTrue(PrefsJsonCore.longMap(null).isEmpty())
        assertTrue(PrefsJsonCore.longMap("{\"a\":").isEmpty())
        assertTrue("根是数组不是对象", PrefsJsonCore.longMap("[1]").isEmpty())
    }

    @Test
    fun encodeLongMapRoundTrips() {
        val map = linkedMapOf("com.tencent.mm|a" to 30L, "com.lark|b" to 60L)
        assertEquals(map, PrefsJsonCore.longMap(PrefsJsonCore.encodeLongMap(map)))
    }

    // ----------------------------------------------------------------- objects

    @Test
    fun objectsProjectsEveryEntry() {
        val raw = """[{"pkg":"a","first_seen":1},{"pkg":"b","first_seen":2}]"""
        val list = PrefsJsonCore.objects(raw) { it.optString("pkg") to it.optLong("first_seen") }
        assertEquals(listOf("a" to 1L, "b" to 2L), list)
    }

    @Test
    fun objectsSkipsNullProjectionWithoutDroppingTheRest() {
        val raw = """[{"pkg":"a"},{"label":"没有包名"},{"pkg":"c"}]"""
        val list = PrefsJsonCore.objects(raw) { obj ->
            obj.optString("pkg").takeIf { it.isNotEmpty() }
        }
        assertEquals("单条坏数据只丢自己，不能牵连整张已观察名单", listOf("a", "c"), list)
    }

    @Test
    fun objectsSkipsNonObjectElements() {
        val raw = """["裸字符串",{"pkg":"a"},1,null]"""
        val list = PrefsJsonCore.objects(raw) { it.optString("pkg") }
        assertEquals(listOf("a"), list)
    }

    @Test
    fun objectsMalformedIsEmpty() {
        assertTrue(PrefsJsonCore.objects(null) { "x" }.isEmpty())
        assertTrue(
            "根是对象不是数组",
            PrefsJsonCore.objects("""{"pkg":"a"}""") { "x" }.isEmpty()
        )
        assertTrue(PrefsJsonCore.objects("[{\"pkg\":") { "x" }.isEmpty())
    }

    // ------------------------------------------------- 与旧实现的逐字对照实验

    @Test
    fun oldInlineCatchAndCoreAgreeOnBrokenInput() {
        val samples = listOf(
            null, "", "   ", "[]", "{}", """["a","","b"]""",
            "[\"a\",", """{"a":1}""", "not json", "[1,2,3]"
        )
        for (raw in samples) {
            assertEquals("旧实现 " + raw, legacyStrings(raw), PrefsJsonCore.strings(raw))
        }
    }

    @Test
    fun oldInlineCatchAndCoreAgreeOnBrokenLongMap() {
        val samples = listOf(
            null, "", "{}", """{"a":30}""", """{"a":0,"b":-1}""",
            "{\"a\":", "[5]", "garbage"
        )
        for (raw in samples) {
            assertEquals("旧实现 " + raw, legacyLongMap(raw), PrefsJsonCore.longMap(raw))
        }
    }

    @Test
    fun readersReturnIndependentCopies() {
        val raw = """["a","b"]"""
        val first = PrefsJsonCore.strings(raw).toMutableList()
        first.add("c")
        assertEquals(
            "读出来的表是拷贝，调用方改它不该影响下一次读",
            listOf("a", "b"),
            PrefsJsonCore.strings(raw)
        )
    }

    // ------------------------------------------------------- 旧实现（逐字保留）

    private fun legacyStrings(raw: String?): List<String> {
        val result = mutableListOf<String>()
        try {
            val arr = JSONArray(raw.orEmpty())
            for (i in 0 until arr.length()) {
                val v = arr.optString(i)
                if (v.isNotEmpty()) result.add(v)
            }
        } catch (e: Exception) {
        }
        return result
    }

    private fun legacyLongMap(raw: String?): Map<String, Long> {
        val result = mutableMapOf<String, Long>()
        try {
            val obj = JSONObject(raw.orEmpty())
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val v = obj.optLong(k, 0L)
                if (k.isNotEmpty() && v > 0) result[k] = v
            }
        } catch (e: Exception) {
        }
        return result
    }
}
