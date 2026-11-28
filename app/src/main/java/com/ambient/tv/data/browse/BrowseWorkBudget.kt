package com.ambient.tv.data.browse

import android.os.SystemClock
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Input cancels speculative work immediately. No decoder or UI object belongs in this budget. */
object BrowseWorkBudget {
    @Volatile var foreground = false
    @Volatile private var lastInputMs = 0L
    @Volatile var speculativeJob: Job? = null
    @Volatile var musicPlaying = false
    val scrolling = java.util.concurrent.atomic.AtomicInteger()
    val foregroundRequests = java.util.concurrent.atomic.AtomicInteger()
    fun interact() {
        lastInputMs = SystemClock.elapsedRealtime()
        speculativeJob?.cancel()
    }
    fun recentlyInteracting(quietMs: Long = 600L): Boolean =
        scrolling.get() > 0 || SystemClock.elapsedRealtime() - lastInputMs < quietMs

    // Capture before starting optional work and carry through network, parsing and storage.
    // A game that started and ended between two checks still invalidates the old work.
    fun gameEpoch(): Long = com.ambient.tv.ui.gamepad.GameInputRouting.gameStartEpoch.value

    fun gameFreeSince(epoch: Long): Boolean =
        !com.ambient.tv.ui.gamepad.GameInputRouting.active && gameEpoch() == epoch

    fun idleSince(epoch: Long): Boolean = idle() && gameFreeSince(epoch)

    /** Observe the start epoch, not just a Boolean: a very short game session still
     * invalidates older speculative work. Cancel blocking calls on IO, not the UI thread. */
    suspend fun <T> interruptOnGameStart(
        cancel: () -> Unit, epoch: Long = gameEpoch(), block: suspend () -> T,
    ): T = coroutineScope {
        val routing = com.ambient.tv.ui.gamepad.GameInputRouting
        check(gameFreeSince(epoch)) { "Game owns foreground" }
        val watcher = launch(Dispatchers.IO) {
            routing.gameStartEpoch.first { it != epoch }
            cancel()
        }
        try {
            val result = block()
            check(gameFreeSince(epoch)) { "Game interrupted speculative work" }
            result
        } finally {
            watcher.cancel()
        }
    }

    fun idle(): Boolean = foreground && !recentlyInteracting(1_500L) &&
        foregroundRequests.get() == 0 && !musicPlaying &&
        !com.ambient.tv.ui.gamepad.GameInputRouting.active &&
        !com.ambient.tv.ui.player.BackgroundPlaybackSession.snapshot.value.active
}

object SpeculativeRequest : AbstractCoroutineContextElement(Key) {
    object Key : CoroutineContext.Key<SpeculativeRequest>
}
