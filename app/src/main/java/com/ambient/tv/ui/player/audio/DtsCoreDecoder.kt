@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.ambient.tv.ui.player.audio

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.decoder.DecoderException
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.decoder.SimpleDecoder
import androidx.media3.decoder.SimpleDecoderOutputBuffer
import java.nio.ByteBuffer

/**
 * DTS-HD to DTS core, without decoding: every DTS-HD access unit starts with a complete
 * DTS core frame (sync 0x7FFE8001) followed by the extension substream. Only the core goes
 * to an ENCODING_DTS AudioTrack, so a DTS-only receiver never sees extension data. This is
 * what MPV's DTS-core mode and Kodi send. Lossless-only DTS-HD (no core) fails the decoder,
 * which hands the item to the regular audio fallback chain.
 */
internal class DtsCoreDecoder(val inputFormat: Format) :
    SimpleDecoder<DecoderInputBuffer, SimpleDecoderOutputBuffer, DecoderException>(
        emptySlots<DecoderInputBuffer>(BUFFERS), emptySlots<SimpleDecoderOutputBuffer>(BUFFERS),
    ) {
    val outputFormat: Format = dtsCoreOutputFormat(inputFormat)

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
        val target = output.init(input.timeUs, size)
        val core = data.duplicate()
        core.limit(start + size)
        target.put(core)
        target.flip()
        return null
    }

    private companion object { const val BUFFERS = 16 }
}

/** SimpleDecoder fills the slots itself through createInputBuffer/createOutputBuffer. */
@Suppress("UNCHECKED_CAST")
private inline fun <reified T> emptySlots(size: Int): Array<T> = arrayOfNulls<T>(size) as Array<T>

/** DTS core frames carry at most 5.1 (6 channels); 44.1/48 kHz cores keep their rate. */
internal fun dtsCoreOutputFormat(input: Format): Format = Format.Builder()
    .setSampleMimeType(MimeTypes.AUDIO_DTS)
    .setChannelCount(input.channelCount.takeIf { it > 0 }?.coerceAtMost(6) ?: 6)
    .setSampleRate(input.sampleRate.takeIf { it in setOf(32_000, 44_100, 48_000) } ?: 48_000)
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
