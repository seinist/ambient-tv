package com.ambient.tv.games

import com.ambient.tv.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Explicit user-action download from the existing GitHub release publisher; NEVER at game start. */
@Singleton
class GameShaderPackChannel @Inject constructor(private val store: GameShaderPackStore) {
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true }

    val available: Boolean get() = store.available &&
        BuildConfig.CORES_REPO.matches(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"))

    /** A release has exactly one named archive. Its contents are authenticated by the pinned key. */
    suspend fun installLatest(): GameShaderPackManifest = withContext(Dispatchers.IO) {
        if (!available) throw IOException("Signed shader pack channel unavailable")
        val repo = BuildConfig.CORES_REPO
        val releases = json.decodeFromString(ListSerializer(Release.serializer()),
            fetch("https://api.github.com/repos/" + repo + "/releases?per_page=40", 2 * 1024 * 1024)
                .decodeToString())
        val release = releases.firstOrNull { !it.draft && !it.prerelease &&
            it.tag.matches(Regex("shader-packs-[a-zA-Z0-9._-]{1,70}")) }
            ?: throw IOException("No published shader packs")
        val url = release.assets.singleOrNull { it.name == "shader-pack.zip" }?.url
            ?: throw IOException("Signed shader pack archive missing")
        // Do not accept metadata-driven arbitrary URLs; GitHub may redirect to its asset CDN.
        val officialPrefix = "https://github.com/" + repo + "/releases/download/" + release.tag + "/"
        if (url != officialPrefix + "shader-pack.zip") throw IOException("Unexpected shader pack origin")
        store.install(ByteArrayInputStream(fetch(url, GameShaderPackArchive.MAX_ARCHIVE_BYTES)))
    }

    private fun fetch(url: String, maxBytes: Int): ByteArray {
        val request = Request.Builder().url(url).header("Accept", "application/octet-stream").build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Shader download HTTP " + response.code)
            val body = response.body
            if (body.contentLength() > maxBytes) throw IOException("Shader download exceeds size limit")
            val out = ByteArrayOutputStream()
            body.byteStream().use { stream ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    if (out.size() + count > maxBytes) throw IOException("Shader download exceeds size limit")
                    if (count > 0) out.write(buffer, 0, count)
                }
            }
            return out.toByteArray()
        }
    }

    @Serializable
    private data class Asset(val name: String, @SerialName("browser_download_url") val url: String)
    @Serializable
    private data class Release(
        @SerialName("tag_name") val tag: String,
        val draft: Boolean = false,
        val prerelease: Boolean = false,
        val assets: List<Asset> = emptyList(),
    )
}
