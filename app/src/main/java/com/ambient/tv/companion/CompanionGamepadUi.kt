package com.ambient.tv.companion

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ambient.tv.R
import com.ambient.tv.games.GameTouchMode
import com.ambient.tv.ui.gamepad.retro.RetroPadButton
import com.ambient.tv.ui.games.GameTouchControls
import kotlinx.coroutines.delay
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty
import kotlinx.coroutines.launch

/** Initial layout follows the console but never changes the TV's authoritative core mapping. */
internal fun companionDefaultTouchMode(systemId: String?): GameTouchMode = when (systemId) {
    "n64", "nds", "wii" -> GameTouchMode.HYBRID
    "psx", "ps2", "psp", "n3ds", "gamecube", "wiiu", "dreamcast", "saturn" -> GameTouchMode.DUAL
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
private class CompanionInputFrame : ReadWriteProperty<Any?, CompanionGamepadState> {
    @Volatile private var frame = CompanionGamepadState()
    override fun getValue(thisRef: Any?, property: KProperty<*>): CompanionGamepadState = frame
    override fun setValue(thisRef: Any?, property: KProperty<*>, value: CompanionGamepadState) {
        frame = value
    }
}

/** Dedicated phone page, never a dialog: navigation, MiniPlayer and playback bars are absent. */
@Composable
internal fun CompanionGamepadScreen(manager: CompanionManager, modifier: Modifier = Modifier) {
    val state by manager.state.collectAsState()
    val info = state.remote?.gamepadInfo
    val port = state.remote?.gamepadPort ?: -1
    val claimed = state.online && info != null && port in 0..3
    val playing = claimed && info?.ready == true
    var modeKey by rememberSaveable(state.target?.fingerprint, info?.systemId) {
        mutableStateOf(companionDefaultTouchMode(info?.systemId).key)
    }
    var inputKind by rememberSaveable(state.target?.fingerprint, info?.systemId) {
        mutableStateOf(if (info?.pointerInput == true) "touch" else "pad")
    }
    var pointerRegion by rememberSaveable(state.target?.fingerprint, info?.systemId) {
        mutableStateOf(companionDefaultPointerRegion(info?.systemId))
    }
    var motionEnabled by rememberSaveable(state.target?.fingerprint, info?.systemId) { mutableStateOf(false) }
    val mode = GameTouchMode.fromKey(modeKey)
    var pad by remember(info?.sessionId) { CompanionInputFrame() }
    val motion = rememberCompanionMotionSource(playing && motionEnabled)
    val haptics = LocalHapticFeedback.current
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
    LaunchedEffect(playing, info?.sessionId, motionEnabled) {
        if (playing) {
            var last: CompanionGamepadState? = null
            var ticks = 0
            while (true) {
                val next = pad.copy(motion = if (motionEnabled) motion.snapshot() else null)
                if (next != last || ticks >= 30) {
                    if (manager.gamepadInput(next)) {
                        last = next
                        ticks = 0
                        // Mouse deltas are relative, not a held position. A successful
                        // enqueue consumes them once, never repeating them on the heartbeat.
                        if (next.pointer?.kind == "mouse" &&
                            (next.pointer.dx != 0f || next.pointer.dy != 0f)) {
                            val pointerNow = pad.pointer
                            if (pointerNow?.kind == "mouse") {
                                pad = pad.copy(pointer = pointerNow.copy(
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
    LaunchedEffect(playing) {
        if (!playing) pad = CompanionGamepadState()
    }
    LaunchedEffect(inputKind) {
        if (pad.pointer != null) {
            pad = pad.copy(pointer = null)
            if (playing) manager.gamepadInput(pad)
        }
    }
    DisposableEffect(claimed, info?.sessionId) {
        onDispose { if (claimed) manager.gamepadInput(CompanionGamepadState()) }
    }
    val setButton: (RetroPadButton, Boolean) -> Unit = { button, pressed ->
        val mask = if (pressed) pad.buttons or button.mask else pad.buttons and button.mask.inv()
        if (pad.buttons != mask) {
            pad = pad.copy(buttons = mask)
            manager.gamepadInput(pad)
            if (pressed) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }
    val setPointer: (CompanionPointerState?) -> Unit = { pointer ->
        if (pointer?.kind == "mouse" && (pointer.dx != 0f || pointer.dy != 0f)) {
            // Accumulate relative movement until the next bounded sender tick. No one-packet-
            // per-touch-sample storm, and no duplicates from the periodic heartbeat.
            val existing = pad.pointer?.takeIf { it.kind == "mouse" }
            pad = pad.copy(pointer = pointer.copy(
                dx = ((existing?.dx ?: 0f) + pointer.dx).coerceIn(-1f, 1f),
                dy = ((existing?.dy ?: 0f) + pointer.dy).coerceIn(-1f, 1f),
            ))
        } else {
            val previous = pad.pointer
            val next = pad.copy(pointer = pointer)
            if (next != pad) {
                pad = next
                // Press/release and mode switches are immediate; continuous drag positions
                // flow only through the sampled sender above.
                if (companionPointerEdge(previous, pointer)) manager.gamepadInput(next)
            }
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
                TextButton(onClick = { manager.gamepadMenu() }, enabled = claimed) {
                    Text(stringResource(R.string.companion_gamepad_menu))
                }
            }

            // These are controls in the page, not dialogs or overlays. Horizontal scrolling
            // prevents clipped labels on portrait phones and uses the same accent as the app.
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

            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (playing) {
                    if (inputKind == "pad") {
                        GameTouchControls(
                            mode = mode,
                            onButton = setButton,
                            onDpadDirections = { directions ->
                                val mask = companionDpadMask(pad.buttons, directions)
                                if (mask != pad.buttons) {
                                    pad = pad.copy(buttons = mask)
                                    manager.gamepadInput(pad) // one atomic frame per direction
                                }
                            },
                            onStick = { index, x, y ->
                                val next = if (index == 0) pad.copy(leftX = x, leftY = y)
                                    else pad.copy(rightX = x, rightY = y)
                                if (next != pad) {
                                    pad = next
                                    if (x == 0f && y == 0f) manager.gamepadInput(next)
                                }
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
