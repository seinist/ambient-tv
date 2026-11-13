package com.ambient.tv.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.ambient.tv.R
import com.ambient.tv.data.local.AppPreferences

/**
 * How much of the settings is shown, in Settings and in the player panel alike (as in Kodi).
 * A hidden setting keeps its value and keeps working; search and direct shortcuts still reach
 * every page. Declaration order is the order of growing detail.
 */
enum class SettingsLevel(val key: String, val labelRes: Int) {
    SIMPLE("simple", R.string.settings_level_simple),
    ADVANCED("advanced", R.string.settings_level_advanced),
    EXPERT("expert", R.string.settings_level_expert);

    /** True when something assigned to [required] is shown at this level. */
    fun shows(required: SettingsLevel): Boolean = ordinal >= required.ordinal

    companion object {
        fun fromKey(key: String?): SettingsLevel = entries.firstOrNull { it.key == key } ?: EXPERT
    }
}

/** One observable level for every open settings surface. */
object SettingsLevels {
    private var chosen by mutableStateOf<SettingsLevel?>(null)

    fun current(preferences: AppPreferences): SettingsLevel =
        chosen ?: SettingsLevel.fromKey(preferences.settingsLevelKey)

    fun set(preferences: AppPreferences, level: SettingsLevel) {
        preferences.settingsLevelKey = level.key
        chosen = level
    }
}

@Composable
fun rememberSettingsLevel(): SettingsLevel {
    val context = LocalContext.current
    val preferences = remember(context) { com.ambient.tv.ui.player.videoImagePreferences(context) }
    return SettingsLevels.current(preferences)
}

/** Areas of the player's video panel: what a level shows. */
internal fun videoSectionLevel(key: String): SettingsLevel = when (key) {
    "scaling", "motion" -> SettingsLevel.SIMPLE
    "renderer", "filters", "color" -> SettingsLevel.ADVANCED
    else -> SettingsLevel.EXPERT
}
