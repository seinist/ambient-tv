package com.ambient.tv.vpn

import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.PortUnreachableException
import java.net.SocketTimeoutException

/**
 * The UDP side of the tunnel: one connected socket to the peer that survives what a real network
 * does. A send or receive error (ICMP port unreachable while the server restarts, ENETUNREACH during
 * a Wi-Fi/mobile switch) is a lost packet, never an exception that ends the tunnel threads; after
 * repeated errors, or when asked after a network change, the socket is rebuilt (protected again,
 * with the endpoint name resolved anew: dynamic-DNS endpoints change their address).
 */
internal class WgUdpLink(
    private val endpointHost: String,
    private val endpointPort: Int,
    private val protect: (DatagramSocket) -> Boolean,
    private val resolve: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
    private val receiveTimeoutMs: Int = 250,
) {
    @Volatile private var socket: DatagramSocket? = null
    @Volatile private var closed = false
    @Volatile var address: InetAddress? = null
        private set
    @Volatile private var sendFailures = 0
    @Volatile private var receiveFailures = 0

    /** Opens the first socket; throws when the endpoint cannot be resolved or the socket not protected. */
    @Synchronized
    fun open() {
        if (closed) return
        val resolved = pick(resolve(endpointHost)) ?: throw java.net.UnknownHostException(endpointHost)
        connect(resolved)
    }

    /** IPv4 first: a dual-stack name often lists IPv6 first on phones without an IPv6 route. */
    private fun pick(addresses: List<InetAddress>): InetAddress? =
        addresses.filterIsInstance<Inet4Address>().firstOrNull() ?: addresses.firstOrNull()

    private fun connect(target: InetAddress) {
        val fresh = DatagramSocket()
        if (!protect(fresh)) { fresh.close(); error("protect") }
        // Datagrams from anything but the peer are discarded by the socket layer.
        fresh.connect(InetSocketAddress(target, endpointPort))
        fresh.soTimeout = receiveTimeoutMs
        val old = socket
        socket = fresh
        address = target
        sendFailures = 0
        receiveFailures = 0
        runCatching { old?.close() }
        // close() may have run concurrently (it is not synchronized so a stop never waits for DNS):
        // a socket built after it must not stay open.
        if (closed) runCatching { fresh.close() }
    }

    /** Rebuilds the socket, e.g. after a network change; an unresolvable name keeps the old address. */
    @Synchronized
    fun reopen() {
        if (closed) return
        val target = runCatching { pick(resolve(endpointHost)) }.getOrNull() ?: address ?: return
        runCatching { connect(target) }
    }

    /** True when the name now resolves to another address and the socket was moved there. */
    @Synchronized
    fun refreshEndpoint(): Boolean {
        if (closed) return false
        val fresh = runCatching { pick(resolve(endpointHost)) }.getOrNull() ?: return false
        if (fresh == address) return false
        return runCatching { connect(fresh) }.isSuccess
    }

    /** Sends one datagram; false when it was lost. Never throws. */
    fun send(data: ByteArray, length: Int = data.size): Boolean {
        val current = socket ?: return false
        return try {
            current.send(DatagramPacket(data, length))
            sendFailures = 0
            true
        } catch (_: IOException) {
            if (!closed && ++sendFailures % REOPEN_AFTER == 0) reopen()
            false
        }
    }

    /** Sends a prepared packet (reused buffer); false when it was lost. Never throws. */
    fun send(packet: DatagramPacket): Boolean {
        val current = socket ?: return false
        return try {
            current.send(packet)
            sendFailures = 0
            true
        } catch (_: IOException) {
            if (!closed && ++sendFailures % REOPEN_AFTER == 0) reopen()
            false
        }
    }

    /**
     * Receives into [packet]: its length, or -1 on timeout or a transient error. Never throws; a
     * socket that keeps failing is rebuilt.
     */
    fun receive(packet: DatagramPacket): Int {
        val current = socket ?: return -1
        return try {
            current.receive(packet)
            receiveFailures = 0
            packet.length
        } catch (_: SocketTimeoutException) {
            -1
        } catch (_: PortUnreachableException) {
            // The peer's host answered "nobody listens": it is restarting. Keep trying.
            -1
        } catch (_: IOException) {
            if (closed) return -1
            if (++receiveFailures >= REOPEN_AFTER) reopen() else Thread.sleep(ERROR_PAUSE_MS)
            -1
        }
    }

    fun close() {
        closed = true
        runCatching { socket?.close() }
    }

    companion object {
        /** Consecutive send/receive errors before the socket is rebuilt. */
        const val REOPEN_AFTER = 3
        private const val ERROR_PAUSE_MS = 100L
    }
}
