package com.ambient.tv.domain.model

@kotlinx.serialization.Serializable
data class LibrarySection(
    val key: String,
    val title: String,
    val type: String,
    val thumbUrl: String?,
    val artUrl: String?,
)
