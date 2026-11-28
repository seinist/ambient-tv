package com.ambient.tv.ui.player.backend

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.OutputStream
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
    val seekTargetUs: Long? = null,
    val seekRequestId: Long = 0,
) {
    enum class Kind { EVENT_MESSAGE, PRODUCER_REFERENCE_TIME }
}

internal object VlcTimedMetadataPacket {
    const val MAX_BOX = 1024 * 1024
    sealed interface Message {
        data class Event(val event: VlcTimedMetadata) : Message
        data class Epoch(val epoch: Long, val seekTargetUs: Long? = null, val seekRequestId: Long = 0) : Message
    }

    // Acknowledge only after the receiver has applied the epoch barrier.
    fun acknowledgeEpoch(output: OutputStream, epoch: Long) {
        require(epoch in 0L..0xffffffffL)
        val data = DataOutputStream(output)
        data.writeInt(0x41464131) // AFA1
        data.writeInt(epoch.toInt())
        data.flush()
    }

    fun read(input: InputStream): Message {
        val data = DataInputStream(input)
        val magic = data.readInt()
        require(magic in 0x41464d31..0x41464d33) // AFM1 / AFM2 / AFM3
        val wireKind = data.readInt()
        val pts = data.readLong()
        val duration = data.readLong()
        val size = data.readInt()
        val epoch = data.readInt().toLong() and 0xffffffffL
        val target = if (magic >= 0x41464d32) data.readLong().also { require(it >= -1L) }.takeIf { it >= 0L } else null
        val requestId = if (magic == 0x41464d33) data.readLong().also { require(it >= 0L) } else 0L
        if (wireKind == 2) {
            require(pts == 0L && duration == 0L && size == 0)
            return Message.Epoch(epoch, target, requestId)
        }
        val kind = when (wireKind) {
            0 -> VlcTimedMetadata.Kind.EVENT_MESSAGE
            1 -> VlcTimedMetadata.Kind.PRODUCER_REFERENCE_TIME
            else -> throw IllegalArgumentException("Invalid timed metadata kind")
        }
        require(pts >= 0 && duration >= 0 && duration <= Long.MAX_VALUE - pts && size in 8..MAX_BOX)
        val box = ByteArray(size)
        data.readFully(box)
        val header = DataInputStream(box.inputStream())
        val boxSize = header.readInt().toLong() and 0xffffffffL
        val type = header.readInt()
        require(type == if (kind == VlcTimedMetadata.Kind.EVENT_MESSAGE) 0x656d7367 else 0x70726674)
        require(if (boxSize == 1L) size >= 16 && header.readLong() == size.toLong() else boxSize == size.toLong())
        return Message.Event(VlcTimedMetadata(kind, pts, duration, box, epoch, target, requestId))
    }
}

/** Per-controller bounded history. A retired media session cannot publish into a new one. */
internal class VlcTimedMetadataInbox {
    private val mutable = MutableStateFlow<List<VlcTimedMetadata>>(emptyList())
    val events: StateFlow<List<VlcTimedMetadata>> = mutable.asStateFlow()
    private var generation = 0L
    private var epoch = 0L
    private var discardedThroughEpoch = -1L
    private var requestedTargetUs: Long? = null
    private var requestedId = 0L
    private var fenceSerial = 0L
    private var bytes = 0
    @Synchronized fun beginSession(): Long {
        generation++
        epoch = 0
        discardedThroughEpoch = -1
        requestedTargetUs = null
        requestedId = 0L
        fenceSerial = 0L
        bytes = 0
        mutable.value = emptyList()
        return generation
    }
    internal data class SeekFence(val session: Long, val epoch: Long, val previous: Long,
        val previousTargetUs: Long?, val previousId: Long, val serial: Long,
        val previousEvents: List<VlcTimedMetadata>, val previousBytes: Int, val changed: Boolean)

    @Synchronized fun clearForSeek(targetUs: Long? = null, requestId: Long = 0L): SeekFence {
        // A retry of the same tagged native request must not erase a
        // barrier or events that arrived while its playback clock settles.
        val changed = requestId == 0L || requestId != requestedId || targetUs != requestedTargetUs
        val fence = SeekFence(generation, epoch, discardedThroughEpoch, requestedTargetUs, requestedId,
            ++fenceSerial, mutable.value, bytes, changed)
        if (!changed) return fence
        requestedTargetUs = targetUs
        requestedId = requestId
        // The native worker may already be sending one packet from the retired epoch.
        // Do not advance speculatively: retries can occur before VLC actually seeks.
        discardedThroughEpoch = maxOf(discardedThroughEpoch, epoch)
        bytes = 0
        mutable.value = emptyList()
        return fence
    }

    @Synchronized fun rejectSeek(fence: SeekFence) {
        // Do not undo a new media session or a native epoch observed during the call.
        if (fence.changed && generation == fence.session && epoch == fence.epoch && fence.serial == fenceSerial) {
            discardedThroughEpoch = fence.previous
            requestedTargetUs = fence.previousTargetUs
            requestedId = fence.previousId
            bytes = fence.previousBytes
            mutable.value = fence.previousEvents
        }
    }

    @Synchronized fun offer(session: Long, message: VlcTimedMetadataPacket.Message) {
        when (message) {
            is VlcTimedMetadataPacket.Message.Epoch -> advanceEpoch(session, message.epoch, message.seekTargetUs, message.seekRequestId)
            is VlcTimedMetadataPacket.Message.Event -> offer(session, message.event)
        }
    }

    private fun advanceEpoch(session: Long, incoming: Long, targetUs: Long?, requestId: Long): Boolean {
        if (session != generation || incoming < epoch || incoming <= discardedThroughEpoch) return false
        if (incoming != epoch) {
            epoch = incoming
            bytes = 0
            mutable.value = emptyList()
        }
        // VLC may coalesce setTime calls. Never infer the request count from epochs.
        // Old AFM1 senders cannot prove a target after an explicit seek.
        return if (requestedId != 0L) requestId == requestedId
            else requestedTargetUs == null || targetUs == requestedTargetUs
    }

    @Synchronized fun offer(session: Long, event: VlcTimedMetadata) {
        if (!advanceEpoch(session, event.seekEpoch, event.seekTargetUs, event.seekRequestId)) return
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
