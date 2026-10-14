package com.ambient.tv.data.remote

import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.network.LocalNetworkPolicy
import com.ambient.tv.network.NetworkEndpoint
import java.io.IOException
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

/** Rewrites PMS API requests to the currently configured server and optionally enforces LAN-only use. */
@Singleton
class DynamicServerUrlInterceptor @Inject constructor(
    private val appPreferences: AppPreferences,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val host = NetworkEndpoint.normalizeHost(appPreferences.host)
        if (host.isBlank()) return chain.proceed(original)
        if (appPreferences.localNetworkOnly && !LocalNetworkPolicy.isLocalHost(host)) {
            throw IOException("Blocked non-local Plex server while LAN-only mode is enabled")
        }
        val newUrl = original.url.newBuilder()
            .scheme(if (appPreferences.plexUseHttps) "https" else "http")
            .host(host)
            .port(appPreferences.port)
            .build()
        return chain.proceed(original.newBuilder().url(newUrl).build())
    }
}
