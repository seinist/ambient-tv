package com.ambient.tv.ui.youtube

import com.ambient.tv.data.telegram.TelegramMedia
import com.ambient.tv.data.telegram.TelegramMediaKind
import com.ambient.tv.youtube.YouTubeMetadata
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
class YouTubeDetailOriginTest {
    private val origin = YouTubeDetailOrigin(-10042L, 27)
    private val video = YouTubeMetadata(
        videoId = "selected-id",
        title = "Actual video title",
        author = "YouTube author",
        description = "Video description",
        publishedDate = "Video publication",
    )

    private fun post() = TelegramMedia(
        chatId = origin.chatId,
        messageId = origin.messageId,
        channel = "Monogram source channel",
        title = "A different post title",
        kind = TelegramMediaKind.YOUTUBE,
        summary = "The source post links several videos.",
        publishedAtMs = 1_790_000_000_000L,
    )

    @Test fun routeOriginAcceptsTelegramChannelIdsAndRejectsIncompletePairs() {
        assertEquals(origin, youtubeDetailOrigin(origin.chatId, origin.messageId))
        assertNull(youtubeDetailOrigin(null, 27))
        assertNull(youtubeDetailOrigin(0L, 27))
        assertNull(youtubeDetailOrigin(-10042L, null))
        assertNull(youtubeDetailOrigin(-10042L, 0))
        assertNull(youtubeDetailOrigin(-10042L, -1))
    }

    @Test fun delayedSourceEnrichmentRetainsLoadedVideoMetadataAndActions() = runTest {
        val state = MutableStateFlow(YouTubeDetailState(origin = origin))
        val pending = CompletableDeferred<TelegramMedia?>()
        val job = launch {
            updateYouTubeDetailOrigin(origin, state) { chatId, messageId ->
                assertEquals(origin.chatId, chatId)
                assertEquals(origin.messageId, messageId)
                pending.await()
            }
        }
        runCurrent()
        assertFalse(job.isCompleted)
        // YouTube finishes first; the post may arrive much later or require a reconnect.
        state.value = state.value.copy(loading = false, metadata = video, signedIn = true)
        pending.complete(post())
        job.join()

        assertEquals(video, state.value.metadata)
        assertFalse(state.value.loading)
        assertTrue(state.value.signedIn)
        assertFalse(state.value.offline)
        assertNull(state.value.error)
        assertEquals("Monogram source channel", state.value.origin?.channel)
        assertEquals(post().publishedAtMs, state.value.origin?.publishedAtMs)
        assertEquals("YouTube author", state.value.metadata?.author)
        assertEquals("Video publication", state.value.metadata?.publishedDate)
    }

    @Test fun unavailableSourceDoesNotHideVideoOrMarkYouTubeOffline() = runTest {
        val initial = YouTubeDetailState(loading = false, metadata = video, origin = origin)
        val state = MutableStateFlow(initial)
        updateYouTubeDetailOrigin(origin, state) { _, _ -> error("Monogram session is unavailable") }
        assertEquals(initial, state.value)
        updateYouTubeDetailOrigin(origin, state) { _, _ -> null }
        assertEquals(initial, state.value)
    }

    @Test fun staleOrMismatchedSourceCannotReplaceTheCurrentAttribution() = runTest {
        val initial = YouTubeDetailState(loading = false, metadata = video, origin = origin)
        val state = MutableStateFlow(initial)
        updateYouTubeDetailOrigin(origin, state) { _, _ -> post().copy(messageId = 99) }
        assertEquals(initial, state.value)

        val pending = CompletableDeferred<TelegramMedia?>()
        val job = launch { updateYouTubeDetailOrigin(origin, state) { _, _ -> pending.await() } }
        runCurrent()
        val current = initial.copy(origin = YouTubeDetailOrigin(-10099L, 2))
        state.value = current
        pending.complete(post())
        job.join()
        assertEquals(current, state.value)
    }

    @Test fun cancelledSourceLoadCannotPublishAfterItsProviderReturns() = runTest {
        val initial = YouTubeDetailState(loading = false, metadata = video, origin = origin)
        val state = MutableStateFlow(initial)
        val pending = CompletableDeferred<TelegramMedia?>()
        val job = launch {
            updateYouTubeDetailOrigin(origin, state) { _, _ ->
                withContext(NonCancellable) { pending.await() }
            }
        }
        runCurrent()
        job.cancel()
        pending.complete(post())
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(initial, state.value)
    }
}
