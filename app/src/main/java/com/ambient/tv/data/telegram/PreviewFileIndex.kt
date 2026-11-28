package com.ambient.tv.data.telegram

import java.io.File

/**
 * Bounded, access-ordered preview disk index. Caller serializes access with the
 * cache's publication/invalidation lock. Files can disappear outside that lock
 * (Android cache reclaim), so hits always verify existence and length.
 */
internal class PreviewFileIndex(
    private val directory: File,
    private val maxCount: Int,
    private val maxBytes: Long,
    private val maxAgeMs: Long = 7L * 24 * 60 * 60 * 1000,
    private val clock: () -> Long = System::currentTimeMillis,
    private val accepts: (File) -> Boolean,
) {
    private data class Entry(val bytes: Long, val touched: Long)
    private val entries = LinkedHashMap<File, Entry>(128, .75f, true)
    private var bytes = 0L
    private var seeded = false

    private fun seed() {
        if (seeded) return
        seeded = true
        directory.listFiles().orEmpty().filter { it.isFile && accepts(it) }
            .map { it to Entry(it.length(), it.lastModified()) }
            .sortedBy { it.second.touched }.forEach { (file, entry) ->
                entries[file] = entry
                bytes += entry.bytes
            }
    }

    private fun remove(file: File) {
        entries.remove(file)?.let { bytes -= it.bytes }
    }

    fun hit(file: File): Boolean {
        seed()
        // A single stat supplies existence, type, length and persisted LRU age.
        val attributes = runCatching {
            java.nio.file.Files.readAttributes(file.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java)
        }.getOrNull()
        val size = attributes?.size() ?: 0L
        if (attributes?.isRegularFile != true || size <= 0L) {
            remove(file)
            return false
        }
        val now = clock()
        val old = entries[file]
        val persisted = attributes.lastModifiedTime().toMillis()
        if (now - (old?.touched ?: persisted) > maxAgeMs) {
            if (file.delete() || !file.exists()) remove(file)
            return false
        }
        if (old == null || old.bytes != size) {
            remove(file)
            bytes += size
        }
        if (now - persisted > 60_000L) file.setLastModified(now)
        entries[file] = Entry(size, now)
        trim(now)
        return entries.containsKey(file)
    }

    fun publish(file: File) {
        seed()
        remove(file)
        val size = file.length()
        if (size <= 0L || !file.isFile) return
        val now = clock()
        entries[file] = Entry(size, now)
        bytes += size
        trim(now)
    }

    private fun trim(now: Long) {
        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) {
            val (file, entry) = iterator.next()
            if (entries.size <= maxCount && bytes <= maxBytes && now - entry.touched <= maxAgeMs) break
            if (file.delete() || !file.exists()) {
                iterator.remove()
                bytes -= entry.bytes
            }
        }
    }

    /** Invalidation also deletes files outside the index at the caller. */
    fun removeMatching(predicate: (File) -> Boolean) {
        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (predicate(entry.key)) {
                bytes -= entry.value.bytes
                iterator.remove()
            }
        }
    }

    fun clear() {
        entries.clear()
        bytes = 0L
        seeded = false
    }
}
