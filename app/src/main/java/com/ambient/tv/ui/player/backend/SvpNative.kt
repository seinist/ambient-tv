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
    private var selectedPath: String? = null

    @Synchronized
    fun sharedNativePath(): String? = if (probe()) selectedPath else null

    /** Probe the selected engine without enabling frame interpolation. */
    @Synchronized
    fun probe(): Boolean {
        if (!attempted) {
            var external = false
            attempted = true
            loaded = runCatching {
                val selected = com.ambient.tv.modules.SystemModuleHost.loadSelected(com.ambient.tv.modules.SystemModule.SVP)
                external = selected != null
                if (selected == null) {
                    if (!com.ambient.tv.modules.SystemModuleAvailability.bundled(com.ambient.tv.modules.SystemModule.SVP))
                        return@runCatching false
                    System.loadLibrary("ambientsvp")
                }
                selectedPath = selected?.nativeDirectory?.resolve("libambientsvp.so")?.absolutePath ?: "libambientsvp.so"
                val native = abi()
                if (native != SVP_NATIVE_ABI) {
                    timber.log.Timber.w("SVP disabled: libambientsvp ABI %d, app expects %d (rebuild native libs together)", native, SVP_NATIVE_ABI)
                }
                native == SVP_NATIVE_ABI
            }.onFailure { timber.log.Timber.w(it, "SVP runtime unavailable") }.getOrDefault(false)
            com.ambient.tv.modules.SystemModuleAvailability.reportProbe(
                com.ambient.tv.modules.SystemModule.SVP, external, loaded,
            )
        }
        return loaded
    }

    @Synchronized
    fun updateGate(allowed: Boolean): Boolean {
        if (allowed && !attempted) probe()
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
