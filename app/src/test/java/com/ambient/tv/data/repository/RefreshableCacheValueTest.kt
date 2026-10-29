package com.ambient.tv.data.repository

import org.junit.Assert.*
import org.junit.Test

class RefreshableCacheValueTest {
    @Test fun reconnectRejectsAnOlderInFlightCacheWrite() {
        val cache = RefreshableCacheValue<String>()
        val oldRequest = cache.generation()
        cache.invalidate()
        assertFalse(cache.publish(oldRequest, "old collections", 100L))
        assertNull(cache.fresh(100L, 600_000L))
    }

    @Test fun oldResponseCannotReplaceTheSuccessfulPostReconnectValue() {
        val cache = RefreshableCacheValue<String>()
        val oldRequest = cache.generation()
        cache.publish(oldRequest, "before reconnect", 10L)
        cache.invalidate()
        assertTrue(cache.publish(cache.generation(), "fresh collections", 20L))
        assertFalse(cache.publish(oldRequest, "late old collections", 30L))
        assertEquals("fresh collections", cache.fresh(30L, 600_000L))
    }

    @Test fun regularTtlStillExpiresWithoutAReconnect() {
        val cache = RefreshableCacheValue<String>()
        cache.publish(cache.generation(), "collections", 100L)
        assertEquals("collections", cache.fresh(199L, 100L))
        assertNull(cache.fresh(200L, 100L))
    }

    @Test fun invalidationImmediatelyHidesTheOldValueFromFreshReads() {
        val cache = RefreshableCacheValue<List<String>>()
        cache.publish(cache.generation(), listOf("old"), 100L)
        cache.invalidate()
        assertNull(cache.fresh(101L, 600_000L))
        assertTrue(cache.publish(cache.generation(), emptyList(), 102L))
        assertEquals(emptyList<String>(), cache.fresh(103L, 600_000L))
    }
}
