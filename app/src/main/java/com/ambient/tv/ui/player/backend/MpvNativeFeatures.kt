package com.ambient.tv.ui.player.backend

/**
 * Patch markers of the bundled native libraries, read at runtime from libambientmpv
 * ([MpvNativeBridge.nativeFeatures]). The Kotlin code may be newer than the prebuilt libraries
 * (jniLibs are not versioned with the sources); a feature only counts when the loaded libraries
 * really contain its patch. Older libraries without the JNI entry report none: the old
 * behaviour applies (`dolbyvision=1`, no `hw_min_height`, no EGL PQ route).
 */
internal object MpvNativeFeatures {
    /** libavcodec mediacodec `dolbyvision` accepts 2 (base-layer routing, ffmpeg-dolby-vision-routing.patch). */
    const val DV_ROUTING = "ffmpeg-dv-routing"
    /** libavcodec mediacodec `hw_min_height` (ffmpeg-mediacodec-sd-software.patch). */
    const val SD_SOFTWARE = "ffmpeg-sd-software"
    /** libavcodec mediacodec `color_info` (ffmpeg-mediacodec-color-info.patch). */
    const val COLOR_INFO = "ffmpeg-color-info"
    /** libmpv tags its EGL window surface BT2020_PQ (mpv-android-egl-hdr-surface.patch). */
    const val EGL_HDR_SURFACE = "mpv-egl-hdr-surface"

    val current: Set<String> by lazy {
        if (!MpvBackendAvailability.nativeRuntimeAvailable) return@lazy emptySet()
        // UnsatisfiedLinkError (an older libambientmpv) is caught: no features.
        runCatching { parseMpvNativeFeatures(MpvNativeBridge.nativeFeatures()) }
            .onFailure { timber.log.Timber.w("MPV native features unavailable (%s); old option set", it.javaClass.simpleName) }
            .getOrDefault(emptySet())
            .also { timber.log.Timber.i("MPV native features %s", it.sorted()) }
    }
}

internal fun parseMpvNativeFeatures(raw: String?): Set<String> =
    raw.orEmpty().split(',', ' ', '\n').map(String::trim).filter(String::isNotEmpty).toSet()
