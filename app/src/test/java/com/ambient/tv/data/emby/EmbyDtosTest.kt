package com.ambient.tv.data.emby

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbyDtosTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `public system info accepts additional server fields`() {
        val info = json.decodeFromString<EmbySystemInfo>(
            """{"ServerName":"Living Room","Version":"10.10.7","Id":"server-1","StartupWizardCompleted":true}""",
        )

        assertEquals("Living Room", info.serverName)
        assertEquals("server-1", info.id)
    }

    @Test
    fun `authentication response exposes user token and server`() {
        val auth = json.decodeFromString<EmbyAuthResponse>(
            """{"User":{"Id":"user-1","Name":"Ada","Policy":{}},"AccessToken":"token-1","ServerId":"server-1"}""",
        )

        assertEquals("user-1", auth.user.id)
        assertEquals("Ada", auth.user.name)
        assertEquals("token-1", auth.accessToken)
        assertEquals("server-1", auth.serverId)
    }

    @Test
    fun `items response keeps playback and media source information`() {
        val response = json.decodeFromString<EmbyItemsResponse>(
            """{"Items":[{"Id":"movie-1","Name":"Movie","Type":"Movie","RunTimeTicks":72000000000,"UserData":{"PlaybackPositionTicks":120000000,"Played":false},"MediaSources":[{"Id":"source-1","Container":"mkv","SupportsDirectPlay":true,"MediaStreams":[{"Index":0,"Type":"Video","Codec":"hevc"}]}]}],"TotalRecordCount":1,"StartIndex":0}""",
        )

        val item = response.items.single()
        assertEquals("movie-1", item.id)
        assertEquals(120_000_000L, item.userData?.playbackPositionTicks)
        assertEquals("source-1", item.mediaSources.single().id)
        assertTrue(item.mediaSources.single().supportsDirectPlay)
        assertEquals("hevc", item.mediaSources.single().mediaStreams.single().codec)
    }

    @Test
    fun `episode keeps series primary image tag for poster tiles`() {
        val item = json.decodeFromString<EmbyItem>(
            """{"Id":"ep-1","Name":"Pilot","Type":"Episode","SeriesId":"show-1","SeriesName":"Series","SeriesPrimaryImageTag":"series-tag","ImageTags":{"Primary":"episode-tag"}}""",
        )
        assertEquals("show-1", item.seriesId)
        assertEquals("series-tag", item.seriesPrimaryImageTag)
        assertEquals("episode-tag", item.imageTags["Primary"])
    }

    @Test
    fun `item logo uses own tag before inherited parent logo`() {
        val movie = json.decodeFromString<EmbyItem>(
            """{"Id":"movie-1","Name":"Movie","Type":"Movie","ImageTags":{"Primary":"poster","Logo":"logo-tag"}}""",
        )
        assertEquals("movie-1" to "logo-tag", movie.logoSource())

        val episode = json.decodeFromString<EmbyItem>(
            """{"Id":"ep-1","Name":"Pilot","Type":"Episode","ImageTags":{"Primary":"still"},"ParentLogoItemId":"show-1","ParentLogoImageTag":"show-logo"}""",
        )
        assertEquals("show-1" to "show-logo", episode.logoSource())
        assertEquals(null, json.decodeFromString<EmbyItem>("""{"Id":"bare","Name":"Bare","Type":"Movie"}""").logoSource())
    }

    @Test
    fun `series derives watched episodes from the unplayed count`() {
        val series = json.decodeFromString<EmbyItem>(
            """{"Id":"show-1","Name":"Series","Type":"Series","ChildCount":2,"RecursiveItemCount":10,"UserData":{"UnplayedItemCount":4,"Played":false}}""",
        )
        assertEquals(6, series.watchedLeafCount())

        val withoutUnplayedCount = json.decodeFromString<EmbyItem>(
            """{"Id":"show-2","Name":"Series","Type":"Series","RecursiveItemCount":10,"UserData":{"Played":false}}""",
        )
        assertEquals(null, withoutUnplayedCount.watchedLeafCount())

        val seasonsOnly = json.decodeFromString<EmbyItem>(
            """{"Id":"show-3","Name":"Series","Type":"Series","ChildCount":2,"UserData":{"UnplayedItemCount":4}}""",
        )
        assertEquals(null, seasonsOnly.watchedLeafCount())
    }
}
