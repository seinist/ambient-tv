package com.ambient.tv.data.jellyfin

import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.network.LocalNetworkPolicy
import com.ambient.tv.network.NetworkEndpoint
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.Interceptor
import okhttp3.Response

@Singleton
class JellyfinDynamicUrlInterceptor @Inject constructor(
    private val preferences: AppPreferences,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val host = NetworkEndpoint.normalizeHost(preferences.jellyfinHost)
        if (host.isBlank()) return chain.proceed(chain.request())
        if (preferences.localNetworkOnly && !LocalNetworkPolicy.isLocalHost(host)) {
            throw IOException("Blocked non-local Jellyfin server while LAN-only mode is enabled")
        }
        val url = chain.request().url.newBuilder()
            .scheme(if (preferences.jellyfinUseHttps) "https" else "http")
            .host(host)
            .port(preferences.jellyfinPort)
            .encodedPath(preferences.jellyfinBasePath + chain.request().url.encodedPath)
            .build()
        return chain.proceed(chain.request().newBuilder().url(url).build())
    }
}
