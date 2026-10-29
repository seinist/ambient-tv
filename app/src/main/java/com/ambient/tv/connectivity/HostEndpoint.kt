package com.ambient.tv.connectivity

import com.ambient.tv.network.NetworkEndpoint

internal data class HostEndpoint(val system: String, val host: String, val port: Int, val machine: String)

/** Shared identity for a saved Live TV profile and its reachability evidence. */
internal fun mediaServerProfileSourceId(system: String, profileId: String, userId: String? = null): String? {
    if (profileId.isBlank()) return null
    return when (system) {
        "plex" -> "plex:$profileId"
        "jellyfin", "emby" -> userId?.takeIf(String::isNotBlank)?.let { "$system:$profileId:$it" }
        else -> null
    }
}

/** A saved profile's request cannot report on an unrelated currently selected server. */
internal fun matchingHostEndpoint(
    endpoints: List<HostEndpoint>,
    host: String,
    port: Int,
    system: String? = null,
): HostEndpoint? = endpoints.firstOrNull {
    (system == null || it.system == system) && NetworkEndpoint.sameEndpoint(host, port, it.host, it.port)
}
