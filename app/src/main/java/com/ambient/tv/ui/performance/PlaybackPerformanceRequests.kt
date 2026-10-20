package com.ambient.tv.ui.performance

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * One-tap playback performance switch from the player (e.g. "Leistung auf Ausgewogen" while
 * Sparsam overrides the saved image settings). The activity owns the resolved profile, so it
 * persists the key and updates [LocalPerformanceProfile]; the running player then re-applies
 * its image options through its existing gate effect.
 */
object PlaybackPerformanceRequests {
    private val mutable = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val requests: SharedFlow<String> = mutable.asSharedFlow()

    fun request(modeKey: String): Boolean =
        modeKey in PerformanceMode.KEYS && mutable.tryEmit(modeKey)
}

/** Lightest playback mode that lifts the Sparsam gates; SVP additionally needs [PerformanceMode.MAXIMUM]. */
internal fun playbackModeLiftingSparsam(forSvp: Boolean): PerformanceMode =
    if (forSvp) PerformanceMode.MAXIMUM else PerformanceMode.BALANCED
