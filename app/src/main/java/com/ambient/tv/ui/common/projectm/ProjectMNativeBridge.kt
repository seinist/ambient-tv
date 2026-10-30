package com.ambient.tv.ui.common.projectm

internal object ProjectMNativeBridge {
    external fun nativeCreate(): Long
    external fun nativeSetAudioSource(handle: Long, source: Int)
    external fun nativeDestroy(handle: Long)
    external fun nativeResize(handle: Long, width: Int, height: Int, meshX: Int, meshY: Int)
    external fun nativeRender(handle: Long)
    external fun nativeFeedPcm(handle: Long, samples: FloatArray)
    external fun nativeLoadPreset(handle: Long, presetData: String, smooth: Boolean)
    external fun nativeSetTexturePath(handle: Long, path: String)
    external fun nativeSetPresetDuration(handle: Long, seconds: Double)
    external fun nativeSetSoftCutDuration(handle: Long, seconds: Double)
    external fun nativeSetBeatSensitivity(handle: Long, sensitivity: Float)
    external fun nativeSetLocked(handle: Long, locked: Boolean)
    external fun nativeVersion(): String
}

internal data class ProjectMRuntimeStatus(
    val available: Boolean,
    val version: String? = null,
    val error: String? = null,
)

internal fun probeProjectMRuntime(
    abiSupported: Boolean,
    loadLibrary: (String) -> Unit,
    readVersion: () -> String,
): ProjectMRuntimeStatus {
    if (!abiSupported) return ProjectMRuntimeStatus(false, error = "unsupported ABI")
    // projectM itself uses libc++_shared. Android normally resolves it transitively, but explicitly
    // loading the packaged runtime first also covers devices whose linker namespace does not make
    // that dependency visible until another native engine has run.
    runCatching { loadLibrary("c++_shared") }
    return runCatching {
        loadLibrary("projectM-4")
        loadLibrary("ambientprojectm")
        readVersion().trim().takeIf(String::isNotBlank)
            ?.let { ProjectMRuntimeStatus(true, version = it) }
            ?: ProjectMRuntimeStatus(false, error = "native version probe returned empty")
    }.getOrElse { error ->
        ProjectMRuntimeStatus(
            available = false,
            error = buildString {
                append(error.javaClass.simpleName)
                error.message?.takeIf(String::isNotBlank)?.let { append(": "); append(it) }
            },
        )
    }
}

object ProjectMAvailability {
    /** Configuration is meaningful for every ABI the app ships projectM for, even if this run's
     * native probe failed. That keeps the handheld UI reachable and lets playback fall back safely. */
    val canConfigure: Boolean
        get() = com.ambient.tv.ui.player.backend.NativeArmRuntime.isSupportedDevice

    private val status: ProjectMRuntimeStatus by lazy {
        probeProjectMRuntime(
            abiSupported = canConfigure,
            loadLibrary = System::loadLibrary,
            readVersion = ProjectMNativeBridge::nativeVersion,
        ).also { result ->
            if (result.available) {
                timber.log.Timber.i("projectM native runtime %s (%s)",
                    result.version, com.ambient.tv.ui.player.backend.NativeArmRuntime.selectedAbi)
            } else {
                timber.log.Timber.w("projectM native runtime unavailable on %s: %s",
                    com.ambient.tv.ui.player.backend.NativeArmRuntime.selectedAbi ?: "unsupported ABI",
                    result.error ?: "unknown error")
            }
        }
    }

    val isAvailable: Boolean get() = status.available
    val version: String? get() = status.version
    val unavailableReason: String? get() = status.error
}
