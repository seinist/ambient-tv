@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.ambient.tv.ui.player

import android.app.Application
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = Application::class)
class PlaybackServiceRadioActionsTest {
    @Test fun radioPublishesSkipButNeverSeek() {
        val commands = sessionPlaybackCommands(state(canSeek = false, previous = true, next = true))
        assertTrue(commands.contains(Player.COMMAND_SEEK_TO_PREVIOUS))
        assertTrue(commands.contains(Player.COMMAND_SEEK_TO_NEXT))
        assertFalse(commands.contains(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM))
    }

    @Test fun radioOmitsUnavailableQueueDirections() {
        val commands = sessionPlaybackCommands(state(canSeek = false, previous = false, next = true))
        assertFalse(commands.contains(Player.COMMAND_SEEK_TO_PREVIOUS))
        assertFalse(commands.contains(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM))
        assertTrue(commands.contains(Player.COMMAND_SEEK_TO_NEXT))
        assertTrue(commands.contains(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM))
    }

    @Test fun carMusicQueuesExposeSkipAndSeek() {
        val commands = sessionPlaybackCommands(state(canSeek = true, previous = true, next = true))
        assertTrue(commands.contains(Player.COMMAND_SEEK_TO_PREVIOUS))
        assertTrue(commands.contains(Player.COMMAND_SEEK_TO_NEXT))
        assertTrue(commands.contains(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM))
    }

    @Test fun idleBrowserCanSelectAndPrepareItemsWithoutOfferingQueueMutation() {
        val commands = sessionPlaybackCommands(SessionPlaybackState())
        assertTrue(commands.contains(Player.COMMAND_SET_MEDIA_ITEM))
        assertTrue(commands.contains(Player.COMMAND_PREPARE))
        assertTrue(commands.contains(Player.COMMAND_PLAY_PAUSE))
        assertFalse(commands.contains(Player.COMMAND_CHANGE_MEDIA_ITEMS))
        assertFalse(commands.contains(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM))
    }

    @Test fun ordinaryVideoKeepsSeekWithoutQueueNavigation() {
        val commands = sessionPlaybackCommands(state(canSeek = true, previous = false, next = false))
        assertTrue(commands.contains(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM))
        assertFalse(commands.contains(Player.COMMAND_SEEK_TO_PREVIOUS))
        assertFalse(commands.contains(Player.COMMAND_SEEK_TO_NEXT))
    }

    @Test fun skipCommandsReachSourceQueueWithoutPublishingSyntheticMediaItems() {
        val actions = RecordingActions()
        val player = SessionPlaybackPlayer(Looper.getMainLooper(), actions)
        player.update(state(canSeek = false, previous = true, next = true).copy(isLive = true))

        player.seekToNextMediaItem()
        player.seekToNext()
        player.seekToPrevious()
        player.seekToPreviousMediaItem()
        player.seekTo(15_000)

        assertEquals(listOf("next", "next", "previous", "previous"), actions.events)
        assertEquals(1, player.mediaItemCount)
        assertTrue(player.isCurrentMediaItemLive)
        assertFalse(player.isCurrentMediaItemSeekable)
        player.release()
    }

    @Test fun unsupportedPlaylistMutationsLeaveCurrentPlaybackIntact() {
        val actions = RecordingActions()
        val player = SessionPlaybackPlayer(Looper.getMainLooper(), actions)
        player.update(state(canSeek = true, previous = false, next = false))
        player.setMediaItems(emptyList())
        player.setMediaItems(listOf(MediaItem.Builder().setMediaId("one").build(),
            MediaItem.Builder().setMediaId("two").build()))
        assertEquals("current", player.currentMediaItem?.mediaId)
        assertTrue(actions.events.isEmpty())
        player.release()
    }

    private fun state(canSeek: Boolean, previous: Boolean, next: Boolean) = SessionPlaybackState(
        mediaItem = MediaItem.Builder().setMediaId("current").build(),
        canSeek = canSeek,
        hasPrevious = previous,
        hasNext = next,
    )

    private class RecordingActions : SessionPlaybackPlayer.Actions {
        val events = mutableListOf<String>()
        override fun play() { events += "play" }
        override fun pause() { events += "pause" }
        override fun stop() { events += "stop" }
        override fun seekTo(positionMs: Long) { events += "seek:$positionMs" }
        override fun skip(next: Boolean) { events += if (next) "next" else "previous" }
        override fun select(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<*> =
            Futures.immediateVoidFuture()
    }
}
