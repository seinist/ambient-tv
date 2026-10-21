package com.ambient.tv.ui.player

import android.app.Activity
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.zIndex
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.media3.exoplayer.ExoPlayer
import androidx.tv.material3.Text
import com.ambient.tv.R
import com.ambient.tv.ui.player.backend.MpvPlaybackSnapshot
import com.ambient.tv.ui.player.backend.VlcPlaybackSnapshot
import com.ambient.tv.ui.player.backend.VlcRuntimeStats

/** One labeled value in the live Stats-for-Nerds overlay. */
data class NerdStatsOverlayRow(
    val label: String,
    val value: String,
    val warn: Boolean = false,
)

/** Compact sectioned model shared across Media3 / MPV / VLC overlay presentation. */
data class NerdStatsOverlayModel(
    val video: List<NerdStatsOverlayRow> = emptyList(),
    val audio: List<NerdStatsOverlayRow> = emptyList(),
    val system: List<NerdStatsOverlayRow> = emptyList(),
) {
    val isEmpty: Boolean get() = video.isEmpty() && audio.isEmpty() && system.isEmpty()
}

/** Localized field labels so builders stay unit-testable without Android resources. */
data class NerdStatsOverlayLabels(
    val video: String = "Video",
    val audio: String = "Audio",
    val videoDecoder: String = "Video decoder",
    val audioDecoder: String = "Audio decoder",
    val videoBitrate: String = "Video bitrate",
    val audioBitrate: String = "Audio bitrate",
    val surface: String = "Surface",
    val renderedFrames: String = "Rendered frames",
    val droppedFrames: String = "Dropped frames",
    val skippedFrames: String = "Skipped frames",
    val lostFrames: String = "Lost frames",
    val displayedFrames: String = "Displayed frames",
    val decoderDrops: String = "Decoder drops",
    val fps: String = "FPS",
    val hardwareDecode: String = "Hardware decode",
    val audioOutput: String = "Audio output",
    val bitstream: String = "Bitstream",
    val active: String = "Active",
    val audioUnderruns: String = "Audio underruns",
    val lostAudioBuffers: String = "Lost audio buffers",
    val playedAudioBuffers: String = "Played audio buffers",
    val ao: String = "AO",
    val vo: String = "VO",
    val avSync: String = "A/V sync",
    val backend: String = "Backend",
    val bufferHealth: String = "Buffer health",
    val networkThroughput: String = "Network throughput",
    val cacheSpeed: String = "Cache speed",
    val displayRefresh: String = "Display refresh",
    val inputRead: String = "Input read",
    val modePassthrough: String = "Passthrough",
    val modeDtsCore: String = "DTS core",
    val modeLocalAc3: String = "Local AC3",
    val modePcm: String = "PCM",
    val videoFilters: String = "Video filters",
    val deinterlacer: String = "Deinterlacer",
    val upscaler: String = "Upscaler",
    val frameInterpolation: String = "Frame interpolation",
    val cadence: String = "Display cadence",
    val doubleRate: String = "Double frame rate",
    val pulldown: String = "3:2 pulldown",
)

@Composable
fun rememberNerdStatsOverlayLabels(): NerdStatsOverlayLabels {
    val context = LocalContext.current
    return remember(context) { nerdStatsOverlayLabels(context) }
}

internal fun nerdStatsOverlayLabels(context: Context): NerdStatsOverlayLabels = NerdStatsOverlayLabels(
    video = context.getString(R.string.player_nerd_section_video),
    audio = context.getString(R.string.player_nerd_section_audio),
    videoDecoder = context.getString(R.string.player_nerd_video_decoder),
    audioDecoder = context.getString(R.string.player_nerd_audio_decoder),
    videoBitrate = context.getString(R.string.player_nerd_video_bitrate),
    audioBitrate = context.getString(R.string.player_nerd_audio_bitrate),
    surface = context.getString(R.string.player_nerd_surface),
    renderedFrames = context.getString(R.string.player_nerd_rendered_frames),
    droppedFrames = context.getString(R.string.player_nerd_dropped_frames),
    skippedFrames = context.getString(R.string.player_nerd_skipped_frames),
    lostFrames = context.getString(R.string.player_nerd_lost_frames),
    displayedFrames = context.getString(R.string.player_nerd_displayed_frames),
    decoderDrops = context.getString(R.string.player_nerd_decoder_drops),
    fps = context.getString(R.string.player_nerd_fps),
    hardwareDecode = context.getString(R.string.player_nerd_hardware_decode),
    audioOutput = context.getString(R.string.player_info_audio_output),
    bitstream = context.getString(R.string.player_nerd_bitstream),
    active = context.getString(R.string.player_nerd_bitstream_active),
    audioUnderruns = context.getString(R.string.player_nerd_audio_underruns),
    lostAudioBuffers = context.getString(R.string.player_nerd_lost_audio_buffers),
    playedAudioBuffers = context.getString(R.string.player_nerd_played_audio_buffers),
    ao = context.getString(R.string.player_nerd_ao),
    vo = context.getString(R.string.player_nerd_vo),
    avSync = context.getString(R.string.player_nerd_av_sync),
    backend = context.getString(R.string.player_nerd_backend),
    bufferHealth = context.getString(R.string.player_nerd_buffer_health),
    networkThroughput = context.getString(R.string.player_nerd_network_throughput),
    cacheSpeed = context.getString(R.string.player_nerd_cache_speed),
    displayRefresh = context.getString(R.string.player_nerd_display_refresh),
    inputRead = context.getString(R.string.player_nerd_input_read),
    modePassthrough = context.getString(R.string.diag_mode_passthrough),
    modeDtsCore = context.getString(R.string.diag_mode_dts_core),
    modeLocalAc3 = context.getString(R.string.diag_mode_local_ac3),
    modePcm = context.getString(R.string.diag_mode_pcm),
    videoFilters = context.getString(R.string.player_nerd_video_filters),
    deinterlacer = context.getString(R.string.player_nerd_deinterlacer),
    upscaler = context.getString(R.string.player_nerd_upscaler),
    frameInterpolation = context.getString(R.string.player_nerd_frame_interpolation),
    cadence = context.getString(R.string.player_cadence),
    doubleRate = context.getString(R.string.player_cadence_double),
    pulldown = context.getString(R.string.player_cadence_pulldown),
)

internal fun displayRefreshHz(context: Context): Double? =
    (context as? Activity)
        ?.let(ContextCompat::getDisplayOrDefault)
        ?.refreshRate
        ?.takeIf { it > 0f }
        ?.toDouble()


internal fun MutableList<NerdStatsOverlayRow>.addAppliedVideoRows(
    labels: NerdStatsOverlayLabels,
    applied: AppliedVideoProcessing,
) {
    add(NerdStatsOverlayRow(labels.videoFilters, appliedStatValue(applied.videoFilters)))
    add(NerdStatsOverlayRow(labels.deinterlacer, appliedStatValue(applied.deinterlacer)))
    add(NerdStatsOverlayRow(labels.upscaler, appliedStatValue(applied.upscaler)))
    add(NerdStatsOverlayRow(labels.frameInterpolation, appliedStatValue(applied.frameInterpolation)))
}

internal fun audioOutputModeLabel(labels: NerdStatsOverlayLabels, mode: AudioOutputMode?): String? = when (mode) {
    null -> null
    AudioOutputMode.PASSTHROUGH -> labels.modePassthrough
    AudioOutputMode.DTS_CORE -> labels.modeDtsCore
    AudioOutputMode.LOCAL_AC3 -> labels.modeLocalAc3
    AudioOutputMode.PCM -> labels.modePcm
}

/**
 * Pure builder for Media3 overlay rows. Kept free of Compose/resources so unit tests can
 * cover warn coloring and field selection without a player or Robolectric.
 */
internal fun buildMedia3NerdOverlayModel(
    stats: Media3NerdStats,
    labels: NerdStatsOverlayLabels = NerdStatsOverlayLabels(),
    videoSummary: String? = null,
    videoBitrateBitsPerSecond: Long? = null,
    audioSummary: String? = null,
    audioBitrateBitsPerSecond: Long? = null,
    audioOutputMode: AudioOutputMode? = null,
    displayRefreshHz: Double? = null,
    cadenceFps: Double? = null,
    applied: AppliedVideoProcessing = media3AppliedProcessing(
        effectsActive = false,
        scaler = null,
        brightness = 0,
        contrast = 0,
        saturation = 0,
        outputWidth = 0,
        outputHeight = 0,
    ),
): NerdStatsOverlayModel {
    val video = buildList {
        videoSummary?.takeIf { it.isNotBlank() }?.let {
            add(NerdStatsOverlayRow(labels.video, it))
        }
        stats.videoDecoderName?.let {
            add(NerdStatsOverlayRow(labels.videoDecoder, it))
        }
        formatNerdBitrate(videoBitrateBitsPerSecond)?.let {
            add(NerdStatsOverlayRow(labels.videoBitrate, it))
        }
        stats.surfaceSize?.let {
            add(NerdStatsOverlayRow(labels.surface, it))
        }
        stats.renderedVideoFrames?.let {
            add(NerdStatsOverlayRow(labels.renderedFrames, it.toString()))
        }
        stats.droppedVideoFrames?.let { drops ->
            add(NerdStatsOverlayRow(labels.droppedFrames, drops.toString(), warn = drops > 0))
        }
        stats.skippedVideoFrames?.takeIf { it > 0 }?.let {
            add(NerdStatsOverlayRow(labels.skippedFrames, it.toString(), warn = true))
        }
        addAppliedVideoRows(labels, applied)
    }
    val audio = buildList {
        audioSummary?.takeIf { it.isNotBlank() }?.let {
            add(NerdStatsOverlayRow(labels.audio, it))
        }
        stats.audioDecoderName?.let {
            add(NerdStatsOverlayRow(labels.audioDecoder, it))
        }
        formatNerdBitrate(audioBitrateBitsPerSecond)?.let {
            add(NerdStatsOverlayRow(labels.audioBitrate, it))
        }
        audioOutputModeLabel(labels, audioOutputMode)?.let {
            add(NerdStatsOverlayRow(labels.audioOutput, it))
        }
        if (audioOutputMode != null && audioOutputMode != AudioOutputMode.PCM) {
            add(NerdStatsOverlayRow(labels.bitstream, labels.active))
        }
        stats.audioUnderruns.takeIf { it > 0 }?.let {
            add(NerdStatsOverlayRow(labels.audioUnderruns, it.toString(), warn = true))
        }
    }
    val system = buildList {
        add(NerdStatsOverlayRow(labels.backend, "Media3"))
        formatNerdDurationMs(stats.bufferDurationMs)?.let {
            add(NerdStatsOverlayRow(labels.bufferHealth, it))
        }
        formatNerdBitrate(stats.bandwidthEstimateBitsPerSecond)?.let {
            add(NerdStatsOverlayRow(labels.networkThroughput, it))
        }
        playbackCadenceLabel(cadenceFps, displayRefreshHz, labels.doubleRate, labels.pulldown)?.let {
            add(NerdStatsOverlayRow(labels.cadence, it))
        }
        formatNerdHz(displayRefreshHz)?.let {
            add(NerdStatsOverlayRow(labels.displayRefresh, it))
        }
    }
    return NerdStatsOverlayModel(video = video, audio = audio, system = system)
}

internal fun buildMpvNerdOverlayModel(
    snapshot: MpvPlaybackSnapshot,
    labels: NerdStatsOverlayLabels = NerdStatsOverlayLabels(),
    audioOutputMode: AudioOutputMode? = null,
    backendLabel: String = "MPV",
    displayRefreshHz: Double? = snapshot.displayFps,
): NerdStatsOverlayModel {
    val video = buildList {
        // The video line carries the source rate; the FPS row what leaves the filters (SVP).
        listOfNotNull(
            snapshot.videoSize,
            formatNerdFps(snapshot.containerFps ?: snapshot.fps.takeIf { it > 0.0 }),
            snapshot.videoCodec?.uppercase(),
        ).joinToString(" · ").takeIf { it.isNotBlank() }?.let {
            add(NerdStatsOverlayRow(labels.video, it))
        }
        snapshot.decoder?.let {
            add(NerdStatsOverlayRow(labels.hardwareDecode, it))
        }
        formatNerdBitrate(snapshot.videoBitrateBitsPerSecond)?.let {
            add(NerdStatsOverlayRow(labels.videoBitrate, it))
        }
        formatNerdFpsChange(snapshot.containerFps, snapshot.fps.takeIf { it > 0.0 })?.let {
            add(NerdStatsOverlayRow(labels.fps, it))
        }
        snapshot.frameDropCount?.let { drops ->
            add(NerdStatsOverlayRow(labels.droppedFrames, drops.toString(), warn = drops > 0L))
        }
        snapshot.decoderFrameDropCount?.takeIf { it > 0L }?.let {
            add(NerdStatsOverlayRow(labels.decoderDrops, it.toString(), warn = true))
        }
        addAppliedVideoRows(labels, mpvAppliedProcessing(snapshot))
    }
    val audio = buildList {
        snapshot.audioCodec?.takeIf { it.isNotBlank() }?.let {
            add(NerdStatsOverlayRow(labels.audio, it.uppercase()))
        }
        formatNerdBitrate(snapshot.audioBitrateBitsPerSecond)?.let {
            add(NerdStatsOverlayRow(labels.audioBitrate, it))
        }
        snapshot.audioOutputFormat?.takeIf { it.isNotBlank() }?.let {
            add(NerdStatsOverlayRow(labels.ao, it))
        }
        audioOutputModeLabel(labels, audioOutputMode)?.let {
            add(NerdStatsOverlayRow(labels.audioOutput, it))
        }
        if (audioOutputMode != null && audioOutputMode != AudioOutputMode.PCM) {
            add(NerdStatsOverlayRow(labels.bitstream, labels.active))
        }
        formatNerdSeconds(snapshot.avSyncSeconds)?.let { sync ->
            val warn = snapshot.avSyncSeconds?.let { kotlin.math.abs(it) > 0.125 } == true
            add(NerdStatsOverlayRow(labels.avSync, sync, warn = warn))
        }
    }
    val system = buildList {
        add(NerdStatsOverlayRow(labels.backend, backendLabel))
        snapshot.videoOutput?.let {
            add(NerdStatsOverlayRow(labels.vo, it))
        }
        snapshot.accelerationFallback?.let { add(NerdStatsOverlayRow("Fallback", it, warn = true)) }
        snapshot.shieldSoftwareStatus?.let { add(NerdStatsOverlayRow("Decoder", it, warn = true)) }
        formatNerdSeconds(snapshot.cacheDurationSeconds)?.let {
            add(NerdStatsOverlayRow(labels.bufferHealth, it))
        }
        formatNerdByteRate(snapshot.cacheSpeedBytesPerSecond)?.let {
            add(NerdStatsOverlayRow(labels.cacheSpeed, it))
        }
        playbackCadenceLabel(playbackOverlayFps(snapshot.fps, snapshot.containerFps).takeIf { snapshot.svpStatus == null }, displayRefreshHz, labels.doubleRate, labels.pulldown)?.let {
            add(NerdStatsOverlayRow(labels.cadence, it))
        }
        formatNerdHz(displayRefreshHz)?.let {
            add(NerdStatsOverlayRow(labels.displayRefresh, it))
        }
    }
    return NerdStatsOverlayModel(video = video, audio = audio, system = system)
}

internal fun buildVlcNerdOverlayModel(
    snapshot: VlcPlaybackSnapshot,
    labels: NerdStatsOverlayLabels = NerdStatsOverlayLabels(),
    audioCodec: String? = null,
    audioOutputMode: AudioOutputMode? = null,
    displayRefreshHz: Double? = null,
): NerdStatsOverlayModel {
    val stats: VlcRuntimeStats? = snapshot.runtimeStats
    val video = buildList {
        if (snapshot.hasVideo) {
            listOfNotNull(
                stats?.let { s ->
                    if (s.videoWidth != null && s.videoHeight != null) "${s.videoWidth}×${s.videoHeight}" else null
                },
                formatNerdFps(stats?.videoFps),
                stats?.videoCodec?.takeIf { it.isNotBlank() }?.uppercase(),
            ).joinToString(" · ").takeIf { it.isNotBlank() }?.let {
                add(NerdStatsOverlayRow(labels.video, it))
            }
            formatNerdBitrate(stats?.videoBitrateBitsPerSecond)?.let {
                add(NerdStatsOverlayRow(labels.videoBitrate, it))
            }
            formatNerdFps(stats?.videoFps)?.let {
                add(NerdStatsOverlayRow(labels.fps, it))
            }
            stats?.displayedPictures?.let {
                add(NerdStatsOverlayRow(labels.displayedFrames, it.toString()))
            }
            stats?.lostPictures?.let { lost ->
                add(NerdStatsOverlayRow(labels.lostFrames, lost.toString(), warn = lost > 0))
            }
        }
        addAppliedVideoRows(labels, vlcAppliedProcessing(snapshot))
    }
    val audio = buildList {
        (stats?.audioCodec ?: audioCodec)?.takeIf { it.isNotBlank() }?.let {
            add(NerdStatsOverlayRow(labels.audio, it.uppercase()))
        }
        formatNerdBitrate(stats?.audioBitrateBitsPerSecond)?.let {
            add(NerdStatsOverlayRow(labels.audioBitrate, it))
        }
        audioOutputModeLabel(labels, audioOutputMode)?.let {
            add(NerdStatsOverlayRow(labels.audioOutput, it))
        }
        if (audioOutputMode != null && audioOutputMode != AudioOutputMode.PCM) {
            add(NerdStatsOverlayRow(labels.bitstream, labels.active))
        }
        stats?.lostAudioBuffers?.takeIf { it > 0 }?.let {
            add(NerdStatsOverlayRow(labels.lostAudioBuffers, it.toString(), warn = true))
        }
        stats?.playedAudioBuffers?.let {
            add(NerdStatsOverlayRow(labels.playedAudioBuffers, it.toString()))
        }
    }
    val system = buildList {
        add(NerdStatsOverlayRow(labels.backend, "VLC"))
        snapshot.videoOutput?.let { add(NerdStatsOverlayRow(labels.vo, it)) }
        snapshot.accelerationFallback?.let { add(NerdStatsOverlayRow("Fallback", it, warn = true)) }
        playbackCadenceLabel(stats?.videoFps.takeIf { snapshot.hasVideo && snapshot.svpStatus == null }, displayRefreshHz, labels.doubleRate, labels.pulldown)?.let {
            add(NerdStatsOverlayRow(labels.cadence, it))
        }
        formatNerdBytes(stats?.readBytes)?.let {
            add(NerdStatsOverlayRow(labels.inputRead, it))
        }
        formatNerdHz(displayRefreshHz)?.let {
            add(NerdStatsOverlayRow(labels.displayRefresh, it))
        }
    }
    return NerdStatsOverlayModel(video = video, audio = audio, system = system)
}

@Composable
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
fun rememberMedia3NerdOverlayModel(
    player: ExoPlayer,
    stats: Media3NerdStats,
    enabled: Boolean,
    audioOutputMode: AudioOutputMode? = null,
): NerdStatsOverlayModel? {
    if (!enabled) return null
    val context = LocalContext.current
    val labels = rememberNerdStatsOverlayLabels()
    val videoFormat = player.videoFormat
    val audioFormat = player.audioFormat
    val videoSummary = videoFormat?.let { format ->
        listOfNotNull(
            if (format.width > 0 && format.height > 0) "${format.width}×${format.height}" else null,
            format.frameRate.takeIf { it > 0f }?.let { formatNerdFps(it.toDouble()) },
            format.sampleMimeType?.substringAfter('/')?.uppercase(),
        ).joinToString(" · ").takeIf { it.isNotBlank() }
    }
    val audioSummary = audioFormat?.let { format ->
        listOfNotNull(
            format.sampleMimeType?.substringAfter('/')?.uppercase(),
            format.channelCount.takeIf { it > 0 }?.let { "${it}ch" },
            format.sampleRate.takeIf { it > 0 }?.let { "%.1f kHz".format(java.util.Locale.US, it / 1000.0) },
        ).joinToString(" · ").takeIf { it.isNotBlank() }
    }
    val refresh = rememberDisplayRefreshHz()
    val applied = Media3ImageProcessing.peekApplied(player)
    return remember(
        stats,
        videoSummary,
        audioSummary,
        videoFormat?.bitrate,
        audioFormat?.bitrate,
        audioOutputMode,
        refresh,
        labels,
        applied,
    ) {
        buildMedia3NerdOverlayModel(
            stats = stats,
            labels = labels,
            videoSummary = videoSummary,
            videoBitrateBitsPerSecond = videoFormat?.bitrate?.takeIf { it > 0 }?.toLong(),
            audioSummary = audioSummary,
            audioBitrateBitsPerSecond = audioFormat?.bitrate?.takeIf { it > 0 }?.toLong(),
            audioOutputMode = audioOutputMode,
            displayRefreshHz = refresh,
            cadenceFps = videoFormat?.frameRate?.toDouble(),
            applied = applied,
        )
    }
}

@Composable
fun rememberMpvNerdOverlayModel(
    snapshot: MpvPlaybackSnapshot,
    enabled: Boolean,
    audioOutputMode: AudioOutputMode? = null,
    backendLabel: String = "MPV",
): NerdStatsOverlayModel? {
    if (!enabled) return null
    val labels = rememberNerdStatsOverlayLabels()
    val refresh = rememberDisplayRefreshHz() ?: snapshot.displayFps
    return remember(snapshot, audioOutputMode, backendLabel, labels, refresh) {
        buildMpvNerdOverlayModel(snapshot, labels, audioOutputMode, backendLabel, refresh)
    }
}

@Composable
fun rememberVlcNerdOverlayModel(
    snapshot: VlcPlaybackSnapshot,
    enabled: Boolean,
    audioCodec: String? = null,
    audioOutputMode: AudioOutputMode? = null,
): NerdStatsOverlayModel? {
    if (!enabled) return null
    val context = LocalContext.current
    val labels = rememberNerdStatsOverlayLabels()
    val refresh = rememberDisplayRefreshHz()
    return remember(snapshot, audioCodec, audioOutputMode, refresh, labels) {
        buildVlcNerdOverlayModel(snapshot, labels, audioCodec, audioOutputMode, refresh)
    }
}

/**
 * Kodi-like live Stats-for-Nerds overlay. Non-focusable and non-interactive so OSD / panels
 * keep D-pad and pointer ownership. Stays visible while [enabled] even if settings panels are open
 *. [NERD_STATS_OVERLAY_Z_INDEX] keeps it above the OSD scrim; the settings panel uses a
 * higher z-index so the panel is not covered by these stats.
 */
/** Above the video and OSD scrim, below [PLAYER_SETTINGS_PANEL_Z_INDEX]. */
internal const val NERD_STATS_OVERLAY_Z_INDEX = 20f

/** Settings / side panel, above [NERD_STATS_OVERLAY_Z_INDEX]. */
internal const val PLAYER_SETTINGS_PANEL_Z_INDEX = 30f

@Composable
fun BoxScope.PlaybackNerdStatsOverlay(
    enabled: Boolean,
    model: NerdStatsOverlayModel?,
    modifier: Modifier = Modifier,
    alignment: Alignment = Alignment.TopStart,
    audioConversion: String? = null,
) {
    if (!enabled || model == null || model.isEmpty) return
    Column(
        modifier = modifier
            .align(alignment)
            .zIndex(NERD_STATS_OVERLAY_Z_INDEX)
            .padding(start = 16.dp, top = 16.dp, end = 16.dp)
            .widthIn(min = 220.dp, max = 340.dp)
            .focusProperties { canFocus = false }
            .focusable(false)
            .clearAndSetSemantics { }
            .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(8.dp))
            .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = stringResource(R.string.player_stats_for_nerds),
            color = Color.White.copy(alpha = 0.92f),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace,
        )
        NerdOverlaySection(stringResource(R.string.player_nerd_section_video), model.video)
        NerdOverlaySection(stringResource(R.string.player_nerd_section_audio), model.audio + listOfNotNull(
            audioConversion?.let { NerdStatsOverlayRow(stringResource(R.string.audio_conversion_location), it) },
        ))
        NerdOverlaySection(stringResource(R.string.player_nerd_section_system), model.system)
    }
}

@Composable
private fun NerdOverlaySection(title: String, rows: List<NerdStatsOverlayRow>) {
    if (rows.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = title.uppercase(),
            color = Color(0xFF7EC8FF),
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(top = 2.dp),
        )
        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = row.label,
                    color = Color.White.copy(alpha = 0.70f),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                )
                Text(
                    text = row.value,
                    color = if (row.warn) Color(0xFFFF8A80) else Color.White.copy(alpha = 0.95f),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = if (row.warn) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
