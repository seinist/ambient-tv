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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class GameLibraryUiState(
    val games: List<LibraryGame> = emptyList(),
    val folders: List<GameLibraryFolder> = emptyList(),
    val sources: List<FileSource> = emptyList(),
    val section: String = "all",
    val query: String = "",
    val view: String = "grid",
    val gridColumns: Int = 8,
    val listRows: Int = 7,
    val titleStyle: String = "auto",
    val busy: Boolean = false,
    val ready: Boolean = false,
    val error: String? = null,
    val selectedSource: FileSource? = null,
    val browsedPaths: List<Pair<String, String>> = emptyList(),
    val directoryEntries: List<FileEntry> = emptyList(),
    val browsing: Boolean = false,
    val pendingWakeGame: LibraryGame? = null,
    val savingNetworkSource: Boolean = false,
    val testingNetworkSource: Boolean = false,
)

@HiltViewModel
class GameLibraryViewModel @Inject constructor(
    private val library: GameLibraryRepository,
    private val files: FileStore,
    private val access: FileAccess,
    private val preferences: AppPreferences,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    private val mutable = MutableStateFlow(GameLibraryUiState())
    val state: StateFlow<GameLibraryUiState> = mutable.asStateFlow()
    private var scan: Job? = null
    private var browse: Job? = null

    init {
        viewModelScope.launch {
            combine(library.revisions, files.revision, preferences.changes()) { _, _, _ -> Unit }.collect {
                reload()
            }
        }
    }

    private suspend fun reload() {
        val games = withContext(Dispatchers.IO) { library.games() }
        mutable.update { it.copy(
            games = games, folders = library.folders(), sources = files.sources(),
            view = library.viewStyle(), gridColumns = library.gridColumns(),
            listRows = library.listRows(), titleStyle = library.titleStyle(), ready = true,
        ) }
    }

    fun section(value: String) {
        mutable.update { it.copy(section = value) }
    }
    fun query(value: String) = mutable.update { it.copy(query = value) }
    fun wireGuardConfigured(): Boolean = preferences.wireGuardProfile() != null
    fun view(value: String) = library.setViewStyle(value)
    fun gridColumns(value: Int) = library.setGridColumns(value)
    fun listRows(value: Int) = library.setListRows(value)
    fun titleStyle(value: String) = library.setTitleStyle(value)
    fun favorite(game: LibraryGame) = library.setFavorite(game.fileId, !game.favorite)
    fun played(game: LibraryGame) = library.recordPlayed(game.fileId)

    /** Only when selecting a game: probe a manually-wakeable NAS before opening Libretro. */
    fun requestPlay(game: LibraryGame, start: () -> Unit) {
        viewModelScope.launch {
            mutable.update { it.copy(error = null, pendingWakeGame = null) }
            try {
                if (library.needsWakeForLaunch(game.sourceId)) {
                    mutable.update { it.copy(pendingWakeGame = game) }
                } else {
                    played(game)
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
                    played(game)
                    start(game)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { mutable.update { it.copy(error = error.message) } }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }

    fun remove(folder: GameLibraryFolder) = library.removeFolder(folder.id)
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

    fun testNewNetworkSource(source: FileSource, password: String, keepPassword: Boolean, sshKey: String?) {
        viewModelScope.launch {
            mutable.update { it.copy(testingNetworkSource = true, error = null) }
            try {
                val valid = source.copy(showInFiles = false).validated()
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
                val valid = source.copy(showInFiles = false).validated()
                val match = files.sources().firstOrNull { it.id != valid.id &&
                    it.sameLocation(valid) && it.username == valid.username && it.domain == valid.domain }
                if (match != null) {
                    mutable.update { it.copy(error = "This network share is already configured; select the existing source.") }
                    return@launch
                }
                withContext(Dispatchers.IO) { files.saveSource(valid, if (keepPassword) null else password, sshKey) }
                selectSource(valid)
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { mutable.update { it.copy(error = error.message) } }
            finally { mutable.update { it.copy(savingNetworkSource = false) } }
        }
    }
    fun scanAll() {
        if (scan?.isActive == true) return
        scan = viewModelScope.launch {
            mutable.update { it.copy(busy = true, error = null) }
            try {
                for (folder in library.folders()) library.scan(folder.id)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { mutable.update { it.copy(error = error.message ?: "Scan failed") } }
            finally { mutable.update { it.copy(busy = false) }; reload() }
        }
    }
    fun scanFolder(folder: GameLibraryFolder) {
        if (scan?.isActive == true) return
        scan = viewModelScope.launch {
            mutable.update { it.copy(busy = true, error = null) }
            try { library.scan(folder.id) }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { mutable.update { it.copy(error = error.message ?: "Scan failed") } }
            finally { mutable.update { it.copy(busy = false) }; reload() }
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
