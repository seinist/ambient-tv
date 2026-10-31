package com.ambient.tv.livetv

import com.ambient.tv.trailers.TrailerTitle
import com.ambient.tv.trailers.matchProgrammeTitle
import com.ambient.tv.tvheadend.data.tvhTimerNode
import com.ambient.tv.ui.tvheadend.nextPaddingStep
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Timer editing payloads and the Live TV art fallback chain. */
class LiveTvTimerEditingTest {
    private fun recording(image: String? = null, fanart: String? = null, broadcast: String? = null) = LiveTvRecording(
        uuid = "r1", channelUuid = "c1", channelName = "Das Erste", title = "Tatort",
        startEpochSeconds = 1000, stopEpochSeconds = 2000, status = null, scheduleStatus = "scheduled",
        url = null, imageUrl = image, fanartUrl = fanart, fileSizeBytes = null, broadcastEventId = broadcast,
    )
    private val channel = LiveTvChannel("c1", "Das Erste", 1, "http://tvh/imagecache/1")
    private fun event(id: String, image: String?, title: String = "Tatort", start: Long = 1000) =
        LiveTvProgramme(id, "c1", "Das Erste", title, startEpochSeconds = start, stopEpochSeconds = start + 1000, imageUrl = image)

    @Test fun tvheadendSavesOnlyTheChangedFields() {
        val node = tvhTimerNode("u1", LiveTvTimerEdit(paddingBeforeMinutes = 5, priority = 1))
        assertEquals(setOf("uuid", "start_extra", "pri"), node.keys)
        assertEquals(5, node["start_extra"]!!.jsonPrimitive.int)
        val series = tvhTimerNode("u2", LiveTvTimerEdit(paddingAfterMinutes = 99_999, enabled = false))
        assertEquals(1440, series["stop_extra"]!!.jsonPrimitive.int)
        assertEquals(JsonPrimitive(false), series["enabled"])
    }

    @Test fun jellyfinKeepsTheTimerAndSetsPaddingInSeconds() {
        val timer = JsonObject(mapOf("Id" to JsonPrimitive("t"), "PrePaddingSeconds" to JsonPrimitive(60), "Name" to JsonPrimitive("Tatort")))
        val updated = mediaBrowserTimerUpdate(timer, LiveTvTimerEdit(paddingBeforeMinutes = 5, paddingAfterMinutes = 10))
        assertEquals(JsonPrimitive("Tatort"), updated["Name"])
        assertEquals(300, updated["PrePaddingSeconds"]!!.jsonPrimitive.int)
        assertEquals(600, updated["PostPaddingSeconds"]!!.jsonPrimitive.int)
        assertEquals(timer, mediaBrowserTimerUpdate(timer, LiveTvTimerEdit()))
        assertTrue(LiveTvTimerEdit().isEmpty)
        assertFalse(LiveTvTimerEdit(enabled = true).isEmpty)
    }

    @Test fun paddingStepsMoveAlongTheList() {
        assertEquals(1, nextPaddingStep(0, 1))
        assertEquals(0, nextPaddingStep(0, -1))
        assertEquals(10, nextPaddingStep(7, 1))
        assertEquals(5, nextPaddingStep(7, -1))
        assertEquals(120, nextPaddingStep(120, 1))
    }

    @Test fun recordingArtFallsBackThroughGuideTitleAndLogo() {
        // Prefer landscape artwork (fanart, then TMDb) over a recording's poster/thumbnail.
        assertEquals(LiveTvArt("fan"), liveTvRecordingArt(recording(image = "own", fanart = "fan"), emptyList(), listOf(channel)))
        assertEquals(LiveTvArt("fan"), liveTvRecordingArt(recording(fanart = "fan"), emptyList(), listOf(channel)))
        assertEquals(LiveTvArt("own"), liveTvRecordingArt(recording(image = "own"), emptyList(), listOf(channel)))
        // The EPG event it was recorded from, by broadcast id or by channel, start and title.
        assertEquals(LiveTvArt("epg"), liveTvRecordingArt(recording(broadcast = "e9"), listOf(event("e9", "epg", start = 5)), listOf(channel)))
        assertEquals(LiveTvArt("epg"), liveTvRecordingArt(recording(), listOf(event("e1", "epg")), listOf(channel)))
        val tmdb = liveTvRecordingArt(recording(), emptyList(), listOf(channel), mapOf("tatort" to "tmdb"))!!
        assertEquals("tmdb", tmdb.url)
        assertTrue(tmdb.external)
        // TMDb art never gets the media server's credentials.
        assertEquals(emptyMap<String, String>(), tmdb.headers(mapOf("Authorization" to "Basic x")))
        val logo = liveTvRecordingArt(recording(), emptyList(), listOf(channel))!!
        assertTrue(logo.logo)
        assertEquals(mapOf("Authorization" to "Basic x"), logo.headers(mapOf("Authorization" to "Basic x")))
        assertNull(liveTvRecordingArt(recording(), emptyList(), emptyList()))
    }

    @Test fun seriesAndProgrammeArt() {
        val series = LiveTvSeriesTimer("s", "Tatort", "Tatort", "Das Erste")
        assertEquals(LiveTvArt("epg"), liveTvSeriesArt(series, listOf(event("e", null, title = "Other"), event("e2", "epg", title = "TATORT ")), listOf(channel)))
        assertTrue(liveTvSeriesArt(series, emptyList(), listOf(channel))!!.logo)
        assertEquals(LiveTvArt("own"), liveTvProgrammeArt(event("e", "own"), listOf(channel)))
        assertTrue(liveTvProgrammeArt(event("e", null), listOf(channel), mapOf("tatort" to "t"))!!.external)
    }

    @Test fun programmeTitlesMatchTmdbOnlyExactly() {
        val candidates = listOf(
            TrailerTitle(1, name = "Tatort", backdrop = "/a.jpg", popularity = 5.0),
            TrailerTitle(2, name = "Tatort", backdrop = "/b.jpg", popularity = 50.0),
            TrailerTitle(3, name = "Tatort: Reifezeugnis", backdrop = "/c.jpg", popularity = 99.0),
            TrailerTitle(4, name = "Tatort", popularity = 500.0),
        )
        assertEquals(2, matchProgrammeTitle(candidates, " tatort ")?.id)
        assertNull(matchProgrammeTitle(candidates, "Polizeiruf 110"))
    }
}
