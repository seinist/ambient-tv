package com.ambient.tv.ui.settings

import com.ambient.tv.ui.theme.AccentStyle

/**
 * SettingsUiState's primary constructor already reaches the JVM's method
 * argument limit. Pack two appearance keys in a single draft-only string;
 * AppPreferences and the backup schema persist both keys independently.
 *
 * Do not add another constructor field: the data class's copy()/copy$default
 * descriptor would exceed the JVM's 255 parameter-slot limit.
 */
private const val STYLE_SEPARATOR = "~"

internal fun packThemeAndAccentStyle(theme: String, style: String): String =
    theme.substringBefore(STYLE_SEPARATOR) + STYLE_SEPARATOR + AccentStyle.fromKey(style).key

internal val SettingsUiState.accentStyleKey: String
    get() = AccentStyle.fromKey(themeModeKey.substringAfter(STYLE_SEPARATOR, AccentStyle.DEFAULT)).key

internal val SettingsUiState.displayThemeModeKey: String
    get() = themeModeKey.substringBefore(STYLE_SEPARATOR)

internal fun SettingsUiState.withAccentStyleKey(style: String): SettingsUiState =
    copy(themeModeKey = packThemeAndAccentStyle(displayThemeModeKey, style))

internal fun SettingsUiState.withThemeModeKey(theme: String): SettingsUiState =
    copy(themeModeKey = packThemeAndAccentStyle(theme, accentStyleKey))
