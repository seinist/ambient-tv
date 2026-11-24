package com.ambient.tv.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

/**
 * One seek path per player screen, on the absolute (timeline) clock.
 *
 * Arrows, media keys, OSD buttons and the seek bar all stack on the requested time. Re-reading
 * the engine instead is what made seeks bounce: VLC/MPV still report the pre-seek position while
 * a seek is in flight, so the next jump started from there (or the OSD fell back to it).
 */
@Stable
class PlaybackSeekState internal constructor(
    private val coordinator: PlaybackSeekCoordinator,
    initialPositionMs: Long,
    private val onSeekRequested: () -> Unit = {},
    private val isCurrent: () -> Boolean = { true },
    private val readEnginePositionMs: (() -> Long)? = null,
    private val commit: (Long) -> Unit,
) {
    /** What the OSD shows: the requested target while a seek is in flight, else the engine. */
    var positionMs by mutableLongStateOf(initialPositionMs.coerceAtLeast(0L))
        private set
    /** Chapter membership follows the requested target even when the decoder lands just before it. */
    val chapterPositionMs: Long get() = coordinator.chapterPositionMs(positionMs)
    internal var requestVersion by mutableIntStateOf(0)
        private set
    /** Hidden chrome must still reconcile a committed seek before suspending its clock. */
    internal var awaitingSeekAck by mutableStateOf(false)
        private set

    internal val isActive: Boolean get() = isCurrent()

    fun seekBy(deltaMs: Long, durationMs: Long) {
        if (!isActive) return
        refreshForInput()
        request(coordinator.requestRelative(deltaMs, positionMs, durationMs))
    }

    fun seekTo(targetMs: Long) {
        if (!isActive) return
        refreshForInput()
        request(coordinator.requestAbsolute(targetMs))
    }

    /** Restart the absolute title timeline, replacing any pending seek before resuming. */
    internal fun playFromStart(onPlay: () -> Unit) {
        if (!isActive) return
        seekTo(0L)
        // A button press is a complete action, not a burst of relative remote-key seeks.
        flushPending()
        onPlay()
    }

    /** Hidden OSD polling may be suspended; input still starts from the current decoder clock. */
    internal fun refreshForInput(): Long {
        if (isActive) readEnginePositionMs?.let { sync(it()) }
        return positionMs
    }

    private fun request(target: Long) {
        onSeekRequested()
        positionMs = target
        awaitingSeekAck = coordinator.pendingTarget != null
        coordinator.consumeCommit()?.let(commit)
        requestVersion++
    }

    internal val pendingTargetMs: Long? get() = coordinator.pendingTarget.takeIf { isActive }

    /** A session/remote seek supersedes the local target without sending it to the decoder again. */
    internal fun followExternalSeek(targetMs: Long) {
        if (!isActive) return
        coordinator.followExternalSeek(targetMs)
        positionMs = targetMs.coerceAtLeast(0L)
        awaitingSeekAck = coordinator.pendingTarget != null
    }

    /** An asynchronous source restart owns seeking; follow the real clock until it is ready. */
    internal fun cancelPendingSeek(actualPositionMs: Long) {
        if (!isActive) return
        coordinator.cancelPendingSeek()
        positionMs = actualPositionMs.coerceAtLeast(0L)
        awaitingSeekAck = false
    }

    internal fun flushPending() {
        if (!isActive) return
        coordinator.consumeCommit(force = true)?.let(commit)
    }

    internal fun commitSettled() {
        if (!isActive) return
        // The effect already waited for the last request. Checking a second clock here can
        // miss its boundary by a millisecond and silently drop the burst's final commit.
        coordinator.consumeCommit(force = true)?.let(commit)
    }

    internal fun sync(enginePositionMs: Long) {
        if (!isActive) return
        positionMs = coordinator.displayedPosition(enginePositionMs)
        awaitingSeekAck = coordinator.pendingTarget != null
    }

    internal val debounceMs: Long get() = coordinator.debounceMs
}

/**
 * [enginePositionMs] and [commit] both use the timeline clock (engine position plus
 * `timelineOffsetMs`), so a target below a transcode's offset still reaches `seekTimeline`.
 */
@Composable
fun rememberPlaybackSeekState(
    key: Any?,
    enginePositionMs: () -> Long,
    commit: (Long) -> Unit,
    pollIntervalMs: Long = 250L,
    /** Hidden chrome stops polling once any pending seek has been reconciled. */
    pollEnabled: Boolean = true,
    /** Live/Timeshift keeps its own seek owner; recording-only states must not take it over. */
    registerWithSession: Boolean = true,
): PlaybackSeekState {
    val owner = BackgroundPlaybackSession.activeHandle()
    val generation = BackgroundPlaybackSession.snapshot.value.generation
    // Gapless playback reuses one decoder across items. Scope both state and updated callbacks
    // to its session generation, so disposal cannot flush an old target through a new callback.
    return androidx.compose.runtime.key(key, owner, generation) {
        val currentEngine by rememberUpdatedState(enginePositionMs)
        val currentCommit by rememberUpdatedState(commit)
        val state = remember {
            PlaybackSeekState(PlaybackSeekCoordinator(), currentEngine(),
                onSeekRequested = BackgroundPlaybackSession::userSeek,
                isCurrent = {
                    val current = BackgroundPlaybackSession.snapshot.value
                    playbackSeekOwnerIsCurrent(owner, generation, BackgroundPlaybackSession.activeHandle(),
                        current.generation, current.replacingSource)
                },
                readEnginePositionMs = { currentEngine() },
                commit = { currentCommit(it) })
        }
        androidx.compose.runtime.DisposableEffect(state, registerWithSession) {
            if (registerWithSession) BackgroundPlaybackSession.registerSeekState(owner, state)
            onDispose {
                // Minimize keeps the owner and flushes; a replaced item/decoder is already inert.
                if (registerWithSession) state.flushPending()
                BackgroundPlaybackSession.unregisterSeekState(state)
            }
        }
        val shouldPoll = pollEnabled || state.awaitingSeekAck
        LaunchedEffect(state, shouldPoll, pollIntervalMs) {
            if (!shouldPoll) return@LaunchedEffect
            while (true) {
                if (!state.isActive) return@LaunchedEffect
                state.sync(currentEngine())
                delay(pollIntervalMs)
            }
        }
        LaunchedEffect(state, state.requestVersion) {
            if (state.requestVersion == 0) return@LaunchedEffect
            delay(state.debounceMs)
            state.commitSettled()
        }
        state
    }
}
