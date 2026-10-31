package com.ambient.tv.network

import coil3.intercept.Interceptor
import coil3.request.ImageResult
import com.ambient.tv.data.browse.BrowseScopes
import com.ambient.tv.data.browse.displayArtworkUrl
import com.ambient.tv.data.local.AppPreferences
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Persist the same artwork under the same key whether its URL came directly from a
 * provider or from a token-free browse snapshot. Include the hashed provider scope:
 * two accounts sharing a host must never share their private image cache entries.
 *
 * Only media-server endpoints are rewritten. External images retain Coil's own key,
 * avoiding collisions between arbitrary signed CDN URLs with distinct query semantics.
 */
@Singleton
class MediaArtworkDiskCacheInterceptor @Inject constructor(
    private val preferences: AppPreferences,
    private val scopes: BrowseScopes,
) : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val request = chain.request
        val url = request.data as? String ?: return chain.proceed()
        if (request.diskCacheKey != null) return chain.proceed()
        val endpoint = url.toHttpUrlOrNull() ?: return chain.proceed()
        val provider = when {
            matches(endpoint.host, endpoint.port, preferences.host, preferences.port) -> "plex"
            matches(endpoint.host, endpoint.port, preferences.jellyfinHost, preferences.jellyfinPort) -> "jellyfin"
            matches(endpoint.host, endpoint.port, preferences.embyHost, preferences.embyPort) -> "emby"
            else -> return chain.proceed()
        }
        val key = normalizedMediaArtworkDiskKey(url, scopes.forProvider(provider)) ?: return chain.proceed()
        return chain.withRequest(request.newBuilder().diskCacheKey(key).build()).proceed()
    }

    private fun matches(host: String, port: Int, configuredHost: String, configuredPort: Int): Boolean {
        val normalized = NetworkEndpoint.normalizeHost(configuredHost)
        return normalized.isNotBlank() && NetworkEndpoint.sameEndpoint(host, port, normalized, configuredPort)
    }
}

/** Safe to persist: source scope is hashed and the canonical URL contains no auth query. */
internal fun normalizedMediaArtworkDiskKey(url: String, sourceScope: String): String? =
    displayArtworkUrl(url)?.let { "media-artwork-v1:$sourceScope:$it" }
