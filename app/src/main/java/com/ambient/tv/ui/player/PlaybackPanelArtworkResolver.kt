package com.ambient.tv.ui.player

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Keeps the compact playback-settings hero sharp without reusing 4K backdrops or tiny card
 * thumbnails. Provider image URLs already contain resize parameters, so the panel can request a
 * purpose-sized variant without carrying provider-specific DTOs into the UI.
 */
object PlaybackPanelArtworkResolver {
    data class Result(
        val primaryUrl: String?,
        val fallbackUrls: List<String> = emptyList(),
    )

    fun resolve(
        backdropUrl: String?,
        thumbnailUrl: String?,
        highQualityAssets: Boolean,
    ): Result {
        val width = if (highQualityAssets) 1280 else 960
        val height = if (highQualityAssets) 720 else 540
        val candidates = listOfNotNull(backdropUrl, thumbnailUrl)
            .map { resizeKnownProviderUrl(it, width, height) }
            .filter(String::isNotBlank)
            .distinct()
        return Result(candidates.firstOrNull(), candidates.drop(1))
    }

    internal fun resizeKnownProviderUrl(url: String, width: Int, height: Int): String {
        val parsed = url.toHttpUrlOrNull() ?: return url
        val path = parsed.encodedPath
        return when {
            path.contains("/photo/:/transcode", ignoreCase = true) -> parsed.newBuilder()
                .setQueryParameter("width", width.toString())
                .setQueryParameter("height", height.toString())
                .setQueryParameter("minSize", "1")
                .setQueryParameter("upscale", "1")
                .build()
                .toString()

            path.contains("/Images/", ignoreCase = true) -> parsed.newBuilder()
                .setQueryParameter("maxWidth", width.toString())
                .build()
                .toString()

            else -> url
        }
    }
}
