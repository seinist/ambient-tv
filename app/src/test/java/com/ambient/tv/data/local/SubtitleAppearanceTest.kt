package com.ambient.tv.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleAppearanceTest {
    @Test fun unknownValuesFallBackToDefaults() {
        val odd = SubtitleAppearance(size = "huge", color = "pink", edge = "glow", background = "x", positionPercent = 90).normalized()
        assertEquals(SubtitleAppearance(positionPercent = SubtitleAppearance.MAX_POSITION), odd)
        assertEquals(SubtitleAppearance.MIN_POSITION, SubtitleAppearance(positionPercent = -4).normalized().positionPercent)
    }

    @Test fun defaultLooksLikeTheFormerFixedMedia3Style() {
        val a = SubtitleAppearance()
        assertEquals(0.045f, a.textFraction, 0f)
        assertEquals(0xFFFFFFFF.toInt(), a.textArgb)
        assertEquals(0, a.backgroundArgb)
        assertTrue(a.bold)
        assertEquals(6, a.positionPercent)
    }

    @Test fun mpvGetsABoxOnlyWithABackgroundAndNoOutlineInIt() {
        val plain = SubtitleAppearance().mpvProperties().toMap()
        assertEquals("outline-and-shadow", plain["sub-border-style"])
        assertEquals("2.5", plain["sub-outline-size"])
        assertEquals("#FFFFFFFF", plain["sub-color"])
        assertEquals("43", plain["sub-margin-y"])
        val boxed = SubtitleAppearance(background = SubtitleAppearance.BACKGROUND_TRANSLUCENT, color = SubtitleAppearance.COLOR_YELLOW).mpvProperties().toMap()
        assertEquals("background-box", boxed["sub-border-style"])
        assertEquals("#99000000", boxed["sub-back-color"])
        assertEquals("0", boxed["sub-outline-size"])
        assertEquals("#FFFFE14D", boxed["sub-color"])
        val shadow = SubtitleAppearance(edge = SubtitleAppearance.EDGE_SHADOW, bold = false).mpvProperties().toMap()
        assertEquals("2.5", shadow["sub-shadow-offset"])
        assertEquals("no", shadow["sub-bold"])
    }

    @Test fun vlcScalesRelativeToMediumAndUsesRgbColors() {
        val options = SubtitleAppearance(size = SubtitleAppearance.SIZE_LARGE, color = SubtitleAppearance.COLOR_YELLOW,
            background = SubtitleAppearance.BACKGROUND_OPAQUE).vlcOptions()
        assertTrue("--sub-text-scale=122" in options)
        assertTrue("--freetype-color=${0xFFE14D}" in options)
        assertTrue("--freetype-background-opacity=255" in options)
        assertTrue("--freetype-outline-thickness=0" in options)
        assertTrue("--sub-text-scale=100" in SubtitleAppearance().vlcOptions())
    }
}
