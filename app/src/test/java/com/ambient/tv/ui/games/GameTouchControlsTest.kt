package com.ambient.tv.ui.games

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import com.ambient.tv.ui.gamepad.retro.RetroPadButton
import com.ambient.tv.ui.gamepad.retro.TouchControlStyle
import com.ambient.tv.games.GameTouchMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameTouchControlsTest {
    @Test fun dpadSupportsDiagonalsAndReturnsNeutralAtCenter() {
        val size = IntSize(100, 100)
        assertEquals(emptySet<RetroPadButton>(), touchDpadDirections(Offset(50f, 50f), size))
        assertEquals(setOf(RetroPadButton.LEFT, RetroPadButton.UP), touchDpadDirections(Offset(0f, 0f), size))
        assertEquals(setOf(RetroPadButton.RIGHT, RetroPadButton.DOWN), touchDpadDirections(Offset(99f, 99f), size))
        assertEquals(setOf(RetroPadButton.UP), touchDpadDirections(Offset(50f, 0f), size))
    }

    @Test fun touchSticksClampAndReturnToCenter() {
        val size = IntSize(100, 100)
        assertEquals(Offset.Zero, touchStickAxes(Offset(50f, 50f), size))
        val topLeft = touchStickAxes(Offset(-200f, -200f), size)
        assertTrue(topLeft.x in -1f..0f)
        assertTrue(topLeft.y in -1f..0f)
        assertTrue(topLeft.getDistance() <= 1.01f)
        assertEquals(Offset.Zero, touchStickAxes(Offset.Zero, IntSize.Zero))
    }

    @Test fun portraitDeckKeepsEveryControlReachableAndSeparateOnNarrowPhones() {
        for (width in listOf(320f, 360f, 393f, 480f)) {
            for (mode in GameTouchMode.entries) {
                assertUsableLayout(mode, width, gameTouchDeckHeight(mode, width), portrait = true)
            }
        }
    }

    @Test fun landscapeTargetsDoNotCoverTheMenuOrEachOther() {
        for ((width, height) in listOf(568f to 212f, 568f to 240f, 640f to 280f, 780f to 340f)) {
            for (mode in GameTouchMode.entries) {
                val layout = assertUsableLayout(mode, width, height, portrait = false)
                val menu = androidx.compose.ui.geometry.Rect(12f, 12f, 60f, 60f)
                assertTrue(layout.buttons.values.none { it.overlaps(menu) })
                assertTrue(listOfNotNull(layout.dpad, layout.leftStick, layout.rightStick).none { it.overlaps(menu) })
            }
        }
    }

    @Test fun landscapeUtilitiesAndRewindStayOutsideThePictureAtEverySupportedPadSize() {
        for ((width, height) in listOf(568f to 212f, 640f to 320f, 780f to 340f, 980f to 480f)) {
            for (mode in GameTouchMode.entries) for (scale in listOf(100, 125, 160)) {
                for (presentation in listOf(GameTouchPresentation.Generic, gameTouchPresentation("snes"),
                    gameTouchPresentation("nes"), gameTouchPresentation("megadrive", "md6"),
                    gameTouchPresentation("n64"), gameTouchPresentation("wii"))) {
                    val style = TouchControlStyle(scalePercent = scale)
                    val layout = gameTouchLayout(mode, width, height, false, style, presentation)
                    val top = gameTouchUtilityToolbarHeight(mode, width, height, style, presentation)
                    val picture = Rect(0f, top, width, height)
                    val menu = Rect(12f, 12f, 60f, 60f)
                    val rewind = Rect(width / 2f - 28f, 8f, width / 2f + 28f, 56f)
                    assertTrue("$mode $width x $height scale=$scale has no picture", picture.height > 0f)
                    assertFalse(picture.overlaps(menu))
                    assertFalse(picture.overlaps(rewind))
                    layout.buttons.forEach { (button, bounds) ->
                        if (button !in layout.faces) {
                            assertFalse("$button covers picture at $mode $width x $height scale=$scale",
                                bounds.overlaps(picture))
                        }
                        assertFalse("Rewind covers $button at $mode $width x $height scale=$scale",
                            bounds.overlaps(rewind))
                    }
                }
            }
        }
    }

    @Test fun shortRemotePortraitDeckStillFitsDualStickAndUtilityButtons() {
        for (width in listOf(320f, 360f, 393f)) {
            for (mode in GameTouchMode.entries) {
                assertUsableLayout(mode, width, 380f, portrait = true)
            }
        }
    }

    private fun assertUsableLayout(mode: GameTouchMode, width: Float, height: Float, portrait: Boolean): GameTouchLayout {
        val layout = gameTouchLayout(mode, width, height, portrait)
        val targets = listOfNotNull(layout.dpad, layout.leftStick, layout.rightStick) + layout.buttons.values
        targets.forEach { bounds ->
            assertTrue("$mode $width x $height: $bounds is clipped",
                bounds.left >= 0f && bounds.top >= 0f && bounds.right <= width && bounds.bottom <= height)
            assertTrue("$mode: undersized target $bounds", bounds.width >= 48f && bounds.height >= 48f)
        }
        targets.forEachIndexed { index, bounds ->
            targets.drop(index + 1).forEach { other ->
                assertTrue("$mode $width x $height: $bounds covers $other", !bounds.overlaps(other))
            }
        }
        return layout
    }
}
