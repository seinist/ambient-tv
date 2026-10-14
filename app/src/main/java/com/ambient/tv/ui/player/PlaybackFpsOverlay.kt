@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.ambient.tv.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.media3.exoplayer.ExoPlayer
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Compact live FPS + display refresh overlay. Non-focusable so OSD / panels keep input.
 * Hidden while the full Stats-for-Nerds panel is open ([nerdStatsVisible]).
 */
@Composable
fun BoxScope.PlaybackFpsOverlay(
    enabled: Boolean,
    fps: Double?,
    refreshHz: Double?,
    nerdStatsVisible: Boolean = false,
    modifier: Modifier = Modifier,
    alignment: Alignment = Alignment.TopStart,
    cadenceFps: Double? = fps,
) {
    if (!enabled || nerdStatsVisible) return
    val display = rememberPlaybackDisplayOutput()
    val video = rememberPlaybackVideoResolution(enabled)
    val hz = display.refreshHz ?: refreshHz
    val cadence = playbackCadenceLabel(
        cadenceFps, hz,
        androidx.compose.ui.res.stringResource(com.ambient.tv.R.string.player_cadence_double),
        androidx.compose.ui.res.stringResource(com.ambient.tv.R.string.player_cadence_pulldown),
    )
    val text = formatPlaybackFpsOverlay(fps, hz, video, display.resolution, cadence) ?: return
    Text(
        text = text,
        color = Color.White.copy(alpha = 0.92f),
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        fontFamily = FontFamily.Monospace,
        modifier = modifier
            .align(alignment)
            .zIndex(NERD_STATS_OVERLAY_Z_INDEX)
            .padding(start = 16.dp, top = 16.dp, end = 16.dp)
            .focusProperties { canFocus = false }
            .focusable(false)
            .clearAndSetSemantics { }
            .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(8.dp))
            .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

/** "23.976 fps · 59.940 Hz" — either part alone is fine; null when both missing. */
internal fun formatPlaybackFpsOverlay(
    fps: Double?, refreshHz: Double?, video: String? = null, display: String? = null,
    cadence: String? = playbackCadenceLabel(fps, refreshHz),
): String? {
    if (video != null || display != null) return listOf(
        listOfNotNull("Video ${video ?: "–"}", formatNerdFps(fps)).joinToString(" · "),
        listOfNotNull("Display ${display ?: "–"}", formatNerdHz(refreshHz), cadence).joinToString(" · "),
    ).joinToString("\n")
    val fpsText = formatNerdFps(fps)
    val hzText = formatNerdHz(refreshHz)
    return when {
        fpsText != null && hzText != null -> listOfNotNull(fpsText, hzText, cadence).joinToString(" · ")
        fpsText != null -> fpsText
        hzText != null -> hzText
        else -> null
    }
}

/**
 * Measured rendered FPS from Media3 decoder counters, falling back to the container frame rate
 * until enough samples exist.
 */
@Composable
fun rememberMedia3PlaybackFps(player: ExoPlayer, enabled: Boolean): Double? {
    var measured by remember(player) { mutableStateOf<Double?>(null) }
    LaunchedEffect(player, enabled) {
        if (!enabled) {
            measured = null
            return@LaunchedEffect
        }
        val tracker = RateTracker()
        while (isActive) {
            val counters = player.videoDecoderCounters
            counters?.ensureUpdated()
            val rendered = counters?.renderedOutputBufferCount?.toLong()
            val now = android.os.SystemClock.elapsedRealtime()
            val rate = tracker.observe(rendered, now)
            val container = player.videoFormat?.frameRate?.toDouble()?.takeIf { it.isFinite() && it > 0.0 }
            measured = rate?.takeIf { it > 0.5 } ?: container
            delay(500)
        }
    }
    return measured
}

@Composable
fun rememberDisplayRefreshHz(): Double? {
    return rememberPlaybackDisplayOutput().refreshHz
}

/** Prefer live estimated fps, then container fps. */
internal fun playbackOverlayFps(estimated: Double?, container: Double?): Double? =
    estimated?.takeIf { it.isFinite() && it > 0.0 }
        ?: container?.takeIf { it.isFinite() && it > 0.0 }

internal data class PlaybackDisplayOutput(val resolution: String? = null, val refreshHz: Double? = null)

/** Physical active mode, not the logical Android desktop or the preferred mode. */
@Composable
internal fun rememberPlaybackDisplayOutput(): PlaybackDisplayOutput {
    val context = LocalContext.current
    val view = LocalView.current
    fun read(): PlaybackDisplayOutput {
        val display = view.display ?: return PlaybackDisplayOutput()
        val mode = display.mode
        return PlaybackDisplayOutput(formatPlaybackResolution(mode.physicalWidth, mode.physicalHeight),
            display.refreshRate.toDouble().takeIf { it.isFinite() && it > 0 })
    }
    var output by remember(view) { mutableStateOf(read()) }
    DisposableEffect(context, view) {
        val manager = context.getSystemService(DisplayManager::class.java)
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) { output = read() }
            override fun onDisplayRemoved(displayId: Int) { output = read() }
            override fun onDisplayChanged(displayId: Int) { if (view.display?.displayId == displayId) output = read() }
        }
        output = read()
        manager.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
        onDispose { manager.unregisterDisplayListener(listener) }
    }
    return output
}

internal fun formatPlaybackResolution(width: Int?, height: Int?): String? =
    if (width != null && height != null && width > 0 && height > 0) "${width}×${height}" else null

@Composable
private fun rememberPlaybackVideoResolution(enabled: Boolean): String? {
    var resolution by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(enabled) {
        if (!enabled) { resolution = null; return@LaunchedEffect }
        while (isActive) {
            resolution = when (val handle = BackgroundPlaybackSession.activeHandle()) {
                is Media3PlaybackHandle -> handle.player.videoFormat?.let { formatPlaybackResolution(it.width, it.height) }
                is com.ambient.tv.ui.player.backend.MpvPlaybackController -> handle.videoSize()
                is com.ambient.tv.ui.player.backend.VlcPlaybackController -> handle.runtimeStats()?.let { formatPlaybackResolution(it.videoWidth, it.videoHeight) }
                else -> null
            }
            delay(500)
        }
    }
    return resolution
}
