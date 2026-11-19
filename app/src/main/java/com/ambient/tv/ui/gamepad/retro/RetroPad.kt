package com.ambient.tv.ui.gamepad.retro

import android.view.KeyEvent
import androidx.annotation.StringRes
import androidx.compose.runtime.mutableStateOf
import com.ambient.tv.R

/**
 * The libretro RetroPad (RETRO_DEVICE_JOYPAD). [id] is RETRO_DEVICE_ID_JOYPAD_*; [key] is stored,
 * never rename one. Face buttons follow SNES positions: B bottom, A right, Y left, X top.
 */
enum class RetroPadButton(val key: String, val id: Int, @StringRes val titleRes: Int) {
    UP("up", 4, R.string.retro_button_up),
    DOWN("down", 5, R.string.retro_button_down),
    LEFT("left", 6, R.string.retro_button_left),
    RIGHT("right", 7, R.string.retro_button_right),
    A("a", 8, R.string.retro_button_a),
    B("b", 0, R.string.retro_button_b),
    X("x", 9, R.string.retro_button_x),
    Y("y", 1, R.string.retro_button_y),
    L("l", 10, R.string.retro_button_l),
    R("r", 11, R.string.retro_button_r),
    L2("l2", 12, R.string.retro_button_l2),
    R2("r2", 13, R.string.retro_button_r2),
    L3("l3", 14, R.string.retro_button_l3),
    R3("r3", 15, R.string.retro_button_r3),
    START("start", 3, R.string.retro_button_start),
    SELECT("select", 2, R.string.retro_button_select),
    ;

    val mask: Int get() = 1 shl id

    companion object {
        fun fromKey(key: String): RetroPadButton? = entries.firstOrNull { it.key == key }
    }
}

/** Kinds of input device that can play; each has its own key layout. Stored by [key]. */
enum class RetroInputKind(val key: String, @StringRes val titleRes: Int) {
    GAMEPAD("gamepad", R.string.retro_kind_gamepad),
    KEYBOARD("keyboard", R.string.retro_kind_keyboard),
    REMOTE("remote", R.string.retro_kind_remote),
    ;

    companion object {
        fun fromKey(key: String): RetroInputKind? = entries.firstOrNull { it.key == key }
    }
}

/** What a key can do in a game besides a RetroPad button. */
enum class RetroMenuTrigger(val key: String) {
    /** Opens the game menu (OSD) at once. */
    PRESS("menu"),
    /** Held for the long-press time opens the menu; a short press is the key's button. */
    HOLD("menu_hold"),
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
    fun handles(keyCode: Int): Boolean = button(keyCode) != null || opensMenu(keyCode) || holdOpensMenu(keyCode)

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
        menu = mapOf(RetroMenuTrigger.PRESS to listOf(KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_F1)),
    )
    RetroInputKind.REMOTE -> RetroInputProfile(
        buttons = dpad() + mapOf(
            RetroPadButton.A to listOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER),
            RetroPadButton.B to listOf(KeyEvent.KEYCODE_BACK),
            RetroPadButton.Y to listOf(KeyEvent.KEYCODE_PROG_RED),
            RetroPadButton.X to listOf(KeyEvent.KEYCODE_PROG_GREEN),
            RetroPadButton.SELECT to listOf(KeyEvent.KEYCODE_PROG_YELLOW, KeyEvent.KEYCODE_INFO),
            RetroPadButton.START to listOf(KeyEvent.KEYCODE_PROG_BLUE, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE),
            RetroPadButton.L to listOf(KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_MEDIA_REWIND),
            RetroPadButton.R to listOf(KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD),
        ),
        menu = mapOf(
            RetroMenuTrigger.PRESS to listOf(KeyEvent.KEYCODE_MENU),
            RetroMenuTrigger.HOLD to listOf(KeyEvent.KEYCODE_BACK),
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
    /** Input device descriptor → player index (0-based) or [RetroPorts.OFF]; missing = automatic. */
    val ports: Map<String, Int> = emptyMap(),
) {
    fun profile(kind: RetroInputKind): RetroInputProfile = profiles[kind] ?: defaultRetroProfile(kind)

    fun withProfile(kind: RetroInputKind, profile: RetroInputProfile): RetroInputConfig =
        copy(profiles = if (profile == defaultRetroProfile(kind)) profiles - kind else profiles + (kind to profile))

    fun withPort(descriptor: String, port: Int): RetroInputConfig =
        copy(ports = if (port == RetroPorts.AUTO) ports - descriptor else ports + (descriptor to port))

    fun encode(): Map<String, String> = buildMap {
        put("start_select_menu", if (startSelectMenu) "1" else "0")
        put("dpad_stick", if (dpadAsStick) "1" else "0")
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
            return RetroInputConfig(
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
