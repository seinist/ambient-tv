package com.ambient.tv.ui.settings

import com.ambient.tv.ui.gamepad.retro.RetroPadButton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InputConsoleVectorLayoutsTest {
    private val imported = listOf(
        Triple("nes", "", "nes"), Triple("snes", "", "snes"),
        Triple("megadrive", "md3", "mega-drive"), Triple("megadrive", "sms", "master-system"),
        Triple("sega32x", "md3", "mega-drive"), Triple("n64", "", "n64"),
        Triple("psx", "", "ps1"), Triple("ps2", "", "ps2"),
        Triple("gamecube", "", "gamecube"), Triple("wii", "remote", "wii"),
        Triple("wiiu", "", "wii-u"), Triple("dreamcast", "", "dreamcast"),
        Triple("saturn", "", "sega-saturn"), Triple("vb", "", "virtual-boy"),
        Triple("a2600", "", "atari-2600"),
    )

    @Test fun importedCoreControlsHaveUniqueTargetsAndStayInsideTheirViewport() {
        imported.forEach { (system, variant, vector) ->
            val art = consoleArtwork(system, variant)
            assertEquals("$system/$variant", vector, art.vectorId)
            assertEquals("Duplicate key IDs: $system", art.keys.size, art.keys.map { it.id }.distinct().size)
            assertEquals("Duplicate targets: $system", art.keys.size, art.keys.map { it.target }.distinct().size)
            assertTrue("No visible controls: $system", art.keys.any { !it.auxiliary })
            art.keys.filterNot { it.auxiliary }.forEach { key ->
                val halfWidth = key.widthFraction / 2f
                val halfHeight = key.heightFraction * art.aspectRatio / 2f
                assertTrue("$system/${key.id}: invalid dimensions", halfWidth > 0f && halfHeight > 0f)
                assertTrue("$system/${key.id}: left clipped", key.x - halfWidth >= 0f)
                assertTrue("$system/${key.id}: right clipped", key.x + halfWidth <= 1f)
                assertTrue("$system/${key.id}: top clipped", key.y - halfHeight >= 0f)
                assertTrue("$system/${key.id}: bottom clipped", key.y + halfHeight <= 1f)
            }
        }
    }

    @Test fun importingGeometryPreservesTheExistingLabelsTargetsAndOrder() {
        val original = InputArtwork("Existing template", InputArtworkShape.OVAL, listOf(
            InputArtworkKey("a", "Keep A", .1f, .2f, RetroPadButton.A),
            InputArtworkKey("select", "Keep Select", .3f, .4f, RetroPadButton.SELECT, wide = true),
            InputArtworkKey("unrepresented", "Keep auxiliary", .5f, .6f, RetroPadButton.L2),
        ))
        imported.forEach { (system, variant, _) ->
            val actual = original.withConsoleVector(system, variant)
            assertEquals(original.title, actual.title)
            assertEquals(original.keys.map { listOf(it.id, it.label, it.target, it.wide) },
                actual.keys.map { listOf(it.id, it.label, it.target, it.wide) })
            assertTrue(actual.keys.single { it.id == "unrepresented" }.auxiliary)
        }
    }

    @Test fun incompatibleControllerVariantsRetainTheirOriginalArtwork() {
        listOf("" to "megadrive", "md6" to "megadrive", "gg" to "megadrive", "md6" to "sega32x",
            "nunchuk" to "wii", "" to "gba").forEach { (variant, system) ->
            assertNull("$system/$variant", consoleArtwork(system, variant).vectorId)
        }
    }

    @Test fun rearAndConsoleControlsAreNotDrawnOnUnrelatedFrontButtons() {
        assertTrue(consoleArtwork("wii").keys.single { it.id == "b" }.auxiliary)
        assertTrue(consoleArtwork("n64").keys.single { it.id == "l2" }.auxiliary)
        assertTrue(consoleArtwork("gamecube").keys.single { it.id == "r2" }.auxiliary)
        assertTrue(consoleArtwork("a2600").keys.single { it.id == "start" }.auxiliary)
        assertTrue(consoleArtwork("vb").keys.filter { it.id == "x" || it.id == "y" }.all { it.auxiliary })
        assertTrue(consoleArtwork("megadrive", "sms").keys.filter { it.id == "start" || it.id == "select" }.all { it.auxiliary })
    }

    @Test fun dreamcastLabelsFollowThePhysicalButtonPositionsWithoutChangingRetroPadTargets() {
        val keys = consoleArtwork("dreamcast").keys.associateBy { it.id }
        val a = keys.getValue("a")
        val b = keys.getValue("b")
        val x = keys.getValue("x")
        val y = keys.getValue("y")
        assertEquals("A", a.label)
        assertEquals(RetroPadButton.A, a.target)
        assertEquals("B", b.label)
        assertEquals(RetroPadButton.B, b.target)
        assertTrue(a.y > b.y && a.x < b.x)
        assertTrue(x.x < y.x && x.y > y.y)
        assertFalse(a.auxiliary || b.auxiliary || x.auxiliary || y.auxiliary)
    }
}
