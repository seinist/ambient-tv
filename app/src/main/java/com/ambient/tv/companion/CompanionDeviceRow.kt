package com.ambient.tv.companion

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ambient.tv.R
import com.ambient.tv.ui.common.AppDialog
import com.ambient.tv.ui.common.TvActionButton
import kotlinx.coroutines.delay

/** Reachability of [peer], refreshed on every state change and every few seconds (seen TTL). */
@Composable
internal fun rememberReachability(manager: CompanionManager, peer: CompanionPeer): PeerReachability {
    val state by manager.state.collectAsState()
    val now by produceState(SystemClock.elapsedRealtime()) {
        while (true) { delay(5_000); value = SystemClock.elapsedRealtime() }
    }
    return manager.reachability(peer, state, now)
}

internal fun PeerReachability.labelRes(): Int = when (this) {
    PeerReachability.CONNECTED -> R.string.companion_reach_connected
    PeerReachability.REACHABLE -> R.string.companion_reach_reachable
    PeerReachability.WAKEABLE -> R.string.companion_reach_wakeable
    PeerReachability.SEARCHING -> R.string.companion_reach_searching
    PeerReachability.UNREACHABLE -> R.string.companion_reach_unreachable
}

/** Same marker everywhere: unreachable devices are dimmed and say so. */
internal fun Modifier.reachabilityDim(reachability: PeerReachability): Modifier =
    alpha(if (reachability == PeerReachability.UNREACHABLE) 0.5f else 1f)

@Composable
internal fun CompanionReachabilityLabel(reachability: PeerReachability, modifier: Modifier = Modifier) {
    Text(stringResource(reachability.labelRes()), modifier = modifier.reachabilityDim(reachability),
        style = MaterialTheme.typography.bodySmall,
        color = if (reachability == PeerReachability.UNREACHABLE) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
}

/** "Entkoppeln" with a confirmation; always allowed, also for unreachable devices. */
@Composable
internal fun CompanionUnpairButton(manager: CompanionManager, peer: CompanionPeer, onUnpaired: () -> Unit = {}) {
    var confirm by remember(peer.fingerprint) { mutableStateOf(false) }
    TvActionButton(stringResource(R.string.companion_unpair), { confirm = true })
    if (confirm) AppDialog(
        onDismiss = { confirm = false },
        title = stringResource(R.string.companion_unpair_confirm_title, peer.name),
        footer = {
            TvActionButton(stringResource(R.string.companion_abort), { confirm = false })
            TvActionButton(stringResource(R.string.companion_unpair), { confirm = false; manager.revoke(peer); onUnpaired() })
        },
    ) { Text(stringResource(R.string.companion_unpair_confirm_body)) }
}

/**
 * Shared paired-device row: primary content (name/action), reachability marker and unpair.
 * [primary] gets the weighted, dimmed modifier.
 */
@Composable
internal fun CompanionPeerRow(
    manager: CompanionManager,
    peer: CompanionPeer,
    reachability: PeerReachability = rememberReachability(manager, peer),
    onUnpaired: () -> Unit = {},
    primary: @Composable (Modifier) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            primary(Modifier.weight(1f).reachabilityDim(reachability))
            CompanionUnpairButton(manager, peer, onUnpaired)
        }
        CompanionReachabilityLabel(reachability)
    }
}
