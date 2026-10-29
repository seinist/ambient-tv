package com.ambient.tv.livetv.teletext

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PsiSectionAssemblerTest {
    private fun section(id: Int, size: Int = 16): ByteArray = ByteArray(size) { (it and 0x7F).toByte() }.also {
        it[0] = id.toByte()
        it[1] = (0xB0 or ((size - 3) shr 8)).toByte()
        it[2] = (size - 3).toByte()
    }

    /** Adaptation stuffing keeps short payloads short, including split section headers. */
    private fun packet(payload: ByteArray, counter: Int, start: Boolean = false, discontinuity: Boolean = false): ByteArray {
        require(payload.size <= if (discontinuity) 182 else 184)
        return ByteArray(188) { 0xFF.toByte() }.also {
            it[0] = 0x47
            it[1] = (if (start) 0x40 else 0).toByte()
            it[2] = 0x20
            val adaptation = 183 - payload.size
            val offset = if (adaptation < 0) {
                it[3] = (0x10 or counter).toByte()
                4
            } else {
                it[3] = (0x30 or counter).toByte()
                it[4] = adaptation.toByte()
                if (adaptation > 0) it[5] = (if (discontinuity) 0x80 else 0).toByte()
                5 + adaptation
            }
            payload.copyInto(it, offset)
        }
    }

    private fun start(section: ByteArray, counter: Int = 0) = packet(byteArrayOf(0) + section, counter, start = true)

    @Test fun splitSectionHeaderAndCounterWrapAreReassembled() {
        val received = mutableListOf<ByteArray>()
        val assembler = PsiSectionAssembler { received += it }
        val bytes = section(0x02)
        assembler.packet(start(bytes.copyOfRange(0, 1), 14), 0)
        assembler.packet(packet(bytes.copyOfRange(1, 2), 15), 0)
        assertTrue(received.isEmpty())
        assembler.packet(packet(bytes.copyOfRange(2, bytes.size), 0), 0)
        assertEquals(1, received.size)
        assertArrayEquals(bytes, received.single())
    }

    @Test fun pointerCompletesPreviousSectionAndAllFollowingSectionsAreRead() {
        val received = mutableListOf<ByteArray>()
        val assembler = PsiSectionAssembler { received += it }
        val first = section(0x74, 200)
        val second = section(0x74, 24)
        val third = section(0x74, 18)
        assembler.packet(start(first.copyOfRange(0, 183)), 0)
        val tail = first.copyOfRange(183, first.size)
        // The pointer also covers stuffing after the old section, before the next section.
        val payload = byteArrayOf((tail.size + 2).toByte()) + tail + byteArrayOf(-1, -1) + second + third + byteArrayOf(-1)
        assembler.packet(packet(payload, 1, start = true), 0)
        assertEquals(3, received.size)
        listOf(first, second, third).forEachIndexed { index, bytes -> assertArrayEquals(bytes, received[index]) }
    }

    @Test fun duplicatePayloadDoesNotCorruptPendingSectionOrEmitItTwice() {
        val received = mutableListOf<ByteArray>()
        val assembler = PsiSectionAssembler { received += it }
        val bytes = section(0x74, 400)
        val first = start(bytes.copyOfRange(0, 183))
        val middle = packet(bytes.copyOfRange(183, 367), 1)
        val last = packet(bytes.copyOfRange(367, bytes.size), 2)
        for (packet in listOf(first, first, middle, middle, last, last)) assembler.packet(packet, 0)
        assertEquals(1, received.size)
        assertArrayEquals(bytes, received.single())
    }

    @Test fun repeatedCompletePacketIsOnlyDeliveredOnce() {
        val received = mutableListOf<ByteArray>()
        val assembler = PsiSectionAssembler { received += it }
        val bytes = section(0x02)
        val packet = start(bytes)
        assembler.packet(packet, 0)
        assembler.packet(packet, 0)
        assertEquals(1, received.size)
        assertArrayEquals(bytes, received.single())
    }

    @Test fun missingPacketDropsThePartialSectionUntilANewStart() {
        val received = mutableListOf<ByteArray>()
        val assembler = PsiSectionAssembler { received += it }
        val bytes = section(0x02, 400)
        assembler.packet(start(bytes.copyOfRange(0, 183)), 0)
        assembler.packet(packet(bytes.copyOfRange(367, bytes.size), 2), 0)
        assertTrue(received.isEmpty())
        val replacement = section(0x02)
        assembler.packet(start(replacement, 3), 0)
        assertEquals(1, received.size)
        assertArrayEquals(replacement, received.single())
    }

    @Test fun adaptationOnlyDiscontinuityDiscardsThePendingSection() {
        val received = mutableListOf<ByteArray>()
        val assembler = PsiSectionAssembler { received += it }
        val bytes = section(0x74, 200)
        assembler.packet(start(bytes.copyOfRange(0, 183)), 0)
        val adaptationOnly = packet(byteArrayOf(), 0, discontinuity = true).also { it[3] = 0x20 }
        assembler.packet(adaptationOnly, 0)
        assembler.packet(packet(bytes.copyOfRange(183, bytes.size), 1), 0)
        assertTrue(received.isEmpty())
        val replacement = section(0x74)
        assembler.packet(start(replacement, 7), 0)
        assertArrayEquals(replacement, received.single())
    }

    @Test fun adaptationOnlyWithoutDiscontinuityDoesNotConsumeAContinuityCount() {
        val received = mutableListOf<ByteArray>()
        val assembler = PsiSectionAssembler { received += it }
        val bytes = section(0x74, 200)
        assembler.packet(start(bytes.copyOfRange(0, 183)), 0)
        assembler.packet(packet(byteArrayOf(), 0).also { it[3] = 0x20 }, 0)
        assembler.packet(packet(bytes.copyOfRange(183, bytes.size), 1), 0)
        assertArrayEquals(bytes, received.single())
    }

    @Test fun payloadDiscontinuityAndResetDiscardPartialData() {
        val received = mutableListOf<ByteArray>()
        val assembler = PsiSectionAssembler { received += it }
        val bytes = section(0x74, 200)
        assembler.packet(start(bytes.copyOfRange(0, 183), 5), 0)
        assembler.packet(packet(bytes.copyOfRange(183, bytes.size), 6, discontinuity = true), 0)
        assertTrue(received.isEmpty())
        assembler.packet(start(bytes.copyOfRange(0, 183), 7), 0)
        assembler.reset()
        assembler.packet(packet(bytes.copyOfRange(183, bytes.size), 8), 0)
        assertTrue(received.isEmpty())
        val replacement = section(0x74)
        assembler.reset()
        assembler.packet(start(replacement, 8), 0)
        assertArrayEquals(replacement, received.single())
    }

    @Test fun erroredScrambledAndMalformedPacketsCannotCompleteASection() {
        val corruptions: List<(ByteArray) -> Unit> = listOf(
            { it[1] = (it[1].toInt() or 0x80).toByte() }, // transport_error_indicator
            { it[3] = (it[3].toInt() or 0x80).toByte() }, // transport_scrambling_control
            { it[4] = 184.toByte() }, // adaptation field extends beyond the packet
        )
        for (corrupt in corruptions) {
            val received = mutableListOf<ByteArray>()
            val assembler = PsiSectionAssembler { received += it }
            val bytes = section(0x74, 200)
            assembler.packet(start(bytes.copyOfRange(0, 183)), 0)
            val tail = packet(bytes.copyOfRange(183, bytes.size), 1)
            corrupt(tail)
            assembler.packet(tail, 0)
            assembler.packet(packet(bytes.copyOfRange(183, bytes.size), 2), 0)
            assertTrue(received.isEmpty())
            val replacement = section(0x74)
            assembler.packet(start(replacement, 3), 0)
            assertArrayEquals(replacement, received.single())
        }
    }

    @Test fun invalidPointerAndSectionLengthRecoverAtTheNextStart() {
        val received = mutableListOf<ByteArray>()
        val assembler = PsiSectionAssembler { received += it }
        assembler.packet(packet(byteArrayOf(20, 0, 0), 0, start = true), 0)
        assembler.packet(start(byteArrayOf(0x74, 0xBF.toByte(), 0xFF.toByte()), 1), 0)
        assertTrue(received.isEmpty())
        val replacement = section(0x74)
        assembler.packet(start(replacement, 2), 0)
        assertArrayEquals(replacement, received.single())
    }
}
