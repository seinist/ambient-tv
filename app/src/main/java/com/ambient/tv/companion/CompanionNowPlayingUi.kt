package com.ambient.tv.companion

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ambient.tv.R

@Composable
private fun RemoteArtwork(frame: CompanionFrame, modifier: Modifier = Modifier) {
    val bitmap = remember(frame.artwork) { decodeCompanionArtwork(frame.artwork) }
    Box(modifier.clip(RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
        if (bitmap != null) androidx.compose.foundation.Image(bitmap.asImageBitmap(), frame.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(Icons.Default.Cast, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
    }
}

@Composable
internal fun CompanionRemoteMiniPlayer(manager: CompanionManager, state: CompanionState) {
    val frame = state.currentPlayback() ?: return
    Surface(tonalElevation = 3.dp) {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).clickable(onClick = manager::showNowPlaying).padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                RemoteArtwork(frame, Modifier.size(44.dp))
                Column(Modifier.weight(1f)) {
                    Text(frame.title.ifBlank { stringResource(R.string.playback_status_loading) }, style = MaterialTheme.typography.titleSmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    frame.subtitle.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    val time = if (frame.isLive) "LIVE" else if (frame.duration > 0) "${playbackTime(frame.position)} / ${playbackTime(frame.duration)}" else ""
                    Text(listOf(state.target?.name.orEmpty(), playbackPhaseLabel(frame.phase), time).filter { it.isNotBlank() }.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            frame.primaryPlaybackAction?.let { action ->
                IconButton({ manager.playbackCommand(action, frame) }) {
                    Icon(if (action == "pause") Icons.Default.Pause else Icons.Default.PlayArrow,
                        stringResource(when (action) { "retry" -> R.string.home_retry; "pause" -> R.string.player_pause; else -> R.string.player_play }))
                }
            }
            if (frame.capabilities.isEmpty() || "stop" in frame.capabilities)
                IconButton({ manager.playbackCommand("stop", frame) }) { Icon(Icons.Default.Stop, stringResource(R.string.player_stop)) }
        }
    }
}

@Composable
internal fun CompanionNowPlayingDialog(manager: CompanionManager, state: CompanionState) {
    com.ambient.tv.ui.common.AppDialog(onDismiss = manager::hideNowPlaying,
        title = stringResource(R.string.companion_remote_target, state.target?.name.orEmpty()),
        footer = {
            com.ambient.tv.ui.common.TvActionButton(stringResource(R.string.companion_remote_control), manager::showRemote)
            com.ambient.tv.ui.common.TvActionButton(stringResource(R.string.action_close), manager::hideNowPlaying)
        }) {
        val frame = state.currentPlayback()
        if (frame == null) Text(stringResource(R.string.playback_status_idle))
        else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                RemoteArtwork(frame, Modifier.size(96.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(frame.title.ifBlank { stringResource(R.string.playback_status_loading) }, style = MaterialTheme.typography.titleMedium)
                    frame.subtitle.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    Text(playbackPhaseLabel(frame.phase), color = MaterialTheme.colorScheme.primary)
                }
            }
            key(frame.media ?: frame.title, frame.playbackSessionId) {
                if (frame.seekable) com.ambient.tv.ui.mobile.HandheldSeekBar(frame.position, frame.duration,
                    onSeekBy = { delta -> manager.playbackCommand("seek", frame, frame.position + delta) }, onInteraction = {})
                else if (frame.isLive) Text("LIVE")
                else if (frame.duration > 0) Text("${playbackTime(frame.position)} / ${playbackTime(frame.duration)}")
            }
            CompanionPlaybackOptions(manager, frame)
            com.ambient.tv.ui.mobile.DeviceActionRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                frame.primaryPlaybackAction?.let { action ->
                    com.ambient.tv.ui.common.TvActionButton(stringResource(when (action) { "retry" -> R.string.home_retry; "pause" -> R.string.player_pause; else -> R.string.player_play }),
                        { manager.playbackCommand(action, frame) }, icon = if (action == "pause") Icons.Default.Pause else Icons.Default.PlayArrow)
                }
                if (frame.capabilities.isEmpty() || "stop" in frame.capabilities)
                    com.ambient.tv.ui.common.TvActionButton(stringResource(R.string.player_stop), { manager.playbackCommand("stop", frame) }, icon = Icons.Default.Stop)
            }
        }
        state.remote?.takeIf { state.online && (it.receiverId.isEmpty() || it.receiverId == state.target?.fingerprint) }
            ?.let { CompanionQueueControls(manager, it) }
    }
}
