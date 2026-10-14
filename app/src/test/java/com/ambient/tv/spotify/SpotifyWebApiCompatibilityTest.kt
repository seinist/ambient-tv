package com.ambient.tv.spotify

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyWebApiCompatibilityTest {
    @Test fun searchLimitMatches2026DevModeLimit() {
        assertEquals(10, SpotifyWebApi.SEARCH_LIMIT)
    }

    @Test fun requestedScopesIncludeFollowedArtists() {
        assertTrue("user-follow-read" in SpotifyAuthorizationPolicy.REQUESTED_SCOPES)
    }

    @Test fun parsesFebruary2026PlaylistItemShape() {
        val page = Json.parseToJsonElement(
            """{"items":[{"item":{"id":"t1","name":"Track","duration_ms":1234,"explicit":false,"artists":[{"id":"a1","name":"Artist"}],"album":{"id":"al1","name":"Album","artists":[{"id":"a1","name":"Artist"}],"images":[]}}}]}"""
        ).jsonObject
        val parsed = newApi().parsePlaylistItems(page)
        assertEquals(1, parsed.size)
        assertEquals("t1", parsed.single().id)
        assertEquals("Artist", parsed.single().artists.single().name)
    }
    @Test fun playlistEpisodeKeepsItsSpotifyEpisodeUri() {
        val page = Json.parseToJsonElement(
            """{"items":[{"item":{"id":"e1","type":"episode","uri":"spotify:episode:e1","name":"Episode","duration_ms":5000,"explicit":false,"images":[],"show":{"id":"s1","name":"Show","publisher":"Publisher","images":[]}}}]}"""
        ).jsonObject
        val parsed = newApi().parsePlaylistItems(page)
        assertEquals("spotify:episode:e1", parsed.single().uri)
        assertEquals("Publisher", parsed.single().artists.single().name)
    }

    private fun newApi() = SpotifyWebApi(okhttp3.ConnectionPool(), okhttp3.Dispatcher())
}
