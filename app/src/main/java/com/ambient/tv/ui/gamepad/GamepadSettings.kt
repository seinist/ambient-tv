package com.ambient.tv.ui.gamepad

import androidx.annotation.StringRes
import androidx.compose.runtime.mutableStateOf
import com.ambient.tv.R
import com.ambient.tv.ui.keyboard.KeyAction
import com.ambient.tv.ui.keyboard.KeyBindingScope

/** Menus are everywhere outside the fullscreen player; the player layer falls back to menus. */
enum class GamepadContext(val key: String) { MENUS("menus"), PLAYER("player") }

/** One button, pressed briefly or held (same threshold as remote key bindings). */
data class GamepadSlot(val button: GamepadButton, val long: Boolean) {
    internal fun storageKey(context: GamepadContext) =
        "${context.key}.${button.key}" + if (long) ".long" else ""
}

/** How far a stick must lean before it moves the focus. */
enum class GamepadDeadzone(val key: String, val activation: Float, @StringRes val titleRes: Int) {
    SMALL("small", 0.3f, R.string.gamepad_deadzone_small),
    MEDIUM("medium", 0.5f, R.string.gamepad_deadzone_medium),
    LARGE("large", 0.7f, R.string.gamepad_deadzone_large),
    ;

    /** Lower release point, so a stick resting near the edge does not flicker. */
    val release: Float get() = activation * 0.7f

    companion object {
        fun fromKey(key: String?) = entries.firstOrNull { it.key == key }
    }
}

/** Repeat timing while a stick stays leaned; it speeds up after [ACCELERATE_AFTER_MS]. */
enum class GamepadRepeatSpeed(
    val key: String,
    val initialDelayMs: Long,
    val intervalMs: Long,
    val fastIntervalMs: Long,
    @StringRes val titleRes: Int,
) {
    SLOW("slow", 500, 200, 120, R.string.gamepad_repeat_slow),
    NORMAL("normal", 400, 140, 70, R.string.gamepad_repeat_normal),
    FAST("fast", 300, 90, 45, R.string.gamepad_repeat_fast),
    ;

    companion object {
        const val ACCELERATE_AFTER_MS = 1_500L
        fun fromKey(key: String?) = entries.firstOrNull { it.key == key }
    }
}

data class GamepadConfig(
    val enabled: Boolean = true,
    /** Nintendo-style: B confirms and A goes back (only changes the defaults of A and B). */
    val confirmOnB: Boolean = false,
    val leftStickNavigates: Boolean = true,
    /** Right stick up/down pages through lists; left/right winds in the player. */
    val rightStickScrolls: Boolean = true,
    val deadzone: GamepadDeadzone = GamepadDeadzone.MEDIUM,
    val repeat: GamepadRepeatSpeed = GamepadRepeatSpeed.NORMAL,
    /** Only what the user changed; everything else comes from [gamepadDefault]. */
    val overrides: Map<GamepadContext, Map<GamepadSlot, GamepadCommand>> = emptyMap(),
) {
    /** The setting shown for [slot] in [context]: the player layer may show [GamepadCommand.Inherit]. */
    fun configured(context: GamepadContext, slot: GamepadSlot): GamepadCommand =
        overrides[context]?.get(slot) ?: gamepadDefault(context, slot, confirmOnB)

    /** What a press does right now; never [GamepadCommand.Inherit]. */
    fun resolve(button: GamepadButton, long: Boolean, player: Boolean): GamepadCommand {
        val slot = GamepadSlot(button, long)
        if (player) {
            val own = configured(GamepadContext.PLAYER, slot)
            if (own != GamepadCommand.Inherit) return own
        }
        return configured(GamepadContext.MENUS, slot).takeUnless { it == GamepadCommand.Inherit } ?: GamepadCommand.None
    }

    /** True when holding [button] does something different from a short press. */
    fun hasLongPress(button: GamepadButton, player: Boolean): Boolean =
        resolve(button, long = true, player = player) != GamepadCommand.None

    fun withCommand(context: GamepadContext, slot: GamepadSlot, command: GamepadCommand): GamepadConfig {
        val layer = overrides[context].orEmpty()
        val updated = if (command == gamepadDefault(context, slot, confirmOnB)) layer - slot else layer + (slot to command)
        val others = overrides - context
        return copy(overrides = if (updated.isEmpty()) others else others + (context to updated))
    }

    /** Takes over controller buttons from old remote bindings without replacing newer choices. */
    fun withLegacy(moved: Map<GamepadContext, Map<GamepadSlot, GamepadCommand>>): GamepadConfig =
        moved.entries.fold(this) { acc, (context, layer) ->
            layer.entries.fold(acc) { inner, (slot, command) ->
                if (inner.overrides[context]?.containsKey(slot) == true) inner
                else inner.withCommand(context, slot, command)
            }
        }

    fun encode(): Map<String, String> = buildMap {
        put("enabled", enabled.flag())
        put("confirm", if (confirmOnB) "b" else "a")
        put("left_stick", leftStickNavigates.flag())
        put("right_stick", rightStickScrolls.flag())
        put("deadzone", deadzone.key)
        put("repeat", repeat.key)
        overrides.forEach { (context, layer) ->
            layer.forEach { (slot, command) -> put(slot.storageKey(context), command.storageKey) }
        }
    }

    companion object {
        private fun Boolean.flag() = if (this) "1" else "0"

        fun decode(stored: Map<String, String>): GamepadConfig {
            val base = GamepadConfig(
                enabled = stored["enabled"] != "0",
                confirmOnB = stored["confirm"] == "b",
                leftStickNavigates = stored["left_stick"] != "0",
                rightStickScrolls = stored["right_stick"] != "0",
                deadzone = GamepadDeadzone.fromKey(stored["deadzone"]) ?: GamepadDeadzone.MEDIUM,
                repeat = GamepadRepeatSpeed.fromKey(stored["repeat"]) ?: GamepadRepeatSpeed.NORMAL,
            )
            val overrides = GamepadContext.entries.associateWith { context ->
                buildMap<GamepadSlot, GamepadCommand> {
                    GamepadButton.entries.forEach { button ->
                        listOf(false, true).forEach { long ->
                            val slot = GamepadSlot(button, long)
                            val command = stored[slot.storageKey(context)]?.let(GamepadCommand::decode) ?: return@forEach
                            // Menus have no "as in menus"; an unknown value keeps the default.
                            if (context == GamepadContext.MENUS && command == GamepadCommand.Inherit) return@forEach
                            put(slot, command)
                        }
                    }
                }
            }.filterValues { it.isNotEmpty() }
            return base.copy(overrides = overrides)
        }
    }
}

/** Defaults: console conventions for menus, media keys in the player; A and B as in menus. */
internal fun gamepadDefault(context: GamepadContext, slot: GamepadSlot, confirmOnB: Boolean): GamepadCommand {
    val (button, long) = slot
    return when (context) {
        GamepadContext.MENUS -> if (long) when (button) {
            GamepadButton.START -> GamepadCommand.Action(KeyAction.SETTINGS)
            else -> GamepadCommand.None
        } else when (button) {
            GamepadButton.A -> key(if (confirmOnB) GamepadKeyCommand.BACK else GamepadKeyCommand.CONFIRM)
            GamepadButton.B -> key(if (confirmOnB) GamepadKeyCommand.CONFIRM else GamepadKeyCommand.BACK)
            GamepadButton.X -> key(GamepadKeyCommand.CONTEXT_MENU)
            GamepadButton.Y -> GamepadCommand.Action(KeyAction.SEARCH)
            GamepadButton.L1 -> key(GamepadKeyCommand.PAGE_UP)
            GamepadButton.R1 -> key(GamepadKeyCommand.PAGE_DOWN)
            GamepadButton.START -> GamepadCommand.Action(KeyAction.SIDEBAR)
            GamepadButton.SELECT -> GamepadCommand.Action(KeyAction.VIEW_OPTIONS)
            GamepadButton.MODE -> GamepadCommand.Action(KeyAction.START_PAGE)
            GamepadButton.L2, GamepadButton.R2, GamepadButton.THUMB_L, GamepadButton.THUMB_R -> GamepadCommand.None
        }
        // Page navigation (start page, settings, search …) is not available in the fullscreen
        // player, so the player does not inherit long presses or the Home button.
        GamepadContext.PLAYER -> if (long) when (button) {
            GamepadButton.Y -> GamepadCommand.Action(KeyAction.AUDIO_TRACKS)
            else -> GamepadCommand.None
        } else when (button) {
            GamepadButton.X -> key(GamepadKeyCommand.PLAY_PAUSE)
            GamepadButton.Y -> GamepadCommand.Action(KeyAction.SUBTITLES)
            GamepadButton.L1 -> key(GamepadKeyCommand.PREVIOUS)
            GamepadButton.R1 -> key(GamepadKeyCommand.NEXT)
            GamepadButton.L2 -> key(GamepadKeyCommand.REWIND)
            GamepadButton.R2 -> key(GamepadKeyCommand.FAST_FORWARD)
            GamepadButton.THUMB_L -> GamepadCommand.Action(KeyAction.DETAILS)
            GamepadButton.THUMB_R -> GamepadCommand.Action(KeyAction.CHAPTERS)
            GamepadButton.START -> GamepadCommand.Action(KeyAction.SHOW_OSD)
            GamepadButton.SELECT -> GamepadCommand.Action(KeyAction.PLAYBACK_SETTINGS)
            GamepadButton.MODE -> GamepadCommand.None
            GamepadButton.A, GamepadButton.B -> GamepadCommand.Inherit
        }
    }
}

private fun key(command: GamepadKeyCommand) = GamepadCommand.Key(command)

/** Commands offered for [context] in Settings, in display order. */
fun gamepadCommandChoices(context: GamepadContext): List<GamepadCommand> = buildList {
    if (context == GamepadContext.PLAYER) add(GamepadCommand.Inherit)
    add(GamepadCommand.None)
    GamepadKeyCommand.entries.forEach { add(GamepadCommand.Key(it)) }
    GamepadVolumeCommand.entries.forEach { add(GamepadCommand.Volume(it)) }
    // Player actions only exist while the player is in front.
    KeyAction.entries.filter { it.scope == KeyBindingScope.GLOBAL || context == GamepadContext.PLAYER }
        .forEach { add(GamepadCommand.Action(it)) }
}

/**
 * Remote key bindings stored before controllers had their own page may hold controller buttons.
 * Returns the remaining remote bindings and those buttons as controller overrides.
 */
fun splitLegacyGamepadBindings(
    stored: Map<String, String>,
): Pair<Map<String, String>, Map<GamepadContext, Map<GamepadSlot, GamepadCommand>>> {
    val remote = mutableMapOf<String, String>()
    val moved = mutableMapOf<GamepadContext, MutableMap<GamepadSlot, GamepadCommand>>()
    stored.forEach { (actionKey, value) ->
        val code = value.substringBefore(':').toIntOrNull()
        val button = code?.let(GamepadButton::fromKeyCode)
        val action = KeyAction.fromKey(actionKey)
        if (button == null || action == null) {
            // Generic BUTTON_1…16 have no row on the controller page; they are dropped with them.
            if (code == null || !isGamepadButtonKeyCode(code)) remote[actionKey] = value
            return@forEach
        }
        val context = if (action.scope == KeyBindingScope.PLAYER) GamepadContext.PLAYER else GamepadContext.MENUS
        val slot = GamepadSlot(button, long = value.substringAfter(':', "0") == "1")
        moved.getOrPut(context) { mutableMapOf() }[slot] = GamepadCommand.Action(action)
    }
    return remote to moved
}

/** App-wide controller settings; MainActivity attaches storage. Main thread only. */
object GamepadInput {
    private val mutable = mutableStateOf(GamepadConfig())
    val config: GamepadConfig get() = mutable.value
    private var persist: (Map<String, String>) -> Unit = {}

    /** The last controller button pressed, for the button test on the settings page. */
    val lastButton = mutableStateOf<GamepadButton?>(null)

    /**
     * A game in front takes controller input before any translation. Returning false lets the
     * event take its normal way (e.g. while the game's OSD is open).
     */
    var rawSink: ((android.view.InputEvent) -> Boolean)? = null

    /** While Settings records a key for a game layout it receives every key first, untranslated. */
    var capture: ((android.view.KeyEvent) -> Boolean)? = null

    fun attach(stored: Map<String, String>, save: (Map<String, String>) -> Unit) {
        persist = save
        mutable.value = GamepadConfig.decode(stored)
    }

    fun reload(stored: Map<String, String>) { mutable.value = GamepadConfig.decode(stored) }

    fun encoded(): Map<String, String> = config.encode()

    fun update(transform: (GamepadConfig) -> GamepadConfig) {
        val value = transform(config)
        if (value == config) return
        mutable.value = value
        persist(value.encode())
    }

    fun setCommand(context: GamepadContext, slot: GamepadSlot, command: GamepadCommand) =
        update { it.withCommand(context, slot, command) }

    fun resetMappings() = update { it.copy(overrides = emptyMap()) }

    fun importLegacy(moved: Map<GamepadContext, Map<GamepadSlot, GamepadCommand>>) {
        if (moved.isNotEmpty()) update { it.withLegacy(moved) }
    }
}
