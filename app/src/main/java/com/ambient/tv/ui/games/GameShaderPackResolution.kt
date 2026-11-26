package com.ambient.tv.ui.games

import com.ambient.tv.games.GameSettingsState
import com.ambient.tv.games.GameSystem
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
) {
    /** Unrelated preferences can change during verification. Only a newer
     * picture selection/scope (or an edit of the targeted shader key) wins.
     * GameSettings is owned by the main dispatcher, so validation and update
     * are serialized with other UI/settings writes. */
    fun stillApplies(current: GameSettingsState, system: GameSystem, gameKey: String): Boolean {
        val targetKey = if (forGame) "shader.game.$gameKey" else "shader.${system.id}"
        return current.gamePictureScope(gameKey) == forGame &&
            current.values[targetKey] == previousSettings.values[targetKey] &&
            current.packShader(system, gameKey) == previousSettings.packShader(system, gameKey) &&
            current.shader(system, gameKey) == previousSettings.shader(system, gameKey) &&
            current.shaderStrength(system, gameKey) == previousSettings.shaderStrength(system, gameKey) &&
            current.effect(system, gameKey) == previousSettings.effect(system, gameKey)
    }
}
