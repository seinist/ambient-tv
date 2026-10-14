package com.ambient.tv.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackAccentTest {
    @Test fun paletteMatchesRgbComplementsRatherThanHslOpposites() {
        val expected = listOf(0x1A5FF2, 0x0075CC, 0xA25600, 0xC7384A, 0x9C3E8B, 0x588400, 0x008857, 0x0099A0)
        AccentOptions.values.zip(expected).forEach { (accent, rgb) ->
            assertEquals(rgb, rgbComplement(accent).toArgb() and 0xFFFFFF)
        }
        assertEquals(AccentOptions.keys, PlaybackAccentOptions.keys)
        AccentOptions.keys.zip(expected).forEach { (key, rgb) ->
            assertEquals(rgb, playbackAccentColorFor(key, "#000000", "#FFFFFF").toArgb() and 0xFFFFFF)
        }
    }

    @Test fun customSecondColorIsIndependentAndFallbackModesAreStable() {
        assertEquals(0xFF00CC, playbackAccentColorFor("custom", "#FF00CC", "#123456").toArgb() and 0xFFFFFF)
        assertEquals(0xFF00CC, playbackAccentColorFor("custom", "#FF00CC", "#FFFFFF").toArgb() and 0xFFFFFF)
        assertEquals(0xEDCBA9, playbackAccentColorFor("custom", null, "#123456").toArgb() and 0xFFFFFF)
        for (key in listOf("artwork", "unknown")) {
            assertEquals(0x1A5FF2, playbackAccentColorFor(key, "#000000").toArgb() and 0xFFFFFF)
        }
    }

    @Test fun customExtremesAndDoubleInversionAreStable() {
        assertEquals(Color.White, rgbComplement(Color.Black))
        assertEquals(Color.Black, rgbComplement(Color.White))
        (AccentOptions.values + Color(0xFF123456)).forEach {
            assertEquals(it.toArgb(), rgbComplement(rgbComplement(it)).toArgb())
        }
    }

    @Test fun readableStatusTextHasContrastInAllThemes() {
        for (surface in listOf(AppSurface, Color(0xFF090909), Color(0xFF1A1D22))) {
            for (accent in AccentOptions.values + listOf(Color.Black, Color.White)) {
                assertTrue(contrastRatio(accessibleAccent(rgbComplement(accent), surface), surface) >= 4.5f)
            }
        }
    }
}
