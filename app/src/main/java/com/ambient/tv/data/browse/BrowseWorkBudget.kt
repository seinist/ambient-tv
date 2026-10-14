package com.ambient.tv.data.browse

import android.os.SystemClock
import kotlinx.coroutines.Job
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

    fun idle(): Boolean = foreground && !recentlyInteracting(1_500L) &&
        foregroundRequests.get() == 0 && !musicPlaying &&
        !com.ambient.tv.ui.player.BackgroundPlaybackSession.snapshot.value.active
}

object SpeculativeRequest : AbstractCoroutineContextElement(Key) {
    object Key : CoroutineContext.Key<SpeculativeRequest>
}
