package com.ambient.tv.ui.player

import android.media.AudioDeviceInfo
import android.media.AudioFormat
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.data.local.AudioOptions
import com.ambient.tv.data.local.memoryPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Multichannel/passthrough fixes: TV routes, forced channels, summaries and server targets. */
class AudioMultichannelTest {
    private val ac3 = AudioFormat.ENCODING_AC3
    private val eac3 = AudioFormat.ENCODING_E_AC3
    private fun speaker() = AudioRouteDevice(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, emptySet(), 2)
    private fun arc(vararg encodings: Int) = AudioRouteDevice(AudioDeviceInfo.TYPE_HDMI_ARC, encodings.toSet(), 2)
    private fun hdmi(channels: Int, vararg encodings: Int) = AudioRouteDevice(AudioDeviceInfo.TYPE_HDMI, encodings.toSet(), channels)
    private fun headphones() = AudioRouteDevice(AudioDeviceInfo.TYPE_WIRED_HEADPHONES, emptySet(), 2)

    // F3: passthrough on TVs

    @Test fun tvSpeakerWithDirectSupportAllowsPassthrough() {
        assertTrue(routeAcceptsEncoding(listOf(speaker()), ac3, television = true, routedDirectSupport = true))
        // A phone's speaker never accepts a bitstream, whatever Android answered.
        assertFalse(routeAcceptsEncoding(listOf(speaker()), ac3, television = false, routedDirectSupport = true))
    }

    @Test fun tvArcIsNotVetoedByBuiltInSpeakersBeforeApi33() {
        val devices = listOf(speaker(), arc(ac3, eac3))
        assertTrue(routeAcceptsEncoding(devices, ac3, television = true, routedDirectSupport = false))
        assertTrue(routeAcceptsEncoding(devices, AudioFormat.ENCODING_E_AC3_JOC, television = true))
        // ARC that does not advertise DTS stays unconfirmed.
        assertFalse(routeAcceptsEncoding(devices, AudioFormat.ENCODING_DTS, television = true))
        // Only the speaker: nothing digital to carry the bitstream.
        assertFalse(routeAcceptsEncoding(listOf(speaker()), ac3, television = true, routedDirectSupport = false))
    }

    @Test fun phoneBehaviourIsUnchanged() {
        assertFalse(routeAcceptsEncoding(listOf(speaker(), arc(ac3)), ac3))
        assertTrue(routeAcceptsEncoding(listOf(hdmi(8, ac3)), ac3))
        assertFalse(routeAcceptsEncoding(emptyList(), ac3, television = true, routedDirectSupport = true))
        // Phones keep preferring the headphone jack while HDMI is attached.
        assertEquals(listOf(headphones()), selectAudioRouteDevices(listOf(hdmi(8, ac3), headphones()), routingKnown = false))
    }

    @Test fun tvWithDigitalOutputIgnoresPermanentHeadphoneJack() {
        val selected = selectAudioRouteDevices(listOf(speaker(), arc(ac3), headphones()), routingKnown = false, television = true)
        assertFalse(selected.any { it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES })
        // Without a digital output the jack is the only signal and still counts.
        assertEquals(listOf(headphones()), selectAudioRouteDevices(listOf(speaker(), headphones()), routingKnown = false, television = true))
        // A known route (API 33+) is never second-guessed.
        assertEquals(listOf(headphones()), selectAudioRouteDevices(listOf(headphones()), routingKnown = true, television = true))
    }

    @Test fun hdmiWithStereoEdidStillPassesAc3ButMixesPcmToStereo() {
        val devices = listOf(hdmi(2, ac3, eac3))
        assertTrue(routeAcceptsEncoding(devices, ac3))
        assertEquals(2, routedPcmChannels(devices))
        val route = AudioRouteCapabilities(setOf("ac3", "eac3"), 2, true, "avr", listOf(AudioDeviceInfo.TYPE_HDMI))
        assertEquals(AudioOutputMode.PASSTHROUGH, planAudioOutput(AudioOptions(output = "auto"), "ac3", route, "mpv", true, sourceChannels = 6).mode)
        val aac = planAudioOutput(AudioOptions(output = "auto"), "aac", route, "mpv", true, sourceChannels = 6)
        assertEquals(AudioOutputMode.PCM, aac.mode)
        assertEquals("stereo", aac.channels)
    }

    // F5: Kanäle erzwingen

    @Test fun forceFiveOneRaisesPcmLayoutOnAStereoRoute() {
        val route = AudioRouteCapabilities(emptySet(), 2, true, "avr", listOf(AudioDeviceInfo.TYPE_HDMI))
        val forced = AudioOptions(output = "pcm", forceChannels = 6)
        val plan = planAudioOutput(forced, "aac", route, "mpv", false, sourceChannels = 6)
        assertEquals(AudioOutputMode.PCM, plan.mode)
        assertEquals("5.1", plan.channels)
        // Never invents channels: a stereo source stays as it is.
        assertEquals("auto", planAudioOutput(forced, "aac", route, "mpv", false, sourceChannels = 2).channels)
        // An explicit 5.1 mix is no longer clipped to the reported stereo.
        assertEquals("5.1", planAudioOutput(AudioOptions(output = "pcm", channels = "5.1", forceChannels = 6), "aac", route, "mpv", false, sourceChannels = 6).channels)
        // Without the setting the old behaviour applies.
        assertEquals("stereo", planAudioOutput(AudioOptions(output = "pcm"), "aac", route, "mpv", false, sourceChannels = 6).channels)
        // The PCM limit still wins over forcing.
        assertEquals("stereo", planAudioOutput(AudioOptions(output = "pcm", forceChannels = 8, pcmChannelLimit = 2), "aac", route, "mpv", false, sourceChannels = 8).channels)
    }

    @Test fun forcedBitstreamOffersCommonCodecsWithoutConfirmation() {
        val route = AudioRouteCapabilities(emptySet(), 2, true)
        val forced = route.forcedBy(AudioOptions(output = "bitstream", forceChannels = 6))
        assertEquals(setOf("ac3", "eac3", "dts"), forced.passthrough)
        assertEquals(6, forced.pcmChannels)
        assertTrue(visibleChannelModes(forced).contains("5.1"))
        assertFalse(visibleChannelModes(forced).contains("7.1"))
        assertTrue(visibleChannelModes(route.forcedBy(AudioOptions(forceChannels = 8))).contains("7.1"))
        // Lossless formats are not guessed.
        assertFalse("truehd" in forced.passthrough)
        val plan = planAudioOutput(AudioOptions(output = "bitstream", forceChannels = 6), "eac3", route, "mpv", true, sourceChannels = 6)
        assertEquals(AudioOutputMode.PASSTHROUGH, plan.mode)
        // Rejected by the output in this playback: back to PCM.
        val rejected = planAudioOutput(AudioOptions(output = "bitstream", forceChannels = 6), "eac3", route, "mpv", true,
            rejectedCodecs = setOf("eac3"), sourceChannels = 6)
        assertEquals(AudioOutputMode.PCM, rejected.mode)
    }

    @Test fun forceFailureFallsBackToTheReportedRouteForThisSession() {
        val preferences = AppPreferences(memoryPreferences())
        val session = AudioSession(preferences, "mpv", sourcePassthrough = false)
        session.update(session.options.copy(output = "pcm", forceChannels = 6))
        session.sourceChannels = 6
        session.sourceCodec = "aac"
        session.updateRoute(AudioRouteCapabilities(emptySet(), 2, true, "tv"))
        assertEquals("5.1", session.outputPlan().channels)
        assertEquals(6, session.outputPcmChannels)
        session.forcedChannelsFailed = true
        assertEquals("stereo", session.outputPlan().channels)
        assertEquals(2, session.outputPcmChannels)
        assertEquals(6, session.options.forceChannels)
    }

    @Test fun outputFailuresDoNotFollowToAnotherRenderer() {
        val preferences = AppPreferences(memoryPreferences())
        val failed = AudioSession(preferences, "mpv", sourcePassthrough = false).apply {
            forceStereoPcm = true
            forcedChannelsFailed = true
        }
        val sameRenderer = AudioSession(preferences, "mpv", sourcePassthrough = false).apply { restoreFromSession(failed) }
        assertTrue(sameRenderer.forceStereoPcm)
        assertTrue(sameRenderer.forcedChannelsFailed)
        val otherRenderer = AudioSession(preferences, "media3", sourcePassthrough = false).apply { restoreFromSession(failed) }
        assertFalse(otherRenderer.forceStereoPcm)
        assertFalse(otherRenderer.forcedChannelsFailed)
    }

    // F2: plain summary

    @Test fun summaryNamesOutputChannelsAndBitstream() {
        val speakers = AudioRouteCapabilities(setOf("ac3"), 2, false, "tv", listOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))
        val off = audioRouteSummary(speakers, AudioOptions(), bitstreamPermitted = false, sourceChannels = 6)
        assertEquals(AudioOutputKind.SPEAKER, off.kind)
        assertEquals(2, off.channels)
        assertFalse(off.bitstream)
        assertTrue(off.stereoMixHint)
        assertFalse(audioRouteSummary(speakers, AudioOptions(), false, sourceChannels = 2).stereoMixHint)
        val on = audioRouteSummary(speakers, AudioOptions(), bitstreamPermitted = true, sourceChannels = 6)
        assertTrue(on.bitstream)
        assertFalse(on.stereoMixHint)
        // Nothing confirmed: no hint, passthrough would not help.
        assertFalse(audioRouteSummary(speakers.copy(passthrough = emptySet()), AudioOptions(), false, 6).stereoMixHint)
        val forced = audioRouteSummary(speakers, AudioOptions(forceChannels = 6), false, 6)
        assertEquals(6, forced.channels)
        assertEquals(6, forced.forcedChannels)
        assertEquals(0, audioRouteSummary(speakers, AudioOptions(forceChannels = 6), false, 6, forcedChannelsFailed = true).forcedChannels)
        assertEquals(AudioOutputKind.HDMI_EARC, audioOutputKind(listOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_HDMI_EARC)))
        assertEquals(AudioOutputKind.HDMI_ARC, audioOutputKind(listOf(AudioDeviceInfo.TYPE_HDMI_ARC)))
        assertEquals(null, audioOutputKind(emptyList()))
    }

    // F1: plan log

    @Test fun planLogNamesSwitchesAndDevices() {
        val route = AudioRouteCapabilities(setOf("ac3"), 2, true, "tv", listOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_HDMI_ARC))
        val options = AudioOptions(output = "auto", forceChannels = 6)
        val plan = planAudioOutput(options, "eac3", route, "mpv", false, sourceChannels = 6)
        val line = audioPlanLogLine("mpv", false, options, "auto", route, "eac3", 6, plan)
        assertTrue(line, line.startsWith("Audio plan backend=mpv passthroughSwitch=off output=auto profile=auto/tv"))
        assertTrue(line, "forceChannels=6" in line)
        assertTrue(line, "confirmed=ac3" in line)
        assertTrue(line, "devices=BUILTIN_SPEAKER,HDMI_ARC" in line)
        assertTrue(line, "-> mode=PCM" in line)
        assertTrue(audioPlanLogLine("mpv", true, options, "auto", route, null, null, plan, forceFailed = true, forceStereo = true)
            .let { "forceChannels=6(failed)" in it && it.endsWith(" forceStereo") && "passthroughSwitch=on" in it })
    }

    // F6: server conversion

    @Test fun serverConversionFollowsTheOutputWidth() {
        val stereo = AudioRouteCapabilities(emptySet(), 2, false)
        val surround = AudioRouteCapabilities(emptySet(), 8, false)
        assertEquals(2, serverAudioChannels(AudioOptions(), stereo, 6, "aac"))
        assertEquals(6, serverAudioChannels(AudioOptions(), surround, 6, "aac"))
        assertEquals(8, serverAudioChannels(AudioOptions(), surround, null, "aac"))
        assertEquals(6, serverAudioChannels(AudioOptions(forceChannels = 6), stereo, 8, "aac"))
        assertEquals(2, serverAudioChannels(AudioOptions(pcmChannelLimit = 2), surround, 8, "aac"))
        assertEquals(6, serverAudioChannels(AudioOptions(), stereo, 8, "ac3"))
        val query = com.ambient.tv.data.remote.serverAudioConversionQuery("aac", false, 2, null, 6)
        assertEquals("6", query["TranscodingMaxAudioChannels"])
        assertEquals("384000", query["AudioBitrate"])
        val eac3 = com.ambient.tv.data.remote.serverAudioConversionQuery("eac3", false, 2, null, 8)
        assertEquals("eac3", eac3["AudioCodec"])
        assertEquals("6", eac3["TranscodingMaxAudioChannels"])
    }

    @Test fun serverPrefersEac3WhenPcmWouldDownmixAndTheOutputConfirmsIt() {
        AudioCodecRejects.clear()
        val arc = AudioRouteCapabilities(setOf("eac3"), 2, false, "tv")
        assertEquals("eac3", serverAudioTarget(AudioOptions(output = "source"), arc, 6, sourcePassthrough = true))
        // Passthrough switched off: stays PCM.
        assertEquals("aac", serverAudioTarget(AudioOptions(output = "source"), arc, 6, sourcePassthrough = false))
        // Wide PCM output: no need to encode.
        assertEquals("aac", serverAudioTarget(AudioOptions(output = "source"), arc.copy(pcmChannels = 8), 6, true))
        // Stereo source: nothing to keep.
        assertEquals("aac", serverAudioTarget(AudioOptions(output = "source"), arc, 2, true))
        // Not confirmed by the output.
        assertEquals("aac", serverAudioTarget(AudioOptions(output = "source"), arc.copy(passthrough = emptySet()), 6, true))
        assertTrue(serverAudioTargetIsEncoded("eac3"))
        assertFalse(serverAudioTargetIsEncoded("aac"))
    }
}
