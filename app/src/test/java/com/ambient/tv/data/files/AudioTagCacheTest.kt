package com.ambient.tv.data.files

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class AudioTagCacheTest {
    private val context: Application get() = RuntimeEnvironment.getApplication()
    private val source = FileSource(id = "nas", name = "Music", kind = "smb", host = "NAS.local", share = "music", username = "alice")
    private var now = 1_000_000L
    private lateinit var cache: AudioTagCache

    @Before fun before() {
        context.deleteDatabase("audio-tags-v1.db")
        cache = createCache()
    }
    @After fun after() {
        cache.close()
        context.deleteDatabase("audio-tags-v1.db")
    }
    private fun createCache(maxEntries: Int = 20_000, maxBytes: Long = 32L * 1024 * 1024) =
        AudioTagCache(context, { now }, maxEntries, maxBytes)
    private fun entry(index: Int, sourceId: String = source.id) =
        FileEntry(sourceId, "album/$index.mp3", "$index.mp3", false, size = 100_000L, modified = 1234L)
    private fun sql(block: (SQLiteDatabase) -> Unit) = context.openOrCreateDatabase("audio-tags-v1.db", 0, null).use(block)

    @Test fun processRestartUsesPositiveAndNegativeHitsWithoutOpeningAnyTrack() = runBlocking {
        val tracks = listOf(entry(1), entry(2))
        cache.write(source, listOf(AudioTagRecord(tracks[0], AudioTags("Song", "Artist", track = 1)),
            AudioTagRecord(tracks[1], null))) { true }
        cache.close()
        cache = createCache()
        var opens = 0
        val batches = mutableListOf<AudioTagBatch>()
        loadAudioTags(cache, source, tracks, valid = { true }, read = { opens++; error("Must not reopen a known version") }, publish = batches::add)
        assertEquals(0, opens)
        assertEquals(1, batches.size)
        assertTrue(batches.single().complete)
        assertEquals("Song", batches.single().tags[tracks[0].id]?.title)
        assertFalse(batches.single().tags.containsKey(tracks[1].id))
        assertTrue(cache.read(source, tracks).containsKey(tracks[1].id))
    }

    @Test fun sizeModificationFormatAndParserRevisionsInvalidateExactlyTheAffectedVersion() = runBlocking {
        val track = entry(1)
        cache.write(source, listOf(AudioTagRecord(track, AudioTags("Original")))) { true }
        assertTrue(cache.read(source, listOf(track.copy(size = track.size + 1))).isEmpty())
        assertTrue(cache.read(source, listOf(track.copy(modified = track.modified + 1))).isEmpty())
        // SAF can retain a document id while its filename is renamed.
        assertTrue(cache.read(source, listOf(track.copy(name = "1.flac"))).isEmpty())
        assertEquals("Original", cache.read(source, listOf(track))[track.id]?.title)
        sql { it.execSQL("UPDATE audio_tags SET parser_version=0") }
        assertTrue(cache.read(source, listOf(track)).isEmpty())
    }

    @Test fun accountRootAndServerChangesAreIsolatedWhileLabelsAndWakeSettingsReuseMetadata() = runBlocking {
        val track = entry(1)
        cache.write(source, listOf(AudioTagRecord(track, AudioTags("Original")))) { true }
        listOf(source.copy(username = "bob"), source.copy(share = "other"), source.copy(rootPath = "new"),
            source.copy(host = "other.local"), source.copy(serverKey = "changed"), source.copy(domain = "other"),
            source.copy(secure = false), source.copy(nfsUid = 123), source.copy(wireGuard = true)).forEach {
            assertTrue(cache.read(it, listOf(track)).isEmpty())
        }
        val cosmetic = source.copy(name = "Albums", host = "nas.LOCAL", wolAuto = true, wolMac = "00:11:22:33:44:55")
        assertEquals("Original", cache.read(cosmetic, listOf(track))[track.id]?.title)
        val otherAccount = source.copy(username = "bob")
        cache.write(otherAccount, listOf(AudioTagRecord(track, AudioTags("Bob's album")))) { true }
        assertTrue(cache.read(source, listOf(track)).isEmpty())
        assertEquals("Bob's album", cache.read(otherAccount, listOf(track))[track.id]?.title)
        sql { db -> db.rawQuery("SELECT scope,file_key,source_id FROM audio_tags", null).use {
            assertTrue(it.moveToFirst())
            assertEquals(64, it.getString(0).length)
            assertEquals(64, it.getString(1).length)
            assertEquals("nas", it.getString(2))
            assertFalse(it.getString(0).contains("bob"))
            assertFalse(it.getString(1).contains("album"))
        } }
    }

    @Test fun providersWithoutReliableVersionsExpireAndClockRollbackCannotMakeThemImmortal() = runBlocking {
        val unknownTime = entry(1).copy(modified = 0)
        val unknownSize = entry(2).copy(size = 0)
        val versioned = entry(3)
        val tracks = listOf(unknownTime, unknownSize, versioned)
        cache.write(source, tracks.map { AudioTagRecord(it, AudioTags(it.name)) }) { true }
        assertEquals(3, cache.read(source, tracks).size)
        now += 6L * 60 * 60 * 1000
        assertEquals(setOf(versioned.id), cache.read(source, tracks).keys)
        now = 1L
        assertTrue(cache.read(source, tracks).isEmpty())
    }

    @Test fun lruEvictsColdRowsSelectivelyAndSizeAccountingSurvivesUpdatesAndRemoval() = runBlocking {
        cache.close(); cache = createCache(maxEntries = 3)
        val tracks = (1..4).map { entry(it) }
        tracks.take(3).forEach { cache.write(source, listOf(AudioTagRecord(it, AudioTags(it.name)))) { true }; now++ }
        now += 6L * 60 * 60 * 1000
        assertEquals(1, cache.read(source, listOf(tracks[0])).size)
        now++
        cache.write(source, listOf(AudioTagRecord(tracks[3], AudioTags("New")))) { true }
        assertEquals(setOf(tracks[0].id, tracks[2].id, tracks[3].id), cache.read(source, tracks).keys)
        cache.write(source, listOf(AudioTagRecord(tracks[0], AudioTags("a".repeat(2_000))))) { true }
        assertEquals(512, cache.read(source, listOf(tracks[0]))[tracks[0].id]?.title?.length)
        sql { db -> db.rawQuery("SELECT entries,bytes,(SELECT COUNT(*) FROM audio_tags),(SELECT SUM(bytes) FROM audio_tags) FROM audio_tag_size", null).use {
            assertTrue(it.moveToFirst()); assertEquals(3L, it.getLong(0)); assertEquals(it.getLong(0), it.getLong(2)); assertEquals(it.getLong(1), it.getLong(3))
        } }
        cache.removeSource(source.id)
        assertTrue(cache.read(source, tracks).isEmpty())
        sql { db -> db.rawQuery("SELECT entries,bytes FROM audio_tag_size", null).use {
            assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)); assertEquals(0, it.getInt(1))
        } }
    }

    @Test fun byteBudgetAlsoBoundsCacheBeforeRowLimitIsReached() = runBlocking {
        cache.close(); cache = createCache(maxBytes = 900)
        val tracks = (1..3).map { entry(it) }
        tracks.forEach { cache.write(source, listOf(AudioTagRecord(it, AudioTags("x".repeat(100))))) { true }; now++ }
        assertEquals(setOf(tracks[1].id, tracks[2].id), cache.read(source, tracks).keys)
    }

    @Test fun generationChangeRollsBackAnEntireBatchIncludingAlreadyUpdatedRows() = runBlocking {
        val tracks = (1..2).map { entry(it) }
        cache.write(source, listOf(AudioTagRecord(tracks[0], AudioTags("Old")))) { true }
        val checks = AtomicInteger()
        cache.write(source, tracks.map { AudioTagRecord(it, AudioTags("Stale")) }) { checks.incrementAndGet() <= 2 }
        assertEquals(mapOf(tracks[0].id to AudioTags("Old")), cache.read(source, tracks))
    }

    @Test fun aFolderQueryDoesNotHydrateTheRestOfALargeCatalogAndUsesThePrimaryIndex() = runBlocking {
        val tracks = (1..3_000).map { entry(it) }
        cache.write(source, tracks.map { AudioTagRecord(it, AudioTags(it.name)) }) { true }
        val folder = tracks.subList(600, 900)
        assertEquals(folder.map { it.id }.toSet(), cache.read(source, folder).keys)
        sql { db -> db.rawQuery("EXPLAIN QUERY PLAN SELECT title FROM audio_tags WHERE scope=? AND file_key IN (?)",
            arrayOf(audioTagScope(source), "one-file")).use { rows ->
            val plan = buildString { while (rows.moveToNext()) append(rows.getString(3)) }
            assertTrue(plan, plan.contains("SEARCH", ignoreCase = true))
            assertTrue(plan, plan.contains("INDEX", ignoreCase = true))
            assertFalse(plan, plan.contains("SCAN TABLE audio_tags", ignoreCase = true))
        } }
    }

    @Test fun folderAndPlaybackReadsShareTheSameFourFileBudget() = runBlocking {
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val jobs = List(10) {
            launch {
                cache.withReadPermit {
                    val running = active.incrementAndGet()
                    maximum.updateAndGet { maxOf(it, running) }
                    if (running == 4) started.complete(Unit)
                    try { release.await() } finally { active.decrementAndGet() }
                }
            }
        }
        withTimeout(2_000) { started.await() }
        assertEquals(4, active.get())
        release.complete(Unit)
        withTimeout(2_000) { jobs.joinAll() }
        assertEquals(4, maximum.get())
        assertEquals(0, active.get())
    }
}
