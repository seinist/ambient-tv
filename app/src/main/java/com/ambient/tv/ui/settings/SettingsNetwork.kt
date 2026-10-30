package com.ambient.tv.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ambient.tv.R
import com.ambient.tv.ui.common.MediaSystem
import com.ambient.tv.ui.common.SettingsActionRow
import com.ambient.tv.ui.common.SettingsNavigationRow
import com.ambient.tv.ui.common.TvActionButton
import com.ambient.tv.ui.common.TvSettingRow
import com.ambient.tv.ui.common.TvTextField
import com.ambient.tv.ui.common.rememberPendingAction
import com.ambient.tv.ui.common.systemName
import com.ambient.tv.ui.common.systemString

/** Cross-category links open a target page without adding the caller page to Back history. */
internal val LocalOpenSettingsPage = androidx.compose.runtime.staticCompositionLocalOf<(SettingsCategory, String) -> Unit> { { _, _ -> } }
/** Register the MAC field of a lazily composed Wake-on-LAN group for deep links. */
internal val LocalSettingsWakeFieldFocus = androidx.compose.runtime.staticCompositionLocalOf<MutableMap<String, FocusRequester>> { mutableMapOf() }

@Composable
internal fun SettingsPageLink(target: SettingsCategory, id: String, label: Int = target.titleRes) {
    val open = LocalOpenSettingsPage.current
    SettingsNavigationRow(stringResource(label), modifier = Modifier, onClick = { open(target, id) })
}

internal fun LazyListScope.networkReceiverItems(viewModel: SettingsViewModel) {
    item(key = "receiver-configuration") {
        val running by viewModel.dlnaRendererRunning.collectAsState()
        val receivedTitle by viewModel.dlnaRendererReceivedTitle.collectAsState()
        var name by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(viewModel.dlnaRendererName()) }
        var enabled by remember { mutableStateOf(viewModel.dlnaRendererEnabled()) }
        var ask by remember { mutableStateOf(viewModel.dlnaRendererAsk()) }
        val receiverFocus = remember { FocusRequester() }
        var trusted by remember(enabled) { mutableIntStateOf(viewModel.dlnaRendererTrustedCount()) }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SettingsSubheader(stringResource(R.string.settings_system_receiver_identity))
            SettingsGroup(subtitle = stringResource(R.string.renderer_settings_hint), card = true) {
                Text(stringResource(if (running) R.string.settings_network_receiver_running else R.string.settings_network_receiver_stopped))
                receivedTitle?.let { Text(stringResource(R.string.settings_network_receiving, it)) }
                TvTextField(name, { name = it.take(80) }, stringResource(R.string.settings_network_receiver_name), requireExplicitEditing = true, modifier = Modifier.fillMaxWidth())
                SettingsActionRow {
                    TvActionButton(stringResource(R.string.files_save), { viewModel.saveDlnaRendererName(name) }, enabled = name.isNotBlank())
                }
                TvSettingRow(
                    title = stringResource(R.string.renderer_enabled),
                    body = stringResource(R.string.renderer_enabled_hint),
                    checked = enabled,
                    modifier = Modifier.focusRequester(receiverFocus),
                    onCheckedChange = { enabled = it; viewModel.setDlnaRendererEnabled(it) },
                )
            }
            SettingsSubheader(stringResource(R.string.settings_system_receiver_access))
            // Android 17+: without local network access the renderer cannot start; say so here.
            if (enabled) com.ambient.tv.ui.common.LocalNetworkPermissionSetting(onGranted = viewModel::refreshDlnaRenderer)
            TvSettingRow(
                title = stringResource(R.string.renderer_ask),
                body = stringResource(R.string.renderer_ask_hint),
                checked = ask,
                onCheckedChange = { ask = it; viewModel.setDlnaRendererAsk(it) },
                enabled = enabled,
            )
            if (enabled) {
                TvActionButton(
                    stringResource(R.string.renderer_forget_senders, trusted),
                    { receiverFocus.requestFocus(); viewModel.forgetDlnaRendererSenders(); trusted = 0 },
                    enabled = trusted > 0,
                )
            }
        }
    }
}

internal fun LazyListScope.wireGuardSystemItems(uiState: SettingsUiState, viewModel: SettingsViewModel) {
    if (uiState.wireGuardSummary.isBlank()) return
    item(key = "wireguard-routes-section") { SettingsSubheader(stringResource(R.string.settings_network_routes)) }
    item(key = "wireguard-route-plex") { WireGuardSystemSwitch(systemString(MediaSystem.PLEX, R.string.wg_use_plex), uiState.wireGuardPlexEnabled, viewModel::setWireGuardPlexEnabled, viewModel) }
    item(key = "wireguard-route-jellyfin") { WireGuardSystemSwitch(systemString(MediaSystem.JELLYFIN, R.string.wg_use_jellyfin), uiState.wireGuardJellyfinEnabled, viewModel::setWireGuardJellyfinEnabled, viewModel) }
    item(key = "wireguard-route-emby") { WireGuardSystemSwitch(systemString(MediaSystem.EMBY, R.string.wg_use_emby), uiState.wireGuardEmbyEnabled, viewModel::setWireGuardEmbyEnabled, viewModel) }
    item(key = "wireguard-route-tvheadend") { WireGuardSystemSwitch(systemString(MediaSystem.TVHEADEND, R.string.wg_use_tvheadend), uiState.wireGuardTvheadendEnabled, viewModel::setWireGuardTvheadendEnabled, viewModel) }
    item(key = "wireguard-related-section") { SettingsSubheader(stringResource(R.string.settings_system_sources_devices)) }
    item(key = "wireguard-servers-link") { SettingsPageLink(SettingsCategory.WAKE, "wireguard-servers", R.string.settings_network_wake) }
    item(key = "wireguard-devices-link") { SettingsPageLink(SettingsCategory.COMPANION, "wireguard-devices", R.string.settings_network_devices) }
}

internal fun LazyListScope.networkWakeItems(
    uiState: SettingsUiState,
    viewModel: SettingsViewModel,
    groups: List<WakeHostGroup>,
    requestExit: (() -> Unit) -> Unit,
) {
    // Cross-page links resolve the semantic target key against the current lazy item keys.
    item(key = "wake-intro") { Text(stringResource(R.string.settings_network_wake_hint)) }
    if (hasWakeProfilePicker(uiState)) item(key = "wake-profiles") { WakeProfilePicker(uiState, viewModel, requestExit) }
    item(key = "wake-hosts-section") { SettingsSubheader(stringResource(R.string.settings_system_wake_servers)) }
    groups.forEach { group ->
        item(key = "wake-group:" + group.key) { WakeGroupCard(group, viewModel) }
    }
    item(key = "wake-sources") {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (groups.isEmpty()) Text(stringResource(R.string.settings_wake_empty))
            SettingsSubheader(stringResource(R.string.settings_system_sources_network))
            Text(stringResource(R.string.settings_network_dlna_hint))
            SettingsPageLink(SettingsCategory.FILES, "wake-files", R.string.files_manage)
            SettingsPageLink(SettingsCategory.DLNA, "wake-dlna", R.string.dlna_servers)
        }
    }
    item(key = "wake-behaviour-section") { SettingsSubheader(stringResource(R.string.settings_system_wake_behaviour)) }
    item(key = "wake-retries") { WolRetrySetting(uiState.wolRetryCount, viewModel::onWolRetryCountChanged) }
    item(key = "wake-wireguard-link") { SettingsPageLink(SettingsCategory.WIREGUARD, "wake-wireguard") }
}

/** Choosing a saved profile switches the active server; unrelated to waking, so kept apart. */
@Composable
private fun WakeProfilePicker(uiState: SettingsUiState, viewModel: SettingsViewModel, requestExit: (() -> Unit) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SettingsSubheader(stringResource(R.string.settings_wake_profiles))
        if (uiState.savedServers.size > 1) {
            SettingsSelect(label = systemName(MediaSystem.PLEX), options = uiState.savedServers.map { server ->
                SettingsSelectOption(text = server.name + " · " + server.host, selected = server.id == uiState.activeServerId,
                    onClick = { requestExit { viewModel.selectSavedServer(server) } })
            })
        }
        if (uiState.savedJellyfinServers.size > 1) {
            SettingsSelect(label = systemName(MediaSystem.JELLYFIN), options = uiState.savedJellyfinServers.map { server ->
                SettingsSelectOption(text = server.name + " · " + server.host, selected = server.id == uiState.activeJellyfinServerId,
                    onClick = { requestExit { viewModel.selectJellyfinServer(server) } })
            })
        }
        if (uiState.savedEmbyServers.size > 1) {
            SettingsSelect(label = systemName(MediaSystem.EMBY), options = uiState.savedEmbyServers.map { server ->
                SettingsSelectOption(text = server.name + " · " + server.host, selected = server.id == uiState.activeEmbyServerId,
                    onClick = { requestExit { viewModel.selectEmbyServer(server) } })
            })
        }
    }
}

@Composable
private fun wakeMemberLabel(member: WakeMember): String = when (member.kind) {
    WakeMemberKind.PLEX -> systemName(MediaSystem.PLEX)
    WakeMemberKind.JELLYFIN -> systemName(MediaSystem.JELLYFIN)
    WakeMemberKind.EMBY -> systemName(MediaSystem.EMBY)
    WakeMemberKind.TVHEADEND -> systemName(MediaSystem.TVHEADEND)
    WakeMemberKind.FILES -> member.label + " (SMB/NFS)"
    WakeMemberKind.DLNA -> member.label + " (DLNA)"
}

/** One machine: a single MAC for all its services, one wake button, auto-wake per service. */
@Composable
private fun WakeGroupCard(group: WakeHostGroup, viewModel: SettingsViewModel) {
    val macFocus = remember(group.key) { FocusRequester() }
    val registry = LocalSettingsWakeFieldFocus.current
    androidx.compose.runtime.DisposableEffect(group.key, macFocus, registry) {
        registry[group.key] = macFocus
        onDispose { if (registry[group.key] === macFocus) registry.remove(group.key) }
    }
    val busy by viewModel.wakeGroupBusy.collectAsState()
    val resolved by viewModel.wakeMacResolved.collectAsState()
    val resolving = group.key in busy
    var pending by rememberPendingAction(resolving)
    var mac by remember(group.key) { mutableStateOf(group.mac) }
    LaunchedEffect(resolved) { resolved?.takeIf { it.groupKey == group.key }?.let { mac = it.mac } }
    val labels = group.members.map { wakeMemberLabel(it) }
    SettingsGroup(
        title = group.hosts.joinToString(" · ").ifBlank { labels.first() },
        subtitle = stringResource(R.string.settings_wake_group_services, labels.joinToString(", ")),
        card = true,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TvTextField(requireExplicitEditing = true, value = mac,
                onValueChange = { mac = it; viewModel.setGroupWakeMac(group, mac) },
                inputFormatter = { com.ambient.tv.wol.formatMacAddressInput(it) },
                label = stringResource(R.string.settings_mac_label), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), modifier = Modifier.focusRequester(macFocus).fillMaxWidth())
            if (mac.isNotBlank() && !isUsableWakeMac(mac)) {
                Text(stringResource(R.string.settings_status_mac_invalid), color = MaterialTheme.colorScheme.error)
            }
            if (group.macConflict) {
                Text(stringResource(R.string.settings_wake_mac_conflict, group.macs.joinToString(", ")))
                TvActionButton(stringResource(R.string.settings_wake_apply_mac, group.mac),
                    { mac = group.mac; viewModel.setGroupWakeMac(group, group.mac) })
            }
            SettingsActionRow {
                TvActionButton(text = stringResource(R.string.settings_resolve_mac), onClick = { pending = "resolve"; viewModel.lookupGroupMac(group, verify = false) },
                    enabled = !resolving && group.probe != null, loading = resolving && pending == "resolve")
                TvActionButton(text = stringResource(R.string.settings_verify_mac), onClick = { pending = "verify"; viewModel.lookupGroupMac(group, verify = true) },
                    enabled = !resolving && isUsableWakeMac(group.mac), loading = resolving && pending == "verify")
            }
            Text(stringResource(R.string.settings_system_auto_wake), style = MaterialTheme.typography.titleSmall)
            group.members.forEachIndexed { index, member ->
                AutoWakeRow(
                    stringResource(R.string.settings_wake_auto_for, labels[index]),
                    if (member.kind == WakeMemberKind.TVHEADEND) stringResource(R.string.settings_tvh_auto_wake_hint) else null,
                    member.mac, member.autoWake,
                ) { viewModel.setWakeMemberAutoWake(member, it) }
            }
            SettingsActionRow {
                if (group.members.any { it.kind.isMediaServer }) {
                    TvActionButton(stringResource(R.string.settings_test_connection), { viewModel.testGroup(group) })
                }
                TvActionButton(stringResource(R.string.settings_network_wake_now), { viewModel.wakeGroup(group) }, enabled = isUsableWakeMac(group.mac))
            }
            NasWakeDetails(group, viewModel)
        }
    }
}

/** Broadcast address, WoL port, wait time and tunnel of a NAS source, editable here as before grouping. */
@Composable
private fun NasWakeDetails(
    group: WakeHostGroup,
    settings: SettingsViewModel,
    vm: com.ambient.tv.ui.files.FilesViewModel = androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel(key = "network-file-settings"),
) {
    val members = group.members.filter { it.kind == WakeMemberKind.FILES }
    if (members.isEmpty()) return
    val state by vm.state.collectAsState()
    var editor by remember { mutableStateOf<com.ambient.tv.data.files.FileSource?>(null) }
    var opener by remember { mutableStateOf<FocusRequester?>(null) }
    fun close() { vm.resetEditor(); editor = null; runCatching { opener?.requestFocus() } }
    LaunchedEffect(state.editorSaved) {
        val saved = editor
        if (state.editorSaved > 0 && saved != null) { settings.dropSourceWakeDraft(saved.id); close() }
    }
    members.forEach { member ->
        val source = state.sources.firstOrNull { it.id == member.id } ?: return@forEach
        androidx.compose.runtime.key(source.id) {
            val focus = remember { FocusRequester() }
            SettingsNavigationRow(stringResource(R.string.settings_wake_nas_details, source.name),
                modifier = Modifier.focusRequester(focus), onClick = { vm.resetEditor(); opener = focus; editor = source })
        }
    }
    editor?.let { source ->
        com.ambient.tv.ui.common.OperationFeedbackScope(vm.feedback, "editor:${source.id}")
        com.ambient.tv.ui.files.NetworkSourceDialog(source, state.saving, state.testing, state.editorFeedback, vm.wireGuardConfigured(),
            onDismiss = ::close, onTest = vm::testNetwork, onSave = vm::saveNetwork, networkOnly = true, keyChallenge = state.keyChallenge)
    }
}

@Composable
private fun WolRetrySetting(selected: Int, onSelect: (Int) -> Unit) {
    SettingsSelect(
        label = stringResource(R.string.settings_wol_retries),
        description = stringResource(R.string.settings_wol_retries_hint),
        options = com.ambient.tv.wol.WOL_RETRY_COUNTS.map { count ->
        SettingsSelectOption(
            text = if (count == 0) stringResource(R.string.settings_wol_retries_off)
                else stringResource(R.string.settings_wol_retries_value, count),
            selected = selected == count,
            onClick = { onSelect(count) },
        )
    })
}
