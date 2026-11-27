package com.ambient.tv.ui.navigation

import com.ambient.tv.data.files.FileKeys
import com.ambient.tv.data.files.FileSource
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.data.local.MediaSelectionPreferences
import com.ambient.tv.data.local.TokenStore
import com.ambient.tv.games.GameCoreExperiment
import com.ambient.tv.games.library.LibraryGame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.security.MessageDigest

/** Immutable configuration, including a removed/re-added source and changed credentials. */
internal data class GameSelectionSource(val source: FileSource, val identity: String)

/** Home carries an encoded catalog ID and its console; never treat it as a video media ID. */
internal fun gameSelectionFileId(homeKey: String): String? {
    val prefix = com.ambient.tv.ui.home.GAME_HOME_ITEM_PREFIX
    if (!homeKey.startsWith(prefix)) return null
    val payload = homeKey.removePrefix(prefix)
    val system = payload.substringAfterLast('|', "")
    if (com.ambient.tv.games.GameSystems.byId(system) == null) return null
    return android.net.Uri.decode(payload.substringBeforeLast('|', "")).takeIf(String::isNotBlank)
}

internal fun gameSelectionSource(
    preferences: AppPreferences,
    tokens: TokenStore,
    fileId: String,
): GameSelectionSource? {
    val sourceId = runCatching { FileKeys.parse(fileId).first }.getOrNull() ?: return null
    return synchronized(preferences.fileSourceLock) {
        // Games-only folders deliberately remain playable when Files is hidden or disabled.
        val source = preferences.fileSources.firstOrNull { it.id == sourceId } ?: return@synchronized null
        val credentials = tokens.smbPasswordForSource(sourceId).orEmpty() + '\u0000' +
            tokens.sshKeyForSource(sourceId).orEmpty()
        val identity = java.util.Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(credentials.toByteArray())) + ":" +
            preferences.filePlaybackScopes[sourceId].orEmpty()
        GameSelectionSource(source, identity)
    }
}

internal enum class GameSelectionPhase { RESOLVING, OPTIONS, CHECKING, WAKE, LAUNCHING, FAILED }
internal enum class GameSelectionFailure { MISSING, UNAVAILABLE }

internal data class GameSelectionUiState(
    val phase: GameSelectionPhase,
    val game: LibraryGame? = null,
    val sourceName: String = "",
    val failure: GameSelectionFailure? = null,
)

private data class GameLaunchIdentity(
    val fileId: String,
    val folderId: String,
    val sourceId: String,
    val path: String,
    val systemId: String,
)

private fun LibraryGame.launchIdentity() = GameLaunchIdentity(fileId, folderId, sourceId, path, systemId)

/**
 * One selection from Home, the game library or a game's Play action. Details and the Ask
 * dialog read only the local catalog. Source access starts only after an explicit Play.
 * The composition owns its scope and cancels on navigation, backgrounding and disposal.
 */
internal class GameSelectionCoordinator(
    private val scope: CoroutineScope,
    private val origin: () -> String?,
    private val source: (String) -> GameSelectionSource?,
    private val lookup: suspend (String) -> LibraryGame?,
    private val needsWake: suspend (String) -> Boolean,
    private val wake: suspend (String) -> Boolean,
    private val onNewSelection: () -> Unit = {},
    private val openDetails: (LibraryGame) -> Unit,
    /** The target picker may defer playback. [complete] validates again before navigation. */
    private val launch: (game: LibraryGame, complete: (() -> Unit) -> Unit) -> Unit,
    /** Like [launch], but the game starts over instead of continuing its automatic state. */
    private val launchFresh: ((game: LibraryGame, complete: (() -> Unit) -> Unit) -> Unit)? = null,
    private val launchExperiment: ((LibraryGame, GameCoreExperiment, (() -> Unit) -> Unit) -> Unit)? = null,
) {
    private class Request(
        val fileId: String,
        val origin: String?,
        val source: GameSelectionSource?,
        val behavior: String,
        var fresh: Boolean = false,
        val experiment: GameCoreExperiment? = null,
        var game: LibraryGame? = null,
        var completing: Boolean = false,
    )

    private var active: Request? = null
    private var job: Job? = null
    private val mutable = MutableStateFlow<GameSelectionUiState?>(null)
    val state = mutable.asStateFlow()

    fun cancel() {
        active = null
        job?.cancel()
        job = null
        mutable.value = null
    }

    /** Back or another page must retire a pending wake or target-picker callback. */
    fun navigationChanged() {
        active?.let { if (it.origin != origin()) cancel() }
    }

    fun sourceChanged() { active?.let(::current) }

    fun select(fileId: String, behavior: String, fresh: Boolean = false, experiment: GameCoreExperiment? = null) {
        val currentSource = source(fileId)
        val normalizedBehavior = MediaSelectionPreferences.normalize(behavior)
        if (active?.let { it.fileId == fileId && it.origin == origin() && it.source == currentSource &&
                it.behavior == normalizedBehavior && it.fresh == fresh && it.experiment == experiment } == true) return
        // Only a new intent retires other media; repeated OK must keep its own launch gate.
        onNewSelection()
        cancel()
        val request = Request(fileId, origin(), currentSource, normalizedBehavior, fresh, experiment)
        active = request
        mutable.value = GameSelectionUiState(GameSelectionPhase.RESOLVING)
        job = scope.launch {
            try {
                val game = refresh(request) ?: return@launch
                when (request.behavior) {
                    MediaSelectionPreferences.PLAY -> prepare(request, allowWake = false, selected = game)
                    MediaSelectionPreferences.ASK -> show(request, GameSelectionPhase.OPTIONS)
                    else -> finish(request) { openDetails(game) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { fail(request, GameSelectionFailure.UNAVAILABLE) }
        }
    }

    fun play(fileId: String, experiment: GameCoreExperiment? = null) =
        select(fileId, MediaSelectionPreferences.PLAY, experiment = experiment)

    /** Starts over: the automatic state is kept only as the undo state, like a reset in the game. */
    fun playFresh(fileId: String) = select(fileId, MediaSelectionPreferences.PLAY, fresh = true)
    fun details(fileId: String) = select(fileId, MediaSelectionPreferences.DETAILS)

    /** Options, a failed attempt and a wake offer all keep the same selected game. */
    fun playPending(allowWake: Boolean = false, fresh: Boolean? = null) {
        val request = active ?: return
        if (mutable.value?.phase !in setOf(GameSelectionPhase.OPTIONS, GameSelectionPhase.WAKE, GameSelectionPhase.FAILED)) return
        if (!current(request)) return
        // Chosen in the options; a retry or wake afterwards keeps that choice.
        fresh?.let { request.fresh = it }
        job?.cancel()
        show(request, GameSelectionPhase.CHECKING)
        job = scope.launch {
            try { prepare(request, allowWake) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { fail(request, GameSelectionFailure.UNAVAILABLE) }
        }
    }

    fun detailsPending() {
        val request = active ?: return
        if (mutable.value?.phase != GameSelectionPhase.OPTIONS || !current(request)) return
        job?.cancel()
        show(request, GameSelectionPhase.RESOLVING)
        job = scope.launch {
            try {
                val game = refresh(request) ?: return@launch
                finish(request) { openDetails(game) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { fail(request, GameSelectionFailure.UNAVAILABLE) }
        }
    }

    private fun current(request: Request): Boolean {
        if (active !== request) return false
        if (request.origin == origin() && request.source == source(request.fileId)) return true
        cancel()
        return false
    }

    private fun show(request: Request, phase: GameSelectionPhase, failure: GameSelectionFailure? = null) {
        if (current(request)) mutable.value = GameSelectionUiState(
            phase, request.game, request.source?.source?.name.orEmpty(), failure)
    }

    private fun fail(request: Request, reason: GameSelectionFailure) = show(request, GameSelectionPhase.FAILED, reason)

    private suspend fun refresh(request: Request): LibraryGame? {
        if (!current(request)) return null
        val game = lookup(request.fileId)
        if (!current(request)) return null
        if (game == null) {
            fail(request, GameSelectionFailure.MISSING)
            return null
        }
        // Metadata/favorite updates are harmless; a different ROM assignment is a new intent.
        if (game.fileId != request.fileId || request.game?.launchIdentity()?.let { it != game.launchIdentity() } == true) {
            fail(request, GameSelectionFailure.MISSING)
            return null
        }
        request.game = game
        return game
    }

    private suspend fun prepare(request: Request, allowWake: Boolean, selected: LibraryGame? = null) {
        val game = selected ?: refresh(request) ?: return
        if (request.source == null || request.source.source.id != game.sourceId) {
            fail(request, GameSelectionFailure.UNAVAILABLE)
            return
        }
        show(request, GameSelectionPhase.CHECKING)
        // A server may already be back while its offer is open. Probe again before waking it.
        val requiresWake = request.source.source.network && needsWake(game.sourceId)
        if (request.source.source.network && refresh(request) == null) return
        if (requiresWake && !allowWake) {
            show(request, GameSelectionPhase.WAKE)
            return
        }
        if (requiresWake) {
            val awake = wake(game.sourceId)
            if (refresh(request) == null) return
            if (!awake) {
                fail(request, GameSelectionFailure.UNAVAILABLE)
                return
            }
        }
        show(request, GameSelectionPhase.LAUNCHING)
        if (!current(request)) return
        val experiment = request.experiment
        if (experiment != null) {
            val start = launchExperiment ?: return fail(request, GameSelectionFailure.UNAVAILABLE)
            start(requireNotNull(request.game), experiment) { navigate -> complete(request, navigate) }
            return
        }
        val start = launchFresh?.takeIf { request.fresh } ?: launch
        start(requireNotNull(request.game)) { navigate -> complete(request, navigate) }
    }

    /** Validate both source and catalog after any deferred output-target selection. */
    private fun complete(request: Request, navigate: () -> Unit) {
        if (!current(request) || request.completing || mutable.value?.phase != GameSelectionPhase.LAUNCHING) return
        request.completing = true
        job = scope.launch {
            try {
                if (refresh(request) != null) finish(request, navigate)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { fail(request, GameSelectionFailure.UNAVAILABLE) }
            finally { request.completing = false }
        }
    }

    private fun finish(request: Request, action: () -> Unit) {
        if (!current(request)) return
        active = null
        mutable.value = null
        action()
    }
}
