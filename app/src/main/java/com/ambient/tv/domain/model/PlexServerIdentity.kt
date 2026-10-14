package com.ambient.tv.domain.model

data class PlexServerIdentity(
    val machineIdentifier: String?,
    val version: String?,
    val claimed: Boolean,
)
