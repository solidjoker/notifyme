package com.notifyme.android

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AnalysisCaseRecord] 的 JSON 读写兼容性单测。
 *
 * 钉的是第四轮报告外发现第 1 条：org.json 的**单参** optDouble(name) 在字段缺失时
 * 返回 NaN（缺省值就是 NaN），所以「缺 s1 块」的旧记录读进来就带着 NaN，
 * 再 toJson 就抛 JSONException: JSON does not allow non-finite numbers——
 * 落库（patchRecords 原子重写）与上报（/analysis 请求体）双双炸掉。
 *
 * 修复口径：读入缺省一律 0.0，写出前再过一道有限性闸（finiteOrZero）。
 */
class AnalysisRecordJsonTest {

    /** 一条典型的旧记录：没有 s1 / forks / rejected 三件套。 */
    private fun legacyJson(): JSONObject = JSONObject()
        .put("case_id", "c0")
        .put("conversation", "测试会话")
        .put("window_end", 5_000L)
        .put("message_count", 3)
        .put("analyzed_at", 5_000L)
        .put("protocol", "laya")

    @Test
    fun `缺 s1 块的旧记录读取后数值字段都是有限数`() {
        val record = AnalysisCaseRecord.fromJson(legacyJson())
        assertNotNull(record)
        val r = record!!
        assertTrue(r.s1NeedActionProb.isFinite())
        assertTrue(r.s1Importance.isFinite())
        assertTrue(r.s1Confidence.isFinite())
        assertEquals(0.0, r.s1NeedActionProb, 0.0)
        assertEquals(0.0, r.s1Importance, 0.0)
        assertEquals(0.0, r.s1Confidence, 0.0)
    }

    @Test
    fun `缺 s1 块的旧记录 toJson 往返不抛异常`() {
        val record = AnalysisCaseRecord.fromJson(legacyJson())!!
        // 修复前就在这一行抛 JSONException: JSON does not allow non-finite numbers
        val text = record.toJson().toString()
        assertTrue(text.contains("case_id"))
        assertTrue(text.contains("c0"))
    }

    @Test
    fun `s1 里缺 importance confidence prob 时缺省为 0`() {
        val obj = legacyJson().put("s1", JSONObject().put("topic", "通知"))
        val record = AnalysisCaseRecord.fromJson(obj)!!
        assertEquals(0.0, record.s1Importance, 0.0)
        assertEquals(0.0, record.s1Confidence, 0.0)
        assertEquals(0.0, record.s1NeedActionProb, 0.0)
        assertEquals("通知", record.s1Topic)
    }

    @Test
    fun `forks 里缺 prob 时缺省为 0 而不是 NaN`() {
        val obj = legacyJson()
            .put("forks", JSONArray().put(JSONObject().put("fork", "prefilter")))
        val record = AnalysisCaseRecord.fromJson(obj)!!
        assertEquals(1, record.forks.size)
        assertTrue(record.forks[0].prob.isFinite())
        assertEquals(0.0, record.forks[0].prob, 0.0)
    }

    @Test
    fun `正常记录的数值在往返后保持不变`() {
        val obj = legacyJson().put(
            "s1",
            JSONObject()
                .put("need_action", JSONObject().put("value", true).put("prob", 0.8))
                .put("importance", 7.0)
                .put("confidence", 0.9)
                .put("topic", "付款")
        )
        val record = AnalysisCaseRecord.fromJson(obj)!!
        assertEquals(0.8, record.s1NeedActionProb, 1e-9)
        assertEquals(7.0, record.s1Importance, 1e-9)
        assertEquals(0.9, record.s1Confidence, 1e-9)

        val round = AnalysisCaseRecord.fromJson(record.toJson())!!
        assertEquals(0.8, round.s1NeedActionProb, 1e-9)
        assertEquals(7.0, round.s1Importance, 1e-9)
        assertEquals(0.9, round.s1Confidence, 1e-9)
        assertEquals("付款", round.s1Topic)
    }

    @Test
    fun `被拒与隔离字段在老记录里缺省为 0 0 false`() {
        val record = AnalysisCaseRecord.fromJson(legacyJson())!!
        assertEquals(0, record.rejectedCount)
        assertEquals(0L, record.firstRejectedAt)
        assertFalse(record.quarantined)
    }

    @Test
    fun `写出前把非有限数抹平成 0`() {
        val record = AnalysisCaseRecord.fromJson(legacyJson())!!
        val broken = record.copy(
            s1NeedActionProb = Double.NaN,
            s1Importance = Double.POSITIVE_INFINITY,
            s1Confidence = Double.NEGATIVE_INFINITY
        )
        val text = broken.toJson().toString()
        assertFalse(text.contains("NaN"))
        assertFalse(text.contains("Infinity"))
        val round = AnalysisCaseRecord.fromJson(JSONObject(text))!!
        assertEquals(0.0, round.s1NeedActionProb, 0.0)
        assertEquals(0.0, round.s1Importance, 0.0)
        assertEquals(0.0, round.s1Confidence, 0.0)
    }
}