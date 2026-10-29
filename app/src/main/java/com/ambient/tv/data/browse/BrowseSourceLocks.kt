package com.ambient.tv.data.browse

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Automatic cache expiry must not invalidate a remote write's generation while it is committing. */
internal class BrowseSourceLocks {
    private val locks = ConcurrentHashMap<String, Mutex>()

    private fun keys(sources: Set<String>) =
        (if ("*" in sources) CACHE_SOURCES else sources.map { it.substringBefore(':') }.toSet()).intersect(CACHE_SOURCES)

    fun covers(held: Set<String>, requested: Set<String>): Boolean = keys(held).containsAll(keys(requested))

    suspend fun <T> withSources(sources: Set<String>, block: suspend () -> T): T {
        val keys = keys(sources).sorted()
        suspend fun acquire(index: Int): T {
            if (index == keys.size) return block()
            return locks.getOrPut(keys[index]) { Mutex() }.withLock { acquire(index + 1) }
        }
        return acquire(0)
    }

    private companion object {
        val CACHE_SOURCES = setOf("plex", "jellyfin", "emby", "tvheadend", "iptv", "trailers")
    }
}
