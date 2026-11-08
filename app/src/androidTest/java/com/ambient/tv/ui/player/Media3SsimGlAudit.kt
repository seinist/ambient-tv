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
 * Real-device offscreen GLES smoke test. Runs production SSim multi-pass programs on
 * consecutive neutral/colored frames, noninteger ratios and resize/reconfigure cycles.
 * Full-frame checks catch phase-dependent cubic-weight errors that exact 2x and center-only
 * samples miss. Repeated edge frames also detect stale intermediate textures.
 * Android/JVM unit tests cannot guarantee shader compilation on the target GPU.
 */
internal fun auditMedia3SsimShaders(context: Context, log: (String) -> Unit) {
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
    fun uploadFrame(texture: Int, width: Int, height: Int, rgb: IntArray?) {
        val bytes = ByteBuffer.allocateDirect(width * height * 4)
        for (y in 0 until height) for (x in 0 until width) {
            val color = rgb ?: when {
                x < width / 3 && y in height / 3 until height * 2 / 3 -> intArrayOf(24, 200, 40)
                x < width / 2 -> intArrayOf(210, 40, 24)
                else -> intArrayOf(24, 40, 210)
            }
            color.forEach { bytes.put(it.toByte()) }
            bytes.put(255.toByte())
        }
        bytes.position(0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, width, height,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, bytes)
        GlUtil.checkGlError()
    }

    fun execute(label: String, effect: GlEffect, sizes: List<IntArray>) {
        val program = effect.toGlShaderProgram(context, false) as BaseGlShaderProgram
        val samples = listOf(
            "dark gray" to intArrayOf(32, 32, 32),
            "gray" to intArrayOf(128, 128, 128),
            "red" to intArrayOf(210, 40, 24),
            "green" to intArrayOf(24, 200, 40),
            "blue" to intArrayOf(24, 40, 210),
            "edges" to null,
            "gray again" to intArrayOf(128, 128, 128),
            "edges again" to null,
        )
        try {
            for ((width, height, expectedWidth, expectedHeight) in sizes) {
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
                    var firstEdges: ByteArray? = null
                    samples.forEachIndexed { frame, (name, rgb) ->
                        uploadFrame(input, width, height, rgb)
                        GlUtil.focusFramebufferUsingCurrentContext(fbo, expectedWidth, expectedHeight)
                        program.drawFrame(input, frame * 33_333L)
                        val actual = ByteBuffer.allocateDirect(expectedWidth * expectedHeight * 4)
                        GLES20.glReadPixels(0, 0, expectedWidth, expectedHeight,
                            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, actual)
                        GlUtil.checkGlError()
                        val pixels = ByteArray(actual.capacity())
                        actual.position(0)
                        actual.get(pixels)
                        for (pixel in 0 until expectedWidth * expectedHeight) {
                            check((pixels[pixel * 4 + 3].toInt() and 255) == 255) { "$label $name alpha" }
                            if (rgb != null) {
                                for (channel in 0..2) {
                                    val value = pixels[pixel * 4 + channel].toInt() and 255
                                    check(abs(value - rgb[channel]) <= 6) {
                                        "$label ${width}x$height $name pixel $pixel channel $channel=$value, expected ${rgb[channel]}"
                                    }
                                }
                                if (rgb[0] == rgb[1] && rgb[1] == rgb[2]) {
                                    val values = (0..2).map { pixels[pixel * 4 + it].toInt() and 255 }
                                    check(values.max() - values.min() <= 2) { "$label $name gained a tint" }
                                }
                            }
                        }
                        if (rgb == null) {
                            firstEdges?.let { check(it.contentEquals(pixels)) { "$label stale edge-frame data" } }
                                ?: run { firstEdges = pixels }
                        }
                    }
                    log("PASS $label ${width}x$height to ${expectedWidth}x$expectedHeight, ${samples.size} consecutive frames")
                } finally {
                    if (fbo != 0) GlUtil.deleteFbo(fbo)
                    if (output != 0) GlUtil.deleteTexture(output)
                    if (input != 0) GlUtil.deleteTexture(input)
                }
            }
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
            log("SKIP SSim GL: no fragment highp or renderable FP16")
            return
        }
        execute("SSimDown", Media3SsimDownEffect(16, 12), listOf(
            intArrayOf(32, 24, 16, 12), intArrayOf(29, 21, 16, 11), intArrayOf(32, 24, 16, 12),
        ))
        val superSizes = listOf(
            intArrayOf(16, 12, 32, 24), intArrayOf(19, 13, 32, 22), intArrayOf(16, 12, 32, 24),
        )
        execute("SSimSuper Cubic", Media3SsimSuperEffect(
            Media3CubicScaleEffect(32, 24, b = 0f, c = 0.5f)), superSizes)
        execute("SSimSuper Lanczos3", Media3SsimSuperEffect(
            Media3KernelEffect("ssim_lanczos3", 32, 24, sigmoid = false)), superSizes)
    } finally {
        EGL14.eglMakeCurrent(egl, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(egl, surface)
        EGL14.eglDestroyContext(egl, glContext)
        EGL14.eglTerminate(egl)
        EGL14.eglReleaseThread()
    }
}
