package com.ambient.tv.car

import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.livetv.LiveTvChannel
import com.ambient.tv.livetv.LiveTvChannelKind
import com.ambient.tv.livetv.LiveTvPlaybackSession
import com.ambient.tv.livetv.LiveTvPlaybackSource
import com.ambient.tv.livetv.LiveTvProgramme
import com.ambient.tv.livetv.LiveTvZapOrder
import com.ambient.tv.ui.navigation.Destinations
import com.ambient.tv.ui.player.BackgroundPlaybackSession
import com.ambient.tv.ui.player.PlaybackHandle
import com.ambient.tv.ui.player.PlaybackSeekStopTest
import com.ambient.tv.ui.player.PlaybackStatus
import com.ambient.tv.ui.tvheadend.TvheadendPlayerState
import com.ambient.tv.ui.tvheadend.liveTvMedia3MimeType
import com.ambient.tv.ui.tvheadend.liveRadioSessionRestore
import com.ambient.tv.ui.tvheadend.liveRadioSessionMetadata
import com.ambient.tv.ui.tvheadend.zapChannels
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = PlaybackSeekStopTest.PreferencesApplication::class)
class CarLivePlaybackTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val preferences get() = AppPreferences(context.getSharedPreferences("car-live", 0))

    private class Handle(private val playing: Boolean) : PlaybackHandle {
        var released = false
        override fun status() = PlaybackStatus(positionMs = 42_000, playing = playing)
        override fun play() = Unit
        override fun pause() = Unit
        override fun seekTo(positionMs: Long) = Unit
        override fun release() { released = true }
    }

    private fun selection(
        system: String = "tvheadend",
        url: String = "http://server.test/stream/channel/radio",
    ): CarAudioSelection.Live {
        val sourceId = "$system:test"
        val session = object : LiveTvPlaybackSession {
            override suspend fun report(positionMs: Long, playing: Boolean, stopped: Boolean) = Unit
        }
        return CarAudioSelection.Live(
            LiveTvChannel("radio", "News", 7, "http://server.test/imagecache/7",
                sourceId = sourceId, kind = LiveTvChannelKind.RADIO),
            LiveTvPlaybackSource(url, mapOf("Authorization" to "stream-auth"), "News", isLive = true, session = session),
            listOf(CarAudioIds.liveAudio(sourceId, "radio"), CarAudioIds.liveAudio(sourceId, "next")),
            system,
            artworkHeaders = mapOf("Authorization" to "image-auth"),
        )
    }

    @Before fun reset() {
        BackgroundPlaybackSession.stop()
        BackgroundPlaybackSession.takeCompleted()
        context.getSharedPreferences("car-live", 0).edit().clear().commit()
    }

    @After fun cleanup() {
        BackgroundPlaybackSession.stop()
        BackgroundPlaybackSession.takeCompleted()
        LiveTvZapOrder.clear()
    }

    @Test fun autoRadioOpensTheSourcePlayerAndCarriesItsExistingStreamAndArtworkLogin() {
        for (system in listOf("tvheadend", "iptv")) {
            val selection = selection(system)
            val id = selection.queue.first()
            val metadata = selection.playerMetadata(id, preferences)
            val ready = metadata.resumeState as TvheadendPlayerState.Ready
            assertEquals(Destinations.tvhPlayer("channel", "radio", "$system:test"), metadata.route)
            assertNotEquals(Destinations.LIVE_TV, metadata.route)
            assertEquals("radio", metadata.mediaId)
            assertEquals(id, carLiveMediaId(metadata))
            assertEquals("$system:test", metadata.liveTvSourceId)
            assertFalse(metadata.hasVideo)
            assertTrue(metadata.isLive)
            assertEquals(selection.channel.iconUrl, metadata.artworkUrl)
            assertEquals(selection.artworkHeaders, metadata.artworkHeaders)
            assertSame(selection.stream.session, metadata.liveTvSession)
            assertSame(selection.stream.session, ready.source.session)
            assertEquals(selection.stream.url, ready.source.url)
            assertEquals(selection.stream.headers, ready.source.headers)
            assertEquals(selection.channel, ready.channel)
        }
    }

    @Test fun reopeningKeepsTheAutoRendererAndAudioModeWhileUsingThePhoneVisualizerSettings() {
        val preferences = preferences.apply {
            tvheadendPlaybackBackendKey = "vlc"
            mediaAudioPassthroughEnabled = true
            audioVisualizerModeKey = "off"
            projectmPresetIntervalSeconds = 120
            projectmTransitionSeconds = 4
            projectmQualityKey = "high"
            projectmPresetCategoryKey = "favorites"
        }
        val selection = selection()
        val ready = selection.playerMetadata(selection.queue.first(), preferences).resumeState as TvheadendPlayerState.Ready
        assertEquals("media3", ready.playbackBackendKey)
        assertFalse(ready.audioPassthroughEnabled)
        assertEquals(preferences.audioVisualizerModeKey, ready.audioVisualizerModeKey)
        assertEquals(preferences.projectmPresetIntervalSeconds, ready.projectmPresetIntervalSeconds)
        assertEquals(preferences.projectmTransitionSeconds, ready.projectmTransitionSeconds)
        assertEquals(preferences.projectmQualityKey, ready.projectmQualityKey)
        assertEquals(preferences.projectmPresetCategoryKey, ready.projectmPresetCategoryKey)
    }

    @Test fun reopeningLiveOrPausedMpegTsAndHlsReusesTheRunningDecoder() {
        for (url in listOf("http://server.test/stream/channel/radio", "https://radio.test/live.M3U8?ticket=123")) {
            for (playing in listOf(true, false)) {
                BackgroundPlaybackSession.stop()
                BackgroundPlaybackSession.takeCompleted()
                val selection = selection(url = url)
                val metadata = selection.playerMetadata(selection.queue.first(), preferences)
                val autoKey = listOf(url, 0L, selection.stream.headers, false, liveTvMedia3MimeType(url))
                val handle = BackgroundPlaybackSession.acquire(context, metadata, "media3", autoKey) { Handle(playing) }
                val generation = BackgroundPlaybackSession.snapshot.value.generation

                // Both notification and mini-player navigate this route. The player VM retrieves
                // its Ready state from this cache before considering a new repository.liveStream().
                val ready = BackgroundPlaybackSession.cached(metadata.route) as TvheadendPlayerState.Ready
                val fullscreenKey = listOf(ready.source.url, ready.source.startPositionMs, ready.source.headers,
                    ready.audioPassthroughEnabled, liveTvMedia3MimeType(ready.source.url))
                val reopened: Handle = BackgroundPlaybackSession.acquire<Handle>(context,
                    metadata.copy(subtitle = "Current programme", resumeState = ready), ready.playbackBackendKey, fullscreenKey) {
                    error("Opening the phone player must not create another decoder")
                }
                assertSame(handle, reopened)
                assertFalse(handle.released)
                assertEquals(generation, BackgroundPlaybackSession.snapshot.value.generation)
                assertEquals(playing, BackgroundPlaybackSession.snapshot.value.wantsToPlay)
                assertEquals(selection.queue.first(), carLiveMediaId(BackgroundPlaybackSession.snapshot.value.metadata))
            }
        }
    }

    @Test fun phoneZappingUsesTheAutoRadioOrderAndStillMapsBackToTheHeadUnitQueue() {
        val selection = selection()
        val queue = selection.queue + selection.queue.first() + CarAudioIds.liveAudio("iptv:other", "foreign")
        val metadata = selection.copy(queue = queue).playerMetadata(queue.first(), preferences)
        val ready = metadata.resumeState as TvheadendPlayerState.Ready
        assertEquals(listOf("radio", "next"), ready.channelOrder)
        val next = selection.channel.copy(uuid = "next", name = "Next")
        val television = selection.channel.copy(uuid = "tv", kind = LiveTvChannelKind.TV)
        val channels = zapChannels(listOf(television, next, selection.channel), ready.channelOrder)
        assertEquals(listOf("radio", "next"), channels.map { it.uuid })

        // The ordinary phone player publishes the raw UUID and source identity for every zap.
        val nextMetadata = metadata.copy(
            mediaId = next.uuid, route = Destinations.tvhPlayer("channel", next.uuid, metadata.liveTvSourceId),
        )
        assertEquals(1, selection.queue.indexOf(carLiveMediaId(nextMetadata)))
    }

    @Test fun unrelatedOrVideoPlaybackCannotMatchThePreviousAutoRadioQueue() {
        val selection = selection()
        val metadata = selection.playerMetadata(selection.queue.first(), preferences)
        for (other in listOf(
            metadata.copy(hasVideo = true),
            metadata.copy(isLive = false),
            metadata.copy(source = "radio"),
            metadata.copy(mediaId = ""),
            metadata.copy(liveTvSourceId = null),
            metadata.copy(liveTvSourceId = "tvheadend:"),
            metadata.copy(liveTvSourceId = "iptv:other"),
        )) assertNull(carLiveMediaId(other))
        assertNull(carLiveMediaId(null))
    }

    @Test fun autoNextAndPreviousUpdateTheOpenPhonePlayerWithoutRetuningTheOldStation() {
        val first = selection()
        // Distinct stations can use the same URL. The new session/route still owns the screen.
        val next = first.copy(channel = first.channel.copy(uuid = "next", name = "Next"))
        val initialMetadata = first.playerMetadata(first.queue.first(), preferences)
        fun key(ready: TvheadendPlayerState.Ready) = listOf(ready.source.url, ready.source.startPositionMs,
            ready.source.headers, ready.audioPassthroughEnabled, liveTvMedia3MimeType(ready.source.url))
        BackgroundPlaybackSession.acquire(context, initialMetadata, "media3",
            key(initialMetadata.resumeState as TvheadendPlayerState.Ready)) { Handle(true) }
        var observedGeneration = BackgroundPlaybackSession.snapshot.value.generation
        for ((selection, id) in listOf(next to first.queue[1], first to first.queue[0])) {
            val metadata = selection.playerMetadata(id, preferences)
            val newHandle = BackgroundPlaybackSession.acquire(context, metadata, "media3",
                key(metadata.resumeState as TvheadendPlayerState.Ready)) { Handle(true) }
            val snapshot = BackgroundPlaybackSession.snapshot.value
            val restored = liveRadioSessionRestore(snapshot, "tvheadend:test", "tvheadend", "channel", observedGeneration)!!
            observedGeneration = snapshot.generation
            assertEquals(selection.channel.uuid, restored.channel?.uuid)
            assertEquals(listOf("radio", "next"), restored.channelOrder)
            val presented: Handle = BackgroundPlaybackSession.acquire<Handle>(context, metadata,
                restored.playbackBackendKey, key(restored)) {
                error("The open phone player must attach Auto's newly selected stream")
            }
            assertSame(newHandle, presented)
            assertEquals(id, carLiveMediaId(BackgroundPlaybackSession.snapshot.value.metadata))
        }
    }

    @Test fun externalPlayerRestoreRejectsStalePendingUnrelatedAndNonRadioSessions() {
        val selection = selection()
        val metadata = selection.playerMetadata(selection.queue.first(), preferences)
        val snapshot = BackgroundPlaybackSession.Snapshot(metadata, "media3", generation = 12)
        fun restore(value: BackgroundPlaybackSession.Snapshot = snapshot, kind: String = "channel", after: Long = 11) =
            liveRadioSessionRestore(value, "tvheadend:test", "tvheadend", kind, after)
        assertNotNull(restore())
        assertNull(restore(after = 12))
        assertNull(restore(after = 13))
        assertNull(restore(snapshot.copy(replacingSource = true)))
        assertNull(restore(snapshot.copy(metadata = null)))
        assertNull(restore(kind = "recording"))
        val ready = metadata.resumeState as TvheadendPlayerState.Ready
        for (other in listOf(
            metadata.copy(hasVideo = true),
            metadata.copy(isLive = false),
            metadata.copy(source = "iptv"),
            metadata.copy(liveTvSourceId = "tvheadend:another"),
            metadata.copy(mediaId = "another"),
            metadata.copy(route = Destinations.LIVE_TV),
            metadata.copy(resumeState = null),
            metadata.copy(resumeState = ready.copy(channelOrder = null)),
            metadata.copy(resumeState = ready.copy(channel = selection.channel.copy(kind = LiveTvChannelKind.TV))),
        )) assertNull(restore(snapshot.copy(metadata = other)))
    }

    @Test fun sameGenerationSongAndProgrammeUpdatesReachThePhoneWithoutResettingItsControlsOrLooping() {
        val selection = selection()
        val metadata = selection.playerMetadata(selection.queue.first(), preferences)
        val original = metadata.resumeState as TvheadendPlayerState.Ready
        val current = original.copy(
            source = original.source.copy(subtitle = "Old song"),
            manualRendererKey = "vlc", sourceWidth = 1280, sourceHeight = 720,
            audioVisualizerModeKey = "off", projectmQualityKey = "high",
        )
        val programme = LiveTvProgramme("now", "radio", "News", "Morning show",
            startEpochSeconds = 900, stopEpochSeconds = 1100, sourceId = "tvheadend:test")
        val next = programme.copy(eventId = "next", title = "News at noon", startEpochSeconds = 1100, stopEpochSeconds = 1200)
        val updated = carLiveNowPlayingMetadata(metadata, listOf(programme, next), 1000, "New song")
        val snapshot = BackgroundPlaybackSession.Snapshot(updated, "media3", generation = 31)
        val merged = liveRadioSessionMetadata(snapshot, "tvheadend:test", "tvheadend", "channel", 31, current)!!
        assertEquals("New song", merged.source.subtitle)
        assertEquals(programme, merged.nowEvent)
        assertEquals(next, merged.nextEvent)
        assertEquals(current.manualRendererKey, merged.manualRendererKey)
        assertEquals(current.sourceWidth, merged.sourceWidth)
        assertEquals(current.sourceHeight, merged.sourceHeight)
        assertEquals(current.audioVisualizerModeKey, merged.audioVisualizerModeKey)
        assertEquals(current.projectmQualityKey, merged.projectmQualityKey)
        assertEquals(current.source.copy(subtitle = "New song"), merged.source)
        assertEquals(current.channelOrder, merged.channelOrder)

        // Both the exact Ready emitted by the UI and an equivalent service copy are stable.
        assertNull(liveRadioSessionMetadata(snapshot.copy(metadata = updated.copy(resumeState = merged)),
            "tvheadend:test", "tvheadend", "channel", 31, merged))
        assertNull(liveRadioSessionMetadata(snapshot, "tvheadend:test", "tvheadend", "channel", 31, merged))
    }

    @Test fun sameGenerationMetadataCannotOverrideAnotherStreamOrAPendingTune() {
        val selection = selection()
        val metadata = selection.playerMetadata(selection.queue.first(), preferences)
        val current = metadata.resumeState as TvheadendPlayerState.Ready
        val updated = carLiveNowPlayingMetadata(metadata, emptyList(), 1000, "New song")
        val snapshot = BackgroundPlaybackSession.Snapshot(updated, "media3", generation = 31)
        fun merge(state: TvheadendPlayerState.Ready = current, expected: Long = 31,
            observed: BackgroundPlaybackSession.Snapshot = snapshot) =
            liveRadioSessionMetadata(observed, "tvheadend:test", "tvheadend", "channel", expected, state)
        assertNotNull(merge())
        assertNull(merge(expected = 30))
        assertNull(merge(expected = 32))
        assertNull(merge(observed = snapshot.copy(replacingSource = true)))
        assertNull(merge(current.copy(channel = selection.channel.copy(uuid = "next"))))
        assertNull(merge(current.copy(source = current.source.copy(url = "http://server.test/replacement"))))
        assertNull(merge(current.copy(source = current.source.copy(headers = emptyMap()))))
        assertNull(merge(current.copy(source = current.source.copy(session = null))))
        assertNull(merge(current.copy(source = current.source.copy(startPositionMs = 1000))))
    }

    @Test fun selectingTheSameStationFromAnotherGroupUpdatesThePhoneQueueWithoutRetuning() {
        val selection = selection()
        val metadata = selection.playerMetadata(selection.queue.first(), preferences)
        val current = metadata.resumeState as TvheadendPlayerState.Ready
        val otherGroup = metadata.copy(resumeState = current.copy(channelOrder = listOf("radio")))
        val snapshot = BackgroundPlaybackSession.Snapshot(otherGroup, "media3", generation = 31)
        val merged = liveRadioSessionMetadata(snapshot, "tvheadend:test", "tvheadend", "channel", 31, current)!!
        assertEquals(listOf("radio"), merged.channelOrder)
        assertEquals(current.source.url, merged.source.url)
        assertSame(current.source.session, merged.source.session)
        assertNull(liveRadioSessionMetadata(snapshot, "tvheadend:test", "tvheadend", "channel", 31, merged))
    }
}
