package com.ambient.tv.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.ambient.tv.R
import com.ambient.tv.connectivity.UnreachableServerGateState
import com.ambient.tv.ui.common.MediaSystem
import com.ambient.tv.ui.common.SystemLogo
import com.ambient.tv.ui.common.TvActionButton
import com.ambient.tv.ui.common.systemName

/** "Wake up" where Wake-on-LAN is set up, else "Reconnect": the one action for an offline server. */
@Composable
fun offlineActionLabel(state: UnreachableServerGateState): String = stringResource(when {
    state.retrying -> R.string.unreachable_retrying
    state.wakeUnavailable -> R.string.offline_reconnect
    else -> R.string.offline_wake
})

/**
 * Shows [content] while [system] is reachable, else the offline page in its place: no half
 * loaded library and no "no items". The page loads by itself once the server answers.
 */
@Composable
fun OfflineSystemGuard(system: String, onOpenSettings: (String) -> Unit, content: @Composable () -> Unit) {
    val vm: UnreachableServerViewModel = hiltViewModel()
    val state by vm.state.collectAsState()
    if (system in state.offline) OfflineSystemPage(system, state, vm::retry, { onOpenSettings(system) })
    else content()
}

@Composable
fun OfflineSystemPage(
    system: String,
    state: UnreachableServerGateState,
    onReconnect: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val media = MediaSystem.entries.firstOrNull { it.key == system }
    val name = media?.let { systemName(it) } ?: system
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 560.dp).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            media?.let { SystemLogo(it, size = 48.dp) }
            Text(stringResource(R.string.offline_library_title, name), style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
            Text(
                stringResource(R.string.offline_library_body) +
                    (if (state.wakeUnavailable) "\n" + stringResource(R.string.unreachable_no_wake_hint) else ""),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvActionButton(text = offlineActionLabel(state), onClick = onReconnect, loading = state.retrying,
                    modifier = Modifier.focusRequester(focus))
                TvActionButton(text = stringResource(R.string.nav_settings), onClick = onOpenSettings)
            }
        }
    }
}
