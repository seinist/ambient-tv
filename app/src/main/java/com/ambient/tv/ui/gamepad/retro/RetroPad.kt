package com.ambient.tv.ui.gamepad.retro

import android.view.KeyEvent
import androidx.annotation.StringRes
import androidx.compose.runtime.mutableStateOf
import com.ambient.tv.R as AppR

/**
 * The libretro RetroPad (RETRO_DEVICE_JOYPAD). [id] is RETRO_DEVICE_ID_JOYPAD_*; [key] is stored,
 * never rename one. Face buttons follow SNES positions: B bottom, A right, Y left, X top.
 */
enum class RetroPadButton(val key: String, val id: Int, @StringRes val titleRes: Int) {
    UP("up", 4, AppR.string.retro_button_up),
    DOWN("down", 5, AppR.string.retro_button_down),
    LEFT("left", 6, AppR.string.retro_button_left),
    RIGHT("right", 7, AppR.string.retro_button_right),
    A("a", 8, AppR.string.retro_button_a),
    B("b", 0, AppR.string.retro_button_b),
    X("x", 9, AppR.string.retro_button_x),
    Y("y", 1, AppR.string.retro_button_y),
    L("l", 10, AppR.string.retro_button_l),
    R("r", 11, AppR.string.retro_button_r),
    L2("l2", 12, AppR.string.retro_button_l2),
    R2("r2", 13, AppR.string.retro_button_r2),
    L3("l3", 14, AppR.string.retro_button_l3),
    R3("r3", 15, AppR.string.retro_button_r3),
    START("start", 3, AppR.string.retro_button_start),
    SELECT("select", 2, AppR.string.retro_button_select),
    ;

    val mask: Int get() = 1 shl id

    companion object {
        fun fromKey(key: String): RetroPadButton? = entries.firstOrNull { it.key == key }
    }
}

/** Kinds of input device that can play; each has its own key layout. Stored by [key]. */
enum class RetroInputKind(val key: String, @StringRes val titleRes: Int) {
    GAMEPAD("gamepad", AppR.string.retro_kind_gamepad),
    KEYBOARD("keyboard", AppR.string.retro_kind_keyboard),
    REMOTE("remote", AppR.string.retro_kind_remote),
    ;

    companion object {
        fun fromKey(key: String): RetroInputKind? = entries.firstOrNull { it.key == key }
    }
}

/** What a key can do in a game besides a RetroPad button (game menu and hotkeys). */
enum class RetroMenuTrigger(val key: String, @StringRes val titleRes: Int) {
    /** Opens the game menu (OSD) at once. */
    PRESS("menu", AppR.string.game_controls_menu),
    /** Held for the long-press time opens the menu; a short press is the key's button. */
    HOLD("menu_hold", AppR.string.game_controls_menu_hold),
    /** Rewinds while held. */
    REWIND("rewind", AppR.string.game_controls_rewind),
    /** Switches fast forward on and off. */
    FAST_FORWARD("fast_forward", AppR.string.game_controls_fast_forward),
}

/** One kind's layout: key codes per RetroPad button plus the menu keys. */
data class RetroInputProfile(
    val buttons: Map<RetroPadButton, List<Int>>,
    val menu: Map<RetroMenuTrigger, List<Int>>,
) {
    private val byKey: Map<Int, RetroPadButton> by lazy {
        buildMap { buttons.forEach { (button, codes) -> codes.forEach { code -> putIfAbsent(code, button) } } }
    }

    fun button(keyCode: Int): RetroPadButton? = byKey[keyCode]
    fun opensMenu(keyCode: Int): Boolean = keyCode in menu[RetroMenuTrigger.PRESS].orEmpty()
    fun holdOpensMenu(keyCode: Int): Boolean = keyCode in menu[RetroMenuTrigger.HOLD].orEmpty()
    fun rewinds(keyCode: Int): Boolean = keyCode in menu[RetroMenuTrigger.REWIND].orEmpty()
    fun fastForwards(keyCode: Int): Boolean = keyCode in menu[RetroMenuTrigger.FAST_FORWARD].orEmpty()
    fun handles(keyCode: Int): Boolean =
        button(keyCode) != null || menu.values.any { keyCode in it }

    /** [keyCode] becomes the only key of [target]; it leaves every other button and menu entry. */
    fun assign(target: RetroInputTarget, keyCode: Int): RetroInputProfile {
        val cleanButtons = buttons.mapValues { (_, codes) -> codes - keyCode }
        val cleanMenu = menu.mapValues { (_, codes) -> codes - keyCode }
        return when (target) {
            is RetroInputTarget.Button -> RetroInputProfile(cleanButtons + (target.button to listOf(keyCode)), cleanMenu)
            is RetroInputTarget.Menu -> RetroInputProfile(cleanButtons, cleanMenu + (target.trigger to listOf(keyCode)))
        }.full()
    }

    fun keys(target: RetroInputTarget): List<Int> = when (target) {
        is RetroInputTarget.Button -> buttons[target.button].orEmpty()
        is RetroInputTarget.Menu -> menu[target.trigger].orEmpty()
    }
}

/** A row of the layout: a RetroPad button or a way to open the game menu. */
sealed interface RetroInputTarget {
    data class Button(val button: RetroPadButton) : RetroInputTarget
    data class Menu(val trigger: RetroMenuTrigger) : RetroInputTarget
}

/**
 * The global layout stays backward compatible. Optional overlays change only assigned targets,
 * so changes to a system's preset are inherited by every core and game unless overridden.
 */
enum class RetroInputLayerScope(val key: String) { SYSTEM("system"), CORE("core"), GAME("game") }

data class RetroInputProfileContext(
    val systemId: String,
    val coreId: String,
    val gameId: String? = null,
) {
    fun layerId(scope: RetroInputLayerScope, kind: RetroInputKind): String? {
        val identity = when (scope) {
            RetroInputLayerScope.SYSTEM -> systemId.takeIf(String::isNotBlank)
            RetroInputLayerScope.CORE -> coreId.takeIf(String::isNotBlank)?.let { "$systemId\u0000$it" }
            RetroInputLayerScope.GAME -> gameId?.takeIf(String::isNotBlank)?.let { "$systemId\u0000$coreId\u0000$it" }
        } ?: return null
        // Safe for SharedPreferences maps, filenames and user-supplied ROM paths.
        val encoded = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(identity.toByteArray(Charsets.UTF_8))
        return "${scope.key}.$encoded.${kind.key}"
    }
}

/** Missing targets inherit the parent; an explicit empty list means unassigned. */
data class RetroInputOverlay(
    val buttons: Map<RetroPadButton, List<Int>> = emptyMap(),
    val menu: Map<RetroMenuTrigger, List<Int>> = emptyMap(),
) {
    fun resolve(parent: RetroInputProfile): RetroInputProfile {
        val resultButtons = parent.buttons.toMutableMap()
        val resultMenu = parent.menu.toMutableMap()
        // Newer layer always wins if the inherited profile later reuses the same physical key.
        // Explicitly empty lists remain valid overrides (unassigned).
        buttons.forEach { (button, codes) ->
            resultButtons.keys.forEach { target ->
                if (target != button) resultButtons[target] = resultButtons[target].orEmpty() - codes.toSet()
            }
            resultMenu.keys.forEach { target ->
                resultMenu[target] = resultMenu[target].orEmpty() - codes.toSet()
            }
            resultButtons[button] = codes
        }
        menu.forEach { (trigger, codes) ->
            resultButtons.keys.forEach { target ->
                resultButtons[target] = resultButtons[target].orEmpty() - codes.toSet()
            }
            resultMenu.keys.forEach { target ->
                if (target != trigger) resultMenu[target] = resultMenu[target].orEmpty() - codes.toSet()
            }
            resultMenu[trigger] = codes
        }
        return RetroInputProfile(resultButtons, resultMenu).full()
    }

    fun isEmpty(): Boolean = buttons.isEmpty() && menu.isEmpty()

    companion object {
        fun difference(parent: RetroInputProfile, updated: RetroInputProfile) = RetroInputOverlay(
            RetroPadButton.entries.mapNotNull { button ->
                updated.buttons[button]?.takeIf { it != parent.buttons[button] }?.let { button to it }
            }.toMap(),
            RetroMenuTrigger.entries.mapNotNull { trigger ->
                updated.menu[trigger]?.takeIf { it != parent.menu[trigger] }?.let { trigger to it }
            }.toMap(),
        )
    }
}

/**
 * Defaults. Controllers by position (Android reports the bottom face button as BUTTON_A on every
 * pad, Nintendo included). Keyboard as in RetroArch. The remote plays with arrows, OK and Back;
 * colour and media keys add the rest, and holding Back opens the game menu.
 */
fun defaultRetroProfile(kind: RetroInputKind): RetroInputProfile = defaultLayout(kind).full()

/** Every button and menu trigger present (empty when unassigned), so layouts compare by content. */
internal fun RetroInputProfile.full() = RetroInputProfile(
    buttons = RetroPadButton.entries.associateWith { buttons[it].orEmpty() },
    menu = RetroMenuTrigger.entries.associateWith { menu[it].orEmpty() },
)

private fun defaultLayout(kind: RetroInputKind): RetroInputProfile = when (kind) {
    RetroInputKind.GAMEPAD -> RetroInputProfile(
        buttons = dpad() + mapOf(
            RetroPadButton.B to listOf(KeyEvent.KEYCODE_BUTTON_A),
            RetroPadButton.A to listOf(KeyEvent.KEYCODE_BUTTON_B),
            RetroPadButton.Y to listOf(KeyEvent.KEYCODE_BUTTON_X),
            RetroPadButton.X to listOf(KeyEvent.KEYCODE_BUTTON_Y),
            RetroPadButton.L to listOf(KeyEvent.KEYCODE_BUTTON_L1),
            RetroPadButton.R to listOf(KeyEvent.KEYCODE_BUTTON_R1),
            RetroPadButton.L2 to listOf(KeyEvent.KEYCODE_BUTTON_L2),
            RetroPadButton.R2 to listOf(KeyEvent.KEYCODE_BUTTON_R2),
            RetroPadButton.L3 to listOf(KeyEvent.KEYCODE_BUTTON_THUMBL),
            RetroPadButton.R3 to listOf(KeyEvent.KEYCODE_BUTTON_THUMBR),
            RetroPadButton.START to listOf(KeyEvent.KEYCODE_BUTTON_START),
            RetroPadButton.SELECT to listOf(KeyEvent.KEYCODE_BUTTON_SELECT),
        ),
        menu = mapOf(RetroMenuTrigger.PRESS to listOf(KeyEvent.KEYCODE_BUTTON_MODE)),
    )
    RetroInputKind.KEYBOARD -> RetroInputProfile(
        buttons = dpad() + mapOf(
            RetroPadButton.A to listOf(KeyEvent.KEYCODE_X),
            RetroPadButton.B to listOf(KeyEvent.KEYCODE_Z),
            RetroPadButton.X to listOf(KeyEvent.KEYCODE_S),
            RetroPadButton.Y to listOf(KeyEvent.KEYCODE_A),
            RetroPadButton.L to listOf(KeyEvent.KEYCODE_Q),
            RetroPadButton.R to listOf(KeyEvent.KEYCODE_W),
            RetroPadButton.L2 to listOf(KeyEvent.KEYCODE_E),
            RetroPadButton.R2 to listOf(KeyEvent.KEYCODE_R),
            RetroPadButton.L3 to listOf(KeyEvent.KEYCODE_D),
            RetroPadButton.R3 to listOf(KeyEvent.KEYCODE_F),
            RetroPadButton.START to listOf(KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER),
            RetroPadButton.SELECT to listOf(KeyEvent.KEYCODE_SHIFT_RIGHT),
        ),
        menu = mapOf(
            RetroMenuTrigger.PRESS to listOf(KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_F1),
            RetroMenuTrigger.REWIND to listOf(KeyEvent.KEYCODE_DEL),
            RetroMenuTrigger.FAST_FORWARD to listOf(KeyEvent.KEYCODE_SPACE),
        ),
    )
    RetroInputKind.REMOTE -> RetroInputProfile(
        buttons = dpad() + mapOf(
            RetroPadButton.A to listOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER),
            RetroPadButton.B to listOf(KeyEvent.KEYCODE_BACK),
            RetroPadButton.Y to listOf(KeyEvent.KEYCODE_PROG_RED),
            RetroPadButton.X to listOf(KeyEvent.KEYCODE_PROG_GREEN),
            RetroPadButton.SELECT to listOf(KeyEvent.KEYCODE_PROG_YELLOW, KeyEvent.KEYCODE_INFO),
            RetroPadButton.START to listOf(KeyEvent.KEYCODE_PROG_BLUE, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE),
            RetroPadButton.L to listOf(KeyEvent.KEYCODE_CHANNEL_DOWN),
            RetroPadButton.R to listOf(KeyEvent.KEYCODE_CHANNEL_UP),
        ),
        menu = mapOf(
            RetroMenuTrigger.PRESS to listOf(KeyEvent.KEYCODE_MENU),
            RetroMenuTrigger.HOLD to listOf(KeyEvent.KEYCODE_BACK),
            RetroMenuTrigger.REWIND to listOf(KeyEvent.KEYCODE_MEDIA_REWIND),
            RetroMenuTrigger.FAST_FORWARD to listOf(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD),
        ),
    )
}

private fun dpad() = mapOf(
    RetroPadButton.UP to listOf(KeyEvent.KEYCODE_DPAD_UP),
    RetroPadButton.DOWN to listOf(KeyEvent.KEYCODE_DPAD_DOWN),
    RetroPadButton.LEFT to listOf(KeyEvent.KEYCODE_DPAD_LEFT),
    RetroPadButton.RIGHT to listOf(KeyEvent.KEYCODE_DPAD_RIGHT),
)

/** Players a device can be given; [AUTO] puts controllers in order and keyboard/remote on player 1. */
object RetroPorts {
    const val COUNT = 4
    const val AUTO = -2
    const val OFF = -1
}

data class RetroInputConfig(
    /** Start + Select together open the game menu on controllers. */
    val startSelectMenu: Boolean = true,
    /** The D-pad also moves the left analog stick (3D games with a remote or keyboard). */
    val dpadAsStick: Boolean = false,
    /** Only changed kinds; the rest come from [defaultRetroProfile]. */
    val profiles: Map<RetroInputKind, RetroInputProfile> = emptyMap(),
    /** Sparse system/core/game overrides, keyed by stable scope identity and input kind. */
    val scopedProfiles: Map<String, RetroInputOverlay> = emptyMap(),
    /** Input device descriptor → player index (0-based) or [RetroPorts.OFF]; missing = automatic. */
    val ports: Map<String, Int> = emptyMap(),
) {
    fun profile(kind: RetroInputKind): RetroInputProfile = profiles[kind] ?: defaultRetroProfile(kind)

    fun profile(kind: RetroInputKind, context: RetroInputProfileContext, through: RetroInputLayerScope? = null): RetroInputProfile {
        var current = profile(kind)
        for (scope in RetroInputLayerScope.entries) {
            if (through != null && scope.ordinal > through.ordinal) break
            context.layerId(scope, kind)?.let { key ->
                scopedProfiles[key]?.let { current = it.resolve(current) }
            }
        }
        return current
    }

    /** Change a single mapping in one scope without freezing unrelated inherited mappings. */
    fun assign(
        kind: RetroInputKind, context: RetroInputProfileContext,
        scope: RetroInputLayerScope, target: RetroInputTarget, keyCode: Int,
    ): RetroInputConfig {
        val key = context.layerId(scope, kind) ?: return this
        val parent = if (scope == RetroInputLayerScope.SYSTEM) profile(kind)
            else profile(kind, context, RetroInputLayerScope.entries[scope.ordinal - 1])
        val updated = profile(kind, context, scope).assign(target, keyCode)
        val overlay = RetroInputOverlay.difference(parent, updated)
        return copy(scopedProfiles = if (overlay.isEmpty()) scopedProfiles - key
            else scopedProfiles + (key to overlay))
    }

    fun resetScope(kind: RetroInputKind, context: RetroInputProfileContext, scope: RetroInputLayerScope): RetroInputConfig =
        context.layerId(scope, kind)?.let { copy(scopedProfiles = scopedProfiles - it) } ?: this

    fun withProfile(kind: RetroInputKind, profile: RetroInputProfile): RetroInputConfig =
        copy(profiles = if (profile == defaultRetroProfile(kind)) profiles - kind else profiles + (kind to profile))

    fun withPort(descriptor: String, port: Int): RetroInputConfig =
        copy(ports = if (port == RetroPorts.AUTO) ports - descriptor else ports + (descriptor to port))

    fun encode(): Map<String, String> = buildMap {
        put("start_select_menu", if (startSelectMenu) "1" else "0")
        put("dpad_stick", if (dpadAsStick) "1" else "0")
        scopedProfiles.forEach { (identity, overlay) ->
            overlay.buttons.forEach { (button, codes) ->
                put("preset.$identity.button.${button.key}", codes.joinToString(","))
            }
            overlay.menu.forEach { (trigger, codes) ->
                put("preset.$identity.menu.${trigger.key}", codes.joinToString(","))
            }
        }
        profiles.forEach { (kind, profile) ->
            RetroPadButton.entries.forEach { button ->
                put("keys.${kind.key}.${button.key}", profile.buttons[button].orEmpty().joinToString(","))
            }
            RetroMenuTrigger.entries.forEach { trigger ->
                put("${trigger.key}.${kind.key}", profile.menu[trigger].orEmpty().joinToString(","))
            }
        }
        ports.forEach { (descriptor, port) -> put("port.$descriptor", if (port == RetroPorts.OFF) "off" else "${port + 1}") }
    }

    companion object {
        private fun codes(value: String?): List<Int>? =
            value?.split(',')?.mapNotNull { it.trim().toIntOrNull()?.takeIf { code -> code > 0 } }

        fun decode(stored: Map<String, String>): RetroInputConfig {
            val profiles = RetroInputKind.entries.mapNotNull { kind ->
                val present = RetroPadButton.entries.any { stored.containsKey("keys.${kind.key}.${it.key}") }
                if (!present) return@mapNotNull null
                val defaults = defaultRetroProfile(kind)
                kind to RetroInputProfile(
                    buttons = RetroPadButton.entries.associateWith { button ->
                        codes(stored["keys.${kind.key}.${button.key}"]) ?: defaults.buttons[button].orEmpty()
                    },
                    menu = RetroMenuTrigger.entries.associateWith { trigger ->
                        codes(stored["${trigger.key}.${kind.key}"]) ?: defaults.menu[trigger].orEmpty()
                    },
                )
            }.toMap()
            val ports = stored.filterKeys { it.startsWith("port.") }.mapNotNull { (key, value) ->
                val port = when (value) {
                    "off" -> RetroPorts.OFF
                    else -> value.toIntOrNull()?.minus(1)?.takeIf { it in 0 until RetroPorts.COUNT }
                } ?: return@mapNotNull null
                key.removePrefix("port.").takeIf(String::isNotEmpty)?.let { it to port }
            }.toMap()
            val layers = mutableMapOf<String, RetroInputOverlay>()
            stored.filterKeys { it.startsWith("preset.") }.forEach { (key, value) ->
                val identityAndTarget = key.removePrefix("preset.").split('.')
                if (identityAndTarget.size != 5 || identityAndTarget[0] !in setOf("system", "core", "game")) return@forEach
                val kind = RetroInputKind.fromKey(identityAndTarget[2]) ?: return@forEach
                val id = "${identityAndTarget[0]}.${identityAndTarget[1]}.${kind.key}"
                val old = layers[id] ?: RetroInputOverlay()
                val decodedCodes = codes(value) ?: return@forEach
                when (identityAndTarget[3]) {
                    "button" -> RetroPadButton.fromKey(identityAndTarget[4])?.let {
                        layers[id] = old.copy(buttons = old.buttons + (it to decodedCodes))
                    }
                    "menu" -> RetroMenuTrigger.entries.firstOrNull { it.key == identityAndTarget[4] }?.let {
                        layers[id] = old.copy(menu = old.menu + (it to decodedCodes))
                    }
                }
            }
            return RetroInputConfig(
                scopedProfiles = layers,
                startSelectMenu = stored["start_select_menu"] != "0",
                dpadAsStick = stored["dpad_stick"] == "1",
                profiles = profiles,
                ports = ports,
            )
        }
    }
}

/** App-wide game input settings; MainActivity attaches storage. Main thread only. */
object RetroInputSettings {
    /** Last active game is session-only; paths and game identity never become synced settings. */
    val lastPlayed = mutableStateOf<RetroInputProfileContext?>(null)
    private val mutable = mutableStateOf(RetroInputConfig())
    val config: RetroInputConfig get() = mutable.value
    private var persist: (Map<String, String>) -> Unit = {}

    fun attach(stored: Map<String, String>, save: (Map<String, String>) -> Unit) {
        persist = save
        mutable.value = RetroInputConfig.decode(stored)
    }

    fun reload(stored: Map<String, String>) { mutable.value = RetroInputConfig.decode(stored) }

    fun update(transform: (RetroInputConfig) -> RetroInputConfig) {
        val value = transform(config)
        if (value == config) return
        mutable.value = value
        persist(value.encode())
    }
}
