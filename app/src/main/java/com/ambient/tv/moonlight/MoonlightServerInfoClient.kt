package com.ambient.tv.moonlight

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import com.ambient.tv.data.local.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Only unauthenticated GameStream discovery. Streaming, application listing and pairing
 * must use pinned upstream Moonlight transport with mutual TLS and trusted host certificates.
 * This client deliberately makes no HTTPS requests and never overrides TLS validation.
 */
class MoonlightServerInfoClient @Inject constructor(private val preferences: AppPreferences) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(2500, TimeUnit.MILLISECONDS)
        .readTimeout(3000, TimeUnit.MILLISECONDS)
        .callTimeout(5000, TimeUnit.MILLISECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    suspend fun probe(host: MoonlightHost): MoonlightServerInfo = withContext(Dispatchers.IO) {
        val valid = host.validated()
        val url = HttpUrl.Builder()
            .scheme("http")
            .host(valid.host)
            .port(valid.port)
            .addPathSegment("serverinfo")
            .addQueryParameter("uniqueid", preferences.moonlightDeviceId)
            .build()
        client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Moonlight HTTP " + response.code)
            val stream = response.body?.byteStream() ?: throw IOException("Empty Moonlight response")
            val bytes = stream.use { input ->
                val output = ByteArrayOutputStream()
                val block = ByteArray(4096)
                while (true) {
                    val count = input.read(block)
                    if (count < 0) break
                    if (output.size() + count > 64 * 1024) throw IOException("Moonlight response too large")
                    output.write(block, 0, count)
                }
                output.toByteArray()
            }
            parseMoonlightServerInfo(bytes)
        }
    }
}
