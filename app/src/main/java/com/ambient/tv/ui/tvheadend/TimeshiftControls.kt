package com.ambient.tv.ui.tvheadend

import androidx.compose.runtime.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.ui.res.stringResource
import com.ambient.tv.R
import com.ambient.tv.ui.common.PlayerPanelEntry
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import com.ambient.tv.livetv.LiveTimeshift

internal fun seekLiveTimeshiftBy(positionMs: Long, deltaMs: Long) {
    val buffer = LiveTimeshift.current ?: return
    val anchor = LiveTimeshift.anchor.value ?: return
    val seek = LiveTimeshift.seekHandler ?: return
    val edge = buffer.liveWallMs
    val oldest = buffer.oldestWallMs.coerceAtMost(edge)
    val position = anchor + positionMs.coerceIn(0L, (edge - anchor).coerceAtLeast(0L))
    val target = (position + deltaMs).coerceIn(oldest, edge)
    seek(if (deltaMs > 0 && target >= edge - 1_500L) null else target)
}

internal fun handleTimeshiftMediaKey(
    event: KeyEvent,
    positionMs: Long,
    jumpMs: Long,
    onInteraction: () -> Unit,
): Boolean {
    val delta = when (event.key) {
        Key.MediaRewind -> -jumpMs
        Key.MediaFastForward -> jumpMs
        else -> return false
    }
    if (LiveTimeshift.current == null || LiveTimeshift.anchor.value == null || LiveTimeshift.seekHandler == null) return false
    if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0) {
        onInteraction()
        seekLiveTimeshiftBy(positionMs, delta)
    }
    return true
}

/** Closer to the broadcast than this counts as live (player start-up and network buffering). */
internal const val TIMESHIFT_LIVE_TOLERANCE_MS = 10_000L

internal fun canJumpToLive(isLive: Boolean, anchorMs: Long?, positionMs: Long, edgeMs: Long?, canSeek: Boolean): Boolean {
    if (!isLive || anchorMs == null || edgeMs == null || !canSeek) return false
    val elapsed = (edgeMs - anchorMs).coerceAtLeast(0L)
    return elapsed - positionMs.coerceIn(0L, elapsed) > TIMESHIFT_LIVE_TOLERANCE_MS
}

@Composable
internal fun jumpToLiveAvailable(isLive: Boolean, positionMs: Long): Boolean {
    val anchor by LiveTimeshift.anchor.collectAsState()
    // The buffer advances even while playback is paused.
    val edge by produceState<Long?>(null, isLive, anchor) {
        while (isLive && anchor != null) {
            value = LiveTimeshift.current?.liveWallMs
            kotlinx.coroutines.delay(1_000L)
        }
    }
    return canJumpToLive(isLive, anchor, positionMs, edge, LiveTimeshift.seekHandler != null)
}

@Composable
internal fun JumpToLivePanelEntry(isLive: Boolean, positionMs: Long) {
    val available = jumpToLiveAvailable(isLive, positionMs)
    // Seeking replaces the stream and immediately makes this action unavailable. Keep the
    // same focus target until the panel closes instead of removing its focused child.
    var wasAvailable by remember(isLive) { mutableStateOf(false) }
    SideEffect { if (available) wasAvailable = true }
    if (isLive && (available || wasAvailable)) {
        PlayerPanelEntry(
            icon = Icons.Filled.PlayArrow,
            title = stringResource(R.string.player_jump_live),
            value = stringResource(R.string.player_jump_live_hint),
            onClick = { if (available) LiveTimeshift.seekHandler?.invoke(null) },
        )
    }
}
