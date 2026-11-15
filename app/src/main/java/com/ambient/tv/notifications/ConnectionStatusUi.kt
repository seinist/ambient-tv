package com.ambient.tv.notifications

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ambient.tv.R
import com.ambient.tv.ui.common.RightPanelDivider
import com.ambient.tv.ui.common.RightPanelOverlay
import com.ambient.tv.ui.common.RightSidePanel
import com.ambient.tv.ui.common.TvActionButton
import com.ambient.tv.ui.mobile.LocalHandheldUi
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The shell observes only availability and panel visibility. Packet/countdown updates stay in
 * the notification host, and minimizing remains presentation state owned by the shared store.
 */
@Stable
class ConnectionStatusUiState {
    var isDetailsOpen by mutableStateOf(false)
        private set
    var isAvailable by mutableStateOf(false)
        private set
    private var onClosed: (() -> Unit)? = null

    internal fun updateAvailability(available: Boolean) {
        isAvailable = available
    }

    fun openDetails(onClosed: (() -> Unit)? = null) {
        if (isDetailsOpen) return
        this.onClosed = onClosed
        AppNotifications.store.restoreConnectivity()
        isDetailsOpen = true
    }

    fun closeDetails() {
        if (!isDetailsOpen) return
        isDetailsOpen = false
        val restore = onClosed
        onClosed = null
        restore?.invoke()
    }

    fun minimize() = AppNotifications.store.minimizeConnectivity()
    fun expand() = AppNotifications.store.restoreConnectivity()
}

/** A single bounded summary. The same two lines also appear inside the intro. */
@Composable
internal fun ConnectionStatusSummary(
    status: ConnectivitySnapshot,
    reduced: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val primary = status.primary
    val allReady = status.rows.isNotEmpty() && status.rows.all { it.notice.phase == NoticePhase.SUCCESS }
    val title = when {
        status.hasErrors -> stringResource(
            if (primary?.kind == NoticeKind.VPN) R.string.start_status_vpn_unavailable
            else if (primary?.message == R.string.notice_network_lost) R.string.notice_network_lost
            else R.string.start_status_unavailable,
        )
        status.working && primary?.kind == NoticeKind.VPN ->
            stringResource(R.string.start_status_connecting_vpn)
        status.working && primary?.message == R.string.notice_service_starting ->
            stringResource(R.string.start_status_starting_services)
        status.working && primary?.kind == NoticeKind.WAKE ->
            stringResource(
                if (status.activeWakes.size > 1) R.string.start_status_waking_servers
                else R.string.start_status_waking_server,
            )
        status.working -> stringResource(R.string.start_status_connecting)
        allReady -> stringResource(R.string.start_status_ready)
        else -> stringResource(R.string.start_status_title)
    }
    val secondary = when {
        primary == null -> stringResource(R.string.start_status_empty)
        !status.working && !status.hasErrors -> noticeMessage(primary)
        status.readyCount > 0 -> stringResource(
            R.string.start_status_source_and_ready, noticeTitle(primary), status.readyCount,
        )
        else -> noticeTitle(primary)
    }
    Row(
        modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ConnectionPhaseIcon(
            phase = when {
                status.hasErrors -> NoticePhase.ERROR
                status.working -> NoticePhase.WORKING
                allReady -> NoticePhase.SUCCESS
                else -> NoticePhase.INFO
            },
            reduced = reduced,
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, color = colors.onSurface, style = MaterialTheme.typography.titleSmall,
                minLines = 1, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(secondary, color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
                minLines = 1, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ConnectionPhaseIcon(phase: NoticePhase, reduced: Boolean, modifier: Modifier = Modifier) {
    val color = if (phase == NoticePhase.ERROR) MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.primary
    Box(modifier.size(24.dp), contentAlignment = Alignment.Center) {
        if (phase == NoticePhase.WORKING && !reduced) {
            CircularProgressIndicator(Modifier.size(20.dp), color = color, strokeWidth = 2.dp)
        } else {
            Icon(
                imageVector = when (phase) {
                    NoticePhase.WORKING -> Icons.Filled.HourglassEmpty
                    NoticePhase.SUCCESS -> Icons.Filled.CheckCircle
                    NoticePhase.ERROR -> Icons.Filled.Warning
                    NoticePhase.INFO -> Icons.Filled.Info
                },
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = color,
            )
        }
    }
}

/** Child disposal may run in the same removal as its whole card; only the card owns hand-back. */
private class ConnectionStatusFocusAttachment {
    var attached = false
}

/**
 * Details is the stable TV focus anchor. A disappearing cancel action returns there; Back
 * leaves the card without cancelling any job. Progress updates never request focus again.
 */
@Composable
internal fun CompactConnectionStatus(
    status: ConnectivitySnapshot,
    reduced: Boolean,
    onDetails: () -> Unit,
    onCancel: (AppNotice) -> Unit,
    modifier: Modifier = Modifier,
    autoFocus: Boolean = false,
    wakeFocus: TvWakeFocusState = remember { TvWakeFocusState() },
    onNavigateToSidebar: () -> Boolean = { false },
    onFocusReleased: () -> Unit = {},
) {
    val handheld = LocalHandheldUi.current
    val view = LocalView.current
    val release by rememberUpdatedState(onFocusReleased)
    var cardFocused by remember { mutableStateOf(false) }
    var detailsFocused by remember { mutableStateOf(false) }
    var cancelFocused by remember { mutableStateOf(false) }
    val cardFocusedNow by rememberUpdatedState(cardFocused)
    val cancelFocusedNow by rememberUpdatedState(cancelFocused)
    val cancel = status.activeWakes.singleOrNull()
    val attachment = remember { ConnectionStatusFocusAttachment() }
    var autoFocusAttempted by remember(status.sessionId) { mutableStateOf(false) }
    DisposableEffect(wakeFocus, handheld) {
        attachment.attached = true
        if (!handheld) wakeFocus.attach()
        onDispose {
            attachment.attached = false
            if (!handheld) {
                val shouldReturn = cardFocusedNow
                wakeFocus.detach()
                if (shouldReturn) view.post { release() }
            }
        }
    }
    LaunchedEffect(autoFocus, status.sessionId, handheld) {
        if (handheld || !autoFocus || autoFocusAttempted) return@LaunchedEffect
        autoFocusAttempted = true
        repeat(8) {
            withFrameNanos { }
            if (wakeFocus.requestFocus()) return@LaunchedEffect
        }
    }
    BackHandler(enabled = !handheld && cardFocused) {
        if (!onNavigateToSidebar()) release()
    }
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier
            .testTag("connection-status-card")
            .onFocusChanged {
                cardFocused = it.hasFocus
                if (!handheld) wakeFocus.onFocusChanged(it.hasFocus)
            }
            .onPreviewKeyEvent { event ->
                !handheld && detailsFocused && event.key == Key.DirectionLeft &&
                    event.type == KeyEventType.KeyDown && onNavigateToSidebar()
            }
            .focusGroup()
            .background(colors.surface.copy(alpha = .98f), shape)
            .border(1.dp, colors.onSurface.copy(alpha = .16f), shape)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ConnectionStatusSummary(status, reduced, Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TvActionButton(
                text = stringResource(R.string.start_status_details),
                onClick = onDetails,
                icon = Icons.Filled.Info,
                modifier = Modifier.weight(1f)
                    .testTag("connection-status-details")
                    .then(if (!handheld) Modifier.focusRequester(wakeFocus.requester) else Modifier)
                    .onFocusChanged { detailsFocused = it.isFocused },
            )
            if (cancel != null) {
                key(cancel.key) {
                    DisposableEffect(cancel.key) {
                        onDispose {
                            if (!handheld && cancelFocusedNow) view.post {
                                // Whole-card removal already posted the single external
                                // hand-back. Only rescue Details when this card still exists.
                                if (attachment.attached && !wakeFocus.requestEntryFocus()) release()
                            }
                        }
                    }
                    TvActionButton(
                        text = stringResource(R.string.home_cancel_wake),
                        onClick = { onCancel(cancel) },
                        modifier = Modifier.weight(1f).testTag("connection-status-cancel")
                            .onFocusChanged { cancelFocused = it.isFocused },
                    )
                }
            }
        }
    }
}

/** Swiping changes presentation only; the reachable badge still opens the live operation. */
@Composable
internal fun MinimizableConnectionStatus(
    onMinimize: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    var offset by remember { mutableFloatStateOf(0f) }
    val threshold = with(LocalDensity.current) { 72.dp.toPx() }
    val minimize by rememberUpdatedState(onMinimize)
    val minimizeLabel = stringResource(R.string.start_status_minimize)
    Box(
        modifier.offset { IntOffset(offset.roundToInt(), 0) }
            .semantics { dismiss(label = minimizeLabel) { minimize(); true } }
            .pointerInput(threshold) {
                detectHorizontalDragGestures(
                    onDragCancel = { offset = 0f },
                    onDragEnd = {
                        if (abs(offset) >= threshold) minimize()
                        offset = 0f
                    },
                    onHorizontalDrag = { change, distance ->
                        change.consume()
                        offset += distance
                    },
                )
            },
    ) { content() }
}

@Composable
internal fun ConnectionStatusBadge(
    hasErrors: Boolean,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    sessionId: Long = 0L,
    autoFocus: Boolean = false,
    wakeFocus: TvWakeFocusState = remember { TvWakeFocusState() },
    onNavigateToSidebar: () -> Boolean = { false },
    onFocusReleased: () -> Unit = {},
) {
    val handheld = LocalHandheldUi.current
    val view = LocalView.current
    val release by rememberUpdatedState(onFocusReleased)
    var focused by remember { mutableStateOf(false) }
    val focusedNow by rememberUpdatedState(focused)
    var autoFocusAttempted by remember(sessionId) { mutableStateOf(false) }
    DisposableEffect(wakeFocus, handheld) {
        if (!handheld) wakeFocus.attach()
        onDispose {
            if (!handheld) {
                val shouldReturn = focusedNow
                wakeFocus.detach()
                if (shouldReturn) view.post { release() }
            }
        }
    }
    LaunchedEffect(sessionId, autoFocus, handheld) {
        if (handheld || !autoFocus || autoFocusAttempted) return@LaunchedEffect
        autoFocusAttempted = true
        repeat(8) {
            withFrameNanos { }
            if (wakeFocus.requestFocus()) return@LaunchedEffect
        }
    }
    BackHandler(enabled = !handheld && focused) {
        if (!onNavigateToSidebar()) release()
    }
    TvActionButton(
        text = stringResource(if (hasErrors) R.string.start_status_problem else R.string.start_status_title),
        onClick = onOpen,
        icon = if (hasErrors) Icons.Filled.Warning else Icons.Filled.Info,
        modifier = modifier.testTag("connection-status-badge")
            .then(if (!handheld) Modifier.focusRequester(wakeFocus.requester) else Modifier)
            .onFocusChanged {
                focused = it.isFocused
                if (!handheld) wakeFocus.onFocusChanged(it.hasFocus)
            }
            .onPreviewKeyEvent { event ->
                !handheld && event.key == Key.DirectionLeft && event.type == KeyEventType.KeyDown &&
                    onNavigateToSidebar()
            },
    )
}

@Composable
internal fun ConnectionStatusDetails(
    status: ConnectivitySnapshot,
    reduced: Boolean,
    onClose: () -> Unit,
    onCancel: (AppNotice) -> Unit,
    onRetry: (AppNotice) -> Unit,
    canRetry: (AppNotice) -> Boolean,
) {
    RightPanelOverlay(onOutsideTap = onClose) {
        RightSidePanel(
            title = stringResource(R.string.start_status_title),
            onBack = onClose,
            closeInsteadOfBack = true,
            modifier = Modifier.align(Alignment.TopEnd).testTag("connection-status-panel"),
        ) {
            if (status.rows.isEmpty()) {
                Text(stringResource(R.string.start_status_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                status.rows.forEachIndexed { index, row ->
                    key(row.key) {
                        if (index > 0) RightPanelDivider()
                        ConnectionStatusDetailRow(row, reduced, onCancel, onRetry, canRetry)
                    }
                }
            }
        }
    }
}

@Composable
internal fun ConnectionStatusDetailRow(
    row: ConnectivityRow,
    reduced: Boolean,
    onCancel: (AppNotice) -> Unit,
    onRetry: (AppNotice) -> Unit,
    canRetry: (AppNotice) -> Boolean,
) {
    val colors = MaterialTheme.colorScheme
    val notice = row.notice
    var technicalExpanded by remember(row.key) { mutableStateOf(false) }
    val technicalNotice = row.details.firstOrNull { it.kind == NoticeKind.WAKE && (it.packets > 0 || it.progressTotalMs > 0) }
        ?: notice
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
            ConnectionPhaseIcon(notice.phase, reduced)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(noticeTitle(notice), color = colors.onSurface, style = MaterialTheme.typography.titleSmall)
                Text(noticeMessage(notice),
                    color = if (notice.phase == NoticePhase.ERROR) colors.error else colors.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium)
            }
        }
        // A machine can expose several services. Keep their results inspectable without
        // multiplying wake controls or pretending that all services share one countdown.
        val serviceCount = row.details.count { it.kind == NoticeKind.CONNECTION }
        val sourceDetails = row.details.filter {
            (it.kind == NoticeKind.CONNECTION && serviceCount > 1) ||
                (it.blockedBy != null && it.args.isNotEmpty())
        }
        if (sourceDetails.isNotEmpty()) {
            sourceDetails.forEach { service ->
                Text(
                    stringResource(R.string.start_status_service_detail, noticeTitle(service), noticeMessage(service)),
                    color = if (service.phase == NoticePhase.ERROR) colors.error else colors.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = 34.dp),
                )
            }
        }
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            row.cancelKey?.let { cancelKey ->
                val cancelNotice = row.details.firstOrNull { it.key == cancelKey && it.phase == NoticePhase.WORKING }
                    ?: notice.copy(key = cancelKey, phase = NoticePhase.WORKING, kind = NoticeKind.WAKE)
                TvActionButton(
                    text = stringResource(R.string.home_cancel_wake),
                    onClick = { onCancel(cancelNotice) },
                    icon = Icons.Filled.PowerSettingsNew,
                    fillWidth = true,
                    modifier = Modifier.testTag("connection-cancel:" + row.key),
                )
            }
            if (canRetry(notice)) {
                TvActionButton(
                    text = stringResource(R.string.start_status_retry),
                    onClick = { onRetry(notice) },
                    icon = Icons.Filled.Refresh,
                    fillWidth = true,
                    modifier = Modifier.testTag("connection-retry:" + row.key),
                )
            }
        }
        if (technicalNotice.packets > 0 || technicalNotice.progressTotalMs > 0) {
            TvActionButton(
                text = stringResource(
                    if (technicalExpanded) R.string.start_status_hide_technical_details
                    else R.string.start_status_technical_details,
                ),
                onClick = { technicalExpanded = !technicalExpanded },
                icon = if (technicalExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                fillWidth = true,
                modifier = Modifier.testTag("connection-technical:" + row.key),
            )
            if (technicalExpanded) ConnectionTechnicalDetails(technicalNotice)
        }
    }
}

/** Only the expanded diagnostic section has a one-second clock. There is no boot percentage. */
@Composable
private fun ConnectionTechnicalDetails(notice: AppNotice) {
    val working = notice.phase == NoticePhase.WORKING
    val now by produceState(System.currentTimeMillis(), working, notice.progressStartedAt, notice.nextPacketAt) {
        while (working) {
            value = System.currentTimeMillis()
            delay(1_000)
        }
    }
    Column(Modifier.fillMaxWidth().padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        if (notice.packets > 0) {
            Text(
                stringResource(R.string.start_status_packets, notice.packetsSent, notice.packets),
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
            )
        }
        if (working && notice.progressTotalMs > 0) {
            val remaining = ((notice.progressStartedAt + notice.progressTotalMs - now).coerceAtLeast(0) + 999) / 1_000
            Text(
                stringResource(R.string.start_status_wait_remaining, remaining),
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
            )
        }
        if (working && notice.nextPacketAt > 0 && notice.packetsSent < notice.packets) {
            val remaining = ((notice.nextPacketAt - now).coerceAtLeast(0) + 999) / 1_000
            Text(
                stringResource(R.string.start_status_retry_in, remaining),
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
            )
        }
        Text(
            stringResource(R.string.start_status_time_budget_hint),
            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
        )
    }
}
