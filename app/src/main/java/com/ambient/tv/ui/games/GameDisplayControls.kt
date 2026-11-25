package com.ambient.tv.ui.games

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.ambient.tv.R
import com.ambient.tv.games.GameDisplayProfile
import com.ambient.tv.games.GameDisplayScope
import com.ambient.tv.games.GameSettings
import com.ambient.tv.games.displayProfile
import com.ambient.tv.games.ownDisplayProfile
import com.ambient.tv.games.withDisplayProfile
import com.ambient.tv.ui.common.PlayerDropUpSelector
import com.ambient.tv.ui.common.SettingsHint
import com.ambient.tv.ui.mobile.isTelevision
import com.ambient.tv.ui.player.DisplayRefreshRateMatcher
import com.ambient.tv.ui.player.SupportedDisplayMode
import com.ambient.tv.ui.player.findActivity
import com.ambient.tv.ui.player.rememberAvailableDisplayModes
import com.ambient.tv.ui.player.videoImagePreferences
import java.util.Locale

@Composable
private fun resolutionOptions(modes: List<SupportedDisplayMode>, handheld: Boolean): List<Pair<String, String>> {
    val automatic = listOf(
        GameDisplayProfile.SYSTEM to stringResource(R.string.game_display_system),
        GameDisplayProfile.DISPLAY to stringResource(R.string.game_display_display),
        GameDisplayProfile.GAME to stringResource(R.string.game_display_game),
        GameDisplayProfile.PERFORMANCE to stringResource(R.string.game_display_performance),
    )
    return (if (handheld) automatic.take(1) else automatic) + modes.map { it.key to it.label }
}

@Composable
private fun GameDisplaySelectors(
    profile: GameDisplayProfile, modes: List<SupportedDisplayMode>, handheld: Boolean, blocked: Boolean,
    onChange: (GameDisplayProfile) -> Unit,
) {
    val fixed = GameDisplayProfile.isFixed(profile.resolution)
    val rateOptions = listOf(
        "off" to stringResource(R.string.game_display_rate_off),
        "on" to stringResource(R.string.game_display_rate_on),
    )
    val rateKey = if (profile.matchRate) "on" else "off"
    PlayerDropUpSelector(
        label = stringResource(R.string.game_display_rate),
        value = rateOptions.first { it.first == rateKey }.second,
        options = rateOptions, selectedKey = rateKey, dropUp = false,
        selectorKey = "game-display-rate",
        disabledKeys = if (blocked || fixed) rateOptions.map { it.first }.toSet() else emptySet(),
        onSelected = { if (!blocked && !fixed) onChange(profile.copy(matchRate = it == "on")) },
    )
    val available = resolutionOptions(modes, handheld)
    val missing = available.none { it.first == profile.resolution }
    val options = if (missing) available + (profile.resolution to stringResource(R.string.game_display_unavailable)) else available
    PlayerDropUpSelector(
        label = stringResource(R.string.game_display_resolution),
        value = options.first { it.first == profile.resolution }.second,
        options = options, selectedKey = profile.resolution, dropUp = false,
        selectorKey = "game-display-resolution",
        disabledKeys = if (blocked) options.map { it.first }.toSet() else
            if (missing) setOf(profile.resolution) else emptySet(),
        onSelected = { if (!blocked && available.any { option -> option.first == it }) onChange(profile.copy(resolution = it)) },
    )
    if (profile.matchRate && !fixed) {
        val doubleKey = if (profile.preferDouble) "on" else "off"
        val doubleOptions = listOf("off" to stringResource(R.string.game_display_rate_off),
            "on" to stringResource(R.string.game_display_prefer_double_on))
        PlayerDropUpSelector(
            label = stringResource(R.string.game_display_prefer_double),
            value = doubleOptions.first { it.first == doubleKey }.second,
            options = doubleOptions, selectedKey = doubleKey, dropUp = false,
            selectorKey = "game-display-double",
            disabledKeys = if (blocked) doubleOptions.map { it.first }.toSet() else emptySet(),
            onSelected = { if (!blocked) onChange(profile.copy(preferDouble = it == "on")) },
        )
    }
}

@Composable
internal fun GamesGlobalDisplaySettings() {
    val context = LocalContext.current
    val activity = context.findActivity()
    val handheld = !isTelevision(context)
    val modes = rememberAvailableDisplayModes(activity ?: context)
    val baseline = DisplayRefreshRateMatcher.currentMode(activity)
    val preferences = remember(context) { videoImagePreferences(context) }
    val available = baseline?.let { gameDisplayModes(modes, it, handheld, preferences.displayModeWhitelist) }.orEmpty()
    if (available.size <= 1 && !GameSettings.state.displayProfile(scope = GameDisplayScope.GLOBAL).active) return
    val profile = GameSettings.state.displayProfile(scope = GameDisplayScope.GLOBAL)
    GameDisplaySelectors(profile, available, handheld, blocked = false) { selected ->
        GameSettings.update { it.withDisplayProfile(GameDisplayScope.GLOBAL, "", "", selected) }
    }
    SettingsHint(stringResource(if (GameDisplayProfile.isFixed(profile.resolution)) R.string.game_display_fixed_hint else R.string.game_display_hint))
}

@Composable
internal fun GameDisplayPanel(
    coreId: String, gameKey: String, modes: List<SupportedDisplayMode>, handheld: Boolean,
    format: GameDisplayFormat?, actualMode: SupportedDisplayMode?, switching: Boolean, failed: Boolean,
    netplay: Boolean,
) {
    val state = GameSettings.state
    var scope by remember(coreId, gameKey) {
        mutableStateOf(when {
            state.ownDisplayProfile(GameDisplayScope.GAME, coreId, gameKey) != null -> GameDisplayScope.GAME
            state.ownDisplayProfile(GameDisplayScope.CORE, coreId, gameKey) != null -> GameDisplayScope.CORE
            else -> GameDisplayScope.GLOBAL
        })
    }
    val blocked = switching || netplay
    val scopes = listOf(
        GameDisplayScope.GLOBAL.name to stringResource(R.string.game_display_global),
        GameDisplayScope.CORE.name to stringResource(R.string.game_display_core),
        GameDisplayScope.GAME.name to stringResource(R.string.game_display_game_scope),
    )
    PlayerDropUpSelector(
        label = stringResource(R.string.game_display_scope),
        value = scopes.first { it.first == scope.name }.second,
        options = scopes, selectedKey = scope.name, dropUp = false,
        selectorKey = "game-display-scope",
        disabledKeys = if (blocked) scopes.map { it.first }.toSet() else emptySet(),
        onSelected = { key -> if (!blocked) scope = GameDisplayScope.valueOf(key) },
    )
    val profile = state.displayProfile(coreId, gameKey, scope)
    GameDisplaySelectors(profile, modes, handheld, blocked) { selected ->
        GameSettings.update { it.withDisplayProfile(scope, coreId, gameKey, selected) }
    }
    if (scope != GameDisplayScope.GLOBAL && state.ownDisplayProfile(scope, coreId, gameKey) != null) {
        GamePanelChoice(
            text = stringResource(R.string.game_display_inherit), selected = false,
            enabled = !blocked, focusKey = "game-display-inherit",
            onClick = { GameSettings.update { it.withDisplayProfile(scope, coreId, gameKey, null) } },
        )
    }
    val source = listOfNotNull(
        format?.frame?.let { "${it.width} × ${it.height}" },
        format?.fps?.let { String.format(Locale.ROOT, "%.3f FPS", it) },
    ).joinToString(" · ").ifEmpty { stringResource(R.string.game_display_unknown) }
    GamePanelInfo(stringResource(R.string.game_display_source) + ": " + source)
    GamePanelInfo(stringResource(R.string.game_display_output) + ": " +
        (actualMode?.label ?: stringResource(R.string.game_display_unknown)))
    if (netplay) GamePanelText(stringResource(R.string.game_display_netplay))
    else if (switching) GamePanelText(stringResource(R.string.game_display_switching))
    else if (failed) GamePanelText(stringResource(R.string.game_display_failed))
    GamePanelText(stringResource(if (GameDisplayProfile.isFixed(profile.resolution)) R.string.game_display_fixed_hint else R.string.game_display_hint))
}
