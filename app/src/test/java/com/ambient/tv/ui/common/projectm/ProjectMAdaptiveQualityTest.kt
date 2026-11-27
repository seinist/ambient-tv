package com.ambient.tv.ui.common.projectm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProjectMAdaptiveQualityTest {
    @Test
    fun `slow sustained cadence lowers resolution and stable cadence recovers gradually`() {
        val controller = ProjectMAdaptiveQuality()
        var now = 1L
        fun advance(duration: Long, period: Long) {
            repeat((duration / period).toInt()) {
                now += period
                controller.observe(now, 60, true)
            }
        }
        advance(2_000_000_000, 33_333_333)
        assertEquals(0, controller.level)
        advance(3_000_000_000, 33_333_333)
        assertEquals(1, controller.level)
        advance(5_000_000_000, 33_333_333)
        assertEquals(2, controller.level)
        advance(7_000_000_000, 16_666_667)
        assertEquals(2, controller.level)
        advance(5_000_000_000, 16_666_667)
        assertEquals(1, controller.level)
        advance(11_000_000_000, 16_666_667)
        assertEquals(0, controller.level)
    }

    @Test
    fun `pause gap is not treated as overload and manual mode resets adaptive level`() {
        val controller = ProjectMAdaptiveQuality()
        var now = 1L
        repeat(150) {
            now += 33_333_333
            controller.observe(now, 60, true)
        }
        assertEquals(1, controller.level)
        now += 5_000_000_000
        assertNull(controller.observe(now, 60, true))
        assertEquals(1, controller.level)
        assertEquals(0, controller.observe(now + 16_666_667, 60, false))
        assertEquals(0, controller.level)
    }

    @Test
    fun `adaptive cap only limits automatic resolution`() {
        assertEquals(1600 to 900, ProjectMRenderResolution.bufferSize("auto", 1920, 1080, 1920, 1080, 2160, false, 900))
        assertEquals(1280 to 720, ProjectMRenderResolution.bufferSize("auto", 1920, 1080, 1920, 1080, 2160, false, 720))
        assertEquals(3840 to 2160, ProjectMRenderResolution.bufferSize("2160", 1920, 1080, 1920, 1080, 2160, false, 720))
    }
}
