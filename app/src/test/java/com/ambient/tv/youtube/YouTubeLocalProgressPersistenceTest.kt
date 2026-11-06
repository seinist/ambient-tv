package com.ambient.tv.youtube

import android.app.Application
import android.content.Context
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
    private val preferences get() = context.getSharedPreferences("youtube_local_progress", Context.MODE_PRIVATE)

    @Before fun setUp() {
        clearProcessMemory()
        assertTrue(preferences.edit().clear().commit())
    }

    @After fun tearDown() {
        clearProcessMemory()
        preferences.edit().clear().commit()
    }

    @Test fun exactPositionAndDurationSurviveReloadIncludingChangesWithinOnePercent() {
        val id = "persisted-exact-progress"
        YouTubeLocalProgress.init(context)
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

    @Test fun legacyPercentOnlyEntriesStillLoadAndGainExactTimeOnTheirNextReport() {
        val id = "legacy-progress"
        val withoutRemote = "legacy-progress-without-remote"
        val stored = JSONObject()
            .put(id, JSONObject().put("p", 41).put("r", 10).put("t", 1_234L))
            .put(withoutRemote, JSONObject().put("p", 25).put("t", 1_235L))
        assertTrue(preferences.edit().putString("entries", stored.toString()).commit())

        YouTubeLocalProgress.init(context)

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

    @Test fun completionPersistsAsFullyWatchedAndRestartsFromZeroAfterReload() {
        val id = "persisted-completion"
        YouTubeLocalProgress.init(context)
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

    @Test fun completionWithoutADecoderDurationSurvivesReload() {
        val id = "persisted-completion-no-duration"
        YouTubeLocalProgress.init(context)
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

    private fun savedEntry(id: String): JSONObject =
        JSONObject(requireNotNull(preferences.getString("entries", null))).getJSONObject(id)

    private fun reloadFromPreferences() {
        // Flush pending apply() writes before discarding only the in-memory singleton state.
        assertTrue(preferences.edit().commit())
        clearProcessMemory()
        YouTubeLocalProgress.init(context)
    }

    private fun clearProcessMemory() = synchronized(YouTubeLocalProgress) {
        // Simulate process recreation without adding reset methods to the production API.
        // Reflection is restricted to clearing the two stores and their initialization guard;
        // all saved-state assertions and reloads use SharedPreferences and the public methods.
        val type = YouTubeLocalProgress::class.java
        for (name in listOf("entries", "lastRemote")) {
            val field = type.getDeclaredField(name).apply { isAccessible = true }
            (field.get(null) as MutableMap<*, *>).clear()
        }
        type.getDeclaredField("context").apply { isAccessible = true }.set(null, null)
    }
}
