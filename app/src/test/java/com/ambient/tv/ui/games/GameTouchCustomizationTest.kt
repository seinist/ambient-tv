package com.ambient.tv.ui.games

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.ambient.tv.games.GameTouchMode
import com.ambient.tv.ui.gamepad.retro.RetroInputConfig
import com.ambient.tv.ui.gamepad.retro.RetroPadButton
import com.ambient.tv.ui.gamepad.retro.TouchControlStyle
import com.ambient.tv.ui.settings.consoleArtworkVariants
import com.ambient.tv.ui.settings.supportedConsoleArtworkIds
import org.junit.Assert.*
import org.junit.Test

class GameTouchCustomizationTest {
    @Test fun sharedStyleAndSystemVariantsRoundTripThroughTheExistingBackupEnvelope() {
        val config = RetroInputConfig(touchStyle = TouchControlStyle(130, 50, 0, 50, 20, 2, true),
            touchVariants = mapOf("megadrive" to "md3", "n64" to "retropad"))
        assertEquals(config, RetroInputConfig.decode(config.encode()))
        assertEquals(TouchControlStyle(), RetroInputConfig.decode(emptyMap()).touchStyle)
        assertEquals(TouchControlStyle(), TouchControlStyle.decode("100,75,0,0,NaN,1,1"))
        assertEquals(TouchControlStyle(140, 25, 0, 100, 35, 0, true),
            TouchControlStyle.decode("999,-20,-10,200,50,-1,1"))
    }

    @Test fun consoleFacesUseTheEditorTargetsAndRecognizableLabels() {
        val nes = gameTouchPresentation("nes")
        assertEquals(listOf(RetroPadButton.B, RetroPadButton.A), nes.faces)
        assertEquals(GameTouchFaceShape.PAIR, nes.shape)
        assertFalse(nes.labels.containsKey(RetroPadButton.L2))
        val ps = gameTouchPresentation("psx")
        assertEquals("✕", ps.labels[RetroPadButton.B])
        assertEquals("△", ps.labels[RetroPadButton.X])
        val sega = gameTouchPresentation("megadrive", "md6")
        assertEquals(GameTouchFaceShape.SIX, sega.shape)
        assertEquals("A", sega.labels[RetroPadButton.Y])
        assertEquals("X", sega.labels[RetroPadButton.L])
        assertEquals("Z", sega.labels[RetroPadButton.R])
        assertEquals(3, gameTouchPresentation("megadrive", "md3").faces.size)
        assertEquals(2, gameTouchPresentation("megadrive", "sms").faces.size)
        assertEquals(GameTouchPresentation.Generic, gameTouchPresentation("nes", "retropad"))
    }

    @Test fun allConsoleTargetsRemainReachableAcrossSizeAndPositionExtremes() {
        var cases = 0
        for (system in supportedConsoleArtworkIds) {
            for (variant in listOf("") + consoleArtworkVariants(system).map { it.first }) {
                for (mode in GameTouchMode.entries) {
                    for ((width, height) in listOf(320f to 380f, 393f to 520f, 480f to 700f,
                        568f to 212f, 780f to 340f, 980f to 480f)) {
                        val portrait = width < height
                        for (scale in listOf(100, 140)) for (position in listOf(0, 50, 100)) {
                            val style = TouchControlStyle(scalePercent = scale, portraitPosition = position,
                                landscapePosition = position)
                            val layout = gameTouchLayout(mode, width, height, portrait, style,
                                gameTouchPresentation(system, variant))
                            val targets = listOfNotNull(layout.dpad, layout.leftStick, layout.rightStick) + layout.buttons.values
                            targets.forEach { bounds ->
                                assertTrue("$system/$variant $mode $width x $height scale=$scale $position: $bounds",
                                    bounds.left >= -.01f && bounds.top >= -.01f && bounds.right <= width + .01f && bounds.bottom <= height + .01f)
                                assertTrue(bounds.width >= 47.99f && bounds.height >= 47.99f)
                            }
                            targets.forEachIndexed { index, bounds -> targets.drop(index + 1).forEach { other ->
                                assertFalse("$system/$variant $mode $width x $height scale=$scale $position: $bounds overlaps $other",
                                    bounds.overlaps(other))
                            } }
                            if (!portrait) {
                                val menu = Rect(12f, 12f, 60f, 60f)
                                assertTrue(targets.none { it.overlaps(menu) })
                            }
                            cases++
                        }
                    }
                }
            }
        }
        assertTrue(cases >= 5_000)
    }

    @Test fun portraitStartSelectShareTheShoulderRowWhenThereIsRoom() {
        val snes = gameTouchPresentation("snes")
        val height = gameTouchDeckHeight(GameTouchMode.DPAD, 360f, presentation = snes)
        val layout = gameTouchLayout(GameTouchMode.DPAD, 360f, height, true, presentation = snes)
        val l = layout.buttons.getValue(RetroPadButton.L)
        val r = layout.buttons.getValue(RetroPadButton.R)
        val select = layout.buttons.getValue(RetroPadButton.SELECT)
        val start = layout.buttons.getValue(RetroPadButton.START)
        // One row under the clusters: L, Select, Start, R; centred, and no extra bottom row.
        for (utility in listOf(select, start)) assertEquals(l.top, utility.top, .01f)
        assertTrue(l.right <= select.left && select.right <= start.left && start.right <= r.left)
        assertEquals(180f, (select.left + start.right) / 2f, .5f)
        assertEquals(height - 8f, maxOf(l.bottom, r.bottom, start.bottom), .5f)
        // PlayStation-style pads with four shoulders keep a separate row on narrow phones only.
        val ps = gameTouchPresentation("psx")
        assertTrue(gameTouchDeckHeight(GameTouchMode.DPAD, 360f, presentation = ps) > height)
        assertEquals(gameTouchDeckHeight(GameTouchMode.DPAD, 412f, presentation = snes),
            gameTouchDeckHeight(GameTouchMode.DPAD, 412f, presentation = ps), .5f)
        // Artwork shorthand becomes the printed names; Wii keeps its real + and −.
        assertEquals("Start", snes.labels[RetroPadButton.START])
        assertEquals("Select", snes.labels[RetroPadButton.SELECT])
        assertEquals("+", gameTouchPresentation("wii").labels[RetroPadButton.START])
    }

    @Test fun twoAndSixFacePadsSupportRollingChordsWithoutPhantomButtons() {
        for ((system, variant) in listOf("nes" to "", "megadrive" to "md3", "megadrive" to "md6")) {
            val layout = gameTouchLayout(GameTouchMode.DPAD, 360f, 380f, true,
                presentation = gameTouchPresentation(system, variant))
            val input = GameTouchInput(layout)
            val first = layout.faces[0]
            val second = layout.faces[1]
            val a = layout.buttons.getValue(first).center
            val b = layout.buttons.getValue(second).center
            input.press(1, a)
            assertEquals(setOf(first), input.state.buttons)
            input.move(1, (a + b) / 2f)
            assertEquals(setOf(first, second), input.state.buttons)
            input.move(1, b)
            assertEquals(setOf(second), input.state.buttons)
            input.release(1)
            assertTrue(input.state.buttons.isEmpty())
        }
    }

    @Test fun userDeadzoneAndDiagonalWidthChangeOnlyTheirInputAreas() {
        val layout = gameTouchLayout(GameTouchMode.HYBRID, 360f, 450f, true)
        val narrow = GameTouchInput(layout, TouchControlStyle(stickDeadzonePercent = 30, diagonalSensitivity = 0))
        val wide = GameTouchInput(layout, TouchControlStyle(stickDeadzonePercent = 0, diagonalSensitivity = 2))
        val stick = layout.leftStick!!
        val nearCenter = stick.center + Offset(stick.width * .08f, 0f)
        narrow.press(1, nearCenter); wide.press(1, nearCenter)
        assertEquals(Offset.Zero, narrow.state.leftStick)
        assertTrue(wide.state.leftStick.x > 0f)
        val direction = layout.dpad!!.let { it.center + Offset(it.width * .4f, it.height * .2f) }
        narrow.press(2, direction); wide.press(2, direction)
        assertEquals(setOf(RetroPadButton.RIGHT), narrow.state.buttons)
        assertEquals(setOf(RetroPadButton.RIGHT, RetroPadButton.DOWN), wide.state.buttons)
    }
}
