@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.ambient.tv.ui.player

import androidx.media3.ui.AspectRatioFrameLayout
import kotlin.math.abs

/** The two exact, aspect-preserving scales relative to FIT in the actual player viewport. */
internal data class PlayerZoomEdges(val width: Float, val height: Float)

internal fun playerZoomEdges(width: Int, height: Int, sourceAspectRatio: Float?): PlayerZoomEdges? {
    val ratio = sourceAspectRatio?.takeIf { it.isFinite() && it > 0f } ?: return null
    if (width <= 0 || height <= 0) return null
    val viewportRatio = width.toFloat() / height
    return if (ratio >= viewportRatio) PlayerZoomEdges(1f, ratio / viewportRatio)
    else PlayerZoomEdges(viewportRatio / ratio, 1f)
}

/**
 * One gesture owns an unsnapped accumulator. Feeding the displayed, snapped value back into the
 * next movement would trap slow pinches forever at an edge. The wider release band prevents
 * jitter while still letting either finger direction leave a snap deliberately.
 */
internal class PlayerPinchZoomGesture(
    edges: PlayerZoomEdges,
    initial: VideoPresentation,
) {
    private val snapPoints = listOf(edges.width, edges.height)
        .filter { it.isFinite() && it in VIDEO_MIN_ZOOM..VIDEO_MAX_ZOOM }.distinct().sorted()
    private var rawZoom = initial.normalized().let { effective ->
        when {
            effective.aspectRatio != null -> 1f
            effective.resizeMode == AspectRatioFrameLayout.RESIZE_MODE_ZOOM ->
                maxOf(edges.width, edges.height) * effective.zoom
            effective.resizeMode == AspectRatioFrameLayout.RESIZE_MODE_FILL -> 1f
            else -> effective.zoom
        }
    }.coerceIn(VIDEO_MIN_ZOOM, VIDEO_MAX_ZOOM)
    private var snapped = nearestSnap(rawZoom)
    var zoom: Float = snapped ?: rawZoom
        private set

    fun scaleBy(factor: Float): VideoPresentation {
        if (!factor.isFinite() || factor <= 0f) return presentation()
        val previous = rawZoom
        rawZoom = (rawZoom * factor).coerceIn(VIDEO_MIN_ZOOM, VIDEO_MAX_ZOOM)
        val held = snapped?.takeIf { abs(rawZoom / it - 1f) <= snapBand(it, SNAP_RELEASE, 0.45f) }
        val nearby = held ?: nearestSnap(rawZoom)
        val crossed = if (nearby != null) null else snapPoints
            .filter { (previous < it && rawZoom > it) || (previous > it && rawZoom < it) }
            .minByOrNull { abs(previous - it) }
        // A fast gesture can leap across the whole capture band. Start at the reached edge
        // then, so a stationary follow-up event cannot immediately undo the snap.
        if (crossed != null) rawZoom = crossed
        snapped = nearby ?: crossed
        zoom = snapped ?: rawZoom
        return presentation()
    }

    private fun nearestSnap(value: Float): Float? = snapPoints
        .filter { abs(value / it - 1f) <= snapBand(it, SNAP_ENTER, 0.35f) }
        .minByOrNull { abs(value / it - 1f) }

    // Similar video/display aspect ratios produce close edges. Neither snap's release band
    // may cover the other edge, or fitting the second dimension would become impossible.
    private fun snapBand(point: Float, maximum: Float, neighborFraction: Float): Float =
        snapPoints.filter { it != point }.minOfOrNull { abs(it / point - 1f) * neighborFraction }
            ?.coerceAtMost(maximum) ?: maximum

    // Pinching intentionally returns to the source aspect ratio; fixed aspect presets still
    // retain their complete-picture contract until an actual zoom gesture is recognized.
    private fun presentation() = VideoPresentation(zoom = zoom)

    private companion object {
        const val SNAP_ENTER = 0.03f
        const val SNAP_RELEASE = 0.06f
    }
}
