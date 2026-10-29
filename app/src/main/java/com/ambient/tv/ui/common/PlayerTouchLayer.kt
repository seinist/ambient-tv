@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.ambient.tv.ui.common

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.media3.common.Player
import com.ambient.tv.ui.mobile.LocalHandheldUi
import com.ambient.tv.ui.player.PlayerPinchZoomGesture
import com.ambient.tv.ui.player.VideoPresentation
import com.ambient.tv.ui.player.playerZoomEdges
import com.ambient.tv.ui.player.normalized
import kotlin.math.abs

/** Local UI state and the renderer are updated together, so the video settings stay accurate. */
class PlayerPinchZoom(
    val key: Any,
    val presentation: VideoPresentation,
    val sourceAspectRatio: () -> Float?,
    val onChanged: (VideoPresentation) -> Unit,
    val enabled: Boolean = true,
)

/** Decoder geometry includes non-square pixels; source metadata is only an opening fallback. */
fun Player.pinchVideoAspectRatio(fallback: Float? = null): Float? = videoSize.let { size ->
    if (size.width > 0 && size.height > 0) {
        (size.width * size.pixelWidthHeightRatio / size.height).takeIf { it.isFinite() && it > 0f }
            ?: fallback
    } else fallback
}

/**
 * Touch layer shared by all playback backends.
 *
 * It deliberately sits between the native video surface and the Compose OSD. That means OSD
 * buttons keep receiving their own taps, while taps on otherwise empty video space still work.
 * A single tap toggles the OSD. Double-tapping the left/right thirds seeks when seeking is
 * available; double-tapping the centre toggles play/pause.
 * On handheld video pages, a two-finger pinch scales the picture and snaps to its width/height
 * edges. OSD buttons and panels remain above this layer and keep their own touch handling.
 */
@Composable
fun PlayerTouchLayer(
    onToggleControls: () -> Unit,
    onTogglePlayback: () -> Unit,
    onSeekBack: (() -> Unit)? = null,
    onSeekForward: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    pinchZoom: PlayerPinchZoom? = null,
) {
    val currentToggleControls by rememberUpdatedState(onToggleControls)
    val currentTogglePlayback by rememberUpdatedState(onTogglePlayback)
    val currentSeekBack by rememberUpdatedState(onSeekBack)
    val currentSeekForward by rememberUpdatedState(onSeekForward)
    val currentPinch by rememberUpdatedState(pinchZoom)
    val pinchAvailable = LocalHandheldUi.current && pinchZoom != null
    val pinchEnabled = pinchAvailable && pinchZoom.enabled == true
    var viewport by remember(pinchZoom?.key) { mutableStateOf(IntSize.Zero) }
    var lastPinchPresentation by remember(pinchZoom?.key) { mutableStateOf<VideoPresentation?>(null) }
    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerHoverIcon(PointerIcon.Hand)
            .onSizeChanged { next ->
                // A crop fitted to portrait must not become an extreme crop after rotation or
                // entering split-screen. A later manual choice wins, even on the same player.
                if (pinchAvailable && viewport != IntSize.Zero && viewport != next) {
                    val lastPinch = lastPinchPresentation
                    val current = currentPinch
                    lastPinchPresentation = null
                    if (lastPinch != null && current != null && current.presentation.normalized() == lastPinch) {
                        current.onChanged(VideoPresentation())
                    }
                }
                viewport = next
            }
            .pointerInput(pinchEnabled, pinchZoom?.key, viewport) {
                if (!pinchEnabled) return@pointerInput
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    var multiTouch = false
                    var gesture: PlayerPinchZoomGesture? = null
                    var cumulativeZoom = 1f
                    var pastSlop = false
                    do {
                        // Cancel the tap detector as soon as a second finger joins, even if
                        // neither finger moves. Otherwise releasing a pinch can pause or seek.
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.count { it.pressed } >= 2) {
                            if (!multiTouch) {
                                multiTouch = true
                                val config = currentPinch
                                val edges = playerZoomEdges(size.width, size.height, config?.sourceAspectRatio?.invoke())
                                if (edges != null && config != null) gesture = PlayerPinchZoomGesture(edges, config.presentation)
                            }
                            val factor = event.calculateZoom()
                            if (factor.isFinite() && factor > 0f) {
                                cumulativeZoom *= factor
                                if (!pastSlop) {
                                    val movement = abs(1f - cumulativeZoom) * event.calculateCentroidSize(useCurrent = false)
                                    pastSlop = movement > viewConfiguration.touchSlop
                                }
                                if (pastSlop) {
                                    gesture?.scaleBy(cumulativeZoom)?.let { presentation ->
                                        val normalized = presentation.normalized()
                                        currentPinch?.onChanged?.invoke(normalized)
                                        lastPinchPresentation = normalized
                                    }
                                    cumulativeZoom = 1f
                                }
                            }
                        }
                        if (multiTouch) event.changes.forEach { it.consume() }
                    } while (event.changes.any { it.pressed })
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { currentToggleControls() },
                    onDoubleTap = { position: Offset ->
                        when {
                            position.x < size.width * 0.35f && currentSeekBack != null -> currentSeekBack?.invoke()
                            position.x > size.width * 0.65f && currentSeekForward != null -> currentSeekForward?.invoke()
                            else -> currentTogglePlayback()
                        }
                    },
                )
            },
    )
}
