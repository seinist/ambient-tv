package com.ambient.tv.tvheadend.data

import com.ambient.tv.livetv.LiveTvChannel
import com.ambient.tv.livetv.LiveTvProgramme
import com.ambient.tv.livetv.LiveTvRecording
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TvheadendDiskCacheTest {
    private class FakeStore : TvheadendCacheStore {
        val data = mutableMapOf<String, MutableMap<String, String>>()
        val removed = mutableListOf<String>()
        override fun put(scope: String, key: String, valid: () -> Boolean, encode: () -> String) {
            if (valid()) data.getOrPut(scope) { mutableMapOf() }[key] = encode()
        }
        override suspend fun read(scope: String): Map<String, String> = data[scope].orEmpty()
        override fun remove(scope: String) { removed += scope; data.remove(scope) }
    }

    private val channels = ListSerializer(LiveTvChannel.serializer())
    private val recordings = ListSerializer(LiveTvRecording.serializer())

    private fun recording(uuid: String) = LiveTvRecording(uuid, "ch", "Das Erste", "Tatort", startEpochSeconds = 1, stopEpochSeconds = 2,
        status = "Completed", scheduleStatus = "completed", url = "dvrfile/$uuid?ticket=secret", imageUrl = null, fanartUrl = null, fileSizeBytes = null)

    private fun event(id: String, start: Long, stop: Long) = LiveTvProgramme(id, "ch", "Das Erste", "T$id", startEpochSeconds = start, stopEpochSeconds = stop)

    @Test fun restartReadsStoredDataOfSameScope() = runBlocking {
        val store = FakeStore()
        TvheadendDiskCache(store).write("tvh:a", "channels", channels, listOf(LiveTvChannel("u1", "Das Erste", 1, null)))
        val restarted = TvheadendDiskCache(store)
        assertEquals("Das Erste", restarted.read("tvh:a", "channels", channels)?.single()?.name)
    }

    @Test fun scopeChangeRemovesOldServerData() = runBlocking {
        val store = FakeStore()
        val cache = TvheadendDiskCache(store)
        cache.write("tvh:a", "channels", channels, listOf(LiveTvChannel("u1", "A", 1, null)))
        assertNull(cache.read("tvh:b", "channels", channels))
        assertTrue("tvh:a" in store.removed)
        assertFalse("tvh:a" in store.data)
    }

    @Test fun disablingDeletesStoredData() = runBlocking {
        val store = FakeStore()
        val cache = TvheadendDiskCache(store)
        cache.write("tvh:a", "dvr:finished", recordings, listOf(recording("r1")))
        cache.clear("tvh:a")
        assertNull(cache.read("tvh:a", "dvr:finished", recordings))
        assertNull(TvheadendDiskCache(store).read("tvh:a", "dvr:finished", recordings))
    }

    @Test fun recordingUrlsAndLogoTicketsAreNotStored() = runBlocking {
        val store = FakeStore()
        val cache = TvheadendDiskCache(store)
        cache.write("tvh:a", "dvr:finished", recordings, listOf(recording("r1")))
        cache.write("tvh:a", "channels", channels, listOf(LiveTvChannel("u1", "A", 1, "http://nas:9981/imagecache/12?ticket=abc")))
        val stored = store.data.getValue("tvh:a").values.joinToString()
        assertFalse(stored.contains("ticket"))
        assertFalse(stored.contains("dvrfile"))
        val restored = TvheadendDiskCache(store)
        assertNull(restored.read("tvh:a", "dvr:finished", recordings)?.single()?.url)
        assertEquals("http://nas:9981/imagecache/12", restored.read("tvh:a", "channels", channels)?.single()?.iconUrl)
    }

    @Test fun epgIsTrimmedToNowUntilTwelveHours() {
        val now = 100_000L
        val kept = TvheadendDiskCache.trimEpg(listOf(
            event("past", now - 7200, now - 60),
            event("live", now - 600, now + 600),
            event("later", now + 11 * 3600, now + 12 * 3600),
            event("tooLate", now + 13 * 3600, now + 14 * 3600),
        ), now).map { it.eventId }
        assertEquals(listOf("live", "later"), kept)
    }

    @Test fun epgMergePrefersFreshCopyOfAnEvent() {
        val now = 100_000L
        val merged = TvheadendDiskCache.mergeEpg(
            listOf(event("1", now, now + 60).copy(title = "old"), event("2", now + 60, now + 120)),
            listOf(event("1", now, now + 60).copy(title = "new")), now)
        assertEquals(listOf("new", "T2"), merged.map { it.title })
    }

    @Test fun rejectedLoginIsNotHiddenBehindStoredData() {
        assertFalse(tvhStoredDataMayReplace(TvheadendHttpException(401, "401")))
        assertFalse(tvhStoredDataMayReplace(TvheadendHttpException(403, "403")))
        assertTrue(tvhStoredDataMayReplace(TvheadendHttpException(503, "503")))
        assertTrue(tvhStoredDataMayReplace(java.net.ConnectException("down")))
    }
}
