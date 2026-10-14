package com.ambient.tv.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoRenderTargetTest {
    @Test fun logicalDesktopDoesNotLimitPhysicalOutput() {
        assertEquals(3840 to 2160, physicalVideoRenderSize(1920, 1080, 1920, 1080, 3840, 2160))
        assertEquals(3840 to 2160, physicalVideoRenderSize(1280, 720, 1280, 720, 3840, 2160))
    }
    @Test fun preservesLetterboxAndMiniPlayerGeometry() {
        assertEquals(3840 to 2076, physicalVideoRenderSize(1920, 1038, 1920, 1080, 3840, 2160))
        assertEquals(960 to 540, physicalVideoRenderSize(480, 270, 1920, 1080, 3840, 2160))
    }
    @Test fun followsPhysicalModeChanges() {
        assertEquals(1920 to 1080, physicalVideoRenderSize(1920, 1080, 1920, 1080, 1920, 1080))
        assertEquals(3840 to 2160, physicalVideoRenderSize(1920, 1080, 1920, 1080, 3840, 2160))
        assertNull(physicalVideoRenderSize(0, 1080, 1920, 1080, 3840, 2160))
    }
    @Test fun rendererMatchesCadenceWithoutDroppingOutputResolution() {
        val base = SupportedDisplayMode("4k60", 1, 3840, 2160, 60f)
        val low = SupportedDisplayMode("1080p24", 2, 1920, 1080, 24f)
        val film = SupportedDisplayMode("4k24", 3, 3840, 2160, 24f)
        assertEquals(film, DisplayRefreshRateMatcher.selectRendererMode(listOf(low, film), base, 24f, true, true, false))
        assertEquals(base, DisplayRefreshRateMatcher.selectRendererMode(listOf(low), base, 24f, true, true, false))
        assertEquals(base, DisplayRefreshRateMatcher.selectRendererMode(listOf(low, film), base, 24f, true, true, true))
    }
}
