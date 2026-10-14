package com.ambient.tv.wol

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton

enum class MacAddressSource {
    PROC_ARP,
    IP_NEIGH,
    ARP_COMMAND,
}

data class MacAddressResolution(
    val ipAddress: String,
    val macAddress: String,
    val source: MacAddressSource,
)

/**
 * Best-effort MAC discovery for a Plex server on the same L2 network.
 *
 * Android does not expose a public API for arbitrary LAN-neighbour MAC addresses. We therefore:
 *  1. resolve the configured hostname to IPv4,
 *  2. briefly touch the Plex TCP port to populate the kernel neighbour table,
 *  3. inspect the available ARP/neighbour views.
 *
 * The lookup deliberately matches the exact server IP. This avoids accidentally persisting a
 * router/gateway MAC for servers reached through another subnet/VLAN.
 */
@Singleton
class MacAddressResolver @Inject constructor() {

    suspend fun resolveMacAddress(host: String, port: Int = 32400): String? =
        resolve(host, port)?.macAddress

    suspend fun resolve(host: String, port: Int = 32400): MacAddressResolution? =
        withContext(Dispatchers.IO) {
            val addresses = runCatching { InetAddress.getAllByName(host) }
                .onFailure { Timber.d(it, "Unable to resolve host for MAC discovery: $host") }
                .getOrNull()
                ?.filterIsInstance<Inet4Address>()
                .orEmpty()

            for (address in addresses) {
                val ip = address.hostAddress ?: continue
                primeNeighbourCache(ip, port)

                readFromProcNetArp(ip)?.let { mac ->
                    return@withContext MacAddressResolution(ip, mac, MacAddressSource.PROC_ARP)
                }
                readFromIpNeighShow(ip)?.let { mac ->
                    return@withContext MacAddressResolution(ip, mac, MacAddressSource.IP_NEIGH)
                }
                readFromArpCommand(ip)?.let { mac ->
                    return@withContext MacAddressResolution(ip, mac, MacAddressSource.ARP_COMMAND)
                }
            }
            null
        }

    private fun primeNeighbourCache(ipAddress: String, port: Int) {
        // A completed TCP connect is not required: even attempting to reach the neighbour causes
        // the kernel to perform ARP/NDP resolution on directly connected networks.
        runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ipAddress, port), 900)
            }
        }.onFailure { Timber.v(it, "Neighbour-cache priming did not connect to $ipAddress:$port") }
    }

    private fun readFromProcNetArp(ipAddress: String): String? = runCatching {
        File("/proc/net/arp").readLines().drop(1).firstNotNullOfOrNull { line ->
            val columns = line.trim().split(Regex("\\s+"))
            if (columns.size >= 4 && columns[0] == ipAddress) normalizeMac(columns[3]) else null
        }
    }.onFailure { Timber.d(it, "/proc/net/arp not readable") }.getOrNull()

    private fun readFromIpNeighShow(ipAddress: String): String? = runCatching {
        val process = Runtime.getRuntime().exec(arrayOf("ip", "neigh", "show", ipAddress))
        val output = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor()
        Regex("lladdr\\s+([0-9a-fA-F:-]{17})")
            .find(output)
            ?.groupValues
            ?.get(1)
            ?.let(::normalizeMac)
    }.onFailure { Timber.d(it, "`ip neigh show` failed") }.getOrNull()

    private fun readFromArpCommand(ipAddress: String): String? = runCatching {
        val process = Runtime.getRuntime().exec(arrayOf("arp", "-n", ipAddress))
        val output = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor()
        Regex("([0-9a-fA-F]{2}[:-]){5}[0-9a-fA-F]{2}")
            .find(output)
            ?.value
            ?.let(::normalizeMac)
    }.onFailure { Timber.d(it, "`arp -n` failed") }.getOrNull()

    private fun normalizeMac(value: String): String? {
        val normalized = value.trim().replace('-', ':').uppercase()
        if (!MAC_PATTERN.matches(normalized)) return null
        if (normalized == "00:00:00:00:00:00") return null
        if (normalized == "FF:FF:FF:FF:FF:FF") return null
        return normalized
    }

    private companion object {
        val MAC_PATTERN = Regex("(?:[0-9A-F]{2}:){5}[0-9A-F]{2}")
    }
}
