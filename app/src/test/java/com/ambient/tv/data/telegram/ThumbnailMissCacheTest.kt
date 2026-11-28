package com.ambient.tv.data.telegram

import org.junit.Assert.*
import org.junit.Test

class ThumbnailMissCacheTest {
    @Test fun expiresAndSeparatesAccountAndMediaRevision() {
        var clock = 0L
        val cache = ThumbnailMissCache(nowMs = { clock })
        cache.record("1:2:3:4")
        assertTrue(cache.isRecent("1:2:3:4"))
        assertFalse(cache.isRecent("2:2:3:4"))
        assertFalse(cache.isRecent("1:2:3:5"))
        clock = 4_999L
        assertTrue(cache.isRecent("1:2:3:4"))
        clock = 5_000L
        assertFalse(cache.isRecent("1:2:3:4"))
    }

    @Test fun boundedLruAndClear() {
        val cache = ThumbnailMissCache(maxEntries = 2, nowMs = { 0L })
        cache.record("a")
        cache.record("b")
        assertTrue(cache.isRecent("a"))
        cache.record("c")
        assertFalse(cache.isRecent("b"))
        assertTrue(cache.isRecent("a"))
        cache.clear()
        assertFalse(cache.isRecent("a"))
        assertFalse(cache.isRecent("c"))
    }
}
