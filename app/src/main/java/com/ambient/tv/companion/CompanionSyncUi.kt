package com.ambient.tv.companion

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.ambient.tv.R
import com.ambient.tv.data.local.SettingsBackup
import com.ambient.tv.ui.common.AppDialog
import com.ambient.tv.ui.common.TvActionButton
import com.ambient.tv.ui.common.TvSettingRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun CompanionSyncDialog(
    vm: CompanionViewModel,
    navSnapshot: SettingsBackup? = null,
    onDismiss: () -> Unit,
) {
    val state by vm.manager.state.collectAsState()
    val handheld = com.ambient.tv.ui.mobile.LocalHandheldUi.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var source by remember { mutableStateOf(navSnapshot) }
    var mode by remember { mutableStateOf(if (navSnapshot != null) "nav_sounds" else "accounts") }
    var selected by remember { mutableStateOf<Set<String>?>(null) }
    var targetId by remember { mutableStateOf<String?>(null) }
    var credentials by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(navSnapshot == null) }
    var failed by remember { mutableStateOf(false) }
    var sent by remember { mutableStateOf(false) }
    var fromFile by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (navSnapshot == null) {
            val result = withContext(Dispatchers.IO) { runCatching { vm.settings.snapshot() } }
            source = result.getOrNull(); failed = result.isFailure; loading = false
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            loading = true
            val result = withContext(Dispatchers.IO) { runCatching {
                val raw = requireNotNull(context.contentResolver.openInputStream(uri)).use {
                    it.readBytesLimited(CompanionBackupPolicy.MAX_BYTES).toString(Charsets.UTF_8)
                }
                vm.settings.preview(raw)
            } }
            result.onSuccess { source = it; selected = null; fromFile = true }
            failed = result.isFailure; loading = false
        }
    }
    val choices = remember(source) { source?.let(::syncAccountChoices).orEmpty() }
    val accountIds = selected.takeUnless { mode == "settings" } ?: defaultSyncAccounts(choices)
    val target = state.paired.firstOrNull { it.fingerprint == targetId }
    val reverse = state.syncPeers[targetId]
    val connected = target != null && (reverse != null || state.target?.fingerprint == targetId && state.online)
    val supported = connected && (reverse ?: state.remote?.capabilities)?.contains(if (mode == "nav_sounds") "nav_sounds" else "settings_sync") == true
    val locked = sent || state.backupBusy || state.backupStatus == "pending"
    val dirty = vm.manager.settingsDirty && mode != "nav_sounds"
    AppDialog(title = stringResource(if (navSnapshot != null) R.string.nav_sync_title else R.string.sync_title),
        maxWidth = 760.dp, maxHeightFraction = .94f, onDismiss = onDismiss, footer = {
            TvActionButton(stringResource(R.string.companion_close), onDismiss)
            TvActionButton(stringResource(R.string.companion_send_to, target?.name.orEmpty()), {
                val snapshot = source ?: return@TvActionButton
                val result = runCatching {
                    val chosen = if (mode == "nav_sounds") snapshot else selectSyncAccounts(snapshot, accountIds)
                    CompanionBackupPolicy.prepare(chosen, credentials && mode != "nav_sounds", mode)
                }
                result.onSuccess {
                    vm.manager.sendSettings(it, mode, credentials && mode != "nav_sounds", target)
                    sent = true
                }.onFailure { failed = true }
            }, enabled = supported && !locked && !loading && !dirty && source != null && (mode != "accounts" || accountIds.isNotEmpty()))
        }) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(if (handheld) R.string.sync_to_tv_hint else R.string.sync_to_phone_hint))
            com.ambient.tv.ui.common.LocalNetworkPermissionSetting(onGranted = vm.manager::onLocalNetworkPermissionGranted)
            if (state.paired.isEmpty()) {
                Text(stringResource(R.string.sync_no_devices))
                TvActionButton(stringResource(R.string.companion_devices), { onDismiss(); vm.manager.showPanel() })
            }
            state.paired.forEach { peer ->
                TvActionButton(peer.name, {
                    targetId = peer.fingerprint
                    if (handheld && peer.fingerprint !in state.syncPeers && (state.target?.fingerprint != peer.fingerprint || !state.online)) vm.manager.connect(state.devices.firstOrNull { it.fingerprint == peer.fingerprint } ?: peer)
                }, selected = targetId == peer.fingerprint, enabled = !locked, fillWidth = true)
            }
            if (target != null && !connected) Text(stringResource(if (handheld) R.string.nav_sync_connecting else R.string.companion_phone_connect_first))
            if (connected && !supported) Text(stringResource(R.string.nav_sync_update))
            if (navSnapshot == null) {
                listOf("accounts" to R.string.sync_accounts, "settings" to R.string.sync_all, "nav_sounds" to R.string.sync_sounds).forEach { (key, label) ->
                    TvActionButton(stringResource(label), { mode = key }, selected = mode == key, enabled = !locked, fillWidth = true)
                }
            }
            Text(stringResource(if (mode == "nav_sounds") R.string.nav_sync_hint else R.string.sync_scope_hint))
            if (mode != "nav_sounds") {
                if (mode == "accounts") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TvActionButton(stringResource(R.string.sync_select_all), { selected = choices.map { it.key }.toSet() }, enabled = !locked)
                        TvActionButton(stringResource(R.string.sync_select_none), { selected = emptySet() }, enabled = !locked)
                    }
                    choices.forEach { choice ->
                        TvActionButton((if (choice.key in accountIds) "✓  " else "○  ") + choice.label,
                            { selected = if (choice.key in accountIds) accountIds - choice.key else accountIds + choice.key },
                            selected = choice.key in accountIds, enabled = !locked, fillWidth = true)
                    }
                }
                TvSettingRow(title = stringResource(R.string.companion_include_credentials), checked = credentials,
                    enabled = !locked, onCheckedChange = { credentials = it })
                if (dirty) Text(stringResource(R.string.sync_save_first))
            }
            if (navSnapshot == null) {
                Text(stringResource(if (fromFile) R.string.companion_selected_backup else R.string.companion_current_settings))
                TvActionButton(stringResource(R.string.companion_choose_backup), {
                    runCatching { picker.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }.onFailure { failed = true }
                }, enabled = !locked && !loading)
                if (fromFile) TvActionButton(stringResource(R.string.companion_current_settings), {
                    scope.launch {
                        loading = true
                        val result = withContext(Dispatchers.IO) { runCatching { vm.settings.snapshot() } }
                        result.onSuccess { source = it; selected = null; fromFile = false }
                        failed = result.isFailure; loading = false
                    }
                }, enabled = !locked && !loading)
            }
            if (loading) androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth())
            if (failed) Text(stringResource(R.string.companion_backup_failed))
            if (sent) {
                Text(stringResource(when (state.backupStatus) {
                    "applied" -> R.string.companion_import_done
                    "rejected", "expired" -> R.string.companion_import_cancelled
                    "failed" -> R.string.companion_backup_failed
                    "pending" -> R.string.sync_waiting
                    else -> if (!connected) R.string.companion_backup_failed else R.string.companion_transfer_running
                }))
                if (!state.backupBusy && state.backupStatus != "pending") TvActionButton(stringResource(R.string.sync_another), {
                    sent = false; targetId = null; failed = false
                })
            }
        }
    }
}
