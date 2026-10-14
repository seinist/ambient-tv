package com.ambient.tv.companion

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ambient.tv.R
import com.ambient.tv.domain.model.MediaItem
import com.ambient.tv.spotify.SpotifyRepository
import com.ambient.tv.ui.mobile.LocalHandheldUi
import com.ambient.tv.ui.player.BackgroundPlaybackSession

internal val LocalCompanionConnection = compositionLocalOf<String?> { null }
internal val LocalPlaybackIndicators = compositionLocalOf { emptyList<PlaybackIndicator>() }
internal val LocalPlaybackTimelines = compositionLocalOf { emptyList<Pair<Boolean, CompanionFrame>>() }
internal val LocalPlaybackBrowseSources = compositionLocalOf { PlaybackBrowseSources() }
internal val LocalOpenActivePlayback = compositionLocalOf<(PlaybackIndicator) -> Unit> { {} }

/** Tile consumers observe identity/phase only, never the once-per-second playhead. */
@Composable
internal fun PlaybackPresentationProvider(manager: CompanionManager, mapper: CompanionMediaMapper,
    spotify: SpotifyRepository, onOpenLocal: (String) -> Unit, content: @Composable () -> Unit) {
    val session by BackgroundPlaybackSession.snapshot.collectAsState()
    val music by spotify.playback.collectAsState()
    val companion by manager.state.collectAsState()
    val handheld = LocalHandheldUi.current
    val localName = stringResource(if (handheld) R.string.companion_this_phone else R.string.playback_this_tv)
    val local = if (useSpotifyPlayback(session, music)) spotifyPlaybackFrame(music) else localPlaybackFrame(session).let { frame ->
        if (frame.media == null && session.metadata != null) frame.copy(media = CompanionMedia("device-local", session.metadata!!.mediaId)) else frame
    }
    val remote = if (handheld) companion.currentPlayback() else null
    fun indicator(frame: CompanionFrame, remote: Boolean, name: String): PlaybackIndicator? =
        frame.media?.takeIf { frame.playbackActive && frame.phase !in setOf("idle", "ended") }?.let {
            PlaybackIndicator(it, frame.parentIds, frame.phase, name, remote)
        }
    val projected = listOfNotNull(remote?.let { indicator(it, true, companion.target?.name.orEmpty()) }, indicator(local, false, localName))
    val indicators = remember(projected) { projected }
    val readSources = mapper.browseSources()
    val sources = remember(readSources) { readSources }
    val timelines = listOfNotNull(remote?.let { true to it }, false to local)
    val latestRoute = rememberUpdatedState(if (useSpotifyPlayback(session, music)) com.ambient.tv.ui.navigation.Destinations.spotifyPlayer() else session.metadata?.route)
    val openLocal = rememberUpdatedState(onOpenLocal)
    val open: (PlaybackIndicator) -> Unit = remember(manager) { { activity ->
        if (activity.remote) manager.showNowPlaying() else latestRoute.value?.let { openLocal.value(it) }
    } }
    CompositionLocalProvider(LocalCompanionConnection provides companion.target?.name?.takeIf { handheld && companion.online },
        LocalPlaybackIndicators provides indicators, LocalPlaybackTimelines provides timelines,
        LocalPlaybackBrowseSources provides sources, LocalOpenActivePlayback provides open, content = content)
}

@Composable
internal fun playbackIndicator(item: MediaItem): PlaybackIndicator? {
    val sources = LocalPlaybackBrowseSources.current
    return LocalPlaybackIndicators.current.firstOrNull { it.matches(item, sources) }
}

@Composable
internal fun playbackIndicator(media: CompanionMedia): PlaybackIndicator? =
    LocalPlaybackIndicators.current.firstOrNull { samePlaybackMedia(it.media, media) ||
        (media.kind == "youtube" && it.media.kind == "youtube" && media.id == it.media.id) ||
        (media.kind in setOf("spotify:album", "spotify:artist") && it.media.kind == "spotify:track" && media.id in it.parentIds) }

@Composable
internal fun playbackPhaseLabel(phase: String): String = stringResource(when (phase) {
    "playing" -> R.string.playback_status_playing
    "paused" -> R.string.playback_status_paused
    "loading", "buffering" -> R.string.playback_status_loading
    "error" -> R.string.playback_status_error
    else -> R.string.playback_status_idle
})

@Composable
internal fun PlaybackBadge(indicator: PlaybackIndicator?, modifier: Modifier = Modifier) {
    indicator ?: return
    val label = playbackPhaseLabel(indicator.phase)
    val description = stringResource(R.string.playback_status_on_device, label, indicator.device)
    Row(modifier.semantics { contentDescription = description }
        .background(Color.Black.copy(alpha = .82f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        androidx.compose.material3.Icon(when (indicator.phase) {
            "paused" -> Icons.Default.Pause
            "error" -> Icons.Default.ErrorOutline
            "loading", "buffering" -> Icons.Default.HourglassEmpty
            else -> Icons.Default.PlayArrow
        }, null, Modifier.size(14.dp), tint = if (indicator.phase == "error") MaterialTheme.colorScheme.error else com.ambient.tv.ui.theme.LocalPlaybackAccent.current.text)
        Text(if (indicator.remote) "TV · $label" else label, style = MaterialTheme.typography.labelSmall,
            color = Color.White, maxLines = 1)
    }
}

@Composable
internal fun PlaybackStatusSection(indicator: PlaybackIndicator?) {
    LocalCompanionConnection.current?.let { name ->
        Text(stringResource(R.string.companion_connected, name), style = MaterialTheme.typography.bodySmall)
    }
    indicator ?: return
    val frame = LocalPlaybackTimelines.current.firstOrNull { (remote, frame) ->
        remote == indicator.remote && samePlaybackMedia(frame.media, indicator.media)
    }?.second ?: return
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(stringResource(R.string.playback_status_on_device, playbackPhaseLabel(indicator.phase), indicator.device),
            color = if (indicator.phase == "error") MaterialTheme.colorScheme.error else com.ambient.tv.ui.theme.LocalPlaybackAccent.current.text, style = MaterialTheme.typography.bodyMedium)
        frame.subtitle.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2) }
        if (frame.isLive) Text("LIVE", style = MaterialTheme.typography.labelMedium)
        else if (frame.duration > 0) {
            Text("${playbackTime(frame.position)} / ${playbackTime(frame.duration)}", style = MaterialTheme.typography.bodySmall)
            androidx.compose.material3.LinearProgressIndicator(progress = { (frame.position.toFloat() / frame.duration).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(3.dp), color = com.ambient.tv.ui.theme.LocalPlaybackAccent.current.indicator,
                trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = .15f))
        }
    }
}

internal fun playbackTime(ms: Long): String = com.ambient.tv.ui.common.formatPlaybackClock(ms)

/** A playing item can also be explicitly started on another device without disconnecting. */
@Composable
internal fun CompanionChooseDeviceAction(onPlay: () -> Unit) {
    if (LocalCompanionConnection.current != null) {
        com.ambient.tv.ui.common.TvActionButton(stringResource(R.string.companion_choose_device), onPlay)
    }
}
