package com.ambient.tv.youtube

import java.io.IOException
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeFormatResolutionTest {
    private data class Info(val quality: Int)

    @Test fun `live HLS needs neither DASH nor progressive work`() {
        var progressiveChecked = false
        val quality = youtubePlayableFormatScore(false, true, true, emptyList()) {
            progressiveChecked = true; false
        }
        var switches = 0
        assertEquals(Info(4), resolveYouTubeFormatInfo(load = { Info(quality) },
            switchClient = { switches++; true }, score = { it.quality }))
        assertFalse(progressiveChecked)
        assertEquals(0, switches)
    }

    @Test fun `DASH must contain usable video and audio`() {
        val video = YouTubeAdaptiveFormat("video/mp4", "https://example.test/video")
        val audio = YouTubeAdaptiveFormat("audio/mp4", "https://example.test/audio")
        assertEquals(4, youtubePlayableFormatScore(false, false, false, listOf(video, audio)) {
            error("No progressive scan for complete DASH")
        })
        assertEquals(0, youtubePlayableFormatScore(false, false, false, listOf(audio)) { false })
        assertEquals(0, youtubePlayableFormatScore(false, false, false,
            listOf(video.copy(url = " "), audio)) { false })
        assertEquals(0, youtubePlayableFormatScore(false, false, false,
            listOf(video.copy(isOtf = true), audio)) { false })
        assertEquals(2, youtubePlayableFormatScore(false, false, false, listOf(audio)) { true })
        assertEquals(1, youtubePlayableFormatScore(false, false, true, emptyList()) { false })
        assertEquals(0, youtubePlayableFormatScore(true, true, true, listOf(video, audio)) { true })
    }

    @Test fun `VLC live keeps recovery until a muxed stream exists`() {
        val hlsOnly = youtubePlayableFormatScore(false, true, true, emptyList(), preferLiveHls = false) { false }
        assertEquals(0, hlsOnly)
        var calls = 0
        var switches = 0
        val result = resolveYouTubeFormatInfo(load = {
            calls++
            Info(youtubePlayableFormatScore(false, true, true, emptyList(), preferLiveHls = false) { calls == 4 })
        }, switchClient = { switches++; true }, score = { it.quality })
        assertEquals(Info(4), result)
        assertEquals(4, calls)
        assertEquals(3, switches)
    }

    @Test fun `complete DASH returns without a client switch`() {
        assertEquals(Info(4), resolveYouTubeFormatInfo(load = { Info(4) },
            switchClient = { error("Already ready") }, score = { it.quality }))
    }

    @Test fun `progressive attempts one DASH upgrade and keeps the best response`() {
        var calls = 0
        var switches = 0
        val innertube = mutableListOf<Boolean>()
        val result = resolveYouTubeFormatInfo(load = { allowed ->
            innertube += allowed; calls++; Info(if (calls == 1) 2 else 0)
        }, switchClient = { switches++; true }, score = { it.quality })
        assertEquals(Info(2), result)
        assertEquals(2, calls)
        assertEquals(1, switches)
        assertEquals(listOf(true, false), innertube)
    }

    @Test fun `quality upgrade can still select DASH`() {
        var calls = 0
        assertEquals(Info(4), resolveYouTubeFormatInfo(load = { Info(if (calls++ == 0) 2 else 4) },
            switchClient = { true }, score = { it.quality }))
    }

    @Test fun `a failed load or switch cannot discard a playable response`() {
        for (failSwitch in listOf(false, true)) {
            var calls = 0
            assertEquals(Info(2), resolveYouTubeFormatInfo(load = {
                if (calls++ == 0) Info(2) else throw IOException("offline")
            }, switchClient = { if (failSwitch) throw IOException("switch") else true }, score = { it.quality }))
        }
    }

    @Test fun `no fallback retains five recovery switches`() {
        var calls = 0
        var switches = 0
        assertEquals(Info(0), resolveYouTubeFormatInfo(load = { calls++; Info(0) },
            switchClient = { switches++; true }, score = { it.quality }))
        assertEquals(6, calls)
        assertEquals(5, switches)
    }

    @Test fun `initial exception can recover on the next client`() {
        var calls = 0
        assertEquals(Info(4), resolveYouTubeFormatInfo(load = {
            if (calls++ == 0) throw IOException("initial") else Info(4)
        }, switchClient = { true }, score = { it.quality }))
    }

    @Test fun `fallback discovered during recovery gets only one upgrade`() {
        var calls = 0
        assertEquals(Info(2), resolveYouTubeFormatInfo(load = {
            calls++; Info(if (calls >= 3) 2 else 0)
        }, switchClient = { true }, score = { it.quality }))
        assertEquals(4, calls)
    }

    @Test fun `missing client switch keeps available response`() {
        assertEquals(Info(2), resolveYouTubeFormatInfo(load = { Info(2) },
            switchClient = { false }, score = { it.quality }))
    }

    @Test fun `cancellation is not a fallback or another client attempt`() {
        var switches = 0
        var cancelled = false
        try {
            resolveYouTubeFormatInfo<Info>(load = { throw CancellationException("closed") },
                switchClient = { switches++; true }, score = { it.quality })
        } catch (_: CancellationException) { cancelled = true }
        assertTrue(cancelled)
        assertEquals(0, switches)
    }

    @Test fun `all failing clients propagate the last failure`() {
        var calls = 0
        try {
            resolveYouTubeFormatInfo<Info>(load = { throw IOException("failure-${++calls}") },
                switchClient = { true }, score = { it.quality })
            error("Expected failure")
        } catch (error: IOException) { assertEquals("failure-6", error.message) }
    }
}
