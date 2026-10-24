package com.ambient.tv.companion

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ambient.tv.R
import com.ambient.tv.ui.common.TvActionButton
import com.ambient.tv.ui.common.TvTextField

/** Phone pages drawn over the content area by the handheld navigation host instead of dialogs. */
internal fun CompanionState.handheldPageOpen(): Boolean = pairingFlow || remotePanel || panel && sharedMedia == null

/**
 * Full-page frame for a phone companion page: back arrow, title, optional subtitle and a
 * scrolling body. It swallows touches so the page kept alive underneath never reacts.
 */
@Composable
internal fun CompanionPageScaffold(
    title: String,
    onBack: () -> Unit,
    subtitle: String? = null,
    progress: Float? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    BackHandler(onBack = onBack)
    Surface(
        Modifier.fillMaxSize().pointerInput(Unit) { awaitEachGesture { awaitFirstDown(requireUnconsumed = false) } },
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    subtitle?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) }
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
        }
    }
}

/** "Handy & TV" as a page: overview of paired TVs, or one TV's details. */
@Composable
internal fun CompanionDevicesScreen(manager: CompanionManager) {
    val state by manager.state.collectAsState()
    CompanionPageScaffold(stringResource(R.string.companion_devices), onBack = manager::hidePanel) {
        CompanionDeviceHub(manager, state)
    }
}

/**
 * Guided pairing on the phone. 1: start pairing on the TV (the search shows when it is found),
 * 2: scan its QR code or type its code, 3: compare the codes; then a success step with next actions.
 */
@Composable
internal fun CompanionPairingScreen(manager: CompanionManager) {
    val state by manager.state.collectAsState()
    var scanStep by rememberSaveable { mutableStateOf(false) }
    var typeCode by rememberSaveable { mutableStateOf(false) }
    var more by rememberSaveable { mutableStateOf(false) }
    var invitation by rememberSaveable { mutableStateOf("") }
    // Set once a comparison code was shown: a later wait is the TV's confirmation, a later error a failed pairing.
    var compared by rememberSaveable { mutableStateOf(false) }
    var attempted by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.prompt) { if (state.prompt != null) compared = true }
    LaunchedEffect(state.busy) { if (state.busy) attempted = true }
    DisposableEffect(manager) { manager.startPageSearch(); onDispose { manager.stopSearchUnlessPanel() } }
    val done = state.pairingResult.isNotBlank()
    val step = when { done || state.prompt != null || compared && state.busy -> 3; scanStep -> 2; else -> 1 }
    val failed = attempted && !state.busy && !done && state.connectionError.isNotBlank()
    fun back() {
        if (step == 2 && !state.busy) {
            scanStep = false
            typeCode = false
            return
        }
        if (state.prompt != null) manager.confirm(false)
        manager.closePairingFlow()
    }
    val found = state.devices.filter { d -> state.paired.none { it.fingerprint == d.fingerprint } }
    CompanionPageScaffold(
        title = stringResource(R.string.companion_pairing_title),
        subtitle = if (done) null else stringResource(R.string.companion_pairing_step, step),
        progress = if (done) 1f else step / 3f,
        onBack = ::back,
    ) {
        when {
            done -> PairingDone(manager, state)
            state.prompt != null -> PairingCompare(manager, state.prompt!!)
            state.busy -> {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(stringResource(if (compared) R.string.companion_pairing_wait_tv else state.connectionStage ?: R.string.companion_connecting),
                    style = MaterialTheme.typography.bodyLarge)
            }
            !scanStep -> {
                Text(stringResource(R.string.companion_pairing_prepare_title), style = MaterialTheme.typography.headlineSmall)
                Text(stringResource(R.string.companion_pairing_prepare_body), style = MaterialTheme.typography.bodyLarge)
                com.ambient.tv.ui.common.LocalNetworkPermissionSetting(onGranted = manager::onLocalNetworkPermissionGranted)
                if (found.isNotEmpty()) found.forEach { tv ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                        Text(stringResource(R.string.companion_pairing_found, tv.name), style = MaterialTheme.typography.titleMedium)
                    }
                } else CompanionSearchStatus(state)
                TvActionButton(stringResource(R.string.companion_pairing_next_scan), { scanStep = true },
                    icon = Icons.Default.QrCodeScanner, selected = true, fillWidth = true)
            }
            else -> {
                Text(stringResource(R.string.companion_pairing_scan_title), style = MaterialTheme.typography.headlineSmall)
                if (failed) Text(stringResource(R.string.companion_pairing_failed), color = MaterialTheme.colorScheme.error)
                if (!typeCode) {
                    // Keyed so a failed attempt gets a fresh scanner.
                    key(failed) { CompanionQrScanner(onDecoded = { text -> attempted = true; manager.pair(text) }, modifier = Modifier.fillMaxWidth()) }
                    TextButton({ typeCode = true }) { Text(stringResource(R.string.companion_pairing_cant_scan)) }
                } else {
                    if (found.isEmpty()) Text(stringResource(R.string.companion_pairing_no_tv_found))
                    found.forEach { tv -> CompanionPairWithCode(manager, tv, enabled = !state.busy) }
                    TvActionButton(stringResource(R.string.companion_search), manager::discover, loading = state.searching, fillWidth = true)
                    TextButton({ typeCode = false }) { Text(stringResource(R.string.companion_scan_qr)) }
                }
                TextButton({ more = !more }) { Text(stringResource(R.string.companion_pairing_more)) }
                if (more) {
                    TvTextField(invitation, { invitation = it.take(512) }, label = stringResource(R.string.companion_invitation), modifier = Modifier.fillMaxWidth())
                    TvActionButton(stringResource(R.string.companion_pair), { attempted = true; manager.pair(invitation) },
                        enabled = invitation.isNotBlank() && !state.busy, fillWidth = true)
                }
            }
        }
    }
}

@Composable
private fun PairingCompare(manager: CompanionManager, prompt: CompanionPrompt) {
    Text(stringResource(R.string.companion_pairing_compare_title), style = MaterialTheme.typography.headlineSmall)
    Text(stringResource(R.string.companion_pair_with) + " " + prompt.name, style = MaterialTheme.typography.titleMedium)
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.28f))) {
        Text(prompt.code, Modifier.fillMaxWidth().padding(vertical = 24.dp), style = MaterialTheme.typography.displaySmall,
            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, letterSpacing = 3.sp, textAlign = TextAlign.Center)
    }
    Text(stringResource(R.string.companion_confirm_body), style = MaterialTheme.typography.bodyMedium)
    TvActionButton(stringResource(R.string.companion_codes_match), { manager.confirm(true) }, icon = Icons.Default.Check, selected = true, fillWidth = true)
    TvActionButton(stringResource(R.string.companion_cancel), { manager.confirm(false) }, fillWidth = true)
}

@Composable
private fun PairingDone(manager: CompanionManager, state: CompanionState) {
    val peer = state.paired.lastOrNull { it.name == state.pairingResult }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(Icons.Default.CheckCircle, null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
        Text(stringResource(R.string.companion_pairing_done_title, state.pairingResult), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Text(stringResource(R.string.companion_pairing_done_body), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
    }
    TvActionButton(stringResource(R.string.companion_remote_control), { manager.closePairingFlow(); manager.showRemote() },
        icon = Icons.Default.SettingsRemote, selected = true, fillWidth = true, enabled = state.online)
    TvActionButton(stringResource(R.string.sync_title), { manager.showSettingsTransferTo(peer) }, icon = Icons.Default.Sync, fillWidth = true)
    TvActionButton(stringResource(R.string.companion_pairing_done), manager::closePairingFlow, fillWidth = true)
}

/** Overview card for one paired TV: name, status and the two everyday actions; tap opens details. */
@Composable
internal fun CompanionTvCard(manager: CompanionManager, state: CompanionState, peer: CompanionPeer, onOpen: () -> Unit) {
    val reach = rememberReachability(manager, peer)
    val live = state.online && state.target?.fingerprint == peer.fingerprint
    Surface(onClick = onOpen, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Default.Tv, null, Modifier.size(28.dp).reachabilityDim(reach),
                    tint = if (live) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                Column(Modifier.weight(1f)) {
                    Text(peer.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    CompanionReachabilityLabel(reach)
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.companion_details))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val found = state.devices.firstOrNull { it.fingerprint == peer.fingerprint } ?: peer
                TvActionButton(stringResource(if (live) R.string.companion_remote_control else R.string.companion_connect),
                    { manager.connectRemote(found) }, icon = Icons.Default.SettingsRemote,
                    enabled = live || !state.busy && reach.allowsActions(), modifier = Modifier.weight(1f))
                TvActionButton(stringResource(R.string.sync_title), { manager.showSettingsTransferTo(peer) },
                    icon = Icons.Default.Sync, enabled = reach.allowsActions(), modifier = Modifier.weight(1f))
            }
        }
    }
}
