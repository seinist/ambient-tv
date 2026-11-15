package com.ambient.tv.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    @Test fun fullHdUiOnAFourKLogicalWindowKeepsFourKPanelVideo() {
        assertEquals(3840 to 2160,
            physicalVideoRenderSize(1920, 1080, 3840, 2160, 3840, 2160, 2.0, 2.0))
        // Native UI on a system-provided 1080p desktop must not double the video to 8K.
        assertEquals(3840 to 2160,
            physicalVideoRenderSize(3840, 2160, 1920, 1080, 3840, 2160, 0.5, 0.5))
    }
    @Test fun automaticWindowTargetPreservesItsOriginalBudgetInBothUiScalingDirections() {
        assertEquals(3840 to 2160, windowVideoRenderSize(1920, 1080, 2.0, 2.0))
        assertEquals(1920 to 1080, windowVideoRenderSize(3840, 2160, 0.5, 0.5))
        assertEquals(1920 to 1080, windowVideoRenderSize(1920, 1080))
    }
    @Test fun scaledUiKeepsSmallerVideoWindowsAndLetterboxingProportional() {
        assertEquals(960 to 540,
            physicalVideoRenderSize(480, 270, 3840, 2160, 3840, 2160, 2.0, 2.0))
        assertEquals(960 to 540, windowVideoRenderSize(480, 270, 2.0, 2.0))
        assertEquals(3840 to 1608,
            physicalVideoRenderSize(1920, 804, 3840, 2160, 3840, 2160, 2.0, 2.0))
        // The same scaled window follows a real display-mode change to 1080p.
        assertEquals(1920 to 1080,
            physicalVideoRenderSize(1920, 1080, 3840, 2160, 1920, 1080, 2.0, 2.0))
    }
    @Test fun directAndExplicitUiNeverFixTheBufferWhileAutoOnlyCorrectsScaledUi() {
        for (key in listOf("auto", "panel", "ui")) {
            val direct = gpuRenderTargetMode(key, gpuOutput = false, atDisplayResolution = false)
            assertEquals(VideoRenderTargetMode.UI, direct)
            assertFalse(direct.fixesBuffer(uiScaled = true))
        }
        val explicitUi = gpuRenderTargetMode("ui", gpuOutput = true, atDisplayResolution = false)
        assertFalse(explicitUi.fixesBuffer(uiScaled = true))
        assertEquals(1920 to 1080, windowVideoRenderSize(1920, 1080))
        val auto = gpuRenderTargetMode("auto", gpuOutput = true, atDisplayResolution = false)
        assertEquals(VideoRenderTargetMode.WINDOW, auto)
        assertFalse(auto.fixesBuffer(uiScaled = false))
        assertTrue(auto.fixesBuffer(uiScaled = true))
        assertEquals(VideoRenderTargetMode.DISPLAY,
            gpuRenderTargetMode("panel", gpuOutput = true, atDisplayResolution = true))
    }
    @Test fun invalidWindowScalesDoNotProduceBuffers() {
        for (scale in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertNull(windowVideoRenderSize(1920, 1080, scale, 1.0))
            assertNull(physicalVideoRenderSize(1920, 1080, 3840, 2160, 3840, 2160, 1.0, scale))
        }
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
