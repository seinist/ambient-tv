package com.ambient.tv.ui.player

import com.ambient.tv.domain.model.MediaItem
import com.ambient.tv.domain.model.MediaTag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull

/** Movie id, or the series id of an episode: the item whose collections apply. Null for anything else. */
internal fun videoScopeOwner(item: MediaItem): String? = when {
    item.type.equals("movie", ignoreCase = true) -> item.ratingKey.takeIf { it.isNotBlank() }
    item.type.equals("episode", ignoreCase = true) -> item.grandparentRatingKey?.takeIf { it.isNotBlank() }
    else -> null
}

/** Season, series and collections of [item] on [serverId]; null for anything but movies and episodes. */
internal fun playbackVideoScope(item: MediaItem, serverId: String?, collections: List<MediaTag>): PlaybackVideoScope? {
    val episode = item.type.equals("episode", ignoreCase = true)
    val movie = item.type.equals("movie", ignoreCase = true)
    if (!episode && !movie) return null
    return PlaybackVideoScope(
        serverId = serverId,
        seasonId = item.parentRatingKey.takeIf { episode },
        seasonLabel = item.parentTitle.takeIf { episode },
        seriesId = item.grandparentRatingKey.takeIf { episode },
        seriesLabel = item.grandparentTitle.takeIf { episode },
        collections = collections.filter { !it.id.isNullOrBlank() },
        episode = episode,
    )
}

/**
 * Collections per movie/series, looked up off the playback path. [get] waits at most `waitMs`; a slower
 * lookup keeps running in [scope] and is cached for the next play. Only results are cached (newest
 * [maxEntries], least recently used dropped); a failed or empty-handed lookup is retried after [retryAfterMs].
 * Concurrent requests for one key share a single lookup.
 */
internal class CollectionsLookupCache(
    private val scope: CoroutineScope,
    private val maxEntries: Int = 500,
    private val retryAfterMs: Long = 5 * 60_000L,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val done = object : LinkedHashMap<String, List<MediaTag>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<MediaTag>>?) = size > maxEntries
    }
    private val inFlight = HashMap<String, Deferred<List<MediaTag>?>>()
    private val failedAt = object : LinkedHashMap<String, Long>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?) = size > maxEntries
    }

    @Synchronized fun cached(key: String): List<MediaTag>? = done[key]

    @Synchronized internal fun size(): Int = done.size

    suspend fun get(key: String, waitMs: Long, fetch: suspend () -> List<MediaTag>?): List<MediaTag>? {
        val pending = synchronized(this) {
            done[key]?.let { return it }
            inFlight[key] ?: run {
                if (failedAt[key]?.let { clock() - it < retryAfterMs } == true) return null
                scope.async(start = CoroutineStart.LAZY) {
                    val value = runCatching { fetch() }.getOrNull()
                    synchronized(this@CollectionsLookupCache) {
                        inFlight.remove(key)
                        if (value != null) { done[key] = value; failedAt.remove(key) } else failedAt[key] = clock()
                    }
                    value
                }.also { inFlight[key] = it; it.start() }
            }
        }
        return withTimeoutOrNull(waitMs) { pending.await() }
    }
}

/** Longest wait for collections before playback continues without the collection scope. */
internal const val VIDEO_SCOPE_COLLECTIONS_WAIT_MS = 750L

/** Process-wide collections cache for the player's season/series/collection scopes. */
internal val SeriesCollectionsCache = CollectionsLookupCache(CoroutineScope(SupervisorJob() + Dispatchers.IO))
