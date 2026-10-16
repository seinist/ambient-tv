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
    var fromTv by remember { mutableStateOf(false) }
    val pull = handheld && fromTv && navSnapshot == null
    // The phone keeps searching while the dialog is open so reachable TVs are recognised.
    if (handheld) DisposableEffect(vm.manager) { vm.manager.startPageSearch(); onDispose { vm.manager.stopSearchUnlessPanel() } }
    val now by produceState(android.os.SystemClock.elapsedRealtime()) {
        while (true) { kotlinx.coroutines.delay(5_000); value = android.os.SystemClock.elapsedRealtime() }
    }
    val reach = state.paired.associate { it.fingerprint to vm.manager.reachability(it, state, now) }
    // Phone: any actionable TV (the app connects or wakes it). TV: only phones connected right now.
    fun selectable(peer: CompanionPeer) = reach[peer.fingerprint]?.let { if (handheld) it.allowsActions() else it == PeerReachability.CONNECTED } == true
    val connectable = state.paired.filter(::selectable)
    val connecting = target != null && !connected && state.busy && state.target?.fingerprint == targetId
    val pullSupported = connected && state.target?.fingerprint == targetId && state.remote?.capabilities?.contains("backup_request") == true
    // A stale "pending" from an earlier transfer no longer locks the dialog; only this one does.
    val locked = sent || state.backupBusy || state.pulling
    val dirty = vm.manager.settingsDirty && mode != "nav_sounds" && !pull
    // Never a dead button: say why sending is not possible yet.
    val reason: Int? = when {
        locked || loading || state.paired.isEmpty() -> null
        target == null -> if (connectable.isEmpty()) null else R.string.sync_reason_pick
        !connected -> if (connecting) R.string.sync_reason_connecting else if (handheld) R.string.sync_reason_connect_failed else R.string.sync_tv_no_phone
        pull && !pullSupported -> R.string.nav_sync_update
        !pull && !supported -> R.string.nav_sync_update
        dirty -> R.string.sync_save_first
        !pull && mode == "accounts" && accountIds.isEmpty() -> R.string.sync_reason_no_accounts
        else -> null
    }
    AppDialog(title = stringResource(if (navSnapshot != null) R.string.nav_sync_title else R.string.sync_title),
        maxWidth = 760.dp, maxHeightFraction = .94f, onDismiss = onDismiss, footer = {
            TvActionButton(stringResource(R.string.companion_close), onDismiss)
            if (pull) TvActionButton(stringResource(R.string.sync_pull_action), {
                vm.manager.requestBackupFromTv(mode, credentials && mode != "nav_sounds")
            }, enabled = pullSupported && !locked, loading = state.pulling)
            else TvActionButton(stringResource(R.string.companion_send_to, target?.name.orEmpty()), {
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
            if (handheld && navSnapshot == null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TvActionButton(stringResource(R.string.sync_direction_to_tv), { fromTv = false }, selected = !fromTv, enabled = !locked)
                TvActionButton(stringResource(R.string.sync_direction_from_tv), { fromTv = true; if (mode == "nav_sounds") mode = "accounts" }, selected = fromTv, enabled = !locked)
            }
            Text(stringResource(if (pull) R.string.sync_pull_hint else if (handheld) R.string.sync_to_tv_hint else R.string.sync_to_phone_hint))
            if (!handheld) Text(stringResource(R.string.sync_tv_pull_hint), style = androidx.tv.material3.MaterialTheme.typography.bodySmall)
            if (state.paired.isNotEmpty()) Text(stringResource(if (handheld) R.string.sync_pick_device else R.string.sync_pick_phone), style = androidx.tv.material3.MaterialTheme.typography.bodySmall)
            com.ambient.tv.ui.common.LocalNetworkPermissionSetting(onGranted = vm.manager::onLocalNetworkPermissionGranted)
            if (state.paired.isEmpty()) {
                Text(stringResource(R.string.sync_no_devices))
                TvActionButton(stringResource(R.string.companion_devices), { onDismiss(); vm.manager.showPanel() })
            }
            else if (connectable.isEmpty() && !state.searching) {
                // Nothing can be reached: explain what to do instead of offering dead choices.
                Text(stringResource(if (handheld) R.string.sync_no_connectable else R.string.sync_tv_no_phone))
                if (handheld) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TvActionButton(stringResource(R.string.companion_search), vm.manager::discover)
                    TvActionButton(stringResource(R.string.companion_devices), { onDismiss(); vm.manager.showPanel() })
                }
            }
            state.paired.forEach { peer ->
                val r = reach[peer.fingerprint] ?: PeerReachability.UNREACHABLE
                Column(Modifier.reachabilityDim(r)) {
                    TvActionButton(peer.name, {
                        targetId = peer.fingerprint
                        // Picking a TV connects (and wakes) it automatically.
                        if (handheld && peer.fingerprint !in state.syncPeers && (state.target?.fingerprint != peer.fingerprint || !state.online)) vm.manager.connect(state.devices.firstOrNull { it.fingerprint == peer.fingerprint } ?: peer)
                    }, selected = targetId == peer.fingerprint, enabled = !locked && selectable(peer), fillWidth = true)
                    CompanionReachabilityLabel(r)
                }
            }
            if (connecting) {
                androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(stringResource(state.connectionStage ?: R.string.sync_connecting_to, target?.name.orEmpty()))
            }
            reason?.let { Text(stringResource(it), style = androidx.tv.material3.MaterialTheme.typography.bodySmall) }
            if (state.pulling) Text(stringResource(R.string.sync_pull_running))
            if (navSnapshot == null) {
                listOf("accounts" to R.string.sync_accounts, "settings" to R.string.sync_all, "nav_sounds" to R.string.sync_sounds).filter { !pull || it.first != "nav_sounds" }.forEach { (key, label) ->
                    TvActionButton(stringResource(label), { mode = key }, selected = mode == key, enabled = !locked, fillWidth = true)
                }
            }
            Text(stringResource(if (mode == "nav_sounds") R.string.nav_sync_hint else R.string.sync_scope_hint))
            if (mode != "nav_sounds") {
                if (mode == "accounts" && !pull) {
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
            if (navSnapshot == null && !pull) {
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
