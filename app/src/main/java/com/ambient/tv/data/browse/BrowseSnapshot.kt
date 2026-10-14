package com.ambient.tv.data.browse

import com.ambient.tv.domain.model.*
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

@Serializable
data class BrowseSnapshot(
    val storedAtMs: Long = System.currentTimeMillis(),
    val items: List<MediaItem> = emptyList(),
    val sections: List<LibrarySection>? = null,
    val hubs: List<LibraryHub>? = null,
) {
    fun displayOnly() = copy(
        items = items.map { it.displayOnly() },
        sections = sections?.map { it.copy(thumbUrl = displayArtworkUrl(it.thumbUrl), artUrl = displayArtworkUrl(it.artUrl)) },
        hubs = hubs?.map { it.copy(items = it.items.map { item -> item.displayOnly() }) },
    )
}

/** Allow only known artwork parameters; never persist URL user-info, tokens or signed streams. */
internal fun displayArtworkUrl(value: String?): String? {
    if (value == null || !(value.startsWith("https://", ignoreCase = true) || value.startsWith("http://", ignoreCase = true))) return null
    val url = value.toHttpUrlOrNull() ?: return null
    val allowed = setOf("width", "height", "minSize", "upscale", "url", "tag", "quality", "maxWidth", "maxHeight", "fillWidth", "fillHeight", "format")
    return url.newBuilder().username("").password("").apply {
        url.queryParameterNames.filterNot { it in allowed }.forEach(::removeAllQueryParameters)
        // Plex transcode artwork can contain a nested authenticated URL.
        url.queryParameter("url")?.takeIf { it.contains("://") }?.let {
            setQueryParameter("url", displayArtworkUrl(it) ?: "")
        }
    }.build().toString()
}

internal fun MediaItem.displayOnly(): MediaItem {
    checkBrowseWriteBudget()
    return copy(
    thumbUrl = displayArtworkUrl(thumbUrl), artUrl = displayArtworkUrl(artUrl),
    parentThumbUrl = displayArtworkUrl(parentThumbUrl), grandparentThumbUrl = displayArtworkUrl(grandparentThumbUrl),
    logoUrl = displayArtworkUrl(logoUrl), themeMusicUrl = null,
    mediaVersions = emptyList(),
    cast = cast.map { it.copy(thumbUrl = displayArtworkUrl(it.thumbUrl)) },
)
}
