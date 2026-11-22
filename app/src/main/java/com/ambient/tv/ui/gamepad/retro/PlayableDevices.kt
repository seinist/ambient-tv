package com.ambient.tv.ui.gamepad.retro

import android.hardware.input.InputManager
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.ambient.tv.R
import com.ambient.tv.companion.CompanionViewModel
import com.ambient.tv.companion.COMPANION_HANDHELD

/** Device changes are reflected without recreating the Settings view. */
@Composable
internal fun rememberPlayableDevices(): List<RetroInputDevice> {
    val context = LocalContext.current
    val companion: CompanionViewModel = hiltViewModel()
    val companionState by companion.manager.state.collectAsState()
    var devices by remember { mutableStateOf(playableInputDevices()) }
    DisposableEffect(context) {
        val manager = context.getSystemService(InputManager::class.java)
        val listener = object : InputManager.InputDeviceListener {
            override fun onInputDeviceAdded(deviceId: Int) { devices = playableInputDevices() }
            override fun onInputDeviceRemoved(deviceId: Int) { devices = playableInputDevices() }
            override fun onInputDeviceChanged(deviceId: Int) { devices = playableInputDevices() }
        }
        manager?.registerInputDeviceListener(listener, null)
        onDispose { manager?.unregisterInputDeviceListener(listener) }
    }
    // Show trusted paired handsets even when offline, so button/port profiles can be prepared
    // before starting a game. The pinned identity stays stable across reconnections/renames.
    val phones = remember(companionState.paired, context) { companionState.paired.filter { it.role == COMPANION_HANDHELD }
        .distinctBy { it.fingerprint }
        .map { peer -> RetroInputDevice(
            id = -1, kind = RetroInputKind.COMPANION,
            descriptor = companionControllerDescriptor(peer.fingerprint),
            name = peer.name.ifBlank { context.getString(R.string.retro_kind_companion) },
        ) } }
    return remember(devices, phones) { devices + phones }
}

