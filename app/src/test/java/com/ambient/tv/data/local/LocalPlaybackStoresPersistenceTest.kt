package com.ambient.tv.data.local

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import com.ambient.tv.data.dlna.DlnaKeys
import com.ambient.tv.data.dlna.DlnaProgress
import com.ambient.tv.data.dlna.DlnaServer
import com.ambient.tv.data.dlna.DlnaStore
import com.ambient.tv.data.dlna.DlnaNode
import com.ambient.tv.data.dlna.toMediaItem
import com.ambient.tv.data.files.FileEntry
import com.ambient.tv.data.files.FileHistory
import com.ambient.tv.data.files.FileSource
import com.ambient.tv.data.files.FileStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = Application::class)
class LocalPlaybackStoresPersistenceTest {
    private val context: Application get() = RuntimeEnvironment.getApplication()
    private val regular get() = context.getSharedPreferences("playback_store_fixture", Context.MODE_PRIVATE)
    private lateinit var preferences: AppPreferences
    private lateinit var tokens: TokenStore
    private lateinit var database: LocalPlaybackDatabase
    private val stores = mutableListOf<FileStore>()
    private val source = FileSource(id = "nas", name = "NAS", kind = "smb", host = "nas.local", share = "media")
    private val server = DlnaServer(id = "dlna", udn = "uuid:fixture", name = "Media",
        descriptionUrl = "http://192.168.1.2/device.xml", contentDirectoryUrl = "http://192.168.1.2/content")

    @Before fun setUp() {
        context.deleteDatabase("local_playback.db")
        assertTrue(regular.edit().clear().commit())
        preferences = AppPreferences(regular)
        tokens = TokenStore(memoryPreferences(), preferences)
        database = LocalPlaybackDatabase(context)
    }
    @After fun tearDown() {
        detachStores()
        database.closeForTest()
        regular.edit().clear().commit()
        context.deleteDatabase("local_playback.db")
    }
    private fun files() = FileStore(preferences, tokens, regular, database).also(stores::add)
    private fun detachStores() {
        // Only simulate process teardown; production keeps this singleton/listener for its process lifetime.
        val listener = FileStore::class.java.getDeclaredField("sourceListener").apply { isAccessible = true }
        stores.forEach { regular.unregisterOnSharedPreferenceChangeListener(listener.get(it) as SharedPreferences.OnSharedPreferenceChangeListener) }
        stores.clear()
    }
    private suspend fun reopenFiles(): FileStore {
        assertTrue(database.flush())
        detachStores()
        database.closeForTest()
        database = LocalPlaybackDatabase(context)
        return files().also { it.awaitReady() }
    }

    @Test fun fileMigrationPreservesOrderExactTimeCompletionAndIgnoresOrphans() = runBlocking {
        preferences.fileSources = listOf(source)
        val first = FileEntry(source.id, "first.mkv", "first.mkv", false)
        val second = FileEntry(source.id, "second.mkv", "second.mkv", false)
        val orphan = first.copy(sourceId = "deleted")
        regular.edit().putString("file_favorites", Json.encodeToString(listOf(first, orphan, second)))
            .putString("file_history", Json.encodeToString(listOf(FileHistory(second, 12_345, 120_000, 7, true), FileHistory(orphan), FileHistory(first, 500, 5_000, 6))))
            .commit()
        val store = files()
        store.awaitReady()
        assertEquals(listOf(first, second), store.favorites())
        assertEquals(listOf(second, first), store.history().map { it.entry })
        assertEquals(12_345L, store.historyFor(second.id)?.positionMs)
        assertTrue(requireNotNull(store.historyFor(second.id)).completed)
        assertFalse(regular.contains("file_favorites"))
        assertFalse(regular.contains("file_history"))
        val reopened = reopenFiles()
        assertEquals(store.favorites(), reopened.favorites())
        assertEquals(store.history(), reopened.history())
    }

    @Test fun oneFileReportWritesOneOfFiveHundredRowsAndNoPreferenceBlob() = runBlocking {
        preferences.fileSources = listOf(source)
        val initial = (1..500).map { FileHistory(FileEntry(source.id, "$it.mkv", "$it.mkv", false), it.toLong(), 100_000, it.toLong()) }
        regular.edit().putString("file_history", Json.encodeToString(initial)).commit()
        val store = files(); store.awaitReady()
        val writes = database.rowWriteCount
        val retention = database.retentionPassCount
        val preferenceSnapshot = regular.all
        store.record(initial[249].entry, 55_555, 0)
        assertEquals(55_555L, store.historyFor(initial[249].entry.id)?.positionMs)
        assertTrue(store.flush())
        assertEquals(1L, database.rowWriteCount - writes)
        assertEquals(retention, database.retentionPassCount)
        assertEquals(preferenceSnapshot, regular.all)
        assertEquals(100_000L, reopenFiles().historyFor(initial[249].entry.id)?.durationMs)
    }

    @Test fun sourceRemoveAndReaddFencesPendingRowsAndLateReportsEvenAtTheSameLocation() = runBlocking {
        preferences.fileSources = listOf(source)
        val store = files(); store.awaitReady()
        val oldScope = requireNotNull(store.playbackScope(source))
        val entry = FileEntry(source.id, "movie.mkv", "movie.mkv", false)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        database.load("hold", 1, { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); emptyList() }, {}, {}, { throw it })
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        try {
            store.record(entry, 12_345, 100_000, expectedScope = oldScope)
            preferences.fileSources = emptyList()
            preferences.fileSources = listOf(source)
            assertNotEquals(oldScope, store.playbackScope(source))
            store.record(entry, 99_999, 100_000, expectedSource = source, expectedScope = oldScope)
            assertTrue(store.history().isEmpty())
            release.countDown()
            assertTrue(store.flush())
        } finally { release.countDown() }
        assertTrue(reopenFiles().history().isEmpty())
        assertFalse(regular.contains("file_history"))
    }

    @Test fun sameLocationSettingsImportKeepsRowsButDifferentRootImportAndBackupCannotCarryEpochs() = runBlocking {
        preferences.fileSources = listOf(source)
        val store = files(); store.awaitReady()
        val entry = FileEntry(source.id, "local-only-987654.mkv", "local-only-987654.mkv", false)
        store.favorite(entry); store.record(entry, 9_876_543, 10_000_000)
        val scope = store.playbackScope(source)
        val exported = SettingsBackupManager(preferences, tokens).exportJson()
        assertFalse(exported.contains("file_playback_scopes"))
        assertFalse(exported.contains(requireNotNull(scope)))
        assertFalse(exported.contains(entry.name))
        SettingsBackupManager(preferences, tokens).importJson(exported)
        assertEquals(scope, store.playbackScope(source))
        assertEquals(9_876_543L, store.historyFor(entry.id)?.positionMs)
        preferences.fileSources = listOf(source.copy(rootPath = "different"))
        assertTrue(store.favorites().isEmpty())
        assertTrue(store.history().isEmpty())
        assertTrue(store.flush())
        assertTrue(reopenFiles().history().isEmpty())
    }

    @Test fun dlnaMigrationAndRediscoveryPreserveProgressWithoutInvalidatingServers() = runBlocking {
        val seeder = DlnaStore(regular)
        seeder.save(server)
        val key = DlnaKeys.key(server.id, "movie")
        regular.edit().putString("dlna_progress", Json.encodeToString(listOf(DlnaProgress(key, 45_678, true, 123)))).commit()
        val store = DlnaStore(regular, database); store.awaitReady()
        assertEquals(45_678L, store.progressFor(key)?.positionMs)
        assertTrue(requireNotNull(store.progressFor(key)).completed)
        assertFalse(regular.contains("dlna_progress"))
        val scope = store.playbackScope(server)
        store.refreshSaved(listOf(server.copy(descriptionUrl = "http://192.168.1.3/device.xml", contentDirectoryUrl = "http://192.168.1.3/content")))
        assertEquals(scope, store.playbackScope(store.servers().single()))
        val revision = store.serverRevision.value
        val writes = database.rowWriteCount
        store.recordProgress(key, 55_555, expectedScope = scope)
        assertTrue(store.flush())
        assertEquals(1L, database.rowWriteCount - writes)
        assertEquals(revision, store.serverRevision.value)
        assertEquals(55_555L, store.progressFor(key)?.positionMs)
    }

    @Test fun dlnaLateProducerCannotRetargetRemovedReaddedIdOrAnotherUdn() = runBlocking {
        val store = DlnaStore(regular, database)
        store.save(server); store.awaitReady()
        val key = DlnaKeys.key(server.id, "movie")
        val oldScope = requireNotNull(store.playbackScope(server))
        store.recordProgress(key, 1_000, expectedScope = oldScope)
        store.remove(server.id)
        store.save(server)
        store.recordProgress(key, 2_000, expectedScope = oldScope)
        assertNull(store.progressFor(key))
        val replacementScope = requireNotNull(store.playbackScope(server))
        store.recordProgress(key, 3_000, expectedScope = replacementScope)
        store.replace(listOf(server.copy(udn = "uuid:another-device")))
        store.recordProgress(key, 4_000, expectedScope = replacementScope)
        assertNull(store.progressFor(key))
        assertTrue(store.flush())
        val reopened = DlnaStore(regular, database); reopened.awaitReady()
        assertNull(reopened.progressFor(key))
    }

    @Test fun playbackIncarnationIsTransientInMediaSnapshotsAndPortableSettings() {
        val store = DlnaStore(regular, database)
        store.save(server)
        val scope = requireNotNull(store.playbackScope(server))
        val item = DlnaNode(server.id, "movie", null, "Movie", null, false, null, null, null, emptyList())
            .toMediaItem().copy(localProgressScope = scope)
        val encoded = Json.encodeToString(item)
        assertFalse(encoded.contains("localProgressScope"))
        assertFalse(encoded.contains(scope))
        val exported = SettingsBackupManager(preferences, tokens, store).exportJson()
        assertFalse(exported.contains("dlna_progress_scopes"))
        assertFalse(exported.contains(scope))
    }

    @Test fun sourceEditsPreserveMalformedFileLegacyAndItsOriginalScopeUntilRecoveryCommits() = runBlocking {
        preferences.fileSources = listOf(source)
        val originalScope = preferences.filePlaybackScopes.getValue(source.id)
        val raw = "[{\"entry\":broken-file-history"
        regular.edit().putString("file_history", raw).commit()
        preferences.fileSources = listOf(source.copy(host = "replacement-nas"))
        assertEquals(raw, regular.getString("file_history", null))
        assertNotEquals(originalScope, preferences.filePlaybackScopes[source.id])
        val store = files(); store.awaitReady()
        assertTrue(store.history().isEmpty())
        assertFalse(regular.contains("file_history"))
        assertFalse(regular.contains("file_history_legacy_scopes"))
        context.openOrCreateDatabase("local_playback.db", Context.MODE_PRIVATE, null).use { db ->
            db.rawQuery("SELECT raw_payload,source_scopes FROM playback_recovery WHERE namespace='file_history'", null).use {
                assertTrue(it.moveToFirst()); assertEquals(raw, it.getString(0))
                assertEquals(originalScope, Json.decodeFromString<Map<String, String>>(it.getString(1))[source.id])
            }
        }
        val entry = FileEntry(source.id, "new.mkv", "new.mkv", false)
        store.record(entry, 3_333, 100_000)
        assertTrue(store.flush())
        assertEquals(3_333L, reopenFiles().historyFor(entry.id)?.positionMs)
    }

    @Test fun sourceEditsPreserveMalformedDlnaLegacyAndFreshPlaybackRemainsAvailable() = runBlocking {
        val seeder = DlnaStore(regular); seeder.save(server)
        val originalScope = requireNotNull(seeder.playbackScope(server))
        val raw = "[{broken-dlna-history"
        regular.edit().putString("dlna_progress", raw).commit()
        val replacement = server.copy(udn = "uuid:replacement")
        seeder.replace(listOf(replacement))
        assertEquals(raw, regular.getString("dlna_progress", null))
        val store = DlnaStore(regular, database); store.awaitReady()
        val key = DlnaKeys.key(server.id, "movie")
        assertNull(store.progressFor(key))
        context.openOrCreateDatabase("local_playback.db", Context.MODE_PRIVATE, null).use { db ->
            db.rawQuery("SELECT raw_payload,source_scopes FROM playback_recovery WHERE namespace='dlna_progress'", null).use {
                assertTrue(it.moveToFirst()); assertEquals(raw, it.getString(0))
                assertEquals(originalScope, Json.decodeFromString<Map<String, String>>(it.getString(1))[server.id])
            }
        }
        store.recordProgress(key, 4_444, expectedScope = store.playbackScope(replacement))
        assertTrue(store.flush())
        assertEquals(4_444L, store.progressFor(key)?.positionMs)
        assertFalse(regular.contains("dlna_progress"))
    }
}
