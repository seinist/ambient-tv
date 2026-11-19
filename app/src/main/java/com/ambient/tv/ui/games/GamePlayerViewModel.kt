package com.ambient.tv.ui.games

import android.graphics.Bitmap
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ambient.tv.data.files.FileStore
import com.ambient.tv.games.GameBiosManager
import com.ambient.tv.games.GameBiosStatus
import com.ambient.tv.games.GameCore
import com.ambient.tv.games.GameCoreIncompatibleException
import com.ambient.tv.games.GameCoreManager
import com.ambient.tv.games.GameRomPreparer
import com.ambient.tv.games.GameStateEntry
import com.ambient.tv.games.GameStateKind
import com.ambient.tv.games.GameStateStore
import com.ambient.tv.games.GameStorage
import com.ambient.tv.games.GameSystems
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
    val coreStamp: String,
    val systemDirectory: File,
    val savesDirectory: File,
    val sram: ByteArray?,
    /** The automatic state to continue from, if its core build matches. */
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

enum class GameFailure { NOT_FOUND, UNKNOWN_SYSTEM, CORE_DOWNLOAD, CORE_INCOMPATIBLE, NO_ABI, BIOS_MISSING, START }

@HiltViewModel
class GamePlayerViewModel @Inject constructor(
    saved: SavedStateHandle,
    private val preparer: GameRomPreparer,
    private val cores: GameCoreManager,
    private val storage: GameStorage,
    private val states: GameStateStore,
    private val bios: GameBiosManager,
    private val files: FileStore,
    val retroAchievements: com.ambient.tv.games.RetroAchievementsClient,
    private val cheatStore: com.ambient.tv.games.GameCheatStore,
) : ViewModel() {
    private val fileId: String = checkNotNull(saved.get<String>("fileId")) { "fileId" }
    private val mutable = MutableStateFlow<GameUiState>(GameUiState.Preparing(null))
    val state: StateFlow<GameUiState> = mutable.asStateFlow()
    private val stateList = MutableStateFlow<List<GameStateEntry>>(emptyList())
    /** Save states of the running game: automatic, undo, then the user's newest first. */
    val saveStates: StateFlow<List<GameStateEntry>> = stateList.asStateFlow()
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
            bios.missingRequired(game.system, GameSystems.extension(game.file.name))?.let { missing ->
                mutable.value = GameUiState.Failed(GameFailure.BIOS_MISSING, missing.file); return@launch
            }
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
                cores.download(game.system.core, com.ambient.tv.games.GameSettings.state.coreSource) { progress -> mutable.value = GameUiState.DownloadingCore(game, progress) }
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
        val stamp = GameStateStore.coreStamp(core)
        val session = withContext(Dispatchers.IO) {
            runCatching { files.record(game.entry) }
            val auto = states.entry(game, GameStateKind.AUTO)?.takeIf { it.coreStamp == stamp }
            GameSession(
                game = game,
                coreFile = core,
                coreStamp = stamp,
                systemDirectory = storage.systemDirectory,
                savesDirectory = storage.savesDirectory,
                sram = storage.read(storage.sramFile(game)),
                autoState = auto?.let { states.read(game, it) },
            )
        }
        refreshStates(game)
        mutable.value = GameUiState.Ready(session)
    }

    private fun refreshStates(game: PreparedGame) {
        stateList.value = states.list(game)
    }

    fun biosStatus(game: PreparedGame): List<GameBiosStatus> = bios.status(game.system)

    /** The core failed to load or crashed while starting. */
    fun failed(detail: String?) {
        mutable.value = GameUiState.Failed(GameFailure.START, detail)
    }

    /** Removes a core (e.g. after it failed) so the next start loads it again. */
    fun removeCore(core: GameCore) = cores.remove(core)

    fun cheats(game: PreparedGame): List<com.ambient.tv.games.GameCheat> = cheatStore.load(game)

    fun saveCheats(game: PreparedGame, cheats: List<com.ambient.tv.games.GameCheat>) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) { cheatStore.save(game, cheats) }
    }

    suspend fun databaseCheats(game: PreparedGame): List<com.ambient.tv.games.GameCheat> = cheatStore.fromDatabase(game)

    /** Remembers what [core] declares, so settings can offer its options outside a game. */
    fun saveDeclaredOptions(core: GameCore, options: List<Pair<String, String?>>) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) { cores.saveDeclaredOptions(core, options) }
    }

    fun saveSram(game: PreparedGame, bytes: ByteArray) = storage.write(storage.sramFile(game), bytes)

    fun saveState(session: GameSession, kind: GameStateKind, bytes: ByteArray, screenshot: Bitmap?): GameStateEntry {
        val entry = states.save(session.game, kind, bytes, screenshot, session.coreStamp)
        refreshStates(session.game)
        return entry
    }

    fun readState(game: PreparedGame, entry: GameStateEntry): ByteArray? = states.read(game, entry)

    fun screenshot(game: PreparedGame, entry: GameStateEntry): File? = states.screenshot(game, entry)

    fun latestManualState(game: PreparedGame): GameStateEntry? = states.latestManual(game)

    fun deleteState(game: PreparedGame, entry: GameStateEntry) {
        states.delete(game, entry)
        refreshStates(game)
    }

    /** "Start over": the next start does not continue from the automatic state. */
    fun clearAutoState(game: PreparedGame) {
        states.entry(game, GameStateKind.AUTO)?.let { states.delete(game, it) }
        refreshStates(game)
    }
}
