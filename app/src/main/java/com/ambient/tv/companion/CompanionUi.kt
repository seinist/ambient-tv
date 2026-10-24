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
    LaunchedEffect(state.settingsPanel) { if (!state.settingsPanel) connectionEditor.removeState("transfer") }
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
    if (state.settingsPanel) { connectionEditor.SaveableStateProvider("transfer") { CompanionSyncDialog(viewModel, onDismiss = manager::hideSettingsTransfer) }; return }
    if (!state.panel) return
    if (!handheld && state.pairingWith.isNotBlank()) {
        com.ambient.tv.ui.common.AppDialog(onDismiss = manager::hidePanel,
            title = stringResource(R.string.companion_confirm_title), maxWidth = 520.dp,
            footer = { TvActionButton(stringResource(R.string.companion_close), manager::hidePanel) }) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.companion_pairing_waiting, state.pairingWith), style = MaterialTheme.typography.bodyLarge)
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
        return
    }
    if (!handheld && state.invitation != null) {
        val link = state.invitation!!
        com.ambient.tv.ui.common.QrCodeDialog(
            title = stringResource(R.string.companion_pair),
            hint = stringResource(R.string.companion_qr_hint),
            payload = link,
            code = state.pairCode.takeIf { it.isNotBlank() },
            onDismiss = manager::hidePanel,
            footer = {
                com.ambient.tv.ui.common.TvActionButton(
                    stringResource(R.string.companion_close),
                    onClick = manager::hidePanel,
                )
            },
        )
        return
    }
    com.ambient.tv.ui.common.AppDialog(onDismiss = manager::hidePanel,
        title = stringResource(R.string.companion_devices), maxWidth = 620.dp, maxHeightFraction = .94f,
        footer = { TvActionButton(stringResource(R.string.companion_close), manager::hidePanel) }) {
        if (handheld) CompanionDeviceHub(manager, state)
        else Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            com.ambient.tv.ui.common.LocalNetworkPermissionSetting(onGranted = manager::onLocalNetworkPermissionGranted)
            TvActionButton(stringResource(R.string.companion_pair_phone), manager::openPairing, selected = true, fillWidth = true)
            state.paired.forEach { peer -> CompanionPeerRow(manager, peer) { mod -> Text(peer.name, modifier = mod) } }
        }
    }
}

/** A discovered, not yet paired TV: type the short code from its screen. */
@Composable
internal fun CompanionPairWithCode(manager: CompanionManager, peer: CompanionPeer, enabled: Boolean) {
    var code by androidx.compose.runtime.saveable.rememberSaveable(peer.fingerprint) { mutableStateOf("") }
    var expanded by androidx.compose.runtime.saveable.rememberSaveable(peer.fingerprint) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // The TV's own name is the title; the code hint stays short underneath.
        TvActionButton(peer.name, { expanded = !expanded }, enabled = enabled, selected = expanded, fillWidth = true)
        if (expanded) Text(stringResource(R.string.companion_pair_code_hint), style = MaterialTheme.typography.bodySmall)
        if (expanded) Row(verticalAlignment = Alignment.CenterVertically) {
            TvTextField(
                code,
                { code = it.take(16) },
                label = stringResource(R.string.companion_pair_code),
                requireExplicitEditing = false,
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            TvActionButton(stringResource(R.string.companion_pair),
                { manager.pairWithCode(peer, code) },
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
        // Details arrive as a notification; inline only a short hint.
        state.searchEmpty -> Text(stringResource(R.string.companion_search_empty_short), style = MaterialTheme.typography.bodySmall)
    }
}
