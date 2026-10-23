package com.ambient.tv.ui.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.data.local.AudioOptions
import com.ambient.tv.data.local.memoryPreferences
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class ProfileAudioProcessorTest {
    private fun process(samples: ShortArray, channels: Int, options: AudioOptions, routeChannels: Int = 8, channelMask: Int = -1): Pair<Int, ShortArray> {
        val session = AudioSession(AppPreferences(memoryPreferences()), "media3", false)
        session.route = AudioRouteCapabilities(pcmChannels = routeChannels)
        session.update(options)
        val processor = ProfileAudioProcessor(session)
        processor.setInputLayout(androidx.media3.common.Format.Builder().setSampleMimeType("audio/raw")
            .setChannelCount(channels).setChannelMask(channelMask).build(), null)
        val output = processor.configure(AudioProcessor.AudioFormat(48000, channels, C.ENCODING_PCM_16BIT))
        processor.flush()
        val input = ByteBuffer.allocateDirect(samples.size * 2).order(ByteOrder.nativeOrder())
        samples.forEach(input::putShort)
        input.flip()
        processor.queueInput(input)
        assertFalse(input.hasRemaining())
        val buffer = processor.output.order(ByteOrder.nativeOrder())
        return output.channelCount to ShortArray(buffer.remaining() / 2) { buffer.short }
    }

    @Test fun threePointOneMaskKeepsCenterAndDoesNotMixLfeAsSurround() {
        val options = AudioOptions(output = "pcm", channels = "stereo")
        val (_, center) = process(shortArrayOf(0, 0, 10000, 0), 4, options, channelMask = 0x3c)
        assertTrue(center[0] > 0)
        assertEquals(center[0], center[1])
        val (_, lfe) = process(shortArrayOf(0, 0, 0, 10000), 4, options, channelMask = 0x3c)
        assertArrayEquals(shortArrayOf(0, 0), lfe)
        val (_, mono) = process(shortArrayOf(10000), 1, options, channelMask = 0x4)
        assertArrayEquals(shortArrayOf(10000, 10000), mono)
    }

    @Test fun automaticStereoRouteKeepsEveryFullRangeChannel() {
        for (count in listOf(6, 8)) for (channel in 0 until count) {
            val input = ShortArray(count * 2)
            input[channel] = 10000
            input[count + channel] = -10000
            val (outputChannels, output) = process(input, count, AudioOptions(output = "pcm"), 2)
            assertEquals(2, outputChannels)
            assertEquals(4, output.size)
            if (channel == 3) assertTrue(output.all { it == 0.toShort() })
            else assertTrue(output.take(2).any { it > 0 })
            if (channel == 2) assertEquals(output[0], output[1])
            assertEquals(-output[0].toInt(), output[2].toInt())
            assertEquals(-output[1].toInt(), output[3].toInt())
        }
    }

    @Test fun keptDownmixLimitsAfterSummingAllChannels() {
        val (channels, output) = process(ShortArray(6 * 4) { 32767 }, 6,
            AudioOptions(output = "pcm", channels = "stereo", maintainDownmixVolume = true))
        assertEquals(2, channels)
        assertTrue(output.all { it in 1..31130 })
    }

    @Test fun sevenPointOneDownmixKeepsBothSurroundPairsAndFrameBoundaries() {
        val frame = shortArrayOf(100, 200, 300, 400, 500, 600, 700, 800)
        val (channels, output) = process(frame + frame, 8, AudioOptions(output = "pcm", channels = "5.1"))
        assertEquals(6, channels)
        assertArrayEquals(shortArrayOf(100, 200, 300, 400, 849, 990, 100, 200, 300, 400, 849, 990), output)
    }

    @Test fun fivePointOneRemainsCompleteWithSevenPointOneAndUpmixEnabled() {
        val input = shortArrayOf(100, 200, 300, 400, 500, 600, 10, 20, 30, 40, 50, 60)
        for (upmix in listOf(false, true)) {
            val (channels, output) = process(input, 6, AudioOptions(output = "pcm", channels = "7.1", upmix = upmix))
            assertEquals(6, channels)
            assertArrayEquals(input, output)
        }
    }

    @Test fun downmixDoesNotApplyDialogueCenterBoostAgain() {
        val frame = shortArrayOf(0, 0, 10000, 0, 0, 0)
        val (_, plain) = process(frame, 6, AudioOptions(output = "pcm", channels = "stereo", dialogue = 0))
        val (channels, boosted) = process(frame, 6, AudioOptions(output = "pcm", channels = "stereo", dialogue = 6))
        assertEquals(2, channels)
        val linear = (plain[0] * Math.pow(10.0, 6.0 / 20.0)).toInt()
        assertTrue(kotlin.math.abs(boosted[0].toInt()) < linear)
        assertTrue(kotlin.math.abs(boosted[0].toInt() - plain[0].toInt()) < kotlin.math.abs(plain[0].toInt()))
    }

    @Test fun quadUpmixWritesAllDeclaredChannels() {
        val (channels, output) = process(shortArrayOf(100, 200, 300, 400), 4, AudioOptions(output = "pcm", channels = "5.1", upmix = true))
        assertEquals(6, channels)
        assertEquals(6, output.size)
        assertTrue(output[0] > 0 && output[1] > 0)
    }

    @Test fun autoUpmixExpandsStereoToFakeFivePointOne() {
        val (channels, output) = process(shortArrayOf(1000, 2000), 2, AudioOptions(output = "pcm", channels = "auto", upmix = true))
        assertEquals(6, channels)
        assertEquals(6, output.size)
        assertEquals(1000, output[0].toInt())
        assertEquals(2000, output[1].toInt())
        assertEquals(1500, output[2].toInt())
        assertEquals(0, output[3].toInt())
        assertEquals(700, output[4].toInt())
        assertEquals(1400, output[5].toInt())
    }

    @Test fun stereoChannelModeKeepsStereoEvenWithUpmix() {
        val (channels, output) = process(shortArrayOf(1000, 0), 2, AudioOptions(output = "pcm", channels = "stereo", upmix = true))
        assertEquals(2, channels)
        assertEquals(2, output.size)
    }

    @Test fun forcedStereoFallbackDoesNotUpmix() {
        val session = AudioSession(AppPreferences(memoryPreferences()), "media3", false)
        session.route = AudioRouteCapabilities(pcmChannels = 8)
        session.update(AudioOptions(output = "pcm", channels = "auto", upmix = true))
        session.forceStereoPcm = true
        val output = ProfileAudioProcessor(session).configure(AudioProcessor.AudioFormat(48000, 2, C.ENCODING_PCM_16BIT))
        assertEquals(2, output.channelCount)
    }
}
