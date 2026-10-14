package com.ambient.tv.wol

import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.di.SharedOkHttp
import com.ambient.tv.network.LocalNetworkPolicy
import com.ambient.tv.network.NetworkEndpoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ServerReachabilityChecker @Inject constructor(
    private val appPreferences: AppPreferences,
    @SharedOkHttp connectionPool: ConnectionPool,
    @SharedOkHttp dispatcher: Dispatcher,
) {
    private val client = OkHttpClient.Builder()
        .connectionPool(connectionPool)
        .dispatcher(dispatcher)
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()

    /** `/identity` answers without authentication, so the probe never sends the server token. */
    suspend fun isReachable(): Boolean = withContext(Dispatchers.IO) {
        val host = NetworkEndpoint.normalizeHost(appPreferences.host)
        if (host.isBlank()) return@withContext false
        if (appPreferences.localNetworkOnly && !LocalNetworkPolicy.isPotentiallyLocalHost(host)) return@withContext false
        runCatching {
            val scheme = if (appPreferences.plexUseHttps) "https" else "http"
            val url = "$scheme://${NetworkEndpoint.authorityHost(host)}:${appPreferences.port}/identity"
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }
}
