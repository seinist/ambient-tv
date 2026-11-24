package com.ambient.tv.ui.romm

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ambient.tv.games.library.GameLibraryRepository
import com.ambient.tv.games.library.LibraryGame
import com.ambient.tv.romm.RommAccountStore
import com.ambient.tv.romm.RommAuthException
import com.ambient.tv.romm.RommLibrary
import com.ambient.tv.romm.RommMenuPlatform
import com.ambient.tv.ui.games.GameLibraryUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class RommLibraryUiState(
    /** The shared game library state, so header, chips, sort bar and view panel are the same ones. */
    val library: GameLibraryUiState = GameLibraryUiState(),
    val platforms: List<RommMenuPlatform> = emptyList(),
    val signedIn: Boolean = false,
    val hasCatalog: Boolean = false,
    /** Showing the device's copy because the server did not answer (or its machine is offline). */
    val offline: Boolean = false,
    /** The machine RomM runs on is known to be offline (shared with the other servers on it). */
    val serverOffline: Boolean = false,
    val signInRejected: Boolean = false,
    val error: String? = null,
)

/**
 * The RomM library in the game library's look: grid, list and rows, sort and title options are the
 * same settings the local games use. Opening it reads the server again when the copy is old.
 */
@HiltViewModel
class RommLibraryViewModel @Inject constructor(
    private val romm: RommLibrary,
    private val accounts: RommAccountStore,
    private val gameLibrary: GameLibraryRepository,
    private val hosts: com.ambient.tv.connectivity.HostConnectivity,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    private val mutable = MutableStateFlow(RommLibraryUiState())
    val state: StateFlow<RommLibraryUiState> = mutable.asStateFlow()
    private val section = MutableStateFlow("all")
    private val query = MutableStateFlow("")

    init {
        viewModelScope.launch {
            combine(romm.state, gameLibrary.revisions, accounts.changes, section, query) { _, _, _, section, query -> section to query }
                .collectLatest { (section, query) -> reload(section, query) }
        }
        // Like the other server pages: an offline machine shows in the page header, nowhere else.
        viewModelScope.launch {
            hosts.states.collect {
                val offline = hosts.health(HOST_SYSTEM) == com.ambient.tv.connectivity.HostHealth.OFFLINE
                mutable.update { state -> state.copy(serverOffline = offline) }
            }
        }
        viewModelScope.launch {
            val stale = withContext(Dispatchers.IO) { romm.catalog() }
                ?.let { System.currentTimeMillis() - it.fetchedAt > STALE_AFTER_MS } ?: true
            if (stale && accounts.account() != null) refresh()
        }
    }

    private suspend fun reload(section: String, query: String) {
        val sort = gameLibrary.sortKey()
        val descending = gameLibrary.sortDescending()
        val titleStyle = gameLibrary.titleStyle()
        val (games, platforms, catalog) = withContext(Dispatchers.IO) {
            Triple(romm.games(section, query, sort, descending, titleStyle), romm.menuPlatforms(), romm.catalog())
        }
        val libraryState = romm.state.value
        mutable.update { current ->
            current.copy(
                library = current.library.copy(
                    games = games,
                    section = section,
                    query = query,
                    view = gameLibrary.viewStyle(),
                    gridColumns = gameLibrary.gridColumns(),
                    listRows = gameLibrary.listRows(),
                    titleStyle = titleStyle,
                    sortKey = sort,
                    sortDescending = descending,
                    busy = libraryState.refreshing,
                    ready = catalog != null || !libraryState.refreshing,
                ),
                platforms = platforms,
                signedIn = accounts.account() != null,
                hasCatalog = catalog != null,
            )
        }
    }

    fun section(value: String) {
        section.value = value
    }

    fun query(value: String) {
        query.value = value
    }

    /** Reads the server again; the copy on this device stays when it does not answer. */
    fun refresh() {
        viewModelScope.launch {
            mutable.update { it.copy(error = null) }
            try {
                romm.refresh()
                mutable.update { it.copy(offline = false, signInRejected = false, error = null) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutable.update { it.copy(
                    offline = error !is RommAuthException,
                    signInRejected = error is RommAuthException,
                    error = rommErrorMessage(context, error),
                    library = it.library.copy(ready = true),
                ) }
            }
        }
    }

    fun favorite(game: LibraryGame) {
        viewModelScope.launch(Dispatchers.IO) { romm.setFavorite(game.fileId, !game.favorite) }
    }

    // The local library's view settings: the same grid, list and sort for every game.
    fun view(value: String) = gameLibrary.setViewStyle(value)
    fun gridColumns(value: Int) = gameLibrary.setGridColumns(value)
    fun listRows(value: Int) = gameLibrary.setListRows(value)
    fun titleStyle(value: String) = gameLibrary.setTitleStyle(value)
    fun sort(value: String) = gameLibrary.setSortKey(value)
    fun sortDescending(value: Boolean) = gameLibrary.setSortDescending(value)

    private companion object {
        const val STALE_AFTER_MS = 10 * 60 * 1000L
        const val HOST_SYSTEM = "romm"
    }
}
