package com.ambient.tv.ui.player

import androidx.compose.ui.input.key.Key
import com.ambient.tv.R
import com.ambient.tv.domain.model.CastMember
import com.ambient.tv.domain.model.MediaItem
import com.ambient.tv.domain.model.MediaVersion
import com.ambient.tv.livetv.LiveTvChannel
import com.ambient.tv.livetv.LiveTvProgramme
import com.ambient.tv.livetv.LiveTvRecording
import com.ambient.tv.ui.common.MediaSystem
import com.ambient.tv.ui.common.PlayerPanelNavigation
import com.ambient.tv.ui.common.UnifiedPlayerPanel
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerDetailInfoTest {

    private fun item(
        type: String,
        title: String = "Title",
        thumbUrl: String? = "thumb",
        grandparentThumbUrl: String? = null,
        parentTitle: String? = null,
        grandparentTitle: String? = null,
        summary: String? = "Plot",
        cast: List<CastMember> = emptyList(),
        versions: List<MediaVersion> = emptyList(),
        genres: List<String> = emptyList(),
    ) = MediaItem(
        ratingKey = "123",
        title = title,
        type = type,
        index = 5,
        thumbUrl = thumbUrl,
        artUrl = "art",
        year = 2024,
        summary = summary,
        tagline = " ",
        durationMs = 5_400_000L,
        viewOffsetMs = null,
        parentTitle = parentTitle,
        parentRatingKey = null,
        grandparentTitle = grandparentTitle,
        grandparentRatingKey = null,
        contentRating = null,
        rating = null,
        studio = null,
        grandparentThumbUrl = grandparentThumbUrl,
        cast = cast,
        mediaVersions = versions,
        genres = genres,
    )

    private val fallback = PlayerDetailMediaFallback(title = "Fallback", mediaType = "movie", artworkUrl = "still")

    @Test fun moviesShowTheirPosterWithYearRuntimeAndPlot() {
        val detail = playerDetailForMedia(item("movie", genres = listOf("A", "B", "C", "D", "E", "F")), fallback)
        assertEquals("thumb", detail.artworkUrl)
        assertEquals(PlayerDetailArtShape.POSTER, detail.artShape)
        assertEquals(listOf("2024", "1h 30min"), detail.meta)
        assertEquals("Plot", detail.overview)
        // Blank texts are dropped, genres are capped like the detail page.
        assertNull(detail.tagline)
        assertEquals(5, detail.genres.size)
        assertEquals(OsdPlayer.MEDIA, detail.osdPlayer)
        assertEquals(MediaSystem.PLEX, detail.system)
    }

    @Test fun episodesShowTheSeriesPosterLikeTheDetailPage() {
        val episode = item("episode", thumbUrl = "episode-still", grandparentThumbUrl = "series-poster")
        assertEquals("series-poster", playerDetailForMedia(episode, fallback).artworkUrl)
    }

    @Test fun musicShowsTheSquareAlbumCoverAndArtistAlbumLine() {
        val track = item("track", thumbUrl = "album-cover", parentTitle = "Album", grandparentTitle = "Artist")
        val detail = playerDetailForMedia(track, fallback)
        assertEquals("album-cover", detail.artworkUrl)
        assertEquals(PlayerDetailArtShape.SQUARE, detail.artShape)
        assertEquals("Artist · Album", detail.eyebrow)
    }

    @Test fun withoutMetadataThePlayerStateIsUsed() {
        val detail = playerDetailForMedia(null, PlayerDetailMediaFallback(
            title = "Folge", mediaType = "episode", posterArtUrl = "poster", grandparentTitle = "Serie",
            seasonIndex = 2, episodeIndex = 3, durationMs = 0L,
        ))
        assertEquals("Folge", detail.title)
        assertEquals("poster", detail.artworkUrl)
        assertEquals("Serie · S2 E3", detail.eyebrow)
        assertTrue(detail.meta.isEmpty())
        assertNull(detail.system)
    }

    @Test fun techChipsAndFileNameDescribeThePlayingVersion() {
        val versions = listOf(
            MediaVersion(null, "1080", "h264", "aac", "mkv", fileName = "a.mkv"),
            MediaVersion(null, "4k", "hevc", "eac3", "mkv", fileName = "b.mkv"),
        )
        val detail = playerDetailForMedia(item("movie", versions = versions), fallback, selectedVersionIndex = 1)
        assertEquals(listOf("b.mkv"), detail.item?.mediaVersions?.map { it.fileName })
    }

    @Test fun castIsPlainTextWithRoles() {
        val cast = listOf(CastMember("1", "Anna", "Ella", null), CastMember("2", "Ben", null, null))
        assertEquals("Anna (Ella), Ben", playerDetailCastText(item("movie", cast = cast)))
        assertNull(playerDetailCastText(item("movie")))
    }

    @Test fun youtubeShowsTheThumbnailChannelAndCappedDescription() {
        val long = "word ".repeat(2_000)
        val detail = playerDetailForYouTube("Video", "Author", "Channel", long, null,
            listOf("t1", "t2"), isLive = false, durationMs = 600_000L)
        assertEquals("t1", detail.artworkUrl)
        assertEquals(listOf("t2"), detail.artworkFallbackUrls)
        assertEquals(PlayerDetailArtShape.WIDE, detail.artShape)
        assertEquals("Channel", detail.eyebrow)
        assertEquals(listOf("10min"), detail.meta)
        assertTrue(detail.overview!!.length <= PLAYER_DETAIL_OVERVIEW_MAX_CHARS + 2)
        assertTrue(detail.overview!!.endsWith("…"))
        val live = playerDetailForYouTube("Live", "Author", null, null, "img", emptyList(), isLive = true, durationMs = 0L)
        assertTrue(live.live)
        assertEquals("Author", live.eyebrow)
        assertNull(live.overview)
    }

    private val channel = LiveTvChannel(uuid = "c", name = "Das Erste", number = 1, iconUrl = "logo")

    private fun programme(title: String, start: Long, stop: Long, image: String? = null) = LiveTvProgramme(
        eventId = title, channelUuid = "c", channelName = "Das Erste", title = title,
        description = "Beschreibung", startEpochSeconds = start, stopEpochSeconds = stop, imageUrl = image,
    )

    @Test fun liveTvShowsTheEpgImageOrElseTheChannelLogo() {
        val now = programme("Tagesschau", 72_000, 72_900, image = "epg")
        val next = programme("Tatort", 72_900, 78_300)
        val detail = playerDetailForLiveTv(channel, now, next, null, ZoneOffset.UTC)!!
        assertEquals("epg", detail.artworkUrl)
        assertEquals(PlayerDetailArtShape.WIDE, detail.artShape)
        assertEquals("Tagesschau", detail.title)
        assertEquals("1  Das Erste", detail.eyebrow)
        assertEquals("20:00–20:15", detail.meta.first())
        assertEquals(listOf(PlayerDetailLine(R.string.player_detail_next, "20:15–21:45  Tatort")), detail.lines)

        val noImage = playerDetailForLiveTv(channel, now.copy(imageUrl = null), null, null, ZoneOffset.UTC)!!
        assertEquals("logo", noImage.artworkUrl)
        assertEquals(PlayerDetailArtShape.LOGO, noImage.artShape)
        assertTrue(noImage.lines.isEmpty())

        val noEpg = playerDetailForLiveTv(channel, null, null, null, ZoneOffset.UTC)!!
        assertEquals("Das Erste", noEpg.title)
        assertNull(playerDetailForLiveTv(null, null, null, null))
    }

    @Test fun recordingsUseTheirOwnArtwork() {
        val recording = LiveTvRecording(
            uuid = "r", channelUuid = "c", channelName = "Das Erste", title = "Film", description = "Text",
            startEpochSeconds = 0, stopEpochSeconds = 5_400, status = null, scheduleStatus = null, url = null,
            imageUrl = null, fanartUrl = "fanart", fileSizeBytes = null,
        )
        val detail = playerDetailForLiveTv(channel, null, null, recording, ZoneOffset.UTC)!!
        assertEquals("Film", detail.title)
        assertEquals("fanart", detail.artworkUrl)
        assertEquals("Text", detail.overview)
    }

    @Test fun radioShowsTheStationLogoNowPlayingAndBitrate() {
        val detail = playerDetailForRadio("Radio Eins", " Song – Artist ", "logo", listOf("logo", "alt"), emptyMap(),
            "Deutschland", listOf("pop", " pop", "rock", ""), "mp3", 128)
        assertEquals("logo", detail.artworkUrl)
        assertEquals(listOf("alt"), detail.artworkFallbackUrls)
        assertEquals(PlayerDetailArtShape.SQUARE, detail.artShape)
        assertEquals(listOf(PlayerDetailLine(R.string.player_detail_now_playing, "Song – Artist")), detail.lines)
        assertEquals(listOf("pop", "rock"), detail.genres)
        assertEquals(listOf("MP3", "128 kbps"), detail.chips)
        val quiet = playerDetailForRadio("Radio", null, null, emptyList(), emptyMap(), null, emptyList(), null, 0)
        assertTrue(quiet.lines.isEmpty())
        assertTrue(quiet.chips.isEmpty())
    }

    @Test fun shortTextsAreNotCapped() {
        assertEquals("kurz", capPlayerDetailText("  kurz "))
        assertNull(capPlayerDetailText("   "))
    }

    @Test fun detailsButtonIsOptInForTheFourPlayersButNotSpotify() {
        assertTrue(OsdButton.DETAILS.optIn)
        listOf(OsdPlayer.MEDIA, OsdPlayer.YOUTUBE, OsdPlayer.LIVE_TV, OsdPlayer.RADIO).forEach { player ->
            assertTrue(OsdButton.DETAILS in player.buttons)
            assertFalse(osdButtonVisible(OsdButton.DETAILS, player, emptySet()))
            assertTrue(osdButtonVisible(OsdButton.DETAILS, player, setOf("+${player.name}/DETAILS")))
        }
        assertFalse(OsdButton.DETAILS in OsdPlayer.SPOTIFY.buttons)
    }

    @Test fun closingTheDialogReturnsFocusToTheDetailsButton() {
        val nav = PlayerPanelNavigation()
        openPlayerDetails(nav, "Details")
        assertEquals(UnifiedPlayerPanel.DETAILS, nav.panel)
        // The Close button and Back both run closePlayerDetails.
        closePlayerDetails(nav)
        assertEquals(UnifiedPlayerPanel.NONE, nav.panel)
        assertEquals("bar:Details", nav.restoreKey)
    }

    @Test fun infoKeyTogglesTheDialogFromAnyPanel() {
        val nav = PlayerPanelNavigation()
        nav.fromOrigin("bar:Settings", root = true) { nav.open(UnifiedPlayerPanel.SETTINGS) }
        toggleDetailsByInfoKey(nav, "Details")
        assertEquals(UnifiedPlayerPanel.DETAILS, nav.panel)
        assertEquals(1, nav.depth)
        toggleDetailsByInfoKey(nav, "Details")
        assertEquals(UnifiedPlayerPanel.NONE, nav.panel)
        assertEquals("bar:Details", nav.restoreKey)
    }

    @Test fun inactivityNeverClosesTheDialog() {
        val nav = PlayerPanelNavigation()
        openPlayerDetails(nav, "Details")
        assertFalse(nav.closeForInactivity(nav.activity.version))
        assertEquals(UnifiedPlayerPanel.DETAILS, nav.panel)
        // Other panels still time out.
        nav.fromOrigin("bar:Settings", root = true) { nav.open(UnifiedPlayerPanel.SETTINGS) }
        assertTrue(nav.closeForInactivity(nav.activity.version))
    }

    @Test fun dpadScrollsTheTextThenReachesTheCloseButton() {
        assertEquals(PlayerDetailKeyAction.SCROLL_DOWN, playerDetailKeyAction(Key.DirectionDown, false, false, true))
        assertEquals(PlayerDetailKeyAction.FOCUS_CLOSE, playerDetailKeyAction(Key.DirectionDown, false, true, false))
        assertEquals(PlayerDetailKeyAction.SCROLL_UP, playerDetailKeyAction(Key.DirectionUp, false, true, true))
        assertEquals(PlayerDetailKeyAction.CONSUME, playerDetailKeyAction(Key.DirectionUp, false, false, true))
        assertEquals(PlayerDetailKeyAction.FOCUS_CLOSE, playerDetailKeyAction(Key.DirectionRight, false, false, false))
        assertEquals(PlayerDetailKeyAction.FOCUS_TEXT, playerDetailKeyAction(Key.DirectionUp, true, false, false))
        assertEquals(PlayerDetailKeyAction.FOCUS_TEXT, playerDetailKeyAction(Key.DirectionLeft, true, false, false))
        // OK activates the Close button; Back and media keys are left to the player.
        assertEquals(PlayerDetailKeyAction.PASS, playerDetailKeyAction(Key.DirectionCenter, true, false, false))
        assertEquals(PlayerDetailKeyAction.CONSUME, playerDetailKeyAction(Key.DirectionCenter, false, false, false))
        assertEquals(PlayerDetailKeyAction.PASS, playerDetailKeyAction(Key.Back, false, false, false))
        assertEquals(PlayerDetailKeyAction.PASS, playerDetailKeyAction(Key.MediaPlayPause, false, false, false))
    }
}
