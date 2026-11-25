package com.ambient.tv.ui.games

import com.ambient.tv.games.GameSettingsState
import com.swordfish.libretrodroid.ShaderConfig

/** A verified source (or a known missing source) is valid only for its exact selection. */
internal data class GameShaderPackResolution(
    val selection: String?,
    val config: ShaderConfig.Custom?,
) {
    fun matches(request: String?): Boolean = selection == request
    fun configFor(request: String?): ShaderConfig.Custom? = config.takeIf { matches(request) }
}

/** Identity matters: a later click on the same preset supersedes the previous IO request. */
internal class GameShaderPackChoice(
    val selection: String,
    val forGame: Boolean,
    val previousSettings: GameSettingsState,
)
