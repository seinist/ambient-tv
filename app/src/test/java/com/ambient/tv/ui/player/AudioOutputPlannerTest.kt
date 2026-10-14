package com.ambient.tv.ui.player

import com.ambient.tv.data.local.AudioOptions
import com.ambient.tv.ui.player.backend.MPV_LOCAL_AC3_FILTER
import com.ambient.tv.ui.player.backend.mpvAudioChannels
import com.ambient.tv.ui.player.backend.mpvAudioGraph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioOutputPlannerTest {
    @Test fun defaultRecoveryUsesAc3ForArcButPcmForMultichannelHdmi() {
        val options = AudioOptions()
        val arc = AudioRouteCapabilities(setOf("ac3"), 2, true)
        assertEquals(AudioOutputMode.LOCAL_AC3, planAudioOutput(options, "truehd", arc, "media3", false).mode)
        assertTrue(planAudioOutput(options, "truehd", arc, "media3", false).needsMpv)
        assertEquals(AudioOutputMode.PCM, planAudioOutput(options, "truehd", arc.copy(pcmChannels = 8), "mpv", false).mode)
    }

    @Test fun pcmOrOffPreferenceNeverEnablesAnEncoder() {
        val arc = AudioRouteCapabilities(setOf("ac3"), 2, true)
        for (mode in listOf("pcm", "off")) {
            val plan = planAudioOutput(AudioOptions(localFallback = mode), "truehd", arc, "media3", false, media3CanDecode = false)
            assertEquals(AudioOutputMode.PCM, plan.mode)
            assertEquals(mode == "pcm", plan.needsMpv)
        }
        assertEquals(AudioOutputMode.PCM, planAudioOutput(AudioOptions(output = "pcm"), "truehd", arc, "mpv", true).mode)
    }

    @Test fun encoderRequiresConfirmedAndAllowedAc3AndDoesNotEncodeStereo() {
        val arc = AudioRouteCapabilities(setOf("ac3"), 2, true)
        assertEquals(AudioOutputMode.PCM, planAudioOutput(AudioOptions(allowedCodecs = emptyList()), "truehd", arc, "mpv", false).mode)
        assertEquals(AudioOutputMode.PCM, planAudioOutput(AudioOptions(), "truehd", arc.copy(passthrough = emptySet()), "mpv", false).mode)
        assertEquals(AudioOutputMode.PCM, planAudioOutput(AudioOptions(), "flac", arc, "mpv", false, sourceChannels = 2).mode)
        assertEquals(AudioOutputMode.LOCAL_AC3, planAudioOutput(AudioOptions(), "flac", arc, "mpv", false, sourceChannels = 6).mode)
        assertEquals(AudioOutputMode.PCM, planAudioOutput(AudioOptions(), "truehd", arc, "mpv", false, rejectedCodecs = setOf("ac3")).mode)
    }

    @Test fun autoBackendCanRequestLocalDecoderWithoutPlatformSupport() {
        val plan = planAudioOutput(AudioOptions(), "opus", AudioRouteCapabilities(mpvAvailable = true), "auto", false, media3CanDecode = false)
        assertTrue(plan.needsMpv)
        assertEquals(AudioOutputMode.PCM, plan.mode)
    }

    @Test fun pcmFallbackDoesNotExtractLossyDtsCore() {
        val route = AudioRouteCapabilities(setOf("dts"), 2, true)
        assertEquals(AudioOutputMode.PCM, planAudioOutput(AudioOptions(output = "bitstream", localFallback = "pcm"), "dts-hd", route, "mpv", true).mode)
        assertEquals(AudioOutputMode.PCM, planAudioOutput(AudioOptions(output = "auto"), "dts-hd", route.copy(pcmChannels = 8), "mpv", true).mode)
    }

    private val hdmi = AudioRouteCapabilities(
        passthrough = setOf("ac3", "eac3", "dts", "truehd"),
        pcmChannels = 8,
        mpvAvailable = true,
    )

    @Test fun dtsCoreWhenHdIsNotConfirmed() {
        val plan = planAudioOutput(
            AudioOptions(output = "bitstream", allowedCodecs = listOf("ac3", "eac3", "dts", "dts-hd", "truehd")),
            "dts-hd",
            AudioRouteCapabilities(passthrough = setOf("ac3", "dts"), pcmChannels = 6, mpvAvailable = true),
            "mpv",
            true,
        )
        assertEquals(AudioOutputMode.DTS_CORE, plan.mode)
        assertTrue(plan.spdifCodecs.contains("dts"))
        assertFalse(plan.spdifCodecs.contains("dts-hd"))
        assertFalse(plan.applyDsp)
    }

    @Test fun trueHdPassthroughSkipsEncoder() {
        val plan = planAudioOutput(AudioOptions(output = "auto", localAc3 = true), "truehd", hdmi, "vlc", true)
        assertEquals(AudioOutputMode.PASSTHROUGH, plan.mode)
        assertTrue(plan.spdifCodecs.contains("truehd"))
        assertFalse(plan.needsMpv)
        assertFalse(plan.heavyAudio)
    }

    @Test fun localAc3StaysOnDeviceAndKeepsDspSeparateFromEncoder() {
        val options = AudioOptions(output = "bitstream", localAc3 = true, eqEnabled = true, dialogue = 2)
        val plan = planAudioOutput(options, "truehd", AudioRouteCapabilities(setOf("ac3"), 6, true), "vlc", true)
        assertEquals(AudioOutputMode.LOCAL_AC3, plan.mode)
        assertTrue(plan.needsMpv)
        assertTrue(plan.spdifCodecs.isEmpty())
        assertTrue(plan.applyDsp)
        val graph = mpvAudioGraph(options.copy(channels = "5.1", upmix = false))
        assertFalse(graph.contains("lavcac3enc"))
        assertFalse(MPV_LOCAL_AC3_FILTER.contains("lavfi"))
        assertTrue(plan.encoded && plan.applyDsp)
        assertTrue(options.pcmOptions(true, encodedOutput = plan.encoded && !plan.applyDsp).eqEnabled)
    }

    @Test fun bitstreamWishKeepsEqWhenRouteFallsBackToPcm() {
        val options = AudioOptions(output = "bitstream", eqEnabled = true, night = true)
        val plan = planAudioOutput(options, "truehd", AudioRouteCapabilities(mpvAvailable = true), "mpv", true)
        assertEquals(AudioOutputMode.PCM, plan.mode)
        val applied = options.pcmOptions(true, encodedOutput = plan.encoded && !plan.applyDsp)
        assertTrue(applied.eqEnabled)
        assertTrue(applied.night)
    }

    @Test fun rejectedPassthroughCodecIsNotTriedAgain() {
        val route = AudioRouteCapabilities(setOf("ac3", "truehd"), 2, true)
        val options = AudioOptions(output = "auto", localAc3 = true)
        val blocked = planAudioOutput(options, "truehd", route, "mpv", true, rejectedCodecs = setOf("truehd"))
        assertEquals(AudioOutputMode.LOCAL_AC3, blocked.mode)
        val noAc3 = planAudioOutput(options, "ac3", route, "mpv", true, rejectedCodecs = setOf("ac-3"))
        assertEquals(AudioOutputMode.PCM, noAc3.mode)
        assertTrue(noAc3.spdifCodecs.isEmpty())
    }

    @Test fun hiddenCodecsAndChannelModesFollowTheRoute() {
        val stereo = AudioRouteCapabilities(pcmChannels = 2)
        assertTrue(visibleBitstreamCodecs(stereo).isEmpty())
        assertFalse(visibleChannelModes(stereo).contains("5.1"))
        assertEquals("stereo", clipChannelMode("7.1", 2))
        assertEquals("5.1", clipChannelMode("7.1", 6))
        assertTrue(visibleChannelModes(AudioRouteCapabilities(pcmChannels = 8)).contains("7.1"))
    }

    @Test fun media3DecodeMissAsksForMpvWithoutInventingBitstream() {
        val plan = planAudioOutput(AudioOptions(), "dts", AudioRouteCapabilities(mpvAvailable = true), "media3", true, media3CanDecode = false)
        assertEquals(AudioOutputMode.PCM, plan.mode)
        assertTrue(plan.needsMpv)
        assertTrue(plan.spdifCodecs.isEmpty())
    }

    @Test fun mpvAutoDoesNotForceAStereoDownmix() {
        assertEquals("auto-safe", mpvAudioChannels("auto", encoded = false, upmix = false, applyDsp = true))
        assertEquals("auto-safe", mpvAudioChannels("auto", encoded = true, upmix = true, applyDsp = false))
        assertEquals("5.1", mpvAudioChannels("auto", encoded = false, upmix = true, applyDsp = true))
        assertEquals("5.1", mpvAudioChannels("5.1", encoded = false, upmix = false, applyDsp = true))
        assertEquals("7.1", mpvAudioChannels("7.1", encoded = false, upmix = false, applyDsp = true))
        for (mode in listOf("stereo", "mono", "left", "right")) {
            assertEquals(mode, "stereo", mpvAudioChannels(mode, encoded = false, upmix = false, applyDsp = true))
        }
    }

}
