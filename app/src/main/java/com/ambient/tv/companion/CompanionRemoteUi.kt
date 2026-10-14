package com.ambient.tv.companion

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.automirrored.filled.KeyboardReturn
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ambient.tv.R
import com.ambient.tv.ui.common.AppDialog
import com.ambient.tv.ui.common.TvActionButton

@Composable
internal fun CompanionRemoteDialog(manager: CompanionManager) {
    val state by manager.state.collectAsState()
    val granted = state.online && state.remote?.inputGranted == true
    var tab by rememberSaveable(state.target?.fingerprint) { mutableIntStateOf(0) }
    var draft by rememberSaveable(state.target?.fingerprint) { mutableStateOf("") }
    var sensitivity by rememberSaveable { mutableFloatStateOf(1f) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val haptics = LocalHapticFeedback.current
    val send: (CompanionInput) -> Unit = { input ->
        if (granted) {
            manager.remoteInput(input)
            if (input.action !in setOf("move", "scroll")) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }
    val key: (String) -> Unit = { send(CompanionInput("key", key = it)) }
    val labels = listOf(R.string.remote_tab_dpad, R.string.remote_tab_mouse, R.string.remote_tab_keyboard)
    val icons = listOf(Icons.Default.ControlCamera, Icons.Default.Mouse, Icons.Default.Keyboard)
    AppDialog(
        onDismiss = manager::hideRemote,
        title = stringResource(R.string.companion_remote_target, state.target?.name.orEmpty()),
        maxWidth = 560.dp,
        maxHeightFraction = 0.94f,
        captureInitialFocus = false,
        header = {
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(if (granted) Icons.Default.CheckCircle else Icons.Default.Info, null,
                    Modifier.size(18.dp), tint = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(when {
                    !state.online -> R.string.remote_disconnected
                    granted -> R.string.remote_ready
                    else -> R.string.remote_waiting
                }), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            }
            PrimaryTabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0f)) {
                labels.forEachIndexed { index, label ->
                    Tab(selected = tab == index, onClick = {
                        focus.clearFocus(); keyboard?.hide(); tab = index
                    }, text = { Text(stringResource(label), maxLines = 1) },
                        icon = { Icon(icons[index], null, Modifier.size(22.dp)) })
                }
            }
        },
        footer = {
            OutlinedButton({ key("back") }, enabled = granted, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.back))
            }
            TvActionButton(stringResource(R.string.companion_end_control), manager::hideRemote)
        },
    ) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!granted) {
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(if (state.online) R.string.companion_control_unavailable else R.string.remote_reconnect_hint,
                            state.remote?.controller.orEmpty()), style = MaterialTheme.typography.bodyMedium)
                        if (state.online) OutlinedButton({ manager.send(CompanionFrame(type = "input_claim")) }) {
                            Text(stringResource(R.string.companion_take_control))
                        }
                    }
                }
            }
            when (tab) {
                0 -> RemoteDirectionPad(granted, key)
                1 -> RemoteMousePad(granted, sensitivity, { sensitivity = it }, send)
                2 -> RemoteKeyboard(granted, draft, { draft = it }, send, key)
            }
            RemotePlayerControls(manager, state)
        }
    }
}

@Composable
private fun RemoteDirectionPad(enabled: Boolean, key: (String) -> Unit) {
    Text(stringResource(R.string.remote_dpad_hint), style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Surface(Modifier.widthIn(max = 320.dp).fillMaxWidth(), shape = RoundedCornerShape(32.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            RemoteDirectionButton(Icons.Default.KeyboardArrowUp, R.string.remote_up, enabled) { key("up") }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically) {
                RemoteDirectionButton(Icons.AutoMirrored.Filled.KeyboardArrowLeft, R.string.remote_left, enabled) { key("left") }
                FilledIconButton({ key("ok") }, enabled = enabled, modifier = Modifier.size(76.dp), shape = CircleShape) {
                    Icon(Icons.Default.Check, stringResource(R.string.remote_ok), Modifier.size(32.dp))
                }
                RemoteDirectionButton(Icons.AutoMirrored.Filled.KeyboardArrowRight, R.string.remote_right, enabled) { key("right") }
            }
            RemoteDirectionButton(Icons.Default.KeyboardArrowDown, R.string.remote_down, enabled) { key("down") }
        }
    }
}

@Composable
private fun RemoteDirectionButton(icon: ImageVector, label: Int, enabled: Boolean, action: () -> Unit) {
    FilledTonalIconButton(action, enabled = enabled, modifier = Modifier.size(64.dp), shape = RoundedCornerShape(20.dp)) {
        Icon(icon, stringResource(label), Modifier.size(34.dp))
    }
}

@Composable
private fun RemoteMousePad(enabled: Boolean, sensitivity: Float, onSensitivity: (Float) -> Unit, send: (CompanionInput) -> Unit) {
    val currentSend by rememberUpdatedState(send)
    val currentSensitivity by rememberUpdatedState(sensitivity)
    val clickLabel = stringResource(R.string.remote_click)
    val sensitivityLabel = stringResource(R.string.remote_sensitivity)
    Surface(Modifier.fillMaxWidth().height(240.dp), shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (enabled) 0.65f else 0.3f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Box(Modifier.fillMaxSize()
            .semantics(mergeDescendants = true) {
                role = Role.Button
                if (enabled) onClick(clickLabel) { currentSend(CompanionInput("click")); true }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    var previousCount = 1
                    var multiTouch = false
                    var moved = false
                    var pending = Offset.Zero
                    var travel = Offset.Zero
                    var lastSent = down.uptimeMillis
                    var action = "move"
                    do {
                        val event = awaitPointerEvent()
                        val active = event.changes.filter { it.pressed }
                        val count = active.size
                        // Flush using the previous gesture mode before a finger is added or lifted.
                        if (count != previousCount) {
                            if (moved && pending != Offset.Zero) {
                                currentSend(CompanionInput(action, x = (pending.x / size.width.coerceAtLeast(1) * currentSensitivity).coerceIn(-1f, 1f),
                                    y = (pending.y / size.height.coerceAtLeast(1) * currentSensitivity).coerceIn(-1f, 1f)))
                            }
                            pending = Offset.Zero
                            previousCount = count
                        }
                        if (count >= 2) multiTouch = true
                        action = if (count >= 2) "scroll" else "move"
                        val delta = if (active.isEmpty()) Offset.Zero else active.map { it.positionChange() }.reduce { a, b -> a + b } / count.toFloat()
                        travel += delta
                        pending += delta
                        if (travel.getDistance() > viewConfiguration.touchSlop) moved = true
                        val now = event.changes.first().uptimeMillis
                        if (moved && pending != Offset.Zero && now - lastSent >= 40) {
                            currentSend(CompanionInput(action, x = (pending.x / size.width.coerceAtLeast(1) * currentSensitivity).coerceIn(-1f, 1f),
                                y = (pending.y / size.height.coerceAtLeast(1) * currentSensitivity).coerceIn(-1f, 1f)))
                            pending = Offset.Zero
                            lastSent = now
                        }
                        event.changes.forEach { it.consume() }
                    } while (event.changes.any { it.pressed })
                    if (!moved && !multiTouch) currentSend(CompanionInput("click"))
                }
            }, contentAlignment = Alignment.Center) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Default.TouchApp, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.remote_mouse_hint), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            }
        }
    }
    OutlinedButton({ send(CompanionInput("click")) }, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Text(clickLabel)
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(sensitivityLabel, style = MaterialTheme.typography.labelMedium)
        Slider(value = sensitivity, onValueChange = onSensitivity, valueRange = 0.5f..2f, steps = 5,
            enabled = enabled, modifier = Modifier.weight(1f).semantics { contentDescription = sensitivityLabel })
    }
}

@Composable
private fun RemoteKeyboard(enabled: Boolean, text: String, onText: (String) -> Unit,
    send: (CompanionInput) -> Unit, key: (String) -> Unit) {
    val requester = remember { FocusRequester() }
    val insert = { if (enabled && text.isNotEmpty()) { send(CompanionInput("text", text = text)); onText("") } }
    Text(stringResource(R.string.remote_keyboard_hint), style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (enabled) com.ambient.tv.ui.common.TvTextField(
        value = text,
        onValueChange = { value -> onText(value.filter { it != '\u0000' && (!it.isISOControl() || it == '\n' || it == '\t') }.take(2048)) },
        modifier = Modifier.fillMaxWidth().focusRequester(requester),
        label = stringResource(R.string.companion_type_text),
        singleLine = false,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
    )
    Button(insert, enabled = enabled && text.isNotEmpty(), modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
        Text(stringResource(R.string.companion_insert_text))
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton({ key("delete") }, enabled = enabled, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
            Icon(Icons.AutoMirrored.Filled.Backspace, stringResource(R.string.companion_delete_character), Modifier.size(22.dp))
        }
        OutlinedButton({ key("enter") }, enabled = enabled, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
            Icon(Icons.AutoMirrored.Filled.KeyboardReturn, null, Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.companion_enter))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RemotePlayerControls(manager: CompanionManager, state: CompanionState) {
    val frame = state.currentPlayback() ?: return
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            CompanionRemoteMiniPlayer(manager, state)
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally)) {
                if ("previous" in frame.capabilities) RemotePlayerButton(Icons.Default.SkipPrevious, R.string.companion_previous) {
                    manager.playbackCommand("previous", frame)
                }
                if (frame.seekable && (frame.capabilities.isEmpty() || "seek" in frame.capabilities)) {
                    RemotePlayerButton(Icons.Default.Replay10, R.string.remote_rewind) {
                        manager.playbackCommand("seek", frame, frame.position - 10_000)
                    }
                    RemotePlayerButton(Icons.Default.Forward10, R.string.remote_forward) {
                        manager.playbackCommand("seek", frame, frame.position + 10_000)
                    }
                }
                if ("next" in frame.capabilities) RemotePlayerButton(Icons.Default.SkipNext, R.string.companion_next) {
                    manager.playbackCommand("next", frame)
                }
            }
            if ("volume" in frame.capabilities && frame.volume != null && frame.volumeMax > 0) {
                val volume = frame.volume.coerceIn(0, frame.volumeMax)
                var audible by remember(frame.receiverId, frame.media, frame.playbackSessionId) {
                    mutableIntStateOf(volume.takeIf { it > 0 } ?: (frame.volumeMax / 2).coerceAtLeast(1))
                }
                LaunchedEffect(volume) { if (volume > 0) audible = volume }
                val volumeLabel = stringResource(R.string.companion_tv_volume, volume, frame.volumeMax)
                Text(volumeLabel, Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.labelMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RemotePlayerButton(if (volume == 0) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                        if (volume == 0) R.string.companion_unmute else R.string.companion_mute) {
                        manager.playbackCommand("volume", frame, if (volume == 0) audible.coerceAtMost(frame.volumeMax).toLong() else 0L)
                    }
                    var value by remember(frame.receiverId, frame.media, frame.playbackSessionId) { mutableFloatStateOf(volume.toFloat()) }
                    var dragging by remember(frame.receiverId, frame.media, frame.playbackSessionId) { mutableStateOf(false) }
                    LaunchedEffect(volume, frame.volumeMax) { if (!dragging) value = volume.toFloat() }
                    Slider(value = value.coerceIn(0f, frame.volumeMax.toFloat()), onValueChange = { dragging = true; value = it },
                        onValueChangeFinished = { manager.playbackCommand("volume", frame, value.toLong()); dragging = false },
                        valueRange = 0f..frame.volumeMax.toFloat(),
                        modifier = Modifier.weight(1f).semantics { contentDescription = volumeLabel })
                }
            }
            TextButton(manager::showNowPlaying, Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Tune, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.remote_player_options))
            }
        }
    }
}

@Composable
private fun RemotePlayerButton(icon: ImageVector, label: Int, action: () -> Unit) {
    IconButton(action, Modifier.size(48.dp)) { Icon(icon, stringResource(label), Modifier.size(24.dp)) }
}
