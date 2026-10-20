@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.ambient.tv.ui.player.audio

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.decoder.Decoder
import androidx.media3.decoder.DecoderException
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.decoder.SimpleDecoder
import androidx.media3.decoder.SimpleDecoderOutputBuffer
import java.nio.ByteBuffer
import java.util.concurrent.Executor

/**
 * DTS-HD to DTS core, without decoding: a DTS-HD access unit normally starts with a complete
 * DTS core frame (sync 0x7FFE8001) followed by the extension substream. Only the core goes
 * to an ENCODING_DTS AudioTrack, so a DTS-only receiver never sees extension data. This is
 * what MPV's DTS-core mode and Kodi send. Streams without a core never reach this decoder
 * when a local decoder exists: [DtsHdDecoder] checks the first packet and decodes those.
 */
internal class DtsCoreDecoder(val inputFormat: Format) :
    SimpleDecoder<DecoderInputBuffer, SimpleDecoderOutputBuffer, DecoderException>(
        emptySlots<DecoderInputBuffer>(BUFFERS), emptySlots<SimpleDecoderOutputBuffer>(BUFFERS),
    ) {
    /** Header of the first core frame; set on the decoder thread before its first output. */
    @Volatile var header: DtsCoreHeader? = null
        private set

    /** Asked by DecoderAudioRenderer after the first output buffer, so [header] is known. */
    val outputFormat: Format get() = dtsCoreOutputFormat(inputFormat, header)

    init {
        setInitialInputBufferSize(inputFormat.maxInputSize.takeIf { it > 0 } ?: 32 * 1024)
    }

    override fun getName() = "DTS core passthrough"
    override fun createInputBuffer() = DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DIRECT)
    override fun createOutputBuffer() = SimpleDecoderOutputBuffer { releaseOutputBuffer(it) }
    override fun createUnexpectedDecodeException(error: Throwable) = DecoderException("DTS core extraction failed", error)

    override fun decode(input: DecoderInputBuffer, output: SimpleDecoderOutputBuffer, reset: Boolean): DecoderException? {
        val data = input.data ?: return DecoderException("DTS-HD packet without data")
        val start = data.position()
        val size = dtsCoreFrameSize(data, start, data.limit())
            ?: return DecoderException("DTS-HD packet has no DTS core")
        if (header == null) header = parseDtsCoreHeader(data, start, data.limit())
        val target = output.init(input.timeUs, size)
        val core = data.duplicate()
        core.limit(start + size)
        target.put(core)
        target.flip()
        return null
    }

    private companion object { const val BUFFERS = 16 }
}

/**
 * DTS-HD decoder chosen from the first packet: with a core frame it sends the core
 * ([DtsCoreDecoder]); without one (lossless-only DTS-HD) it decodes locally via [decodeLocally]
 * right away instead of failing first. [onCoreMissing] lets the audio plan follow.
 * Without a local decoder the core decoder is used and fails, which starts the regular
 * audio fallback chain.
 */
internal class DtsHdDecoder(
    val inputFormat: Format,
    private val decodeLocally: (() -> Decoder<DecoderInputBuffer, SimpleDecoderOutputBuffer, DecoderException>)?,
    private val onCoreMissing: () -> Unit,
) : Decoder<DecoderInputBuffer, SimpleDecoderOutputBuffer, DecoderException> {
    @Volatile var delegate: Decoder<DecoderInputBuffer, SimpleDecoderOutputBuffer, DecoderException>? = null
        private set
    private var staging: DecoderInputBuffer? = null
    private var startTimeUs = 0L
    private var callback: Pair<Decoder.Callback, Executor>? = null

    override fun getName() = delegate?.name ?: "DTS-HD core or local decode"

    override fun setOutputStartTimeUs(outputStartTimeUs: Long) {
        startTimeUs = outputStartTimeUs
        delegate?.setOutputStartTimeUs(outputStartTimeUs)
    }

    override fun setCallback(callback: Decoder.Callback, executor: Executor) {
        this.callback = callback to executor
        delegate?.setCallback(callback, executor)
    }

    override fun dequeueInputBuffer(): DecoderInputBuffer? {
        delegate?.let { return it.dequeueInputBuffer() }
        if (staging != null) return null
        return DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DIRECT).also { staging = it }
    }

    override fun queueInputBuffer(inputBuffer: DecoderInputBuffer) {
        delegate?.let { it.queueInputBuffer(inputBuffer); return }
        check(inputBuffer === staging) { "Foreign input" }
        val local = decodeLocally
        val next = if (local != null && !dtsHdPacketHasCore(inputBuffer)) {
            onCoreMissing()
            local()
        } else DtsCoreDecoder(inputFormat)
        next.setOutputStartTimeUs(startTimeUs)
        callback?.let { (cb, executor) -> next.setCallback(cb, executor) }
        delegate = next
        staging = null
        val target = next.dequeueInputBuffer() ?: throw DecoderException("DTS-HD decoder has no input buffer")
        copyDecoderInput(inputBuffer, target)
        next.queueInputBuffer(target)
    }

    override fun dequeueOutputBuffer(): SimpleDecoderOutputBuffer? = delegate?.dequeueOutputBuffer()

    override fun flush() {
        // Core presence is a property of the stream; keep the chosen path across seeks.
        staging = null
        delegate?.flush()
    }

    override fun release() { delegate?.release() }
}

/** End of stream or empty input never forces a decode; only a data packet without core does. */
internal fun dtsHdPacketHasCore(input: DecoderInputBuffer): Boolean {
    if (input.isEndOfStream) return true
    val data = input.data ?: return true
    if (!data.hasRemaining()) return true
    return dtsCoreFrameSize(data, data.position(), data.limit()) != null
}

private fun copyDecoderInput(source: DecoderInputBuffer, target: DecoderInputBuffer) {
    target.clear()
    target.timeUs = source.timeUs
    target.format = source.format
    if (source.isEndOfStream) target.addFlag(C.BUFFER_FLAG_END_OF_STREAM)
    if (source.isKeyFrame) target.addFlag(C.BUFFER_FLAG_KEY_FRAME)
    if (source.isFirstSample) target.addFlag(C.BUFFER_FLAG_FIRST_SAMPLE)
    if (source.isLastSample) target.addFlag(C.BUFFER_FLAG_LAST_SAMPLE)
    if (source.notDependedOn()) target.addFlag(C.BUFFER_FLAG_NOT_DEPENDED_ON)
    source.data?.duplicate()?.let { bytes ->
        target.ensureSpaceForWrite(bytes.remaining())
        target.data!!.put(bytes)
    }
    target.flip()
}

/** SimpleDecoder fills the slots itself through createInputBuffer/createOutputBuffer. */
@Suppress("UNCHECKED_CAST")
private inline fun <reified T> emptySlots(size: Int): Array<T> = arrayOfNulls<T>(size) as Array<T>

/**
 * Fields of a DTS core frame header (ETSI TS 102 114, 5.3.1).
 * [mainChannels] counts full-range channels including the XCh centre surround of DTS-ES
 * discrete 6.1; [lfe] is the LFE channel. [channelLabel] is "5.1", "6.1", "2.0" …
 */
internal data class DtsCoreHeader(val mainChannels: Int, val lfe: Boolean, val sampleRate: Int?, val xch: Boolean) {
    val channelCount: Int get() = mainChannels + if (lfe) 1 else 0
    val channelLabel: String get() = "$mainChannels.${if (lfe) 1 else 0}"
}

/** Full-range channels per AMODE; 0–15 are defined, higher values are user-defined. */
private val DTS_AMODE_CHANNELS = intArrayOf(1, 2, 2, 2, 2, 3, 3, 4, 4, 5, 6, 6, 6, 7, 8, 8)
private val DTS_SAMPLE_RATES = intArrayOf(0, 8_000, 16_000, 32_000, 0, 0, 11_025, 22_050, 44_100, 0, 0, 12_000, 24_000, 48_000, 0, 0)

/**
 * Parses a big-endian 16-bit DTS core header at [offset]. Bit positions from the frame start:
 * AMODE 60–65, SFREQ 66–69, EXT_AUDIO_ID 80–82, EXT_AUDIO 83, LFF 85–86.
 * XCh (EXT_AUDIO_ID 0 with EXT_AUDIO set) adds the centre surround channel of DTS-ES 6.1.
 * Other extensions (X96, XXCH) do not change the core layout we can name safely.
 */
internal fun parseDtsCoreHeader(data: ByteBuffer, offset: Int, limit: Int): DtsCoreHeader? {
    dtsCoreFrameSize(data, offset, limit) ?: return null
    fun bits(start: Int, count: Int): Int {
        var value = 0
        for (i in start until start + count) {
            val byte = data.get(offset + i / 8).toInt() and 0xFF
            value = (value shl 1) or ((byte shr (7 - i % 8)) and 1)
        }
        return value
    }
    val amode = bits(60, 6)
    if (amode >= DTS_AMODE_CHANNELS.size) return null
    val rate = DTS_SAMPLE_RATES[bits(66, 4)].takeIf { it > 0 }
    val xch = bits(83, 1) == 1 && bits(80, 3) == 0
    val lff = bits(85, 2)
    return DtsCoreHeader(DTS_AMODE_CHANNELS[amode] + if (xch) 1 else 0, lff == 1 || lff == 2, rate, xch)
}

/**
 * The AudioTrack carries at most 5.1 for DTS (Media3's passthrough limit); a 6.1 core still
 * goes out whole, the receiver decodes XCh from the bitstream. 44.1/48 kHz cores keep their rate.
 */
internal fun dtsCoreOutputFormat(input: Format, header: DtsCoreHeader? = null): Format = Format.Builder()
    .setSampleMimeType(MimeTypes.AUDIO_DTS)
    .setChannelCount((header?.channelCount ?: input.channelCount.takeIf { it > 0 } ?: 6).coerceIn(2, 6))
    .setSampleRate((header?.sampleRate ?: input.sampleRate).takeIf { it in setOf(32_000, 44_100, 48_000) } ?: 48_000)
    .build()

/**
 * Size of the big-endian 16-bit DTS core frame at [offset], or null when the packet does not
 * start with one. FSIZE is the 14-bit field after sync (32), FTYPE (1), SHORT (5), CPF (1)
 * and NBLKS (7); the frame is FSIZE + 1 bytes and at least 96 bytes long.
 */
internal fun dtsCoreFrameSize(data: ByteBuffer, offset: Int, limit: Int): Int? {
    if (limit - offset < 10) return null
    fun byte(i: Int) = data.get(offset + i).toInt() and 0xFF
    if (byte(0) != 0x7F || byte(1) != 0xFE || byte(2) != 0x80 || byte(3) != 0x01) return null
    val size = (((byte(5) and 0x03) shl 12) or (byte(6) shl 4) or (byte(7) ushr 4)) + 1
    return size.takeIf { it >= 96 && it <= limit - offset }
}
