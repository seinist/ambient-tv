package com.ambient.tv.companion

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ambient.tv.R
import com.ambient.tv.ui.common.TvActionButton

/**
 * Shared phone entry point, in Settings and on the "Handy & TV" page: an overview of the paired
 * TVs with their status, and per TV a detail view with its actions, connection setup and unpairing.
 * Pairing itself is the guided page ([CompanionPairingScreen]).
 */
@Composable
internal fun CompanionDeviceHub(manager: CompanionManager, state: CompanionState) {
    var detail by rememberSaveable { mutableStateOf<String?>(null) }
    DisposableEffect(manager) { manager.startPageSearch(); onDispose { manager.stopSearchUnlessPanel() } }
    val peer = state.paired.firstOrNull { it.fingerprint == detail }
    if (peer != null) {
        BackHandler { detail = null }
        CompanionTvDetail(manager, state, peer, onBack = { detail = null })
    } else {
        CompanionTvOverview(manager, state, onOpen = { detail = it.fingerprint })
    }
}

@Composable
private fun CompanionTvOverview(manager: CompanionManager, state: CompanionState, onOpen: (CompanionPeer) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        com.ambient.tv.ui.common.LocalNetworkPermissionSetting(onGranted = manager::onLocalNetworkPermissionGranted)
        if (state.paired.isEmpty()) {
            Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Default.Tv, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.companion_empty_title), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                Text(stringResource(R.string.companion_empty_body), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
            TvActionButton(stringResource(R.string.companion_pair_tv), manager::startPairingFlow,
                icon = Icons.Default.Add, selected = true, fillWidth = true)
        } else {
            CompanionStatusCard(state)
            state.paired.forEach { peer -> CompanionTvCard(manager, state, peer) { onOpen(peer) } }
            if (state.paired.none { state.online && it.fingerprint == state.target?.fingerprint }) {
                TvActionButton(stringResource(R.string.companion_search), manager::discover, loading = state.searching, fillWidth = true)
            }
            TvActionButton(stringResource(R.string.companion_add_tv), manager::startPairingFlow, icon = Icons.Default.Add, fillWidth = true)
            if (state.rollbackAvailable) TvActionButton(stringResource(R.string.companion_restore_backup), manager::showRestore, fillWidth = true)
        }
    }
}

/** "Where am I": connected to which TV, or how many are paired, plus a running connection attempt. */
@Composable
private fun CompanionStatusCard(state: CompanionState) {
    val live = state.target?.takeIf { state.online }
    Surface(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(),
        color = if (live != null) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(if (live != null) Icons.Default.CheckCircle else Icons.Default.Info, null, Modifier.size(20.dp))
                Text(if (live != null) stringResource(R.string.companion_overview_connected, live.name)
                    else stringResource(R.string.companion_overview_disconnected), style = MaterialTheme.typography.titleMedium)
            }
            Text(stringResource(R.string.companion_overview_paired_count, state.paired.size), style = MaterialTheme.typography.bodySmall)
            if (state.busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(stringResource(state.connectionStage ?: R.string.companion_connecting), style = MaterialTheme.typography.bodySmall)
            } else if (state.connectionError.isNotBlank()) {
                Text(stringResource(R.string.companion_connect_failed), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            state.sendingTo?.let { Text(stringResource(R.string.companion_sending, it), style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun CompanionTvDetail(manager: CompanionManager, state: CompanionState, peer: CompanionPeer, onBack: () -> Unit) {
    val reach = rememberReachability(manager, peer)
    val live = state.online && state.target?.fingerprint == peer.fingerprint
    val found = state.devices.firstOrNull { it.fingerprint == peer.fingerprint } ?: peer
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
            Column(Modifier.weight(1f)) {
                Text(peer.name, style = MaterialTheme.typography.titleLarge)
                CompanionReachabilityLabel(reach)
            }
        }
        if (live) {
            TvActionButton(stringResource(R.string.companion_remote_control), manager::showRemote,
                icon = Icons.Default.SettingsRemote, selected = true, fillWidth = true)
            TvActionButton(stringResource(R.string.playback_open), manager::showNowPlaying, icon = Icons.Default.PlayArrow, fillWidth = true)
        } else {
            TvActionButton(stringResource(R.string.companion_connect), { manager.connectRemote(found) },
                icon = Icons.Default.SettingsRemote, selected = true, fillWidth = true,
                enabled = !state.busy && reach.allowsActions(), loading = state.busy && state.target?.fingerprint == peer.fingerprint)
        }
        TvActionButton(stringResource(R.string.sync_title), { manager.showSettingsTransferTo(peer) },
            icon = Icons.Default.Sync, fillWidth = true, enabled = reach.allowsActions())
        if (live) TvActionButton(stringResource(R.string.companion_disconnect), { manager.disconnect() }, fillWidth = true)
        HorizontalDivider()
        Text(stringResource(R.string.companion_detail_connection), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.companion_detail_connection_hint), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        CompanionConnectionSettingsContent(manager, fixedPeer = peer)
        HorizontalDivider()
        CompanionUnpairButton(manager, peer, onUnpaired = onBack)
    }
}
