package com.ambient.tv.ui.games

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ambient.tv.games.GameSystems
import com.ambient.tv.games.PreparedGame
import com.ambient.tv.games.RaAchievement
import com.ambient.tv.games.RaConsoles
import com.ambient.tv.games.RaGame
import com.ambient.tv.games.RetroAchievementsClient
import com.swordfish.libretrodroid.AchievementDef
import com.swordfish.libretrodroid.GLRetroView
import com.swordfish.libretrodroid.LibretroDroid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/** Where the achievements of the running game stand. */
sealed interface GameAchievementState {
    /** Switched off, no account, or a system RetroAchievements does not cover. */
    data object Off : GameAchievementState
    data object Connecting : GameAchievementState
    /** RetroAchievements does not know this dump. */
    data object Unknown : GameAchievementState
    data class Active(val game: RaGame, val hardcore: Boolean) : GameAchievementState
    data class Failed(val message: String) : GameAchievementState
}

/**
 * RetroAchievements for one game: finds it by its ROM hash, starts a session (hardcore when asked
 * for), hands the not yet unlocked achievements to rcheevos in LibretroDroid and reports unlocks.
 * State is Compose state, changed on the main thread.
 */
class GameAchievements(private val client: RetroAchievementsClient, private val scope: CoroutineScope) {
    var state by mutableStateOf<GameAchievementState>(GameAchievementState.Off)
        private set
    var unlocked by mutableStateOf<Set<Long>>(emptySet())
        private set
    /** The achievement just unlocked, shown over the game for a few seconds. */
    var banner by mutableStateOf<RaAchievement?>(null)
        private set

    private var hardcoreRequested by mutableStateOf(false)

    /** Hardcore rules apply (also while connecting for hardcore): no loading states, no rewind, no cheats. */
    val hardcore: Boolean
        get() = when (val current = state) {
            is GameAchievementState.Active -> current.hardcore
            GameAchievementState.Connecting -> hardcoreRequested
            else -> false
        }
    private var job: Job? = null

    fun start(view: GLRetroView, game: PreparedGame, enabled: Boolean, hardcoreRequested: Boolean) {
        job?.cancel()
        val account = client.account
        val console = RaConsoles.forGame(game.system, GameSystems.extension(game.file.name))
        if (!enabled || account == null || console == null) {
            state = GameAchievementState.Off
            return
        }
        this.hardcoreRequested = hardcoreRequested
        state = GameAchievementState.Connecting
        job = scope.launch {
            try {
                val hash = withContext(Dispatchers.IO) { RetroCoreRuntime.core.computeRomHash(game.file.absolutePath, console) }
                    ?.takeIf { it.isNotBlank() }
                val gameId = hash?.let { client.gameId(it) }
                if (hash == null || gameId == null) {
                    state = GameAchievementState.Unknown
                    return@launch
                }
                val session = client.startSession(account, gameId, hash, hardcoreRequested)
                val raGame = client.game(account, gameId)
                val done = if (hardcoreRequested) session.hardcoreUnlocked else session.unlocked
                val watchable = RetroAchievementsClient.watchable(raGame)
                unlocked = done.intersect(watchable.map { it.id }.toSet())
                val armed = watchable.filter { it.id !in done }
                    .map { AchievementDef(it.id, it.memAddr) }.toTypedArray()
                view.achievementUnlockListener = { id -> scope.launch { onUnlocked(id) } }
                val consoleId = raGame.consoleId.takeIf { it > 0 } ?: console
                if (armed.isNotEmpty()) view.queueEvent { runCatching { LibretroDroid.initAchievements(armed, consoleId) } }
                state = GameAchievementState.Active(raGame.copy(achievements = watchable), hardcoreRequested)
                while (isActive) {
                    delay(PING_MS)
                    client.ping(account, gameId)
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                Timber.w(error, "RetroAchievements session failed")
                state = GameAchievementState.Failed(error.message.orEmpty())
            }
        }
    }

    private suspend fun onUnlocked(id: Long) {
        val active = state as? GameAchievementState.Active ?: return
        if (id in unlocked) return
        val achievement = active.game.achievements.firstOrNull { it.id == id } ?: return
        unlocked = unlocked + id
        banner = achievement
        scope.launch {
            delay(BANNER_MS)
            if (banner?.id == id) banner = null
        }
        val account = client.account ?: return
        // A failed report is tried again a few times; the unlock itself stays shown.
        repeat(3) { attempt ->
            if (runCatching { client.award(account, id, active.hardcore) }.getOrDefault(false)) return
            delay(5_000L * (attempt + 1))
        }
    }

    fun stop(view: GLRetroView) {
        job?.cancel()
        view.achievementUnlockListener = null
        if (state is GameAchievementState.Active) view.queueEvent { runCatching { RetroCoreRuntime.core.clearAchievements() } }
    }

    private companion object {
        const val PING_MS = 120_000L
        const val BANNER_MS = 5_000L
    }
}
