package com.ambient.tv.branding

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import com.ambient.tv.R

/**
 * Local SVG branding for About, setup, empty states and navigation identity.
 * SVG currentColor follows the user accent; white lettering and dark TV text
 * retain the approved design's contrast. PNG exports are not used in the app UI.
 */
@Composable
fun BrandMark(
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    tint: Color = MaterialTheme.colorScheme.primary,
    contentDescription: String? = stringResource(R.string.app_name),
) {
    Image(
        painter = rememberBrandSvgPainter("ambient-tv-icon.svg", tint),
        contentDescription = contentDescription,
        modifier = modifier.size(size),
        alpha = tint.alpha,
        contentScale = ContentScale.Fit,
    )
}

@Composable
fun BrandWordmark(
    modifier: Modifier = Modifier,
    height: Dp = 22.dp,
    tint: Color = MaterialTheme.colorScheme.primary,
    contentDescription: String? = stringResource(R.string.app_name),
) {
    Image(
        painter = rememberBrandSvgPainter("ambient-tv-wordmark.svg", tint),
        contentDescription = contentDescription,
        modifier = modifier.height(height).width(height * 4.75f),
        alpha = tint.alpha,
        contentScale = ContentScale.Fit,
    )
}

@Composable
fun BrandCombo(
    modifier: Modifier = Modifier,
    height: Dp = 40.dp,
    tint: Color = MaterialTheme.colorScheme.primary,
    contentDescription: String? = stringResource(R.string.app_name),
) {
    Image(
        painter = rememberBrandSvgPainter("ambient-tv-combo.svg", tint),
        contentDescription = contentDescription,
        modifier = modifier.height(height).width(height * 4.8f),
        alpha = tint.alpha,
        contentScale = ContentScale.Fit,
    )
}
