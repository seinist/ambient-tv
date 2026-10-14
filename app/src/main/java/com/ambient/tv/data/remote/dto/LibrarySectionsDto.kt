package com.ambient.tv.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

@Serializable
data class LibrarySectionsResponse(
    @SerialName("MediaContainer") val container: LibrarySectionsContainer,
)

@Serializable
data class LibrarySectionsContainer(
    val size: Int = 0,
    @SerialName("Directory") val directories: List<DirectoryDto> = emptyList(),
)

@Serializable
data class DirectoryDto(
    val key: String,
    val title: String,
    val type: String,
    val agent: String? = null,
    val art: String? = null,
    val thumb: String? = null,
)

/** Filter choices of a library, e.g. its genres; [key] is the id the `genre=` filter expects. */
@Serializable
data class FilterChoicesResponse(
    @SerialName("MediaContainer") val container: FilterChoicesContainer,
)

@Serializable
data class FilterChoicesContainer(
    @SerialName("Directory") val choices: List<FilterChoiceDto> = emptyList(),
)

@Serializable
data class FilterChoiceDto(
    val key: String = "",
    val title: String = "",
)

@Serializable
data class HubsResponse(
    @SerialName("MediaContainer") val container: HubsContainer,
)

@Serializable
data class HubsContainer(
    @SerialName("Hub") val hubs: List<HubDto> = emptyList(),
)

@Serializable
data class HubDto(
    val hubIdentifier: String = "",
    val title: String = "",
    val type: String = "",
    /** Kept raw: a hub may list entries (clips, people) that are no library [MetadataDto]. */
    @SerialName("Metadata") val metadata: List<JsonElement> = emptyList(),
) {
    fun mediaItems(): List<MetadataDto> = metadata.mapNotNull { element ->
        runCatching { hubItemJson.decodeFromJsonElement(MetadataDto.serializer(), element) }.getOrNull()
    }
}

private val hubItemJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
}
