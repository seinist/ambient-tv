package com.ambient.tv.ui.common

import android.app.Application
import android.database.DatabaseUtils
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
class ArtworkFocusCacheTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val name = "artwork-focus-test.db"
    private var clock = 1_000L
    private lateinit var cache: ArtworkFocusCache

    @Before fun setUp() {
        context.deleteDatabase(name)
        cache = ArtworkFocusCache(context, name, maxEntries = 3) { clock }
    }

    @After fun tearDown() {
        cache.close()
        context.deleteDatabase(name)
    }

    @Test fun overflowPreservesRecentEntriesAndSuccessfulNegativeResults() {
        cache.put("old", ArtworkFocusPoint(.5f, .4f))
        clock++
        cache.put("negative", ArtworkFocusPoint(null, null))
        clock++
        cache.put("recent", ArtworkFocusPoint(.7f, .2f))
        clock++
        cache.put("new", ArtworkFocusPoint(.3f, .2f))

        assertNull(cache.get("old"))
        assertEquals(ArtworkFocusPoint(null, null), cache.get("negative"))
        assertEquals(ArtworkFocusPoint(.7f, .2f), cache.get("recent"))
        assertEquals(ArtworkFocusPoint(.3f, .2f), cache.get("new"))
        assertEquals(3L, DatabaseUtils.queryNumEntries(cache.readableDatabase, "artwork_focus"))
        cache.close()
        cache = ArtworkFocusCache(context, name, maxEntries = 3) { clock }
        assertEquals(ArtworkFocusPoint(null, null), cache.get("negative"))
    }

    @Test fun recentlyReusedImageSurvivesSelectiveEvictionWithoutWritingEveryRead() {
        cache.put("reused", ArtworkFocusPoint(.5f, .4f))
        clock++
        cache.put("cold", ArtworkFocusPoint(.5f, .4f))
        clock++
        cache.put("other", ArtworkFocusPoint(.5f, .4f))
        val before = totalChanges()
        repeat(100) { assertNotNull(cache.get("reused")) }
        assertEquals("A visible card must not write the database on every use", before, totalChanges())
        clock += 3_600_000L
        assertNotNull(cache.get("reused"))
        assertEquals(before + 1, totalChanges())
        clock++
        cache.put("new", ArtworkFocusPoint(.5f, .4f))
        assertNotNull(cache.get("reused"))
        assertNull(cache.get("cold"))
    }

    @Test fun mutableUrlsExpireAndInvalidCoordinatesAreNeverAccepted() {
        cache.put("image", ArtworkFocusPoint(.5f, .4f))
        clock += 31 * 24 * 60 * 60 * 1000L
        assertNull(cache.get("image"))
        assertFalse(ArtworkFocusPoint(Float.NaN, .4f).valid)
        assertFalse(ArtworkFocusPoint(1.1f, .4f).valid)
        assertFalse(ArtworkFocusPoint(null, .4f).valid)
    }

    @Test fun queryUsesTheImageKeyIndex() {
        val plan = cache.readableDatabase.rawQuery(
            "EXPLAIN QUERY PLAN SELECT x,y,analyzed_at,accessed_at FROM artwork_focus WHERE image_key=?",
            arrayOf("image"),
        ).use { rows -> buildString { while (rows.moveToNext()) append(rows.getString(3)) } }
        assertTrue(plan, plan.contains("SEARCH", ignoreCase = true))
        assertFalse(plan, plan.contains("SCAN artwork_focus", ignoreCase = true))
    }

    private fun totalChanges(): Long = DatabaseUtils.longForQuery(cache.readableDatabase, "SELECT total_changes()", null)
}
