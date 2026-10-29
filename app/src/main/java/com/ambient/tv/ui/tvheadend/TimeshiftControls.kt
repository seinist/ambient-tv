package com.ambient.tv.ui.tvheadend

import androidx.compose.runtime.*
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import com.ambient.tv.livetv.LIVE_TIMESHIFT_PREROLL_MS
import com.ambient.tv.livetv.LiveTimeshift

/**
 * Wall-clock target of a relative timeshift seek, or null for the live edge. A forward seek that
 * would land closer to live than the preroll opens the live URL instead, so playback keeps the
 * full preroll and does not stall right after the reopen.
 */
internal fun timeshiftSeekTarget(anchorMs: Long, positionMs: Long, deltaMs: Long, oldestMs: Long, edgeMs: Long): Long? {
    val oldest = oldestMs.coerceAtMost(edgeMs)
    val position = anchorMs + positionMs.coerceIn(0L, (edgeMs - anchorMs).coerceAtLeast(0L))
    val target = (position + deltaMs).coerceIn(oldest, edgeMs)
    return if (deltaMs > 0 && target >= edgeMs - LIVE_TIMESHIFT_PREROLL_MS) null else target
}

internal fun seekLiveTimeshiftBy(positionMs: Long, deltaMs: Long) {
    val buffer = LiveTimeshift.current ?: return
    val anchor = LiveTimeshift.anchor.value ?: return
    val seek = LiveTimeshift.seekHandler ?: return
    com.ambient.tv.ui.player.BackgroundPlaybackSession.userSeek()
    seek(timeshiftSeekTarget(anchor, positionMs, deltaMs, buffer.oldestWallMs, buffer.liveWallMs))
}

internal fun handleTimeshiftMediaKey(
    event: KeyEvent,
    positionMs: Long,
    jumpMs: Long,
    onInteraction: () -> Unit,
): Boolean {
    val step = com.ambient.tv.ui.player.BackgroundPlaybackSession.seekJumpMs(jumpMs)
    val delta = when (event.key) {
        Key.MediaRewind -> -step
        Key.MediaFastForward -> step
        else -> return false
    }
    if (LiveTimeshift.current == null || LiveTimeshift.anchor.value == null || LiveTimeshift.seekHandler == null) return false
    if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0) {
        onInteraction()
        seekLiveTimeshiftBy(positionMs, delta)
    }
    return true
}

/** Closer to the broadcast than this counts as live (preroll, player start-up and network buffering). */
internal const val TIMESHIFT_LIVE_TOLERANCE_MS = 10_000L

/** "Zurück zu Live" is active when playback is clearly behind, or the server recording has ended. */
internal fun liveJumpEnabled(shiftable: Boolean, behindMs: Long, recordingFinished: Boolean = false): Boolean =
    shiftable && (recordingFinished || behindMs > TIMESHIFT_LIVE_TOLERANCE_MS)

internal fun canJumpToLive(isLive: Boolean, anchorMs: Long?, positionMs: Long, edgeMs: Long?, canSeek: Boolean, recordingFinished: Boolean = false): Boolean {
    if (!isLive || anchorMs == null || edgeMs == null || !canSeek) return false
    val elapsed = (edgeMs - anchorMs).coerceAtLeast(0L)
    return liveJumpEnabled(true, elapsed - positionMs.coerceIn(0L, elapsed), recordingFinished)
}

@Composable
internal fun jumpToLiveAvailable(isLive: Boolean, positionMs: Long): Boolean {
    val anchor by LiveTimeshift.anchor.collectAsState()
    // The buffer advances even while playback is paused.
    val window by produceState<Pair<Long?, Boolean>>(null to false, isLive, anchor) {
        while (isLive && anchor != null) {
            val buffer = LiveTimeshift.current
            value = buffer?.liveWallMs to (buffer?.finished == true)
            kotlinx.coroutines.delay(1_000L)
        }
    }
    return canJumpToLive(isLive, anchor, positionMs, window.first, LiveTimeshift.seekHandler != null, window.second)
}
