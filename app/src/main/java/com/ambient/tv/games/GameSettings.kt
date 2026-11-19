package com.ambient.tv.games

import androidx.annotation.StringRes
import androidx.compose.runtime.mutableStateOf
import com.ambient.tv.R

/** Picture filters LibretroDroid offers. Stored by [key]. */
enum class GameShader(val key: String, @StringRes val titleRes: Int, @StringRes val hintRes: Int) {
    SHARP("sharp", R.string.game_shader_sharp, R.string.game_shader_sharp_hint),
    PIXEL("pixel", R.string.game_shader_pixel, R.string.game_shader_pixel_hint),
    SMOOTH("smooth", R.string.game_shader_smooth, R.string.game_shader_smooth_hint),
    CRT("crt", R.string.game_shader_crt, R.string.game_shader_crt_hint),
    LCD("lcd", R.string.game_shader_lcd, R.string.game_shader_lcd_hint),
    ;

    companion object {
        fun fromKey(key: String?) = entries.firstOrNull { it.key == key }
    }
}

/**
 * Game settings: the picture filter (per system, falling back to all games) and core options
 * (per core). Values the core does not know any more are ignored when it starts.
 */
data class GameSettingsState(val values: Map<String, String> = emptyMap()) {
    fun shader(system: GameSystem): GameShader =
        GameShader.fromKey(values["shader.${system.id}"]) ?: GameShader.fromKey(values["shader"]) ?: GameShader.SHARP

    /** The filter set for exactly this system, or null when it follows all games. */
    fun ownShader(system: GameSystem): GameShader? = GameShader.fromKey(values["shader.${system.id}"])

    fun withShader(system: GameSystem?, shader: GameShader?): GameSettingsState {
        val key = if (system == null) "shader" else "shader.${system.id}"
        return copy(values = if (shader == null) values - key else values + (key to shader.key))
    }

    fun coreOptions(core: GameCore): Map<String, String> {
        val prefix = "core.${core.id}."
        return values.filterKeys { it.startsWith(prefix) }.mapKeys { it.key.removePrefix(prefix) }
    }

    fun withCoreOption(core: GameCore, key: String, value: String?): GameSettingsState {
        val stored = "core.${core.id}.$key"
        return copy(values = if (value == null) values - stored else values + (stored to value))
    }

    fun withoutCoreOptions(core: GameCore): GameSettingsState =
        copy(values = values.filterKeys { !it.startsWith("core.${core.id}.") })
}

/** App-wide game settings; MainActivity attaches storage. Main thread only. */
object GameSettings {
    private val mutable = mutableStateOf(GameSettingsState())
    val state: GameSettingsState get() = mutable.value
    private var persist: (Map<String, String>) -> Unit = {}

    fun attach(stored: Map<String, String>, save: (Map<String, String>) -> Unit) {
        persist = save
        mutable.value = GameSettingsState(stored)
    }

    fun reload(stored: Map<String, String>) { mutable.value = GameSettingsState(stored) }

    fun update(transform: (GameSettingsState) -> GameSettingsState) {
        val value = transform(state)
        if (value == state) return
        mutable.value = value
        persist(value.values)
    }
}

/** A core option as the core declares it ("Description; first|second|third"). */
data class GameCoreOption(val key: String, val title: String, val values: List<String>, val value: String) {
    companion object {
        fun parse(key: String, description: String?, value: String?): GameCoreOption? {
            val text = description ?: return null
            val title = text.substringBefore(';').trim().ifEmpty { key }
            val choices = text.substringAfter(';', "").split('|').map { it.trim() }.filter { it.isNotEmpty() }
            if (choices.isEmpty()) return null
            return GameCoreOption(key, title, choices, value?.takeIf { it in choices } ?: choices.first())
        }
    }
}
