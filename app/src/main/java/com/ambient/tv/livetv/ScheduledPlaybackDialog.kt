package com.ambient.tv.livetv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.ambient.tv.R
import com.ambient.tv.domain.model.MediaItem
import com.ambient.tv.games.library.LibraryGame
import com.ambient.tv.ui.common.AppDialog
import com.ambient.tv.ui.common.TvActionButton
import com.ambient.tv.ui.common.TvSettingRow
import com.ambient.tv.ui.mobile.DeviceActionRow
import java.time.Instant
import java.time.ZonedDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@Composable
internal fun MediaScheduleAction(item: MediaItem) {
    val provider = scheduledMediaProvider(item.ratingKey, item.type) ?: return
    val context = LocalContext.current.applicationContext
    val identity = remember(provider, item.ratingKey) { scheduledPlaybackIdentity(context, provider, item.ratingKey) } ?: return
    val duration = ((item.durationMs ?: 0L) / 60_000L).coerceIn(0L, 1440L).toInt()
    // Private server artwork can contain credentials. Do not copy URLs into the reminder preferences.
    ScheduleAction(item.title, null, ScheduledPlayback(provider, item.ratingKey, identity, durationMinutes = duration))
}

@Composable
internal fun GameScheduleAction(game: LibraryGame, romm: Boolean = false, enabled: Boolean = true) {
    val context = LocalContext.current.applicationContext
    val provider = if (romm) "romm" else "games"
    val identity = remember(provider, game.fileId) { scheduledPlaybackIdentity(context, provider, game.fileId) } ?: return
    ScheduleAction(game.title, null, ScheduledPlayback(provider, game.fileId, identity,
        systemId = game.systemId, durationMinutes = 60), enabled)
}

@Composable
private fun ScheduleAction(title: String, imageUrl: String?, plan: ScheduledPlayback, enabled: Boolean = true) {
    val context = LocalContext.current.applicationContext
    val store = remember(context) { LiveTvReminderStore(context) }
    val entries by store.reminders.collectAsState()
    val draft = LiveTvReminder(plan.provider, "", plan.provider.replaceFirstChar { it.uppercase() }, plan.targetId,
        title, ZonedDateTime.now().plusMinutes(15).withSecond(0).withNano(0).toEpochSecond(),
        imageUrl = imageUrl, plan = plan)
    val existing = entries.firstOrNull { it.id == draft.id }
    var editing by remember(draft.id) { mutableStateOf(false) }
    TvActionButton(stringResource(if (existing == null) R.string.schedule_playback else R.string.schedule_edit),
        onClick = { editing = true }, enabled = enabled, selected = existing != null)
    if (editing) ScheduledPlaybackDialog(existing ?: draft, store) { editing = false }
}

@Composable
internal fun ScheduledPlaybackDialog(entry: LiveTvReminder, store: LiveTvReminderStore, onClose: () -> Unit) {
    val initial = entry.plan ?: return
    val context = LocalContext.current.applicationContext
    val entries by store.reminders.collectAsState()
    val zone = remember(initial.zoneId) { runCatching { ZoneId.of(initial.zoneId) }.getOrElse { ZoneId.systemDefault() } }
    var time by remember(entry.id, entry.startEpochSeconds) {
        mutableStateOf(Instant.ofEpochSecond(entry.startEpochSeconds).atZone(zone).withSecond(0).withNano(0))
    }
    var plan by remember(entry.id, entry.startEpochSeconds) { mutableStateOf(initial) }
    var error by remember(entry.id) { mutableStateOf(false) }
    val candidate = entry.copy(startEpochSeconds = time.toEpochSecond(), due = false, ready = false,
        snoozedUntilMs = 0L, probeAttempts = 0, plan = plan)
    val conflict = entries.any { it.id != candidate.id && candidate.overlaps(it) }
    val exists = entries.any { it.id == entry.id }
    val valid = candidate.startEpochSeconds > System.currentTimeMillis() / 1000
    AppDialog(title = stringResource(R.string.schedule_playback), hint = entry.title, onDismiss = onClose,
        footer = {
            TvActionButton(stringResource(R.string.schedule_save), onClick = {
                if (scheduledPlaybackIdentity(context, plan.provider, plan.targetId) == plan.sourceIdentity &&
                    store.putPlan(candidate)) onClose() else error = true
            }, enabled = valid, selected = true)
            if (exists) TvActionButton(stringResource(R.string.reminder_remove), onClick = { store.remove(entry.id); onClose() })
            TvActionButton(stringResource(R.string.common_back), onClick = onClose)
        }) {
        Text(time.format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .withLocale(Locale.getDefault())))
        DeviceActionRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TvActionButton(stringResource(R.string.schedule_today), onClick = {
                time = ZonedDateTime.now(zone).plusMinutes(15).withSecond(0).withNano(0)
            })
            TvActionButton(stringResource(R.string.schedule_tomorrow), onClick = {
                time = ZonedDateTime.now(zone).plusDays(1).withHour(20).withMinute(15).withSecond(0).withNano(0)
            })
        }
        ScheduleAdjust(stringResource(R.string.schedule_date), time.toLocalDate().toString(),
            { time = time.minusDays(1) }, { time = time.plusDays(1) })
        ScheduleAdjust(stringResource(R.string.schedule_hour), time.hour.toString().padStart(2, '0'),
            { time = time.minusHours(1) }, { time = time.plusHours(1) })
        ScheduleAdjust(stringResource(R.string.schedule_minute), time.minute.toString().padStart(2, '0'),
            { time = time.minusMinutes(1) }, { time = time.plusMinutes(1) })
        TvSettingRow(title = stringResource(R.string.programme_reminder_auto_play), checked = plan.automatic,
            onCheckedChange = { plan = plan.copy(automatic = it) })
        if (!plan.automatic) ScheduleAdjust(stringResource(R.string.reminder_lead_title),
            plan.leadMinutes.toString() + " min",
            { plan = plan.copy(leadMinutes = (plan.leadMinutes - 5).coerceAtLeast(0)) },
            { plan = plan.copy(leadMinutes = (plan.leadMinutes + 5).coerceAtMost(60)) })
        TvSettingRow(title = stringResource(R.string.schedule_restart), checked = plan.restart,
            onCheckedChange = { plan = plan.copy(restart = it) })
        TvSettingRow(title = stringResource(R.string.schedule_weekly), checked = plan.weekly,
            onCheckedChange = { plan = plan.copy(weekly = it) })
        if (plan.game) TvSettingRow(title = stringResource(R.string.schedule_remind_end), checked = plan.remindAtEnd,
            onCheckedChange = { plan = plan.copy(remindAtEnd = it) })
        if (plan.game) ScheduleAdjust(stringResource(R.string.schedule_duration), plan.durationMinutes.toString() + " min",
            { plan = plan.copy(durationMinutes = (plan.durationMinutes - 15).coerceAtLeast(15)) },
            { plan = plan.copy(durationMinutes = (plan.durationMinutes + 15).coerceAtMost(1440)) })
        if (plan.durationMinutes > 0) Text(stringResource(R.string.schedule_end,
            time.plusMinutes(plan.durationMinutes.toLong()).format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT))))
        if (conflict) Text(stringResource(R.string.schedule_conflict))
        if (!valid) Text(stringResource(R.string.schedule_future))
        if (error) Text(stringResource(R.string.schedule_failed))
    }
}

@Composable
private fun ScheduleAdjust(label: String, value: String, minus: () -> Unit, plus: () -> Unit) {
    Text(label)
    DeviceActionRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TvActionButton("−", onClick = minus)
        Text(value)
        TvActionButton("+", onClick = plus)
    }
}
