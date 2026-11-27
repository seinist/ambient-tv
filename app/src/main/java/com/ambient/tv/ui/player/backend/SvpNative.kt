package com.ambient.tv.ui.player.backend

import androidx.annotation.Keep
import android.content.Context
import android.system.Os
import java.io.File

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
    private var cacheAttempted = false

    @Synchronized
    fun updateGate(allowed: Boolean, context: Context? = null): Boolean {
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
        if (allowed && loaded && context != null && !cacheAttempted) {
            cacheAttempted = true
            // Never use external storage or accept an imported pipeline blob.
            // Older ABI-compatible engines may lack the optional JNI method.
            runCatching {
                val dir = File(context.applicationContext.cacheDir.canonicalFile, "svp-pipelines-v1")
                if (!dir.exists()) dir.mkdir()
                if (dir.isDirectory && dir.canonicalFile == dir.absoluteFile) {
                    Os.chmod(dir.path, 448) // 0700
                    setCacheDirectory(dir.path.toByteArray(Charsets.UTF_8))
                }
            }.onFailure { timber.log.Timber.d(it, "SVP disk pipeline cache unavailable") }
        }
        if (loaded) setEnabled(allowed)
        return allowed && loaded
    }

    private external fun setCacheDirectory(path: ByteArray): Boolean
    private external fun abi(): Int
    @Synchronized
    fun isEnabledAndAvailable(): Boolean = loaded && enabled()

    private external fun setEnabled(enabled: Boolean)
    /** Process-wide diagnostics; called only after updateGate confirmed the library. */
    external fun enabled(): Boolean
    external fun interpolatedFrames(): Long
    external fun gpuFrames(): Long
    private external fun residentOutputs(): Long

    /** Missing entry means an older engine; never enable its experimental transport. */
    @Synchronized
    fun residentOutputsOrNull(): Long? = if (loaded) runCatching { residentOutputs() }.getOrNull() else null

    /** Process-wide synthesized frames, or null while the library is not loaded. */
    @Synchronized
    fun interpolatedFramesOrNull(): Long? = if (loaded) runCatching { interpolatedFrames() }.getOrNull() else null
}
