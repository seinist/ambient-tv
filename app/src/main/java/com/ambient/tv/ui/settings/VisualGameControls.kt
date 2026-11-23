package com.ambient.tv.ui.settings

import android.view.InputDevice
import android.view.KeyEvent
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.ambient.tv.R
import com.ambient.tv.companion.CompanionViewModel
import com.ambient.tv.games.GameSystems
import com.ambient.tv.ui.common.SettingsActionRow
import com.ambient.tv.ui.common.SettingsHint
import com.ambient.tv.ui.common.SettingsNavigationRow
import com.ambient.tv.ui.common.TvActionButton
import com.ambient.tv.ui.gamepad.GamepadButton
import com.ambient.tv.ui.gamepad.GamepadInput
import com.ambient.tv.ui.gamepad.retro.*
import com.ambient.tv.ui.keyboard.keyLabel
import com.ambient.tv.ui.mobile.LocalHandheldUi
import com.ambient.tv.ui.theme.LocalUiTokens
import kotlinx.coroutines.delay

private val systemOwnedKeys = setOf(
    KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_VOLUME_UP,
    KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE, KeyEvent.KEYCODE_TV_POWER,
    KeyEvent.KEYCODE_SLEEP, KeyEvent.KEYCODE_WAKEUP, KeyEvent.KEYCODE_ASSIST,
    KeyEvent.KEYCODE_VOICE_ASSIST, KeyEvent.KEYCODE_APP_SWITCH,
)

/** Core-aware Libretro input editor. Core device type and button descriptors remain core-owned. */
@Composable
internal fun VisualGameControls() {
    val host = LocalContext.current
    val config = RetroInputSettings.config
    val lastPlayed = RetroInputSettings.lastPlayed.value
    var kindKey by rememberSaveable { mutableStateOf(RetroInputKind.GAMEPAD.key) }
    var systemId by rememberSaveable { mutableStateOf(GameSystems.all.first().id) }
    var coreId by rememberSaveable { mutableStateOf(GameSystems.all.first().core.id) }
    var scopeKey by rememberSaveable { mutableStateOf("default") }
    var selectedDescriptor by rememberSaveable { mutableStateOf("") }
    val consoleVariant = config.touchVariants[systemId].orEmpty()
    var targetKey by rememberSaveable { mutableStateOf("b") }
    var physicalKey by rememberSaveable { mutableStateOf<String?>(null) }
    var menuTarget by remember { mutableStateOf<RetroMenuTrigger?>(null) }
    var listening by remember { mutableStateOf(false) }
    var captureDescriptor by remember { mutableStateOf<String?>(null) }
    var captureDeviceId by remember { mutableIntStateOf(-1) }
    // "Assign all": the console buttons still to record, walked through one after another.
    var assignAll by remember { mutableStateOf<List<RetroPadButton>?>(null) }
    var assignAllMisses by remember { mutableIntStateOf(0) }

    val availability = rememberPlayableDeviceAvailability()
    val devices = availability.devices
    val availableKinds = availableGameInputKinds(devices)
    val availableKindKey = availableInputSelection(kindKey, availableKinds.map { it.key })
    val kind = RetroInputKind.fromKey(availableKindKey.orEmpty()) ?: RetroInputKind.GAMEPAD
    val system = GameSystems.byId(systemId) ?: GameSystems.all.first()
    val core = system.core(coreId) ?: system.core
    val last = lastPlayed?.takeIf { it.systemId == system.id && it.coreId == core.id }
    val ctx = RetroInputProfileContext(system.id, core.id, last?.gameId)
    val compatibleDevices = devices.filter { it.kind == kind }
    val device = compatibleDevices.firstOrNull { it.descriptor == selectedDescriptor }
        ?: compatibleDevices.firstOrNull()
    val deviceScope = scopeKey == "device" && device != null
    val level = RetroInputLayerScope.entries.firstOrNull { it.key == scopeKey }
    val profile = when {
        deviceScope -> config.deviceProfile(kind, device.descriptor)
        level == null -> config.profile(kind)
        else -> config.profile(kind, ctx, level,
            deviceDescriptor = device?.descriptor.takeIf { kind == RetroInputKind.COMPANION })
    }
    val target: RetroInputTarget = menuTarget?.let { RetroInputTarget.Menu(it) }
        ?: RetroInputTarget.Button(RetroPadButton.fromKey(targetKey) ?: RetroPadButton.B)
    val physicalController = device?.takeIf { it.kind == RetroInputKind.GAMEPAD }?.let { selected ->
        InputDevice.getDevice(selected.id)?.takeIf { it.descriptor == selected.descriptor }
    }
    val controllerModel = GamepadInput.config.controllerModel(physicalController)
    val physicalArt = attachedArtwork(kind.key, controllerModel.labels, controllerModel = controllerModel)
    val variants = consoleArtworkVariants(system.id)
    val effectiveVariant = variants.firstOrNull { it.first == consoleVariant }?.first ?: variants.firstOrNull()?.first.orEmpty()
    val consoleArt = consoleArtwork(system.id, effectiveVariant)
    val defaults = remember(kind) { defaultRetroProfile(kind) }
    fun keyName(code: Int): String =
        if (kind == RetroInputKind.COMPANION)
            companionControllerButton(code)?.let { host.getString(it.titleRes) } ?: keyLabel(host, code)
        else GamepadButton.fromKeyCode(code)?.let(controllerModel::label) ?: keyLabel(host, code)
    val consoleMap = consoleLegend(consoleArt, profile, defaults) { code ->
        if (kind != RetroInputKind.COMPANION) compactKeyName(code, ::keyName)
        else companionControllerButton(code)?.let { button ->
            when (button) {
                RetroPadButton.UP -> "▲"
                RetroPadButton.DOWN -> "▼"
                RetroPadButton.LEFT -> "◀"
                RetroPadButton.RIGHT -> "▶"
                else -> button.key.uppercase()
            }
        } ?: keyName(code)
    }
    val deviceMap = deviceLegend(physicalArt, profile, defaults, { visualKeyCode(kind.key, it) }) { button ->
        consoleArt.keys.firstOrNull { it.target == button }?.label ?: host.getString(button.titleRes)
    }
    val handheld = LocalHandheldUi.current
    val companion: CompanionViewModel = hiltViewModel()
    val fallbackFocus = remember { FocusRequester() }
    var editorFocused by remember { mutableStateOf(false) }
    var previousDescriptor by remember { mutableStateOf(device?.descriptor) }
    var previousDeviceId by remember { mutableIntStateOf(device?.id ?: -1) }
    val removedSelection = previousDescriptor != null && devices.none { it.descriptor == previousDescriptor }
    val recoverFocus = editorFocused && removedSelection
    LaunchedEffect(availableKindKey, device?.descriptor, device?.id) {
        if (availableKindKey != null) kindKey = availableKindKey
        // Persist the effective fallback too: reconnecting an old device must not switch the
        // mapping target away from the controller the user has been editing in the meantime.
        selectedDescriptor = device?.descriptor.orEmpty()
        if (previousDescriptor != device?.descriptor || previousDeviceId != (device?.id ?: -1)) {
            listening = false
            captureDescriptor = null
            captureDeviceId = -1
            physicalKey = null
            previousDescriptor = device?.descriptor
            previousDeviceId = device?.id ?: -1
            if (recoverFocus) {
                withFrameNanos { }
                runCatching { fallbackFocus.requestFocus() }
            }
        }
    }

    fun assign(code: Int) {
        if (device == null || code in systemOwnedKeys || code <= KeyEvent.KEYCODE_UNKNOWN) return
        // Input-device callbacks and recomposition are asynchronous. A click/capture from the
        // previous frame must not write a profile after unplugging or reusing an Android ID.
        if (kind != RetroInputKind.COMPANION &&
            InputDevice.getDevice(device.id)?.descriptor != device.descriptor) return
        RetroInputSettings.update { old ->
            if (deviceScope) old.assignDevice(kind, device.descriptor, target, code)
            else if (level == null) old.withProfile(kind, old.profile(kind).assign(target, code))
            else old.assign(kind, ctx, level, target, code)
        }
    }

    /** Moves "assign all" on; false when there is no sequence or it just ended. */
    fun advanceAssignAll(timedOut: Boolean): Boolean {
        val order = assignAll ?: return false
        val current = RetroPadButton.fromKey(targetKey)
        val step = current?.let { nextAssignAllStep(order, it, assignAllMisses, timedOut) }
        if (step == null) {
            assignAll = null
            assignAllMisses = 0
            return false
        }
        assignAllMisses = step.misses
        targetKey = step.button.key
        physicalKey = null
        return true
    }

    LaunchedEffect(listening) {
        // Any other way listening stops (a click, a device change) also ends the sequence.
        if (!listening) {
            assignAll = null
            assignAllMisses = 0
        }
    }

    if (listening && kind != RetroInputKind.COMPANION && device != null &&
        captureDescriptor == device.descriptor && captureDeviceId == device.id) {
        DisposableEffect(kind, ctx, level, device.descriptor, device.id, deviceScope, target) {
            var downCode = KeyEvent.KEYCODE_UNKNOWN
            val listener: (KeyEvent) -> Boolean = capture@{ event ->
                if (event.deviceId != device.id || event.device?.descriptor != device.descriptor) return@capture false
                if (event.keyCode in systemOwnedKeys) return@capture false
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 &&
                    downCode == KeyEvent.KEYCODE_UNKNOWN) {
                    downCode = event.keyCode
                    assign(downCode)
                }
                if (event.action == KeyEvent.ACTION_UP && event.keyCode == downCode &&
                    !advanceAssignAll(timedOut = false)) listening = false
                true
            }
            GamepadInput.capture = listener
            onDispose { if (GamepadInput.capture === listener) GamepadInput.capture = null }
        }
        LaunchedEffect(target, kindKey) {
            delay(6_000)
            if (!advanceAssignAll(timedOut = true)) listening = false
        }
    }

    Column(Modifier.fillMaxWidth().onFocusChanged { editorFocused = it.hasFocus }.focusGroup(),
        verticalArrangement = Arrangement.spacedBy(LocalUiTokens.current.compactGap)) {
        if (availableKinds.isNotEmpty()) SettingsActionRow {
            availableKinds.forEach { option ->
                TvActionButton(stringResource(option.titleRes), selected = option == kind,
                    onClick = { listening = false; kindKey = option.key; physicalKey = null },
                    modifier = if (option == kind) Modifier.focusRequester(fallbackFocus) else Modifier)
            }
        }
        if (device == null) SettingsHint(stringResource(if (handheld)
            R.string.input_device_touch_available else R.string.input_device_no_controller))
        if (!handheld && RetroInputKind.COMPANION !in availableKinds) {
            SettingsActionRow {
                TvActionButton(stringResource(R.string.input_device_connect_phone), companion.manager::showPairing)
            }
        }
        if (compatibleDevices.isNotEmpty()) SettingsSelect(
            label = stringResource(R.string.input_mapping_device),
            options = compatibleDevices.map { option ->
                SettingsSelectOption(
                    text = option.name.ifBlank { option.descriptor },
                    selected = option.descriptor == device?.descriptor,
                    onClick = { listening = false; selectedDescriptor = option.descriptor },
                )
            },
        )
        physicalController?.let { ControllerModelSettings(it) }
        if (device?.kind == RetroInputKind.COMPANION) {
            SettingsHint(companionControllerStatus(availability.companion[device.descriptor]))
        }
        SettingsSelect(
            label = stringResource(R.string.input_mapping_system),
            options = GameSystems.alphabetical.map { option ->
                SettingsSelectOption(option.name, option.id == system.id,
                    onClick = {
                        listening = false; systemId = option.id; coreId = option.core.id
                        scopeKey = "default"; menuTarget = null
                    })
            },
        )
        SettingsSelect(
            label = stringResource(R.string.input_mapping_core),
            options = system.cores.map { option ->
                SettingsSelectOption(option.name, option.id == core.id,
                    onClick = { listening = false; coreId = option.id; scopeKey = "default" })
            },
        )
        if (variants.size > 1) SettingsSelect(
            label = stringResource(R.string.input_mapping_visual_variant),
            description = stringResource(R.string.input_mapping_visual_variant_hint),
            options = variants.map { (id, title) ->
                SettingsSelectOption(title, id == effectiveVariant,
                    onClick = {
                        listening = false
                        RetroInputSettings.update { it.copy(touchVariants = it.touchVariants + (system.id to id)) }
                    })
            },
        )
        val choices: List<Triple<String, RetroInputLayerScope?, Int>> = listOf(
            Triple("default", null, R.string.input_mapping_scope_global),
            Triple("device", null, R.string.input_mapping_scope_device),
            Triple("system", RetroInputLayerScope.SYSTEM, R.string.input_mapping_scope_system),
            Triple("core", RetroInputLayerScope.CORE, R.string.input_mapping_scope_core),
            Triple("game", RetroInputLayerScope.GAME, R.string.input_mapping_scope_game),
        )
        if (device != null) SettingsSelect(
            label = stringResource(R.string.input_mapping_scope),
            options = choices.map { (key, value, title) ->
                SettingsSelectOption(stringResource(title), scopeKey == key,
                    enabled = when (key) {
                        "game" -> last != null
                        else -> true
                    },
                    onClick = { listening = false; scopeKey = key })
            },
        )
        if (last == null) SettingsHint(stringResource(R.string.input_mapping_no_game))
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (device == null) {
                Column {
                    SettingsSubheader(stringResource(R.string.input_mapping_console_preview, consoleArt.title))
                    InputDeviceArtwork(consoleArt, targetKey, { entry ->
                        entry.target?.let { targetKey = it.key; menuTarget = null; listening = false }
                    }, modifier = Modifier.focusRequester(fallbackFocus),
                        assignments = consoleMap.labels, changedKeys = consoleMap.changed)
                }
            } else if (maxWidth >= 650.dp) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(LocalUiTokens.current.itemGap)) {
                    Column(Modifier.weight(1f)) {
                        SettingsSubheader(stringResource(R.string.input_mapping_attached_preview, device.name))
                        InputDeviceArtwork(physicalArt, physicalKey, { entry ->
                            physicalKey = entry.id
                            visualKeyCode(kind.key, entry.id)?.let(::assign)
                        }, assignments = deviceMap.labels, changedKeys = deviceMap.changed)
                    }
                    Column(Modifier.weight(1f)) {
                        SettingsSubheader(stringResource(R.string.input_mapping_console_preview, consoleArt.title))
                        InputDeviceArtwork(consoleArt, targetKey, { entry ->
                            entry.target?.let { targetKey = it.key; menuTarget = null; listening = false }
                        }, assignments = consoleMap.labels, changedKeys = consoleMap.changed)
                    }
                }
            } else {
                Column {
                    SettingsSubheader(stringResource(R.string.input_mapping_console_preview, consoleArt.title))
                    InputDeviceArtwork(consoleArt, targetKey, { entry ->
                        entry.target?.let { targetKey = it.key; menuTarget = null; listening = false }
                    }, assignments = consoleMap.labels, changedKeys = consoleMap.changed)
                    SettingsSubheader(stringResource(R.string.input_mapping_attached_preview, device.name))
                    InputDeviceArtwork(physicalArt, physicalKey, { entry ->
                        physicalKey = entry.id
                        visualKeyCode(kind.key, entry.id)?.let(::assign)
                    }, assignments = deviceMap.labels, changedKeys = deviceMap.changed)
                }
            }
        }
        if (consoleMap.changed.isNotEmpty() || deviceMap.changed.isNotEmpty()) {
            SettingsHint(stringResource(R.string.input_mapping_legend_changed_hint))
        }
        if (device != null) {
            SettingsSelect(
                label = stringResource(R.string.input_mapping_retropad_target),
                options = RetroPadButton.entries.map { button ->
                    SettingsSelectOption(
                        stringResource(button.titleRes),
                        selected = menuTarget == null && button.key == targetKey,
                        onClick = { menuTarget = null; targetKey = button.key; listening = false },
                    )
                },
            )
            SettingsSelect(
                label = stringResource(R.string.game_controls_menu),
                options = (listOf<RetroMenuTrigger?>(null) + RetroMenuTrigger.entries).map { option ->
                    SettingsSelectOption(
                        option?.let { stringResource(it.titleRes) } ?: stringResource(R.string.key_binding_none),
                        selected = option == menuTarget,
                        onClick = { menuTarget = option; listening = false },
                    )
                },
            )
            val name = when (val t = target) {
                is RetroInputTarget.Button -> stringResource(t.button.titleRes)
                is RetroInputTarget.Menu -> stringResource(t.trigger.titleRes)
            }
            val keys = profile.keys(target).joinToString(" · ", transform = ::keyName)
                .ifBlank { stringResource(R.string.key_binding_none) }
            val sequence = assignAll
            val step = sequence?.indexOfFirst { it.key == targetKey }?.takeIf { it >= 0 }
            SettingsNavigationRow(
                title = name,
                summary = when {
                    listening && sequence != null && step != null ->
                        stringResource(R.string.input_mapping_assign_all_step, step + 1, sequence.size)
                    listening -> stringResource(R.string.game_controls_listening)
                    else -> keys
                },
                // The virtual phone sends no Android KeyEvent to capture: choose the desired target
                // above and tap the source key in the interactive device illustration instead.
                onClick = {
                    if (kind != RetroInputKind.COMPANION) {
                        captureDescriptor = device.descriptor
                        captureDeviceId = device.id
                        listening = !listening
                    }
                },
            )
            SettingsHint(stringResource(when {
                kind == RetroInputKind.COMPANION -> R.string.game_controls_companion_mapping_hint
                assignAll != null -> R.string.input_mapping_assign_all_hint
                else -> R.string.input_mapping_capture_hint
            }))
            // Records every console button in turn, highlighted in the console illustration,
            // like the bind-all walkthroughs of RetroArch and Kodi. The phone has no key events.
            if (kind != RetroInputKind.COMPANION) SettingsActionRow {
                val running = assignAll != null
                TvActionButton(
                    stringResource(if (running) R.string.input_mapping_assign_all_stop
                        else R.string.input_mapping_assign_all),
                    selected = running,
                    onClick = {
                        if (running) {
                            listening = false
                        } else {
                            val order = assignAllOrder(consoleArt)
                            menuTarget = null
                            physicalKey = null
                            targetKey = order.first().key
                            assignAllMisses = 0
                            assignAll = order
                            captureDescriptor = device.descriptor
                            captureDeviceId = device.id
                            listening = true
                        }
                    },
                )
            }
            val hasOverride = when {
                deviceScope -> config.deviceProfiles.containsKey(
                    "${kind.key}." + java.util.Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(device.descriptor.toByteArray(Charsets.UTF_8)))
                level == null -> kind in config.profiles
                else -> ctx.layerId(level, kind) in config.scopedProfiles
            }
            if (hasOverride) SettingsActionRow {
                TvActionButton(stringResource(R.string.input_mapping_reset_scope), onClick = {
                    listening = false
                    RetroInputSettings.update {
                        if (deviceScope) it.resetDevice(kind, device.descriptor)
                        else if (level == null) it.copy(profiles = it.profiles - kind)
                        else it.resetScope(kind, ctx, level)
                    }
                })
            }
        }
    }
}
