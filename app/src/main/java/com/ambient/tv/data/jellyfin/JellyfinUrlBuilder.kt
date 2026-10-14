package com.ambient.tv.data.jellyfin

import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.data.local.TokenStore
import com.ambient.tv.network.NetworkEndpoint
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.HttpUrl.Companion.toHttpUrl

@Singleton
class JellyfinUrlBuilder @Inject constructor(
    private val preferences: AppPreferences,
    private val tokenStore: TokenStore,
    private val performanceProfileResolver: com.ambient.tv.ui.performance.PerformanceProfileResolver,
) {
    fun imageUrl(
        itemId: String,
        type: String,
        index: Int? = null,
        tag: String? = null,
        maxWidth: Int? = null,
        format: String? = null,
    ): String? {
        if (preferences.jellyfinHost.isBlank()) return null
        val path = buildString {
            append("Items/").append(JellyfinBackend.raw(itemId)).append("/Images/").append(type)
            if (index != null) append('/').append(index)
        }
        val highQuality = performanceProfileResolver.effectiveHighQualityAssets()
        val requestedWidth = maxWidth ?: when {
            type.equals("Backdrop", true) -> if (highQuality) BACKDROP_HIGH_MAX_WIDTH else BACKDROP_MAX_WIDTH
            type.equals("Logo", true) -> if (highQuality) LOGO_HIGH_MAX_WIDTH else LOGO_MAX_WIDTH
            else -> if (highQuality) PRIMARY_HIGH_MAX_WIDTH else PRIMARY_MAX_WIDTH
        }
        val imageFormat = format ?: if (type.equals("Logo", true)) "png" else null
        return url(
            path,
            mapOf(
                "tag" to tag,
                "maxWidth" to requestedWidth.toString(),
                "quality" to if (highQuality) "95" else "85",
                "format" to imageFormat,
                // Coil uses MediaImageAuthInterceptor (header auth). Query token remains a
                // fallback for any non-Coil image fetch and older cached URL shapes (N-04 partial).
                "ApiKey" to tokenStore.jellyfinToken,
            ),
        )
    }

    fun logoUrl(itemId: String, tag: String?): String? {
        if (tag.isNullOrBlank()) return null
        return imageUrl(itemId, "Logo", tag = tag, format = "png")
    }

    fun trickplayUrl(itemId: String, sourceId: String, width: Int): String = url(
        "Videos/${JellyfinBackend.raw(itemId)}/Trickplay/$width/__INDEX__.jpg",
        mapOf("mediaSourceId" to sourceId, "ApiKey" to tokenStore.jellyfinToken),
    )

    fun personImageUrl(personId: String?, tag: String?): String? = personId?.let {
        imageUrl(
            it,
            "Primary",
            tag = tag,
            maxWidth = if (performanceProfileResolver.effectiveHighQualityAssets()) PERSON_HIGH_MAX_WIDTH else PERSON_MAX_WIDTH,
        )
    }

    fun directStreamUrl(itemId: String, mediaSource: JellyfinMediaSource): String? {
        val container = mediaSource.container?.lowercase()?.takeIf { it.matches(Regex("[a-z0-9]+")) } ?: "mkv"
        return url(
            "Videos/${JellyfinBackend.raw(itemId)}/stream.$container",
            mapOf(
                "static" to "true",
                "mediaSourceId" to mediaSource.id.takeIf { it.isNotBlank() },
                "ApiKey" to tokenStore.jellyfinToken,
            ),
        )
    }

    /** Original audio file of a theme song item, unaltered by the server. */
    fun themeSongUrl(itemId: String): String = url(
        "Audio/${JellyfinBackend.raw(itemId)}/stream",
        mapOf("static" to "true", "ApiKey" to tokenStore.jellyfinToken),
    )

    fun externalSubtitleUrl(itemId: String, mediaSourceId: String, index: Int): String = url(
        "Videos/${JellyfinBackend.raw(itemId)}/$mediaSourceId/Subtitles/$index/Stream.vtt",
        mapOf("ApiKey" to tokenStore.jellyfinToken),
    )

    fun absolute(path: String?): String? {
        if (path.isNullOrBlank()) return null
        if (path.startsWith("http://") || path.startsWith("https://")) return path
        val scheme = if (preferences.jellyfinUseHttps) "https" else "http"
        val host = NetworkEndpoint.authorityHost(preferences.jellyfinHost)
        val base = "$scheme://$host:${preferences.jellyfinPort}${preferences.jellyfinBasePath}/".toHttpUrl()
        val relative = if (preferences.jellyfinBasePath.isNotBlank() && path.startsWith(preferences.jellyfinBasePath + "/")) {
            path.removePrefix(preferences.jellyfinBasePath).removePrefix("/")
        } else path.removePrefix("/")
        val resolved = base.resolve(relative) ?: return null
        if (resolved.queryParameter("ApiKey") != null || tokenStore.jellyfinToken.isNullOrBlank()) return resolved.toString()
        return resolved.newBuilder().addQueryParameter("ApiKey", tokenStore.jellyfinToken).build().toString()
    }

    fun compatibilityUrl(itemId: String, mediaSourceId: String?, startPositionMs: Long, video: Boolean, audioStreamIndex: Int? = null, subtitleStreamIndex: Int = -1, audioCodec: String = "aac", playSessionId: String? = null): String = url(
        "Videos/${JellyfinBackend.raw(itemId)}/main.m3u8",
        buildMap {
            put("UserId", preferences.jellyfinUserId)
            put("MediaSourceId", mediaSourceId)
            put("StartTimeTicks", (startPositionMs.coerceAtLeast(0) * 10_000L).toString())
            put("AudioStreamIndex", audioStreamIndex?.toString())
            put("SubtitleStreamIndex", subtitleStreamIndex.toString())
            put("VideoCodec", if (video) "h264" else "copy")
            putAll(com.ambient.tv.data.remote.serverAudioConversionQuery(audioCodec, video, audioStreamIndex, playSessionId))
            put("DeviceId", preferences.clientIdentifier)
            put("SegmentContainer", "ts")
            put("Protocol", "hls")
            put("ApiKey", tokenStore.jellyfinToken)
        },
    )

    private fun url(path: String, query: Map<String, String?> = emptyMap()): String {
        val scheme = if (preferences.jellyfinUseHttps) "https" else "http"
        val host = NetworkEndpoint.authorityHost(preferences.jellyfinHost)
        val builder = "$scheme://$host:${preferences.jellyfinPort}${preferences.jellyfinBasePath}/".toHttpUrl().newBuilder()
        path.split('/').filter(String::isNotBlank).forEach(builder::addPathSegment)
        query.forEach { (key, value) -> if (!value.isNullOrBlank()) builder.addQueryParameter(key, value) }
        return builder.build().toString()
    }

    private companion object {
        const val PRIMARY_MAX_WIDTH = 600
        const val PRIMARY_HIGH_MAX_WIDTH = 1600
        const val BACKDROP_MAX_WIDTH = 1280
        const val BACKDROP_HIGH_MAX_WIDTH = 3840
        const val PERSON_MAX_WIDTH = 240
        const val PERSON_HIGH_MAX_WIDTH = 640
        const val LOGO_MAX_WIDTH = 800
        const val LOGO_HIGH_MAX_WIDTH = 1600
    }
}
