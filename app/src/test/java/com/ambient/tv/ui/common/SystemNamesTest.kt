package com.ambient.tv.ui.common

import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.data.local.SystemNameOverrides
import com.ambient.tv.data.local.memoryPreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SystemNamesTest {
    @After fun reset() {
        SystemNameOverrides.names = emptyMap()
    }

    @Test fun systemsKeepTheirProviderNameUntilRenamed() {
        assertNull(MediaSystem.PLEX.customName)
        assertEquals("Plex: Libraries", MediaSystem.PLEX.renamed("Plex: Libraries"))
        assertEquals("Plex, Jellyfin and TVHeadend", MediaSystem.renamedAll("Plex, Jellyfin and TVHeadend"))
    }

    @Test fun savedNameReplacesTheProviderNameInTexts() {
        val prefs = AppPreferences(memoryPreferences())
        prefs.systemNames = mapOf("plex" to "  Movies  ", "tvheadend" to "Live", "jellyfin" to " ", "unknown" to "x")
        assertEquals(mapOf("plex" to "Movies", "tvheadend" to "Live"), prefs.systemNames)
        assertEquals("Movies", MediaSystem.PLEX.customName)
        assertEquals("Movies: Libraries", MediaSystem.PLEX.renamed("Plex: Libraries"))
        assertEquals("Movies, Jellyfin and Live", MediaSystem.renamedAll("Plex, Jellyfin and TVHeadend"))
        assertEquals("Live", MediaSystem.fromKey("tv")?.customName)
    }

    @Test fun aNameThatIsAnotherProviderIsNotRenamedAgain() {
        SystemNameOverrides.names = mapOf("plex" to "Emby", "emby" to "Archive")
        assertEquals("Emby and Archive", MediaSystem.renamedAll("Plex and Emby"))
    }

    @Test fun namesSurviveARestartAndAreCappedInLength() {
        val storage = memoryPreferences()
        AppPreferences(storage).systemNames = mapOf("spotify" to "x".repeat(80))
        SystemNameOverrides.names = emptyMap()
        AppPreferences(storage)
        assertEquals("x".repeat(SystemNameOverrides.MAX_LENGTH), MediaSystem.SPOTIFY.customName)
    }

    @Test fun youtubeBrandIsSmartTubeAndFromKeyAcceptsAlias() {
        assertEquals("SmartTube", MediaSystem.YOUTUBE.brand)
        assertEquals(MediaSystem.YOUTUBE, MediaSystem.fromKey("youtube"))
        assertEquals(MediaSystem.YOUTUBE, MediaSystem.fromKey("SmartTube"))
        assertEquals(MediaSystem.YOUTUBE, MediaSystem.fromKey("smarttube"))
        SystemNameOverrides.names = mapOf("youtube" to "ClipTube")
        assertEquals("ClipTube: Home", MediaSystem.YOUTUBE.renamed("SmartTube: Home"))
    }
}
