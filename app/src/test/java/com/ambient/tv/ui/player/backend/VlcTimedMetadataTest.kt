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

    @Test fun packetPreservesOpaqueBoxAndClock() {
        val event = VlcTimedMetadataPacket.read(packet(2).inputStream())
        assertEquals(VlcTimedMetadata.Kind.EVENT_MESSAGE, event.kind)
        assertEquals(250000L, event.presentationTimeUs)
        assertEquals(100000L, event.durationUs)
        assertEquals(2L, event.seekEpoch)
        assertArrayEquals(byteArrayOf(0,0,0,8,101,109,115,103), event.box)
    }

    @Test fun rejectsOversizeWrongTypeAndTruncation() {
        assertTrue(runCatching { VlcTimedMetadataPacket.read(packet(size = 2 * 1024 * 1024).inputStream()) }.isFailure)
        assertTrue(runCatching { VlcTimedMetadataPacket.read(packet(type = 0x70726674).inputStream()) }.isFailure)
        assertTrue(runCatching { VlcTimedMetadataPacket.read(packet().copyOf(34).inputStream()) }.isFailure)
    }

    @Test fun isolatesMediaSessionsSeekEpochsAndBoundsHistory() {
        val inbox = VlcTimedMetadataInbox()
        val old = inbox.beginSession()
        val current = inbox.beginSession()
        val event = VlcTimedMetadataPacket.read(packet(1).inputStream())
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
