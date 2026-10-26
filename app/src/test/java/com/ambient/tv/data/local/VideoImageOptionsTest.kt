package com.ambient.tv.data.local

import org.junit.Assert.*
import org.junit.Test

class VideoImageOptionsTest {
    @Test fun oldPreferencesKeepTheirScalerAndDeband() {
        val preferences = AppPreferences(memoryPreferences())
        preferences.mpvScalingFilterKey = "nnedi3_nns32"
        preferences.mpvDebandEnabled = false
        preferences.vlcScalingFilterKey = "nearest"
        assertEquals("nnedi3_nns32", preferences.mpvImageOptions.scaler)
        assertEquals("off", preferences.mpvImageOptions.deband)
        assertEquals("nearest", preferences.vlcImageOptions.scaler)
        assertFalse(preferences.vlcImageOptions.softwareProcessing)
        assertFalse(preferences.media3ImageOptions.enabled)
    }

    @Test fun allNewOptionsSurviveBackupRoundTrip() {
        val source = AppPreferences(memoryPreferences())
        source.mpvImageOptions = MpvImageOptions(scaler = "artcnn16", chromaScaler = "krig", downscaler = "ssim_down",
            sharpening = "cas", denoise = "light", deblock = "medium", superResolution = true,
            antiring = 70, brightness = -5, contrast = 10, saturation = 15, gamma = -10,
            toneMapping = "bt.2390", ditherDepth = "10", sigmoid = false, deband = "strong",
            svpMode = "balanced")
        source.vlcImageOptions = VlcImageOptions(softwareProcessing = true, scaler = "spline", deband = "light",
            denoise = "medium", brightness = 5, contrast = -5, saturation = 10, gamma = -10)
        source.media3ImageOptions = Media3ImageOptions(true, "fsr", 5, -10, 15, sharpening = "cas", deband = "medium", gamma = 7)
        val exported = SettingsBackupManager(source, TokenStore(memoryPreferences(), source)).exportJson()
        val target = AppPreferences(memoryPreferences())
        SettingsBackupManager(target, TokenStore(memoryPreferences(), target)).importJson(exported)
        assertEquals(source.mpvImageOptions, target.mpvImageOptions)
        assertEquals(source.vlcImageOptions, target.vlcImageOptions)
        assertEquals(source.media3ImageOptions, target.media3ImageOptions)

    }

    @Test fun schema29ImportClearsStaleAdvancedDefaults() {
        val preferences = AppPreferences(memoryPreferences())
        preferences.mpvImageOptions = MpvImageOptions(scaler = "fsrcnnx16", denoise = "medium", sharpening = "cas")
        preferences.vlcImageOptions = VlcImageOptions(softwareProcessing = true, denoise = "medium")
        preferences.media3ImageOptions = Media3ImageOptions(enabled = true)
        SettingsBackupManager(preferences, TokenStore(memoryPreferences(), preferences)).importJson(
            """{"schemaVersion":29,"exportedAtEpochMs":0,"mpvScalingFilterKey":"spline36","mpvDebandEnabled":false,"vlcScalingFilterKey":"bicubic"}""",
        )
        assertEquals(MpvImageOptions(scaler = "spline36", deband = "off"), preferences.mpvImageOptions)
        assertEquals(VlcImageOptions(scaler = "bicubic"), preferences.vlcImageOptions)
        assertEquals(Media3ImageOptions(), preferences.media3ImageOptions)
    }

    @Test fun invalidImportedValuesCannotInjectNativeOptions() {
        val mpv = MpvImageOptions(scaler = "lanczos:evil", chromaScaler = "missing", downscaler = "missing",
            sharpening = "missing", denoise = "missing", deblock = "missing", brightness = Int.MAX_VALUE,
            gamma = Int.MIN_VALUE, antiring = 999, toneMapping = "missing").normalized()
        assertEquals("lanczos", mpv.scaler)
        assertEquals("off", mpv.denoise)
        assertEquals("off", mpv.deblock)
        assertEquals(20, mpv.brightness)
        assertEquals(-20, mpv.gamma)
        assertEquals(100, mpv.antiring)
        assertEquals("auto", mpv.toneMapping)
        assertEquals("bilinear", VlcImageOptions(scaler = "evil", denoise = "bad").normalized().scaler)
        assertEquals("bilinear", Media3ImageOptions(scaler = "evil").normalized().scaler)
        val junk = Media3ImageOptions(sharpening = "evil", deband = "evil", gamma = -99).normalized()
        assertEquals("off", junk.sharpening)
        assertEquals("off", junk.deband)
        assertEquals(-20, junk.gamma)
        assertEquals("fsr", Media3ImageOptions(scaler = "fsr").normalized().scaler)
    }

    @Test fun integratedSharpenersDisableStackedSsimSuperRes() {
        assertFalse(MpvImageOptions(scaler = "fsr", superResolution = true).normalized().superResolution)
        assertFalse(MpvImageOptions(scaler = "nis", superResolution = true).normalized().superResolution)
        assertTrue(MpvImageOptions(scaler = "artcnn16", superResolution = true).normalized().superResolution)
    }

    @Test fun everyCatalogSelectionCanBePersisted() {
        val preferences = AppPreferences(memoryPreferences())
        VideoImageCatalog.mpvScalers.keys.forEach { key ->
            preferences.mpvImageOptions = MpvImageOptions(scaler = key)
            assertEquals(key, preferences.mpvImageOptions.scaler)
            assertEquals(key, preferences.mpvScalingFilterKey)
        }
        VideoImageCatalog.vlcScalers.keys.forEach { key ->
            preferences.vlcImageOptions = VlcImageOptions(scaler = key)
            assertEquals(key, preferences.vlcImageOptions.scaler)
        }
    }

    @Test fun removedInterpolationIsIgnoredWithoutLosingSvpOrOtherOptions() {
        val backing = memoryPreferences()
        backing.edit().putString("mpv_image_options",
            """{"frameInterpolation":true,"svpMode":"balanced","scaler":"spline64","brightness":5}"""
        ).apply()
        val preferences = AppPreferences(backing)
        val options = preferences.mpvImageOptions
        assertEquals("balanced", options.svpMode)
        assertEquals("spline64", options.scaler)
        assertEquals(5, options.brightness)
        preferences.mpvImageOptions = options
        assertFalse(backing.getString("mpv_image_options", "")!!.contains("frameInterpolation"))

        val manager = SettingsBackupManager(preferences, TokenStore(memoryPreferences(), preferences))
        val legacyBackup = manager.exportJson().replace(
            "\"mpvImageOptions\": {", "\"mpvImageOptions\": {\"frameInterpolation\": true,",
        )
        assertTrue(legacyBackup.contains("frameInterpolation"))
        manager.importJson(legacyBackup)
        assertEquals(options, preferences.mpvImageOptions)
        assertFalse(manager.exportJson().contains("frameInterpolation"))
    }

    @Test fun corruptStoredJsonFallsBackToLegacyValues() {
        val backing = memoryPreferences()
        backing.edit().putString("mpv_image_options", "broken").putString("mpv_scaling_filter", "spline64").apply()
        assertEquals("spline64", AppPreferences(backing).mpvImageOptions.scaler)
    }

}
