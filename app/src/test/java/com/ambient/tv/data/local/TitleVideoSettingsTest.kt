package com.ambient.tv.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TitleVideoSettingsTest {
    private val changed = TitleVideoSettings(mpv = MpvImageOptions(svpMode = "balanced"))

    @Test fun emptySettingsRemoveTheTitle() {
        val stored = TitleVideoSettings.put(emptyMap(), "plex:1", changed.copy(savedAt = 1))
        assertTrue("plex:1" in stored)
        assertFalse("plex:1" in TitleVideoSettings.put(stored, "plex:1", TitleVideoSettings(savedAt = 2)))
    }

    @Test fun onlyTheNewestTitlesAreKept() {
        var all = emptyMap<String, TitleVideoSettings>()
        (1..5).forEach { all = TitleVideoSettings.put(all, "plex:$it", changed.copy(savedAt = it.toLong()), limit = 3) }
        assertEquals(setOf("plex:3", "plex:4", "plex:5"), all.keys)
    }

    @Test fun savedAtAloneIsEmpty() {
        assertTrue(TitleVideoSettings(savedAt = 99).isEmpty)
        assertFalse(changed.isEmpty)
    }

    @Test fun overloadContinueSignatureIsNotEmpty() {
        assertFalse(TitleVideoSettings(mpvOverloadContinueSignature = "shader:nnedi3_nns32").isEmpty)
        assertFalse(TitleVideoSettings(vlcOverloadContinueSignature = "svp:balanced").isEmpty)
    }

    @Test fun explicitRendererOverridesTheGlobalDefaultUntilForgotten() {
        val title = TitleVideoSettings(rendererKey = "vlc")
        assertFalse(title.isEmpty)
        assertEquals("vlc", title.rendererOrDefault("media3"))
        assertEquals("media3", title.copy(rendererKey = null).rendererOrDefault("media3"))
        assertEquals("auto", title.copy(rendererKey = "auto").rendererOrDefault("mpv"))
        assertEquals("mpv", title.copy(rendererKey = "invalid").rendererOrDefault("mpv"))
    }
    @Test fun explicitDefaultSnapshotAndAudioSurviveSerialization() {
        val json = kotlinx.serialization.json.Json { encodeDefaults = false }
        val profile = TitleVideoSettings(snapshot = true, rendererKey = "mpv",
            mpv = MpvImageOptions(), audio = AudioSettings().update(AudioOptions(night = true, delayMs = 80)))
        val stored = TitleVideoSettings.put(emptyMap(), "plex:1", profile)
        assertEquals(profile, stored["plex:1"])
        val encoded = json.encodeToString(TitleVideoSettings.serializer(), profile)
        assertEquals(profile, json.decodeFromString(TitleVideoSettings.serializer(), encoded))
        assertEquals(TitleVideoSettings(), json.decodeFromString(TitleVideoSettings.serializer(), "{}"))
    }
}
