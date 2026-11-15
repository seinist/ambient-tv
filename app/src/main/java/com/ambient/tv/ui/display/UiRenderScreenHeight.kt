package com.ambient.tv.ui.display

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Use render pixels with render density, so resolution never changes logical dialog sizing. */
@Composable
internal fun uiRenderScreenHeight(): Dp = resolveUiRenderScreenHeight(
    viewport = LocalUiRenderEnvironment.current.viewport,
    screenHeightPixels = LocalContext.current.resources.displayMetrics.heightPixels,
    density = LocalDensity.current,
    fallbackHeightDp = LocalConfiguration.current.screenHeightDp,
)

internal fun resolveUiRenderScreenHeight(
    viewport: UiRenderViewport?,
    screenHeightPixels: Int,
    density: Density,
    fallbackHeightDp: Int,
): Dp = with(density) { (viewport?.renderHeight ?: screenHeightPixels).toDp() }
    .takeIf { it > 0.dp } ?: fallbackHeightDp.dp
