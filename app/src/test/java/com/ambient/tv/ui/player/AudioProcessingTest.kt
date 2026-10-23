package com.ambient.tv.ui.player

import com.ambient.tv.data.local.AudioOptions
import com.ambient.tv.data.local.AudioTrackPreference
import com.ambient.tv.ui.player.backend.mpvAudioGraph
import kotlin.math.*
import org.junit.Assert.*
import org.junit.Test

class AudioProcessingTest {
    @Test fun defaultDspIsTransparent() {
        val frame = floatArrayOf(0.99f, -0.7f)
        PcmAudioDsp(48000, 2).process(frame)
        assertArrayEquals(floatArrayOf(0.99f, -0.7f), frame, 0f)
    }

    @Test fun nightModeReducesSustainedLoudnessAndLimitsPeaks() {
        val dsp = PcmAudioDsp(48000, 2)
        dsp.configure(AudioOptions(night = true))
        val frame = FloatArray(2)
        repeat(48000) {
            frame[0] = 0.98f; frame[1] = -0.98f
            dsp.process(frame)
            assertTrue(abs(frame[0]) <= 0.950001f)
            assertEquals(-frame[0], frame[1], 0.000001f)
        }
        assertTrue(frame[0] < 0.3f)
    }

    @Test fun normalizationDoesNotAmplifySilenceOrExceedGainCap() {
        val dsp = PcmAudioDsp(48000, 2)
        dsp.configure(AudioOptions(normalize = true))
        val frame = FloatArray(2)
        repeat(48000) { dsp.process(frame) }
        assertArrayEquals(floatArrayOf(0f, 0f), frame, 0f)
        repeat(96000) {
            frame[0] = 0.03f; frame[1] = 0.03f; dsp.process(frame)
            assertTrue(frame[0] <= 0.060001f)
        }
        assertTrue(frame[0] > 0.05f)
    }

    @Test fun eqBoostIsFrequencySelectiveAndStable() {
        fun rms(hz: Double): Double {
            val dsp = PcmAudioDsp(48000, 1)
            dsp.configure(AudioOptions(eqEnabled = true, eqDb = List(10) { if (it == 4) 6 else 0 }))
            val f = FloatArray(1); var sum = 0.0
            repeat(48000) { n ->
                f[0] = (0.05 * sin(2 * PI * hz * n / 48000)).toFloat(); dsp.process(f)
                assertTrue(f[0].isFinite())
                if (n > 24000) sum += f[0] * f[0]
            }
            return sqrt(sum / 23999)
        }
        assertTrue(rms(1000.0) > rms(100.0) * 1.7)
    }

    @Test fun lowSampleRatesKeepEqFinite() {
        val dsp = PcmAudioDsp(8000, 1)
        dsp.configure(AudioOptions(eqEnabled = true, eqDb = List(10) { 12 }, dialogue = 6))
        val frame = FloatArray(1)
        repeat(8000) { frame[0] = 0.7f; dsp.process(frame); assertTrue(frame[0].isFinite() && abs(frame[0]) <= 0.95001f) }
    }

    @Test fun downmixKeepsCenterAndExcludesLfe() {
        val output = FloatArray(2)
        stereoDownmix(floatArrayOf(0f, 0f, 1f, 0f, 0f, 0f), "stereo", output)
        assertTrue(output[0] > 0f); assertEquals(output[0], output[1], 0f)
        stereoDownmix(floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f), "stereo", output)
        assertArrayEquals(floatArrayOf(0f, 0f), output, 0f)
    }

    @Test fun namedLayoutHandlesSideSurroundAndReorderedCenter() {
        val matrix = StereoDownmixMatrix(listOf(PcmSpeaker.SR, PcmSpeaker.FC, PcmSpeaker.FL, PcmSpeaker.LFE, PcmSpeaker.FR, PcmSpeaker.SL))
        val out = FloatArray(2)
        matrix.mix(floatArrayOf(0f, 1f, 0f, 0f, 0f, 0f), "stereo", out)
        assertEquals(0.2928932f, out[0], 0.00001f)
        assertEquals(out[0], out[1], 0f)
        matrix.mix(floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f), "stereo", out)
        assertEquals(0f, out[0], 0f)
        assertEquals(0.2928932f, out[1], 0.00001f)
    }

    @Test fun downmixHeadroomCoversAllCorrelatedChannelsAndCenterBoost() {
        for (count in 3..8) for (boost in listOf(0, 6)) {
            val matrix = StereoDownmixMatrix(pcmSpeakerLayout(count), boost)
            val out = FloatArray(2)
            matrix.mix(FloatArray(count) { 1f }, "stereo", out)
            assertTrue(out.all { it <= 1.000001f })
        }
    }

    @Test fun keepVolumePreservesQuietCenterAndLimitsCombinedPeaksWithoutChangingBalance() {
        val matrix = StereoDownmixMatrix(pcmSpeakerLayout(6))
        val limiter = StereoPeakLimiter(48000)
        val out = FloatArray(2)
        matrix.mix(floatArrayOf(0f, 0f, 0.1f, 0f, 0f, 0f), "stereo", out, true)
        limiter.process(out)
        assertEquals(0.07071068f, out[0], 0.000001f)
        matrix.mix(floatArrayOf(1f, 0.5f, 0f, 0f, 1f, 0.5f), "stereo", out, true)
        limiter.process(out)
        assertEquals(0.95f, out[0], 0.000001f)
        assertEquals(out[0] * 0.5f, out[1], 0.000001f)
        out[0] = 0.1f; out[1] = 0.05f
        limiter.process(out)
        assertTrue(out[0] < 0.1f) // Release continues across frames/buffers.
        assertEquals(out[0] * 0.5f, out[1], 0.000001f)
        repeat(48000) { out[0] = 0.1f; out[1] = 0.05f; limiter.process(out) }
        assertEquals(0.1f, out[0], 0.000001f)
    }

    @Test fun keepVolumeDoesNotBoostAnExistingStereoSource() {
        val out = FloatArray(2)
        stereoDownmix(floatArrayOf(0.5f, -0.25f), "stereo", out, maintainVolume = true)
        assertArrayEquals(floatArrayOf(0.5f, -0.25f), out, 0f)
    }

    @Test fun dualMonoAndQuadMappingAreCorrect() {
        val output = FloatArray(2)
        stereoDownmix(floatArrayOf(0.7f, -0.2f), "right", output)
        assertArrayEquals(floatArrayOf(-0.2f, -0.2f), output, 0f)
        stereoDownmix(floatArrayOf(0f, 0f, 1f, 0f), "stereo", output)
        assertTrue(output[0] > 0f); assertEquals(0f, output[1], 0f)
        stereoDownmix(floatArrayOf(0f, 0f, 0f, 0f, 1f, 0f, 0f), "stereo", output)
        assertTrue(output[0] > 0f); assertEquals(output[0], output[1], 0f)
    }

    @Test fun monoSourceWorksInEveryOutputMode() {
        val output = FloatArray(2)
        for (mode in listOf("stereo", "mono", "left", "right")) {
            stereoDownmix(floatArrayOf(0.4f), mode, output)
            assertArrayEquals(floatArrayOf(0.4f, 0.4f), output, 0f)
        }
    }

    @Test fun videoOffsetHasCorrectSignAndBounds() {
        assertEquals(1_250_000L, Media3AudioProcessing.videoPositionUs(1_000_000L, 250))
        assertEquals(750_000L, Media3AudioProcessing.videoPositionUs(1_000_000L, -250))
        assertEquals(3_000_000L, Media3AudioProcessing.videoPositionUs(1_000_000L, 9000))
    }

    @Test fun nativeGraphIncludesDownmixBeforeChannelSelectionAndLimiterAfterDsp() {
        val graph = mpvAudioGraph(AudioOptions(channels = "right", night = true, normalize = true))
        assertTrue(graph.startsWith("aresample=ochl=stereo:"))
        assertTrue(graph.indexOf("aresample") < graph.indexOf("pan="))
        assertTrue(graph.indexOf("acompressor") < graph.indexOf("alimiter"))
        assertTrue(mpvAudioGraph(AudioOptions()).isEmpty())
        assertFalse(mpvAudioGraph(AudioOptions(eqEnabled = true, eqDb = List(10) { 6 }), 8000).contains("f=16000"))
        val upmix = mpvAudioGraph(AudioOptions(upmix = true), sourceChannels = 2)
        assertFalse(mpvAudioGraph(AudioOptions(upmix = true), sourceChannels = 6).contains("pan="))
        assertFalse(mpvAudioGraph(AudioOptions(upmix = true), sourceChannels = 8).contains("pan="))
        assertTrue(upmix.startsWith("pan=5.1|"))
        assertFalse(upmix.contains("aformat="))
        assertTrue(mpvAudioGraph(AudioOptions(channels = "5.1")).isEmpty())
        val kept = mpvAudioGraph(AudioOptions(channels = "stereo", maintainDownmixVolume = true))
        assertTrue(kept.contains("rematrix_maxval=1000"))
        assertTrue(kept.contains("alimiter="))
        assertFalse(kept.contains("volume=3dB"))
    }

    @Test fun trackLanguageOutranksRoleAndRoleOutranksChannels() {
        val tracks = listOf(
            AudioTrackMetadata("en", "Commentary", 6),
            AudioTrackMetadata("de", "Main", 6),
            AudioTrackMetadata("de", "Kommentar", 2),
        )
        assertEquals(tracks[2], choosePreferredAudioTrack(tracks, "de", AudioTrackPreference("commentary", "surround")) { it })
        assertEquals(tracks[1], choosePreferredAudioTrack(tracks, "de", AudioTrackPreference("main", "stereo")) { it })
    }

    @Test fun requestedLanguageIsRequiredEvenWithoutRolePreferences() {
        val tracks = listOf(AudioTrackMetadata("en", "Deutsch commentary", 6), AudioTrackMetadata("ger", "Main", 2))
        assertEquals(tracks[1], choosePreferredAudioTrack(tracks, "de-DE", AudioTrackPreference()) { it })
        assertNull(choosePreferredAudioTrack(tracks.take(1), "de", AudioTrackPreference("commentary")) { it })
        assertEquals(AudioTrackMetadata(null, "ger"), choosePreferredAudioTrack(listOf(AudioTrackMetadata(null, "ger")), "de", AudioTrackPreference()) { it })
    }

    @Test fun originalAudioIsNeverInferredFromEnglishLanguage() {
        val tracks = listOf(AudioTrackMetadata("ja", "Original", 2), AudioTrackMetadata("en", "Dub", 6))
        assertEquals(tracks[0], choosePreferredAudioTrack(tracks, null, AudioTrackPreference("original")) { it })
        assertEquals(tracks[1], choosePreferredAudioTrack(tracks, "en", AudioTrackPreference()) { it })
        assertNull(choosePreferredAudioTrack(listOf(AudioTrackMetadata(null, null)), null, AudioTrackPreference("original")) { it })
    }
}
