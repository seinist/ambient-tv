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
): PlaybackSeekState {
    val currentEngine by rememberUpdatedState(enginePositionMs)
    val currentCommit by rememberUpdatedState(commit)
    val state = remember(key) {
        PlaybackSeekState(PlaybackSeekCoordinator(), currentEngine(),
            commit = { currentCommit(it) }, onSeekRequested = BackgroundPlaybackSession::userSeek)
    }
    androidx.compose.runtime.DisposableEffect(state) {
        val owner = BackgroundPlaybackSession.activeHandle()
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

/**
 * Seek step for a held key: starts at [baseMs] and doubles every [SEEK_DOUBLING_REPEATS] key
 * repeats, so a long press accelerates smoothly instead of in fixed tiers. Capped at a fraction of
 * [durationMs] so a long press crosses a film in a few seconds without overshooting short clips.
 */
internal fun acceleratedSeekStepMs(baseMs: Long, repeatCount: Int, durationMs: Long): Long {
    val cap = if (durationMs in 1 until Long.MAX_VALUE) {
        (durationMs / SEEK_MAX_STEP_DIVISOR).coerceAtLeast(baseMs)
    } else SEEK_MAX_STEP_UNKNOWN_MS.coerceAtLeast(baseMs)
    val factor = Math.pow(2.0, repeatCount.coerceAtLeast(0) / SEEK_DOUBLING_REPEATS)
    return (baseMs * factor).toLong().coerceIn(baseMs, cap)
}

private const val SEEK_DOUBLING_REPEATS = 6.0
private const val SEEK_MAX_STEP_DIVISOR = 30L
private const val SEEK_MAX_STEP_UNKNOWN_MS = 300_000L

/** Keeps emulator key-up/down pairs in one burst without accelerating ordinary spaced taps. */
internal class SeekKeyAcceleration {
    private var keyCode = -1
    private var lastEventMs = Long.MIN_VALUE
    private var repeats = 0

    fun step(baseMs: Long, key: Int, eventMs: Long, nativeRepeat: Int, durationMs: Long): Long {
        val gap = if (lastEventMs == Long.MIN_VALUE) Long.MAX_VALUE else eventMs - lastEventMs
        // Native keyboards wait before their first repeat; synthetic pairs have no such signal.
        val limit = if (repeats == 0 && nativeRepeat > 0) 650L else 180L
        val continuing = key == keyCode && gap in 0L..limit
        repeats = if (continuing) maxOf(repeats + 1, nativeRepeat) else 0
        keyCode = key
        lastEventMs = eventMs
        return acceleratedSeekStepMs(baseMs, repeats, durationMs)
    }
}
