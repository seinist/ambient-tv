package com.ambient.tv.data.files

import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class AudioTagLoaderTest {
    private val source = FileSource(id = "nas", name = "Music", kind = "smb", host = "nas", share = "music")
    private fun entry(index: Int) = FileEntry(source.id, "album/$index.mp3", "$index.mp3", false, 1_000L, 123L)
    private class Cache(initial: Map<String, AudioTags?> = emptyMap()) : AudioTagStorage {
        val rows = initial.toMutableMap()
        val writes = mutableListOf<List<AudioTagRecord>>()
        override suspend fun read(source: FileSource, entries: List<FileEntry>) = rows.filterKeys { id -> entries.any { it.id == id } }
        override suspend fun write(source: FileSource, records: List<AudioTagRecord>, valid: () -> Boolean) {
            if (records.isEmpty() || !valid()) return
            writes += records
            records.forEach { rows[it.entry.id] = it.tags }
        }
    }

    @Test fun cachedTagsArePublishedBeforeSlowFilesAndOnlyFourReadersCanRun() = runBlocking {
        val tracks = (1..25).map(::entry)
        val cache = Cache(mapOf(tracks[0].id to AudioTags("Cached", track = 1)))
        val started = AtomicInteger()
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        val fourStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val batches = mutableListOf<AudioTagBatch>()
        val job = launch {
            loadAudioTags(cache, source, tracks, valid = { true }, read = {
                val running = active.incrementAndGet(); maximum.updateAndGet { previous -> maxOf(previous, running) }
                if (started.incrementAndGet() == 4) fourStarted.complete(Unit)
                try { release.await(); AudioTags(it.name, track = it.name.substringBefore('.').toInt()) }
                finally { active.decrementAndGet() }
            }, publish = batches::add)
        }
        withTimeout(2_000) { fourStarted.await() }
        assertEquals(1, batches.size)
        assertEquals(mapOf(tracks[0].id to AudioTags("Cached", track = 1)), batches.single().tags)
        assertFalse(batches.single().complete)
        assertEquals(4, active.get())
        release.complete(Unit)
        withTimeout(2_000) { job.join() }
        assertEquals(4, maximum.get())
        assertEquals(24, started.get())
        assertTrue(batches.last().complete)
        assertEquals(25, batches.last().tags.size)
        assertTrue(cache.writes.all { it.size <= 12 })
        assertEquals(24, cache.writes.sumOf { it.size })
        assertEquals(0, active.get())
    }

    @Test fun genuineTaglessFilesAreCachedButNetworkFailuresAreRetried() = runBlocking {
        val tracks = (1..3).map(::entry)
        val cache = Cache()
        val reads = mutableListOf<String>()
        suspend fun pass() = loadAudioTags(cache, source, tracks, valid = { true }, read = {
            reads += it.id
            when (it.id) { tracks[0].id -> AudioTags("Tagged"); tracks[1].id -> null; else -> throw IOException("Offline") }
        }, publish = {})
        pass()
        assertEquals(tracks.map { it.id }.toSet(), reads.toSet())
        assertTrue(cache.rows.containsKey(tracks[1].id))
        assertFalse(cache.rows.containsKey(tracks[2].id))
        reads.clear(); pass()
        assertEquals(listOf(tracks[2].id), reads)
    }

    @Test fun sourceOrFolderChangeCancelsBlockedReadersAndCannotPublishOrWriteLateResults() = runBlocking {
        val tracks = (1..8).map(::entry)
        val cache = Cache()
        val active = AtomicInteger()
        val started = CompletableDeferred<Unit>()
        val neverReleased = CompletableDeferred<Unit>()
        var valid = true
        val batches = mutableListOf<AudioTagBatch>()
        val job: Job = launch {
            loadAudioTags(cache, source, tracks, valid = { valid }, read = {
                if (active.incrementAndGet() == 4) started.complete(Unit)
                try { neverReleased.await(); AudioTags("Stale") } finally { active.decrementAndGet() }
            }, publish = batches::add)
        }
        withTimeout(2_000) { started.await() }
        valid = false
        withTimeout(2_000) { job.join() }
        assertTrue(job.isCancelled)
        assertEquals(0, active.get())
        assertEquals(1, batches.size)
        assertTrue(batches.single().tags.isEmpty())
        assertTrue(cache.writes.isEmpty())
    }

    @Test fun offlineSnapshotReadsOnlyLocalCacheAndDoesNotDeclareAPartialAlbumComplete() = runBlocking {
        val tracks = listOf(entry(1), entry(2))
        val cache = Cache(mapOf(tracks[0].id to AudioTags("Known")))
        val batches = mutableListOf<AudioTagBatch>()
        loadAudioTags(cache, source, tracks, readMissing = false, valid = { true },
            read = { error("Snapshot must not contact the NAS") }, publish = batches::add)
        assertEquals(1, batches.size)
        assertFalse(batches.single().complete)
        assertEquals(setOf(tracks[0].id), batches.single().tags.keys)
        assertTrue(cache.writes.isEmpty())
    }

    @Test fun aFastTrackIsPublishedWithoutWaitingForTheLastSlowTrack() = runBlocking {
        val tracks = listOf(entry(1), entry(2))
        val slow = CompletableDeferred<Unit>()
        val partial = CompletableDeferred<Unit>()
        val cache = Cache()
        val batches = mutableListOf<AudioTagBatch>()
        val job = launch {
            loadAudioTags(cache, source, tracks, valid = { true }, read = {
                if (it.id == tracks[1].id) slow.await()
                AudioTags(it.name)
            }, publish = {
                batches += it
                if (!it.complete && it.tags.size == 1) partial.complete(Unit)
            })
        }
        withTimeout(2_000) { partial.await() }
        assertFalse(job.isCompleted)
        assertEquals(setOf(tracks[0].id), batches.last().tags.keys)
        slow.complete(Unit)
        withTimeout(2_000) { job.join() }
        assertTrue(batches.last().complete)
    }

    @Test fun playbackReusesPositiveAndNegativeCacheHitsAndRejectsAChangedSource() = runBlocking {
        val track = entry(1)
        val cache = Cache(mapOf(track.id to AudioTags("Known")))
        assertEquals("Known", readAudioTag(cache, source, track, valid = { true }, read = { error("Cached") })?.title)
        cache.rows[track.id] = null
        assertNull(readAudioTag(cache, source, track, valid = { true }, read = { error("Cached negative") }))
        cache.rows.clear()
        var valid = true
        try {
            readAudioTag(cache, source, track, valid = { valid }, read = { valid = false; AudioTags("Wrong source") })
            fail("The stale source must be rejected")
        } catch (_: IOException) { /* expected */ }
        assertTrue(cache.rows.isEmpty())
        assertTrue(cache.writes.isEmpty())
    }

    @Test fun partialTagBatchesDoNotReorderEntriesAndFinalAlbumOrderKeepsOtherSlots() = runBlocking {
        val tracks = (1..3).map(::entry)
        val directory = FileEntry(source.id, "album/sub", "Sub", true)
        val entries = listOf(directory) + tracks
        val cache = Cache(mapOf(tracks[0].id to AudioTags("Last", track = 3)))
        var visible = entries
        val snapshots = mutableListOf<List<FileEntry>>()
        loadAudioTags(cache, source, tracks, valid = { true }, read = {
            AudioTags(it.name, track = if (it.id == tracks[1].id) 2 else 1)
        }, publish = { batch ->
            if (batch.complete) visible = MusicFolder.sort(visible, batch.tags)
            snapshots += visible
        })
        assertSame(entries, snapshots.first())
        assertEquals(listOf(directory, tracks[2], tracks[1], tracks[0]), snapshots.last())
        assertEquals(entries.map { it.id }.toSet(), snapshots.last().map { it.id }.toSet())
    }
}
