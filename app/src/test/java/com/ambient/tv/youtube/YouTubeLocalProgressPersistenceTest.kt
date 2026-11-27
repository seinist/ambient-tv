package com.ambient.tv.youtube

import android.app.Application
import android.content.Context
import com.ambient.tv.data.local.LocalPlaybackDatabase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = Application::class)
class YouTubeLocalProgressPersistenceTest {
    private val context: Application get() = RuntimeEnvironment.getApplication()
    private lateinit var database: LocalPlaybackDatabase
    private val preferences get() = context.getSharedPreferences("youtube_local_progress", Context.MODE_PRIVATE)

    @Before fun setUp() {
        clearProcessMemory()
        context.deleteDatabase("local_playback.db")
        database = LocalPlaybackDatabase(context)
        assertTrue(preferences.edit().clear().commit())
    }

    @After fun tearDown() {
        database.closeForTest()
        clearProcessMemory()
        context.deleteDatabase("local_playback.db")
        preferences.edit().clear().commit()
    }

    @Test fun exactPositionAndDurationSurviveReloadIncludingChangesWithinOnePercent() = runBlocking {
        val id = "persisted-exact-progress"
        initProgress()
        YouTubeLocalProgress.observeRemote(id, 10)
        YouTubeLocalProgress.record(id, 575_000L, 3_600_000L)
        YouTubeLocalProgress.record(id, 575_999L, 3_600_000L)

        val saved = savedEntry(id)
        assertEquals(15, saved.getInt("p"))
        assertEquals(10, saved.getInt("r"))
        assertEquals(575_999L, saved.getLong("position"))
        assertEquals(3_600_000L, saved.getLong("duration"))
        assertTrue(saved.has("t"))

        reloadFromPreferences()

        assertEquals(15, YouTubeLocalProgress.percent(id, 10))
        assertEquals(3_600_000L, YouTubeLocalProgress.durationMs(id))
        // Both early playback with no duration and later metadata must retain the exact time.
        assertEquals(575_999L, YouTubeLocalProgress.resumePositionMs(id, 0L))
        assertEquals(575_999L, YouTubeLocalProgress.resumePositionMs(id, 3_600_000L, 10))
    }

    @Test fun legacyPercentOnlyEntriesStillLoadAndGainExactTimeOnTheirNextReport() = runBlocking {
        val id = "legacy-progress"
        val withoutRemote = "legacy-progress-without-remote"
        val stored = JSONObject()
            .put(id, JSONObject().put("p", 41).put("r", 10).put("t", 1_234L))
            .put(withoutRemote, JSONObject().put("p", 25).put("t", 1_235L))
        assertTrue(preferences.edit().putString("entries", stored.toString()).commit())

        initProgress()

        assertEquals(41, YouTubeLocalProgress.percent(id, 10))
        assertNull(YouTubeLocalProgress.durationMs(id))
        assertEquals(41_000L, YouTubeLocalProgress.resumePositionMs(id, 100_000L))
        assertEquals(50_000L, YouTubeLocalProgress.resumePositionMs(withoutRemote, 200_000L))
        YouTubeLocalProgress.observeRemote(id, 10) // A stale server response must not lose the old entry.
        assertEquals(41_000L, YouTubeLocalProgress.resumePositionMs(id, 100_000L))

        YouTubeLocalProgress.record(id, 41_555L, 100_000L)
        assertEquals(41_555L, savedEntry(id).getLong("position"))
        assertEquals(100_000L, savedEntry(id).getLong("duration"))
        reloadFromPreferences()
        assertEquals(41_555L, YouTubeLocalProgress.resumePositionMs(id, 0L))
        assertEquals(50_000L, YouTubeLocalProgress.resumePositionMs(withoutRemote, 200_000L))
    }

    @Test fun completionPersistsAsFullyWatchedAndRestartsFromZeroAfterReload() = runBlocking {
        val id = "persisted-completion"
        initProgress()
        YouTubeLocalProgress.observeRemote(id, 30)
        YouTubeLocalProgress.record(id, 30_000L, 100_000L)
        YouTubeLocalProgress.record(id, 80_000L, 100_000L, completed = true)

        val saved = savedEntry(id)
        assertEquals(100, saved.getInt("p"))
        assertEquals(100_000L, saved.getLong("position"))
        assertEquals(100_000L, saved.getLong("duration"))

        reloadFromPreferences()
        YouTubeLocalProgress.observeCached(id, 30)
        YouTubeLocalProgress.observeRemote(id, 30)
        assertEquals(100, YouTubeLocalProgress.percent(id, 30))
        assertEquals(0L, YouTubeLocalProgress.resumePositionMs(id, 100_000L, 30))
    }

    @Test fun completionWithoutADecoderDurationSurvivesReload() = runBlocking {
        val id = "persisted-completion-no-duration"
        initProgress()
        YouTubeLocalProgress.record(id, 80_000L, 0L, completed = true)

        val saved = savedEntry(id)
        assertEquals(100, saved.getInt("p"))
        assertEquals(80_000L, saved.getLong("position"))
        assertFalse(saved.has("duration"))

        reloadFromPreferences()
        assertEquals(100, YouTubeLocalProgress.percent(id, 25))
        assertNull(YouTubeLocalProgress.durationMs(id))
        assertEquals(0L, YouTubeLocalProgress.resumePositionMs(id, 0L, 25))
    }

    @Test fun oneProgressTickRewritesOneOfFourHundredEntries() = runBlocking {
        val legacy = JSONObject()
        repeat(400) { index -> legacy.put("video-$index", JSONObject().put("p", 10).put("r", 0).put("t", index)
            .put("position", 10_000).put("duration", 100_000)) }
        assertTrue(preferences.edit().putString("entries", legacy.toString()).commit())
        initProgress()
        val before = database.rowWriteCount
        val beforeRetention = database.retentionPassCount
        YouTubeLocalProgress.record("video-250", 25_123, 100_000)
        assertEquals(25_123L, savedEntry("video-250").getLong("position"))
        assertEquals(1L, database.rowWriteCount - before)
        assertEquals(beforeRetention, database.retentionPassCount)
        assertEquals(10_000L, savedEntry("video-249").getLong("position"))
    }

    @Test fun cachedCardBeforePrimingCannotOverwritePersistedExactTimeAndHydrationPublishes() = runBlocking {
        val id = "cold-cached-card"
        assertTrue(preferences.edit().putString("entries", JSONObject().put(id,
            JSONObject().put("p", 41).put("r", 10).put("t", 123).put("position", 41_555).put("duration", 100_000)).toString()).commit())
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        database.load("hold", 1, { entered.countDown(); check(release.await(5, java.util.concurrent.TimeUnit.SECONDS)); emptyList() }, {}, {}, { throw it })
        assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
        try {
            YouTubeLocalProgress.init(context, database)
            YouTubeLocalProgress.observeCached(id, 75)
            val before = YouTubeLocalProgress.revision
            release.countDown()
            YouTubeLocalProgress.awaitReady()
            assertEquals(41_555L, YouTubeLocalProgress.resumePositionMs(id, 100_000))
            assertTrue(YouTubeLocalProgress.revision > before)
        } finally { release.countDown() }
    }

    @Test fun malformedLegacyIsRetainedForRecoveryWhileNewPlaybackRemainsAvailable() = runBlocking {
        val raw = "{\"old-video\":broken"
        assertTrue(preferences.edit().putString("entries", raw).commit())
        initProgress()
        assertFalse(preferences.contains("entries"))
        context.openOrCreateDatabase("local_playback.db", Context.MODE_PRIVATE, null).use { db ->
            db.rawQuery("SELECT raw_payload,source_scopes FROM playback_recovery WHERE namespace='youtube_progress'", null).use {
                assertTrue(it.moveToFirst()); assertEquals(raw, it.getString(0)); assertEquals("device", it.getString(1))
            }
        }
        YouTubeLocalProgress.record("new-video", 12_345, 100_000)
        assertEquals(12_345L, savedEntry("new-video").getLong("position"))
        reloadFromPreferences()
        assertEquals(12_345L, YouTubeLocalProgress.resumePositionMs("new-video", 100_000))
    }

    @Test fun liveBeforeHydrationDiscardsThePersistedPositionAndTheQueuedServerProgress() = runBlocking {
        val live = "live"
        val vod = "vod"
        val legacy = JSONObject().put(live, JSONObject().put("p", 41).put("t", 123)
            .put("position", 41_555).put("duration", 100_000))
        assertTrue(preferences.edit().putString("entries", legacy.toString()).commit())
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        database.load("hold", 1, { entered.countDown(); check(release.await(5, java.util.concurrent.TimeUnit.SECONDS)); emptyList() },
            {}, {}, { throw it })
        assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
        try {
            YouTubeLocalProgress.init(context, database)
            YouTubeLocalProgress.observeRemote(live, 75)
            YouTubeLocalProgress.observeRemote(vod, 25)
            YouTubeLocalProgress.discardLive(live)
            release.countDown()
            YouTubeLocalProgress.awaitReady()
            assertNull(YouTubeLocalProgress.resumePositionMs(live, 100_000))
            assertEquals(25_000L, YouTubeLocalProgress.resumePositionMs(vod, 100_000))
            reloadFromPreferences()
            assertNull(YouTubeLocalProgress.resumePositionMs(live, 100_000))
        } finally { release.countDown() }
    }

    private suspend fun initProgress() {
        YouTubeLocalProgress.init(context, database)
        YouTubeLocalProgress.awaitReady()
    }

    private suspend fun savedEntry(id: String): JSONObject {
        assertTrue(YouTubeLocalProgress.flush())
        assertFalse("The aggregate preference is removed only after committed migration", preferences.contains("entries"))
        return context.openOrCreateDatabase("local_playback.db", Context.MODE_PRIVATE, null).use { db ->
            db.rawQuery("SELECT payload FROM playback_rows WHERE namespace='youtube_progress' AND item_key=?", arrayOf(id)).use {
                assertTrue(it.moveToFirst())
                JSONObject(it.getString(0))
            }
        }
    }

    private suspend fun reloadFromPreferences() {
        assertTrue(YouTubeLocalProgress.flush())
        database.closeForTest()
        clearProcessMemory()
        database = LocalPlaybackDatabase(context)
        initProgress()
    }

    private fun clearProcessMemory() = synchronized(YouTubeLocalProgress) {
        // Simulates process recreation. Production reads/writes remain the public API and
        // real Android SQLite; reflection only resets this process-owned singleton.
        val type = YouTubeLocalProgress::class.java
        for (name in listOf("entries", "lastRemote", "remoteBeforeReady")) {
            val field = type.getDeclaredField(name).apply { isAccessible = true }
            (field.get(null) as MutableMap<*, *>).clear()
        }
        (type.getDeclaredField("beforeReady").apply { isAccessible = true }.get(null) as MutableSet<*>).clear()
        for (name in listOf("context", "database")) type.getDeclaredField(name).apply { isAccessible = true }.set(null, null)
        for (name in listOf("loaded", "loading")) type.getDeclaredField(name).apply { isAccessible = true }.setBoolean(null, false)
        type.getDeclaredField("ready").apply { isAccessible = true }.set(null, CompletableDeferred<Unit>())
    }
}
