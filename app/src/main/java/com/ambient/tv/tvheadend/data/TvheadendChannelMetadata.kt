package com.ambient.tv.tvheadend.data

import com.ambient.tv.livetv.LiveTvChannelAccess
import com.ambient.tv.livetv.LiveTvChannelKind
import kotlinx.serialization.json.*
import java.util.Locale

internal data class TvhChannelMetadata(
    val tags: List<String>,
    val kind: LiveTvChannelKind,
    val access: LiveTvChannelAccess,
)

internal fun tvhTagNames(rows: List<JsonObject>): Map<String, String> = rows.mapNotNull { row ->
    val id = (row["key"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
    val name = (row["val"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
    id to name
}.toMap()

internal fun tvhChannelMetadata(channel: JsonObject, tagNames: Map<String, String>, services: Map<String, JsonObject>): TvhChannelMetadata {
    fun ids(key: String) = (channel[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    val tags = ids("tags").mapNotNull(tagNames::get).distinct().sortedBy { it.lowercase(Locale.ROOT) }
    val serviceIds = ids("services").distinct()
    val mapped = serviceIds.map { services[it]?.takeUnless { row -> (row["enabled"] as? JsonPrimitive)?.booleanOrNull == false } }
    val kinds = mapped.map { service ->
        when ((service?.get("dvb_servicetype") as? JsonPrimitive)?.intOrNull) {
            0x02, 0x0a -> LiveTvChannelKind.RADIO
            0x01, 0x11, 0x16, 0x17, 0x18, 0x19, 0x1a, 0x1b, 0x1c, 0x1d, 0x1e, 0x1f,
            0x80, 0x91, 0x96, 0xa0, 0xa4, 0xa6, 0xa8, 0xd3 -> LiveTvChannelKind.TV
            else -> LiveTvChannelKind.UNKNOWN
        }
    }
    val taggedKinds = tags.mapNotNull { when (it.trim().lowercase(Locale.ROOT)) {
        "radio" -> LiveTvChannelKind.RADIO
        "tv", "sdtv", "hdtv", "uhdtv" -> LiveTvChannelKind.TV
        else -> null
    } }.distinct()
    val serviceKind = kinds.distinct().singleOrNull()?.takeUnless { it == LiveTvChannelKind.UNKNOWN }
    val kind = serviceKind ?: taggedKinds.singleOrNull() ?: LiveTvChannelKind.UNKNOWN
    val encryption = mapped.map { (it?.get("encrypted") as? JsonPrimitive)?.booleanOrNull }
    val taggedAccess = tags.mapNotNull { when (it.trim().lowercase(Locale.ROOT)) {
        "fta", "free-to-air", "free to air" -> LiveTvChannelAccess.FTA
        "encrypted", "verschlüsselt" -> LiveTvChannelAccess.ENCRYPTED
        else -> null
    } }.distinct().singleOrNull()
    val access = when {
        encryption.any { it == false } -> LiveTvChannelAccess.FTA
        encryption.isNotEmpty() && encryption.all { it == true } -> LiveTvChannelAccess.ENCRYPTED
        else -> taggedAccess ?: LiveTvChannelAccess.UNKNOWN
    }
    return TvhChannelMetadata(tags, kind, access)
}
