package com.ambient.tv.companion

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ambient.tv.R
import com.ambient.tv.games.GameTouchMode
import com.ambient.tv.ui.gamepad.retro.RetroPadButton
import com.ambient.tv.ui.games.GameTouchControls
import kotlinx.coroutines.delay

/** Suggested initial layout; users can change it without changing TV core mappings. */
internal fun companionDefaultTouchMode(systemId: String?): GameTouchMode = when (systemId) {
    "n64", "nds", "wii" -> GameTouchMode.HYBRID
    "psx", "ps2", "psp", "n3ds", "gamecube", "wiiu", "dreamcast", "saturn" -> GameTouchMode.DUAL
    else -> GameTouchMode.DPAD
}

/**
 * Standalone full-width phone page. Unlike a dialog, it hides phone navigation, mini player and
 * remote playback bars so thumbs can use the whole display in either orientation.
 */
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
    val mode = GameTouchMode.fromKey(modeKey)
    var pad by remember(info?.sessionId) { mutableStateOf(CompanionGamepadState()) }
    val haptics = LocalHapticFeedback.current
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

    // The TV may start a game after opening this page. Claim each new core session once.
    LaunchedEffect(state.gamepadPanel, state.online, info?.sessionId) {
        if (state.gamepadPanel && state.online && info != null) manager.claimGamepad()
    }
    // Press/release events below are immediate. Analog movement is limited to ~30 Hz and idle
    // snapshots are refreshed to prevent a lost socket frame from leaving a button held.
    LaunchedEffect(playing, info?.sessionId) {
        if (playing) {
            var last: CompanionGamepadState? = null
            var ticks = 0
            while (true) {
                val next = pad
                if (next != last || ticks >= 15) {
                    if (manager.gamepadInput(next)) { last = next; ticks = 0 }
                }
                ticks++
                delay(32)
            }
        }
    }
    LaunchedEffect(playing) {
        if (!playing) pad = CompanionGamepadState()
    }
    DisposableEffect(claimed, info?.sessionId) {
        onDispose { if (claimed) manager.gamepadInput(CompanionGamepadState()) }
    }

    Surface(
        modifier.fillMaxSize().pointerInput(Unit) {
            awaitEachGesture { awaitFirstDown(requireUnconsumed = false) }
        },
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                IconButton(onClick = back) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.companion_gamepad_back))
                }
                Icon(Icons.Default.SportsEsports, null, tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        info?.title?.ifBlank { null } ?: stringResource(R.string.companion_gamepad_title),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        if (claimed) stringResource(R.string.companion_gamepad_player, port + 1)
                        else state.target?.name.orEmpty(),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = {
                    val next = (GameTouchMode.entries.indexOf(mode) + 1) % GameTouchMode.entries.size
                    modeKey = GameTouchMode.entries[next].key
                }) {
                    Icon(Icons.Default.Tune, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(mode.titleRes), maxLines = 1)
                }
                TextButton(onClick = { manager.gamepadMenu() }, enabled = claimed) {
                    Text(stringResource(R.string.companion_gamepad_menu))
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (playing) {
                    GameTouchControls(
                        mode = mode,
                        onButton = { button, pressed ->
                            val nextMask = if (pressed) pad.buttons or button.mask else pad.buttons and button.mask.inv()
                            if (pad.buttons != nextMask) {
                                pad = pad.copy(buttons = nextMask)
                                // Don't lose short taps or button releases between analog samples.
                                manager.gamepadInput(pad)
                                if (pressed) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                        },
                        onStick = { index, x, y ->
                            val next = when (index) {
                                0 -> pad.copy(leftX = x, leftY = y)
                                else -> pad.copy(rightX = x, rightY = y)
                            }
                            if (next != pad) {
                                pad = next
                                if (x == 0f && y == 0f) manager.gamepadInput(next)
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Column(
                        Modifier.fillMaxWidth().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(Icons.Default.SportsEsports, null, Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            stringResource(when {
                                !state.online -> R.string.remote_disconnected
                                info == null -> R.string.companion_gamepad_no_game
                                !claimed -> R.string.companion_gamepad_not_claimed
                                else -> R.string.companion_gamepad_paused
                            }),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
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
