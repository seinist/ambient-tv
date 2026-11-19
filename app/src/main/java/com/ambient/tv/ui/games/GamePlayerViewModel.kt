package com.ambient.tv.ui.games

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ambient.tv.games.GameCore
import com.ambient.tv.games.GameCoreIncompatibleException
import com.ambient.tv.games.GameCoreManager
import com.ambient.tv.games.GameRomPreparer
import com.ambient.tv.games.GameStateSlot
import com.ambient.tv.games.GameStorage
import com.ambient.tv.games.PreparedGame
import com.ambient.tv.games.UnknownGameException
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/** Everything a running game needs. */
data class GameSession(
    val game: PreparedGame,
    val coreFile: File,
    val systemDirectory: File,
    val savesDirectory: File,
    val sram: ByteArray?,
    val autoState: ByteArray?,
) {
    override fun equals(other: Any?) = other is GameSession && other.game == game && other.coreFile == coreFile
    override fun hashCode() = game.hashCode() * 31 + coreFile.hashCode()
}

sealed interface GameUiState {
    /** Copying the game; [progress] 0…1 or null while unknown. */
    data class Preparing(val progress: Float?) : GameUiState
    /** The system's core is not installed yet: the user decides whether to load it. */
    data class NeedsCore(val game: PreparedGame, val sourceUrl: String?) : GameUiState
    data class DownloadingCore(val game: PreparedGame, val progress: Float?) : GameUiState
    data class Ready(val session: GameSession) : GameUiState
    data class Failed(val reason: GameFailure, val detail: String? = null) : GameUiState
}

enum class GameFailure { NOT_FOUND, UNKNOWN_SYSTEM, CORE_DOWNLOAD, CORE_INCOMPATIBLE, NO_ABI, START }

@HiltViewModel
class GamePlayerViewModel @Inject constructor(
    saved: SavedStateHandle,
    private val preparer: GameRomPreparer,
    private val cores: GameCoreManager,
    private val storage: GameStorage,
) : ViewModel() {
    private val fileId: String = checkNotNull(saved.get<String>("fileId")) { "fileId" }
    private val mutable = MutableStateFlow<GameUiState>(GameUiState.Preparing(null))
    val state: StateFlow<GameUiState> = mutable.asStateFlow()
    private val quick = MutableStateFlow(false)
    /** A quick save exists for this game. */
    val hasQuickState: StateFlow<Boolean> = quick.asStateFlow()
    private var job: Job? = null

    init { load() }

    fun retry() = load()

    private fun load() {
        job?.cancel()
        job = viewModelScope.launch {
            mutable.value = GameUiState.Preparing(null)
            val game = try {
                preparer.prepare(fileId) { progress -> mutable.value = GameUiState.Preparing(progress) }
            } catch (error: UnknownGameException) {
                mutable.value = GameUiState.Failed(GameFailure.UNKNOWN_SYSTEM, error.name); return@launch
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                mutable.value = GameUiState.Failed(GameFailure.NOT_FOUND, error.message); return@launch
            }
            if (cores.abi == null) { mutable.value = GameUiState.Failed(GameFailure.NO_ABI); return@launch }
            val core = cores.installed(game.system.core)
            if (core == null) mutable.value = GameUiState.NeedsCore(game, cores.sourceUrl(game.system.core))
            else start(game, core)
        }
    }

    /** The user agreed to load the core for this system. */
    fun downloadCore() {
        val game = (state.value as? GameUiState.NeedsCore)?.game ?: return
        job?.cancel()
        job = viewModelScope.launch {
            mutable.value = GameUiState.DownloadingCore(game, null)
            val file = try {
                cores.download(game.system.core) { progress -> mutable.value = GameUiState.DownloadingCore(game, progress) }
            } catch (error: GameCoreIncompatibleException) {
                mutable.value = GameUiState.Failed(GameFailure.CORE_INCOMPATIBLE, error.message); return@launch
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                mutable.value = GameUiState.Failed(GameFailure.CORE_DOWNLOAD, error.message); return@launch
            }
            start(game, file)
        }
    }

    private suspend fun start(game: PreparedGame, core: File) {
        val session = withContext(Dispatchers.IO) {
            GameSession(
                game = game,
                coreFile = core,
                systemDirectory = storage.systemDirectory,
                savesDirectory = storage.savesDirectory,
                sram = storage.read(storage.sramFile(game)),
                autoState = storage.read(storage.stateFile(game, GameStateSlot.AUTO)),
            )
        }
        quick.value = storage.stateFile(game, GameStateSlot.QUICK).isFile
        mutable.value = GameUiState.Ready(session)
    }

    /** The core failed to load or crashed while starting. */
    fun failed(detail: String?) {
        mutable.value = GameUiState.Failed(GameFailure.START, detail)
    }

    /** Removes the core of the current game (e.g. after it failed) so it can be loaded again. */
    fun removeCore(core: GameCore) = cores.remove(core)

    fun saveSram(game: PreparedGame, bytes: ByteArray) = storage.write(storage.sramFile(game), bytes)

    fun saveState(game: PreparedGame, slot: GameStateSlot, bytes: ByteArray) {
        storage.write(storage.stateFile(game, slot), bytes)
        if (slot == GameStateSlot.QUICK) quick.value = true
    }

    fun readState(game: PreparedGame, slot: GameStateSlot): ByteArray? = storage.read(storage.stateFile(game, slot))

    /** "Start over": the next start does not continue from the automatic save. */
    fun clearAutoState(game: PreparedGame) {
        storage.stateFile(game, GameStateSlot.AUTO).delete()
    }
}
