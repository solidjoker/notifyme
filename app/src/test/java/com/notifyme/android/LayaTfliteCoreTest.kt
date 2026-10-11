// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import com.notifyme.android.laya.LayaCalibration
import com.notifyme.android.laya.LayaDecoder
import com.notifyme.android.laya.LayaJson
import com.notifyme.android.laya.LayaPromptBuilder
import com.notifyme.android.laya.LayaQuestion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * laya 移植核心（纯 JVM 部分）的契约测试：JSON 保序、校准桶、解码 schema 与
 * Python 官方参考的确定性口径。图谱/分词器部分由设备端 LayaGateRunner 的
 * 201 行 fixture 门验证（本仓库不携带 34MB tokenizer.json）。
 */
class LayaTfliteCoreTest {

    private val delta = 1e-4

    @Test
    fun layaJsonPreservesObjectKeyOrder() {
        val parsed = LayaJson.asObject(
            LayaJson.parse("""{"z":1,"m":2,"a":3,"0":4}""")
        )
        assertEquals(listOf("z", "m", "a", "0"), parsed.keys.toList())
    }

    @Test
    fun layaJsonStringifyRoundTrip() {
        val value = linkedMapOf(
            "name" to "周报",
            "score" to 1.0,
            "flags" to listOf(true, null, 3),
            "nested" to linkedMapOf("k" to "v")
        )
        val text = LayaJson.stringify(value)
        val back = LayaJson.asObject(LayaJson.parse(text))
        assertEquals(listOf("name", "score", "flags", "nested"), back.keys.toList())
        // pythonFloat：整数值 double 序列化为 1.0 而不是 1
        // LayaJson.stringify 用 python json.dumps 默认分隔符（", " 与 ": "），冒号后有空格
        assertTrue("score 应序列化为 1.0：" + text, text.contains("\"score\": 1.0"))
        @Suppress("UNCHECKED_CAST")
        val flags = back["flags"] as List<Any?>
        // LayaJson.parse 与 Python 一致：JSON 整数解析为 Long
        assertEquals(listOf<Any?>(true, null, 3L), flags)
    }

    @Test
    fun calibrationBucketOverridesTypeTemperature() {
        val cal = LayaCalibration.fromMap(
            mapOf(
                "temperature" to listOf(0.7, 0.8, 0.9),
                "temperature_by_options" to mapOf("choice:2" to 0.5, "score:11+" to 0.2)
            )
        )
        assertEquals(0.5, cal.temperature("choice", 2), delta)
        assertEquals(0.7, cal.temperature("choice", 3), delta)   // 落回类型温度
        assertEquals(0.8, cal.temperature("score", 10), delta)
        assertEquals(0.2, cal.temperature("score", 11), delta)
        assertEquals(0.9, cal.temperature("noul", 2), delta)
        assertEquals("choice:2", LayaCalibration.bucket("choice", 1))
        assertEquals("choice:3-5", LayaCalibration.bucket("choice", 5))
        assertEquals("choice:6-10", LayaCalibration.bucket("choice", 6))
        assertEquals("choice:11+", LayaCalibration.bucket("choice", 100))
    }

    @Test
    fun softmaxSumsToOneAndKeepsOrder() {
        val p = LayaDecoder.softmax(floatArrayOf(1f, 2f, 3f))
        assertEquals(1.0, p.sum().toDouble(), 1e-6)
        assertTrue(p[2] > p[1] && p[1] > p[0])
    }

    @Test
    fun confidenceUniformZeroPeakOne() {
        assertEquals(0.0, LayaDecoder.confidence(floatArrayOf(0.5f, 0.5f)), delta)
        assertEquals(1.0, LayaDecoder.confidence(floatArrayOf(0f, 1f)), delta)
        // 单选项无法定义归一化熵 → 视为满置信
        assertEquals(1.0, LayaDecoder.confidence(floatArrayOf(1f)), delta)
    }

    @Test
    fun round4MatchesPythonBankersScale() {
        assertEquals(0.3333, LayaDecoder.round4(1.0 / 3.0), 1e-12)
        assertEquals(0.0, LayaDecoder.round4(0.00002), 1e-12)
    }

    @Test
    fun gatherReadsMarkerPositions() {
        val logits = floatArrayOf(9f, 8f, 7f, 6f)
        assertEquals(listOf(6f, 9f), LayaDecoder.gather(logits, intArrayOf(3, 0)).toList())
    }

    @Test
    fun decodeChoiceProducesOfficialSchemaInOrder() {
        val answer = LayaDecoder.decode(
            floatArrayOf(1f, 2f, 3f),
            floatArrayOf(0f, 5f),
            mapOf(
                "type" to "choice",
                "instructions" to "pick one",
                "criteria" to linkedMapOf("a" to "first", "b" to "second", "c" to "third")
            )
        )
        assertEquals(listOf("type", "choice", "probabilities", "confidence", "action"),
            answer.keys.toList())
        assertEquals("c", answer["choice"])
        val probs = LayaJson.asObject(answer["probabilities"])
        assertEquals(listOf("a", "b", "c"), probs.keys.toList())
        assertEquals(0.6652, (probs["c"] as Number).toDouble(), delta)
        @Suppress("UNCHECKED_CAST")
        val act = answer["action"] as Map<String, Any?>
        assertEquals(0.0067, (act["act_probability"] as Number).toDouble(), delta)
    }

    @Test
    fun decodeScoreExpectationIsIndexWeightedSum() {
        val answer = LayaDecoder.decode(
            floatArrayOf(3f, 2f, 1f),
            floatArrayOf(0f, 5f),
            mapOf("type" to "score", "instructions" to "rate",
                "criteria" to listOf("low", "mid", "high"))
        )
        assertEquals(listOf("type", "score", "legend", "probabilities", "confidence", "action"),
            answer.keys.toList())
        // 0*0.6652 + 1*0.2447 + 2*0.0900 ≈ 0.4247
        assertEquals(0.4247, (answer["score"] as Number).toDouble(), delta)
        val legend = LayaJson.asObject(answer["legend"])
        assertEquals(listOf("0", "1", "2"), legend.keys.toList())
        assertEquals("low", legend["0"])
    }

    @Test
    fun decodeNoulTrueProbabilityIsSecondOption() {
        // noul 选项顺序恒为 false 在前：p[1] 才是 true 概率
        val answer = LayaDecoder.decode(
            floatArrayOf(1f, 3f),
            floatArrayOf(0f, 5f),
            mapOf("type" to "noul", "instructions" to "needs action?")
        )
        assertEquals(listOf("type", "noul", "confidence", "action"), answer.keys.toList())
        assertEquals(0.8808, (answer["noul"] as Number).toDouble(), delta)
        assertEquals(0.8808, (answer["confidence"] as Number).toDouble(), delta)
    }

    @Test
    fun decodeRejectsOptionCountMismatch() {
        assertThrows(IllegalArgumentException::class.java) {
            LayaDecoder.decode(
                floatArrayOf(1f, 2f),
                floatArrayOf(0f, 0f),
                mapOf("type" to "choice", "instructions" to "x",
                    "criteria" to linkedMapOf("a" to null, "b" to null, "c" to null))
            )
        }
    }

    @Test
    fun renderOptionsNoulUsesHostDefaults() {
        val question = LayaPromptBuilder.normalize(
            mapOf("type" to "noul", "instructions" to "statement")
        )
        assertEquals(
            listOf(
                "false: no, the statement does not hold",
                "true: yes, the statement holds"
            ),
            LayaPromptBuilder.renderOptions(question)
        )
        val explicit = LayaPromptBuilder.normalize(
            mapOf("type" to "noul", "instructions" to "s",
                "criteria" to mapOf("false" to "否", "true" to "是"))
        )
        assertEquals(listOf("false: 否", "true: 是"),
            LayaPromptBuilder.renderOptions(explicit))
    }

    @Test
    fun promptConstantsMatchHostContract() {
        // PAD=0 <pad> / SEP=1 <eos> / CLS=2 <bos> / MASK=4——不是 sentencepiece 的 0/1/2/3
        assertEquals(0, LayaPromptBuilder.PAD)
        assertEquals(1, LayaPromptBuilder.SEP)
        assertEquals(2, LayaPromptBuilder.CLS)
        assertEquals(4, LayaPromptBuilder.MASK)
    }

    private fun <K, V> Map<K, V>.assertKeys() = keys.toList()
}
