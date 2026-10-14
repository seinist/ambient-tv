package com.ambient.tv.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class ServerProfile(
    val id: String,
    val name: String,
    val host: String,
    val port: Int = 32400,
    val useHttps: Boolean = false,
    val machineIdentifier: String? = null,
    val macAddress: String? = null,
    val autoWakeEnabled: Boolean = true,
)

@Serializable
data class DiscoveredPlexServer(
    val name: String,
    val host: String,
    val port: Int,
    val machineIdentifier: String? = null,
    val version: String? = null,
)
