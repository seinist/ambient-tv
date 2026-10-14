package com.ambient.tv.companion

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ambient.tv.R
import com.ambient.tv.ui.common.AppDialog
import com.ambient.tv.ui.common.TvActionButton

@Composable
internal fun CompanionReceiverStatus(manager: CompanionManager) {
    val state by manager.state.collectAsState()
    if (state.controller.isNotEmpty()) Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.TopEnd) {
        Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.companion_control_active, state.controller), style = MaterialTheme.typography.bodyMedium)
                TvActionButton(stringResource(R.string.companion_end_control), onClick = { manager.releaseRemoteControl() })
            }
        }
    }
}

@Composable
private fun TransferCard(title: String, body: String) {
    Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f))) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Option(text: String, checked: Boolean, enabled: Boolean = true, change: (Boolean) -> Unit) {
    TvActionButton((if (checked) "✓  " else "○  ") + text, onClick = { change(!checked) }, selected = checked, enabled = enabled, fillWidth = true)
}

@Composable
internal fun CompanionImportDialog(vm: CompanionViewModel) {
    val manager = vm.manager
    val state by manager.state.collectAsState()
    val offer = state.backupOffer
    val restore = state.restoreDialog
    if (offer == null && !restore) return
    var overwriteAccounts by remember(offer?.id) { mutableStateOf(false) }
    var hardware by remember(offer?.id) { mutableStateOf(false) }
    val cancelFocus = remember(offer?.id, restore) { FocusRequester() }
    AppDialog(
        title = stringResource(if (restore) R.string.companion_restore_backup else R.string.companion_import_title),
        maxWidth = 660.dp, maxHeightFraction = 0.92f,
        onDismiss = { if (restore) manager.closeRestore() else manager.rejectBackup() },
        footer = {
            TvActionButton(stringResource(R.string.companion_abort), onClick = { if (restore) manager.closeRestore() else manager.rejectBackup() },
                modifier = Modifier.focusRequester(cancelFocus), enabled = !state.backupBusy)
            TvActionButton(stringResource(if (restore) R.string.companion_restore_action else if (offer?.mode in setOf("settings", "nav_sounds") || overwriteAccounts) R.string.companion_overwrite else R.string.companion_add_accounts),
                onClick = { if (restore) manager.restoreSettings() else manager.applyBackup(offer?.mode in setOf("settings", "nav_sounds") || overwriteAccounts, hardware) },
                enabled = !state.backupBusy)
        },
    ) {
        LaunchedEffect(offer?.id, restore) { withFrameNanos { }; cancelFocus.requestFocus() }
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (restore) {
                TransferCard(stringResource(R.string.companion_overwrite_question), stringResource(R.string.companion_restore_hint))
            } else if (offer != null) {
                Text(stringResource(R.string.companion_import_sender, offer.sender), style = MaterialTheme.typography.titleMedium)
                if (offer.mode == "nav_sounds") {
                    TransferCard(stringResource(R.string.nav_sync_title), stringResource(R.string.nav_sync_hint))
                } else {
                    TransferCard(stringResource(if (offer.mode == "accounts") R.string.companion_accounts_only else R.string.companion_accounts_settings),
                        stringResource(R.string.companion_import_counts, offer.accounts, offer.sources))
                    Text(stringResource(if (offer.credentials) R.string.companion_credentials_received else R.string.companion_credentials_preserved),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (offer.mode == "accounts") Option(stringResource(R.string.companion_overwrite_accounts), overwriteAccounts, !state.backupBusy) { overwriteAccounts = it }
                    else {
                        Text(stringResource(R.string.companion_overwrite_question), style = MaterialTheme.typography.titleMedium)
                        Option(stringResource(R.string.companion_include_hardware), hardware, !state.backupBusy) { hardware = it }
                    }
                }
                Text(stringResource(R.string.companion_import_safety), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (state.backupBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}
