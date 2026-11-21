package com.ambient.tv.games

import com.ambient.tv.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Where cores come from. */
enum class GameCoreSource(val key: String) {
    /** The app's own signed build when it has the core, otherwise the libretro buildbot. */
    AUTO("auto"),
    /** Only the app's own build (pinned commits, 16 KB pages, signed manifest). */
    AMBIENT("ambient"),
    /** Only the libretro buildbot (nightly). */
    BUILDBOT("buildbot"),
    ;

    companion object {
        fun fromKey(key: String?) = entries.firstOrNull { it.key == key } ?: AUTO
    }
}

/** One core build of the app's own channel. */
@Serializable
data class GameCoreBuild(
    val core: String,
    val abi: String,
    val asset: String,
    val sha256: String,
    val size: Long = 0,
    val commit: String = "",
    val repo: String = "",
)

@Serializable
data class GameCoreManifest(val version: Int = 1, val tag: String = "", val created: String = "", val cores: List<GameCoreBuild> = emptyList())

class GameCoreSignatureException(message: String) : IOException(message)

/**
 * The app's own core channel: the newest "cores-…" release of the app repository, whose
 * manifest.json is signed (ECDSA P-256) by the key in cores/signing-public.pem. Every core zip is
 * checked against the manifest's SHA-256 before it is installed.
 */
@Singleton
class GameCoreChannel @Inject constructor() {
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Mutex()
    private var cached: Pair<GameCoreManifest, Map<String, String>>? = null
    private var cachedAt = 0L

    /** False in builds without the public key: the channel then never answers. */
    val available: Boolean get() = BuildConfig.CORES_PUBLIC_KEY.isNotBlank()

    /** The verified manifest and its asset URLs, or null when there is no release yet. */
    suspend fun manifest(): Pair<GameCoreManifest, Map<String, String>>? = lock.withLock {
        if (!available) return@withLock null
        cached?.takeIf { System.currentTimeMillis() - cachedAt < CACHE_MS }?.let { return@withLock it }
        withContext(Dispatchers.IO) {
            val releases = json.decodeFromString(ListSerializer(Release.serializer()),
                get("https://api.github.com/repos/${BuildConfig.CORES_REPO}/releases?per_page=40").decodeToString())
            val release = releases.filter { it.tag.startsWith("cores-") && !it.draft }.maxByOrNull { it.published.orEmpty() }
                ?: return@withContext null
            val assets = release.assets.associate { it.name to it.url }
            val manifestBytes = get(assets["manifest.json"] ?: throw GameCoreSignatureException("manifest missing"))
            val signature = get(assets["manifest.json.sig"] ?: throw GameCoreSignatureException("signature missing"))
            if (!verify(manifestBytes, signature, BuildConfig.CORES_PUBLIC_KEY)) throw GameCoreSignatureException("manifest signature invalid")
            val manifest = json.decodeFromString(GameCoreManifest.serializer(), manifestBytes.decodeToString())
            (manifest to assets).also {
                cached = it
                cachedAt = System.currentTimeMillis()
            }
        }
    }

    /** The build of [core] for [abi], with its download URL, or null when the channel lacks it. */
    suspend fun build(core: GameCore, abi: String): Pair<GameCoreBuild, String>? {
        val (manifest, assets) = manifest() ?: return null
        val build = manifest.cores.firstOrNull { it.core == core.id && it.abi == abi } ?: return null
        val url = assets[build.asset] ?: return null
        return build to url
    }

    /** Release-list and signature files are untrusted until the manifest signature is verified. */
    private fun get(url: String): ByteArray {
        val request = Request.Builder().url(url).header("Accept", "application/octet-stream, application/json").build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body
            if (body.contentLength() > MAX_METADATA_BYTES)
                throw IOException("Core channel metadata exceeds limit")
            val result = java.io.ByteArrayOutputStream()
            body.byteStream().use { input ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (result.size().toLong() + n > MAX_METADATA_BYTES)
                        throw IOException("Core channel metadata exceeds limit")
                    result.write(buffer, 0, n)
                }
            }
            return result.toByteArray()
        }
    }

    @Serializable private data class Asset(
        val name: String,
        @SerialName("browser_download_url") val url: String,
    )
    @Serializable private data class Release(
        @SerialName("tag_name") val tag: String,
        val draft: Boolean = false,
        @SerialName("published_at") val published: String? = null,
        val assets: List<Asset> = emptyList(),
    )

    companion object {
        private const val CACHE_MS = 30 * 60_000L
        private const val MAX_METADATA_BYTES = 8L * 1024 * 1024

        /** ECDSA P-256 / SHA-256 over [data] (signature DER, as openssl dgst -sign writes it). */
        fun verify(data: ByteArray, signature: ByteArray, publicKeyBase64: String): Boolean = runCatching {
            val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(java.util.Base64.getMimeDecoder().decode(publicKeyBase64)))
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(key)
                update(data)
                verify(signature)
            }
        }.getOrDefault(false)

        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
