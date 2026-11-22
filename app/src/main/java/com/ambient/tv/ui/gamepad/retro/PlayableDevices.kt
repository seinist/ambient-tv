package com.ambient.tv.ui.gamepad.retro

import android.hardware.input.InputManager
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.ambient.tv.R
import com.ambient.tv.companion.CompanionViewModel
import com.ambient.tv.companion.COMPANION_HANDHELD
import com.ambient.tv.companion.CompanionGamepadSession
import com.ambient.tv.companion.CompanionPeer

internal data class CompanionControllerAvailability(val connected: Boolean, val playerPort: Int? = null)

internal data class PlayableDeviceAvailability(
    val devices: List<RetroInputDevice>,
    val companion: Map<String, CompanionControllerAvailability>,
)

/** Pairing makes a handset configurable; a running core or a connection is not required. */
internal fun playableDeviceAvailability(
    physicalDevices: List<RetroInputDevice>,
    paired: List<CompanionPeer>,
    connectedFingerprints: Set<String>,
    playerPorts: Map<String, Int>,
    defaultPhoneName: String,
): PlayableDeviceAvailability {
    val phones = paired.filter { it.role == COMPANION_HANDHELD && it.fingerprint.isNotBlank() }
        .distinctBy { it.fingerprint }
    return PlayableDeviceAvailability(
        devices = physicalDevices + phones.map { peer -> RetroInputDevice(
            id = -1, kind = RetroInputKind.COMPANION,
            descriptor = companionControllerDescriptor(peer.fingerprint),
            name = peer.name.ifBlank { defaultPhoneName },
        ) },
        companion = phones.associate { peer ->
            val connected = peer.fingerprint in connectedFingerprints
            companionControllerDescriptor(peer.fingerprint) to CompanionControllerAvailability(
                connected = connected,
                playerPort = playerPorts[peer.fingerprint]?.takeIf { connected && it in 0 until RetroPorts.COUNT },
            )
        },
    )
}

internal fun availableGameInputKinds(devices: List<RetroInputDevice>): List<RetroInputKind> =
    RetroInputKind.entries.filter { kind -> devices.any { it.kind == kind } }

/** Device changes are reflected without recreating the Settings view. */
@Composable
internal fun rememberPlayableDeviceAvailability(): PlayableDeviceAvailability {
    val context = LocalContext.current
    val companion: CompanionViewModel = hiltViewModel()
    val companionState by companion.manager.state.collectAsState()
    val playerPorts by CompanionGamepadSession.playerPorts.collectAsState()
    var devices by remember { mutableStateOf(playableInputDevices()) }
    DisposableEffect(context) {
        val manager = context.getSystemService(InputManager::class.java)
        val listener = object : InputManager.InputDeviceListener {
            override fun onInputDeviceAdded(deviceId: Int) { devices = playableInputDevices() }
            override fun onInputDeviceRemoved(deviceId: Int) { devices = playableInputDevices() }
            override fun onInputDeviceChanged(deviceId: Int) { devices = playableInputDevices() }
        }
        manager?.registerInputDeviceListener(listener, null)
        devices = playableInputDevices()
        onDispose { manager?.unregisterInputDeviceListener(listener) }
    }
    return remember(devices, companionState.paired, companionState.connectedSessions, playerPorts, context) {
        playableDeviceAvailability(devices, companionState.paired,
            companionState.connectedSessions.values.map { it.fingerprint }.toSet(),
            playerPorts, context.getString(R.string.retro_kind_companion))
    }
}

@Composable
internal fun rememberPlayableDevices(): List<RetroInputDevice> = rememberPlayableDeviceAvailability().devices

