package com.ambient.tv.ui.player.backend

import android.opengl.EGL14
import timber.log.Timber

/** EGL extension that lets a window surface be tagged PQ/BT.2020 (Kodi's GLES HDR route). */
internal const val EGL_EXT_BT2020_PQ = "EGL_EXT_gl_colorspace_bt2020_pq"
/** Required for EGL_GL_COLORSPACE_KHR at all. */
internal const val EGL_KHR_GL_COLORSPACE = "EGL_KHR_gl_colorspace"

/** True when [extensions] (eglQueryString(EGL_EXTENSIONS)) names both colour-space extensions. */
internal fun eglHdr10PqFromExtensions(extensions: String?): Boolean {
    val names = extensions?.split(' ', '\t', '\n')?.filter { it.isNotBlank() }?.toSet() ?: return false
    return EGL_EXT_BT2020_PQ in names && EGL_KHR_GL_COLORSPACE in names
}

/**
 * Read-only EGL query on the default display: colour-space extensions plus a window-capable
 * RGB10_A2 config (mpv's egl-output-format=rgb10_a2 fails VO init without one).
 * Any failure counts as unsupported, so the HDR GPU option keeps today's direct route.
 */
internal object MpvEglHdrProbe {
    fun hdr10Pq(): Boolean = runCatching {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) return@runCatching false
        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) return@runCatching false
        val extensions = EGL14.eglQueryString(display, EGL14.EGL_EXTENSIONS)
        if (!eglHdr10PqFromExtensions(extensions)) {
            Timber.i("EGL HDR: no BT2020_PQ colour space (%s)", extensions?.take(400))
            return@runCatching false
        }
        val attributes = intArrayOf(
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_RED_SIZE, 10, EGL14.EGL_GREEN_SIZE, 10, EGL14.EGL_BLUE_SIZE, 10,
            EGL14.EGL_ALPHA_SIZE, 2,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<android.opengl.EGLConfig>(16)
        val count = IntArray(1)
        if (!EGL14.eglChooseConfig(display, attributes, 0, configs, 0, configs.size, count, 0)) return@runCatching false
        val exact = (0 until count[0]).any { index ->
            val config = configs[index] ?: return@any false
            listOf(EGL14.EGL_RED_SIZE to 10, EGL14.EGL_GREEN_SIZE to 10, EGL14.EGL_BLUE_SIZE to 10, EGL14.EGL_ALPHA_SIZE to 2)
                .all { (attribute, size) ->
                    val value = IntArray(1)
                    EGL14.eglGetConfigAttrib(display, config, attribute, value, 0) && value[0] == size
                }
        }
        Timber.i("EGL HDR: BT2020_PQ available, rgb10_a2 window config=%s", exact)
        exact
    }.getOrElse { Timber.w(it, "EGL HDR probe failed"); false }
}
