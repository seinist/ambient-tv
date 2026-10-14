package com.ambient.tv.data.local

import com.ambient.tv.ui.player.PlayerVideoSettings
import com.ambient.tv.ui.player.withResolution
import com.ambient.tv.ui.player.withTitle
import org.junit.Assert.*
import org.junit.Test

class ResolutionVideoProfileTest {
    @Test fun savingOnePlayingRendererKeepsOtherRendererSettings() {
        val configured = ResolutionVideoProfile("auto", mpv = MpvImageOptions(sharpening = "cas"),
            mpvOutput = "gpu", mpvDecoder = "mediacodec-copy", vlc = VlcImageOptions(scaler = "point"),
            audio = AudioSettings().update(AudioOptions(delayMs = 80)))
        val capture = ResolutionVideoProfile("vlc", vlc = VlcImageOptions(scaler = "bilinear"),
            vlcDeinterlaceMode = "auto", vlcDeinterlaceFilter = "yadif2x")
        val saved = configured.withSnapshot(capture)
        assertEquals("vlc", saved.rendererKey)
        assertEquals(capture.vlc, saved.vlc)
        assertEquals("yadif2x", saved.vlcDeinterlaceFilter)
        assertEquals(configured.mpv, saved.mpv)
        assertEquals(configured.mpvDecoder, saved.mpvDecoder)
        assertEquals(configured.audio, saved.audio)
    }
    @Test fun editingAndRemovingLiveAudioVideoKeepsLibraryAndOtherLiveClasses() {
        val movie = ResolutionVideoProfile("mpv", mpv = MpvImageOptions(sharpening = "adaptive"))
        val hd = ResolutionVideoProfile("vlc", vlcDeinterlaceFilter = "yadif2x")
        val live = ResolutionVideoProfile("mpv", mpvOutput = "direct",
            audio = AudioSettings().update(AudioOptions(output = "pcm", delayMs = 80)))
        val original = mapOf("width:1280" to movie, "live:width:1920" to hd)
        val changed = withLiveResolutionProfile(original, "width:1280", live)
        assertEquals(movie, changed["width:1280"])
        assertEquals(hd, changed["live:width:1920"])
        assertEquals(live, changed["live:width:1280"])
        assertEquals(original, withLiveResolutionProfile(changed, "width:1280", null))
    }

    @Test(expected = IllegalArgumentException::class)
    fun liveEditorCannotWriteGlobalProfiles() {
        withLiveResolutionProfile(emptyMap(), "all", ResolutionVideoProfile("vlc"))
    }
    @Test fun croppedVideosShareWidthButPortraitAndNearbyWidthsDoNot() {
        val profile = ResolutionVideoProfile("mpv")
        val profiles = mapOf("width:1920" to profile)
        for (height in listOf(800, 1080, 1200)) {
            val metadata = com.ambient.tv.ui.player.PlaybackMetadata(route = "movie", title = "Movie",
                source = "plex", mediaId = "1", sourceWidth = 1920, sourceHeight = height)
            assertEquals(profile, profiles[com.ambient.tv.ui.player.ResolutionVideoProfileStore.key(metadata)])
        }
        assertNull(profiles[VideoResolutionClass.key(1919)])
        assertNull(profiles[VideoResolutionClass.key(1080)])
        assertNull(VideoResolutionClass.key(null))
        assertNull(VideoResolutionClass.key(0))
        assertNotEquals(VideoResolutionClass.key(720), VideoResolutionClass.key(1280))
    }

    @Test fun liveOverrideWinsAsAWholeAndDeletionRestoresRegularWidthFallback() {
        val regular = ResolutionVideoProfile("mpv", mpv = MpvImageOptions(sharpening = "cas"))
        val live = ResolutionVideoProfile("vlc", vlcDeinterlaceMode = "auto", vlcDeinterlaceFilter = "yadif2x")
        val profiles = mapOf("width:1280" to regular, "live:width:1280" to live)
        assertEquals(live, resolutionProfileFor(profiles, "width:1280", true))
        assertNull(resolutionProfileFor(profiles, "width:1280", true)?.mpv)
        assertEquals(regular, resolutionProfileFor(profiles, "width:1280", false))
        assertEquals(regular, resolutionProfileFor(withLiveResolutionProfile(profiles, "width:1280", null), "width:1280", true))
        assertNull(resolutionProfileFor(profiles, "width:1920", true))
        assertNull(resolutionProfileFor(profiles, null, true))
    }

    @Test fun migrationCollisionsAreDeterministicAndKeepLiveSeparate() {
        val standard = ResolutionVideoProfile("mpv")
        val cropped = ResolutionVideoProfile("vlc")
        val live = ResolutionVideoProfile("vlc", vlcDeinterlaceFilter = "yadif2x")
        val old = linkedMapOf("1080" to standard, "1920x800" to cropped, "live:1920x800" to live,
            "720x480" to standard, "720x576" to cropped, "higher" to standard)
        val expected = mapOf("width:1920" to standard, "live:width:1920" to live, "width:720" to cropped)
        assertEquals(expected, VideoResolutionClass.migrateProfiles(old))
        assertEquals(expected, VideoResolutionClass.migrateProfiles(old.entries.reversed().associate { it.toPair() }))
        assertEquals(cropped, VideoResolutionClass.migrateProfiles(old + ("width:1920" to cropped))["width:1920"])
        assertNull(VideoResolutionClass.normalizeProfileKey("live:width:0"))
    }

    @Test fun installedV1DataMigratesOnceWithoutDestroyingOriginals() {
        val storage = memoryPreferences()
        val raw = """{"1080":{"rendererKey":"mpv"},"1920x800":{"rendererKey":"vlc"}}"""
        storage.edit().putString("resolution_video_profiles_v1", raw).apply()
        val preferences = AppPreferences(storage)
        assertEquals(mapOf("width:1920" to ResolutionVideoProfile("mpv")), preferences.resolutionVideoProfiles)
        assertEquals(raw, storage.getString("resolution_video_profiles_v1", null))
        preferences.resolutionVideoProfiles = emptyMap()
        assertTrue(AppPreferences(storage).resolutionVideoProfiles.isEmpty())
    }

    @Test fun widthProfilesSurviveBackupAndLegacyBackupsMigrate() {
        val source = AppPreferences(memoryPreferences())
        val profile = ResolutionVideoProfile("vlc")
        source.resolutionVideoProfiles = mapOf("width:1920" to profile, "live:width:720" to profile, "width:2560" to profile)
        val target = AppPreferences(memoryPreferences())
        val backup = SettingsBackupManager(target, TokenStore(memoryPreferences(), target))
        backup.importJson(SettingsBackupManager(source, TokenStore(memoryPreferences(), source)).exportJson())
        assertEquals(source.resolutionVideoProfiles, target.resolutionVideoProfiles)
        backup.importJson("""{"schemaVersion":51,"exportedAtEpochMs":0,"resolutionVideoProfiles":{"720":{"rendererKey":"mpv"},"live:720x576":{"rendererKey":"vlc"}}}""")
        assertEquals(mapOf("width:1280" to ResolutionVideoProfile("mpv"), "live:width:720" to profile), target.resolutionVideoProfiles)
    }

    @Test fun savedResolutionRendererBeatsTheAppDefaultUnlessInvalid() {
        assertEquals("vlc", ResolutionVideoProfile("vlc").rendererOrDefault("mpv"))
        assertEquals("mpv", ResolutionVideoProfile("unknown").rendererOrDefault("mpv"))
        assertEquals("media3", TitleVideoSettings(rendererKey = "media3").rendererOrDefault(
            ResolutionVideoProfile("vlc").rendererOrDefault("mpv"),
        ))
    }

    @Test fun liveTvAndRegularVideoUseDifferentProfilesAtTheSameResolution() {
        val video = com.ambient.tv.ui.player.PlaybackMetadata(
            route = "movie", title = "Movie", source = "plex", mediaId = "1",
            sourceWidth = 1920, sourceHeight = 1080,
        )
        val live = video.copy(route = "channel", source = "tvheadend", isLive = true)
        assertEquals("width:1920", com.ambient.tv.ui.player.ResolutionVideoProfileStore.key(video))
        assertEquals("live:width:1920", com.ambient.tv.ui.player.ResolutionVideoProfileStore.key(live))
    }

    @Test fun resolutionProfilesSurviveBackupAndMissingOldFieldsStayEmpty() {
        val source = AppPreferences(memoryPreferences())
        source.resolutionVideoProfiles = mapOf("1080" to ResolutionVideoProfile(
            rendererKey = "mpv", mpv = MpvImageOptions(sharpening = "cas"), mpvOutput = "gpu", mpvDecoder = "software",
            audio = AudioSettings().update(AudioOptions(night = true, delayMs = 120)),
        ), "all" to ResolutionVideoProfile(rendererKey = "mpv", audio = AudioSettings().update(AudioOptions(night = true))), "live:1080" to ResolutionVideoProfile(
            rendererKey = "vlc", vlc = VlcImageOptions(softwareProcessing = true),
            audio = AudioSettings().update(AudioOptions(output = "pcm", channels = "2", delayMs = 80)),
            vlcDeinterlaceMode = "auto", vlcDeinterlaceFilter = "yadif",
        ))
        val raw = SettingsBackupManager(source, TokenStore(memoryPreferences(), source)).exportJson()
        val target = AppPreferences(memoryPreferences())
        SettingsBackupManager(target, TokenStore(memoryPreferences(), target)).importJson(raw)
        assertEquals(source.resolutionVideoProfiles, target.resolutionVideoProfiles)
        SettingsBackupManager(target, TokenStore(memoryPreferences(), target)).importJson(
            """{"schemaVersion":49,"exportedAtEpochMs":0}""",
        )
        assertTrue(target.resolutionVideoProfiles.isEmpty())
    }
    @Test fun outputAndDecoderFollowTheSameScopePriorityAsRenderer() {
        val global = PlayerVideoSettings(mpvOutput = "auto", mpvDecoder = "auto", vlcOutput = "android")
        val resolution = ResolutionVideoProfile(rendererKey = "mpv", mpvOutput = "gpu", mpvDecoder = "software")
        val title = TitleVideoSettings(rendererKey = "mpv", mpvOutput = "direct", mpvDecoder = "mediacodec")
        assertEquals("gpu", global.withResolution(resolution).mpvOutput)
        assertEquals("software", global.withResolution(resolution).mpvDecoder)
        val resolved = global.withResolution(resolution).withTitle(title)
        assertEquals("direct", resolved.mpvOutput)
        assertEquals("mediacodec", resolved.mpvDecoder)
        assertEquals("android", resolved.vlcOutput)
        assertEquals(global, global.withResolution(null).withTitle(null))
    }
}
