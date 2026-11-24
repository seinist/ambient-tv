package com.ambient.tv.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RommBackupTest {
    private fun manager(preferences: AppPreferences, tokens: TokenStore = TokenStore(memoryPreferences(), preferences)) =
        SettingsBackupManager(preferences, tokens)

    @Test fun addressAndAccountTravelButEachDeviceSignsInItself() {
        val source = AppPreferences(memoryPreferences()).apply {
            rommEnabled = true
            rommServerUrl = "http://nas:8080"
            rommUsername = "player"
            rommAuthMode = "token"
        }
        val sourceTokens = TokenStore(memoryPreferences(), source).apply { rommToken = "rmm_secret" }
        val exported = manager(source, sourceTokens).exportJson()
        assertFalse("The device token never leaves the device", exported.contains("rmm_secret"))

        val target = AppPreferences(memoryPreferences())
        manager(target).importJson(exported)
        assertTrue(target.rommEnabled)
        assertEquals("http://nas:8080", target.rommServerUrl)
        assertEquals("player", target.rommUsername)
        assertEquals("", target.rommAuthMode)
        assertFalse(target.isRommConfigured)
    }

    @Test fun theSameServerKeepsThisDevicesSignIn() {
        val target = AppPreferences(memoryPreferences()).apply {
            rommServerUrl = "http://nas:8080"
            rommAuthMode = "basic"
        }
        val source = AppPreferences(memoryPreferences()).apply {
            rommEnabled = true
            rommServerUrl = "http://nas:8080"
        }
        manager(target).importJson(manager(source).exportJson())
        assertEquals("basic", target.rommAuthMode)
        assertTrue(target.isRommConfigured)
    }
}
