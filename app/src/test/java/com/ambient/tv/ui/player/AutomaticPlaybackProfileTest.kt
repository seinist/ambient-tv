package com.ambient.tv.ui.player

import com.ambient.tv.data.local.*
import org.junit.Assert.*
import org.junit.Test

class AutomaticPlaybackProfileTest {
    @Test fun automaticProfileRestoreAndSessionRestartDoNotPostPresetNotifications() {
        val prefs = AppPreferences(memoryPreferences())
        val audio = prefs.audioSettings.update(AudioOptions(night = true))
        val title = TitleVideoSettings(audio = audio, audioDelayMs = 120)
        val resolution = ResolutionVideoProfile("media3", audio = audio)
        val notifications = com.ambient.tv.notifications.AppNotifications.store
        val before = notifications.state.value
        val session = AudioSession(prefs, "media3", false).apply { titleScoped = true }
        restoreSavedPlaybackAudio(session, title, resolution)
        restoreSavedPlaybackAudio(session, title, resolution)
        assertTrue(session.options.night)
        assertEquals(120, session.options.delayMs)
        session.update(session.options.copy(delayMs = 210))
        val restarted = AudioSession(prefs, "media3", false)
        restarted.restoreFromSession(session)
        restoreSavedPlaybackAudio(restarted, title, resolution)
        assertEquals(210, restarted.options.delayMs)
        assertEquals(before, notifications.state.value)
    }

    @Test fun lateResolutionRefinesGlobalAudioWithoutWritingGlobals() {
        val prefs = AppPreferences(memoryPreferences())
        val defaults = prefs.audioSettings
        val global = defaults.update(defaults.current.copy(night = true))
        val resolution = defaults.update(defaults.current.copy(eqEnabled = true, delayMs = 90))
        val session = AudioSession(prefs, "media3", false)
        assertTrue(session.restoreAutomaticProfile(global))
        assertTrue(session.restoreAutomaticProfile(resolution))
        assertEquals(resolution.current, session.options)
        assertFalse(session.restoreAutomaticProfile(resolution))
        assertEquals(defaults, prefs.audioSettings)
    }

    @Test fun leavingProfiledResolutionRestoresGlobalAudioAndVideo() {
        val prefs = AppPreferences(memoryPreferences())
        val defaults = prefs.audioSettings
        val filtered = defaults.update(defaults.current.copy(eqEnabled = true, delayMs = 90))
        val session = AudioSession(prefs, "mpv", false)
        session.restoreAutomaticProfile(filtered)
        assertTrue(session.restoreAutomaticProfile(null))
        assertEquals(defaults.current, session.options)
        assertFalse(session.restoreAutomaticProfile(null))
        assertEquals(defaults, prefs.audioSettings)
        val defaultImage = MpvImageOptions()
        val filteredImage = defaultImage.copy(sharpening = "cas")
        val gate = AutomaticProfileValue(defaultImage)
        var image = defaultImage
        gate.apply(image, filteredImage) { image = it; image }
        gate.apply(image, defaultImage) { image = it; image }
        assertEquals(defaultImage, image)
    }

    @Test fun resettingGlobalsPreservesExactResolutionAndVideoProfiles() {
        val prefs = AppPreferences(memoryPreferences())
        val profile = ResolutionVideoProfile("vlc")
        val title = TitleVideoSettings(rendererKey = "mpv")
        prefs.resolutionVideoProfiles = mapOf("all" to profile, "1440" to profile, "live:720x576" to profile)
        prefs.titleVideoSettings = mapOf("youtube:test" to title)
        prefs.mpvImageOptions = MpvImageOptions(sharpening = "cas")
        prefs.audioSettings = prefs.audioSettings.update(AudioOptions(night = true))
        prefs.playbackBackendKey = "vlc"
        prefs.tvheadendPlaybackBackendKey = "mpv"
        prefs.mpvVideoOutputModeKey = "gpu"
        resetGlobalPlaybackProfile(prefs)
        // Tvheadend keeps its own default player.
        assertEquals("mpv", prefs.tvheadendPlaybackBackendKey)
        assertEquals(mapOf("width:2560" to profile, "live:width:720" to profile), prefs.resolutionVideoProfiles)
        assertEquals(mapOf("youtube:test" to title), prefs.titleVideoSettings)
        assertEquals(MpvImageOptions(), prefs.mpvImageOptions)
        assertEquals(AudioSettings(), prefs.audioSettings)
        assertEquals("auto", prefs.playbackBackendKey)
        assertEquals("direct", prefs.mpvVideoOutputModeKey)
    }

    @Test fun manualAudioAndManualLoadBeatLateResolution() {
        val prefs = AppPreferences(memoryPreferences())
        val defaults = prefs.audioSettings
        val profile = defaults.update(defaults.current.copy(night = true))
        val session = AudioSession(prefs, "vlc", false)
        session.restoreAutomaticProfile(profile)
        session.update(session.options.copy(delayMs = 210))
        assertFalse(session.restoreAutomaticProfile(defaults))
        assertEquals(210, session.options.delayMs)
        session.restoreProfile(defaults)
        assertFalse(session.restoreAutomaticProfile(profile))
        assertEquals(defaults.current, session.options)
    }

    @Test fun outputRestartPreservesAutomaticRefinementAndManualEdits() {
        val prefs = AppPreferences(memoryPreferences())
        val global = prefs.audioSettings.update(AudioOptions(night = true))
        val resolution = prefs.audioSettings.update(AudioOptions(eqEnabled = true))
        val old = AudioSession(prefs, "mpv", false)
        old.restoreAutomaticProfile(global)
        val restarted = AudioSession(prefs, "mpv", false)
        restarted.restoreFromSession(old)
        assertTrue(restarted.restoreAutomaticProfile(resolution))
        restarted.update(restarted.options.copy(delayMs = 180))
        val secondRestart = AudioSession(prefs, "mpv", false)
        secondRestart.restoreFromSession(restarted)
        assertFalse(secondRestart.restoreAutomaticProfile(global))
        assertEquals(180, secondRestart.options.delayMs)
    }

    @Test fun lateVideoDefaultsRespectEditsAndNormalizeAppliedValues() {
        val gate = AutomaticProfileValue("global")
        var actual = "global"
        gate.apply(actual, "global") { error("Unchanged profiles must not restart the renderer") }
        gate.apply(actual, "resolution") { actual = it; actual }
        assertEquals("resolution", actual)
        // Same gate survives a fullscreen remount/output restart.
        actual = "manual"
        gate.apply(actual, "title") { actual = it; actual }
        assertEquals("manual", actual)
        val normalized = AutomaticProfileValue(0)
        var strength = 0
        normalized.apply(strength, 150) { strength = it.coerceAtMost(100); strength }
        normalized.apply(strength, 50) { strength = it; strength }
        assertEquals(50, strength)
    }

    @Test fun rendererPriorityAndUnavailableBackendFallback() {
        val resolution = ResolutionVideoProfile("vlc")
        val title = TitleVideoSettings(rendererKey = "mpv")
        assertEquals("mpv", savedPlaybackRenderer("media3", title, resolution, true))
        assertEquals("media3", savedPlaybackRenderer("media3", title, resolution, false))
        assertEquals("vlc", savedPlaybackRenderer("media3", null, resolution, true))
        assertEquals("media3", savedPlaybackRenderer("media3", null, null, true))
        assertEquals("vlc", savedPlaybackRenderer("media3", TitleVideoSettings(rendererKey = "invalid"), resolution, true))
    }
}
