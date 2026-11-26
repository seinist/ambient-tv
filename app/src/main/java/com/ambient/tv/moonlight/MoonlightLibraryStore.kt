package com.ambient.tv.moonlight

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json

@Serializable
data class MoonlightHostCatalog(
    val serverId: String,
    val updatedEpochMs: Long,
    val apps: List<MoonlightApp>,
    val peerIdentity: String? = null,
)

/**
 * Persistent nonsecret Sunshine app metadata, favorites, recents and artwork.
 * Paired identity is checked on *every* cache read: removing/changing a host's certificate
 * invalidates its old catalog even if the hostname or IP is the same. A cached item is NOT
 * proof that Sunshine is online, and must never be launched without fresh mutual TLS.
 */
@Singleton
class MoonlightLibraryStore @Inject constructor(
    @ApplicationContext context: Context,
    private val trust: MoonlightHostTrust,
) {
    private val prefs = context.getSharedPreferences("moonlight_library_v1", Context.MODE_PRIVATE)
    private val artworkDir = File(context.filesDir, "moonlight_artwork").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Any()
    private val mutableRevisions = MutableStateFlow(0L)
    val revisions = mutableRevisions.asStateFlow()
    private val mutableMetadataRevisions = MutableStateFlow(0L)
    val metadataRevisions = mutableMetadataRevisions.asStateFlow()
    private val mutableArtworkRevisions = MutableStateFlow(0L)
    val artworkRevisions = mutableArtworkRevisions.asStateFlow()
    private val online = mutableMapOf<String, Pair<String, Long>>()
    private fun changed(metadata: Boolean = true) {
        mutableRevisions.value += 1L
        if (metadata) mutableMetadataRevisions.value += 1L
        else mutableArtworkRevisions.value += 1L
    }

    fun isRecentlyOnline(hostId: String): Boolean = synchronized(lock) {
        val identity = trust.identity(hostId) ?: return false
        val probe = online[hostId] ?: return false
        probe.first == identity &&
            android.os.SystemClock.elapsedRealtime() - probe.second in 0 until CATALOG_TTL_MS
    }

    fun markOffline(hostId: String) = synchronized(lock) { online.remove(hostId); Unit }

    fun invalidateCatalog(hostId: String) = synchronized(lock) {
        online.remove(hostId)
        if (prefs.contains("catalog:$hostId")) {
            prefs.edit().remove("catalog:$hostId").apply()
            changed()
        }
    }
    private val prefix = Regex("[a-zA-Z0-9_-]{1,64}")

    fun catalog(hostId: String): MoonlightHostCatalog? = synchronized(lock) {
        if (!prefix.matches(hostId)) return null
        val pinned = trust.trustedServerId(hostId) ?: return null
        val identity = trust.identity(hostId) ?: return null
        val text = prefs.getString("catalog:$hostId", null)?.takeIf { it.length <= 384 * 1024 } ?: return null
        val cached = runCatching { json.decodeFromString<MoonlightHostCatalog>(text) }.getOrNull()
        cached?.takeIf { it.serverId == pinned && it.peerIdentity == identity && it.apps.size <= 4096 }
    }

    fun putCatalog(hostId: String, catalog: MoonlightAppCatalog) = synchronized(lock) {
        require(prefix.matches(hostId))
        val serverId = trust.trustedServerId(hostId)
            ?: throw SecurityException("Cannot cache applications for an unpaired host")
        val identity = trust.identity(hostId) ?: throw SecurityException("Missing Moonlight identity")
        if (catalog.peerIdentity != identity) throw SecurityException("Moonlight catalog identity changed")
        val apps = catalog.apps.filter { it.id > 0 && it.title.isNotBlank() }.take(4096)
        val old = catalog(hostId)
        val value = json.encodeToString(MoonlightHostCatalog(
            serverId, System.currentTimeMillis(), apps, identity,
        ))
        require(value.length <= 384 * 1024)
        prefs.edit().putString("catalog:$hostId", value).apply()
        online[hostId] = identity to android.os.SystemClock.elapsedRealtime()
        if (old?.apps != apps) changed()
    }

    fun favorites(): Set<String> = synchronized(lock) {
        prefs.getStringSet("favorites", emptySet())?.toSet().orEmpty().filterTo(mutableSetOf(), ::validAppKey)
    }

    fun favorite(hostId: String, appId: Int): Boolean = key(hostId, appId) in favorites()

    fun toggleFavorite(hostId: String, appId: Int): Boolean = synchronized(lock) {
        val item = key(hostId, appId)
        val next = favorites().toMutableSet()
        val added = if (item in next) { next.remove(item); false } else { next.add(item); true }
        prefs.edit().putStringSet("favorites", next.take(2048).toSet()).apply()
        changed()
        added
    }

    fun recent(): List<String> = synchronized(lock) {
        prefs.getString("recent", null)?.split('|').orEmpty().filter(::validAppKey).distinct().take(128)
    }

    fun recordLaunch(hostId: String, appId: Int) = synchronized(lock) {
        val item = key(hostId, appId)
        val ordered = listOf(item) + recent().filterNot { it == item }
        if (ordered.take(128) != recent()) {
            prefs.edit().putString("recent", ordered.take(128).joinToString("|")).apply()
            changed()
        }
    }

    fun peerIdentity(hostId: String): String? = trust.identity(hostId)

    fun artworkFile(hostId: String, appId: Int): File? {
        if (!prefix.matches(hostId) || appId <= 0) return null
        val identity = trust.identity(hostId) ?: return null
        return File(artworkDir, "$hostId-$identity-$appId.img").takeIf { it.isFile && it.length() in 1..MAX_IMAGE_BYTES.toLong() }
    }

    /** Caller has already fetched image bytes over mutually authenticated, pinned HTTPS. */
    fun saveArtwork(hostId: String, appId: Int, bytes: ByteArray, expectedIdentity: String): File? = synchronized(lock) {
        if (!prefix.matches(hostId) || appId <= 0 || trust.identity(hostId) != expectedIdentity ||
            bytes.size !in 1..MAX_IMAGE_BYTES) return null
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth !in 1..4096 || bounds.outHeight !in 1..4096 ||
            bounds.outWidth.toLong() * bounds.outHeight > 8_388_608L) return null
        val target = File(artworkDir, "$hostId-$expectedIdentity-$appId.img")
        val temporary = File(artworkDir, "$hostId-$expectedIdentity-$appId.part")
        try {
            temporary.writeBytes(bytes)
            if (!temporary.renameTo(target)) {
                temporary.delete()
                return null
            }
            trimArtwork()
            changed(metadata = false)
            target
        } catch (_: Exception) {
            temporary.delete()
            null
        }
    }

    fun clearHost(hostId: String) = synchronized(lock) {
        if (!prefix.matches(hostId)) return
        online.remove(hostId)
        prefs.edit().remove("catalog:$hostId").putStringSet(
            "favorites", favorites().filterNot { it.startsWith("$hostId:") }.toSet(),
        ).putString("recent", recent().filterNot { it.startsWith("$hostId:") }.joinToString("|")).apply()
        artworkDir.listFiles()?.filter { it.name.startsWith("$hostId-") }?.forEach(File::delete)
        changed()
    }

    private fun trimArtwork() {
        val images = artworkDir.listFiles()?.filter { it.isFile && it.name.endsWith(".img") } ?: return
        var total = images.sumOf(File::length)
        if (total <= MAX_CACHE_BYTES) return
        for (file in images.sortedBy(File::lastModified)) {
            if (total <= MAX_CACHE_BYTES) break
            val length = file.length()
            if (file.delete()) total -= length
        }
    }

    private fun key(hostId: String, appId: Int): String {
        require(prefix.matches(hostId) && appId > 0)
        return "$hostId:$appId"
    }
    private fun validAppKey(key: String): Boolean {
        val parts = key.split(':')
        return parts.size == 2 && prefix.matches(parts[0]) &&
            (parts[1].toIntOrNull() ?: 0) > 0
    }

    private companion object {
        const val CATALOG_TTL_MS = 60_000L
        const val MAX_IMAGE_BYTES = 3 * 1024 * 1024
        const val MAX_CACHE_BYTES = 120L * 1024 * 1024
    }
}
