package com.ambient.tv.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class IdentityResponse(
    @SerialName("MediaContainer") val container: IdentityContainer,
)

@Serializable
data class IdentityContainer(
    val size: Int = 0,
    val machineIdentifier: String? = null,
    val version: String? = null,
    val claimed: Boolean = false,
)
