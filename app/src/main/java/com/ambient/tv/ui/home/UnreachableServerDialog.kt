package com.ambient.tv.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ambient.tv.R
import com.ambient.tv.connectivity.UnreachableServerGateState
import com.ambient.tv.ui.common.AppDialog
import com.ambient.tv.ui.common.MediaSystem
import com.ambient.tv.ui.common.TvActionButton
import com.ambient.tv.ui.common.systemName

/**
 * Shown when a media server is down but another system is already usable.
 * Continue hides the dead servers (it does not delete them) and closes.
 * Back does the same. When nothing else works this dialog is not presented.
 */
@Composable
fun UnreachableServerDialog(
    state: UnreachableServerGateState,
    onConfirm: () -> Unit,
    onRetry: () -> Unit,
    onOpenSettings: (String) -> Unit,
) {
    if (!state.canDismiss) return
    val serverName = state.names.joinToString(", ").ifBlank {
        stringResource(R.string.home_server_generic_name)
    }
    val confirm = {
        if (state.canDismiss) onConfirm()
    }
    val systems = MediaSystem.entries.filter { it.key in state.unreachable }
    AppDialog(
        onDismiss = confirm,
        onDismissRequest = confirm,
        title = stringResource(R.string.home_server_unreachable_title),
        hint = stringResource(R.string.home_server_unreachable_body, serverName) +
            "\n" + stringResource(R.string.home_server_unreachable_continue_hint),
        footer = {
            TvActionButton(text = stringResource(R.string.home_server_unreachable_continue), onClick = confirm)
            TvActionButton(text = stringResource(R.string.home_retry), onClick = onRetry)
            if (systems.size == 1) {
                TvActionButton(
                    text = stringResource(R.string.home_check_settings),
                    onClick = { onOpenSettings(systems.single().key) },
                )
            }
        },
    ) {
        // Multiple long system labels would overflow the compact TV dialog footer.
        if (systems.size > 1) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                systems.forEach { system ->
                    TvActionButton(
                        text = stringResource(R.string.home_check_system_settings, systemName(system)),
                        onClick = { onOpenSettings(system.key) },
                        fillWidth = true,
                    )
                }
            }
        }
    }
}
