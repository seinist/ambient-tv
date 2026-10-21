package com.ambient.tv.tvheadend.data

import com.ambient.tv.livetv.LiveTvChannel
import com.ambient.tv.livetv.LiveTvProgramme
import com.ambient.tv.livetv.LiveTvRecording
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Disk side of the TVHeadend cache; [com.ambient.tv.data.browse.BrowseDiskStore] in the app. */
internal interface TvheadendCacheStore {
    fun put(scope: String, key: String, valid: () -> Boolean, encode: () -> String)
    suspend fun read(scope: String): Map<String, String>
    fun remove(scope: String)
}

internal class BrowseTvheadendCacheStore(private val disk: com.ambient.tv.data.browse.BrowseDiskStore) : TvheadendCacheStore {
    override fun put(scope: String, key: String, valid: () -> Boolean, encode: () -> String) = disk.put(scope, key, valid, encode)
    override suspend fun read(scope: String): Map<String, String> = disk.read(scope)
    override fun remove(scope: String) = disk.remove(scope)
}

/**
 * Last successful TVHeadend answers per server/account scope, kept for 30 days (store retention)
 * so channels, guide and recordings stay visible while the server sleeps. Display data only:
 * recording URLs are dropped and logo URLs lose their ticket; streams are never stored.
 * A new scope (other host, port, HTTPS, user or password) removes the previous scope's data.
 */
internal class TvheadendDiskCache(private val store: TvheadendCacheStore) {
    private val json = Json { ignoreUnknownKeys = true }
    private class Scope(val key: String) {
        @Volatile var raw: Map<String, String>? = null
        val values = ConcurrentHashMap<String, Any>()
        val restoreLock = Mutex()
    }
    @Volatile private var active: Scope? = null

    @Synchronized private fun sync(scope: String): Scope {
        active?.takeIf { it.key == scope }?.let { return it }
        active?.let { store.remove(it.key) }
        return Scope(scope).also { active = it }
    }

    /** TVHeadend disabled: forget the active scope's data (memory and disk). */
    @Synchronized fun clear(scope: String?) {
        setOfNotNull(scope, active?.key).forEach(store::remove)
        active = null
    }

    @Suppress("UNCHECKED_CAST")
    suspend fun <T : Any> read(scope: String, key: String, serializer: KSerializer<T>): T? {
        val state = sync(scope)
        synchronized(this) {
            if (active !== state) return null
            (state.values[key] as? T)?.let { return it }
        }
        val records = state.raw ?: state.restoreLock.withLock {
            state.raw ?: store.read(scope).also { state.raw = it }
        }
        if (active !== state) return null
        val decoded = records[key]?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() } ?: return null
        return synchronized(this) {
            // Scope identity also rejects A -> B -> A and clear -> reload while decoding.
            if (active !== state) return null
            state.values.putIfAbsent(key, decoded)
            state.values[key] as? T
        }
    }

    @Synchronized fun <T : Any> write(scope: String, key: String, serializer: KSerializer<T>, value: T) {
        val state = sync(scope)
        val stored = displayOnly(value)
        state.values[key] = stored
        store.put(scope, key, { active === state && state.values[key] === stored }) { json.encodeToString(serializer, stored) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> displayOnly(value: T): T = when {
        value is List<*> && value.firstOrNull() is LiveTvRecording -> (value as List<LiveTvRecording>).map { it.copy(url = null) } as T
        value is List<*> && value.firstOrNull() is LiveTvChannel -> (value as List<LiveTvChannel>).map { it.copy(iconUrl = persistedLogoUrl(it.iconUrl)) } as T
        else -> value
    }

    companion object {
        const val SERVER = "server"
        const val CHANNELS = "channels"
        const val NOW = "now"
        const val EPG = "epg"
        const val EPG_WINDOW_SECONDS = 12L * 3600L
        const val MAX_EPG_EVENTS = 8_000

        /** Dated guide data, trimmed to what can still be shown: now until now + 12 h. */
        fun trimEpg(events: List<LiveTvProgramme>, nowEpochSeconds: Long): List<LiveTvProgramme> =
            events.filter { it.stopEpochSeconds > nowEpochSeconds && it.startEpochSeconds <= nowEpochSeconds + EPG_WINDOW_SECONDS }
                .distinctBy { it.eventId }.sortedBy { it.startEpochSeconds }.take(MAX_EPG_EVENTS)

        /** New window wins over the stored one for the same event; old events past their end drop out. */
        fun mergeEpg(stored: List<LiveTvProgramme>, fresh: List<LiveTvProgramme>, nowEpochSeconds: Long): List<LiveTvProgramme> =
            trimEpg(fresh + stored, nowEpochSeconds)

        /** Ticket and any other query parameter removed; the image is keyed by its path (channel UUID). */
        fun persistedLogoUrl(url: String?): String? {
            val value = url ?: return null
            val parsed = value.toHttpUrlOrNull() ?: return null
            return parsed.newBuilder().username("").password("").query(null).build().toString()
        }
    }
}
