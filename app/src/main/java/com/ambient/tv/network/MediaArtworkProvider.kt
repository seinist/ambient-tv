package com.ambient.tv.network

import com.ambient.tv.data.browse.displayArtworkUrl
import com.ambient.tv.data.local.AppPreferences
import java.security.MessageDigest
import java.util.Locale
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** An internal request fence, removed by MediaImageAuthInterceptor before any network call. */
internal const val MEDIA_ARTWORK_SCOPE_HEADER = "X-Ambient-Artwork-Scope"

internal data class MediaImageEndpoint(
    val provider: String, val host: String, val port: Int, val https: Boolean, val basePath: String = "",
)

/** Longest complete base path wins; ambiguous configurations never choose another account. */
internal fun mediaImageProvider(url: HttpUrl, endpoints: List<MediaImageEndpoint>): String? {
    val matches = endpoints.mapNotNull { endpoint ->
        val host = NetworkEndpoint.normalizeHost(endpoint.host)
        val base = endpoint.basePath.trim().trim('/').let {
            if (it.isEmpty()) "" else HttpUrl.Builder().scheme("http").host("path.invalid").encodedPath("/$it").build().encodedPath.trimEnd('/')
        }
        if (host.isBlank() || url.scheme != (if (endpoint.https) "https" else "http") ||
            !NetworkEndpoint.sameEndpoint(url.host, url.port, host, endpoint.port) ||
            (base.isNotEmpty() && url.encodedPath != base && !url.encodedPath.startsWith("$base/"))) null
        else endpoint.provider to base.length
    }
    val longest = matches.maxOfOrNull { it.second } ?: return null
    val best = matches.filter { it.second == longest }.map { it.first }.distinct()
    if (best.size == 1) return best.single()
    // Plex and a root-mounted MediaBrowser server have distinguishable image routes.
    val plexRoute = url.encodedPath.startsWith("/photo/:/transcode") || url.encodedPath.startsWith("/library/")
    return if (plexRoute) "plex".takeIf(best::contains) else best.filterNot { it == "plex" }.singleOrNull()
}

internal fun mediaImageEndpoints(preferences: AppPreferences): List<MediaImageEndpoint> = listOf(
    MediaImageEndpoint("plex", preferences.host, preferences.port, preferences.plexUseHttps),
    MediaImageEndpoint("jellyfin", preferences.jellyfinHost, preferences.jellyfinPort, preferences.jellyfinUseHttps, preferences.jellyfinBasePath),
    MediaImageEndpoint("emby", preferences.embyHost, preferences.embyPort, preferences.embyUseHttps, preferences.embyBasePath),
)

internal fun mediaImageProvider(url: HttpUrl, preferences: AppPreferences): String? = mediaImageProvider(url, mediaImageEndpoints(preferences))

/** An explicit account must not inherit the currently selected automatic provider account. */
internal fun explicitMediaImageCredential(url: HttpUrl, provider: String?, headers: Map<String, String>): Boolean {
    fun header(name: String) = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
    header("Authorization")?.takeIf(String::isNotBlank)?.let { if (CredentialScope.allows(it, url)) return true }
    return when (provider) {
        "plex" -> !header("X-Plex-Token").isNullOrBlank()
        "jellyfin", "emby" -> !header("X-Emby-Token").isNullOrBlank()
        else -> false
    }
}

private val artworkSourcePreferences = setOf(
    "server_host", "server_port", "server_profiles_v1", "active_server_id", "plex_enabled", "plex_https", "metadata_language",
    "jellyfin_enabled", "jellyfin_host", "jellyfin_port", "jellyfin_https", "jellyfin_base_path", "jellyfin_user_id",
    "active_jellyfin_server_id", "jellyfin_server_profiles_v1", "emby_enabled", "emby_host", "emby_port", "emby_https",
    "emby_base_path", "emby_user_id", "active_emby_server_id", "emby_server_profiles_v1",
)
internal fun mediaArtworkSourcePreference(key: String?): Boolean = key == null || key in artworkSourcePreferences ||
    key.startsWith("plex_token") || key.startsWith("jellyfin_token_") || key.startsWith("emby_token_")

/** Resize/encoding variants share invalidation, while their actual image cache keys stay distinct. */
internal fun mediaArtworkResourceUrl(value: String): String? {
    val url = displayArtworkUrl(value)?.toHttpUrlOrNull() ?: return null
    val transformed = url.encodedPath.contains("/photo/:/transcode", ignoreCase = true) ||
        url.encodedPath.contains("/Images/", ignoreCase = true)
    if (!transformed) return url.toString()
    val rendering = setOf("width", "height", "minsize", "upscale", "quality", "maxwidth", "maxheight", "fillwidth", "fillheight", "format")
    return url.newBuilder().apply {
        url.queryParameterNames.filter { it.lowercase(Locale.ROOT) in rendering }.forEach(::removeAllQueryParameters)
    }.build().toString()
}

/** Authentication and Referer affect image bytes. Only their length-framed digest becomes a key. */
internal fun mediaArtworkHeadersKey(headers: Map<String, String>): String? {
    if (headers.isEmpty()) return null
    val digest = MessageDigest.getInstance("SHA-256")
    headers.entries.filterNot { it.key.equals(MEDIA_ARTWORK_SCOPE_HEADER, ignoreCase = true) }
        .map { it.key.lowercase(Locale.ROOT) to it.value }
        .sortedWith(compareBy<Pair<String, String>> { it.first }.thenBy { it.second })
        .forEach { (name, value) -> listOf(name, value).forEach { field ->
            val bytes = field.toByteArray(Charsets.UTF_8)
            for (shift in 24 downTo 0 step 8) digest.update((bytes.size ushr shift).toByte())
            digest.update(bytes)
        } }
    val hex = "0123456789abcdef"
    return buildString(64) { digest.digest().forEach { append(hex[(it.toInt() and 255) ushr 4]); append(hex[it.toInt() and 15]) } }
}
