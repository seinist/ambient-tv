package com.ambient.tv.network

import android.content.Context
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper
import android.util.LruCache
import com.ambient.tv.backdropImageLoader
import com.ambient.tv.data.browse.BrowseScopes
import com.ambient.tv.data.local.AppPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal const val MEDIA_ARTWORK_IDENTITY_EXTRA = "ambient.media-artwork.identity"
internal const val MEDIA_ARTWORK_REVISION_EXTRA = "ambient.media-artwork.revision"
internal const val MEDIA_ARTWORK_HEADERS_EXTRA = "ambient.media-artwork.headers"

internal fun mediaArtworkRefreshChanged(previous: String?, current: String?, explicitlyAffected: Boolean): Boolean =
    explicitlyAffected || !current.isNullOrEmpty() && current != previous

/** Compose compares these extras; a newly allocated but otherwise equal request would not reload. */
internal fun coil3.request.ImageRequest.Builder.artworkRefreshRevision(sequence: Long) = apply {
    if (sequence != 0L) memoryCacheKeyExtra(MEDIA_ARTWORK_REVISION_EXTRA, "pending:$sequence")
    // The interceptor replaces this UI marker with the persisted image revision before caching.
}

internal fun mediaArtworkIdentityKey(baseKey: String): String {
    val bytes = MessageDigest.getInstance("SHA-256").digest(baseKey.toByteArray(Charsets.UTF_8))
    val hex = "0123456789abcdef"
    return buildString(64) { bytes.forEach { append(hex[(it.toInt() and 255) ushr 4]); append(hex[it.toInt() and 15]) } }
}

internal fun revisedMediaArtworkDiskKey(baseKey: String, revision: String): String =
    if (revision.isEmpty()) baseKey else "$baseKey:refresh:$revision"

/**
 * Only explicit edits create rows. Ordinary reads never write an access timestamp and the
 * in-process lookup cache is bounded. The disk table retains small invalidation tombstones:
 * deleting one could make an old same-URL image current again after a process restart.
 * Neither URLs nor credentials are stored here, only the digest of the account-scoped key.
 */
internal class MediaArtworkRevisionStore(
    context: Context,
    name: String = "media_artwork_revisions.db",
) : SQLiteOpenHelper(context.applicationContext, name, null, 1) {
    private val known = LruCache<String, String>(512)
    private val memoryOnly = java.util.concurrent.ConcurrentHashMap<String, String>()
    @Volatile private var hasRevisions: Boolean? = null

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE artwork_revision (image_key TEXT NOT NULL PRIMARY KEY,revision TEXT NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    // LruCache has its own short lock. Never acquire the SQLite transaction lock on a hot hit.
    fun cachedRevision(key: String): String? =
        memoryOnly[key] ?: known.get(key) ?: if (hasRevisions == false) "" else null

    fun isMemoryOnly(key: String): Boolean = memoryOnly.containsKey(key)

    @Synchronized fun revision(key: String): String {
        memoryOnly[key]?.let { return it }
        known.get(key)?.let { return it }
        val db = readableDatabase
        if (hasRevisions == null) hasRevisions = DatabaseUtils.longForQuery(db,
            "SELECT EXISTS(SELECT 1 FROM artwork_revision)", null) != 0L
        if (hasRevisions == false) return ""
        return db.rawQuery("SELECT revision FROM artwork_revision WHERE image_key=?", arrayOf(key)).use {
            if (it.moveToFirst()) it.getString(0) else ""
        }.also { known.put(key, it) }
    }

    /** Publish all affected images in one transaction before any old cache key is removed. */
    @Synchronized fun refresh(keys: Collection<String>): MediaArtworkRevisionUpdate {
        val unique = keys.distinct()
        if (unique.isEmpty()) return MediaArtworkRevisionUpdate(emptyMap(), persisted = true)
        val previous = unique.associateWith { key ->
            try { revision(key) } catch (_: SQLiteException) { cachedRevision(key).orEmpty() }
        }
        val next = UUID.randomUUID().toString()
        val persisted = try {
            val db = writableDatabase
            db.beginTransaction()
            try {
                db.compileStatement("INSERT OR REPLACE INTO artwork_revision(image_key,revision) VALUES(?,?)").use { statement ->
                    unique.forEach { key ->
                        statement.bindString(1, key)
                        statement.bindString(2, next)
                        statement.executeInsert()
                    }
                }
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
            unique.forEach(memoryOnly::remove)
            true
        } catch (error: SQLiteException) {
            // A targeted retry must still get new bytes when the persistent cache cannot write.
            // These transient revisions must never read/write the old persistent binary cache.
            unique.forEach { memoryOnly[it] = next }
            timber.log.Timber.w("Artwork revision write failed: %s", error.javaClass.simpleName)
            false
        }
        unique.forEach { known.put(it, next) }
        hasRevisions = true
        return MediaArtworkRevisionUpdate(previous, persisted)
    }
}

internal data class MediaArtworkRevisionUpdate(val previous: Map<String, String>, val persisted: Boolean)

internal data class MediaArtworkRefreshEvent(val sequence: Long = 0, val urls: Set<String> = emptySet())

/** One rare event per explicit edit; no observers run on an image/frame/progress timer. */
internal object MediaArtworkRefreshEvents {
    private val mutable = MutableStateFlow(MediaArtworkRefreshEvent())
    val changes = mutable.asStateFlow()
    fun publish(urls: Set<String>) { mutable.value = MediaArtworkRefreshEvent(mutable.value.sequence + 1, urls) }
}

internal data class MediaArtworkAddress(val baseKey: String, val imageKey: String, val scope: String, val providerScope: String?)

internal data class MediaArtworkCacheIdentity(val baseKey: String, val imageKey: String, val revision: String, val persistent: Boolean = true, val providerScope: String? = null) {
    val diskKey: String get() = revisedMediaArtworkDiskKey(baseKey, revision)
}

internal fun mediaArtworkMemoryKeyMatches(extras: Map<String, String>, identity: MediaArtworkCacheIdentity?): Boolean =
    identity != null && extras[MEDIA_ARTWORK_IDENTITY_EXTRA] == identity.imageKey &&
        extras[MEDIA_ARTWORK_REVISION_EXTRA] == identity.revision

/** Shared by both Coil loaders and metadata editing, with exactly the same provider/account scope. */
@Singleton
class MediaArtworkRefresh @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: AppPreferences,
    private val scopes: BrowseScopes,
    @com.ambient.tv.data.local.RegularPrefs private val regularPreferences: android.content.SharedPreferences,
    @com.ambient.tv.data.local.EncryptedPrefs private val credentialPreferences: android.content.SharedPreferences,
) {
    private val revisions = MediaArtworkRevisionStore(context)
    private val keyHashes = LruCache<String, String>(512)
    private val refreshLock = Mutex()
    private val mutableSources = MutableStateFlow(0L)
    internal val sources = mutableSources.asStateFlow()
    private val sourceListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (mediaArtworkSourcePreference(key)) mutableSources.update { it + 1L }
    }
    private val sourceGuard = Any()
    private var endpoints: Pair<Long, List<MediaImageEndpoint>>? = null
    private val scopeValues = mutableMapOf<String, Pair<Long, String>>()
    private data class AddressKey(val url: String, val headers: String?, val sourceVersion: Long)
    private data class AddressValue(val address: MediaArtworkAddress?)
    private val addresses = LruCache<AddressKey, AddressValue>(512)

    init {
        regularPreferences.registerOnSharedPreferenceChangeListener(sourceListener)
        credentialPreferences.registerOnSharedPreferenceChangeListener(sourceListener)
    }

    /** Pure settings/hash lookup: safe during composition; no SQLite open/read occurs here. */
    internal fun address(url: String, headers: Map<String, String> = emptyMap()): MediaArtworkAddress? {
        val headerKey = mediaArtworkHeadersKey(headers)
        while (true) {
            val version = sources.value
            val key = AddressKey(url, headerKey, version)
            addresses.get(key)?.let { return it.address }
            val endpoint = url.toHttpUrlOrNull() ?: return null
            // Explicit Authorization owns the request independently of any automatic account.
            val explicitAuthorization = explicitMediaImageCredential(endpoint, null, headers)
            val provider = if (explicitAuthorization) null else {
                val configured = synchronized(sourceGuard) {
                    endpoints?.takeIf { it.first == version }?.second ?: mediaImageEndpoints(preferences).also { endpoints = version to it }
                }
                mediaImageProvider(endpoint, configured)
            }
            val result = if (provider == null || explicitMediaImageCredential(endpoint, provider, headers)) {
                if (headerKey == null) null else {
                    // Explicitly authenticated external/TVH images keep their original URL semantics.
                    val base = "header-artwork-v1:" + hash("$headerKey:$url")
                    MediaArtworkAddress(base, hash(base), "headers:$headerKey", null)
                }
            } else {
                val providerScope = synchronized(sourceGuard) {
                    scopeValues[provider]?.takeIf { it.first == version }?.second ?: scopes.forProvider(provider).also { scopeValues[provider] = version to it }
                }
                val scope = if (headerKey == null) providerScope else "$providerScope:$headerKey"
                val base = normalizedMediaArtworkDiskKey(url, scope) ?: return null
                val resource = mediaArtworkResourceUrl(url) ?: return null
                MediaArtworkAddress(base, hash("media-artwork-resource-v1:$scope:$resource"), scope, providerScope)
            }
            if (sources.value != version) continue
            addresses.put(key, AddressValue(result))
            return result
        }
    }

    private fun hash(value: String): String = keyHashes.get(value) ?: mediaArtworkIdentityKey(value).also { keyHashes.put(value, it) }

    internal fun cachedIdentity(address: MediaArtworkAddress): MediaArtworkCacheIdentity? =
        revisions.cachedRevision(address.imageKey)?.let { address.withRevision(it) }

    private fun MediaArtworkAddress.withRevision(revision: String) = MediaArtworkCacheIdentity(
        baseKey, imageKey, revision, !revisions.isMemoryOnly(imageKey), providerScope,
    )

    internal suspend fun identity(url: String, headers: Map<String, String> = emptyMap()): MediaArtworkCacheIdentity? {
        val address = address(url, headers) ?: return null
        cachedIdentity(address)?.let { return it }
        return withContext(Dispatchers.IO) { address.withRevision(revisions.revision(address.imageKey)) }
    }

    /**
     * A late request may repopulate an old disk key; the committed revision makes it unreachable.
     * Only matching entries are evicted, from both RAM budgets and their shared image disk cache.
     * The crop-analysis cache naturally follows the new SuccessResult.diskCacheKey.
     */
    suspend fun refresh(urls: Collection<String?>, valid: () -> Boolean = { true }): Boolean = refreshLock.withLock {
        if (!valid()) return@withLock false
        var sourceVersion: Long
        var affected: List<Triple<String, String, String>>
        do {
            sourceVersion = sources.value
            affected = urls.filterNotNull().distinct().mapNotNull { url ->
                address(url)?.let { Triple(url, it.baseKey, it.imageKey) }
            }
            if (!valid()) return@withLock false
        } while (sources.value != sourceVersion)
        if (affected.isEmpty()) return@withLock true
        try {
            val persisted = withContext(Dispatchers.IO) {
                if (!valid()) return@withContext false
                val update = revisions.refresh(affected.map { it.third })
                val previous = update.previous
                val ids = previous.keys
                val loaders = listOf(coil3.SingletonImageLoader.get(context), backdropImageLoader(context)).distinct()
                loaders.forEach { loader ->
                    loader.memoryCache?.let { cache ->
                        cache.keys.filter { it.extras[MEDIA_ARTWORK_IDENTITY_EXTRA] in ids }.forEach(cache::remove)
                    }
                }
                // Both loaders share one DiskCache in production. A test/fallback loader may not.
                loaders.mapNotNull { it.diskCache }.distinct().forEach { cache ->
                    affected.forEach { (_, base, id) ->
                        runCatching { cache.remove(revisedMediaArtworkDiskKey(base, previous.getValue(id))) }
                    }
                }
                update.persisted
            }
            if (valid()) MediaArtworkRefreshEvents.publish(affected.mapNotNull { mediaArtworkResourceUrl(it.first) }.toSet())
            persisted
        } catch (error: SQLiteException) {
            timber.log.Timber.w(error, "Artwork refresh metadata unavailable")
            false
        }
    }
}
