package com.ambient.tv.network

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Binds explicit `Authorization` header values to the origin (scheme, host, port) they were
 * issued for. Image/artwork loaders pass caller-supplied headers to whatever URL a server
 * returned; a bound credential is dropped for any other origin, so e.g. the TVHeadend password
 * never reaches an external picon/logo host. Unbound header values are left untouched.
 *
 * The table is bounded by least-recent use: every bind and lookup refreshes a binding, so a
 * credential in use is never dropped (and so never becomes unbound, which would let it through).
 */
object CredentialScope {
    private const val MAX_BINDINGS = 64
    private val origins = object : LinkedHashMap<String, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > MAX_BINDINGS
    }

    fun bind(authorization: String, url: HttpUrl) {
        val bound = origin(url)
        synchronized(origins) { origins[authorization] = bound }
    }

    // Access-ordered: a lookup reorders the map, so reads take the lock as well.
    private fun boundOrigin(authorization: String): String? = synchronized(origins) { origins[authorization] }

    /** True unless [authorization] is bound to a different origin than [url]. */
    fun allows(authorization: String, url: HttpUrl): Boolean = boundOrigin(authorization)?.let { it == origin(url) } ?: true

    /** [headers] without bound `Authorization` values that belong to another origin than [url]. */
    fun scopedHeaders(url: String?, headers: Map<String, String>): Map<String, String> {
        if (headers.isEmpty()) return headers
        val parsed = url?.toHttpUrlOrNull()
        return headers.filter { (name, value) ->
            !name.equals("Authorization", ignoreCase = true) || boundOrigin(value) == null ||
                parsed != null && allows(value, parsed)
        }
    }

    private fun origin(url: HttpUrl) = "${url.scheme}://${url.host.lowercase().trimEnd('.')}:${url.port}"
}
