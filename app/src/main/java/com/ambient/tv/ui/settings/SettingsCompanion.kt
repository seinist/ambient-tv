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
    item(key = "companion-network") {
        val vm: CompanionViewModel = hiltViewModel()
        if (!LocalHandheldUi.current) CompanionConnectionSettingsContent(vm.manager)
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
            com.ambient.tv.companion.CompanionDeviceHub(manager, state)
        } else {
            state.connected.forEach { name ->
                Text(stringResource(R.string.companion_connected, name))
            }
            if (state.paired.isEmpty()) Text(stringResource(R.string.sync_no_devices))
            state.paired.forEach { peer -> com.ambient.tv.companion.CompanionPeerRow(manager, peer) { mod -> Text(peer.name, modifier = mod) } }
            TvActionButton(stringResource(R.string.companion_pair_phone), manager::showPairing, fillWidth = true)
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
    if (!LocalHandheldUi.current) return
    val state by vm.manager.state.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SettingsSectionHeader(stringResource(R.string.settings_companion_transfer))
        CompanionHint(if (LocalHandheldUi.current) R.string.settings_companion_transfer_hint else R.string.sync_phone_to_tv_only)
        if (LocalHandheldUi.current) TvActionButton(stringResource(R.string.sync_title), vm.manager::showSettingsTransfer, fillWidth = true)
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
