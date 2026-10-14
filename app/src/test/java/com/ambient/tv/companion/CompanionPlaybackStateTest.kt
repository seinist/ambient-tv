package com.ambient.tv.companion

import com.ambient.tv.domain.model.MediaItem
import com.ambient.tv.data.files.FileKeys
import com.ambient.tv.data.dlna.DlnaKeys
import com.ambient.tv.spotify.*
import com.ambient.tv.ui.player.BackgroundPlaybackSession.Snapshot
import com.ambient.tv.ui.player.PlaybackMetadata
import com.ambient.tv.ui.player.PlaybackStatus
import org.junit.Assert.*
import org.junit.Test

class CompanionPlaybackStateTest {
    private val media = CompanionMedia("library", "j:episode", "server-a")
    private val metadata = PlaybackMetadata("player/episode", "Series", "Episode", "jellyfin", "j:episode",
        durationMs = 100_000, timelineOffsetMs = 20_000, companionMedia = media, parentMediaIds = listOf("j:season", "j:series"))
    private fun item(id: String, type: String = "movie", linked: List<String> = emptyList()) = MediaItem(
        ratingKey = id, title = "Item", type = type, index = null, thumbUrl = null, artUrl = null,
        year = null, summary = null, tagline = null, durationMs = null, viewOffsetMs = null,
        parentTitle = null, parentRatingKey = null, grandparentTitle = null, grandparentRatingKey = null,
        contentRating = null, rating = null, studio = null, linkedRatingKeys = linked)
    private fun indicator(ref: CompanionMedia = media) = PlaybackIndicator(ref, metadata.parentMediaIds, "playing", "Living room", true)

    @Test fun exactAndParentBadgesAreScopedToTheServerAndLinkedProviderKey() {
        val sources = PlaybackBrowseSources(library = mapOf("j:" to "server-a", "" to "plex-b"))
        assertTrue(indicator().matches(item("j:episode", "episode"), sources))
        assertTrue(indicator().matches(item("42", "episode", listOf("j:episode")), sources))
        assertTrue(indicator().matches(item("j:series", "show"), sources))
        assertTrue(indicator().matches(item("j:season", "season"), sources))
        assertFalse(indicator().matches(item("j:series", "movie"), sources))
        assertFalse(indicator().matches(item("j:other", "episode"), sources))
        assertFalse(indicator().matches(item("j:episode"), sources.copy(library = mapOf("j:" to "server-b"))))
        assertFalse(indicator(media.copy(trailer = true)).matches(item("j:episode"), sources))
    }

    @Test fun networkFileAndDlnaIdentitiesSurviveDifferentLocalSourceIds() {
        val sources = PlaybackBrowseSources(files = mapOf("phone-source" to "same-share"), dlna = mapOf("phone-dlna" to "same-udn"))
        assertTrue(indicator(CompanionMedia("file", "Films/A.mkv", "same-share")).matches(item(FileKeys.key("phone-source", "Films/A.mkv")), sources))
        assertFalse(indicator(CompanionMedia("file", "Films/A.mkv", "other-share")).matches(item(FileKeys.key("phone-source", "Films/A.mkv")), sources))
        assertTrue(indicator(CompanionMedia("dlna", "object-1", "same-udn")).matches(item(DlnaKeys.key("phone-dlna", "object-1")), sources))
        assertFalse(indicator(CompanionMedia("device-local", "42")).matches(item("42"), sources))
    }

    @Test fun stoppedOfflineOrUnpairedTargetsNeverProduceAnActiveStatus() {
        val peer = CompanionPeer("pin", "TV")
        val frame = CompanionFrame(type = "state", active = true, playbackState = "playing", media = media)
        val state = CompanionState(target = peer, paired = listOf(peer), online = true, remote = frame)
        assertEquals(frame, state.currentPlayback())
        assertNull(state.copy(online = false).currentPlayback())
        assertNull(state.copy(target = null).currentPlayback())
        assertNull(state.copy(paired = emptyList()).currentPlayback())
        assertNull(state.copy(remote = frame.copy(active = false)).currentPlayback())
        assertNull(state.copy(remote = frame.copy(playbackState = "ended")).currentPlayback())
        assertNull(state.copy(remote = frame.copy(receiverId = "another-tv")).currentPlayback())
        val legacy = CompanionFrame(type = "state", title = "Old TV title", playing = false)
        assertEquals("paused", state.copy(remote = legacy).currentPlayback()?.phase)
        assertNull(state.copy(remote = legacy.copy(title = "")).currentPlayback())
    }

    @Test fun absoluteTimelineAndPlayIntentRemainCorrectWhilePausedBufferingOrReplacing() {
        val snapshot = Snapshot(metadata, status = PlaybackStatus(positionMs = 5_000, durationMs = 80_000), generation = 7)
        val paused = localPlaybackFrame(snapshot)
        assertEquals(25_000L, paused.position); assertEquals(100_000L, paused.duration)
        assertEquals("paused", paused.phase); assertTrue(paused.seekable)
        assertEquals(media, paused.media); assertEquals(metadata.parentMediaIds, paused.parentIds)
        val buffering = localPlaybackFrame(snapshot.copy(wantsToPlay = true))
        assertEquals("buffering", buffering.phase); assertTrue(buffering.playIntent)
        assertEquals("loading", localPlaybackFrame(snapshot.copy(replacingSource = true)).phase)
        assertFalse(localPlaybackFrame(snapshot.copy(replacingSource = true)).playIntent)
        assertTrue(localPlaybackFrame(snapshot.copy(replacingSource = true, wantsToPlay = true)).playIntent)
        assertFalse(localPlaybackFrame(snapshot.copy(replacingSource = true)).seekable)
        assertEquals("error", localPlaybackFrame(snapshot.copy(status = snapshot.status.copy(error = "decoder"))).phase)
        assertFalse(localPlaybackFrame(snapshot.copy(status = snapshot.status.copy(ended = true))).playbackActive)
        assertEquals("idle", localPlaybackFrame(Snapshot()).phase)
    }

    @Test fun liveHasNoVodDurationOrSeekAndFallbackDurationIncludesTranscodeOffset() {
        val live = localPlaybackFrame(Snapshot(metadata.copy(isLive = true)))
        assertTrue(live.isLive); assertEquals(0L, live.duration); assertFalse(live.seekable)
        val fallback = localPlaybackFrame(Snapshot(metadata.copy(durationMs = 0), status = PlaybackStatus(durationMs = 80_000)))
        assertEquals(100_000L, fallback.duration)
    }

    @Test fun delayedCommandsCannotSeekTheNextTitleServerOrRendererGeneration() {
        val current = CompanionFrame(type = "state", active = true, media = media, playbackSessionId = 8)
        val seek = CompanionFrame(type = "seek", media = media, playbackSessionId = 8, position = 30_000)
        assertTrue(matchesPlaybackCommand(seek, current))
        assertTrue(matchesPlaybackCommand(seek.copy(media = media.copy(restart = true)), current))
        assertFalse(matchesPlaybackCommand(seek.copy(playbackSessionId = 7), current))
        assertFalse(matchesPlaybackCommand(seek, current.copy(media = media.copy(id = "j:next"))))
        assertFalse(matchesPlaybackCommand(seek, current.copy(media = media.copy(source = "other-server"))))
        assertFalse(matchesPlaybackCommand(seek, current.copy(active = false)))
        assertTrue(matchesPlaybackCommand(CompanionFrame(type = "pause"), current)) // Legacy phone.
    }

    @Test fun spotifyStatusContainsTrackParentsAndDoesNotHideAnActiveVideoWhenPaused() {
        val track = SpotifyTrack("track", "Song", listOf(SpotifyArtist("artist", "Artist")), SpotifyAlbum("album", "Album"))
        val music = SpotifyPlaybackState(current = track, playing = true, positionMs = 3_000, durationMs = 60_000)
        val frame = spotifyPlaybackFrame(music)
        assertEquals(CompanionMedia("spotify:track", "track"), frame.media)
        assertEquals(listOf("album", "artist"), frame.parentIds); assertTrue(frame.seekable)
        assertTrue(useSpotifyPlayback(Snapshot(metadata), music))
        assertFalse(useSpotifyPlayback(Snapshot(metadata), music.copy(playing = false)))
        assertTrue(useSpotifyPlayback(Snapshot(), music.copy(playing = false)))
        assertFalse(spotifyPlaybackFrame(SpotifyPlaybackState()).playbackActive)
    }

    @Test fun primaryControlsUseOnlyCapabilitiesOfferedByTheCurrentReceiver() {
        val frame = CompanionFrame(type = "state", active = true, playing = true, media = media)
        assertEquals("pause", frame.primaryPlaybackAction)
        assertEquals("play", frame.copy(playing = false).primaryPlaybackAction)
        assertEquals("retry", frame.copy(capabilities = listOf("stop", "retry")).primaryPlaybackAction)
        assertNull(frame.copy(capabilities = listOf("stop", "volume")).primaryPlaybackAction)
        assertEquals("play", frame.copy(capabilities = listOf("play")).primaryPlaybackAction)
        assertEquals("pause", frame.copy(playing = false, capabilities = listOf("pause")).primaryPlaybackAction)
    }

    @Test fun notificationIntentsRemainBoundToReceiverGenerationAndMedia() {
        val frame = CompanionFrame(type = "state", media = media, playbackSessionId = 7)
        val original = companionCommandIdentity("tv-a", frame)
        assertNotEquals(original, companionCommandIdentity("tv-b", frame))
        assertNotEquals(original, companionCommandIdentity("tv-a", frame.copy(playbackSessionId = 8)))
        assertNotEquals(original, companionCommandIdentity("tv-a", frame.copy(media = media.copy(id = "j:next"))))
        assertEquals(original, companionCommandIdentity("tv-a", frame.copy(position = 5000, playing = true)))
    }
}
