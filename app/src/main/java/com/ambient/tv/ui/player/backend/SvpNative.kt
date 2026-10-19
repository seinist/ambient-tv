package com.ambient.tv.ui.player.backend

import androidx.annotation.Keep

/**
 * Native configuration ABI shared by libambientsvp, the mpv/VLC adapters and this JNI check
 * (include/ambientsvp.h, src/lib.rs). A mismatch means the native libraries were not rebuilt
 * together; SVP then stays unavailable instead of reading a foreign config struct.
 */
internal const val SVP_NATIVE_ABI = 2

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
            loaded = runCatching {
                System.loadLibrary("ambientsvp")
                val native = abi()
                if (native != SVP_NATIVE_ABI) {
                    timber.log.Timber.w("SVP disabled: libambientsvp ABI %d, app expects %d (rebuild native libs together)", native, SVP_NATIVE_ABI)
                }
                native == SVP_NATIVE_ABI
            }.getOrDefault(false)
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

    /** Process-wide synthesized frames, or null while the library is not loaded. */
    @Synchronized
    fun interpolatedFramesOrNull(): Long? = if (loaded) runCatching { interpolatedFrames() }.getOrNull() else null
}
