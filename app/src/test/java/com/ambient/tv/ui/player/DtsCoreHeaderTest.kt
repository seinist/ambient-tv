package com.ambient.tv.ui.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.decoder.Decoder
import androidx.media3.decoder.DecoderException
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.decoder.SimpleDecoderOutputBuffer
import com.ambient.tv.data.local.AudioOptions
import com.ambient.tv.ui.player.audio.DtsCoreDecoder
import com.ambient.tv.ui.player.audio.DtsCoreHeader
import com.ambient.tv.ui.player.audio.DtsHdDecoder
import com.ambient.tv.ui.player.audio.dtsCoreConversionText
import com.ambient.tv.ui.player.audio.dtsCoreOutputFormat
import com.ambient.tv.ui.player.audio.dtsHdPacketHasCore
import com.ambient.tv.ui.player.audio.parseDtsCoreHeader
import java.nio.ByteBuffer
import java.util.concurrent.Executor
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DtsCoreHeaderTest {
    @After fun reset() = AudioCodecRejects.clear()

    /** A core frame of [size] bytes; bit positions follow ETSI TS 102 114. */
    private fun frame(size: Int = 1006, amode: Int = 9, sfreq: Int = 13, lff: Int = 1, extAudio: Boolean = false, extId: Int = 0): ByteArray {
        val bytes = ByteArray(size)
        fun put(start: Int, count: Int, value: Int) {
            for (i in 0 until count) {
                val bit = (value shr (count - 1 - i)) and 1
                val pos = start + i
                val idx = pos / 8
                val mask = 1 shl (7 - pos % 8)
                bytes[idx] = (if (bit == 1) bytes[idx].toInt() or mask else bytes[idx].toInt() and mask.inv()).toByte()
            }
        }
        put(0, 32, 0x7FFE8001.toInt())
        put(32, 1, 1); put(33, 5, 31); put(39, 7, 15)
        put(46, 14, size - 1)
        put(60, 6, amode); put(66, 4, sfreq); put(70, 5, 15)
        put(80, 3, extId); put(83, 1, if (extAudio) 1 else 0); put(85, 2, lff)
        return bytes
    }

    private fun parse(bytes: ByteArray) = parseDtsCoreHeader(ByteBuffer.wrap(bytes), 0, bytes.size)

    @Test fun fiveOneCoreAt48k() {
        val header = parse(frame())!!
        assertEquals(DtsCoreHeader(mainChannels = 5, lfe = true, sampleRate = 48_000, xch = false), header)
        assertEquals("5.1", header.channelLabel)
        assertEquals(6, header.channelCount)
    }

    @Test fun xchMakesDtsEsSixOne() {
        val header = parse(frame(extAudio = true, extId = 0))!!
        assertTrue(header.xch)
        assertEquals("6.1", header.channelLabel)
        // X96 (2) and XXCH (6) are not the XCh centre surround.
        assertEquals("5.1", parse(frame(extAudio = true, extId = 2))!!.channelLabel)
        assertEquals("5.1", parse(frame(extAudio = true, extId = 6))!!.channelLabel)
        // EXT_AUDIO_ID without the EXT_AUDIO flag is meaningless.
        assertEquals("5.1", parse(frame(extAudio = false, extId = 0))!!.channelLabel)
    }

    @Test fun stereoMonoAndRates() {
        assertEquals("2.0", parse(frame(amode = 2, lff = 0, sfreq = 8))!!.channelLabel)
        assertEquals(44_100, parse(frame(amode = 2, lff = 0, sfreq = 8))!!.sampleRate)
        assertEquals("1.0", parse(frame(amode = 0, lff = 0))!!.channelLabel)
        assertEquals("5.1", parse(frame(lff = 2))!!.channelLabel)
        assertEquals("5.0", parse(frame(lff = 0))!!.channelLabel)
        assertNull(parse(frame(sfreq = 4))!!.sampleRate)
    }

    @Test fun userDefinedLayoutsAndBrokenFramesAreRejected() {
        assertNull(parse(frame(amode = 16)))
        assertNull(parse(frame().copyOf(50)))
        assertNull(parse(byteArrayOf(0x64, 0x58, 0x20, 0x25) + ByteArray(200)))
    }

    private val ma71 = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_DTS_HD).setChannelCount(8).setSampleRate(48_000).build()

    @Test fun audioTrackGetsTheRealLayoutCappedAtFiveOne() {
        assertEquals(6, dtsCoreOutputFormat(ma71, parse(frame(extAudio = true))).channelCount)
        assertEquals(2, dtsCoreOutputFormat(ma71, parse(frame(amode = 2, lff = 0))).channelCount)
        assertEquals(44_100, dtsCoreOutputFormat(ma71, parse(frame(sfreq = 8))).sampleRate)
    }

    @Test fun chipNamesTheCoreLayout() {
        assertEquals("DTS-HD 7.1 → DTS 6.1", dtsCoreConversionText(ma71, parse(frame(extAudio = true))))
        assertEquals("DTS-HD 7.1 → DTS 5.1", dtsCoreConversionText(ma71, parse(frame())))
        assertEquals("DTS-HD 7.1 → DTS 5.1", dtsCoreConversionText(ma71, null))
        assertEquals("6.1", audioChannelLabel(7))
    }

    // --- Lossless-only DTS-HD: decided from the first packet ---

    private fun input(bytes: ByteArray?, eos: Boolean = false) = DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DIRECT).apply {
        if (eos) addFlag(C.BUFFER_FLAG_END_OF_STREAM)
        bytes?.let { ensureSpaceForWrite(it.size); data!!.put(it); flip() }
    }

    private val extensionOnly = byteArrayOf(0x64, 0x58, 0x20, 0x25) + ByteArray(400)

    @Test fun firstPacketDecidesCorePresence() {
        assertTrue(dtsHdPacketHasCore(input(frame() + extensionOnly)))
        assertFalse(dtsHdPacketHasCore(input(extensionOnly)))
        assertTrue(dtsHdPacketHasCore(input(null, eos = true)))
    }

    private class FakeLocal : Decoder<DecoderInputBuffer, SimpleDecoderOutputBuffer, DecoderException> {
        val buffer = DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DIRECT)
        var queued: ByteArray? = null
        var queuedTimeUs = 0L
        var startTimeUs = -1L
        var callbackSet = false
        override fun getName() = "fake local"
        override fun setOutputStartTimeUs(outputStartTimeUs: Long) { startTimeUs = outputStartTimeUs }
        override fun setCallback(callback: Decoder.Callback, executor: Executor) { callbackSet = true }
        override fun dequeueInputBuffer() = buffer
        override fun queueInputBuffer(inputBuffer: DecoderInputBuffer) {
            val data = inputBuffer.data!!
            queued = ByteArray(data.remaining()).also { data.duplicate().get(it) }
            queuedTimeUs = inputBuffer.timeUs
        }
        override fun dequeueOutputBuffer(): SimpleDecoderOutputBuffer? = null
        override fun flush() {}
        override fun release() {}
    }

    private val callback = object : Decoder.Callback {
        override fun onInputBufferAvailable() {}
        override fun onOutputBufferAvailable() {}
    }

    @Test fun losslessOnlyStreamGoesStraightToTheLocalDecoder() {
        val local = FakeLocal()
        var missing = 0
        val decoder = DtsHdDecoder(ma71, { local }) { missing++; AudioCodecRejects.markDtsCoreMissing() }
        decoder.setOutputStartTimeUs(1234)
        decoder.setCallback(callback) { it.run() }
        val first = decoder.dequeueInputBuffer()!!
        assertNull("one input until the path is chosen", decoder.dequeueInputBuffer())
        first.ensureSpaceForWrite(extensionOnly.size); first.data!!.put(extensionOnly); first.flip(); first.timeUs = 42
        decoder.queueInputBuffer(first)
        assertSame(local, decoder.delegate)
        assertEquals(1, missing)
        assertArrayEquals(extensionOnly, local.queued)
        assertEquals(42, local.queuedTimeUs)
        assertEquals(1234, local.startTimeUs)
        assertTrue(local.callbackSet)
        assertSame(local.buffer, decoder.dequeueInputBuffer())
        assertTrue(AudioCodecRejects.dtsCoreMissing)
    }

    private fun queueFirst(decoder: DtsHdDecoder, packet: ByteArray) {
        val first = decoder.dequeueInputBuffer()!!
        first.ensureSpaceForWrite(packet.size); first.data!!.put(packet); first.flip()
        decoder.queueInputBuffer(first)
    }

    @Test fun streamWithCoreUsesTheCorePath() {
        val decoder = DtsHdDecoder(ma71, { FakeLocal() }) { error("core is present") }
        queueFirst(decoder, frame() + extensionOnly)
        assertTrue(decoder.delegate is DtsCoreDecoder)
        assertFalse(AudioCodecRejects.dtsCoreMissing)
        decoder.release()
    }

    @Test fun withoutALocalDecoderTheCorePathFailsIntoTheFallbackChain() {
        val decoder = DtsHdDecoder(ma71, null) { error("nothing to switch to") }
        queueFirst(decoder, extensionOnly)
        assertTrue(decoder.delegate is DtsCoreDecoder)
        decoder.release()
    }

    @Test fun missingCoreRemovesDtsCoreFromThePlanUntilTheNextItem() {
        val route = AudioRouteCapabilities(passthrough = setOf("ac3", "dts"), pcmChannels = 2)
        val options = AudioOptions(output = "bitstream")
        assertEquals(AudioOutputMode.DTS_CORE, planAudioOutput(options, "dts-hd", route, "media3", true).mode)
        assertEquals(AudioOutputMode.PCM, planAudioOutput(options, "dts-hd", route, "media3", true, dtsCoreMissing = true).mode)
        val before = AudioCodecRejects.version
        AudioCodecRejects.markDtsCoreMissing()
        assertTrue(AudioCodecRejects.dtsCoreMissing)
        assertTrue(AudioCodecRejects.version > before)
        AudioCodecRejects.clear()
        assertFalse(AudioCodecRejects.dtsCoreMissing)
    }
}
