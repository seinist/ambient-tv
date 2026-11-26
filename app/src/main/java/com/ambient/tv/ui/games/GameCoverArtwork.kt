package com.ambient.tv.ui.games

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.ambient.tv.games.metadata.GameArtworkPriority
import com.ambient.tv.games.metadata.SharedGameArtworkPriority
import com.ambient.tv.ui.common.ArtworkFallback
import com.ambient.tv.ui.common.ArtworkImage
import com.ambient.tv.ui.common.isActiveNavigationDestination

internal val LocalGameArtworkPriority = staticCompositionLocalOf<GameArtworkPriority> { SharedGameArtworkPriority }

/** The caller reserves the cover's geometry before loading. Neither success nor fallback changes it. */
@Composable
fun GameCoverArtwork(
    cover: Any?,
    title: String,
    systemId: String,
    modifier: Modifier = Modifier,
    focused: Boolean = false,
    /** Browse cards supply their indexed ID. A detail's standalone artwork needs no queue demand. */
    fileId: String? = null,
    systemIcon: Int? = null,
) {
    val priority = LocalGameArtworkPriority.current
    val owner = remember(fileId) { Any() }
    var inViewport by remember(fileId) { mutableStateOf(false) }
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val active = isActiveNavigationDestination() && lifecycleState.isAtLeast(Lifecycle.State.STARTED)
    val visible = inViewport && active
    DisposableEffect(priority, owner) {
        onDispose { priority.remove(owner) }
    }
    SideEffect {
        if (fileId != null) priority.update(owner, fileId, visible, focused)
    }
    val artworkModifier = if (fileId == null) modifier else modifier.onGloballyPositioned { coordinates ->
        // boundsInWindow clips through nested lazy viewports. Composed/prefetched neighbours
        // outside a horizontal row or the outer vertical list do not acquire priority.
        val bounds = coordinates.takeIf { it.isAttached }?.boundsInWindow()
        inViewport = bounds != null && bounds.width > 0f && bounds.height > 0f
    }
    ArtworkImage(
        model = cover,
        title = title,
        modifier = artworkModifier,
        contentScale = ContentScale.Fit,
        staggerUncached = true,
        prioritizeUncached = focused,
        loadUncached = fileId == null || visible,
        fallbackContent = {
            // Same card face as every other library's missing artwork (gradient + title);
            // the system emblem replaces nothing, it only adds which console the game is for.
            ArtworkFallback(
                title = title,
                icon = painterResource(systemIcon ?: GameSystemIcons.drawable(systemId)),
                iconModifier = Modifier.testTag("game-cover-fallback"),
            )
        },
    )
}

/** Limit the available stage, never the source ratio. Even unusually wide/tall art stays complete. */
internal fun gameCoverFrameSize(width: Dp, height: Dp, aspectRatio: Float): DpSize {
    val ratio = aspectRatio.takeIf { it.isFinite() && it > 0f } ?: 1f
    val fittedWidth = minOf(width, height * ratio)
    return DpSize(fittedWidth, fittedWidth / ratio)
}
