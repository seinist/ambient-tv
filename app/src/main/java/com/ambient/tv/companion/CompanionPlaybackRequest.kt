package com.ambient.tv.companion

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import com.ambient.tv.R
import com.ambient.tv.ui.common.AppDialog
import com.ambient.tv.ui.common.TvActionButton

internal class CompanionSourceUnavailableException : IllegalStateException()

internal data class CompanionPlaybackRequest(
    val media: CompanionMedia?,
    val title: String,
    val playLocally: () -> Unit,
    val starting: Boolean = false,
    val device: String = "",
    val error: Int? = null,
)

internal fun companionStartConfirmed(request: CompanionMedia, frame: CompanionFrame, requestId: String = ""): Boolean {
    if (!frame.playbackActive || frame.phase != "playing") return false
    if ("start_confirmation" in frame.capabilities && requestId.isNotEmpty()) {
        return frame.startRequestId == requestId && frame.startStatus == "playing" && frame.phase == "playing"
    }
    if (samePlaybackMedia(request, frame.media)) return true
    // Spotify projects the current track, even when an album/playlist was requested.
    return request.kind in setOf("spotify:album", "spotify:artist", "spotify:playlist") &&
        frame.media?.kind == "spotify:track" &&
        (if (request.offsetId.isNotEmpty()) frame.media.id == request.offsetId else request.id in frame.parentIds)
}

@Composable
internal fun CompanionPlaybackTargetDialog(manager: CompanionManager, state: CompanionState) {
    val request by manager.playbackRequest.collectAsState()
    val selection = request ?: return
    AppDialog(
        onDismiss = { if (!selection.starting) manager.cancelPlaybackRequest() },
        title = stringResource(R.string.companion_choose_playback),
        footer = {
            TvActionButton(stringResource(R.string.files_cancel), manager::cancelPlaybackRequest,
                enabled = !selection.starting)
        },
    ) {
        if (selection.title.isNotBlank()) Text(selection.title, style = MaterialTheme.typography.titleMedium)
        if (selection.starting) {
            CircularProgressIndicator()
            Text(stringResource(R.string.companion_starting_on, selection.device))
            if (!state.online) state.connectionStage?.let { Text(stringResource(it)) }
        } else {
            selection.error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
            TvActionButton(stringResource(R.string.companion_play_here), manager::playRequestLocally, fillWidth = true)
            if (selection.media == null) Text(stringResource(R.string.companion_source_phone_only))
            state.paired.forEach { peer ->
                val connected = state.online && state.target?.fingerprint == peer.fingerprint
                TvActionButton(stringResource(if (connected) R.string.companion_play_on else R.string.companion_reconnect_play, peer.name),
                    { manager.playRequestOn(peer) }, enabled = selection.media != null, fillWidth = true)
            }
        }
    }
}
