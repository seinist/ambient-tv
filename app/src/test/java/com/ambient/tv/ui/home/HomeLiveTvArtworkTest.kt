package com.ambient.tv.ui.home

import com.ambient.tv.livetv.LiveTvChannel
import com.ambient.tv.livetv.LiveTvProgramme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeLiveTvArtworkTest {
    private val headers = mapOf("Authorization" to "Basic secret")
    private val logoUrl = "https://tv.example/logo.png"
    private val epgUrl = "https://tv.example/epg.jpg"
    private val tmdbUrl = "https://image.tmdb.org/t/p/w780/backdrop.jpg"
    private val channel = LiveTvChannel(
        uuid = "channel", name = "Sender", number = 1, iconUrl = logoUrl, sourceId = "tvheadend",
    )
    private val programme = LiveTvProgramme(
        eventId = "event", channelUuid = channel.uuid, channelName = channel.name,
        title = "Ein Film", startEpochSeconds = 100, stopEpochSeconds = 3600, imageUrl = epgUrl,
    )
    private fun item(event: LiveTvProgramme? = programme, source: LiveTvChannel = channel) =
        HomeLiveTvItem(source, event, headers, "TVHeadend")

    @Test fun tmdbArtWinsOnHomeAndNeverReceivesTvheadendCredentials() {
        val entry = item()
        val art = entry.bestArtwork(mapOf("ein film" to tmdbUrl))!!
        assertEquals(tmdbUrl, art.url)
        assertFalse(art.logo)
        assertTrue(art.external)
        assertTrue(art.headers(headers).isEmpty())

        val hero = entry.toHomeHeroContent(mapOf("ein film" to tmdbUrl))
        assertEquals(tmdbUrl, hero.artworkUrl)
        assertFalse(hero.artworkIsLogo)
        assertTrue(hero.imageHeaders.isEmpty())
        assertEquals(logoUrl, hero.titleLogoUrl)
        assertEquals(headers, hero.titleLogoHeaders)
        assertEquals("livetv:tvheadend:channel:event", entry.homeHeroKey())
    }

    @Test fun missingTmdbFallsBackToEpgAndThenTheChannelLogo() {
        val entry = item()
        val epg = entry.bestArtwork()!!
        assertEquals(epgUrl, epg.url)
        assertFalse(epg.external)
        assertEquals(headers, entry.toHomeHeroContent().imageHeaders)

        val noEpg = item(programme.copy(imageUrl = null))
        assertEquals(logoUrl, noEpg.bestArtwork()!!.url)
        val hero = noEpg.toHomeHeroContent()
        assertTrue(hero.artworkIsLogo)
        assertEquals(headers, hero.imageHeaders)
        assertEquals(logoUrl, hero.artworkUrl)

        val withoutProgramme = item(event = null)
        assertTrue(withoutProgramme.bestArtwork()!!.logo)
        assertEquals(channel.name, withoutProgramme.toHomeHeroContent().title)
        assertNull(item(event = null, source = channel.copy(iconUrl = null)).bestArtwork())
    }
}
