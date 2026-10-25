package com.ambient.tv.data.local

import com.ambient.tv.companion.CompanionBackupPolicy
import kotlinx.serialization.decodeFromString
import org.junit.Assert.*
import org.junit.Test

class ScopedVideoSettingsBackupTest {
    private val title = mapOf("plex:3:srv:42" to TitleVideoSettings(rendererKey = "mpv", savedAt = 2))
    private val scoped = mapOf("season:plex:3:srv:7" to TitleVideoSettings(audioDelayMs = 50, savedAt = 3))

    @Test fun titleAndScopedProfilesSurviveABackupAndOldBackupsKeepThem() {
        val source = AppPreferences(memoryPreferences())
        source.titleVideoSettings = title
        source.scopedVideoSettings = scoped
        val exported = SettingsBackupManager(source, TokenStore(memoryPreferences(), source)).exportJson()
        val target = AppPreferences(memoryPreferences())
        val manager = SettingsBackupManager(target, TokenStore(memoryPreferences(), target))
        manager.importJson(exported)
        assertEquals(title, target.titleVideoSettings)
        assertEquals(scoped, target.scopedVideoSettings)
        manager.importJson("""{"schemaVersion":68,"exportedAtEpochMs":0}""")
        assertEquals(title, target.titleVideoSettings)
        assertEquals(scoped, target.scopedVideoSettings)
    }

    @Test fun restoreDropsMalformedKeysAndTitleOnlyFieldsInScopes() {
        val restoredTitles = restorableTitleVideoSettings(title + mapOf("" to TitleVideoSettings(rendererKey = "vlc"),
            "season:plex:0::1" to TitleVideoSettings(rendererKey = "vlc"), "plex:1" to TitleVideoSettings()))
        assertEquals(title, restoredTitles)
        val restoredScopes = restorableScopedVideoSettings(mapOf("plex:1" to TitleVideoSettings(rendererKey = "vlc"),
            "series:plex:0::9" to TitleVideoSettings(rendererKey = "mpv", mpvOverloadContinueSignature = "x", snapshot = true)))
        assertEquals(mapOf("series:plex:0::9" to TitleVideoSettings(rendererKey = "mpv")), restoredScopes)
        val many = (1..TitleVideoSettings.MAX_TITLES + 5).associate { "plex:$it" to TitleVideoSettings(rendererKey = "mpv", savedAt = it.toLong()) }
        assertEquals(TitleVideoSettings.MAX_TITLES, restorableTitleVideoSettings(many).size)
        assertFalse("plex:1" in restorableTitleVideoSettings(many))
    }

    @Test fun companionCarriesProfilesOnlyFromSchema69AsDeviceSettings() {
        val incoming = SettingsBackup(schemaVersion = 69, exportedAtEpochMs = 2, titleVideoSettings = title, scopedVideoSettings = scoped)
        val target = SettingsBackup(schemaVersion = 69, exportedAtEpochMs = 1)
        val merged = CompanionBackupPolicy.json.decodeFromString<SettingsBackup>(CompanionBackupPolicy.merge(incoming, target, "settings", true, true))
        assertEquals(title, merged.titleVideoSettings)
        assertEquals(scoped, merged.scopedVideoSettings)
        val old = CompanionBackupPolicy.json.decodeFromString<SettingsBackup>(CompanionBackupPolicy.merge(
            incoming.copy(schemaVersion = 68), target.copy(titleVideoSettings = mapOf("plex:1" to TitleVideoSettings(rendererKey = "vlc"))), "settings", true, true))
        assertEquals(mapOf("plex:1" to TitleVideoSettings(rendererKey = "vlc")), old.titleVideoSettings)
        val withoutDevice = CompanionBackupPolicy.json.decodeFromString<SettingsBackup>(CompanionBackupPolicy.merge(incoming, target, "settings", true, false))
        assertTrue(withoutDevice.titleVideoSettings.isEmpty())
    }

    private fun full(i: Int) = TitleVideoSettings(
        rendererKey = "mpv", mpv = MpvImageOptions(sharpening = "medium", denoise = "high", brightness = i % 7, contrast = 3, saturation = -2),
        mpvDeinterlaceMode = "on", mpvDeinterlaceFilter = "bwdif", vlc = VlcImageOptions(softwareProcessing = true, brightness = 4),
        vlcDeinterlaceMode = "on", vlcDeinterlaceFilter = "yadif", media3 = Media3ImageOptions(enabled = true, contrast = 5),
        mpvOutput = "gpu-next", mpvDecoder = "mediacodec-copy", vlcOutput = "opengl", audioDelayMs = 120 + i, subtitleDelayMs = -300,
        mpvOverloadContinueSignature = "sig-$i", vlcOverloadContinueSignature = "vsig-$i",
        audio = AudioSettings(selectedProfile = "tv"), savedAt = i.toLong())

    @Test fun companionFitsFullProfilesCompactlyOrLeavesThemOnTheTv() {
        val titles = (1..TitleVideoSettings.MAX_TITLES).associate { "plex:3:srv:$it" to full(it) }
        val scopes = (1..200).associate { "series:plex:3:srv:$it" to full(it).copy(audioDelayMs = null, subtitleDelayMs = null,
            mpvOverloadContinueSignature = null, vlcOverloadContinueSignature = null) }
        val source = SettingsBackup(schemaVersion = 69, exportedAtEpochMs = 2, titleVideoSettings = titles, scopedVideoSettings = scopes)
        val prepared = CompanionBackupPolicy.prepareWithInfo(source, credentials = false)
        assertTrue(prepared.json.toByteArray().size <= CompanionBackupPolicy.MAX_BYTES)
        // 200 full titles + 200 full scopes fit thanks to the compact encoding, and arrive intact.
        assertFalse(prepared.profilesOmitted)
        val sent = CompanionBackupPolicy.json.decodeFromString<SettingsBackup>(prepared.json)
        assertEquals(titles, sent.titleVideoSettings)
        assertEquals(scopes, sent.scopedVideoSettings)
        // Far beyond the limit: the transfer still works without the profiles, flagged for the hint.
        val huge = source.copy(scopedVideoSettings = (1..5_000).associate { "collection:plex:3:srv:$it" to full(it) })
        val omitted = CompanionBackupPolicy.prepareWithInfo(huge, credentials = false)
        assertTrue(omitted.profilesOmitted)
        assertTrue(omitted.json.toByteArray().size <= CompanionBackupPolicy.MAX_BYTES)
        val received = CompanionBackupPolicy.json.decodeFromString<SettingsBackup>(omitted.json)
        assertTrue(received.titleVideoSettings.isEmpty() && received.scopedVideoSettings.isEmpty())
        // The receiver keeps its own profiles when they were left out.
        val own = mapOf("plex:1" to TitleVideoSettings(rendererKey = "vlc"))
        val merged = CompanionBackupPolicy.json.decodeFromString<SettingsBackup>(CompanionBackupPolicy.merge(
            received, SettingsBackup(schemaVersion = 69, exportedAtEpochMs = 1, titleVideoSettings = own), "settings", true, true))
        assertEquals(own, merged.titleVideoSettings)
    }

    @Test fun scopesAreCappedNewestFirst() {
        var all = emptyMap<String, TitleVideoSettings>()
        for (i in 1..TitleVideoSettings.MAX_SCOPES + 10) all = TitleVideoSettings.putScoped(all, "series:plex:0::$i", TitleVideoSettings(rendererKey = "mpv", savedAt = i.toLong()))
        assertEquals(TitleVideoSettings.MAX_SCOPES, all.size)
        assertFalse("series:plex:0::1" in all)
        assertTrue("series:plex:0::${TitleVideoSettings.MAX_SCOPES + 10}" in all)
        val restored = restorableScopedVideoSettings((1..TitleVideoSettings.MAX_SCOPES + 3).associate {
            "season:plex:0::$it" to TitleVideoSettings(rendererKey = "vlc", savedAt = it.toLong()) })
        assertEquals(TitleVideoSettings.MAX_SCOPES, restored.size)
        assertFalse("season:plex:0::3" in restored)
    }

    @Test fun liveProfileAutoInheritsAndNewLiveProfilesStartOnAuto() {
        assertEquals("vlc", ResolutionVideoProfile(rendererKey = "auto").liveRendererOrDefault("vlc"))
        assertEquals("mpv", ResolutionVideoProfile(rendererKey = "mpv").liveRendererOrDefault("vlc"))
        assertEquals("vlc", ResolutionVideoProfile(rendererKey = "bogus").liveRendererOrDefault("vlc"))
        val general = ResolutionVideoProfile(rendererKey = "mpv", mpv = MpvImageOptions(sharpening = "medium"))
        assertEquals(general.copy(rendererKey = "auto"), liveProfileTemplate(general))
    }
}
