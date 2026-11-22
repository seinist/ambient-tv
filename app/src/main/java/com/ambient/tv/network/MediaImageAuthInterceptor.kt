package com.ambient.tv.network

import com.ambient.tv.BuildConfig
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.data.local.LanguagePreferences
import com.ambient.tv.data.local.TokenStore
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

internal fun hasExplicitAuthorization(request: Request): Boolean =
    !request.header("Authorization").isNullOrBlank()

/**
 * Adds Jellyfin/Emby/Plex auth headers for Coil image fetches so artwork URLs do not need
 * long-lived tokens in the query string. Matches the complete request endpoint against
 * configured servers, so services sharing a host but using different ports stay isolated.
 * Stream/player URLs that bypass Coil still embed query tokens where the backend requires them.
 */
@Singleton
class MediaImageAuthInterceptor @Inject constructor(
    private val preferences: AppPreferences,
    private val tokenStore: TokenStore,
    private val scopes: com.ambient.tv.data.browse.BrowseScopes,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val expectedScope = original.header(MEDIA_ARTWORK_SCOPE_HEADER)
        val withoutFence = original.newBuilder().removeHeader(MEDIA_ARTWORK_SCOPE_HEADER).build()
        // A credential bound to another origin (TVHeadend password on an external logo host)
        // is removed before anything else runs.
        val request = withoutFence.header("Authorization")
            ?.takeUnless { CredentialScope.allows(it, withoutFence.url) }
            ?.let { withoutFence.newBuilder().removeHeader("Authorization").build() }
            ?: withoutFence
        val host = request.url.host
        if (host.isBlank()) return chain.proceed(request)

        val provider = mediaImageProvider(request.url, preferences)
        val token = when (provider) {
            "plex" -> tokenStore.token
            "jellyfin" -> tokenStore.jellyfinToken
            "emby" -> tokenStore.embyToken
            else -> null
        }
        if (expectedScope != null && (provider == null || scopes.forMediaProviderToken(provider, token) != expectedScope))
            throw java.io.IOException("Artwork source changed")

        // Callers such as TVHeadend pass Basic authentication directly with their image request.
        // Never replace that explicit credential with a token for another media server.
        if (hasExplicitAuthorization(request)) return chain.proceed(request)

        if (provider == "jellyfin") {
            if (!token.isNullOrBlank() && request.header("X-Emby-Token").isNullOrBlank()) {
                val authorization = mediaBrowserAuthorization(token)
                return chain.proceed(
                    request.withCurrentImageToken("ApiKey", token).newBuilder()
                        .header("Authorization", authorization)
                        .header("X-Emby-Authorization", authorization)
                        .header("X-Emby-Token", token)
                        .header("Accept-Language", LanguagePreferences.resolve(preferences.metadataLanguageKey))
                        .build(),
                )
            }
        }

        if (provider == "emby") {
            if (!token.isNullOrBlank() && request.header("X-Emby-Token").isNullOrBlank()) {
                val authorization = mediaBrowserAuthorization(token)
                return chain.proceed(
                    request.withCurrentImageToken("api_key", token).newBuilder()
                        .header("Authorization", authorization)
                        .header("X-Emby-Authorization", authorization)
                        .header("X-Emby-Token", token)
                        .header("Accept-Language", LanguagePreferences.resolve(preferences.metadataLanguageKey))
                        .build(),
                )
            }
        }

        if (provider == "plex") {
            if (!token.isNullOrBlank() && request.header("X-Plex-Token").isNullOrBlank()) {
                return chain.proceed(
                    request.withCurrentImageToken("X-Plex-Token", token).newBuilder()
                        .header("X-Plex-Token", token)
                        .build(),
                )
            }
        }

        return chain.proceed(request)
    }

    private fun mediaBrowserAuthorization(token: String): String = buildString {
        append("MediaBrowser Client=\"${com.ambient.tv.branding.AppBrand.name}\", Device=\"Android TV\", DeviceId=\"")
        append(preferences.clientIdentifier)
        append("\", Version=\"").append(BuildConfig.VERSION_NAME).append('"')
        append(", Token=\"").append(token).append('"')
    }

    private fun Request.withCurrentImageToken(name: String, token: String): Request {
        val names = url.queryParameterNames.filter { it.equals(name, ignoreCase = true) ||
            (name != "X-Plex-Token" && it.replace("_", "").equals("apikey", ignoreCase = true)) }
        if (names.isEmpty()) return this
        // Old live URLs can still contain a prior account's token; header and query must agree.
        return newBuilder().url(url.newBuilder().apply { names.forEach(::removeAllQueryParameters) }
            .addQueryParameter(name, token).build()).build()
    }
}
