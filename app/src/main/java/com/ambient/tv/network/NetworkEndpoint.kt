package com.ambient.tv.network

/** Small, Android-free helpers for host/port values entered in settings. */
object NetworkEndpoint {
    /**
     * Normalize a user supplied endpoint to a bare hostname/IP. Accepts the forms users
     * commonly paste into Settings: host, host:port, http(s)://host[:port][/path], and
     * bracketed IPv6. Keeping this canonical is important because playback URLs are built
     * outside Retrofit as well.
     */
    fun normalizeHost(value: String): String {
        // Pasted values can carry zero-width or other invisible characters that break DNS lookups.
        val trimmed = value.filterNot { Character.getType(it) == Character.FORMAT.toInt() || it.isISOControl() }.trim()
        if (trimmed.isBlank()) return ""
        val schemeIndex = trimmed.indexOf("://")
        val withoutScheme = if (schemeIndex >= 0) trimmed.substring(schemeIndex + 3) else trimmed
        // user:password@host is a valid URL form; the credentials are not part of the host.
        val authority = withoutScheme.substringBefore('/').substringBefore('?').substringBefore('#')
            .substringAfterLast('@').trim()
        if (authority.isBlank()) return ""
        if (authority.startsWith('[') && authority.contains(']')) {
            return authority.substringAfter('[').substringBefore(']').trim()
        }
        // More than one colon means a bare IPv6 literal. A port on IPv6 must be bracketed.
        return if (authority.count { it == ':' } > 1) authority else authority.substringBefore(':').trim()
    }

    fun authorityHost(host: String): String {
        val normalized = normalizeHost(host)
        return if (normalized.contains(':')) "[$normalized]" else normalized
    }

    /** Extracts a hostname/IP from either a bare host or a URL-like host/path setting. */
    fun extractHost(value: String): String = normalizeHost(value)

    /**
     * Compare complete network endpoints, rather than just host names. Several media servers
     * commonly share one LAN host behind different ports (for example TVHeadend and Jellyfin).
     */
    fun sameEndpoint(requestHost: String, requestPort: Int, configuredHost: String, configuredPort: Int): Boolean =
        normalizeHost(requestHost).equals(normalizeHost(configuredHost), ignoreCase = true) &&
            requestPort == configuredPort

    fun validPortOrDefault(port: Int, defaultPort: Int): Int =
        port.takeIf { it in 1..65535 } ?: defaultPort
}
