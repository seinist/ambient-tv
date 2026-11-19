package com.ambient.tv.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.ambient.tv.R
import com.ambient.tv.games.GameBiosManager
import com.ambient.tv.games.GameBiosState
import com.ambient.tv.games.GameCoreManager
import com.ambient.tv.games.GameRomPreparer
import com.ambient.tv.games.GameSystems
import com.ambient.tv.ui.common.SettingsActionRow
import com.ambient.tv.ui.common.SettingsHint
import com.ambient.tv.ui.common.SettingsNavigationRow
import com.ambient.tv.ui.common.TvActionButton
import com.ambient.tv.ui.games.titleRes
import com.ambient.tv.ui.theme.LocalUiTokens
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface GamesSettingsEntryPoint {
    fun bios(): GameBiosManager
    fun cores(): GameCoreManager
    fun roms(): GameRomPreparer
}

/** Settings → Playback → Games: firmware (BIOS) per system, loaded cores and the game copy cache. */
internal fun LazyListScope.gamesSettingsItems() {
    item(key = "games-bios") { GamesBios() }
    item(key = "games-cores") { GamesCores() }
    item(key = "games-cache") { GamesCache() }
}

@Composable
private fun gamesEntryPoint(): GamesSettingsEntryPoint {
    val context = LocalContext.current
    return remember(context) {
        EntryPointAccessors.fromApplication(context.applicationContext, GamesSettingsEntryPoint::class.java)
    }
}

@Composable
private fun GamesBios() {
    val bios = gamesEntryPoint().bios()
    var version by remember { mutableIntStateOf(0) }
    val systems = remember(version) { GameSystems.all.filter { it.bios.isNotEmpty() }.map { it to bios.status(it) } }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(LocalUiTokens.current.compactGap)) {
        SettingsHint(stringResource(R.string.games_settings_explanation))
        SettingsSubheader(stringResource(R.string.games_bios))
        SettingsHint(stringResource(R.string.games_bios_hint))
        systems.forEach { (system, statuses) ->
            SettingsGroup(title = system.name, collapsible = true) {
                statuses.forEach { status ->
                    val required = when {
                        status.bios.requiredFor == null -> stringResource(R.string.games_bios_optional)
                        status.bios.requiredFor.isEmpty() -> stringResource(R.string.games_bios_required)
                        else -> stringResource(R.string.games_bios_required_for, status.bios.requiredFor.joinToString(", ") { ".$it" })
                    }
                    SettingsNavigationRow(
                        title = status.bios.file,
                        summary = listOf(status.bios.description, required, stringResource(status.state.titleRes())).joinToString(" · "),
                        enabled = status.state != GameBiosState.MISSING,
                        onClick = {
                            // Selecting a present file removes it (e.g. to replace it with another dump).
                            bios.remove(status.bios)
                            version++
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun GamesCores() {
    val cores = gamesEntryPoint().cores()
    var version by remember { mutableIntStateOf(0) }
    val installed = remember(version) { GameSystems.all.map { it.core }.distinctBy { it.id }.filter { cores.installed(it) != null } }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(LocalUiTokens.current.compactGap)) {
        SettingsSubheader(stringResource(R.string.games_cores))
        SettingsHint(stringResource(R.string.games_cores_hint))
        if (installed.isEmpty()) SettingsHint(stringResource(R.string.games_cores_none))
        installed.forEach { core ->
            val systems = GameSystems.all.filter { it.core.id == core.id }.joinToString(", ") { it.name }
            SettingsNavigationRow(
                title = core.name,
                summary = stringResource(R.string.games_core_summary, systems, core.license),
                onClick = {
                    cores.remove(core)
                    version++
                },
            )
        }
    }
}

@Composable
private fun GamesCache() {
    val context = LocalContext.current
    val roms = gamesEntryPoint().roms()
    var version by remember { mutableIntStateOf(0) }
    val bytes = remember(version) { roms.cacheBytes() }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(LocalUiTokens.current.compactGap)) {
        SettingsSubheader(stringResource(R.string.games_cache))
        SettingsHint(stringResource(R.string.games_cache_hint, android.text.format.Formatter.formatShortFileSize(context, bytes)))
        if (bytes > 0) SettingsActionRow {
            TvActionButton(stringResource(R.string.games_cache_clear), onClick = {
                roms.clearCache()
                version++
            })
        }
    }
}
