package com.ambient.tv.data.radio

const val RADIO_HOME_ITEM_PREFIX = "radio-station:"

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

@Serializable
data class RadioBrowserStationDto(
    @SerialName("stationuuid") val stationUuid: String = "",
    val name: String = "",
    val url: String = "",
    @SerialName("url_resolved") val resolvedUrl: String = "",
    val homepage: String = "",
    val favicon: String = "",
    val tags: String = "",
    val country: String = "",
    @SerialName("countrycode") val countryCode: String = "",
    val state: String = "",
    val language: String = "",
    val codec: String = "",
    val bitrate: Int = 0,
    val hls: Int = 0,
    val votes: Int = 0,
    @SerialName("clickcount") val clickCount: Int = 0,
    @SerialName("lastcheckok") val lastCheckOk: Int = 0,
)

@Serializable
data class RadioBrowserFacetDto(
    val name: String = "",
    @SerialName("stationcount") val stationCount: JsonElement = JsonPrimitive(0),
    @SerialName("iso_639") val iso639: String? = null,
)

data class RadioFacet(
    val id: String,
    val name: String,
    val stationCount: Int,
)

internal fun RadioBrowserFacetDto.count(): Int =
    (stationCount as? JsonPrimitive)?.let { primitive ->
        primitive.intOrNull ?: primitive.content.toIntOrNull()
    }?.coerceAtLeast(0) ?: 0

@Serializable
data class RadioBrowserClickDto(
    val ok: String = "",
    val message: String = "",
    @SerialName("stationuuid") val stationUuid: String = "",
    val name: String = "",
    val url: String = "",
)

@Serializable
data class RadioStation(
    val id: String,
    val name: String,
    val streamUrl: String,
    val homepage: String?,
    val artworkUrl: String?,
    val country: String?,
    val countryCode: String?,
    val state: String?,
    val languages: List<String>,
    val tags: List<String>,
    val codec: String?,
    val bitrateKbps: Int?,
    val isHls: Boolean,
    val votes: Int,
    val clickCount: Int,
)

internal fun RadioBrowserStationDto.toRadioStation(): RadioStation? {
    val uuid = stationUuid.trim()
    val stationName = name.trim()
    val stream = resolvedUrl.trim().ifBlank { url.trim() }
    if (uuid.isBlank() || stationName.isBlank() || stream.isBlank()) return null

    fun csv(value: String): List<String> = value
        .split(',')
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinctBy(String::lowercase)

    return RadioStation(
        id = uuid,
        name = stationName,
        streamUrl = stream,
        homepage = homepage.trim().takeIf(String::isNotBlank),
        artworkUrl = favicon.trim().takeIf(String::isNotBlank),
        country = country.trim().takeIf(String::isNotBlank),
        countryCode = countryCode.trim().uppercase().takeIf(String::isNotBlank),
        state = state.trim().takeIf(String::isNotBlank),
        languages = csv(language),
        tags = csv(tags),
        codec = codec.trim().uppercase().takeIf(String::isNotBlank),
        bitrateKbps = bitrate.takeIf { it > 0 },
        isHls = hls != 0,
        votes = votes.coerceAtLeast(0),
        clickCount = clickCount.coerceAtLeast(0),
    )
}
