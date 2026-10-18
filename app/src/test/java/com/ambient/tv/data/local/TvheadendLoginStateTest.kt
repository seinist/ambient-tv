package com.ambient.tv.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TvheadendLoginStateTest {
    /** Endpoint set, then the stored login state replaced by exactly what [seed] writes (legacy installs). */
    private fun preferences(seed: (android.content.SharedPreferences.Editor) -> Unit = {}): AppPreferences {
        val raw = memoryPreferences()
        val prefs = AppPreferences(raw).apply { tvheadendEnabled = true; tvheadendHost = "nas.local" }
        raw.edit().remove(AppPreferences.KEY_TVH_LOGIN_STATE).remove(AppPreferences.KEY_TVH_LOGIN_VERIFIED).also(seed).apply()
        return prefs
    }

    @Test fun legacyFalseMigratesToUnknownAndStaysVisible() {
        val prefs = preferences { it.putBoolean(AppPreferences.KEY_TVH_LOGIN_VERIFIED, false) }
        assertEquals(TvheadendLoginState.UNKNOWN, prefs.tvheadendLoginState)
        assertTrue(prefs.isTvheadendConfigured)
        assertFalse(prefs.tvheadendLoginRejected)
    }

    @Test fun legacyTrueAndAbsentAreVerified() {
        assertEquals(TvheadendLoginState.VERIFIED, storedTvheadendLoginState(null, true))
        assertEquals(TvheadendLoginState.VERIFIED, storedTvheadendLoginState(null, null))
        assertEquals(TvheadendLoginState.UNKNOWN, storedTvheadendLoginState(null, false))
        assertEquals(TvheadendLoginState.REJECTED, storedTvheadendLoginState("REJECTED", true))
        assertEquals(TvheadendLoginState.VERIFIED, storedTvheadendLoginState("garbage", null))
    }

    @Test fun writingStateDropsLegacyKey() {
        val raw = memoryPreferences()
        raw.edit().putBoolean(AppPreferences.KEY_TVH_LOGIN_VERIFIED, false).apply()
        val prefs = AppPreferences(raw)
        prefs.tvheadendLoginState = TvheadendLoginState.VERIFIED
        assertFalse(raw.contains(AppPreferences.KEY_TVH_LOGIN_VERIFIED))
        assertEquals("VERIFIED", raw.getString(AppPreferences.KEY_TVH_LOGIN_STATE, null))
    }

    @Test fun rejectedHidesFromHomeButKeepsMenuEntry() {
        val prefs = preferences()
        prefs.tvheadendLoginState = TvheadendLoginState.REJECTED
        assertFalse(prefs.isTvheadendConfigured)
        assertTrue(prefs.hasTvheadendEndpoint)
        assertTrue(prefs.tvheadendLoginRejected)
    }

    @Test fun endpointChangeTurnsRejectedIntoUnknown() {
        val prefs = preferences()
        prefs.tvheadendLoginState = TvheadendLoginState.REJECTED
        prefs.tvheadendUsername = "bob"
        assertEquals(TvheadendLoginState.UNKNOWN, prefs.tvheadendLoginState)
        assertTrue(prefs.isTvheadendConfigured)
    }

    @Test fun compatFalseNeverRejects() {
        val prefs = preferences()
        prefs.tvheadendLoginVerified = false
        assertEquals(TvheadendLoginState.UNKNOWN, prefs.tvheadendLoginState)
        assertTrue(prefs.isTvheadendConfigured)
    }
}
