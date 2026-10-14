package com.ambient.tv.data.repository

import com.ambient.tv.data.remote.dto.PlexResourceDto
import java.net.URI

/**
 * The PIN login returns the *account* token. A server shared by a friend or a Plex Home owner
 * rejects that token (401), it needs the per-server `accessToken` from plex.tv resources.
 * The edited server is matched by machine identifier first, else by host and port of one of
 * its connections (plain address or plex.direct URI). Null means no unambiguous match.
 */
internal fun plexServerAccessToken(
    resources: List<PlexResourceDto>,
    machineIdentifier: String?,
    host: String,
    port: Int,
): String? = plexMatchingServer(resources, machineIdentifier, host, port)?.accessToken?.trim()

/** HTTPS `plex.direct` address (host, port) of [server] for the same machine the user typed in. */
internal fun plexDirectSecureAddress(server: PlexResourceDto, host: String): Pair<String, Int>? {
    val wantedHost = host.trim().removePrefix("[").removeSuffix("]")
    return server.connections
        .filter { it.protocol.equals("https", ignoreCase = true) && it.address.equals(wantedHost, ignoreCase = true) }
        .mapNotNull { connection ->
            val uri = connection.uri?.let { runCatching { URI(it) }.getOrNull() } ?: return@mapNotNull null
            val uriHost = uri.host ?: return@mapNotNull null
            if (!uriHost.endsWith(".plex.direct", ignoreCase = true)) return@mapNotNull null
            uriHost to (uri.port.takeIf { it > 0 } ?: connection.port ?: return@mapNotNull null)
        }
        .firstOrNull()
}

internal fun plexMatchingServer(
    resources: List<PlexResourceDto>,
    machineIdentifier: String?,
    host: String,
    port: Int,
): PlexResourceDto? {
    val servers = resources.filter { resource ->
        resource.provides.orEmpty().split(',').any { it.trim() == "server" } &&
            !resource.accessToken.isNullOrBlank()
    }
    val byId = machineIdentifier?.takeIf(String::isNotBlank)?.let { id ->
        servers.firstOrNull { it.clientIdentifier == id }
    }
    val match = byId ?: run {
        val wantedHost = host.trim().removePrefix("[").removeSuffix("]")
        if (wantedHost.isEmpty()) return null
        servers.filter { server ->
            server.connections.any { connection ->
                val uriHost = connection.uri?.let { runCatching { URI(it).host }.getOrNull() }
                    ?.removePrefix("[")?.removeSuffix("]")
                val samePort = (connection.port ?: connection.uri?.let { runCatching { URI(it).port }.getOrNull() }) == port
                samePort && (connection.address.equals(wantedHost, ignoreCase = true) ||
                    uriHost.equals(wantedHost, ignoreCase = true))
            }
        }.singleOrNull()
    }
    return match
}
