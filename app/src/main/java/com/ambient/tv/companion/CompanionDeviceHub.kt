package com.ambient.tv.companion

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ambient.tv.R
import com.ambient.tv.ui.common.TvActionButton
import com.ambient.tv.ui.common.TvTextField

/** Shared phone entry point, both in Settings and from the always-visible remote shortcut. */
@Composable
internal fun CompanionDeviceHub(manager: CompanionManager, state: CompanionState) {
    var addDevice by rememberSaveable { mutableStateOf(false) }
    var manage by rememberSaveable { mutableStateOf(false) }
    var scanning by rememberSaveable { mutableStateOf(false) }
    var manualLink by rememberSaveable { mutableStateOf(false) }
    var invitation by rememberSaveable { mutableStateOf("") }
    DisposableEffect(manager) { manager.startPageSearch(); onDispose { manager.stopSearchUnlessPanel() } }
    LaunchedEffect(state.online) { if (state.online) addDevice = false }
    if (scanning) CompanionQrScannerDialog(manager) { scanning = false }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        com.ambient.tv.ui.common.LocalNetworkPermissionSetting(onGranted = manager::onLocalNetworkPermissionGranted)
        if (state.online) {
            Text(stringResource(R.string.companion_connected, state.target?.name.orEmpty()), style = MaterialTheme.typography.titleMedium)
            TvActionButton(stringResource(R.string.companion_remote_control), manager::showRemote,
                icon = Icons.Default.SettingsRemote, selected = true, fillWidth = true)
            TvActionButton(stringResource(R.string.sync_title), manager::showSettingsTransfer,
                icon = Icons.Default.Sync, fillWidth = true)
            TvActionButton(stringResource(R.string.playback_open), manager::showNowPlaying,
                icon = Icons.Default.PlayArrow, fillWidth = true)
        }
        state.sharedMedia?.let {
            Text(stringResource(R.string.companion_choose_tv), style = MaterialTheme.typography.titleMedium)
        }
        state.paired.filter { !state.online || it.fingerprint != state.target?.fingerprint || state.sharedMedia != null }.forEach { peer ->
            val reach = rememberReachability(manager, peer)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TvActionButton(stringResource(if (state.sharedMedia != null) R.string.companion_send_to else R.string.companion_remote_for, peer.name), {
                    val found = state.devices.firstOrNull { it.fingerprint == peer.fingerprint } ?: peer
                    if (state.sharedMedia != null) manager.sendSharedMediaTo(found) else manager.connectRemote(found)
                }, icon = Icons.Default.SettingsRemote, fillWidth = true,
                    enabled = !state.busy && state.sendingTo == null && reach.allowsActions())
                CompanionReachabilityLabel(reach)
            }
        }
        if (state.busy) {
            androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(stringResource(state.connectionStage ?: R.string.companion_connecting))
        }
        if (state.connectionError.isNotBlank() && !state.busy) Text(stringResource(R.string.companion_connect_failed), color = MaterialTheme.colorScheme.error)
        state.sendingTo?.let { Text(stringResource(R.string.companion_sending, it)) }
        if (!state.online && state.paired.isNotEmpty()) {
            TvActionButton(stringResource(R.string.companion_search), manager::discover, loading = state.searching, fillWidth = true)
            TvActionButton(stringResource(R.string.sync_title), manager::showSettingsTransfer, icon = Icons.Default.Sync, fillWidth = true)
        }
        if (state.paired.isNotEmpty()) TvActionButton(stringResource(R.string.companion_add_tv), { addDevice = !addDevice },
            icon = Icons.Default.Add, fillWidth = true, selected = addDevice)
        if (state.paired.isEmpty() || addDevice) {
            Text(stringResource(R.string.companion_pair_steps), style = MaterialTheme.typography.bodyMedium)
            TvActionButton(stringResource(R.string.companion_scan_qr), { scanning = true }, icon = Icons.Default.QrCodeScanner,
                selected = true, fillWidth = true, enabled = !state.busy)
            Text(stringResource(R.string.companion_code_alternative), style = MaterialTheme.typography.labelLarge)
            state.devices.filter { d -> state.paired.none { it.fingerprint == d.fingerprint } }.forEach { peer ->
                CompanionPairWithCode(manager, peer, enabled = !state.busy)
            }
            CompanionSearchStatus(state)
            TvActionButton(stringResource(R.string.companion_search), manager::discover, loading = state.searching, fillWidth = true)
            TvActionButton(stringResource(R.string.companion_paste_link), { manualLink = !manualLink }, fillWidth = true)
            if (manualLink) {
                TvTextField(invitation, { invitation = it.take(512) }, label = stringResource(R.string.companion_invitation), modifier = Modifier.fillMaxWidth())
                TvActionButton(stringResource(R.string.companion_pair), { manager.pair(invitation) }, enabled = invitation.isNotBlank() && !state.busy, fillWidth = true)
            }
        }
        if (state.paired.isNotEmpty()) {
            TvActionButton(stringResource(R.string.companion_manage_devices), { manage = !manage }, icon = Icons.Default.Settings, fillWidth = true, selected = manage)
            if (manage) {
                if (state.online) TvActionButton(stringResource(R.string.companion_disconnect), { manager.disconnect() }, fillWidth = true)
                TvActionButton(stringResource(R.string.companion_connection_settings), manager::showConnectionSettings, fillWidth = true)
                state.paired.forEach { peer -> CompanionPeerRow(manager, peer) { mod -> Text(peer.name, modifier = mod) } }
                if (state.rollbackAvailable) TvActionButton(stringResource(R.string.companion_restore_backup), manager::showRestore, fillWidth = true)
            }
        }
    }
}
