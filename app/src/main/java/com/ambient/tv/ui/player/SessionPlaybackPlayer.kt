@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.ambient.tv.ui.player

import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.Executor

/** Published state of the existing renderer or of the Spotify playback mirrored by Android Auto. */
internal data class SessionPlaybackState(
    val mediaItem: MediaItem? = null,
    val uid: Any? = null,
    val positionMs: Long = 0L,
    val durationMs: Long = C.TIME_UNSET,
    val playWhenReady: Boolean = false,
    val buffering: Boolean = false,
    val ended: Boolean = false,
    val error: String? = null,
    val canSeek: Boolean = false,
    val hasPrevious: Boolean = false,
    val hasNext: Boolean = false,
    val isLive: Boolean = false,
)

/**
 * Media3's controller-facing player. The service owns the renderers and their audio focus; this
 * adapter only publishes their state and forwards transport commands to the existing controls.
 */
internal class SessionPlaybackPlayer(
    looper: Looper,
    private val actions: Actions,
) : SimpleBasePlayer(looper) {
    interface Actions {
        /** Cancels still-resolving library requests before a possibly deferred engine pause. */
        fun onPauseRequested() = Unit
        fun play()
        fun pause()
        fun stop()
        fun seekTo(positionMs: Long)
        fun skip(next: Boolean)
        /** Completes when the selected source owns playback, including Spotify's async result. */
        fun select(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<*>
    }

    private var published = SessionPlaybackState()
    private class PreparedSelection(
        val mediaItems: List<MediaItem>,
        val startIndex: Int,
        val startPositionMs: Long,
    ) {
        var prepared = false
        var started = false
        var playWhenReady = false
        val item get() = mediaItems[startIndex.takeIf { it != C.INDEX_UNSET } ?: 0]
    }
    private var preparedSelection: PreparedSelection? = null
    private var closed = false
    private var lastError: PlaybackException? = null
    private val handler = Handler(looper)
    private val applicationExecutor = Executor { task ->
        if (Looper.myLooper() == looper) task.run() else handler.post(task)
    }
    private val selection = PlaybackSelectionGate(applicationExecutor) { play ->
        if (play) actions.play() else actions.pause()
    }

    val hasPreparedSelection: Boolean get() = preparedSelection?.started == false
    val isSelecting: Boolean get() = preparedSelection?.started == true

    fun update(state: SessionPlaybackState) {
        verifyApplicationThread()
        if (closed) return
        // A prepare-only request survives ordinary ticks/metadata changes of the paused source.
        // An actual new source started in the app supersedes that unstarted Auto selection.
        if (hasPreparedSelection && state.mediaItem != null && state.uid != published.uid) {
            preparedSelection = null
        }
        published = state
        invalidateState()
    }

    override fun getState(): State {
        val prepared = preparedSelection
        val source = prepared?.let {
            SessionPlaybackState(
                mediaItem = it.item,
                uid = it,
                positionMs = it.startPositionMs.takeIf { position -> position != C.TIME_UNSET } ?: 0L,
                playWhenReady = it.playWhenReady,
                buffering = it.started,
            )
        } ?: published
        val item = source.mediaItem
        val playbackState = when {
            source.error != null || item == null -> Player.STATE_IDLE
            prepared != null && !prepared.prepared -> Player.STATE_IDLE
            source.ended -> Player.STATE_ENDED
            source.buffering -> Player.STATE_BUFFERING
            else -> Player.STATE_READY
        }
        val durationMs = source.durationMs.takeIf { it > 0L } ?: C.TIME_UNSET
        val itemData = item?.let {
            MediaItemData.Builder(source.uid ?: it.mediaId)
                .setMediaItem(it)
                .setMediaMetadata(it.mediaMetadata)
                .setIsSeekable(source.canSeek)
                .setIsDynamic(source.isLive)
                .setLiveConfiguration(if (source.isLive) MediaItem.LiveConfiguration.UNSET else null)
                .setDurationUs(if (durationMs == C.TIME_UNSET) C.TIME_UNSET else durationMs * 1_000L)
                .build()
        }
        if (lastError?.message != source.error) {
            lastError = source.error?.let { PlaybackException(it, null, PlaybackException.ERROR_CODE_UNSPECIFIED) }
        }
        return State.Builder()
            .setAvailableCommands(sessionPlaybackCommands(source))
            .setPlaylist(itemData?.let(::listOf) ?: emptyList())
            .setCurrentMediaItemIndex(if (itemData == null) C.INDEX_UNSET else 0)
            .setContentPositionMs(source.positionMs.coerceAtLeast(0L))
            .setPlayWhenReady(source.playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(playbackState)
            .setIsLoading(playbackState == Player.STATE_BUFFERING)
            .setPlayerError(lastError)
            .build()
    }

    override fun getPlaceholderState(suggestedPlaceholderState: State): State {
        if (!selection.isPending) return suggestedPlaceholderState
        // A newly selected catalog item must not inherit the outgoing source's seek/skip controls.
        // Play and Pause are accepted here, but are applied only after that selection succeeds.
        val hasItem = !suggestedPlaceholderState.timeline.isEmpty
        return suggestedPlaceholderState.buildUpon()
            .setAvailableCommands(sessionPlaybackCommands(SessionPlaybackState()))
            .setPlayerError(null)
            .setPlaybackState(if (hasItem) Player.STATE_BUFFERING else Player.STATE_IDLE)
            .setIsLoading(hasItem)
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (!playWhenReady) actions.onPauseRequested()
        preparedSelection?.let { prepared ->
            prepared.playWhenReady = playWhenReady
            if (!prepared.started) {
                return if (playWhenReady) startSelection(prepared) else Futures.immediateVoidFuture()
            }
        }
        return selection.setPlayWhenReady(playWhenReady)
    }

    override fun handlePrepare(): ListenableFuture<*> {
        preparedSelection?.prepared = true
        return selection.awaitSelection()
    }

    override fun handleSetMediaItems(
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<*> {
        if (mediaItems.size != 1 || (startIndex != C.INDEX_UNSET && startIndex != 0)) {
            return Futures.immediateFailedFuture<Any>(IllegalArgumentException("Select one catalog item"))
        }
        val startPlaying = playWhenReady
        preparedSelection = null
        selection.cancel()
        val prepared = PreparedSelection(mediaItems, startIndex, startPositionMs)
        preparedSelection = prepared
        if (startPlaying) return startSelection(prepared)
        // The existing backends start on selection, so resolve them lazily for prepare-only
        // controller requests. This also avoids a brief, audible Spotify start followed by Pause.
        actions.pause()
        return Futures.immediateVoidFuture()
    }

    private fun startSelection(prepared: PreparedSelection): ListenableFuture<*> {
        prepared.prepared = true
        prepared.started = true
        prepared.playWhenReady = true
        return selection.start(initialPlayWhenReady = true, onFinished = {
            if (preparedSelection === prepared) preparedSelection = null
        }) {
            actions.select(prepared.mediaItems, prepared.startIndex, prepared.startPositionMs)
        }
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        if (selection.isPending) return selection.awaitSelection()
        when (seekCommand) {
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM ->
                if (published.hasPrevious) actions.skip(next = false)
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM ->
                if (published.hasNext) actions.skip(next = true)
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM -> if (published.canSeek && positionMs != C.TIME_UNSET) {
                val position = positionMs.coerceAtLeast(0L).let {
                    if (published.durationMs > 0L) it.coerceAtMost(published.durationMs) else it
                }
                actions.seekTo(position)
            }
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        preparedSelection = null
        selection.cancel()
        actions.stop()
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        closed = true
        preparedSelection = null
        selection.cancel()
        // Releasing an obsolete service's controller must not release a newer renderer generation.
        return Futures.immediateVoidFuture()
    }
}

internal fun sessionPlaybackCommands(source: SessionPlaybackState): Player.Commands =
    Player.Commands.Builder().addAll(
        Player.COMMAND_PLAY_PAUSE,
        Player.COMMAND_PREPARE,
        Player.COMMAND_STOP,
        Player.COMMAND_RELEASE,
        Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
        Player.COMMAND_GET_TIMELINE,
        Player.COMMAND_GET_METADATA,
        // Catalog selections are single items; the existing source queues own queue navigation.
        Player.COMMAND_SET_MEDIA_ITEM,
    ).apply {
        if (source.mediaItem != null && source.canSeek) add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
        if (source.mediaItem != null && source.hasPrevious) {
            addAll(Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
        }
        if (source.mediaItem != null && source.hasNext) {
            addAll(Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
        }
    }.build()

/**
 * MediaSession sends setMediaItem, prepare and play consecutively, without awaiting source
 * resolution. Keep their play intent off the outgoing engine and discard it when cancelled.
 */
internal class PlaybackSelectionGate(
    private val executor: Executor,
    private val applyPlayWhenReady: (Boolean) -> Unit,
) {
    private class Pending(val operation: ListenableFuture<*>, var playWhenReady: Boolean)
    private var pending: Pending? = null
    val isPending: Boolean get() = pending != null

    fun start(
        initialPlayWhenReady: Boolean,
        onFinished: () -> Unit,
        select: () -> ListenableFuture<*>,
    ): ListenableFuture<*> {
        cancel()
        val operation = try {
            select()
        } catch (error: Exception) {
            onFinished()
            return Futures.immediateFailedFuture<Any>(error)
        }
        val request = Pending(operation, initialPlayWhenReady)
        pending = request
        operation.addListener({
            if (pending !== request) return@addListener
            pending = null
            onFinished()
            if (runCatching { Futures.getDone(operation) }.isSuccess) {
                applyPlayWhenReady(request.playWhenReady)
            }
        }, executor)
        return operation
    }

    fun setPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        val request = pending
        if (request != null) {
            request.playWhenReady = playWhenReady
            return request.operation
        }
        applyPlayWhenReady(playWhenReady)
        return Futures.immediateVoidFuture()
    }

    fun awaitSelection(): ListenableFuture<*> = pending?.operation ?: Futures.immediateVoidFuture()

    fun cancel() {
        val previous = pending
        pending = null
        previous?.operation?.cancel(true)
    }
}
