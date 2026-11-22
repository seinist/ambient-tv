package com.ambient.tv.ui.settings

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.ambient.tv.ui.gamepad.ControllerModel
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class InputArtworkGeometryTest {
    @Test fun everyControllerKeepsItsNativeAspectRatioAcrossPhoneAndTvWidths() {
        ControllerModel.entries.forEach { model ->
            val art = attachedArtwork("controller", controllerModel = model)
            listOf(280f, 320f, 360f, 399f, 400f, 600f, 1000f).forEach { width ->
                val fitted = fitInputArtwork(art, width, 340f)
                assertEquals(model.key, art.aspectRatio, fitted.width / fitted.height, .0001f)
                assertTrue(fitted.width <= width && fitted.height <= 340.001f)
                art.keys.filterNot { it.auxiliary }.forEach { key ->
                    val bounds = inputArtworkKeyBounds(key, fitted)
                    assertTrue("${model.key}/${key.id}: $bounds clipped at $width", bounds.left >= 0f &&
                        bounds.top >= 0f && bounds.right <= fitted.width && bounds.bottom <= fitted.height)
                    assertEquals(key, inputArtworkKeyAt(art, fitted, bounds.center, 0f))
                }
            }
        }
    }

    @Test fun artworkAndRoundMarkersScaleTogetherWithoutTheOld400dpJump() {
        val art = attachedArtwork("controller", controllerModel = ControllerModel.XBOX_SERIES)
        val a = art.keys.single { it.id == "a" }
        val before = inputArtworkKeyBounds(a, fitInputArtwork(art, 399f, 500f))
        val after = inputArtworkKeyBounds(a, fitInputArtwork(art, 400f, 500f))
        assertEquals(before.width, before.height, .0001f)
        assertEquals(after.width, after.height, .0001f)
        assertEquals(400f / 399f, after.width / before.width, .0001f)
        assertEquals(400f / 399f, after.center.x / before.center.x, .0001f)
        assertEquals(400f / 399f, after.center.y / before.center.y, .0001f)
    }

    @Test fun remoteAndKeyboardKeysStaySeparateAndInsideTheirHousing() {
        val previous = Locale.getDefault()
        try {
            listOf(Locale.GERMAN, Locale.ENGLISH).forEach { locale ->
                Locale.setDefault(locale)
                listOf("remote", "keyboard", "companion").forEach { kind ->
                    val art = attachedArtwork(kind)
                    val size = fitInputArtwork(art, 296f, 300f)
                    val keys = art.keys.filterNot { it.auxiliary }
                    assertEquals(keys.size, keys.map { it.id }.distinct().size)
                    keys.forEachIndexed { index, key ->
                        val bounds = inputArtworkKeyBounds(key, size)
                        assertTrue("$locale/$kind/${key.id}: $bounds clipped", bounds.left >= 0f &&
                            bounds.top >= 0f && bounds.right <= size.width && bounds.bottom <= size.height)
                        keys.drop(index + 1).forEach { other ->
                            assertFalse("$kind/${key.id} overlaps ${other.id}",
                                bounds.overlaps(inputArtworkKeyBounds(other, size)))
                        }
                    }
                }
            }
        } finally { Locale.setDefault(previous) }
    }

    @Test fun denseTouchTargetsResolveToTheNearestKeyAndNeverToRearButtons() {
        val art = attachedArtwork("remote")
        val size = fitInputArtwork(art, 320f, 300f)
        val red = art.keys.single { it.id == "red" }
        val green = art.keys.single { it.id == "green" }
        val redCenter = inputArtworkKeyBounds(red, size).center
        val greenCenter = inputArtworkKeyBounds(green, size).center
        // Both forgiving touch areas include this point: the closest visible key wins.
        assertEquals(red, inputArtworkKeyAt(art, size, redCenter + (greenCenter - redCenter) * .4f, 20f))
        assertNull(inputArtworkKeyAt(art, size, Offset(-50f, -50f), 10f))
        assertNull(inputArtworkKeyAt(art.copy(keys = listOf(red.copy(auxiliary = true))), size, redCenter, 20f))
    }

    @Test fun invalidOrUnmeasuredContainersHaveNoDrawableArea() {
        val art = attachedArtwork("controller")
        assertEquals(Size.Zero, fitInputArtwork(art, 0f, 300f))
        assertEquals(Size.Zero, fitInputArtwork(art, 300f, -1f))
        assertEquals(Size.Zero, fitInputArtwork(art, Float.POSITIVE_INFINITY, 300f))
        assertEquals(Size.Zero, fitInputArtwork(art, Float.NaN, 300f))
    }

    @Test fun n64StickDoesNotPretendToHaveAThumbClickAndNunchukControlsStayOffTheRemote() {
        assertTrue(attachedArtwork("controller", controllerModel = ControllerModel.N64)
            .keys.single { it.id == "l3" }.auxiliary)
        val nunchuk = consoleArtwork("wii", "nunchuk")
        assertTrue(nunchuk.keys.filter { it.id in setOf("b", "l", "r") }.all { it.auxiliary })
    }
}
