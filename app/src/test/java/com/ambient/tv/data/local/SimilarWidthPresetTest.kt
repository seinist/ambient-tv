package com.ambient.tv.data.local

import org.junit.Assert.*
import org.junit.Test

/** Width presets: exact width first, else a saved width of the same standard class (±3 %). */
class SimilarWidthPresetTest {
    private val p1920 = ResolutionVideoProfile("mpv", mpv = MpvImageOptions(sharpening = "cas"))
    private val p1904 = ResolutionVideoProfile("vlc")
    private val live1920 = ResolutionVideoProfile("media3")

    @Test fun standardClassAllowsSmallCrops() {
        assertEquals(1920, standardWidthClass(1916))
        assertEquals(1920, standardWidthClass(1864))
        assertNull(standardWidthClass(1440))
        assertEquals(3840, standardWidthClass(3836))
        assertNull(standardWidthClass(0))
        assertNull(standardWidthClass(null))
    }

    @Test fun exactWidthWinsOverSimilarOne() {
        val profiles = mapOf("width:1920" to p1920, "width:1904" to p1904)
        assertEquals("width:1904", matchingResolutionKey(profiles, "width:1904"))
        assertEquals("width:1920", matchingResolutionKey(profiles, "width:1920"))
    }

    @Test fun similarWidthUsesClosestSavedPresetOfTheSameClass() {
        val profiles = mapOf("width:1920" to p1920, "width:1904" to p1904, "width:1280" to ResolutionVideoProfile("auto"))
        assertEquals("width:1920", matchingResolutionKey(profiles, "width:1916"))
        assertEquals("width:1904", matchingResolutionKey(profiles, "width:1900"))
        // A preset saved for a cropped width also serves the standard width.
        assertEquals("width:1904", matchingResolutionKey(mapOf("width:1904" to p1904), "width:1920"))
        // Different class or no class: nothing.
        assertNull(matchingResolutionKey(profiles, "width:1440"))
        assertNull(matchingResolutionKey(mapOf("width:1280" to p1920), "width:1916"))
        assertNull(matchingResolutionKey(profiles, null))
    }

    @Test fun livePresetsStaySeparateFromRegularOnes() {
        val profiles = mapOf("width:1920" to p1920, "live:width:1920" to live1920)
        assertEquals("width:1920", matchingResolutionKey(profiles, "width:1916"))
        assertEquals("live:width:1920", matchingResolutionKey(profiles, "width:1916", "live:"))
        assertSame(live1920, resolutionProfileFor(profiles, "width:1916", live = true))
        assertSame(p1920, resolutionProfileFor(profiles, "width:1916", live = false))
        assertSame(p1920, resolutionProfileFor(mapOf("width:1920" to p1920), "width:1916", live = true))
        assertEquals("width:1920", resolutionProfileKeyFor(mapOf("width:1920" to p1920), "width:1916", live = true))
    }
}
