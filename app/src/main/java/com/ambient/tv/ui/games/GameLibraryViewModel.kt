package com.ambient.tv.ui.games

import android.content.Context
import android.content.Intent
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ambient.tv.data.files.FileAccess
import com.ambient.tv.data.files.FileEntry
import com.ambient.tv.data.files.FileSource
import com.ambient.tv.data.files.FileStore
import com.ambient.tv.games.library.GameLibraryFolder
import com.ambient.tv.games.library.GameLibraryQuery
import com.ambient.tv.games.library.GameLibraryRepository
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.games.library.LibraryGame
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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
    val scanProcessed: Int = 0,
    val scanDiscovered: Int = 0,
    val scanFolderName: String = "",
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
)

@HiltViewModel
class GameLibraryViewModel @Inject constructor(
    private val library: GameLibraryRepository,
    private val files: FileStore,
    private val access: FileAccess,
    private val wakeMachines: com.ambient.tv.wol.WakeMachines,
    private val preferences: AppPreferences,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    private val mutable = MutableStateFlow(GameLibraryUiState())
    val state: StateFlow<GameLibraryUiState> = mutable.asStateFlow()
    private var scan: Job? = null
    private var browse: Job? = null
    private val selection = MutableStateFlow<GameLibraryQuery?>(null)

    private data class ReadRequest(
        val revision: com.ambient.tv.games.library.GameLibraryRevision,
        val query: GameLibraryQuery,
        val titleStyle: String,
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
            combine(library.revisions, files.revision, preferences.changes().onStart { emit(Unit) }, selection) { _, _, _, query ->
                query?.let { LibraryRequest(
                    ReadRequest(library.revisionSnapshot(),
                        it.copy(sort = library.sortKey(), descending = library.sortDescending()), library.titleStyle()),
                    library.viewStyle(), library.gridColumns(), library.listRows(),
                ) }
            }.filterNotNull().distinctUntilChanged().collectLatest { request ->
                try { reload(request) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    mutable.update { it.copy(games = emptyList(), ready = true,
                        error = error.message ?: "Game library unavailable") }
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
        mutable.update { it.copy(
            games = games, systems = systems, folders = library.folders(), sources = files.sources(),
            view = request.view, gridColumns = request.gridColumns, listRows = request.listRows,
            titleStyle = request.read.titleStyle, sortKey = request.read.query.sort,
            sortDescending = request.read.query.descending, ready = true, error = null,
        ) }
    }

    fun section(value: String) {
        mutable.update { it.copy(section = value) }
        selection.update { (it ?: GameLibraryQuery(search = mutable.value.query)).copy(section = value) }
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
            catch (error: Exception) { mutable.update { it.copy(error = error.message ?: "Game library unavailable") } }
        }
    }
    fun rename(game: LibraryGame, value: String) = editCatalog { library.setCustomTitle(game.fileId, value) }
    /** Optional, offline, user-supplied DAT files. Never download a catalog implicitly. */
    fun importDat(systemId: String, uri: Uri) {
        viewModelScope.launch {
            mutable.update { it.copy(busy = true, error = null, message = null) }
            try {
                val count = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.let { library.importDat(systemId, it) }
                        ?: throw java.io.FileNotFoundException("DAT file is unavailable")
                }
                mutable.update { it.copy(message = "$count names imported · rescan ROM folders to match") }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { mutable.update { it.copy(error = error.message ?: "DAT import failed") } }
            finally { mutable.update { it.copy(busy = false) } }
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
            catch (error: Exception) { mutable.update { it.copy(error = error.message) } }
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
            catch (error: Exception) { mutable.update { it.copy(error = error.message) } }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }

    fun remove(folder: GameLibraryFolder) = editCatalog { library.removeFolder(folder.id) }
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
            } catch (error: Exception) { mutable.update { it.copy(error = error.message) } }
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
            catch (error: Exception) { mutable.update { it.copy(error = error.message) } }
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
                    mutable.update { it.copy(error = "This network share is already configured; select the existing source.") }
                    return@launch
                }
                withContext(Dispatchers.IO) { files.saveSource(valid, if (keepPassword) null else password, sshKey) }
                selectSource(valid)
                mutable.update { it.copy(networkSaveRevision = it.networkSaveRevision + 1) }
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { mutable.update { it.copy(error = error.message) } }
            finally { mutable.update { it.copy(savingNetworkSource = false) } }
        }
    }
    fun cancelScan() = scan?.cancel()

    fun scanAll() {
        if (scan?.isActive == true) return
        scan = viewModelScope.launch {
            mutable.update { it.copy(busy = true, scanning = true,
                scanProcessed = 0, scanDiscovered = 0, error = null) }
            try {
                var done = 0
                var discovered = 0
                for (folder in library.folders()) {
                    val baseDone = done
                    val baseDiscovered = discovered
                    library.scan(folder.id) { processed, known ->
                        mutable.update { it.copy(scanProcessed = baseDone + processed,
                            scanDiscovered = baseDiscovered + known,
                            scanFolderName = folder.path.ifBlank { folder.sourceId }) }
                    }
                    done = mutable.value.scanProcessed
                    discovered = mutable.value.scanDiscovered
                    // Display each completed folder immediately, without waiting for a second NAS.
                    reload()
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { mutable.update { it.copy(error = error.message ?: "Scan failed") } }
            finally { mutable.update { it.copy(busy = false, scanning = false) }; reload() }
        }
    }
    fun scanFolder(folder: GameLibraryFolder) {
        if (scan?.isActive == true) return
        scan = viewModelScope.launch {
            mutable.update { it.copy(busy = true, scanning = true, scanProcessed = 0,
                scanDiscovered = 0, scanFolderName = folder.path, error = null) }
            try {
                library.scan(folder.id) { processed, known ->
                    mutable.update { it.copy(scanProcessed = processed, scanDiscovered = known) }
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { mutable.update { it.copy(error = error.message ?: "Scan failed") } }
            finally { mutable.update { it.copy(busy = false, scanning = false) }; reload() }
        }
    }
    /** Persist the exact SAF tree grant and register it with Files, just like video folders. */
    fun addLocalSource(uri: Uri) {
        try {
            files.sources().firstOrNull { it.kind == "local" && it.treeUri == uri.toString() }?.let {
                selectSource(it)
                return
            }
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val name = uri.lastPathSegment?.substringAfterLast(':')?.takeIf { it.isNotBlank() } ?: "Retro ROMs"
            val source = FileSource(name = name.take(160), kind = "local", treeUri = uri.toString(), showInFiles = false)
            files.saveSource(source, null)
            selectSource(source)
        } catch (error: Exception) { mutable.update { it.copy(error = error.message) } }
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
                mutable.update { it.copy(directoryEntries = items.entries.filter(FileEntry::directory),
                    error = if (items.more) "More folders exist. Select a narrower folder." else null) }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { mutable.update { it.copy(error = error.message ?: "Source unavailable",
                directoryEntries = emptyList()) } }
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
            catch (error: Exception) { mutable.update { it.copy(error = error.message) } }
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
            mutable.update { it.copy(error = error.message) }
            false
        }
    }
}
