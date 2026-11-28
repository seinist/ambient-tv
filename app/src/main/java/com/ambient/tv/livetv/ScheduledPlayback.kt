package com.ambient.tv.livetv

import android.content.Context
import com.ambient.tv.data.browse.PlaybackAvailability
import com.ambient.tv.data.browse.mediaProvider
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.data.local.TokenStore
import com.ambient.tv.romm.RommAccountStore
import com.ambient.tv.romm.RommKeys
import com.ambient.tv.ui.navigation.Destinations
import com.ambient.tv.ui.navigation.gameSelectionSource
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.hilt.android.EntryPointAccessors
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneId
import java.security.MessageDigest

@Serializable
data class ScheduledPlayback(
    val provider: String,
    val targetId: String,
    val sourceIdentity: String,
    val systemId: String? = null,
    val restart: Boolean = false,
    val automatic: Boolean = false,
    val leadMinutes: Int = 0,
    val durationMinutes: Int = 0,
    val weekly: Boolean = false,
    val remindAtEnd: Boolean = false,
    val zoneId: String = ZoneId.systemDefault().id,
) {
    val game: Boolean get() = provider == "games" || provider == "romm"
    fun route(): String = if (game) Destinations.gamePlayer(targetId, systemId, fresh = restart)
        else Destinations.player(targetId, restart = restart)
}

internal fun scheduledMediaProvider(id: String, type: String): String? =
    mediaProvider(id).takeIf { it in setOf("plex", "jellyfin", "emby") && type in setOf("movie", "episode") && id.isNotBlank() && (it != "plex" || id.all(Char::isDigit)) }

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ScheduledPlaybackEntryPoint {
    fun playbackAvailability(): PlaybackAvailability
    fun appPreferences(): AppPreferences
    fun tokenStore(): TokenStore
    fun rommAccountStore(): RommAccountStore
}

internal fun scheduledPlaybackIdentity(context: Context, provider: String, targetId: String): String? {
    val entry = EntryPointAccessors.fromApplication(context.applicationContext, ScheduledPlaybackEntryPoint::class.java)
    val raw = when (provider) {
        "plex", "jellyfin", "emby" -> {
            if (scheduledMediaProvider(targetId, "movie") != provider) return null
            val prefs = entry.appPreferences()
            val enabled = when (provider) {
                "plex" -> prefs.isConfigured && prefs.plexEnabled
                "jellyfin" -> prefs.isJellyfinConfigured && prefs.jellyfinEnabled
                else -> prefs.isEmbyConfigured && prefs.embyEnabled
            }
            if (!enabled) return null
            entry.playbackAvailability().mediaIdentity(targetId)
        }
        "games" -> gameSelectionSource(entry.appPreferences(), entry.tokenStore(), targetId)?.identity
        "romm" -> entry.rommAccountStore().account()?.serverKey?.takeIf { it == RommKeys.parse(targetId)?.first }
        else -> null
    } ?: return null
    return MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

internal fun LiveTvReminder.nextOccurrence(nowMs: Long): LiveTvReminder? {
    val p = plan?.takeIf { it.weekly } ?: return null
    val zone = runCatching { ZoneId.of(p.zoneId) }.getOrElse { ZoneId.systemDefault() }
    var next = Instant.ofEpochSecond(startEpochSeconds).atZone(zone).plusWeeks(1)
    while (next.toInstant().toEpochMilli() <= nowMs) next = next.plusWeeks(1)
    return copy(startEpochSeconds = next.toEpochSecond(), due = false, snoozedUntilMs = 0L, ready = false, probeAttempts = 0)
}

internal fun LiveTvReminder.overlaps(other: LiveTvReminder): Boolean {
    fun end(entry: LiveTvReminder): Long = entry.stopEpochSeconds
        ?: (entry.startEpochSeconds + (entry.plan?.durationMinutes?.coerceAtLeast(1)?.times(60L) ?: 60L))
    return startEpochSeconds < end(other) && other.startEpochSeconds < end(this)
}

/** A failed/cancelled launch remains in the persistent queue. Only confirmed playback consumes it. */
internal object ScheduledPlaybackRuntime {
    private val pending = kotlinx.coroutines.flow.MutableStateFlow<LiveTvReminder?>(null)
    val awaiting: kotlinx.coroutines.flow.StateFlow<LiveTvReminder?> = pending

    fun awaitStart(entry: LiveTvReminder) { pending.value = entry }
    fun cancel(entry: LiveTvReminder) { pending.compareAndSet(entry, null) }

    fun confirm(context: Context, expected: LiveTvReminder) {
        val plan = expected.plan ?: return
        if (!pending.compareAndSet(expected, null)) return
        if (scheduledPlaybackIdentity(context, plan.provider, plan.targetId) == plan.sourceIdentity)
            LiveTvReminderStore(context.applicationContext).completePlan(expected)
    }

    fun gameStarted(context: Context, fileId: String) {
        val expected = pending.value ?: return
        val plan = expected.plan ?: return
        if (plan.game && plan.targetId == fileId) confirm(context, expected)
    }
}
