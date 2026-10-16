package com.ambient.tv.livetv

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Column
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
) {
    val id: String get() = "$sourceId|$eventId"
}

/** Own preferences file, so the alarm receiver can read it without the app's object graph. */
@Singleton
class LiveTvReminderStore @Inject constructor(@ApplicationContext private val context: Context) {
    private val prefs = context.getSharedPreferences("live_tv_reminders", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val mutable = MutableStateFlow(load())
    val reminders = mutable.asStateFlow()

    private fun load(): List<LiveTvReminder> = runCatching { json.decodeFromString<List<LiveTvReminder>>(prefs.getString(KEY, "[]")!!) }
        .getOrDefault(emptyList())
        // Started more than an hour ago: nothing left to remind of.
        .filter { it.startEpochSeconds > System.currentTimeMillis() / 1000 - 3600 }

    fun refresh() { mutable.value = load() }

    fun has(sourceId: String, eventId: String): Boolean = reminders.value.any { it.sourceId == sourceId && it.eventId == eventId }

    /** Adds or removes; true when the programme now has a reminder. */
    @Synchronized fun toggle(reminder: LiveTvReminder): Boolean {
        val current = load()
        val exists = current.any { it.id == reminder.id }
        val next = if (exists) current.filterNot { it.id == reminder.id } else (current + reminder).sortedBy { it.startEpochSeconds }.take(200)
        save(next)
        if (exists) LiveTvReminderScheduler.cancel(context, reminder) else LiveTvReminderScheduler.schedule(context, reminder)
        return !exists
    }

    @Synchronized fun remove(id: String) = save(load().filterNot { it.id == id })

    fun find(id: String): LiveTvReminder? = load().firstOrNull { it.id == id }

    private fun save(list: List<LiveTvReminder>) {
        prefs.edit().putString(KEY, json.encodeToString(list)).apply()
        mutable.update { list }
    }

    private companion object { const val KEY = "reminders" }
}

object LiveTvReminderScheduler {
    private const val ACTION_FIRE = "com.ambient.tv.action.LIVE_REMINDER"
    const val EXTRA_ID = "com.ambient.tv.extra.REMINDER_ID"

    fun autoSwitch(context: Context): Boolean =
        context.getSharedPreferences("live_tv_reminders", Context.MODE_PRIVATE).getBoolean("auto_switch", false)

    fun setAutoSwitch(context: Context, enabled: Boolean) {
        context.getSharedPreferences("live_tv_reminders", Context.MODE_PRIVATE).edit().putBoolean("auto_switch", enabled).apply()
    }

    private fun leadMs(context: Context): Long =
        context.getSharedPreferences("live_tv_reminders", Context.MODE_PRIVATE).getInt("lead_minutes", 2).coerceIn(0, 15) * 60_000L

    fun setLeadMinutes(context: Context, minutes: Int) {
        context.getSharedPreferences("live_tv_reminders", Context.MODE_PRIVATE).edit().putInt("lead_minutes", minutes.coerceIn(0, 15)).apply()
        rescheduleAll(context)
    }

    fun leadMinutes(context: Context): Int = (leadMs(context) / 60_000L).toInt()

    private fun pending(context: Context, reminder: LiveTvReminder, create: Boolean): PendingIntent? =
        PendingIntent.getBroadcast(
            context, reminder.id.hashCode(),
            Intent(context, LiveTvReminderReceiver::class.java).setAction(ACTION_FIRE).putExtra(EXTRA_ID, reminder.id),
            PendingIntent.FLAG_IMMUTABLE or if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE,
        )

    fun schedule(context: Context, reminder: LiveTvReminder) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val at = (reminder.startEpochSeconds * 1000 - leadMs(context)).coerceAtLeast(System.currentTimeMillis() + 1000)
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
        val intent = pending(context, reminder, create = false) ?: return
        context.getSystemService(AlarmManager::class.java)?.cancel(intent)
        intent.cancel()
    }

    /** After a reboot or a changed lead time: alarms do not survive either. */
    fun rescheduleAll(context: Context) {
        LiveTvReminderStore(context.applicationContext).reminders.value.forEach { schedule(context, it) }
    }

    internal fun isFire(intent: Intent?) = intent?.action == ACTION_FIRE
}

/** A reminder that is due while the app is open; [LiveTvReminderPromptDialog] shows it. */
object LiveTvReminderPrompt {
    var foreground = false
        internal set
    val due = MutableStateFlow<LiveTvReminder?>(null)
}

class LiveTvReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED || intent?.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            LiveTvReminderScheduler.rescheduleAll(context)
            return
        }
        if (!LiveTvReminderScheduler.isFire(intent)) return
        val id = intent?.getStringExtra(LiveTvReminderScheduler.EXTRA_ID) ?: return
        val store = LiveTvReminderStore(context.applicationContext)
        val reminder = store.find(id) ?: return
        store.remove(id)
        if (LiveTvReminderScheduler.autoSwitch(context) && LiveTvReminderPrompt.foreground) {
            LiveTvReminderPrompt.due.value = null
            LauncherIntents.post(Destinations.tvhPlayer("channel", reminder.channelUuid, reminder.sourceId.ifBlank { null }))
        } else {
            LiveTvReminderPrompt.due.value = reminder
            notify(context, reminder)
        }
    }

    private fun notify(context: Context, reminder: LiveTvReminder) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.reminder_channel), NotificationManager.IMPORTANCE_HIGH))
        val open = runCatching {
            Intent.parseUri(LauncherIntents.openChannelUri(context, reminder.channelUuid, reminder.sourceId).toString(), Intent.URI_INTENT_SCHEME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }.getOrNull() ?: return
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
        runCatching { manager.notify(reminder.id.hashCode(), notification) }
    }

    private companion object { const val CHANNEL = "live_tv_reminders" }
}

/** In-app prompt for a due reminder: switch to the channel or dismiss. */
@Composable
fun LiveTvReminderPromptDialog() {
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        LiveTvReminderPrompt.foreground = true
        onPauseOrDispose { LiveTvReminderPrompt.foreground = false }
    }
    val due by LiveTvReminderPrompt.due.collectAsState()
    // Fired while the app was in the background: once the programme has long started, a prompt is noise.
    val reminder = due?.takeIf { it.startEpochSeconds * 1000 > System.currentTimeMillis() - 30 * 60_000L } ?: return
    AppDialog(
        onDismiss = { LiveTvReminderPrompt.due.value = null },
        title = stringResource(R.string.reminder_title, reminder.title),
        hint = reminder.channelName,
        footer = {
            TvActionButton(stringResource(R.string.reminder_switch), {
                LiveTvReminderPrompt.due.value = null
                LauncherIntents.post(Destinations.tvhPlayer("channel", reminder.channelUuid, reminder.sourceId.ifBlank { null }))
            }, selected = true)
            TvActionButton(stringResource(R.string.reminder_dismiss), { LiveTvReminderPrompt.due.value = null })
        },
    )
}
