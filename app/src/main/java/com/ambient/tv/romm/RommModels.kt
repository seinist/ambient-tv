package com.ambient.tv.romm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * The parts of RomM's REST API that Ambient TV reads. Every field is optional with a default:
 * the schemas grew over RomM 3.x to 5.x, and an older or newer server must still parse.
 */

@Serializable
data class RommPlatform(
    val id: Long,
    val slug: String = "",
    @SerialName("fs_slug") val fsSlug: String = "",
    val name: String = "",
    @SerialName("custom_name") val customName: String? = null,
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("rom_count") val romCount: Int = 0,
) {
    /** The name the RomM web interface shows for this platform. */
    val title: String
        get() = customName?.takeIf(String::isNotBlank) ?: displayName?.takeIf(String::isNotBlank)
            ?: name.ifBlank { slug }
}

@Serializable
data class RommRomFile(
    val id: Long = 0,
    @SerialName("file_name") val fileName: String = "",
    @SerialName("file_size_bytes") val sizeBytes: Long = 0,
)

@Serializable
data class RommRomMetadata(
    val genres: List<String> = emptyList(),
    val companies: List<String> = emptyList(),
    val publishers: List<String> = emptyList(),
    val developers: List<String> = emptyList(),
    @SerialName("player_count") val playerCount: String? = null,
    /** Unix time; RomM 4+ sends milliseconds, older versions seconds. */
    @SerialName("first_release_date") val firstReleaseDate: Long? = null,
)

@Serializable
data class RommRomUser(
    @SerialName("last_played") val lastPlayed: String? = null,
)

@Serializable
data class RommRom(
    val id: Long,
    @SerialName("platform_id") val platformId: Long = 0,
    @SerialName("platform_slug") val platformSlug: String = "",
    @SerialName("platform_fs_slug") val platformFsSlug: String = "",
    @SerialName("fs_name") val fsName: String = "",
    @SerialName("fs_name_no_tags") val fsNameNoTags: String = "",
    @SerialName("fs_name_no_ext") val fsNameNoExt: String = "",
    @SerialName("fs_size_bytes") val fsSizeBytes: Long = 0,
    val name: String? = null,
    val summary: String? = null,
    val metadatum: RommRomMetadata? = null,
    @SerialName("path_cover_small") val pathCoverSmall: String? = null,
    @SerialName("path_cover_large") val pathCoverLarge: String? = null,
    @SerialName("url_cover") val urlCover: String? = null,
    @SerialName("merged_screenshots") val screenshots: List<String> = emptyList(),
    val regions: List<String> = emptyList(),
    val languages: List<String> = emptyList(),
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("rom_user") val romUser: RommRomUser? = null,
    @SerialName("has_multiple_files") val hasMultipleFiles: Boolean = false,
    /** RomM 3.x name of [hasMultipleFiles]. */
    val multi: Boolean = false,
    val files: List<RommRomFile> = emptyList(),
    @SerialName("missing_from_fs") val missingFromFs: Boolean = false,
) {
    /** RomM sends several files of one game as a single ZIP (with an M3U for multi-disc games). */
    val zipped: Boolean get() = hasMultipleFiles || multi || files.size > 1

    val displayTitle: String
        get() = name?.takeIf(String::isNotBlank) ?: fsNameNoTags.ifBlank { fsNameNoExt.ifBlank { fsName } }

    val releaseYear: Int?
        get() {
            val raw = metadatum?.firstReleaseDate?.takeIf { it > 0 } ?: return null
            val millis = if (raw > 100_000_000_000L) raw else raw * 1000
            return java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
                .apply { timeInMillis = millis }.get(java.util.Calendar.YEAR)
        }
}

/** A firmware file RomM keeps for a platform (Library › Platform › Firmware). */
@Serializable
data class RommFirmware(
    val id: Long,
    @SerialName("platform_id") val platformId: Long = 0,
    @SerialName("file_name") val fileName: String = "",
    @SerialName("file_size_bytes") val sizeBytes: Long = 0,
    @SerialName("md5_hash") val md5: String? = null,
    @SerialName("is_verified") val verified: Boolean = false,
)

@Serializable
data class RommScreenshot(
    @SerialName("download_path") val downloadPath: String? = null,
)

/** A save (in-game memory) or a save state stored on the server. */
@Serializable
data class RommAsset(
    val id: Long,
    @SerialName("rom_id") val romId: Long = 0,
    @SerialName("file_name") val fileName: String = "",
    @SerialName("file_size_bytes") val sizeBytes: Long = 0,
    @SerialName("download_path") val downloadPath: String? = null,
    val emulator: String? = null,
    @SerialName("updated_at") val updatedAt: String = "",
    val screenshot: RommScreenshot? = null,
)

@Serializable
data class RommPage<T>(
    val items: List<T> = emptyList(),
    val total: Int? = null,
)

@Serializable
data class RommUser(
    val id: Long = 0L,
    val username: String = "",
)

/** A user collection; each user's Favorites is the one with [isFavorite]. */
data class RommCollection(
    val id: Long,
    val name: String,
    val isFavorite: Boolean,
    val userId: Long,
    val ownerUsername: String,
    val romIds: Set<Long>,
)

@Serializable
internal data class RommDeviceAuthRequest(
    @SerialName("client_device_identifier") val deviceIdentifier: String,
    val name: String,
    val client: String,
    val platform: String,
    @SerialName("client_version") val clientVersion: String,
    @SerialName("requested_scopes") val scopes: List<String>,
)

@Serializable
data class RommDeviceAuth(
    @SerialName("device_code") val deviceCode: String,
    @SerialName("user_code") val userCode: String,
    @SerialName("verification_path") val verificationPath: String = "/pair/device",
    @SerialName("verification_path_complete") val verificationPathComplete: String? = null,
    @SerialName("expires_in") val expiresIn: Int = 600,
    val interval: Int = 5,
)

@Serializable
internal data class RommDeviceTokenRequest(@SerialName("device_code") val deviceCode: String)

@Serializable
internal data class RommDeviceToken(@SerialName("access_token") val accessToken: String)

@Serializable
internal data class RommPairExchangeRequest(val code: String)

@Serializable
internal data class RommPairExchange(@SerialName("raw_token") val rawToken: String)

@Serializable
internal data class RommErrorDetail(val detail: String? = null)

@Serializable
internal data class RommDeleteStates(val states: List<Long>)
