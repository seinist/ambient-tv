package com.ambient.tv.companion

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.*
import com.ambient.tv.ui.input.pointerIdleVisibility
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ambient.tv.R
import com.ambient.tv.games.GameTouchMode
import com.ambient.tv.ui.gamepad.retro.RetroPadButton
import com.ambient.tv.ui.gamepad.retro.RetroInputSettings
import com.ambient.tv.ui.gamepad.retro.CompanionTouchProfile
import com.ambient.tv.ui.gamepad.retro.companionTouchProfileKey
import com.ambient.tv.ui.games.GameTouchControls
import com.ambient.tv.ui.games.rememberGameButtonHaptic
import com.ambient.tv.ui.games.GameTouchOptions
import com.ambient.tv.ui.games.gameTouchPresentation
import com.ambient.tv.ui.gamepad.GamepadInput
import com.ambient.tv.ui.gamepad.GameInputMode
import com.ambient.tv.ui.common.holdAction
import com.ambient.tv.ui.gamepad.retro.RetroPadInput
import com.ambient.tv.ui.gamepad.retro.RetroPorts
import com.ambient.tv.ui.gamepad.retro.RetroInputProfileContext
import com.ambient.tv.ui.gamepad.retro.isPhysicalGamepadActivity
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** Initial layout follows the console but never changes the TV's authoritative core mapping. */
internal fun companionDefaultTouchMode(systemId: String?): GameTouchMode = when (systemId) {
    "n64", "nds", "wii" -> GameTouchMode.HYBRID
    "psx", "ps2", "psp", "n3ds", "gamecube", "wiiu", "dreamcast", "saturn", "moonlight" -> GameTouchMode.DUAL
    else -> GameTouchMode.DPAD
}

private val COMPANION_DPAD_BUTTONS = setOf(
    RetroPadButton.UP, RetroPadButton.DOWN, RetroPadButton.LEFT, RetroPadButton.RIGHT,
)
private val COMPANION_DPAD_MASK = COMPANION_DPAD_BUTTONS.fold(0) { mask, button -> mask or button.mask }

/** Replace only direction bits; ABXY/shoulder holds are preserved across DPAD drags. */
internal fun companionDpadMask(previous: Int, pressed: Set<RetroPadButton>): Int =
    (previous and COMPANION_DPAD_MASK.inv()) or
        pressed.filter { it in COMPANION_DPAD_BUTTONS }.fold(0) { mask, button -> mask or button.mask }

/** True for transitions that must bypass movement sampling and survive queue pressure. */
internal fun companionPointerEdge(old: CompanionPointerState?, next: CompanionPointerState?): Boolean =
    old?.kind != next?.kind || old?.buttons != next?.buttons ||
        old?.offscreen != next?.offscreen ||
        old?.contacts?.filter { it.pressed }?.map { it.index } !=
        next?.contacts?.filter { it.pressed }?.map { it.index }

/** No Compose invalidation per raw stick/sensor sample. Child controls repaint their own knobs,
 * while the full-page header/layout recomposes only when an actual UI setting changes.
 */
private class CompanionInputFrame {
    // A plain holder prevents full-screen Compose recomposition on each 60Hz stick sample.
    @Volatile var value: CompanionGamepadState = CompanionGamepadState()
}

/** Dedicated phone page, never a dialog: navigation, MiniPlayer and playback bars are absent. */
@Composable
internal fun CompanionGamepadScreen(manager: CompanionManager, modifier: Modifier = Modifier) {
    val state by manager.state.collectAsState()
    val info = state.remote?.gamepadInfo
    val port = state.remote?.gamepadPort ?: -1
    val claimed = state.online && info != null && port in 0..3
    val playing = claimed && info.acceptsGameplay
    val lifecycleOwner = LocalLifecycleOwner.current
    var resumed by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> resumed = true
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> resumed = false
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val transmitting = playing && resumed
    val phoneContext = LocalContext.current
    var rumbleEnabled by rememberSaveable(state.target?.fingerprint) { mutableStateOf(true) }
    val vibrator = remember(phoneContext) {
        if (android.os.Build.VERSION.SDK_INT >= 31)
            phoneContext.getSystemService(android.os.VibratorManager::class.java)?.defaultVibrator
        else @Suppress("DEPRECATION") phoneContext.getSystemService(android.os.Vibrator::class.java)
    }
    val rumble = info?.rumble
    LaunchedEffect(transmitting, rumbleEnabled, info?.sessionId, rumble) {
        if (!transmitting || !rumbleEnabled || rumble == null || info?.systemId != "moonlight") {
            runCatching { vibrator?.cancel() }
        } else {
            val level = (maxOf(rumble.low, rumble.high).coerceIn(0, 65535) * 255L / 65535L).toInt()
            if (level > 0 && vibrator?.hasVibrator() == true)
                runCatching { vibrator.vibrate(android.os.VibrationEffect.createOneShot(160L, level.coerceAtLeast(1))) }
        }
    }
    DisposableEffect(vibrator) { onDispose { runCatching { vibrator?.cancel() } } }
    val navigating = claimed && resumed && info.acceptsNavigation
    val renderedInfo by rememberUpdatedState(info)
    val confirmHold = remember(manager) { CompanionGamepadHold(rewind = false, manager::sendGamepadCommand) }
    val rewindHold = remember(manager) { CompanionGamepadHold(rewind = true, manager::sendGamepadCommand) }
    val touchConfirmOwner = remember { Any() }
    val touchRewindOwner = remember { Any() }
    val hardwareRewindOwner = remember { Any() }
    LaunchedEffect(confirmHold.held) {
        while (confirmHold.held) { delay(500); if (confirmHold.held) confirmHold.renew() }
    }
    LaunchedEffect(rewindHold.held) {
        while (rewindHold.held) { delay(500); if (rewindHold.held) rewindHold.renew() }
    }
    DisposableEffect(lifecycleOwner, confirmHold, rewindHold) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                confirmHold.clear(); rewindHold.clear()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer); confirmHold.clear(); rewindHold.clear() }
    }
    LaunchedEffect(info?.sessionId, info?.contextRevision) {
        confirmHold.clear(); rewindHold.clear()
    }
    LaunchedEffect(navigating, transmitting, info?.rewindAvailable) {
        if (!navigating) confirmHold.clear()
        if (!transmitting || !info.rewindAvailable) rewindHold.clear()
    }
    var touchOptionsOpen by remember { mutableStateOf(false) }
    val touchStyle = RetroInputSettings.config.touchStyle
    val touchVariant = RetroInputSettings.config.touchVariants[info?.systemId].orEmpty()
    val touchPresentation = remember(info?.systemId, touchVariant) { gameTouchPresentation(info?.systemId, touchVariant) }
    val coreActions = remember(info?.sessionId, info?.inputDescriptions, port) {
        com.ambient.tv.games.GameInputDescriptions.forPort(
            com.ambient.tv.games.GameInputDescriptions.normalized(info?.inputDescriptions.orEmpty()), port)
    }
    var hidingGamepadId by remember(info?.sessionId) { mutableStateOf<Int?>(null) }
    val hideTouchForGamepad by rememberUpdatedState(touchStyle.hideOnGamepadInput)
    val touchProfileKey = companionTouchProfileKey(state.target?.fingerprint, info?.systemId)
    val storedTouchProfile = RetroInputSettings.config.companionTouchProfiles[touchProfileKey]
    var modeKey by rememberSaveable(state.target?.fingerprint, info?.systemId) {
        mutableStateOf(storedTouchProfile?.mode ?: companionDefaultTouchMode(info?.systemId).key)
    }
    var inputKind by rememberSaveable(state.target?.fingerprint, info?.systemId) {
        mutableStateOf(storedTouchProfile?.input ?: if (info?.pointerInput == true) "touch" else "pad")
    }
    var pointerRegion by rememberSaveable(state.target?.fingerprint, info?.systemId) {
        mutableStateOf(storedTouchProfile?.region ?: companionDefaultPointerRegion(info?.systemId))
    }
    var motionEnabled by rememberSaveable(state.target?.fingerprint, info?.systemId) {
        mutableStateOf(storedTouchProfile?.motion ?: false)
    }
    LaunchedEffect(touchProfileKey, modeKey, inputKind, pointerRegion, motionEnabled) {
        if (touchProfileKey.isNotEmpty()) {
            val profile = CompanionTouchProfile(modeKey, inputKind, pointerRegion, motionEnabled)
            RetroInputSettings.update { it.withCompanionTouchProfile(touchProfileKey, profile) }
        }
    }
    val mode = GameTouchMode.fromKey(modeKey)
    val padFrame = remember(info?.sessionId) { CompanionInputFrame() }
    val padInputs = remember(info?.sessionId) { CompanionPadInputs() }
    fun publishPad(send: Boolean = true) {
        val next = padFrame.value.copy(buttons = padInputs.buttons, leftX = padInputs.axis(0),
            leftY = padInputs.axis(1), rightX = padInputs.axis(2), rightY = padInputs.axis(3),
            leftTrigger = padInputs.trigger(0), rightTrigger = padInputs.trigger(1))
        if (next != padFrame.value) {
            padFrame.value = next
            if (send) manager.gamepadInput(next)
        }
    }
    val currentPublishPad by rememberUpdatedState<(Boolean) -> Unit>({ publishPad(it) })
    val hardwareInput = remember(info?.sessionId) {
        val handler = Handler(Looper.getMainLooper())
        RetroPadInput(
            profileContext = RetroInputProfileContext(info?.systemId.orEmpty(), info?.coreId.orEmpty()),
            schedule = { delay, task ->
                val runnable = Runnable(task)
                handler.postDelayed(runnable, delay)
                ({ handler.removeCallbacks(runnable) })
            },
            onMenu = { renderedInfo?.takeIf { it.acceptsGameplay }?.let { manager.gamepadMenu(it) } },
            onRewind = { rewindHold.set(hardwareRewindOwner, it, renderedInfo) },
        )
    }
    val currentHardwareInput by rememberUpdatedState(hardwareInput)
    val physicalRouter = remember(manager) {
        val handler = Handler(Looper.getMainLooper())
        CompanionGamepadMenuInput(
            gameInput = { currentHardwareInput.onEvent(it) },
            navigate = { action, session, revision ->
                manager.sendGamepadCommand(CompanionGamepadCommand(session, revision, action))
            },
            resume = { session, revision ->
                manager.sendGamepadCommand(CompanionGamepadCommand(session, revision, "resume"))
            },
            confirm = { device, held, cancelled, session, revision ->
                confirmHold.set(device, held, renderedInfo?.takeIf {
                    it.sessionId == session && it.contextRevision == revision
                }, cancelled)
            },
            scheduler = { delay, task ->
                val runnable = Runnable(task)
                handler.postDelayed(runnable, delay)
                ({ handler.removeCallbacks(runnable) })
            },
            clock = android.os.SystemClock::uptimeMillis,
        )
    }
    val physicalMode = when {
        !claimed || !resumed || touchOptionsOpen -> GameInputMode.BLOCKED
        transmitting -> GameInputMode.RUNNING
        navigating && info.interactionMode == CompanionGamepadInteraction.DIALOG -> GameInputMode.DIALOG
        navigating -> GameInputMode.MENU
        else -> GameInputMode.BLOCKED
    }
    SideEffect { physicalRouter.update(physicalMode, info?.contextRevision ?: 0L, info?.sessionId.orEmpty()) }
    val currentTransmitting by rememberUpdatedState(transmitting)
    DisposableEffect(hardwareInput, physicalRouter, claimed, resumed, touchOptionsOpen) {
        val enabled = claimed && resumed
        hardwareInput.onChanged = {
            var buttons = 0
            for (player in 0 until RetroPorts.COUNT) buttons = buttons or hardwareInput.buttons(player)
            padInputs.hardwareButtons = buttons
            for (axis in 0..3) {
                var value = 0
                for (player in 0 until RetroPorts.COUNT) {
                    val candidate = hardwareInput.analog(player, axis / 2, axis % 2)
                    if (kotlin.math.abs(candidate) > kotlin.math.abs(value)) value = candidate
                }
                padInputs.hardwareAxis(axis, value / 32767f)
            }
            for (trigger in 0..1) {
                var value = 0
                for (player in 0 until RetroPorts.COUNT) value = maxOf(value, hardwareInput.trigger(player, trigger))
                padInputs.hardwareTrigger(trigger, value / 32767f)
            }
            val previous = padFrame.value
            val edge = previous.buttons != padInputs.buttons ||
                (previous.leftX != 0f && padInputs.axis(0) == 0f) ||
                (previous.leftY != 0f && padInputs.axis(1) == 0f) ||
                (previous.rightX != 0f && padInputs.axis(2) == 0f) ||
                (previous.rightY != 0f && padInputs.axis(3) == 0f) ||
                ((previous.leftTrigger ?: 0f) > 0f) != (padInputs.trigger(0) > 0f) ||
                ((previous.rightTrigger ?: 0f) > 0f) != (padInputs.trigger(1) > 0f)
            currentPublishPad(edge && currentTransmitting)
        }
        val sink: (android.view.InputEvent) -> Boolean = { event ->
            val handled = physicalRouter.onEvent(event)
            if (currentTransmitting && handled && hideTouchForGamepad && isPhysicalGamepadActivity(event, hardwareInput)) hidingGamepadId = event.deviceId
            // Observe held input even in the phone's own options dialog, but let that dialog
            // receive its normal UI navigation instead of forwarding it to the TV.
            handled && !touchOptionsOpen
        }
        val removed: (Int) -> Unit = { id ->
            hardwareInput.deviceRemoved(id)
            physicalRouter.deviceRemoved(id)
            if (hidingGamepadId == id) hidingGamepadId = null
        }
        if (enabled) {
            GamepadInput.rawSink = sink
            GamepadInput.deviceRemovedSink = removed
        }
        onDispose {
            if (GamepadInput.rawSink === sink) GamepadInput.rawSink = null
            if (GamepadInput.deviceRemovedSink === removed) GamepadInput.deviceRemovedSink = null
            hardwareInput.onChanged = null
            physicalRouter.releaseAll()
            hardwareInput.releaseAll()
            padInputs.clearHardware()
            currentPublishPad(true)
        }
    }
    val motion = rememberCompanionMotionSource(transmitting && motionEnabled)
    val pulse = rememberGameButtonHaptic()
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    DisposableEffect(view) {
        val wasKeptOn = view.keepScreenOn
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = wasKeptOn }
    }

    val back: () -> Unit = {
        if (state.online) manager.showRemote() else manager.hideGamepad()
    }
    BackHandler(onBack = back)
    LaunchedEffect(state.gamepadPanel, state.online, info?.sessionId) {
        if (state.gamepadPanel && state.online && info != null) manager.claimGamepad()
    }

    // Press edges are sent immediately below. Axis, pointer and gyro snapshots are sampled
    // at ~60Hz; an idle heartbeat at 500ms keeps TV deadman state and buttons alive.
    LaunchedEffect(transmitting, info?.sessionId, motionEnabled) {
        if (transmitting) {
            var last: CompanionGamepadState? = null
            var ticks = 0
            while (true) {
                val next = padFrame.value.copy(motion = if (motionEnabled) motion.snapshot() else null)
                if (next != last || ticks >= 30) {
                    if (manager.gamepadInput(next)) {
                        last = next
                        ticks = 0
                        // Mouse deltas are relative, not a held position. A successful
                        // enqueue consumes them once, never repeating them on the heartbeat.
                        if (next.pointer?.kind == "mouse" &&
                            (next.pointer.dx != 0f || next.pointer.dy != 0f)) {
                            val pointerNow = padFrame.value.pointer
                            if (pointerNow?.kind == "mouse") {
                                padFrame.value = padFrame.value.copy(pointer = pointerNow.copy(
                                    dx = (pointerNow.dx - next.pointer.dx).coerceIn(-1f, 1f),
                                    dy = (pointerNow.dy - next.pointer.dy).coerceIn(-1f, 1f),
                                ))
                            }
                        }
                    }
                }
                ticks++
                delay(16)
            }
        }
    }
    LaunchedEffect(transmitting) {
        if (!transmitting) {
            // App background, screen lock or TV OSD: never send held-button heartbeats.
            // The TV's own deadman remains a fallback if this neutral frame is lost.
            padInputs.clear()
            hardwareInput.releaseAll()
            padFrame.value = CompanionGamepadState()
            manager.gamepadInput(CompanionGamepadState())
        }
    }
    LaunchedEffect(playing) {
        if (!playing) { touchOptionsOpen = false }
    }
    LaunchedEffect(inputKind) {
        if (padFrame.value.pointer != null) {
            padFrame.value = padFrame.value.copy(pointer = null)
            if (transmitting) manager.gamepadInput(padFrame.value)
        }
    }
    DisposableEffect(claimed, info?.sessionId) {
        onDispose { if (claimed) manager.gamepadInput(CompanionGamepadState()) }
    }
    val setButton: (RetroPadButton, Boolean) -> Unit = { button, pressed ->
        val mask = if (pressed) padInputs.touchButtons or button.mask else padInputs.touchButtons and button.mask.inv()
        if (padInputs.touchButtons != mask) {
            padInputs.touchButtons = mask
            publishPad()
            if (pressed && touchStyle.buttonHaptics && button !in COMPANION_DPAD_BUTTONS) pulse()
        }
    }
    val setPointer: (CompanionPointerState?) -> Unit = { pointer ->
        if (pointer?.kind == "mouse" && (pointer.dx != 0f || pointer.dy != 0f)) {
            // Accumulate relative movement until the next bounded sender tick. No one-packet-
            // per-touch-sample storm, and no duplicates from the periodic heartbeat.
            val existing = padFrame.value.pointer?.takeIf { it.kind == "mouse" }
            padFrame.value = padFrame.value.copy(pointer = pointer.copy(
                dx = ((existing?.dx ?: 0f) + pointer.dx).coerceIn(-1f, 1f),
                dy = ((existing?.dy ?: 0f) + pointer.dy).coerceIn(-1f, 1f),
            ))
        } else {
            // A click, release or input-mode change must never replace mouse travel that has
            // not yet reached the 60Hz sampler. Enqueue those relative deltas first.
            val pending = padFrame.value.pointer
            if (pending?.kind == "mouse" && (pending.dx != 0f || pending.dy != 0f)) {
                if (manager.gamepadInput(padFrame.value)) {
                    padFrame.value = padFrame.value.copy(pointer = pending.copy(dx = 0f, dy = 0f))
                }
            }
            val previous = padFrame.value.pointer
            val next = padFrame.value.copy(pointer = pointer)
            if (next != padFrame.value) {
                padFrame.value = next
                // Press/release and mode switches are immediate; continuous drag positions
                // flow only through the sampled sender above.
                if (companionPointerEdge(previous, pointer)) manager.gamepadInput(next)
            }
        }
    }

    val configuration = LocalConfiguration.current
    val landscape = configuration.screenWidthDp > configuration.screenHeightDp
    val compactHeader = landscape || configuration.screenHeightDp < 600
    var optionsOpen by remember(compactHeader) { mutableStateOf(false) }
    // Short windows keep the whole thumb area: configuration moves into the header's popover.
    // On tall portrait phones the same controls remain visible above the gamepad.
    val options: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = {
                val choices = listOf("pad", "touch", "mouse", "gun")
                inputKind = choices[(choices.indexOf(inputKind) + 1) % choices.size]
            }) {
                Text(stringResource(when (inputKind) {
                    "touch" -> R.string.companion_gamepad_input_touch
                    "mouse" -> R.string.companion_gamepad_input_mouse
                    "gun" -> R.string.companion_gamepad_input_gun
                    else -> R.string.companion_gamepad_input_pad
                }))
            }
            if (inputKind == "pad") TextButton(onClick = {
                optionsOpen = false
                padInputs.clearTouch()
                publishPad()
                touchOptionsOpen = true
            }) { Text(stringResource(R.string.game_touch_customize)) }
            if (inputKind == "pad") TextButton(onClick = {
                val next = (GameTouchMode.entries.indexOf(mode) + 1) % GameTouchMode.entries.size
                modeKey = GameTouchMode.entries[next].key
            }) {
                Icon(Icons.Default.Tune, null, Modifier.size(17.dp))
                Text(stringResource(mode.titleRes), maxLines = 1)
            }
            if (inputKind == "touch") TextButton(onClick = {
                val regions = listOf("full", "lower", "upper", "left", "right")
                pointerRegion = regions[(regions.indexOf(pointerRegion) + 1) % regions.size]
            }) {
                Text(stringResource(when (pointerRegion) {
                    "lower" -> R.string.companion_gamepad_region_lower
                    "upper" -> R.string.companion_gamepad_region_upper
                    "left" -> R.string.companion_gamepad_region_left
                    "right" -> R.string.companion_gamepad_region_right
                    else -> R.string.companion_gamepad_region_full
                }))
            }
            if (info?.systemId == "moonlight") TextButton(onClick = { rumbleEnabled = !rumbleEnabled }) {
                Text(stringResource(if (rumbleEnabled) R.string.moonlight_companion_rumble_on else R.string.moonlight_companion_rumble_off))
            }
            if (motion.availableMask != 0) TextButton(onClick = { motionEnabled = !motionEnabled }) {
                Text(stringResource(if (motionEnabled) R.string.companion_gamepad_motion_on
                    else R.string.companion_gamepad_motion_off))
            }
            if (inputKind == "gun") TextButton(onClick = {
                setPointer(CompanionPointerState(kind = "gun", offscreen = true, buttons = 2))
                scope.launch {
                    delay(48L) // Core must observe the reload edge for at least one frame.
                    setPointer(CompanionPointerState(kind = "gun"))
                }
            }) { Text(stringResource(R.string.companion_gamepad_reload)) }
        }
    }

    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(onClick = back) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.companion_gamepad_back))
                }
                Icon(Icons.Default.SportsEsports, null, tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp))
                Column(Modifier.weight(1f)) {
                    Text(info?.title?.ifBlank { null } ?: stringResource(R.string.companion_gamepad_title),
                        maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                    Text(if (claimed) stringResource(R.string.companion_gamepad_player, port + 1)
                        else state.target?.name.orEmpty(),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (compactHeader && transmitting) Box {
                    IconButton(onClick = { optionsOpen = !optionsOpen }) {
                        Icon(Icons.Default.Tune, stringResource(R.string.game_touch_controls))
                    }
                    DropdownMenu(
                        expanded = optionsOpen,
                        onDismissRequest = { optionsOpen = false },
                        modifier = Modifier.width(320.dp).pointerIdleVisibility(),
                    ) { options() }
                }
                if (compactHeader && transmitting && info.rewindAvailable && !touchOptionsOpen) {
                    key(info.sessionId, info.contextRevision) {
                        CompanionGamepadRewindControl(info.rewindSeconds,
                            onPressed = { rewindHold.set(touchRewindOwner, it, info) }, compact = true)
                    }
                }
                if (claimed && resumed && (transmitting || info.interaction == null))
                    TextButton(onClick = { manager.gamepadMenu(info) }) {
                    Text(stringResource(R.string.companion_gamepad_menu))
                }
            }

            if (!compactHeader && transmitting) options()

            if (!compactHeader && transmitting && info.rewindAvailable && !touchOptionsOpen) {
                key(info.sessionId, info.contextRevision) {
                    CompanionGamepadRewindControl(info.rewindSeconds,
                        onPressed = { rewindHold.set(touchRewindOwner, it, info) },
                        modifier = Modifier.align(Alignment.CenterHorizontally).padding(horizontal = 12.dp, vertical = 4.dp))
                }
            }

            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (navigating) {
                    key(info.sessionId, info.contextRevision) {
                        CompanionGameMenuControls(onNavigate = { manager.gamepadNavigate(it, info) },
                            onResume = { manager.gamepadMenu(info) },
                            onConfirm = { held, cancelled -> confirmHold.set(touchConfirmOwner, held, info, cancelled) },
                            canResume = info.acceptsResume)
                    }
                } else if (transmitting && !touchOptionsOpen) {
                    if (inputKind == "pad" && touchStyle.hideOnGamepadInput && hidingGamepadId != null) {
                        TextButton(onClick = { hidingGamepadId = null }) {
                            Icon(Icons.Default.SportsEsports, null)
                            Text(stringResource(R.string.game_touch_show))
                        }
                    } else if (inputKind == "pad") {
                        GameTouchControls(
                            mode = mode,
                            style = touchStyle,
                            presentation = touchPresentation,
                            coreActions = coreActions,
                            portrait = !landscape,
                            onButton = setButton,
                            onButtonsChanged = { buttons ->
                                val previous = padInputs.touchButtons
                                val mask = buttons.fold(0) { bits, button -> bits or button.mask }
                                if (mask != previous) {
                                    padInputs.touchButtons = mask
                                    // A complete snapshot avoids intermediate releases during face-button rolls.
                                    publishPad()
                                }
                            },
                            onStick = { index, x, y ->
                                padInputs.touchStick(index, x, y)
                                publishPad(send = x == 0f && y == 0f)
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Column(Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 4.dp)) {
                            CompanionGamePointerPad(
                                kind = inputKind, region = pointerRegion, enabled = playing,
                                onPointer = setPointer,
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                            )
                            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                CompanionGamepadHoldButton("A", Modifier.weight(1f)) { setButton(RetroPadButton.A, it) }
                                CompanionGamepadHoldButton("B", Modifier.weight(1f)) { setButton(RetroPadButton.B, it) }
                                CompanionGamepadHoldButton("SEL", Modifier.weight(1f)) { setButton(RetroPadButton.SELECT, it) }
                                CompanionGamepadHoldButton("START", Modifier.weight(1f)) { setButton(RetroPadButton.START, it) }
                            }
                        }
                    }
                } else {
                    Column(Modifier.fillMaxWidth().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Default.SportsEsports, null, Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(stringResource(when {
                            !state.online -> R.string.remote_disconnected
                            info == null -> R.string.companion_gamepad_no_game
                            !claimed -> R.string.companion_gamepad_not_claimed
                            info.interactionMode == CompanionGamepadInteraction.LOADING -> R.string.game_preparing
                            info.interactionMode == CompanionGamepadInteraction.BUSY -> R.string.companion_gamepad_busy
                            info.interactionMode == CompanionGamepadInteraction.BACKGROUND -> R.string.companion_gamepad_background
                            else -> R.string.companion_gamepad_paused
                        }), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (info != null && !claimed && state.online) {
                            Button(onClick = { manager.claimGamepad() }) {
                                Text(stringResource(R.string.companion_gamepad_claim))
                            }
                        }
                    }
                }
            }
        }
    }
    if (touchOptionsOpen) AlertDialog(
        onDismissRequest = { touchOptionsOpen = false },
        modifier = Modifier.pointerIdleVisibility(),
        title = { Text(stringResource(R.string.game_touch_customize)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            com.ambient.tv.ui.games.GameCoreInputActions(coreActions, touchPresentation)
            GameTouchOptions(info?.systemId)
        } },
        confirmButton = { TextButton(onClick = { touchOptionsOpen = false }) { Text(stringResource(android.R.string.ok)) } },
    )
}

/** Bounded renewals keep the primary player's held rewind alive, never a toggle. */
@Composable
private fun CompanionGamepadRewindControl(
    seconds: Float,
    onPressed: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val label = stringResource(R.string.game_rewind_hold)
    Surface(modifier.heightIn(min = 48.dp).semantics { contentDescription = label }
        .holdAction(onHold = onPressed, interactionSource = interactions),
        shape = RoundedCornerShape(14.dp),
        color = if (pressed) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, if (pressed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)) {
        Row(Modifier.padding(horizontal = if (compact) 8.dp else 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 8.dp)) {
            Icon(Icons.Default.FastRewind, null, Modifier.size(22.dp))
            if (!compact) Text(stringResource(R.string.game_rewind))
            Text(stringResource(R.string.game_rewind_buffer_seconds, seconds), style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** Press-and-hold is not an onClick pulse: cores see every DOWN and UP transition. */
@Composable
private fun CompanionGamepadHoldButton(
    label: String,
    modifier: Modifier = Modifier,
    onPressed: (Boolean) -> Unit,
) {
    var down by remember { mutableStateOf(false) }
    val latest by rememberUpdatedState(onPressed)
    DisposableEffect(Unit) { onDispose { latest(false) } }
    Surface(
        modifier.heightIn(min = 42.dp).pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                down = true
                latest(true)
                try { waitForUpOrCancellation() }
                finally { down = false; latest(false) }
            }
        },
        shape = RoundedCornerShape(14.dp),
        color = if (down) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, if (down) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outlineVariant),
    ) {
        Box(Modifier.fillMaxWidth().padding(10.dp), contentAlignment = Alignment.Center) {
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}
