package com.ambient.tv.ui.youtube

import com.ambient.tv.youtube.YouTubeChannelInfo
import com.ambient.tv.youtube.YouTubeItem
import com.ambient.tv.youtube.YouTubeMetadata
import com.ambient.tv.youtube.YouTubePlayback
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class YouTubePlayerMetadataTest {
    private fun stream(id: String) = YouTubePlayback(
        videoId = id, title = "SmartTube", uri = "fixture://$id", durationMs = 120_000L,
    )

    private fun detail(id: String) = YouTubeMetadata(
        videoId = id,
        title = "Official trailer",
        author = "Studio channel",
        description = "The official trailer description",
        imageUrl = "https://fixture.invalid/thumbnail.jpg",
        backgroundUrl = "https://fixture.invalid/backdrop.jpg",
        durationMs = 120_000L,
        percentWatched = 50,
        publishedDate = "vor 3 Tagen",
    )

    @Test fun delayedTrailerDetailsUpdateThePlayingStreamWithoutResumingItsWatchHistory() = runTest {
        val id = "trailer-delayed-metadata"
        val initial = YouTubePlayerState(
            loading = false,
            playback = stream(id).copy(resumeMs = 0L, resumeResolved = true, resumeRewindSeconds = 0),
        )
        val state = MutableStateFlow(initial)
        val pending = CompletableDeferred<YouTubeMetadata>()
        val job = launch {
            updateYouTubePlayerMetadata(id, isTrailer = true, state = state,
                loadMetadata = { pending.await() },
                loadChannel = { error("Trailers must not load account/channel controls") },
            )
        }
        runCurrent()
        assertFalse(job.isCompleted)
        assertEquals(initial, state.value)
        assertEquals("fixture://$id", state.value.playback!!.uri)

        pending.complete(detail(id))
        job.join()
        val playback = state.value.playback!!
        assertEquals("Official trailer", playback.title)
        assertEquals("Studio channel", playback.author)
        assertEquals("The official trailer description", playback.description)
        assertEquals("vor 3 Tagen", playback.publishedDate)
        assertEquals("https://fixture.invalid/thumbnail.jpg", playback.imageUrl)
        assertEquals("https://fixture.invalid/backdrop.jpg", playback.backgroundUrl)
        assertEquals("fixture://$id", playback.uri)
        assertEquals(0L, playback.resumeMs)
        assertTrue(playback.resumeResolved)
        assertEquals(0, playback.resumeRewindSeconds)
        assertFalse(state.value.loading)
        assertNull(state.value.channel)
        assertNull(state.value.nextVideo)
        assertNull(state.value.error)
    }

    @Test fun unavailableMetadataRetainsThePlayableStreamAndExistingDetails() = runTest {
        val id = "trailer-metadata-unavailable"
        val initial = YouTubePlayerState(
            loading = false,
            playback = stream(id).copy(
                title = "Known trailer", author = "Known studio", publishedDate = "2026",
                resumeResolved = true,
            ),
        )
        val state = MutableStateFlow(initial)
        for (fails in listOf(false, true)) {
            updateYouTubePlayerMetadata(id, isTrailer = true, state = state,
                loadMetadata = { if (fails) error("Metadata unavailable") else null },
                loadChannel = { error("No channel request without metadata") },
            )
            assertEquals(initial, state.value)
        }
    }

    @Test fun lateDetailsCannotReplaceAnotherVideosTitleOrChannel() = runTest {
        val id = "previous-video-metadata"
        val state = MutableStateFlow(YouTubePlayerState(loading = false, playback = stream(id)))
        val pending = CompletableDeferred<YouTubeMetadata>()
        val job = launch {
            updateYouTubePlayerMetadata(id, isTrailer = false, state = state,
                loadMetadata = { pending.await() },
                loadChannel = { YouTubeChannelInfo("old-channel", title = "Old channel") },
            )
        }
        runCurrent()
        val current = YouTubePlayerState(
            loading = false,
            playback = stream("current-video").copy(title = "Current title"),
            channel = YouTubeChannelInfo("current-channel", title = "Current channel"),
        )
        state.value = current
        pending.complete(detail(id))
        job.join()
        assertEquals(current, state.value)
    }

    @Test fun cancelledMetadataWorkCannotPublishAfterTheProviderReturns() = runTest {
        val id = "cancelled-trailer-metadata"
        val initial = YouTubePlayerState(loading = false, playback = stream(id).copy(resumeResolved = true))
        val state = MutableStateFlow(initial)
        val pending = CompletableDeferred<YouTubeMetadata>()
        val job = launch {
            updateYouTubePlayerMetadata(id, isTrailer = true, state = state,
                // A blocking provider can finish after cancellation; suspendResult must reject it.
                loadMetadata = { withContext(NonCancellable) { pending.await() } },
                loadChannel = { error("Cancelled metadata must not request channel controls") },
            )
        }
        runCurrent()
        job.cancel()
        pending.complete(detail(id))
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(initial, state.value)
    }

    @Test fun regularYouTubeStillEnrichesItsChannelAndUnresolvedSavedPosition() = runTest {
        val id = "regular-video-metadata"
        val next = YouTubeItem("next-video", "Next video")
        val initial = YouTubePlayerState(
            loading = false,
            playback = stream(id).copy(resumeRewindSeconds = 5),
            nextVideo = next,
            previousVideoId = "previous-video",
            liveRefreshGeneration = 3,
        )
        val state = MutableStateFlow(initial)
        val channel = YouTubeChannelInfo("studio-channel", "Studio channel", subscribed = true)
        var channelRequests = 0
        updateYouTubePlayerMetadata(id, isTrailer = false, state = state,
            loadMetadata = { detail(id) },
            loadChannel = { loaded ->
                assertEquals(id, loaded.videoId)
                channelRequests++
                channel
            },
        )
        val playback = state.value.playback!!
        assertEquals("Official trailer", playback.title)
        assertEquals("Studio channel", playback.author)
        assertEquals(55_000L, playback.resumeMs)
        assertTrue(playback.resumeResolved)
        assertEquals(channel, state.value.channel)
        assertEquals(1, channelRequests)
        assertEquals(initial.copy(playback = playback, channel = channel), state.value)
    }
}
