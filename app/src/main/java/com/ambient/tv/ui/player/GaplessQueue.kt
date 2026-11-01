@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.ambient.tv.ui.player

import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import java.lang.ref.WeakReference

/**
 * Gapless music: the prepared next song waiting in the running Media3 engine's queue.
 *
 * Lives with the engine, not with the player screen, so a minimized player or an app in the
 * background still plays on without a gap. With the player screen open, the screen adopts the
 * next song (PlayerViewModel.adoptGaplessNext). Without it, this queue moves the playback session
 * on to the prepared song by itself (title, notification, MediaSession, progress reports) and
 * asks the song's [Entry.continuation] to prepare the one after it.
 */
internal class GaplessQueue(
    /** "Stop after this item": the item has to end at its own end. */
    private val endOfItemSleep: () -> Boolean,
    /** Route of the playback session that currently owns the engine. */
    private val sessionRoute: () -> String?,
    private val continueSession: (fromRoute: String, metadata: PlaybackMetadata) -> Boolean,
    private val launch: (suspend () -> Unit) -> Unit,
) {
    /** The part of the engine the queue needs; Media3's player in the app, a fake in tests. */
    interface Target {
        val currentIndex: Int
        val itemCount: Int
        fun removeItems(fromIndex: Int, toIndex: Int)
        fun appendItem(id: String, url: String)
        fun pause()
    }

    class Entry(
        val ratingKey: String,
        val streamUrl: String,
        /** Route of the song playing now; the queued song may only follow that playback. */
        val fromRoute: String,
        /** Session metadata of the queued song, used when no player screen adopts it. */
        val metadata: PlaybackMetadata,
        /** Prepares the song after this one once it played on without the player screen. */
        val continuation: (suspend () -> Unit)? = null,
    )

    var target: Target? = null
        private set
    var entry: Entry? = null
        private set
    /** An open player screen adopts the next song itself. */
    var screenAttached: Boolean = false
        private set

    fun bind(next: Target) {
        if (target === next) return
        target = next
        apply()
    }

    /** The engine was released: nothing may follow it any more. */
    fun unbind(old: Target?) {
        if (old != null && target !== old) return
        target = null
        entry = null
        screenAttached = false
    }

    fun attachScreen(next: Target) {
        bind(next)
        screenAttached = true
    }

    /** The screen left (minimized, background): the queued song stays and plays on. */
    fun detachScreen(old: Target) {
        if (target === old) screenAttached = false
    }

    /** Replaces the queued song; null removes it. */
    fun offer(next: Entry?) {
        entry = next
        apply()
    }

    /** Re-checks the queue, e.g. after the sleep timer changed. */
    fun refresh() = apply()

    private fun apply() {
        val player = target ?: return
        val index = player.currentIndex
        if (player.itemCount > index + 1) player.removeItems(index + 1, player.itemCount)
        val next = entry ?: return
        if (endOfItemSleep() || sessionRoute() != next.fromRoute) return
        player.appendItem(next.ratingKey, next.streamUrl)
    }

    /**
     * Media3 moved on to a queued song by itself. Returns true when this queue took it over
     * (no player screen); with a screen the screen adopts it.
     */
    fun onAutoTransition(player: Target, mediaId: String?): Boolean {
        if (target !== player || screenAttached) return false
        val next = entry?.takeIf { it.ratingKey == mediaId }
        if (next == null) {
            // Never play an unknown song on under the previous song's title.
            Timber.w("Gapless: unexpected item %s without the player screen", mediaId)
            player.pause()
            return false
        }
        entry = null
        if (!continueSession(next.fromRoute, next.metadata)) {
            Timber.w("Gapless: session refused %s", next.ratingKey)
            player.pause()
            return false
        }
        player.currentIndex.takeIf { it > 0 }?.let { player.removeItems(0, it) }
        next.continuation?.let { continuation -> launch { continuation() } }
        return true
    }
}

/** The app's single [GaplessQueue], bound to the Media3 engine of the current playback. */
internal object Media3GaplessQueue {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var continuationJob: Job? = null

    val queue = GaplessQueue(
        endOfItemSleep = { BackgroundPlaybackSession.sleepTimer.kind == SleepTimerKind.END_OF_ITEM },
        sessionRoute = { BackgroundPlaybackSession.snapshot.value.takeIf { it.active }?.metadata?.route },
        continueSession = BackgroundPlaybackSession::continueWithNextItem,
        launch = { block ->
            continuationJob?.cancel()
            continuationJob = scope.launch {
                runCatching { block() }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it; Timber.w(it, "Gapless: next song not prepared") }
            }
        },
    )

    private class ExoTarget(player: ExoPlayer) : GaplessQueue.Target, Player.Listener {
        private val ref = WeakReference(player)
        override val currentIndex get() = ref.get()?.currentMediaItemIndex ?: 0
        override val itemCount get() = ref.get()?.mediaItemCount ?: 0
        override fun removeItems(fromIndex: Int, toIndex: Int) {
            runCatching { ref.get()?.removeMediaItems(fromIndex, toIndex) }
        }
        override fun appendItem(id: String, url: String) {
            ref.get()?.addMediaItem(androidx.media3.common.MediaItem.Builder().setUri(url).setMediaId(id).build())
        }
        override fun pause() { ref.get()?.pause() }
        fun owns(player: ExoPlayer) = ref.get() === player
        fun detach() { ref.get()?.removeListener(this) }
        override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
            if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) return
            queue.onAutoTransition(this, mediaItem?.mediaId)
        }
    }

    private fun targetFor(player: ExoPlayer): ExoTarget {
        (queue.target as? ExoTarget)?.takeIf { it.owns(player) }?.let { return it }
        (queue.target as? ExoTarget)?.let { old -> release(old) }
        return ExoTarget(player).also(player::addListener)
    }

    private fun release(old: ExoTarget) {
        old.detach()
        continuationJob?.cancel()
        continuationJob = null
        queue.unbind(old)
    }

    /** The player screen shows this engine and adopts the next song itself. */
    fun attachScreen(player: ExoPlayer) = queue.attachScreen(targetFor(player))

    /** Minimized or in the background: the queued song stays and this queue adopts it. */
    fun detachScreen(player: ExoPlayer) {
        (queue.target as? ExoTarget)?.takeIf { it.owns(player) }?.let(queue::detachScreen)
    }

    /** The prepared next song for [player]'s current playback; null removes it. */
    fun offer(player: ExoPlayer, entry: GaplessQueue.Entry?) {
        queue.bind(targetFor(player))
        queue.offer(entry)
    }

    /** Removes the queued song of whichever engine holds one (manual next, new item, Stop). */
    fun clear() {
        continuationJob?.cancel()
        continuationJob = null
        queue.offer(null)
    }

    fun refresh() = queue.refresh()

    /** The engine is released (Stop, swipe away, standby, renderer switch). */
    fun released(player: ExoPlayer) {
        val target = (queue.target as? ExoTarget)?.takeIf { it.owns(player) } ?: return
        release(target)
    }
}
