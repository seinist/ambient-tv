package com.ambient.tv.ui.settings

import android.hardware.input.InputManager
import android.view.InputDevice
import android.view.KeyEvent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.ambient.tv.R
import com.ambient.tv.ui.common.SettingsActionRow
import com.ambient.tv.ui.common.SettingsHint
import com.ambient.tv.ui.common.TvActionButton
import com.ambient.tv.ui.common.TvSettingRow
import com.ambient.tv.ui.gamepad.GamepadButton
import com.ambient.tv.ui.gamepad.GamepadContext
import com.ambient.tv.ui.gamepad.GamepadDeadzone
import com.ambient.tv.ui.gamepad.GamepadInput
import com.ambient.tv.ui.gamepad.GamepadLabels
import com.ambient.tv.ui.gamepad.GamepadRepeatSpeed
import com.ambient.tv.ui.gamepad.GamepadSlot
import com.ambient.tv.ui.gamepad.gamepadCommandChoices
import com.ambient.tv.ui.gamepad.titleRes
import com.ambient.tv.ui.theme.LocalUiTokens

/** Settings → App & data → Controller: options and the button layout for menus and the player. */
internal fun LazyListScope.controllerSettingsItems() {
    item(key = "controller-status") { ControllerStatus() }
    item(key = "controller-general") { ControllerGeneral() }
    if (!GamepadInput.config.enabled) return
    GamepadContext.entries.forEach { context ->
        listOf(false, true).forEach { long ->
            item(key = "controller-map-${context.key}-$long") { ControllerMapping(context, long) }
        }
    }
    item(key = "controller-reset") { ControllerReset() }
}

/** Real controllers (not remotes that also report a D-pad): face buttons or a stick. */
private fun connectedControllers(): List<InputDevice> = InputDevice.getDeviceIds().toList()
    .mapNotNull(InputDevice::getDevice)
    .filter { device ->
        !device.isVirtual && (
            device.supportsSource(InputDevice.SOURCE_JOYSTICK) ||
                (device.supportsSource(InputDevice.SOURCE_GAMEPAD) && device.hasKeys(KeyEvent.KEYCODE_BUTTON_A).firstOrNull() == true)
            )
    }

/** Connected controllers, updated while the page is shown. */
@Composable
internal fun rememberConnectedControllers(): List<InputDevice> {
    val context = LocalContext.current
    var devices by remember { mutableStateOf(connectedControllers()) }
    DisposableEffect(context) {
        val manager = context.getSystemService(InputManager::class.java)
        val listener = object : InputManager.InputDeviceListener {
            override fun onInputDeviceAdded(deviceId: Int) { devices = connectedControllers() }
            override fun onInputDeviceRemoved(deviceId: Int) { devices = connectedControllers() }
            override fun onInputDeviceChanged(deviceId: Int) { devices = connectedControllers() }
        }
        manager?.registerInputDeviceListener(listener, null)
        onDispose { manager?.unregisterInputDeviceListener(listener) }
    }
    return devices
}

@Composable
private fun controllerLabels(): GamepadLabels =
    GamepadLabels.forVendor(rememberConnectedControllers().firstOrNull()?.vendorId)

@Composable
internal fun ControllerStatus() {
    val devices = rememberConnectedControllers()
    val labels = GamepadLabels.forVendor(devices.firstOrNull()?.vendorId)
    val config = GamepadInput.config
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(LocalUiTokens.current.compactGap)) {
        SettingsHint(stringResource(R.string.gamepad_settings_explanation))
        SettingsHint(
            if (devices.isEmpty()) stringResource(R.string.gamepad_none_connected)
            else stringResource(R.string.gamepad_connected, devices.joinToString(", ") { it.name })
        )
        GamepadInput.lastButton.value?.takeIf { config.enabled }?.let { button ->
            val command = config.resolve(button, long = false, player = false)
            SettingsHint(stringResource(R.string.gamepad_last_button, labels.label(button), stringResource(command.titleRes())))
        }
    }
}

@Composable
internal fun ControllerGeneral() {
    val config = GamepadInput.config
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(LocalUiTokens.current.compactGap)) {
        SettingsSubheader(stringResource(R.string.gamepad_section_general))
        TvSettingRow(
            title = stringResource(R.string.gamepad_enabled),
            body = stringResource(R.string.gamepad_enabled_hint),
            checked = config.enabled,
            onCheckedChange = { enabled -> GamepadInput.update { it.copy(enabled = enabled) } },
        )
        if (!config.enabled) return@Column
        SettingsSelect(
            label = stringResource(R.string.gamepad_confirm_button),
            description = stringResource(R.string.gamepad_confirm_button_hint),
            options = listOf(false, true).map { onB ->
                SettingsSelectOption(
                    text = stringResource(if (onB) R.string.gamepad_confirm_right else R.string.gamepad_confirm_bottom),
                    selected = config.confirmOnB == onB,
                    onClick = { GamepadInput.update { it.copy(confirmOnB = onB) } },
                )
            },
        )
        TvSettingRow(
            title = stringResource(R.string.gamepad_left_stick),
            body = stringResource(R.string.gamepad_left_stick_hint),
            checked = config.leftStickNavigates,
            onCheckedChange = { on -> GamepadInput.update { it.copy(leftStickNavigates = on) } },
        )
        TvSettingRow(
            title = stringResource(R.string.gamepad_right_stick),
            body = stringResource(R.string.gamepad_right_stick_hint),
            checked = config.rightStickScrolls,
            onCheckedChange = { on -> GamepadInput.update { it.copy(rightStickScrolls = on) } },
        )
        SettingsSelect(
            label = stringResource(R.string.gamepad_deadzone),
            description = stringResource(R.string.gamepad_deadzone_hint),
            options = GamepadDeadzone.entries.map { zone ->
                SettingsSelectOption(
                    text = stringResource(zone.titleRes),
                    selected = config.deadzone == zone,
                    onClick = { GamepadInput.update { it.copy(deadzone = zone) } },
                )
            },
        )
        SettingsSelect(
            label = stringResource(R.string.gamepad_repeat),
            description = stringResource(R.string.gamepad_repeat_hint),
            options = GamepadRepeatSpeed.entries.map { speed ->
                SettingsSelectOption(
                    text = stringResource(speed.titleRes),
                    selected = config.repeat == speed,
                    onClick = { GamepadInput.update { it.copy(repeat = speed) } },
                )
            },
        )
    }
}

/** One select per button; the player and long-press layouts start folded. */
@Composable
internal fun ControllerMapping(context: GamepadContext, long: Boolean, selectedButton: GamepadButton? = null) {
    val config = GamepadInput.config
    val labels = controllerLabels()
    val choices = remember(context) { gamepadCommandChoices(context) }
    val title = stringResource(when (context) {
        GamepadContext.MENUS -> if (long) R.string.gamepad_section_menus_long else R.string.gamepad_section_menus
        GamepadContext.PLAYER -> if (long) R.string.gamepad_section_player_long else R.string.gamepad_section_player
    })
    val subtitle = stringResource(when (context) {
        GamepadContext.MENUS -> if (long) R.string.gamepad_section_long_hint else R.string.gamepad_section_menus_hint
        GamepadContext.PLAYER -> if (long) R.string.gamepad_section_long_hint else R.string.gamepad_section_player_hint
    })
    SettingsGroup(
        title = title,
        subtitle = subtitle,
        collapsible = context == GamepadContext.PLAYER || long,
    ) {
        (selectedButton?.let(::listOf) ?: GamepadButton.entries).forEach { button ->
            val slot = GamepadSlot(button, long)
            val current = config.configured(context, slot)
            val name = labels.label(button)
            SettingsSelect(
                label = if (long) stringResource(R.string.key_binding_long, name) else name,
                options = choices.map { command ->
                    SettingsSelectOption(
                        text = stringResource(command.titleRes()),
                        selected = command == current,
                        onClick = { GamepadInput.setCommand(context, slot, command) },
                    )
                },
            )
        }
    }
}

@Composable
internal fun ControllerReset(context: GamepadContext? = null) {
    if (context == null && GamepadInput.config.overrides.isEmpty()) return
    if (context != null && GamepadInput.config.overrides[context].isNullOrEmpty()) return
    SettingsActionRow {
        TvActionButton(stringResource(R.string.gamepad_reset), onClick = {
            if (context == null) GamepadInput.resetMappings()
            else GamepadInput.update { it.copy(overrides = it.overrides - context) }
        })
    }
}
