package com.ambient.tv.ui.player.backend

import org.junit.Assert.*
import org.junit.Test

class MpvZeroCopyFailureCacheTest {
    @Test fun failureOnlyAffectsTheSameScopeAndExpires() {
        var clock = 0L
        val cache = MpvZeroCopyFailureCache(nowMs = { clock }, ttlMs = 100L)
        cache.record("source-a/1080p")
        assertTrue(cache.contains("source-a/1080p"))
        assertFalse(cache.contains("source-a/2160p"))
        assertFalse(cache.contains("source-b/1080p"))
        assertFalse(cache.contains(null))
        clock = 100L
        assertFalse(cache.contains("source-a/1080p"))
        assertFalse(cache.any())
    }

    @Test fun boundedLruAndExplicitReset() {
        val cache = MpvZeroCopyFailureCache(nowMs = { 0L }, capacity = 2)
        cache.record("a")
        cache.record("b")
        assertTrue(cache.contains("a"))
        cache.record("c")
        assertTrue(cache.contains("a"))
        assertFalse(cache.contains("b"))
        assertTrue(cache.contains("c"))
        cache.clear()
        assertFalse(cache.any())
    }
}
