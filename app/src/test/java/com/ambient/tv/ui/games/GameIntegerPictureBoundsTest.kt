package com.ambient.tv.ui.games

import androidx.compose.ui.unit.IntRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GameIntegerPictureBoundsTest {
    private val gba = GameContentFrame(240, 160)

    @Test fun unpacksTheNativeSnapshotAndRejectsUnknownSizes() {
        assertEquals(gba, GameContentFrame.unpack((240L shl 32) or 160L))
        assertNull(GameContentFrame.unpack(0L))
        assertNull(GameContentFrame.unpack(240L shl 32))
    }

    @Test fun wholeNumberPictureIsCentredSoTheAmbientFillMeetsItsEdge() {
        // A phone's landscape centre area between the side controls.
        val area = IntRect(300, 0, 2100, 1080)
        val picture = gameIntegerPictureBounds(area, gba, automatic = false, aspect = 1.5f)
        // 1080 / 160 = 6.75 -> 6x: 1440 x 960, not the 1620 x 1080 aspect fit.
        assertEquals(IntRect(480, 60, 1920, 1020), picture)
    }

    @Test fun anExactlySizedSurfaceKeepsTheSameFactorInTheRenderer() {
        val picture = gameIntegerPictureBounds(IntRect(0, 0, 2400, 1080), gba, automatic = false, aspect = 1.5f)
        assertEquals(6, gameIntegerScale(picture.width, picture.height, gba, automatic = false))
        assertEquals(6, gameIntegerScale(picture.width, picture.height, gba, automatic = true))
    }

    @Test fun automaticModeFallsBackToTheAspectFitWithALargeBorder() {
        // 1100 / 160 = 6.875: 6 keeps 87 % (< 90 %), so the renderer fits normally.
        val area = IntRect(0, 0, 3000, 1100)
        assertEquals(0, gameIntegerScale(area.width, area.height, gba, automatic = true))
        val picture = gameIntegerPictureBounds(area, gba, automatic = true, aspect = 1.5f)
        assertEquals(1650, picture.width)
        assertEquals(1100, picture.height)
        assertEquals(1500, picture.center.x)
    }

    @Test fun aScreenSmallerThanOneFrameUsesTheNormalFit() {
        val picture = gameIntegerPictureBounds(IntRect(0, 0, 200, 100), gba, automatic = false, aspect = 1.5f)
        assertEquals(IntRect(25, 0, 175, 100), picture)
    }

    @Test fun aChangedSizeNeedsTwoAgreeingPollsButTheFirstSizeAppliesAtOnce() {
        val filter = GameContentFrameFilter()
        assertEquals(gba, filter.observe(gba))
        assertNull(filter.observe(gba))
        val hiRes = GameContentFrame(512, 224)
        assertNull(filter.observe(hiRes))
        assertNull(filter.observe(gba)) // one alternating frame does not resize the surface.
        assertNull(filter.observe(hiRes))
        assertEquals(hiRes, filter.observe(hiRes))
        assertNull(filter.observe(null)) // unknown keeps the last known size.
    }
}
