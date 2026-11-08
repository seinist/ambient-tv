@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.ambient.tv.ui.player

import android.content.Context
import android.opengl.EGL14
import android.opengl.GLES20
import androidx.media3.common.util.GlUtil
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import java.nio.ByteBuffer
import kotlin.math.abs

/**
 * Real-device offscreen GLES regression test. Runs production Anime4K multi-pass programs on
 * neutral and coloured frames, including fitted output and texture reconfiguration. Checking
 * every RGB channel catches a displayed CNN feature map, a colour cast and stale frame bindings.
 * Android/JVM unit tests cannot guarantee shader compilation on the target GPU.
 */
internal fun auditMedia3Anime4kShaders(context: Context, log: (String) -> Unit) {
    val egl = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    check(egl != EGL14.EGL_NO_DISPLAY)
    check(EGL14.eglInitialize(egl, IntArray(2), 0, IntArray(2), 0))
    val configurations = arrayOfNulls<android.opengl.EGLConfig>(1)
    val count = IntArray(1)
    check(EGL14.eglChooseConfig(egl, intArrayOf(
        EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
        EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
        EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
        EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
        EGL14.EGL_NONE), 0, configurations, 0, 1, count, 0) && count[0] > 0)
    val configuration = checkNotNull(configurations[0])
    var glContext = EGL14.eglCreateContext(egl, configuration, EGL14.EGL_NO_CONTEXT,
        intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
    if (glContext == EGL14.EGL_NO_CONTEXT) {
        glContext = EGL14.eglCreateContext(egl, configuration, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
    }
    val surface = EGL14.eglCreatePbufferSurface(egl, configuration,
        intArrayOf(EGL14.EGL_WIDTH, 64, EGL14.EGL_HEIGHT, 64, EGL14.EGL_NONE), 0)
    check(glContext != EGL14.EGL_NO_CONTEXT && surface != EGL14.EGL_NO_SURFACE)
    check(EGL14.eglMakeCurrent(egl, surface, surface, glContext))
    val frames = listOf(
        "dark gray" to intArrayOf(32, 32, 32),
        "gray" to intArrayOf(128, 128, 128),
        "red" to intArrayOf(210, 40, 24),
        "green" to intArrayOf(24, 200, 40),
        "blue" to intArrayOf(24, 40, 210),
        // Reusing the same texture after coloured frames also catches stale sampler bindings.
        "gray again" to intArrayOf(128, 128, 128),
    )
    var frameNumber = 0L

    fun renderFrames(
        program: BaseGlShaderProgram,
        label: String,
        width: Int,
        height: Int,
        expectedWidth: Int,
        expectedHeight: Int,
        probes: List<Pair<String, IntArray>> = frames,
    ) {
        val size = program.configure(width, height)
        check(size.width == expectedWidth && size.height == expectedHeight) {
            "$label output ${size.width}x${size.height}, expected ${expectedWidth}x$expectedHeight"
        }
        var input = 0
        var output = 0
        var fbo = 0
        try {
            input = GlUtil.createTexture(width, height, false)
            output = GlUtil.createTexture(expectedWidth, expectedHeight, false)
            fbo = GlUtil.createFboForTexture(output)
            val bytes = ByteBuffer.allocateDirect(width * height * 4)
            val actual = ByteBuffer.allocateDirect(expectedWidth * expectedHeight * 4)
            var maxError = 0
            for ((frameLabel, rgb) in probes) {
                bytes.clear()
                repeat(width * height) {
                    rgb.forEach { bytes.put(it.toByte()) }
                    bytes.put(255.toByte())
                }
                bytes.position(0)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, input)
                GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, width, height,
                    GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, bytes)
                GlUtil.focusFramebufferUsingCurrentContext(fbo, expectedWidth, expectedHeight)
                // Poison the destination so an undrawn/stale frame cannot pass a gray check.
                GLES20.glClearColor(0f, 0f, 1f, 1f)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                program.drawFrame(input, frameNumber++ * 33_333L)
                val returnedFbo = IntArray(1)
                val returnedViewport = IntArray(4)
                GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, returnedFbo, 0)
                GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, returnedViewport, 0)
                check(returnedFbo[0] == fbo && returnedViewport.contentEquals(
                    intArrayOf(0, 0, expectedWidth, expectedHeight),
                )) { "$label $frameLabel did not restore the caller's output framebuffer/viewport" }
                actual.clear()
                GLES20.glReadPixels(0, 0, expectedWidth, expectedHeight,
                    GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, actual)
                GlUtil.checkGlError()
                for (pixel in 0 until expectedWidth * expectedHeight) {
                    var low = 255
                    var high = 0
                    for (channel in 0..2) {
                        val value = actual.get(pixel * 4 + channel).toInt() and 255
                        val error = abs(value - rgb[channel])
                        maxError = maxOf(maxError, error)
                        check(error <= 6) {
                            "$label $frameLabel pixel $pixel channel $channel=$value, expected ${rgb[channel]}"
                        }
                        low = minOf(low, value)
                        high = maxOf(high, value)
                    }
                    if (rgb[0] == rgb[1] && rgb[1] == rgb[2]) {
                        check(high - low <= 2) {
                            "$label $frameLabel pixel $pixel has a colour cast: channel range $low..$high"
                        }
                    }
                    check((actual.get(pixel * 4 + 3).toInt() and 255) == 255) {
                        "$label $frameLabel pixel $pixel is not opaque"
                    }
                }
            }
            log("PASS $label real GLES ${width}x$height to ${expectedWidth}x$expectedHeight, " +
                "${probes.size} consecutive frames, max RGB error $maxError/255")
        } finally {
            if (fbo != 0) runCatching { GlUtil.deleteFbo(fbo) }
            if (output != 0) runCatching { GlUtil.deleteTexture(output) }
            if (input != 0) runCatching { GlUtil.deleteTexture(input) }
        }
    }

    fun withProgram(effect: GlEffect, action: (BaseGlShaderProgram) -> Unit) {
        val program = effect.toGlShaderProgram(context, false) as BaseGlShaderProgram
        try {
            action(program)
        } finally {
            program.release()
        }
    }
    try {
        val extensions = GLES20.glGetString(GLES20.GL_EXTENSIONS).orEmpty()
        val version = GLES20.glGetString(GLES20.GL_VERSION).orEmpty()
        val hasFp16 = "GL_EXT_color_buffer_half_float" in extensions ||
            (version.contains("OpenGL ES 3") && "GL_EXT_color_buffer_float" in extensions)
        if (!media3FragmentHighp() || !hasFp16) {
            log("SKIP Anime4K GL: no fragment highp or renderable FP16")
            return
        }
        for (key in MEDIA3_ANIME4K_SCALERS) {
            withProgram(Media3Anime4kEffect(key, 32, 24)) { program ->
                renderFrames(program, "$key 2x", 16, 12, 32, 24)
                // Reallocate feature maps and enter the final fitting pass on the same program.
                renderFrames(program, "$key reconfigured", 20, 15, 32, 24,
                    frames.filter { it.first in setOf("gray", "red", "blue", "gray again") })
                // Drop the fit texture again; the original output must still be rendered.
                renderFrames(program, "$key restored 2x", 16, 12, 32, 24, frames.takeLast(1))
            }
            // A fresh effect exercises initial activation with a non-2x panel fit.
            withProgram(Media3Anime4kEffect(key, 24, 18)) { program ->
                renderFrames(program, "$key recreated 1.5x", 16, 12, 24, 18)
            }
        }
    } finally {
        EGL14.eglMakeCurrent(egl, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(egl, surface)
        EGL14.eglDestroyContext(egl, glContext)
        EGL14.eglTerminate(egl)
        EGL14.eglReleaseThread()
    }
}
