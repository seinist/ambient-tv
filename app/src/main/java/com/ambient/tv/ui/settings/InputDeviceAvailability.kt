package com.ambient.tv.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.ambient.tv.R
import com.ambient.tv.ui.gamepad.retro.CompanionControllerAvailability

/** Remote/keyboard settings remain accessible for TV control and keyboard learning. */
internal fun availablePlayerInputKinds(hasController: Boolean): List<String> =
    if (hasController) listOf("controller", "remote", "keyboard") else listOf("remote", "keyboard")

/** Resolve before rendering so a removed source never leaves one frame of editable phantom controls. */
internal fun availableInputSelection(requested: String, available: List<String>): String? =
    requested.takeIf { it in available } ?: available.firstOrNull()

/** An unplugged per-device editor falls back to another real device, never silently to the global profile. */
internal fun availableControllerDescriptor(requested: String, available: List<String>): String = when {
    requested.isEmpty() -> ""
    requested in available -> requested
    else -> available.firstOrNull().orEmpty()
}

@Composable
internal fun companionControllerStatus(status: CompanionControllerAvailability?): String = when {
    status?.playerPort != null -> stringResource(R.string.input_device_phone_active, status.playerPort + 1)
    status?.connected == true -> stringResource(R.string.input_device_phone_connected)
    else -> stringResource(R.string.input_device_phone_offline)
}
