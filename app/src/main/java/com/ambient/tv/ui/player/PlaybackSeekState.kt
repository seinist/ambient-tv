package com.ambient.tv.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
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
    private val commit: (Long) -> Unit,
) {
    /** What the OSD shows: the requested target while a seek is in flight, else the engine. */
    var positionMs by mutableLongStateOf(initialPositionMs.coerceAtLeast(0L))
        private set
    /** Chapter membership follows the requested target even when the decoder lands just before it. */
    val chapterPositionMs: Long get() = coordinator.chapterPositionMs(positionMs)
    internal var requestVersion by mutableIntStateOf(0)
        private set

    fun seekBy(deltaMs: Long, durationMs: Long) =
        request(coordinator.requestRelative(deltaMs, positionMs, durationMs))

    fun seekTo(targetMs: Long) = request(coordinator.requestAbsolute(targetMs))

    private fun request(target: Long) {
        onSeekRequested()
        positionMs = target
        coordinator.consumeCommit()?.let(commit)
        requestVersion++
    }

    internal val pendingTargetMs: Long? get() = coordinator.pendingTarget

    /** A session/remote seek supersedes the local target without sending it to the decoder again. */
    internal fun followExternalSeek(targetMs: Long) {
        coordinator.followExternalSeek(targetMs)
        positionMs = targetMs.coerceAtLeast(0L)
    }

    internal fun flushPending() {
        coordinator.consumeCommit(force = true)?.let(commit)
    }

    internal fun commitSettled() {
        coordinator.consumeCommit()?.let(commit)
    }

    internal fun sync(enginePositionMs: Long) {
        positionMs = coordinator.displayedPosition(enginePositionMs)
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
    /** When false, timeline position is not polled (used with PerformanceProfile OSD gate). */
    pollEnabled: Boolean = true,
    /** Live/Timeshift keeps its own seek owner; recording-only states must not take it over. */
    registerWithSession: Boolean = true,
): PlaybackSeekState {
    val currentEngine by rememberUpdatedState(enginePositionMs)
    val currentCommit by rememberUpdatedState(commit)
    val state = remember(key) {
        PlaybackSeekState(PlaybackSeekCoordinator(), currentEngine(),
            commit = { currentCommit(it) }, onSeekRequested = BackgroundPlaybackSession::userSeek)
    }
    androidx.compose.runtime.DisposableEffect(state, registerWithSession) {
        val owner = BackgroundPlaybackSession.activeHandle().takeIf { registerWithSession }
        BackgroundPlaybackSession.registerSeekState(owner, state)
        onDispose {
            if (owner != null && BackgroundPlaybackSession.owns(owner)) state.flushPending()
            BackgroundPlaybackSession.unregisterSeekState(state)
        }
    }
    LaunchedEffect(state, pollEnabled, pollIntervalMs) {
        if (!pollEnabled) return@LaunchedEffect
        while (true) {
            state.sync(currentEngine())
            delay(pollIntervalMs)
        }
    }
    LaunchedEffect(state, state.requestVersion) {
        if (state.requestVersion == 0) return@LaunchedEffect
        delay(state.debounceMs)
        state.commitSettled()
    }
    return state
}
