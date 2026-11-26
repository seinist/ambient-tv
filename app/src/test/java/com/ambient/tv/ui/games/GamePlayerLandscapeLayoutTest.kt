package com.ambient.tv.ui.games

import androidx.compose.ui.geometry.Rect
import com.ambient.tv.games.GameTouchMode
import com.ambient.tv.ui.gamepad.retro.TouchControlStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GamePlayerLandscapeLayoutTest {
    @Test fun snesUsesTheFullLandscapeHeightAndExposesAmbientBehindSideControls() {
        val plan = gamePlayerLandscapeLayout(GameTouchMode.DPAD, 800f, 360f, 4f / 3f,
            touchEnabled = true, presentation = gameTouchPresentation("snes"))
        assertEquals(Rect(160f, 0f, 640f, 360f), plan.surface)
        val controls = listOfNotNull(plan.controls.dpad, plan.controls.faceButtonsBounds) +
            plan.controls.buttons.filterKeys { it !in plan.controls.faces }.values + plan.menu
        controls.forEach { assertFalse("The native surface hides a side control's ambient background: $it",
            it.overlaps(plan.surface)) }
    }

    @Test fun aspectChangesFitTheActualPictureIncludingRotatedAndWideContent() {
        for (aspect in listOf(4f / 3f, 8f / 7f, 1f, 3f / 2f, 16f / 10f, 16f / 9f, 3f / 4f)) {
            for (touch in listOf(false, true)) {
                val plan = gamePlayerLandscapeLayout(GameTouchMode.DPAD, 960f, 400f, aspect,
                    touchEnabled = touch, presentation = gameTouchPresentation("snes"))
                assertEquals(aspect, plan.surface.width / plan.surface.height, .0001f)
                assertEquals(480f, plan.surface.center.x, .001f)
                assertEquals(200f, plan.surface.center.y, .001f)
                assertEquals(400f, plan.surface.height, .001f)
                assertFalse(plan.menu.overlaps(plan.surface))
            }
        }
        assertEquals(gamePictureBounds(640f, 360f, 4f / 3f), gamePictureBounds(640f, 360f, Float.NaN))
        assertEquals(gamePictureBounds(640f, 360f, 4f / 3f), gamePictureBounds(640f, 360f, 0f))
    }

    @Test fun allSideUtilityTargetsRemainReachableWithoutCoveringThePictureOrOtherControls() {
        for ((width, height) in listOf(568f to 212f, 568f to 240f, 640f to 280f,
            568f to 320f, 640f to 360f, 800f to 360f, 960f to 480f)) {
            for (mode in GameTouchMode.entries) for (scale in listOf(100, 125, 140)) {
                for (presentation in listOf(GameTouchPresentation.Generic, gameTouchPresentation("snes"),
                    gameTouchPresentation("nes"), gameTouchPresentation("megadrive", "md6"),
                    gameTouchPresentation("n64"), gameTouchPresentation("wii"), gameTouchPresentation("n3ds"))) {
                    for (position in listOf(0, 100)) for (aspect in listOf(4f / 3f, 16f / 9f, 1f, 3f / 4f)) {
                        val plan = gamePlayerLandscapeLayout(mode, width, height, aspect, true,
                            TouchControlStyle(scalePercent = scale, landscapePosition = position), presentation)
                        val label = "$mode $width x $height scale=$scale position=$position aspect=$aspect ${presentation.shape}"
                        assertUsable(plan, width, height, label)
                    }
                }
            }
        }
    }

    @Test fun shortDualLayoutKeepsEveryButtonAndUsesTheSameInputRouter() {
        val plan = gamePlayerLandscapeLayout(GameTouchMode.DUAL, 568f, 212f, 4f / 3f, true)
        val original = gameTouchLayout(GameTouchMode.DUAL, 568f, 212f, false)
        assertEquals(original.buttons.keys, plan.controls.buttons.keys)
        assertEquals(96f, plan.controls.leftStick!!.width, .001f)
        assertEquals(96f, plan.controls.rightStick!!.width, .001f)
        val input = GameTouchInput(plan.controls)
        for ((button, bounds) in plan.controls.buttons) {
            input.press(1, bounds.center)
            assertEquals("Wrong input at compact $button", setOf(button), input.state.buttons)
            input.release(1)
            assertTrue(input.state.buttons.isEmpty())
        }
    }

    @Test fun integerScalingRetainsTheWholeCentralAreaForSourcePixels() {
        val presentation = gameTouchPresentation("snes")
        val fitted = gamePlayerLandscapeLayout(GameTouchMode.DPAD, 800f, 360f, 4f / 3f,
            true, presentation = presentation)
        val integer = gamePlayerLandscapeLayout(GameTouchMode.DPAD, 800f, 360f, 4f / 3f,
            true, presentation = presentation, fitSurface = false)
        assertEquals(360f, integer.surface.height, .001f)
        assertTrue(integer.surface.width >= 640f)
        assertTrue(fitted.surface.width < 640f)
        // A 640×224 SNES frame still has room for one native whole-pixel scale, even though
        // the chosen display aspect is 4:3. The UI must not narrow that fit to 480 pixels.
        assertEquals(1, minOf(integer.surface.width / 640f, integer.surface.height / 224f).toInt())
        assertFalse(integer.menu.overlaps(integer.surface))
    }

    private fun assertUsable(plan: GamePlayerLandscapeLayout, width: Float, height: Float, label: String) {
        assertTrue("$label has no picture", plan.surface.width > 0f && plan.surface.height > 0f)
        assertTrue("$label picture is clipped", inside(plan.surface, width, height))
        val pad = plan.controls
        val utilities = pad.buttons.filterKeys { it !in pad.faces }.values + plan.menu
        utilities.forEach { assertFalse("$label utility $it covers the picture", it.overlaps(plan.surface)) }
        val targets = listOfNotNull(pad.dpad, pad.leftStick, pad.rightStick) + pad.buttons.values + plan.menu
        targets.forEach {
            assertTrue("$label target $it is clipped", inside(it, width, height))
            assertTrue("$label lost a 48 dp target: $it", it.width >= 47.999f && it.height >= 47.999f)
        }
        // The face cluster owns its inter-button rolling sectors too.
        val receivers = listOfNotNull(pad.dpad, pad.leftStick, pad.rightStick, pad.faceButtonsBounds) + utilities
        receivers.forEachIndexed { index, bounds -> receivers.drop(index + 1).forEach { other ->
            assertFalse("$label $bounds overlaps $other", bounds.overlaps(other))
        } }
        assertTrue("$label restore control is clipped", inside(plan.showTouch, width, height))
        assertFalse("$label restore control covers picture", plan.showTouch.overlaps(plan.surface))
        assertFalse("$label restore control covers menu", plan.showTouch.overlaps(plan.menu))
    }

    private fun inside(rect: Rect, width: Float, height: Float) = rect.left >= -.001f &&
        rect.top >= -.001f && rect.right <= width + .001f && rect.bottom <= height + .001f
}
