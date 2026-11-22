package com.ambient.tv.data.radio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class RadioBrowserServerPoolTest {
    @Test fun roundRobinPoolAndCurrentMirrorAlwaysHaveFallback() {
        val hosts = radioBrowserHostOrder(emptyList(), null)
        assertEquals(listOf("all.api.radio-browser.info", "de1.api.radio-browser.info"), hosts)
        assertFalse("nl1.api.radio-browser.info" in hosts)
    }

    @Test fun discoveredMirrorsAreKeptWithoutDuplicates() {
        val hosts = radioBrowserHostOrder(
            listOf("de1.api.radio-browser.info", "at2.api.radio-browser.info"),
            null,
        )
        assertEquals(
            listOf("all.api.radio-browser.info", "de1.api.radio-browser.info", "at2.api.radio-browser.info"),
            hosts,
        )
    }

    @Test fun lastSuccessfulMirrorIsPreferredButNotTrustedWhenAbsent() {
        val discovered = listOf("at2.api.radio-browser.info")
        val healthy = radioBrowserHostOrder(discovered, "at2.api.radio-browser.info")
        assertEquals("at2.api.radio-browser.info", healthy.first())
        assertEquals(healthy.size, healthy.distinct().size)
        assertTrue("all.api.radio-browser.info" in healthy)
        assertEquals("all.api.radio-browser.info", radioBrowserHostOrder(discovered, "expired.api.radio-browser.info").first())
    }

    @Test fun anOlderFailureCannotDiscardANewerSuccessfulMirrorOrItsDiscoveryCache() {
        val discoveries = AtomicInteger()
        val pool = RadioBrowserServerPool {
            discoveries.incrementAndGet()
            listOf("fr1.api.radio-browser.info")
        }
        pool.hosts(nowMs = 1_000)
        val olderRequest = pool.successGeneration

        pool.markSuccessful("fr1.api.radio-browser.info")
        pool.clear(olderRequest)

        assertEquals("fr1.api.radio-browser.info", pool.hosts(nowMs = 2_000).first())
        assertEquals(1, discoveries.get())
        assertEquals(olderRequest + 1, pool.successGeneration)
    }

    @Test fun aCurrentFailureClearsThePreferredMirrorAndRediscoversOnTheNextRequest() {
        val discoveries = AtomicInteger()
        val pool = RadioBrowserServerPool {
            discoveries.incrementAndGet()
            listOf("fr1.api.radio-browser.info")
        }
        pool.hosts(nowMs = 1_000)
        pool.markSuccessful("fr1.api.radio-browser.info")

        pool.clear(pool.successGeneration)

        assertEquals(RadioBrowserServerPool.DISCOVERY_HOST, pool.hosts(nowMs = 2_000).first())
        assertEquals(2, discoveries.get())
    }

    @Test fun discoveryIsReusedUntilExpiryAndThePreferredMirrorRemainsAValidCandidate() {
        val discoveries = AtomicInteger()
        val pool = RadioBrowserServerPool {
            discoveries.incrementAndGet()
            listOf("fr1.api.radio-browser.info")
        }
        pool.hosts(nowMs = 1_000)
        pool.markSuccessful("fr1.api.radio-browser.info")

        assertEquals("fr1.api.radio-browser.info", pool.hosts(nowMs = 2_000).first())
        assertEquals(1, discoveries.get())
        assertEquals("fr1.api.radio-browser.info", pool.hosts(nowMs = 3_601_000).first())
        assertEquals(2, discoveries.get())
    }
}
