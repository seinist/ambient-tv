package com.ambient.tv.livetv

import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.Serializable

/**
 * Live TV sources the sidebar shows. A failed probe keeps the last answer; a source leaves only
 * after two "no live TV" answers at least [REMOVE_AFTER_MS] apart (a waking server may briefly
 * report no channels). Persisted, so the entries survive a restart while the server sleeps.
 */
internal class LiveTvKnownSources(private val clock: () -> Long = System::currentTimeMillis) {
    private val known = ConcurrentHashMap<String, AvailableLiveTvSource>()
    private val firstMissAt = ConcurrentHashMap<String, Long>()

    fun available(source: AvailableLiveTvSource): AvailableLiveTvSource {
        firstMissAt.remove(source.source.id)
        known[source.source.id] = source
        return source
    }

    /** Probe answered "no live TV": kept until a second such answer [REMOVE_AFTER_MS] later. */
    fun unavailable(id: String): AvailableLiveTvSource? {
        val now = clock()
        val first = firstMissAt.putIfAbsent(id, now) ?: return known[id]
        if (now - first < REMOVE_AFTER_MS) return known[id]
        firstMissAt.remove(id)
        known.remove(id)
        return null
    }

    fun failed(id: String): AvailableLiveTvSource? = known[id]

    fun retain(ids: Set<String>) {
        known.keys.retainAll(ids)
        firstMissAt.keys.retainAll(ids)
    }

    fun restore(records: List<KnownLiveTvSource>) = records.forEach { known.putIfAbsent(it.id, it.toAvailable()) }

    fun snapshot(): List<KnownLiveTvSource> = known.values.sortedBy { it.source.id }.map(KnownLiveTvSource::of)

    companion object {
        const val REMOVE_AFTER_MS = 2 * 60_000L
    }
}

@Serializable
internal data class KnownLiveTvSource(val id: String, val system: String, val name: String, val capabilities: LiveTvCapabilities) {
    fun toAvailable() = AvailableLiveTvSource(LiveTvSource(id, system, name), capabilities)
    companion object {
        fun of(source: AvailableLiveTvSource) = KnownLiveTvSource(source.source.id, source.source.system, source.source.name, source.capabilities)
    }
}
