package com.ambient.tv.livetv

import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.data.local.memoryPreferences
import org.junit.Assert.*
import org.junit.Test

class LiveTvOptionalFeaturesTest {
    @Test fun defaultOffEvenWhenBufferSizeAlreadyExists() {
        val prefs = AppPreferences(memoryPreferences())
        prefs.liveTimeshiftMb = 2048
        assertFalse(prefs.liveTimeshiftEnabled)
        assertFalse(prefs.liveDirectRecordEnabled)
    }
    @Test fun switchesPersistIndependentlyAndKeepBufferSize() {
        val store = memoryPreferences()
        val prefs = AppPreferences(store)
        prefs.liveTimeshiftMb = 512
        prefs.liveTimeshiftEnabled = true
        prefs.liveDirectRecordEnabled = true
        val restored = AppPreferences(store)
        assertTrue(restored.liveTimeshiftEnabled)
        assertTrue(restored.liveDirectRecordEnabled)
        restored.liveTimeshiftEnabled = false
        assertTrue(restored.liveDirectRecordEnabled)
        assertEquals(512, restored.liveTimeshiftMb)
    }
}
