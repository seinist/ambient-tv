package com.ambient.tv.games.metadata

import android.content.Context
import android.content.SharedPreferences
import android.graphics.BitmapFactory
import android.net.Uri
import com.ambient.tv.games.library.GameLibraryRepository
import com.ambient.tv.games.library.LibraryGame
import com.ambient.tv.data.local.RegularPrefs
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Background enrichment and explicit edits share one lifecycle. No method reads a ROM to hash it;
 * browsing consumes the SQLite projection and local files. Tickets reject late work after removal.
 */
@Singleton
class GameMetadataRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val library: GameLibraryRepository,
    private val providers: GameMetadataProviders,
    private val settings: GameMetadataSettings,
    private val localImport: GameMetadataLocalImporter,
    @RegularPrefs private val preferences: SharedPreferences,
) {
    private val gameLocks = Array(32) { Mutex() }
    private val previewLocks = Array(16) { Mutex() }
    private val downloadSlots = Semaphore(2)
    private val nameDatabaseLock = Mutex()
    private val jobsLock = Any()
    private val folderJobs = mutableMapOf<String, MutableMap<Job, Int>>()
    private val previews get() = File(context.cacheDir, "game_metadata_previews")
    private val incoming get() = File(context.cacheDir, "game_metadata_fetch")
    private val maintenanceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val reconcileRequests = Channel<Unit>(Channel.CONFLATED)
    private val coverRecoveryStarted = java.util.concurrent.atomic.AtomicBoolean()
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key in setOf("game_library_folders", "file_sources", "file_playback_scopes")) {
            reconcileRequests.trySend(Unit)
        }
    }

    init {
        preferences.registerOnSharedPreferenceChangeListener(preferenceListener)
        maintenanceScope.launch {
            reconcileRequests.receiveAsFlow().collect {
                try {
                    library.reconcile()
                    library.backfillCoverGeometry()
                    reconcilePreviews()
                }
                catch (error: java.util.concurrent.CancellationException) { throw error }
                catch (error: Exception) { timber.log.Timber.w(error, "Game metadata cleanup deferred") }
            }
        }
        reconcileRequests.trySend(Unit)
    }

    fun availableProviders(): List<String> = providers.availableProviders()

    /** Start only when Library or Home actually has game cards to display. */
    fun startCoverRecovery() = startCoverRecovery(SharedGameArtworkPriority)

    private data class MissingCover(val game: LibraryGame, val provider: String, val reference: String)

    internal fun startCoverRecovery(priority: GameArtworkPriority) {
        if (!coverRecoveryStarted.compareAndSet(false, true)) return
        maintenanceScope.launch {
            GameArtworkRecovery(
                priority = priority,
                changes = merge(library.revisions.map { Unit }, settings.changes.map { Unit }),
                pending = { fileId ->
                    if (!settings.options().enabled) null else {
                        val game = library.game(fileId)
                        if (game == null || game.coverPath?.let { File(it).isFile } == true) null else {
                            val stored = library.metadata(fileId)
                            stored?.artwork(GameArtworkKind.COVER)?.let { reference ->
                                MissingCover(game, stored.artworkProviders[GameArtworkKind.COVER.key]
                                    ?: stored.provider, reference)
                            }
                        }
                    }
                },
                identity = { target -> gameMetadataDigest("${target.provider}\u0000${target.reference}") },
                restore = { target ->
                    settings.options().enabled && ensureArtwork(target.game, GameArtworkKind.COVER)
                },
                onFailure = { timber.log.Timber.w("Stored game cover is temporarily unavailable") },
            ).run()
        }
    }

    internal suspend fun closeForTests() {
        preferences.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        reconcileRequests.close()
        maintenanceScope.coroutineContext.job.cancelAndJoin()
    }

    suspend fun detail(fileId: String): GameMetadata? = withContext(Dispatchers.IO) {
        val game = library.game(fileId) ?: return@withContext null
        val stored = library.metadata(fileId) ?: GameMetadata(title = game.title)
        game.customTitle?.takeIf(String::isNotBlank)?.let { title ->
            stored.copy(title = title, lockedFields = stored.lockedFields + "title")
        } ?: stored
    }

    suspend fun search(game: LibraryGame, query: String, provider: String,
                       artworkKind: GameArtworkKind? = null): List<GameMetadataCandidate> =
        withGame(game) { current ->
            val ticket = library.metadataTicket(current.fileId) ?: return@withGame emptyList()
            val options = settings.options()
            val request = gameMetadataQuery(current, library.metadata(current.fileId),
                settings.language(), options.region, ticket.crc32, query, provider)
            if (request.title.isBlank()) emptyList()
            else {
                val candidates = if (provider == GameMetadataProviderIds.LIBRETRO) {
                    val clean = gameMetadataSearchName(request.title)
                    val titles = library.searchDatTitles(current.systemId, request.title).ifEmpty {
                        if (clean != request.title) library.searchDatTitles(current.systemId, clean) else emptyList()
                    }
                    GameMetadataPlatforms.libretroCandidates(request, titles).let { choices ->
                        // Catalog search is local and usable even if the artwork server is offline.
                        // Only fall back to probing guessed image names when the catalog has no hit.
                        choices.filter { it.catalogMatch }.ifEmpty { choices }.let { found ->
                            if (artworkKind == null) found.distinctBy { it.metadata.canonicalTitle } else found
                        }
                    }
                } else providers.search(request, provider)
                withLibretroImage(candidates.take(30), current.fileId,
                    artworkKind?.let(::listOf) ?: listOf(GameArtworkKind.COVER, GameArtworkKind.BACKDROP, GameArtworkKind.TITLE),
                    keepCatalogNames = artworkKind == null)
            }
        }

    /**
     * A downloaded catalog confirms names independently of artwork. Guessed Libretro image
     * paths still require a real response, and artwork-only choices always require an image.
     */
    private suspend fun withLibretroImage(
        candidates: List<GameMetadataCandidate>, fileId: String, kinds: List<GameArtworkKind>,
        keepCatalogNames: Boolean = false,
    ): List<GameMetadataCandidate> = coroutineScope {
        candidates.map { candidate -> async {
            if (candidate.metadata.provider != GameMetadataProviderIds.LIBRETRO) return@async candidate
            if (keepCatalogNames && candidate.catalogMatch) return@async candidate
            val found = kinds.firstOrNull { kind ->
                try { previewArtwork(candidate, kind, fileId) != null }
                catch (failure: GameMetadataProviderException) { throw failure }
                catch (_: IOException) { false }
            } ?: return@async null
            val reference = candidate.metadata.artwork(found)
            if (found == kinds.first() || reference == null) candidate
            else candidate.copy(metadata = candidate.metadata.withArtwork(kinds.first(), reference,
                candidate.metadata.artworkProviders[found.key] ?: candidate.metadata.provider,
                candidate.metadata.artworkSources[found.key] ?: candidate.metadata.sourceUrl))
        } }.awaitAll().filterNotNull()
    }

    suspend fun applyMatch(game: LibraryGame, candidate: GameMetadataCandidate): Boolean = withGame(game) { current ->
        applyCandidate(current, candidate, manual = true, force = true)
    }

    suspend fun artworkChoices(game: LibraryGame, kind: GameArtworkKind): List<GameMetadataCandidate> =
        withGame(game) { current ->
            val stored = library.metadata(current.fileId) ?: return@withGame emptyList()
            val ticket = library.metadataTicket(current.fileId) ?: return@withGame emptyList()
            if (stored.provider !in providers.availableProviders()) return@withGame emptyList()
            val query = gameMetadataQuery(current, stored, settings.language(), settings.options().region,
                ticket.crc32, provider = stored.provider)
            withLibretroImage(providers.artworkChoices(query, stored.provider, kind).take(30), current.fileId, listOf(kind))
        }

    suspend fun save(game: LibraryGame, metadata: GameMetadata, lockedFields: Set<String>): Boolean =
        withGame(game) { current ->
            val ticket = library.metadataTicket(current.fileId) ?: return@withGame false
            val record = metadata.copy(lockedFields = lockedFields, updatedAt = System.currentTimeMillis()).bounded()
            val accepted = library.saveMetadata(ticket, record, record.lockedFields, manual = true)
            if (accepted) library.setCustomTitle(current.fileId,
                if ("title" in record.lockedFields) record.title else "")
            accepted
        }

    suspend fun setLocalArtwork(game: LibraryGame, kind: GameArtworkKind, uri: Uri): Boolean =
        withGame(game) { current ->
            require(uri.scheme == "content") { "Select an image with the document picker" }
            val ticket = library.metadataTicket(current.fileId) ?: return@withGame false
            val staged = context.contentResolver.openInputStream(uri)?.use(library.artworkFiles::stage)
                ?: throw IOException("The selected image cannot be read")
            try {
                currentCoroutineContext().ensureActive()
                if (!library.setArtwork(ticket, kind, staged, pinned = true)) return@withGame false
                val previous = library.metadata(current.fileId) ?: GameMetadata(title = current.title)
                val updated = previous.withArtwork(kind, null, null, null).copy(
                    lockedFields = previous.lockedFields + gameArtworkLock(kind),
                    updatedAt = System.currentTimeMillis())
                library.saveMetadata(ticket, updated, updated.lockedFields, manual = true)
            } finally { library.artworkFiles.discard(staged) }
        }

    /** Choosing an image does not replace the text match or the other image. */
    suspend fun setArtwork(game: LibraryGame, kind: GameArtworkKind, candidate: GameMetadataCandidate): Boolean =
        withGame(game) { current ->
            val reference = candidate.metadata.artwork(kind) ?: return@withGame false
            val ticket = library.metadataTicket(current.fileId) ?: return@withGame false
            val provider = candidate.metadata.artworkProviders[kind.key] ?: candidate.metadata.provider
            val staged = downloadStage(provider, reference) ?: return@withGame false
            try {
                currentCoroutineContext().ensureActive()
                if (!library.setArtwork(ticket, kind, staged, pinned = true)) return@withGame false
                val previous = library.metadata(current.fileId) ?: GameMetadata(title = current.title)
                val record = previous.withArtwork(kind, reference, provider,
                    candidate.metadata.artworkSources[kind.key] ?: candidate.metadata.sourceUrl).copy(
                    lockedFields = previous.lockedFields + gameArtworkLock(kind), updatedAt = System.currentTimeMillis())
                library.saveMetadata(ticket, record, record.lockedFields, manual = true)
            } finally { library.artworkFiles.discard(staged) }
        }

    /** Used only by visible artwork choices. URLs with provider authentication never reach Coil. */
    suspend fun previewArtwork(candidate: GameMetadataCandidate, kind: GameArtworkKind, fileId: String? = null): String? =
        withContext(Dispatchers.IO) {
            val reference = candidate.metadata.artwork(kind) ?: return@withContext null
            val provider = candidate.metadata.artworkProviders[kind.key] ?: candidate.metadata.provider
            val ticket = fileId?.let { library.metadataTicket(it) }
            if (fileId != null && ticket == null) return@withContext null
            val owner = ticket?.folderId ?: "unassigned"
            val job = currentCoroutineContext().job
            register(owner, job)
            val key = gameMetadataDigest("$provider\u0000$reference")
            try { previewLocks[(key.hashCode() and Int.MAX_VALUE) % previewLocks.size].withLock {
                val directory = File(previews, gameMetadataDigest(owner))
                val output = File(directory, "$key.image")
                if (ticket != null && library.metadataTicket(ticket.fileId) != ticket) return@withLock null
                if (output.isFile && System.currentTimeMillis() - output.lastModified() < PREVIEW_TTL) {
                    return@withLock output.absolutePath
                }
                directory.mkdirs()
                val temporary = File.createTempFile("preview-", ".tmp", directory)
                try {
                    val found = downloadSlots.withPermit { providers.downloadArtwork(provider, reference, temporary) }
                    currentCoroutineContext().ensureActive()
                    if (!found) return@withLock null
                    validateImage(temporary)
                    if (ticket != null && library.metadataTicket(ticket.fileId) != ticket) return@withLock null
                    if (!temporary.renameTo(output)) throw IOException("The image preview cannot be stored")
                    trimPreviews(output)
                    output.absolutePath
                } finally { temporary.delete() }
            } } finally { unregister(owner, job) }
        }

    /** A detail page can request its background without another metadata lookup or ROM read. */
    suspend fun ensureArtwork(game: LibraryGame, kind: GameArtworkKind): Boolean = withGame(game) { current ->
        val metadata = library.metadata(current.fileId) ?: return@withGame false
        ensureStoredArtwork(current, metadata, kind, force = false)
    }

    suspend fun refresh(game: LibraryGame, force: Boolean = false): Boolean = withGame(game) { current ->
        val options = settings.options()
        if (!options.enabled) return@withGame false
        val ticket = library.metadataTicket(current.fileId) ?: return@withGame false
        var old = library.metadata(current.fileId)
        // DAT names are useful even when there is no cover, the provider has no artwork
        // namespace for this platform, or its server fails. Publish the local title first.
        val catalog = localCatalogMatches(current, old)
        catalogDisplayMetadata(current, old, catalog)?.let { named ->
            currentCoroutineContext().ensureActive()
            if (!library.saveMetadata(ticket, named)) return@withGame false
            old = library.metadata(current.fileId)
        }
        val platformQuery = GameMetadataQuery(current.systemId, current.title, current.filename)
        val provider = old?.takeIf { it.matchLocked }?.provider ?: GameMetadataPlatforms.automaticProvider(
            platformQuery, options.provider, providers.availableProviders())
        // A manually identified game must not silently switch sources when its key is removed.
        if (old?.matchLocked == true && provider !in providers.availableProviders())
            throw GameMetadataProviderException(GameMetadataFailure.CREDENTIALS)
        val known = old?.takeIf { it.provider == provider }
        val query = gameMetadataQuery(current, known, settings.language(), options.region,
            ticket.crc32, provider = provider)
        val fingerprint = gameMetadataLookupFingerprint(provider, gameMetadataFingerprint(current, query))
        // A manually chosen catalog name may still lack a proven thumbnail namespace.
        // Neither an earlier miss nor the recent text edit can postpone resolving it.
        val unresolvedLibretro = known?.provider == GameMetadataProviderIds.LIBRETRO &&
            (known.providerGameId.isBlank() || "cover" !in known.lockedFields &&
                current.coverPath?.let { File(it).isFile } != true)
        if (!force && !unresolvedLibretro) {
            when (library.lookupStatus(current.fileId, provider, fingerprint)) {
                "found" -> {
                    if (known != null && gameMetadataMatchesQueryIdentity(known, query)) {
                        ensureStoredArtwork(current, known, GameArtworkKind.COVER, force = false)
                        return@withGame true
                    }
                }
                "not_found", "ambiguous" -> return@withGame false
            }
            if (known != null && known.updatedAt > System.currentTimeMillis() - FOUND_TTL &&
                gameMetadataMatchesQueryIdentity(known, query)) {
                ensureStoredArtwork(current, known, GameArtworkKind.COVER, force = false)
                return@withGame true
            }
        }
        val candidates = try {
            if (provider == GameMetadataProviderIds.LIBRETRO) {
                val lookup = if (catalog.isNotEmpty() && known?.matchLocked != true)
                    query.copy(providerGameId = null) else query
                GameMetadataPlatforms.libretroCandidates(lookup, catalog)
            } else providers.search(query, provider)
        }
        catch (error: GameMetadataProviderException) {
            if (error.kind != GameMetadataFailure.UNSUPPORTED) throw error
            library.saveLookupStatus(ticket, provider, fingerprint, "not_found", System.currentTimeMillis() + MISSING_TTL)
            return@withGame false
        }
        currentCoroutineContext().ensureActive()
        val confirmed = candidates.filter { it.exactMatch }
        // Libretro's static URL is validated by an actual image response. It is not an API match.
        val selected = if (provider == GameMetadataProviderIds.LIBRETRO)
            automaticLibretroCandidates(candidates, current.verifiedTitle, known?.matchLocked == true)
            else confirmed.singleOrNull()?.let(::listOf).orEmpty()
        var rejectedArtwork: IOException? = null
        for (candidate in selected) {
            val applied = try { applyCandidate(current, candidate, manual = false, force = force) }
            catch (error: IOException) {
                if (!isRejectedArtworkResponse(error)) throw error
                if (rejectedArtwork == null) rejectedArtwork = error
                false
            }
            if (applied) {
                library.saveLookupStatus(ticket, provider, fingerprint, "found", System.currentTimeMillis() + FOUND_TTL)
                return@withGame true
            }
        }
        // A rejected image is not a confirmed missing game. Other compatible thumbnail
        // namespaces may work, but an entirely invalid response must remain retryable.
        rejectedArtwork?.let { throw it }
        val status = if (candidates.isNotEmpty() && provider != GameMetadataProviderIds.LIBRETRO) "ambiguous" else "not_found"
        library.saveLookupStatus(ticket, provider, fingerprint, status, System.currentTimeMillis() + MISSING_TTL)
        false
    }

    /** Exact system-scoped name matches; this never opens a ROM or contacts the network. */
    private fun localCatalogMatches(game: LibraryGame, previous: GameMetadata?): List<String> {
        game.verifiedTitle?.takeIf(String::isNotBlank)?.let { return listOf(it) }
        if (previous?.matchLocked == true) return emptyList()
        val filename = gameCatalogFilename(game.systemId, game.filename)
        val hints = listOfNotNull(filename.takeIf(String::isNotBlank),
            game.internalTitle?.takeIf(String::isNotBlank)).distinct()
        return hints.firstNotNullOfOrNull { hint ->
            val queries = listOf(hint, com.ambient.tv.games.library.gameDisplayTitle(hint),
                gameMetadataCatalogBase(hint), gameMetadataSearchName(hint)).distinct()
            val titles = queries.flatMap { library.searchDatTitles(game.systemId, it) }.distinct()
            matchingGameCatalogTitles(hint, titles).takeIf { it.isNotEmpty() }
        }.orEmpty()
    }

    data class NameDatabaseResult(val systemIds: Set<String>, val names: Int) {
        val systems: Int get() = systemIds.size
    }

    /**
     * Like RetroArch's "Update Databases", on request only: loads libretro-database's names for
     * [only] these systems, or for all systems in the library. A rescan then names games by
     * checksum ([GameLibraryRepository.scan]).
     */
    suspend fun loadNameDatabases(
        only: Collection<String>? = null, onlyMissing: Boolean = false,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): NameDatabaseResult =
        nameDatabaseLock.withLock { withContext(Dispatchers.IO) {
            val existing = if (onlyMissing) library.datCatalogSizes() else emptyMap()
            val systems = (only ?: library.indexedSystems())
                .filter { GameNameDatabases.files(it).isNotEmpty() && (!onlyMissing || (existing[it] ?: 0) == 0) }
                .distinct().sorted()
            val staging = File(context.cacheDir, "game-name-databases-${java.util.UUID.randomUUID()}")
            if (!staging.mkdirs()) throw IOException("The game name database cannot be staged")
            try {
                val loaded = linkedSetOf<String>()
                var names = 0
                systems.forEachIndexed { index, system ->
                    onProgress(index, systems.size)
                    val files = GameNameDatabases.files(system).mapIndexedNotNull { part, path ->
                        File(staging, "$system-$part.dat").takeIf { providers.downloadNameDatabase(path, it) }
                    }
                    currentCoroutineContext().ensureActive()
                    if (files.isNotEmpty()) {
                        names += library.importDats(system, files)
                        loaded += system
                    }
                    files.forEach(File::delete)
                }
                onProgress(systems.size, systems.size)
                NameDatabaseResult(loaded, names)
            } finally { staging.deleteRecursively() }
        } }

    /** Prepare catalogs before the first checksum scan; isolate unavailable upstream systems.
     * Automatic metadata being disabled remains a user choice, including during folder scans. */
    suspend fun prepareNameCatalogs(systems: Collection<String>, automatic: Boolean): Set<String> =
        withContext(Dispatchers.IO) {
            val options = settings.options()
            if (!options.enabled || automatic && !options.automatic) return@withContext emptySet()
            val loaded = linkedSetOf<String>()
            for (system in systems.distinct().filter { GameNameDatabases.files(it).isNotEmpty() }) {
                currentCoroutineContext().ensureActive()
                try { loaded += loadNameDatabases(listOf(system), onlyMissing = true).systemIds }
                catch (error: IOException) {
                    timber.log.Timber.w(error, "Game name catalog unavailable for %s", system)
                } catch (error: IllegalArgumentException) {
                    timber.log.Timber.w(error, "Invalid game name catalog for %s", system)
                }
            }
            loaded
        }

    /** Explicit folder work feeds this bounded queue; visible cards only reorder an active batch. */
    suspend fun enrichFolder(folderId: String, automatic: Boolean = false,
                             prioritizedFileIds: () -> List<String> = SharedGameArtworkPriority::prioritizedFileIds,
                             systemId: String? = null,
                             onProgress: (Int, Int) -> Unit = { _, _ -> }): Unit =
        withContext(Dispatchers.IO) {
            coroutineScope {
                val job = currentCoroutineContext().job
                register(folderId, job)
                library.beginMetadataBatch()
                try {
                    if (library.folders().none { it.id == folderId }) return@coroutineScope
                    val options = settings.options()
                    if (!options.enabled) {
                        if (!automatic) throw IOException(context.getString(
                            com.ambient.tv.R.string.game_metadata_settings_off_hint))
                        return@coroutineScope
                    }
                    if (automatic && !options.automatic) return@coroutineScope
                    val systems = (systemId?.let(::listOf) ?: library.indexedSystems())
                        .filter { folderId in library.foldersWithSystem(it) }
                    prepareNameCatalogs(systems, automatic)
                    val total = library.folderGameCount(folderId, systemId)
                    var completed = 0
                    var failed = 0
                    var firstFailure: IOException? = null
                    val queue = GameMetadataBatchQueue(
                        fileId = LibraryGame::fileId,
                        prioritizedFileIds = prioritizedFileIds,
                        page = { after, limit -> library.folderGames(folderId, after, limit, systemId) },
                        find = { id, after -> library.folderGame(folderId, id, after, systemId) },
                    )
                    var lastPulse = System.nanoTime()
                    onProgress(0, total)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        if (library.folders().none { it.id == folderId }) break
                        val game = queue.next() ?: break
                        currentCoroutineContext().ensureActive()
                        try {
                            refresh(game)
                        } catch (error: IOException) {
                            // A bad response for one ROM must not strand the rest of a NAS
                            // library. Connectivity, credentials, quotas and storage errors
                            // still stop immediately; a missing match is not an error.
                            if (error !is GameArtworkValidationException &&
                                (error !is GameMetadataProviderException || error.kind != GameMetadataFailure.INVALID_RESPONSE))
                                throw error
                            failed++
                            if (firstFailure == null) firstFailure = error
                        }
                        completed++
                        if (System.nanoTime() - lastPulse >= BATCH_PULSE_NS) {
                            library.flushMetadataBatch()
                            onProgress(completed, total)
                            lastPulse = System.nanoTime()
                        }
                    }
                    onProgress(completed, total)
                    firstFailure?.let { throw GameMetadataBatchException(failed, completed, it) }
                } finally { library.endMetadataBatch(); unregister(folderId, job) }
            }
        }

    /** Called only after an explicit ROM-folder scan; local sidecars have precedence over downloads. */
    suspend fun importLocalFolder(folderId: String, systemId: String? = null) = withContext(Dispatchers.IO) {
        val job = currentCoroutineContext().job
        register(folderId, job)
        library.beginMetadataBatch()
        var lastPulse = System.nanoTime()
        try { localImport.importFolder(folderId, systemId = systemId) {
            if (System.nanoTime() - lastPulse >= BATCH_PULSE_NS) {
                library.flushMetadataBatch()
                lastPulse = System.nanoTime()
            }
        } }
        finally { library.endMetadataBatch(); unregister(folderId, job) }
    }

    suspend fun cancelFolder(folderId: String) {
        val current = currentCoroutineContext().job
        val running = synchronized(jobsLock) { folderJobs[folderId]?.keys?.filter { it !== current }.orEmpty() }
        running.forEach { it.cancel() }
        running.forEach { it.join() }
        withContext(Dispatchers.IO) {
            val directory = File(previews, gameMetadataDigest(folderId))
            directory.listFiles()?.forEach { it.delete() }
            directory.delete()
        }
    }

    private suspend fun applyCandidate(
        game: LibraryGame, candidate: GameMetadataCandidate, manual: Boolean, force: Boolean,
    ): Boolean {
        val ticket = library.metadataTicket(game.fileId) ?: return false
        val previous = library.metadata(game.fileId)
        if (!manual && previous?.matchLocked == true && !sameGameMetadataMatch(previous, candidate.metadata))
            return false
        var incomingMetadata = candidate.metadata.copy(
            matchLocked = manual || previous?.matchLocked == true,
            updatedAt = System.currentTimeMillis())
        var confirmedImage: GameArtworkFile? = null
        val coverLocked = previous?.lockedFields?.contains("cover") == true
        val catalogName = incomingMetadata.provider == GameMetadataProviderIds.LIBRETRO && candidate.catalogMatch
        if (incomingMetadata.provider == GameMetadataProviderIds.LIBRETRO) {
            var rejectedArtwork: IOException? = null
            // A combined system's DAT does not identify its thumbnail subfolder. Manual
            // catalog results show each title once and try the compatible namespaces here.
            val alternatives = if (catalogName && manual) GameMetadataPlatforms.libretroCandidates(
                GameMetadataQuery(game.systemId, incomingMetadata.canonicalTitle, game.filename,
                    canonicalTitle = incomingMetadata.canonicalTitle,
                    language = incomingMetadata.language, region = incomingMetadata.region),
                listOf(incomingMetadata.canonicalTitle),
            ).map { it.metadata }.filter { it.canonicalTitle == incomingMetadata.canonicalTitle }
                else listOf(incomingMetadata)
            probe@ for (alternative in alternatives) {
                for (kind in listOf(GameArtworkKind.COVER, GameArtworkKind.BACKDROP, GameArtworkKind.TITLE)) {
                    val reference = alternative.artwork(kind) ?: continue
                    try { confirmedImage = downloadStage(alternative.provider, reference) }
                    catch (error: IOException) {
                        // A corrupt/oversize cover must not block a valid screenshot or
                        // title screen. Transport, account and quota errors still stop IO.
                        if (isRejectedArtworkResponse(error)) {
                            if (rejectedArtwork == null) rejectedArtwork = error
                            continue
                        }
                        if (!catalogName || !manual) throw error
                        timber.log.Timber.w("Libretro artwork is unavailable for a catalog match")
                        break@probe
                    }
                    if (confirmedImage != null) {
                        incomingMetadata = alternative.copy(matchLocked = incomingMetadata.matchLocked,
                            updatedAt = incomingMetadata.updatedAt)
                        if (kind != GameArtworkKind.COVER && !coverLocked) incomingMetadata = incomingMetadata.withArtwork(
                            GameArtworkKind.COVER, reference, incomingMetadata.provider, incomingMetadata.sourceUrl)
                        break@probe
                    }
                }
            }
            // Automatic enrichment keeps trying the other platform namespaces. Verified
            // DAT titles already display in the library independently of metadata/artwork.
            if (confirmedImage == null) {
                if (!catalogName || !manual) {
                    rejectedArtwork?.let { throw it }
                    return false
                }
                if (alternatives.map { it.providerGameId.substringBefore('|') }.distinct().size > 1)
                    incomingMetadata = incomingMetadata.copy(providerGameId = "", coverUrl = null,
                        backdropUrl = null, titleScreenUrl = null)
            }
        }
        try {
            currentCoroutineContext().ensureActive()
            val metadata = preserveGameMetadataLocks(previous, incomingMetadata)
            if (!library.saveMetadata(ticket, metadata, metadata.lockedFields, manual)) return false
            if (confirmedImage != null && !coverLocked) {
                if (!library.setArtwork(ticket, GameArtworkKind.COVER, confirmedImage, pinned = false)) return false
            } else if (!catalogName) ensureOptionalArtwork(game, metadata, GameArtworkKind.COVER, force)
            if (manual && (!catalogName || confirmedImage != null))
                ensureOptionalArtwork(game, metadata, GameArtworkKind.BACKDROP, force)
            return true
        } finally { confirmedImage?.let(library.artworkFiles::discard) }
    }

    private fun isRejectedArtworkResponse(error: IOException): Boolean =
        error is GameArtworkValidationException ||
            error is GameMetadataProviderException && error.kind == GameMetadataFailure.INVALID_RESPONSE

    /** Applying a text match has succeeded even if an optional image cannot be fetched. */
    private suspend fun ensureOptionalArtwork(
        game: LibraryGame, metadata: GameMetadata, kind: GameArtworkKind, force: Boolean,
    ) {
        try { ensureStoredArtwork(game, metadata, kind, force) }
        catch (_: IOException) { timber.log.Timber.w("Optional game artwork is unavailable") }
    }

    private suspend fun ensureStoredArtwork(
        game: LibraryGame, metadata: GameMetadata, kind: GameArtworkKind, force: Boolean,
    ): Boolean {
        val present = library.artworkPath(game.fileId, kind)?.let(::File)?.isFile == true
        if (present && (!force || gameArtworkLock(kind) in metadata.lockedFields)) return true
        val reference = metadata.artwork(kind) ?: return present
        val provider = metadata.artworkProviders[kind.key] ?: metadata.provider
        val ticket = library.metadataTicket(game.fileId) ?: return false
        val fingerprint = gameMetadataLookupFingerprint(provider,
            gameMetadataDigest("image:${kind.key}:$provider:$reference"))
        if (!force && library.lookupStatus(game.fileId, provider, fingerprint) == "not_found") return false
        val staged = downloadStage(provider, reference) ?: run {
            currentCoroutineContext().ensureActive()
            library.saveLookupStatus(ticket, provider, fingerprint, "not_found", System.currentTimeMillis() + MISSING_TTL)
            return false
        }
        return try {
            currentCoroutineContext().ensureActive()
            library.setArtwork(ticket, kind, staged, pinned = gameArtworkLock(kind) in metadata.lockedFields)
        } finally { library.artworkFiles.discard(staged) }
    }

    private suspend fun downloadStage(provider: String, reference: String): GameArtworkFile? {
        incoming.mkdirs()
        val temporary = File.createTempFile("art-", ".tmp", incoming)
        return try {
            if (!downloadSlots.withPermit { providers.downloadArtwork(provider, reference, temporary) }) return null
            currentCoroutineContext().ensureActive()
            temporary.inputStream().use(library.artworkFiles::stage)
        } finally { temporary.delete() }
    }

    private suspend fun <T> withGame(game: LibraryGame, action: suspend (LibraryGame) -> T): T =
        withContext(Dispatchers.IO) {
            val job = currentCoroutineContext().job
            register(game.folderId, job)
            try {
                gameLocks[(game.fileId.hashCode() and Int.MAX_VALUE) % gameLocks.size].withLock {
                    currentCoroutineContext().ensureActive()
                    val current = library.game(game.fileId)
                    if (current != null && (current.systemId != game.systemId || current.sourceId != game.sourceId ||
                            current.path != game.path || current.size != game.size || current.modified != game.modified)) {
                        throw IOException("The game changed; open its details again")
                    }
                    // The captured game is used only to return a stale-ticket result, never to recreate it.
                    action(current ?: game)
                }
            } finally { unregister(game.folderId, job) }
        }

    private fun register(folderId: String, job: Job) = synchronized(jobsLock) {
        val jobs = folderJobs.getOrPut(folderId) { mutableMapOf() }
        jobs[job] = (jobs[job] ?: 0) + 1
    }

    private fun unregister(folderId: String, job: Job) = synchronized(jobsLock) {
        val jobs = folderJobs[folderId] ?: return@synchronized
        val count = (jobs[job] ?: 1) - 1
        if (count <= 0) jobs.remove(job) else jobs[job] = count
        if (jobs.isEmpty()) folderJobs.remove(folderId)
    }

    private fun validateImage(file: File) {
        if (file.length() !in 1..GameArtworkFiles.MAX_IMAGE_BYTES) throw IOException("Invalid image size")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth !in 1..8192 || bounds.outHeight !in 1..8192 ||
            bounds.outWidth.toLong() * bounds.outHeight > GameArtworkFiles.MAX_IMAGE_PIXELS)
            throw IOException("Invalid image dimensions")
    }

    /** Folder/source/backup reconciliation also removes short-lived image chooser previews. */
    private fun reconcilePreviews() {
        if (!previews.isDirectory) return
        val folders = library.folders().associateBy { gameMetadataDigest(it.id) }
        for (directory in previews.listFiles().orEmpty()) {
            val folder = folders[directory.name]
            if ((folder == null || library.folderGameCount(folder.id) == 0) &&
                directory.name.matches(Regex("[a-f0-9]{64}")) &&
                directory.canonicalFile.parentFile == previews.canonicalFile) {
                directory.listFiles()?.forEach { it.delete() }
                directory.delete()
            }
        }
    }

    private fun trimPreviews(keep: File) {
        val entries = previews.listFiles()?.filter { it.isDirectory && it.name.matches(Regex("[a-f0-9]{64}")) }
            ?.flatMap { it.listFiles().orEmpty().asList() }?.filter { it.isFile && it.extension == "image" }
            ?.sortedByDescending(File::lastModified).orEmpty()
        var used = 0L
        var retained = 0
        for (entry in entries) {
            if (entry != keep && (retained >= 48 || used + entry.length() > 32L * 1024 * 1024 ||
                    System.currentTimeMillis() - entry.lastModified() > PREVIEW_TTL)) entry.delete()
            else { used += entry.length(); retained++ }
        }
    }

    companion object {
        private const val FOUND_TTL = 30L * 24 * 60 * 60 * 1000
        private const val MISSING_TTL = 7L * 24 * 60 * 60 * 1000
        private const val PREVIEW_TTL = 24L * 60 * 60 * 1000
        private const val BATCH_PULSE_NS = 250_000_000L
    }
}
