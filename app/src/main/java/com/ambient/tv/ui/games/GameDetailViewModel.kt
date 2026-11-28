package com.ambient.tv.ui.games

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ambient.tv.R
import com.ambient.tv.data.files.FileStore
import com.ambient.tv.games.library.GameLibraryRepository
import com.ambient.tv.games.library.LibraryGame
import com.ambient.tv.games.metadata.GameArtworkKind
import com.ambient.tv.games.metadata.GameMetadata
import com.ambient.tv.games.metadata.GameMetadataCandidate
import com.ambient.tv.games.metadata.GameMetadataRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class GameDetailUiState(
    val loading: Boolean = true,
    val game: LibraryGame? = null,
    val sourceName: String = "",
    val metadata: GameMetadata = GameMetadata(),
    val busy: Boolean = false,
    val error: String? = null,
    val refreshEmpty: Boolean = false,
    val searching: Boolean = false,
    val searchCompleted: Boolean = false,
    val matches: List<GameMetadataCandidate> = emptyList(),
    val providers: List<String> = emptyList(),
    val systemChoices: List<com.ambient.tv.games.GameSystem> = emptyList(),
)

/** Pre-metadata renamed games already contain a protected user choice. */
internal fun gameMetadataWithExistingTitle(value: GameMetadata, customTitle: String?): GameMetadata =
    customTitle?.takeIf(String::isNotBlank)?.let { title ->
        value.copy(title = title, lockedFields = value.lockedFields + "title")
    } ?: value

/** The detail page reads only cached catalog data; only Play may probe or wake the ROM source. */
@HiltViewModel
class GameDetailViewModel @Inject constructor(
    saved: SavedStateHandle,
    private val library: GameLibraryRepository,
    private val metadata: GameMetadataRepository,
    private val files: FileStore,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    private val fileId = saved.get<String>("fileId").orEmpty()
    private val mutable = MutableStateFlow(GameDetailUiState())
    val state = mutable.asStateFlow()
    private var actionJob: Job? = null
    private var searchJob: Job? = null
    private var searchRevision = 0L
    private var detailRevision = 0L
    private var backdropAttempt: String? = null

    init {
        viewModelScope.launch {
            library.revisions.collectLatest {
                try { reload() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    val message = gameOperationError(context, error, R.string.game_error_library)
                    mutable.update { it.copy(loading = false, error = message) }
                }
            }
        }
    }

    private suspend fun reload() {
        val revision = ++detailRevision
        val (game, systems) = withContext(Dispatchers.IO) {
            library.game(fileId) to library.gameSystemChoices(fileId)
        }
        val details = if (game != null) gameMetadataWithExistingTitle(
            metadata.detail(fileId) ?: GameMetadata(), game.customTitle) else GameMetadata()
        // An action's reload and the database revision collector can overlap. Only the
        // newest read may publish, including when a folder was removed during image IO.
        if (revision != detailRevision) return
        val sourceName = files.sources().firstOrNull { it.id == game?.sourceId }?.name.orEmpty()
        mutable.update { it.copy(loading = false, game = game, sourceName = sourceName, metadata = details,
            providers = metadata.availableProviders(), systemChoices = systems) }
        val image = details.backdropUrl
        if (game != null && game.backdropPath == null && image != null && backdropAttempt != image) {
            backdropAttempt = image
            viewModelScope.launch {
                try { metadata.ensureArtwork(game, GameArtworkKind.BACKDROP) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* A missing optional backdrop never blocks the details. */ }
            }
        }
    }

    fun providersChanged() {
        backdropAttempt = null
        mutable.update { it.copy(providers = metadata.availableProviders(), error = null, refreshEmpty = false) }
        viewModelScope.launch {
            try { reload() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { showError(error, R.string.game_error_library) }
        }
    }

    fun clearSearch() {
        searchRevision++
        searchJob?.cancel()
        mutable.update { it.copy(searching = false, searchCompleted = false, matches = emptyList(), error = null) }
    }

    fun search(query: String, provider: String, artworkKind: GameArtworkKind? = null) {
        lookup { game -> metadata.search(game, query, provider, artworkKind) }
    }

    fun artworkChoices(kind: GameArtworkKind) {
        lookup { game -> metadata.artworkChoices(game, kind) }
    }

    private fun lookup(find: suspend (LibraryGame) -> List<GameMetadataCandidate>) {
        val game = mutable.value.game ?: return
        val revision = ++searchRevision
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            mutable.update { it.copy(searching = true, searchCompleted = false, matches = emptyList(), error = null) }
            try {
                val matches = find(game)
                if (revision == searchRevision) mutable.update { it.copy(matches = matches, searchCompleted = true) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (revision == searchRevision) {
                    val message = gameOperationError(context, error, R.string.game_error_search)
                    mutable.update { it.copy(error = message, searchCompleted = true) }
                }
            }
            finally { if (revision == searchRevision) mutable.update { it.copy(searching = false) } }
        }
    }

    private fun action(onDone: (Boolean) -> Unit = {}, failureMessage: Int = R.string.game_error_update,
                       block: suspend (LibraryGame) -> Boolean) {
        if (actionJob?.isActive == true) return
        val game = mutable.value.game ?: return
        actionJob = viewModelScope.launch {
            mutable.update { it.copy(busy = true, error = null, refreshEmpty = false) }
            try {
                val applied = block(game)
                reload()
                onDone(applied)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { showError(error, failureMessage) }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }

    fun applyMatch(match: GameMetadataCandidate, onDone: (Boolean) -> Unit) =
        action(reportUnapplied(onDone)) { metadata.applyMatch(it, match) }

    fun save(value: GameMetadata, locked: Set<String>, onDone: (Boolean) -> Unit) =
        action(onDone) { metadata.save(it, value, locked) }

    fun setArtwork(kind: GameArtworkKind, match: GameMetadataCandidate, onDone: (Boolean) -> Unit) =
        action(reportUnapplied(onDone)) { metadata.setArtwork(it, kind, match) }

    /** A choice that cannot be applied (its image is gone, say) must not look like an ignored tap. */
    private fun reportUnapplied(onDone: (Boolean) -> Unit): (Boolean) -> Unit = { applied ->
        if (!applied) mutable.update { it.copy(error = context.getString(R.string.game_edit_match_failed)) }
        onDone(applied)
    }

    fun importArtwork(kind: GameArtworkKind, uri: Uri, onDone: (Boolean) -> Unit) =
        action(onDone) { metadata.setLocalArtwork(it, kind, uri) }

    suspend fun previewArtwork(match: GameMetadataCandidate, kind: GameArtworkKind): String? =
        metadata.previewArtwork(match, kind, fileId)

    fun refresh() = action(failureMessage = R.string.game_error_search, onDone = { applied ->
        mutable.update { it.copy(refreshEmpty = !applied && it.game != null) }
    }) { metadata.refresh(it, force = true) }

    fun changeSystem(systemId: String?) = action(onDone = { applied ->
        if (!applied) mutable.update { it.copy(error = context.getString(R.string.game_error_update)) }
    }) { game ->
        if (!library.changeGameSystem(game.fileId, systemId)) false
        else {
            val updated = withContext(Dispatchers.IO) { library.game(game.fileId) }
            updated?.let {
                try { metadata.refresh(it, force = true) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* Assignment is committed; artwork can retry. */ }
            }
            true
        }
    }

    fun favorite() = action {
        withContext(Dispatchers.IO) { library.setFavorite(it.fileId, !it.favorite) }
        true
    }

    private fun showError(error: Throwable, fallback: Int) {
        val message = gameOperationError(context, error, fallback)
        mutable.update { it.copy(error = message) }
    }
}
