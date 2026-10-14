package com.ambient.tv.data.remote.dto

import com.ambient.tv.data.jellyfin.JellyfinSegmentsResponse
import com.ambient.tv.data.emby.EmbyChapter
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class SegmentDtoTest {
    private val json = Json { ignoreUnknownKeys = true }
    @Test fun plexReadsTypedMillisecondsAndIgnoresFutureFields() {
        val item = json.decodeFromString<MetadataDto>("""{"ratingKey":"1","key":"k","title":"Episode","type":"episode","Marker":[{"type":"intro","startTimeOffset":12000,"endTimeOffset":62000,"final":false},{"type":"credits","startTimeOffset":90000,"endTimeOffset":95000}]}""")
        assertEquals(listOf("intro", "credits"), item.markers.map { it.type })
        assertEquals(62000L, item.markers.first().endTimeOffset)
    }
    @Test fun jellyfinReadsNativeSegmentsAndMissingCollections() {
        val response = json.decodeFromString<JellyfinSegmentsResponse>("""{"Items":[{"Type":"Intro","StartTicks":120000000,"EndTicks":620000000},{"Type":"Outro","StartTicks":900000000,"EndTicks":950000000}],"TotalRecordCount":2}""")
        assertEquals(12000L, response.items.first().startTicks / 10000)
        assertEquals("Outro", response.items.last().type)
        assertTrue(json.decodeFromString<JellyfinSegmentsResponse>("{}").items.isEmpty())
    }
    @Test fun embyDistinguishesTypedMarkersFromOrdinaryChapters() {
        val marker = json.decodeFromString<EmbyChapter>("""{"Name":"Intro","MarkerType":"IntroEnd","StartPositionTicks":620000000}""")
        val chapter = json.decodeFromString<EmbyChapter>("""{"Name":"Intro","StartPositionTicks":0}""")
        assertEquals("IntroEnd", marker.markerType)
        assertEquals(62000L, marker.startTicks / 10000)
        assertNull(chapter.markerType)
    }
}
