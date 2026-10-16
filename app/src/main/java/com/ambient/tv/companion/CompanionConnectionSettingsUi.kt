package com.ambient.tv.companion

import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import kotlinx.serialization.encodeToString
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ambient.tv.R
import com.ambient.tv.ui.common.*
import com.ambient.tv.ui.mobile.LocalHandheldUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first

@Composable
internal fun CompanionConnectionSettingsDialog(manager: CompanionManager) {
    var working by remember { mutableStateOf(false) }
    AppDialog(title = stringResource(R.string.companion_connection_settings), onDismiss = manager::hideConnectionSettings,
        maxWidth = 700.dp, maxHeightFraction = .94f, footer = {
            TvActionButton(stringResource(R.string.companion_close), manager::hideConnectionSettings, enabled = !working)
        }) {
        CompanionConnectionSettingsContent(manager, onWorkingChanged = { working = it })
    }
}

@Composable
internal fun CompanionConnectionSettingsContent(
    manager: CompanionManager,
    onWorkingChanged: (Boolean) -> Unit = {},
) {
    val state by manager.state.collectAsState()
    val context = LocalContext.current
    val handheld = LocalHandheldUi.current
    val scope = rememberCoroutineScope()
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    var pendingAction by remember { mutableStateOf<String?>(null) }
    SideEffect { onWorkingChanged(working) }
    val overlay = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        scope.launch {
            lifecycle.currentStateFlow.first { it.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) }
            runCatching { manager.enableBackgroundReceiver(true) }
                .onFailure { manager.notice(R.string.companion_launch_permission, error = true) }
        }
    }
    val vpnPermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { }
    val peer = state.paired.firstOrNull { it.fingerprint == selected }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        LocalNetworkPermissionSetting(onGranted = manager::onLocalNetworkPermissionGranted)
        if (!handheld) {
            TvSettingRow(title = stringResource(R.string.companion_receiver_background),
                body = stringResource(R.string.companion_receiver_hint), checked = state.backgroundReceiver,
                onCheckedChange = { enabled ->
                    runCatching {
                        if (enabled && !manager.backgroundLaunchAllowed()) {
                            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + context.packageName))
                            overlay.launch(intent)
                        } else manager.enableBackgroundReceiver(enabled)
                    }.onFailure { manager.notice(R.string.companion_launch_permission, error = true) }
                })
            if (state.backgroundReceiver && !manager.backgroundLaunchAllowed()) Text(stringResource(R.string.companion_launch_permission))
        }
        if (handheld) {
            Text(stringResource(R.string.settings_companion_select_device))
            if (state.paired.isEmpty()) Text(stringResource(R.string.sync_no_devices))
            DisposableEffect(manager) { manager.startPageSearch(); onDispose { manager.stopSearchUnlessPanel() } }
            state.paired.forEach { item ->
                // Settings stay editable for unreachable devices: they are how one becomes reachable.
                CompanionPeerRow(manager, item, onUnpaired = { if (selected == item.fingerprint) selected = null }) { mod ->
                    TvActionButton(item.name, { selected = item.fingerprint }, selected = selected == item.fingerprint,
                        enabled = !working, modifier = mod)
                }
            }
            if (peer != null) key(peer.fingerprint) {
                var settings by rememberSaveable(stateSaver = Saver<CompanionConnectionSettings, String>(
                    save = { companionJson.encodeToString(it) }, restore = { companionJson.decodeFromString(it) }
                )) { mutableStateOf(manager.connectionSettings(peer)) }
                var portText by rememberSaveable { mutableStateOf(settings.port.takeIf { it > 0 }?.toString().orEmpty()) }
                var wakePortText by rememberSaveable { mutableStateOf(settings.wakePort.toString()) }
                fun current() = settings.copy(port = if (portText.isBlank()) 0 else portText.toIntOrNull() ?: -1,
                    wakePort = wakePortText.toIntOrNull() ?: -1)
                fun save(testWake: Boolean = false, testConnection: Boolean = false) {
                    pendingAction = if (testWake) "wake" else if (testConnection) "test" else "save"
                    working = true
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { manager.saveConnectionSettings(peer, current()) }
                            if (testWake) {
                                withContext(Dispatchers.IO) { manager.testWake(peer) }
                                manager.notice(R.string.companion_wake_sent, peer.name)
                            } else if (testConnection) manager.connect(peer, allowWake = false)
                            else manager.notice(R.string.companion_connection_saved, peer.name)
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) {
                            manager.notice(if (testWake) R.string.companion_wake_failed else R.string.companion_connection_failed, peer.name, error = true)
                        } finally { working = false; pendingAction = null }
                    }
                }
                TvSettingRow(title = stringResource(R.string.companion_connection_vpn_toggle),
                    body = stringResource(R.string.companion_connection_vpn_hint), checked = settings.wireGuard,
                    enabled = !working, onCheckedChange = { settings = settings.copy(wireGuard = it, vpnHost = settings.vpnHost.ifBlank { peer.host }) })
                if (settings.wireGuard) {
                    TvTextField(settings.vpnHost, { settings = settings.copy(vpnHost = it.trim().take(15)) }, stringResource(R.string.companion_connection_address), requireExplicitEditing = true)
                    if (VpnService.prepare(context) != null) TvActionButton(stringResource(R.string.companion_connection_vpn_permission), {
                        VpnService.prepare(context)?.let(vpnPermission::launch)
                    })
                }
                TvTextField(portText, { portText = it.filter(Char::isDigit).take(5) }, stringResource(R.string.companion_connection_port, peer.port), requireExplicitEditing = true)
                TvSettingRow(title = stringResource(R.string.companion_connection_wake_toggle),
                    body = stringResource(R.string.companion_connection_wake_hint), checked = settings.wake,
                    enabled = !working, onCheckedChange = { settings = settings.copy(wake = it) })
                TvTextField(settings.mac, { settings = settings.copy(mac = it.trim().take(17)) }, stringResource(R.string.companion_connection_mac), requireExplicitEditing = true)
                TvActionButton(stringResource(R.string.companion_connection_detected_mac), {
                    settings = settings.copy(mac = manager.connectionSettings(peer).mac)
                }, enabled = !working)
                if (settings.wake || settings.mac.isNotBlank()) {
                    TvTextField(settings.broadcast, { settings = settings.copy(broadcast = it.trim().take(15)) }, stringResource(R.string.companion_connection_broadcast), requireExplicitEditing = true)
                    TvTextField(wakePortText, { wakePortText = it.filter(Char::isDigit).take(5) }, stringResource(R.string.companion_connection_wake_port), requireExplicitEditing = true)
                    TvActionButton(stringResource(R.string.companion_connection_test_wake), { save(testWake = true) },
                        enabled = !working && current().valid() && validCompanionMac(settings.mac), loading = working && pendingAction == "wake")
                }
                if (!current().valid()) Text(stringResource(R.string.companion_connection_invalid))
                TvActionButton(stringResource(R.string.companion_connection_save), { save() }, enabled = !working && current().valid(), loading = working && pendingAction == "save")
                TvActionButton(stringResource(R.string.companion_connection_test), { save(testConnection = true) }, enabled = !working && !state.busy && current().valid() && manager.reachability(peer, state) != PeerReachability.UNREACHABLE, loading = (working && pendingAction == "test") || state.busy)
            }
        }
        if (state.busy) Text(stringResource(state.connectionStage ?: R.string.companion_connecting))
        // Details are posted as notifications; inline only a short hint.
        if (state.connectionError.isNotBlank() && !state.busy) Text(stringResource(R.string.companion_error_hint), style = MaterialTheme.typography.bodySmall)
    }
}
