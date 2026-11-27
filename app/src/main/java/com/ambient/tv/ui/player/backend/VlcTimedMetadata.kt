package com.ambient.tv.ui.player.backend

import java.io.DataInputStream
import java.io.InputStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** An opaque ISO BMFF timed metadata box from the experimental VLC demuxer.
 * The application must interpret scheme-specific payloads explicitly, never as commands.
 */
data class VlcTimedMetadata(
    val kind: Kind,
    val presentationTimeUs: Long,
    val durationUs: Long,
    val box: ByteArray,
    val seekEpoch: Long,
) {
    enum class Kind { EVENT_MESSAGE, PRODUCER_REFERENCE_TIME }
}

internal object VlcTimedMetadataPacket {
    const val MAX_BOX = 1024 * 1024
    fun read(input: InputStream): VlcTimedMetadata {
        val data = DataInputStream(input)
        require(data.readInt() == 0x41464d31) // AFM1
        val kind = when (data.readInt()) {
            0 -> VlcTimedMetadata.Kind.EVENT_MESSAGE
            1 -> VlcTimedMetadata.Kind.PRODUCER_REFERENCE_TIME
            else -> throw IllegalArgumentException("Invalid timed metadata kind")
        }
        val pts = data.readLong()
        val duration = data.readLong()
        val size = data.readInt()
        val epoch = data.readInt().toLong() and 0xffffffffL
        require(pts >= 0 && duration >= 0 && duration <= Long.MAX_VALUE - pts && size in 8..MAX_BOX)
        val box = ByteArray(size)
        data.readFully(box)
        val header = DataInputStream(box.inputStream())
        val boxSize = header.readInt().toLong() and 0xffffffffL
        val type = header.readInt()
        require(type == if (kind == VlcTimedMetadata.Kind.EVENT_MESSAGE) 0x656d7367 else 0x70726674)
        require(if (boxSize == 1L) size >= 16 && header.readLong() == size.toLong() else boxSize == size.toLong())
        return VlcTimedMetadata(kind, pts, duration, box, epoch)
    }
}

/** Per-controller bounded history. A retired media session cannot publish into a new one. */
internal class VlcTimedMetadataInbox {
    private val mutable = MutableStateFlow<List<VlcTimedMetadata>>(emptyList())
    val events: StateFlow<List<VlcTimedMetadata>> = mutable.asStateFlow()
    private var generation = 0L
    private var epoch = -1L
    private var discardedThroughEpoch = -1L
    private var bytes = 0
    @Synchronized fun beginSession(): Long {
        generation++
        epoch = -1
        discardedThroughEpoch = -1
        bytes = 0
        mutable.value = emptyList()
        return generation
    }
    internal data class SeekFence(val session: Long, val epoch: Long, val previous: Long)

    @Synchronized fun clearForSeek(): SeekFence {
        val fence = SeekFence(generation, epoch, discardedThroughEpoch)
        // The native worker may already be sending one packet from the retired epoch.
        // Do not advance speculatively: retries can occur before VLC actually seeks.
        discardedThroughEpoch = maxOf(discardedThroughEpoch, epoch)
        bytes = 0
        mutable.value = emptyList()
        return fence
    }

    @Synchronized fun rejectSeek(fence: SeekFence) {
        // Do not undo a new media session or a native epoch observed during the call.
        if (generation == fence.session && epoch == fence.epoch) {
            discardedThroughEpoch = fence.previous
        }
    }

    @Synchronized fun offer(session: Long, event: VlcTimedMetadata) {
        if (session != generation || event.seekEpoch < epoch ||
            event.seekEpoch <= discardedThroughEpoch) return
        if (event.seekEpoch != epoch) {
            epoch = event.seekEpoch
            bytes = 0
            mutable.value = emptyList()
        }
        if (event.box.size > VlcTimedMetadataPacket.MAX_BOX) return
        val next = mutable.value.toMutableList()
        while (next.isNotEmpty() && (next.size >= 64 || bytes + event.box.size > 4 * 1024 * 1024)) {
            bytes -= next.removeAt(0).box.size
        }
        next.add(event)
        bytes += event.box.size
        mutable.value = next
    }
}
