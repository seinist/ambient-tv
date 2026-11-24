package com.ambient.tv.romm

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.ambient.tv.data.local.RegularPrefs
import com.ambient.tv.games.GameSystems
import com.ambient.tv.games.library.LibraryGame
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** The server's library as last read: playable platforms and their games. */
@Serializable
data class RommCatalog(
    val platforms: List<RommPlatform> = emptyList(),
    val roms: List<RommRom> = emptyList(),
    val fetchedAt: Long = 0L,
)

/** One RomM platform in the menu: its id, the name RomM gives it and the console that plays it. */
data class RommMenuPlatform(val id: Long, val title: String, val systemId: String, val count: Int) {
    val section: String get() = RommLibrary.platformSection(id)
}

data class RommLibraryState(
    val serverKey: String? = null,
    val catalog: RommCatalog? = null,
    val refreshing: Boolean = false,
    val error: Throwable? = null,
    /** Favorites or play history changed; the catalog itself may be unchanged. */
    val revision: Long = 0L,
)

/**
 * RomM's games as the shared game UI shows them ([LibraryGame]). The catalog is kept on this
 * device per server, so menus, Home and the library open without a request and stay usable while
 * the server is offline. Reading the server again is a user action (opening RomM, Refresh).
 */
@Singleton
class RommLibrary @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: RommClient,
    private val accounts: RommAccountStore,
    @RegularPrefs private val prefs: SharedPreferences,
) {
    private val mutable = MutableStateFlow(RommLibraryState())
    val state: StateFlow<RommLibraryState> = mutable.asStateFlow()
    private val refreshLock = Mutex()
    private val fileLock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val favoriteLock = Any()
    private val favoriteSync = Mutex()
    @Volatile private var favoriteJob: Job? = null

    /** The catalog of the current server, from memory or this device's copy. */
    fun catalog(): RommCatalog? {
        val account = accounts.account() ?: return null
        val current = mutable.value
        if (current.serverKey == account.serverKey && current.catalog != null) return current.catalog
        val stored = synchronized(fileLock) { readCatalog(account.serverKey) }
        mutable.update { it.copy(serverKey = account.serverKey, catalog = stored, error = null) }
        return stored
    }

    /**
     * Reads platforms and games from the server and keeps them on this device. A call that waited
     * for another refresh (menu and library opening together) takes that result instead of a second one.
     */
    suspend fun refresh(): RommCatalog {
        val requested = System.currentTimeMillis()
        return refreshLock.withLock { refreshLocked(requested) }
    }

    private suspend fun refreshLocked(requested: Long): RommCatalog {
        val account = accounts.account() ?: throw java.io.FileNotFoundException("RomM is not set up")
        mutable.value.takeIf { it.serverKey == account.serverKey }?.catalog
            ?.takeIf { it.fetchedAt >= requested }?.let { return it }
        mutable.update { it.copy(serverKey = account.serverKey, refreshing = true, error = null) }
        return try {
            val platforms = client.platforms(account)
                .filter { RommPlatforms.system(it) != null }
                .sortedBy { it.title.lowercase(Locale.ROOT) }
            val roms = platforms.filter { it.romCount != 0 }.flatMap { platform ->
                client.roms(account, platform.id).filter { it.playable }
            }
            val catalog = RommCatalog(platforms, roms, System.currentTimeMillis())
            withContext(Dispatchers.IO) { synchronized(fileLock) { writeCatalog(account.serverKey, catalog) } }
            mutable.update { it.copy(serverKey = account.serverKey, catalog = catalog, refreshing = false, error = null,
                revision = it.revision + 1) }
            scheduleFavoriteSync()
            catalog
        } catch (error: Throwable) {
            mutable.update { it.copy(refreshing = false, error = error) }
            throw error
        }
    }

    /** Platforms with at least one game, for the menu; empty while RomM is not set up. */
    fun menuPlatforms(): List<RommMenuPlatform> {
        val catalog = catalog() ?: return emptyList()
        val counts = catalog.roms.groupingBy { it.platformId }.eachCount()
        return catalog.platforms.mapNotNull { platform ->
            val system = RommPlatforms.system(platform) ?: return@mapNotNull null
            val count = counts[platform.id] ?: 0
            if (count == 0) null else RommMenuPlatform(platform.id, platform.title, system.id, count)
        }
    }

    fun rom(fileId: String): RommRom? {
        val (serverKey, romId) = RommKeys.parse(fileId) ?: return null
        if (accounts.account()?.serverKey != serverKey) return null
        return catalog()?.roms?.firstOrNull { it.id == romId }
    }

    fun platform(id: Long): RommPlatform? = catalog()?.platforms?.firstOrNull { it.id == id }

    /** Games of [section] ("all", "favorites", "recent" or a platform), filtered and sorted like the game library. */
    fun games(section: String = "all", search: String = "", sort: String = "name", descending: Boolean = false,
              titleStyle: String = "auto"): List<LibraryGame> {
        val account = accounts.account() ?: return emptyList()
        val catalog = catalog() ?: return emptyList()
        val platforms = catalog.platforms.associateBy { it.id }
        val favorites = favorites(account.serverKey)
        val played = played(account.serverKey)
        val platformFilter = platformId(section)
        val query = search.trim().lowercase(Locale.ROOT)
        val games = catalog.roms.asSequence()
            .filter { platformFilter == null || it.platformId == platformFilter }
            .mapNotNull { rom -> libraryGame(account, rom, platforms[rom.platformId], favorites, played, titleStyle) }
            .filter { section != "favorites" || it.favorite }
            .filter { section != "recent" || it.lastPlayed > 0L }
            .filter { query.isEmpty() || it.title.lowercase(Locale.ROOT).contains(query) || it.filename.lowercase(Locale.ROOT).contains(query) }
            .toList()
        if (section == "recent") return games.sortedByDescending { it.lastPlayed }.take(RECENT_LIMIT)
        val byName = compareBy<LibraryGame> { it.title.lowercase(Locale.ROOT) }.thenBy { it.fileId }
        val comparator = when (sort) {
            "system" -> compareBy<LibraryGame> { (it.metadataPlatform ?: it.systemId).lowercase(Locale.ROOT) }.then(byName)
            "recent" -> compareByDescending<LibraryGame> { it.lastPlayed }.then(byName)
            else -> byName
        }
        val sorted = games.sortedWith(comparator)
        return if (descending) sorted.reversed() else sorted
    }

    fun game(fileId: String): LibraryGame? {
        val account = accounts.account() ?: return null
        val rom = rom(fileId) ?: return null
        return libraryGame(account, rom, platform(rom.platformId), favorites(account.serverKey), played(account.serverKey))
    }

    /** Shows the change here at once; RomM's Favorites collection follows when the server answers. */
    fun setFavorite(fileId: String, value: Boolean) {
        val (serverKey, romId) = RommKeys.parse(fileId) ?: return
        synchronized(favoriteLock) {
            val current = favorites(serverKey)
            val next = if (value) current + romId else current - romId
            val pending = pendingFavorites(serverKey).filterNot { it.drop(1).toLongOrNull() == romId }.toSet() +
                ((if (value) "+" else "-") + romId)
            prefs.edit {
                putStringSet(favoritesKey(serverKey), next.mapTo(hashSetOf()) { it.toString() })
                putStringSet(pendingFavoritesKey(serverKey), pending)
            }
        }
        mutable.update { it.copy(revision = it.revision + 1) }
        scheduleFavoriteSync(FAVORITE_SYNC_DELAY_MS)
    }

    /** Brings this device's favorites and the user's RomM Favorites together, in the background. */
    fun scheduleFavoriteSync(delayMs: Long = 0L) {
        favoriteJob?.cancel()
        favoriteJob = scope.launch {
            delay(delayMs)
            try {
                syncFavorites()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // Kept here and sent with the next attempt (opening RomM, the next change).
                com.ambient.tv.diagnostics.DiagnosticLog.warn("romm", "favorites not synced: ${error.message}")
            }
        }
    }

    /**
     * RomM keeps each user's favorites in a collection marked as Favorites. Changes made on this
     * device are sent first; then the collection is what this device shows.
     */
    suspend fun syncFavorites() = favoriteSync.withLock {
        val account = accounts.account() ?: return@withLock
        val serverKey = account.serverKey
        val pending = synchronized(favoriteLock) {
            // Favorites marked before they were synced with RomM count as changes to send.
            if (!prefs.getBoolean(favoritesSyncedKey(serverKey), false)) {
                val earlier = favorites(serverKey).map { "+$it" }
                prefs.edit {
                    putStringSet(pendingFavoritesKey(serverKey), pendingFavorites(serverKey) + earlier)
                    putBoolean(favoritesSyncedKey(serverKey), true)
                }
            }
            pendingFavorites(serverKey)
        }
        val adds = pending.filter { it.startsWith("+") }.mapNotNullTo(linkedSetOf()) { it.drop(1).toLongOrNull() }
        val removes = pending.filter { it.startsWith("-") }.mapNotNullTo(linkedSetOf()) { it.drop(1).toLongOrNull() }
        val me = client.me(account.baseUrl, account.auth)
        val mine = client.collections(account).filter {
            if (me.id != 0L && it.userId != 0L) it.userId == me.id else it.ownerUsername.equals(account.username, ignoreCase = true)
        }
        // Servers without favorites flags keep the collection this device made under its name.
        var collection = mine.firstOrNull { it.isFavorite }
            ?: mine.firstOrNull { it.name == FAVORITES_NAME || it.name == FAVORITES_FALLBACK_NAME }
        if (collection == null && adds.isNotEmpty()) {
            collection = try {
                client.createFavorites(account, FAVORITES_NAME)
            } catch (error: RommHttpException) {
                // An ordinary collection of that name already exists.
                if (error is RommAuthException) throw error
                client.createFavorites(account, FAVORITES_FALLBACK_NAME)
            }
        }
        val current = collection
        val synced = if (current == null) null else {
            val add = adds.filterNot { it in current.romIds }
            val remove = removes.filter { it in current.romIds }
            if (add.isEmpty() && remove.isEmpty()) current else client.changeCollection(account, current, add, remove)
        }
        val remote = synced?.romIds.orEmpty()
        val changed = synchronized(favoriteLock) {
            // Changes made while this ran stay for the next round.
            val later = pendingFavorites(serverKey) - pending
            val next = remote.toMutableSet()
            later.forEach { op ->
                val id = op.drop(1).toLongOrNull() ?: return@forEach
                if (op.startsWith("+")) next += id else next -= id
            }
            val before = favorites(serverKey)
            prefs.edit {
                putStringSet(favoritesKey(serverKey), next.mapTo(hashSetOf()) { it.toString() })
                putStringSet(pendingFavoritesKey(serverKey), later)
            }
            before != next
        }
        if (changed) mutable.update { it.copy(revision = it.revision + 1) }
    }

    /** After the first frame of a RomM game, like the local library's Recent games. */
    fun recordPlayed(fileId: String, now: Long = System.currentTimeMillis()) {
        val (serverKey, romId) = RommKeys.parse(fileId) ?: return
        val entries = played(serverKey).toMutableMap()
        entries[romId] = now
        val kept = entries.entries.sortedByDescending { it.value }.take(PLAYED_LIMIT)
        prefs.edit { putString(playedKey(serverKey), kept.joinToString(",") { "${it.key}:${it.value}" }) }
        mutable.update { it.copy(revision = it.revision + 1) }
        // RomM's own "last played", so other clients and devices see it too; best effort.
        val account = accounts.account()?.takeIf { it.serverKey == serverKey } ?: return
        scope.launch {
            try {
                client.markPlayed(account, romId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                com.ambient.tv.diagnostics.DiagnosticLog.warn("romm", "last played not sent: ${error.message}")
            }
        }
    }

    fun coverUrl(account: RommAccount, rom: RommRom): String? =
        client.imageUrl(account.baseUrl, rom.pathCoverLarge) ?: client.imageUrl(account.baseUrl, rom.urlCover)
            ?: client.imageUrl(account.baseUrl, rom.pathCoverSmall)

    fun screenshotUrls(account: RommAccount, rom: RommRom): List<String> =
        rom.screenshots.mapNotNull { client.imageUrl(account.baseUrl, it) }

    /** Removes this device's copy of a server's catalog (after signing out of it). */
    fun forget(serverKey: String) {
        synchronized(fileLock) { File(root(serverKey), CATALOG).delete() }
        mutable.update { if (it.serverKey == serverKey) RommLibraryState(revision = it.revision + 1) else it }
    }

    private fun libraryGame(
        account: RommAccount,
        rom: RommRom,
        platform: RommPlatform?,
        favorites: Set<Long>,
        played: Map<Long, Long>,
        titleStyle: String = "auto",
    ): LibraryGame? {
        // A list saved by an earlier version may still hold entries without a file.
        if (!rom.playable) return null
        val system = RommPlatforms.system(rom) ?: platform?.let(RommPlatforms::system) ?: return null
        val cover = coverUrl(account, rom)
        val serverPlayed = rom.romUser?.lastPlayed?.let(::parseInstant) ?: 0L
        return LibraryGame(
            fileId = RommKeys.fileId(account.serverKey, rom.id),
            folderId = "romm:${account.serverKey}",
            sourceId = "romm:${account.serverKey}",
            path = rom.fsName,
            filename = rom.fsName,
            systemId = system.id,
            title = if (titleStyle == "filename") rom.fsNameNoExt.ifBlank { rom.displayTitle } else rom.displayTitle,
            internalTitle = null,
            size = rom.fsSizeBytes,
            modified = 0L,
            favorite = rom.id in favorites,
            lastPlayed = maxOf(played[rom.id] ?: 0L, serverPlayed),
            metadataTitle = rom.name,
            metadataYear = rom.releaseYear,
            coverPath = cover,
            backdropPath = screenshotUrls(account, rom).firstOrNull(),
            // Server covers are mostly portrait boxes (IGDB, SteamGridDB); the image keeps its own shape.
            coverWidth = if (cover != null) 3 else null,
            coverHeight = if (cover != null) 4 else null,
            metadataRegion = rom.regions.firstOrNull(),
            metadataPlatform = platform?.title ?: GameSystems.byId(system.id)?.name,
        )
    }

    private fun favorites(serverKey: String): Set<Long> =
        prefs.getStringSet(favoritesKey(serverKey), emptySet()).orEmpty().mapNotNullTo(hashSetOf()) { it.toLongOrNull() }

    private fun pendingFavorites(serverKey: String): Set<String> =
        prefs.getStringSet(pendingFavoritesKey(serverKey), emptySet()).orEmpty().toSet()

    private fun played(serverKey: String): Map<Long, Long> =
        prefs.getString(playedKey(serverKey), "").orEmpty().split(',').mapNotNull { entry ->
            val id = entry.substringBefore(':').toLongOrNull() ?: return@mapNotNull null
            val time = entry.substringAfter(':', "").toLongOrNull() ?: return@mapNotNull null
            id to time
        }.toMap()

    private fun root(serverKey: String) = File(context.filesDir, "romm/$serverKey")

    private fun readCatalog(serverKey: String): RommCatalog? = runCatching {
        val file = File(root(serverKey), CATALOG)
        if (!file.isFile) null else client.json.decodeFromString(RommCatalog.serializer(), file.readText())
    }.getOrNull()

    private fun writeCatalog(serverKey: String, catalog: RommCatalog) {
        val file = File(root(serverKey), CATALOG)
        file.parentFile?.mkdirs()
        val atomic = androidx.core.util.AtomicFile(file)
        val out = atomic.startWrite()
        try {
            out.write(client.json.encodeToString(RommCatalog.serializer(), catalog).toByteArray())
            atomic.finishWrite(out)
        } catch (error: Exception) {
            atomic.failWrite(out)
            throw error
        }
    }

    companion object {
        private const val CATALOG = "catalog.json"
        private const val RECENT_LIMIT = 100
        private const val PLAYED_LIMIT = 500

        fun platformSection(id: Long) = "p$id"

        fun platformId(section: String): Long? =
            section.takeIf { it.startsWith("p") }?.removePrefix("p")?.toLongOrNull()

        private fun favoritesKey(serverKey: String) = "romm_favorites_$serverKey"
        private fun pendingFavoritesKey(serverKey: String) = "romm_favorites_pending_$serverKey"
        private fun favoritesSyncedKey(serverKey: String) = "romm_favorites_synced_$serverKey"
        private const val FAVORITE_SYNC_DELAY_MS = 1_500L
        /** The name RomM's web interface gives the Favorites it creates. */
        private const val FAVORITES_NAME = "Favorites"
        private const val FAVORITES_FALLBACK_NAME = "Ambient TV Favorites"
        private fun playedKey(serverKey: String) = "romm_played_$serverKey"

        internal fun parseInstant(value: String): Long? = runCatching {
            java.time.OffsetDateTime.parse(value).toInstant().toEpochMilli()
        }.recoverCatching {
            // RomM 3 sent naive UTC times without an offset.
            java.time.LocalDateTime.parse(value).toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
        }.getOrNull()
    }
}
