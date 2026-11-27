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

/** Stable stored IDs: do not rename existing keys. Includes original effects and pinned Libretro filters. */
enum class GameShader(val key: String, @StringRes val titleRes: Int, @StringRes val hintRes: Int, val group: GameShaderGroup) {
    SHARP("sharp", R.string.game_shader_sharp, R.string.game_shader_sharp_hint, GameShaderGroup.SCALING),
    PIXEL("pixel", R.string.game_shader_pixel, R.string.game_shader_pixel_hint, GameShaderGroup.SCALING),
    SMOOTH("smooth", R.string.game_shader_smooth, R.string.game_shader_smooth_hint, GameShaderGroup.SCALING),
    CUT2("cut2", R.string.game_shader_cut2, R.string.game_shader_cut2_hint, GameShaderGroup.SCALING),
    BICUBIC("bicubic", R.string.game_shader_bicubic, R.string.game_shader_bicubic_hint, GameShaderGroup.SCALING),
    ADAPTIVE("adaptive", R.string.game_shader_adaptive, R.string.game_shader_adaptive_hint, GameShaderGroup.SCALING),
    SHARP_BILINEAR("sharp-bilinear", R.string.game_shader_sharp_bilinear, R.string.game_shader_sharp_bilinear_hint, GameShaderGroup.SCALING),
    PIXEL_AA("pixel-aa", R.string.game_shader_pixel_aa, R.string.game_shader_pixel_aa_hint, GameShaderGroup.SCALING),
    SCALEFX("scalefx", R.string.game_shader_scalefx, R.string.game_shader_scalefx_hint, GameShaderGroup.SCALING),
    SABR("sabr", R.string.game_shader_sabr, R.string.game_shader_sabr_hint, GameShaderGroup.SCALING),
    XBR_LV2("xbr-lv2", R.string.game_shader_xbr_lv2, R.string.game_shader_xbr_lv2_hint, GameShaderGroup.SCALING),
    XBRZ_FREESCALE("xbrz-freescale", R.string.game_shader_xbrz, R.string.game_shader_xbrz_hint, GameShaderGroup.SCALING),
    XSAL_2X("2xsal", R.string.game_shader_2xsal, R.string.game_shader_2xsal_hint, GameShaderGroup.SCALING),
    SUPER_2XSAI("super-2xsai", R.string.game_shader_super_2xsai, R.string.game_shader_super_2xsai_hint, GameShaderGroup.SCALING),
    SUPER_EAGLE("super-eagle", R.string.game_shader_super_eagle, R.string.game_shader_super_eagle_hint, GameShaderGroup.SCALING),
    SCALEHQ2X("scalehq2x", R.string.game_shader_scalehq2x, R.string.game_shader_scalehq2x_hint, GameShaderGroup.SCALING),
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
 * Requested input conversion for a Vulkan core's GLES compositor. The shader
 * also requires matching native source evidence for each frame. This is HDR-to-SDR;
 * most cores output SDR. FP16 transport is optional with an RGBA8 fallback.
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
/** Whole-number scaling: off, always, or only where it leaves a small border (large screens). */
enum class GameIntegerScaling(val key: String, @StringRes val titleRes: Int) {
    OFF("0", R.string.game_picture_integer_off),
    ON("1", R.string.game_picture_integer_on),
    AUTO("auto", R.string.game_picture_integer_auto),
    ;

    val enabled: Boolean get() = this != OFF

    companion object {
        fun fromKey(key: String?) = entries.firstOrNull { it.key == key }
    }
}

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
 * The picture a system starts with until someone chooses: LCD-type filters and whole-number
 * scaling for handheld screens, crisp pixels for 2D consoles, smoothed edges for 3D consoles.
 */
/** Stored for a game whose own picture has no effect although its system has one. */
private const val NO_EFFECT = "none"

object GamePictureDefaults {
    private val handhelds = setOf("gb", "gba", "ngp", "wswan", "lynx")
    private val polygonal = setOf("n64", "gamecube", "wii", "wiiu", "ps2", "psp", "dreamcast")

    fun shader(system: GameSystem): GameShader = when (system.id) {
        "gba" -> GameShader.GBA_COLOR
        "nds", "n3ds" -> GameShader.DS_LCD
        in handhelds -> GameShader.LCD
        in polygonal -> GameShader.SMOOTH
        else -> GameShader.SHARP
    }

    fun integerScaling(system: GameSystem): GameIntegerScaling =
        if (system.id in handhelds) GameIntegerScaling.ON else GameIntegerScaling.OFF

    /**
     * Effects that can run in front of any filter: they work on the original picture, so they
     * stay correct before scaling, CRT and LCD filters alike.
     */
    val effects: List<GameShader> = listOf(
        GameShader.NTSC_COMPOSITE, GameShader.VHS_SOFT, GameShader.COLOR_BOOST, GameShader.FILM_GRAIN,
    )

    /** Systems with a handheld screen; the Mega Drive entry also runs Game Gear games. */
    private val handheldScreens = handhelds + setOf("nds", "n3ds", "psp", "vb", "megadrive")

    /**
     * Whether the picture panel offers [shader] for [system]: handheld screen filters only on
     * handhelds, and the console-specific ones only on their console.
     */
    fun offers(system: GameSystem, shader: GameShader): Boolean = when (shader) {
        GameShader.GAMEBOY_DMG, GameShader.GBC_GRID -> system.id == "gb"
        GameShader.GBA_COLOR -> system.id == "gba"
        GameShader.DS_LCD -> system.id in setOf("nds", "n3ds")
        else -> shader.group != GameShaderGroup.HANDHELD || system.id in handheldScreens
    }

    /** Filters that suit the system's screen, shown first and marked in the picture panel. */
    fun recommended(system: GameSystem): Set<GameShader> = when (system.id) {
        "gb" -> setOf(GameShader.LCD, GameShader.GAMEBOY_DMG, GameShader.GBC_GRID)
        "gba" -> setOf(GameShader.GBA_COLOR, GameShader.LCD)
        "nds", "n3ds" -> setOf(GameShader.DS_LCD, GameShader.LCD)
        in handhelds -> setOf(GameShader.LCD)
        in polygonal -> setOf(GameShader.SMOOTH, GameShader.BICUBIC, GameShader.ADAPTIVE)
        else -> setOf(GameShader.SHARP, GameShader.PIXEL, GameShader.SHARP_BILINEAR, GameShader.CRT, GameShader.NTSC_COMPOSITE)
    }
}

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
            ?: GameShader.fromKey(values["shader"]) ?: GamePictureDefaults.shader(system)

    /** An optional game override takes precedence over the system and global defaults. */
    fun ownShader(system: GameSystem): GameShader? = GameShader.fromKey(values["shader.${system.id}"])

    fun ownGameShader(gameKey: String): GameShader? = GameShader.fromKey(values["shader.game.$gameKey"])

    fun withGameShader(gameKey: String, shader: GameShader?): GameSettingsState =
        set("shader.game.$gameKey", shader?.key)

    /** Signed pack selections reuse built-in shader precedence, never a separate dangling override. */
    fun packShader(system: GameSystem, gameKey: String? = null): String? {
        val value = gameKey?.let { values["shader.game." + it] }
            ?: values["shader." + system.id] ?: values["shader"]
        return value?.takeIf { it.startsWith("pack:") }?.removePrefix("pack:")
    }

    fun withPackShader(system: GameSystem, gameKey: String?, selection: String?): GameSettingsState {
        val key = if (gameKey != null) "shader.game." + gameKey else "shader." + system.id
        return set(key, selection?.let { "pack:" + it })
    }

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

    fun aspect(system: GameSystem, gameKey: String? = null): GameAspect =
        gameKey?.let { GameAspect.fromKey(values["aspect.game.$it"]) }
            ?: GameAspect.fromKey(values["aspect.${system.id}"]) ?: GameAspect.CORE

    fun withGameAspect(gameKey: String, aspect: GameAspect?): GameSettingsState = set("aspect.game.$gameKey", aspect?.key)

    fun withAspect(system: GameSystem, aspect: GameAspect): GameSettingsState = set("aspect.${system.id}", aspect.key.takeIf { aspect != GameAspect.CORE })

    /** Scales by whole numbers only: every source pixel the same size, black borders around. */
    fun integerScaling(system: GameSystem, gameKey: String? = null): GameIntegerScaling =
        gameKey?.let { GameIntegerScaling.fromKey(values["integer.game.$it"]) }
            ?: GameIntegerScaling.fromKey(values["integer.${system.id}"])
            ?: GamePictureDefaults.integerScaling(system)

    fun withIntegerScaling(system: GameSystem, mode: GameIntegerScaling): GameSettingsState =
        set("integer.${system.id}", mode.key.takeIf { mode != GamePictureDefaults.integerScaling(system) })

    fun withGameIntegerScaling(gameKey: String, mode: GameIntegerScaling?): GameSettingsState =
        set("integer.game.$gameKey", mode?.key)

    /** The extra effect in front of the filter, or null. A game can switch the system's effect off. */
    fun effect(system: GameSystem, gameKey: String? = null): GameShader? {
        val own = gameKey?.let { values["effect.game.$it"] }
        if (own == NO_EFFECT) return null
        return (own ?: values["effect.${system.id}"])?.let(GameShader::fromKey)
            ?.takeIf { it in GamePictureDefaults.effects }
    }

    fun withEffect(system: GameSystem, effect: GameShader?): GameSettingsState =
        set("effect.${system.id}", effect?.takeIf { it in GamePictureDefaults.effects }?.key)

    fun withGameEffect(gameKey: String, effect: GameShader?): GameSettingsState =
        set("effect.game.$gameKey", effect?.takeIf { it in GamePictureDefaults.effects }?.key ?: NO_EFFECT)

    /** Filter, strength, effect, shape and scaling can belong to one game; then all of them do. */
    fun gamePictureScope(gameKey: String): Boolean =
        gamePictureKeys(gameKey).any { it in values }

    /** Pins the picture the game shows now to the game, so later system changes leave it alone. */
    fun withGamePicture(system: GameSystem, gameKey: String): GameSettingsState {
        val pack = packShader(system, gameKey)
        val current = this
        return current.withoutGamePicture(gameKey)
            .set("shader.game.$gameKey", pack?.let { "pack:$it" } ?: current.shader(system, gameKey).key)
            .withShaderStrength(system, gameKey, current.shaderStrength(system, gameKey))
            .withGameAspect(gameKey, current.aspect(system, gameKey))
            .withGameIntegerScaling(gameKey, current.integerScaling(system, gameKey))
            .withGameEffect(gameKey, current.effect(system, gameKey))
    }

    fun withoutGamePicture(gameKey: String): GameSettingsState =
        copy(values = values - gamePictureKeys(gameKey).toSet())

    private fun gamePictureKeys(gameKey: String) = listOf(
        "shader.game.$gameKey", "shader.intensity.game.$gameKey", "aspect.game.$gameKey", "integer.game.$gameKey",
        "effect.game.$gameKey",
    )

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

    /** Global game profile; overrides only optional overhead, without editing individual choices. */
    val performanceProfile: GamePerformanceProfile get() = GamePerformanceProfile.fromKey(values["performance.profile"])

    fun withPerformanceProfile(profile: GamePerformanceProfile): GameSettingsState =
        set("performance.profile", profile.storedKey)

    val rewindForPlayback: Boolean get() = performanceProfile.effectiveRewind(rewind)

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

    /** SDR games as HDR10 on HDR displays (GLES output, all games); off unless switched on. */
    val sdrToHdr: Boolean get() = values["hdr.sdr"] == "1"

    fun withSdrToHdr(on: Boolean): GameSettingsState = set("hdr.sdr", "1".takeIf { on })

    /** SDR white inside the HDR signal in nits, like the video setting; 0 = Auto (203). */
    val sdrReferenceWhite: Int get() = values["hdr.sdr.white"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0

    fun withSdrReferenceWhite(nits: Int): GameSettingsState = set("hdr.sdr.white", nits.takeIf { it > 0 }?.toString())

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
        .takeIf { it in GameCoreSource.regular } ?: GameCoreSource.AUTO

    fun withCoreSource(source: GameCoreSource): GameSettingsState =
        set("core_source", source.key.takeIf { source != GameCoreSource.AUTO && source in GameCoreSource.regular })

    internal fun migrateCoreSource(): GameSettingsState =
        if (GameCoreSource.fromKey(values["core_source"]) !in GameCoreSource.regular) {
            withCoreSource(GameCoreSource.AUTO).withCoreExperimentsEnabled(true)
        } else this

    val coreExperimentsEnabled: Boolean get() = values["core_experiments"] == "1"

    fun withCoreExperimentsEnabled(enabled: Boolean): GameSettingsState = set("core_experiments", "1".takeIf { enabled })

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
        reload(stored)
    }

    fun reload(stored: Map<String, String>) {
        val migrated = GameSettingsState(stored).migrateCoreSource()
        mutable.value = migrated
        if (migrated.values != stored) persist(migrated.values)
    }

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
