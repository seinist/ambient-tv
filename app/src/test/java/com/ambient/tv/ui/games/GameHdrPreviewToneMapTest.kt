package com.ambient.tv.ui.games

import com.ambient.tv.games.GameHdrToneMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class GameHdrPreviewToneMapTest {
    private fun red(argb: Int) = (argb ushr 16) and 255
    private fun green(argb: Int) = (argb ushr 8) and 255
    private fun blue(argb: Int) = argb and 255

    @Test fun sdrColorCodesAreNeverModified() {
        val code = GameHdrPreviewToneMap.toneMapCode8(64, 128, 255, GameHdrToneMap.OFF, 200)
        assertEquals(0xc84080ff.toInt(), code)
    }

    @Test fun blackRemainsBlackAndAlphaPreserved() {
        assertEquals(0xc8000000.toInt(), GameHdrPreviewToneMap.toneMapCode8(
            0, 0, 0, GameHdrToneMap.PQ, 200))
        assertEquals(0xff000000.toInt(), GameHdrPreviewToneMap.toneMapCode8(
            0, 0, 0, GameHdrToneMap.HLG))
    }

    @Test fun pqAndHlgProduceDifferentMonotonicToneMappedGreys() {
        for (mode in listOf(GameHdrToneMap.PQ, GameHdrToneMap.HLG)) {
            val shadow = red(GameHdrPreviewToneMap.toneMapCode8(72, 72, 72, mode))
            val mid = red(GameHdrPreviewToneMap.toneMapCode8(152, 152, 152, mode))
            val highlight = red(GameHdrPreviewToneMap.toneMapCode8(220, 220, 220, mode))
            assertTrue("$mode shadow/mid", shadow < mid)
            assertTrue("$mode mid/highlight", mid < highlight)
        }
        val pq = GameHdrPreviewToneMap.toneMapCode8(180, 180, 180, GameHdrToneMap.PQ)
        val hlg = GameHdrPreviewToneMap.toneMapCode8(180, 180, 180, GameHdrToneMap.HLG)
        assertNotEquals(pq, hlg)
        assertTrue(abs(red(pq) - green(pq)) <= 4)
        assertTrue(abs(green(pq) - blue(pq)) <= 4)
    }

    @Test fun boundedOutputForOutOfRangeSoftwareSamples() {
        val a = GameHdrPreviewToneMap.toneMapCode8(-20, 999, 100, GameHdrToneMap.PQ, -10)
        assertEquals(0, (a ushr 24) and 255)
        assertTrue(red(a) in 0..255)
        assertTrue(green(a) in 0..255)
        assertTrue(blue(a) in 0..255)
    }
}
