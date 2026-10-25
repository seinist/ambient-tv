package com.ambient.tv.data.browse

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceReportOrderTest {
    @Test
    fun aFailureStartedBeforeTheLastSuccessIsStale() {
        val order = SourceReportOrder()
        val hung = order.begin()          // request hangs through the wake
        val fresh = order.begin()
        assertTrue(order.accept("plex", fresh, SourceHealth.ONLINE))
        assertFalse(order.accept("plex", hung, SourceHealth.OFFLINE))
        assertTrue(order.accept("plex", order.begin(), SourceHealth.OFFLINE))
    }

    @Test
    fun successesAlwaysApplyAndScopesAreSeparate() {
        val order = SourceReportOrder()
        val old = order.begin()
        assertTrue(order.accept("plex", order.begin(), SourceHealth.ONLINE))
        assertTrue(order.accept("plex", old, SourceHealth.ONLINE))
        assertTrue(order.accept("jellyfin", old, SourceHealth.OFFLINE))
    }
}
