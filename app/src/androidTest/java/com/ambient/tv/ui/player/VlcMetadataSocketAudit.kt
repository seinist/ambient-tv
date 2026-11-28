package com.ambient.tv.ui.player

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.SystemClock
import com.ambient.tv.ui.player.backend.VlcTimedMetadataInbox
import com.ambient.tv.ui.player.backend.VlcTimedMetadataReceiver
import java.io.DataInputStream
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real Android LocalServerSocket receiver; same golden header as native encoder test. */
internal fun auditVlcMetadataSocket(log: (String) -> Unit) {
    val inbox = VlcTimedMetadataInbox()
    val session = inbox.beginSession()
    inbox.clearForSeek(1234000, 41)
    inbox.clearForSeek(1234000, 42)
    val processed = CountDownLatch(4)
    val receiver = checkNotNull(VlcTimedMetadataReceiver.open {
        val accepted = inbox.offer(session, it)
        processed.countDown()
        accepted
    })
    fun packet(epoch: Int, id: Long, event: Boolean, packetId: Long = id): ByteArray =
        ByteBuffer.allocate(if (event) 64 else 48).apply {
            putInt(if (event) 0x41464d34 else 0x41464d33); putInt(if (event) 0 else 2)
            putLong(if (event) 250000L else 0L)
            putLong(if (event) 100000L else 0L)
            putInt(if (event) 8 else 0); putInt(epoch)
            putLong(1234000); putLong(id)
            if (event) { putLong(packetId); putInt(8); putInt(0x656d7367) }
        }.array()
    fun send(bytes: ByteArray, ack: Int? = null, accepted: Boolean = true) {
        LocalSocket().use { socket ->
            socket.connect(LocalSocketAddress(receiver.socketName, LocalSocketAddress.Namespace.ABSTRACT))
            socket.soTimeout = 2000
            socket.outputStream.write(bytes, 0, 13)
            socket.outputStream.write(bytes, 13, bytes.size - 13)
            socket.outputStream.flush()
            if (ack != null) {
                val input = DataInputStream(socket.inputStream)
                check(input.readInt() == (if (accepted) 0x41464131 else 0x41464132) && input.readInt() == ack)
            }
        }
    }
    receiver.use {
        val golden = "41464d3300000002000000000000000000000000000000000000000000000007000000000012d450000000000000002a"
        check(packet(7, 42, false).contentEquals(golden.chunked(2).map { it.toInt(16).toByte() }.toByteArray()))
        send(packet(6, 41, true), 6, accepted = false)
        send(packet(7, 42, false), 7)
        send(packet(7, 42, true), 7)
        send(packet(7, 42, true), 7) // retransmission after a lost ACK
        check(processed.await(3, TimeUnit.SECONDS))
        check(inbox.events.value.single().seekRequestId == 42L)
        inbox.clearForSeek(1234000, 43)
        send(packet(8, 42, true), 8, accepted = false)
        send(packet(9, 43, false), 9)
        send(packet(9, 43, true), 9)
        val end = SystemClock.elapsedRealtime() + 3000
        while (inbox.events.value.singleOrNull()?.seekRequestId != 43L && SystemClock.elapsedRealtime() < end) {
            Thread.sleep(10)
        }
        check(inbox.events.value.single().seekRequestId == 43L)
        inbox.beginSession()
        send(packet(10, 43, false), 10, accepted = false)
        check(inbox.events.value.isEmpty())
    }
    log("AFM3 Android socket, fragmented packets, ACK and repeated seek identity passed")
}
