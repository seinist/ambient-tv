package com.ambient.tv.network

import android.app.Application
import android.database.DatabaseUtils
import coil3.annotation.ExperimentalCoilApi
import coil3.compose.AsyncImageModelEqualityDelegate
import coil3.request.ImageRequest
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
class MediaArtworkRefreshTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val name = "artwork-refresh-test.db"
    private lateinit var store: MediaArtworkRevisionStore

    @Before fun setUp() {
        context.deleteDatabase(name)
        store = MediaArtworkRevisionStore(context, name)
    }
    @After fun tearDown() { store.close(); context.deleteDatabase(name) }

    @Test fun sameUrlRefreshSurvivesRestartWithoutAffectingOtherImagesOrAccounts() {
        val url = "http://media.test:32400/library/metadata/42/thumb?X-Plex-Token=secret"
        val base = requireNotNull(normalizedMediaArtworkDiskKey(url, "account-a"))
        val id = mediaArtworkIdentityKey(base)
        val otherAccount = mediaArtworkIdentityKey(requireNotNull(normalizedMediaArtworkDiskKey(url, "account-b")))
        val otherImage = mediaArtworkIdentityKey(requireNotNull(normalizedMediaArtworkDiskKey(url.replace("42", "43"), "account-a")))
        val oldDiskKey = revisedMediaArtworkDiskKey(base, store.revision(id))
        val changed = store.refresh(listOf(id))
        assertTrue(changed.persisted)
        assertEquals(mapOf(id to ""), changed.previous)
        val revision = store.revision(id)
        assertTrue(revision.isNotBlank())
        assertNotEquals(oldDiskKey, revisedMediaArtworkDiskKey(base, revision))
        assertEquals("", store.revision(otherAccount))
        assertEquals("", store.revision(otherImage))
        store.close()
        store = MediaArtworkRevisionStore(context, name)
        assertEquals(revision, store.revision(id))
        // Even if an old in-flight load republishes oldDiskKey, new requests never address it.
        assertNotEquals(oldDiskKey, revisedMediaArtworkDiskKey(base, store.revision(id)))
        val persistedKey = store.readableDatabase.rawQuery("SELECT image_key FROM artwork_revision", null).use {
            assertTrue(it.moveToFirst()); it.getString(0)
        }
        assertTrue(persistedKey.matches(Regex("[0-9a-f]{64}")))
        assertFalse(persistedKey.contains("secret"))
    }

    @Test fun repeatedReadsAndMissingImagesNeverWriteRows() {
        assertEquals("", store.revision("fresh"))
        assertEquals(0L, DatabaseUtils.queryNumEntries(store.readableDatabase, "artwork_revision"))
        store.refresh(listOf("edited"))
        val before = changes()
        repeat(1_000) { store.revision("edited"); store.revision("unrelated-$it") }
        assertEquals(before, changes())
        assertEquals(1L, DatabaseUtils.queryNumEntries(store.readableDatabase, "artwork_revision"))
    }

    @Test fun repeatedRefreshChangesOnlyRequestedRowsAndUsesAnIndex() {
        store.refresh(listOf("poster", "backdrop", "poster"))
        val first = store.revision("poster")
        val backdrop = store.revision("backdrop")
        assertEquals(first, backdrop)
        assertEquals(mapOf("poster" to first), store.refresh(listOf("poster")).previous)
        assertNotEquals(first, store.revision("poster"))
        assertEquals(backdrop, store.revision("backdrop"))
        assertEquals(2L, DatabaseUtils.queryNumEntries(store.readableDatabase, "artwork_revision"))
        val plan = store.readableDatabase.rawQuery(
            "EXPLAIN QUERY PLAN SELECT revision FROM artwork_revision WHERE image_key=?", arrayOf("poster"),
        ).use { rows -> buildString { while (rows.moveToNext()) append(rows.getString(3)) } }
        assertTrue(plan, plan.contains("SEARCH", ignoreCase = true))
        assertFalse(plan, plan.contains("SCAN artwork_revision", ignoreCase = true))
    }

    @OptIn(ExperimentalCoilApi::class)
    @Test fun sameUrlEditActuallyChangesCoilComposeRequestEquality() {
        fun request(sequence: Long) = ImageRequest.Builder(context).data("http://media.test/same-image")
            .artworkRefreshRevision(sequence).build()
        assertTrue(AsyncImageModelEqualityDelegate.Default.equals(request(0), request(0)))
        assertFalse(AsyncImageModelEqualityDelegate.Default.equals(request(0), request(1)))
        assertFalse(AsyncImageModelEqualityDelegate.Default.equals(request(1), request(2)))
        val resolved = request(2).newBuilder().memoryCacheKeyExtra(MEDIA_ARTWORK_REVISION_EXTRA, "persisted").build()
        assertEquals("persisted", resolved.memoryCacheKeyExtras[MEDIA_ARTWORK_REVISION_EXTRA])
    }

    @Test fun failedWriteGetsATargetedMemoryRevisionAndCannotReusePersistentBinaryData() {
        store.refresh(listOf("poster"))
        val old = store.revision("poster")
        store.writableDatabase.execSQL("PRAGMA query_only=ON")
        val failed = store.refresh(listOf("poster"))
        assertFalse(failed.persisted)
        assertEquals(old, failed.previous["poster"])
        assertNotEquals(old, store.revision("poster"))
        assertEquals(store.revision("poster"), store.cachedRevision("poster"))
        assertTrue(store.isMemoryOnly("poster"))
        assertFalse(store.isMemoryOnly("unrelated"))
        val transient = store.revision("poster")
        store.writableDatabase.execSQL("PRAGMA query_only=OFF")
        assertTrue(store.refresh(listOf("poster")).persisted)
        assertFalse(store.isMemoryOnly("poster"))
        assertNotEquals(transient, store.revision("poster"))
        val recovered = store.revision("poster")
        store.close(); store = MediaArtworkRevisionStore(context, name)
        assertEquals(recovered, store.revision("poster"))
    }

    @Test fun placeholdersAndContinuityRejectOldAccountRevisionAndUnknownIdentity() {
        val current = MediaArtworkCacheIdentity("base", "account-a-image", "revision-2")
        val valid = mapOf(MEDIA_ARTWORK_IDENTITY_EXTRA to current.imageKey, MEDIA_ARTWORK_REVISION_EXTRA to current.revision)
        assertTrue(mediaArtworkMemoryKeyMatches(valid, current))
        assertFalse(mediaArtworkMemoryKeyMatches(valid, current.copy(imageKey = "account-b-image")))
        assertFalse(mediaArtworkMemoryKeyMatches(valid, current.copy(revision = "revision-3")))
        assertFalse(mediaArtworkMemoryKeyMatches(valid, null))
        assertFalse(mediaArtworkMemoryKeyMatches(emptyMap(), current))
    }

    private fun changes() = DatabaseUtils.longForQuery(store.readableDatabase, "SELECT total_changes()", null)
}
