package com.ambient.tv.ui.player.backend

import java.io.File

/**
 * Native GPU program binaries belong in Android's disposable app cache, separately from the
 * materialized GLSL sources. libmpv's config=no default leaves its automatic cache path unset.
 * An unavailable cache must not prevent playback; mpv can always compile from the sources.
 */
internal fun mpvGpuShaderCacheDirectory(appCacheDirectory: File): File? = runCatching {
    val directory = File(appCacheDirectory, "mpv-gpu-shaders")
    if (!directory.isDirectory) directory.mkdirs()
    directory.takeIf { it.isDirectory && it.canWrite() }
}.getOrNull()
