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
enum class GameCoreSource(val key: String, val experimentalProfile: String? = null) {
    /** The app's own signed build when it has the core, otherwise the libretro buildbot. */
    AUTO("auto"),
    /** Only the app's own build (pinned commits, 16 KB pages, signed manifest). */
    AMBIENT("ambient"),
    /** Only the libretro buildbot (nightly). */
    BUILDBOT("buildbot"),
    HOTPATHS("experiment-hotpaths", "core-hotpaths"),
    O3("experiment-o3", "o3"),
    THINLTO("experiment-thinlto", "thinlto"),
    O3_THINLTO("experiment-o3-thinlto", "o3-thinlto"),
    TUNE_A57("experiment-a57", "tune-a57"),
    PICODRIVE_SH2("experiment-sh2", "picodrive-sh2"),
    SNES_THREADED("experiment-snes-threaded", "snes9x2010-threaded"),
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
data class GameCoreManifest(val version: Int = 1, val tag: String = "", val created: String = "", val cores: List<GameCoreBuild> = emptyList(), val profile: String = "baseline")

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
    private data class VerifiedRelease(val manifest: GameCoreManifest, val assets: Map<String, String>)
    private var cachedReleases = emptyList<Release>()
    private var cachedAt = 0L
    private val verified = mutableMapOf<String, VerifiedRelease>()

    /** False in builds without the public key: the channel then never answers. */
    val available: Boolean get() = BuildConfig.CORES_PUBLIC_KEY.isNotBlank()

    data class Download(val build: GameCoreBuild, val url: String, val tag: String)

    /** Resolve partial experimental releases per core and ABI; never mix channels. */
    suspend fun build(core: GameCore, abi: String, source: GameCoreSource = GameCoreSource.AMBIENT): Download? = lock.withLock {
        if (!available) return@withLock null
        withContext(Dispatchers.IO) {
            if (cachedAt == 0L || System.currentTimeMillis() - cachedAt !in 0 until CACHE_MS) {
                val releases = mutableListOf<Release>()
                // Experiments must not push the production release outside the first page.
                // A page of 100 complete core catalogs can exceed the metadata cap.
                // Never silently fall back because a fixed history window was exhausted.
                for (page in 1..100) {
                    val batch = json.decodeFromString(ListSerializer(Release.serializer()),
                        get("https://api.github.com/repos/${BuildConfig.CORES_REPO}/releases?per_page=10&page=$page").decodeToString())
                    releases += batch
                    if (batch.size < 10) break
                    if (page == 100) throw IOException("Core release history exceeds supported search limit")
                }
                cachedReleases = releases
                cachedAt = System.currentTimeMillis()
                verified.clear()
            }
            val profile = source.experimentalProfile ?: "baseline"
            val candidates = cachedReleases.filter { !it.draft && matchesChannel(it.tag, it.prerelease, profile) }
                .sortedByDescending { it.published.orEmpty() }
            for (release in candidates) {
                val expectedAsset = "$abi--${core.id}_libretro_android.so.zip"
                // Only the signed manifest may establish that a core is absent.
                val entry = verified[release.tag] ?: run {
                    val assets = release.assets.associate { it.name to it.url }
                    if (assets.size != release.assets.size) throw GameCoreSignatureException("duplicate release assets")
                    val bytes = get(assets["manifest.json"] ?: throw GameCoreSignatureException("manifest missing"))
                    val signature = get(assets["manifest.json.sig"] ?: throw GameCoreSignatureException("signature missing"))
                    if (!verify(bytes, signature, BuildConfig.CORES_PUBLIC_KEY)) throw GameCoreSignatureException("manifest signature invalid")
                    val manifest = try {
                        json.decodeFromString(GameCoreManifest.serializer(), bytes.decodeToString())
                    } catch (_: kotlinx.serialization.SerializationException) {
                        throw GameCoreSignatureException("invalid signed manifest")
                    }
                    if (!matchesManifest(manifest, release.tag, profile)) throw GameCoreSignatureException("manifest channel mismatch")
                    VerifiedRelease(manifest, assets).also { verified[release.tag] = it }
                }
                val build = entry.manifest.cores.singleOrNull { it.core == core.id && it.abi == abi }
                if (build == null) {
                    if (profile == "baseline") return@withContext null
                    if (expectedAsset in entry.assets) throw GameCoreSignatureException("experimental core missing from signed manifest")
                    continue
                }
                if (build.asset != expectedAsset || !build.sha256.matches(Regex("[0-9a-fA-F]{64}")) || build.size <= 0)
                    throw GameCoreSignatureException("invalid core manifest entry")
                val url = entry.assets[build.asset] ?: throw GameCoreSignatureException("signed asset missing")
                return@withContext Download(build, url, release.tag)
            }
            null
        }
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
        val prerelease: Boolean = false,
        @SerialName("published_at") val published: String? = null,
        val assets: List<Asset> = emptyList(),
    )

    companion object {
        internal fun matchesChannel(tag: String, prerelease: Boolean, profile: String): Boolean =
            if (profile == "baseline") !prerelease && tag.startsWith("cores-")
            else prerelease && tag.startsWith("core-experiments-$profile--")

        internal fun matchesManifest(manifest: GameCoreManifest, tag: String, profile: String): Boolean =
            manifest.version == 1 && manifest.tag == tag && manifest.profile == profile &&
                manifest.cores.map { it.core to it.abi }.distinct().size == manifest.cores.size

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
