package com.ambient.tv.companion

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.ambient.tv.ui.common.TvTextField
import com.ambient.tv.ui.common.TvActionButton
import com.ambient.tv.R
import com.ambient.tv.ui.mobile.LocalHandheldUi
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class CompanionViewModel @Inject constructor(val manager: CompanionManager, val mapper: CompanionMediaMapper, val settings: CompanionSettingsService) : ViewModel()

@Composable
fun CompanionButton(viewModel: CompanionViewModel = hiltViewModel()) {
    com.ambient.tv.ui.common.TvChip(stringResource(R.string.companion_devices), icon = Icons.Default.Cast, onClick = viewModel.manager::showPanel)
    // The status itself remains informational; only this explicit action takes focus.
}

/**
 * Handheld bottom output row ("Ausgabe: Dieses Handy").
 * Hidden on television and unless a paired TV is the live connection.
 */
internal fun showCompanionOutputBar(state: CompanionState): Boolean {
    val target = state.target ?: return false
    return state.online && state.paired.any { it.fingerprint == target.fingerprint }
}

@Composable
fun CompanionOutputBar(viewModel: CompanionViewModel = hiltViewModel()) {
    val manager = viewModel.manager
    val state by manager.state.collectAsState()
    if (!LocalHandheldUi.current || !showCompanionOutputBar(state)) return
    Surface {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            if (state.enqueueSelection) TextButton(manager::cancelQueueSelection) { Text(stringResource(R.string.companion_queue_cancel)) }
            TextButton(manager::showPanel, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.Cast, null)
                Spacer(Modifier.width(8.dp))
                Text(state.target?.let { stringResource(if (state.online) R.string.companion_connected else R.string.companion_output_offline, it.name) }
                    ?: stringResource(R.string.companion_this_phone))
            }
            if (state.online) TextButton(manager::showRemote) { Text(stringResource(R.string.companion_remote_control)) }
        }
    }
}

@Composable
fun CompanionRemotePlaybackBar(viewModel: CompanionViewModel = hiltViewModel()) {
    if (!LocalHandheldUi.current) return
    val state by viewModel.manager.state.collectAsState()
    CompanionRemoteMiniPlayer(viewModel.manager, state)
}

@Composable
fun CompanionDialogs(viewModel: CompanionViewModel = hiltViewModel()) {
    CompositionLocalProvider(
        LocalContentColor provides MaterialTheme.colorScheme.onSurface,
        androidx.tv.material3.LocalContentColor provides MaterialTheme.colorScheme.onSurface,
    ) {
        CompanionDialogsContent(viewModel)
    }
}

@Composable
private fun CompanionDialogsContent(viewModel: CompanionViewModel) {
    val manager = viewModel.manager
    val state by manager.state.collectAsState()
    val handheld = LocalHandheldUi.current
    val connectionEditor = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    val playbackRequest by manager.playbackRequest.collectAsState()
    if (handheld && playbackRequest != null && state.backupOffer == null && !state.restoreDialog && state.prompt == null) {
        CompanionPlaybackTargetDialog(manager, state)
        return
    }
    if (state.connectionSettingsPanel && state.backupOffer == null && !state.restoreDialog && state.prompt == null) { connectionEditor.SaveableStateProvider("connection") { CompanionConnectionSettingsDialog(manager) }; return }
    if (handheld && state.nowPlayingPanel) CompanionNowPlayingDialog(manager, state)
    if (!handheld) CompanionReceiverStatus(manager)
    if (state.backupOffer != null || state.restoreDialog) {
        CompanionImportDialog(viewModel)
        return
    }
    state.prompt?.let { prompt ->
        val screenPixels = androidx.compose.ui.platform.LocalContext.current.resources.displayMetrics.heightPixels
        val compact = with(androidx.compose.ui.platform.LocalDensity.current) { screenPixels.toDp() } < 500.dp
        com.ambient.tv.ui.common.AppDialog(onDismiss = { manager.confirm(false) },
            title = stringResource(R.string.companion_confirm_title),
            maxWidth = 520.dp,
            maxHeightFraction = 0.94f,
            footer = {
                com.ambient.tv.ui.common.TvActionButton(stringResource(R.string.companion_cancel), onClick = { manager.confirm(false) })
                com.ambient.tv.ui.common.TvActionButton(stringResource(R.string.companion_codes_match),
                    onClick = { manager.confirm(true) }, selected = true, icon = Icons.Default.Check)
            }) {
                val device: @Composable () -> Unit = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp)) {
                        Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                            Icon(if (handheld) Icons.Default.Tv else Icons.Default.Smartphone, null,
                                Modifier.padding(if (compact) 8.dp else 12.dp).size(if (compact) 24.dp else 28.dp), tint = MaterialTheme.colorScheme.onSurface)
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(stringResource(R.string.companion_pair_with), style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(prompt.name, style = if (compact) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                val code: @Composable () -> Unit = {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)),
                    ) {
                        Column(Modifier.padding(horizontal = 12.dp, vertical = if (compact) 10.dp else 20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 8.dp)) {
                            Text(stringResource(R.string.companion_comparison_code), style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(prompt.code, style = if (compact) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.headlineLarge,
                                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, letterSpacing = if (compact) 2.sp else 3.sp,
                                color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
                        }
                    }
                }
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 20.dp)) {
                    if (compact) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Box(Modifier.weight(1f)) { device() }
                            Box(Modifier.weight(1.15f)) { code() }
                        }
                    } else {
                        device()
                        code()
                    }
                    Text(stringResource(R.string.companion_confirm_body), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        return
    }
    if (handheld && state.remotePanel) { CompanionRemoteDialog(manager); return }
    if (state.settingsPanel) { CompanionSyncDialog(viewModel, onDismiss = manager::hideSettingsTransfer); return }
    if (!state.panel) return
    if (!handheld && state.invitation != null) {
        val link = state.invitation!!
        com.ambient.tv.ui.common.QrCodeDialog(
            title = stringResource(R.string.companion_pair),
            hint = stringResource(R.string.companion_qr_hint),
            payload = link,
            code = state.pairCode.takeIf { it.isNotBlank() },
            onDismiss = manager::closePairing,
            footer = {
                com.ambient.tv.ui.common.TvActionButton(
                    stringResource(R.string.companion_close),
                    onClick = manager::closePairing,
                )
            },
        )
        return
    }
    val devicesFocus = remember { FocusRequester() }
    val invitationFocus = remember { FocusRequester() }
    var invitation by remember { mutableStateOf("") }
    var scanning by remember { mutableStateOf(false) }
    if (handheld && scanning) { CompanionQrScannerDialog(manager) { scanning = false }; return }
    com.ambient.tv.ui.common.AppDialog(onDismiss = manager::hidePanel,
        title = stringResource(if (handheld) R.string.companion_connect_tv else R.string.companion_pair_phone),
        footer = { com.ambient.tv.ui.common.TvActionButton(stringResource(R.string.companion_close), onClick = manager::hidePanel) }) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            com.ambient.tv.ui.common.LocalNetworkPermissionSetting(onGranted = manager::onLocalNetworkPermissionGranted)
            Text(stringResource(R.string.companion_connection_availability))
            if (handheld) {
                if (state.online) {
                    TvActionButton(stringResource(R.string.playback_open), manager::showNowPlaying)
                    TvActionButton(stringResource(R.string.companion_remote_control), manager::showRemote)
                }
                state.sharedMedia?.let { media ->
                    Text(if (media.kind == "url") com.ambient.tv.data.shared.SharedMediaLink.parse(media.id)?.title.orEmpty()
                        else stringResource(R.string.companion_shared_youtube), style = MaterialTheme.typography.titleMedium)
                }
                TvActionButton(stringResource(R.string.companion_disconnect), { manager.disconnect() })
                Text(stringResource(R.string.companion_scan_hint))
                if (handheld) TvActionButton(stringResource(R.string.companion_scan_qr), { scanning = true })
                TvTextField(invitation, { invitation = it.take(512) }, label = stringResource(R.string.companion_invitation), requireExplicitEditing = true, modifier = Modifier.focusRequester(invitationFocus).fillMaxWidth())
                TvActionButton(stringResource(R.string.companion_pair), { invitationFocus.requestFocus(); manager.pair(invitation); invitation = "" }, enabled = invitation.isNotBlank() && !state.busy)
                if (state.paired.isNotEmpty()) Text(stringResource(R.string.companion_section_paired), style = MaterialTheme.typography.titleSmall)
                state.paired.forEach { peer ->
                    val session = state.sessions[peer.fingerprint].takeIf { peer.fingerprint != state.target?.fingerprint }
                    session?.let { Text(listOf(peer.name, it.title.ifBlank { stringResource(R.string.playback_status_idle) }, playbackPhaseLabel(it.phase)).joinToString(" · "), style = MaterialTheme.typography.bodySmall) }
                    val discovered = state.devices.firstOrNull { it.fingerprint == peer.fingerprint }
                    Row {
                        TvActionButton(
                            if (state.sharedMedia != null) stringResource(R.string.companion_send_to, peer.name) else stringResource(if (state.online && state.target?.fingerprint == peer.fingerprint) R.string.companion_connected else R.string.companion_paired, peer.name),
                            { if (state.sharedMedia != null) manager.sendSharedMediaTo(peer) else manager.connect(discovered ?: peer) },
                            enabled = !state.busy && state.sendingTo == null, modifier = Modifier.weight(1f))
                        TvActionButton(stringResource(R.string.companion_forget), { devicesFocus.requestFocus(); manager.revoke(peer) })
                    }
                }
                Text(stringResource(R.string.companion_section_found), style = MaterialTheme.typography.titleSmall)
                state.devices.filter { d -> state.paired.none { it.fingerprint == d.fingerprint } }.forEach { peer ->
                    CompanionPairWithCode(manager, peer, enabled = !state.busy)
                }
                CompanionSearchStatus(state)
                TvActionButton(stringResource(R.string.companion_search), manager::discover, modifier = Modifier.focusRequester(devicesFocus), fillWidth = true,
                    loading = state.searching)
            }
            if (!handheld) {
                if (state.controller.isNotEmpty()) TvActionButton(stringResource(R.string.companion_end_control), { devicesFocus.requestFocus(); manager.releaseRemoteControl() })
                Text(stringResource(R.string.companion_tv_only_hint))
                TvActionButton(stringResource(R.string.companion_pair_phone), manager::openPairing, modifier = Modifier.focusRequester(devicesFocus), fillWidth = true)
                state.paired.forEach { peer ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(peer.name, modifier = Modifier.weight(1f))
                        TvActionButton(stringResource(R.string.companion_forget), { devicesFocus.requestFocus(); manager.revoke(peer) })
                    }
                }
                if (state.enabled) TvActionButton(stringResource(R.string.companion_disable), { devicesFocus.requestFocus(); manager.enableReceiver(false) })
            }
            if (state.busy) Text(stringResource(R.string.companion_connecting))
            state.sendingTo?.let { Text(stringResource(R.string.companion_sending, it)) }
        }
    }
}

/** A discovered, not yet paired TV: type the short code from its screen. */
@Composable
internal fun CompanionPairWithCode(manager: CompanionManager, peer: CompanionPeer, enabled: Boolean) {
    var code by remember(peer.fingerprint) { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.companion_unpaired_device, peer.name))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TvTextField(
                code,
                { code = it.take(16) },
                label = stringResource(R.string.companion_pair_code),
                requireExplicitEditing = true,
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            TvActionButton(stringResource(R.string.companion_pair),
                { manager.pairWithCode(peer, code); code = "" },
                enabled = enabled && normalizePairCode(code).length == PAIR_CODE_LENGTH,
            )
        }
    }
}

/** Visible search progress on the phone: searching, or why nothing turned up. */
@Composable
internal fun CompanionSearchStatus(state: CompanionState) {
    val unpairedFound = state.devices.any { d -> state.paired.none { it.fingerprint == d.fingerprint } }
    when {
        state.searching && !unpairedFound -> Text(stringResource(R.string.companion_searching), style = MaterialTheme.typography.bodySmall)
        state.searchEmpty -> Text(stringResource(R.string.companion_search_empty), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error)
    }
}
