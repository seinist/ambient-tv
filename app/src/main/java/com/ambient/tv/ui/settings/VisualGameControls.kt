package com.ambient.tv.ui.settings

import android.view.InputDevice
import android.hardware.input.InputManager
import android.view.KeyEvent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ambient.tv.R
import com.ambient.tv.games.GameSystems
import com.ambient.tv.ui.common.SettingsActionRow
import com.ambient.tv.ui.common.SettingsHint
import com.ambient.tv.ui.common.SettingsNavigationRow
import com.ambient.tv.ui.common.TvActionButton
import com.ambient.tv.ui.gamepad.GamepadButton
import com.ambient.tv.ui.gamepad.GamepadInput
import com.ambient.tv.ui.gamepad.GamepadLabels
import com.ambient.tv.ui.gamepad.retro.*
import com.ambient.tv.ui.keyboard.keyLabel
import com.ambient.tv.ui.theme.LocalUiTokens
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.ambient.tv.companion.CompanionViewModel
import com.ambient.tv.companion.COMPANION_HANDHELD
import kotlinx.coroutines.delay

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
    val phones = companionState.paired.filter { it.role == COMPANION_HANDHELD }
        .distinctBy { it.fingerprint }
        .map { peer -> RetroInputDevice(
            id = -1, kind = RetroInputKind.COMPANION,
            descriptor = companionControllerDescriptor(peer.fingerprint),
            name = peer.name.ifBlank { context.getString(R.string.retro_kind_companion) },
        ) }
    return devices + phones
}

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
    var consoleVariant by rememberSaveable { mutableStateOf("") }
    var targetKey by rememberSaveable { mutableStateOf("b") }
    var physicalKey by rememberSaveable { mutableStateOf<String?>(null) }
    var menuTarget by remember { mutableStateOf<RetroMenuTrigger?>(null) }
    var listening by remember { mutableStateOf(false) }

    val kind = RetroInputKind.fromKey(kindKey) ?: RetroInputKind.GAMEPAD
    val system = GameSystems.byId(systemId) ?: GameSystems.all.first()
    val core = system.core(coreId) ?: system.core
    val last = lastPlayed?.takeIf { it.systemId == system.id && it.coreId == core.id }
    val ctx = RetroInputProfileContext(system.id, core.id, last?.gameId)
    val devices = rememberPlayableDevices()
    val compatibleDevices = devices.filter { it.kind == kind }
    val device = compatibleDevices.firstOrNull { it.descriptor == selectedDescriptor }
        ?: compatibleDevices.firstOrNull()
    val deviceScope = scopeKey == "device" && device != null
    val level = RetroInputLayerScope.entries.firstOrNull { it.key == scopeKey }
    val profile = when {
        deviceScope -> config.deviceProfile(kind, device!!.descriptor)
        level == null -> config.profile(kind)
        else -> config.profile(kind, ctx, level,
            deviceDescriptor = device?.descriptor.takeIf { kind == RetroInputKind.COMPANION })
    }
    val target: RetroInputTarget = menuTarget?.let { RetroInputTarget.Menu(it) }
        ?: RetroInputTarget.Button(RetroPadButton.fromKey(targetKey) ?: RetroPadButton.B)
    val labels = GamepadLabels.forVendor(device?.let { InputDevice.getDevice(it.id)?.vendorId })
    val physicalArt = attachedArtwork(kindKey, labels)
    val variants = consoleArtworkVariants(system.id)
    val effectiveVariant = variants.firstOrNull { it.first == consoleVariant }?.first ?: variants.firstOrNull()?.first.orEmpty()
    val consoleArt = consoleArtwork(system.id, effectiveVariant)

    fun assign(code: Int) {
        if (code in systemOwnedKeys || code <= KeyEvent.KEYCODE_UNKNOWN) return
        RetroInputSettings.update { old ->
            if (deviceScope) old.assignDevice(kind, device!!.descriptor, target, code)
            else if (level == null) old.withProfile(kind, old.profile(kind).assign(target, code))
            else old.assign(kind, ctx, level, target, code)
        }
    }

    if (listening && kind != RetroInputKind.COMPANION) {
        DisposableEffect(kind, ctx, level, device?.descriptor, deviceScope, target) {
            var downCode = KeyEvent.KEYCODE_UNKNOWN
            val listener: (KeyEvent) -> Boolean = capture@{ event ->
                if (event.keyCode in systemOwnedKeys) return@capture false
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 &&
                    downCode == KeyEvent.KEYCODE_UNKNOWN) {
                    downCode = event.keyCode
                    assign(downCode)
                }
                if (event.action == KeyEvent.ACTION_UP && event.keyCode == downCode) listening = false
                true
            }
            GamepadInput.capture = listener
            onDispose { if (GamepadInput.capture === listener) GamepadInput.capture = null }
        }
        LaunchedEffect(target, kindKey) {
            delay(6_000)
            listening = false
        }
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(LocalUiTokens.current.compactGap)) {
        SettingsHint(stringResource(R.string.game_controls_explanation))
        SettingsActionRow {
            RetroInputKind.entries.forEach { option ->
                TvActionButton(stringResource(option.titleRes), selected = option == kind,
                    onClick = { listening = false; kindKey = option.key; physicalKey = null })
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
        SettingsSelect(
            label = stringResource(R.string.input_mapping_system),
            options = GameSystems.alphabetical.map { option ->
                SettingsSelectOption(option.name, option.id == system.id,
                    onClick = {
                        listening = false; systemId = option.id; coreId = option.core.id
                        scopeKey = "default"; menuTarget = null; consoleVariant = ""
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
                    onClick = { listening = false; consoleVariant = id })
            },
        )
        val choices: List<Triple<String, RetroInputLayerScope?, Int>> = listOf(
            Triple("default", null, R.string.input_mapping_scope_global),
            Triple("device", null, R.string.input_mapping_scope_device),
            Triple("system", RetroInputLayerScope.SYSTEM, R.string.input_mapping_scope_system),
            Triple("core", RetroInputLayerScope.CORE, R.string.input_mapping_scope_core),
            Triple("game", RetroInputLayerScope.GAME, R.string.input_mapping_scope_game),
        )
        SettingsSelect(
            label = stringResource(R.string.input_mapping_scope),
            options = choices.map { (key, value, title) ->
                SettingsSelectOption(stringResource(title), scopeKey == key,
                    enabled = when (key) {
                        "device" -> device != null
                        "game" -> last != null
                        else -> true
                    },
                    onClick = { listening = false; scopeKey = key })
            },
        )
        if (last == null) SettingsHint(stringResource(R.string.input_mapping_no_game))
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth >= 650.dp) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(LocalUiTokens.current.itemGap)) {
                    Column(Modifier.weight(1f)) {
                        SettingsSubheader(stringResource(R.string.input_mapping_attached_preview, device?.name ?: physicalArt.title))
                        InputDeviceArtwork(physicalArt, physicalKey, { entry ->
                            physicalKey = entry.id
                            visualKeyCode(kindKey, entry.id)?.let(::assign)
                        })
                    }
                    Column(Modifier.weight(1f)) {
                        SettingsSubheader(stringResource(R.string.input_mapping_console_preview, consoleArt.title))
                        InputDeviceArtwork(consoleArt, targetKey, { entry ->
                            entry.target?.let { targetKey = it.key; menuTarget = null; listening = false }
                        })
                    }
                }
            } else {
                Column {
                    SettingsSubheader(stringResource(R.string.input_mapping_console_preview, consoleArt.title))
                    InputDeviceArtwork(consoleArt, targetKey, { entry ->
                        entry.target?.let { targetKey = it.key; menuTarget = null; listening = false }
                    })
                    SettingsSubheader(stringResource(R.string.input_mapping_attached_preview, device?.name ?: physicalArt.title))
                    InputDeviceArtwork(physicalArt, physicalKey, { entry ->
                        physicalKey = entry.id
                        visualKeyCode(kindKey, entry.id)?.let(::assign)
                    })
                }
            }
        }
        SettingsHint(stringResource(R.string.input_mapping_keep_core_default))
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
        val keys = profile.keys(target).joinToString(" · ") { code ->
            if (kind == RetroInputKind.COMPANION)
                companionControllerButton(code)?.let { host.getString(it.titleRes) } ?: keyLabel(host, code)
            else GamepadButton.fromKeyCode(code)?.let(labels::label) ?: keyLabel(host, code)
        }.ifBlank { stringResource(R.string.key_binding_none) }
        SettingsNavigationRow(
            title = name,
            summary = if (listening) stringResource(R.string.game_controls_listening) else keys,
            // The virtual phone sends no Android KeyEvent to capture: choose the desired target
            // above and tap the source key in the interactive device illustration instead.
            onClick = { if (kind != RetroInputKind.COMPANION) listening = !listening },
        )
        SettingsHint(stringResource(if (kind == RetroInputKind.COMPANION)
            R.string.game_controls_companion_mapping_hint else R.string.input_mapping_capture_hint))
        val hasOverride = when {
            deviceScope -> config.deviceProfiles.containsKey(
                "${kind.key}." + java.util.Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(device!!.descriptor.toByteArray(Charsets.UTF_8)))
            level == null -> kind in config.profiles
            else -> ctx.layerId(level, kind) in config.scopedProfiles
        }
        if (hasOverride) SettingsActionRow {
            TvActionButton(stringResource(R.string.input_mapping_reset_scope), onClick = {
                listening = false
                RetroInputSettings.update {
                    if (deviceScope) it.resetDevice(kind, device!!.descriptor)
                    else if (level == null) it.copy(profiles = it.profiles - kind)
                    else it.resetScope(kind, ctx, level)
                }
            })
        }
    }
}
