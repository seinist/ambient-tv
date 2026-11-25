package com.ambient.tv.ui.games

import com.ambient.tv.games.GameCore
import com.ambient.tv.games.GameHdrToneMap
import com.ambient.tv.games.GameSettingsState

/** Keep the picture input choice and the effective core color format in agreement. */
internal object GameHdrInputSettings {
    fun select(state: GameSettingsState, core: GameCore, gameKey: String, input: GameHdrToneMap): GameSettingsState {
        val mode = state.withHdrToneMap(core, input)
        val (key, hdrValue) = GameVulkanHdrSourceEvidence.hdrOption(core.id) ?: return mode
        val updated = when {
            input == GameHdrToneMap.PQ -> mode.withCoreOption(core, key, hdrValue)
            state.coreOptions(core)[key] == hdrValue -> mode.withCoreOption(core, key, null)
            else -> mode
        }
        // The game's own value wins over the global core option. An explicit
        // picture choice replaces this one conflict, preserving unrelated
        // per-game options and all other games' overrides.
        return if (input == GameHdrToneMap.PQ || state.gameCoreOptions(core, gameKey)[key] == hdrValue)
            updated.withGameCoreOption(core, gameKey, key, null)
        else updated
    }
}
