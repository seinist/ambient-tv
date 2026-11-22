package com.ambient.tv.data.files

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal data class AudioTagBatch(val tags: Map<String, AudioTags>, val complete: Boolean)

/**
 * Two passes: one bounded local lookup, then four missing-file readers. Only the final batch
 * may reorder an album. A bounded channel applies back pressure while batched SQLite IO runs;
 * no coroutine, map copy or write transaction is created for every file in a large folder.
 */
internal suspend fun loadAudioTags(
    cache: AudioTagStorage,
    source: FileSource,
    entries: List<FileEntry>,
    readMissing: Boolean = true,
    valid: () -> Boolean,
    read: suspend (FileEntry) -> AudioTags?,
    publish: suspend (AudioTagBatch) -> Unit,
) = coroutineScope {
    suspend fun checkCurrent() {
        currentCoroutineContext().ensureActive()
        if (!valid()) throw CancellationException("Music folder changed")
    }
    checkCurrent()
    val cached = cache.read(source, entries)
    checkCurrent()
    val found = cached.mapNotNull { (id, tags) -> tags?.let { id to it } }.toMap().toMutableMap()
    val missing = entries.filterNot { cached.containsKey(it.id) }
    publish(AudioTagBatch(found.toMap(), complete = missing.isEmpty()))
    if (!readMissing || missing.isEmpty()) return@coroutineScope

    data class ReadResult(val entry: FileEntry, val tags: AudioTags?, val successful: Boolean)
    val next = AtomicInteger()
    val results = Channel<ReadResult>(capacity = 8)
    repeat(minOf(4, missing.size)) {
        launch {
            while (true) {
                checkCurrent()
                val entry = missing.getOrNull(next.getAndIncrement()) ?: break
                val result = try {
                    ReadResult(entry, read(entry), successful = true)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { ReadResult(entry, null, successful = false) }
                checkCurrent()
                results.send(result)
            }
        }
    }
    val pending = mutableListOf<AudioTagRecord>()
    var completed = 0
    var sincePublish = 0
    var flushAtNanos = 0L
    try {
        while (completed < missing.size) {
            // Publish a few fast tracks even when the next file takes much longer to read.
            val waitMillis = if (sincePublish == 0) 100L else
                ((flushAtNanos - System.nanoTime() + 999_999L) / 1_000_000L).coerceIn(1L, 100L)
            val result = withTimeoutOrNull(waitMillis) { results.receive() }
            checkCurrent()
            if (result == null && sincePublish == 0) continue
            if (result != null) {
                completed++
                if (sincePublish == 0) flushAtNanos = System.nanoTime() + 100_000_000L
                sincePublish++
                if (result.successful) {
                    result.tags?.let { found[result.entry.id] = it }
                    pending += AudioTagRecord(result.entry, result.tags)
                }
            }
            if (result == null || sincePublish >= 12 || System.nanoTime() >= flushAtNanos || completed == missing.size) {
                publish(AudioTagBatch(found.toMap(), complete = completed == missing.size))
                checkCurrent()
                cache.write(source, pending.toList(), valid)
                pending.clear()
                sincePublish = 0
            }
        }
    } finally { results.cancel() }
}

/** Playback uses the same versioned result instead of reopening an already indexed track. */
internal suspend fun readAudioTag(
    cache: AudioTagStorage,
    source: FileSource,
    entry: FileEntry,
    valid: () -> Boolean,
    read: suspend () -> AudioTags?,
): AudioTags? {
    suspend fun checkCurrent() {
        currentCoroutineContext().ensureActive()
        if (!valid()) throw java.io.IOException("Source changed")
    }
    checkCurrent()
    val cached = cache.read(source, listOf(entry))
    checkCurrent()
    if (cached.containsKey(entry.id)) return cached[entry.id]
    val tags = try { read() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { return null }
    checkCurrent()
    cache.write(source, listOf(AudioTagRecord(entry, tags)), valid)
    checkCurrent()
    return tags
}
