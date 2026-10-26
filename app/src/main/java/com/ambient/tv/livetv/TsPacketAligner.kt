package com.ambient.tv.livetv

/**
 * Keeps the timeshift ring on the 188-byte MPEG-TS packet grid.
 *
 * The recorder only appends whole packets that start with the sync byte 0x47, so every multiple
 * of 188 in the ring is a packet start ([TimeshiftBuffer.urlAt] and the ring fallback rely on
 * that). After an upstream reconnect ([startSplice]) the partial last packet of the old
 * connection is dropped, the new stream is re-synchronised on 0x47, and the first packet of every
 * PID that carries an adaptation field gets its `discontinuity_indicator` set, so demuxers know
 * that the clock and continuity counters jump here instead of guessing.
 *
 * Not thread-safe: only the recorder thread uses it.
 */
internal class TsPacketAligner {
    private var work = ByteArray(0)
    private var carry = 0
    private var synced = false
    private var marking = false
    private var markedBytes = 0L
    private val markedPids = HashSet<Int>()

    /** Bytes thrown away so far (partial packets, garbage before a sync byte). */
    var droppedBytes = 0L
        private set
    /** Packets whose discontinuity_indicator was set. */
    var markedPackets = 0
        private set

    /** A new upstream connection starts: whatever is left of the old one is not a whole packet. */
    fun startSplice() {
        droppedBytes += carry
        carry = 0
        synced = false
        marking = true
        markedBytes = 0L
        markedPids.clear()
    }

    /** Feeds [length] bytes of [bytes]; [emit] receives only whole, sync-checked packets. */
    fun feed(bytes: ByteArray, length: Int, emit: (ByteArray, Int, Int) -> Unit) {
        if (length <= 0) return
        val total = carry + length
        if (work.size < total) work = work.copyOf(maxOf(total, work.size * 2))
        System.arraycopy(bytes, 0, work, carry, length)
        var i = 0
        var runStart = -1
        fun flush(end: Int) {
            if (runStart >= 0 && end > runStart) emit(work, runStart, end - runStart)
            runStart = -1
        }
        while (true) {
            if (!synced) {
                val start = findSync(i, total)
                if (start < 0) {
                    // Keep a possible sync byte at the very end for the next read.
                    val keep = minOf(TS_PACKET - 1, total - i).coerceAtLeast(0)
                    droppedBytes += (total - i - keep)
                    i = total - keep
                    break
                }
                droppedBytes += (start - i)
                i = start
                if (total - i <= TS_PACKET) break // Cannot confirm the next sync byte yet.
                synced = true
            }
            if (total - i < TS_PACKET) break
            if (work[i] != SYNC) {
                flush(i)
                synced = false
                continue
            }
            if (runStart < 0) runStart = i
            if (marking) mark(i)
            i += TS_PACKET
        }
        flush(i)
        carry = total - i
        if (carry > 0) System.arraycopy(work, i, work, 0, carry)
    }

    /** First position from [from] whose byte is 0x47 and whose next packet (if present) is too. */
    private fun findSync(from: Int, total: Int): Int {
        var p = from
        while (p < total) {
            if (work[p] == SYNC && (p + TS_PACKET >= total || work[p + TS_PACKET] == SYNC)) return p
            p++
        }
        return -1
    }

    private fun mark(p: Int) {
        markedBytes += TS_PACKET
        if (markedBytes > MARK_WINDOW_BYTES) { marking = false; return }
        val pid = ((work[p + 1].toInt() and 0x1F) shl 8) or (work[p + 2].toInt() and 0xFF)
        if (pid == NULL_PID || pid in markedPids) return
        val adaptation = (work[p + 3].toInt() shr 4) and 0x3
        if ((adaptation == 2 || adaptation == 3) && (work[p + 4].toInt() and 0xFF) > 0) {
            work[p + 5] = (work[p + 5].toInt() or 0x80).toByte()
            markedPids += pid
            markedPackets++
        }
    }

    companion object {
        const val TS_PACKET = 188
        private const val SYNC = 0x47.toByte()
        private const val NULL_PID = 0x1FFF
        /** Only packets shortly after the splice are marked (~2 MiB, a few seconds of HD). */
        internal const val MARK_WINDOW_BYTES = 2L * 1024 * 1024
    }
}
