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

    @Test fun targetAwarePacketsRejectDelayedDifferentSeekEvenWithoutBarrier() {
        val inbox = VlcTimedMetadataInbox()
        val session = inbox.beginSession()
        val old = readEvent(packet().inputStream())
        inbox.clearForSeek(1000000)
        inbox.clearForSeek(2000000)
        inbox.offer(session, VlcTimedMetadataPacket.Message.Epoch(1, 1000000))
        inbox.offer(session, old.copy(seekEpoch = 1, seekTargetUs = 1000000))
        assertTrue(inbox.events.value.isEmpty())
        val current = old.copy(seekEpoch = 2, seekTargetUs = 2000000)
        inbox.offer(session, current)
        assertEquals(listOf(current), inbox.events.value)
        inbox.offer(session, VlcTimedMetadataPacket.Message.Epoch(1, 1000000))
        assertEquals(listOf(current), inbox.events.value)
        inbox.clearForSeek(3000000)
        inbox.offer(session, old.copy(seekEpoch = 3))
        assertTrue(inbox.events.value.isEmpty())
    }

    @Test fun versionTwoBarrierCarriesNativeRequestedTime() {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use {
            it.writeInt(0x41464d32); it.writeInt(2)
            it.writeLong(0); it.writeLong(0)
            it.writeInt(0); it.writeInt(7); it.writeLong(1234000)
        }
        assertEquals(VlcTimedMetadataPacket.Message.Epoch(7, 1234000),
            VlcTimedMetadataPacket.read(out.toByteArray().inputStream()))
    }

    @Test fun identicalTargetsRequireExactRequestIdAcrossRetriesAndCoalescing() {
        val inbox = VlcTimedMetadataInbox()
        val session = inbox.beginSession()
        val old = readEvent(packet().inputStream())
        inbox.clearForSeek(1000000, 41)
        inbox.clearForSeek(1000000, 42)
        inbox.offer(session, VlcTimedMetadataPacket.Message.Epoch(1, 1000000, 41))
        inbox.offer(session, old.copy(seekEpoch = 1, seekTargetUs = 1000000, seekRequestId = 41))
        assertTrue(inbox.events.value.isEmpty())
        val latest = old.copy(seekEpoch = 2, seekTargetUs = 1000000, seekRequestId = 42)
        inbox.offer(session, latest)
        assertEquals(listOf(latest), inbox.events.value)
        inbox.clearForSeek(1000000, 43)
        inbox.offer(session, latest.copy(seekEpoch = 3))
        assertTrue(inbox.events.value.isEmpty())
        val current = latest.copy(seekEpoch = 4, seekRequestId = 43)
        inbox.offer(session, current)
        assertEquals(listOf(current), inbox.events.value)
        val retry = inbox.clearForSeek(1000000, 43)
        assertEquals(listOf(current), inbox.events.value)
        inbox.rejectSeek(retry)
        assertEquals(listOf(current), inbox.events.value)
        inbox.offer(session, current.copy(seekEpoch = 5))
        assertEquals(43L, inbox.events.value.single().seekRequestId)
    }

    @Test fun versionThreeCarriesRequestIdIndependentlyOfTarget() {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use {
            it.writeInt(0x41464d33); it.writeInt(2)
            it.writeLong(0); it.writeLong(0)
            it.writeInt(0); it.writeInt(7); it.writeLong(1234000); it.writeLong(123)
        }
        assertEquals(VlcTimedMetadataPacket.Message.Epoch(7, 1234000, 123),
            VlcTimedMetadataPacket.read(out.toByteArray().inputStream()))
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

    @Test fun overlappingRejectedSeeksRestoreTheLastCommittedHistory() {
        val inbox = VlcTimedMetadataInbox()
        val session = inbox.beginSession()
        val old = readEvent(packet(3).inputStream())
        inbox.offer(session, old)
        val a = inbox.clearForSeek(1000000, 41)
        val b = inbox.clearForSeek(2000000, 42)
        inbox.rejectSeek(a)
        assertTrue(inbox.events.value.isEmpty())
        inbox.rejectSeek(b)
        assertEquals(listOf(old), inbox.events.value)
        val c = inbox.clearForSeek(3000000, 43)
        val d = inbox.clearForSeek(4000000, 44)
        inbox.acceptSeek(c)
        inbox.rejectSeek(d)
        val current = old.copy(seekEpoch = 4, seekTargetUs = 3000000, seekRequestId = 43)
        inbox.offer(session, current)
        assertEquals(listOf(current), inbox.events.value)
        inbox.offer(session, current.copy(box = current.box.copyOf()))
        assertEquals(listOf(current), inbox.events.value)
    }

    @Test fun rejectedRetryDoesNotOverwriteAnEventReceivedDuringNativeCall() {
        val inbox = VlcTimedMetadataInbox()
        val session = inbox.beginSession()
        inbox.clearForSeek(1000000, 41)
        val first = readEvent(packet(1).inputStream()).copy(seekTargetUs = 1000000, seekRequestId = 41)
        inbox.offer(session, first)
        val retry = inbox.clearForSeek(1000000, 41)
        val second = first.copy(presentationTimeUs = first.presentationTimeUs + 100000)
        inbox.offer(session, second)
        inbox.rejectSeek(retry)
        assertEquals(listOf(first, second), inbox.events.value)
    }

    @Test fun rejectedNewSeekRestoresHistoryAndByteBudget() {
        val inbox = VlcTimedMetadataInbox()
        val session = inbox.beginSession()
        val event = readEvent(packet(1, VlcTimedMetadataPacket.MAX_BOX).inputStream())
        repeat(4) { inbox.offer(session, event.copy(presentationTimeUs = it.toLong())) }
        val history = inbox.events.value
        val fence = inbox.clearForSeek(1000000, 41)
        assertTrue(inbox.events.value.isEmpty())
        inbox.rejectSeek(fence)
        assertEquals(history, inbox.events.value)
        inbox.offer(session, event.copy(presentationTimeUs = 4))
        assertEquals(history.drop(1).map { it.presentationTimeUs } + 4L, inbox.events.value.map { it.presentationTimeUs })
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
