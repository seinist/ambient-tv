package com.ambient.tv.companion

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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

/**
 * The device a transfer goes to until the user picks one. Re-evaluated on every state change, so
 * a TV that connects or turns up after the dialog opened is chosen without a tap; a stale second
 * pairing that nothing can reach no longer leaves the target empty.
 */
internal fun initialSyncTarget(state: CompanionState, selectable: (CompanionPeer) -> Boolean = { true }): String? =
    state.target?.fingerprint?.takeIf { state.online && state.paired.any { peer -> peer.fingerprint == it } }
        ?: state.paired.singleOrNull { it.fingerprint in state.syncPeers }?.fingerprint
        ?: state.paired.singleOrNull()?.fingerprint
        ?: state.paired.singleOrNull(selectable)?.fingerprint

internal fun syncCapability(mode: String) = when (mode) {
    "preferences" -> "settings_only"
    "nav_sounds" -> "nav_sounds"
    else -> "settings_sync"
}

@Composable
internal fun CompanionSyncDialog(vm: CompanionViewModel, navSnapshot: SettingsBackup? = null, onDismiss: () -> Unit) {
    val manager = vm.manager
    val state by manager.state.collectAsState()
    val handheld = com.ambient.tv.ui.mobile.LocalHandheldUi.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var source by remember { mutableStateOf(navSnapshot) }
    var mode by rememberSaveable { mutableStateOf(if (navSnapshot != null) "nav_sounds" else "preferences") }
    var selected by rememberSaveable { mutableStateOf<List<String>?>(null) }
    // Opened from a TV's card or detail page: that TV is the target.
    var pickedId by rememberSaveable { mutableStateOf(state.transferTarget) }
    var credentials by rememberSaveable { mutableStateOf(false) }
    var sent by rememberSaveable { mutableStateOf(false) }
    var loading by remember { mutableStateOf(navSnapshot == null) }
    var failed by remember { mutableStateOf(false) }
    var profilesOmitted by remember { mutableStateOf(false) }
    var fromFile by remember { mutableStateOf(false) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var changeTarget by rememberSaveable { mutableStateOf(false) }
    var attemptedTarget by remember { mutableStateOf<String?>(null) }
    fun reload() {
        scope.launch {
            loading = true
            val result = withContext(Dispatchers.IO) { runCatching { vm.settings.snapshot() } }
            result.exceptionOrNull()?.let { android.util.Log.w("CompanionSync", "Settings snapshot failed", it) }
            source = result.getOrNull(); failed = result.isFailure; loading = false; fromFile = false
        }
    }
    LaunchedEffect(Unit) { if (navSnapshot == null) reload() }
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
    val now by produceState(android.os.SystemClock.elapsedRealtime()) {
        while (true) { kotlinx.coroutines.delay(5_000); value = android.os.SystemClock.elapsedRealtime() }
    }
    val reach = state.paired.associate { it.fingerprint to manager.reachability(it, state, now) }
    fun selectable(peer: CompanionPeer) = reach[peer.fingerprint]?.let { if (handheld) it.allowsActions() else it == PeerReachability.CONNECTED } == true
    val targetId = pickedId?.takeIf { id -> state.paired.any { it.fingerprint == id } } ?: initialSyncTarget(state, ::selectable)
    val choices = remember(source) { source?.let(::syncAccountChoices).orEmpty() }
    // A selection from an earlier snapshot whose IDs no longer exist falls back to the default.
    val accountIds = selected?.toSet()?.intersect(choices.map { it.key }.toSet())?.takeIf { selected!!.isEmpty() || it.isNotEmpty() }
        ?: defaultSyncAccounts(choices)
    val target = state.paired.firstOrNull { it.fingerprint == targetId }
    val reverse = state.syncPeers[targetId]
    val connected = target != null && (reverse != null || state.target?.fingerprint == targetId && state.online)
    val supported = connected && (reverse ?: state.remote?.capabilities)?.contains(syncCapability(mode)) == true
    val directionAllowed = companionReceiveModeAllowed(handheld, mode)
    val includeAccounts = mode in setOf("accounts", "settings")
    val sendCredentials = credentials && includeAccounts
    if (handheld) DisposableEffect(manager) { manager.startPageSearch(); onDispose { manager.stopSearchUnlessPanel() } }
    val connecting = target != null && !connected && state.busy && state.target?.fingerprint == targetId
    val locked = sent || state.backupBusy
    val dirty = manager.settingsDirty && mode != "nav_sounds"
    // A running transfer keeps its device even if another TV connects meanwhile.
    LaunchedEffect(locked) { if (locked && pickedId == null) pickedId = targetId }
    LaunchedEffect(targetId, connected, reach[targetId], locked, attemptedTarget) {
        if (handheld && target != null && !connected && !locked && selectable(target) && attemptedTarget != targetId) {
            attemptedTarget = targetId
            manager.connect(state.devices.firstOrNull { it.fingerprint == targetId } ?: target)
        }
    }
    val reason = when {
        sent -> when {
            state.backupBusy -> R.string.companion_transfer_running
            state.backupStatus == "applied" -> R.string.companion_import_done
            state.backupStatus in setOf("rejected", "expired") -> R.string.companion_import_cancelled
            state.backupStatus == "failed" || !connected -> R.string.companion_backup_failed
            else -> R.string.sync_waiting
        }
        state.backupBusy -> R.string.companion_backup_failed_busy
        target == null -> when {
            state.paired.isEmpty() -> R.string.sync_no_devices
            handheld && state.paired.none(::selectable) -> R.string.sync_no_connectable
            else -> R.string.sync_reason_pick
        }
        connecting -> R.string.sync_reason_connecting
        !connected -> if (handheld) R.string.sync_no_connectable else R.string.sync_tv_no_phone
        !directionAllowed -> R.string.sync_phone_to_tv_only
        // Capabilities arrive with the TV's first status answer; until then this is still connecting.
        reverse == null && state.remote == null -> R.string.sync_reason_connecting
        !supported -> R.string.nav_sync_update
        dirty -> R.string.sync_save_first
        loading -> R.string.sync_loading
        failed -> R.string.companion_backup_failed
        mode == "accounts" && choices.isEmpty() -> R.string.sync_reason_no_accounts_found
        mode == "accounts" && accountIds.isEmpty() -> R.string.sync_reason_no_accounts
        else -> null
    }
    val canSend = directionAllowed && supported && !locked && !loading && !failed && !dirty && source != null && (mode != "accounts" || accountIds.isNotEmpty())
    AppDialog(title = stringResource(if (navSnapshot != null) R.string.nav_sync_title else R.string.sync_title),
        maxWidth = 680.dp, maxHeightFraction = .94f, onDismiss = onDismiss, footer = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                reason?.let { Text(stringResource(it)) }
                if (sent && profilesOmitted) Text(stringResource(R.string.sync_profiles_omitted))
                if (dirty && manager.saveSettingsBeforeTransfer != null) {
                    TvActionButton(stringResource(R.string.sync_save_reload), {
                        failed = manager.saveSettingsBeforeTransfer?.invoke { reload() } != true
                    }, fillWidth = true)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TvActionButton(stringResource(R.string.companion_close), onDismiss)
                    TvActionButton(stringResource(R.string.sync_send_action), {
                        val snapshot = source ?: return@TvActionButton
                        val result = runCatching {
                            val chosen = if (includeAccounts) selectSyncAccounts(snapshot, accountIds) else snapshot
                            CompanionBackupPolicy.prepareWithInfo(chosen, sendCredentials, mode)
                        }
                        result.onSuccess {
                            profilesOmitted = it.profilesOmitted
                            sent = manager.sendSettings(it.json, mode, sendCredentials, target); failed = !sent
                        }
                            .onFailure { failed = true }
                    }, enabled = canSend, selected = canSend, modifier = Modifier.weight(1f))
                }
            }
        }) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (target != null) {
                Text(target.name, style = androidx.tv.material3.MaterialTheme.typography.titleLarge)
                CompanionReachabilityLabel(reach[targetId] ?: PeerReachability.UNREACHABLE)
                if (state.paired.size > 1) TvActionButton(stringResource(R.string.companion_change_tv), { changeTarget = !changeTarget }, enabled = !locked)
            }
            if (target == null || changeTarget) state.paired.forEach { peer ->
                TvActionButton(peer.name, { pickedId = peer.fingerprint; attemptedTarget = null; changeTarget = false },
                    selected = targetId == peer.fingerprint, enabled = !locked && selectable(peer), fillWidth = true)
            }
            com.ambient.tv.ui.common.LocalNetworkPermissionSetting(onGranted = manager::onLocalNetworkPermissionGranted)
            if (state.paired.isEmpty()) TvActionButton(stringResource(R.string.companion_pair), manager::pairForTransfer, fillWidth = true)
            if (target != null && !connected && !connecting && !locked && handheld) {
                TvActionButton(stringResource(R.string.sync_retry_connection), { attemptedTarget = null; manager.discover() }, fillWidth = true)
                TvActionButton(stringResource(R.string.companion_connection_settings), manager::showConnectionSettings, fillWidth = true)
            }
            if (connecting || loading) androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth())
            if (!sent) {
                Text(stringResource(R.string.sync_confirmation_hint))
                if (navSnapshot == null) {
                    Text(stringResource(R.string.sync_content_label), style = androidx.tv.material3.MaterialTheme.typography.titleMedium)
                    listOf("preferences" to R.string.sync_preferences, "accounts" to R.string.sync_accounts, "settings" to R.string.sync_all).forEach { (key, label) ->
                        TvActionButton(stringResource(label), { mode = key; failed = false }, selected = mode == key, enabled = !locked, fillWidth = true)
                    }
                }
                Text(stringResource(if (mode == "preferences") R.string.sync_preferences_hint else if (mode == "nav_sounds") R.string.nav_sync_hint else R.string.sync_accounts_hint))
                if (includeAccounts) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TvActionButton(stringResource(R.string.sync_select_all), { selected = defaultSyncAccounts(choices).toList() }, enabled = !locked)
                        TvActionButton(stringResource(R.string.sync_select_none), { selected = emptyList() }, enabled = !locked)
                    }
                    choices.forEach { choice ->
                        TvSettingRow(title = choice.label, checked = choice.key in accountIds, enabled = !locked,
                            onCheckedChange = { checked -> selected = (if (checked) accountIds + choice.key else accountIds - choice.key).toList() })
                    }
                    if (WIREGUARD_CHOICE in accountIds) Text(stringResource(R.string.sync_wireguard_hint))
                    TvSettingRow(title = stringResource(R.string.companion_include_credentials), checked = credentials, enabled = !locked, onCheckedChange = { credentials = it })
                    Text(stringResource(if (credentials) R.string.sync_credentials_on else R.string.sync_credentials_off))
                }
                if (navSnapshot == null) {
                    TvActionButton(stringResource(R.string.sync_more_options), { advanced = !advanced }, selected = advanced, enabled = !locked, fillWidth = true)
                    if (advanced) {
                        if (handheld) TvActionButton(stringResource(R.string.sync_sounds), { mode = "nav_sounds" }, selected = mode == "nav_sounds", enabled = !locked, fillWidth = true)
                        Text(stringResource(if (fromFile) R.string.companion_selected_backup else R.string.companion_current_settings))
                        TvActionButton(stringResource(R.string.companion_choose_backup), {
                            runCatching { picker.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }.onFailure { failed = true }
                        }, enabled = !locked && !loading, fillWidth = true)
                        TvActionButton(stringResource(R.string.sync_reload), { reload() }, enabled = !locked && !loading, fillWidth = true)
                    }
                }
                if (failed) TvActionButton(stringResource(R.string.sync_reload), { reload() }, fillWidth = true)
            } else if (!state.backupBusy && state.backupStatus != "pending") {
                TvActionButton(stringResource(R.string.sync_another), { sent = false; failed = false; if (navSnapshot == null) reload() }, fillWidth = true)
            }
        }
    }
}
