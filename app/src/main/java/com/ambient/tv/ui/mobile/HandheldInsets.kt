package com.ambient.tv.ui.mobile

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection

/** Physical screen insets in pixels. Independent of layout direction. */
internal data class EdgeInsets(
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0,
) {
    fun union(other: EdgeInsets): EdgeInsets = EdgeInsets(
        left = maxOf(left, other.left),
        top = maxOf(top, other.top),
        right = maxOf(right, other.right),
        bottom = maxOf(bottom, other.bottom),
    )
}

/**
 * Safe content padding for a handheld window.
 *
 * Portrait uses the full safe-drawing box: status bar and cutout are on the top, navigation
 * and the IME on the bottom. Landscape is different. Android moves the portrait top status bar
 * and its display cutout onto a short edge, and padding for that inset letterboxes a dead
 * column down the side of the player (and every other handheld screen). Those horizontal
 * status/cutout insets are dropped so video and pages stay edge-to-edge. A navigation bar or
 * the IME that actually occupies an edge is kept, and a status bar or cutout that stayed on
 * the long edge (top/bottom) is still cleared.
 */
internal fun handheldContentEdgeInsets(
    landscape: Boolean,
    status: EdgeInsets,
    cutout: EdgeInsets,
    navigation: EdgeInsets,
    ime: EdgeInsets,
): EdgeInsets {
    if (!landscape) return status.union(cutout).union(navigation).union(ime)
    return EdgeInsets(
        left = maxOf(navigation.left, ime.left),
        top = maxOf(status.top, cutout.top, navigation.top, ime.top),
        right = maxOf(navigation.right, ime.right),
        bottom = maxOf(status.bottom, cutout.bottom, navigation.bottom, ime.bottom),
    )
}

@Composable
internal fun handheldContentWindowInsets(): WindowInsets {
    if (!isHandheldLandscape()) return WindowInsets.safeDrawing
    // statusBars/displayCutout/navigationBars/ime are @Composable. Capture them here; the
    // layout reads the values later, which is what union() does as well.
    return HandheldLandscapeContentWindowInsets(
        status = WindowInsets.statusBars,
        cutout = WindowInsets.displayCutout,
        navigation = WindowInsets.navigationBars,
        imeInsets = WindowInsets.ime,
    )
}

/**
 * Delegating (instead of subtracting the side cutout from [WindowInsets.safeDrawing]) keeps a
 * side navigation bar even when it shares an edge with the cutout.
 */
private class HandheldLandscapeContentWindowInsets(
    private val status: WindowInsets,
    private val cutout: WindowInsets,
    private val navigation: WindowInsets,
    private val imeInsets: WindowInsets,
) : WindowInsets {
    override fun getLeft(density: Density, layoutDirection: LayoutDirection): Int =
        resolved(density, layoutDirection).left

    override fun getTop(density: Density): Int =
        resolved(density, LayoutDirection.Ltr).top

    override fun getRight(density: Density, layoutDirection: LayoutDirection): Int =
        resolved(density, layoutDirection).right

    override fun getBottom(density: Density): Int =
        resolved(density, LayoutDirection.Ltr).bottom

    private fun resolved(density: Density, layoutDirection: LayoutDirection): EdgeInsets =
        handheldContentEdgeInsets(
            landscape = true,
            status = status.edges(density, layoutDirection),
            cutout = cutout.edges(density, layoutDirection),
            navigation = navigation.edges(density, layoutDirection),
            ime = imeInsets.edges(density, layoutDirection),
        )
}

private fun WindowInsets.edges(density: Density, layoutDirection: LayoutDirection): EdgeInsets =
    EdgeInsets(
        left = getLeft(density, layoutDirection),
        top = getTop(density),
        right = getRight(density, layoutDirection),
        bottom = getBottom(density),
    )
