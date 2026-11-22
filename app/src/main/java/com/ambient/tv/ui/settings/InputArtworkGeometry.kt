package com.ambient.tv.ui.settings

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import kotlin.math.min

internal fun defaultArtworkAspectRatio(shape: InputArtworkShape): Float = when (shape) {
    InputArtworkShape.REMOTE -> .4f
    InputArtworkShape.WII_REMOTE -> .3f
    InputArtworkShape.KEYBOARD -> 3.2f
    InputArtworkShape.DUAL_SCREEN -> .95f
    InputArtworkShape.SCREEN_PAD -> 2.05f
    InputArtworkShape.OVAL -> 2.0f
    InputArtworkShape.JOYSTICK -> 1.15f
    InputArtworkShape.HANDHELD -> 1.65f
    InputArtworkShape.PAD, InputArtworkShape.THREE_GRIP -> 1.5f
}

/** One uniform fit for the artwork and every mark on it, independent of screen density. */
internal fun fitInputArtwork(artwork: InputArtwork, availableWidth: Float, maxHeight: Float): Size {
    if (availableWidth <= 0f || maxHeight <= 0f || !availableWidth.isFinite() || !maxHeight.isFinite()) {
        return Size.Zero
    }
    val ratio = artwork.aspectRatio.takeIf { it.isFinite() && it > 0f } ?: 1f
    val width = min(availableWidth, maxHeight * ratio)
    return Size(width, width / ratio)
}

internal fun inputArtworkKeyBounds(entry: InputArtworkKey, size: Size): Rect {
    val width = entry.widthFraction * size.width
    val height = entry.heightFraction * size.width
    return Rect(entry.x * size.width - width / 2f, entry.y * size.height - height / 2f,
        entry.x * size.width + width / 2f, entry.y * size.height + height / 2f)
}

/**
 * Phones use the nearest visible key, with a bounded forgiving radius. Hit slop is resolved
 * once for the whole picture rather than overlapping many independently enlarged targets.
 * The full-size key strip remains an accessible alternative for dense keyboard diagrams.
 */
internal fun inputArtworkKeyAt(artwork: InputArtwork, size: Size, point: Offset, slop: Float): InputArtworkKey? =
    artwork.keys.asSequence().filterNot { it.auxiliary }.map { entry ->
        val bounds = inputArtworkKeyBounds(entry, size)
        entry to bounds
    }.filter { (_, bounds) -> bounds.inflate(slop.coerceAtLeast(0f)).contains(point) }
        .minByOrNull { (_, bounds) -> (bounds.center - point).getDistanceSquared() }?.first

/** Anchors are measured in the upstream SVG's original 64 x 64 coordinate system. */
internal data class ControllerKeyAnchor(val x: Float, val y: Float, val width: Float = 4.5f, val height: Float = width)

internal fun InputArtwork.withControllerVector(
    id: String,
    anchors: Map<String, ControllerKeyAnchor>,
): InputArtwork {
    val asset = ControllerVectorAssets[id] ?: return this
    return copy(
        vectorId = id,
        aspectRatio = asset.aspectRatio,
        keys = keys.map { entry ->
            val anchor = anchors[entry.id]
            if (anchor == null) entry.copy(auxiliary = true)
            else entry.copy(
                x = (anchor.x - asset.left) / asset.width,
                y = (anchor.y - asset.top) / asset.height,
                widthFraction = anchor.width / asset.width,
                heightFraction = anchor.height / asset.width,
                auxiliary = false,
            )
        },
    )
}

internal fun directionAnchors(x: Float, y: Float, distance: Float = 3.5f, size: Float = 3.6f): Map<String, ControllerKeyAnchor> = mapOf(
    "up" to ControllerKeyAnchor(x, y - distance, size),
    "down" to ControllerKeyAnchor(x, y + distance, size),
    "left" to ControllerKeyAnchor(x - distance, y, size),
    "right" to ControllerKeyAnchor(x + distance, y, size),
)
