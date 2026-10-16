package com.ambient.tv.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ambient.tv.R
import com.ambient.tv.companion.CompanionConnectionSettingsContent
import com.ambient.tv.companion.CompanionViewModel
import com.ambient.tv.companion.allowsActions
import com.ambient.tv.ui.common.SettingsSectionHeader
import com.ambient.tv.ui.common.TvActionButton
import com.ambient.tv.ui.common.TvSettingRow
import com.ambient.tv.ui.mobile.LocalHandheldUi

/** One searchable settings page; pairing and transfers retain their confirmation flows. */
internal fun LazyListScope.companionSettingsItems() {
    item(key = "companion-devices") { CompanionDevicesSection() }
    item(key = "companion-transfer") { SettingsPageLink(SettingsCategory.TRANSFER, "companion-transfer") }
    item(key = "companion-network") {
        val vm: CompanionViewModel = hiltViewModel()
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SettingsSectionHeader(stringResource(R.string.settings_companion_network))
            CompanionHint(if (LocalHandheldUi.current) R.string.settings_companion_network_hint else R.string.companion_receiver_hint)
            CompanionConnectionSettingsContent(vm.manager)
        }
    }
}

@Composable
private fun CompanionDevicesSection(vm: CompanionViewModel = hiltViewModel()) {
    val manager = vm.manager
    val state by manager.state.collectAsState()
    val handheld = LocalHandheldUi.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SettingsSectionHeader(stringResource(R.string.settings_companion_devices))
        CompanionHint(if (handheld) R.string.settings_companion_devices_hint else R.string.companion_tv_only_hint)
        if (handheld) {
            HandheldCompanionDevices(manager, state)
        } else {
            state.connected.forEach { name ->
                Text(stringResource(R.string.companion_connected, name))
            }
            if (state.paired.isEmpty()) Text(stringResource(R.string.sync_no_devices))
            state.paired.forEach { peer -> com.ambient.tv.companion.CompanionPeerRow(manager, peer) { mod -> Text(peer.name, modifier = mod) } }
            TvActionButton(stringResource(R.string.companion_pair_phone), manager::showPanel, fillWidth = true)
            if (state.online) TvActionButton(stringResource(R.string.companion_disconnect), { manager.disconnect() }, fillWidth = true)
        }
        if (!handheld) {
            TvSettingRow(
                title = stringResource(R.string.settings_companion_receiver),
                body = stringResource(R.string.settings_companion_receiver_hint),
                checked = state.enabled,
                onCheckedChange = manager::enableReceiver,
            )
            if (state.controller.isNotEmpty()) {
                TvActionButton(stringResource(R.string.companion_end_control), { manager.releaseRemoteControl() }, fillWidth = true)
            }
        }
        // Errors are notifications; the network section below shows the one short inline hint.
        if (state.busy) Text(stringResource(state.connectionStage ?: R.string.companion_connecting))
    }
}

@Composable
internal fun CompanionTransferSection(vm: CompanionViewModel = hiltViewModel()) {
    val state by vm.manager.state.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SettingsSectionHeader(stringResource(R.string.settings_companion_transfer))
        CompanionHint(R.string.settings_companion_transfer_hint)
        TvActionButton(stringResource(R.string.sync_title), vm.manager::showSettingsTransfer, fillWidth = true)
        if (state.rollbackAvailable) {
            TvActionButton(stringResource(R.string.companion_restore_backup), vm.manager::showRestore, fillWidth = true)
        }
    }
}

@Composable
private fun CompanionHint(res: Int) {
    Text(stringResource(res), style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/**
 * Phone: TVs already linked to this phone, then - clearly apart - new TVs found on this Wi-Fi.
 * The page searches by itself while it is open.
 */
@Composable
private fun HandheldCompanionDevices(manager: com.ambient.tv.companion.CompanionManager, state: com.ambient.tv.companion.CompanionState) {
    androidx.compose.runtime.DisposableEffect(manager) {
        manager.startPageSearch()
        onDispose { manager.stopSearchUnlessPanel() }
    }
    SettingsSectionHeader(stringResource(R.string.companion_section_paired))
    if (state.paired.isEmpty()) Text(stringResource(R.string.companion_no_paired_tv), style = MaterialTheme.typography.bodySmall)
    state.paired.forEach { peer ->
        val connected = state.online && state.target?.fingerprint == peer.fingerprint
        val found = state.devices.firstOrNull { it.fingerprint == peer.fingerprint }
        val reach = com.ambient.tv.companion.rememberReachability(manager, peer)
        com.ambient.tv.companion.CompanionPeerRow(manager, peer, reach) { mod ->
            TvActionButton(
                if (connected) stringResource(R.string.companion_connected, peer.name) else peer.name,
                onClick = { if (!connected && !state.busy) manager.connect(found ?: peer) },
                selected = connected,
                enabled = connected || reach.allowsActions(),
                modifier = mod,
            )
        }
    }
    if (state.online) {
        TvActionButton(stringResource(R.string.playback_open), manager::showNowPlaying, fillWidth = true)
        TvActionButton(stringResource(R.string.companion_remote_control), manager::showRemote, fillWidth = true)
        TvActionButton(stringResource(R.string.companion_disconnect), { manager.disconnect() }, fillWidth = true)
    }

    SettingsSectionHeader(stringResource(R.string.companion_section_found))
    CompanionHint(R.string.companion_found_hint)
    com.ambient.tv.companion.CompanionSearchStatus(state)
    state.devices.filter { d -> state.paired.none { it.fingerprint == d.fingerprint } }.forEach { peer ->
        com.ambient.tv.companion.CompanionPairWithCode(manager, peer, enabled = !state.busy)
    }
    TvActionButton(stringResource(R.string.companion_search), manager::discover, fillWidth = true, loading = state.searching)
    TvActionButton(stringResource(R.string.companion_pair_qr_or_link), manager::showPanel, fillWidth = true)
}
