package com.ambient.tv.ui.settings

import android.view.KeyEvent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.ambient.tv.R
import com.ambient.tv.ui.common.SettingsActionRow
import com.ambient.tv.ui.common.SettingsHint
import com.ambient.tv.ui.common.TvActionButton
import com.ambient.tv.ui.gamepad.GamepadButton
import com.ambient.tv.ui.gamepad.GamepadContext
import com.ambient.tv.ui.gamepad.GamepadInput
import com.ambient.tv.ui.gamepad.GamepadLabels
import com.ambient.tv.ui.keyboard.KeyAction
import com.ambient.tv.ui.keyboard.KeyBinding
import com.ambient.tv.ui.keyboard.KeyBindingScope
import com.ambient.tv.ui.keyboard.KeyBindings
import com.ambient.tv.ui.keyboard.isBindableKey
import com.ambient.tv.ui.keyboard.keyLabel
import com.ambient.tv.ui.theme.LocalUiTokens

/**
 * Separate UI and playback pages, both using the same device vector, material, focus and
 * existing settings selectors. This is deliberately part of the normal SettingsHub page.
 */
internal fun LazyListScope.inputControlsItems(scope: KeyBindingScope) {
    item(key = "input-controls-${scope.name.lowercase()}") { InputControlsPage(scope) }
}

@Composable
private fun InputControlsPage(scope: KeyBindingScope) {
    var kind by rememberSaveable(scope) { mutableStateOf("controller") }
    var selectedKey by rememberSaveable(kind, scope) { mutableStateOf("a") }
    var long by rememberSaveable(kind, scope) { mutableStateOf(false) }
    val controllerContext = if (scope == KeyBindingScope.GLOBAL) GamepadContext.MENUS else GamepadContext.PLAYER
    val controllers = rememberConnectedControllers()
    val labels = GamepadLabels.forVendor(controllers.firstOrNull()?.vendorId)
    val artwork = attachedArtwork(kind, labels)
    val tokens = LocalUiTokens.current

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(tokens.compactGap)) {
        SettingsHint(stringResource(R.string.input_mapping_select_key))
        SettingsActionRow {
            listOf(
                "controller" to R.string.input_mapping_controller,
                "remote" to R.string.input_mapping_remote,
                "keyboard" to R.string.input_mapping_keyboard,
            ).forEach { (id, text) ->
                TvActionButton(
                    text = stringResource(text),
                    selected = kind == id,
                    onClick = { kind = id },
                )
            }
        }
        SettingsSubheader(
            stringResource(R.string.input_mapping_attached_preview, when (kind) {
                "controller" -> controllers.firstOrNull()?.name ?: artwork.title
                else -> artwork.title
            })
        )
        InputDeviceArtwork(
            artwork = artwork,
            selectedKey = selectedKey,
            onSelectKey = { selectedKey = it.id },
        )
        SettingsActionRow {
            TvActionButton(
                stringResource(R.string.input_mapping_short),
                selected = !long,
                onClick = { long = false },
            )
            TvActionButton(
                stringResource(R.string.input_mapping_long),
                selected = long,
                onClick = { long = true },
            )
        }

        if (kind == "controller") {
            val selected = GamepadButton.fromKey(selectedKey)
            if (selected == null) {
                SettingsHint(stringResource(R.string.input_mapping_fixed_key))
            } else {
                SettingsHint(stringResource(R.string.input_mapping_key_selected, labels.label(selected)))
                // Only the selected button is shown; no 4 long unfolded lists.
                ControllerMapping(controllerContext, long, selected)
            }
            if (scope == KeyBindingScope.GLOBAL) {
                ControllerStatus()
                ControllerGeneral()
            }
            ControllerReset(controllerContext)
        } else {
            RemoteKeyboardMapping(scope, kind, selectedKey, long)
            // The original press-to-learn rows are still available for unfamiliar buttons.
            SettingsSubheader(stringResource(R.string.input_mapping_all_actions))
            KeyBindingSettings(scope, kind)
        }
    }
}

internal fun visualKeyCode(kind: String, key: String): Int? {
    if (kind == "controller") return GamepadButton.fromKey(key)?.keyCode ?: when (key) {
        "up" -> KeyEvent.KEYCODE_DPAD_UP
        "down" -> KeyEvent.KEYCODE_DPAD_DOWN
        "left" -> KeyEvent.KEYCODE_DPAD_LEFT
        "right" -> KeyEvent.KEYCODE_DPAD_RIGHT
        else -> null
    }

    if (kind == "keyboard") return when (key) {
        "Esc" -> KeyEvent.KEYCODE_ESCAPE
        "Enter" -> KeyEvent.KEYCODE_ENTER
        "Space" -> KeyEvent.KEYCODE_SPACE
        else -> when {
            key.length == 1 && key[0] in 'A'..'Z' -> KeyEvent.KEYCODE_A + key[0].code - 'A'.code
            key.startsWith("F") && key.drop(1).toIntOrNull() in 1..12 ->
                KeyEvent.KEYCODE_F1 + (key.drop(1).toInt() - 1)
            else -> null
        }
    }
    return when (key) {
        "power" -> KeyEvent.KEYCODE_POWER
        "home" -> KeyEvent.KEYCODE_HOME
        "back" -> KeyEvent.KEYCODE_BACK
        "menu" -> KeyEvent.KEYCODE_MENU
        "up" -> KeyEvent.KEYCODE_DPAD_UP
        "down" -> KeyEvent.KEYCODE_DPAD_DOWN
        "left" -> KeyEvent.KEYCODE_DPAD_LEFT
        "right" -> KeyEvent.KEYCODE_DPAD_RIGHT
        "ok" -> KeyEvent.KEYCODE_DPAD_CENTER
        "play" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        "red" -> KeyEvent.KEYCODE_PROG_RED
        "green" -> KeyEvent.KEYCODE_PROG_GREEN
        "yellow" -> KeyEvent.KEYCODE_PROG_YELLOW
        "blue" -> KeyEvent.KEYCODE_PROG_BLUE
        else -> null
    }
}

@Composable
private fun RemoteKeyboardMapping(scope: KeyBindingScope, kind: String, key: String, long: Boolean) {
    val context = LocalContext.current
    val code = visualKeyCode(kind, key)
    if (code == null || !isBindableKey(code)) {
        SettingsHint(stringResource(R.string.input_mapping_fixed_key))
        return
    }
    val binding = KeyBinding(code, long)
    val selected = KeyBindings.effectiveBindingsFor(kind).entries.firstOrNull {
        it.key.scope == scope && it.value == binding
    }?.key

    SettingsSelect(
        label = stringResource(R.string.input_mapping_key_action, keyLabel(context, code)),
        options = (listOf<KeyAction?>(null) + KeyAction.entries.filter { it.scope == scope }).map { action ->
            SettingsSelectOption(
                text = action?.let { context.getString(it.titleRes) }
                    ?: context.getString(R.string.key_binding_none),
                selected = selected == action,
                onClick = {
                    if (action == null) selected?.let { KeyBindings.clearFor(kind, it) }
                    else KeyBindings.bindFor(kind, action, binding)
                },
            )
        },
    )
}
