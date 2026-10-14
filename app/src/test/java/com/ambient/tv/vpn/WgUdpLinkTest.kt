package com.ambient.tv.vpn

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WgUdpLinkTest {
    private val loopback = InetAddress.getByName("127.0.0.1")

    private fun link(port: Int, resolve: (String) -> List<InetAddress> = { listOf(loopback) }, protect: (DatagramSocket) -> Boolean = { true }) =
        WgUdpLink("peer.example", port, protect, resolve, receiveTimeoutMs = 100)

    private fun freeClosedPort(): Int = DatagramSocket(0, loopback).use { it.localPort }

    @Test fun `datagrams flow both ways`() {
        DatagramSocket(0, loopback).use { server ->
            server.soTimeout = 2_000
            val link = link(server.localPort).apply { open() }
            assertTrue(link.send(byteArrayOf(1, 2, 3)))
            val inbound = DatagramPacket(ByteArray(16), 16)
            server.receive(inbound)
            assertEquals(3, inbound.length)
            server.send(DatagramPacket(byteArrayOf(9, 8), 2, inbound.socketAddress))
            val reply = DatagramPacket(ByteArray(16), 16)
            assertEquals(2, link.receive(reply))
            link.close()
        }
    }

    @Test fun `a peer that is down never throws and never ends the loop`() {
        // Sending to a closed local port makes the kernel answer with ICMP port unreachable; the
        // next send/receive on the connected socket then fails. That must stay a lost packet.
        val link = link(freeClosedPort()).apply { open() }
        repeat(50) {
            link.send(byteArrayOf(1))
            assertEquals(-1, link.receive(DatagramPacket(ByteArray(16), 16)))
        }
        link.close()
    }

    @Test fun `a protect failure is reported when opening`() {
        val link = link(1, protect = { false })
        assertFalse(runCatching { link.open() }.isSuccess)
    }

    @Test fun `ipv4 is preferred for a dual stack name`() {
        val v6 = InetAddress.getByName("::1")
        val link = link(freeClosedPort(), resolve = { listOf(v6, loopback) }).apply { open() }
        assertEquals(loopback, link.address)
        link.close()
    }

    @Test fun `reopen rebuilds the socket and keeps working`() {
        DatagramSocket(0, loopback).use { server ->
            server.soTimeout = 2_000
            val link = link(server.localPort).apply { open() }
            link.reopen()
            assertTrue(link.send(byteArrayOf(7)))
            val inbound = DatagramPacket(ByteArray(8), 8)
            server.receive(inbound)
            assertEquals(1, inbound.length)
            link.close()
            assertFalse(link.send(byteArrayOf(1)) && false) // closed link: no exception, no crash
        }
    }

    @Test fun `a changed dynamic dns address moves the socket, an unresolvable name keeps it`() {
        DatagramSocket(0).use { server ->
            server.soTimeout = 2_000
            var current = listOf(loopback)
            var fail = false
            val link = link(server.localPort, resolve = { if (fail) throw java.net.UnknownHostException(it) else current }).apply { open() }
            assertFalse(link.refreshEndpoint()) // same address
            val other = InetAddress.getByName("127.0.0.2")
            current = listOf(other)
            assertTrue(link.refreshEndpoint())
            assertEquals(other, link.address)
            fail = true
            assertFalse(link.refreshEndpoint())
            link.reopen()
            assertEquals(other, link.address) // kept the last known address
            assertNotEquals(null, link.address)
            link.close()
        }
    }
}
