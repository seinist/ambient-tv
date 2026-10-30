package com.ambient.tv.data.radio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
