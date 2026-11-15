package com.ambient.tv.data.local

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class MenuIdentityPersistenceTest {
    @After fun clearDisplayNames() {
        SystemNameOverrides.names = emptyMap()
    }

    @Test fun newMenuNamesUseTheSameNormalizationAsExistingSystems() {
        val longName = "W".repeat(SystemNameOverrides.MAX_LENGTH + 5)
        assertEquals(
            mapOf("plex" to "Kino", "iptv" to "Weltfernsehen", "weather" to longName.take(SystemNameOverrides.MAX_LENGTH)),
            SystemNameOverrides.normalized(mapOf(
                "plex" to " Kino ",
                "iptv" to " Weltfernsehen ",
                "weather" to " $longName ",
                "radio" to "   ",
                "unknown" to "Unbekannt",
            )),
        )
    }

    @Test fun namesSurviveReopeningPreferencesWithoutChangingOtherSystems() {
        val storage = memoryPreferences()
        val originalNames = mapOf("plex" to "Kino", "tvheadend" to "Satellit", "radio" to "Sender")
        val expected = originalNames + mapOf("iptv" to "Weltfernsehen", "weather" to "Draußen")
        AppPreferences(storage).apply { systemNames = originalNames }
            .apply { systemNames = systemNames + mapOf("iptv" to "Weltfernsehen", "weather" to "Draußen") }

        val restored = AppPreferences(storage)
        assertEquals(expected, restored.systemNames)
        assertEquals(expected, SystemNameOverrides.names)
    }

    @Test fun savedMenuOrderRetainsWeatherAndIptvAtTheirChosenPositions() {
        val storage = memoryPreferences()
        val chosen = listOf("weather", "plex", "iptv", "radio", "tvheadend")
        AppPreferences(storage).sidebarSystemOrder = chosen + listOf("weather", "unknown")

        val restored = AppPreferences(storage).sidebarSystemOrder
        assertEquals(chosen, restored.take(chosen.size))
        assertEquals(restored.distinct(), restored)
        assertFalse("unknown" in restored)
    }

    @Test fun dividersAndIptvSubmenuConfigurationSurviveReopening() {
        val storage = memoryPreferences()
        val dividers = setOf("iptv", "weather", "plex", "home", "search")
        val entryOrder = mapOf(
            "iptv" to listOf("tv:iptv:one:guide", "tv:iptv:one:live"),
            "plex" to listOf("plex:home", "plex:movies"),
        )
        val entryDividers = mapOf("iptv" to listOf("tv:iptv:one:guide"))
        AppPreferences(storage).apply {
            sidebarDividersAfter = dividers
            sidebarEntryOrder = entryOrder
            sidebarEntryDividers = entryDividers
        }

        val restored = AppPreferences(storage)
        assertEquals(dividers, restored.sidebarDividersAfter)
        assertEquals(entryOrder, restored.sidebarEntryOrder)
        assertEquals(entryDividers, restored.sidebarEntryDividers)
    }

    @Test fun backupRoundTripsNewNamesOrderAndDividersWithExistingConfiguration() {
        val source = AppPreferences(memoryPreferences()).apply {
            systemNames = mapOf("plex" to "Kino", "radio" to "Sender", "iptv" to "Weltfernsehen", "weather" to "Draußen")
            sidebarSystemOrder = listOf("weather", "iptv", "plex", "radio")
            sidebarDividersAfter = setOf("weather", "iptv", "plex")
            sidebarEntryOrder = mapOf("iptv" to listOf("tv:iptv:one:guide", "tv:iptv:one:live"))
            sidebarEntryDividers = mapOf("iptv" to listOf("tv:iptv:one:guide"))
        }
        val exported = SettingsBackupManager(source, TokenStore(memoryPreferences(), source)).exportJson()
        val targetStorage = memoryPreferences()
        val target = AppPreferences(targetStorage)
        SettingsBackupManager(target, TokenStore(memoryPreferences(), target)).importJson(exported)

        val restored = AppPreferences(targetStorage)
        assertEquals(source.systemNames, restored.systemNames)
        assertEquals(source.sidebarSystemOrder, restored.sidebarSystemOrder)
        assertEquals(source.sidebarDividersAfter, restored.sidebarDividersAfter)
        assertEquals(source.sidebarEntryOrder, restored.sidebarEntryOrder)
        assertEquals(source.sidebarEntryDividers, restored.sidebarEntryDividers)
    }
}
