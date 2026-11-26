package com.ambient.tv.moonlight

import org.junit.Assert.*
import org.junit.Test

class MoonlightCatalogFreshnessTest {
    @Test fun onlyFreshMatchingAuthenticatedProbeAllowsOnlineState() {
        assertTrue(moonlightProbeFresh("peer", "peer", 1_000, 60_999))
        assertFalse(moonlightProbeFresh("peer", "peer", 1_000, 61_000))
        assertFalse(moonlightProbeFresh("other", "peer", 1_000, 1_001))
        assertFalse(moonlightProbeFresh(null, "peer", 1_000, 1_001))
        assertFalse(moonlightProbeFresh("peer", "peer", 1_000, 999))
        assertFalse(moonlightProbeFresh("peer", "peer", -1, 0))
    }
}
