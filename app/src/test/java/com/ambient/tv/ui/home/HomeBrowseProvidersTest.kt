package com.ambient.tv.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class HomeBrowseProvidersTest {
    @Test fun recoveredJellyfinDoesNotKeepWarningOrRetryingForUnconfiguredEmby() {
        val providers = homeBrowseProviders(false, true, false, false, false, false)
        val offlineSources = setOf("plex", "emby")
        assertEquals(listOf("jellyfin"), providers)
        assertFalse(providers.any { it in offlineSources })
    }

    @Test fun dedicatedPagesIgnoreOtherConfiguredOfflineSources() {
        assertEquals(listOf("plex"), homeBrowseProviders(true, true, true, true, false, false))
        assertEquals(listOf("jellyfin"), homeBrowseProviders(true, true, true, false, true, false))
        assertEquals(listOf("emby"), homeBrowseProviders(true, true, true, false, false, true))
    }

    @Test fun combinedHomeChecksAllConfiguredSources() {
        assertEquals(listOf("plex", "jellyfin", "emby"), homeBrowseProviders(true, true, true, false, false, false))
        assertEquals(emptyList<String>(), homeBrowseProviders(false, false, false, false, false, false))
    }
}
