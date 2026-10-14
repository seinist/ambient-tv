package com.ambient.tv.network

import java.net.Inet6Address
import java.net.InetAddress

/**
 * LAN gate for PMS traffic. The separate plex.tv PIN/token client bypasses this policy by design
 * and is only invoked after an explicit user action.
 *
 * Actual PMS HTTP calls use [isLocalHost], which requires a hostname to resolve to a local
 * address. Wake-on-LAN may use [isPotentiallyLocalHost] so a sleeping machine referenced by an
 * unresolved single-label DNS/NetBIOS name can still receive a wake packet.
 */
object LocalNetworkPolicy {
    private const val DECISION_TTL_NS = 30_000_000_000L
    private val decisions = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Boolean>>()

    /**
     * Every API request and artwork URL asks this, artwork even on the main thread. A hostname
     * decision is reused for 30 s, so a name is resolved at most twice a minute instead of per call.
     */
    fun isLocalHost(host: String): Boolean {
        val normalized = normalize(host) ?: return false
        if (normalized.equals("localhost", true) || normalized.endsWith(".local", true)) return true
        val now = System.nanoTime()
        decisions[normalized.lowercase()]?.let { (at, local) -> if (now - at in 0 until DECISION_TTL_NS) return local }
        val addresses = resolve(normalized)
        val local = addresses.any(::isLocalAddress)
        // A failed lookup is not cached: it may be a transient DNS hiccup.
        if (addresses.isNotEmpty()) decisions[normalized.lowercase()] = now to local
        return local
    }

    /** The addresses [host] resolves to right now, for explaining a LAN-only rejection. */
    fun resolvedAddresses(host: String): List<String> =
        normalize(host)?.let(::resolve).orEmpty().mapNotNull { it.hostAddress }.distinct()

    /** Forgets cached decisions, e.g. after a network change. */
    fun clearCache() = decisions.clear()

    fun isPotentiallyLocalHost(host: String): Boolean {
        val normalized = normalize(host) ?: return false
        if (normalized.equals("localhost", true) || normalized.endsWith(".local", true)) return true
        val resolved = resolve(normalized)
        if (resolved.isNotEmpty()) return resolved.any(::isLocalAddress)
        // Preserve WOL for sleeping hosts whose local DNS/NetBIOS record is temporarily absent.
        return !normalized.contains('.') && !normalized.contains(':')
    }

    private fun normalize(host: String): String? =
        host.trim().removePrefix("[").removeSuffix("]").takeIf { it.isNotBlank() }

    private fun resolve(host: String): List<InetAddress> =
        runCatching { InetAddress.getAllByName(host).toList() }.getOrDefault(emptyList())

    internal fun isLocalAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress) return true
        val bytes = address.address
        if (bytes.size == 4) {
            val first = bytes[0].toInt() and 0xFF
            val second = bytes[1].toInt() and 0xFF
            // RFC 6598 shared address space (100.64.0.0/10), commonly used inside ISP/private edges.
            if (first == 100 && second in 64..127) return true
        }
        if (address is Inet6Address) {
            val first = address.address.firstOrNull()?.toInt()?.and(0xFF) ?: return false
            // RFC 4193 Unique Local Address fc00::/7.
            if (first and 0xFE == 0xFC) return true
        }
        return false
    }
}
