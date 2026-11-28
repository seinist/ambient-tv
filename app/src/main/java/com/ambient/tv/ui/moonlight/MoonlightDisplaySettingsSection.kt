package com.ambient.tv.ui.moonlight

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.ambient.tv.R
import com.ambient.tv.moonlight.MoonlightStreamProfile
import com.ambient.tv.ui.common.PlayerDropUpSelector
import com.ambient.tv.ui.common.SettingsHint
import com.ambient.tv.ui.mobile.isTelevision
import com.ambient.tv.ui.player.DisplayRefreshRateMatcher
import com.ambient.tv.ui.player.findActivity
import com.ambient.tv.ui.player.rememberAvailableDisplayModes
import com.ambient.tv.ui.player.videoImagePreferences
import com.ambient.tv.ui.settings.SettingsGroup
import com.ambient.tv.ui.settings.SettingsSubheader

@Composable
internal fun MoonlightDisplaySettingsSection(profile: MoonlightStreamProfile, update: (MoonlightStreamProfile) -> Unit) {
    val context = LocalContext.current
    val activity = context.findActivity()
    val handheld = !isTelevision(context)
    val preferences = remember(context) { videoImagePreferences(context) }
    val modes = rememberAvailableDisplayModes(activity ?: context)
    val original = DisplayRefreshRateMatcher.currentMode(activity)
    val available = original?.let { moonlightDisplayModes(modes, it, handheld, preferences.displayModeWhitelist) }.orEmpty()
    if (available.size <= 1 && !moonlightDisplayRequested(profile)) return
    val fixed = MoonlightStreamProfile.isFixedDisplayMode(profile.displayOutputMode)
    val off = stringResource(R.string.moonlight_display_off)
    val on = stringResource(R.string.moonlight_display_on)
    val rateOptions = listOf("off" to off, "on" to on)
    val rateKey = if (profile.matchDisplayRefresh) "on" else "off"
    val automatic = listOf(
        MoonlightStreamProfile.DISPLAY_SYSTEM to stringResource(R.string.moonlight_display_system),
        MoonlightStreamProfile.DISPLAY_STREAM to stringResource(R.string.moonlight_display_stream),
        MoonlightStreamProfile.DISPLAY_FULL to stringResource(R.string.moonlight_display_full),
        MoonlightStreamProfile.DISPLAY_PERFORMANCE to stringResource(R.string.moonlight_display_performance),
    )
    val choices = (if (handheld) automatic.take(1) else automatic) + available.map { it.key to it.label }
    val missing = choices.none { it.first == profile.displayOutputMode }
    val options = if (missing) choices + (profile.displayOutputMode to stringResource(R.string.moonlight_display_unavailable)) else choices
    SettingsSubheader(stringResource(R.string.moonlight_display_title))
    SettingsGroup(card = true) {
        PlayerDropUpSelector(
            label = stringResource(R.string.moonlight_display_refresh),
            value = if (profile.matchDisplayRefresh) on else off,
            options = rateOptions, selectedKey = rateKey, dropUp = false,
            selectorKey = "moonlight-display-refresh",
            disabledKeys = if (fixed) rateOptions.map { it.first }.toSet() else emptySet(),
            onSelected = { if (!fixed) update(profile.copy(matchDisplayRefresh = it == "on")) },
        )
        PlayerDropUpSelector(
            label = stringResource(R.string.moonlight_display_output),
            value = options.first { it.first == profile.displayOutputMode }.second,
            options = options, selectedKey = profile.displayOutputMode, dropUp = false,
            selectorKey = "moonlight-display-output",
            disabledKeys = if (missing) setOf(profile.displayOutputMode) else emptySet(),
            onSelected = { key -> if (choices.any { it.first == key }) update(profile.copy(displayOutputMode = key)) },
        )
        if (profile.matchDisplayRefresh && !fixed) {
            PlayerDropUpSelector(
                label = stringResource(R.string.moonlight_display_double),
                value = if (profile.preferDoubleDisplayRefresh) on else off,
                options = rateOptions, selectedKey = if (profile.preferDoubleDisplayRefresh) "on" else "off",
                dropUp = false, selectorKey = "moonlight-display-double",
                onSelected = { update(profile.copy(preferDoubleDisplayRefresh = it == "on")) },
            )
        }
        SettingsHint(stringResource(R.string.moonlight_display_hint))
    }
}
