package com.ambient.tv.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Immutable
data class UiTokens(
    val screenPadding: Dp = 32.dp,
    val sectionGap: Dp = 24.dp,
    val itemGap: Dp = 12.dp,
    val compactGap: Dp = 8.dp,
    val cardRadius: Dp = 12.dp,
    val controlRadius: Dp = 10.dp,
    val panelRadius: Dp = 20.dp,
    val pillRadius: Dp = 999.dp,
    val focusBorder: Dp = 2.dp,
    val focusElevation: Dp = 10.dp,
    val focusScale: Float = 1.025f,
    val subtleFocusScale: Float = 1.012f,
    val animationFastMs: Int = 140,
    val animationNormalMs: Int = 200,
    val heroOverlayStrength: Float = 0.66f,
    val dimSurfaceAlpha: Float = 0.72f,
)

val LocalUiTokens = staticCompositionLocalOf { UiTokens() }

/** Uniform distance from the screen edge to content on every side. */
@androidx.compose.runtime.Composable
@androidx.compose.runtime.ReadOnlyComposable
fun screenGutter(): Dp = LocalUiTokens.current.screenPadding
