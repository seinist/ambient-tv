package com.ambient.tv.ui.home

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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

/** Always verify the selected service before deciding whether its machine still needs WOL. */
@Composable
fun offlineActionLabel(state: UnreachableServerGateState): String =
    stringResource(if (state.retrying) R.string.unreachable_retrying else R.string.home_retry)

/** Monotonic deadline of the existing background retry (not a second retry timer). */
internal fun offlineRetrySeconds(nextCheckAtElapsedMs: Long, nowElapsedMs: Long): Int? {
    if (nextCheckAtElapsedMs <= 0L) return null
    val remaining = (nextCheckAtElapsedMs - nowElapsedMs).coerceAtLeast(0L)
    return ((remaining + 999L) / 1_000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}

/** A source guard keeps its saved browse tree alive. Only playback/detail actions are gated. */
@Composable
fun OfflineSystemGuard(system: String, onOpenSettings: (String) -> Unit, content: @Composable () -> Unit) {
    val vm: UnreachableServerViewModel = hiltViewModel()
    val state by vm.state.collectAsState()
    val offline = system in state.offline
    var openedWakeAttempted by remember(system) { mutableStateOf(false) }
    LaunchedEffect(system, offline) {
        if (offline && !openedWakeAttempted) {
            openedWakeAttempted = true
            vm.onOpenSystem(system)
        }
    }
    content()
}

/** One compact recovery action above a saved catalog; never requests focus or starts WOL. */
@Composable
fun OfflineSourceStatus(
    systems: Set<String>? = null,
    modifier: Modifier = Modifier,
    additionalSources: List<String> = emptyList(),
    onRetryAdditional: () -> Unit = {},
) {
    val vm: UnreachableServerViewModel = hiltViewModel()
    val state by vm.state.collectAsState()
    val sourceRevision by vm.availability.changes.collectAsState()
    val offline = remember(state.offline, systems, sourceRevision) {
        val failedServices = systems.orEmpty().filter {
            it in setOf("plex", "jellyfin", "emby", "tvheadend") && vm.availability.browseUnconfirmed(it)
        }
        (state.offline + failedServices).filter { systems == null || it in systems }
    }
    if (offline.isNotEmpty() || additionalSources.isNotEmpty()) SavedBrowseStatus(
        sources = (offline + additionalSources).distinct(), retrying = state.retrying,
        onRetry = { offline.forEach(vm::retrySystem); onRetryAdditional() },
        modifier = modifier,
    )
}

/** Reusable by Live TV, where repository fallback can be stale without a host-offline flag. */
@Composable
fun SavedBrowseStatus(
    sources: List<String>,
    retrying: Boolean,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    savedAt: String? = null,
) {
    val names = sources.map { source ->
        MediaSystem.fromKey(source)?.let { systemName(it) } ?: source
    }.joinToString(", ")
    Row(
        modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.offline_browse_saved_title, names), style = MaterialTheme.typography.titleSmall)
            Text(
                savedAt?.let { stringResource(R.string.offline_browse_saved_at, it) }
                    ?: stringResource(R.string.offline_browse_saved_body),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TvActionButton(
            text = stringResource(if (retrying) R.string.unreachable_retrying else R.string.home_retry),
            onClick = onRetry, loading = retrying, enabled = !retrying,
        )
    }
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
                stringResource(R.string.offline_browse_no_cache_body),
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
                    stringResource(when {
                        system in state.wakeReady -> R.string.offline_wol_ready
                        state.hostHealth[system] == HostHealth.ONLINE -> R.string.offline_wol_not_needed
                        else -> R.string.offline_wol_missing
                    }),
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
                    text = offlineActionLabel(state),
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
