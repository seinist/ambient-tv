package com.ambient.tv.ui.tvheadend

import com.ambient.tv.data.local.*
import com.ambient.tv.ui.player.*
import com.ambient.tv.ui.player.backend.PlaybackBackend
import com.ambient.tv.ui.player.backend.PlaybackBackendResolver
import org.junit.Assert.*
import org.junit.Test

class LiveRendererSelectionTest {
    private val profiles = mapOf("width:1920" to ResolutionVideoProfile("vlc"),
        "live:width:1920" to ResolutionVideoProfile("mpv"))
    private fun choose(channel: TitleVideoSettings? = null, live: Boolean = true,
        manual: String? = null, recovery: String? = null, dv: Boolean = false,
        available: Boolean = true, width: String? = "width:1920", default: String = "media3") =
        selectLiveRenderer(default, channel, profiles, width, live, manual, recovery, dv, available)

    @Test fun channelOverridesLiveWidthWhichOverridesGeneralWidth() {
        assertEquals(LiveRendererSelection("mpv", LiveRendererOrigin.LIVE_PRESET), choose())
        assertEquals(LiveRendererSelection("vlc", LiveRendererOrigin.VIDEO_PRESET), choose(live = false))
        assertEquals(LiveRendererSelection("media3", LiveRendererOrigin.CHANNEL),
            choose(TitleVideoSettings(rendererKey = "media3")))
        assertEquals(LiveRendererSelection("media3", LiveRendererOrigin.DEFAULT), choose(width = null))
    }

    @Test fun liveAutoStaysOnMedia3WhenWidthArrivesAndPresetsRequestMpv() {
        val before = choose(width = null, default = "auto")
        val after = choose(default = "auto")
        assertEquals(LiveRendererSelection("auto", LiveRendererOrigin.DEFAULT), before)
        assertEquals(before, after)
        assertEquals(PlaybackBackend.MEDIA3, PlaybackBackendResolver.resolve(after.key))
        assertEquals("mpv", choose(TitleVideoSettings(rendererKey = "mpv"), default = "auto").key)
        assertEquals("mpv", choose(manual = "mpv", default = "auto").key)
        assertEquals(LiveRendererOrigin.RECOVERY, choose(recovery = "mpv", default = "auto").origin)
        // Recorded videos retain their existing resolution-preset renderer selection.
        assertEquals("vlc", choose(live = false, default = "auto").key)
        assertEquals("auto", selectLiveRenderer("auto", null,
            mapOf("width:1920" to ResolutionVideoProfile("mpv")), "width:1920", true).key)
    }

    @Test fun explicitAutoSurvivesAnMpvPreset() {
        val chosen = choose(TitleVideoSettings(rendererKey = "auto"))
        assertEquals("auto", chosen.key)
        assertEquals(LiveRendererOrigin.CHANNEL, chosen.origin)
        assertEquals(PlaybackBackend.MEDIA3, PlaybackBackendResolver.resolve(chosen.key))
    }

    @Test fun recoveryIsTemporaryAndDoesNotRewriteTheChannelChoice() {
        val saved = TitleVideoSettings(rendererKey = "auto")
        assertEquals(LiveRendererOrigin.RECOVERY, choose(saved, manual = "vlc", recovery = "mpv").origin)
        assertEquals("mpv", choose(saved, recovery = "mpv").key)
        assertEquals("auto", choose(saved).key)
        assertEquals("vlc", choose(saved, manual = "vlc").key)
    }

    @Test fun lateDolbyVisionOverridesEveryUnsafeSelectionAtTheSameWidth() {
        assertEquals("mpv", choose().key)
        assertEquals(LiveRendererSelection("media3", LiveRendererOrigin.DOLBY_VISION), choose(dv = true))
        for (engine in listOf("mpv", "vlc")) {
            assertEquals("media3", choose(TitleVideoSettings(rendererKey = engine), dv = true).key)
            assertEquals("media3", choose(manual = engine, dv = true).key)
            assertEquals("media3", choose(recovery = engine, dv = true).key)
        }
    }

    @Test fun unavailableMpvHasAnExplicitReason() {
        assertEquals(LiveRendererSelection("media3", LiveRendererOrigin.UNAVAILABLE), choose(available = false))
    }

    private fun metadata(server: String? = "server-a", channel: String = "channel-1") =
        PlaybackMetadata(route = "live/$channel", title = "Programme", source = "tvheadend",
            mediaId = channel, isLive = true, liveTvSourceId = server)

    @Test fun channelIdentityIsStableAndIsolatesServersAndChannels() {
        val first = TitleVideoSettingsStore.key(metadata())
        assertEquals(first, TitleVideoSettingsStore.key(metadata().copy(title = "Next programme")))
        assertNotEquals(first, TitleVideoSettingsStore.key(metadata("server-b")))
        assertNotEquals(first, TitleVideoSettingsStore.key(metadata(channel = "channel-2")))
        assertNotEquals(TitleVideoSettingsStore.channelKey("a:b", "c"),
            TitleVideoSettingsStore.channelKey("a", "b:c"))
        assertNull(TitleVideoSettingsStore.key(metadata(null)))
        assertNull(TitleVideoSettingsStore.key(metadata().copy(isTrailer = true)))
        assertNull(TitleVideoSettingsStore.key(metadata().copy(source = "shared")))
        assertEquals("tvheadend:channel-1", TitleVideoSettingsStore.key(metadata().copy(isLive = false)))
    }

    @Test fun channelAudioVideoAndAutoSurvivePreferenceRecreation() {
        val storage = memoryPreferences()
        val prefs = AppPreferences(storage)
        val key = TitleVideoSettingsStore.key(metadata())!!
        val saved = TitleVideoSettings(rendererKey = "auto", audioDelayMs = 175,
            audio = AudioSettings().update(AudioOptions(night = true)),
            mpv = MpvImageOptions(sharpening = "cas"), mpvOutput = "gpu",
            mpvDecoder = "mediacodec-copy", vlcDeinterlaceMode = "on",
            media3 = Media3ImageOptions(), subtitleDelayMs = 80)
        prefs.titleVideoSettings = TitleVideoSettings.put(prefs.titleVideoSettings, key, saved)
        val restored = AppPreferences(storage).titleVideoSettings
        assertEquals(saved, restored[key])
        assertNull(restored[TitleVideoSettingsStore.key(metadata("server-b"))])
        assertEquals("auto", choose(restored[key]).key)
    }

    @Test fun channelDelayRemainsSeparateFromWidthProfileAndSurvivesRendererSwitch() {
        val prefs = AppPreferences(memoryPreferences())
        val session = AudioSession(prefs, "media3", false)
        session.restoreAutomaticProfile(AudioSettings().update(AudioOptions(night = true)))
        TitleVideoSettingsStore.attachAudio(session, metadata(), 175)
        assertEquals(175, session.options.delayMs)
        val switched = AudioSession(prefs, "mpv", false)
        switched.restoreFromSession(session)
        assertEquals(175, switched.options.delayMs)
        assertEquals(AudioSettings(), prefs.audioSettings)
    }
}
