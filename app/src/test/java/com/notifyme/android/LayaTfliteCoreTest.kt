// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** LayaTfliteEngine core logic tests. */
class LayaTfliteCoreTest {

    private fun makeTok(vocab: Map<String, Int>): LayaTfliteEngine.SpTokenizer =
        LayaTfliteEngine.SpTokenizer(vocab)

    private val baseVocab = mapOf(
        "<bos>" to 0, "</s>" to 1, "<pad>" to 2, "<unk>" to 3, "<mask>" to 4
    )

    @Test
    fun tokenizerGreedyLongestMatch() {
        val vocab = baseVocab + mapOf("abc" to 100, "abcd" to 101, "e" to 102)
        val tok = makeTok(vocab)
        val ids = tok.encode("abcde")
        assertEquals(listOf(101, 102), ids)
    }

    @Test
    fun tokenizerUnknownCharsGoToUnk() {
        val tok = makeTok(baseVocab)
        val ids = tok.encode("xyz")
        assertTrue(ids.drop(1).dropLast(1).all { it == 3 })
    }

    @Test
    fun tokenizerAppendsClsAndSep() {
        val tok = makeTok(baseVocab + mapOf("hi" to 100))
        val ids = tok.encode("hi")
        assertEquals(100, ids.first())
        assertEquals(100, ids.last())
    }

    @Test
    fun calibrationDefaultTemperatureIs1() {
        val f = File.createTempFile("cal", ".json")
        f.writeText("{}")
        val c = LayaTfliteEngine.Calibration.load(f)
        assertEquals(1.0, c.choice, 0.001)
        f.delete()
    }

    @Test
    fun calibrationReadsCustomTemperatures() {
        val f = File.createTempFile("cal", ".json")
        f.writeText("{\"choice\": 0.7, \"score\": 0.8, \"noul\": 0.9}")
        val c = LayaTfliteEngine.Calibration.load(f)
        assertEquals(0.7, c.choice, 0.001)
        assertEquals(0.9, c.noul, 0.001)
        f.delete()
    }

    @Test
    fun softmaxNormalizesToSumOf1() {
        val logits = listOf(2.0f, 1.0f, 0.1f)
        val maxL = logits.max()
        val exps = logits.map { Math.exp((it - maxL).toDouble()) }
        val sum = exps.sum()
        val probs = exps.map { it / sum }
        assertEquals(1.0, probs.sum(), 0.001)
        assertTrue(probs[0] > probs[1])
        assertTrue(probs[1] > probs[2])
    }
}