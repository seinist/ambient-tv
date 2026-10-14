package com.ambient.tv.network

/**
 * Normalizes optional reverse-proxy base paths (Jellyfin/Emby) and rejects traversal segments.
 * Empty input becomes ""; otherwise a single leading slash with no trailing slash.
 */
object BasePathNormalizer {
    fun normalize(value: String): String {
        val trimmed = value.trim().trim('/')
        if (trimmed.isBlank()) return ""
        val segments = trimmed.split('/').filter { it.isNotEmpty() }
        require(segments.none { it == "." || it == ".." }) {
            "Base path must not contain '.' or '..' segments"
        }
        return "/" + segments.joinToString("/")
    }
}
