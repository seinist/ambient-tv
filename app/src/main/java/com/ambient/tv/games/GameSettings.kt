package com.ambient.tv.games

import androidx.annotation.StringRes
import androidx.compose.runtime.mutableStateOf
import com.ambient.tv.R
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Picture filters LibretroDroid offers. Stored by [key]. */
enum class GameShaderGroup(@StringRes val titleRes: Int) {
    SCALING(R.string.game_shader_group_scaling),
    CRT(R.string.game_shader_group_crt),
    HANDHELD(R.string.game_shader_group_handheld),
    SIGNAL(R.string.game_shader_group_signal),
    EFFECTS(R.string.game_shader_group_effects),
}

/** Stable stored IDs: do not rename existing keys. Extra shaders are original compact GLSL. */
enum class GameShader(val key: String, @StringRes val titleRes: Int, @StringRes val hintRes: Int, val group: GameShaderGroup) {
    SHARP("sharp", R.string.game_shader_sharp, R.string.game_shader_sharp_hint, GameShaderGroup.SCALING),
    PIXEL("pixel", R.string.game_shader_pixel, R.string.game_shader_pixel_hint, GameShaderGroup.SCALING),
    SMOOTH("smooth", R.string.game_shader_smooth, R.string.game_shader_smooth_hint, GameShaderGroup.SCALING),
    CUT2("cut2", R.string.game_shader_cut2, R.string.game_shader_cut2_hint, GameShaderGroup.SCALING),
    BICUBIC("bicubic", R.string.game_shader_bicubic, R.string.game_shader_bicubic_hint, GameShaderGroup.SCALING),
    ADAPTIVE("adaptive", R.string.game_shader_adaptive, R.string.game_shader_adaptive_hint, GameShaderGroup.SCALING),
    SHARP_BILINEAR("sharp-bilinear", R.string.game_shader_sharp_bilinear, R.string.game_shader_sharp_bilinear_hint, GameShaderGroup.SCALING),
    PIXEL_AA("pixel-aa", R.string.game_shader_pixel_aa, R.string.game_shader_pixel_aa_hint, GameShaderGroup.SCALING),
    CRT("crt", R.string.game_shader_crt, R.string.game_shader_crt_hint, GameShaderGroup.CRT),
    CRT_LIGHT("crt-light", R.string.game_shader_crt_light, R.string.game_shader_crt_light_hint, GameShaderGroup.CRT),
    CRT_GEOMETRY("crt-geometry", R.string.game_shader_crt_geometry, R.string.game_shader_crt_geometry_hint, GameShaderGroup.CRT),
    CRT_APERTURE("crt-aperture", R.string.game_shader_crt_aperture, R.string.game_shader_crt_aperture_hint, GameShaderGroup.CRT),
    CRT_GLOW("crt-glow", R.string.game_shader_crt_glow, R.string.game_shader_crt_glow_hint, GameShaderGroup.CRT),
    LCD("lcd", R.string.game_shader_lcd, R.string.game_shader_lcd_hint, GameShaderGroup.HANDHELD),
    GAMEBOY_DMG("gb-dmg", R.string.game_shader_gb_dmg, R.string.game_shader_gb_dmg_hint, GameShaderGroup.HANDHELD),
    GBC_GRID("gbc-grid", R.string.game_shader_gbc_grid, R.string.game_shader_gbc_grid_hint, GameShaderGroup.HANDHELD),
    GBA_COLOR("gba-color", R.string.game_shader_gba_color, R.string.game_shader_gba_color_hint, GameShaderGroup.HANDHELD),
    DS_LCD("ds-lcd", R.string.game_shader_ds_lcd, R.string.game_shader_ds_lcd_hint, GameShaderGroup.HANDHELD),
    NTSC_COMPOSITE("ntsc", R.string.game_shader_ntsc, R.string.game_shader_ntsc_hint, GameShaderGroup.SIGNAL),
    VHS_SOFT("vhs", R.string.game_shader_vhs, R.string.game_shader_vhs_hint, GameShaderGroup.SIGNAL),
    FILM_GRAIN("film-grain", R.string.game_shader_film_grain, R.string.game_shader_film_grain_hint, GameShaderGroup.EFFECTS),
    DEBAND("deband", R.string.game_shader_deband, R.string.game_shader_deband_hint, GameShaderGroup.EFFECTS),
    COLOR_BOOST("color-boost", R.string.game_shader_color_boost, R.string.game_shader_color_boost_hint, GameShaderGroup.EFFECTS),
    ;

    companion object {
        fun fromKey(key: String?) = entries.firstOrNull { it.key == key }
    }
}

/**
 * Manual input transfer conversion for the GLES compositor of a Vulkan libretro core.
 * This is HDR-to-SDR only. Most cores output SDR; never enable automatically or call it
 * native HDR output. FP16 Vulkan-to-GLES transport is optional with a safe RGBA8 fallback.
 */
enum class GameHdrToneMap(val key: String, @StringRes val titleRes: Int) {
    OFF("off", R.string.game_picture_hdr_off),
    PQ("pq", R.string.game_picture_hdr_pq),
    HLG("hlg", R.string.game_picture_hdr_hlg),
    ;

    companion object {
        fun fromKey(key: String?): GameHdrToneMap? = entries.firstOrNull { it.key == key }
    }
}

/** Picture shape. [ratio] null keeps the shape the core reports. */
enum class GameAspect(val key: String, val ratio: Float?, @StringRes val titleRes: Int) {
    CORE("core", null, R.string.game_aspect_core),
    FOUR_THREE("4:3", 4f / 3f, R.string.game_aspect_4_3),
    EIGHT_SEVEN("8:7", 8f / 7f, R.string.game_aspect_8_7),
    SQUARE("1:1", 1f, R.string.game_aspect_1_1),
    THREE_TWO("3:2", 3f / 2f, R.string.game_aspect_3_2),
    SIXTEEN_TEN("16:10", 16f / 10f, R.string.game_aspect_16_10),
    WIDE("16:9", 16f / 9f, R.string.game_aspect_16_9),
    ;

    companion object {
        fun fromKey(key: String?) = entries.firstOrNull { it.key == key }
    }
}

/** Virtual handset controls can be hidden for pointer-heavy games. */
enum class GameTouchMode(val key: String, @StringRes val titleRes: Int) {
    DPAD("pad", R.string.game_touch_mode_dpad),
    ANALOG("analog", R.string.game_touch_mode_analog),
    HYBRID("hybrid", R.string.game_touch_mode_hybrid),
    DUAL("dual", R.string.game_touch_mode_dual),
    ;
    companion object {
        fun fromKey(key: String?): GameTouchMode = entries.firstOrNull { it.key == key } ?: DPAD
    }
}

/** Where a core option's value comes from. */
enum class GameOptionOrigin { DEFAULT, CORE, GAME }

/**
 * Game settings: picture (filter, shape, whole-number scaling; per system with the filter falling
 * back to all games), rewind, the graphics interface of GPU cores, and core options. Core options
 * apply to every game of a core ("core.<core>.<key>") unless a game has its own value
 * ("game.<game>.<core>.<key>"). Values the core does not know any more are ignored when it starts.
 */
data class GameSettingsState(val values: Map<String, String> = emptyMap()) {
    fun shader(system: GameSystem, gameKey: String? = null): GameShader =
        gameKey?.let { ownGameShader(it) }
            ?: GameShader.fromKey(values["shader.${system.id}"])
            ?: GameShader.fromKey(values["shader"]) ?: GameShader.SHARP

    /** An optional game override takes precedence over the system and global defaults. */
    fun ownShader(system: GameSystem): GameShader? = GameShader.fromKey(values["shader.${system.id}"])

    fun ownGameShader(gameKey: String): GameShader? = GameShader.fromKey(values["shader.game.$gameKey"])

    fun withGameShader(gameKey: String, shader: GameShader?): GameSettingsState =
        set("shader.game.$gameKey", shader?.key)

    fun withShader(system: GameSystem?, shader: GameShader?): GameSettingsState {
        val key = if (system == null) "shader" else "shader.${system.id}"
        return copy(values = if (shader == null) values - key else values + (key to shader.key))
    }

    /** Finite, small parameter presets; invalid backup values cannot affect GLSL generation. */
    fun shaderStrength(system: GameSystem, gameKey: String? = null): Float {
        val raw = gameKey?.let { values["shader.intensity.game.$it"] }
            ?: values["shader.intensity.${system.id}"] ?: "1.0"
        return raw.toFloatOrNull()?.takeIf { it.isFinite() }?.coerceIn(0.5f, 1.5f) ?: 1f
    }

    fun withShaderStrength(system: GameSystem, gameKey: String?, strength: Float): GameSettingsState {
        val key = if (gameKey == null) "shader.intensity.${system.id}" else "shader.intensity.game.$gameKey"
        val bounded = strength.takeIf { it.isFinite() }?.coerceIn(0.5f, 1.5f) ?: 1f
        return set(key, bounded.toString().takeUnless { bounded == 1f })
    }

    fun aspect(system: GameSystem): GameAspect = GameAspect.fromKey(values["aspect.${system.id}"]) ?: GameAspect.CORE

    fun withAspect(system: GameSystem, aspect: GameAspect): GameSettingsState = set("aspect.${system.id}", aspect.key.takeIf { aspect != GameAspect.CORE })

    /** Scales by whole numbers only: every source pixel the same size, black borders around. */
    fun integerScaling(system: GameSystem): Boolean = values["integer.${system.id}"] == "1"

    fun withIntegerScaling(system: GameSystem, on: Boolean): GameSettingsState = set("integer.${system.id}", "1".takeIf { on })

    /** DS/3DS pointer games default to an unobstructed screen; others show buttons on phones. */
    fun touchControls(system: GameSystem): Boolean =
        values["touch.${system.id}"]?.let { it == "1" } ?: !system.pointerInput

    fun withTouchControls(system: GameSystem, on: Boolean): GameSettingsState =
        set("touch.${system.id}", (if (on) "1" else "0").takeIf { on != !system.pointerInput })

    fun touchMode(system: GameSystem): GameTouchMode = GameTouchMode.fromKey(values["touchmode.${system.id}"])

    fun withTouchMode(system: GameSystem, mode: GameTouchMode): GameSettingsState =
        set("touchmode.${system.id}", mode.key.takeIf { mode != GameTouchMode.DPAD })

    /** Rewind keeps the last seconds of play in memory; on unless switched off. */
    val rewind: Boolean get() = values["rewind"] != "0"

    fun withRewind(on: Boolean): GameSettingsState = set("rewind", "0".takeIf { !on })

    /** GPU cores: ask for Vulkan instead of OpenGL ES (used when the device and core can). */
    fun vulkan(core: GameCore): Boolean =
        core.hardware && core.supportsVulkan && (core.requiresVulkan || values["vulkan.${core.id}"] == "1")

    fun withVulkan(core: GameCore, on: Boolean): GameSettingsState =
        if (!core.supportsVulkan || core.requiresVulkan) this
        else set("vulkan.${core.id}", "1".takeIf { on })

    /** Manual PQ/HLG input decoding is meaningful only for a Vulkan hardware core. */
    fun hdrToneMap(core: GameCore): GameHdrToneMap =
        if (core.hardware && core.supportsVulkan)
            GameHdrToneMap.fromKey(values["hdr.${core.id}"]) ?: GameHdrToneMap.OFF
        else GameHdrToneMap.OFF

    fun withHdrToneMap(core: GameCore, mode: GameHdrToneMap): GameSettingsState =
        if (!core.hardware || !core.supportsVulkan) this
        else set("hdr.${core.id}", mode.key.takeIf { mode != GameHdrToneMap.OFF })

    /** RetroAchievements while playing (needs an account); on unless switched off. */
    val achievements: Boolean get() = values["achievements"] != "0"

    fun withAchievements(on: Boolean): GameSettingsState = set("achievements", "0".takeIf { !on })

    /** RetroAchievements hardcore: no loading states, rewind or cheats; unlocks count double. */
    val hardcore: Boolean get() = values["hardcore"] == "1"

    fun withHardcore(on: Boolean): GameSettingsState = set("hardcore", "1".takeIf { on })

    /** Controllers vibrate when the game asks for it; on unless switched off. */
    val rumble: Boolean get() = values["rumble"] != "0"

    fun withRumble(on: Boolean): GameSettingsState = set("rumble", "0".takeIf { !on })

    /** The libretro device type of player [port] with [core] (e.g. analog pad, mouse), null = core default. */
    fun controllerType(core: GameCore, port: Int): Int? = values["device.${core.id}.$port"]?.toIntOrNull()

    fun withControllerType(core: GameCore, port: Int, type: Int?): GameSettingsState = set("device.${core.id}.$port", type?.toString())

    /** The core [system] plays with unless a game has its own: chosen in settings, else the default. */
    fun systemCore(system: GameSystem): GameCore = system.core(values["syscore.${system.id}"]) ?: system.core

    fun withSystemCore(system: GameSystem, core: GameCore): GameSettingsState =
        set("syscore.${system.id}", core.id.takeIf { core.id != system.core.id && system.core(core.id) != null })

    /** The core for one game ([gameKey]): its own choice, else the system's. */
    fun gameCore(system: GameSystem, gameKey: String): GameCore = system.core(values["gamecore.$gameKey"]) ?: systemCore(system)

    /** The game's own core, or null when it follows the system. */
    fun ownGameCore(system: GameSystem, gameKey: String): GameCore? = system.core(values["gamecore.$gameKey"])

    fun withGameCore(system: GameSystem, gameKey: String, core: GameCore?): GameSettingsState =
        set("gamecore.$gameKey", core?.id?.takeIf { system.core(it) != null })

    /** Where cores are loaded from (the app's signed build, the libretro buildbot, or the first that has it). */
    val coreSource: GameCoreSource get() = GameCoreSource.fromKey(values["core_source"])

    fun withCoreSource(source: GameCoreSource): GameSettingsState = set("core_source", source.key.takeIf { source != GameCoreSource.AUTO })

    /** Options of [core] for all its games. */
    fun coreOptions(core: GameCore): Map<String, String> = withPrefix("core.${core.id}.")

    /** Options only [gameKey] uses with [core]. */
    fun gameCoreOptions(core: GameCore, gameKey: String): Map<String, String> = withPrefix(gamePrefix(core, gameKey))

    /** What [core] starts with for [gameKey]: the core's options, the game's own on top. */
    fun effectiveCoreOptions(core: GameCore, gameKey: String?): Map<String, String> =
        coreOptions(core) + (gameKey?.let { gameCoreOptions(core, it) } ?: emptyMap())

    fun coreOptionOrigin(core: GameCore, gameKey: String?, key: String): GameOptionOrigin = when {
        gameKey != null && values.containsKey(gamePrefix(core, gameKey) + key) -> GameOptionOrigin.GAME
        values.containsKey("core.${core.id}.$key") -> GameOptionOrigin.CORE
        else -> GameOptionOrigin.DEFAULT
    }

    /** For all games of [core]; the value replaces this game's own one, so the choice shows at once. */
    fun withCoreOption(core: GameCore, key: String, value: String?, gameKey: String? = null): GameSettingsState {
        val cleared = gameKey?.let { set(gamePrefix(core, it) + key, null) } ?: this
        return cleared.set("core.${core.id}.$key", value)
    }

    fun withGameCoreOption(core: GameCore, gameKey: String, key: String, value: String?): GameSettingsState =
        set(gamePrefix(core, gameKey) + key, value)

    fun withoutCoreOptions(core: GameCore): GameSettingsState =
        copy(values = values.filterKeys { !it.startsWith("core.${core.id}.") })

    fun withoutGameCoreOptions(core: GameCore, gameKey: String): GameSettingsState =
        copy(values = values.filterKeys { !it.startsWith(gamePrefix(core, gameKey)) })

    private fun gamePrefix(core: GameCore, gameKey: String) = "game.$gameKey.${core.id}."

    private fun withPrefix(prefix: String): Map<String, String> =
        values.filterKeys { it.startsWith(prefix) }.mapKeys { it.key.removePrefix(prefix) }

    private fun set(key: String, value: String?): GameSettingsState =
        copy(values = if (value == null) values - key else values + (key to value))
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

/** One value of a core option: what the core stores and what it shows. */
data class GameCoreValue(val value: String, val label: String)

/**
 * A core option as the core declares it. Cores with core options v1/v2 describe it as JSON (title,
 * info, category, labelled values, default, visibility; translated by the core for the device
 * language); older cores as "Description; first|second|third".
 */
data class GameCoreOption(
    val key: String,
    val title: String,
    val values: List<GameCoreValue>,
    val value: String,
    val info: String = "",
    val category: GameCoreCategory? = null,
    val default: String = values.firstOrNull()?.value.orEmpty(),
    val visible: Boolean = true,
) {
    val label: String get() = values.firstOrNull { it.value == value }?.label ?: value

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(key: String, description: String?, value: String?): GameCoreOption? {
            val text = description?.trim() ?: return null
            if (text.startsWith("{")) return parseMeta(key, text, value)
            val title = text.substringBefore(';').trim().ifEmpty { key }
            val choices = text.substringAfter(';', "").split('|').map { it.trim() }.filter { it.isNotEmpty() }
            if (choices.isEmpty()) return null
            return GameCoreOption(key, title, choices.map { GameCoreValue(it, it) }, value?.takeIf { it in choices } ?: choices.first())
        }

        private fun parseMeta(key: String, text: String, value: String?): GameCoreOption? {
            val meta = runCatching { json.decodeFromString(GameCoreOptionMeta.serializer(), text) }.getOrNull() ?: return null
            val values = meta.values.filter { it.value.isNotEmpty() }.map { GameCoreValue(it.value, it.label.ifBlank { it.value }) }
            if (values.isEmpty()) return null
            val default = meta.default.takeIf { d -> values.any { it.value == d } } ?: values.first().value
            return GameCoreOption(
                key = key,
                title = meta.title.ifBlank { key },
                values = values,
                value = value?.takeIf { v -> values.any { it.value == v } } ?: default,
                info = meta.info,
                category = meta.category.takeIf { it.isNotBlank() }?.let {
                    GameCoreCategory(it, meta.categoryTitle.ifBlank { it }, meta.categoryInfo)
                },
                default = default,
                visible = meta.visible,
            )
        }
    }
}

/** A group of core options (core options v2). */
data class GameCoreCategory(val key: String, val title: String, val info: String)

@Serializable
private data class GameCoreOptionMeta(
    val title: String = "",
    val info: String = "",
    val category: String = "",
    val categoryTitle: String = "",
    val categoryInfo: String = "",
    val default: String = "",
    val visible: Boolean = true,
    val values: List<GameCoreOptionMetaValue> = emptyList(),
)

@Serializable
private data class GameCoreOptionMetaValue(val value: String = "", val label: String = "")
