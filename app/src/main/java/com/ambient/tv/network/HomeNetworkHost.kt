package com.ambient.tv.network

import java.net.InetAddress

/**
 * Whether [host] names a server in the home network, decided from the name alone (no DNS, so it is
 * safe on the main thread): private/link-local/ULA literals, `.local`, single-label names and
 * `plex.direct` names that encode a private address. Any other name is treated as remote.
 */
internal fun isHomeNetworkHost(host: String): Boolean {
    val name = host.trim().removePrefix("[").removeSuffix("]").lowercase()
    if (name.isEmpty()) return false
    if (name == "localhost" || name.endsWith(".local") || '.' !in name && ':' !in name) return true
    literalAddress(plexDirectAddress(name) ?: name)?.let { return LocalNetworkPolicy.isLocalAddress(it) }
    return false
}

/** `192-168-1-10.<hash>.plex.direct` carries the server's address in its first label. */
private fun plexDirectAddress(host: String): String? {
    if (!host.endsWith(".plex.direct")) return null
    val label = host.substringBefore('.')
    return if (label.count { it == '-' } == 3) label.replace('-', '.') else label.replace('-', ':')
}

private fun literalAddress(host: String): InetAddress? {
    val ipv4 = Regex("\\d{1,3}(\\.\\d{1,3}){3}")
    val looksLiteral = ipv4.matches(host) || (':' in host && host.all { it.isLetterOrDigit() || it == ':' || it == '.' })
    // InetAddress resolves names; only hand it strings that are already addresses.
    return if (looksLiteral) runCatching { InetAddress.getByName(host) }.getOrNull() else null
}
