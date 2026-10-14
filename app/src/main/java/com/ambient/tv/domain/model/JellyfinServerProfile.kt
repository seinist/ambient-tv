package com.ambient.tv.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class JellyfinServerProfile(
    val id: String,
    val name: String,
    val host: String,
    val port: Int = 8096,
    val useHttps: Boolean = false,
    val basePath: String = "",
    val serverId: String? = null,
    val userId: String? = null,
    val username: String? = null,
    val authenticated: Boolean = false,
    val macAddress: String? = null,
    val autoWakeEnabled: Boolean = true,
)

data class DiscoveredJellyfinServer(
    val name: String,
    val host: String,
    val port: Int,
    val useHttps: Boolean,
    val basePath: String = "",
    val serverId: String? = null,
)
