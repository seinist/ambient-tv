package com.ambient.tv.ui.tvheadend

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.ambient.tv.R
import com.ambient.tv.tvheadend.domain.TvhChannel
import com.ambient.tv.tvheadend.domain.TvhEpgEvent
import com.ambient.tv.tvheadend.domain.TvhPlaybackSource
import com.ambient.tv.ui.common.MetaBadge
import com.ambient.tv.ui.player.PlayerTechBadgeRow
import com.ambient.tv.ui.common.PlayerControlBar
import com.ambient.tv.ui.common.PlayerExitButton
import com.ambient.tv.ui.common.PlayerIconButton
import com.ambient.tv.ui.common.PlayerSleepTimerButton
import com.ambient.tv.ui.player.ReferencePlaybackSummary
import com.ambient.tv.ui.player.ReferencePlayerBottomContent
import com.ambient.tv.ui.player.ReferencePlayerClock
import com.ambient.tv.ui.player.ReferencePlayerHeader
import com.ambient.tv.ui.player.ReferencePlayerOverlayBackground
import com.ambient.tv.ui.player.TvPlayerSeekBar

/** TVHeadend OSD rendered with the same chrome hierarchy and controls as the Plex/Jellyfin player. */
@Composable
fun BoxScope.TvhReferencePlayerChrome(
    source: TvhPlaybackSource,
    channel: TvhChannel?,
    imageHeaders: Map<String, String>,
    artworkUrl: String?,
    artworkFallbackUrls: List<String>,
    nowEvent: TvhEpgEvent?,
    nextEvent: TvhEpgEvent?,
    positionMs: Long,
    durationMs: Long,
    playing: Boolean,
    loading: Boolean,
    backendLabel: String,
    mediaBadges: List<String>,
    seekJumpMs: Long,
    /** Own slot: [mediaBadges] keeps only four extras, which would drop an SVP chip. */
    svpBadge: String? = null,
    /** Own slot as well: "Passthrough", "DTS-HD 7.1 → DTS 5.1", "→ PCM". */
    audioBadge: String? = null,
    seekFocusRequester: FocusRequester,
    playFocusRequester: FocusRequester,
    onTogglePlayback: () -> Unit,
    onSeekBy: (Long) -> Unit,
    onZap: (Int) -> Unit,
    onStop: () -> Unit,
    onBack: () -> Unit,
    onOpenAudio: () -> Unit,
    onOpenSubtitles: () -> Unit,
    onOpenRenderer: () -> Unit,
    /** Audio-only (radio): replaces the video button with the MilkDrop panel. */
    onOpenVisualizer: (() -> Unit)? = null,
    onOpenSleep: () -> Unit,
    onOpenSettings: () -> Unit,
    audioSelected: Boolean = false,
    subtitlesSelected: Boolean = false,
    rendererSelected: Boolean = false,
    visualizerSelected: Boolean = false,
    sleepSelected: Boolean = false,
    settingsSelected: Boolean = false,
) {
    val nowSeconds = System.currentTimeMillis() / 1000L
    val liveEventDurationMs = nowEvent?.durationSeconds?.times(1000L) ?: 0L
    val liveEventPositionMs = nowEvent?.let {
        ((nowSeconds - it.startEpochSeconds).coerceAtLeast(0L) * 1000L)
    } ?: 0L
    val title = nowEvent?.title ?: source.title
    val subtitle = nowEvent?.subtitle?.takeIf { it.isNotBlank() }
        ?: source.subtitle?.takeIf { it.isNotBlank() }
    val contextLine = buildList {
        channel?.name?.takeIf { it.isNotBlank() }?.let(::add)
        if (source.isLive) add(stringResource(if (com.ambient.tv.livetv.LiveTimeshift.current?.recordingId != null) R.string.live_recording_playback else R.string.tvh_live).uppercase())
        source.streamProfile?.takeIf { it.isNotBlank() }?.let(::add)
    }.joinToString("  •  ")
    val resolvedArtwork = artworkUrl ?: nowEvent?.imageUrl ?: channel?.iconUrl
    val fallbacks = (artworkFallbackUrls + listOfNotNull(nowEvent?.imageUrl, channel?.iconUrl))
        .filter { it.isNotBlank() && it != resolvedArtwork }
        .distinct()

    ReferencePlayerOverlayBackground()
    ReferencePlayerClock(
        remainingMs = if (!source.isLive && durationMs > 0L) (durationMs - positionMs).coerceAtLeast(0L) else null,
        liveLabel = if (source.isLive) stringResource(if (com.ambient.tv.livetv.LiveTimeshift.current?.recordingId != null) R.string.live_recording_playback else R.string.tvh_live).uppercase() else null,
        modifier = Modifier.align(Alignment.TopEnd).padding(top = 34.dp, end = 48.dp),
    )
    ReferencePlayerBottomContent {
        ReferencePlayerHeader(
            // Keep the header geometry while programme artwork and subtitles load after a zap.
            modifier = if (source.isLive)
                Modifier.heightIn(min = com.ambient.tv.ui.mobile.deviceDp(104.dp, 56.dp))
            else Modifier,
            title = title,
            artworkUrl = resolvedArtwork,
            artworkFallbackUrls = fallbacks,
            artworkHeaders = imageHeaders,
            contextLine = contextLine,
            subtitle = subtitle,
            artworkAspectRatio = 16f / 9f,
            artworkContentScale = if (resolvedArtwork == channel?.iconUrl && nowEvent?.imageUrl.isNullOrBlank()) ContentScale.Fit else ContentScale.Crop,
        )

        // The same controls serve local Timeshift and the growing server recording.
        val timeshiftAnchor = com.ambient.tv.livetv.LiveTimeshift.anchor.collectAsState().value
        val timeshiftSeek = com.ambient.tv.livetv.LiveTimeshift.seekHandler
        val shiftable = source.isLive && timeshiftAnchor != null && timeshiftSeek != null
        val changingChannel = com.ambient.tv.ui.player.LocalPlayerChangingItem.current
        val hadTimeshift = remember { mutableStateOf(shiftable) }
        SideEffect { if (!changingChannel) hadTimeshift.value = shiftable }
        // Restarting the buffer clears its anchor. Retain the timeline during that handoff,
        // but only enable seeking once the new buffer is available.
        val showTimeshift = shiftable || (source.isLive && changingChannel && hadTimeshift.value)
        // A player that reports stream timestamps instead of time since start would put the picture
        // in the future: the position is bounded by the time since the stream was opened.
        val sinceAnchor = (System.currentTimeMillis() - (timeshiftAnchor ?: 0L)).coerceAtLeast(0L)
        val watchedWallMs = (timeshiftAnchor ?: 0L) + positionMs.coerceIn(0L, sinceAnchor)
        val seekBuffer = com.ambient.tv.livetv.LiveTimeshift.current
        val recordingPlayback = seekBuffer?.recordingId != null
        val behindMs = if (shiftable) ((seekBuffer?.liveWallMs ?: System.currentTimeMillis()) - watchedWallMs).coerceAtLeast(0L) else 0L
        val shifted = behindMs > TIMESHIFT_LIVE_TOLERANCE_MS
        if (source.isLive) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (nowEvent != null && liveEventDurationMs > 0L)
                        "${formatTvhChromeClock(nowEvent.startEpochSeconds)} – ${formatTvhChromeClock(nowEvent.stopEpochSeconds)}"
                    else "",
                    color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.82f),
                    fontSize = 13.sp,
                )
                // EPG gives the broadcast end; delayed playback finishes later by its live offset.
                if (nowEvent != null && nowEvent.stopEpochSeconds > nowSeconds) {
                    Text(
                        stringResource(
                            R.string.player_ends_at,
                            formatTvhChromeClock(nowEvent.stopEpochSeconds + behindMs / 1000L),
                        ),
                        color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.64f),
                        fontSize = 13.sp,
                    )
                }
            }
            if (!showTimeshift) LinearProgressIndicator(
                progress = {
                    if (liveEventDurationMs > 0L)
                        (liveEventPositionMs.toFloat() / liveEventDurationMs.toFloat()).coerceIn(0f, 1f)
                    else 0f
                },
                color = com.ambient.tv.ui.theme.LocalPlaybackAccent.current.indicator,
                modifier = Modifier.fillMaxWidth(),
            )
            // EPG is fetched separately from the stream. Empty text retains the line height
            // without briefly showing programme information from the previous channel.
            Text(
                nextEvent?.let {
                    stringResource(R.string.tvh_next_program, formatTvhChromeClock(it.startEpochSeconds), it.title)
                } ?: "",
                color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.62f),
                fontSize = 12.sp,
                maxLines = 1,
            )
        } else if (!source.isLive && durationMs > 0L) {
            ReferencePlaybackSummary(positionMs = positionMs, durationMs = durationMs)
            TvPlayerSeekBar(
                positionMs = positionMs,
                durationMs = durationMs,
                onSeekBy = onSeekBy,
                onInteraction = {},
                onTogglePlayback = onTogglePlayback,
                onPlay = { if (!playing) onTogglePlayback() },
                modifier = Modifier.focusRequester(seekFocusRequester),
            )
        }

        if (showTimeshift) {
            val buffer = com.ambient.tv.livetv.LiveTimeshift.current
            val oldest = buffer?.oldestWallMs ?: watchedWallMs
            val edge = maxOf(oldest, buffer?.liveWallMs ?: watchedWallMs)
            TvPlayerSeekBar(
                positionMs = watchedWallMs.coerceIn(oldest, edge) - oldest,
                durationMs = (edge - oldest).coerceAtLeast(1L),
                onSeekBy = { delta -> if (shiftable) seekLiveTimeshiftBy(positionMs, delta) },
                onInteraction = {},
                onTogglePlayback = onTogglePlayback,
                onPlay = { if (!playing) onTogglePlayback() },
                modifier = Modifier.focusRequester(seekFocusRequester),
                timelineContext = { previewPosition ->
                    if (nowEvent != null && liveEventDurationMs > 0L) {
                        val previewWallMs = oldest + previewPosition
                        val programmeMs = programmePositionMs(previewWallMs, nowEvent.startEpochSeconds, nowEvent.stopEpochSeconds)
                        val beforeProgramme = previewWallMs < nowEvent.startEpochSeconds * 1000L
                        Text(
                            if (beforeProgramme) stringResource(R.string.timeshift_before_programme, formatTvhChromeClock(previewWallMs / 1000L))
                            else stringResource(R.string.timeshift_programme_position,
                                formatTvhChromeDuration(programmeMs),
                                formatTvhChromeDuration(liveEventDurationMs),
                                formatTvhChromeClock(previewWallMs / 1000L)),
                            color = androidx.tv.material3.MaterialTheme.colorScheme.primary,
                            fontSize = 12.sp,
                        )
                        LinearProgressIndicator(
                            progress = { (programmeMs.toDouble() / liveEventDurationMs).toFloat().coerceIn(0f, 1f) },
                            color = androidx.tv.material3.MaterialTheme.colorScheme.primary,
                            modifier = Modifier.fillMaxWidth().height(3.dp),
                        )
                    } else {
                        Text("", fontSize = 12.sp)
                        LinearProgressIndicator(
                            progress = { 0f },
                            color = androidx.tv.material3.MaterialTheme.colorScheme.primary,
                            modifier = Modifier.fillMaxWidth().height(3.dp),
                        )
                    }
                },

            )
        }

        PlayerTechBadgeRow {
            MetaBadge(backendLabel, emphasized = true)
            if (source.isLive && recordingPlayback) MetaBadge(stringResource(R.string.live_recording_playback), emphasized = true)
            if (source.isLive && shifted) MetaBadge(stringResource(R.string.timeshift_behind, formatTvhChromeDuration(behindMs)), emphasized = true)
            else if (source.isLive && !recordingPlayback) MetaBadge(stringResource(R.string.tvh_live).uppercase(), emphasized = true)
            svpBadge?.let { MetaBadge(it, emphasized = true) }
            com.ambient.tv.ui.player.PlayerStreamBadges(mediaBadges)
            audioBadge?.let { MetaBadge(it) }
        }

        PlayerControlBar(
            leftContent = {
                val seekJumpSeconds = (seekJumpMs / 1000L).toInt().coerceAtLeast(1)
                if (source.isLive) {
                    PlayerIconButton(Icons.Filled.SkipPrevious, stringResource(R.string.tvh_previous_channel), { onZap(-1) })
                    if (shiftable) PlayerIconButton(Icons.Filled.FastRewind, stringResource(R.string.player_seek_back, seekJumpSeconds), {
                        seekLiveTimeshiftBy(positionMs, -seekJumpMs)
                    })
                } else {
                    PlayerIconButton(Icons.Filled.FastRewind, stringResource(R.string.player_seek_back, seekJumpSeconds), { onSeekBy(-seekJumpMs) })
                }
                PlayerIconButton(
                    if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    stringResource(if (playing) R.string.player_pause else R.string.player_play),
                    onTogglePlayback,
                    modifier = Modifier.focusRequester(playFocusRequester),
                    primary = true,
                    loading = loading,
                )
                PlayerIconButton(Icons.Filled.Stop, stringResource(R.string.player_stop), onStop)
                val recording = LocalLiveRecordingControl.current
                if (source.isLive && recording.available) {
                    PlayerIconButton(
                        if (recording.scheduled) Icons.Filled.Stop else Icons.Filled.FiberManualRecord,
                        stringResource(if (recording.scheduled) R.string.player_record_stop else R.string.tvh_record),
                        recording.record,
                        selected = recording.scheduled,
                        loading = recording.busy,
                    )
                }

                if (source.isLive) {
                    if (shiftable) {
                        PlayerIconButton(Icons.Filled.FastForward, stringResource(R.string.player_seek_forward, seekJumpSeconds), {
                            seekLiveTimeshiftBy(positionMs, seekJumpMs)
                        })
                    }
                    PlayerIconButton(Icons.Filled.SkipNext, stringResource(R.string.tvh_next_channel), { onZap(1) })
                } else {
                    PlayerIconButton(Icons.Filled.FastForward, stringResource(R.string.player_seek_forward, seekJumpSeconds), { onSeekBy(seekJumpMs) })
                }
            },
            rightContent = {
                PlayerExitButton(onBack)
                PlayerIconButton(Icons.AutoMirrored.Filled.VolumeUp, stringResource(R.string.player_audio), onOpenAudio, selected = audioSelected)
                PlayerIconButton(Icons.Filled.Subtitles, stringResource(R.string.player_subtitles), onOpenSubtitles, selected = subtitlesSelected)
                if (onOpenVisualizer != null) {
                    PlayerIconButton(Icons.Filled.GraphicEq, stringResource(R.string.player_visualizer), onOpenVisualizer, selected = visualizerSelected)
                } else {
                    PlayerIconButton(Icons.Filled.Movie, stringResource(R.string.player_video), onOpenRenderer, selected = rendererSelected)
                }
                PlayerSleepTimerButton(onOpenSleep, selected = sleepSelected)
                PlayerIconButton(Icons.Filled.Settings, stringResource(R.string.player_playback_settings), onOpenSettings, selected = settingsSelected)
            },
        )
    }
}

private fun formatTvhChromeClock(epochSeconds: Long): String =
    java.time.Instant.ofEpochSecond(epochSeconds)
        .atZone(java.time.ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))

private fun formatTvhChromeDuration(ms: Long): String = com.ambient.tv.ui.common.formatPlaybackClock(ms)

/**
 * The live-TV and recording OSD of Media3, VLC and MPV: the active engine supplies its values and
 * actions, PersistentPlayerOverlays draws it above the engine. Fields keep each engine's details.
 */
internal data class LiveOsdModel(
    /** Media3 kept its OSD on screen while a panel was open; VLC/MPV hide it with the timeout. */
    val showWithPanel: Boolean,
    val source: TvhPlaybackSource,
    val channel: TvhChannel?,
    val imageHeaders: Map<String, String>,
    val artworkUrl: String?,
    val artworkFallbackUrls: List<String>,
    val nowEvent: TvhEpgEvent?,
    val nextEvent: TvhEpgEvent?,
    val positionMs: Long,
    val durationMs: Long,
    val playing: Boolean,
    val loading: Boolean,
    val backendLabel: String,
    val mediaBadges: List<String>,
    val seekJumpMs: Long,
    /** "SVP 25 → 50" while SVP interpolates; shown outside [mediaBadges]. */
    val svpBadge: String? = null,
    /** Audio output/conversion chip; shown outside [mediaBadges]. */
    val audioBadge: String? = null,
    val onTogglePlayback: () -> Unit,
    val onSeekBy: (Long) -> Unit,
    val onZap: (Int) -> Unit,
    val onStop: () -> Unit,
    val onBack: () -> Unit,
    val onOpenAudio: () -> Unit,
    val onOpenSubtitles: () -> Unit,
    val onOpenRenderer: () -> Unit,
    val onOpenVisualizer: (() -> Unit)? = null,
    val onOpenSleep: () -> Unit,
    val onOpenSettings: () -> Unit,
    val audioSelected: Boolean = false,
    val subtitlesSelected: Boolean = false,
    val rendererSelected: Boolean = false,
    val visualizerSelected: Boolean = false,
    val sleepSelected: Boolean = false,
    val settingsSelected: Boolean = false,
    /** VLC: a short note about a track change, shown at the top of the OSD. */
    val message: String? = null,
) : com.ambient.tv.ui.player.PlayerOsdSpec {
    @Composable
    override fun Content(visible: Boolean, panel: com.ambient.tv.ui.common.UnifiedPlayerPanel) {
        val seekFocusRequester = com.ambient.tv.ui.player.rememberPlayerSeekFocus()
        val playFocusRequester = com.ambient.tv.ui.player.rememberPlayerPlayFocus()
        com.ambient.tv.ui.player.PlayerOverlayVisibility(
            visible = visible || (showWithPanel && panel != com.ambient.tv.ui.common.UnifiedPlayerPanel.NONE),
        ) {
            TvhReferencePlayerChrome(
                source = source,
                channel = channel,
                imageHeaders = imageHeaders,
                artworkUrl = artworkUrl,
                artworkFallbackUrls = artworkFallbackUrls,
                nowEvent = nowEvent,
                nextEvent = nextEvent,
                positionMs = positionMs,
                durationMs = durationMs,
                playing = playing,
                loading = loading,
                backendLabel = backendLabel,
                mediaBadges = mediaBadges,
                seekJumpMs = seekJumpMs,
                svpBadge = svpBadge,
                audioBadge = audioBadge,
                seekFocusRequester = seekFocusRequester,
                playFocusRequester = playFocusRequester,
                onTogglePlayback = onTogglePlayback,
                onSeekBy = onSeekBy,
                onZap = onZap,
                onStop = onStop,
                onBack = onBack,
                onOpenAudio = onOpenAudio,
                onOpenSubtitles = onOpenSubtitles,
                onOpenRenderer = onOpenRenderer,
                onOpenVisualizer = onOpenVisualizer,
                onOpenSleep = onOpenSleep,
                onOpenSettings = onOpenSettings,
                audioSelected = audioSelected,
                subtitlesSelected = subtitlesSelected,
                rendererSelected = rendererSelected,
                visualizerSelected = visualizerSelected,
                sleepSelected = sleepSelected,
                settingsSelected = settingsSelected,
            )
            message?.let {
                Text(
                    it,
                    style = androidx.tv.material3.MaterialTheme.typography.bodySmall,
                    color = androidx.tv.material3.MaterialTheme.colorScheme.primary,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 82.dp),
                )
            }
        }
    }
}
