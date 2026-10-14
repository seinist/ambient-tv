package com.ambient.tv.ui.player.backend

enum class PlaybackBackend(val key: String) {
    AUTO("auto"),
    MEDIA3("media3"),
    VLC("vlc"),
    MPV("mpv");

    companion object {
        fun fromKey(value: String?): PlaybackBackend = entries.firstOrNull { it.key == value } ?: AUTO
    }
}

object MpvBackendAvailability {
    val selectedAbi: String?
        get() = NativeArmRuntime.selectedAbi

    /** True when the native libmpv payload and JNI shim can be loaded for this process ABI. */
    val nativeRuntimeAvailable: Boolean by lazy {
        if (!NativeArmRuntime.isSupportedDevice) {
            false
        } else {
            runCatching {
                // Android resolves libmpv.so/libambientmpv.so from the matching ABI directory
                // (arm64-v8a, armeabi-v7a, x86 or x86_64). No manual library path is needed.
                System.loadLibrary("mpv")
                System.loadLibrary("ambientmpv")
                MpvNativeBridge.nativeVersion().isNotBlank()
            }.onFailure { timber.log.Timber.e(it, "MPV native runtime could not be loaded") }.getOrDefault(false)
        }
    }

    /**
     * Keep MPV disabled until the app-side controller/render surface is implemented.
     * Native libraries alone are not a usable playback backend.
     */
    val isAvailable: Boolean
        get() = nativeRuntimeAvailable && MpvNativeBridge.rendererImplemented
}

object PlaybackBackendResolver {
    fun resolve(requested: String?): PlaybackBackend = when (val backend = PlaybackBackend.fromKey(requested)) {
        PlaybackBackend.AUTO -> PlaybackBackend.MEDIA3
        PlaybackBackend.MPV -> if (MpvBackendAvailability.isAvailable) PlaybackBackend.MPV else PlaybackBackend.MEDIA3
        else -> backend
    }
}
