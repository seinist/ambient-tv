package com.ambient.tv.ui.settings

import android.view.InputDevice
import android.view.KeyEvent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.ambient.tv.R
import com.ambient.tv.ui.common.SettingsActionRow
import com.ambient.tv.ui.common.SettingsHint
import com.ambient.tv.ui.common.SettingsNavigationRow
import com.ambient.tv.ui.common.TvActionButton
import com.ambient.tv.ui.common.TvSettingRow
import com.ambient.tv.ui.gamepad.GamepadButton
import com.ambient.tv.ui.gamepad.GamepadInput
import com.ambient.tv.ui.gamepad.GamepadLabels
import com.ambient.tv.ui.gamepad.retro.RetroInputDevice
import com.ambient.tv.ui.gamepad.retro.RetroInputKind
import com.ambient.tv.ui.gamepad.retro.RetroInputSettings
import com.ambient.tv.ui.gamepad.retro.RetroInputTarget
import com.ambient.tv.ui.gamepad.retro.RetroMenuCombo
import com.ambient.tv.ui.gamepad.retro.RetroMenuTrigger
import com.ambient.tv.ui.gamepad.retro.RetroPadButton
import com.ambient.tv.ui.gamepad.retro.RetroPorts
import com.ambient.tv.ui.gamepad.retro.playableInputDevices
import com.ambient.tv.ui.gamepad.retro.rememberPlayableDevices
import com.ambient.tv.ui.gamepad.retro.rememberPlayableDeviceAvailability
import com.ambient.tv.ui.keyboard.keyLabel
import com.ambient.tv.ui.theme.LocalUiTokens
import kotlinx.coroutines.delay

/** Settings → App & data → Game controls: who plays as which player, and each kind's key layout. */
internal fun LazyListScope.gameControlsItems() {
    item(key = "game-controls-visual") { VisualGameControls() }
    item(key = "game-controls-players") { GameControlsPlayers() }
    item(key = "game-controls-options") { GameControlsOptions() }
    item(key = "game-controls-touch") { com.ambient.tv.ui.games.GameTouchOptions() }
}

/** Keys Android keeps for itself; they never play. */
private val SYSTEM_KEYS = setOf(
    KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE,
    KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_SLEEP, KeyEvent.KEYCODE_WAKEUP, KeyEvent.KEYCODE_TV_POWER,
    KeyEvent.KEYCODE_ASSIST, KeyEvent.KEYCODE_VOICE_ASSIST, KeyEvent.KEYCODE_APP_SWITCH, KeyEvent.KEYCODE_SETTINGS,
)

private fun playableDevices(): List<RetroInputDevice> = playableInputDevices()

@Composable
private fun GameControlsPlayers() {
    val config = RetroInputSettings.config
    val availability = rememberPlayableDeviceAvailability()
    val devices = availability.devices
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(LocalUiTokens.current.compactGap)) {
        SettingsSubheader(stringResource(R.string.game_controls_players))
        if (devices.isNotEmpty()) SettingsHint(stringResource(R.string.game_controls_explanation))
        if (devices.isEmpty()) SettingsHint(stringResource(R.string.game_controls_no_devices))
        val choices = listOf(RetroPorts.AUTO) + (0 until RetroPorts.COUNT) + RetroPorts.OFF
        devices.forEach { device -> key(device.descriptor) {
            val current = config.ports[device.descriptor] ?: RetroPorts.AUTO
            SettingsSelect(
                label = device.name.ifBlank { stringResource(device.kind.titleRes) },
                description = if (device.kind == RetroInputKind.COMPANION)
                    companionControllerStatus(availability.companion[device.descriptor])
                else stringResource(device.kind.titleRes),
                options = choices.map { port ->
                    SettingsSelectOption(
                        text = when (port) {
                            RetroPorts.AUTO -> stringResource(R.string.game_controls_port_auto)
                            RetroPorts.OFF -> stringResource(R.string.game_controls_port_off)
                            else -> stringResource(R.string.game_controls_port_player, port + 1)
                        },
                        selected = current == port,
                        onClick = { RetroInputSettings.update { it.withPort(device.descriptor, port) } },
                    )
                },
            )
        } }
    }
}

@Composable
private fun GameControlsOptions() {
    val config = RetroInputSettings.config
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(LocalUiTokens.current.compactGap)) {
        SettingsSubheader(stringResource(R.string.gamepad_section_general))
        SettingsSelect(
            label = stringResource(R.string.game_controls_combo),
            description = stringResource(R.string.game_controls_combo_hint),
            options = RetroMenuCombo.entries.map { combo ->
                SettingsSelectOption(
                    text = stringResource(combo.titleRes),
                    selected = config.menuCombo == combo,
                    onClick = { RetroInputSettings.update { it.copy(menuCombo = combo) } },
                )
            },
        )
        TvSettingRow(
            title = stringResource(R.string.game_controls_dpad_stick),
            body = stringResource(R.string.game_controls_dpad_stick_hint),
            checked = config.dpadAsStick,
            onCheckedChange = { on -> RetroInputSettings.update { it.copy(dpadAsStick = on) } },
        )
    }
}

private fun targets(): List<RetroInputTarget> =
    RetroPadButton.entries.map { RetroInputTarget.Button(it) } + RetroMenuTrigger.entries.map { RetroInputTarget.Menu(it) }

@Composable
private fun RetroInputTarget.title(): String = when (this) {
    is RetroInputTarget.Button -> stringResource(button.titleRes)
    is RetroInputTarget.Menu -> stringResource(trigger.titleRes)
}

/** One kind's layout: choose a row, then press the key on that device. */
@Composable
private fun GameControlsLayout(kind: RetroInputKind) {
    val context = LocalContext.current
    val config = RetroInputSettings.config
    val profile = config.profile(kind)
    val labels = remember { GamepadLabels.forVendor(playableDevices().firstOrNull { it.kind == RetroInputKind.GAMEPAD }?.let { InputDevice.getDevice(it.id)?.vendorId }) }
    var listening by remember { mutableStateOf<RetroInputTarget?>(null) }

    val active = listening
    if (active != null) {
        DisposableEffect(active) {
            var downCode = KeyEvent.KEYCODE_UNKNOWN
            GamepadInput.capture = capture@{ event ->
                val code = event.keyCode
                if (code in SYSTEM_KEYS) return@capture false
                when (event.action) {
                    KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0 && downCode == KeyEvent.KEYCODE_UNKNOWN) {
                        downCode = code
                        RetroInputSettings.update { it.withProfile(kind, it.profile(kind).assign(active, code)) }
                    }
                    // Only the release of the recorded key ends listening; others (the OK that opened the row) are dropped.
                    KeyEvent.ACTION_UP -> if (code == downCode) listening = null
                }
                true
            }
            onDispose { GamepadInput.capture = null }
        }
        LaunchedEffect(active) {
            delay(LISTEN_TIMEOUT_MS)
            listening = null
        }
    }

    fun keyName(code: Int): String =
        GamepadButton.fromKeyCode(code)?.let(labels::label) ?: keyLabel(context, code)

    SettingsGroup(
        title = stringResource(kind.titleRes),
        subtitle = stringResource(R.string.game_controls_layout_hint),
        collapsible = true,
    ) {
        targets().forEach { target ->
            val keys = profile.keys(target)
            SettingsNavigationRow(
                title = target.title(),
                summary = when {
                    listening == target -> stringResource(R.string.game_controls_listening)
                    keys.isEmpty() -> stringResource(R.string.key_binding_none)
                    else -> keys.joinToString(" · ", transform = ::keyName)
                },
                icon = if (listening == target) Icons.Filled.SportsEsports else null,
                onClick = { listening = if (listening == target) null else target },
            )
        }
        if (kind in config.profiles) SettingsActionRow {
            TvActionButton(stringResource(R.string.game_controls_reset), onClick = {
                listening = null
                RetroInputSettings.update { it.copy(profiles = it.profiles - kind) }
            })
        }
    }
}

private const val LISTEN_TIMEOUT_MS = 6_000L
