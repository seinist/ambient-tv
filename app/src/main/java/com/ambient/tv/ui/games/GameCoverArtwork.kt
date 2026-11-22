package com.ambient.tv.ui.games

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.tv.material3.MaterialTheme
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
            // ArtworkImage's own Box aligns custom content at TopStart. Fill that Box first;
            // otherwise the smaller vector ends up in the top-left quarter of the cover.
            BoxWithConstraints(
                Modifier.fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .24f)),
                contentAlignment = Alignment.Center,
            ) {
                val size = minOf(maxWidth, maxHeight) * .56f
                androidx.compose.material3.Icon(
                    painterResource(GameSystemIcons.drawable(systemId)),
                    contentDescription = null,
                    modifier = Modifier.size(size).testTag("game-cover-fallback"),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}

/** Limit the available stage, never the source ratio. Even unusually wide/tall art stays complete. */
internal fun gameCoverFrameSize(width: Dp, height: Dp, aspectRatio: Float): DpSize {
    val ratio = aspectRatio.takeIf { it.isFinite() && it > 0f } ?: 1f
    val fittedWidth = minOf(width, height * ratio)
    return DpSize(fittedWidth, fittedWidth / ratio)
}
