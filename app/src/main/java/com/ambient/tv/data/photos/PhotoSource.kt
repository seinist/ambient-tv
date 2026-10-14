package com.ambient.tv.data.photos

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import retrofit2.http.GET
import retrofit2.http.Url

/** One provider behind the photo browser/viewer. Keys carry the provider prefix. */
interface PhotoSource {
    val system: PhotoSystem
    fun owns(key: String): Boolean
    fun available(): Boolean
    suspend fun libraries(): List<PhotoLibrary>
    suspend fun list(albumKey: String): List<PhotoEntry>
    /** Photos below [albumKey] including sub-albums, bounded by [limit]. */
    suspend fun listRecursive(albumKey: String, limit: Int): List<PhotoEntry>
    suspend fun details(entry: PhotoEntry): PhotoExif? = entry.exif
    /** Newest photos across all libraries of this provider. */
    suspend fun recent(limit: Int): List<PhotoEntry>
}

/** Raw JSON access; photo payloads differ enough per server that typed DTOs would not help. */
interface PhotoJsonApi {
    @GET suspend fun get(@Url url: String): JsonObject
}

internal fun JsonObject.str(name: String): String? = when (val value = this[name]) {
    null, JsonNull -> null
    is JsonPrimitive -> value.contentOrNull?.takeIf { it.isNotBlank() }
    else -> null
}

internal fun JsonObject.long(name: String): Long? = str(name)?.toDoubleOrNull()?.toLong()
internal fun JsonObject.int(name: String): Int? = str(name)?.toDoubleOrNull()?.toInt()
internal fun JsonObject.double(name: String): Double? = str(name)?.toDoubleOrNull()
internal fun JsonObject.obj(name: String): JsonObject? = this[name] as? JsonObject
internal fun JsonObject.array(name: String): List<JsonObject> = (this[name] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
internal fun JsonElement?.asObj(): JsonObject? = this as? JsonObject

internal fun isoToEpochMs(value: String?): Long? = value?.let {
    runCatching { java.time.OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull()
        ?: runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull()
        ?: runCatching { java.time.LocalDate.parse(it.take(10)).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrNull()
}

internal fun formatAperture(value: Double?): String? = value?.takeIf { it > 0 }?.let { "f/" + trimNumber(it) }
internal fun formatFocal(value: Double?): String? = value?.takeIf { it > 0 }?.let { trimNumber(it) + " mm" }
internal fun formatExposure(seconds: Double?): String? = seconds?.takeIf { it > 0 }?.let {
    if (it >= 1) trimNumber(it) + " s" else "1/" + Math.round(1 / it) + " s"
}
internal fun formatLocation(lat: Double?, lon: Double?): String? =
    if (lat == null || lon == null || (lat == 0.0 && lon == 0.0)) null
    else String.format(java.util.Locale.ROOT, "%.5f, %.5f", lat, lon)

private fun trimNumber(value: Double): String =
    if (value == Math.floor(value)) value.toLong().toString() else String.format(java.util.Locale.ROOT, "%.1f", value)
