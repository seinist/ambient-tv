package com.ambient.tv.data.telegram

/** Short bounded suppression of ordinary thumbnail misses during rapid view changes.
 * Keys include the repository account epoch and native media revision.
 * A local hit is checked first; exceptions/cancellation never enter this cache.
 */
internal class ThumbnailMissCache(
    private val maxEntries: Int = 256,
    private val ttlMs: Long = 5_000L,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private val entries = LinkedHashMap<String, Long>(16, .75f, true)

    @Synchronized fun isRecent(key: String): Boolean {
        val at = entries[key] ?: return false
        if (nowMs() - at < ttlMs) return true
        entries.remove(key)
        return false
    }

    @Synchronized fun record(key: String) {
        entries[key] = nowMs()
        while (entries.size > maxEntries.coerceAtLeast(0)) entries.remove(entries.keys.first())
    }

    @Synchronized fun clear() { entries.clear() }
}
