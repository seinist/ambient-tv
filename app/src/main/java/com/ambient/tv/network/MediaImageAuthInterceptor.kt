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
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        // A credential bound to another origin (TVHeadend password on an external logo host)
        // is removed before anything else runs.
        val request = original.header("Authorization")
            ?.takeUnless { CredentialScope.allows(it, original.url) }
            ?.let { original.newBuilder().removeHeader("Authorization").build() }
            ?: original
        val host = request.url.host
        if (host.isBlank()) return chain.proceed(request)

        // Callers such as TVHeadend pass Basic authentication directly with their image request.
        // Never replace that explicit credential with a token for another media server.
        if (hasExplicitAuthorization(request)) return chain.proceed(request)

        val jellyfinHost = NetworkEndpoint.normalizeHost(preferences.jellyfinHost)
        if (jellyfinHost.isNotBlank() && matchesEndpoint(request, jellyfinHost, preferences.jellyfinPort)) {
            val token = tokenStore.jellyfinToken
            if (!token.isNullOrBlank() && request.header("X-Emby-Token").isNullOrBlank()) {
                val authorization = mediaBrowserAuthorization(token)
                return chain.proceed(
                    request.newBuilder()
                        .header("Authorization", authorization)
                        .header("X-Emby-Authorization", authorization)
                        .header("X-Emby-Token", token)
                        .header("Accept-Language", LanguagePreferences.resolve(preferences.metadataLanguageKey))
                        .build(),
                )
            }
        }

        val embyHost = NetworkEndpoint.normalizeHost(preferences.embyHost)
        if (embyHost.isNotBlank() && matchesEndpoint(request, embyHost, preferences.embyPort)) {
            val token = tokenStore.embyToken
            if (!token.isNullOrBlank() && request.header("X-Emby-Token").isNullOrBlank()) {
                val authorization = mediaBrowserAuthorization(token)
                return chain.proceed(
                    request.newBuilder()
                        .header("Authorization", authorization)
                        .header("X-Emby-Authorization", authorization)
                        .header("X-Emby-Token", token)
                        .header("Accept-Language", LanguagePreferences.resolve(preferences.metadataLanguageKey))
                        .build(),
                )
            }
        }

        val plexHost = NetworkEndpoint.normalizeHost(preferences.host)
        if (plexHost.isNotBlank() && matchesEndpoint(request, plexHost, preferences.port)) {
            val token = tokenStore.token
            if (!token.isNullOrBlank() && request.header("X-Plex-Token").isNullOrBlank()) {
                return chain.proceed(
                    request.newBuilder()
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

    private fun matchesEndpoint(request: Request, configuredHost: String, configuredPort: Int): Boolean =
        NetworkEndpoint.sameEndpoint(request.url.host, request.url.port, configuredHost, configuredPort)
}
