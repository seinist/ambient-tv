package com.ambient.tv.ui.player.backend

import okhttp3.Dns
import okhttp3.HttpUrl
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * A manifest can explicitly refer to a public HTTPS CDN. Such requests are anonymous
 * (no source headers/cookies) and must not turn a remote playlist into a LAN proxy.
 * Validate DNS in the client that actually connects, not only in the playlist parser.
 */
internal fun vlcRelayCdnUrlAllowed(url: HttpUrl): Boolean {
    val host = url.host.lowercase(java.util.Locale.ROOT).trimEnd('.')
    // Some HTTP stacks do not invoke DNS for an IP literal. Reject private literal
    // addresses here too; do not resolve ordinary DNS names in the manifest parser.
    if (':' in host || host.all { it in '0'..'9' || it == '.' }) {
        if (!runCatching { vlcRelayPublicAddress(InetAddress.getByName(host)) }.getOrDefault(false)) return false
    }
    return url.scheme == "https" && url.port == 443 &&
        url.username.isEmpty() && url.password.isEmpty() &&
        ('.' in host || ':' in host) &&
        host != "localhost" && !host.endsWith(".localhost") && !host.endsWith(".local") &&
        !host.endsWith(".internal") && !host.endsWith(".home.arpa") &&
        !host.endsWith(".test") && !host.endsWith(".invalid")
}

internal fun vlcRelayResourceAllowed(origin: HttpUrl, url: HttpUrl): Boolean =
    url.username.isEmpty() && url.password.isEmpty() &&
        (vlcRelayOriginAllowed(origin, url) || vlcRelayCdnUrlAllowed(url))

/** Conservatively allow global unicast addresses; no private, special-purpose or mapped address. */
internal fun vlcRelayPublicAddress(address: InetAddress): Boolean {
    if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
        address.isSiteLocalAddress || address.isMulticastAddress) return false
    val b = address.address.map { it.toInt() and 255 }
    if (b.size == 4) {
        val (a, c, d) = b
        return when {
            a == 0 || a == 10 || a == 127 || a >= 224 -> false
            a == 100 && c in 64..127 -> false
            a == 169 && c == 254 -> false
            a == 172 && c in 16..31 -> false
            a == 192 && c == 168 -> false
            a == 192 && c == 0 && d in setOf(0, 2) -> false
            a == 192 && c == 88 && d == 99 -> false
            a == 198 && c in 18..19 -> false
            a == 198 && c == 51 && d == 100 -> false
            a == 203 && c == 0 && d == 113 -> false
            else -> true
        }
    }
    if (b.size != 16 || b[0] !in 0x20..0x3f) return false
    // Reject the transition/translation networks and IPv6 documentation/special-use blocks.
    if (b[0] == 0x20 && b[1] == 0x02) return false
    if (b[0] == 0x20 && b[1] == 0x01 &&
        (b[2] < 2 || b[2] == 0x0d && b[3] == 0xb8)) return false
    if (b[0] == 0x3f && b[1] == 0xff && b[2] < 0x10) return false
    return true
}

internal class VlcRelayPublicDns(private val delegate: Dns = Dns.SYSTEM) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val addresses = delegate.lookup(hostname)
        if (addresses.isEmpty() || addresses.any { !vlcRelayPublicAddress(it) })
            throw UnknownHostException("Non-public media CDN address")
        return addresses
    }
}
