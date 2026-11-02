package com.ambient.tv.wol

/**
 * The server is a private address that none of this device's local networks contains (on the
 * road, on mobile data, in another Wi-Fi). A Magic Packet broadcast cannot reach it from here,
 * so waking is not started at all instead of counting down a wake that cannot work.
 */
class WolNotOnLocalNetworkException(val host: String) :
    IllegalStateException("$host is not in this device's local network")

/** The four bytes of a literal private IPv4 address (RFC 1918 and link-local), else null. */
internal fun privateIpv4Bytes(host: String): ByteArray? {
    val parts = host.trim().split('.')
    if (parts.size != 4) return null
    val bytes = parts.map { part ->
        if (part.isEmpty() || part.length > 3 || part.any { !it.isDigit() }) return null
        part.toInt().takeIf { it in 0..255 } ?: return null
    }
    val (a, b) = bytes
    val private = a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 169 && b == 254)
    return if (private) ByteArray(4) { bytes[it].toByte() } else null
}

/** Whether [host] lies in the IPv4 network [address]/[prefixLength]. */
internal fun ipv4InSubnet(host: ByteArray, address: ByteArray, prefixLength: Int): Boolean {
    if (host.size != 4 || address.size != 4 || prefixLength !in 0..32) return false
    var remaining = prefixLength
    for (i in 0 until 4) {
        val bits = remaining.coerceIn(0, 8)
        remaining -= bits
        if (bits == 0) return true
        val mask = (0xFF shl (8 - bits)) and 0xFF
        if ((host[i].toInt() and mask) != (address[i].toInt() and mask)) return false
    }
    return true
}

/**
 * Whether a local broadcast can reach [host]. Unknown cases count as reachable: host names,
 * public addresses (routed WOL), a configured directed broadcast, WireGuard wake targets.
 * [localSubnets] are this device's Wi-Fi/Ethernet IPv4 addresses with their prefix lengths.
 */
internal fun wolLocalBroadcastCanReach(
    host: String?,
    broadcastAddress: String,
    hasWireGuardTarget: Boolean,
    localSubnets: List<Pair<ByteArray, Int>>,
): Boolean {
    val target = host?.let(::privateIpv4Bytes) ?: return true
    if (hasWireGuardTarget) return true
    if (broadcastAddress.trim() != "255.255.255.255") return true
    return localSubnets.any { (address, prefix) -> ipv4InSubnet(target, address, prefix) }
}
