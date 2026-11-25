package com.ambient.tv.games

/** Physical output settings are deliberately separate from core render resolution and shaders. */
internal data class GameDisplayProfile(
    val matchRate: Boolean = false,
    val resolution: String = SYSTEM,
    val preferDouble: Boolean = false,
) {
    val active: Boolean get() = matchRate || resolution != SYSTEM
    fun encode(): String = "${if (matchRate) 1 else 0}|$resolution|${if (preferDouble) 1 else 0}"

    companion object {
        const val SYSTEM = "system"
        const val DISPLAY = "display"
        const val GAME = "game"
        const val PERFORMANCE = "performance"
        private val fixed = Regex("[1-9][0-9]{1,4}x[1-9][0-9]{1,4}@[0-9]{1,3}\\.[0-9]{3}")
        fun isFixed(key: String) = fixed.matches(key) &&
            key.substringAfter('@').toFloatOrNull()?.let { it.isFinite() && it in 1f..999f } == true
        fun decode(value: String?): GameDisplayProfile? {
            val parts = value?.split('|') ?: return null
            if (parts.size != 3 || parts[0] !in setOf("0", "1") || parts[2] !in setOf("0", "1")) return null
            val mode = parts[1]
            if (mode !in setOf(SYSTEM, DISPLAY, GAME, PERFORMANCE) && !isFixed(mode)) return null
            return GameDisplayProfile(parts[0] == "1", mode, parts[2] == "1")
        }
    }
}

internal enum class GameDisplayScope { GLOBAL, CORE, GAME }

private fun displayKey(scope: GameDisplayScope, coreId: String, gameKey: String): String = when (scope) {
    GameDisplayScope.GLOBAL -> "display.global"
    GameDisplayScope.CORE -> "display.core.$coreId"
    GameDisplayScope.GAME -> "display.game.$gameKey"
}

internal fun GameSettingsState.ownDisplayProfile(scope: GameDisplayScope, coreId: String = "", gameKey: String = "") =
    GameDisplayProfile.decode(values[displayKey(scope, coreId, gameKey)])

internal fun GameSettingsState.displayProfile(
    coreId: String = "", gameKey: String = "", scope: GameDisplayScope = GameDisplayScope.GAME,
): GameDisplayProfile =
    (if (scope == GameDisplayScope.GAME && gameKey.isNotEmpty()) ownDisplayProfile(GameDisplayScope.GAME, coreId, gameKey) else null)
        ?: (if (scope != GameDisplayScope.GLOBAL && coreId.isNotEmpty()) ownDisplayProfile(GameDisplayScope.CORE, coreId, gameKey) else null)
        ?: ownDisplayProfile(GameDisplayScope.GLOBAL) ?: GameDisplayProfile()

internal fun GameSettingsState.withDisplayProfile(
    scope: GameDisplayScope, coreId: String, gameKey: String, profile: GameDisplayProfile?,
): GameSettingsState {
    val key = displayKey(scope, coreId, gameKey)
    return copy(values = if (profile == null) values - key else values + (key to profile.encode()))
}
