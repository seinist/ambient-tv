package com.ambient.tv.ui.player.backend

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import org.junit.Assert.*
import org.junit.Test

class VlcTimedMetadataTest {
    private fun packet(epoch: Int = 0, size: Int = 8, type: Int = 0x656d7367): ByteArray =
        ByteArrayOutputStream().also { out ->
            DataOutputStream(out).use {
                it.writeInt(0x41464d31); it.writeInt(0)
                it.writeLong(250000); it.writeLong(100000)
                it.writeInt(size); it.writeInt(epoch)
                it.writeInt(size); it.writeInt(type)
                if (size in 9..VlcTimedMetadataPacket.MAX_BOX) it.write(ByteArray(size - 8))
            }
        }.toByteArray()

    private fun readEvent(input: java.io.InputStream): VlcTimedMetadata =
        (VlcTimedMetadataPacket.read(input) as VlcTimedMetadataPacket.Message.Event).event

    private fun barrier(epoch: Int, size: Int = 0): ByteArray =
        ByteArrayOutputStream().also { out ->
            DataOutputStream(out).use {
                it.writeInt(0x41464d31); it.writeInt(2)
                it.writeLong(0); it.writeLong(0)
                it.writeInt(size); it.writeInt(epoch)
            }
        }.toByteArray()

    @Test fun initialSeekRejectsEpochZeroBeforeFirstMetadataAndAcceptsBarrier() {
        val inbox = VlcTimedMetadataInbox()
        val session = inbox.beginSession()
        inbox.clearForSeek()
        val old = readEvent(packet().inputStream())
        inbox.offer(session, old)
        assertTrue(inbox.events.value.isEmpty())
        val marker = VlcTimedMetadataPacket.read(barrier(1).inputStream())
        assertEquals(VlcTimedMetadataPacket.Message.Epoch(1), marker)
        inbox.offer(session, marker)
        val current = old.copy(seekEpoch = 1)
        inbox.offer(session, current)
        inbox.offer(session, marker)
        inbox.offer(session, old)
        assertEquals(listOf(current), inbox.events.value)
        inbox.offer(session, VlcTimedMetadataPacket.Message.Epoch(2))
        assertTrue(inbox.events.value.isEmpty())
        inbox.offer(session, current)
        assertTrue(inbox.events.value.isEmpty())
        assertTrue(runCatching { VlcTimedMetadataPacket.read(barrier(2, 8).inputStream()) }.isFailure)
    }

    @Test fun epochAcknowledgmentPreservesUnsignedWireValue() {
        val out = ByteArrayOutputStream()
        VlcTimedMetadataPacket.acknowledgeEpoch(out, 0xfedcba98L)
        val data = java.io.DataInputStream(out.toByteArray().inputStream())
        assertEquals(0x41464131, data.readInt())
        assertEquals(0xfedcba98L, data.readInt().toLong() and 0xffffffffL)
        assertEquals(-1, data.read())
        assertTrue(runCatching { VlcTimedMetadataPacket.acknowledgeEpoch(out, -1) }.isFailure)
        assertTrue(runCatching { VlcTimedMetadataPacket.acknowledgeEpoch(out, 0x100000000L) }.isFailure)
    }

    @Test fun lostBarrierIsRecoveredByNewerEventAndDuplicateBarrierIsIdempotent() {
        val inbox = VlcTimedMetadataInbox()
        val session = inbox.beginSession()
        val first = readEvent(packet(1).inputStream())
        inbox.offer(session, first)
        inbox.clearForSeek()
        val latest = first.copy(seekEpoch = 3)
        inbox.offer(session, latest)
        inbox.offer(session, VlcTimedMetadataPacket.Message.Epoch(2))
        inbox.offer(session, VlcTimedMetadataPacket.Message.Epoch(3))
        assertEquals(listOf(latest), inbox.events.value)
    }

    @Test fun packetPreservesOpaqueBoxAndClock() {
        val event = readEvent(packet(2).inputStream())
        assertEquals(VlcTimedMetadata.Kind.EVENT_MESSAGE, event.kind)
        assertEquals(250000L, event.presentationTimeUs)
        assertEquals(100000L, event.durationUs)
        assertEquals(2L, event.seekEpoch)
        assertArrayEquals(byteArrayOf(0,0,0,8,101,109,115,103), event.box)
    }

    @Test fun rejectsOversizeWrongTypeAndTruncation() {
        assertTrue(runCatching { readEvent(packet(size = 2 * 1024 * 1024).inputStream()) }.isFailure)
        assertTrue(runCatching { readEvent(packet(type = 0x70726674).inputStream()) }.isFailure)
        assertTrue(runCatching { readEvent(packet().copyOf(34).inputStream()) }.isFailure)
    }

    @Test fun seekRejectsInFlightRetiredEpochWithoutPredictingNativeRetries() {
        val inbox = VlcTimedMetadataInbox()
        val session = inbox.beginSession()
        val old = readEvent(packet(3).inputStream())
        inbox.offer(session, old)
        inbox.clearForSeek()
        inbox.offer(session, old)
        assertTrue(inbox.events.value.isEmpty())
        inbox.clearForSeek()
        val current = old.copy(seekEpoch = 4)
        inbox.offer(session, current)
        inbox.offer(session, old)
        assertEquals(listOf(current), inbox.events.value)
    }

    @Test fun rejectedSeekRestoresCurrentEpochButCannotUndoNewSessionOrEpoch() {
        val inbox = VlcTimedMetadataInbox()
        val session = inbox.beginSession()
        val old = readEvent(packet(3).inputStream())
        inbox.offer(session, old)
        inbox.rejectSeek(inbox.clearForSeek())
        inbox.offer(session, old)
        assertEquals(listOf(old), inbox.events.value)
        val fence = inbox.clearForSeek()
        val current = old.copy(seekEpoch = 4)
        inbox.offer(session, current)
        inbox.rejectSeek(fence)
        inbox.offer(session, old)
        assertEquals(listOf(current), inbox.events.value)
        val retired = inbox.clearForSeek()
        val next = inbox.beginSession()
        inbox.rejectSeek(retired)
        val initial = old.copy(seekEpoch = 0)
        inbox.offer(next, initial)
        assertEquals(listOf(initial), inbox.events.value)
    }

    @Test fun isolatesMediaSessionsSeekEpochsAndBoundsHistory() {
        val inbox = VlcTimedMetadataInbox()
        val old = inbox.beginSession()
        val current = inbox.beginSession()
        val event = readEvent(packet(1).inputStream())
        inbox.offer(old, event)
        assertTrue(inbox.events.value.isEmpty())
        repeat(70) { inbox.offer(current, event.copy(presentationTimeUs = it.toLong())) }
        assertEquals(64, inbox.events.value.size)
        val newer = event.copy(seekEpoch = 2)
        inbox.offer(current, newer)
        inbox.offer(current, event)
        assertEquals(listOf(newer), inbox.events.value)
        inbox.beginSession()
        assertTrue(inbox.events.value.isEmpty())
    }
}
