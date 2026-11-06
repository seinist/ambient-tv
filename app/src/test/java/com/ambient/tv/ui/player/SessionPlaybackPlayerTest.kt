@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.ambient.tv.ui.player

import android.app.Application
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = Application::class)
class SessionPlaybackPlayerTest {
    @Test fun statusAndMetadataFollowTheExistingRenderer() {
        val player = SessionPlaybackPlayer(Looper.getMainLooper(), RecordingActions())
        val state = playing("current").copy(
            mediaItem = item("current").buildUpon().setMediaMetadata(
                MediaMetadata.Builder().setTitle("Title").setArtist("Channel").build(),
            ).build(),
            positionMs = 25_000,
            durationMs = 120_000,
        )
        player.update(state)
        assertEquals(Player.STATE_READY, player.playbackState)
        assertTrue(player.isPlaying)
        assertEquals(25_000L, player.currentPosition)
        assertEquals(120_000L, player.duration)
        assertEquals("Title", player.mediaMetadata.title)

        player.update(state.copy(buffering = true))
        assertEquals(Player.STATE_BUFFERING, player.playbackState)
        assertTrue(player.playWhenReady)
        assertFalse(player.isPlaying)
        player.update(state.copy(playWhenReady = false))
        assertEquals(Player.STATE_READY, player.playbackState)
        assertFalse(player.isPlaying)
        player.update(state.copy(ended = true))
        assertEquals(Player.STATE_ENDED, player.playbackState)
        assertFalse(player.isPlaying)
        player.update(SessionPlaybackState())
        assertEquals(Player.STATE_IDLE, player.playbackState)
        assertEquals(0, player.mediaItemCount)
        player.release()
    }

    @Test fun repeatedErrorSnapshotsEmitOneErrorAndRemainValidIdleStates() {
        val player = SessionPlaybackPlayer(Looper.getMainLooper(), RecordingActions())
        player.update(playing("current"))
        assertEquals(Player.STATE_READY, player.playbackState)
        val errors = mutableListOf<String?>()
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) { errors += error.message }
        })
        player.update(playing("current").copy(error = "Stream unavailable", buffering = true))
        player.update(playing("current").copy(error = "Stream unavailable", positionMs = 10_000))
        assertEquals(Player.STATE_IDLE, player.playbackState)
        assertFalse(player.isLoading)
        assertEquals(listOf("Stream unavailable"), errors)
        player.update(playing("current"))
        assertNull(player.playerError)
        player.release()
    }

    @Test fun prepareOnlyDoesNotStartAnyDecoderOrSpotifyPlayback() {
        val actions = RecordingActions()
        val player = SessionPlaybackPlayer(Looper.getMainLooper(), actions)
        val old = playing("old").copy(playWhenReady = false)
        player.update(old)

        player.setMediaItem(item("selected"), 7_000)
        player.prepare()
        player.update(old.copy(positionMs = 14_000))

        assertTrue(actions.requests.isEmpty())
        assertEquals(listOf("pause"), actions.events)
        assertEquals("selected", player.currentMediaItem?.mediaId)
        assertEquals(7_000L, player.currentPosition)
        assertEquals(Player.STATE_READY, player.playbackState)
        assertFalse(player.playWhenReady)
        assertTrue(player.hasPreparedSelection)

        player.play()
        assertEquals("selected", actions.requests.single().id)
        assertEquals(7_000L, actions.requests.single().positionMs)
        assertEquals(listOf("pause", "select:selected"), actions.events)
        player.update(playing("selected"))
        actions.requests.single().future.set(null)
        assertEquals("play", actions.events.last())
        assertFalse(player.hasPreparedSelection)
        assertFalse(player.isSelecting)
        player.release()
    }

    @Test fun playingSelectionDefersTransportUntilNewSourceOwnsPlayback() {
        val actions = RecordingActions()
        val player = SessionPlaybackPlayer(Looper.getMainLooper(), actions)
        player.update(playing("old").copy(canSeek = true, hasNext = true))

        player.setMediaItem(item("selected"))
        player.prepare()
        player.play()
        player.pause()
        player.play()
        player.seekTo(8_000)
        player.seekToNext()

        assertEquals(listOf("select:selected"), actions.events)
        assertEquals(Player.STATE_BUFFERING, player.playbackState)
        assertFalse(player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM))
        assertFalse(player.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT))

        player.update(playing("selected"))
        actions.requests.single().future.set(null)
        assertEquals(listOf("select:selected", "play"), actions.events)
        assertEquals("selected", player.currentMediaItem?.mediaId)
        player.release()
    }

    @Test fun pauseCancelsLibraryResolutionImmediatelyEvenWhenEnginePauseIsDeferred() {
        val actions = RecordingActions()
        val player = SessionPlaybackPlayer(Looper.getMainLooper(), actions)
        player.setMediaItem(item("selected"))
        player.prepare()
        player.pause()
        assertEquals(1, actions.pauseRequests)
        player.play()
        player.pause()
        assertEquals(2, actions.pauseRequests)
        assertEquals(listOf("pause", "select:selected"), actions.events)
        player.update(playing("selected"))
        actions.requests.single().future.set(null)
        assertEquals("pause", actions.events.last())
        player.release()
    }

    @Test fun pauseImmediatelyReachesRestoredOwnerWhenItsHookCancelsTheSelection() {
        val actions = RecordingActions(cancelSelectionOnPause = true)
        val player = SessionPlaybackPlayer(Looper.getMainLooper(), actions)
        player.update(playing("old"))
        player.setMediaItem(item("selected"))
        player.play()

        player.pause()

        val request = actions.requests.single()
        assertTrue(request.future.isCancelled)
        assertEquals(listOf("select:selected", "pause"), actions.events)
        assertFalse(player.isSelecting)
        assertEquals("old", player.currentMediaItem?.mediaId)
        assertEquals(Player.STATE_READY, player.playbackState)
        assertFalse(request.future.set(null))
        assertEquals(listOf("select:selected", "pause"), actions.events)
        player.release()
    }

    @Test fun stopCancelsPendingSelectionAndNeverAppliesItsQueuedPlay() {
        val actions = RecordingActions()
        val player = SessionPlaybackPlayer(Looper.getMainLooper(), actions)
        player.update(playing("old"))
        player.setMediaItem(item("selected"))
        player.play()
        player.stop()

        assertTrue(actions.requests.single().future.isCancelled)
        assertEquals(listOf("select:selected", "stop"), actions.events)
        assertFalse(player.isSelecting)
        player.release()
    }

    @Test fun newerSelectionCancelsOldRequestAndReceivesOnlyItsOwnPlay() {
        val actions = RecordingActions()
        val player = SessionPlaybackPlayer(Looper.getMainLooper(), actions)
        player.update(playing("old"))
        player.setMediaItem(item("first"))
        player.play()
        player.setMediaItem(item("second"))
        player.play()

        assertTrue(actions.requests.first().future.isCancelled)
        player.update(playing("second"))
        actions.requests.last().future.set(null)
        assertEquals(listOf("select:first", "select:second", "play"), actions.events)
        assertEquals("second", player.currentMediaItem?.mediaId)
        player.release()
    }

    @Test fun failedSelectionDoesNotPlayThePreviousSource() {
        val actions = RecordingActions()
        val player = SessionPlaybackPlayer(Looper.getMainLooper(), actions)
        player.update(playing("old"))
        player.setMediaItem(item("selected"))
        player.play()
        actions.requests.single().future.setException(IllegalStateException("Unavailable"))
        assertEquals(listOf("select:selected"), actions.events)
        assertFalse(player.isSelecting)
        assertEquals("old", player.currentMediaItem?.mediaId)
        player.release()
    }

    @Test fun releasingAdapterCancelsSelectionWithoutStoppingTheServiceOwnedRenderer() {
        val actions = RecordingActions()
        val player = SessionPlaybackPlayer(Looper.getMainLooper(), actions)
        player.update(playing("old"))
        player.setMediaItem(item("selected"))
        player.play()
        player.release()
        assertTrue(actions.requests.single().future.isCancelled)
        assertEquals(listOf("select:selected"), actions.events)
    }

    @Test fun playbackStartedInAppSupersedesAnUnstartedPreparedItem() {
        val actions = RecordingActions()
        val player = SessionPlaybackPlayer(Looper.getMainLooper(), actions)
        player.update(playing("old").copy(playWhenReady = false))
        player.setMediaItem(item("prepared"))
        player.prepare()
        player.update(playing("from-app"))
        assertFalse(player.hasPreparedSelection)
        assertEquals("from-app", player.currentMediaItem?.mediaId)
        assertTrue(actions.requests.isEmpty())
        player.release()
    }

    @Test fun seekingPreservesPublishedTimelineUnitsAndClampsToKnownBounds() {
        val actions = RecordingActions()
        val player = SessionPlaybackPlayer(Looper.getMainLooper(), actions)
        player.update(playing("recording").copy(positionMs = 70_000, durationMs = 120_000, canSeek = true))
        player.seekTo(85_000)
        player.seekTo(-1)
        player.seekTo(200_000)
        assertEquals(listOf("seek:85000", "seek:0", "seek:120000"), actions.events)
        player.release()
    }

    private fun item(id: String) = MediaItem.Builder().setMediaId(id).build()
    private fun playing(id: String) = SessionPlaybackState(mediaItem = item(id), uid = id, playWhenReady = true)

    private class RecordingActions(
        private val cancelSelectionOnPause: Boolean = false,
    ) : SessionPlaybackPlayer.Actions {
        data class Request(val id: String, val positionMs: Long, val future: SettableFuture<Void?>)
        val events = mutableListOf<String>()
        val requests = mutableListOf<Request>()
        var pauseRequests = 0
        override fun onPauseRequested() {
            pauseRequests++
            if (cancelSelectionOnPause) requests.lastOrNull()?.future?.cancel(false)
        }
        override fun play() { events += "play" }
        override fun pause() { events += "pause" }
        override fun stop() { events += "stop" }
        override fun seekTo(positionMs: Long) { events += "seek:$positionMs" }
        override fun skip(next: Boolean) { events += if (next) "next" else "previous" }
        override fun select(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<*> {
            val id = mediaItems[startIndex.takeIf { it != C.INDEX_UNSET } ?: 0].mediaId
            events += "select:$id"
            return SettableFuture.create<Void?>().also { requests += Request(id, startPositionMs, it) }
        }
    }
}
