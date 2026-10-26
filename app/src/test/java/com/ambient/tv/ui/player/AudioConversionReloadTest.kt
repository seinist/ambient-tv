package com.ambient.tv.ui.player

import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.data.local.AudioOptions
import com.ambient.tv.data.local.memoryPreferences
import org.junit.Assert.*
import org.junit.Test

class AudioConversionReloadTest {
    @Test fun restartPreservesTrackAbsoluteTimelineAndIntentWithoutOldDecoderFailures() {
        val preferences = AppPreferences(memoryPreferences())
        val old = AudioSession(preferences, "media3", true).apply { titleScoped = true }
        old.update(old.options.copy(conversionLocation = "server", localAc3 = true, pcmChannelLimit = 2, delayMs = 125))
        old.forceStereoPcm = true
        old.localEncoderFailed = true
        val restart = old.conversionRestart(42L, 12_000L, 90_000L, true)
        old.release()
        val next = AudioSession(preferences, "media3", true)
        restart.restore(next)
        assertEquals(42L, restart.streamId)
        assertEquals(102_000L, restart.absolutePositionMs)
        assertTrue(restart.wasServerConverted)
        assertEquals(old.options, next.options)
        assertFalse(next.forceStereoPcm)
        assertFalse(next.localEncoderFailed)
        assertFalse(next.acceptsAutomaticProfile)
        assertEquals("ac3", serverAudioTarget(next.options, AudioRouteCapabilities(setOf("ac3"), 2), 6, true))
        next.update(next.options.copy(channels = "stereo"))
        assertEquals("aac", serverAudioTarget(next.options, AudioRouteCapabilities(setOf("ac3"), 2), 6, true))
        assertTrue(restart.settings.currentForRoute("tv").localAc3)
    }

    @Test fun serverTransportChangesReloadButDspAndLocalEditsDoNot() {
        val old = AudioOptions(conversionLocation = "server")
        for (next in listOf(old.copy(localAc3 = true), old.copy(pcmChannelLimit = 2),
            old.copy(output = "pcm"), old.copy(channels = "stereo"),
            old.copy(allowedCodecs = emptyList()), old.copy(localFallback = "off"),
            old.copy(conversionLocation = "local"))) {
            assertTrue(audioConversionRequiresReload(old, next))
            assertTrue(audioConversionRequiresReload(next, old))
        }
        assertFalse(audioConversionRequiresReload(old, old.copy(delayMs = 100, night = true)))
        val local = old.copy(conversionLocation = "local")
        assertFalse(audioConversionRequiresReload(local, local.copy(localAc3 = true, pcmChannelLimit = 2)))
    }

    @Test fun callbackReceivesUpdatedProfileAndTitleDelayAndDeclinedReloadAppliesLocally() {
        val session = AudioSession(AppPreferences(memoryPreferences()), "media3", true).apply { titleScoped = true }
        session.update(session.options.copy(delayMs = 125))
        var captured: AudioOptions? = null
        var localApplies = 0
        session.apply = { _, _ -> localApplies++ }
        session.conversionLocationChanged = { changed -> captured = changed.options; true }
        session.update(session.options.copy(conversionLocation = "server", localAc3 = true, pcmChannelLimit = 2))
        assertEquals(session.options, captured)
        assertEquals(125, captured?.delayMs)
        assertEquals(true, captured?.localAc3)
        assertEquals(0, localApplies)
        session.conversionLocationChanged = { false }
        session.update(session.options.copy(localAc3 = false))
        assertEquals(1, localApplies)
    }

    @Test fun contentRestrictionSurvivesRouteRefreshAndInvalidatesThePcmMemo() {
        val session = AudioSession(AppPreferences(memoryPreferences()), "media3", true)
        session.sourceCodec = "aac"
        session.sourceChannels = 6
        session.updateRoute(AudioRouteCapabilities(setOf("ac3"), 2, true))
        session.update(session.options.copy(localAc3 = true, pcmChannelLimit = 2))
        assertEquals(AudioOutputMode.LOCAL_AC3, session.outputPlan().mode)
        assertEquals("5.1", session.pcmOptionsForAudioThread().channels)
        session.updateMpvAllowed(false)
        session.updateRoute(AudioRouteCapabilities(setOf("ac3"), 2, true))
        assertEquals(AudioOutputMode.PCM, session.outputPlan().mode)
        assertFalse(session.outputPlan().needsMpv)
        assertEquals("stereo", session.pcmOptionsForAudioThread().channels)
        assertFalse(session.effectiveRoute.mpvAvailable)
    }
}
