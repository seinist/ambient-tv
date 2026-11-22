package com.ambient.tv.ui.settings

import android.view.InputDevice
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.ambient.tv.R
import com.ambient.tv.ui.gamepad.ControllerModel
import com.ambient.tv.ui.gamepad.GamepadInput
import com.ambient.tv.ui.gamepad.resolveControllerModel

@Composable
private fun controllerModelTitle(model: ControllerModel): String = when (model) {
    ControllerModel.GENERIC -> stringResource(R.string.input_device_model_generic)
    ControllerModel.XBOX -> stringResource(R.string.input_device_model_xbox)
    ControllerModel.PLAYSTATION -> stringResource(R.string.input_device_model_playstation)
    ControllerModel.NINTENDO -> stringResource(R.string.input_device_model_nintendo)
    else -> model.displayName
}

/** A visual override belongs to a real device descriptor and never creates a phantom controller. */
@Composable
internal fun ControllerModelSettings(device: InputDevice) {
    val config = GamepadInput.config
    val configured = config.controllerModels[device.descriptor]
    val detected = resolveControllerModel(device)
    SettingsSelect(
        label = stringResource(R.string.input_device_model),
        description = stringResource(R.string.input_device_model_hint, device.name),
        options = listOf(
            SettingsSelectOption(
                stringResource(R.string.input_device_model_auto, controllerModelTitle(detected)),
                configured == null,
                onClick = { GamepadInput.update { it.withControllerModel(device.descriptor, null) } },
            ),
        ) + ControllerModel.entries.map { model ->
            SettingsSelectOption(controllerModelTitle(model), configured == model,
                onClick = { GamepadInput.update { it.withControllerModel(device.descriptor, model) } })
        },
    )
}
