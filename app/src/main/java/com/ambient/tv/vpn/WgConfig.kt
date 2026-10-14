package com.ambient.tv.vpn

import java.util.Base64
import kotlinx.serialization.Serializable

@Serializable
data class WireGuardProfile(
    val address: String = "",
    val prefix: Int = 32,
    val dns: String = "",
    val mtu: Int = 1280,
    val peerPublicKey: String = "",
    val endpointHost: String = "",
    val endpointPort: Int = 51820,
    val allowedIps: List<String> = emptyList(),
    val keepaliveSeconds: Int = 25,
) {
    fun normalized(): WireGuardProfile = copy(
        address = address.trim(),
        prefix = prefix.coerceIn(0, 32),
        dns = dns.trim(),
        mtu = mtu.coerceIn(1280, 1500),
        peerPublicKey = peerPublicKey.trim(),
        endpointHost = endpointHost.trim(),
        endpointPort = endpointPort.coerceIn(1, 65535),
        allowedIps = allowedIps.map { it.trim() }.filter { it.isNotEmpty() },
        keepaliveSeconds = keepaliveSeconds.coerceIn(0, 65535),
    )

    fun ready(): Boolean = address.isNotBlank() && peerPublicKey.isNotBlank() && endpointHost.isNotBlank() && allowedIps.isNotEmpty()
}

data class WireGuardSecretConfig(
    val profile: WireGuardProfile,
    val privateKey: String,
    val presharedKey: String?,
    /** Further [Peer] sections that were not used: the tunnel carries exactly one peer. */
    val ignoredPeers: Int = 0,
)

data class Cidr(val address: Int, val prefix: Int)

/** Default inner MTU of WireGuard itself; fits a 1500-byte path with an IPv6 endpoint too. */
const val WG_DEFAULT_MTU = 1420

/**
 * Text of a tunnel file as it comes in bytes. Windows tools write UTF-16 (PowerShell `>`,
 * Notepad "Unicode") or UTF-8 with a byte order mark; both must read like plain UTF-8.
 */
fun decodeWireGuardBytes(bytes: ByteArray): String {
    fun has(vararg lead: Int) = bytes.size >= lead.size && lead.indices.all { (bytes[it].toInt() and 0xff) == lead[it] }
    return when {
        has(0xFF, 0xFE) -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        has(0xFE, 0xFF) -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        has(0xEF, 0xBB, 0xBF) -> String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        // UTF-16 without a mark: ASCII text with a zero in every second byte.
        bytes.size >= 4 && bytes[1].toInt() == 0 && bytes[0].toInt() != 0 && bytes[3].toInt() == 0 -> String(bytes, Charsets.UTF_16LE)
        bytes.size >= 4 && bytes[0].toInt() == 0 && bytes[1].toInt() != 0 && bytes[2].toInt() == 0 -> String(bytes, Charsets.UTF_16BE)
        else -> String(bytes, Charsets.UTF_8)
    }
}

private fun String.unquoted(): String = trim().let { v ->
    if (v.length >= 2 && ((v.first() == '"' && v.last() == '"') || (v.first() == '\'' && v.last() == '\''))) v.substring(1, v.length - 1).trim() else v
}

private fun String.csv(): List<String> = split(',', ' ', '\t').map { it.unquoted() }.filter { it.isNotEmpty() }

private val LIST_KEYS = setOf("address", "dns", "allowedips")

private class ParsedPeer(val values: MutableMap<String, String> = linkedMapOf())

/**
 * Parses a wg-quick style tunnel file: WireGuard app exports, FRITZ!Box, Mullvad, ProtonVPN,
 * NordLynx, Cloudflare WARP (wgcf), pivpn, wg-easy and hand-written files.
 *
 * Tolerated: any key case, spaces around `=`, `#` comments (own line or after a value), CRLF,
 * quoted values, unknown keys and sections (PostUp, Table, ListenPort, FwMark, SaveConfig,
 * AmneziaWG fields, …), dual-stack lists (the tunnel is IPv4: IPv6 entries are skipped), DNS search
 * domains, an address without prefix, `PersistentKeepalive = off`, IPv6 and hostname endpoints.
 * With several peers the first one that has an Endpoint is used.
 */
fun parseWireGuardConfig(text: String): WireGuardSecretConfig {
    val interfaceValues = linkedMapOf<String, String>()
    val peers = mutableListOf<ParsedPeer>()
    var section = ""
    text.removePrefix("\uFEFF").lineSequence().forEach { raw ->
        val line = raw.substringBefore('#').trim()
        if (line.isEmpty()) return@forEach
        if (line.startsWith("[") && line.contains("]")) {
            section = line.substringAfter('[').substringBefore(']').trim().lowercase()
            if (section == "peer") peers += ParsedPeer()
            return@forEach
        }
        if ('=' !in line) return@forEach
        val key = line.substringBefore("=").trim().lowercase()
        val value = line.substringAfter("=", "").trim()
        if (key.isEmpty() || value.isEmpty()) return@forEach
        // wg-quick accumulates repeated list keys (Cloudflare WARP writes several Address/AllowedIPs lines).
        fun put(target: MutableMap<String, String>) {
            val existing = target[key]
            target[key] = if (existing != null && key in LIST_KEYS) "$existing,$value" else value
        }
        when (section) {
            "interface" -> put(interfaceValues)
            "peer" -> put(peers.last().values)
        }
    }
    require(interfaceValues.isNotEmpty() || peers.isNotEmpty()) { "No [Interface] or [Peer] section found" }
    require(peers.isNotEmpty()) { "[Peer] section is missing" }
    val peer = peers.firstOrNull { it.values["endpoint"] != null } ?: peers.first()
    val peerValues = peer.values

    val privateKey = (interfaceValues["privatekey"] ?: throw IllegalArgumentException("PrivateKey is missing")).unquoted()
    val publicKey = (peerValues["publickey"] ?: throw IllegalArgumentException("PublicKey is missing")).unquoted()
    checkKey("PrivateKey", privateKey); checkKey("PublicKey", publicKey)
    val preshared = peerValues["presharedkey"]?.unquoted()?.takeIf { it.isNotEmpty() }
    preshared?.let { checkKey("PresharedKey", it) }

    // The tunnel is IPv4-only: take the IPv4 entries, skip IPv6 ones and DNS search domains.
    val addresses = (interfaceValues["address"] ?: throw IllegalArgumentException("Address is missing"))
        .csv().filter { !it.contains(':') }
    require(addresses.isNotEmpty()) { "Address needs an IPv4 address" }
    val (address, prefix) = splitPrefix(addresses.first())
    require(runCatching { ipv4ToInt(address) }.isSuccess && prefix in 0..32) { "Address is not a valid IPv4 address: ${addresses.first()}" }

    val dns = interfaceValues["dns"]?.csv()?.firstOrNull { runCatching { ipv4ToInt(it) }.isSuccess }.orEmpty()

    val endpoint = (peerValues["endpoint"] ?: throw IllegalArgumentException("Endpoint is missing")).unquoted()
    val (host, port) = parseEndpoint(endpoint)
    val allowed = (peerValues["allowedips"] ?: throw IllegalArgumentException("AllowedIPs is missing"))
        .csv().filter { !it.contains(':') }
    require(allowed.isNotEmpty()) { "AllowedIPs needs an IPv4 range" }
    allowed.forEach { require(runCatching { parseCidr(it) }.isSuccess) { "AllowedIPs entry is not a valid IPv4 range: $it" } }

    val keepalive = peerValues["persistentkeepalive"]?.trim()?.lowercase()?.let { v ->
        if (v == "off") 0 else v.toIntOrNull() ?: 25
    } ?: 25
    return WireGuardSecretConfig(
        profile = WireGuardProfile(
            address = address,
            prefix = prefix,
            dns = dns,
            mtu = interfaceValues["mtu"]?.trim()?.toIntOrNull() ?: WG_DEFAULT_MTU,
            peerPublicKey = publicKey,
            endpointHost = host,
            endpointPort = port,
            allowedIps = allowed,
            keepaliveSeconds = keepalive,
        ).normalized(),
        privateKey = privateKey,
        presharedKey = preshared,
        ignoredPeers = peers.size - 1,
    )
}

/** `host:port`, `[v6]:port` or `v6-literal` is rejected for lack of a port. */
private fun parseEndpoint(endpoint: String): Pair<String, Int> {
    val host: String
    val portText: String
    if (endpoint.startsWith("[")) {
        require(endpoint.contains("]:")) { "Endpoint must be [address]:port, got $endpoint" }
        host = endpoint.substringAfter('[').substringBefore(']')
        portText = endpoint.substringAfterLast("]:")
    } else {
        require(endpoint.count { it == ':' } == 1) { "Endpoint must be host:port, got $endpoint" }
        host = endpoint.substringBefore(':')
        portText = endpoint.substringAfter(':')
    }
    val port = portText.trim().toIntOrNull()
    require(host.isNotBlank() && port != null && port in 1..65535) { "Endpoint must be host:port, got $endpoint" }
    return host.trim() to port
}

private fun checkKey(name: String, value: String) {
    require(runCatching { decodeKey(value) }.isSuccess) { "$name is not a valid WireGuard key (44 Base64 characters)" }
}

fun decodeKey(text: String): ByteArray {
    // Whitespace inside (wrapped lines) and a missing "=" pad are common in hand-copied keys.
    val compact = text.filterNot { it.isWhitespace() }
    val padded = compact + "=".repeat((4 - compact.length % 4) % 4)
    val decoded = Base64.getDecoder().decode(padded)
    require(decoded.size == 32)
    return decoded
}

fun parseCidr(text: String): Cidr {
    val address = text.substringBefore("/").trim()
    val prefix = text.substringAfter("/", "32").toInt()
    require(prefix in 0..32)
    return Cidr(ipv4ToInt(address), prefix)
}

fun ipv4ToInt(text: String): Int {
    val parts = text.split(".")
    require(parts.size == 4)
    var value = 0
    parts.forEach { part ->
        val octet = part.toInt()
        require(octet in 0..255)
        value = (value shl 8) or octet
    }
    return value
}

fun intToIpv4(value: Int): String = List(4) { octet -> (value ushr (24 - 8 * octet)) and 255 }.joinToString(".")

fun covers(cidr: Cidr, ip: Int): Boolean {
    if (cidr.prefix == 0) return true
    val mask = if (cidr.prefix >= 32) -1 else (-1 shl (32 - cidr.prefix))
    return cidr.address and mask == ip and mask
}

fun directedBroadcast(cidr: Cidr): String? {
    if (cidr.prefix !in 8..30) return null
    val mask = -1 shl (32 - cidr.prefix)
    return intToIpv4(cidr.address or mask.inv())
}

fun wakeTargetsFor(allowedIps: List<String>, hosts: List<String>): List<String> {
    val cidrs = allowedIps.mapNotNull { runCatching { parseCidr(it) }.getOrNull() }
    val targets = mutableListOf<String>()
    hosts.forEach { host ->
        val ip = runCatching { ipv4ToInt(host.trim()) }.getOrNull() ?: return@forEach
        val covering = cidrs.filter { covers(it, ip) }
        if (covering.isEmpty()) return@forEach
        targets += intToIpv4(ip)
        covering.mapNotNull(::directedBroadcast).forEach(targets::add)
    }
    return targets.distinct()
}

/** Routes installed for the enabled systems: the server itself, plus directed wake, not the whole AllowedIPs list. */
fun tunnelRoutes(allowedIps: List<String>, hosts: List<String>): List<String> {
    val cidrs = allowedIps.mapNotNull { runCatching { parseCidr(it) }.getOrNull() }
    val routes = linkedSetOf<String>()
    var includeAll = false
    hosts.forEach { host ->
        val ip = runCatching { ipv4ToInt(host.trim()) }.getOrNull()
        if (ip == null) {
            if (host.isNotBlank()) includeAll = true
            return@forEach
        }
        val covering = cidrs.filter { covers(it, ip) }
        if (covering.isEmpty()) return@forEach
        routes += "${intToIpv4(ip)}/32"
        covering.mapNotNull(::directedBroadcast).forEach { routes += "$it/32" }
    }
    if (includeAll) {
        cidrs.forEach { cidr ->
            val mask = if (cidr.prefix <= 0) 0 else if (cidr.prefix >= 32) -1 else -1 shl (32 - cidr.prefix)
            routes += "${intToIpv4(cidr.address and mask)}/${cidr.prefix}"
        }
    }
    return routes.toList()
}

fun hostCovered(allowedIps: List<String>, host: String): Boolean {
    if (host.isBlank()) return false
    val ip = runCatching { ipv4ToInt(host.trim()) }.getOrNull() ?: return false
    return ipCovered(allowedIps, ip)
}

fun ipCovered(allowedIps: List<String>, ip: Int): Boolean =
    allowedIps.mapNotNull { runCatching { parseCidr(it) }.getOrNull() }.any { covers(it, ip) }

/** AllowedIPs parsed once per tunnel instead of once per packet. */
fun parseAllowedIps(allowedIps: List<String>): List<Cidr> =
    allowedIps.mapNotNull { runCatching { parseCidr(it) }.getOrNull() }

/**
 * Length of the valid IPv4 packet at the start of [packet] (first [length] bytes) whose source lies
 * in [allowed], or -1. Allocation-free variant of [validateInboundIpv4] for the tunnel loop.
 */
fun inboundIpv4Length(packet: ByteArray, length: Int, allowed: List<Cidr>): Int {
    if (length < 20 || (packet[0].toInt() ushr 4 and 0x0f) != 4) return -1
    val headerLength = (packet[0].toInt() and 0x0f) * 4
    if (headerLength < 20 || length < headerLength) return -1
    val totalLength = ((packet[2].toInt() and 0xff) shl 8) or (packet[3].toInt() and 0xff)
    if (totalLength < headerLength || totalLength > length) return -1
    val source = ((packet[12].toInt() and 0xff) shl 24) or
        ((packet[13].toInt() and 0xff) shl 16) or
        ((packet[14].toInt() and 0xff) shl 8) or (packet[15].toInt() and 0xff)
    return if (allowed.any { covers(it, source) }) totalLength else -1
}

/** Validate decrypted transport data before it is injected into the TUN interface. */
fun validateInboundIpv4(packet: ByteArray, allowedIps: List<String>): ByteArray? {
    if (packet.size < 20 || (packet[0].toInt() ushr 4 and 0x0f) != 4) return null
    val headerLength = (packet[0].toInt() and 0x0f) * 4
    if (headerLength < 20 || packet.size < headerLength) return null
    val totalLength = ((packet[2].toInt() and 0xff) shl 8) or (packet[3].toInt() and 0xff)
    if (totalLength < headerLength || totalLength > packet.size) return null
    val source = ((packet[12].toInt() and 0xff) shl 24) or
        ((packet[13].toInt() and 0xff) shl 16) or
        ((packet[14].toInt() and 0xff) shl 8) or (packet[15].toInt() and 0xff)
    if (!ipCovered(allowedIps, source)) return null
    return if (totalLength == packet.size) packet else packet.copyOf(totalLength)
}

private fun splitPrefix(value: String): Pair<String, Int> {
    val first = value.split(",").first().trim()
    return first.substringBefore("/") to first.substringAfter("/", "32").toInt()
}
