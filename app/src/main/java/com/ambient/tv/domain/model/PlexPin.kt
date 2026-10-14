package com.ambient.tv.domain.model

data class PlexPin(
    val id: Long,
    /** Code embedded in the auth QR. Long when the pin was created with strong=true. */
    val code: String,
    /** 4-character plex.tv/link PIN when the API returns one separately from [code]. */
    val shortCode: String? = null,
)
