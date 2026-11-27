package com.ambient.tv.modules

import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/** Fixed-origin GitHub release transport. No URLs or trust keys from unsigned metadata. */
internal class SystemModuleTransport {
    private val client = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.MINUTES)
        .build()

    /** Blocking I/O; invoke from a background worker, not the main thread. */
    fun install(
        store: SystemModuleStore,
        module: SystemModule,
        tag: String,
        profile: String = "baseline",
    ): SystemModuleStore.Package? {
        require(Regex("modules-[A-Za-z0-9][A-Za-z0-9._-]{0,100}").matches(tag))
        val manifest = readMetadata(releaseUrl(tag, "manifest.json"), SystemModuleChannel.MAX_MANIFEST_BYTES)
        val signature = readMetadata(releaseUrl(tag, "manifest.json.sig"), 256)
        return store.install(module, tag, manifest, signature, profile) { build ->
            open(releaseUrl(tag, build.asset), build.size)
        }
    }

    private fun releaseUrl(tag: String, asset: String): HttpUrl = HttpUrl.Builder()
        .scheme("https").host("github.com")
        .addPathSegments("seinist/ambient-tv/releases/download")
        .addPathSegment(tag).addPathSegment(asset).build()

    private fun readMetadata(url: HttpUrl, limit: Int): ByteArray =
        open(url, limit.toLong()).use { input ->
            val bytes = ByteArray(limit + 1)
            var count = 0
            while (count < bytes.size) {
                val n = input.read(bytes, count, bytes.size - count)
                if (n == -1) break
                if (n == 0) throw IOException("Module response made no progress")
                count += n
            }
            if (count !in 1..limit) throw SystemModuleTrustException("Module metadata exceeds bounds")
            bytes.copyOf(count)
        }

    private fun open(initial: HttpUrl, limit: Long): InputStream {
        var url = initial
        repeat(6) {
            if (!allowed(url)) throw SystemModuleTrustException("Untrusted module download origin")
            val response = client.newCall(Request.Builder().url(url)
                .header("Accept-Encoding", "identity").get().build()).execute()
            if (response.code in setOf(301, 302, 303, 307, 308)) {
                response.use {
                    url = response.header("Location")?.let(url::resolve)
                        ?: throw IOException("Invalid module redirect")
                }
                return@repeat
            }
            try {
                if (response.code != 200) throw IOException("Module download HTTP ${response.code}")
                val body = response.body ?: throw IOException("Missing module response")
                if (response.header("Content-Encoding")?.equals("identity", ignoreCase = true) == false)
                    throw SystemModuleTrustException("Unexpected module transfer encoding")
                val length = body.contentLength()
                if (length == 0L || length > limit)
                    throw SystemModuleTrustException("Module response exceeds bounds")
                return object : FilterInputStream(body.byteStream()) {
                    override fun close() {
                        response.close()
                    }
                }
            } catch (e: Exception) {
                response.close()
                throw e
            }
        }
        throw IOException("Too many module download redirects")
    }

    companion object {
        internal fun allowed(url: HttpUrl): Boolean =
            url.isHttps && url.port == 443 && url.username.isEmpty() && url.password.isEmpty() &&
                url.host in setOf("github.com", "release-assets.githubusercontent.com",
                    "objects.githubusercontent.com", "github-releases.githubusercontent.com")
    }
}
