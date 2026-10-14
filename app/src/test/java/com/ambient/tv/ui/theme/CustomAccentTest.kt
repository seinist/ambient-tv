package com.ambient.tv.ui.theme

import org.junit.Assert.*
import org.junit.Test

class CustomAccentTest {
    @Test fun accentAndHeroRoundTripThroughBackupWithNormalization() {
        val prefs = com.ambient.tv.data.local.AppPreferences(com.ambient.tv.data.local.memoryPreferences())
        val tokens = com.ambient.tv.data.local.TokenStore(com.ambient.tv.data.local.memoryPreferences(), prefs)
        val backup = com.ambient.tv.data.local.SettingsBackupManager(prefs, tokens)
        prefs.accentColorKey = "custom"
        prefs.customAccentHex = "#0012abef"
        prefs.homeHeroStyleKey = com.ambient.tv.data.local.HomeHeroStyle.ARTWORK_COLOR
        val exported = backup.exportJson()
        prefs.customAccentHex = "invalid"
        prefs.homeHeroStyleKey = "static"
        backup.importJson(exported)
        assertEquals("custom", prefs.accentColorKey)
        assertEquals("#12ABEF", prefs.customAccentHex)
        assertEquals(com.ambient.tv.data.local.HomeHeroStyle.ARTWORK_COLOR, prefs.homeHeroStyleKey)
    }

    @Test fun canonicalRgbIsOpaqueAndStable() {
        assertEquals("#12ABEF", normalizedCustomAccentHex(" #12abef "))
        assertEquals("#12ABEF", normalizedCustomAccentHex("0012abef"))
        assertEquals("#000000", normalizedCustomAccentHex("#000000"))
        assertEquals("#FFFFFF", normalizedCustomAccentHex("#ffffff"))
        listOf("", "#", "#12ab", "#GGGGGG", "#1122334455").forEach {
            assertEquals("#E5A00D", normalizedCustomAccentHex(it))
        }
    }
}
