package com.ambient.tv.ui.games

import android.content.Context
import android.content.Intent
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ambient.tv.R
import com.ambient.tv.data.files.FileAccess
import com.ambient.tv.data.files.FileEntry
import com.ambient.tv.data.files.FileSource
import com.ambient.tv.data.files.FileStore
import com.ambient.tv.games.GameSystems
import com.ambient.tv.games.library.GameLibraryFolder
import com.ambient.tv.games.library.GameLibraryQuery
import com.ambient.tv.games.library.GameLibraryRepository
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.games.library.LibraryGame
import com.ambient.tv.games.metadata.GameMetadataRepository
import com.ambient.tv.games.metadata.GameMetadataBatchException
import com.ambient.tv.ui.files.FilesViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.cancelAndJoin

enum class GameLibraryScanPhase { FILES, NAME_DATABASES, MATCHING_NAMES, METADATA }

data class GameLibraryUiState(
    val games: List<LibraryGame> = emptyList(),
    val systems: List<String> = emptyList(),
    val folders: List<GameLibraryFolder> = emptyList(),
    val sources: List<FileSource> = emptyList(),
    val section: String = "all",
    val query: String = "",
    val view: String = "grid",
    val gridColumns: Int = 8,
    val listRows: Int = 7,
    val titleStyle: String = "auto",
    val sortKey: String = "name",
    val sortDescending: Boolean = false,
    val busy: Boolean = false,
    val scanning: Boolean = false,
    val scanPhase: GameLibraryScanPhase = GameLibraryScanPhase.FILES,
    val scanProcessed: Int = 0,
    val scanDiscovered: Int = 0,
    val scanFolderName: String = "",
    val activeScanFolderId: String? = null,
    val enriching: Boolean = false,
    val removingFolders: Set<String> = emptySet(),
    val ready: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    val selectedSource: FileSource? = null,
    val browsedPaths: List<Pair<String, String>> = emptyList(),
    val directoryEntries: List<FileEntry> = emptyList(),
    val browsing: Boolean = false,
    val pendingWakeGame: LibraryGame? = null,
    val savingNetworkSource: Boolean = false,
    val testingNetworkSource: Boolean = false,
    val networkSaveRevision: Long = 0,
    /** Names in each system's catalog, for the name database page. */
    val nameCatalogs: Map<String, Int> = emptyMap(),
)

@HiltViewModel
class GameLibraryViewModel @Inject constructor(
    private val library: GameLibraryRepository,
    private val files: FileStore,
    private val access: FileAccess,
    private val wakeMachines: com.ambient.tv.wol.WakeMachines,
    private val preferences: AppPreferences,
    @ApplicationContext private val context: Context,
    private val metadata: GameMetadataRepository,
) : ViewModel() {
    private val mutable = MutableStateFlow(GameLibraryUiState())
    val state: StateFlow<GameLibraryUiState> = mutable.asStateFlow()
    // A cancelled job still owns its progress until its final cleanup has completed.
    private var scan: Job? = null
    private var browse: Job? = null
    // Reloads may finish after an import/scan failed. Only the read collector owns clearing
    // its own error; successful catalog refreshes must not erase operation feedback.
    private var libraryReadError: String? = null
    private var metadataFailedGames = 0
    private var firstMetadataFailureReason: String? = null
    private val selection = MutableStateFlow<GameLibraryQuery?>(null)
    private val refreshRevision = MutableStateFlow(0L)

    private data class ReadRequest(
        val revision: com.ambient.tv.games.library.GameLibraryRevision,
        val query: GameLibraryQuery,
        val titleStyle: String,
        val refreshRevision: Long,
    )
    private data class LibraryRequest(
        val read: ReadRequest,
        val view: String,
        val gridColumns: Int,
        val listRows: Int,
    )
    private var loadedGames: Pair<ReadRequest, List<LibraryGame>>? = null
    private var loadedSystems: Pair<com.ambient.tv.games.library.GameLibraryRevision, List<String>>? = null

    init {
        viewModelScope.launch {
            combine(library.revisions, files.revision, preferences.changes().onStart { emit(Unit) }, selection, refreshRevision) { _, _, _, query, refresh ->
                query?.let { LibraryRequest(
                    ReadRequest(library.revisionSnapshot(),
                        it.copy(sort = library.sortKey(), descending = library.sortDescending()), library.titleStyle(), refresh),
                    library.viewStyle(), library.gridColumns(), library.listRows(),
                ) }
            }.filterNotNull().distinctUntilChanged().collectLatest { request ->
                try { reload(request) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    val message = gameOperationError(context, error, R.string.game_error_library)
                    val previousReadError = libraryReadError
                    var published = false
                    mutable.update {
                        published = it.error == null || (previousReadError != null && it.error == previousReadError)
                        it.copy(games = emptyList(), ready = true,
                            error = if (published) message else it.error)
                    }
                    libraryReadError = message.takeIf { published }
                }
            }
        }
    }

    private suspend fun reload(request: LibraryRequest) {
        val games = loadedGames?.takeIf { it.first == request.read }?.second
            ?: kotlinx.coroutines.runInterruptible(Dispatchers.IO) { library.games(request.read.query) }
        val systemsRevision = request.read.revision.copy(overrides = "")
        val systems = loadedSystems?.takeIf { it.first == systemsRevision }?.second
            ?: kotlinx.coroutines.runInterruptible(Dispatchers.IO) { library.systemIds() }
        loadedGames = request.read to games
        loadedSystems = systemsRevision to systems
        val previousReadError = libraryReadError
        mutable.update { it.copy(
            games = games, systems = systems, folders = library.folders(), sources = files.sources(),
            view = request.view, gridColumns = request.gridColumns, listRows = request.listRows,
            titleStyle = request.read.titleStyle, sortKey = request.read.query.sort,
            sortDescending = request.read.query.descending, ready = true,
            error = it.error.takeUnless { error -> previousReadError != null && error == previousReadError },
        ) }
        libraryReadError = null
    }

    /** Management needs folder/source configuration, not every hydrated game or cover. */
    fun openManager() {
        selection.value = GameLibraryQuery(limit = 0)
    }

    fun section(value: String) {
        metadata.startCoverRecovery()
        mutable.update { it.copy(section = value) }
        selection.update { (it ?: GameLibraryQuery(search = mutable.value.query)).copy(section = value, limit = null) }
    }
    fun query(value: String) {
        mutable.update { it.copy(query = value) }
        selection.update { it?.copy(search = value) }
    }
    fun wireGuardConfigured(): Boolean = preferences.wireGuardProfile() != null
    fun view(value: String) = library.setViewStyle(value)
    fun gridColumns(value: Int) = library.setGridColumns(value)
    fun listRows(value: Int) = library.setListRows(value)
    fun titleStyle(value: String) = library.setTitleStyle(value)
    fun sort(value: String) = library.setSortKey(value)
    fun sortDescending(value: Boolean) = library.setSortDescending(value)
    private fun editCatalog(edit: () -> Unit) {
        viewModelScope.launch {
            try { withContext(Dispatchers.IO) { edit() } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { showError(error) }
        }
    }
    fun rename(game: LibraryGame, value: String) = editCatalog { library.setCustomTitle(game.fileId, value) }
    /** Optional, offline, user-supplied DAT files. Never download a catalog implicitly. */
    fun importDat(systemId: String, uri: Uri) {
        if (scan?.isCompleted == false) return
        scan = viewModelScope.launch {
            resetMetadataFailures()
            mutable.update { it.copy(busy = true, scanning = true, scanPhase = GameLibraryScanPhase.NAME_DATABASES,
                scanProcessed = 0, scanDiscovered = 1, error = null, message = null) }
            var catalogsCurrent = false
            try {
                val count = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.let { library.importDat(systemId, it) }
                        ?: throw java.io.FileNotFoundException("DAT file is unavailable")
                }
                val catalogSizes = library.datCatalogSizes()
                mutable.update { it.copy(nameCatalogs = catalogSizes) }
                catalogsCurrent = true
                scanFoldersWithNames(systemId)
                mutable.update { it.copy(message = context.getString(R.string.game_catalog_imported, count)) }
            } catch (cancelled: CancellationException) {
                mutable.update { it.copy(message = null) }
                throw cancelled
            }
            catch (error: Exception) { showError(error, nameDatabaseErrorFallback(R.string.game_error_import)) }
            finally { finishNameDatabaseWork(refreshCatalogs = !catalogsCurrent) }
        }
    }
    fun favorite(game: LibraryGame) = editCatalog { library.setFavorite(game.fileId, !game.favorite) }

    /** Only when selecting a game: probe a manually-wakeable NAS before opening Libretro. */
    fun requestPlay(game: LibraryGame, start: () -> Unit) {
        viewModelScope.launch {
            mutable.update { it.copy(error = null, pendingWakeGame = null) }
            try {
                if (library.needsWakeForLaunch(game.sourceId)) {
                    mutable.update { it.copy(pendingWakeGame = game) }
                } else {
                    start()
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { showError(error, R.string.game_error_play) }
        }
    }
    fun dismissWakeOffer() = mutable.update { it.copy(pendingWakeGame = null) }
    fun wakeAndPlay(start: (LibraryGame) -> Unit) {
        val game = mutable.value.pendingWakeGame ?: return
        viewModelScope.launch {
            mutable.update { it.copy(busy = true, error = null) }
            try {
                if (library.wakeSource(game.sourceId)) {
                    mutable.update { it.copy(pendingWakeGame = null) }
                    start(game)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { showError(error, R.string.files_wake_timeout) }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }

    fun remove(folder: GameLibraryFolder) {
        if (folder.id in mutable.value.removingFolders) return
        mutable.update { it.copy(removingFolders = it.removingFolders + folder.id, error = null) }
        viewModelScope.launch {
            try {
                if (mutable.value.activeScanFolderId == folder.id) scan?.cancelAndJoin()
                metadata.cancelFolder(folder.id)
                withContext(Dispatchers.IO) { library.removeFolder(folder.id) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { showError(error, R.string.game_error_remove) }
            finally { mutable.update { it.copy(removingFolders = it.removingFolders - folder.id) } }
        }
    }
    fun setAlsoShowInFiles(folder: GameLibraryFolder, show: Boolean) =
        library.setAlsoShowInFiles(folder.id, show)

    fun deleteUnusedGameSource(source: FileSource) {
        if (source.showInFiles || library.folders().any { it.sourceId == source.id }) return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    files.removeSource(source.id)
                    if (source.kind == "local" && files.sources().none { it.treeUri == source.treeUri }) {
                        runCatching {
                            context.contentResolver.releasePersistableUriPermission(
                                Uri.parse(source.treeUri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                    }
                }
                closePicker()
            } catch (error: Exception) { showError(error, R.string.game_error_remove) }
        }
    }

    /**
     * A second SMB/NFS share on a known machine inherits its MAC and
     * broadcast settings, but credentials remain per share.
     */
    private fun inheritMachineWake(source: FileSource): FileSource {
        if (source.wolMac.isNotBlank() || files.sources().any { it.id == source.id }) return source
        val sibling = files.sources().firstOrNull { it.kind != "local" &&
            it.host.equals(source.host, ignoreCase = true) && it.wolMac.isNotBlank() }
        if (sibling != null) return source.copy(
            wolMac = sibling.wolMac, wolBroadcast = sibling.wolBroadcast,
            wolPort = sibling.wolPort, wolWaitSeconds = sibling.wolWaitSeconds,
            wolAuto = sibling.wolAuto, wireGuard = source.wireGuard || sibling.wireGuard,
        )
        val mac = wakeMachines.groupFor("files", source.host, null).second.firstOrNull()
        return if (mac != null) source.copy(wolMac = mac) else source
    }

    fun testNewNetworkSource(source: FileSource, password: String, keepPassword: Boolean, sshKey: String?) {
        viewModelScope.launch {
            mutable.update { it.copy(testingNetworkSource = true, error = null) }
            try {
                val valid = inheritMachineWake(source.copy(showInFiles = false).validated())
                access.prepare(valid)
                val original = files.sources().firstOrNull { it.id == valid.id }
                val secret = if (keepPassword && original != null) files.password(original) else password
                when (valid.kind) {
                    "smb" -> access.testSmb(valid, secret)
                    "nfs" -> access.testNfs(valid)
                    "webdav" -> access.testWebDav(valid, secret)
                    "sftp" -> access.testSftp(valid, secret, sshKey ?: original?.let(files::sshKey).orEmpty())
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { showError(error, FilesViewModel.errorMessage(error)) }
            finally { mutable.update { it.copy(testingNetworkSource = false) } }
        }
    }

    fun saveNewNetworkSource(source: FileSource, password: String, keepPassword: Boolean, sshKey: String?) {
        viewModelScope.launch {
            mutable.update { it.copy(savingNetworkSource = true, error = null) }
            try {
                val valid = inheritMachineWake(source.copy(showInFiles = false).validated())
                val match = files.sources().firstOrNull { it.id != valid.id &&
                    it.sameLocation(valid) && it.username == valid.username && it.domain == valid.domain }
                if (match != null) {
                    mutable.update { it.copy(error = context.getString(R.string.game_source_duplicate)) }
                    return@launch
                }
                withContext(Dispatchers.IO) { files.saveSource(valid, if (keepPassword) null else password, sshKey) }
                selectSource(valid)
                mutable.update { it.copy(networkSaveRevision = it.networkSaveRevision + 1) }
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { showError(error, FilesViewModel.errorMessage(error)) }
            finally { mutable.update { it.copy(savingNetworkSource = false) } }
        }
    }
    fun cancelScan() = scan?.cancel()

    /** Aggregate favorites/recent are local catalog filters; only All explicitly scans every source. */
    fun refreshSection() {
        val section = mutable.value.section
        when (section) {
            "all" -> scanAll()
            "favorites", "recent" -> refreshRevision.update { it + 1 }
            else -> if (GameSystems.byId(section) != null) scanAll(section)
        }
    }

    fun scanAll(systemId: String? = null) {
        if (scan?.isCompleted == false) return
        scan = viewModelScope.launch {
            resetMetadataFailures()
            mutable.update { it.copy(busy = true, scanning = true,
                scanPhase = GameLibraryScanPhase.FILES, scanProcessed = 0, scanDiscovered = 0,
                error = null, message = null) }
            try {
                scanEveryFolder(systemId)
                refreshRevision.update { it + 1 }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { showError(error, R.string.game_error_scan) }
            finally { mutable.update { it.copy(busy = false, scanning = false,
                activeScanFolderId = null, enriching = false) } }
        }
    }

    private suspend fun scanEveryFolder(systemId: String? = null) {
        val indexed = systemId?.let { library.foldersWithSystem(it) }.orEmpty()
        for (folder in library.folders()) {
            // Only assigned folders and auto folders already known to contain this system.
            if (systemId != null && folder.systemId != systemId &&
                !(folder.systemId == "auto" && folder.id in indexed)) continue
            // A folder may have been removed while another NAS was being scanned.
            if (folder.id in mutable.value.removingFolders || library.folders().none { it.id == folder.id }) continue
            scanAndEnrich(folder, scanFiles = true, systemId = systemId.takeIf { folder.systemId == "auto" })
            // Each committed scan emits a catalog revision. The single query collector
            // publishes this folder immediately using the current section/search.
        }
    }

    fun refreshNameCatalogs() {
        viewModelScope.launch {
            try {
                val catalogSizes = library.datCatalogSizes()
                mutable.update { it.copy(nameCatalogs = catalogSizes) }
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { showError(error, R.string.game_error_library) }
        }
    }

    /**
     * RetroArch's "Update Databases": loads libretro's names and checksums on request, for one
     * [systemId] or for all systems in the library, then rescans the folders they change so games
     * get their exact names (and with them Libretro box art and better matches elsewhere).
     */
    fun loadNameDatabases(systemId: String? = null) {
        if (scan?.isCompleted == false) return
        scan = viewModelScope.launch {
            resetMetadataFailures()
            mutable.update { it.copy(busy = true, scanning = true, error = null,
                scanPhase = GameLibraryScanPhase.NAME_DATABASES,
                scanProcessed = 0, scanDiscovered = 0, message = null) }
            var catalogsCurrent = false
            try {
                val result = metadata.loadNameDatabases(systemId?.let(::listOf)) { done, total ->
                    mutable.update { it.copy(scanProcessed = done, scanDiscovered = total) }
                }
                val catalogSizes = library.datCatalogSizes()
                mutable.update { it.copy(nameCatalogs = catalogSizes) }
                catalogsCurrent = true
                if (result.systems == 0) {
                    mutable.update { it.copy(message = context.getString(R.string.game_name_databases_none)) }
                    return@launch
                }
                if (systemId == null) {
                    val affected = result.systemIds.flatMap { library.foldersWithSystem(it) }.toSet()
                    for (folder in library.folders()) {
                        if (folder.id !in affected || folder.id in mutable.value.removingFolders ||
                            library.folders().none { it.id == folder.id }) continue
                        scanAndEnrich(folder, scanFiles = true, matchingNames = true)
                    }
                    mutable.update { it.copy(message = context.getString(R.string.game_name_databases_loaded,
                        result.names, result.systems)) }
                    return@launch
                }
                val affected = scanFoldersWithNames(systemId)
                val name = GameSystems.byId(systemId)?.name ?: systemId
                mutable.update { it.copy(message = context.getString(if (!affected)
                    R.string.game_name_databases_later else R.string.game_name_databases_loaded_one, result.names, name)) }
            } catch (error: CancellationException) {
                mutable.update { it.copy(message = null) }
                throw error
            }
            catch (error: Exception) {
                mutable.update { it.copy(message = null) }
                showError(error, nameDatabaseErrorFallback(R.string.game_error_name_databases))
            }
            finally { finishNameDatabaseWork(refreshCatalogs = !catalogsCurrent) }
        }
    }

    private fun nameDatabaseErrorFallback(loadingError: Int): Int = when (mutable.value.scanPhase) {
        GameLibraryScanPhase.NAME_DATABASES -> loadingError
        GameLibraryScanPhase.FILES, GameLibraryScanPhase.MATCHING_NAMES -> R.string.game_error_scan
        GameLibraryScanPhase.METADATA -> R.string.game_error_search
    }

    private suspend fun finishNameDatabaseWork(refreshCatalogs: Boolean) {
        try {
            if (refreshCatalogs) withContext(NonCancellable) {
                // Earlier systems may already be committed when a later download is cancelled
                // or fails. Publish those counts without replacing the operation's actual error.
                try {
                    val catalogSizes = library.datCatalogSizes()
                    mutable.update { it.copy(nameCatalogs = catalogSizes) }
                } catch (_: Exception) { /* Keep the last counts if the database cannot be read. */ }
            }
        } finally {
            mutable.update { it.copy(busy = false, scanning = false,
                activeScanFolderId = null, enriching = false) }
        }
    }

    private suspend fun scanFoldersWithNames(systemId: String): Boolean {
        val affected = library.foldersWithSystem(systemId)
        var scanned = false
        for (folder in library.folders()) {
            if (folder.id !in affected || folder.id in mutable.value.removingFolders) continue
            if (library.folders().none { it.id == folder.id }) continue
            scanAndEnrich(folder, scanFiles = true, matchingNames = true)
            scanned = true
        }
        return scanned
    }

    fun scanFolder(folder: GameLibraryFolder) {
        startFolderWork(folder, scanFiles = true)
    }

    /** Uses only the indexed files of this folder; no NAS listing or ROM hashing is repeated. */
    fun enrichFolder(folder: GameLibraryFolder) {
        startFolderWork(folder, scanFiles = false)
    }

    private fun startFolderWork(folder: GameLibraryFolder, scanFiles: Boolean) {
        if (scan?.isCompleted == false) return
        if (folder.id in mutable.value.removingFolders) return
        scan = viewModelScope.launch {
            resetMetadataFailures()
            mutable.update { it.copy(busy = true, scanning = true, scanProcessed = 0,
                scanPhase = if (scanFiles) GameLibraryScanPhase.FILES else GameLibraryScanPhase.METADATA,
                scanDiscovered = 0, scanFolderName = folder.path, error = null, message = null) }
            try {
                scanAndEnrich(folder, scanFiles)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                showError(error, if (scanFiles) R.string.game_error_scan else R.string.game_error_search)
            }
            finally { mutable.update { it.copy(busy = false, scanning = false,
                activeScanFolderId = null, enriching = false) } }
        }
    }

    private suspend fun scanAndEnrich(folder: GameLibraryFolder, scanFiles: Boolean,
                                      matchingNames: Boolean = false, systemId: String? = null) {
        mutable.update { it.copy(activeScanFolderId = folder.id,
            scanPhase = when {
                !scanFiles -> GameLibraryScanPhase.METADATA
                matchingNames -> GameLibraryScanPhase.MATCHING_NAMES
                else -> GameLibraryScanPhase.FILES
            },
            scanFolderName = folder.path.ifBlank {
                files.sources().firstOrNull { it.id == folder.sourceId }?.name
                    ?: context.getString(R.string.game_folder_fallback)
            },
            scanProcessed = 0, scanDiscovered = 0, enriching = !scanFiles) }
        if (scanFiles) {
            val assigned = systemId ?: folder.systemId.takeUnless { it == "auto" }
            // Assigned folders already know their platform: download the catalog before
            // opening any ROM, so exact checksum identification works in the first scan.
            if (assigned != null) metadata.prepareNameCatalogs(listOf(assigned), automatic = true)
            library.scan(folder.id, systemId = systemId) { processed, known ->
                mutable.update { it.copy(scanProcessed = processed, scanDiscovered = known) }
            }
            if (assigned == null && library.folders().any { it.id == folder.id }) {
                // Auto folders discover their platforms from file types. Only newly loaded
                // checksum catalogs need a targeted second pass, within the same operation.
                val detected = library.indexedSystems().filter { folder.id in library.foldersWithSystem(it) }
                val loaded = metadata.prepareNameCatalogs(detected, automatic = true)
                for (id in loaded.filter(com.ambient.tv.games.metadata.GameNameDatabases::usesChecksums)) {
                    if (folder.id in mutable.value.removingFolders || library.folders().none { it.id == folder.id }) break
                    mutable.update { it.copy(scanPhase = GameLibraryScanPhase.MATCHING_NAMES,
                        scanProcessed = 0, scanDiscovered = 0) }
                    library.scan(folder.id, systemId = id) { processed, known ->
                        mutable.update { it.copy(scanProcessed = processed, scanDiscovered = known) }
                    }
                }
            }
            val sizes = library.datCatalogSizes()
            mutable.update { it.copy(nameCatalogs = sizes) }
        }
        if (folder.id in mutable.value.removingFolders || library.folders().none { it.id == folder.id }) return
        mutable.update { it.copy(enriching = true, scanPhase = GameLibraryScanPhase.METADATA,
            scanProcessed = 0, scanDiscovered = 0) }
        if (scanFiles) metadata.importLocalFolder(folder.id, systemId = systemId)
        try {
            metadata.enrichFolder(folder.id, automatic = scanFiles, systemId = systemId) { processed, known ->
                mutable.update { it.copy(scanProcessed = processed, scanDiscovered = known) }
            }
        } catch (error: GameMetadataBatchException) {
            // The folder was fully attempted. Keep its partial result visible while other
            // folders continue, and accumulate counts for this operation instead of replacing them.
            metadataFailedGames += error.failedGames
            if (firstMetadataFailureReason == null) firstMetadataFailureReason =
                gameOperationError(context, error.firstFailure, R.string.game_error_search)
            libraryReadError = null
            mutable.update { it.copy(error = context.getString(R.string.game_metadata_partial_failure,
                metadataFailedGames, firstMetadataFailureReason)) }
        }
    }

    private fun resetMetadataFailures() {
        metadataFailedGames = 0
        firstMetadataFailureReason = null
    }
    /** Persist the exact SAF tree grant and register it with Files, just like video folders. */
    fun addLocalSource(uri: Uri) {
        try {
            files.sources().firstOrNull { it.kind == "local" && it.treeUri == uri.toString() }?.let {
                selectSource(it)
                return
            }
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val name = uri.lastPathSegment?.substringAfterLast(':')?.takeIf { it.isNotBlank() }
                ?: context.getString(R.string.game_library_title)
            val source = FileSource(name = name.take(160), kind = "local", treeUri = uri.toString(), showInFiles = false)
            files.saveSource(source, null)
            selectSource(source)
        } catch (error: Exception) { showError(error, FilesViewModel.errorMessage(error)) }
    }

    fun selectSource(source: FileSource) {
        val root = access.root(source)
        mutable.update { it.copy(selectedSource = source,
            browsedPaths = listOf(root to source.name), directoryEntries = emptyList(), error = null) }
        browseAt(source, root)
    }
    fun enterFolder(entry: FileEntry) {
        if (!entry.directory) return
        val source = mutable.value.selectedSource ?: return
        mutable.update { it.copy(browsedPaths = it.browsedPaths + (entry.path to entry.name)) }
        browseAt(source, entry.path)
    }
    fun folderUp(): Boolean {
        val state = mutable.value
        if (state.browsedPaths.size <= 1) return false
        val source = state.selectedSource ?: return false
        val paths = state.browsedPaths.dropLast(1)
        mutable.update { it.copy(browsedPaths = paths) }
        browseAt(source, paths.last().first)
        return true
    }
    fun closePicker() {
        browse?.cancel()
        mutable.update { it.copy(selectedSource = null, browsedPaths = emptyList(),
            directoryEntries = emptyList(), browsing = false, error = null) }
    }
    private fun browseAt(source: FileSource, path: String) {
        browse?.cancel()
        browse = viewModelScope.launch {
            mutable.update { it.copy(browsing = true, error = null) }
            try {
                val items = withContext(Dispatchers.IO) {
                    access.list(source, path, 300) { false }
                }
                libraryReadError = null
                mutable.update { it.copy(directoryEntries = items.entries.filter(FileEntry::directory),
                    error = if (items.more) context.getString(R.string.game_folder_more) else null) }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                val message = gameOperationError(context, error, FilesViewModel.errorMessage(error))
                libraryReadError = null
                mutable.update { it.copy(error = message, directoryEntries = emptyList()) }
            }
            finally { mutable.update { it.copy(browsing = false) } }
        }
    }

    fun wakeCurrentSource() {
        val source = mutable.value.selectedSource ?: return
        if (source.wolMac.isBlank()) return
        viewModelScope.launch {
            mutable.update { it.copy(browsing = true, error = null) }
            try {
                if (library.wakeSource(source.id)) browseAt(source, mutable.value.browsedPaths.last().first)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { showError(error, R.string.files_wake_timeout) }
            finally { mutable.update { it.copy(browsing = false) } }
        }
    }

    fun assign(systemId: String): Boolean {
        val source = mutable.value.selectedSource ?: return false
        val path = mutable.value.browsedPaths.lastOrNull()?.first ?: return false
        return try {
            val folder = library.addFolder(source, path, systemId)
            closePicker()
            scanFolder(folder) // explicit user action; permitted to wake the configured NAS
            true
        } catch (error: Exception) {
            showError(error, R.string.game_error_update)
            false
        }
    }

    private fun showError(error: Throwable, fallback: Int = R.string.game_error_update) {
        val message = gameOperationError(context, error, fallback)
        libraryReadError = null
        mutable.update { it.copy(error = message) }
    }
}
