package com.ambient.tv.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MiniPlayerArtworkFallbackTest {
    @Test fun radioStationUsesTwoCharacterMonogramWithinTheExistingCircle() {
        assertEquals("RP", miniPlayerMonogram("Radio Paradise"))
        assertEquals("1L", miniPlayerMonogram("1LIVE"))
        assertEquals("SW", miniPlayerMonogram("SWR3"))
        assertEquals("ÖR", miniPlayerMonogram("Österreich Radio"))
        assertEquals("BF", miniPlayerMonogram("BBC Four"))
    }

    @Test fun missingOrGenericStationNameFallsBackToProviderMark() {
        assertNull(miniPlayerMonogram(""))
        assertNull(miniPlayerMonogram("  "))
        assertNull(miniPlayerMonogram("Radio Browser"))
        assertNull(miniPlayerMonogram("unknown"))
        assertNull(miniPlayerMonogram("unbekannt"))
        assertNull(miniPlayerMonogram("https://stream.example.net/live"))
    }

    @Test fun noArtworkUrlsStillDrawsACircleWhenItHasFallbackContent() {
        assertFalse(shouldDrawPlaybackArtworkCard(null, emptyList(), hasFallbackContent = false))
        assertTrue(shouldDrawPlaybackArtworkCard(null, emptyList(), hasFallbackContent = true))
        assertTrue(shouldDrawPlaybackArtworkCard("", listOf(""), hasFallbackContent = true))
        assertTrue(shouldDrawPlaybackArtworkCard("https://example.org/logo.png", emptyList(), false))
        assertTrue(shouldDrawPlaybackArtworkCard(null, listOf("https://example.org/cover.jpg"), false))
    }

    @Test fun liveWithoutKnownDurationUsesFullAccentRingButNeverFakeProgress() {
        assertEquals(360f, miniPlayerRingSweep(null, isLive = true)!!, 0.001f)
        assertNull(miniPlayerRingSweep(null, isLive = false))
        assertEquals(180f, miniPlayerRingSweep(0.5f, isLive = true)!!, 0.001f)
        assertEquals(90f, miniPlayerRingSweep(0.25f, isLive = false)!!, 0.001f)
        assertEquals(0f, miniPlayerRingSweep(-1f, isLive = false)!!, 0.001f)
        assertEquals(360f, miniPlayerRingSweep(10f, isLive = false)!!, 0.001f)
        assertNull(miniPlayerRingSweep(Float.NaN, isLive = false))
        assertEquals(360f, miniPlayerRingSweep(Float.NaN, isLive = true)!!, 0.001f)
    }
}
