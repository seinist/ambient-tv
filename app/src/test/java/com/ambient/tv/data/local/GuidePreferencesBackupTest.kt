package com.ambient.tv.data.local

import com.ambient.tv.livetv.GuideOptions
import com.ambient.tv.livetv.GuideSort
import com.ambient.tv.livetv.LiveTvChannelAccess
import com.ambient.tv.livetv.LiveTvChannelKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuidePreferencesBackupTest {
    private val chosen = GuideOptions(
        sort = GuideSort.NAME,
        kind = LiveTvChannelKind.TV,
        access = LiveTvChannelAccess.FTA,
        tag = "News & Culture",
        onlyWithEpg = true,
        descending = true,
        onlyHbbtv = true,
    )

    @Test fun guideOptionsForEverySourceTravelWithLibraryPreferences() {
        val source = AppPreferences(memoryPreferences())
        source.setGuideOptions("tvheadend", chosen)
        source.setGuideOptions("iptv:living-room", chosen.copy(kind = LiveTvChannelKind.RADIO, tag = null))
        source.setSectionSort("plex:library", "title")
        val exported = source.portableLibraryPreferences()
        assertTrue(exported.containsKey("guide_options_tvheadend"))
        assertTrue(exported.containsKey("guide_options_iptv:living-room"))

        val target = AppPreferences(memoryPreferences())
        target.setGuideOptions("removed-source", chosen)
        target.restorePortableLibraryPreferences(exported, includeGuideOptions = true)

        assertEquals(chosen, target.guideOptions("tvheadend"))
        assertEquals(chosen.copy(kind = LiveTvChannelKind.RADIO, tag = null), target.guideOptions("iptv:living-room"))
        assertEquals(GuideOptions(), target.guideOptions("removed-source"))
        assertEquals("title", target.sectionSort("plex:library"))
    }

    @Test fun legacyRestoreNeitherClearsNorReplacesReceivingGuideOptions() {
        val target = AppPreferences(memoryPreferences())
        target.setGuideOptions("tvheadend", chosen)
        val sender = AppPreferences(memoryPreferences())
        sender.setGuideOptions("tvheadend", chosen.copy(descending = false))
        sender.setGuideOptions("other-source", chosen)
        sender.setSectionSort("plex:library", "year")

        // The default is the legacy path, even for an old file with hand-added guide keys.
        target.restorePortableLibraryPreferences(sender.portableLibraryPreferences())
        assertEquals(chosen, target.guideOptions("tvheadend"))
        assertEquals(GuideOptions(), target.guideOptions("other-source"))
        assertEquals("year", target.sectionSort("plex:library"))
        target.restorePortableLibraryPreferences(emptyMap())
        assertEquals(chosen, target.guideOptions("tvheadend"))
    }

    @Test fun completeEmptyGuideBackupRestoresDefaultsWithoutTouchingOtherPreferences() {
        val target = AppPreferences(memoryPreferences())
        target.setGuideOptions("tvheadend", chosen)
        target.weatherSettings = target.weatherSettings.copy(enabled = true)

        target.restorePortableLibraryPreferences(emptyMap(), includeGuideOptions = true)

        assertEquals(GuideOptions(), target.guideOptions("tvheadend"))
        assertFalse(target.portableLibraryPreferences().keys.any { it.startsWith("guide_options_") })
        assertTrue(target.weatherSettings.enabled)
    }
}
