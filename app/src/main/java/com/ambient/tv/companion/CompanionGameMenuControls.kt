package com.ambient.tv.companion

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ambient.tv.R

/** The phone keeps its controller claim while the same page operates the TV's game OSD. */
@Composable
internal fun CompanionGameMenuControls(
    onNavigate: (String) -> Unit,
    onResume: () -> Unit,
    onConfirm: (Boolean, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    canResume: Boolean = true,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val compact = maxHeight < 340.dp
        val sideBySide = maxWidth >= 480.dp && compact
        val actions: @Composable ColumnScope.() -> Unit = {
            Text(stringResource(R.string.companion_gamepad_osd_title),
                style = MaterialTheme.typography.titleMedium)
            OutlinedButton(onClick = { onNavigate("back") }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.remote_tv_back))
            }
            if (canResume) Button(onClick = onResume, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Icon(Icons.Default.PlayArrow, null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.game_continue))
            }
        }
        if (sideBySide) Row(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                RemoteDirectionPad(true, onNavigate, compact = true, showHint = false,
                    confirmControl = { GameMenuConfirmButton(it, onConfirm) { onNavigate("ok") } })
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp), content = actions)
        } else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)) {
            RemoteDirectionPad(true, onNavigate, compact = compact, showHint = false,
                confirmControl = { GameMenuConfirmButton(it, onConfirm) { onNavigate("ok") } })
            Column(Modifier.widthIn(max = 320.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp), content = actions)
        }
    }
}

/** Unlike direction taps, OK needs real edges for the TV's press-and-hold game tools. */
@Composable
private fun GameMenuConfirmButton(size: Dp, onHold: (Boolean, Boolean) -> Unit, onTap: () -> Unit) {
    val latest by rememberUpdatedState(onHold)
    var held by remember { mutableStateOf(false) }
    val label = stringResource(R.string.remote_ok)
    DisposableEffect(Unit) { onDispose { latest(false, true) } }
    Surface(Modifier.size(size)
        .semantics { role = Role.Button; contentDescription = label; onClick { onTap(); true } }
        .onFocusChanged { if (!it.hasFocus) { held = false; latest(false, true) } }
        .onPreviewKeyEvent { event ->
            if (event.key != Key.DirectionCenter && event.key != Key.Enter && event.key != Key.NumPadEnter &&
                event.key != Key.Spacebar) false
            else {
                if (event.type == KeyEventType.KeyDown && !held) { held = true; latest(true, false) }
                if (event.type == KeyEventType.KeyUp) { held = false; latest(false, event.nativeKeyEvent.isCanceled) }
                true
            }
        }
        .focusable()
        .pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false).consume()
                held = true
                latest(true, false)
                var cancelled = true
                try { waitForUpOrCancellation()?.let { it.consume(); cancelled = false } }
                finally { held = false; latest(false, cancelled) }
            }
        }, shape = CircleShape,
        color = if (held) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer,
        contentColor = if (held) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimaryContainer) {
        Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.Check, null, Modifier.size(32.dp)) }
    }
}
