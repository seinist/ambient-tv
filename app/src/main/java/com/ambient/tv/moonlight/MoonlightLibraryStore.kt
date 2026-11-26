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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

data class MoonlightArtwork(val file: File, val width: Int, val height: Int) {
    val aspectRatio: Float get() = width.toFloat() / height.coerceAtLeast(1)
}

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
    private val artworkLock = Any()
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private data class Cached(val identity: String, val value: MoonlightHostCatalog)
    private val catalogs = mutableMapOf<String, Cached>()
    private val requestIds = mutableMapOf<String, Long>()
    private var sequence = 0L
    private var artworkBytes = -1L
    // Access time is separate from download freshness (lastModified).
    private val artworkAccess = object : android.util.LruCache<String, Long>(4096) {}
    private val negativeArtwork = LinkedHashMap<String, Long>()
    private val mutableAvailability = MutableStateFlow(0L)
    val availabilityRevisions = mutableAvailability.asStateFlow()
    private val mutableArtworkChanges = MutableStateFlow<Map<String, Long>>(emptyMap())
    val artworkChanges = mutableArtworkChanges.asStateFlow()

    data class Request internal constructor(val hostId: String, val identity: String, val revision: Long)
    fun beginRequest(hostId: String): Request? = synchronized(lock) {
        val identity = trust.identity(hostId) ?: return null
        val revision = ++sequence
        requestIds[hostId] = revision
        Request(hostId, identity, revision)
    }
    fun isCurrent(request: Request): Boolean = synchronized(lock) {
        requestIds[request.hostId] == request.revision && trust.identity(request.hostId) == request.identity
    }
    private val mutableRevisions = MutableStateFlow(0L)
    val revisions = mutableRevisions.asStateFlow()
    private val mutableMetadataRevisions = MutableStateFlow(0L)
    val metadataRevisions = mutableMetadataRevisions.asStateFlow()
    private val mutableArtworkRevisions = MutableStateFlow(0L)
    val artworkRevisions = mutableArtworkRevisions.asStateFlow()
    private val online = mutableMapOf<String, Pair<String, Long>>()
    private val expiryJobs = mutableMapOf<String, kotlinx.coroutines.Job>()

    // A live proof expires even if no browse screen is open. Renewing a proof replaces its timer.
    private fun renewOnline(hostId: String, identity: String) {
        val stamp = android.os.SystemClock.elapsedRealtime()
        val proof = identity to stamp
        online[hostId] = proof
        expiryJobs.remove(hostId)?.cancel()
        expiryJobs[hostId] = io.launch {
            kotlinx.coroutines.delay(MOONLIGHT_PROBE_TTL_MS)
            synchronized(lock) {
                if (online[hostId] == proof && !isRecentlyOnline(hostId)) {
                    online.remove(hostId)
                    expiryJobs.remove(hostId)
                    mutableAvailability.value++
                }
            }
        }
    }

    private fun removeOnline(hostId: String): Boolean {
        expiryJobs.remove(hostId)?.cancel()
        return online.remove(hostId) != null
    }
    private val offlineIdentities = mutableMapOf<String, String>()
    val trustRevisions get() = trust.revisions

    fun isKnownOffline(hostId: String): Boolean = synchronized(lock) {
        val identity = trust.identity(hostId) ?: return@synchronized false
        offlineIdentities[hostId] == identity && !isRecentlyOnline(hostId)
    }
    private fun changed(metadata: Boolean = true) {
        mutableRevisions.value += 1L
        if (metadata) mutableMetadataRevisions.value += 1L
        else mutableArtworkRevisions.value += 1L
    }

    fun isCatalogFresh(hostId: String, maxAgeMs: Long = 5 * 60_000L): Boolean = synchronized(lock) {
        val age = catalog(hostId)?.let { System.currentTimeMillis() - it.updatedEpochMs }
        age != null && age in 0 until maxAgeMs
    }

    fun isRecentlyOnline(hostId: String): Boolean = synchronized(lock) {
        val identity = trust.identity(hostId) ?: return false
        val probe = online[hostId] ?: return false
        moonlightProbeFresh(identity, probe.first, probe.second, android.os.SystemClock.elapsedRealtime())
    }

    fun markOnline(request: Request): Boolean = synchronized(lock) {
        if (!isCurrent(request)) return@synchronized false
        val wasOnline = isRecentlyOnline(request.hostId)
        renewOnline(request.hostId, request.identity)
        val wasOffline = offlineIdentities.remove(request.hostId) != null
        if (!wasOnline || wasOffline) mutableAvailability.value++
        true
    }

    fun markOffline(hostId: String, request: Request? = null) = synchronized(lock) {
        if (request != null && !isCurrent(request)) return@synchronized
        requestIds[hostId] = ++sequence
        val previous = offlineIdentities[hostId]
        if (request == null) offlineIdentities.remove(hostId) else offlineIdentities[hostId] = request.identity
        val removed = removeOnline(hostId)
        if (removed || previous != offlineIdentities[hostId]) mutableAvailability.value++
    }

    fun invalidateCatalog(hostId: String, request: Request? = null) = synchronized(lock) {
        if (request != null && !isCurrent(request)) return@synchronized
        requestIds[hostId] = ++sequence
        val wasOffline = offlineIdentities.remove(hostId) != null
        if (removeOnline(hostId) || wasOffline) mutableAvailability.value++
        val removed = catalogs.remove(hostId) != null
        if (prefs.contains("catalog:$hostId")) {
            prefs.edit().remove("catalog:$hostId").apply()
            changed()
        } else if (removed) changed()
    }
    private val prefix = Regex("[a-zA-Z0-9_-]{1,64}")

    fun catalog(hostId: String): MoonlightHostCatalog? = synchronized(lock) {
        if (!prefix.matches(hostId)) return null
        val pinned = trust.trustedServerId(hostId) ?: return null
        val identity = trust.identity(hostId) ?: return null
        catalogs[hostId]?.takeIf { it.identity == identity }?.let { return it.value }
        val text = prefs.getString("catalog:$hostId", null)?.takeIf { it.length <= MAX_CATALOG_CHARS } ?: return null
        val cached = runCatching { json.decodeFromString<MoonlightHostCatalog>(text) }.getOrNull()
            ?.takeIf { it.serverId == pinned && it.peerIdentity == identity && it.apps.size <= 4096 }
        cached?.also { catalogs[hostId] = Cached(identity, it) }
    }

    fun putCatalog(hostId: String, catalog: MoonlightAppCatalog, request: Request? = null): Boolean = synchronized(lock) {
        if (request != null && !isCurrent(request)) return false
        require(prefix.matches(hostId))
        val serverId = trust.trustedServerId(hostId)
            ?: throw SecurityException("Cannot cache applications for an unpaired host")
        val identity = trust.identity(hostId) ?: throw SecurityException("Missing Moonlight identity")
        if (catalog.peerIdentity != identity) throw SecurityException("Moonlight catalog identity changed")
        val apps = catalog.apps.filter { it.id > 0 && it.title.isNotBlank() }.take(4096)
        val old = catalog(hostId)
        val cached = MoonlightHostCatalog(serverId, System.currentTimeMillis(), apps, identity)
        // The XML parser permits 1 MiB and up to 4096 apps; escaped JSON can be larger.
        // A valid live catalog remains usable even if persistence is unavailable.
        val value = json.encodeToString(cached)
        prefs.edit().apply {
            if (value.length <= MAX_CATALOG_CHARS) putString("catalog:$hostId", value)
            else remove("catalog:$hostId") // Never resurrect an obsolete catalog on process restart.
        }.apply()
        catalogs[hostId] = Cached(identity, cached)
        val wasOnline = isRecentlyOnline(hostId)
        renewOnline(hostId, identity)
        val wasOffline = offlineIdentities.remove(hostId) != null
        if (!wasOnline || wasOffline) mutableAvailability.value++
        if (old?.apps != apps) changed()
        true
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
        if (!validMoonlightHostId(hostId) || appId <= 0) return null
        val identity = trust.identity(hostId) ?: return null
        val directory = File(artworkDir, "$hostId/$identity/$appId")
        directory.listFiles()?.filter { it.isFile && it.extension == "img" && it.length() in 1..MAX_IMAGE_BYTES.toLong() }
            ?.maxByOrNull(File::lastModified)?.let { return it }
        val target = File(artworkDir, "$hostId/$identity/$appId.img")
        return target.takeIf { it.isFile && it.length() in 1..MAX_IMAGE_BYTES.toLong() }
            ?: File(artworkDir, "$hostId-$identity-$appId.img")
                .takeIf { it.isFile && it.length() in 1..MAX_IMAGE_BYTES.toLong() }
    }

    private val artworkBounds = object : android.util.LruCache<String, MoonlightArtwork>(1024) {}

    fun artworkInfo(hostId: String, appId: Int): MoonlightArtwork? {
        val file = artworkFile(hostId, appId) ?: return null
        artworkAccess.put(file.absolutePath, System.currentTimeMillis())
        artworkBounds.get(file.absolutePath)?.let { return it }
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        return MoonlightArtwork(file, bounds.outWidth, bounds.outHeight).also { artworkBounds.put(file.absolutePath, it) }
    }

    private fun artworkKey(hostId: String, appId: Int, identity: String) = "$hostId:$identity:$appId"

    fun shouldLoadArtwork(hostId: String, appId: Int, identity: String): Boolean {
        if (trust.identity(hostId) != identity) return false
        val now = android.os.SystemClock.elapsedRealtime()
        val file = artworkFile(hostId, appId)
        val fresh = file?.let { System.currentTimeMillis() - it.lastModified() in 0 until 86_400_000L } == true
        if (fresh) return false
        return synchronized(artworkLock) { (negativeArtwork[artworkKey(hostId, appId, identity)] ?: 0L) <= now }
    }

    fun artworkUnavailable(hostId: String, appId: Int, identity: String) = synchronized(artworkLock) {
        negativeArtwork[artworkKey(hostId, appId, identity)] = android.os.SystemClock.elapsedRealtime() + 120_000L
        while (negativeArtwork.size > 2048) negativeArtwork.remove(negativeArtwork.keys.first())
    }

    /** Caller has already fetched image bytes over mutually authenticated, pinned HTTPS. */
    fun saveArtwork(hostId: String, appId: Int, bytes: ByteArray, expectedIdentity: String): File? {
        if (!validMoonlightHostId(hostId) || appId <= 0 || trust.identity(hostId) != expectedIdentity ||
            bytes.size !in 1..MAX_IMAGE_BYTES) return null
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth !in 1..4096 || bounds.outHeight !in 1..4096 ||
            bounds.outWidth.toLong() * bounds.outHeight > 8_388_608L) return null
        return synchronized(artworkLock) {
            if (trust.identity(hostId) != expectedIdentity) return@synchronized null
            val directory = File(artworkDir, "$hostId/$expectedIdentity/$appId").apply { mkdirs() }
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            val target = File(directory, "$digest.img")
            if (target.isFile && target.length() == bytes.size.toLong()) {
                target.setLastModified(System.currentTimeMillis())
                negativeArtwork.remove(artworkKey(hostId, appId, expectedIdentity))
                return@synchronized target
            }
            val temporary = File(directory, "$digest.part")
            try {
                if (artworkBytes < 0) artworkBytes = artworkDir.walkTopDown().filter { it.isFile && it.extension == "img" }.sumOf(File::length)
                val previousLength = target.length()
                temporary.writeBytes(bytes)
                if (!temporary.renameTo(target)) { temporary.delete(); return@synchronized null }
                artworkBytes += bytes.size - previousLength
                // The content-addressed filename changes only when bytes change, so Coil/Home
                // cannot reuse an obsolete decode. Keep one revision per app on disk.
                directory.listFiles()?.filter { it != target && it.extension == "img" }?.forEach { old ->
                    val length = old.length()
                    if (old.delete()) artworkBytes -= length
                }
                val legacy = File(directory.parentFile, "$appId.img")
                val legacyLength = legacy.length()
                if (legacy.delete()) artworkBytes -= legacyLength
                artworkBounds.put(target.absolutePath, MoonlightArtwork(target, bounds.outWidth, bounds.outHeight))
                negativeArtwork.remove(artworkKey(hostId, appId, expectedIdentity))
                trimArtwork()
                val revision = mutableArtworkRevisions.value + 1
                val changes = LinkedHashMap(mutableArtworkChanges.value)
                val item = "$hostId:$appId"
                changes.remove(item)
                changes[item] = revision
                while (changes.size > 1024) changes.remove(changes.keys.first())
                synchronized(lock) { changed(metadata = false) }
                mutableArtworkChanges.value = changes
                target
            } catch (_: Exception) {
                temporary.delete()
                null
            }
        }
    }

    fun clearHost(hostId: String) {
        if (!validMoonlightHostId(hostId)) return
        synchronized(lock) {
            requestIds[hostId] = ++sequence
            catalogs.remove(hostId)
            val wasOffline = offlineIdentities.remove(hostId) != null
            if (removeOnline(hostId) || wasOffline) mutableAvailability.value++
            prefs.edit().remove("catalog:$hostId").putStringSet(
                "favorites", favorites().filterNot { it.startsWith("$hostId:") }.toSet(),
            ).putString("recent", recent().filterNot { it.startsWith("$hostId:") }.joinToString("|")).apply()
            changed()
        }
        // Do not hold the metadata lock across disk I/O or delete a new pairing's artwork.
        io.launch {
            synchronized(artworkLock) {
                val current = trust.identity(hostId)
                File(artworkDir, hostId).listFiles()?.filter { it.name != current }?.forEach { it.deleteRecursively() }
                // Older experimental flat filenames are harmless but use exact host-id matching.
                val legacy = Regex(Regex.escape(hostId) + "-[a-f0-9]{64}-[0-9]+\\.(img|part)")
                artworkDir.listFiles()?.filter { it.isFile && legacy.matches(it.name) &&
                    (current == null || !it.name.startsWith("$hostId-$current-")) }?.forEach(File::delete)
                negativeArtwork.keys.removeAll { it.startsWith("$hostId:") }
                artworkBytes = -1L
            }
        }
    }

    private fun trimArtwork() {
        if (artworkBytes <= MAX_CACHE_BYTES) return
        val images = artworkDir.walkTopDown().filter { it.isFile && it.extension == "img" }
            .sortedBy { artworkAccess.get(it.absolutePath) ?: it.lastModified() }.toList()
        for (file in images) {
            if (artworkBytes <= MAX_CACHE_BYTES * 9 / 10) break
            val length = file.length()
            if (file.delete()) {
                artworkBytes -= length
                artworkAccess.remove(file.absolutePath)
                artworkBounds.remove(file.absolutePath)
            }
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
        const val MAX_CATALOG_CHARS = 2 * 1024 * 1024
        const val MAX_IMAGE_BYTES = 3 * 1024 * 1024
        const val MAX_CACHE_BYTES = 120L * 1024 * 1024
    }
}

internal const val MOONLIGHT_PROBE_TTL_MS = 60_000L

/** A persisted catalog never carries a live online proof across a process restart. */
internal fun moonlightProbeFresh(expected: String?, actual: String, probeAt: Long, now: Long): Boolean =
    expected != null && expected == actual && probeAt >= 0L && now >= probeAt && now - probeAt < MOONLIGHT_PROBE_TTL_MS
