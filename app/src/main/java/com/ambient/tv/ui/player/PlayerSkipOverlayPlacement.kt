package com.ambient.tv.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * One skip action is drawn by SegmentSkipOverlay, never copied into the OSD. The OSD
 * reserves a measured context slot and reports its bounds; the same focused button
 * moves between this slot and a bottom-right floating position.
 *
 * Both positions are relative to the player's own viewport, not the device window.
 * This works with TV display scaling, handset insets and the OSD's stow animation.
 */
internal fun skipOverlayPosition(
    viewport: IntSize,
    action: IntSize,
    viewportOrigin: Offset,
    contextBoundsInRoot: Rect?,
    contextVisible: Boolean,
    marginPx: Int,
): IntOffset {
    val margin = marginPx.coerceAtLeast(0)
    val width = viewport.width.coerceAtLeast(0)
    val height = viewport.height.coerceAtLeast(0)
    val actionWidth = action.width.coerceAtLeast(0)
    val actionHeight = action.height.coerceAtLeast(0)
    val leftInset = margin.takeIf { actionWidth + margin * 2 <= width } ?: 0
    val topInset = margin.takeIf { actionHeight + margin * 2 <= height } ?: 0
    val maxX = (width - actionWidth - leftInset).coerceAtLeast(leftInset)
    val maxY = (height - actionHeight - topInset).coerceAtLeast(topInset)
    val floating = IntOffset(maxX, maxY)
    val anchor = contextBoundsInRoot?.takeIf {
        contextVisible && it.width > 0f && it.height > 0f
    } ?: return floating

    // Right-align inside the OSD's own content width; center vertically in its slot.
    val targetX = (anchor.right - viewportOrigin.x - actionWidth).roundToInt()
    val targetY = (anchor.center.y - viewportOrigin.y - actionHeight / 2f).roundToInt()
    return IntOffset(targetX.coerceIn(leftInset, maxX), targetY.coerceIn(topInset, maxY))
}

/**
 * Real layout space *between the reference header and seekbar*, only while a skip action
 * and the OSD are visible. The blank slot is the button's destination: painting remains
 * in SegmentSkipOverlay, above the OSD scrim, so there is exactly one clickable action.
 */
@Composable
internal fun PlayerSkipContextAnchor() {
    val navigation = LocalPlaybackNavigation.current ?: return
    val osd = LocalPlayerChromeState.current?.osd
    val active = navigation.skipActionVisible && navigation.chromeVisible &&
        !navigation.skipOverlayBlocked && osd?.show != false
    val density = LocalDensity.current
    val measuredHeight = navigation.skipActionSize.height
    val targetHeight = with(density) {
        (measuredHeight.takeIf { it > 0 } ?: 48.dp.roundToPx()).toDp()
    } + 8.dp
    val slotHeight by animateDpAsState(targetHeight, animationSpec = tween(190), label = "Skip OSD slot height")

    LaunchedEffect(active) {
        if (!active) navigation.skipContextBoundsInRoot = null
    }
    DisposableEffect(navigation) {
        onDispose { navigation.skipContextBoundsInRoot = null }
    }
    AnimatedVisibility(
        visible = active,
        enter = expandVertically(animationSpec = tween(210)) + fadeIn(tween(160)),
        exit = shrinkVertically(animationSpec = tween(190)) + fadeOut(tween(130)),
    ) {
        Column {
            Spacer(Modifier.height(8.dp))
            Spacer(
                Modifier.fillMaxWidth().height(slotHeight)
                    .onGloballyPositioned { coordinates ->
                        if (active) {
                            val bounds = coordinates.boundsInRoot()
                            if (navigation.skipContextBoundsInRoot != bounds)
                                navigation.skipContextBoundsInRoot = bounds
                        }
                    },
            )
        }
    }
}
