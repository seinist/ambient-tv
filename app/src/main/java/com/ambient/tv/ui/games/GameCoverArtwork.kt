package com.ambient.tv.ui.games

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import com.ambient.tv.ui.common.ArtworkFallback
import com.ambient.tv.ui.common.ArtworkImage

/** The caller reserves the cover's geometry before loading. Neither success nor fallback changes it. */
@Composable
fun GameCoverArtwork(
    cover: Any?,
    title: String,
    systemId: String,
    modifier: Modifier = Modifier,
    focused: Boolean = false,
) {
    ArtworkImage(
        model = cover,
        title = title,
        modifier = modifier,
        contentScale = ContentScale.Fit,
        staggerUncached = true,
        prioritizeUncached = focused,
        fallbackContent = {
            // Same card face as every other library's missing artwork (gradient + title);
            // the system emblem replaces nothing, it only adds which console the game is for.
            ArtworkFallback(
                title = title,
                icon = painterResource(GameSystemIcons.drawable(systemId)),
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
