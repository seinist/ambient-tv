package com.ambient.tv.data.dlna

import java.util.Base64
import java.util.UUID
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** A MediaServer selected by the user. The UDN, rather than its current IP, is its identity. */
@Serializable
data class DlnaServer(
    val id: String = UUID.randomUUID().toString(),
    val udn: String,
    val name: String,
    val descriptionUrl: String,
    val contentDirectoryUrl: String,
    val contentDirectoryType: String = "urn:schemas-upnp-org:service:ContentDirectory:1",
    /** Wake-on-LAN target (empty = off). Kept across rediscovery, part of backup/companion sync. */
    val wolMac: String = "",
    /** Wake the server before browsing when it does not answer. Requires [wolMac]. */
    val wolAuto: Boolean = false,
) {
    fun validated(): DlnaServer {
        require(id.matches(Regex("[a-zA-Z0-9-]{1,80}")))
        require(udn.isNotBlank() && udn.length <= 512)
        require(name.isNotBlank() && name.length <= 160)
        val description = requireNotNull(descriptionUrl.toHttpUrlOrNull())
        val control = requireNotNull(contentDirectoryUrl.toHttpUrlOrNull())
        require(contentDirectoryType.matches(Regex("urn:schemas-upnp-org:service:ContentDirectory:[1-9][0-9]*")))
        val mac = wolMac.trim()
        if (mac.isNotEmpty()) com.ambient.tv.data.files.WakeOnLan.packet(mac)
        require(!wolAuto || mac.isNotEmpty())
        return copy(
            wolMac = mac,
            udn = udn.trim(),
            name = name.trim(),
            descriptionUrl = description.toString(),
            contentDirectoryUrl = control.toString(),
        )
    }
}

@Serializable
data class DlnaProgress(
    val mediaKey: String,
    val positionMs: Long = 0,
    val completed: Boolean = false,
    val updatedAt: Long = 0,
)

@Serializable
data class DlnaResource(
    val url: String,
    val protocolInfo: String?,
    val durationMs: Long? = null,
    val size: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
)

@Serializable
data class DlnaNode(
    val serverId: String,
    val objectId: String,
    val parentObjectId: String?,
    val title: String,
    val upnpClass: String?,
    val container: Boolean,
    val artworkUrl: String?,
    val summary: String?,
    val date: String?,
    val resources: List<DlnaResource>,
    /** Subtitle files the server offers for this item (subtitle `res` entries, sec:CaptionInfoEx). */
    val subtitles: List<DlnaSubtitle> = emptyList(),
) {
    val key: String get() = DlnaKeys.key(serverId, objectId)
}

@Serializable
data class DlnaSubtitle(val url: String, val codec: String)

@Serializable
data class DlnaBrowsePage(
    val nodes: List<DlnaNode>,
    val totalMatches: Int,
    val numberReturned: Int,
    val requestedCount: Int = DlnaRepository.PAGE_SIZE,
)

/** Object IDs are only unique inside one UPnP server, hence the qualified, opaque app key. */
object DlnaKeys {
    private const val PREFIX = "dlna:"

    fun owns(key: String) = key.startsWith(PREFIX)

    fun key(serverId: String, objectId: String): String =
        "$PREFIX$serverId:" + Base64.getUrlEncoder().withoutPadding().encodeToString(objectId.toByteArray(Charsets.UTF_8))

    fun parse(key: String): Pair<String, String> {
        require(owns(key))
        val parts = key.split(':', limit = 3)
        require(parts.size == 3 && parts[1].matches(Regex("[a-zA-Z0-9-]{1,80}")))
        return parts[1] to String(Base64.getUrlDecoder().decode(parts[2]), Charsets.UTF_8)
    }
}
