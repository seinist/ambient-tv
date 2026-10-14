package com.ambient.tv.ui.theme

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import com.ambient.tv.ui.common.TvBringIntoViewSpec


val LocalAccentModeKey = staticCompositionLocalOf { "gold" }

enum class AppThemeMode(val key: String) {
    DARK("dark"), OLED("oled"), DIM("dim");

    companion object {
        fun fromKey(key: String): AppThemeMode = entries.firstOrNull { it.key == key } ?: DARK
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AmbientTvTheme(
    uiScale: Float = 1f,
    accentColorKey: String = "gold",
    customAccentHex: String = "#E5A00D",
    themeModeKey: String = "dark",
    complementaryPlayback: Boolean = false,
    customPlaybackAccentHex: String? = null,
    content: @Composable () -> Unit,
) {
    val handheld = com.ambient.tv.ui.mobile.LocalHandheldUi.current
    val accent = accentColorFor(accentColorKey, customAccentHex)
    val mode = AppThemeMode.fromKey(themeModeKey)
    val background = when (mode) {
        AppThemeMode.DARK -> AppBackground
        AppThemeMode.OLED -> Color.Black
        AppThemeMode.DIM -> Color(0xFF111318)
    }
    val surface = when (mode) {
        AppThemeMode.DARK -> AppSurface
        AppThemeMode.OLED -> Color(0xFF090909)
        AppThemeMode.DIM -> Color(0xFF1A1D22)
    }
    val raised = when (mode) {
        AppThemeMode.DARK -> AppSurfaceRaised
        AppThemeMode.OLED -> Color(0xFF151515)
        AppThemeMode.DIM -> Color(0xFF252930)
    }

    val usableAccent = accessibleAccent(accent, surface)
    val onAccent = contentOnAccent(usableAccent)
    val playback = if (complementaryPlayback) {
        playbackAccentColorFor(accentColorKey, customPlaybackAccentHex, customAccentHex)
    } else usableAccent
    // Design colors stay sRGB 0–1. While the HDR window is active, scale them into
    // extended sRGB so text, surfaces and gradients sit above SDR white. Black stays black.
    val headroom = com.ambient.tv.ui.display.LocalUiHdrState.current?.headroom ?: 1f
    fun Color.lift() = com.ambient.tv.ui.display.uiHdrHighlight(this, headroom)
    val tvTypography = if (handheld) androidx.tv.material3.Typography(
        displaySmall = androidx.compose.ui.text.TextStyle(fontSize = 28.sp, lineHeight = 34.sp),
        headlineLarge = androidx.compose.ui.text.TextStyle(fontSize = 28.sp, lineHeight = 34.sp),
        headlineMedium = androidx.compose.ui.text.TextStyle(fontSize = 24.sp, lineHeight = 30.sp),
        headlineSmall = androidx.compose.ui.text.TextStyle(fontSize = 22.sp, lineHeight = 28.sp),
        bodyLarge = androidx.compose.ui.text.TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
        bodySmall = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
        labelSmall = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
        labelMedium = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, lineHeight = 18.sp),
    ) else androidx.tv.material3.Typography(
        bodySmall = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
        labelSmall = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
        labelMedium = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, lineHeight = 18.sp),
    )
    val tvScheme = darkColorScheme(
        primary = usableAccent.lift(),
        onPrimary = onAccent.lift(),
        primaryContainer = usableAccent.lift(),
        onPrimaryContainer = onAccent.lift(),
        secondary = usableAccent.lift(),
        onSecondary = onAccent.lift(),
        background = background.lift(),
        surface = surface.lift(),
        surfaceVariant = raised.lift(),
        onSurface = AppOnSurface.lift(),
        onSurfaceVariant = AppOnSurfaceMuted.lift(),
    )
    val materialScheme = androidx.compose.material3.darkColorScheme(
        primary = usableAccent.lift(),
        onPrimary = onAccent.lift(),
        primaryContainer = usableAccent.lift(),
        onPrimaryContainer = onAccent.lift(),
        secondary = usableAccent.lift(),
        onSecondary = onAccent.lift(),
        background = background.lift(),
        surface = surface.lift(),
        surfaceVariant = raised.lift(),
        onSurface = AppOnSurface.lift(),
        onSurfaceVariant = AppOnSurfaceMuted.lift(),
        outline = AppOutline.lift(),
    )

    val baseDensity = LocalDensity.current
    val effectiveScale = com.ambient.tv.data.local.UiScalePolicy.normalize(uiScale)
    val scaledDensity = remember(baseDensity.density, baseDensity.fontScale, effectiveScale) {
        Density(density = baseDensity.density * effectiveScale, fontScale = baseDensity.fontScale)
    }
    val tokens = remember(effectiveScale, handheld) {
        if (handheld) UiTokens(screenPadding = 16.dp, sectionGap = 16.dp, itemGap = 10.dp) else UiTokens(
            screenPadding = 32.dp,
            sectionGap = if (effectiveScale < 0.8f) 20.dp else 24.dp,
            itemGap = if (effectiveScale < 0.8f) 10.dp else 12.dp,
        )
    }

    val bringIntoViewSpec = if (handheld) LocalBringIntoViewSpec.current else TvBringIntoViewSpec
    CompositionLocalProvider(
        com.ambient.tv.ui.common.LocalScrollListActions provides remember { com.ambient.tv.ui.common.ScrollListActions() },
        LocalDensity provides scaledDensity,
        LocalUiTokens provides tokens,
        LocalAccentModeKey provides accentColorKey,
        LocalPlaybackAccent provides PlaybackAccent(
            indicator = playback.lift(),
            text = accessibleAccent(playback, surface).lift(),
        ),
        LocalBringIntoViewSpec provides bringIntoViewSpec,
    ) {
        androidx.compose.material3.MaterialTheme(colorScheme = materialScheme) {
            MaterialTheme(colorScheme = tvScheme, typography = tvTypography, content = content)
        }
    }
}
