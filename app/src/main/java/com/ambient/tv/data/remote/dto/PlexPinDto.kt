package com.ambient.tv.data.remote.dto

import kotlinx.serialization.Serializable

@Serializable
data class PlexPinDto(
    val id: Long,
    /** Strong (long) code when requested with strong=true; otherwise the 4-character PIN. */
    val code: String,
    val authToken: String? = null,
    /** Separate manual PIN, if plex.tv ever returns one alongside [code]. Absent today. */
    val shortCode: String? = null,
)
