package com.ambient.tv.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FullscreenTriangleTest {
    @Test fun oversizedTriangleCoversAllFourScreenCorners() {
        val p = FULLSCREEN_TRIANGLE_VERTICES
        assertEquals(6, p.size)

        fun cross(ax: Float, ay: Float, bx: Float, by: Float, x: Float, y: Float) =
            (bx - ax) * (y - ay) - (by - ay) * (x - ax)
        fun contains(x: Float, y: Float) =
            cross(p[0], p[1], p[2], p[3], x, y) >= -0.0001f &&
                cross(p[2], p[3], p[4], p[5], x, y) >= -0.0001f &&
                cross(p[4], p[5], p[0], p[1], x, y) >= -0.0001f

        // Both halves of the framebuffer are covered by this one primitive.
        for (x in listOf(-1f, 1f)) for (y in listOf(-1f, 1f))
            assertTrue("clip corner ($x, $y) was not covered", contains(x, y))
        assertTrue(contains(0f, 0f))
    }
}
