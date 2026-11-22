package com.ambient.tv.ui.games

import android.util.Log
import java.util.concurrent.Executors

/**
 * Captured bytes survive leaving the player. One process-wide writer preserves checkpoint
 * order across rapid Home/resume/exit and across two sessions of the same game.
 * It never owns a core or a Surface: all native capture finishes on the render thread first.
 */
internal object GameCheckpointIo {
    private val queue = GameCheckpointQueue(
        writer = Executors.newSingleThreadExecutor { task ->
            Thread(task, "GameCheckpointIO").apply { isDaemon = true }
        },
        onFailure = { Log.w("GameCheckpointIO", "Could not persist game checkpoint", it) },
    )

    fun <T> reserve(write: (T) -> Unit): GameCheckpointTicket<T> = queue.reserve(write)

    fun execute(write: () -> Unit) = queue.execute(write)

    /** Called only from the prepare-game IO dispatcher before reading a previous session. */
    fun awaitPendingWrites() = queue.awaitPendingWrites()
}
