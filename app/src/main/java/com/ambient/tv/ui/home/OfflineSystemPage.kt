package com.ambient.tv.ui.home

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.ambient.tv.R
import com.ambient.tv.connectivity.HostHealth
import com.ambient.tv.connectivity.UnreachableServerGateState
import com.ambient.tv.ui.common.MediaSystem
import com.ambient.tv.ui.common.SystemLogo
import com.ambient.tv.ui.common.TvActionButton
import com.ambient.tv.ui.common.browseFocusTarget
import com.ambient.tv.ui.common.systemName
import com.ambient.tv.ui.mobile.DeviceActionRow
import kotlinx.coroutines.delay

/** Manual actions depend on the chosen server, not another offline server's Wake-on-LAN setup. */
@Composable
fun offlineActionLabel(state: UnreachableServerGateState, system: String): String = stringResource(when {
    state.retrying -> R.string.unreachable_retrying
    system !in state.wakeReady -> R.string.offline_reconnect
    else -> R.string.offline_wake
})

/** Monotonic deadline of the existing background retry (not a second retry timer). */
internal fun offlineRetrySeconds(nextCheckAtElapsedMs: Long, nowElapsedMs: Long): Int? {
    if (nextCheckAtElapsedMs <= 0L) return null
    val remaining = (nextCheckAtElapsedMs - nowElapsedMs).coerceAtLeast(0L)
    return ((remaining + 999L) / 1_000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}

/** An unreachable media server shows one recovery page until its service is usable again. */
@Composable
fun OfflineSystemGuard(system: String, onOpenSettings: (String) -> Unit, content: @Composable () -> Unit) {
    val vm: UnreachableServerViewModel = hiltViewModel()
    val state by vm.state.collectAsState()
    if (system in state.offline) {
        val nextCheckAt by vm.nextAutomaticCheckAtElapsedMs.collectAsState()
        OfflineSystemPage(
            system, state, vm::retry,
            // Go straight to this server's existing Wake-on-LAN group and MAC editor.
            { onOpenSettings("wake:" + system) },
            nextCheckAtElapsedMs = nextCheckAt,
        )
    } else content()
}

@Composable
fun OfflineSystemPage(
    system: String,
    state: UnreachableServerGateState,
    onReconnect: () -> Unit,
    onOpenSettings: () -> Unit,
    nextCheckAtElapsedMs: Long = 0L,
) {
    val media = MediaSystem.entries.firstOrNull { it.key == system }
    val name = media?.let { systemName(it) } ?: system
    val reconnectFocus = remember { FocusRequester() }
    val settingsFocus = remember { FocusRequester() }
    var lastFocusedAction by rememberSaveable(system) { mutableStateOf("reconnect") }

    // Save the chosen action across Settings navigation, including when NavHost recreates
    // this destination. BrowseFocusReturnScope handles focus while the entry stays composed.
    LaunchedEffect(system) {
        withFrameNanos { }
        runCatching {
            (if (lastFocusedAction == "settings") settingsFocus else reconnectFocus).requestFocus()
        }
    }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val nextCheckSeconds by produceState<Int?>(
        offlineRetrySeconds(nextCheckAtElapsedMs, SystemClock.elapsedRealtime()),
        nextCheckAtElapsedMs, lifecycle,
    ) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                value = offlineRetrySeconds(nextCheckAtElapsedMs, SystemClock.elapsedRealtime())
                delay(1_000L)
            }
        }
    }
    val hostStatus = when (state.hostHealth[system]) {
        HostHealth.ONLINE -> R.string.offline_host_online
        HostHealth.OFFLINE -> R.string.offline_host_offline
        else -> R.string.offline_host_unknown
    }

    Box(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.widthIn(max = 560.dp).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            media?.let { SystemLogo(it, size = 48.dp) }
            Text(
                stringResource(R.string.offline_library_title, name),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Text(
                stringResource(R.string.offline_library_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    stringResource(hostStatus),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                state.serverAddresses[system]?.takeIf { it.isNotBlank() }?.let { address ->
                    Text(
                        stringResource(R.string.offline_server_address, address),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
                Text(
                    stringResource(if (system in state.wakeReady) R.string.offline_wol_ready else R.string.offline_wol_missing),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Text(
                    when {
                        state.retrying -> stringResource(R.string.offline_auto_check_manual)
                        nextCheckSeconds == null -> stringResource(R.string.offline_auto_check_waiting)
                        nextCheckSeconds == 0 -> stringResource(R.string.offline_auto_check_now)
                        else -> stringResource(R.string.offline_auto_check_in, nextCheckSeconds!!)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                )
            }
            DeviceActionRow(
                modifier = Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TvActionButton(
                    text = offlineActionLabel(state, system),
                    onClick = onReconnect,
                    loading = state.retrying,
                    modifier = Modifier
                        .focusRequester(reconnectFocus)
                        .browseFocusTarget("offline:" + system + ":reconnect", content = false)
                        .onFocusChanged { if (it.isFocused) lastFocusedAction = "reconnect" },
                )
                TvActionButton(
                    text = stringResource(R.string.offline_wake_settings),
                    onClick = onOpenSettings,
                    modifier = Modifier
                        .focusRequester(settingsFocus)
                        .browseFocusTarget("offline:" + system + ":settings", content = false)
                        .onFocusChanged { if (it.isFocused) lastFocusedAction = "settings" },
                )
            }
        }
    }
}
