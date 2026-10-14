package com.ambient.tv.ui.player

/** Main-thread queue: coalesce heartbeats while preserving the final stop of each generation. */
internal class PlaybackReportQueue<T>(private val capacity: Int = 64) {
    data class Entry<T>(val value: T, val stopped: Boolean)
    private val entries = LinkedHashMap<Long, Entry<T>>()
    init { require(capacity > 0) }
    val isEmpty: Boolean get() = entries.isEmpty()

    fun put(generation: Long, value: T, stopped: Boolean) {
        val previous = entries[generation]
        if (previous?.stopped == true && !stopped) return
        entries[generation] = Entry(value, stopped)
        while (entries.size > capacity) entries.remove(entries.keys.first())
    }

    fun take(): Entry<T>? = entries.keys.firstOrNull()?.let(entries::remove)
}
