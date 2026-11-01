package com.ambient.tv.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSearchLandingTest {
    @Test fun consumedSearchRemainsAvailableWhenTheLazyRowIsFirstComposed() {
        val landing = SettingsSearchLanding()
        landing.accept("Plex Startseite Reihen")

        // The hub has scrolled and consumed its request before an off-screen row is composed.
        val rowQuery = landing.accept(null)
        assertEquals("Plex Startseite Reihen", rowQuery)
        assertTrue(settingsSearchPrefersStartPageTab(rowQuery!!))
    }

    @Test fun aNewSearchReplacesThePreviousIntentOnTheSamePage() {
        val landing = SettingsSearchLanding()
        landing.accept("home widgets")
        landing.accept(null)
        landing.accept("Plex Untermenü")

        val rowQuery = landing.accept(null)
        assertEquals("Plex Untermenü", rowQuery)
        assertFalse(settingsSearchPrefersStartPageTab(rowQuery!!))

        landing.accept("Startseite")
        assertTrue(settingsSearchPrefersStartPageTab(landing.accept(null)!!))
    }

    @Test fun serverCustomizationSearchFindsTheLazyItemAfterAnyNumberOfSavedServers() {
        for (category in listOf(SettingsCategory.PLEX, SettingsCategory.JELLYFIN, SettingsCategory.EMBY)) {
            val target = when (category) {
                SettingsCategory.PLEX -> "plex:customization"
                SettingsCategory.JELLYFIN -> "jellyfin:customization"
                else -> "emby:customization"
            }
            for (position in listOf(4, 12, 20)) {
                val index = SettingsItemIndex().apply { replace(mapOf("connection" to 0, target to position)) }
                for (query in listOf("Startseite Reihen", "home widgets", "Untermenü", "sidebar libraries")) {
                    assertEquals("$category / $query", position,
                        index.landingPosition(settingsSearchTargetKey(category, query), requested = true))
                }
            }
        }
    }

    @Test fun anOrdinaryEntryOrBackKeepsItsExistingScrollAndTabChoice() {
        val landing = SettingsSearchLanding()
        assertNull(landing.accept(null))
        assertNull(settingsSearchTargetKey(SettingsCategory.PLEX, "Server verbinden"))
        val index = SettingsItemIndex().apply { replace(mapOf("plex:customization" to 20)) }
        assertNull(index.landingPosition("plex:customization", requested = false))
    }
    @Test fun monogramAccountSetupIsReachableThroughSettingsSearchAndDirectEntry() {
        if (!com.ambient.tv.BuildConfig.MONOGRAM_NATIVE) {
            // Builds without the native core expose no Monogram entry at all.
            assertNull(SettingsCategory.fromKey("monogram"))
            return
        }
        assertEquals(SettingsCategory.TELEGRAM, SettingsCategory.fromKey("monogram"))
        assertEquals(SettingsCategory.TELEGRAM, settingsShortcutCategory("telegram:account"))
        assertEquals("telegram-account", settingsShortcutItemKey("telegram:account"))
        assertEquals("telegram-account", settingsShortcutItemKey("monogram:account"))
        for (query in listOf("Monogram API hash", "Telegram QR login", "Telefon Anmeldecode")) {
            val target = settingsSearchTargetKey(SettingsCategory.TELEGRAM, query)
            assertEquals(query, "telegram-account", target)
            val index = SettingsItemIndex().apply {
                replace(mapOf("telegram-account-section" to 4, "telegram-account" to 5))
            }
            assertEquals(5, index.landingPosition(target, requested = true))
        }
    }

}
