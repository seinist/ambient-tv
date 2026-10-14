package com.ambient.tv.data.remote.dto

import kotlinx.serialization.Serializable

/** One entry of plex.tv `api/v2/resources`: a server or player the account can reach. */
@Serializable
data class PlexResourceDto(
    val name: String? = null,
    val clientIdentifier: String? = null,
    /** Comma-separated roles, e.g. "server" or "client,player". */
    val provides: String? = null,
    val owned: Boolean = false,
    /** Token for this server; differs from the account token on servers shared by friends/home. */
    val accessToken: String? = null,
    val connections: List<PlexResourceConnectionDto> = emptyList(),
)

@Serializable
data class PlexResourceConnectionDto(
    val protocol: String? = null,
    val address: String? = null,
    val port: Int? = null,
    val uri: String? = null,
    val local: Boolean = false,
)
