package com.ambient.tv.livetv

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.ambient.tv.ui.MainActivity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import androidx.compose.runtime.mutableLongStateOf
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.hilt.android.EntryPointAccessors
import androidx.tv.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.core.app.NotificationCompat
import com.ambient.tv.R
import com.ambient.tv.launcher.LauncherIntents
import com.ambient.tv.ui.common.AppDialog
import com.ambient.tv.ui.common.TvActionButton
import com.ambient.tv.ui.navigation.Destinations
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** "Remind me" for a programme in the guide of any live source. */
@Serializable
data class LiveTvReminder(
    val sourceId: String,
    val channelUuid: String,
    val channelName: String,
    val eventId: String,
    val title: String,
    val startEpochSeconds: Long,
    val youtube: Boolean = false,
    val due: Boolean = false,
    val snoozedUntilMs: Long = 0L,
    val stopEpochSeconds: Long? = null,
    val imageUrl: String? = null,
    val ready: Boolean = false,
    val probeAttempts: Int = 0,
    val plan: ScheduledPlayback? = null,
    val sessionEnd: Boolean = false,
    val automaticAttempted: Boolean = false,
) {
    val id: String get() = if (sessionEnd) "session-end:$sourceId:$eventId:$startEpochSeconds" else if (plan != null) "plan:${plan.provider}:${plan.targetId}" else if (youtube) "youtube:$eventId" else "$sourceId|$eventId"
    fun route(): String = plan?.route() ?: if (youtube) Destinations.youtubePlayer(eventId)
        else Destinations.tvhPlayer("channel", channelUuid, sourceId.ifBlank { null })
    fun expiryAtMs(): Long = (if (youtube || plan != null) startEpochSeconds + 7 * 86400L
        else stopEpochSeconds ?: (startEpochSeconds + 3600L)) * 1000L
    fun isRelevant(nowMs: Long): Boolean = expiryAtMs() > nowMs
    fun alarmAtMs(leadMinutes: Int, autoSwitch: Boolean): Long =
        if (snoozedUntilMs > 0) snoozedUntilMs else startEpochSeconds * 1000 - (if (autoSwitch) 0 else leadMinutes) * 60_000L
}

/** Own preferences file, so the alarm receiver can read it without the app's object graph. */
@Singleton
class LiveTvReminderStore @Inject constructor(@ApplicationContext private val context: Context) {
    private val prefs = context.getSharedPreferences("live_tv_reminders", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val mutable = shared
    init { refresh() }
    val reminders = mutable.asStateFlow()
    val homeVisible = sharedHomeVisible.asStateFlow()
    fun setHomeVisible(visible: Boolean) {
        prefs.edit().putBoolean("home_visible", visible).apply()
        sharedHomeVisible.value = visible
    }

    private fun load(): List<LiveTvReminder> = runCatching { json.decodeFromString<List<LiveTvReminder>>(prefs.getString(KEY, "[]")!!) }
        .getOrDefault(emptyList())
        // Old records without an end time retain the previous one-hour fallback.
        .mapNotNull { if (it.isRelevant(System.currentTimeMillis())) it else it.nextOccurrence(System.currentTimeMillis()) }

    fun refresh() = synchronized(lock) {
        mutable.value = load()
        sharedHomeVisible.value = prefs.getBoolean("home_visible", true)
    }

    fun has(sourceId: String, eventId: String): Boolean = reminders.value.any { it.sourceId == sourceId && it.eventId == eventId }

    /** Adds or removes; true when the programme now has a reminder. */
    fun toggle(reminder: LiveTvReminder): Boolean = synchronized(lock) {
        val current = load()
        val exists = current.any { it.id == reminder.id }
        if (!exists && current.size >= 200) return@synchronized false
        val next = if (exists) current.filterNot { it.id == reminder.id } else (current + reminder).sortedBy { it.startEpochSeconds }
        save(next)
        if (exists) {
            LiveTvReminderScheduler.cancel(context, reminder)
            context.getSystemService(NotificationManager::class.java)?.cancel(reminder.id, 0)
        } else LiveTvReminderScheduler.schedule(context, reminder)
        !exists
    }

    fun putPlan(reminder: LiveTvReminder): Boolean = synchronized(lock) {
        if (reminder.plan == null || reminder.startEpochSeconds * 1000 <= System.currentTimeMillis()) return@synchronized false
        val current = load()
        if (current.size >= 200 && current.none { it.id == reminder.id }) return@synchronized false
        val next = (current.filterNot { it.id == reminder.id } + reminder).sortedBy { it.startEpochSeconds }
        if (!save(next)) return@synchronized false
        LiveTvReminderScheduler.cancel(context, reminder)
        context.getSystemService(NotificationManager::class.java)?.cancel(reminder.id, 0)
        LiveTvReminderScheduler.schedule(context, reminder)
        true
    }

    /** Consume only the exact occurrence whose playback has been confirmed. */
    fun completePlan(expected: LiveTvReminder) = synchronized(lock) {
        val current = load()
        val entry = current.firstOrNull { it.id == expected.id && it.startEpochSeconds == expected.startEpochSeconds && it.plan == expected.plan }
            ?: return@synchronized
        val now = System.currentTimeMillis()
        val next = entry.nextOccurrence(now)
        val p = entry.plan ?: return@synchronized
        val end = if (p.game && p.remindAtEnd && p.durationMinutes > 0) LiveTvReminder(
            sourceId = p.provider, channelUuid = "", channelName = entry.channelName,
            eventId = p.targetId, title = context.getString(R.string.schedule_session_end_title, entry.title),
            startEpochSeconds = now / 1000 + p.durationMinutes * 60L,
            sessionEnd = true,
        ) else null
        val remaining = current.filterNot { it.id == entry.id } + listOfNotNull(next)
        val replacement = (remaining + listOfNotNull(end).take((200 - remaining.size).coerceAtLeast(0))).sortedBy { it.startEpochSeconds }
        if (!save(replacement)) return@synchronized
        LiveTvReminderScheduler.cancel(context, entry)
        context.getSystemService(NotificationManager::class.java)?.cancel(entry.id, 0)
        listOfNotNull(next, end).filter { candidate -> replacement.any { it.id == candidate.id } }
            .forEach { LiveTvReminderScheduler.schedule(context, it) }
        Unit
    }

    fun remove(id: String) = synchronized(lock) {
        val current = load()
        current.firstOrNull { it.id == id }?.let {
            LiveTvReminderScheduler.cancel(context, it)
            context.getSystemService(NotificationManager::class.java)?.cancel(it.id, 0)
        }
        save(current.filterNot { it.id == id })
    }

    /** Update only known event IDs; absence from a partial guide never cancels a reminder. */
    fun reconcileGuide(sourceId: String, events: List<LiveTvProgramme>) = synchronized(lock) {
        val current = load()
        val byId = events.associateBy { it.eventId }
        val changed = mutableListOf<LiveTvReminder>()
        val next = current.map { old ->
            val event = byId[old.eventId]?.takeIf { old.plan == null && !old.sessionEnd && !old.youtube && old.sourceId == sourceId && it.channelUuid == old.channelUuid }
            if (event == null) old else {
                val moved = event.startEpochSeconds != old.startEpochSeconds
                old.copy(startEpochSeconds = event.startEpochSeconds, stopEpochSeconds = event.stopEpochSeconds,
                    title = event.title, channelName = event.channelName,
                    due = if (moved) false else old.due,
                    ready = if (moved) false else old.ready,
                    snoozedUntilMs = if (moved) 0L else old.snoozedUntilMs).also { if (it != old) changed.add(it) }
            }
        }
        if (changed.isNotEmpty()) {
            save(next.filter { it.isRelevant(System.currentTimeMillis()) })
            changed.forEach {
                LiveTvReminderScheduler.cancel(context, it)
                if (!it.due) context.getSystemService(NotificationManager::class.java)?.cancel(it.id, 0)
                if (it.isRelevant(System.currentTimeMillis())) LiveTvReminderScheduler.schedule(context, it)
            }
        }
    }

    fun markDue(id: String): LiveTvReminder? = synchronized(lock) {
        val current = load()
        val reminder = current.firstOrNull { it.id == id && !it.due } ?: return@synchronized null
        // A replaced alarm can already be in the delivery queue; never fire it early.
        if (LiveTvReminderScheduler.alarmAt(context, reminder) > System.currentTimeMillis()) {
            LiveTvReminderScheduler.schedule(context, reminder)
            return@synchronized null
        }
        val updated = reminder.copy(due = true, snoozedUntilMs = 0L, ready = false)
        save(current.map { if (it.id == id) updated else it })
        updated
    }

    fun markYoutubeReady(expected: LiveTvReminder) = synchronized(lock) {
        val current = load()
        if (current.none { it == expected && it.youtube && it.due }) return@synchronized
        save(current.map { if (it.id == expected.id) it.copy(ready = true, probeAttempts = 0) else it })
    }

    /** Preserve a delayed premiere; stale probes must not resurrect a removed or snoozed entry. */
    fun deferYoutube(expected: LiveTvReminder, revisedStartMs: Long?) = synchronized(lock) {
        val current = load()
        if (current.none { it == expected && it.youtube && it.due }) return@synchronized
        val now = System.currentTimeMillis()
        val attempt = (expected.probeAttempts + 1).coerceIn(1, 5)
        val retry = now + (30_000L shl (attempt - 1)).coerceAtMost(300_000L)
        val start = revisedStartMs?.takeIf { it > now }?.div(1000) ?: expected.startEpochSeconds
        val updated = expected.copy(startEpochSeconds = start, due = false, ready = false,
            snoozedUntilMs = maxOf(retry, start * 1000), probeAttempts = attempt)
        save(current.map { if (it.id == expected.id) updated else it })
        context.getSystemService(NotificationManager::class.java)?.cancel(expected.id, 0)
        LiveTvReminderScheduler.schedule(context, updated)
    }

    fun snooze(id: String) = synchronized(lock) {
        val current = load()
        val reminder = current.firstOrNull { it.id == id } ?: return@synchronized
        val updated = reminder.copy(due = false, ready = false, automaticAttempted = reminder.automaticAttempted || reminder.plan != null,
            snoozedUntilMs = maxOf(System.currentTimeMillis() + 5 * 60_000L,
            if (!reminder.due) LiveTvReminderScheduler.alarmAt(context, reminder) else 0L))
        save(current.map { if (it.id == id) updated else it })
        context.getSystemService(NotificationManager::class.java)?.cancel(id, 0)
        LiveTvReminderScheduler.schedule(context, updated)
    }

    fun find(id: String): LiveTvReminder? = load().firstOrNull { it.id == id }

    private fun save(list: List<LiveTvReminder>): Boolean {
        val saved = prefs.edit().putString(KEY, json.encodeToString(list)).commit()
        mutable.update { list }
        return saved
    }

    private companion object {
        const val KEY = "reminders"
        val lock = Any()
        val shared = MutableStateFlow<List<LiveTvReminder>>(emptyList())
        val sharedHomeVisible = MutableStateFlow(true)
    }
}

object LiveTvReminderScheduler {
    private const val ACTION_FIRE = "com.ambient.tv.action.LIVE_REMINDER"
    const val EXTRA_ID = "com.ambient.tv.extra.REMINDER_ID"

    fun autoSwitch(context: Context, youtube: Boolean = false): Boolean =
        context.getSharedPreferences("live_tv_reminders", Context.MODE_PRIVATE).getBoolean(if (youtube) "youtube_auto_switch" else "auto_switch", false)

    fun setAutoSwitch(context: Context, enabled: Boolean, youtube: Boolean = false) {
        context.getSharedPreferences("live_tv_reminders", Context.MODE_PRIVATE).edit().putBoolean(if (youtube) "youtube_auto_switch" else "auto_switch", enabled).apply()
        rescheduleAll(context)
    }

    fun leadMinutes(context: Context, youtube: Boolean = false): Int =
        context.getSharedPreferences("live_tv_reminders", Context.MODE_PRIVATE)
            .getInt(if (youtube) "youtube_lead_minutes" else "lead_minutes", if (youtube) 0 else 2).coerceIn(0, 15)

    fun setLeadMinutes(context: Context, minutes: Int, youtube: Boolean = false) {
        context.getSharedPreferences("live_tv_reminders", Context.MODE_PRIVATE).edit()
            .putInt(if (youtube) "youtube_lead_minutes" else "lead_minutes", minutes.coerceIn(0, 15)).apply()
        rescheduleAll(context)
    }

    fun alarmAt(context: Context, reminder: LiveTvReminder): Long =
        reminder.alarmAtMs(if (reminder.sessionEnd) 0 else reminder.plan?.leadMinutes ?: leadMinutes(context, reminder.youtube), shouldAutoSwitch(context, reminder))

    fun shouldAutoSwitch(context: Context, reminder: LiveTvReminder): Boolean =
        !reminder.sessionEnd && !reminder.automaticAttempted && (reminder.plan?.automatic ?: autoSwitch(context, reminder.youtube))

    private fun pending(context: Context, reminder: LiveTvReminder, create: Boolean): PendingIntent? =
        PendingIntent.getBroadcast(
            context, reminder.id.hashCode(),
            Intent(context, LiveTvReminderReceiver::class.java).setAction(ACTION_FIRE)
                .setData(Uri.Builder().scheme("ambient-reminder").authority("alarm").appendPath(reminder.id).build())
                .putExtra(EXTRA_ID, reminder.id),
            PendingIntent.FLAG_IMMUTABLE or if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE,
        )

    fun schedule(context: Context, reminder: LiveTvReminder) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        if (reminder.due) return
        val at = alarmAt(context, reminder).coerceAtLeast(System.currentTimeMillis() + 1000)
        val intent = pending(context, reminder, create = true) ?: return
        // Exact alarms need a special permission on Android 12+; a few seconds of slack are fine for TV.
        if (android.os.Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()) {
            runCatching { alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent) }
                .onFailure { alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent) }
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
        }
    }

    fun cancel(context: Context, reminder: LiveTvReminder) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        pending(context, reminder, create = false)?.let { alarms?.cancel(it); it.cancel() }
        // Cancel old-version PendingIntents without a data URI during migration.
        if (!reminder.youtube) PendingIntent.getBroadcast(context, reminder.id.hashCode(),
            Intent(context, LiveTvReminderReceiver::class.java).setAction(ACTION_FIRE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE)?.let { alarms?.cancel(it); it.cancel() }
    }

    /** After a reboot or a changed lead time: alarms do not survive either. */
    fun rescheduleAll(context: Context) {
        LiveTvReminderStore(context.applicationContext).reminders.value.filterNot { it.due }.forEach {
            cancel(context, it)
            schedule(context, it)
        }
    }

    internal fun isFire(intent: Intent?) = intent?.action == ACTION_FIRE
}

/** A reminder that is due while the app is open; [LiveTvReminderPromptDialog] shows it. */
object LiveTvReminderPrompt {
    var foreground = false
        internal set
    val selectedId = MutableStateFlow<String?>(null)
    val plannedLaunch = MutableStateFlow<LiveTvReminder?>(null)
    fun launchPlan(reminder: LiveTvReminder) { plannedLaunch.value = reminder }
    fun select(id: String) { selectedId.value = id }
}

class LiveTvReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED || intent?.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            LiveTvReminderScheduler.rescheduleAll(context)
            if (!LiveTvReminderPrompt.foreground) LiveTvReminderStore(context.applicationContext)
                .reminders.value.filter { it.due }.forEach { notify(context, it) }
            return
        }
        if (!LiveTvReminderScheduler.isFire(intent)) return
        val id = intent?.getStringExtra(LiveTvReminderScheduler.EXTRA_ID) ?: return
        val store = LiveTvReminderStore(context.applicationContext)
        val reminder = store.markDue(id) ?: return
        if (!LiveTvReminderPrompt.foreground) {
            notify(context, reminder)
        }
    }

    private fun notify(context: Context, reminder: LiveTvReminder) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.reminder_channel), NotificationManager.IMPORTANCE_HIGH))
        val open = Intent(context, MainActivity::class.java).setAction(Intent.ACTION_MAIN)
            .setData(Uri.Builder().scheme("ambient-reminder").authority("open").appendPath(reminder.id).build())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val time = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(reminder.startEpochSeconds * 1000))
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(context.getString(R.string.reminder_title, reminder.title))
            .setContentText("${reminder.channelName} · $time")
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(PendingIntent.getActivity(context, reminder.id.hashCode(), open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .build()
        runCatching { manager.notify(reminder.id, 0, notification) }
    }

    private companion object { const val CHANNEL = "live_tv_reminders" }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ProgrammeReminderProbeEntryPoint {
    fun smartTubeEngine(): com.ambient.tv.youtube.SmartTubeEngine
    fun appPreferences(): com.ambient.tv.data.local.AppPreferences
}

/** Persisted queue: unresolved reminders survive process death and are displayed one at a time. */
@Composable
fun LiveTvReminderPromptDialog() {
    val context = LocalContext.current.applicationContext
    val store = remember(context) { LiveTvReminderStore(context) }
    var resumed by remember { mutableStateOf(false) }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        resumed = true
        LiveTvReminderPrompt.foreground = true
        store.refresh()
        LiveTvReminderScheduler.rescheduleAll(context)
        onPauseOrDispose { resumed = false; LiveTvReminderPrompt.foreground = false }
    }
    val reminders by store.reminders.collectAsState()
    val selectedId by LiveTvReminderPrompt.selectedId.collectAsState()
    val pendingRoute by LauncherIntents.requests.collectAsState()
    val pendingPlan by LiveTvReminderPrompt.plannedLaunch.collectAsState()
    val awaitingPlan by ScheduledPlaybackRuntime.awaiting.collectAsState()
    // Inexact Android alarms can be delayed; while the app is visible a single
    // cancellable timer also wakes for the nearest pending reminder.
    val next = reminders.filterNot { it.due }.minByOrNull { LiveTvReminderScheduler.alarmAt(context, it) }
    val nextAt = next?.let { LiveTvReminderScheduler.alarmAt(context, it) }
    LaunchedEffect(resumed, next, nextAt) {
        if (!resumed || next == null || nextAt == null) return@LaunchedEffect
        delay((nextAt - System.currentTimeMillis()).coerceAtLeast(0L))
        store.markDue(next.id)
    }
    val expiry = reminders.minOfOrNull { it.expiryAtMs() }
    LaunchedEffect(resumed, expiry) {
        if (!resumed || expiry == null) return@LaunchedEffect
        delay((expiry - System.currentTimeMillis()).coerceAtLeast(0L))
        val manager = context.getSystemService(NotificationManager::class.java)
        reminders.filterNot { it.isRelevant(System.currentTimeMillis()) }.forEach {
            manager?.cancel(it.id, 0)
            LiveTvReminderScheduler.cancel(context, it)
        }
        store.refresh()
        LiveTvReminderScheduler.rescheduleAll(context)
    }
    val nextProbe = reminders.filter { it.youtube && it.due && !it.ready }.minByOrNull { it.startEpochSeconds }
    LaunchedEffect(resumed, nextProbe) {
        if (!resumed || nextProbe == null) return@LaunchedEffect
        delay((nextProbe.startEpochSeconds * 1000 - System.currentTimeMillis()).coerceAtLeast(0L))
        try {
            val entry = EntryPointAccessors.fromApplication(context, ProgrammeReminderProbeEntryPoint::class.java)
            check(entry.appPreferences().youtubeEnabled && entry.smartTubeEngine().available)
            val offline = entry.smartTubeEngine().probeProgrammeReminder(nextProbe.eventId)
            if (offline == null) store.markYoutubeReady(nextProbe)
            else store.deferYoutube(nextProbe, offline.scheduledStartMs)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            store.deferYoutube(nextProbe, null)
        }
    }
    var lastLaunchAt by remember { mutableLongStateOf(0L) }
    if (!resumed) return
    val now = System.currentTimeMillis()
    val due = reminders.filter { it.due && it.id != awaitingPlan?.id && it.isRelevant(now) &&
        (!it.youtube || it.ready || it.startEpochSeconds * 1000 > now) }.sortedBy { it.startEpochSeconds }
    val selected = reminders.firstOrNull { it.id == selectedId }
    val reminder = selected?.takeIf { it.isRelevant(now) } ?: due.firstOrNull() ?: return
    val time = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)
        .format(java.util.Date(reminder.startEpochSeconds * 1000))
    val competing = reminders.any { it.id != reminder.id && !it.sessionEnd && (it.due || reminder.overlaps(it)) }
    val automatic = selected == null && due.size == 1 && !competing && pendingRoute == null && pendingPlan == null && awaitingPlan == null &&
        now - lastLaunchAt >= 60_000L && (!reminder.youtube || reminder.ready) &&
        LiveTvReminderScheduler.shouldAutoSwitch(context, reminder) &&
        reminder.startEpochSeconds * 1000 <= now
    if (automatic) {
        LaunchedEffect(reminder.id) {
            if (store.find(reminder.id)?.due != true || LauncherIntents.requests.value != null || LiveTvReminderPrompt.plannedLaunch.value != null) return@LaunchedEffect
            lastLaunchAt = System.currentTimeMillis()
            if (reminder.plan != null) { store.snooze(reminder.id); LiveTvReminderPrompt.launchPlan(reminder) }
            else {
                if (reminder.youtube) store.snooze(reminder.id) else store.remove(reminder.id)
                LauncherIntents.post(reminder.route())
            }
        }
        return
    }
    val closeSelection = { LiveTvReminderPrompt.selectedId.value = null }
    if (reminder.sessionEnd) {
        AppDialog(title = reminder.title, hint = time, onDismiss = { closeSelection(); if (reminder.due) store.remove(reminder.id) },
            footer = { TvActionButton(stringResource(if (reminder.due) R.string.common_back else R.string.reminder_remove), { closeSelection(); store.remove(reminder.id) }) })
        return
    }
    if (selected != null && selected.plan != null) {
        ScheduledPlaybackDialog(selected, store) { closeSelection() }
        return
    }
    AppDialog(
        onDismiss = { closeSelection(); if (reminder.due) store.snooze(reminder.id) },
        title = if (reminder.due) stringResource(R.string.reminder_title, reminder.title) else reminder.title,
        hint = reminder.channelName + " · " + time,
        footer = {
            if (reminder.due) {
                TvActionButton(stringResource(R.string.programme_reminder_play), {
                    closeSelection()
                    lastLaunchAt = System.currentTimeMillis()
                    if (reminder.plan != null) { store.snooze(reminder.id); LiveTvReminderPrompt.launchPlan(reminder) }
                    else {
                        if (reminder.youtube) store.snooze(reminder.id) else store.remove(reminder.id)
                        LauncherIntents.post(reminder.route())
                    }
                }, selected = true)
                TvActionButton(stringResource(R.string.programme_reminder_snooze), { closeSelection(); store.snooze(reminder.id) })
            }
            TvActionButton(stringResource(R.string.reminder_remove), { closeSelection(); store.remove(reminder.id) })
            if (!reminder.due) TvActionButton(stringResource(R.string.common_back), closeSelection)
        },
    ) {
        if (reminder.youtube && reminder.due) Text(stringResource(
            if (reminder.ready) R.string.programme_reminder_ready else R.string.programme_reminder_scheduled_hint))
    }
}
