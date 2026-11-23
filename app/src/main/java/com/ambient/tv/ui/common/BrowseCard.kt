package com.ambient.tv.ui.common

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import com.ambient.tv.ui.theme.LocalUiTokens
import com.ambient.tv.ui.theme.animatedFocusSurface

/** The SmartTube browse card: one flat card holds the artwork and its labels. */
internal val BrowseCardShape = RoundedCornerShape(10.dp)

/** Artwork inside a [browseCardSurface], inset by the card's padding. */
internal val BrowseCardArtworkShape = RoundedCornerShape(7.dp)

/**
 * The card's fill and outline: a quiet translucent surface at rest; focus fills it with a
 * light accent and the accent outline. Shared by YouTube and the game library, so both
 * kinds of tiles read as the same card.
 */
@Composable
internal fun Modifier.browseCardSurface(focused: Boolean, shape: Shape = BrowseCardShape): Modifier =
    animatedFocusSurface(
        focused = focused, shape = shape,
        focusedBackground = MaterialTheme.colorScheme.primary.copy(alpha = .12f),
        unfocusedBackground = MaterialTheme.colorScheme.surface.copy(alpha = .58f),
        focusedBorder = MaterialTheme.colorScheme.primary,
        unfocusedBorder = Color.White.copy(alpha = .04f),
        borderWidth = LocalUiTokens.current.focusBorder,
    )
