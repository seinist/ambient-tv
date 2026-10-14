package com.ambient.tv.ui.player

import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.data.local.AudioOptions
import com.ambient.tv.data.local.memoryPreferences
import org.junit.Assert.*
import org.junit.Test

class AudioOnlyOutputTest {
    private fun session(backend: String, audioOnly: Boolean) = AudioSession(
        AppPreferences(memoryPreferences()), backend, true, audioOnly,
    ).apply {
        route = AudioRouteCapabilities(setOf("ac3", "eac3", "dts", "dts-hd", "truehd"), 8, true)
        sourceCodec = "ac3"
        sourceChannels = 6
    }

    @Test fun audioOnlyOverridesEncodedProfilesWithoutOverwritingThem() {
        for (backend in listOf("mpv", "vlc", "media3")) {
            val session = session(backend, true)
            for (output in listOf("auto", "bitstream", "pcm")) {
                val requested = AudioOptions(output = output, localFallback = "auto", localAc3 = true, channels = "5.1")
                session.update(requested)
                assertEquals(requested.normalized(), session.options)
                assertEquals(AudioOutputMode.PCM, session.outputPlan().mode)
                assertFalse(session.bitstreamPermitted)
                assertEquals("5.1", session.outputPlan().channels)
                session.restoreProfile(session.settings)
                assertEquals(AudioOutputMode.PCM, session.outputPlan().mode)
            }
        }
    }

    @Test fun lateAudioDiscoveryReconfiguresOnceAndInvalidatesPcmMemo() {
        val session = session("mpv", false)
        session.update(AudioOptions(output = "bitstream"))
        assertEquals(AudioOutputMode.PASSTHROUGH, session.outputPlan().mode)
        session.pcmOptionsForAudioThread()
        var transitions = 0
        session.routeChanged = { before, after ->
            assertTrue(before.encoded)
            assertEquals(AudioOutputMode.PCM, after.mode)
            transitions++
        }
        session.updateAudioOnly(true)
        session.updateAudioOnly(true)
        assertEquals(1, transitions)
        assertEquals(session.optionsForPcm(), session.pcmOptionsForAudioThread())
        val restarted = session("mpv", false)
        restarted.restoreFromSession(session)
        assertTrue(restarted.audioOnly)
        assertEquals(AudioOutputMode.PCM, restarted.outputPlan().mode)
    }

    @Test fun videoRetainsBitstreamAndAudioOnlyNeverFallsBackToLocalAc3() {
        val video = session("vlc", false)
        video.update(AudioOptions(output = "bitstream"))
        assertEquals(AudioOutputMode.PASSTHROUGH, video.outputPlan().mode)
        val audio = session("vlc", true)
        audio.route = AudioRouteCapabilities(setOf("ac3"), 2, true)
        audio.sourceCodec = "dts-hd"
        audio.update(AudioOptions(output = "bitstream", localFallback = "auto", localAc3 = true))
        assertEquals(AudioOutputMode.PCM, audio.outputPlan().mode)
        assertFalse(audio.outputPlan().needsMpv)
    }
}
