package com.ambient.tv.ui.player.backend

import androidx.annotation.Keep

/** A process-wide native gate also stops filters which are already running. Default is OFF. */
@Keep
internal object SvpNative {
    private var loaded = false
    private var attempted = false

    @Synchronized
    fun updateGate(allowed: Boolean): Boolean {
        // Ordinary playback never loads this optional native library.
        if (allowed && !attempted) {
            attempted = true
            loaded = runCatching { System.loadLibrary("ambientsvp"); abi() == 1 }.getOrDefault(false)
        }
        if (loaded) setEnabled(allowed)
        return allowed && loaded
    }

    private external fun abi(): Int
    @Synchronized
    fun isEnabledAndAvailable(): Boolean = loaded && enabled()

    private external fun setEnabled(enabled: Boolean)
    /** Process-wide diagnostics; called only after updateGate confirmed the library. */
    external fun enabled(): Boolean
    external fun interpolatedFrames(): Long
    external fun gpuFrames(): Long
}
