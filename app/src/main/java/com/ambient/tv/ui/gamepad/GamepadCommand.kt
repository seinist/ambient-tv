package com.ambient.tv.ui.gamepad

import android.view.KeyEvent
import androidx.annotation.StringRes
import com.ambient.tv.R
import com.ambient.tv.ui.keyboard.KeyAction

/**
 * Keys a controller button can stand in for. They are sent through the app's normal key path,
 * so every screen reacts exactly as to the remote: held, they repeat like a held remote key.
 * Stored by [key]; never rename one.
 */
enum class GamepadKeyCommand(val key: String, val keyCode: Int, @StringRes val titleRes: Int) {
    CONFIRM("confirm", KeyEvent.KEYCODE_DPAD_CENTER, R.string.gamepad_cmd_confirm),
    BACK("back", KeyEvent.KEYCODE_BACK, R.string.gamepad_cmd_back),
    CONTEXT_MENU("context_menu", KeyEvent.KEYCODE_MENU, R.string.gamepad_cmd_context_menu),
    PAGE_UP("page_up", KeyEvent.KEYCODE_PAGE_UP, R.string.gamepad_cmd_page_up),
    PAGE_DOWN("page_down", KeyEvent.KEYCODE_PAGE_DOWN, R.string.gamepad_cmd_page_down),
    CHANNEL_UP("channel_up", KeyEvent.KEYCODE_CHANNEL_UP, R.string.key_name_channel_up),
    CHANNEL_DOWN("channel_down", KeyEvent.KEYCODE_CHANNEL_DOWN, R.string.key_name_channel_down),
    PLAY_PAUSE("play_pause", KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, R.string.key_action_play_pause),
    STOP("stop", KeyEvent.KEYCODE_MEDIA_STOP, R.string.gamepad_cmd_stop),
    NEXT("next", KeyEvent.KEYCODE_MEDIA_NEXT, R.string.gamepad_cmd_next),
    PREVIOUS("previous", KeyEvent.KEYCODE_MEDIA_PREVIOUS, R.string.gamepad_cmd_previous),
    FAST_FORWARD("fast_forward", KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, R.string.gamepad_cmd_fast_forward),
    REWIND("rewind", KeyEvent.KEYCODE_MEDIA_REWIND, R.string.gamepad_cmd_rewind),
    ;

    companion object {
        fun fromKey(key: String): GamepadKeyCommand? = entries.firstOrNull { it.key == key }
    }
}

/** System volume; Android does not take volume keys the app sends itself. */
enum class GamepadVolumeCommand(val key: String, @StringRes val titleRes: Int) {
    UP("volume_up", R.string.gamepad_cmd_volume_up),
    DOWN("volume_down", R.string.gamepad_cmd_volume_down),
    MUTE("mute", R.string.gamepad_cmd_mute),
    ;

    companion object {
        fun fromKey(key: String): GamepadVolumeCommand? = entries.firstOrNull { it.key == key }
    }
}

/** What a button press does. [Inherit] only exists for the player layer ("as in menus"). */
sealed interface GamepadCommand {
    val storageKey: String

    data object None : GamepadCommand { override val storageKey = "none" }
    data object Inherit : GamepadCommand { override val storageKey = "inherit" }
    data class Key(val command: GamepadKeyCommand) : GamepadCommand {
        override val storageKey get() = "key:${command.key}"
    }
    data class Volume(val command: GamepadVolumeCommand) : GamepadCommand {
        override val storageKey get() = "volume:${command.key}"
    }
    /** One of the app's key-binding actions, performed by the screen in front (see KeyActionHandlers). */
    data class Action(val action: KeyAction) : GamepadCommand {
        override val storageKey get() = "action:${action.key}"
    }

    companion object {
        fun decode(value: String): GamepadCommand? = when {
            value == None.storageKey -> None
            value == Inherit.storageKey -> Inherit
            value.startsWith("key:") -> GamepadKeyCommand.fromKey(value.removePrefix("key:"))?.let(::Key)
            value.startsWith("volume:") -> GamepadVolumeCommand.fromKey(value.removePrefix("volume:"))?.let(::Volume)
            value.startsWith("action:") -> KeyAction.fromKey(value.removePrefix("action:"))?.let(::Action)
            else -> null
        }
    }
}

@StringRes
fun GamepadCommand.titleRes(): Int = when (this) {
    GamepadCommand.None -> R.string.gamepad_cmd_none
    GamepadCommand.Inherit -> R.string.gamepad_cmd_inherit
    is GamepadCommand.Key -> command.titleRes
    is GamepadCommand.Volume -> command.titleRes
    is GamepadCommand.Action -> action.titleRes
}
