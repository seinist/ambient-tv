package com.ambient.tv.ui.player

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackStatsOverlayStackTest {
    private val window = IntSize(1920, 1080)
    private val popup = IntSize(220, 30)
    private val inset = IntOffset(48, 56)

    @Test
    fun keepsPreferredCornerWithoutFpsOverlay() {
        assertEquals(inset, stackedStatsPopupOffset(inset, popup, window, null, 12))
    }

    @Test
    fun stacksBelowOverlappingFpsOverlay() {
        val fps = IntRect(32, 32, 520, 110)
        assertEquals(IntOffset(48, 122), stackedStatsPopupOffset(inset, popup, window, fps, 12))
    }

    @Test
    fun ignoresFpsOverlayInAnotherCorner() {
        val fps = IntRect(1500, 32, 1888, 110)
        assertEquals(inset, stackedStatsPopupOffset(inset, popup, window, fps, 12))
    }

    @Test
    fun staysInsideWindow() {
        val fps = IntRect(0, 0, 600, 1070)
        assertEquals(IntOffset(48, 1050), stackedStatsPopupOffset(inset, popup, window, fps, 12))
    }

    @Test
    fun emptyBoundsAreIgnored() {
        assertEquals(inset, stackedStatsPopupOffset(inset, popup, window, IntRect(0, 0, 0, 0), 12))
    }
}
