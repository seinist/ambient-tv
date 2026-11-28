package com.ambient.tv.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertSame
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertArrayEquals
import org.junit.Test

/** The ambient backdrop glides between captures instead of cross-fading snapshots. */
class AmbientGlideTest {
    private fun red(p: Int) = p shr 16 and 0xFF

    @Test fun glidesContinuouslyFromBlackTowardsTheCapture() {
        val glide = AmbientGlide()
        glide.setTarget(IntArray(4) { 0xFFFF0000.toInt() }, 2, 2)
        var previous = 0
        // ~30 updates per second: every step moves a little, never a jump.
        repeat(30) {
            assertTrue(glide.step(33f))
            val now = red(glide.pixels()[0])
            assertTrue(now >= previous)
            assertTrue("step ${now - previous}", now - previous <= 15)
            previous = now
        }
        // After one second three quarters of the way, settled after about five seconds.
        assertTrue(previous in 180..210)
        repeat(130) { glide.step(33f) }
        assertEquals(255, red(glide.pixels()[0]))
        assertFalse(glide.step(33f))
    }

    @Test fun aNewCaptureContinuesFromTheShownColours() {
        val glide = AmbientGlide()
        glide.setTarget(IntArray(1) { 0xFFFF0000.toInt() }, 1, 1)
        repeat(20) { glide.step(33f) }
        val before = red(glide.pixels()[0])
        glide.setTarget(IntArray(1) { 0xFF000000.toInt() }, 1, 1)
        glide.step(33f)
        // No cut back to black: the next frame starts just below where the glide was.
        assertTrue(red(glide.pixels()[0]) in (before - 15) until before)
    }

    @Test fun aSizeChangeStartsAgainFromBlack() {
        val glide = AmbientGlide()
        glide.setTarget(IntArray(1) { 0xFFFFFFFF.toInt() }, 1, 1)
        repeat(100) { glide.step(33f) }
        glide.setTarget(IntArray(2) { 0xFFFFFFFF.toInt() }, 1, 2)
        assertEquals(0, red(glide.pixels()[0]))
        assertEquals(1 to 2, glide.width to glide.height)
    }

    @Test fun identicalTargetsDoNotWakeSettledGlideAndStorageIsReused() {
        val glide = AmbientGlide()
        val capture = intArrayOf(0xFF112233.toInt(), 0xFFFF0000.toInt())
        assertTrue(glide.setTarget(capture, 2, 1))
        val output = glide.pixels()
        assertFalse(glide.setTarget(capture, 2, 1))
        capture[0] = 0 // The caller can immediately reuse its capture buffer.
        repeat(160) {
            glide.step(33f)
            assertSame(output, glide.pixels())
        }
        assertFalse(glide.step(33f))
        assertArrayEquals(intArrayOf(0xFF112233.toInt(), 0xFFFF0000.toInt()), output)
        assertFalse(glide.setTarget(output, 2, 1))
        assertTrue(glide.setTarget(intArrayOf(0xFF001122.toInt(), 0xFF000000.toInt()), 2, 1))
        assertTrue(glide.step(Float.MAX_VALUE)) // Reduced-effects mode still snaps.
        assertFalse(glide.step(33f))
        assertSame(output, glide.pixels())
        assertArrayEquals(intArrayOf(0xFF001122.toInt(), 0xFF000000.toInt()), output)
    }

    @Test fun blackCaptureWithNewDimensionsStillSignalsAReplacement() {
        val glide = AmbientGlide()
        assertTrue(glide.setTarget(intArrayOf(0), 1, 1))
        val output = glide.pixels()
        assertFalse(glide.step(33f))
        assertTrue(glide.setTarget(intArrayOf(0, 0), 1, 2))
        assertNotSame(output, glide.pixels())
        assertFalse(glide.step(33f))
        assertFalse(glide.setTarget(intArrayOf(0xFF000000.toInt(), 0), 1, 2))
    }

}
