package com.ambient.tv.data.dlna

import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.network.LocalNetworkPolicy
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Response

internal fun dlnaHttpClient(preferences: AppPreferences): OkHttpClient = OkHttpClient.Builder()
    .dns(com.ambient.tv.network.LanOnlyDns({ preferences.localNetworkOnly }))
    .connectTimeout(5, TimeUnit.SECONDS)
    // Some servers generate large virtual folders on demand. Discovery derives its own short
    // five-second client below; media browsing gets enough time to return a bounded page.
    .readTimeout(30, TimeUnit.SECONDS)
    .callTimeout(35, TimeUnit.SECONDS)
    // Network interceptors also see redirects. This keeps LAN-only mode authoritative after a
    // local device answers with a redirect to a different host.
    .addNetworkInterceptor { chain ->
        val host = chain.request().url.host
        check(!preferences.localNetworkOnly || LocalNetworkPolicy.isLocalHost(host)) {
            "DLNA endpoint is blocked by LAN-only mode"
        }
        chain.proceed(chain.request())
    }
    .addInterceptor(com.ambient.tv.diagnostics.DiagnosticHttpInterceptor).build()

internal fun memoizedDlnaUrlGate(preferences: AppPreferences): (String) -> Boolean {
    val hostDecisions = mutableMapOf<String, Boolean>()
    return gate@{ value ->
        val url = value.toHttpUrlOrNull() ?: return@gate false
        !preferences.localNetworkOnly || hostDecisions.getOrPut(url.host) { LocalNetworkPolicy.isLocalHost(url.host) }
    }
}

internal fun Response.limitedUtf8(maxBytes: Int): String {
    body.byteStream().use { input ->
        val output = ByteArrayOutputStream(minOf(maxBytes, 32 * 1024))
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            check(total <= maxBytes) { "DLNA response exceeds $maxBytes bytes" }
            output.write(buffer, 0, read)
        }
        return output.toString(Charsets.UTF_8.name())
    }
}
