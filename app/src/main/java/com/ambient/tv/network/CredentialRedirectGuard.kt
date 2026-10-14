package com.ambient.tv.network

import java.io.IOException
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient

/**
 * Keeps media-server credentials on the origin they were sent to. OkHttp drops only
 * `Authorization` when a redirect leaves the host; tokens in X-Plex-Token, X-Emby-Token or
 * X-Emby-Authorization (and a login body on a 307/308) would follow a server, reverse proxy or
 * SSO gateway to wherever it points. [install] tags every call with the origin of its first hop
 * and strips those headers on any later hop that left it. The only redirect that keeps them is
 * an upgrade from http to https on the same host.
 */
object CredentialRedirectGuard {
    internal val CREDENTIAL_HEADERS = listOf(
        "Authorization", "X-Plex-Token", "X-Emby-Token", "X-Emby-Authorization", "X-MediaBrowser-Token",
    )

    private class FirstHop(val url: HttpUrl)

    // Application interceptors run once per call: the tag survives into every follow-up request.
    private val tagFirstHop = Interceptor { chain ->
        val request = chain.request()
        chain.proceed(
            if (request.tag(FirstHop::class.java) != null) request
            else request.newBuilder().tag(FirstHop::class.java, FirstHop(request.url)).build(),
        )
    }

    // Network interceptors run for every hop, including redirects and authenticator retries.
    private val stripOnForeignHop = Interceptor { chain ->
        val request = chain.request()
        val origin = request.tag(FirstHop::class.java)?.url
        if (origin == null || keeps(origin, request.url)) return@Interceptor chain.proceed(request)
        // A 307/308 repeats the body (a login password, a playback report) at the new address.
        if (request.body != null) throw IOException("Redirect to another server refused")
        chain.proceed(request.newBuilder().apply { CREDENTIAL_HEADERS.forEach(::removeHeader) }.build())
    }

    /** Call after the interceptors that add credentials: application interceptors run in order. */
    fun install(builder: OkHttpClient.Builder): OkHttpClient.Builder =
        builder.addInterceptor(tagFirstHop).addNetworkInterceptor(stripOnForeignHop)

    internal fun keeps(origin: HttpUrl, hop: HttpUrl): Boolean =
        origin.host == hop.host &&
            (origin.scheme == hop.scheme && origin.port == hop.port || origin.scheme == "http" && hop.scheme == "https")
}
