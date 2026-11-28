package com.ambient.tv.ui.games

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.ambient.tv.R
import com.ambient.tv.games.GameCoreExperiment
import com.ambient.tv.games.GameCoreSource
import com.ambient.tv.games.GameRomPreparer
import com.ambient.tv.games.GameSettings
import com.ambient.tv.games.GameSystems
import com.ambient.tv.games.library.LibraryGame
import com.ambient.tv.ui.common.SettingsHint
import com.ambient.tv.ui.common.SettingsNavigationRow
import com.ambient.tv.ui.settings.SettingsGroup
import com.ambient.tv.ui.settings.GameCoreSourceOverrideSelect
import com.ambient.tv.ui.settings.gamesEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal val GameCoreSource.experimentTitle: Int get() = when (this) {
    GameCoreSource.OPTIMIZED -> R.string.games_core_source_optimized
    GameCoreSource.OPTIMIZED_A57 -> R.string.games_core_source_optimized_a57
    GameCoreSource.HOTPATHS -> R.string.games_core_test_hotpaths
    GameCoreSource.O3 -> R.string.games_core_test_o3
    GameCoreSource.THINLTO -> R.string.games_core_test_thinlto
    GameCoreSource.O3_THINLTO -> R.string.games_core_test_o3_thinlto
    GameCoreSource.TUNE_A57 -> R.string.games_core_test_a57
    GameCoreSource.PICODRIVE_SH2 -> R.string.games_core_test_sh2
    GameCoreSource.SNES_THREADED -> R.string.games_core_test_threaded
    else -> error("Expected a test build")
}

private data class ExperimentOptions(val sources: List<GameCoreSource>, val checking: Boolean = false, val failed: Boolean = false)

/** Both library details open the same locally scoped test launch. No catalog request until expanded. */
@Composable
internal fun GameCoreExperimentOptions(game: LibraryGame, onPlay: (LibraryGame, GameCoreExperiment) -> Unit) {
    val system = GameSystems.byId(game.systemId) ?: return
    val gameKey = GameRomPreparer.sha1(game.fileId)
    val settings = GameSettings.state
    val core = settings.gameCore(system, gameKey)
    GameCoreSourceOverrideSelect(
        own = settings.ownGameCoreSource(gameKey),
        inherited = settings.systemCoreSource(system),
        onSelect = { source -> GameSettings.update { it.withGameCoreSource(gameKey, source) } },
    )
    val entry = gamesEntryPoint()
    val manager = entry.cores()
    val abi = manager.abi ?: return
    val eligible = GameCoreSource.publicExperiments.filter { it.supportsExperiment(core, abi) }
    if (eligible.isEmpty()) return

    key(core.id, abi) {
        GameCoreExperimentList(game, core, abi, eligible, onPlay)
    }
}

@Composable
private fun GameCoreExperimentList(
    game: LibraryGame,
    core: com.ambient.tv.games.GameCore,
    abi: String,
    eligible: List<GameCoreSource>,
    onPlay: (LibraryGame, GameCoreExperiment) -> Unit,
) {
    val entry = gamesEntryPoint()
    SettingsGroup(title = stringResource(R.string.games_core_tests_advanced), collapsible = true) {
        var revision by remember { mutableIntStateOf(0) }
        val result by produceState(ExperimentOptions(emptyList(), checking = true), core, eligible, revision) {
            val channel = entry.channel()
            val installed = withContext(Dispatchers.IO) { eligible.filter { entry.cores().installed(core, it) != null } }
            val candidates = if (channel.available) eligible - installed.toSet() else emptyList()
            value = ExperimentOptions(installed, checking = candidates.isNotEmpty())
            val available = installed.toMutableSet()
            var failed = false
            for (source in candidates) {
                try {
                    if (channel.build(core, abi, source) != null) available += source
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    failed = true
                }
            }
            value = ExperimentOptions(eligible.filter { it in available }, failed = failed)
        }
        if (result.sources.isNotEmpty()) SettingsHint(stringResource(R.string.games_core_experimental_hint))
        result.sources.forEach { source ->
            SettingsNavigationRow(
                title = stringResource(R.string.games_core_test_option, core.name, stringResource(source.experimentTitle)),
                summary = stringResource(if (core.id == "snes9x2010" && source in GameCoreSource.publicExperiments)
                    R.string.games_core_test_threaded_hint else R.string.games_core_test_play),
                onClick = { onPlay(game, GameCoreExperiment(core.id, source)) },
            )
        }
        when {
            result.checking -> SettingsHint(stringResource(R.string.player_loading))
            result.failed -> SettingsNavigationRow(
                title = stringResource(R.string.game_retry),
                summary = stringResource(R.string.games_core_tests_load_failed),
                onClick = { revision++ },
            )
            result.sources.isEmpty() -> SettingsHint(stringResource(R.string.games_core_tests_empty))
        }
    }
}
