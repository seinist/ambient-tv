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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt
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
    sourceFps: Double? = null,
    svpStatus: String? = null,
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
    val text = formatPlaybackFpsOverlay(fps, hz, video, display.resolution, cadence, sourceFps, svpStatus) ?: return
    // Publish the window bounds so the dropped-frames popup can stack below instead of overlapping.
    val token = remember { Any() }
    DisposableEffect(token) { onDispose { PlaybackStatsOverlayLayout.clear(token) } }
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
            .onGloballyPositioned { coordinates ->
                val origin = coordinates.positionInWindow()
                PlaybackStatsOverlayLayout.publish(token, IntRect(
                    origin.x.roundToInt(), origin.y.roundToInt(),
                    origin.x.roundToInt() + coordinates.size.width, origin.y.roundToInt() + coordinates.size.height,
                ))
            }
            .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(8.dp))
            .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

/** "23.976 fps · 59.940 Hz" — either part alone is fine; null when both missing. */
internal fun formatPlaybackFpsOverlay(
    fps: Double?, refreshHz: Double?, video: String? = null, display: String? = null,
    cadence: String? = playbackCadenceLabel(fps, refreshHz),
    sourceFps: Double? = null,
    svpStatus: String? = null,
): String? {
    // A source/display ratio alone cannot identify pulldown while SVP is selected.
    val displayCadence = cadence.takeIf { svpStatus == null }
    val fpsText = formatNerdFpsChange(sourceFps, fps)
    val interpolation = svpStatus?.let { "SVP $it" }
    if (video != null || display != null) return listOfNotNull(
        listOfNotNull("Video ${video ?: "–"}", fpsText).joinToString(" · "),
        listOfNotNull("Display ${display ?: "–"}", formatNerdHz(refreshHz), displayCadence).joinToString(" · "),
        interpolation,
    ).joinToString("\n")
    val hzText = formatNerdHz(refreshHz)
    return listOfNotNull(fpsText, hzText, displayCadence, interpolation)
        .joinToString(" · ").takeIf { it.isNotEmpty() }
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

/**
 * Window bounds of the visible FPS overlay. The dropped-frames popup is a separate window
 * anchored at the same corner; it reads these bounds and stacks itself below.
 */
internal object PlaybackStatsOverlayLayout {
    private var owner: Any? = null
    var fpsBounds by mutableStateOf<IntRect?>(null)
        private set

    fun publish(token: Any, bounds: IntRect) {
        owner = token
        if (fpsBounds != bounds) fpsBounds = bounds
    }

    fun clear(token: Any) {
        if (owner !== token) return
        owner = null
        fpsBounds = null
    }
}

/**
 * Top-left offset of the dropped-frames popup: the preferred inset, moved below the FPS
 * overlay when the two would intersect, and always kept inside the window.
 */
internal fun stackedStatsPopupOffset(
    preferred: IntOffset,
    popupSize: IntSize,
    windowSize: IntSize,
    occupied: IntRect?,
    gapPx: Int,
): IntOffset {
    val x = preferred.x.coerceAtMost((windowSize.width - popupSize.width).coerceAtLeast(0))
    var y = preferred.y
    if (occupied != null && occupied.width > 0 && occupied.height > 0) {
        val popup = IntRect(x, y, x + popupSize.width, y + popupSize.height)
        if (popup.overlaps(occupied)) y = occupied.bottom + gapPx
    }
    return IntOffset(x, y.coerceAtMost((windowSize.height - popupSize.height).coerceAtLeast(0)))
}
