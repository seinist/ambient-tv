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

object ProjectMAvailability {
    val isAvailable: Boolean by lazy {
        runCatching {
            System.loadLibrary("projectM-4")
            System.loadLibrary("ambientprojectm")
            ProjectMNativeBridge.nativeVersion().isNotBlank()
        }.getOrDefault(false)
    }

    val version: String? get() = if (isAvailable) runCatching { ProjectMNativeBridge.nativeVersion() }.getOrNull() else null
}
