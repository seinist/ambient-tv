package com.ambient.tv.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ambient.tv.R
import com.ambient.tv.companion.CompanionConnectionSettingsContent
import com.ambient.tv.companion.CompanionViewModel
import com.ambient.tv.ui.common.SettingsSectionHeader
import com.ambient.tv.ui.common.TvActionButton
import com.ambient.tv.ui.common.TvSettingRow
import com.ambient.tv.ui.mobile.LocalHandheldUi

/**
 * One searchable settings page. Phone: the overview of paired TVs (details and pairing open as
 * their own pages). TV: status, pairing, paired phones, and the rarely needed switches under "Advanced".
 */
internal fun LazyListScope.companionSettingsItems() {
    item(key = "companion-devices") { CompanionDevicesSection() }
}

@Composable
private fun CompanionDevicesSection(vm: CompanionViewModel = hiltViewModel()) {
    val manager = vm.manager
    val state by manager.state.collectAsState()
    if (LocalHandheldUi.current) { com.ambient.tv.companion.CompanionDeviceHub(manager, state); return }
    var advanced by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Where am I: is this TV receiving, and how many phones are paired.
        Text(if (state.enabled) stringResource(R.string.companion_tv_status_on, state.paired.size)
            else stringResource(R.string.companion_tv_status_off), style = MaterialTheme.typography.titleMedium)
        state.connected.forEach { name -> Text(stringResource(R.string.companion_connected, name)) }
        if (state.controller.isNotEmpty()) {
            androidx.compose.material3.Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                color = androidx.compose.material3.MaterialTheme.colorScheme.primaryContainer) {
                Column(androidx.compose.ui.Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.companion_tv_controlled, state.controller))
                    TvActionButton(stringResource(R.string.companion_end_control), { manager.releaseRemoteControl() })
                }
            }
        }
        TvActionButton(stringResource(R.string.companion_pair_phone), manager::showPairing, selected = true, fillWidth = true)
        CompanionHint(R.string.companion_tv_only_hint)
        SettingsSectionHeader(stringResource(R.string.companion_tv_paired_phones))
        if (state.paired.isEmpty()) Text(stringResource(R.string.companion_tv_no_phones))
        state.paired.forEach { peer -> com.ambient.tv.companion.CompanionPeerRow(manager, peer) { mod -> Text(peer.name, modifier = mod) } }
        TvActionButton(stringResource(R.string.companion_advanced), { advanced = !advanced }, selected = advanced, fillWidth = true)
        if (advanced) {
            TvSettingRow(
                title = stringResource(R.string.settings_companion_receiver),
                body = stringResource(R.string.settings_companion_receiver_hint),
                checked = state.enabled,
                onCheckedChange = manager::enableReceiver,
            )
            CompanionConnectionSettingsContent(manager)
        }
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
