package com.ambient.tv.ui.games

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ambient.tv.data.files.FileAccess
import com.ambient.tv.data.files.FileEntry
import com.ambient.tv.data.files.FileSource
import com.ambient.tv.data.files.FileStore
import com.ambient.tv.games.library.GameLibraryFolder
import com.ambient.tv.games.library.GameLibraryRepository
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
    val titleStyle: String = "auto",
    val busy: Boolean = false,
    val ready: Boolean = false,
    val error: String? = null,
    val selectedSource: FileSource? = null,
    val browsedPaths: List<Pair<String, String>> = emptyList(),
    val directoryEntries: List<FileEntry> = emptyList(),
    val browsing: Boolean = false,
)

@HiltViewModel
class GameLibraryViewModel @Inject constructor(
    private val library: GameLibraryRepository,
    private val files: FileStore,
    private val access: FileAccess,
) : ViewModel() {
    private val mutable = MutableStateFlow(GameLibraryUiState())
    val state: StateFlow<GameLibraryUiState> = mutable.asStateFlow()
    private var scan: Job? = null
    private var browse: Job? = null

    init {
        viewModelScope.launch {
            combine(library.revisions, files.revision) { _, _ -> Unit }.collect {
                reload()
            }
        }
    }

    private suspend fun reload() {
        val games = withContext(Dispatchers.IO) { library.games() }
        mutable.update { it.copy(
            games = games, folders = library.folders(), sources = files.sources(),
            view = library.viewStyle(), titleStyle = library.titleStyle(), ready = true,
        ) }
    }

    fun section(value: String) {
        mutable.update { it.copy(section = value) }
    }
    fun query(value: String) = mutable.update { it.copy(query = value) }
    fun view(value: String) = library.setViewStyle(value)
    fun titleStyle(value: String) = library.setTitleStyle(value)
    fun favorite(game: LibraryGame) = library.setFavorite(game.fileId, !game.favorite)
    fun played(game: LibraryGame) = library.recordPlayed(game.fileId)

    fun remove(folder: GameLibraryFolder) = library.removeFolder(folder.id)
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
