package com.ambient.tv.livetv

import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test

class TsPacketAlignerTest {
    /** [count] packets on [pid], each with payload only, or with an adaptation field when [adaptation]. */
    private fun packets(count: Int, pid: Int = 0x100, adaptation: Boolean = false, seed: Int = 0) = ByteArray(count * 188) { i ->
        val p = i % 188
        when (p) {
            0 -> 0x47
            1 -> (pid shr 8) and 0x1F
            2 -> pid and 0xFF
            3 -> (if (adaptation) 0x30 else 0x10) or ((i / 188) and 0x0F)
            4 -> if (adaptation) 7 else (seed + 1)
            5 -> if (adaptation) 0x10 else (seed + 2) // PCR flag
            else -> ((i / 188 + p + seed) % 0x46) // never 0x47 in payload
        }.toByte()
    }

    private fun run(aligner: TsPacketAligner, vararg chunks: ByteArray, splitAt: List<Int> = emptyList()): ByteArray {
        val out = ByteArrayOutputStream()
        chunks.forEachIndexed { index, chunk ->
            if (index in splitAt) aligner.startSplice()
            aligner.feed(chunk, chunk.size) { b, o, l -> out.write(b, o, l) }
        }
        return out.toByteArray()
    }

    private fun assertGrid(data: ByteArray) {
        assertEquals(0, data.size % 188)
        for (p in data.indices step 188) assertEquals("packet at $p", 0x47.toByte(), data[p])
    }

    @Test fun alignedStreamPassesUnchangedEvenByteByByte() {
        val data = packets(50)
        assertArrayEquals(data, run(TsPacketAligner(), data))
        val aligner = TsPacketAligner()
        val out = ByteArrayOutputStream()
        data.forEach { b -> aligner.feed(byteArrayOf(b), 1) { x, o, l -> out.write(x, o, l) } }
        assertArrayEquals(data, out.toByteArray())
        assertEquals(0L, aligner.droppedBytes)
    }

    @Test fun reconnectDropsThePartialPacketAndKeepsTheGrid() {
        val first = packets(20) + packets(1).copyOf(100) // ends mid-packet
        val second = byteArrayOf(1, 2, 3) + packets(20, seed = 5) // starts with garbage
        val aligner = TsPacketAligner()
        val out = run(aligner, first, second, splitAt = listOf(1))
        assertGrid(out)
        assertEquals(40 * 188, out.size)
        assertEquals(100L + 3L, aligner.droppedBytes)
    }

    @Test fun corruptionInsideTheStreamResynchronises() {
        val broken = packets(10) + byteArrayOf(9, 9, 9, 9, 9) + packets(10, seed = 3)
        val aligner = TsPacketAligner()
        val out = run(aligner, broken)
        assertGrid(out)
        assertEquals(20 * 188, out.size)
        assertEquals(5L, aligner.droppedBytes)
    }

    @Test fun firstPacketPerPidWithAdaptationFieldIsMarkedAfterASpliceOnly() {
        val first = packets(5, pid = 0x100, adaptation = true)
        val second = packets(3, pid = 0x100, adaptation = true) + packets(3, pid = 0x101) +
            packets(3, pid = 0x1FFF, adaptation = true) + packets(3, pid = 0x101, adaptation = true) + packets(2)
        val aligner = TsPacketAligner()
        val out = run(aligner, first, second, splitAt = listOf(1))
        assertGrid(out)
        fun flagsAt(packet: Int) = out[packet * 188 + 5].toInt() and 0xFF
        // First connection: untouched.
        (0 until 5).forEach { assertEquals(0x10, flagsAt(it)) }
        // After the splice: PID 0x100 marked once, PID 0x101 on its first AF packet, null PID never.
        assertEquals(0x90, flagsAt(5)); assertEquals(0x10, flagsAt(6))
        assertEquals(0x10, flagsAt(11)) // null PID
        assertEquals(0x90, flagsAt(14)); assertEquals(0x10, flagsAt(15))
        assertEquals(2, aligner.markedPackets)
    }
}
