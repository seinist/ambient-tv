package com.ambient.tv.notifications

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ambient.tv.R
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.ui.common.MediaSystem
import com.ambient.tv.ui.common.TvActionButton
import com.ambient.tv.ui.home.UnreachableServerViewModel
import com.ambient.tv.ui.common.SystemLogo
import com.ambient.tv.ui.common.systemName
import com.ambient.tv.ui.mobile.LocalHandheldUi
import com.ambient.tv.ui.performance.LocalPerformanceProfile
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

private object NotificationWindows {
    val dialogs = mutableStateListOf<Any>()
}

/** The active window presents status cards. On TV, active wakes also expose a focusable cancel action. */
@Composable
fun AppNotificationHost(
    dialog: Boolean = false,
    hidden: Boolean = false,
    playback: Boolean = false,
    autoFocusWake: Boolean = true,
    /** The focused cancel button went away (wake done or cancelled): give focus back. */
    onWakeFocusReleased: () -> Unit = {},
) {
    val window = remember { Any() }
    DisposableEffect(dialog) {
        if (dialog) NotificationWindows.dialogs.add(window)
        onDispose { NotificationWindows.dialogs.remove(window) }
    }
    val ownsPresentation = if (dialog) NotificationWindows.dialogs.lastOrNull() === window
        else NotificationWindows.dialogs.isEmpty()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val accessibility = LocalAccessibilityManager.current
    val presenting by rememberUpdatedState(!hidden)
    val playing by rememberUpdatedState(playback)
    val displayDuration = rememberNotificationDisplayDuration()
    val currentDisplayDuration by rememberUpdatedState(displayDuration)
    if (!dialog) {
        LaunchedEffect(lifecycle, accessibility) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    AppNotifications.store.state.first { it.pending.isNotEmpty() || it.visible.isNotEmpty() }
                    val longTimeout = accessibility?.calculateRecommendedTimeoutMillis(7_000, true, true, false) ?: 7_000
                    val shortTimeout = accessibility?.calculateRecommendedTimeoutMillis(3_000, true, true, false) ?: 3_000
                    AppNotifications.store.tick(
                        presenting && currentDisplayDuration.enabled,
                        playing,
                        currentDisplayDuration.timeoutScale * maxOf(1f, longTimeout.toFloat() / 7_000, shortTimeout.toFloat() / 3_000),
                    )
                    delay(100)
                }
            }
        }
    }
    val snapshot by AppNotifications.store.state.collectAsState()
    val visible = if (ownsPresentation && !hidden && displayDuration.enabled) snapshot.visible else emptyList()
    val sounds = com.ambient.tv.ui.sound.LocalUiSoundPlayer.current
    val announced = remember { mutableSetOf<Pair<Long, NoticePhase>>() }
    LaunchedEffect(visible, sounds) {
        val fresh = visible.filter { announced.add(it.id to it.notice.phase) }
        val kind = when {
            fresh.any { it.notice.phase == NoticePhase.ERROR } -> com.ambient.tv.ui.sound.UiSound.ERROR
            fresh.any { it.notice.phase == NoticePhase.SUCCESS && !it.notice.quietSuccess } -> com.ambient.tv.ui.sound.UiSound.SUCCESS
            else -> null
        }
        kind?.let { sounds?.play(it) }
        if (announced.size > 128) {
            val retainedIds = (snapshot.history + snapshot.pending + snapshot.visible).map { it.id }.toSet()
            announced.removeAll { it.first !in retainedIds }
        }
    }
    val reduced = LocalPerformanceProfile.current.reducedEffects || rememberAnimationsDisabled()
    val handheld = LocalHandheldUi.current
    // Connectivity has several independent producers. Present their current phases inside
    // one stable surface, so wake/resume/provider updates never stack separate connection cards.
    val connections = visible.filter { it.notice.kind in CONNECTIVITY_KINDS }
    val others = visible.filterNot { it.notice.kind in CONNECTIVITY_KINDS }
    // The wake action is essential, so it must remain available even if notice duration is OFF.
    // A card may represent several services; each machine still has exactly one cancel action.
    val activeWakes = if (!handheld && ownsPresentation && !hidden && !dialog)
        snapshot.pending.map(NoticeEntry::notice).filter {
            it.kind == NoticeKind.WAKE && it.phase == NoticePhase.WORKING && it.key.startsWith("wake:")
        }.distinctBy(AppNotice::key)
    else emptyList()
    Box(Modifier.fillMaxSize()
        .then(if (handheld) Modifier.windowInsetsPadding(WindowInsets.safeDrawing) else Modifier)
        .padding(horizontal = if (handheld) 16.dp else 40.dp, vertical = 32.dp)) {
        Column(Modifier.align(Alignment.TopEnd).widthIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (connections.isNotEmpty()) {
                DismissibleNotification(handheld, onDismiss = {
                    connections.forEach { AppNotifications.dismiss(it.notice.key) }
                }) {
                    ConnectionGroup(connections, reduced)
                }
            }
            others.forEach { entry ->
                key(entry.id) {
                    DismissibleNotification(handheld, onDismiss = { AppNotifications.dismiss(entry.notice.key) }) {
                        NotificationCard(entry.notice, reduced)
                    }
                }
            }
        }
        if (activeWakes.isNotEmpty()) {
            val wakeVm: UnreachableServerViewModel = hiltViewModel()
            TvWakeCancelBar(activeWakes, autoFocus = autoFocusWake && !playback, onCancel = wakeVm::cancelWake,
                onFocusReleased = onWakeFocusReleased,
                modifier = Modifier.align(Alignment.BottomCenter))
        }
    }
}

/**
 * On television a notification is deliberately non-focusable. Put the real cancel action
 * outside it, at the bottom of the screen; Back cancels only when this button has focus.
 * Each row is identified by the machine key, so one wake never stops unrelated servers.
 */
@Composable
private fun TvWakeCancelBar(
    wakes: List<AppNotice>,
    autoFocus: Boolean,
    onCancel: (String) -> Unit,
    onFocusReleased: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val view = androidx.compose.ui.platform.LocalView.current
    val currentRelease by androidx.compose.runtime.rememberUpdatedState(onFocusReleased)
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier.widthIn(max = 560.dp).heightIn(max = 300.dp)
            .background(colors.surface.copy(alpha = .98f), shape)
            .border(1.dp, colors.onSurface.copy(alpha = .16f), shape)
            .verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        wakes.forEachIndexed { index, notice ->
            key(notice.key) {
                val machine = notice.key.removePrefix("wake:")
                val focus = remember { FocusRequester() }
                var focused by remember { mutableStateOf(false) }
                if (index == 0 && autoFocus) {
                    LaunchedEffect(notice.key, autoFocus) {
                        withFrameNanos { }
                        runCatching { focus.requestFocus() }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(noticeTitle(notice), style = MaterialTheme.typography.titleSmall,
                            color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(noticeMessage(notice), style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    TvActionButton(
                        text = stringResource(R.string.home_cancel_wake),
                        onClick = { onCancel(machine) },
                        modifier = Modifier.focusRequester(focus)
                            .onFocusChanged { focused = it.isFocused },
                    )
                }
                BackHandler(enabled = focused) { onCancel(machine) }
                // Removed while focused: without this the next D-pad press started over at the
                // first element of the screen. Posted, because the node is still being removed.
                val focusedNow by androidx.compose.runtime.rememberUpdatedState(focused)
                androidx.compose.runtime.DisposableEffect(Unit) {
                    onDispose { if (focusedNow) view.post { currentRelease() } }
                }
            }
        }
    }
}

private val CONNECTIVITY_KINDS = setOf(NoticeKind.CONNECTION, NoticeKind.WAKE, NoticeKind.VPN)

/** One surface for all connectivity phases; entries with the same text share a row. */
@Composable
private fun ConnectionGroup(connections: List<NoticeEntry>, reduced: Boolean, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(14.dp)
    Column(modifier.fillMaxWidth().background(colors.surface.copy(alpha = .98f), shape)
        .border(1.dp, colors.onSurface.copy(alpha = .16f), shape)) {
        connections.groupBy { listOf(it.notice.kind, it.notice.phase, it.notice.message, it.notice.args) }
            .values.forEach { entries ->
                val notice = entries.first().notice.copy(
                    systems = entries.flatMap { it.notice.systems }.distinct(),
                    subject = entries.mapNotNull { it.notice.subject }.distinct()
                        .joinToString(", ").ifBlank { null },
                )
                NotificationCard(notice, reduced, grouped = true)
            }
    }
}

/**
 * Connectivity work still running (wake, connect, VPN), as the start intro waits on it. Like the
 * card host, a connect that only waits for its machine's wake is covered by the wake.
 */
fun workingConnectivity(snapshot: NotificationSnapshot): List<NoticeEntry> {
    val working = snapshot.pending.filter { it.notice.kind in CONNECTIVITY_KINDS && it.notice.phase == NoticePhase.WORKING }
    val waking = working.filter { it.notice.kind == NoticeKind.WAKE }.flatMap { it.notice.systems }.toSet()
    return working.filterNot {
        it.notice.kind == NoticeKind.CONNECTION && it.notice.systems.isNotEmpty() && it.notice.systems.all(waking::contains)
    }
}

/** The connectivity card inside the start intro, while the intro holds the regular cards back. */
@Composable
fun IntroConnectionStatus(entries: List<NoticeEntry>, modifier: Modifier = Modifier) {
    if (entries.isEmpty()) return
    ConnectionGroup(entries, LocalPerformanceProfile.current.reducedEffects || rememberAnimationsDisabled(), modifier)
}

/** Gesture handling is limited to the card, leaving the surrounding overlay touch-through. */
@Composable
private fun DismissibleNotification(
    enabled: Boolean,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    if (!enabled) {
        content()
        return
    }
    var offset by remember { mutableFloatStateOf(0f) }
    val threshold = with(LocalDensity.current) { 72.dp.toPx() }
    val dismissCurrent by rememberUpdatedState(onDismiss)
    Box(Modifier
        .offset { IntOffset(offset.roundToInt(), 0) }
        .semantics { dismiss { dismissCurrent(); true } }
        .pointerInput(threshold) {
            detectHorizontalDragGestures(
                onDragCancel = { offset = 0f },
                onDragEnd = {
                    if (abs(offset) >= threshold) dismissCurrent()
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
private fun rememberNotificationDisplayDuration(): NotificationDisplayDuration {
    val context = LocalContext.current
    val preferences = remember(context) {
        context.getSharedPreferences("ambient_settings", android.content.Context.MODE_PRIVATE)
    }
    var value by remember(preferences) {
        mutableStateOf(NotificationDisplayDuration.fromKey(
            preferences.getString(AppPreferences.KEY_NOTIFICATION_DISPLAY_DURATION, "normal"),
        ))
    }
    DisposableEffect(preferences) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == AppPreferences.KEY_NOTIFICATION_DISPLAY_DURATION) {
                value = NotificationDisplayDuration.fromKey(
                    preferences.getString(AppPreferences.KEY_NOTIFICATION_DISPLAY_DURATION, "normal"),
                )
            }
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return value
}

@Composable
private fun rememberAnimationsDisabled(): Boolean {
    val resolver = LocalContext.current.contentResolver
    fun disabled() = Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    var value by remember(resolver) { mutableStateOf(disabled()) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { value = disabled() }
        }
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return value
}

@Composable
fun noticeTitle(notice: AppNotice): String {
    val providers = notice.systems.mapNotNull(MediaSystem::fromKey).map { systemName(it) }.distinct().joinToString(", ")
    val fallback = stringResource(when (notice.kind) {
        NoticeKind.VPN -> R.string.notice_vpn
        NoticeKind.CONNECTION -> R.string.notice_network
        else -> R.string.notice_app
    })
    return listOfNotNull(providers.takeIf(String::isNotBlank) ?: fallback, notice.subject?.takeIf(String::isNotBlank)).distinct().joinToString(" · ")
}

@Composable
fun noticeMessage(notice: AppNotice): String = stringResource(notice.message, *notice.args.toTypedArray())

@Composable
private fun NotificationCard(notice: AppNotice, reduced: Boolean, grouped: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    val statusColor = if (notice.phase == NoticePhase.ERROR) colors.error else colors.primary
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.fillMaxWidth().then(if (grouped) Modifier else Modifier
            .background(colors.surface.copy(alpha = .98f), shape)
            .border(1.dp, colors.onSurface.copy(alpha = .16f), shape))
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(36.dp)) {
            val system = notice.systems.distinct().singleOrNull()?.let(MediaSystem::fromKey)
            if (system != null) SystemLogo(system, Modifier.align(Alignment.Center), 28.dp)
            else Icon(kindIcon(notice.kind), null, Modifier.size(28.dp).align(Alignment.Center), tint = colors.onSurface)
            if (system != null) Icon(kindIcon(notice.kind), null,
                Modifier.align(Alignment.BottomEnd).background(colors.surface, RoundedCornerShape(4.dp)).padding(2.dp).size(14.dp), tint = colors.onSurfaceVariant)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(noticeTitle(notice), style = MaterialTheme.typography.titleSmall, color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Crossfade(noticeMessage(notice), animationSpec = tween(if (reduced) 0 else 120), label = "noticeText") { message ->
                Text(message, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant,
                    minLines = 1, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (notice.phase == NoticePhase.WORKING && notice.progressTotalMs > 0) TimedProgress(notice, statusColor)
            if (notice.kind == NoticeKind.WAKE) WakePackets(notice)
        }
        Crossfade(notice.phase, animationSpec = tween(if (reduced) 0 else 160), label = "noticeStatus") { phase ->
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                if (phase == NoticePhase.WORKING && !reduced) CircularProgressIndicator(Modifier.size(22.dp), color = statusColor, strokeWidth = 2.dp)
                else Icon(when (phase) {
                    NoticePhase.WORKING -> Icons.Filled.HourglassEmpty
                    NoticePhase.SUCCESS -> Icons.Filled.CheckCircle
                    NoticePhase.ERROR -> Icons.Filled.Warning
                    NoticePhase.INFO -> Icons.Filled.Info
                }, null, Modifier.size(22.dp), tint = statusColor)
            }
        }
    }
}

/** Fixed-height label/value row: moving from 10 to 9 seconds never reflows a handheld card. */
@Composable
private fun WakeCountdownRow(label: String, seconds: Int?) {
    Row(Modifier.fillMaxWidth().heightIn(min = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(if (seconds == null) "" else stringResource(R.string.notice_wake_seconds, seconds),
            modifier = Modifier.width(60.dp),
            style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
            textAlign = TextAlign.End)
    }
}

/** Timed work (a wake): duration is a wait budget, not actual boot-completion progress. */
@Composable
private fun TimedProgress(notice: AppNotice, color: androidx.compose.ui.graphics.Color) {
    val now by produceState(System.currentTimeMillis(), notice.progressStartedAt, notice.progressTotalMs) {
        while (true) { value = System.currentTimeMillis(); delay(500) }
    }
    val elapsed = (now - notice.progressStartedAt).coerceAtLeast(0L)
    val fraction = (elapsed.toFloat() / notice.progressTotalMs).coerceIn(0f, 1f)
    val secondsLeft = ((notice.progressTotalMs - elapsed).coerceAtLeast(0L) + 999) / 1000
    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().height(4.dp),
        color = color, trackColor = color.copy(alpha = .2f), drawStopIndicator = {})
    // Keep the row at zero: removing it just before completion made the whole card jump.
    WakeCountdownRow(stringResource(R.string.notice_wake_remaining_label), secondsLeft.toInt())
}

/** WOL packets and next retry keep the same layout while the seconds tick down. */
@Composable
private fun WakePackets(notice: AppNotice) {
    if (notice.packets <= 0 || notice.packetsSent <= 0) return
    val now by produceState(System.currentTimeMillis(), notice.nextPacketAt) {
        while (true) { value = System.currentTimeMillis(); delay(500) }
    }
    val secondsToNext = ((notice.nextPacketAt - now).coerceAtLeast(0L) + 999) / 1000
    val waitingForNext = notice.phase == NoticePhase.WORKING && notice.packetsSent < notice.packets
    val text = when {
        notice.phase == NoticePhase.SUCCESS -> stringResource(R.string.notice_wake_packets_success, notice.packetsSent)
        notice.phase == NoticePhase.ERROR -> stringResource(R.string.notice_wake_packets_failed, notice.packetsSent, notice.packets)
        notice.packets == 1 -> stringResource(R.string.notice_wake_packet_single)
        waitingForNext -> stringResource(R.string.notice_wake_packet_sent_short, notice.packetsSent, notice.packets)
        else -> stringResource(R.string.notice_wake_packet_last, notice.packetsSent, notice.packets)
    }
    Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1, overflow = TextOverflow.Ellipsis)
    if (notice.phase == NoticePhase.WORKING && notice.packets > 1) {
        WakeCountdownRow(
            label = stringResource(if (waitingForNext) R.string.notice_wake_next_packet_label else R.string.notice_wake_waiting_label),
            seconds = if (waitingForNext) secondsToNext.toInt() else null,
        )
    }
}

private fun kindIcon(kind: NoticeKind) = when (kind) {
    NoticeKind.WAKE -> Icons.Filled.PowerSettingsNew
    NoticeKind.VPN -> Icons.Filled.VpnKey
    NoticeKind.LOGIN -> Icons.AutoMirrored.Filled.Login
    NoticeKind.LIBRARY -> Icons.Filled.VideoLibrary
    NoticeKind.RECORDING -> Icons.Filled.FiberManualRecord
    NoticeKind.SETTINGS -> Icons.Filled.Settings
    NoticeKind.PLAYBACK -> Icons.Filled.PlayArrow
    NoticeKind.CONNECTION -> Icons.Filled.Lan
}
