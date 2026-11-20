package com.ambient.tv.ui.settings

import com.ambient.tv.ui.theme.AccentStyle
import com.ambient.tv.ui.theme.AccentMotionMode
import com.ambient.tv.ui.common.ViewTransitionStyle

/**
 * SettingsUiState's primary constructor already reaches the JVM's method
 * argument limit. Pack appearance keys in a single draft-only string;
 * AppPreferences and the backup schema persist each key independently.
 *
 * Do not add another constructor field: the data class's copy()/copy$default
 * descriptor would exceed the JVM's 255 parameter-slot limit.
 */
private const val STYLE_SEPARATOR = "~"

internal fun packThemeAndAccentStyle(
    theme: String,
    style: String,
    motion: String = AccentMotionMode.DEFAULT,
    transition: String = ViewTransitionStyle.OFF.key,
): String = listOf(theme.substringBefore(STYLE_SEPARATOR), AccentStyle.fromKey(style).key,
    AccentMotionMode.fromKey(motion).key, ViewTransitionStyle.fromKey(transition).key).joinToString(STYLE_SEPARATOR)

internal val SettingsUiState.accentStyleKey: String
    get() = AccentStyle.fromKey(themeModeKey.substringAfter(STYLE_SEPARATOR, AccentStyle.DEFAULT)
        .substringBefore(STYLE_SEPARATOR)).key

internal val SettingsUiState.accentMotionKey: String
    get() = AccentMotionMode.fromKey(themeModeKey.substringAfter(STYLE_SEPARATOR, "")
        .substringAfter(STYLE_SEPARATOR, AccentMotionMode.DEFAULT).substringBefore(STYLE_SEPARATOR)).key

internal val SettingsUiState.viewTransitionStyleKey: String
    get() = ViewTransitionStyle.fromKey(themeModeKey.split(STYLE_SEPARATOR).getOrNull(3)).key

internal val SettingsUiState.displayThemeModeKey: String
    get() = themeModeKey.substringBefore(STYLE_SEPARATOR)

internal fun SettingsUiState.withAccentStyleKey(style: String): SettingsUiState =
    copy(themeModeKey = packThemeAndAccentStyle(displayThemeModeKey, style, accentMotionKey, viewTransitionStyleKey))

internal fun SettingsUiState.withAccentMotionKey(motion: String): SettingsUiState =
    copy(themeModeKey = packThemeAndAccentStyle(displayThemeModeKey, accentStyleKey, motion, viewTransitionStyleKey))

internal fun SettingsUiState.withThemeModeKey(theme: String): SettingsUiState =
    copy(themeModeKey = packThemeAndAccentStyle(theme, accentStyleKey, accentMotionKey, viewTransitionStyleKey))

internal fun SettingsUiState.withViewTransitionStyleKey(style: String): SettingsUiState =
    copy(themeModeKey = packThemeAndAccentStyle(displayThemeModeKey, accentStyleKey, accentMotionKey, style))

/** Keep independent appearance edits when stored preferences refresh during an open draft. */
internal fun mergeAppearanceDraftKeys(draft: SettingsUiState, baseline: SettingsUiState, stored: SettingsUiState): String {
    if (draft.themeModeKey == baseline.themeModeKey) return stored.themeModeKey
    if (stored.themeModeKey == baseline.themeModeKey) return draft.themeModeKey
    return packThemeAndAccentStyle(
        if (draft.displayThemeModeKey != baseline.displayThemeModeKey) draft.displayThemeModeKey else stored.displayThemeModeKey,
        if (draft.accentStyleKey != baseline.accentStyleKey) draft.accentStyleKey else stored.accentStyleKey,
        if (draft.accentMotionKey != baseline.accentMotionKey) draft.accentMotionKey else stored.accentMotionKey,
        if (draft.viewTransitionStyleKey != baseline.viewTransitionStyleKey) draft.viewTransitionStyleKey else stored.viewTransitionStyleKey,
    )
}
